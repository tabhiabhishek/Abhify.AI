package ai.abhify.android

import ai.abhify.android.ui.OrbState
import ai.abhify.android.voice.AppActions
import ai.abhify.android.voice.VoiceSettings
import ai.abhify.android.voice.VoiceSettings.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()

/** 5.4 Voice & Conversation settings. */
class VoiceSettingsTest {
    @Test fun defaultsAreTheOwnersDecisions() {
        val d = VoiceSettings()
        assertNull(d.voiceName)                                                              // automatic = female-marked voice where available
        assertEquals(VoiceSettings.Tone.WARM, d.tone); assertEquals(VoiceSettings.Speed.NATURAL, d.speed); assertEquals(1.0f, d.rate)
        assertEquals(Language.BOTH, d.language); assertEquals(VoiceSettings.ResponseMode.SPOKEN_AND_TEXT, d.responseMode)
        assertTrue(d.interruptions && d.enabled && d.autoListen && d.screenHelp); assertFalse(d.spokenNotifications)
    }

    @Test fun settingsPersistAndDamagedDataFallsBackSafely() {
        val s = VoiceSettings(voiceName = "hi-in-x-hid-local", tone = VoiceSettings.Tone.CALM, speed = VoiceSettings.Speed.CUSTOM, customRate = 1.4f,
            language = Language.HINDI, responseMode = VoiceSettings.ResponseMode.VOICE_ONLY, interruptions = false, spokenNotifications = true, quietFrom = 23, quietTo = 6)
        assertEquals(s, VoiceSettings.fromJson(s.toJson()))
        assertEquals(VoiceSettings(), VoiceSettings.fromJson("{not json"))
        assertEquals(VoiceSettings(), VoiceSettings.fromJson("""{"tone":"SHOUTY"}"""))      // unknown values → defaults
        assertEquals(1.25f, VoiceSettings.fromJson(null, legacyRate = 1.25f).rate)           // the old reading speed is kept
        assertEquals(2.0f, VoiceSettings(speed = VoiceSettings.Speed.CUSTOM, customRate = 9f).rate)
        assertFalse(VoiceSettings(responseMode = VoiceSettings.ResponseMode.TEXT_ONLY).speaks)
    }

    @Test fun languageControlsRecognitionReplyAndSpeech() {
        assertEquals("hi-IN", VoiceSettings(language = Language.HINDI).recognitionTag("en-US"))
        assertEquals("en-IN", VoiceSettings(language = Language.ENGLISH).recognitionTag("hi-IN"))
        assertEquals("en-US", VoiceSettings(language = Language.AUTO).recognitionTag("en-US"))
        assertTrue(VoiceSettings(language = Language.HINDI).conversationInstruction().contains("Reply in Hindi"))
        assertTrue(VoiceSettings().conversationInstruction().contains("the language the user spoke"))
        assertTrue(VoiceSettings(tone = VoiceSettings.Tone.PROFESSIONAL).conversationInstruction().contains("professional"))
    }

    @Test fun quietHoursWrapPastMidnight() {
        val s = VoiceSettings(quietFrom = 22, quietTo = 7)
        assertTrue(s.isQuiet(23) && s.isQuiet(2) && s.isQuiet(22)); assertFalse(s.isQuiet(7) || s.isQuiet(12))
        assertFalse(VoiceSettings(quietFrom = 5, quietTo = 5).isQuiet(5))
    }

    @Test fun femaleIsTakenOnlyFromTheEnginesOwnLabels() {
        assertTrue(VoiceSettings.marksFemale("en-us-x-sfg#female_1-local", emptySet()))
        assertTrue(VoiceSettings.marksFemale("v1", setOf("gender=female")))
        assertFalse(VoiceSettings.marksFemale("en-us-x-sfg-local", emptySet()))           // a name code alone is never guessed
        assertFalse(VoiceSettings.marksFemale("en-us-x-iom#male_1-local", emptySet()))    // "male" isn't "female"
        assertTrue(VoiceSettings.marksMale("en-us-x-iom#male_1-local", emptySet()))
        data class V(val n: String, val f: Set<String>)
        val list = listOf(V("a-local", emptySet()), V("b#female_2-local", emptySet()))
        assertEquals("b#female_2-local", VoiceSettings.autoVoice(list, { it.n }, { it.f })?.n)
        assertNull(VoiceSettings.autoVoice(listOf(V("a", emptySet())), { it.n }, { it.f }))  // none marked → the engine's default
    }
}

