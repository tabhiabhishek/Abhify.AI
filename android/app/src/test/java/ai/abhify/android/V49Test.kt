package ai.abhify.android

import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.task.SourceRef
import ai.abhify.android.task.TaskRunner
import ai.abhify.android.task.TaskSource
import ai.abhify.android.task.TaskStatus
import ai.abhify.android.task.TaskStore
import ai.abhify.android.task.Template
import ai.abhify.android.workspace.ProjectMeta
import ai.abhify.android.workspace.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()
private fun fn(file: String, name: String) = src(file).substringAfter("fun $name(").substringBefore("\n    fun ").substringBefore("\n}\n")

/** Stability milestone: New private chat crash (Cancel before the AI engine's library is loaded). */
class PrivateChatCrashTest {
    @Test fun cancellingBeforeTheNativeLibraryIsLoadedIsSafe() {
        // the root cause, reproduced: the JNI method without its library throws UnsatisfiedLinkError (an Error: uncaught, the app dies)
        assertTrue(runCatching { ai.abhify.android.llm.LlamaBridge.nativeCancel() }.exceptionOrNull() is UnsatisfiedLinkError)
        val e = LlmEngine("/nowhere")
        assertFalse(e.nativeReady)
        e.cancel(); e.cancel()                            // New chat, New private chat, Cancel: used to crash the app here
        assertNull(e.loaded)
    }

    @Test fun everyCancelPathGoesThroughTheGuardedEngineCall() {
        val engine = src("llm/LlmEngine.kt")
        assertTrue(engine.contains("fun cancel() { if (nativeReady) LlamaBridge.nativeCancel() }"))
        assertTrue(engine.contains("System.loadLibrary(\"abhify_llm\")\n            nativeReady = true"))      // set only after a successful load
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("fun setPrivate(on: Boolean) { newChat(); privateChat.value = on }"))
        assertTrue(vm.contains("fun stop() { genJob?.cancel(); g.engine.cancel() }") && vm.contains("fun cancelTask() { taskJob?.cancel(); g.engine.cancel(); taskBusy.value = null }"))
        // nothing outside the engine calls the JNI bridge directly
        File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "LlmEngine.kt" }.forEach {
            assertFalse(it.name, it.readText().contains("LlamaBridge."))
        }
    }

    @Test fun privateChatStartsEmptyAndStoresNothing() {
        val newChat = src("ui/AppViewModel.kt").substringAfter("fun newChat() {").substringBefore("\n")
        for (s in listOf("stop()", "privateChat.value = false", "conversationId.value = null", "remove(\"conversation\")", "messages.clear()", "tab.value = Tab.CHAT"))
            assertTrue(s, newChat.contains(s))
        val send = src("ui/AppViewModel.kt")
        assertTrue(send.contains("if (privateChat.value) withContext(Dispatchers.IO) {\n                    // private: the history is what is on screen; nothing is stored"))
        assertTrue(src("ui/V3Screens.kt").contains("NavRow(\"🕶\", \"New private chat\", \"Not saved, not synced, no new memories\") { vm.setPrivate(true)"))
    }
}

/** Stability milestone: Document summary / study plan / quiz on the phone. */
class TaskRunnerFixTest {
    @get:Rule val tmp = TemporaryFolder()
    private val doc = "The Calvin cycle takes place in the stroma. RuBisCO fixes carbon dioxide onto RuBP. G3P is used to build glucose, sucrose and starch. " +
        "Above about 35 degrees Celsius enzymes such as RuBisCO begin to denature and the rate falls."

    /** The exact llama3.2 output that failed the desktop quiz live (unquoted "explanation" strings). */
    private val llamaOutput = """[
  {
    "q": "What is the location of the Calvin cycle?",
    "options": ["chloroplast", "stroma", "mitochondria", "nucleus"],
    "answer": 1,
    "explanation": The Calvin cycle takes place in the stroma of chloroplasts.
    "quote": "The Calvin cycle takes place in the stroma."
  },
  {
    "q": "What is used to build glucose, sucrose and starch?",
    "options": ["G3P", "RuBP", "ATP", "NADPH"],
    "answer": 0,
    "explanation": G3P is used to build glucose, sucrose and starch.
    "quote": "G3P is used to build glucose, sucrose and starch."
  }
]"""

    @Test fun malformedJsonFromASmallModelStillGivesTheQuiz() {
        val q = TaskRunner.parseQuiz(llamaOutput, doc)!!
        assertEquals(2, q.size); assertEquals(1, q[0].answer); assertEquals(0, q[1].answer)
        assertTrue(q[0].explanation.startsWith("The Calvin cycle takes place") && q.all { it.quoteFound })
    }

