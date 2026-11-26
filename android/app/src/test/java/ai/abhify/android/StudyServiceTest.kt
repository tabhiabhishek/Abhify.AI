package ai.abhify.android

import ai.abhify.android.learn.StudyAi
import ai.abhify.android.learn.StudyCore
import ai.abhify.android.learn.StudyData
import ai.abhify.android.learn.StudyError
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
import kotlin.random.Random

/** Study 7.2 storage and AI flows with a scripted fake AI: validators, progress kept across preference changes, real counts. */
class StudyServiceTest {
    private val tracks = File("src/main/assets/study_tracks.json").readText(Charsets.UTF_8)
    private val tax = File("src/main/assets/skills_taxonomy.json").readText(Charsets.UTF_8)
    private var day = LocalDate.parse("2026-12-01")
    private var clock = 1_800_000_000_000L
    private val replies = ArrayDeque<String>()
    private val prompts = ArrayList<String>()
    private val ai = StudyAi { p, _, _ -> prompts += p; replies.removeFirstOrNull() ?: error("no scripted reply") }
    private val dir = Files.createTempDirectory("abhify-study").toFile()
    private fun service() = StudyService(File(dir, "study.json"), { tracks }, { tax }, ai, { day }, { clock }, Random(7))
    private val svc = service()

    private fun mcqJson(vararg qs: String) = JSONObject().put("questions", JSONArray(qs.map {
        JSONObject().put("q", it).put("options", JSONArray(listOf("Alpha one", "Beta two", "Gamma three", "Delta four"))).put("answer", 2).put("explanation", "Because.")
    })).toString()

    private inline fun expectError(msg: String, block: () -> Unit) {
        try { block(); fail("expected: $msg") } catch (e: StudyError) { assertTrue(e.message, e.message!!.contains(msg)) }
    }

    @Test fun overviewDropsMadeUpModuleIds() {
        val mods = listOf("a", "b", "c", "d").map { StudyCore.Module(it, it.uppercase(), emptyList(), listOf(StudyCore.Lesson("$it-1", "x"))) }
        val o = JSONObject("""{"summary": "A course.", "objectives": ["o1"], "optional": ["a", "zzz", "b", "c"], "focus": {"b": "matters", "nope": "made up", "c": " "}}""")
        val ov = StudyService.validateOverview(o, mods, 1)
        assertEquals(listOf("a", "b"), ov.optional)                         // made-up id dropped; at most modules - 2
        assertEquals(mapOf("b" to "matters"), ov.focus)
        assertEquals(3, ov.completion.size)                                 // default criteria
        expectError("overview was incomplete") { StudyService.validateOverview(JSONObject("{\"summary\": \" \"}"), mods, 1) }
    }

    @Test fun coachScoresMustBeOneToFive() {
        val ok = JSONObject("""{"scores": {"situation": 4, "task": 3.6, "action": 5, "result": 1}, "missing": ["numbers"], "tips": [], "improved": "Better."}""")
        val c = StudyService.validateCoach(ok, 1)
        assertEquals(mapOf("situation" to 4, "task" to 4, "action" to 5, "result" to 1), c.scores)
        for (bad in listOf("""{"scores": {"situation": 6, "task": 3, "action": 5, "result": 1}}""", """{"scores": {"situation": 0, "task": 3, "action": 5, "result": 1}}""",
                "{\"scores\": {\"situation\": \"4\", \"task\": 3, \"action\": 5, \"result\": 1}}", """{"scores": {"situation": 4, "task": 3, "action": 5}}"""))
            expectError("feedback was incomplete") { StudyService.validateCoach(JSONObject(bad), 1) }
    }

    @Test fun cardsNeedThreeUsable() {
        val two = JSONObject().put("cards", JSONArray(listOf(JSONObject().put("front", "What is X?").put("back", "Y."), JSONObject().put("front", "Why?").put("back", "Short front"),
            JSONObject().put("front", "What is Z?").put("back", ""), JSONObject().put("front", "What is W?").put("back", "V."))))
        expectError("too few usable flashcards") { StudyService.validateCards(two) }
        val arr = JSONArray((1..4).map { JSONObject().put("front", "Question $it?").put("back", "Answer $it.") })
        assertEquals(4, StudyService.validateCards(arr).size)               // a bare array is accepted too
    }

