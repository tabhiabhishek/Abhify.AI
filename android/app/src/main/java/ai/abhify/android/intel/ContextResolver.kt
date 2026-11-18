package ai.abhify.android.intel

/**
 * Next build — Personal AI Brain, step 1: the shared context resolver. Given a request and candidate records from the
 * stores (projects, tasks, notes, memories, documents, saved knowledge/news, study, AI task history), it keeps only the
 * permitted, relevant ones, ranks them by shared terms + recency, labels how fresh each one is, and says which sources
 * had nothing. It never invents a record and never reads a store itself (callers pass records; tests pass fixtures).
 */
object ContextResolver {
    enum class Source(val label: String) {
        PROJECT("Project"), TASK("To-do"), NOTE("Note"), MEMORY("Memory"), DOCUMENT("Document"), KNOWLEDGE("Saved knowledge"),
        NEWS("Saved news"), STUDY("Study"), AI_TASK("AI task"), CONVERSATION("Conversation"),
    }
    data class Record(val source: Source, val id: String, val title: String, val text: String, val updatedAt: Long)
    enum class Freshness(val label: String) { RECENT("recent"), THIS_MONTH("this month"), OLDER("older — check it's still current"), UNKNOWN("date unknown") }
    data class Item(val record: Record, val score: Double, val freshness: Freshness)
    data class Result(val items: List<Item>, val empty: List<Source>)

    private const val DAY = 86_400_000L
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "i", "me", "my", "you", "your", "what", "how", "why", "it",
        "for", "on", "with", "this", "that", "be", "do", "does", "can", "should", "about", "please", "we", "our", "today", "now")
    fun terms(s: String) = Regex("[\\p{L}\\p{N}+#]{2,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    fun freshness(updatedAt: Long, now: Long): Freshness = when {
        updatedAt <= 0 -> Freshness.UNKNOWN
        now - updatedAt < 7 * DAY -> Freshness.RECENT
        now - updatedAt < 30 * DAY -> Freshness.THIS_MONTH
        else -> Freshness.OLDER
    }

    /**
     * [permitted]: the sources the user allows here (e.g. no MEMORY when personal memory is off). An empty request keeps
     * the most recent records (useful for "brief me"); otherwise a record must share at least one term with the request.
     */
    fun resolve(request: String, records: List<Record>, permitted: Set<Source>, now: Long = System.currentTimeMillis(), limit: Int = 8, minShared: Int = 1): Result {
        val q = terms(request)
        val allowed = records.filter { it.source in permitted }
        val scored = allowed.mapNotNull { r ->
            val words = terms(r.title + " " + r.text)
            val shared = q.count { it in words }
            val titleHit = q.count { it in terms(r.title) }
            if (q.isNotEmpty() && shared < minShared.coerceIn(1, q.size)) return@mapNotNull null
            val age = if (r.updatedAt > 0) (now - r.updatedAt).coerceAtLeast(0) / DAY.toDouble() else 365.0
            val recency = 1.0 / (1.0 + age / 14.0)                      // ~1 today, 0.5 after two weeks
            Item(r, shared * 2.0 + titleHit + recency, freshness(r.updatedAt, now))
        }.sortedByDescending { it.score }
        val picked = scored.take(limit)
        return Result(picked, permitted.filter { s -> allowed.none { it.source == s } }.sortedBy { it.ordinal })
    }

    /** 5.6 relevance floor for chat: questions with 3+ meaningful words need 2 shared terms (no padding with loose matches). */
    fun chatMinShared(request: String) = if (terms(request).size >= 3) 2 else 1

    /** 5.6: a question about the user's own records in plain chat ("my project", "my notes", "what did I decide …"). */
    val OWN_MATERIAL = Regex("(?i)\\b(my|our) (projects?|notes?|to-?dos?|tasks?|plans?|records?|decisions?|issues?|blockers?|saved (news|knowledge|articles?))\\b|\\bwhat did (i|we) (decide|note|save|plan)\\b")

    /** A compact, cited context block for a prompt: each item with its source, title, date label and an excerpt. */
    fun block(items: List<Item>, maxChars: Int = 1_800): String = buildString {
        for ((i, it) in items.withIndex()) {
            val line = "[C${i + 1}] ${it.record.source.label}: ${it.record.title} (${it.freshness.label})\n${it.record.text.take(300)}\n"
            if (length + line.length > maxChars) break
            append(line)
        }
    }.trim()
}
