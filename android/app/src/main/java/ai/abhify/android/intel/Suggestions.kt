package ai.abhify.android.intel

/**
 * 5.1 Proactive Companion: a few suggestions computed from records on the phone (project next actions and open issues,
 * overdue to-dos, the study dashboard's own suggestions, corrections waiting for review, AI tasks that failed). No model,
 * no background work, no notifications: they are computed when Home opens and shown in one card that can be dismissed
 * item by item or turned off. Each has a stable key, so a dismissed suggestion stays dismissed until its content changes.
 */
object Suggestions {
    enum class Target { PROJECT, STUDY, KNOWLEDGE_CENTER, TASKS }
    data class Suggestion(val key: String, val title: String, val reason: String, val action: String, val target: Target, val ref: Long?, val priority: Int)

    data class ProjectSignal(val id: Long, val name: String, val next: List<String>, val issues: List<String>, val openTodos: Int, val overdueTodos: Int,
                             val statusUpdatedAt: Long, val archived: Boolean)
    data class StudySignal(val topic: String, val reason: String, val priority: Int)
    data class TaskSignal(val title: String, val failedAt: Long)

    const val MAX = 3

    fun compute(projects: List<ProjectSignal>, study: List<StudySignal>, pendingCorrections: Int, failedTasks: List<TaskSignal>,
                dismissed: Set<String>, now: Long = System.currentTimeMillis()): List<Suggestion> = buildList {
        for (p in projects.filter { !it.archived }) {
            if (p.overdueTodos > 0) add(Suggestion("overdue:${p.id}:${p.overdueTodos}", "${p.overdueTodos} overdue to-do${if (p.overdueTodos == 1) "" else "s"} in ${p.name}",
                "Past their due date", "Open project", Target.PROJECT, p.id, 1))
            p.issues.firstOrNull()?.let { i -> add(Suggestion("issue:${p.id}:${i.hashCode()}", "${p.name}: open issue", i.take(120) +
                if (p.issues.size > 1) " (+${p.issues.size - 1} more)" else "", "Review", Target.PROJECT, p.id, 2)) }
            p.next.firstOrNull()?.let { n -> val stale = p.statusUpdatedAt > 0 && now - p.statusUpdatedAt > 14 * 86_400_000L
                add(Suggestion("next:${p.id}:${n.hashCode()}", "Next in ${p.name}: ${n.take(80)}",
                    if (stale) "Status recorded ${ProjectBrief.age(now - p.statusUpdatedAt)}: still current?" else "From your project's next actions",
                    "Continue", Target.PROJECT, p.id, if (stale) 2 else 3)) }
        }
        study.minByOrNull { it.priority }?.let { s -> add(Suggestion("study:${s.topic.hashCode()}:${s.reason.hashCode()}", "Study: ${s.topic}", s.reason, "Open Study", Target.STUDY, null, 2 + s.priority)) }
        if (pendingCorrections > 0) add(Suggestion("corrections:$pendingCorrections", "$pendingCorrections correction${if (pendingCorrections == 1) "" else "s"} to review",
            "Nothing is learned until you approve", "Review", Target.KNOWLEDGE_CENTER, null, 3))
        failedTasks.filter { now - it.failedAt < 3 * 86_400_000L }.maxByOrNull { it.failedAt }?.let { t ->
            add(Suggestion("task:${t.title.hashCode()}:${t.failedAt}", "AI task didn't finish: ${t.title.take(60)}", "You can retry it", "Open tasks", Target.TASKS, null, 3)) }
    }.filter { it.key !in dismissed }.sortedBy { it.priority }.distinctBy { it.ref to it.target.takeIf { t -> t == Target.PROJECT } ?: it.key }.take(MAX)
}