/** 5.4 App Action Registry. */
class AppActionsTest {
    private fun id(s: String) = AppActions.match(s)?.spec?.id

    @Test fun navigationInEnglishAndHindi() {
        mapOf("Open Study" to "open_study", "Hey Abhify, go to news" to "open_news", "please open the knowledge center" to "open_knowledge_center",
            "show my projects" to "open_projects", "open voice settings" to "open_voice_settings", "start a new chat" to "new_chat",
            "open my last conversation" to "open_last_conversation", "go home" to "open_home", "open settings." to "open_settings",
            "न्यूज़ खोलो" to "open_news", "स्टडी खोलो" to "open_study", "नई चैट शुरू करो" to "new_chat", "आज मुझे क्या करना चाहिए" to "recommendations")
            .forEach { (said, want) -> assertEquals(said, want, id(said)) }
    }

    @Test fun readOnlyAndParameterisedActions() {
        assertEquals("recommendations", id("What should I do today?")); assertEquals("project_status", id("what are we working on"))
        assertEquals("model_status", id("show my model status")); assertEquals("study_progress", id("my study progress"))
        assertEquals("screen_help", id("what is this screen"))
        AppActions.match("find my Python notes")!!.let { assertEquals("search", it.spec.id); assertEquals("Python notes", it.arg) }
        AppActions.match("find free courses on machine learning")!!.let { assertEquals("discover", it.spec.id); assertEquals("machine learning", it.arg) }
        AppActions.match("create a task called test the voice panel")!!.let { assertEquals("create_task", it.spec.id); assertEquals("test the voice panel", it.arg) }
        AppActions.match("take a note that the demo is on Friday")!!.let { assertEquals("create_note", it.spec.id); assertEquals("the demo is on Friday", it.arg) }
    }

    @Test fun ordinaryQuestionsStayQuestions() {
        listOf("Find the derivative of x squared", "Can you explain RAG in simple terms?", "What is study material for ML?", "Tell me the news about AI",
            "Explain it with an example", "open source licences explained", "what is the best model for coding?", "delete is a SQL keyword, right?")
            .forEach { assertNull(it, AppActions.match(it)) }
    }

    @Test fun sensitiveRequestsNeverExecuteByVoice() {
        listOf("delete all my conversations", "clear my memories", "erase everything", "export my data", "restore a backup", "turn off online ai", "change privacy settings")
            .forEach { assertEquals(it, "sensitive", id(it)) }
        assertTrue(AppActions.spec("sensitive").needsConfirmation && AppActions.spec("create_task").needsConfirmation)
        assertFalse(AppActions.spec("open_study").needsConfirmation)
        val v = src("ui/VoiceState.kt")
        listOf("deleteAll", "clearConversation", "clearMemories", "deleteConversation", "setOnlineAllowed", "restore(").forEach { assertFalse(it, v.contains(it)) }
        assertTrue(v.contains("AppActions.Kind.SENSITIVE -> { vm.go(AppViewModel.Route.PRIVACY)"))
    }

    @Test fun parametersAreValidated() {
        assertNotNull(AppActions.validate(AppActions.Match(AppActions.spec("create_task"), "")))
        assertNotNull(AppActions.validate(AppActions.Match(AppActions.spec("create_task"), "x".repeat(121))))
        assertNotNull(AppActions.validate(AppActions.Match(AppActions.spec("search"), "a\u0007b")))
        assertNull(AppActions.validate(AppActions.Match(AppActions.spec("create_note"), "fine")))
    }

