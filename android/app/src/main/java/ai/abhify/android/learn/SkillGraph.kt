package ai.abhify.android.learn

/**
 * Next build — Personal Skill Graph and adaptive coach. Built only from the learner's own records: quiz and MCQ answers
 * per topic, flashcard decks, study-plan progress, and goals the user set (plans count as goals). A level is shown only
 * with enough evidence (≥ [MIN_ANSWERS] answers); otherwise "not enough data" — never a made-up score. Prerequisites
 * are a small, general "suggested order" (labelled as general guidance), used only to propose what to learn first.
 */
object SkillGraph {
    const val MIN_ANSWERS = 5

    /** [recent]: the latest answers, oldest first (true = correct), when the source keeps them (MCQs do); used for the trend. */
    data class Evidence(val topic: String, val answered: Int, val correct: Int, val source: String, val recent: List<Boolean> = emptyList())
    data class Deck(val topic: String, val due: Int, val missed: Int)
    data class Goal(val topic: String, val done: Int, val total: Int)

    enum class Level(val label: String) { NEEDS_WORK("Needs work"), DEVELOPING("Developing"), STRONG("Strong"), UNKNOWN("Not enough data yet") }
    data class Node(val skill: String, val answered: Int, val correct: Int, val level: Level, val due: Int, val goal: Goal?, val prerequisites: List<String>,
                    val sources: Set<String>, val minAnswers: Int = MIN_ANSWERS, val trend: String? = null) {
        val accuracy get() = if (answered >= minAnswers) correct * 100 / answered else null
    }

    /** 5.5: "improving" / "declining" / "steady" from the last 10 answers (last 5 vs the 5 before), else null (never guessed). */
    fun trend(recent: List<Boolean>): String? {
        if (recent.size < 10) return null
        val last = recent.takeLast(5).count { it }; val before = recent.takeLast(10).take(5).count { it }
        return when { last - before >= 2 -> "improving"; before - last >= 2 -> "declining"; else -> "steady" }
    }
    data class Next(val skill: String, val reason: String, val action: String)

    /** General learning order (not personal data): skill → what usually comes first. */
    val PREREQUISITES = mapOf(
        "machine learning" to listOf("python", "statistics", "linear algebra"),
        "deep learning" to listOf("machine learning", "linear algebra", "calculus"),
        "generative ai" to listOf("deep learning", "python"),
        "nlp" to listOf("machine learning", "python"),
        "data science" to listOf("python", "statistics", "sql"),
        "statistics" to listOf("probability"),
        "react" to listOf("javascript"),
        "django" to listOf("python"),
        "fastapi" to listOf("python"),
        "mlops" to listOf("machine learning", "docker"),
    )

    fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").replace(Regex("^(intro(duction)? to|basics of) "), "")

    fun level(answered: Int, correct: Int, minAnswers: Int = MIN_ANSWERS): Level = when {
        answered < minAnswers -> Level.UNKNOWN
        correct * 100 / answered < 60 -> Level.NEEDS_WORK
        correct * 100 / answered < 80 -> Level.DEVELOPING
        else -> Level.STRONG
    }

    fun build(evidence: List<Evidence>, decks: List<Deck>, goals: List<Goal>, minAnswers: Int = MIN_ANSWERS): List<Node> {
        val names = (evidence.map { norm(it.topic) } + decks.map { norm(it.topic) } + goals.map { norm(it.topic) }).filter { it.isNotBlank() }.distinct()
        return names.map { n ->
            val ev = evidence.filter { norm(it.topic) == n }
            val a = ev.sumOf { it.answered }; val c = ev.sumOf { it.correct }
            Node(n, a, c, level(a, c, minAnswers), decks.filter { norm(it.topic) == n }.sumOf { it.due }, goals.firstOrNull { norm(it.topic) == n },
                PREREQUISITES[n].orEmpty(), ev.map { it.source }.toSet() + (if (decks.any { norm(it.topic) == n }) setOf("flashcards") else emptySet()),
                minAnswers, trend(ev.flatMap { it.recent }))
        }.sortedWith(compareBy<Node> { it.level.ordinal.let { o -> if (it.level == Level.UNKNOWN) 9 else o } }.thenBy { it.skill })
    }

    /** 5.6 revision plan: measured weak skills (weakest first), then declining trends, then skills with due flashcards. */
    data class Revision(val skill: String, val reason: String)
    fun revisionPlan(nodes: List<Node>, limit: Int = 5): List<Revision> {
        val weak = nodes.filter { it.level == Level.NEEDS_WORK }.sortedBy { it.correct * 100 / it.answered }
            .map { Revision(it.skill, "${it.correct} of ${it.answered} answers correct") }
        val declining = nodes.filter { it.trend == "declining" && it.level != Level.NEEDS_WORK }.map { Revision(it.skill, "last 5 answers worse than the 5 before") }
        val due = nodes.filter { it.due > 0 && it.level != Level.NEEDS_WORK && it.trend != "declining" }.sortedByDescending { it.due }.map { Revision(it.skill, "${it.due} flashcards due") }
        return (weak + declining + due).distinctBy { it.skill }.take(limit)
    }

    /** 5.6 learning path for a goal: its general prerequisites in order, each with the learner's measured level (or "not measured"). */
    data class Step(val skill: String, val status: String)
    fun learningPath(goal: String, nodes: List<Node>): List<Step> {
        val byName = nodes.associateBy { it.skill }
        fun walk(s: String, seen: MutableSet<String>): List<String> = if (!seen.add(s)) emptyList() else PREREQUISITES[s].orEmpty().flatMap { walk(it, seen) } + s
        return walk(norm(goal), mutableSetOf()).map { s -> byName[s]?.let { n -> Step(s, if (n.accuracy != null) "${n.level.label} (${n.answered} answers)" else "${n.answered} answers · not enough data") }
            ?: Step(s, "not measured yet") }
    }

    /** The adaptive next step, with its reason; null when there are no learning records at all. */
    fun next(nodes: List<Node>): Next? {
        if (nodes.isEmpty()) return null
        // 1) a goal whose prerequisite is measured weak (or a goal with weak results itself)
        for (g in nodes.filter { it.goal != null && it.goal.done < it.goal.total }) {
            nodes.firstOrNull { it.skill in g.prerequisites && it.level == Level.NEEDS_WORK }?.let {
                return Next(it.skill, "Your goal \"${g.skill}\" builds on ${it.skill}, where your results are ${it.correct} of ${it.answered} correct.", "Review ${it.skill} first") }
        }
        nodes.filter { it.level == Level.NEEDS_WORK }.minByOrNull { it.correct * 100 / it.answered }?.let {
            return Next(it.skill, "Weakest measured topic: ${it.correct} of ${it.answered} correct.", "Practise ${it.skill} (quiz or MCQs)") }
        nodes.filter { it.due > 0 }.maxByOrNull { it.due }?.let { return Next(it.skill, "${it.due} flashcards are due.", "Review the due cards") }
        nodes.firstOrNull { it.goal != null && it.goal.done < it.goal.total }?.let {
            return Next(it.skill, "Plan in progress: ${it.goal!!.done} of ${it.goal.total} lessons.", "Do the next lesson") }
        nodes.firstOrNull { it.level == Level.UNKNOWN && it.answered > 0 }?.let {
            return Next(it.skill, "Only ${it.answered} answers so far — not enough to measure.", "Answer a few more questions on ${it.skill}") }
        return null
    }
}
