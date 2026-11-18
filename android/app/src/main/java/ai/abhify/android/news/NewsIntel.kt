package ai.abhify.android.news

/**
 * Next build — Personal News Intelligence over stories already fetched (no new requests): related stories, groups of
 * the same story from different publishers (for source comparison), and a topic for Study → Discover. Grouping uses
 * shared headline words only; differing coverage is shown side by side, never labelled as a contradiction.
 */
object NewsIntel {
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by", "from", "is", "are", "was", "be",
        "as", "after", "over", "new", "says", "say", "its", "it", "this", "that", "will", "has", "have", "how", "why", "what", "who",
        "के", "की", "का", "में", "और", "से", "को", "पर", "है", "हैं", "ने", "एक", "भी", "लिए")
    fun terms(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    fun similarity(a: String, b: String): Double {
        val x = terms(a); val y = terms(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }

    /** Other stories sharing enough headline words with [a] (most similar first, excluding the same publisher's duplicate). */
    fun related(a: NewsArticle, all: List<NewsArticle>, limit: Int = 4): List<NewsArticle> =
        all.filter { it.id != a.id }.map { it to similarity(a.title, it.title) }.filter { it.second >= 0.4 }
            .sortedByDescending { it.second }.map { it.first }.distinctBy { it.publisher to it.title }.take(limit)

    /** Groups of the same story covered by ≥ [minPublishers] different publishers (for "Compare sources"). */
    fun clusters(all: List<NewsArticle>, minPublishers: Int = 2): List<List<NewsArticle>> {
        val left = all.toMutableList(); val out = mutableListOf<List<NewsArticle>>()
        while (left.isNotEmpty()) {
            val head = left.removeAt(0)
            val same = left.filter { similarity(head.title, it.title) >= 0.5 }
            left.removeAll(same)
            val group = (listOf(head) + same).distinctBy { it.publisher }
            if (group.size >= minPublishers) out += group
        }
        return out
    }

    /** A short topic from a headline for Study → Discover (the most specific words, original casing). */
    fun learnTopic(title: String): String {
        val words = Regex("[\\p{L}\\p{N}+#.-]{3,}").findAll(title).map { it.value }.filter { it.lowercase() !in STOP }.toList()
        val caps = words.filter { it.first().isUpperCase() && it.lowercase() != it }
        return (caps.ifEmpty { words }).take(3).joinToString(" ").take(60)
    }

    /** The comparison prompt (AI, optional, labelled): shared facts vs differences, from headlines/descriptions only. */
    fun comparePrompt(group: List<NewsArticle>) = "These are reports of the same story from different publishers. List (1) the facts they share, " +
        "(2) details only some of them mention, and (3) differences in framing or emphasis. Don't call a difference a contradiction unless they " +
        "state incompatible facts, and don't add anything that isn't in the text. Only headlines and short descriptions are available.\n\n" +
        "<<<ARTICLES\n${NewsAi.articleBlock(group, group.size)}\nARTICLES>>>"
}
