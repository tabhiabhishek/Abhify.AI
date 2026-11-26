package ai.abhify.android

import ai.abhify.android.learn.BrainSource
import ai.abhify.android.learn.StudyAi
import ai.abhify.android.learn.StudyCore
import ai.abhify.android.learn.StudyData
import ai.abhify.android.learn.StudyError
import ai.abhify.android.learn.StudyExtras
import ai.abhify.android.learn.StudyService
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Study 7.2.0-dev.2 on Android with a scripted fake AI: mock interviews, resume tailoring, the Personal AI Brain opt-in,
 * the model quality check, the course streak fix, and the pure helpers for the reminder, lesson packs, widget and report.
 */
class StudyDev2Test {
    private val tracks = File("src/main/assets/study_tracks.json").readText(Charsets.UTF_8)
    private val tax = File("src/main/assets/skills_taxonomy.json").readText(Charsets.UTF_8)
    private val terms = StudyCore.parseTaxonomy(tax)
    private var day = LocalDate.parse("2026-12-01")
    private val clock = 1_795_000_000_000L                       // 2026-11-18 (UTC)
    private val replies = ArrayDeque<String>()
    private val prompts = ArrayList<String>()
    private val ai = StudyAi { p, _, _ -> prompts += p; replies.removeFirstOrNull() ?: error("no scripted reply") }
    private val dir = Files.createTempDirectory("abhify-study2").toFile()
    private var saves = 0
    private val brain = object : BrainSource {
        override fun memories() = listOf(BrainSource.Memory(1, "Become an ML platform engineer by June", true, "Career goals"),
            BrainSource.Memory(2, "Works at a fintech on payment APIs", false, "Professional profile"),
            BrainSource.Memory(3, "Secret private note that must not be shared", false, "General"))
        override fun projects() = listOf(BrainSource.Project("7", "Fraud detector", "A Kafka + PyTorch side project"), BrainSource.Project("8", "Other", "Not shared"))
    }
    private fun service(b: BrainSource = BrainSource.NONE) =
        StudyService(File(dir, "study.json"), { tracks }, { tax }, ai, { day }, { clock }, Random(7), ZoneOffset.UTC, b, onSaved = { saves++ })
    private val svc = service(brain)

    private inline fun expectError(msg: String, block: () -> Unit) {
        try { block(); fail("expected: $msg") } catch (e: StudyError) { assertTrue(e.message, e.message!!.contains(msg)) }
    }

    private val jd = "Senior Backend Engineer\nRequirements:\n- Python and SQL\n- Redis caching\n- Kubernetes in production\n"

    // ------------------------------------------------------------------ mock interview
    @Test fun mockStartAnswerFinishSummary() = runBlocking {
        svc.makePlan(jd)                                                      // no AI: the plan's gaps are the interview's skills
        val skills = svc.state().plan!!.gaps.map { it.skill }.take(6)
        replies += JSONObject().put("questions", JSONArray(listOf(JSONObject().put("skill", skills[0]).put("question", "How have you used ${skills[0]} at work?"),
            JSONObject().put("skill", "Not asked").put("question", "Ignored question")))).toString()
        val m = svc.mockStart(4)
        assertEquals(listOf("technical", "behavioural", "technical", "behavioural"), m.questions.map { it.kind })
        assertEquals("How have you used ${skills[0]} at work?", m.questions[0].question)
        assertEquals("Explain ${skills[1]} and describe how you have used it in a real project.", m.questions[2].question)   // not written by the AI: the fallback
        assertEquals("leadership", m.questions[1].competency)
        assertEquals("plan", m.source)
        expectError("Say or type your answer first") { svc.mockAnswer(m.id, 0, " a ", 10) }
        expectError("Unknown question") { svc.mockAnswer(m.id, 9, "A real answer", 10) }

        val answer = "I used Python daily for data pipelines. First I profiled the job, then I fixed the slow step."
        replies += """{"scores": {"relevance": 4, "depth": 3, "structure": 5}, "evidence": ["used Python daily for data pipelines", "I led 40 people"], "tips": ["Add a number."]}"""
        val a = svc.mockAnswer(m.id, 0, answer, 95)
        assertEquals(12, a.feedback!!.total); assertNull(a.note)
        assertEquals(listOf("used Python daily for data pipelines"), a.feedback!!.evidence)   // a quote not in the answer is dropped
        assertTrue(prompts.last().contains("Answer:\n$answer"))

        // unusable scores twice: the answer is saved without feedback, with a note
        replies += """{"scores": {"relevance": 6, "depth": 3, "structure": 5}}"""; replies += """{"scores": {"relevance": true, "depth": 3, "structure": 5}}"""
        val b = svc.mockAnswer(m.id, 1, "I resolved a conflict by listening first.", 5000)
        assertNull(b.feedback); assertTrue(b.note!!.contains("weren't usable"))
        val saved = svc.mockGet(m.id).questions[1]
        assertEquals("I resolved a conflict by listening first.", saved.answer); assertEquals(3600, saved.seconds)

        val done = svc.mockFinish(m.id)
        assertTrue(done.finished)
        val sm = done.summary!!
        assertEquals(1, sm.answered); assertEquals(80, sm.percent); assertEquals("depth", sm.weakest); assertEquals(3.0, sm.means["depth"]!!, 0.0)
        val list = svc.mockList()
        assertEquals(1, list.size); assertEquals(2, list[0].answered); assertEquals(4, list[0].questions)
        assertTrue(svc.mockDelete(m.id)); assertFalse(svc.mockDelete(m.id))
        assertTrue(svc.mockList().isEmpty())
        expectError("Unknown interview session") { svc.mockGet(m.id) }
    }

