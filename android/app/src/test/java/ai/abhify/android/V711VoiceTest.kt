package ai.abhify.android

import ai.abhify.android.device.CrashNote
import ai.abhify.android.voice.VoiceChoice
import ai.abhify.android.voice.VoiceChoice.Candidate
import ai.abhify.android.voice.VoiceErrors
import ai.abhify.android.voice.VoiceSettings
import ai.abhify.android.voice.VoiceSettings.Style as S
import ai.abhify.android.voice.WakeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 7.1.1 voice stabilization (JVM unit tests; no device): the three voice choices against installed voices, every
 * recogniser error code, the wake-name matcher, the crash report, and source guards for the crash fixes.
 */
class V711VoiceTest {

    private fun c(name: String, female: Boolean = false, male: Boolean = false, net: Boolean = false, q: Int = 300, def: Boolean = false) =
        Candidate(name, "en-US", female, male, net, q, def)

    @Test fun threeChoicesUseRealInstalledVoices() {
        val voices = listOf(c("en-us-x-iom-network", male = true, net = true, q = 500), c("en-us-x-iol-local", male = true, q = 400),
            c("en-us-x-sfg-local", female = true, q = 400), c("en-us-x-tpd-local", def = true, q = 300))
        assertEquals("en-us-x-iol-local", VoiceChoice.resolve(S.MALE, voices).voiceName)        // offline before online, even if online rates higher
        assertTrue(VoiceChoice.resolve(S.MALE, voices).matched)
        assertEquals("en-us-x-sfg-local", VoiceChoice.resolve(S.FEMALE, voices).voiceName)
        assertEquals("en-us-x-tpd-local", VoiceChoice.resolve(S.ASSISTANT, voices).voiceName)    // the engine's default voice
        assertEquals(3, S.entries.size)
        assertEquals(listOf("Male", "Female", "Assistant"), S.entries.map { it.label })
    }

    @Test fun missingVoiceFallsBackClearlyInsteadOfABrokenOption() {
        val noGender = listOf(c("en-us-x-aaa-local"), c("en-us-x-def-local", def = true))
        val male = VoiceChoice.resolve(S.MALE, noGender)
        assertFalse(male.matched); assertEquals("en-us-x-def-local", male.voiceName); assertTrue(male.note.contains("default voice"))
        val none = VoiceChoice.resolve(S.FEMALE, emptyList())
        assertNull(none.voiceName); assertFalse(none.matched); assertTrue(none.note.contains("No voice"))
        assertEquals("en-us-x-aaa-local", VoiceChoice.resolve(S.ASSISTANT, listOf(c("en-us-x-aaa-local"))).voiceName)   // no default flag: best voice
        val onlineOnly = VoiceChoice.resolve(S.ASSISTANT, listOf(c("net-voice", net = true)))
        assertTrue(onlineOnly.note.contains("internet"))                                           // never claimed to be offline
    }

