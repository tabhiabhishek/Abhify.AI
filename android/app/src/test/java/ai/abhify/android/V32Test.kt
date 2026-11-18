package ai.abhify.android

import ai.abhify.android.chat.JobApplication
import ai.abhify.android.chat.SchemaV5
import ai.abhify.android.chat.SchemaV6
import ai.abhify.android.chat.SchemaV7
import ai.abhify.android.chat.VaultItem
import ai.abhify.android.learn.Studio
import ai.abhify.android.life.Career
import ai.abhify.android.life.ShareKit
import ai.abhify.android.life.StudyKit
import ai.abhify.android.life.VaultKit
import ai.abhify.android.ui.AppViewModel
import ai.abhify.android.voice.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class CareerTest {
    private fun app(company: String = "Northwind", title: String = "Data Analyst", status: String = "APPLIED", notes: String? = null,
                    applied: String? = "2026-11-01", follow: String? = null, interview: String? = null, updated: Long = 1) =
        JobApplication(0, company, title, null, null, null, applied, status, notes, follow, interview, updated)

    @Test fun trackerValidatesRequiredFieldsAndDates() {
        assertNull(Career.validate(app()))
        assertEquals("Company is required.", Career.validate(app(company = " ")))
        assertEquals("Job title is required.", Career.validate(app(title = "")))
        assertTrue(Career.validate(app(applied = "05/11/2026"))!!.contains("2026-11-05"))
        assertTrue(Career.validate(app(interview = "2026-13-01"))!!.startsWith("Interview date"))
        assertNull(Career.validate(app(applied = null, follow = "")))                                // dates are optional
        assertEquals(Career.Status.SAVED, Career.Status.of("nonsense"))
    }

    @Test fun searchFilterAndDueDates() {
        val all = listOf(app("Northwind", "Data Analyst", "APPLIED", "referral from Asha", updated = 3),
            app("Contoso", "ML Engineer", "INTERVIEW", interview = "2026-11-05", updated = 5),
            app("Fabrikam", "Analyst", "REJECTED", follow = "2026-11-01", updated = 4))
        assertEquals(listOf("Contoso", "Fabrikam", "Northwind"), Career.filter(all, "", null).map { it.company })   // newest change first
        assertEquals(listOf("Northwind"), Career.filter(all, "asha", null).map { it.company })                       // notes are searched
        assertEquals(listOf("Contoso"), Career.filter(all, "", Career.Status.INTERVIEW).map { it.company })
        assertEquals(listOf("Contoso"), Career.due(all, "2026-11-05").map { it.company })                           // rejected ones aren't nagged
        assertTrue(Career.due(all, "2026-11-04").isEmpty())
    }

    @Test fun interviewQuestionsParseStrictlyAndUseOnlyTheResumeForProjects() {
        assertEquals("What is a Python generator?" to "- lazy\n- yield", Career.parseQuestion("Question: What is a Python generator?\nOutline:\n- lazy\n- yield"))
        assertEquals("Tell me about a conflict.", Career.parseQuestion("**Question:** Tell me about a conflict.")!!.first.removeSuffix("**").trim().removePrefix("** "))
        assertNull(Career.parseQuestion("Sure! Here is a question for you."))
        val p = Career.questionPrompt(Career.Topic.PROJECTS, "senior", "Built a churn model with XGBoost", listOf("old question"))
        assertTrue(p.contains("<resume>") && p.contains("don't invent") && p.contains("Don't repeat these: old question"))
        assertFalse(Career.questionPrompt(Career.Topic.PYTHON, "beginner", "secret resume", emptyList()).contains("secret resume"))   // resume only where needed
        assertTrue(Career.feedbackPrompt("Q", "A").contains("not a hiring decision"))
    }
}

class StudyKitTest {
    private val qs = listOf(
        Studio.Mcq("2+2?", listOf("3", "4", "5", "6"), 1, "Basic sum."),
        Studio.Mcq("Capital of France?", listOf("Rome", "Paris", "Berlin", "Madrid"), 1, ""),
        Studio.Mcq("ReLU(−1)?", listOf("−1", "1", "0", "0.5"), 2, "max(0,x)"))