    @Test fun mockWithoutPlanUsesWeakestSkillsAndKeepsTwenty() = runBlocking {
        val q = StudyData.Question("k1", "Question one here?", listOf("a", "b", "c", "d"), 0, "x", "t", "medium", "SQL", "", "", null)
        svc.answer(q, false, "SQL"); svc.answer(q.copyKey("k2"), true, "Python")
        replies += """{"questions": [{"skill": "SQL", "question": "Write a join?"}]}"""
        val m = svc.mockStart(3)
        assertEquals("practice", m.source)
        assertEquals(listOf("SQL", null, "Python"), m.questions.map { it.skill })       // weakest first, then behavioural, then the next skill
        assertTrue(prompts.last().contains("SQL, Python"))
        repeat(21) { replies += """{"questions": []}"""; svc.mockStart(3) }
        assertEquals(StudyService.MAX_MOCKS, svc.mockList().size)
    }

    private fun StudyData.Question.copyKey(k: String) = StudyData.Question(k, "Another question?", options, answer, explanation, topic, difficulty, skill, module, moduleId, courseId)

    // ------------------------------------------------------------------ resume tailoring
    @Test fun tailoringRejectsAddedNumbersAndSkillsAndStoresNothing() = runBlocking {
        val resume = "Jane Doe\n- Built REST APIs in Python serving 10k users\n- Cut latency 30% with Redis caching across services\n- Wrote unit tests for the billing module in Java\n"
        val bullets = StudyService.resumeBullets(resume)
        assertEquals(3, bullets.size)
        val jdSk = StudyCore.extractSkills(jd, terms); val jdAll = jdSk.required + jdSk.preferred
        val pick = StudyService.rankBullets(bullets, jdAll, terms)
        val k = { needle: String -> pick.indexOfFirst { bullets[it].contains(needle) } }
        replies += JSONObject().put("bullets", JSONArray(listOf(
            JSONObject().put("i", k("REST")).put("text", "Designed Python REST APIs for 10k users"),
            JSONObject().put("i", k("latency")).put("text", "Cut latency 45% using Redis on Kubernetes")))).toString()
        val t = svc.tailor(resume, jd)
        val byOrig = t.bullets.associateBy { it.original }
        assertEquals("Designed Python REST APIs for 10k users", byOrig.getValue(bullets[0]).rewritten)
        val lat = byOrig.getValue(bullets[1])
        assertNull(lat.rewritten); assertTrue(lat.rejected!!.contains("45") && lat.rejected!!.contains("Kubernetes"))
        assertEquals("The AI didn't rewrite this one.", byOrig.getValue(bullets[2]).rejected)
        assertTrue("Kubernetes" in t.missing && "Python" in t.covered)
        assertFalse(File(dir, "study.json").exists())                          // nothing stored
        expectError("Paste the job description") { svc.tailor(resume, "short") }
        expectError("No resume bullet points were found") { svc.tailor("too short", jd) }
    }

