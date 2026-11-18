package ai.abhify.android.career

import ai.abhify.android.chat.InterviewAttempt

/**
 * Interview practice progress (v3.7), computed from saved attempts only. Scores are the mean of the validated 1–5 AI
 * feedback scores of one attempt (an estimate, not a hiring signal); unscored attempts count as practice but not in averages.
 */
object PracticeStats {
    data class Line(val name: String, val attempts: Int, val scored: Int, val average: Double?, val trend: Double?, val last: Long)

    /** "Technical · Requirement: Kotlin" → "Technical": the topic without the job requirement suffix. */
    fun topicOf(a: InterviewAttempt) = a.topic.substringBefore(" · ").trim()

    /** Change of the average over the last 3 scored attempts versus the 3 before (null until there are 4+ scored). */
    fun trend(scoresNewestFirst: List<Double>): Double? {
        if (scoresNewestFirst.size < 4) return null
        val recent = scoresNewestFirst.take(3); val before = scoresNewestFirst.drop(3).take(3)
        return recent.average() - before.average()
    }

    fun line(name: String, list: List<InterviewAttempt>): Line {
        val sorted = list.sortedByDescending { it.createdAt }
        val scores = sorted.mapNotNull { it.score }
        return Line(name, list.size, scores.size, scores.takeIf { it.isNotEmpty() }?.average(), trend(scores), sorted.firstOrNull()?.createdAt ?: 0)
    }

    fun byTopic(all: List<InterviewAttempt>): List<Line> = all.groupBy(::topicOf).map { (t, l) -> line(t, l) }.sortedByDescending { it.last }
    /** Per saved job ([titles]: jd id → title); attempts for deleted jobs are grouped as "Deleted job". */
    fun byJob(all: List<InterviewAttempt>, titles: Map<Long, String>): List<Line> =
        all.filter { it.jdId != null }.groupBy { it.jdId!! }.map { (id, l) -> line(titles[id] ?: "Deleted job", l) }.sortedByDescending { it.last }

    fun meanScore(scores: Collection<Int>): Double? = scores.filter { it in 1..5 }.takeIf { it.isNotEmpty() }?.average()
}