    @Test fun everyRecognizerErrorHasAMessageAndOnDeviceFailuresFallBack() {
        for (code in -1..20) assertTrue(VoiceErrors.message(code).isNotBlank())
        assertEquals(VoiceErrors.NOT_HEARD, VoiceErrors.message(VoiceErrors.NO_MATCH))
        assertEquals(VoiceErrors.NOT_HEARD, VoiceErrors.message(VoiceErrors.SPEECH_TIMEOUT))
        assertTrue(VoiceErrors.message(VoiceErrors.PERMISSIONS).contains("permission"))
        assertTrue(VoiceErrors.message(VoiceErrors.NETWORK).contains("internet"))
        assertTrue(VoiceErrors.LANGUAGE_UNAVAILABLE in VoiceErrors.ON_DEVICE_FALLBACK && VoiceErrors.SERVER_DISCONNECTED in VoiceErrors.ON_DEVICE_FALLBACK)
        assertFalse(VoiceErrors.PERMISSIONS in VoiceErrors.ON_DEVICE_FALLBACK)                  // a permission problem isn't retried elsewhere
        // the platform constants have these values (android.speech.SpeechRecognizer)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), listOf(VoiceErrors.NETWORK_TIMEOUT, VoiceErrors.NETWORK, VoiceErrors.AUDIO, VoiceErrors.SERVER,
            VoiceErrors.CLIENT, VoiceErrors.SPEECH_TIMEOUT, VoiceErrors.NO_MATCH, VoiceErrors.BUSY, VoiceErrors.PERMISSIONS, VoiceErrors.TOO_MANY,
            VoiceErrors.SERVER_DISCONNECTED, VoiceErrors.LANGUAGE_NOT_SUPPORTED, VoiceErrors.LANGUAGE_UNAVAILABLE))
    }

    @Test fun wakeMatcherUsesTheConfiguredNameAndAvoidsOrdinarySpeech() {
        fun m(t: String, n: String) = WakeMatcher.match(t, n)
        assertEquals("", m("Nova", "Nova")!!.command)
        assertEquals("what time is it", m("hey Nova, what time is it?", "Nova")!!.command)
        assertEquals("open news", m("OK NOVA open news", "nova")!!.command)
        assertNotNull(m("heynova", "Nova")); assertNotNull(m("hi nova", "Nova"))
        assertNotNull(m("hey noba", "Nova"))                                                  // one letter misheard (4–6 letter name)
        assertNotNull(m("Jarviss help me", "Jarvis"))
        assertNotNull(m("hello Athina", "Athena"))
        assertNotNull(m("hey Captain Nova", "Captain Nova"))                                  // multi-word names
        assertNotNull(m("अरे नोवा", "नोवा"))                                                     // any script
        assertNull(m("I love Nova Scotia", "Nova"))                                            // the name must open the phrase
        assertNull(m("the weather today", "Nova"))
        assertNull(m("hey eva", "Ava")); assertNull(m("java is great", "Ava"))                 // short names must match exactly
        assertNotNull(m("hey ava", "Ava"))
        assertNull(m("", "Nova")); assertNull(m("hey", "Nova")); assertNull(m("nova", ""))
        assertEquals(listOf("hey", "nova", "what", "s", "up"), WakeMatcher.normalize("Hey, NOVA!! What's up?"))
    }

    @Test fun crashReportHasTheChainButIsLocalOnly() {
        val e = IllegalStateException("outer", RuntimeException("root cause"))
        val r = CrashNote.format("7.1.1", "main", e, now = 0)
        assertTrue(r.contains("7.1.1") && r.contains("IllegalStateException: outer") && r.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertTrue(r.contains("stored only on this phone"))
        val src = File("src/main/java/ai/abhify/android/device/CrashNote.kt").readText()
        assertFalse(src.contains("http") || src.contains("URL(") || src.contains("upload("))
    }

    @Test fun wakeWordIsOffByDefaultAndPersists() {
        assertFalse(VoiceSettings().wakeWord)
        assertTrue(VoiceSettings.fromJson(VoiceSettings(wakeWord = true).toJson()).wakeWord)
        assertFalse(VoiceSettings.fromJson("{}").wakeWord)
    }

    private fun src(p: String) = File("src/main/java/ai/abhify/android/$p").readText().replace("\r\n", "\n")

    @Test fun crashFixesAreInPlace() {
        val vm = src("ui/AppViewModel.kt")
        // an answer that fails for any reason becomes a message, never an uncaught coroutine exception
        assertTrue(vm.contains("} catch (e: kotlinx.coroutines.CancellationException) { throw e\n            } catch (e: Throwable) {"))
        // one microphone session at a time; Retry; voice errors don't throw
        val start = vm.substring(vm.indexOf("fun startVoice("), vm.indexOf("fun retryVoice()"))
        assertTrue(start.contains("if (listening.value) return") && start.contains("catch (e: Exception)") && start.contains("runCatching { warmForVoice() }"))
        assertTrue(vm.contains("MemoryProfile.LOW) return"))                                  // no model load during listening on low-memory phones
        // no AI set up: say so, keep the words, don't loop the microphone
        val vs = src("ui/VoiceState.kt")
        assertTrue(vs.contains("vm.assistantProblem()?.let") && vs.contains("vm.pendingInput.value = text") && vs.contains("noRelisten = true"))
        assertTrue(vs.contains("CoroutineExceptionHandler") && !vs.contains("vm.viewModelScope.launch {"))   // every voice task is guarded
        // leaving the app releases the microphone
        val act = src("ui/MainActivity.kt")
        assertTrue(act.contains("override fun onStop() { super.onStop(); vm.releaseMicrophone() }"))
        // the recogniser: stale callbacks ignored, on-device fallback, never throws
        val v = src("voice/Voice.kt")
        assertTrue(v.contains("if (my != session || finished) return") && v.contains("ON_DEVICE_FALLBACK") && v.contains("EXTRA_BIASING_STRINGS"))
    }

    @Test fun noNewPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val perms = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertFalse(perms.any { it.contains("FOREGROUND_SERVICE_MICROPHONE") })
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECORD_AUDIO"),
            perms.filter { it in setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECORD_AUDIO") }.toSet())
        assertFalse(perms.contains("android.permission.REQUEST_INSTALL_PACKAGES"))             // 7.2.1 (D8): the unused install permission is gone
        assertEquals(6, perms.size)                                                            // 7 until 7.2.1 (the build adds 3 library ones)
    }
}