    @Test fun resumeBulletsFallBackToSentences() {
        val text = "Led the migration of our billing platform to a new stack over six months. Reduced on-call pages by working with SRE on alerts.\nshort line"
        assertEquals(listOf("Led the migration of our billing platform to a new stack over six months.", "Reduced on-call pages by working with SRE on alerts."),
            StudyService.resumeBullets(text))
        assertEquals(listOf("Numbered achievement line here"), StudyService.resumeBullets("1. Numbered achievement line here\n- tiny"))
    }

    // ------------------------------------------------------------------ Personal AI Brain
    @Test fun brainIsOffByDefaultAndSharesOnlyTickedItems() = runBlocking {
        val c = svc.createCourse("cloud")
        val lid = svc.modulesOf(c)[0].lessons[0].id
        assertNull(svc.courseDetail(c.id).course.brain)
        replies += GOOD_LESSON
        val first = svc.lesson(c.id, lid)
        assertFalse(prompts.last().contains("Learner context")); assertTrue(first.entry.used.isEmpty())
        val src = svc.brainSources()
        assertEquals(listOf(1L), src.goals.map { it.id }); assertEquals(listOf(2L, 3L), src.memories.map { it.id }); assertEquals(2, src.projects.size)

        svc.setBrain(c.id, true, listOf(1L, 2L, 99L), listOf("7"))                 // 99 doesn't exist (deleted since): skipped
        replies += GOOD_LESSON
        val l = svc.lesson(c.id, lid, refresh = true)
        val p = prompts.last()
        assertTrue(p.startsWith("Learner context, shared by the learner for this course only"))
        assertTrue(p.contains("- Goal: Become an ML platform engineer by June") && p.contains("- About me: Works at a fintech") && p.contains("- Project Fraud detector: A Kafka"))
        assertFalse(p.contains("Secret private note") || p.contains("Not shared"))
        assertEquals(listOf("Goal: Become an ML platform engineer by June", "Note: Works at a fintech on payment APIs", "Project: Fraud detector"), l.entry.used)
        assertEquals(l.entry.used, svc.lesson(c.id, lid).entry.used)            // kept with the cached lesson
        replies += "Answer."
        svc.tutor(c.id, lid, "Why?"); assertTrue(prompts.last().contains("- Project Fraud detector"))

        svc.setBrain(c.id, false, listOf(1L), emptyList())
        replies += "Answer."
        svc.tutor(c.id, lid, "Why?"); assertFalse(prompts.last().contains("Learner context"))
        val other = svc.createCourse("ds")
        assertEquals("" to emptyList<String>(), svc.brainContext(svc.courseDetail(other.id).course))   // per course
        assertTrue(svc.dashboard(c.id).history.any { it.kind == "brain" && it.detail == "Personal context off" })
    }

    // ------------------------------------------------------------------ quality check
    @Test fun qualityVerdictIsCachedPerModel() = runBlocking {
        replies += """{"questions": [{"q": "What does append do?", "options": ["a", "b", "c", "d"], "answer": 1, "explanation": "x"}]}"""
        replies += "## In simple words\nx\n## Going deeper\ny\n## Common mistakes\nz\n## Interview questions\nq"
        replies += "17 x 23 = 391"
        val q = svc.qualityCheck("tiny-model")
        assertEquals("good", q.verdict); assertEquals(3, q.probes.values.count { it })
        assertEquals("good", svc.qualityStatus("tiny-model")!!.verdict)
        replies += "not json"; replies += "## In simple words\n## Going deeper\n## Common mistakes"; replies += "It is 390."
        val w = svc.qualityCheck("other")
        assertEquals("poor", w.verdict); assertTrue(w.advice.contains("larger model"))
        assertEquals("good", svc.qualityStatus("tiny-model")!!.verdict)           // each model keeps its own verdict
        assertNull(svc.qualityStatus("never-checked")); assertNull(svc.qualityStatus(null))
        assertTrue(File(dir, "study.json").readText().contains("\"quality\""))
    }