    @Test fun outlineNeedsThreeModulesWithTwoLessons() {
        val terms = StudyCore.parseTaxonomy(tax)
        val short = JSONObject("""{"title": "K8s", "modules": [{"title": "Pods", "lessons": ["a", "b"]}, {"title": "Services", "lessons": ["a"]}, {"title": "Helm", "lessons": ["a", "b", "c"]}, {"title": "", "lessons": ["a", "b"]}]}""")
        expectError("outline was too short") { StudyService.validateOutline(short, "Kubernetes", terms) }
        val good = JSONObject("""{"title": "Kubernetes", "modules": [{"title": "Pods", "lessons": ["a", "b"]}, {"title": "Docker images", "lessons": ["a", "b", "c"]}, {"title": "Helm", "lessons": ["a", "b"]}]}""")
        val (title, mods) = StudyService.validateOutline(good, "Kubernetes", terms)
        assertEquals("Kubernetes", title)
        assertEquals(listOf("m1", "m2", "m3"), mods.map { it.id }); assertEquals(listOf("m2-1", "m2-2", "m2-3"), mods[1].lessons.map { it.id })
        assertTrue(mods[1].skills.containsAll(listOf("Kubernetes", "Docker")))
        expectError("didn't return a course outline") { StudyService.validateOutline(JSONArray(), "x", terms) }
    }

    @Test fun mcqBatchValidationAndDedupe() {
        val m = StudyCore.Module("m", "Mod", listOf("Python"), emptyList())
        val o = JSONObject().put("questions", JSONArray(listOf(
            JSONObject().put("q", "What does len() return in Python?").put("options", JSONArray(listOf("Length", "Type", "Id", "Hash"))).put("answer", 0).put("explanation", "It is the size."),
            JSONObject().put("q", "What does LEN() return in python??").put("options", JSONArray(listOf("Length", "Type", "Id", "Hash"))).put("answer", 0).put("explanation", "dup"),   // same normalised text
            JSONObject().put("q", "Which keyword defines a function?").put("options", JSONArray(listOf("def", "def ", "fun", "fn"))).put("answer", 0).put("explanation", "x"),  // options not different
            JSONObject().put("q", "Which keyword starts a loop?").put("options", JSONArray(listOf("for", "if", "def", "try"))).put("answer", 4).put("explanation", "x"),       // answer out of range
            JSONObject().put("q", "Short?").put("options", JSONArray(listOf("a", "b", "c", "d"))).put("answer", 1).put("explanation", "x"),                                  // too short
            JSONObject().put("q", "Which keyword handles errors?").put("options", JSONArray(listOf("try", "if", "def", "for"))).put("answer", "0").put("explanation", ""),     // no explanation
            JSONObject().put("q", "Already asked question here?").put("options", JSONArray(listOf("a1", "b1", "c1", "d1"))).put("answer", 1).put("explanation", "seen"),
            JSONObject().put("q", "Which keyword imports a module?").put("options", JSONArray(listOf("import", "include", "use", "require"))).put("answer", "0").put("explanation", "ok"))))
        val b = StudyService.validateMcq(o, setOf(StudyService.normQ("Already asked question here?")), 5, "Mod (C)", "medium", "Python", m, "c1", Random(1))
        assertEquals(listOf("What does len() return in Python?", "Which keyword imports a module?"), b.questions.map { it.q })
        assertEquals(6, b.dropped)
        for (q in b.questions) { assertEquals(setOf("Length", "Type", "Id", "Hash").takeIf { q.q.startsWith("What") } ?: setOf("import", "include", "use", "require"), q.options.toSet())
            assertTrue(q.options[q.answer] == "Length" || q.options[q.answer] == "import"); assertEquals("m", q.moduleId); assertEquals("c1", q.courseId) }
        // the same keys as the Web app's qKey (values computed with frontend/study-local.js's function)
        assertEquals("w17063", StudyService.qKey("A b!"))
        assertEquals("wc0e2d5df", StudyService.qKey("What does len() return in Python? It is a long question text indeed"))
    }

