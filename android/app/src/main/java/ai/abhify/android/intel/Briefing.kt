package ai.abhify.android.intel

/**
 * Next build — Personal AI Briefing, composed only from records (no model call, nothing invented): today's plan, projects
 * in progress, to-dos completed this week, study items, AI tasks that failed, the last saved news (with the time it was
 * saved, never presented as current). Each item carries its source so the user can see where it came from and skip it.
 */
object Briefing {
    enum class Kind(val title: String) { MORNING("Morning briefing"), EVENING("Evening summary"), WEEKLY("Weekly review"), ON_DEMAND("Your briefing") }
    data class Item(val key: String, val text: String, val source: String)
    data class Section(val title: String, val items: List<Item>)
    data class Brief(val kind: Kind, val sections: List<Section>) {
        val isEmpty get() = sections.all { it.items.isEmpty() }
        /** Spoken version: short, no source codes; never reads note or document contents. */
        fun spoken(): String = if (isEmpty) "There's nothing in your records to brief you on yet."
            else "${kind.title}. " + sections.filter { it.items.isNotEmpty() }.joinToString(" ") { s -> "${s.title}: " + s.items.take(3).joinToString("; ") { it.text } + "." }
    }

    data class Inputs(
        val plan: DailyPlanner.Plan?, val projects: List<Pair<String, String>>, val completedThisWeek: List<String>,
        val study: List<String>, val failedTasks: List<String>, val news: List<String>, val newsSavedAt: Long, val now: Long,
    )

    fun compose(kind: Kind, i: Inputs, skipped: Set<String> = emptySet()): Brief {
        fun Section.keep() = copy(items = items.filter { it.key !in skipped })
        val today = Section("Today's priorities", i.plan?.entries.orEmpty().take(5).map { e ->
            Item("plan:${e.title.hashCode()}", e.title + (if (e.project.isNotBlank()) " (${e.project})" else ""), "${e.reason} · to-dos/projects") })
        val blocked = Section("Blocked", i.plan?.blocked.orEmpty().take(3).map { Item("blocked:${it.id}", it.title, "marked blocked · ${it.project}") })
        val projects = Section("Projects in progress", i.projects.take(3).map { (name, line) -> Item("proj:${name.hashCode()}", "$name — $line", "project status") })
        val done = Section("Completed this week", i.completedThisWeek.take(5).map { Item("done:${it.hashCode()}", it, "to-dos marked done in the last 7 days") })
        val study = Section("Study", i.study.take(3).map { Item("study:${it.hashCode()}", it, "study records") })
        val failed = Section("Needs attention", i.failedTasks.take(2).map { Item("fail:${it.hashCode()}", "AI task didn't finish: $it", "AI task history") })
        val news = Section(if (i.newsSavedAt > 0) "Saved news (${ProjectBrief.age(i.now - i.newsSavedAt)})" else "Saved news",
            i.news.take(3).map { Item("news:${it.hashCode()}", it, "last saved copy of News, not live") })
        val sections = when (kind) {
            Kind.MORNING, Kind.ON_DEMAND -> listOf(today, blocked, projects, study, failed, news)
            Kind.EVENING -> listOf(done, today.copy(title = "Still open"), failed)
            Kind.WEEKLY -> listOf(done, projects, blocked, study, failed)
        }
        return Brief(kind, sections.map { it.keep() })
    }
}
