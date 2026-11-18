package ai.abhify.android.ui

import ai.abhify.android.agent.AgentTools
import ai.abhify.android.agent.ProjectReview
import ai.abhify.android.chat.addTasks
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.docText
import ai.abhify.android.chat.linkToWorkspace
import ai.abhify.android.chat.unlink
import ai.abhify.android.chat.workspaceDocIds
import ai.abhify.android.chat.workspaceLinks
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.chat.deleteTask
import ai.abhify.android.chat.notes
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.workspaces
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Next build — Agent 2.0 executor for "Review my project and prepare tasks":
 *   plan → project.read → analyse (rules + optional AI with quote check) → preview (wait for approval) → tasks.create
 *   (approved items only) → verify (read back) → report; every tool call goes through AgentTools.validate and is logged
 *   in agent_runs.json. Cancel stops at any step; Undo removes exactly the to-dos this run created.
 */
class AgentState(private val vm: AppViewModel) {
    private val g = vm.g
    enum class Phase { IDLE, RUNNING, AWAITING_APPROVAL, DONE, FAILED, CANCELLED }
    data class StepUi(val title: String, val tool: String?, val status: String, val detail: String = "")

    val phase = mutableStateOf(Phase.IDLE)
    val subject = mutableStateOf("")
    val steps = mutableStateListOf<StepUi>()
    val proposals = mutableStateListOf<ProjectReview.Proposal>()
    val selected = mutableStateListOf<Int>()
    val report = mutableStateOf<String?>(null)
    val createdIds = mutableStateListOf<Long>()
    val audit = AgentTools.AuditStore(File(g.context.filesDir, "agent_runs.json"))
    val history = mutableStateListOf<AgentTools.Run>().apply { addAll(audit.load().reversed()) }
    private var job: Job? = null
    private var runId = ""; private var startedAt = 0L; private var projectId = 0L
    private val log = mutableListOf<AgentTools.Step>()

    val open get() = phase.value != Phase.IDLE

    // ------------------------------------------------------------------ 5.5 workflows beyond "find unfinished work"
    enum class Workflow(val label: String) { REVIEW("Find unfinished work"), STATUS("Status & blockers"), PRIORITIZE("Suggest priorities"), DOCS("Find related documents") }
    /** An approvable change other than a new to-do: a priority/blocked change or a document link (each reversible). */
    data class Item(val title: String, val detail: String, val kind: String, val refId: Long, val priority: DailyPlanner.Priority? = null, val blocked: Boolean? = null)
    val workflow = mutableStateOf(Workflow.REVIEW)
    val items = mutableStateListOf<Item>()
    val summary = mutableStateOf<ai.abhify.android.agent.ProjectInsights.Summary?>(null)
    private val undoActions = mutableListOf<() -> Unit>()

    private fun dayStart() = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    /** All of the project's to-dos (completed ones flagged, used only to spot repeats of finished work). */
    private fun todos(id: Long) = g.db.tasks(id).map { ai.abhify.android.agent.ProjectInsights.Todo(it.id, it.title, it.dueAt, vm.brain.meta[it.id] ?: DailyPlanner.Meta(), it.done) }

