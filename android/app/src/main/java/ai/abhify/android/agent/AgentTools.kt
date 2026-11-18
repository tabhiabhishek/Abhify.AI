package ai.abhify.android.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Next build — Agent 2.0 typed tool registry. The agent (and only code, never model output) calls these tools; each has
 * an id, description, typed inputs with validation, a permission class, a confirmation rule and a result description.
 * READ tools run within the run's permission; REVERSIBLE tools run only after the user approves the preview; SENSITIVE
 * tools are never callable by the agent (deletion, export, privacy changes stay on their confirmation screens).
 */
object AgentTools {
    enum class Kind(val label: String) { READ("reads records"), REVERSIBLE("changes data after your approval; can be undone"), SENSITIVE("never run by the agent") }
    data class Field(val name: String, val required: Boolean = true, val maxLength: Int = 200)
    data class Spec(val id: String, val description: String, val kind: Kind, val inputs: List<Field>, val result: String) {
        val needsApproval get() = kind != Kind.READ
    }

    val SPECS = listOf(
        Spec("project.read", "Read a project's recorded status, notes and open to-dos", Kind.READ, listOf(Field("projectId", maxLength = 20)), "status, notes, to-dos"),
        Spec("tasks.list", "List the open to-dos of a project", Kind.READ, listOf(Field("projectId", maxLength = 20)), "to-do titles"),
        Spec("knowledge.search", "Search saved knowledge, notes and document titles on this phone", Kind.READ, listOf(Field("query", maxLength = 120)), "matching items"),
        Spec("plan.daily", "Make today's plan from to-dos and project next actions", Kind.READ, emptyList(), "plan entries"),
        Spec("tasks.create", "Create to-dos in a project", Kind.REVERSIBLE, listOf(Field("projectId", maxLength = 20), Field("titles", maxLength = 3_000)), "created to-do ids"),
        Spec("tasks.complete", "Mark a to-do done", Kind.REVERSIBLE, listOf(Field("taskId", maxLength = 20)), "updated to-do"),
        Spec("note.create", "Create a note in a project", Kind.REVERSIBLE, listOf(Field("projectId", maxLength = 20), Field("text", maxLength = 5_000)), "note id"),
        Spec("task.meta", "Set a to-do's priority or blocked flag", Kind.REVERSIBLE, listOf(Field("taskId", maxLength = 20), Field("priority", false, 10), Field("blocked", false, 5)), "updated meta"),
        Spec("project.summary", "Summarise a project's recorded status, work, blockers and possible duplicate to-dos", Kind.READ, listOf(Field("projectId", maxLength = 20)), "report lines with sources"),
        Spec("docs.related", "Find library documents that share terms with a project", Kind.READ, listOf(Field("projectId", maxLength = 20)), "document matches"),
        Spec("doc.link", "Link a library document to a project (a reference; nothing is copied)", Kind.REVERSIBLE, listOf(Field("projectId", maxLength = 20), Field("docId", maxLength = 20)), "link"),
        Spec("data.delete", "Delete conversations, memories, documents or other data", Kind.SENSITIVE, emptyList(), "never executed by the agent"),
    )
    fun spec(id: String) = SPECS.firstOrNull { it.id == id }

    /** Null when the call may run; else why not. Unknown ids, sensitive tools and bad inputs are refused here, in code. */
    fun validate(id: String, args: Map<String, String>, approved: Boolean): String? {
        val s = spec(id) ?: return "\"$id\" is not a registered tool."
        if (s.kind == Kind.SENSITIVE) return "${s.description} needs you on its confirmation screen; the agent never does it."
        if (s.needsApproval && !approved) return "${s.description} needs your approval first."
        for (f in s.inputs) {
            val v = args[f.name]
            if (f.required && v.isNullOrBlank()) return "Missing ${f.name}."
            if (v != null && v.length > f.maxLength) return "${f.name} is too long."
            if (v != null && v.any { it.isISOControl() && it != '\n' }) return "${f.name} has characters that can't be used."
        }
        args.keys.firstOrNull { k -> s.inputs.none { it.name == k } }?.let { return "Unknown input \"$it\" for $id." }
        if (s.inputs.any { it.name == "taskId" } && args["taskId"]?.toLongOrNull() == null) return "taskId must be a number."
        if (s.inputs.any { it.name == "projectId" } && args["projectId"]?.toLongOrNull() == null) return "projectId must be a number."
        return null
    }

    // ------------------------------------------------------------------ audit history (filesDir/agent_runs.json, last 100 runs)
    data class Step(val tool: String, val status: String, val detail: String, val at: Long)
    data class Run(val id: String, val workflow: String, val subject: String, val startedAt: Long, val steps: List<Step>, val outcome: String)

    class AuditStore(private val file: File) {
        fun load(): List<Run> = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val st = o.getJSONArray("steps")
                Run(o.getString("id"), o.getString("workflow"), o.optString("subject"), o.getLong("startedAt"),
                    (0 until st.length()).map { st.getJSONObject(it) }.map { Step(it.getString("tool"), it.getString("status"), it.optString("detail"), it.getLong("at")) },
                    o.optString("outcome"))
            }
        }.getOrDefault(emptyList())

        fun append(r: Run) = runCatching {
            val all = (load().filter { it.id != r.id } + r).takeLast(100)
            val a = JSONArray(all.map { x -> JSONObject().put("id", x.id).put("workflow", x.workflow).put("subject", x.subject).put("startedAt", x.startedAt)
                .put("outcome", x.outcome).put("steps", JSONArray(x.steps.map { s -> JSONObject().put("tool", s.tool).put("status", s.status).put("detail", s.detail.take(300)).put("at", s.at) })) })
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
