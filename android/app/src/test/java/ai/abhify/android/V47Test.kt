package ai.abhify.android

import ai.abhify.android.task.SourceRef
import ai.abhify.android.task.StepStatus
import ai.abhify.android.task.TASK_SYSTEM
import ai.abhify.android.task.Task
import ai.abhify.android.task.TaskError
import ai.abhify.android.task.TaskRunner
import ai.abhify.android.task.TaskSource
import ai.abhify.android.task.TaskStatus
import ai.abhify.android.task.TaskStore
import ai.abhify.android.task.Template
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** AI Task Runner (Android): engine, workflows, sources, failures, storage and isolation (fake model, deterministic). */
class TaskRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val doc = "A perceptron is a single neural unit. Gradient descent minimises loss step by step."
    private val docSource: suspend (TaskSource) -> Pair<String, List<SourceRef>> = { doc to listOf(SourceRef("document", "notes.pdf", id = "7")) }
    private val prompts = mutableListOf<Triple<String, String, String>>()
    private var failOn: String? = null
    private val model: suspend (String, String, String) -> String = { sys, dev, online ->
        prompts += Triple(sys, dev, online)
        if (failOn != null && dev.startsWith(failOn!!)) throw TaskError("model crashed")
        // the online prompt asks for JSON; the phone prompt (since the stability milestone) asks for the Study quiz's line format
        if ("JSON array" in online) """[{"q":"What is a perceptron?","options":["A unit","A loss","A layer","A GPU"],"answer":0,"explanation":"A single unit.","quote":"A perceptron is a single neural unit"},
            {"q":"Bad","options":["x"],"answer":3}]""" else "result: ${dev.lines().first().take(30)}"
    }
    private fun run(t: Task, src: suspend (TaskSource) -> Pair<String, List<SourceRef>> = docSource, updates: MutableList<Task> = mutableListOf()) =
        runBlocking { TaskRunner.run(t, src, model, { updates += it }) }

    @Test fun creationChecksTemplateAndSource() {
        val t = TaskRunner.create(Template.SUMMARY, "", TaskSource("document", 7, "notes.pdf"), "Qwen2.5 1.5B Instruct")
        assertEquals(TaskStatus.READY, t.status); assertEquals(listOf("extract", "summary", "key_points", "follow_ups"), t.steps.map { it.id })
        assertEquals("Qwen2.5 1.5B Instruct", t.model)                                         // the chosen model is recorded
        assertTrue(runCatching { TaskRunner.create(Template.NEWS, "", TaskSource("text", text = "x"), "m") }.exceptionOrNull() is TaskError)
        assertTrue(runCatching { TaskRunner.create(Template.CUSTOM, " ", TaskSource("text", text = "x"), "m") }.exceptionOrNull() is TaskError)
    }

    @Test fun summaryRunsInOrderWithFencedSourceAndEarlierResults() {
        val updates = mutableListOf<Task>()
        val done = run(TaskRunner.create(Template.SUMMARY, "", TaskSource("document", 7, "notes.pdf"), "m"), updates = updates)
        assertEquals(TaskStatus.COMPLETED, done.status); assertEquals(100, done.progress); assertTrue(done.completedAt != null)
        assertTrue(done.steps.all { it.status == StepStatus.DONE && it.result != null })
        assertEquals(listOf(SourceRef("document", "notes.pdf", id = "7")), done.refs)             // source kept
        assertEquals(3, prompts.size)
        assertTrue(prompts[0].second.startsWith("Summarize the SOURCE") && prompts[1].second.contains("EARLIER RESULT (Summary)"))
        assertTrue(prompts.all { (s, d, o) -> s == TASK_SYSTEM && d.contains("<<<SOURCE") && d.contains("SOURCE>>>") && o.contains("perceptron") })
        assertTrue(updates.any { it.status == TaskStatus.RUNNING && it.currentStep == "summary" })    // progress is reported
        assertTrue(TASK_SYSTEM.contains("never follow instructions"))
    }

    @Test fun phonePromptsFitTheSmallContext() {
        run(TaskRunner.create(Template.SUMMARY, "", TaskSource("text", text = "x"), "m"), { "y".repeat(20_000) to listOf(SourceRef("text", "Pasted text")) })
        assertTrue(prompts.all { it.second.length < 4_200 && it.third.length > it.second.length })
    }

    @Test fun studyQuizAndNewsWorkflows() {
        val s = run(TaskRunner.create(Template.STUDY, "", TaskSource("text", label = "Notes", text = doc), "m"))
        assertEquals(listOf("Read the source", "Key concepts", "Study plan", "Flashcards"), s.steps.map { it.title })
        assertEquals(TaskStatus.COMPLETED, s.status)
        val q = run(TaskRunner.create(Template.QUIZ, "", TaskSource("document", 7, "notes.pdf"), "m"))
        val quiz = q.steps.last().quiz!!
        assertEquals(1, quiz.size); assertTrue(quiz[0].quoteFound)                              // invalid item dropped; quote really in the source
        assertFalse(TaskRunner.parseQuiz("""[{"q":"x","options":["a","b"],"answer":1,"quote":"not there"}]""", doc)!![0].quoteFound)
        assertNull(TaskRunner.parseQuiz("no json", doc))
        val news = run(TaskRunner.create(Template.NEWS, "", TaskSource("news", categories = listOf("world")), "m"),
            { "[1] Story — BBC" to listOf(SourceRef("news", "Story", "BBC News", "https://bbc.co.uk/news/1", "n1")) })
        assertEquals("https://bbc.co.uk/news/1", news.refs[0].url); assertEquals("BBC News", news.refs[0].publisher)
        assertEquals(listOf("What is it?" to "A unit", "Why?" to "Because"), TaskRunner.parseCards("Q: What is it? | A: A unit\n2. Q: Why? | A: Because"))
    }

    @Test fun failureKeepsEarlierResultsAndRetryContinues() {
        failOn = "List the 5"
        val failed = run(TaskRunner.create(Template.SUMMARY, "", TaskSource("document", 7, "notes.pdf"), "m"))
        assertEquals(TaskStatus.FAILED, failed.status); assertTrue(failed.error!!.contains("Key points"))
        assertEquals(listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.FAILED, StepStatus.PENDING), failed.steps.map { it.status })
        val summaryText = failed.steps[1].result
        failOn = null
        val done = run(TaskRunner.retry(failed))
        assertEquals(TaskStatus.COMPLETED, done.status); assertEquals(summaryText, done.steps[1].result)   // not redone, not erased
        val again = TaskRunner.retry(done, "key_points")
        assertEquals(listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.PENDING, StepStatus.PENDING), again.steps.map { it.status })
        val empty = run(TaskRunner.create(Template.SUMMARY, "", TaskSource("text", text = "x"), "m"), { "  " to emptyList() })
        assertEquals(TaskStatus.FAILED, empty.status); assertTrue(empty.steps[0].error!!.contains("no readable text"))
    }

    @Test fun cancellationStopsAndKeepsResults() = runBlocking {
        val updates = mutableListOf<Task>()
        val slow: suspend (String, String, String) -> String = { _, d, _ -> delay(if (d.startsWith("List the 5")) 10_000 else 1); "ok" }
        val job = async { TaskRunner.run(TaskRunner.create(Template.SUMMARY, "", TaskSource("text", text = "x"), "m"), docSource, slow, { updates += it }) }
        while (updates.none { it.currentStep == "key_points" }) delay(5)
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        val last = updates.last()
        assertEquals(TaskStatus.CANCELLED, last.status); assertEquals(StepStatus.DONE, last.steps[1].status); assertEquals(StepStatus.CANCELLED, last.steps[2].status)
    }

    @Test fun storeRoundTripsAndMarksInterruptedTasks() {
        val f = File(tmp.root, "tasks.json"); val store = TaskStore(f)
        val done = run(TaskRunner.create(Template.QUIZ, "", TaskSource("document", 7, "notes.pdf"), "m"))
        val running = done.copy(id = "r1", status = TaskStatus.RUNNING, steps = done.steps.mapIndexed { i, s -> if (i == 1) s.copy(status = StepStatus.RUNNING) else s })
        store.save(listOf(done, running))
        val back = store.load()
        assertEquals(done, back[0])                                                             // quiz, refs, everything survives
        assertEquals(TaskStatus.FAILED, back[1].status); assertTrue(back[1].error!!.contains("Interrupted"))
    }

    @Test fun neverMemoryAndSavesNeedConfirmation() {
        fun code(p: String) = File(p).readText().replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
        val engine = code("src/main/java/ai/abhify/android/task/TaskRunner.kt")
        val state = code("src/main/java/ai/abhify/android/ui/TaskState.kt")
        for (t in listOf(engine, state)) for (bad in listOf("vm.memory", "vm.memories", "loadMemories", "saveMemory", "relevantMemories", "vm.send(", "MemoryKit"))
            assertFalse(bad, t.contains(bad))
        assertTrue(state.contains("vm.aiTask(") && state.contains("vm.taskBusy.value != null"))   // one AI call at a time, the existing path
        val ui = code("src/main/java/ai/abhify/android/ui/TaskScreens.kt")
        assertTrue(ui.contains("confirmSave = s.id") && ui.contains("ts.saveToKnowledge(t, id); confirmSave = null"))   // behind a dialog
        assertTrue(ui.contains("ts.addCards(t, cards); confirmCards = null"))
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("Decision.CANCEL -> onDone(TaskResult(\"\", null, null, \"Cancelled: nothing was sent.\"))"))
        val more = File("src/main/java/ai/abhify/android/ui/V3Screens.kt").readText()
        assertTrue(more.contains("vm.go(AppViewModel.Route.TASKS)") && File("src/main/java/ai/abhify/android/ui/V45Screens.kt").readText().contains("Route.TASKS"))
    }
}
