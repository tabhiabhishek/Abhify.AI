package ai.abhify.android.intel

import ai.abhify.android.workspace.ProjectStatus

/**
 * 5.1 Project Intelligence: a continuation summary built ONLY from what is stored for the project (the status the user
 * recorded, open to-dos, linked documents). No model writes it and nothing is inferred, so it can't claim progress that
 * wasn't recorded. Used on the project page ("Where you left off"), in project chats (bounded, local by default) and
 * when a question in Auto mode names a project.
 */
object ProjectBrief {
    data class Facts(val name: String, val status: ProjectStatus, val openTodos: List<String>, val overdueTodos: Int, val docs: Int)

    /** Null when nothing was recorded (no status, no open to-dos): the UI then asks the user to add a status instead of guessing. */
    fun summary(f: Facts, now: Long = System.currentTimeMillis(), maxChars: Int = 1_200): String? {
        val s = f.status
        if (s.isEmpty && f.openTodos.isEmpty()) return null
        fun items(l: List<String>, n: Int) = l.take(n).joinToString("; ") + if (l.size > n) " (+${l.size - n} more)" else ""
        val lines = listOfNotNull(
            s.objective.takeIf { it.isNotBlank() }?.let { "Objective: $it" },
            s.stack.takeIf { it.isNotBlank() }?.let { "Technology: $it" },
            s.current.takeIf { it.isNotBlank() }?.let { "Current status" + (if (s.updatedAt > 0) " (recorded ${age(now - s.updatedAt)})" else "") + ": $it" },
            s.milestones.takeIf { it.isNotEmpty() }?.let { "Completed: ${items(it, 4)}" },
            s.issues.takeIf { it.isNotEmpty() }?.let { "Open issues: ${items(it, 4)}" },
            s.next.takeIf { it.isNotEmpty() }?.let { "Next actions: ${items(it, 4)}" },
            s.decisions.takeIf { it.isNotEmpty() }?.let { "Decisions: ${items(it, 4)}" },
            f.openTodos.takeIf { it.isNotEmpty() }?.let { "Open to-dos (${it.size}${if (f.overdueTodos > 0) ", ${f.overdueTodos} overdue" else ""}): ${items(it, 5)}" },
            if (f.docs > 0) "Linked documents: ${f.docs}" else null,
        )
        return ("Project \"${f.name}\" — recorded by the user:\n" + lines.joinToString("\n") { "- $it" }).take(maxChars)
    }

    /** How the summary is presented to the model: facts from the user's records, to use only when relevant. */
    fun forPrompt(summary: String) = "$summary\n(These are the user's own project records; don't add progress that isn't listed.)"

    /** A project named in [question] (name of at least 4 characters, whole words), for Auto Intelligence. */
    fun <T> mentioned(question: String, projects: List<Pair<T, String>>): T? = projects.filter { it.second.trim().length >= 4 }
        .sortedByDescending { it.second.length }
        .firstOrNull { (_, n) -> Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(n.trim()) + "(?![\\p{L}\\p{N}])").containsMatchIn(question) }?.first

    fun age(ms: Long): String {
        val d = ms / 86_400_000L
        return when { ms < 3_600_000L -> "just now"; d < 1 -> "today"; d == 1L -> "yesterday"; d < 30 -> "$d days ago"; else -> "${d / 30} month${if (d >= 60) "s" else ""} ago" }
    }
}
