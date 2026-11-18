package ai.abhify.android.memory

import ai.abhify.android.chat.MemoryItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Controlled learning: a correction you give to an answer becomes a *candidate* knowledge update, never knowledge by
 * itself. It is checked for duplicates, conflicts and sensitive content (the existing MemoryKit/MemoryReview rules),
 * waits for your approval, and only then is stored as a personal memory (category "Corrections" unless it updates an
 * existing memory). Every decision is logged with the previous value, source and time, so an approved change can be
 * reverted. AI answers, web or news text are never turned into knowledge automatically, and no model weights change.
 * The log is filesDir/knowledge_updates.json (no database change).
 */
object KnowledgeUpdates {
    enum class Status { PENDING, APPROVED, REJECTED, REVERTED }
    enum class Kind { NEW, DUPLICATE, UPDATES_EXISTING }

    data class Update(
        val id: String, val text: String, val source: String /* e.g. "Correction to an answer in chat 12" */, val question: String,
        val kind: Kind, val relatedId: Long?, val relatedText: String?, val sensitive: List<String>,
        val status: Status = Status.PENDING, val createdAt: Long, val decidedAt: Long? = null,
        val memoryId: Long? = null, /** the value before an approved update replaced it (for revert) */ val previous: String? = null,
    )

    /** A candidate from a correction. [items]: current memories (for duplicate / conflict checks). Null when empty. */
    fun candidate(text: String, question: String, source: String, items: List<MemoryItem>, now: Long = System.currentTimeMillis()): Update? {
        val t = text.trim().take(500); if (t.length < 3) return null
        val dup = MemoryKit.duplicateOf(t, items)
        val conflict = if (dup == null) MemoryReview.conflictWith(t, items) else null
        val related = dup ?: conflict
        return Update(java.util.UUID.randomUUID().toString().take(12), t, source.take(200), question.take(300),
            when { dup != null -> Kind.DUPLICATE; conflict != null -> Kind.UPDATES_EXISTING; else -> Kind.NEW },
            related?.id, related?.content, MemoryKit.sensitive(t), createdAt = now)
    }

    /** What approving will do, in words (shown before the user decides). */
    fun plan(u: Update): String = when (u.kind) {
        Kind.NEW -> "Saves it as a new memory (category Corrections)."
        Kind.DUPLICATE -> "You already have this memory (\"${u.relatedText?.take(80)}\"): approving keeps the existing one and adds nothing."
        Kind.UPDATES_EXISTING -> "Replaces the memory \"${u.relatedText?.take(80)}\" (the old text is kept here, so you can revert)."
    } + if (u.sensitive.isNotEmpty()) " It looks like a ${u.sensitive.joinToString()}: save it only if you're sure." else ""
}

class KnowledgeUpdateStore(private val file: File) {
    fun load(): List<KnowledgeUpdates.Update> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k)
            fun l(k: String) = if (o.isNull(k)) null else o.optLong(k)
            KnowledgeUpdates.Update(o.getString("id"), o.getString("text"), o.getString("source"), o.optString("question"),
                KnowledgeUpdates.Kind.valueOf(o.getString("kind")), l("relatedId"), s("relatedText"),
                o.optJSONArray("sensitive")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList(),
                KnowledgeUpdates.Status.valueOf(o.getString("status")), o.getLong("createdAt"), l("decidedAt"), l("memoryId"), s("previous"))
        }
    }.getOrDefault(emptyList())

    fun save(items: List<KnowledgeUpdates.Update>) = runCatching {
        file.parentFile?.mkdirs()
        val a = JSONArray(items.takeLast(500).map { u -> JSONObject().put("id", u.id).put("text", u.text).put("source", u.source).put("question", u.question)
            .put("kind", u.kind.name).put("relatedId", u.relatedId ?: JSONObject.NULL).put("relatedText", u.relatedText ?: JSONObject.NULL)
            .put("sensitive", JSONArray(u.sensitive)).put("status", u.status.name).put("createdAt", u.createdAt)
            .put("decidedAt", u.decidedAt ?: JSONObject.NULL).put("memoryId", u.memoryId ?: JSONObject.NULL).put("previous", u.previous ?: JSONObject.NULL) })
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