    @Test fun scoringNavigationAndReview() {
        var q = StudyKit.Quiz(qs, qs.map { null }, 0)
        q = q.pick(1); assertEquals(1, q.correct)
        q = q.pick(3); assertEquals(1, q.picks[0])                                                        // an answer can't be changed afterwards
        q = q.next().pick(0).next().pick(2)
        assertTrue(q.done); assertEquals(2, q.correct); assertEquals(listOf(1), q.wrong)
        assertEquals(2, q.next().index); assertEquals(1, q.prev().index); assertEquals(0, StudyKit.Quiz(qs, qs.map { null }, 0).prev().index)
        val review = q.reviewWrong()!!
        assertEquals(listOf("Capital of France?"), review.questions.map { it.question }); assertTrue(review.picks.all { it == null })
        assertNull(StudyKit.Quiz(qs, listOf(1, 1, 2), 0).reviewWrong())
    }

    @Test fun sessionsSurviveSaveAndResume() {
        val q = StudyKit.Quiz(qs, listOf(1, null, null), 1)
        val (back, sources) = StudyKit.decodeQuiz(StudyKit.encodeQuiz(q, listOf("document: notes.pdf", "written by 📱 On this phone · Qwen")))!!
        assertEquals(q, back); assertEquals("document: notes.pdf", sources.first())
        val cards = listOf("What is SGD?" to "Stochastic gradient descent")
        assertEquals(cards, StudyKit.decodeCards(StudyKit.encodeCards(cards, emptyList())))
        assertEquals("Hello" to listOf("topic: x"), StudyKit.decodeText(StudyKit.encodeText("Hello", listOf("topic: x"))))
        assertEquals("not json" to emptyList<String>(), StudyKit.decodeText("not json"))
        assertNull(StudyKit.decodeQuiz("{broken"))
    }

    @Test fun promptsSayWhatTheyMust() {
        assertTrue(StudyKit.SUMMARY.contains("Short summary") && StudyKit.SUMMARY.contains("Key points") && StudyKit.SUMMARY.contains("Important terms"))
        assertTrue(StudyKit.revision("SQL joins", 5, 20).contains("over 5 days, 20 minutes per day"))
    }
}

class VaultAndShareTest {
    private fun item(id: Long, title: String, body: String) = VaultItem(id, "note", title, body, "Notes", emptyList(), false, null, null, 0, null)

    @Test fun tagsAreNormalised() {
        assertEquals(listOf("ml", "interview", "python"), VaultKit.tags("ML, interview , ml, #python,"))
        assertEquals(12, VaultKit.tags((1..20).joinToString(",") { "t$it" }).size)
    }

    @Test fun questionsOnlySearchTheSelectedItems() {
        val a = item(1, "Leave policy", "Employees get 24 days of annual leave.")
        val b = item(2, "Secret plan", "Annual leave is irrelevant here but this item mentions leave days too.")
        val ctx = VaultKit.context(listOf(a), "How many days of annual leave?", 3000)
        assertEquals(setOf("Leave policy"), ctx.sources.map { it.docName }.toSet())                      // b was not selected, so it can't appear
        assertTrue(VaultKit.context(listOf(a, b), "leave days", 3000).sources.size == 2)
        assertTrue(VaultKit.context(listOf(a), "quantum", 3000).sources.isEmpty())
    }

    @Test fun sharingHidesPrivateDetailsByDefault() {
        val t = "Priya Sharma, priya@example.com, +91 98765 43210, linkedin.com/in/priya — strong SQL. key gsk_ABCDEFGHIJKL1234"
        val hidden = ShareKit.redact(t, includeContact = false)
        assertFalse(hidden.contains("priya@example.com") || hidden.contains("98765") || hidden.contains("linkedin.com") || hidden.contains("gsk_"))
        assertTrue(hidden.contains("strong SQL") && hidden.contains("[email hidden]") && hidden.contains("[phone hidden]"))
        val shown = ShareKit.redact(t, includeContact = true)
        assertTrue(shown.contains("priya@example.com") && shown.contains("98765")); assertFalse(shown.contains("gsk_"))   // keys are never shared
        assertEquals("📚 I practised \"SQL\" with Abhify.AI: 4/5 correct on a 5-question quiz (80%).", ShareKit.studyResult("SQL", 4, 5))
        assertTrue(ShareKit.resumeSummary("Call +91 98765 43210", false).contains("[phone hidden]"))
    }
}