    @Test fun commonModelVariantsAreRead() {
        fun one(s: String) = TaskRunner.parseQuiz(s, doc)?.map { it.answer }
        assertEquals(listOf(1), one("```json\n[{\"q\":\"Where?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"answer\":\"B\",\"explanation\":\"x\",\"quote\":\"stroma\"}]\n```"))
        assertEquals(listOf(2), one("[{\"q\":\"Where?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"answer\":\"2\",\"explanation\":\"x\",},]"))
        assertEquals(listOf(3), one("{\"questions\":[{\"q\":\"Where?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"answer\":3}]}"))
        assertEquals(listOf(0), one("[{\"question\":\"Where?\",\"options\":[\"Stroma\",\"b\",\"c\",\"d\"],\"answer\":\"stroma\"}]"))   // answer as the option text
        assertEquals(listOf(0), one("[{\"q\":\"One?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"answer\":0}, {\"q\":\"Two?\",\"options\":[\"a\""))   // cut off: keeps the complete one
        assertNull(one("[{\"q\":\"Where?\",\"options\":[\"a\",\"b\"],\"answer\":7}]"))
        assertNull(one("Sorry, I can't."))
    }

    @Test fun thePhoneLineFormatIsRead() {
        val lines = "1. Q: Where does the Calvin cycle happen?\nA) Nucleus\nB) Stroma\nC) Cytoplasm\nD) Membrane\nAnswer: B\nWhy: It is in the stroma.\nQuote: \"The Calvin cycle takes place in the stroma.\"\n" +
            "Q: What does RuBisCO fix?\nA) Oxygen\nB) Water\nC) Carbon dioxide\nD) Glucose\nAnswer: C) Carbon dioxide\nWhy: It fixes CO2.\nQuote: RuBisCO fixes carbon dioxide onto RuBP."
        val q = TaskRunner.parseQuiz(lines, doc)!!                         // no blank line between the questions: still two
        assertEquals(listOf(1, 2), q.map { it.answer }); assertTrue(q.all { it.quoteFound }); assertEquals("It is in the stroma.", q[0].explanation)
    }

    @Test fun phoneAndOnlinePromptsDifferAndRespectSettings() {
        val dev = TaskRunner.prompt(Template.QUIZ, "quiz", "", doc.repeat(10), emptyList(), TaskRunner.DEVICE_SOURCE_CHARS, 500, device = true, options = mapOf("count" to "10", "difficulty" to "hard"))
        val online = TaskRunner.prompt(Template.QUIZ, "quiz", "", doc.repeat(10), emptyList(), TaskRunner.ONLINE_SOURCE_CHARS, 2000, options = mapOf("count" to "10", "difficulty" to "hard"))
        assertTrue(dev.contains("Answer: the letter") && !dev.contains("JSON") && dev.startsWith("Write 5 multiple-choice"))   // capped for the phone
        assertTrue(online.contains("JSON array") && online.startsWith("Write 10 multiple-choice") && online.contains("(hard difficulty)"))
        assertEquals(1, TaskRunner.quizCount(5, "short text"))                // never more questions than the source supports
        val plan = TaskRunner.prompt(Template.STUDY, "plan", "", doc, emptyList(), 10_000, 2000, title = "photosynthesis.pdf")
        for (s in listOf("\"photosynthesis.pdf\"", "Learning objectives", "Study sessions", "Revision", "Practice tasks", "Progress checklist")) assertTrue(s, plan.contains(s))
    }

    @Test fun tooShortSourcesFailClearlyWithoutCallingTheModel() {
        var calls = 0
        val t = runBlocking { TaskRunner.run(TaskRunner.create(Template.QUIZ, "", TaskSource("text", text = "Too short."), "m"),
            { "Too short." to listOf(SourceRef("text", "Pasted text")) }, { _, _, _ -> calls++; "x" }, {}) }
        assertEquals(TaskStatus.FAILED, t.status); assertEquals(0, calls); assertTrue(t.error!!.contains("too short"))
    }

    @Test fun quizSettingsAndProjectAreKeptWithTheTask() {
        val t = TaskRunner.create(Template.QUIZ, "", TaskSource("project", 4, "Biology"), "m", options = mapOf("count" to "3", "difficulty" to "easy"), projectId = 4)
        assertEquals(mapOf("count" to "3", "difficulty" to "easy"), t.options); assertEquals(4L, t.projectId)
        assertTrue(runCatching { TaskRunner.create(Template.QUIZ, "", TaskSource("text", text = "x"), "m", options = mapOf("count" to "7")) }.isFailure)
        val store = TaskStore(tmp.newFile("tasks.json")); store.save(listOf(t))
        assertEquals(t, store.load()[0])
        // a task saved by 4.6.1 (no options / projectId) still loads
        val old = TaskStore.toJson(t).apply { remove("options"); remove("projectId") }
        File(tmp.root, "old.json").writeText("[$old]")
        assertEquals(emptyMap<String, String>(), TaskStore(File(tmp.root, "old.json")).load()[0].options)
        assertTrue(TaskRunner.markdown(t).startsWith("# Quiz from document"))
    }

