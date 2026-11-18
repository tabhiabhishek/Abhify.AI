package ai.abhify.android.docs

/**
 * Knowledge Chat: when chat answers from your own knowledge (all documents in the library plus saved Knowledge items),
 * not only from documents you tick or a project you choose.
 *  - NORMAL: never (the previous behaviour: only ticked documents, a project, or an attached file).
 *  - KNOWLEDGE: every question searches your knowledge.
 *  - AUTO (default): only questions that point at your own material ("my notes", "according to my resume", a document's
 *    name…), so ordinary questions stay ordinary and don't pay for retrieval.
 * Retrieval runs on the phone. Passages go to an online AI only when "Online answers may use my knowledge" is on, and
 * then only after the usual consent question.
 */
object KnowledgeChat {
    enum class Mode(val label: String, val hint: String) {
        NORMAL("Normal", "Answers from the AI's own knowledge (and documents you choose)"),
        AUTO("Auto", "Searches your knowledge when a question is about your own material"),
        KNOWLEDGE("Knowledge", "Every answer searches your documents and saved knowledge"),
    }

    private val INTENT = Regex(
        "\\b(?:my|our)\\s+(?:\\w+\\s+)?(?:documents?|docs|notes?|files?|pdfs?|resume|résumé|cv|projects?|knowledge|library|saved|bookmarks?|reports?|papers?|slides?)\\b" +
            "|\\b(?:according to|based on|from|in)\\s+(?:my|the)\\s+(?:\\w+\\s+)?(?:documents?|notes?|files?|knowledge|library|resume|pdf)\\b" +
            "|\\bwhat (?:did|have) i (?:write|save|note|upload)\\b|\\bknowledge (?:base|center)\\b", RegexOption.IGNORE_CASE)

    /** Does [question] need your knowledge under [mode]? [docNames]: a question naming a document (its title) counts too. */
    fun wants(mode: Mode, question: String, docNames: Collection<String> = emptyList()): Boolean = when (mode) {
        Mode.NORMAL -> false
        Mode.KNOWLEDGE -> true
        Mode.AUTO -> INTENT.containsMatchIn(question) || docNames.any { n ->
            val stem = n.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim()
            stem.length >= 4 && question.contains(stem, ignoreCase = true)
        }
    }

    /** Saved Knowledge items as searchable chunks (negative ids so they never collide with documents). */
    fun itemChunks(items: List<Triple<Long, String, String>>): List<Library.Chunk> =
        items.filter { it.third.isNotBlank() }.map { (id, title, body) -> Library.Chunk(-id, "Knowledge: ${title.take(60)}", 0, null, "$title\n$body".take(4_000)) }

    /** Document passages first, then up to two Knowledge items, at most [k] (their scores aren't comparable: hybrid vs BM25). */
    fun merge(docs: List<Library.Hit>, items: List<Library.Hit>, k: Int): List<Library.Hit> {
        val it = items.take(2)
        return (docs.take(k - it.size) + it).distinctBy { h -> h.chunk.docId to h.chunk.index }
    }

    const val NOTHING_FOUND = "📚 Nothing in your knowledge matched this question"
}
