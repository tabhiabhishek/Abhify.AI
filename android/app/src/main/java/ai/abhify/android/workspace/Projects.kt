package ai.abhify.android.workspace

import org.json.JSONObject
import java.io.File

/**
 * Projects (Project Workspace) on Android are the existing workspaces (workspaces / notes / tasks / workspace_links,
 * library_docs.workspace_id): the same data, nothing copied. What a project adds is kept here, in one JSON file next to
 * the database (filesDir/projects.json), so the database schema stays as it is:
 *  - instructions: added to project chats and project AI tasks (bounded);
 *  - archived: hidden from pickers and the project list until restored (nothing is deleted);
 *  - useMemory: whether personal memories may be added to this project's chats. On by default, which is the existing
 *    behaviour of workspace chats, and still only when personal memory is on in Settings; off keeps a project to its own material.
 */
data class ProjectMeta(val instructions: String = "", val archived: Boolean = false, val useMemory: Boolean = true, val updatedAt: Long = 0,
                       /** 5.1 project intelligence: written by the user (never by the AI on its own); all optional */
                       val status: ProjectStatus = ProjectStatus())

/**
 * What the user recorded about a project's progress (5.1). Continuation summaries are built only from these fields,
 * the project's open to-dos and linked documents, so Abhify never states progress that wasn't recorded.
 */
data class ProjectStatus(val objective: String = "", val stack: String = "", val current: String = "", val decisions: List<String> = emptyList(),
                         val milestones: List<String> = emptyList(), val issues: List<String> = emptyList(), val next: List<String> = emptyList(),
                         val updatedAt: Long = 0) {
    val isEmpty get() = objective.isBlank() && stack.isBlank() && current.isBlank() && decisions.isEmpty() && milestones.isEmpty() && issues.isEmpty() && next.isEmpty()
    fun toJson(): JSONObject = JSONObject().put("objective", objective).put("stack", stack).put("current", current)
        .put("decisions", org.json.JSONArray(decisions)).put("milestones", org.json.JSONArray(milestones)).put("issues", org.json.JSONArray(issues))
        .put("next", org.json.JSONArray(next)).put("updatedAt", updatedAt)
    companion object {
        const val MAX_FIELD = 400; const val MAX_ITEMS = 12
        fun list(text: String) = text.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_FIELD) }.take(MAX_ITEMS)
        fun fromJson(o: JSONObject?): ProjectStatus {
            if (o == null) return ProjectStatus()
            fun l(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            return ProjectStatus(o.optString("objective"), o.optString("stack"), o.optString("current"), l("decisions"), l("milestones"), l("issues"), l("next"), o.optLong("updatedAt"))
        }
    }
}

class ProjectStore(private val file: File) {
    fun load(): Map<Long, ProjectMeta> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> val m = o.getJSONObject(k)
            id to ProjectMeta(m.optString("instructions"), m.optBoolean("archived"), m.optBoolean("useMemory", true), m.optLong("updatedAt"),
                ProjectStatus.fromJson(m.optJSONObject("status"))) } }.toMap()
    }.getOrDefault(emptyMap())

    fun save(all: Map<Long, ProjectMeta>) = runCatching {
        file.parentFile?.mkdirs()
        val o = JSONObject()
        all.forEach { (id, m) -> o.put(id.toString(), JSONObject().put("instructions", m.instructions).put("archived", m.archived)
            .put("useMemory", m.useMemory).put("updatedAt", m.updatedAt).apply { if (!m.status.isEmpty) put("status", m.status.toJson()) }) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAX_INSTRUCTIONS = 1_500

        /** Project instructions for the AI: the user's own text, marked as preferences that never override safety or app rules. */
        fun instructionBlock(name: String, instructions: String): String =
            if (instructions.isBlank()) "" else "Project \"$name\" instructions from the user (follow them unless they conflict with your rules):\n" +
                instructions.trim().take(MAX_INSTRUCTIONS)

        /** A project as Markdown (Export): instructions, documents, notes, to-dos and linked items. Titles only for links. */
        fun markdown(name: String, meta: ProjectMeta, docs: List<String>, notes: List<Pair<String, String>>, todos: List<Pair<String, Boolean>>,
                     links: List<Pair<String, String>>): String = buildString {
            append("# $name\n\n")
            if (meta.archived) append("_Archived project._\n\n")
            if (meta.instructions.isNotBlank()) append("## Instructions\n\n${meta.instructions.trim()}\n\n")
            meta.status.takeIf { !it.isEmpty }?.let { st ->
                append("## Status\n\n")
                listOf("Objective" to listOf(st.objective), "Technology" to listOf(st.stack), "Current status" to listOf(st.current), "Completed" to st.milestones,
                    "Open issues" to st.issues, "Next actions" to st.next, "Decisions" to st.decisions).forEach { (k, v) ->
                    val items = v.filter { it.isNotBlank() }; if (items.isNotEmpty()) append("**$k:**\n").append(items.joinToString("\n") { "- $it" }).append("\n\n") }
            }
            append("## Documents\n\n").append(docs.joinToString("\n") { "- $it" }.ifBlank { "- (none)" }).append("\n\n")
            append("## Notes\n\n").append(notes.joinToString("\n\n") { (t, b) -> "### $t\n\n$b" }.ifBlank { "(none)" }).append("\n\n")
            append("## To-dos\n\n").append(todos.joinToString("\n") { (t, done) -> "- [${if (done) "x" else " "}] $t" }.ifBlank { "- (none)" }).append("\n\n")
            if (links.isNotEmpty()) append("## Linked items\n\n").append(links.joinToString("\n") { (k, t) -> "- $k: $t" }).append("\n\n")
            append("Exported from Abhify.AI.\n")
        }
    }
}
