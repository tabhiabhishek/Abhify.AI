package ai.abhify.android.agent

import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.intel.ProjectBrief
import ai.abhify.android.workspace.ProjectStatus

/**
 * 5.5 Agent workflows beyond "find unfinished work", as pure functions over saved records only:
 *  - status summary with blockers and possible duplicate to-dos (read-only report, every line says where it came from);
 *  - priority suggestions (from stored due dates and wording that says the to-do is waiting) → approved via task.meta;
 *  - related documents (library documents whose name/text share terms with the project) → approved via doc.link.
 * Nothing here writes; the executor (AgentState) applies only what the user approves and verifies it.
 */
object ProjectInsights {
    /** [done]: completed to-dos are passed only so a new to-do that repeats finished work can be flagged ("already done?"). */
    data class Todo(val id: Long, val title: String, val dueAt: Long?, val meta: DailyPlanner.Meta, val done: Boolean = false)
    /** A suspected duplicate pair for the user to review; [alreadyDone] = one of the two is completed. */
    data class Dup(val a: Todo, val b: Todo, val alreadyDone: Boolean, val why: String)
    data class Line(val text: String, val source: String)
    data class Summary(val sections: List<Pair<String, List<Line>>>) { val isEmpty get() = sections.all { it.second.isEmpty() } }

    private const val DAY = 86_400_000L
    private val WAITING = Regex("(?i)\\b(blocked|waiting (?:for|on)|stuck|depends on|pending approval|on hold)\\b")

    fun summary(name: String, st: ProjectStatus, all: List<Todo>, notes: String, docs: List<String>, now: Long, dayStart: Long): Summary {
        val todos = all.filter { !it.done }
        val overdue = todos.filter { it.dueAt != null && it.dueAt < dayStart }
        val recorded = listOfNotNull(
            st.objective.ifBlank { null }?.let { Line("Objective: $it", "project status") },
            st.current.ifBlank { null }?.let { Line("Status: $it" + if (st.updatedAt > 0) " (recorded ${ProjectBrief.age(now - st.updatedAt)})" else "", "project status") },
            st.milestones.takeIf { it.isNotEmpty() }?.let { Line("Done: ${it.take(4).joinToString("; ")}", "project milestones") },
        )
        val work = listOfNotNull(
            todos.takeIf { it.isNotEmpty() }?.let { Line("${it.size} open to-do(s)" + (if (overdue.isNotEmpty()) ", ${overdue.size} past their due date" else ""), "to-do list") },
            st.next.takeIf { it.isNotEmpty() }?.let { Line("Next: ${it.take(3).joinToString("; ")}", "project next actions") },
        )
        val blockers = blockers(st, todos, notes)
        val dups = duplicates(all).map { d -> if (d.alreadyDone) Line("\"${d.b.title}\" may already be done: \"${d.a.title}\" is completed (${d.why})", "to-do list (completed item)")
            else Line("\"${d.a.title}\" and \"${d.b.title}\" look like the same to-do (${d.why})", "to-do list (similar wording)") }
        val docLines = docs.take(5).map { Line(it, "linked document") }
        return Summary(listOf("Recorded" to recorded, "Work" to work, "Blockers" to blockers, "Possible duplicates" to dups, "Documents" to docLines))
    }

