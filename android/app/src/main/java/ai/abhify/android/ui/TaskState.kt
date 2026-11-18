package ai.abhify.android.ui

import ai.abhify.android.chat.addCards
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.docById
import ai.abhify.android.chat.docText
import ai.abhify.android.chat.workspaceDocIds
import ai.abhify.android.news.NewsAi
import ai.abhify.android.news.NewsNormalizer
import ai.abhify.android.task.SourceRef
import ai.abhify.android.task.Task
import ai.abhify.android.task.TaskError
import ai.abhify.android.task.TaskRunner
import ai.abhify.android.task.TaskSource
import ai.abhify.android.task.TaskStatus
import ai.abhify.android.task.TaskStore
import ai.abhify.android.task.Template
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Task Runner state: connects the pure engine (task/TaskRunner.kt) to the app's existing pieces:
 * sources (documents, Knowledge entries, the News Center, pasted text), AI (vm.aiTask, one at a time, your AI mode and
 * phone model, consent before anything goes online), storage (a JSON file), and two explicit saves:
 * a result to Knowledge, and flashcards to a deck. Never personal memory or chat history.
 */
class TaskState(private val vm: AppViewModel) {
    private val g = vm.g
    private val store = TaskStore(File(g.context.filesDir, "tasks.json"))
    val tasks = mutableStateListOf<Task>().apply { addAll(store.load().reversed()) }
    val current = mutableStateOf<Task?>(null)
    val message = mutableStateOf<String?>(null)
    /** Set when a project opens the Task Runner: the new-task form starts with that project's documents as the source. */
    val preset = mutableStateOf<TaskSource?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    private fun update(t: Task) {
        val i = tasks.indexOfFirst { it.id == t.id }
        // 5.4 optional spoken notification: only the fact, never the task content; only in the foreground, outside quiet hours
        val before = tasks.getOrNull(i)?.status
        if (before == ai.abhify.android.task.TaskStatus.RUNNING && (t.status == ai.abhify.android.task.TaskStatus.COMPLETED || t.status == ai.abhify.android.task.TaskStatus.FAILED))
            vm.spokenNotice(if (t.status == ai.abhify.android.task.TaskStatus.COMPLETED) "Your AI task has finished." else "An AI task couldn't finish.")
        if (i >= 0) tasks[i] = t else tasks.add(0, t)
        if (current.value?.id == t.id || current.value == null) current.value = t
        store.save(tasks.reversed())
    }

    fun create(template: Template, request: String, source: TaskSource, options: Map<String, String> = emptyMap(), projectId: Long? = null) {
        message.value = null
        if (running) { message.value = "A task is already running. Wait for it or cancel it."; return }
        val model = ai.abhify.android.ai.ModelChoices.chipLabel(vm.modelInputs())
        val t = try { TaskRunner.create(template, request, source, model, options = options, projectId = projectId) } catch (e: TaskError) { message.value = e.message; return }
        current.value = t; update(t); start(t)
    }

    /** The answer to "Send … online?" for the run in progress: asked at the first AI step, reused by the later ones. */
    private var consentDecision: AppViewModel.Decision? = null