    @Test fun nextBatchRotatesModuleAndSkipsOptional() = runBlocking {
        val c = svc.createCourse("aiml")
        val mods = svc.modulesOf(c).map { it.id }
        replies += mcqJson("Question number one about this?"); replies += mcqJson("Question number two about this?")
        val b0 = svc.courseMcq(c.id, 0); val b1 = svc.courseMcq(c.id, 1)
        assertEquals(mods[0], b0.moduleId); assertEquals(mods[1], b1.moduleId)
        assertTrue(prompts[1].contains("Do not repeat") .not())                                       // avoid list is the caller's
        // the same question again is never shown twice (seen across batches)
        replies += mcqJson("Question number one about this?")
        expectError("didn't pass the checks") { svc.courseMcq(c.id, 2) }
        // optional modules (from the overview) are skipped in rotation
        svc.setPrefs(c.id, "Software Engineer", "advanced", 60)
        replies += JSONObject().put("summary", "S").put("optional", JSONArray(listOf(mods[0], mods[1]))).toString()
        svc.buildOverview(c.id)
        val cc = svc.courseDetail(c.id).course
        assertEquals(mods[2], svc.mcqModule(cc, 0, null, false).id)
        assertEquals(mods[3], svc.mcqModule(cc, 1, null, false).id)
        assertEquals(mods[0], svc.mcqModule(cc, 5, mods[0], false).id)                              // one chosen module
        replies += mcqJson("Avoid list question number three?")
        svc.courseMcq(c.id, 3, avoid = listOf("Earlier question text"))
        assertTrue(prompts.last().contains("Do not repeat these earlier questions: Earlier question text"))
        assertTrue(prompts.last().contains("senior candidate"))                                        // level follows the course goal
    }

    @Test fun prefsChangeKeepsProgressAndDashboardIsReal() = runBlocking {
        val c = svc.createCourse("swe")
        assertEquals(c.id, svc.createCourse("swe").id)                                                // one course per track
        assertFalse(svc.dashboard(c.id).prefs.chosen)
        svc.setPrefs(c.id, "Software Engineer", "beginner", 45)
        val lessons = svc.modulesOf(c).flatMap { m -> m.lessons.map { it.id } }
        svc.setDone(c.id, lessons[0], true); svc.setDone(c.id, lessons[1], true); svc.setExercise(c.id, lessons[0], true)
        svc.addTime(c.id, 5_000)                                                                        // one report counts at most 600 s
        svc.addTime(c.id, 120)
        val q = StudyData.Question("k1", "Some question about arrays?", listOf("a", "b", "c", "d"), 1, "why", "t", "medium", "Python", "Data structures", "swe-dsa", c.id)
        svc.answer(q, true); svc.answer(StudyData.Question("k2", "Another question about arrays?", q.options, 1, "why", "t", "medium", "Python", "Data structures", "swe-dsa", c.id), false)
        var d = svc.dashboard(c.id)
        assertEquals(2, d.lessonsDone); assertEquals(lessons.size, d.lessonsTotal)
        assertEquals(StudyCore.pyRound(200.0 / lessons.size), d.progress)
        assertEquals(720L, d.seconds); assertEquals(720L, d.secondsWeek)
        assertEquals(2, d.assessment.answered); assertEquals(50, d.assessment.percent)
        assertEquals(1, d.exercisesDone); assertEquals(1, d.streak)
        assertEquals(lessons[2], d.nextLesson?.id)
        assertEquals(1, d.revision.wrongThisWeek)
        assertEquals(0, d.revision.questionsDue)                                                       // a missed question is due tomorrow, not today
        day = day.plusDays(1)
        assertEquals(1, svc.dashboard(c.id).revision.questionsDue)
        // change the goal and level: everything recorded stays
        svc.setPrefs(c.id, "Robotics engineer", "Strong in Python", 90)
        d = svc.dashboard(c.id)
        assertEquals("custom", d.prefs.levelKey); assertEquals("Robotics engineer", d.prefs.target); assertEquals(90, d.prefs.minutes)
        assertEquals(2, d.lessonsDone); assertEquals(720L, d.seconds); assertEquals(2, d.assessment.answered); assertEquals(1, d.exercisesDone)
        assertNull(d.overview)
        assertEquals(3, d.schedule.perDay)
        assertTrue(d.history.first().kind == "prefs")
        // survives a restart (read back from study.json)
        assertEquals(2, service().dashboard(c.id).lessonsDone)
    }

