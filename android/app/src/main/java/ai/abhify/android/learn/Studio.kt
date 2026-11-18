package ai.abhify.android.learn

/**
 * Prompts and strict parsers for the Documents, Learn and Work AI actions. The parsers accept only well-formed
 * output (small local models sometimes ramble); anything unusable is reported, never silently "fixed".
 */
object Studio {
    enum class Level(val label: String, val hint: String) {
        BEGINNER("Beginner", "Assume no background. Use plain words, an everyday analogy and one tiny example."),
        INTERMEDIATE("Intermediate", "Assume the basics are known. Be precise, show one worked example and a common pitfall."),
        ADVANCED("Advanced", "Be rigorous and concise: the key math or mechanism, trade-offs, and where it breaks."),
    }

    // ------------------------------------------------------------------ prompts
    const val SYSTEM = "You are Abhify.AI, a careful assistant for learning and work. Be accurate; if unsure, say so. Use Markdown."

    fun tutor(topic: String, level: Level) =
        "Teach me \"$topic\". ${level.hint} End with one practice question for me (don't answer it)."

    fun evaluate(question: String, answer: String) =
        "Practice question: $question\n\nMy answer: $answer\n\nEvaluate my answer: say what is correct, what is wrong or missing, " +
            "give the model answer, and end with one follow-up question."

    fun plan(topic: String, level: Level, goal: String, targetDate: String, minutes: Int, days: Long? = null, material: String? = null) =
        "Make a study plan for \"$topic\" for a ${level.label.lowercase()} learner. Goal: ${goal.ifBlank { "solid understanding" }}. " +
            "Target date: ${targetDate.ifBlank { "about 4 weeks" }}" + (days?.let { " ($it days from today)" } ?: "") + ". Time per day: $minutes minutes. " +
            "The plan must fit that time: no more lessons than can be studied in it.\n" +
            (material?.let { "Base the lessons on these study materials (titles and openings):\n$it\n" } ?: "") +
            "Write ONLY a numbered list of 6 to 20 lessons, one per line, like:\n1. Day 1 – Topic: what to study and practise\n" +
            "Include revision lessons. No introduction and no closing text."

    fun cards(topic: String, n: Int = 10) =
        "Write $n flashcards about: $topic\nUse exactly this format, one card per pair of lines, nothing else:\nQ: question\nA: short answer"

    fun cardsFromText(n: Int = 10) =
        "Write $n flashcards that test the key facts in the text below. Use exactly this format, nothing else:\nQ: question\nA: short answer"

    fun mcqFromText(n: Int = 5) =
        "Write $n multiple-choice questions that test the text below. For each, use exactly this format:\n" +
            "Q: question\nA) option\nB) option\nC) option\nD) option\nAnswer: letter\nWhy: one sentence\n(blank line between questions, nothing else)"

    const val SUMMARY = "Summarize the text below in 5–8 bullet points, then one line starting \"In short:\". Only use what the text says."
    const val KEY_POINTS = "List the key points, definitions and numbers in the text below as bullet points. Only use what the text says."
    const val ACTION_ITEMS = "Extract every action item from the text below. Write ONLY a list, one per line, like:\n- [ ] Owner (if named): what to do (due date if stated)\n" +
        "If there are none, write exactly: No action items."
    const val NOTES = "Turn the text below into clear study notes with headings, bullet points and a short glossary. Only use what the text says."
    const val MEETING = "These are raw meeting notes. Write: ## Summary (3–5 bullets), ## Decisions, ## Action items (\"- [ ] owner: task (due)\"), ## Open questions. " +
        "Only use what the notes say."

    fun compare(a: String, b: String) = "Compare the two documents below (\"$a\" and \"$b\"): what they agree on, where they differ, and what each has that the other lacks. Use headings."
    fun email(purpose: String, tone: String) = "Draft an email. Purpose: $purpose. Tone: $tone. Give a subject line, then the body. Keep it concise. Do not invent facts; use [placeholders] for unknown names or dates."
    fun report(title: String) = "Write a structured report titled \"$title\" from the material below: Summary, Background, Findings, Recommendations, Next steps. Only use the material; mark gaps as [needs input]."
    fun sop(question: String) = "Answer this question about our procedures using only the documents: $question"
    fun summarizeChat() = "Summarize this conversation in 3–6 bullet points: the questions asked and the main answers. No introduction."

    // ------------------------------------------------------------------ parsers
    /** "Q: … / A: …" pairs. Lines that are not part of a complete pair are ignored; empty result = unusable output. */
    fun parseCards(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); var q: String? = null
        for (raw in text.lines()) {
            val l = raw.trim().removePrefix("**").replace("**", "")
            when {
                Regex("^(Q|Question)\\s*\\d*\\s*[:.)]\\s*", RegexOption.IGNORE_CASE).containsMatchIn(l) -> q = l.replace(Regex("^(Q|Question)\\s*\\d*\\s*[:.)]\\s*", RegexOption.IGNORE_CASE), "").trim()
                Regex("^(A|Answer)\\s*[:.)]\\s*", RegexOption.IGNORE_CASE).containsMatchIn(l) && q != null -> {
                    val a = l.replace(Regex("^(A|Answer)\\s*[:.)]\\s*", RegexOption.IGNORE_CASE), "").trim()
                    if (q.isNotBlank() && a.isNotBlank()) out += q to a
                    q = null
                }
            }
        }
        return out.distinctBy { it.first.lowercase() }
    }

    /** Numbered or bulleted lines (study-plan lessons, list answers). */
    fun parseList(text: String): List<String> = text.lines().map { it.trim() }
        .mapNotNull { l -> Regex("""^(?:\d+[.)]|[-*•])\s+(?:\[[ xX]]\s+)?(.+)$""").find(l)?.groupValues?.get(1)?.trim()?.removeSurrounding("**") }
        .filter { it.length > 2 }

    /** Action items ("- [ ] …"); "No action items." → empty. */
    fun parseActions(text: String): List<String> = if (text.contains("No action items", ignoreCase = true) && parseList(text).isEmpty()) emptyList() else parseList(text)

    data class Mcq(val question: String, val options: List<String>, val correct: Int, val why: String)

    /** Multiple-choice blocks with exactly four options and a valid answer letter; anything else is dropped. */
    fun parseMcqs(text: String): List<Mcq> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.lines().map { it.trim().replace("**", "") }.filter { it.isNotEmpty() }
        val q = lines.firstOrNull { it.matches(Regex("^(Q|Question)\\s*\\d*\\s*[:.)].+", RegexOption.IGNORE_CASE)) }
            ?.replace(Regex("^(Q|Question)\\s*\\d*\\s*[:.)]\\s*", RegexOption.IGNORE_CASE), "") ?: return@mapNotNull null
        val opts = listOf("A", "B", "C", "D").map { k -> lines.firstOrNull { it.matches(Regex("^$k[).:]\\s*.+")) }?.substring(2)?.trim() ?: return@mapNotNull null }
        val ans = lines.firstOrNull { it.startsWith("Answer", true) }?.substringAfter(':')?.trim()?.firstOrNull()?.uppercaseChar() ?: return@mapNotNull null
        val idx = "ABCD".indexOf(ans).takeIf { it >= 0 } ?: return@mapNotNull null
        if (opts.map { it.lowercase() }.distinct().size < 4) return@mapNotNull null
        Mcq(q, opts, idx, lines.firstOrNull { it.startsWith("Why", true) }?.substringAfter(':')?.trim().orEmpty())
    }
}
