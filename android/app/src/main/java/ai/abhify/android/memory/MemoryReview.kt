package ai.abhify.android.memory

import ai.abhify.android.chat.MemoryItem

/**
 * Advanced personal memory (v3.8): possible contradictions, a review queue, provenance labels, context-aware ranking
 * and a dashboard. Everything here only SUGGESTS: nothing is merged, replaced or deleted without the user's choice.
 */
object MemoryReview {
    private const val DAY = 86_400_000L

    // ------------------------------------------------------------------ contradictions
    /** "Preferred framework: Flask" → key "preferred framework", value "flask". [against] = the "over X" part of "prefers A over B". */
    data class Slot(val key: String, val value: String, val against: String? = null)

    private fun norm(v: String) = v.lowercase().trim().trim('.', ',', ';', '!', '"', '\'').removePrefix("a ").removePrefix("an ").removePrefix("the ").trim()
    private val LEAD = Regex("^(?:the user(?:'s)?|user(?:'s)?|i am|i'm|i|my|he|she|they)\\s+", RegexOption.IGNORE_CASE)
    private val PATTERNS: List<Pair<Regex, (MatchResult) -> Slot>> = listOf(
        Regex("^(preferred|favou?rite|main|primary|current|target|dream|native) ([a-z][a-z ]{1,30}?)\\s*(?:is|:|=|-)\\s*(.+)$", RegexOption.IGNORE_CASE) to
            { m -> Slot("${m.groupValues[1].lowercase().replace("favourite", "favorite")} ${m.groupValues[2].lowercase().trim()}", norm(m.groupValues[3])) },
        Regex("^(?:lives|live|living|is based|based) in (.+)$", RegexOption.IGNORE_CASE) to { m -> Slot("lives in", norm(m.groupValues[1])) },
        Regex("^(?:works|work|working) (?:at|for) (.+)$", RegexOption.IGNORE_CASE) to { m -> Slot("works at", norm(m.groupValues[1])) },
        Regex("^(?:is |am )?(?:works|work|working) as (.+)$", RegexOption.IGNORE_CASE) to { m -> Slot("works as", norm(m.groupValues[1])) },
        Regex("^name is (.+)$", RegexOption.IGNORE_CASE) to { m -> Slot("name", norm(m.groupValues[1])) },
        Regex("^uses (.+?) for (.+)$", RegexOption.IGNORE_CASE) to { m -> Slot("uses for ${norm(m.groupValues[2])}", norm(m.groupValues[1])) },
        Regex("^(?:prefers|prefer|likes|like) (.+?)(?: (?:over|to|rather than|instead of) (.+))?$", RegexOption.IGNORE_CASE) to
            { m -> Slot("prefers", norm(m.groupValues[1]), m.groupValues[2].takeIf { it.isNotBlank() }?.let(::norm)) },
    )

    fun slot(content: String): Slot? {
        val c = content.trim().trimEnd('.').replace(LEAD, "").trim()
        for ((re, f) in PATTERNS) re.matchEntire(c)?.let { return f(it) }
        return null
    }

    /**
     * Two memories that may contradict each other: the same slot with different values ("lives in Pune" / "lives in
     * Bengaluru"). Bare preferences only count when they are opposite ("A over B" vs "B over A") or the values are
     * [related] alternatives (e.g. Flask and FastAPI in the skill taxonomy) — "prefers dark mode" never conflicts with "prefers PyTorch".
     */
    fun contradicts(a: String, b: String, related: (String, String) -> Boolean = { _, _ -> false }): Boolean {
        val x = slot(a) ?: return false; val y = slot(b) ?: return false
        if (x.key != y.key || x.value == y.value || MemoryKit.similar(x.value, y.value)) return false
        if (x.key != "prefers") return true
        return x.value == y.against || y.value == x.against || related(x.value, y.value)
    }
    fun conflictWith(content: String, items: List<MemoryItem>, related: (String, String) -> Boolean = { _, _ -> false }): MemoryItem? =
        items.firstOrNull { contradicts(content, it.content, related) }

    // ------------------------------------------------------------------ review queue
    enum class Kind(val label: String) {
        CONFLICT("Possible contradiction"), DUPLICATE("Duplicates"), COMBINE("Could be combined"), EXPIRED("Expired"), EXPIRING("Expires soon"), STALE("Not used for a long time")
    }
    data class Item(val kind: Kind, val memories: List<MemoryItem>, val reason: String) { val key get() = pairKey(kind, memories) }

