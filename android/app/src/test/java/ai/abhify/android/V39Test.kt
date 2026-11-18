package ai.abhify.android

import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV11
import ai.abhify.android.chat.SchemaV12
import ai.abhify.android.chat.SchemaV13
import ai.abhify.android.chat.SchemaV14
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.learn.PlanCheck
import ai.abhify.android.learn.Srs
import ai.abhify.android.learn.Srs.Rating
import ai.abhify.android.learn.StudyInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class SpacedRepetitionTest {
    private val DAY = 86_400_000L
    private val T = 1_000_000_000L

    @Test fun intervalsFollowTheDocumentedRules() {
        var s = Srs.State()
        val g1 = Srs.next(s, Rating.GOOD, T); assertEquals(1.0, g1.state.intervalDays, 0.0); assertEquals(T + DAY, g1.dueAt)
        val g2 = Srs.next(g1.state, Rating.GOOD, T); assertEquals(3.0, g2.state.intervalDays, 0.0)
        val g3 = Srs.next(g2.state, Rating.GOOD, T); assertEquals(7.5, g3.state.intervalDays, 1e-9)                // 3 × ease 2.5
        val again = Srs.next(g3.state, Rating.AGAIN, T)
        assertEquals(0.0, again.state.intervalDays, 0.0); assertEquals(T + 10 * 60_000L, again.dueAt); assertEquals(1, again.state.lapses); assertEquals(2.3, again.state.ease, 1e-9)
        val easy = Srs.next(Srs.State(), Rating.EASY, T); assertEquals(3.0, easy.state.intervalDays, 0.0); assertEquals(2.65, easy.state.ease, 1e-9)
        val hard = Srs.next(g2.state, Rating.HARD, T); assertEquals(3.6, hard.state.intervalDays, 1e-9); assertEquals(2.35, hard.state.ease, 1e-9)
        repeat(20) { s = Srs.next(s, Rating.AGAIN, T).state }; assertEquals(1.3, s.ease, 1e-9)                    // ease floor
        var e = Srs.State(intervalDays = 100.0, ease = 3.0); e = Srs.next(e, Rating.EASY, T).state; assertEquals(Srs.MAX_DAYS, e.intervalDays, 0.0)   // cap
        assertEquals("10 min", Srs.label(again, T)); assertEquals("1 day", Srs.label(g1, T)); assertEquals("3 days", Srs.label(g2, T))
        assertTrue(Srs.isDue(null, T)); assertFalse(Srs.isDue(T + 1, T)); assertTrue(Srs.mature(21.0))
    }

    @Test fun plansMustFitTheTimeAvailable() {
        val today = LocalDate.of(2026, 11, 5)
        val ten = (1..10).map { "Lesson $it: topic" }
        assertTrue(PlanCheck.check(ten, 30, "2026-11-01", today).error!!.contains("after today"))
        assertTrue(PlanCheck.check(ten, 30, "31/12/2026", today).error!!.contains("2026-12-31"))
        assertTrue(PlanCheck.check(ten.take(2), 30, null, today).error!!.contains("at least 3"))
        assertTrue(PlanCheck.check(ten, 5, null, today).error!!.contains("10 and 600"))
        val tight = PlanCheck.check(ten, 20, "2026-11-08", today)                                        // 3 days × 20 min / 10 lessons = 6 min
        assertNull(tight.error); assertEquals(6L, tight.minutesPerLesson); assertTrue(tight.warnings.single().contains("tight"))
        val ok = PlanCheck.check(ten + ten, 60, "2026-12-31", today)
        assertEquals(10, ok.lessons.size); assertTrue(ok.warnings.isEmpty())                               // duplicates removed
        assertEquals(30, PlanCheck.check((1..40).map { "Lesson $it" }, 60, null, today).lessons.size)
    }

    @Test fun gapSuggestionsComeWithReasonsAndOnlyFromStudyRecords() {
        val now = 100 * DAY
        val s = StudyInsights.suggest(
            quizzes = listOf(StudyInsights.QuizStat("Python", 10, 4), StudyInsights.QuizStat("SQL", 10, 9), StudyInsights.QuizStat("Git", 3, 0)),
            decks = listOf(StudyInsights.DeckStat("Transformers", 40, 12, 4, now - DAY), StudyInsights.DeckStat("Old", 10, 0, 0, now - 40 * DAY)),
            plans = listOf(StudyInsights.PlanStat("Docker", 2, 10, "2026-11-01", now - DAY), StudyInsights.PlanStat("Kotlin", 1, 8, null, now - 20 * DAY),
                StudyInsights.PlanStat("Done", 5, 5, "2026-01-01", 0)),
            sessions = listOf(StudyInsights.SessionStat("Quiz: notes.pdf", 3, 10, now)), now = now, today = "2026-11-05")
        val topics = s.map { it.topic }
        assertTrue("Python" in topics); assertFalse("SQL" in topics); assertFalse("Git" in topics)        // too few answers to judge
        assertTrue(s.count { it.topic == "Transformers" } == 2)                                            // often missed + overdue
        assertTrue(s.first { it.topic == "Docker" }.reason.contains("has passed")); assertTrue(s.first { it.topic == "Kotlin" }.reason.contains("20 days"))
        assertFalse("Done" in topics); assertTrue(s.any { it.topic == "Old" && it.reason.contains("40 days") })
        assertTrue(s.all { it.reason.isNotBlank() && it.action.isNotBlank() })
        assertEquals(s.sortedByDescending { it.priority }, s)
        assertTrue(StudyInsights.suggest(emptyList(), emptyList(), emptyList(), emptyList(), now, "2026-11-05").isEmpty())
    }

    @Test fun schemaV14KeepsCardsAndPlans() {
        v8Db().use { c ->
            c.createStatement().use { st -> listOf(SchemaV9.STATEMENTS, SchemaV10.STATEMENTS, SchemaV11.STATEMENTS, SchemaV12.STATEMENTS, SchemaV13.STATEMENTS).flatten().forEach(st::execute) }
            c.exec("INSERT INTO flashcards (deck, front, back, known, reviews, created_at, updated_at) VALUES ('ML', 'Q', 'A', 1, 7, 1, 1)")
            c.exec("INSERT INTO study_plans (topic, level, created_at, updated_at) VALUES ('Docker', 'Beginner', 1, 1)")
            c.createStatement().use { st -> SchemaV14.apply { st.execute(it) } }
            assertEquals(1, c.one("SELECT known FROM flashcards")); assertEquals(7, c.one("SELECT reviews FROM flashcards"))
            assertNull(c.one("SELECT due_at FROM flashcards")); assertEquals(2.5, (c.one("SELECT ease FROM flashcards") as Number).toDouble(), 0.0)
            assertNull(c.one("SELECT doc_ids FROM study_plans")); assertEquals(0, c.one("SELECT COUNT(*) FROM card_reviews"))
        }
        val main = File("src/main/java/ai/abhify/android")
        assertTrue(File(main, "chat/ChatDb.kt").readText().contains("if (oldVersion < 14) SchemaV14.apply"))
        assertTrue(File(main, "chat/StudioDb.kt").readText().contains("DELETE FROM card_reviews WHERE card_id IN (SELECT id FROM flashcards WHERE deck = ?)"))
        for (f in listOf("learn/Srs.kt", "learn/StudyInsights.kt", "chat/V39Db.kt", "ui/V39Screens.kt"))                            // no reminders/notifications added
            assertFalse(f, File(main, f).readText().contains("Notification"))
    }
}