    /** Runs one of the 5.5 workflows on project [id]: reads only, then (for priorities / documents) waits for approval. */
    fun run(id: Long, wf: Workflow) {
        if (wf == Workflow.REVIEW) { reviewProject(id); return }
        if (job?.isActive == true) return
        reset(); workflow.value = wf; phase.value = Phase.RUNNING; projectId = id
        runId = java.util.UUID.randomUUID().toString().take(10); startedAt = System.currentTimeMillis()
        job = vm.viewModelScope.launch {
            try {
                step("Read the project", "project.read", "running")
                val ws = withContext(Dispatchers.IO) { call("project.read", mapOf("projectId" to "$id"), false) { g.db.workspaces().firstOrNull { it.id == id } ?: error("That project no longer exists.") } }
                subject.value = ws.name; step("Read the project", "project.read", "done")
                val st = vm.projects.meta(id).status; val now = System.currentTimeMillis()
                when (wf) {
                    Workflow.STATUS -> {
                        step("Summarise status, blockers and duplicates", "project.summary", "running")
                        val s = withContext(Dispatchers.IO) { call("project.summary", mapOf("projectId" to "$id"), false) {
                            val notes = g.db.notes(id).joinToString("\n") { "${it.title}\n${it.body}" }
                            val docs = g.db.workspaceDocIds(id).mapNotNull { d -> g.db.allDocs().firstOrNull { it.id == d }?.name }
                            ai.abhify.android.agent.ProjectInsights.summary(ws.name, st, todos(id), notes, docs, now, dayStart()) } }
                        summary.value = s; step("Summarise status, blockers and duplicates", "project.summary", "done")
                        finish(Phase.DONE, if (s.isEmpty) "Nothing is recorded for \"${ws.name}\" yet." else "Summary of \"${ws.name}\" from its saved records (each line shows its source).")
                    }
                    Workflow.PRIORITIZE -> {
                        step("Check due dates and blocked wording", "tasks.list", "running")
                        val sugg = withContext(Dispatchers.IO) { call("tasks.list", mapOf("projectId" to "$id"), false) { ai.abhify.android.agent.ProjectInsights.prioritize(todos(id), now, dayStart()) } }
                        items.addAll(sugg.map { Item(it.title, it.reason + " → " + (it.priority?.let { p -> "set ${p.label} priority" } ?: "mark blocked"), "priority", it.taskId, it.priority, it.blocked) })
                        items.indices.forEach { selected += it }
                        step("Check due dates and blocked wording", "tasks.list", "done", "${sugg.size} suggestion(s)")
                        if (items.isEmpty()) finish(Phase.DONE, "No priority changes are suggested by the saved due dates or wording.") else phase.value = Phase.AWAITING_APPROVAL
                    }
                    Workflow.DOCS -> {
                        step("Compare library documents with the project", "docs.related", "running")
                        val matches = withContext(Dispatchers.IO) { call("docs.related", mapOf("projectId" to "$id"), false) {
                            val text = listOf(ws.name, st.objective, st.current, st.next.joinToString(" "), st.decisions.joinToString(" ")).joinToString(" ")
                            ai.abhify.android.agent.ProjectInsights.relatedDocs(text, g.db.allDocs().map { Triple(it.id, it.name, g.db.docText(it.id).orEmpty()) }, g.db.workspaceDocIds(id).toSet()) } }
                        items.addAll(matches.map { Item(it.name, "shares: ${it.shared.joinToString()}", "link", it.docId) })
                        step("Compare library documents with the project", "docs.related", "done", "${matches.size} candidate(s)")
                        if (items.isEmpty()) finish(Phase.DONE, "No unlinked library document shares enough terms with \"${ws.name}\".") else phase.value = Phase.AWAITING_APPROVAL
                    }
                    Workflow.REVIEW -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { finish(Phase.FAILED, "Stopped: ${e.message ?: "unknown error"}. Nothing was changed.") }
        }
    }

    /** Applies the approved priority changes or document links through their tools, then reads them back. */
    fun approveItems() {
        if (phase.value != Phase.AWAITING_APPROVAL || workflow.value == Workflow.REVIEW) return
        val chosen = selected.sorted().mapNotNull { items.getOrNull(it) }
        if (chosen.isEmpty()) { finish(Phase.DONE, "Nothing selected, so nothing was changed."); return }
        phase.value = Phase.RUNNING
        job = vm.viewModelScope.launch {
            try {
                var verified = 0
                for (it in chosen) {
                    if (it.kind == "priority") {
                        val before = vm.brain.meta[it.refId] ?: DailyPlanner.Meta()
                        val after = before.copy(priority = it.priority ?: before.priority, blocked = it.blocked ?: before.blocked)
                        call("task.meta", mapOf("taskId" to "${it.refId}", "priority" to after.priority.name, "blocked" to "${after.blocked}"), approved = true) { vm.brain.setMeta(it.refId, after) }
                        undoActions += { vm.brain.setMeta(it.refId, before) }
                        if (vm.brain.meta[it.refId] == after) verified++                  // read back
                    } else withContext(Dispatchers.IO) {
                        call("doc.link", mapOf("projectId" to "$projectId", "docId" to "${it.refId}"), approved = true) { g.db.linkToWorkspace(projectId, "doc", it.refId) }
                        val link = g.db.workspaceLinks(projectId).firstOrNull { l -> l.kind == "doc" && l.refId == it.refId }   // read back
                        if (link != null) { verified++; undoActions += { vm.viewModelScope.launch(Dispatchers.IO) { g.db.unlink(link.id) } } }
                    }
                }
                step("Apply and verify", null, if (verified == chosen.size) "done" else "problem", "$verified of ${chosen.size} verified")
                finish(if (verified == chosen.size) Phase.DONE else Phase.FAILED, "Applied and verified $verified of ${chosen.size} change(s) in \"${subject.value}\".")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { finish(Phase.FAILED, "Stopped: ${e.message ?: "unknown error"}.") }
        }
    }
    val canUndo get() = createdIds.isNotEmpty() || undoActions.isNotEmpty()

    private fun step(title: String, tool: String?, status: String, detail: String = "") {
        val i = steps.indexOfFirst { it.title == title }
        val s = StepUi(title, tool, status, detail)
        if (i >= 0) steps[i] = s else steps += s
        if (tool != null) log += AgentTools.Step(tool, status, detail, System.currentTimeMillis())
    }

    /** Runs a registered tool after validation; refused calls are reported, never executed. */
    private fun <T> call(tool: String, args: Map<String, String>, approved: Boolean, run: () -> T): T {
        AgentTools.validate(tool, args, approved)?.let { log += AgentTools.Step(tool, "refused", it, System.currentTimeMillis()); error(it) }
        return run()
    }

    fun reviewProject(id: Long) {
        if (job?.isActive == true) return
        reset(); phase.value = Phase.RUNNING; projectId = id
        runId = java.util.UUID.randomUUID().toString().take(10); startedAt = System.currentTimeMillis()
        job = vm.viewModelScope.launch {
            try {
                listOf("Read the project", "Read its notes and to-dos", "Find unfinished work", "Your approval", "Create approved to-dos", "Verify").forEach { step(it, null, "waiting") }
                step("Read the project", "project.read", "running")
                val (ws, st) = withContext(Dispatchers.IO) { call("project.read", mapOf("projectId" to "$id"), false) {
                    val w = g.db.workspaces().firstOrNull { it.id == id } ?: error("That project no longer exists.")
                    w to vm.projects.meta(id).status } }
                subject.value = ws.name
                step("Read the project", "project.read", "done", if (st.isEmpty) "No recorded status" else "Status recorded ${ai.abhify.android.intel.ProjectBrief.age(System.currentTimeMillis() - st.updatedAt)}")
                step("Read its notes and to-dos", "tasks.list", "running")
                val (notes, open) = withContext(Dispatchers.IO) { call("tasks.list", mapOf("projectId" to "$id"), false) {
                    g.db.notes(id).joinToString("\n\n") { "## ${it.title}\n${it.body}" } to g.db.tasks(id).filter { !it.done }.map { it.title } } }
                step("Read its notes and to-dos", "tasks.list", "done", "${notes.length} characters of notes · ${open.size} open to-dos")
                step("Find unfinished work", null, "running")
                val statusText = listOf(st.current, st.next.joinToString("\n") { "Next: $it" }, st.issues.joinToString("\n") { "Issue: $it" }).filter { it.isNotBlank() }.joinToString("\n")
                val explicit = ProjectReview.explicit(notes, st.next, st.issues)
                val ai = if (notes.isBlank() && statusText.isBlank()) emptyList() else askAi(ws.name, statusText, notes)
                val merged = ProjectReview.merge(explicit, ai, open)
                proposals.addAll(merged); merged.forEachIndexed { i, p -> if (p.selectedByDefault) selected += i }
                step("Find unfinished work", null, "done", "${merged.count { it.verified }} found in your records · ${merged.count { !it.verified }} suggestions · ${merged.count { it.duplicateOf != null }} already to-dos")
                if (merged.isEmpty()) { finish(Phase.DONE, "No unfinished work is recorded in \"${ws.name}\"'s status or notes. Nothing to create."); return@launch }
                step("Your approval", null, "waiting", "Tick what to create, then Approve")
                phase.value = Phase.AWAITING_APPROVAL
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { finish(Phase.FAILED, "Stopped: ${e.message ?: "unknown error"}. Nothing was changed.") }
        }
    }

    /** The AI's proposals (via the normal routing and consent); a failure leaves the rule-based results. */
    private suspend fun askAi(name: String, status: String, notes: String): List<ProjectReview.Proposal> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        vm.aiTask("Agent: reviewing ${name.take(30)}", ProjectReview.SYSTEM, ProjectReview.prompt(name, status, notes), sensitive = "your project notes") { r ->
            val list = if (r.error != null) emptyList() else ProjectReview.parse(r.text, "$status\n$notes")
            if (r.error != null) step("Find unfinished work", null, "running", "AI unavailable (${r.error.take(60)}); using what's written in your notes")
            if (cont.isActive) cont.resumeWith(Result.success(list))
        }
    }

    fun toggle(i: Int) { if (i in selected) selected.remove(i) else selected += i }

    /** The user approved the ticked proposals: create them (reversible tool), then read them back to verify. */
    fun approve() {
        if (phase.value != Phase.AWAITING_APPROVAL) return
        val titles = selected.sorted().mapNotNull { proposals.getOrNull(it)?.title?.take(300) }
        if (titles.isEmpty()) { finish(Phase.DONE, "Nothing selected, so nothing was created."); return }
        phase.value = Phase.RUNNING
        step("Your approval", null, "done", "${titles.size} approved")
        job = vm.viewModelScope.launch {
            try {
                step("Create approved to-dos", "tasks.create", "running")
                val before = withContext(Dispatchers.IO) { g.db.tasks(projectId).map { it.id }.toSet() }
                withContext(Dispatchers.IO) { call("tasks.create", mapOf("projectId" to "$projectId", "titles" to titles.joinToString("\n")), approved = true) { g.db.addTasks(projectId, titles) } }
                step("Create approved to-dos", "tasks.create", "done")
                step("Verify", null, "running")
                val after = withContext(Dispatchers.IO) { g.db.tasks(projectId) }
                val created = after.filter { it.id !in before }
                createdIds.addAll(created.map { it.id })
                val missing = titles.filter { t -> created.none { it.title == t } }
                step("Verify", null, if (missing.isEmpty()) "done" else "problem", "${created.size} to-dos found in the project" + if (missing.isNotEmpty()) " · missing: ${missing.joinToString()}" else "")
                vm.studio.loadWork()
                finish(if (missing.isEmpty()) Phase.DONE else Phase.FAILED,
                    if (missing.isEmpty()) "Created and verified ${created.size} to-do(s) in \"${subject.value}\"." else "Created ${created.size}; ${missing.size} could not be verified.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { finish(Phase.FAILED, "Stopped before creating: ${e.message ?: "unknown error"}.") }
        }
    }

    fun undo() = vm.viewModelScope.launch {
        if (undoActions.isNotEmpty()) { val n = undoActions.size; undoActions.toList().forEach { it() }; undoActions.clear()
            log += AgentTools.Step("undo", "undone", "$n change(s) reverted", System.currentTimeMillis()); report.value = "Undone: $n change(s) reverted."; save(report.value!!); return@launch }
        val ids = createdIds.toList(); if (ids.isEmpty()) return@launch
        withContext(Dispatchers.IO) { ids.forEach { g.db.deleteTask(it) } }
        createdIds.clear(); vm.studio.loadWork(); log += AgentTools.Step("tasks.create", "undone", "${ids.size} removed", System.currentTimeMillis())
        report.value = "Undone: the ${ids.size} to-do(s) this run created were removed."; save(report.value!!)
    }

    fun cancel() { job?.cancel(); vm.cancelTask(); finish(Phase.CANCELLED, "Cancelled. " + if (createdIds.isEmpty()) "Nothing was changed." else "${createdIds.size} to-do(s) had been created (Undo removes them).") }

    fun close() { if (phase.value == Phase.RUNNING) cancel(); reset() }

    private fun finish(p: Phase, msg: String) { phase.value = p; report.value = msg; save(msg) }
    private fun save(outcome: String) {
        val run = AgentTools.Run(runId, workflow.value.name.lowercase(), subject.value, startedAt, log.toList(), outcome)
        audit.append(run); history.removeAll { it.id == runId }; history.add(0, run)
    }
    private fun reset() { steps.clear(); proposals.clear(); selected.clear(); report.value = null; createdIds.clear(); log.clear(); subject.value = ""; phase.value = Phase.IDLE
        items.clear(); summary.value = null; undoActions.clear(); workflow.value = Workflow.REVIEW }
}