    /** Blockers only from records: open issues, to-dos marked blocked, and note lines that say something is waiting. */
    fun blockers(st: ProjectStatus, todos: List<Todo>, notes: String): List<Line> =
        st.issues.map { Line(it, "open issue") } +
            todos.filter { it.meta.blocked }.map { Line(it.title, "to-do marked blocked") } +
            notes.lines().map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.length in 6..200 && WAITING.containsMatchIn(it) }.take(5).map { Line(it, "your notes") }

    private val STOP = setOf("the", "a", "an", "to", "of", "for", "and", "or", "in", "on", "at", "my", "our", "this", "that", "with", "file", "please", "do")

    /** 5.6: normalised terms — lower case, punctuation dropped, stop words removed, light stemming (-ing/-ed/-es/-s). */
    fun norm(s: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.map { w ->
        when { w.length > 5 && w.endsWith("ing") -> w.dropLast(3); w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 4 && w.endsWith("es") -> w.dropLast(2); w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1); else -> w } }
        .map { if (it.length > 3 && it.endsWith("e")) it.dropLast(1) else it }.toSet()             // write / writing / writes → writ

    /**
     * 5.6 smarter duplicates (one project's to-dos): normalised term overlap ≥ 60 %, or one wording contained in the other
     * (≥ 2 terms). An open to-do that repeats a completed one is flagged "already done?" instead. Two completed ones are
     * ignored. Suggestions only — never merged, changed or deleted automatically.
     */
    fun duplicates(todos: List<Todo>): List<Dup> {
        val out = mutableListOf<Dup>()
        val terms = todos.map { norm(it.title) }
        for (i in todos.indices) for (j in i + 1 until todos.size) {
            val x = todos[i]; val y = todos[j]
            if (x.done && y.done) continue
            val a = terms[i]; val b = terms[j]
            if (a.isEmpty() || b.isEmpty()) continue
            val overlap = a.intersect(b).size.toDouble() / a.union(b).size
            val contained = minOf(a.size, b.size) >= 2 && (a.containsAll(b) || b.containsAll(a))
            if (overlap < 0.6 && !contained) continue
            val why = if (overlap >= 0.999) "same wording" else if (contained) "one includes the other" else "${(overlap * 100).toInt()} % of words match"
            out += if (x.done || y.done) Dup(if (x.done) x else y, if (x.done) y else x, true, why) else Dup(x, y, false, why)
        }
        return out.take(8)
    }

    data class PrioritySuggestion(val taskId: Long, val title: String, val priority: DailyPlanner.Priority?, val blocked: Boolean?, val reason: String)

    /** Suggestions from stored data only: overdue/due-soon → High; wording that says it's waiting → Blocked. */
    fun prioritize(todos: List<Todo>, now: Long, dayStart: Long): List<PrioritySuggestion> = todos.filter { !it.done }.mapNotNull { t ->
        when {
            t.dueAt != null && t.dueAt < dayStart && t.meta.priority != DailyPlanner.Priority.HIGH ->
                PrioritySuggestion(t.id, t.title, DailyPlanner.Priority.HIGH, null, "Overdue since ${ProjectBrief.age(now - t.dueAt)} (stored due date)")
            t.dueAt != null && t.dueAt < dayStart + 2 * DAY && t.meta.priority != DailyPlanner.Priority.HIGH ->
                PrioritySuggestion(t.id, t.title, DailyPlanner.Priority.HIGH, null, "Due within 2 days (stored due date)")
            !t.meta.blocked && WAITING.containsMatchIn(t.title) -> PrioritySuggestion(t.id, t.title, null, true, "Its wording says it is waiting on something")
            else -> null
        }
    }

    data class DocMatch(val docId: Long, val name: String, val shared: List<String>)

    /** Library documents not yet linked whose name or beginning shares ≥ 2 meaningful terms with the project. */
    fun relatedDocs(projectText: String, docs: List<Triple<Long, String, String>>, linked: Set<Long>): List<DocMatch> {
        val stop = setOf("the", "and", "for", "with", "this", "that", "from", "project", "notes", "pdf", "docx", "txt")
        fun terms(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in stop }.toSet()
        val p = terms(projectText)
        return docs.filter { it.first !in linked }.mapNotNull { (id, name, text) ->
            val shared = p.intersect(terms(name + " " + text.take(4_000)))
            if (shared.size >= 2) DocMatch(id, name, shared.sorted().take(5)) else null
        }.sortedByDescending { it.shared.size }.take(6)
    }
}
