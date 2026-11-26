package ai.abhify.android.learn

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 7.2.0-dev.2 Android-only Study helpers, pure so they are unit-tested: the daily reminder's text, which lessons an
 * offline lesson pack writes, the home-screen widget's lines and the course progress report's lines. Every number comes
 * from study.json (recorded progress); nothing here is estimated or written by the AI.
 */
object StudyExtras {
    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    /**
     * The daily study reminder: "3 flashcards and 2 missed questions due", with the next lesson when there is one, or
     * "Next lesson: …" alone. null (no notification) when nothing is due and there is no next lesson.
     */
    fun reminderText(cardsDue: Int, questionsDue: Int, nextLesson: String?): String? {
        val parts = listOfNotNull(if (cardsDue > 0) plural(cardsDue, "flashcard", "flashcards") else null,
            if (questionsDue > 0) plural(questionsDue, "missed question", "missed questions") else null)
        val due = if (parts.isEmpty()) null else parts.joinToString(" and ") + " due"
        val next = nextLesson?.takeIf { it.isNotBlank() }?.let { "Next lesson: $it" }
        return when {
            due != null && next != null -> "$due. $next"
            due != null -> due
            else -> next
        }
    }

    /** A course as the lesson-pack worker sees it. */
    class PackCourse(val id: String, val prefsChosen: Boolean, val modules: List<StudyCore.Module>, val done: Set<String>, val optional: Set<String>,
                     val cached: Set<String>)

    /**
     * The lessons an offline pack writes: for each course whose goal and level are chosen, the next [perCourse] lessons in
     * StudyCore.nextLesson order (core modules before optional ones), skipping done and already-written lessons.
     */
    fun packSelection(courses: List<PackCourse>, perCourse: Int = 3): List<Pair<String, String>> = courses.filter { it.prefsChosen }.flatMap { c ->
        val ordered = c.modules.filter { it.id !in c.optional } + c.modules.filter { it.id in c.optional }
        ordered.flatMap { m -> m.lessons.map { it.id } }.filter { it !in c.done && it !in c.cached }.take(perCourse).map { c.id to it }
    }

    /** The widget's three lines. */
    data class Widget(val streak: String, val due: String, val next: String)

    fun widgetContent(streak: Int, cardsDue: Int, questionsDue: Int, nextLesson: String?, courses: Int): Widget {
        val due = cardsDue + questionsDue
        return Widget("🔥 ${plural(streak, "day", "days")} streak",
            if (due == 0) "Nothing due today" else "${plural(due, "item", "items")} due (${plural(cardsDue, "card", "cards")}, ${plural(questionsDue, "missed question", "missed questions")})",
            nextLesson?.takeIf { it.isNotBlank() }?.let { "Next: $it" } ?: if (courses == 0) "Pick a course in Study" else "All lessons done")
    }

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun duration(sec: Long): String { val m = Math.round(sec / 60.0); return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
    private val KINDS = mapOf("lesson" to "Read", "done" to "Finished", "exercise" to "Exercise done", "cards" to "Flashcards made", "capstone" to "Capstone planned",
        "capstone step" to "Capstone step done", "tutor" to "Asked the tutor about", "prefs" to "Goal set", "brain" to "Changed")

    /**
     * The course progress report, line by line, from the dashboard's recorded numbers only (the same numbers the course
     * page shows). Lines starting with "# " are headings. AI-written content (lessons, overview text) is not included.
     */
    fun reportLines(d: StudyService.Dashboard, generatedAt: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val out = ArrayList<String>()
        out += "# ${d.title}: progress report"
        out += "Goal: ${d.prefs.target}"
        out += "Level: ${d.prefs.level} · ${d.prefs.minutes} min a day"
        out += "Generated: ${DAY.format(Instant.ofEpochMilli(generatedAt).atZone(zone))}"
        out += "# Progress"
        out += "Course progress: ${d.progress}%"
        out += "Lessons: ${d.lessonsDone} of ${d.lessonsTotal}"
        out += "Modules: ${d.modulesDone} of ${d.modulesTotal}"
        out += "Score: " + (d.assessment.percent?.let { "$it% (${d.assessment.correct} of ${d.assessment.answered} answers right)" } ?: "no answers yet")
        out += "Time studied: ${duration(d.seconds)} (${duration(d.secondsWeek)} this week)"
        out += "Exercises done: ${d.exercisesDone}"
        out += "Streak: ${plural(d.streak, "day", "days")}"
        out += "# Revision"
        out += "Flashcards due: ${d.revision.cardsDue}"
        out += "Missed questions due: ${d.revision.questionsDue}"
        out += "Wrong answers this week: ${d.revision.wrongThisWeek}"
        out += "# Weak topics"
        if (d.weak.isEmpty()) out += "None recorded (a module is weak after 3+ answers under 60% right)."
        else d.weak.forEach { (_, title) -> out += "- $title" }
        out += "# Capstone"
        out += d.capstone?.let { "${it.title}: ${it.done} of ${it.total} steps done" } ?: "Not planned yet"
        out += "# Recent activity"
        if (d.history.isEmpty()) out += "Nothing yet."
        d.history.take(10).forEach { h -> out += "- ${STAMP.format(Instant.ofEpochMilli(h.at).atZone(zone))} · ${KINDS[h.kind] ?: h.kind}: ${h.detail}" }
        out += ""
        out += "Recorded on this phone; AI-written content not included."
        return out
    }
}