    fun start(t: Task) {
        if (running) { message.value = "A task is already running."; return }
        if (vm.taskBusy.value != null || vm.generating.value) { message.value = "The AI is busy with another answer. Try again when it finishes."; return }
        current.value = t; consentDecision = null
        job = vm.viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val end = runCatching { TaskRunner.run(t, ::loadSource, ::callModel, ::update) }
            android.util.Log.i("AbhifyTask", "Task ${t.id} (${t.type}) ended ${end.getOrNull()?.status ?: "cancelled"} in ${System.currentTimeMillis() - t0} ms")   // no content
        }
    }

    fun cancel() { job?.cancel(); vm.cancelTask() }
    fun retry(t: Task, step: String? = null) = start(TaskRunner.retry(t, step).also(::update))
    fun open(t: Task?) { current.value = t }
    fun delete(t: Task) { tasks.removeAll { it.id == t.id }; if (current.value?.id == t.id) current.value = null; store.save(tasks.reversed()) }

    /** One AI call through the app's existing path; waits for its answer. Fails clearly instead of switching model. */
    private suspend fun callModel(system: String, device: String, online: String): String = suspendCancellableCoroutine { cont ->
        if (vm.taskBusy.value != null || vm.generating.value) { cont.resumeWithException(TaskError("The AI is busy with another answer.")); return@suspendCancellableCoroutine }
        val sensitive = when (current.value?.source?.type) { "document" -> "the document"; "knowledge" -> "the Knowledge entry"; "text" -> "your text"
            "project" -> "the project's documents"; else -> null }
        vm.aiTask("Task: ${current.value?.title ?: "AI task"}", system, device, sensitive, maxDeviceChars = 4_200, userOnline = online,
            decided = consentDecision, onDecided = { consentDecision = it }) { r ->
            if (!cont.isActive) return@aiTask
            if (r.error != null) cont.resumeWithException(TaskError(r.error)) else cont.resume(r.text)
        }
        cont.invokeOnCancellation { vm.cancelTask() }
    }

    /** Existing sources only; every reference is the real item it came from. */
    private suspend fun loadSource(s: TaskSource): Pair<String, List<SourceRef>> = when (s.type) {
        "text" -> if (s.text.isBlank()) throw TaskError("The pasted text is empty.") else s.text to listOf(SourceRef("text", s.label.ifBlank { "Pasted text" }))
        "document" -> withContext(Dispatchers.IO) {
            val d = s.id?.let { g.db.docById(it) } ?: throw TaskError("That document isn't in your library any more.")   // top-level or in a workspace
            val text = g.db.docText(d.id) ?: ""
            if (text.isBlank()) throw TaskError("\"${d.name}\" has no readable text.")
            text to listOf(SourceRef("document", d.name, id = d.id.toString()))
        }
        "project" -> withContext(Dispatchers.IO) {                    // a workspace's own and linked documents, each with a fair share
            val ids = s.id?.let { g.db.workspaceDocIds(it) }.orEmpty()
            if (ids.isEmpty()) throw TaskError("The project \"${s.label}\" has no documents yet. Add or link documents to it first.")
            val share = TaskRunner.ONLINE_SOURCE_CHARS / ids.size
            val parts = ids.mapNotNull { id -> g.db.docById(id)?.let { d -> d to (g.db.docText(id) ?: "") } }.filter { it.second.isNotBlank() }
            if (parts.isEmpty()) throw TaskError("The documents of \"${s.label}\" have no readable text.")
            parts.joinToString("\n\n") { (d, t) -> "## ${d.name}\n${t.take(share)}" } to parts.map { (d, _) -> SourceRef("document", d.name, id = d.id.toString()) }
        }
        "knowledge" -> {
            val item = vm.life.vault.firstOrNull { it.id == s.id } ?: throw TaskError("That Knowledge entry isn't available any more.")
            if (item.body.isBlank()) throw TaskError("\"${item.title}\" is empty.")
            "${item.title}\n\n${item.body}" to listOf(SourceRef("knowledge", item.title, id = item.id.toString()))
        }
        "news" -> {
            val prefs = vm.newsCenter.prefs.value
            val results = s.categories.ifEmpty { listOf("foryou") }.map { g.newsCenter.load(it, prefs) }
            val stories = NewsNormalizer.merge(results.map { it.articles }, prefs, limit = 15)
            if (stories.isEmpty()) throw TaskError(results.firstNotNullOfOrNull { it.problem } ?: "No current stories could be retrieved (check the internet connection).")
            NewsAi.articleBlock(stories) to stories.map { SourceRef("news", it.title, it.publisher, it.url, it.id) }
        }
        else -> throw TaskError("Unknown source.")
    }

    /** Explicit save of one result to Knowledge (with its sources), after the user confirmed. Not personal memory. */
    fun saveToKnowledge(t: Task, stepId: String) {
        val s = t.steps.first { it.id == stepId }
        val refs = t.refs.joinToString("\n") { "- ${it.label}" + (it.url?.let { u -> " (${it.publisher}) $u" } ?: "") }
        vm.life.save("task", "${t.title}: ${s.title}".take(200), "${s.result}\n\nSources:\n$refs", "AI answers", listOf("task", t.type), "task", null)
        message.value = "Saved to Knowledge."
    }

    /** Explicit: the flashcards step's cards into a deck (existing Study cards), after the user confirmed. */
    fun addCards(t: Task, cards: List<Pair<String, String>>) {
        val deck = t.refs.firstOrNull()?.label?.take(40) ?: t.title.take(40)
        vm.viewModelScope.launch(Dispatchers.IO) { g.db.addCards(deck, cards, "task:${t.id}") }
        message.value = "Added ${cards.size} cards to the deck \"$deck\" (Study → Cards)."
    }

    val sourceDocs get() = runCatching { g.db.allDocs() }.getOrDefault(emptyList())
    fun isActive(t: Task) = t.status == TaskStatus.RUNNING
}