    @Test fun lessonCacheCardsAndDeleteCourse() = runBlocking {
        val c = svc.createCourse("cloud")
        val lid = svc.modulesOf(c)[0].lessons[0].id
        expectError("Open the lesson first") { svc.makeCards(c.id, lid) }
        replies += "short"; replies += "## In simple words\nStill too short."
        expectError("lesson was incomplete") { svc.lesson(c.id, lid) }                                  // retried once, then the error
        replies += "short"; replies += GOOD_LESSON                                                         // the automatic retry succeeds
        assertFalse(svc.lesson(c.id, lid).cached)
        assertTrue(svc.lesson(c.id, lid).cached)                                                       // no second AI call
        assertTrue(svc.courseDetail(c.id).modules[0].lessons[0].ready)
        replies += JSONObject().put("cards", JSONArray((1..4).map { JSONObject().put("front", "Front $it?").put("back", "Back $it.") })).toString()
        assertEquals(4, svc.makeCards(c.id, lid).size)
        assertEquals(4, svc.dueCards(c.id).size); assertEquals(4, svc.dueCards(c.id, weekly = true).size)
        val (cardId, _) = svc.dueCards().first()
        val g = svc.gradeCard(cardId, true)
        assertEquals("2026-12-04", g.due); assertEquals(3, svc.dueCards(c.id).size)
        replies += "Tutor answer in Markdown."
        assertEquals("Tutor answer in Markdown.", svc.tutor(c.id, lid, "Why?"))
        assertTrue(prompts.last().contains("Cloud computing explained in plain words.") && prompts.last().contains("Their question: Why?"))
        val other = svc.createCourse("ds")
        svc.deleteCourse(c.id)
        assertEquals(0, svc.dueCards().size)
        assertEquals(listOf(other.id), svc.state().courses.map { it.id })
        expectError("Unknown course") { svc.dashboard(c.id) }
    }

    @Test fun capstonePlanAndStories() = runBlocking {
        val c = svc.createCourse("aiml")
        replies += """{"title": "RAG bot", "brief": "Build it.", "milestones": ["one", "two"]}"""
        expectError("capstone project was incomplete") { svc.makeCapstone(c.id) }
        replies += """```json
{"title": "RAG bot", "brief": "Build it.", "milestones": ["one", "two", "three", "four"], "stretch": ["x"]}
```"""
        assertEquals(4, svc.makeCapstone(c.id).milestones.size)
        svc.capstoneStep(c.id, 1, true)
        assertEquals(1, svc.dashboard(c.id).capstone?.done)
        expectError("doesn't exist") { svc.capstoneStep(c.id, 9, true) }

        expectError("Paste the job description") { svc.makePlan("short") }
        val jd = StudyCoreTest.C.getJSONObject("extract").getJSONObject("ml_engineer").getString("jd")
        val p = svc.makePlan(jd, "Python, SQL, Docker and Git", "2026-12-06", 60)
        assertEquals(5, p.days.size); assertEquals(listOf("Python", "SQL", "Docker", "Git"), p.resumeSkills)
        assertEquals(14, svc.makePlan(jd).days.size)
        svc.planItem(1, 0, true)
        val st = svc.state()
        assertTrue(st.today.first().done)
        assertEquals(p.gaps.size, st.readiness.skills.size)
        expectError("doesn't exist") { svc.planItem(99, 0, true) }

        val s = svc.saveStory(StudyService.StoryInput(null, "Migration rescue", listOf("leadership", "made-up"), "We had a failing migration.", "Fix it.", "I wrote a plan.", "Done on time."))
        assertEquals(listOf("leadership"), s.competencies)
        replies += """{"scores": {"situation": 4, "task": 3, "action": 5, "result": 2}, "missing": ["numbers"], "tips": ["add metrics"], "improved": "Tighter."}"""
        val fb = svc.coach(s.id)
        assertEquals(14, fb.scores.values.sum())
        assertNotNull(svc.stories().single().coach)
        assertEquals(1, svc.state().competencies["leadership"])
    }

