package ai.abhify.android

import ai.abhify.android.career.PracticeStats
import ai.abhify.android.chat.InterviewAttempt
import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV11
import ai.abhify.android.chat.SchemaV12
import ai.abhify.android.chat.SchemaV9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CareerMaintenanceTest {
    private fun a(id: Long, topic: String, t: Long, score: Double? = null, jd: Long? = null) = InterviewAttempt(id, topic, "q$id", "a", null, null, t, jd, score)

    @Test fun practiceProgressIsComputedFromSavedAttemptsOnly() {
        val all = listOf(a(1, "Technical", 1, 2.0), a(2, "Technical", 2, 2.0), a(3, "Technical", 3, 3.0), a(4, "Technical", 4, 4.0), a(5, "Technical", 5, 4.0),
            a(6, "Technical", 6, 5.0), a(7, "Job description · Requirement: Kotlin", 7, 3.0, jd = 10), a(8, "Job description · from the job description", 8, null, jd = 99),
            a(9, "Behavioural", 9))
        val t = PracticeStats.byTopic(all).associateBy { it.name }
        assertEquals(6, t["Technical"]!!.attempts); assertEquals(20.0 / 6, t["Technical"]!!.average!!, 1e-9)
        assertEquals(13.0 / 3 - 7.0 / 3, t["Technical"]!!.trend!!, 1e-9)                         // last 3 (5,4,4) vs previous 3 (3,2,2)
        assertEquals(2, t["Job description"]!!.attempts); assertEquals(1, t["Job description"]!!.scored)   // requirement suffix grouped
        assertNull(t["Behavioural"]!!.average); assertNull(t["Behavioural"]!!.trend)               // not scored ≠ zero
        val jobs = PracticeStats.byJob(all, mapOf(10L to "ML Engineer")).associateBy { it.name }
        assertEquals(1, jobs["ML Engineer"]!!.attempts); assertEquals(1, jobs["Deleted job"]!!.attempts)
        assertNull(PracticeStats.trend(listOf(5.0, 4.0, 3.0)))                                    // too few to call a trend
        assertEquals(3.0, PracticeStats.meanScore(listOf(2, 4, 0, 9))!!, 1e-9)                    // invalid scores ignored
        assertNull(PracticeStats.meanScore(emptyList()))
    }

    @Test fun schemaV12KeepsOldAttemptsAndJobLinksAreCleaned() {
        v8Db().use { c ->
            c.createStatement().use { st -> SchemaV9.apply { st.execute(it) }; SchemaV10.apply { st.execute(it) }; SchemaV11.apply { st.execute(it) } }
            c.exec("INSERT INTO interview_attempts (topic, question, answer, feedback, created_at) VALUES ('Technical', 'Q', 'A', '**Scores**', 1)")
            c.createStatement().use { st -> SchemaV12.apply { st.execute(it) } }
            assertEquals("A", c.one("SELECT answer FROM interview_attempts")); assertNull(c.one("SELECT jd_id FROM interview_attempts")); assertNull(c.one("SELECT score FROM interview_attempts"))
        }
        val life = File("src/main/java/ai/abhify/android/chat/LifeDb.kt").readText()
        assertTrue(life.contains("UPDATE interview_attempts SET jd_id = NULL WHERE jd_id = ?"))     // deleting a job never deletes practice history
        val st = File("src/main/java/ai/abhify/android/ui/LifeState.kt").readText()
        assertTrue(st.contains("val jdId = if (topic.value == Career.Topic.JOB) vm.career.selectedJd.value?.id else null"))
        assertTrue(st.contains("if (fb == null || r.status != null)"))                             // unvalidated feedback still never saved
        assertTrue(File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText().contains("if (oldVersion < 12) SchemaV12.apply"))
    }
}