    @Test fun probeChecks() {
        assertFalse(StudyService.probeOk("json_mcq", """{"questions": [{"q": "Q?", "options": ["a", "b", "c"], "answer": 1}]}"""))
        assertFalse(StudyService.probeOk("json_mcq", """{"questions": [{"q": "Q?", "options": ["a", "b", "c", "d"], "answer": 4}]}"""))
        assertTrue(StudyService.probeOk("json_mcq", "Here: {\"questions\": [{\"q\": \"Q?\", \"options\": [\"a\", \"b\", \"c\", \"d\"], \"answer\": 3}]} done"))
        assertTrue(StudyService.probeOk("lesson_sections", "In simple words\n---\nx\n**Going deeper**\ny\n## Common mistakes\nz"))
        assertFalse(StudyService.probeOk("fact", "3910"))
    }

    // ------------------------------------------------------------------ course streak fix
    @Test fun streakCountsARightAnswerOnlyDayWithoutAddingTime() = runBlocking {
        val c = svc.createCourse("cloud")
        val m = svc.modulesOf(c)[0]
        val q = StudyData.Question("q1", "What is a region?", listOf("a", "b", "c", "d"), 0, "x", m.title, "medium", "AWS", m.title, m.id, c.id)
        assertEquals(0, svc.dashboard(c.id).streak)
        svc.answer(q, true)
        val d = svc.dashboard(c.id)
        assertEquals(1, d.streak); assertEquals(0L, d.seconds); assertEquals(0L, d.secondsWeek)
        assertTrue(d.history.isEmpty())
        day = day.plusDays(1)
        assertEquals(1, svc.dashboard(c.id).streak)                                 // yesterday still counts
        // a wrong answer's date and a history entry's local date count too
        val c2 = svc.createCourse("ds")
        val m2 = svc.modulesOf(c2)[0]
        val wrong = StudyData.Question("q2", "Mean vs median?", listOf("a", "b", "c", "d"), 0, "x", m2.title, "medium", "Statistics", m2.title, m2.id, c2.id)
        svc.answer(wrong, false)
        assertEquals(1, svc.dashboard(c2.id).streak)
    }

    @Test fun prefetchedLessonIsNotStudyActivity() = runBlocking {
        val c = svc.createCourse("cloud")
        val lid = svc.modulesOf(c)[0].lessons[0].id
        replies += GOOD_LESSON
        assertFalse(svc.lesson(c.id, lid, prefetch = true).cached)
        val d = svc.dashboard(c.id)
        assertTrue(d.history.isEmpty()); assertEquals(0, d.streak); assertEquals(0, svc.state().streak)
        assertTrue(svc.lesson(c.id, lid).cached)
        assertTrue(saves > 0)                                                        // the widget hook runs after saves
    }

    // ------------------------------------------------------------------ pure helpers: reminder, packs, widget, report
    @Test fun reminderText() {
        assertEquals("3 flashcards and 2 missed questions due", StudyExtras.reminderText(3, 2, null))
        assertEquals("1 flashcard due. Next lesson: Pods", StudyExtras.reminderText(1, 0, "Pods"))
        assertEquals("1 missed question due", StudyExtras.reminderText(0, 1, " "))
        assertEquals("Next lesson: Pods", StudyExtras.reminderText(0, 0, "Pods"))
        assertNull(StudyExtras.reminderText(0, 0, null))
    }

    @Test fun reminderUsesRealCounts() = runBlocking {
        assertNull(svc.snapshot().let { StudyExtras.reminderText(it.cardsDue, it.questionsDue, it.nextLesson) })   // nothing due, no course: no notification
        val c = svc.createCourse("cloud")
        val s = svc.snapshot()
        assertEquals(svc.modulesOf(c)[0].lessons[0].title, s.nextLesson); assertEquals(c.title, s.nextCourse)
        val m = svc.modulesOf(c)[0]
        svc.answer(StudyData.Question("q1", "What is a region?", listOf("a", "b", "c", "d"), 0, "x", m.title, "medium", "AWS", m.title, m.id, c.id), false)
        day = day.plusDays(1)
        assertEquals(1, svc.snapshot().questionsDue)
        assertTrue(StudyExtras.reminderText(0, svc.snapshot().questionsDue, null)!!.startsWith("1 missed question due"))
    }