class VoiceReaderTest {
    @Test fun pauseAndResumeKeepThePlace() {
        val parts = Voice.parts(("Sentence one is here. ".repeat(30)), 400)
        assertTrue(parts.size >= 2 && parts.all { it.length <= 400 })
        var r = Voice.Reader(parts)
        r = r.started(1); assertEquals(1, r.current)
        r = r.pause(); assertTrue(r.paused); assertEquals(1, r.resumeFrom)
        r = r.started(1); assertFalse(r.paused)
        assertFalse(Voice.Reader().pause().paused)                                                      // nothing to pause
    }
}

class V32StructureTest {
    @Test fun fiveMainAreasAndSettingsStillReachable() {
        // 4.6.2: Home · Chat · Study · News · More; Documents, Career, Knowledge and Settings open from More
        assertEquals(listOf("Home", "Assistant", "Study", "News", "More"), AppViewModel.Tab.entries.filter { it.inBar }.map { it.label })
        assertFalse(AppViewModel.Tab.SETTINGS.inBar)
        val home = File("src/main/java/ai/abhify/android/ui/HomeScreens.kt").readText()
        assertTrue(home.contains("AppViewModel.Tab.MORE, AppViewModel.Tab.SETTINGS -> SettingsTab(vm)"))
        assertTrue(home.contains("AppViewModel.Tab.KNOWLEDGE -> KnowledgeTab(vm)") && File("src/main/java/ai/abhify/android/ui/LifeScreens.kt").readText().contains("WorkTab(vm)"))
    }

    @Test fun newCodeNeverTouchesKeysAndUsesTheConsentRunner() {
        for (f in listOf("ui/LifeState.kt", "ui/LifeScreens.kt", "life/Life.kt", "chat/LifeDb.kt"))
            File("src/main/java/ai/abhify/android/$f").readText().let { t -> assertFalse(f, t.contains("providerStore") || t.contains("saveKey(")) }
        val state = File("src/main/java/ai/abhify/android/ui/LifeState.kt").readText()
        assertFalse(state.contains("hybrid.generate") || state.contains("engine.generate"))                 // all AI goes through aiTask
        assertTrue(Regex("vm\\.aiTask\\(").findAll(state).count() >= 4)
    }

    @Test fun schemaV7MigratesAPopulatedV6Database() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            SchemaV5.apply { st.execute(it) }; SchemaV6.apply { st.execute(it) }
            st.execute("INSERT INTO conversations (title, created_at, updated_at) VALUES ('Keep me', 1, 1)")
            st.execute("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at, role) VALUES ('d', 'cv.pdf', 'PDF', 1, 5, 'Hello', 1, 1, 'resume')")
            st.execute("INSERT INTO resume_versions (doc_id, kind, title, body, created_at) VALUES (1, 'draft', 'Draft', 'Improved', 2)")
            SchemaV7.apply { st.execute(it) }
            SchemaV7.apply { st.execute(it) }                                                             // idempotent (IF NOT EXISTS)
            c.createStatement().executeQuery("SELECT (SELECT title FROM conversations), (SELECT role FROM library_docs), (SELECT body FROM resume_versions)").use { r ->
                assertTrue(r.next()); assertEquals("Keep me", r.getString(1)); assertEquals("resume", r.getString(2)); assertEquals("Improved", r.getString(3))
            }
            st.execute("INSERT INTO job_applications (company, title, resume_doc_id, resume_version_id, status, created_at, updated_at) VALUES ('Contoso', 'ML Eng', 1, 1, 'APPLIED', 1, 1)")
            st.execute("INSERT INTO vault_items (uid, kind, title, body, category, created_at, updated_at) VALUES ('u', 'note', 't', 'b', 'Notes', 1, 1)")
            c.createStatement().executeQuery("SELECT tags, favorite, opened_at FROM vault_items").use { r -> r.next(); assertEquals("", r.getString(1)); assertEquals(0, r.getInt(2)); assertNull(r.getObject(3)) }
            for (t in listOf("job_descriptions", "interview_attempts", "study_sessions")) c.createStatement().executeQuery("SELECT COUNT(*) FROM $t").use { r -> assertNotNull(r.next()) }
        }
        assertTrue(SchemaV7.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME|ON CONFLICT|ALTER)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
}
