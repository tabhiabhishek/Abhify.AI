package ai.abhify.android.ui

import ai.abhify.android.chat.addMemoryItem
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.chunkCounts
import ai.abhify.android.chat.clearVectors
import ai.abhify.android.chat.deleteVectors
import ai.abhify.android.chat.docMeta
import ai.abhify.android.chat.vault
import ai.abhify.android.chat.vectorCounts
import ai.abhify.android.chat.memoryItems
import ai.abhify.android.chat.updateMemoryItem
import ai.abhify.android.docs.RagIndex
import ai.abhify.android.memory.KnowledgeUpdateStore
import ai.abhify.android.memory.KnowledgeUpdates
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Knowledge Center: what Abhify knows (documents, saved Knowledge items, approved memories, saved news), how it is
 * indexed (chunks, semantic vectors), the semantic-search model, and the controlled-learning queue (corrections waiting
 * for approval, and the history of approved changes, revertible). Existing data and screens are reused; nothing here
 * changes the database schema.
 */
class KnowledgeCenterState(private val vm: AppViewModel) {
    private val g = vm.g
    data class DocStatus(val id: Long, val name: String, val chunks: Int, val vectors: Int, val indexedAt: Long?)
    data class Overview(val docs: List<DocStatus>, val items: Int, val news: Int, val memories: Int)
    val overview = mutableStateOf<Overview?>(null)
    val message = mutableStateOf<String?>(null)

    fun refresh() = vm.viewModelScope.launch {
        overview.value = withContext(Dispatchers.IO) {
            val vec = g.db.vectorCounts(ai.abhify.android.docs.EmbeddingManager.ID); val chunks = g.db.chunkCounts()
            val vault = g.db.vault("", null, false, "recent")
            Overview(g.db.allDocs().map { d -> DocStatus(d.id, d.name, chunks[d.id] ?: 0, vec[d.id] ?: 0, g.db.docMeta(d.id).third) },
                vault.count { it.kind != "news" && it.kind != "document" }, vault.count { it.kind == "news" }, g.db.memoryItems().size)
        }
    }

