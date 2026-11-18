package ai.abhify.android.ui

import ai.abhify.android.backup.Storage
import ai.abhify.android.chat.vectorStats
import ai.abhify.android.docs.KnowledgeChat
import ai.abhify.android.memory.KnowledgeUpdates
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 5.0 Knowledge Center (was "Semantic search" in v3.6; same route, so existing links keep working). One place for:
 * how chat uses your knowledge (mode, online use), what is indexed, the optional on-phone semantic model (download only
 * when you tap it), index build with pause, and corrections waiting for your approval (with history and revert).
 */
@Composable
fun SemanticSearchScreen(vm: AppViewModel) {
    val k = vm.knowledge
    val g = vm.g
    var stats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var semanticOn by remember { mutableStateOf(g.semanticEnabled) }
    var installed by remember { mutableStateOf(g.embeddings.installed()) }
    val ov = k.overview.value
    LaunchedEffect(ov) { stats = withContext(Dispatchers.IO) { runCatching { g.db.vectorStats() }.getOrNull() }; installed = g.embeddings.installed() }
    LaunchedEffect(Unit) { k.refresh() }
    Screen(vm, "Knowledge Center") {
        k.message.value?.let { m -> Glass { Text(m, style = MaterialTheme.typography.bodyMedium); TextButton({ k.message.value = null }) { Text("OK") } } }

        // ------------------------------------------------------------ how chat uses your knowledge
        SettingsGroup("Knowledge Chat", accent = Accent.CHAT) {
            SegmentedTabs(KnowledgeChat.Mode.entries.map { it.label }, vm.knowledgeMode.value.ordinal) { vm.setKnowledgeMode(KnowledgeChat.Mode.entries[it]) }
            Muted(vm.knowledgeMode.value.hint + ". Answers cite their sources [S1], [S2]…; if nothing matches, Abhify says so instead of guessing.")
            SwitchRow("Online answers may use my knowledge",
                "Off (default): your documents and saved knowledge are searched on this phone and never sent online — online answers get the question only. " +
                    "On: matching passages may be sent to your online AI, after you agree once per chat.", vm.knowledgeOnline.value) { vm.setKnowledgeOnline(it) }
        }

        // ------------------------------------------------------------ sources
        SettingsGroup("What Abhify knows") {
            if (ov == null) Muted("Counting…") else {
                listOf("Documents" to "${ov.docs.size}", "Saved knowledge items" to "${ov.items}", "Saved news" to "${ov.news}",
                    "Personal memories" to "${ov.memories}${if (vm.memoriesEnabled.value) "" else " (off)"}",
                    "Searchable passages" to "${ov.docs.sumOf { it.chunks }}",
                    "Semantic vectors" to "${ov.docs.sumOf { it.vectors }}" + (stats?.let { " · ${Storage.human(it.second)}" } ?: "")).forEach { (a, b) ->
                    Row { Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(b, style = MaterialTheme.typography.bodyMedium) } }
                val unindexed = ov.docs.count { it.chunks == 0 }
                if (unindexed > 0) Muted("$unindexed document(s) not split yet: they are indexed the first time a question needs them, or with Build index.")
            }
            Muted("Only what you add is used: nothing on the phone is scanned automatically. Projects act as collections — choose a project in chat to ask only its documents.")
        }

        // ------------------------------------------------------------ semantic model
        SettingsGroup("Semantic search (on this phone)", accent = Accent.STUDY) {
            val m = g.embeddings.model
            Text(m.name, fontWeight = FontWeight.SemiBold)
            Muted("Matches by meaning as well as by words (combined with keyword search). ${"%.0f".format(m.sizeBytes / 1e6)} MB download, " +
                "about ${m.estimatedRuntimeMb} MB extra memory while it runs, ${m.license} licence. It pauses itself on low memory, battery saver or heat.")
            val d = k.downloading.value
            when {
                d != null -> {
                    LinearProgressIndicator({ if (d.second > 0) d.first.toFloat() / d.second else 0f }, Modifier.fillMaxWidth())
                    Text("${Storage.human(d.first)} of ${Storage.human(d.second)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ k.pauseDownload() }) { Text("Pause") }
                }
                !installed -> {
                    Button({ k.download() }) { Text("Download (${"%.0f".format(m.sizeBytes / 1e6)} MB)") }
                    Muted("Until then answers use keyword search, which works offline with no download.")
                }
                else -> {
                    SwitchRow("Use semantic search", "Off: keyword search only (the model stays downloaded).", semanticOn) { semanticOn = it; g.semanticEnabled = it; if (!it) g.embeddings.unload() }
                    TextButton({ k.removeModel(); installed = false }) { Text("Remove model") }
                }
            }
        }

        // ------------------------------------------------------------ index
        SettingsGroup("Index") {
            val ix = k.indexing.value
            if (ix != null) {
                LinearProgressIndicator({ if (ix.second > 0) ix.first.toFloat() / ix.second else 0f }, Modifier.fillMaxWidth())
                Text("Indexing: ${ix.first} of ${ix.second} documents", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ k.pauseIndex() }) { Text("Pause") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ k.buildIndex() }) { Text("Build index") }
                    if ((ov?.docs?.sumOf { it.vectors } ?: 0) > 0) OutlinedButton({ k.clearVectors() }) { Text("Clear vectors") }
                }
                Muted("Incremental: only new or changed passages are processed (each passage's checksum is kept). " +
                    if (installed && semanticOn) "Semantic vectors are built too." else "Without the semantic model this only splits documents into passages.")
            }
            ov?.docs?.take(30)?.forEach { doc ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(doc.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(if (doc.chunks == 0) "not indexed" else "${doc.chunks} passages · ${doc.vectors} vectors",
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                    TextButton({ k.buildIndex(doc.id) }, enabled = ix == null) { Text("Reindex") }
                }
            }
        }

        // ------------------------------------------------------------ controlled learning
        val pending = k.updates.filter { it.status == KnowledgeUpdates.Status.PENDING }
        SettingsGroup("Corrections to review (${pending.size})", accent = Accent.CAREER) {
            if (pending.isEmpty()) Muted("When an answer is wrong, tap Correct under it. Your correction waits here: nothing is learned until you approve it.")
            pending.forEach { u ->
                Text(u.text, fontWeight = FontWeight.SemiBold)
                if (u.question.isNotBlank()) Text("For: ${u.question.take(120)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Muted(KnowledgeUpdates.plan(u))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ k.approve(u) }) { Text("Approve") }
                    OutlinedButton({ k.reject(u) }) { Text("Reject") }
                }
                HorizontalDivider()
            }
        }
        val history = k.updates.filter { it.status != KnowledgeUpdates.Status.PENDING }.sortedByDescending { it.decidedAt ?: it.createdAt }.take(20)
        if (history.isNotEmpty()) SettingsGroup("Knowledge history") {
            history.forEach { u ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(u.status.name.lowercase().replaceFirstChar { it.uppercase() } + (u.previous?.let { " · replaced \"${it.take(50)}\"" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                    if (u.status == KnowledgeUpdates.Status.APPROVED && u.kind != KnowledgeUpdates.Kind.DUPLICATE) TextButton({ k.revert(u) }) { Text("Revert") }
                }
            }
        }
    }
}

@Composable private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
