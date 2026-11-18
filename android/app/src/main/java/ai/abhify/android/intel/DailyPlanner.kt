package ai.abhify.android.intel

import org.json.JSONObject
import java.io.File

/**
 * Next build — Productivity: a realistic daily plan from real records only. Overdue/due-today come only from stored
 * due dates; blocked to-dos are listed separately; priority comes from the user (task_meta.json); project next actions
 * fill the remaining time. Nothing is rescheduled or changed — the plan is a recommendation with reasons.
 */
object DailyPlanner {
    enum class Priority(val label: String, val rank: Int) { HIGH("High", 0), NORMAL("Normal", 1), LOW("Low", 2) }
    data class Meta(val priority: Priority = Priority.NORMAL, val blocked: Boolean = false, val minutes: Int? = null)
    /** [projectActiveAt]: when the to-do's project status was last updated (5.5 tie-breaker: recent work first). */
    data class Todo(val id: Long, val title: String, val project: String, val dueAt: Long?, val done: Boolean, val projectActiveAt: Long = 0)
    data class ProjectNext(val project: String, val next: String)
    data class Entry(val title: String, val project: String, val reason: String, val minutes: Int, val taskId: Long?)
    data class Plan(val entries: List<Entry>, val blocked: List<Todo>, val minutesPlanned: Int, val minutesAvailable: Int, val leftOver: Int)

    const val DEFAULT_MINUTES = 30
    private const val DAY = 86_400_000L

    fun plan(todos: List<Todo>, meta: Map<Long, Meta>, nexts: List<ProjectNext>, availableMinutes: Int, now: Long, dayStart: Long): Plan {
        val open = todos.filter { !it.done }
        val blocked = open.filter { meta[it.id]?.blocked == true }
        val candidates = open.filter { meta[it.id]?.blocked != true }.map { t ->
            val m = meta[t.id] ?: Meta()
            val due = t.dueAt
            val (rank, reason) = when {
                due != null && due < dayStart -> 0 to "Overdue since ${ProjectBrief.age(now - due)}"
                due != null && due < dayStart + DAY -> 1 to "Due today"
                m.priority == Priority.HIGH -> 2 to "You marked it high priority"
                due != null && due < dayStart + 3 * DAY -> 3 to "Due within 3 days"
                m.priority == Priority.LOW -> 6 to "Low priority"
                else -> 4 to "Open to-do"
            }
            Triple(rank, t, Entry(t.title, t.project, reason, m.minutes ?: DEFAULT_MINUTES, t.id))
        }.sortedWith(compareBy<Triple<Int, Todo, Entry>> { it.first }.thenBy { it.second.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.second.projectActiveAt }.thenBy { it.second.id })
        val entries = mutableListOf<Entry>(); var used = 0
        for ((_, _, e) in candidates) { if (used + e.minutes > availableMinutes && entries.isNotEmpty()) break; entries += e; used += e.minutes }
        // remaining time: the projects' own recorded next actions (not duplicating a to-do with the same text)
        for (n in nexts) {
            if (used + DEFAULT_MINUTES > availableMinutes) break
            if (entries.any { it.title.equals(n.next, true) }) continue
            entries += Entry(n.next, n.project, "Next action recorded in the project", DEFAULT_MINUTES, null); used += DEFAULT_MINUTES
        }
        return Plan(entries, blocked, used, availableMinutes, (candidates.size - entries.count { it.taskId != null }).coerceAtLeast(0))
    }

    /** "Weekly review": what was actually completed (rows with done = 1 updated in the last 7 days) and what is still open. */
    data class Week(val completed: List<String>, val stillOpen: Int, val overdue: Int)
    fun week(completed: List<String>, todos: List<Todo>, dayStart: Long) =
        Week(completed, todos.count { !it.done }, todos.count { !it.done && it.dueAt != null && it.dueAt < dayStart })
}

/** Priority / blocked / estimate per to-do, beside the database (no schema change): filesDir/task_meta.json. */
class TaskMetaStore(private val file: File) {
    fun load(): Map<Long, DailyPlanner.Meta> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> val m = o.getJSONObject(k)
            id to DailyPlanner.Meta(runCatching { DailyPlanner.Priority.valueOf(m.optString("priority")) }.getOrDefault(DailyPlanner.Priority.NORMAL),
                m.optBoolean("blocked"), if (m.has("minutes")) m.optInt("minutes").coerceIn(5, 480) else null) } }.toMap()
    }.getOrDefault(emptyMap())

    fun save(all: Map<Long, DailyPlanner.Meta>) = runCatching {
        val o = JSONObject()
        all.filterValues { it != DailyPlanner.Meta() }.forEach { (id, m) -> o.put(id.toString(), JSONObject().put("priority", m.priority.name).put("blocked", m.blocked)
            .apply { m.minutes?.let { put("minutes", it) } }) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