    @Test fun corruptFileIsKeptAsideAndBackupRestored() = runBlocking {
        val f = File(dir, "study.json")
        val c = svc.createCourse("swe")
        svc.setDone(c.id, svc.modulesOf(c)[0].lessons[0].id, true)              // second save: study.json.bak holds the first
        assertTrue(File(dir, "study.json.bak").isFile)
        f.writeText("{ not json")
        val d = svc.state()                                                       // reads the backup, never overwrites the bad file
        assertEquals(listOf(c.id), d.courses.map { it.id })
        val kept = dir.listFiles()!!.filter { it.name.startsWith("study.corrupt-") }
        assertEquals(1, kept.size); assertEquals("{ not json", kept[0].readText())
        assertEquals(1, JSONObject(f.readText()).getInt("schema"))
        // a file from a newer app is refused and left as it is
        f.writeText(JSONObject().put("schema", 99).toString())
        expectError("newer Abhify.AI") { svc.state() }
        assertEquals(99, JSONObject(f.readText()).getInt("schema"))
    }

    @Test fun historiesLapsesAndFactGuard() = runBlocking {
        val c = svc.createCourse("aiml")
        svc.setPrefs(c.id, "Software Engineer", "beginner", 60)
        svc.setPrefs(c.id, "Cloud Engineer", "advanced", 30)
        val raw = JSONObject(File(dir, "study.json").readText()).getJSONObject("courses").getJSONObject(c.id)
        assertEquals(1, raw.getJSONArray("prefs_history").length())
        assertEquals("Software Engineer", raw.getJSONArray("prefs_history").getJSONObject(0).getJSONObject("prefs").getString("target"))
        val jd = StudyCoreTest.C.getJSONObject("extract").getJSONObject("ml_engineer").getString("jd")
        svc.makePlan(jd); svc.makePlan(jd)
        val hist = JSONObject(File(dir, "study.json").readText()).getJSONArray("plan_history")
        assertEquals(1, hist.length()); assertTrue(hist.getJSONObject(0).has("archived_at"))
        // a skill already Ready moves to the end of a new plan version
        val q = StudyData.Question("", "", emptyList(), 0, "", "", "", "Machine Learning", "", "", null)
        for (t in svc.tracks().tracks) for (m in t.modules) if ("Machine Learning" in m.skills) { val tc = svc.createCourse(t.id); m.lessons.forEach { svc.setDone(tc.id, it.id, true) } }
        repeat(10) { svc.answer(StudyData.Question("k$it", "Q $it about ML basics?", q.options, 0, "", "", "", "Machine Learning", "", "", null), true) }
        val p = svc.makePlan(jd)
        assertEquals("Machine Learning", p.gaps.last().skill); assertEquals("already Ready: light refresh", p.gaps.last().why)
        // a second miss comes back the same day
        val miss = StudyData.Question("km", "Missed twice question text?", listOf("a", "b", "c", "d"), 0, "", "", "", "Python", "", "", null)
        svc.answer(miss, false); assertEquals(0, svc.dueReviews().size)
        day = day.plusDays(1); svc.answer(miss, false)
        assertEquals(1, svc.dueReviews().size); assertEquals(2, svc.dueReviews().single().review.lapses)
        // the tighter STAR version may not add numbers
        val fb = StudyService.guardFacts(StudyData.Coach(mapOf(), emptyList(), emptyList(), "We cut costs by 40%.", 1), "We cut costs a lot in 2023.")
        assertEquals("", fb.improved); assertTrue(fb.tips.single().contains("numbers that aren't in your story"))
        val same = StudyService.guardFacts(StudyData.Coach(mapOf(), emptyList(), emptyList(), "In 2023 we cut costs.", 1), "We cut costs a lot in 2023.")
        assertEquals("In 2023 we cut costs.", same.improved)
    }