    @Test fun onlyTheUsersOwnWordsCanTriggerActions() {
        // AppActions.match is called in exactly one place: the recogniser's result in VoiceState (never AI output or documents)
        val all = File("src/main/java/ai/abhify/android").walkTopDown().filter { it.extension == "kt" }.filter { "AppActions.match(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("VoiceState.kt"), all)
        assertTrue(src("ui/VoiceState.kt").contains("vm.startVoice(languageTag = s.recognitionTag("))
        assertTrue(src("ui/VoiceState.kt").contains("private fun handle(text: String)"))
    }

    @Test fun everyRegisteredActionIsReachableAndExecuted() {
        val v = src("ui/VoiceState.kt")
        AppActions.SPECS.filter { it.kind == AppActions.Kind.NAVIGATION || it.kind == AppActions.Kind.READ_ONLY }.forEach { assertTrue(it.id, v.contains("\"${it.id}\" ->")) }
        assertTrue(v.contains("\"create_task\" ->") && v.contains("undo.value"))                 // reversible: undo available
    }
}

/** 5.4 conversation loop, neural states, local data, notifications. */
class VoiceCompanionTest {
    @Test fun neuralStatesFollowRealActivity() {
        listOf("IDLE", "LISTENING", "PROCESSING", "THINKING", "SEARCHING", "SPEAKING", "INTERRUPTED", "OFFLINE", "ERROR").forEach { assertTrue(it, OrbState.entries.any { s -> s.name == it }) }
        val a = src("ui/Aurora.kt")
        assertTrue(a.contains("vm.speaking.value -> OrbState.SPEAKING") && a.contains("vm.voiceState.processing.value -> OrbState.PROCESSING")
            && a.contains("vm.voiceState.interrupted.value -> OrbState.INTERRUPTED"))
        val ui = src("ui/VoiceScreens.kt")
        assertTrue(ui.contains("if (vm.listening.value) Waveform(v.level.floatValue)"))           // waveform only while listening, from the real level
        assertTrue(src("voice/Voice.kt").contains("override fun onRmsChanged(v: Float) { onLevel("))
    }

    @Test fun conversationTurnsAreSpokenAndTypedChatIsUnchanged() {
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("if (voiceTurn) voiceState.onAnswerDone(answer, status == null && failure == null && source != null)"))
        assertTrue(vm.contains("else if (status == null && failure == null && source != null && g.readAloud) voice.speak(answer)"))
        assertTrue(vm.contains("if (voiceTurn) voiceSettings.value.conversationInstruction() else \"\""))
        assertTrue(src("ui/VoiceState.kt").contains("vm.send(text)"))                              // questions go through the normal chat (routing, consent, local storage)
    }

    @Test fun conversationDataClearingTouchesOnlyChatsAndMemories() {
        val db = src("chat/V54Db.kt")
        val tables = Regex("UPDATE (\\w+)").findAll(db).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("messages", "conversations", "memories"), tables)
        assertTrue(db.contains("content = ''"))                                                   // content is blanked, not just hidden
        val ui = src("ui/VoiceScreens.kt")
        assertTrue(ui.contains("Confirm(\"Clear all conversations?\"") && ui.contains("Confirm(\"Clear all memories?\"") && ui.contains("Confirm(\"Clear all conversational data?\""))
        assertTrue(src("ui/ManageScreens.kt").contains("ConversationDataGroup(vm)"))
    }

    @Test fun spokenNotificationsAreOptInGenericAndRespectQuietHours() {
        val vm = src("ui/AppViewModel.kt").substringAfter("fun spokenNotice(").substringBefore("\n    }\n")
        assertTrue(vm.contains("!s.spokenNotifications") && vm.contains("s.isQuiet(") && vm.contains("IMPORTANCE_FOREGROUND"))
        val t = src("ui/TaskState.kt")
        assertTrue(t.contains("\"Your AI task has finished.\"") && !t.substringAfter("spokenNotice(").substringBefore("\n").contains("t.title"))
    }

    @Test fun noWakeWordIsFaked() {
        assertFalse(File("src/main/java/ai/abhify/android").walkTopDown().filter { it.extension == "kt" }.any { it.readText().contains("ForegroundService") && it.readText().contains("SpeechRecognizer") })
        assertTrue(src("ui/VoiceScreens.kt").contains("Wake phrase: not available."))
    }
}
