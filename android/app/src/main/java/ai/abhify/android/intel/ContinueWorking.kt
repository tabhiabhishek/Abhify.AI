package ai.abhify.android.intel

/**
 * 5.2 Home "Continue working": where you were, from records on this phone — the last chat, the project you most
 * recently updated (with its next recorded action), an AI task that was interrupted or never run, and a study plan in
 * progress. Pure and deterministic: no model, no background work. Only activity from the last [WINDOW_DAYS] days counts,
 * one item per kind, most recent first, at most [MAX].
 * "Suggestions" say what may need attention; this says where you left off — a project shown here loses its duplicate
 * "Next in …" suggestion.
 */
object ContinueWorking {
    enum class Kind(val glyph: String) { CHAT("💬"), PROJECT("🗂"), TASK("✅"), STUDY("🎓") }
    data class Item(val kind: Kind, val title: String, val detail: String, val ref: String, val at: Long)

    data class ChatSignal(val id: Long, val title: String, val updatedAt: Long)
    data class ProjectSignal(val id: Long, val name: String, val updatedAt: Long, val next: String?, val current: String?, val archived: Boolean)
    data class TaskSignal(val id: String, val title: String, val at: Long, val interrupted: Boolean)
    data class PlanSignal(val topic: String, val done: Int, val total: Int, val lastActivity: Long)

    const val MAX = 3
    const val WINDOW_DAYS = 14

    fun items(chats: List<ChatSignal>, projects: List<ProjectSignal>, tasks: List<TaskSignal>, plans: List<PlanSignal>,
              now: Long = System.currentTimeMillis()): List<Item> {
        val since = now - WINDOW_DAYS * 86_400_000L
        val chat = chats.filter { it.updatedAt in since..now }.maxByOrNull { it.updatedAt }?.let {
            Item(Kind.CHAT, it.title.ifBlank { "Untitled chat" }, "Chat · ${ProjectBrief.age(now - it.updatedAt)}", it.id.toString(), it.updatedAt) }
        val project = projects.filter { !it.archived && it.updatedAt in since..now && (it.next != null || it.current != null) }.maxByOrNull { it.updatedAt }?.let {
            Item(Kind.PROJECT, it.name, it.next?.let { n -> "Next: ${n.take(90)}" } ?: "Status: ${it.current!!.take(90)}", it.id.toString(), it.updatedAt) }
        val task = tasks.filter { it.at in since..now }.maxByOrNull { it.at }?.let {
            Item(Kind.TASK, it.title, if (it.interrupted) "AI task · interrupted, can be resumed" else "AI task · not run yet", it.id, it.at) }
        val plan = plans.filter { it.total > 0 && it.done in 1 until it.total && it.lastActivity in since..now }.maxByOrNull { it.lastActivity }?.let {
            Item(Kind.STUDY, it.topic, "Study plan · ${it.done} of ${it.total} done", it.topic, it.lastActivity) }
        return listOfNotNull(chat, project, task, plan).sortedByDescending { it.at }.take(MAX)
    }
}
