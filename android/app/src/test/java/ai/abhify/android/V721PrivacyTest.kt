package ai.abhify.android

import ai.abhify.android.learn.BrainSource
import ai.abhify.android.learn.StudyAi
import ai.abhify.android.learn.StudyService
import ai.abhify.android.settings.PrivacyDefaults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/** 7.2.1-dev.1 privacy patch on Android: Study personal context and the memory switch (F3), online chat history default (A3),
 *  the unused install-apps permission (A2). */
class V721PrivacyTest {
    private val tracks = File("src/main/assets/study_tracks.json").readText(Charsets.UTF_8)
    private val tax = File("src/main/assets/skills_taxonomy.json").readText(Charsets.UTF_8)
    private val dir = Files.createTempDirectory("abhify-721").toFile()
    private var memoryOn = true
    private val brain = object : BrainSource {
        override fun memories() = listOf(BrainSource.Memory(1, "Become an ML platform engineer", true, "Career goals"))
        override fun projects() = listOf(BrainSource.Project("7", "Fraud detector", "A Kafka side project"))
        override fun memoryEnabled() = memoryOn
    }
    private val svc = StudyService(File(dir, "study.json"), { tracks }, { tax }, StudyAi { _, _, _ -> "" }, { LocalDate.parse("2026-12-01") },
        { 1_795_000_000_000L }, Random(7), ZoneOffset.UTC, brain, onSaved = {})

    @Test fun studyPersonalContextRespectsTheMemorySwitchAndKeepsTheChoices() = runBlocking {
        val c = svc.createCourse("aiml")
        svc.setBrain(c.id, true, listOf(1L), listOf("7"))
        suspend fun course() = svc.courseDetail(c.id).course
        val (on, used) = svc.brainContext(course())
        assertTrue(on.contains("Become an ML platform engineer") && on.contains("Fraud detector") && used.isNotEmpty())
        memoryOn = false
        assertEquals("" to emptyList<String>(), svc.brainContext(course()))                    // memories AND projects off
        assertFalse(svc.brainSources().memoryEnabled)
        assertEquals(listOf(1L), course().brain!!.memories)                                    // choices kept, not deleted
        assertEquals(listOf("7"), course().brain!!.projects)
        memoryOn = true
        assertTrue(svc.brainContext(course()).first.contains("Become an ML platform engineer"))
    }

    @Test fun onlineHistoryIsOffForNewInstallsAndUnchangedForExistingOnes() {
        assertNull(PrivacyDefaults.onlineHistoryToWrite(hasStoredValue = false, existingInstall = false))   // new: read default
        assertFalse(PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL)
        assertEquals(true, PrivacyDefaults.onlineHistoryToWrite(hasStoredValue = false, existingInstall = true))   // old default kept
        assertNull(PrivacyDefaults.onlineHistoryToWrite(hasStoredValue = true, existingInstall = true))     // a stored choice is never touched
        assertNull(PrivacyDefaults.onlineHistoryToWrite(hasStoredValue = true, existingInstall = false))
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertFalse(vm.contains("getBoolean(\"online_history\", true)"))
        assertEquals(4, Regex("getBoolean\\(\"online_history\", ai\\.abhify\\.android\\.settings\\.PrivacyDefaults\\.ONLINE_HISTORY_NEW_INSTALL\\)").findAll(vm).count())
        assertTrue(File("src/main/java/ai/abhify/android/AppGraph.kt").readText().contains("PrivacyDefaults.onlineHistoryToWrite("))
    }

    @Test fun theUnusedInstallAppsPermissionIsGone() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        for (p in listOf("INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS", "RECORD_AUDIO", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED"))
            assertTrue(p, manifest.contains("android.permission.$p") || p == "WAKE_LOCK" || p == "RECEIVE_BOOT_COMPLETED")
    }
}