    /** Stable key for a decision about these memories ("keep both" is remembered and not suggested again). */
    fun pairKey(kind: Kind, ms: List<MemoryItem>) = kind.name + ":" + ms.map { it.uid }.sorted().joinToString("|")

    /**
     * What needs the user's attention. Stale = low importance, not given to the AI, edited or reviewed for [staleDays];
     * Expiring = within [soonDays]. Combine = same category and 40–70% shared words (below the duplicate threshold).
     */
    fun review(items: List<MemoryItem>, now: Long = System.currentTimeMillis(), dismissed: Set<String> = emptySet(),
               related: (String, String) -> Boolean = { _, _ -> false }, staleDays: Int = 180, soonDays: Int = 14): List<Item> {
        val out = mutableListOf<Item>()
        val sorted = items.sortedByDescending { it.updatedAt }
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]; val b = sorted[j]
            when {
                contradicts(a.content, b.content, related) -> out += Item(Kind.CONFLICT, listOf(a, b), "Same topic (${slot(a.content)!!.key}), different answers.")
                MemoryKit.similar(a.content, b.content) -> out += Item(Kind.DUPLICATE, listOf(a, b), "Almost the same text.")
                a.category == b.category && overlap(a.content, b.content) in 0.4..0.7 -> out += Item(Kind.COMBINE, listOf(a, b), "Same category and many shared words.")
            }
        }
        for (m in sorted) {
            val touched = maxOf(m.updatedAt, m.lastUsedAt ?: 0, m.reviewedAt ?: 0)
            when {
                m.expired(now) -> out += Item(Kind.EXPIRED, listOf(m), "Expired; it is no longer used in answers.")
                m.expiresAt != null && m.expiresAt - now < soonDays * DAY -> out += Item(Kind.EXPIRING, listOf(m), "Expires within $soonDays days.")
                m.importance <= 1 && now - touched > staleDays * DAY -> out += Item(Kind.STALE, listOf(m), "Low importance and not used for $staleDays+ days.")
            }
        }
        return out.filter { it.key !in dismissed }.sortedBy { it.kind.ordinal }
    }
    private fun overlap(a: String, b: String): Double {
        val ta = MemoryKit.terms(a); val tb = MemoryKit.terms(b)
        return if (ta.isEmpty() || tb.isEmpty()) 0.0 else ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    // ------------------------------------------------------------------ provenance
    /** How a memory was created, from what was recorded. Unknown stays unknown (never guessed). */
    fun provenance(source: String?, chatTitle: (Long) -> String? = { null }, docName: (Long) -> String? = { null }): String = when {
        source == null -> "Source not recorded (added before v3.4)"
        source == "manual" -> "Added by you"
        source == "chat" -> "\"Remember that…\" in a chat (which chat was not recorded)"
        source.startsWith("remember:") -> "\"Remember that…\" in " + (source.substringAfter(':').toLongOrNull()?.let(chatTitle)?.let { "the chat \"$it\"" } ?: "a chat that no longer exists")
        source.startsWith("chat:") -> "Suggested from " + (source.substringAfter(':').toLongOrNull()?.let(chatTitle)?.let { "the chat \"$it\"" } ?: "a chat that no longer exists") + ", approved by you"
        source.startsWith("doc:") -> "Suggested from " + (source.substringAfter(':').toLongOrNull()?.let(docName)?.let { "the document \"$it\"" } ?: "a document that was deleted") + ", approved by you"
        source == "restore" -> "Restored from a backup"
        else -> "Source: $source"
    }
    fun chatIdOf(source: String?): Long? = source?.takeIf { it.startsWith("chat:") || it.startsWith("remember:") }?.substringAfter(':')?.toLongOrNull()

    // ------------------------------------------------------------------ dashboard
    data class Dashboard(val total: Int, val byCategory: List<Pair<String, Int>>, val expiringSoon: Int, val expired: Int, val duplicates: Int, val conflicts: Int,
                         val recentlyUpdated: List<MemoryItem>, val neverUsed: Int)
    fun dashboard(items: List<MemoryItem>, reviews: List<Item>, now: Long = System.currentTimeMillis()) = Dashboard(
        items.size, items.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
        reviews.count { it.kind == Kind.EXPIRING }, items.count { it.expired(now) }, reviews.count { it.kind == Kind.DUPLICATE }, reviews.count { it.kind == Kind.CONFLICT },
        items.sortedByDescending { it.updatedAt }.take(3), items.count { it.useCount == 0 })
}