    @Test fun consentIsAskedOncePerRunAndDocumentsAreFoundEverywhere() {
        val state = src("ui/TaskState.kt")
        assertTrue(state.contains("decided = consentDecision, onDecided = { consentDecision = it }"))
        assertTrue(state.contains("current.value = t; consentDecision = null"))                      // a new run asks again
        assertTrue(state.contains("g.db.docById(it)") && state.contains("g.db.allDocs()") && !state.contains("g.db.docs()"))
        val ai = src("ui/AppViewModel.kt").substringAfter("fun aiTask(").substringBefore("private data class Quad")
        assertTrue(ai.contains("decided != null && decided != Decision.CANCEL) start(decided == Decision.DEVICE)"))
        assertTrue(ai.contains("if (d != Decision.CANCEL) onDecided(d)"))
        assertTrue(ai.contains("onDone(TaskResult(\"\", null, null, \"The AI is busy with another answer.\")); return"))   // never a silent hang
    }
}

/** Stability milestone: AI and System navigation moved from More into Settings. */
class NavigationSettingsTest {
    private val more = src("ui/V3Screens.kt").substringAfter("fun SettingsTab(").substringBefore("\n}\n")
    private val settings = src("ui/ManageScreens.kt").substringAfter("fun SettingsScreen(").substringBefore("\n}\n")

    /** 5.3 (owner-approved five destinations): the AI and System *screens* open from More (Knowledge, AI & System groups);
     *  Settings keeps the settings themselves. Still exactly one entry per destination. */
    @Test fun aiAndSystemScreensOpenFromMoreAndSettingsKeepsTheSettings() {
        for (r in listOf("MODELS", "PROVIDERS", "MEMORIES", "SEMANTIC", "CONNECTION", "SYNC")) {
            assertTrue("More has $r", more.contains("Route.$r)"))
            assertFalse("Settings no longer has $r", settings.contains("Route.$r)"))
        }
        assertTrue(more.contains("rerunSetup()")); assertFalse(settings.contains("rerunSetup()"))
        for (s in listOf("MemoryProfileCard(vm)", "Copy diagnostics (no keys)")) { assertTrue(s, settings.contains(s)); assertFalse(s, more.contains(s)) }
        for (g in listOf("\"AI\"", "\"Performance\"", "\"Appearance\"", "\"Diagnostics\"")) assertTrue(g, settings.contains("SettingsGroup($g"))
        assertTrue(settings.contains("VoiceSettingsGroup(vm)"))                                   // 5.4: Voice & Conversation (was Voice & audio)
        for (g in listOf("\"Knowledge\"", "\"Workspace\"", "\"Career\"", "\"AI & System\"", "\"Settings & Privacy\"")) assertTrue(g, more.contains("SettingsGroup($g"))
        // every route in More appears once
        Regex("""Route\.([A-Z]+)\)""").findAll(more).map { it.groupValues[1] }.groupingBy { it }.eachCount().forEach { (r, n) -> assertEquals("Route.$r once", 1, n) }
    }

    @Test fun moreKeepsItsOwnEntriesAndReachesSettingsAccountAbout() {
        for (s in listOf("Route.TASKS", "vm.projects.openList()", "Tab.DOCS", "Tab.CAREER", "Tab.KNOWLEDGE", "Route.HISTORY", "Route.SEARCH", "New private chat",
            "Route.DRIVE", "Route.BACKUP", "Route.STORAGE", "Route.PRIVACY", "Route.SETTINGS", "Route.ACCOUNT", "Route.ABOUT", "Run OCR self-test")) assertTrue(s, more.contains(s))
    }

    @Test fun launcherFollowsTheNewHomeOfSystemAndKeepsMore() {
        val sheet = src("ui/Aurora.kt").substringAfter("fun BrandLauncherSheet(").substringBefore("\n}\n")
        assertTrue(sheet.contains("LaunchItem(\"🖥\", \"System (in Settings)\", Accent.SYSTEM, route = AppViewModel.Route.SETTINGS)"))
        assertTrue(sheet.contains("tab = AppViewModel.Tab.MORE") && sheet.contains("route = AppViewModel.Route.PROJECTS"))
        assertTrue(src("ui/MainActivity.kt").contains("AppViewModel.Route.PROJECTS -> ProjectsScreen(vm)"))
    }
}

