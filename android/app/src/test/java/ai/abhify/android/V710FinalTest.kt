package ai.abhify.android

import ai.abhify.android.device.CrashNote
import ai.abhify.android.voice.VoiceChoice
import ai.abhify.android.voice.VoiceChoice.Candidate
import ai.abhify.android.voice.VoiceSettings
import ai.abhify.android.voice.VoiceSettings.Style as S
import ai.abhify.android.voice.VoskWake
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 7.1.0 final: fixes for the crash report from the owner's phone, voice picking, Vosk wake word, native-crash capture. */
class V710FinalTest {
    private fun src(p: String) = File("src/main/$p").readText().replace("\r\n", "\n")

    /** The crash: Android's ICU regex engine rejects the (?U) flag that desktop Java accepts. No source may use it. */
    @Test fun noAndroidIncompatibleRegexFlags() {
        val bad = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("(?U)") || it.contains("UNICODE_CHARACTER_CLASS") } }.map { it.name }.toList()
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun citationsWorkAndTheAnswerCleanupCanNotThrow() {
        // the object whose initialiser crashed on the phone loads and works (JVM; the pattern is now ICU-compatible too)
        val (checked, _) = ai.abhify.android.intel.Citations.labelUnverified("Paris is the capital of France.")
        assertTrue(checked.contains("Paris"))
        val vm = src("java/ai/abhify/android/ui/AppViewModel.kt")
        assertTrue(vm.contains("val (checked, cite) = runCatching {") && vm.contains(".getOrElse { checked to emptyList() }"))
    }

    @Test fun eachChoiceCanUseAVoiceTheUserPicked() {
        val unlabeled = listOf(Candidate("en-us-x-iom-local", "en-US", false, false, false, 400), Candidate("en-us-x-sfg-local", "en-US", false, false, false, 400, true))
        assertFalse(VoiceChoice.resolve(S.MALE, unlabeled).matched)                                  // engine doesn't say which is male
        val r = VoiceChoice.resolve(S.MALE, unlabeled, "en-us-x-iom-local")
        assertTrue(r.matched); assertEquals("en-us-x-iom-local", r.voiceName)
        assertEquals("en-us-x-sfg-local", VoiceChoice.resolve(S.MALE, unlabeled, "gone-voice").voiceName)   // a removed pick falls back
        val s = VoiceSettings().withPicked(S.MALE, "en-us-x-iom-local").withPicked(S.ASSISTANT, "en-us-x-sfg-local")
        val back = VoiceSettings.fromJson(s.toJson())
        assertEquals("en-us-x-iom-local", back.picked(S.MALE)); assertNull(back.picked(S.FEMALE)); assertEquals("en-us-x-sfg-local", back.picked(S.ASSISTANT))
    }

    @Test fun voskListensOnlyForTheName() {
        val g = JSONArray(VoskWake.grammar("Nova"))
        assertEquals(listOf("nova", "hey nova", "hi nova", "hello nova", "ok nova", "okay nova", "[unk]"), (0 until g.length()).map { g.getString(it) })
        assertEquals("hey nova", VoskWake.textOf("""{"partial":"hey nova"}""")); assertEquals("nova", VoskWake.textOf("""{"text":"nova"}"""))
        assertEquals("", VoskWake.textOf("not json")); assertEquals("", VoskWake.textOf(null))
        assertEquals(41_205_931L, VoskWake.BYTES); assertEquals(64, VoskWake.SHA256.length)
        assertTrue(VoskWake.URL_MODEL.startsWith("https://alphacephei.com/vosk/models/"))
        val code = src("java/ai/abhify/android/voice/VoskWake.kt")
        assertTrue(code.contains("if (hex != SHA256)") && code.contains("zip-slip guard"))                  // verified before unpacking
        val vm = src("java/ai/abhify/android/ui/AppViewModel.kt")
        assertTrue(vm.contains("fun installVosk()") && !vm.substring(vm.indexOf("fun onAppForeground()"), vm.indexOf("fun onAppForeground()") + 120).contains("installVosk"))   // never automatic
    }

    @Test fun androidExitReasonsAreRecorded() {
        assertEquals("a native crash (AI engine or another native library)", CrashNote.exitKind(5))
        assertEquals("Android closed it to free memory", CrashNote.exitKind(3))
        assertNull(CrashNote.exitKind(10))                                                             // user request: not a crash
        assertTrue(src("java/ai/abhify/android/AppGraph.kt").contains("CrashNote.recordLastExit("))
    }

    @Test fun nativeCallbackCanNotLeaveAPendingException() {
        assertTrue(src("cpp/abhify_llm.cpp").contains("if (env->ExceptionCheck()) { env->ExceptionClear();"))
        assertTrue(src("java/ai/abhify/android/llm/LlmEngine.kt").contains("runCatching { trySendBlocking("))
    }

    @Test fun emptyChatShowsAHeadingNotAGreeting() {
        val home = src("java/ai/abhify/android/ui/HomeScreens.kt")
        assertTrue(home.contains("Text(\"Ask \${vm.assistantName.value} anything\""))
        assertFalse(home.contains("\"Hi! I'm \${vm.assistantName.value}\""))
    }
    @Test fun weatherWithoutLiveDataIsAnsweredHonestlyNotByTheModel() {
        val off = ai.abhify.android.intel.Realtime.unavailableReply(listOf(ai.abhify.android.intel.Realtime.Category.WEATHER), emptyList(), online = false)
        assertTrue(off.contains("weather") && off.contains("offline") && off.contains("won't guess"))
        val city = ai.abhify.android.intel.Realtime.unavailableReply(listOf(ai.abhify.android.intel.Realtime.Category.WEATHER), listOf("which city?"), online = true)
        assertTrue(city.contains("which city?") && city.contains("weather in Pune"))
        val vm = src("java/ai/abhify/android/ui/AppViewModel.kt")
        val i = vm.indexOf("if (externalNeeded.isNotEmpty() && liveExternal.isEmpty() && webItems.isEmpty())")
        assertTrue(i > 0 && vm.indexOf("return@launch", i) < vm.indexOf("g.hybrid.generate(", i))   // the model is never asked
    }

    @Test fun pipelineLogHasNoPersonalTextAndSurvivesInAFile() {
        val dir = java.nio.file.Files.createTempDirectory("vl").toFile()
        ai.abhify.android.device.VoiceLog.init(dir)
        ai.abhify.android.device.VoiceLog.add("recognizer", "result: 12 chars")
        assertTrue(ai.abhify.android.device.VoiceLog.persisted(dir)!!.contains("recognizer: result: 12 chars"))
        val vm = src("java/ai/abhify/android/ui/AppViewModel.kt")
        assertFalse(vm.lines().any { it.contains("VoiceLog.add(") && (it.contains("\$text") || it.contains("\${text}")) })   // never the words themselves
        assertTrue(src("java/ai/abhify/android/device/CrashNote.kt").contains("Last pipeline events"))
    }
    @Test fun oneSoftFriendlyVoiceAndOtherVoicesOnlyWhenThePhoneHasThem() {
        val ui = src("java/ai/abhify/android/ui/VoiceScreens.kt")
        assertFalse(ui.contains("Choose voice for") || ui.contains("VoiceSettings.Style.entries.forEach"))     // no Male/Female/Assistant chips
        assertTrue(ui.contains("if ((list?.size ?: 0) > 1) TextButton({ choosing = !choosing })"))
        assertTrue(VoiceSettings.SOFT_RATE < 1f && VoiceSettings.SOFT_PITCH in 1.0f..1.1f)
        assertTrue(VoiceSettings.Tone.WARM.instruction.contains("softly"))
    }
}