    @Test fun lessonHeadingsAreNormalisedAndChecked() {
        val raw = """In simple words
---------------
Text here.

**Going deeper**
More text.

1. Common mistakes
Some.
2) Practice exercise
Do this.
## Interview questions
Q and A.
""" + "Filler sentence for length. ".repeat(12)
        val t = StudyService.normalizeLesson(raw)
        for (h in listOf("## In simple words", "## Going deeper", "## Common mistakes", "## Practice exercise", "## Interview questions")) assertTrue(h, t.contains(h))
        assertTrue(StudyService.lessonOk(t))
        assertFalse(StudyService.lessonOk(StudyService.normalizeLesson("## In simple words\n## Going deeper\n## Common mistakes\n## Practice exercise\nshort")))   // < 400 chars
        assertFalse(StudyService.lessonOk("## In simple words\n## Going deeper\n## Summary\n" + "x".repeat(500)))                                     // only 2 sections
    }

    @Test fun listFieldsAreSplitAndKeyNamesDropped() {
        val mods = listOf("a", "b", "c").map { StudyCore.Module(it, it, emptyList(), listOf(StudyCore.Lesson("$it-1", "x"))) }
        val o = JSONObject().put("summary", "S").put("objectives", JSONArray(listOf("Build APIs; Write tests; Deploy.", "objectives", "Monitor\nScale")))
            .put("prerequisites", "Python; SQL").put("completion", JSONArray(listOf("completion")))
        val ov = StudyService.validateOverview(o, mods, 1)
        assertEquals(listOf("Build APIs", "Write tests", "Deploy", "Monitor", "Scale"), ov.objectives)
        assertEquals(listOf("Python", "SQL"), ov.prerequisites)
        assertEquals(3, ov.completion.size)                                                    // only a key name: the default criteria
    }

    @Test fun planChangeShowsWhatMovedAndKeepsProgress() = runBlocking {
        val c = svc.createCourse("aiml")
        val mods = svc.modulesOf(c)
        svc.setPrefs(c.id, "Software Engineer", "beginner", 60)
        replies += JSONObject().put("summary", "S").put("optional", JSONArray(listOf(mods[5].id))).toString()
        svc.buildOverview(c.id)
        assertNull(svc.dashboard(c.id).planChange)                                              // first choice: nothing changed
        svc.setDone(c.id, mods[0].lessons[0].id, true)
        svc.setPrefs(c.id, "Senior AI/ML Engineer", "advanced", 60)
        replies += JSONObject().put("summary", "S").put("optional", JSONArray(listOf(mods[0].id))).toString()
        svc.buildOverview(c.id)
        val pc = svc.dashboard(c.id).planChange!!
        assertEquals("Software Engineer", pc.previousTarget); assertEquals(StudyService.LEVELS["beginner"], pc.previousLevel)
        assertEquals(listOf(mods[5].title), pc.nowCore); assertEquals(listOf(mods[0].title), pc.nowOptional); assertEquals(1, pc.kept)
    }

    @Test fun exportAndImportKeepAPreviousCopy() = runBlocking {
        val c = svc.createCourse("swe")
        svc.setDone(c.id, svc.modulesOf(c)[0].lessons[0].id, true)
        val exported = svc.exportJson()
        assertEquals("abhify-study-export-1", JSONObject(exported).getString("format"))
        svc.deleteCourse(c.id)
        assertEquals(0, svc.state().courses.size)
        expectError("isn't an Abhify Study export") { svc.importJson("{\"format\": \"other\"}") }
        val r = svc.importJson(exported)
        assertEquals(1, r.courses)
        assertEquals(1, svc.dashboard(c.id).lessonsDone)
        val before = dir.listFiles()!!.filter { it.name.startsWith("study.before-import-") }
        assertEquals(1, before.size); assertTrue(JSONObject(before[0].readText()).getJSONObject("courses").length() == 0)
    }

    companion object {
        val GOOD_LESSON = listOf("In simple words", "Going deeper", "Advanced: production and architecture", "Real-world example", "Common mistakes")
            .joinToString("\n\n") { "## $it\n" + "Cloud computing explained in plain words. ".repeat(3) }
    }
}