    @Test fun packSelection() = runBlocking {
        val mods = listOf(StudyCore.Module("a", "A", emptyList(), listOf(StudyCore.Lesson("a1"), StudyCore.Lesson("a2"))),
            StudyCore.Module("b", "B", emptyList(), listOf(StudyCore.Lesson("b1"), StudyCore.Lesson("b2"), StudyCore.Lesson("b3"))))
        val courses = listOf(
            StudyExtras.PackCourse("c1", true, mods, done = setOf("a1"), optional = setOf("a"), cached = setOf("b1")),
            StudyExtras.PackCourse("c2", false, mods, emptySet(), emptySet(), emptySet()),             // goal not chosen: skipped
            StudyExtras.PackCourse("c3", true, mods, setOf("a1", "a2", "b1", "b2", "b3"), emptySet(), emptySet()))
        assertEquals(listOf("c1" to "b2", "c1" to "b3", "c1" to "a2"), StudyExtras.packSelection(courses))   // core first, cached and done skipped
        val c = svc.createCourse("cloud")
        assertTrue(StudyExtras.packSelection(svc.packCourses()).isEmpty())          // no goal chosen yet
        svc.setPrefs(c.id, "Cloud Engineer", "beginner", 60)
        val picks = StudyExtras.packSelection(svc.packCourses())
        assertEquals(svc.modulesOf(c).flatMap { it.lessons }.take(3).map { c.id to it.id }, picks)
    }

    @Test fun widgetContent() {
        assertEquals(StudyExtras.Widget("🔥 1 day streak", "3 items due (2 cards, 1 missed question)", "Next: Pods"), StudyExtras.widgetContent(1, 2, 1, "Pods", 1))
        assertEquals(StudyExtras.Widget("🔥 0 days streak", "Nothing due today", "Pick a course in Study"), StudyExtras.widgetContent(0, 0, 0, null, 0))
        assertEquals("All lessons done", StudyExtras.widgetContent(4, 0, 0, null, 2).next)
    }

    @Test fun reportLinesUseRecordedNumbersOnly() = runBlocking {
        val c = svc.createCourse("cloud")
        svc.setPrefs(c.id, "Cloud Engineer", "beginner", 45)
        val lid = svc.modulesOf(c)[0].lessons[0].id
        svc.setDone(c.id, lid, true); svc.addTime(c.id, 600)
        val d = svc.dashboard(c.id)
        val lines = StudyExtras.reportLines(d, clock, ZoneOffset.UTC)
        assertEquals("# ${c.title}: progress report", lines[0])
        assertTrue("Goal: Cloud Engineer" in lines)
        assertTrue("Generated: 2026-11-18" in lines)
        assertTrue("Lessons: 1 of ${d.lessonsTotal}" in lines && "Course progress: ${d.progress}%" in lines)
        assertTrue("Score: no answers yet" in lines && "Time studied: 10 min (10 min this week)" in lines && "Streak: 1 day" in lines)
        assertTrue("Not planned yet" in lines)
        assertTrue(lines.any { it.startsWith("- 2026-11-18") && it.contains("Finished: ") })
        assertEquals("Recorded on this phone; AI-written content not included.", lines.last())
        assertTrue(lines.count { it.startsWith("- ") && it.contains("·") } <= 10)
    }

    @Test fun newFieldsSurviveAReload() = runBlocking {
        val c = svc.createCourse("cloud")
        svc.setBrain(c.id, true, listOf(2L), listOf("7"))
        replies += "x"; replies += "x"; replies += "391"
        svc.qualityCheck("m")
        replies += """{"questions": []}"""
        val m = svc.mockStart(3)
        val again = service(brain)
        assertEquals(listOf(2L), again.courseDetail(c.id).course.brain!!.memories)
        assertEquals("poor", again.qualityStatus("m")!!.verdict)
        assertEquals(3, again.mockGet(m.id).questions.size)
        assertNotNull(StudyData.parse(File(dir, "study.json").readText()).mocks.firstOrNull())
    }

    companion object {
        val GOOD_LESSON = listOf("In simple words", "Going deeper", "Advanced: production and architecture", "Real-world example", "Common mistakes")
            .joinToString("\n\n") { "## $it\n" + "Cloud computing explained in plain words. ".repeat(3) }
    }
}