/** Stability milestone: News Listen (Android text-to-speech, reusing the briefing on screen). */
class NewsAudioTest {
    @Test fun briefingBecomesOnePartPerItemWithoutCitationsOrLinks() {
        val p = ai.abhify.android.voice.Voice.newsParts("Briefing from 3 stories", "- Markets rose after the **rate** decision [1].\n- A storm hit the coast [2, 3]. See https://x.y/z\n- Science news [3].")
        assertEquals(listOf("Briefing from 3 stories", "Markets rose after the rate decision.", "A storm hit the coast. See", "Science news."), p)
        assertTrue(ai.abhify.android.voice.Voice.newsParts("T", "a ".repeat(600)).all { it.length <= ai.abhify.android.voice.Voice.MAX_PART })
        assertEquals(listOf("T"), ai.abhify.android.voice.Voice.newsParts("T", "  \n\n "))
    }

    @Test fun listenReusesTheTextAndPlaybackIsSingleAndScoped() {
        val vm = src("ui/AppViewModel.kt").substringAfter("fun listenNews(").substringBefore("\n    fun skipSpeech")
        assertTrue(vm.contains("Voice.newsParts(title, text)") && vm.contains("voice.speakParts(parts, \"news\")") && !vm.contains("aiTask("))
        val voice = src("voice/Voice.kt")
        assertTrue(voice.contains("if (t != null) return                                          // still initialising"))   // no second engine
        assertTrue(voice.contains("TextToSpeech.QUEUE_FLUSH"))                                                                  // a new start replaces the old
        val screen = src("ui/NewsScreens.kt")
        assertTrue(screen.contains("onDispose { if (vm.speechOwner.value == \"news\") vm.stopSpeaking() }"))
        assertTrue(screen.contains("vm.listenNews(out.title, out.text)") && screen.contains("vm.skipSpeech(-1)") && screen.contains("vm.setSpeechRate(r)"))
        assertTrue(src("ui/NewsState.kt").contains("if (vm.speechOwner.value == \"news\") vm.stopSpeaking()          // new content"))
    }
}

/** Project Workspace (Android): extra project data next to the existing workspace tables (no schema change). */
class ProjectStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun metaRoundTripsAndDefaultsKeepExistingBehaviour() {
        val s = ProjectStore(File(tmp.root, "projects.json"))
        assertEquals(emptyMap<Long, ProjectMeta>(), s.load())
        assertTrue(ProjectMeta().useMemory && !ProjectMeta().archived)          // workspace chats used memories before: unchanged by default
        s.save(mapOf(3L to ProjectMeta("Answer in German.", archived = true, useMemory = false, updatedAt = 5)))
        assertEquals(ProjectMeta("Answer in German.", true, false, 5), s.load()[3L])
        File(tmp.root, "projects.json").writeText("{\"9\":{\"instructions\":\"x\"}}")
        assertTrue(s.load()[9L]!!.useMemory)
    }

    @Test fun instructionsAreBoundedAndMarkedAsUserPreferences() {
        assertEquals("", ProjectStore.instructionBlock("P", "  "))
        val b = ProjectStore.instructionBlock("Thesis", "x".repeat(5_000))
        assertTrue(b.startsWith("Project \"Thesis\" instructions from the user (follow them unless they conflict with your rules)"))
        assertTrue(b.length < ProjectStore.MAX_INSTRUCTIONS + 120)
    }

    @Test fun exportListsEverythingAndDeleteKeepsDocuments() {
        val md = ProjectStore.markdown("Thesis", ProjectMeta("Be brief."), listOf("chapter1.pdf"), listOf("Idea" to "Use RuBisCO data"), listOf("Read" to true), listOf("chat" to "Lab chat"))
        for (s in listOf("# Thesis", "## Instructions", "- chapter1.pdf", "### Idea", "- [x] Read", "- chat: Lab chat")) assertTrue(s, md.contains(s))
        val del = src("chat/StudioDb.kt").substringAfter("fun ChatDb.deleteWorkspaceKeepDocs(").substringBefore("\n}\n")
        assertTrue(del.indexOf("UPDATE library_docs SET workspace_id = NULL") in 0 until del.indexOf("deleteWorkspace(id)"))
        val state = src("ui/ProjectState.kt")
        assertTrue(state.contains("g.db.deleteWorkspaceKeepDocs(w.id)") && !state.contains("g.db.deleteWorkspace(w.id)"))
    }

    @Test fun projectChatAndTasksStayInsideTheProject() {
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("val mems = if (project?.useMemory == false) emptyList() else memories"))
        assertTrue(vm.contains("ProjectStore.instructionBlock(it.name, project!!.instructions)"))
        val state = src("ui/TaskState.kt").substringAfter("\"project\" -> withContext").substringBefore("else -> throw")
        assertTrue(state.contains("g.db.workspaceDocIds(it)") && !state.contains("allDocs"))        // only this project's documents
        assertNotNull(Template.QUIZ.sources.firstOrNull { it == "project" })
        assertFalse("project" in Template.NEWS.sources)
    }
}