    // ------------------------------------------------------------------ semantic model
    val downloading = mutableStateOf<Pair<Long, Long>?>(null)
    @Volatile private var stopDownload = false
    fun download() {
        if (downloading.value != null) return
        stopDownload = false; downloading.value = 0L to g.embeddings.model.sizeBytes
        vm.viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { g.embeddings.download({ stopDownload }) { done, total -> downloading.value = done to total } }
            downloading.value = null
            message.value = err ?: "Semantic search is ready. Build the index below (or it fills in as you ask questions)."
            refresh()
        }
    }
    fun pauseDownload() { stopDownload = true }
    fun removeModel() { g.embeddings.remove(); message.value = "Semantic search model removed. Answers use keyword search; the stored vectors are kept for a re-download."; refresh() }

    // ------------------------------------------------------------------ index build (incremental: only new or changed chunks)
    val indexing = mutableStateOf<Pair<Int, Int>?>(null)          // documents done / total
    private var job: Job? = null
    @Volatile private var stopIndex = false
    fun buildIndex(only: Long? = null) {
        if (job?.isActive == true) return
        stopIndex = false
        job = vm.viewModelScope.launch {
            val docs = withContext(Dispatchers.IO) { g.db.allDocs().map { it.id } }.filter { only == null || it == only }
            indexing.value = 0 to docs.size
            val t0 = System.currentTimeMillis(); var reason: String? = null
            withContext(Dispatchers.IO) {
                docs.forEachIndexed { i, id ->
                    if (stopIndex) return@withContext
                    if (only != null) { RagIndex.index(g.db, id); g.db.deleteVectors(id) }     // re-split one document on request
                    else if (g.db.docMeta(id).third == null) RagIndex.index(g.db, id)
                    val e = g.embedder()
                    if (e == null && g.embeddings.installed() && g.semanticEnabled) { reason = "paused: low memory, battery saver or heat"; return@withContext }
                    if (e != null) RagIndex.vectors(g.db, listOf(id), emptyMap(), e, isStopped = { stopIndex })
                    indexing.value = i + 1 to docs.size
                }
            }
            android.util.Log.i("AbhifyRag", "index build: ${indexing.value} documents in ${System.currentTimeMillis() - t0} ms")
            message.value = when { reason != null -> "Index build ${reason!!}. It continues where it stopped next time."
                stopIndex -> "Index build paused. Tap Build index to continue where it stopped."
                else -> "Index up to date (${docs.size} documents, ${(System.currentTimeMillis() - t0) / 1000} s)." }
            indexing.value = null; g.embeddings.unload()                                 // frees ~120 MB; it reloads on the next question
            refresh()
        }
    }
    fun pauseIndex() { stopIndex = true }
    /** Deletes the stored semantic vectors only (documents, text and chunks stay). */
    fun clearVectors() = vm.viewModelScope.launch {
        val n = withContext(Dispatchers.IO) { g.db.clearVectors() }
        message.value = "Removed $n stored vectors. Search uses keywords until the index is built again."; refresh()
    }

    // ------------------------------------------------------------------ controlled learning
    private val store = KnowledgeUpdateStore(File(g.context.filesDir, "knowledge_updates.json"))
    val updates = mutableStateListOf<KnowledgeUpdates.Update>().apply { addAll(store.load()) }
    private fun save() = store.save(updates.toList())

    /** A correction typed for an answer: becomes a pending candidate (nothing is stored as knowledge yet). */
    fun propose(text: String, question: String, source: String) = vm.viewModelScope.launch {
        val items = withContext(Dispatchers.IO) { g.db.memoryItems() }
        val u = KnowledgeUpdates.candidate(text, question, source, items) ?: run { message.value = "Write the correct information first."; return@launch }
        updates.add(u); save()
        message.value = "New correction to review below."
    }

    fun approve(u: KnowledgeUpdates.Update) = vm.viewModelScope.launch {
        if (u.status != KnowledgeUpdates.Status.PENDING) return@launch
        val now = System.currentTimeMillis()
        val done = withContext(Dispatchers.IO) {
            when (u.kind) {
                KnowledgeUpdates.Kind.DUPLICATE -> u.copy(status = KnowledgeUpdates.Status.APPROVED, decidedAt = now, memoryId = u.relatedId)
                KnowledgeUpdates.Kind.UPDATES_EXISTING -> {
                    val m = g.db.memoryItems().firstOrNull { it.id == u.relatedId }
                    if (m == null) u.copy(status = KnowledgeUpdates.Status.APPROVED, decidedAt = now, memoryId = g.db.addMemoryItem(u.text, "Corrections", 2, "correction"))
                    else { g.db.updateMemoryItem(m.id, u.text, m.category, m.importance, m.expiresAt)
                        u.copy(status = KnowledgeUpdates.Status.APPROVED, decidedAt = now, memoryId = m.id, previous = m.content) }
                }
                KnowledgeUpdates.Kind.NEW -> u.copy(status = KnowledgeUpdates.Status.APPROVED, decidedAt = now, memoryId = g.db.addMemoryItem(u.text, "Corrections", 2, "correction"))
            }
        }
        replace(done); vm.memory.load(); g.localChanged()
        message.value = "Approved. Abhify uses it in future answers where it's relevant."
    }
    fun reject(u: KnowledgeUpdates.Update) { replace(u.copy(status = KnowledgeUpdates.Status.REJECTED, decidedAt = System.currentTimeMillis())) }

    /** Undo an approved change: an updated memory gets its old text back; a new one is deleted. */
    fun revert(u: KnowledgeUpdates.Update) = vm.viewModelScope.launch {
        if (u.status != KnowledgeUpdates.Status.APPROVED || u.kind == KnowledgeUpdates.Kind.DUPLICATE) return@launch
        withContext(Dispatchers.IO) {
            val m = g.db.memoryItems().firstOrNull { it.id == u.memoryId } ?: return@withContext
            if (u.previous != null) g.db.updateMemoryItem(m.id, u.previous, m.category, m.importance, m.expiresAt) else g.db.deleteMemory(m.id)
        }
        replace(u.copy(status = KnowledgeUpdates.Status.REVERTED, decidedAt = System.currentTimeMillis())); vm.memory.load(); g.localChanged()
        message.value = "Reverted."
    }
    /** 5.4: clears the corrections log (pending and history). Approved memories themselves are handled by memory clearing. */
    fun clearHistory() { updates.clear(); save() }
    private fun replace(u: KnowledgeUpdates.Update) { val i = updates.indexOfFirst { it.id == u.id }; if (i >= 0) updates[i] = u; save() }
}
