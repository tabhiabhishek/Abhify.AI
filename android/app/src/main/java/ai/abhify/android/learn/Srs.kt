package ai.abhify.android.learn

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A small, transparent spaced-repetition scheduler (v3.9), modelled on SM-2 (Wozniak 1990) with four buttons.
 * It is a reasonable heuristic, not a scientifically validated or personalised algorithm:
 *  - Again: back in 10 minutes, ease −0.20, counted as a lapse
 *  - Hard : interval × 1.2 (at least 1 day), ease −0.15
 *  - Good : 1 day → 3 days → interval × ease
 *  - Easy : 3 days for a new card, else interval × ease × 1.3, ease +0.15
 * Ease stays between 1.3 and 3.0 and intervals are capped at [MAX_DAYS].
 */
object Srs {
    enum class Rating(val label: String) { AGAIN("Again"), HARD("Hard"), GOOD("Good"), EASY("Easy") }
    data class State(val intervalDays: Double = 0.0, val ease: Double = 2.5, val lapses: Int = 0, val reviews: Int = 0)
    data class Next(val state: State, val dueAt: Long)

    const val MAX_DAYS = 180.0
    private const val DAY = 86_400_000L
    private const val AGAIN_MS = 10 * 60_000L

    fun next(s: State, r: Rating, now: Long): Next {
        val st = when (r) {
            Rating.AGAIN -> s.copy(intervalDays = 0.0, ease = (s.ease - 0.20).coerceAtLeast(1.3), lapses = s.lapses + 1)
            Rating.HARD -> s.copy(intervalDays = maxOf(1.0, s.intervalDays * 1.2), ease = (s.ease - 0.15).coerceAtLeast(1.3))
            Rating.GOOD -> s.copy(intervalDays = when { s.intervalDays < 1.0 -> 1.0; s.intervalDays < 3.0 -> 3.0; else -> s.intervalDays * s.ease })
            Rating.EASY -> s.copy(intervalDays = if (s.intervalDays < 1.0) 3.0 else s.intervalDays * s.ease * 1.3, ease = (s.ease + 0.15).coerceAtMost(3.0))
        }.let { it.copy(intervalDays = it.intervalDays.coerceAtMost(MAX_DAYS), reviews = s.reviews + 1) }
        val due = if (r == Rating.AGAIN) now + AGAIN_MS else now + (st.intervalDays * DAY).toLong()
        return Next(st, due)
    }

    /** "10 min", "1 day", "3 days", "2 months": shown on each button before the user taps it. */
    fun label(n: Next, now: Long): String {
        val ms = n.dueAt - now
        return when {
            ms < 60 * 60_000L -> "${ms / 60_000} min"
            ms < 45 * DAY -> (ms / DAY).let { if (it == 1L) "1 day" else "$it days" }
            else -> "${Math.round(ms / (30.0 * DAY))} months"
        }
    }

    /** Due now (never reviewed = due). */
    fun isDue(dueAt: Long?, now: Long) = dueAt == null || dueAt <= now
    /** A card counts as "mature" at an interval of 21+ days (common convention). */
    fun mature(intervalDays: Double) = intervalDays >= 21.0
}

/**
 * Plan sanity checks (v3.9): a plan must fit the time the learner said they have. Lessons beyond 30 are dropped;
 * a target date in the past is refused; too little time per lesson is flagged so the user can extend the date or narrow the topic.
 */
object PlanCheck {
    data class Result(val lessons: List<String>, val error: String?, val warnings: List<String>, val daysAvailable: Long?, val minutesPerLesson: Long?)

    fun check(lessons: List<String>, minutesPerDay: Int, targetDate: String?, today: LocalDate = LocalDate.now()): Result {
        val clean = lessons.map { it.trim() }.filter { it.length >= 4 }.distinctBy { it.lowercase() }.take(30)
        if (clean.size < 3) return Result(clean, "The plan needs at least 3 lessons.", emptyList(), null, null)
        if (minutesPerDay !in 10..600) return Result(clean, "Choose between 10 and 600 minutes per day.", emptyList(), null, null)
        val target = targetDate?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return Result(clean, "Write the target date as 2026-12-31.", emptyList(), null, null) }
        val days = target?.let { ChronoUnit.DAYS.between(today, it) }
        if (days != null && days < 1) return Result(clean, "The target date must be after today.", emptyList(), days, null)
        val perLesson = days?.let { it * minutesPerDay / clean.size }
        val warnings = buildList {
            if (lessons.size > 30) add("Only the first 30 lessons were kept.")
            if (perLesson != null && perLesson < 15) add("About $perLesson minutes per lesson before the target date: that is tight. Consider a later date, more time per day, or a narrower topic.")
        }
        return Result(clean, null, warnings, days, perLesson)
    }
}
