package ai.abhify.android.learn

/**
 * Knowledge-gap suggestions (v3.9) from the learner's OWN study records only — quiz answers, document quizzes,
 * flashcard reviews and plans. Never from chats, memories or anything personal. Every suggestion says why.
 */
object StudyInsights {
    data class QuizStat(val topic: String, val answered: Int, val correct: Int)
    data class DeckStat(val deck: String, val cards: Int, val due: Int, val oftenMissed: Int, val lastReviewed: Long?)
    data class PlanStat(val topic: String, val done: Int, val total: Int, val targetDate: String?, val lastActivity: Long)
    data class SessionStat(val title: String, val score: Int, val total: Int, val at: Long)
    data class Suggestion(val topic: String, val reason: String, val action: String, val priority: Int)

    private const val DAY = 86_400_000L

    fun suggest(quizzes: List<QuizStat>, decks: List<DeckStat>, plans: List<PlanStat>, sessions: List<SessionStat>, now: Long, today: String): List<Suggestion> = buildList {
        quizzes.filter { it.answered >= 5 && it.correct * 100 / it.answered < 60 }.forEach {
            add(Suggestion(it.topic, "Low quiz accuracy: ${it.correct} of ${it.answered} correct (${it.correct * 100 / it.answered}%).", "Practise this quiz category", 90 - it.correct * 100 / it.answered / 2)) }
        sessions.filter { it.total > 0 && it.score * 100 / it.total < 60 }.groupBy { it.title }.forEach { (t, l) -> val s = l.maxBy { it.at }
            add(Suggestion(t, "Last quiz on this material: ${s.score} of ${s.total} correct.", "Retake the quiz or review the summary", 75)) }
        decks.filter { it.oftenMissed >= 3 }.forEach { add(Suggestion(it.deck, "${it.oftenMissed} cards forgotten twice or more.", "Review the deck (start with \"Again\" cards)", 70)) }
        decks.filter { it.due >= 10 }.forEach { add(Suggestion(it.deck, "${it.due} cards are due for review.", "Review due cards", 60 + minOf(it.due, 30) / 3)) }
        decks.filter { it.cards > 0 && it.due == 0 && it.lastReviewed != null && now - it.lastReviewed > 30 * DAY }.forEach {
            add(Suggestion(it.deck, "Not reviewed for ${(now - it.lastReviewed!!) / DAY} days.", "A short refresher review", 30)) }
        plans.filter { it.done < it.total }.forEach { p ->
            when {
                p.targetDate != null && p.targetDate < today -> add(Suggestion(p.topic, "Target date ${p.targetDate} has passed with ${p.total - p.done} lessons left.", "Set a new date or trim the plan", 80))
                now - p.lastActivity > 14 * DAY -> add(Suggestion(p.topic, "No lesson done for ${(now - p.lastActivity) / DAY} days.", "Do the next lesson", 50))
            }
        }
    }.sortedByDescending { it.priority }
}
