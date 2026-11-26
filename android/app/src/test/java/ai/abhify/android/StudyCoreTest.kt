package ai.abhify.android

import ai.abhify.android.learn.StudyCore
import ai.abhify.android.learn.StudyCore.toJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Study 7.2: the Kotlin rules reproduce every shared case in backend/tests/data/study_cases.json (the Python reference's
 * output; frontend/study-core.js passes the same file via backend/tests/js/study_harness.js).
 */
class StudyCoreTest {
    companion object {
        private val dataDir: File by lazy {
            var d: File? = File("").absoluteFile
            while (d != null && !File(d, "backend/tests/data/study_cases.json").isFile) d = d.parentFile
            File(d ?: error("repo root (with backend/) not found"), "backend/tests/data")
        }
        val C: JSONObject by lazy { JSONObject(File(dataDir, "study_cases.json").readText(Charsets.UTF_8)) }
        val TAX: List<StudyCore.Term> by lazy { StudyCore.parseTaxonomy(File("src/main/assets/skills_taxonomy.json").readText(Charsets.UTF_8)) }

        /** JSON → plain Kotlin values (maps with sorted keys, lists, Long/Double, null) so results compare structurally. */
        fun canon(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> v.keys().asSequence().sorted().associateWith { canon(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { canon(v.get(it)) }
            is Int -> v.toLong()
            is Long -> v
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else v
            is Number -> v.toDouble().let { if (it == Math.floor(it)) it.toLong() else it }
            is Collection<*> -> v.map { canon(it) }
            else -> v
        }
        fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        fun mods(a: JSONArray) = (0 until a.length()).map { StudyCore.moduleFrom(a.getJSONObject(it)) }
        fun day(s: String): LocalDate = LocalDate.parse(s)
    }

    private fun check(name: String, got: Any?, want: Any?) = assertEquals(name, canon(want), canon(got))

    @Test fun taxonomySize() = assertEquals(C.getInt("taxonomy_size"), TAX.size)

    @Test fun extract() {
        val e = C.getJSONObject("extract")
        assertTrue(e.length() >= 3)
        for (k in e.keys()) { val c = e.getJSONObject(k); check("extract $k", StudyCore.extractSkills(c.getString("jd"), TAX).toJson(), c.get("expect")) }
    }

    @Test fun gapsAndPlan() {
        val g = C.getJSONObject("gaps")
        val jd = StudyCore.skillsFrom(C.getJSONObject("extract").getJSONObject(g.getString("jd")).getJSONObject("expect"))
        check("gaps", JSONArray(StudyCore.gaps(jd, strs(g.getJSONArray("resume_skills"))).map { it.toJson() }), g.get("expect"))
        val p = C.getJSONObject("plan")
        val gl = p.getJSONArray("gaps").let { a -> (0 until a.length()).map { StudyCore.gapFrom(a.getJSONObject(it)) } }
        check("plan", JSONArray(StudyCore.makePlan(gl, day(p.getString("interview")), day(p.getString("today")), p.getInt("minutes")).map { it.toJson() }), p.get("expect"))
        assertEquals(p.getInt("no_date_days"), StudyCore.makePlan(gl, null, day(p.getString("today")), 60).size)
        assertEquals(p.getInt("past_date_days"), StudyCore.makePlan(gl, day("2026-11-01"), day(p.getString("today")), 60).size)
    }

    @Test fun readiness() {
        val a = C.getJSONArray("readiness")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val sc = StudyCore.skillScore(r.getInt("answered"), r.getInt("correct"), r.getInt("lessons_done"), r.getInt("lessons_total"))
            assertEquals("score $i", r.getInt("score"), sc)
            assertEquals("level $i", r.getString("level"), StudyCore.level(sc))
        }
        val o = C.getJSONObject("readiness_overall")
        val mcq = o.getJSONObject("mcq").let { m -> m.keys().asSequence().associateWith { StudyCore.Stat(m.getJSONObject(it).getInt("answered"), m.getJSONObject(it).getInt("correct")) } }
        val les = o.getJSONObject("lessons").let { m -> m.keys().asSequence().associateWith { StudyCore.Progress(m.getJSONObject(it).getInt("done"), m.getJSONObject(it).getInt("total")) } }
        check("readiness", StudyCore.readiness(strs(o.getJSONArray("skills")), mcq, les).toJson(), o.get("expect"))
    }

    @Test fun reviewAndStreak() {
        val r = C.getJSONArray("review")
        for (i in 0 until r.length()) { val c = r.getJSONObject(i)
            check("review $i", StudyCore.nextReview(c.getInt("times_right"), day(c.getString("answered_on")), c.getBoolean("correct"), c.optInt("lapses", 0)).toJson(), c.get("expect")) }
        assertTrue((0 until r.length()).any { r.getJSONObject(it).optInt("lapses") >= 1 })          // the regenerated cases cover repeated misses
        val s = C.getJSONArray("streak")
        for (i in 0 until s.length()) { val c = s.getJSONObject(i)
            assertEquals("streak $i", c.getInt("expect"), StudyCore.streak(strs(c.getJSONArray("days")), day(c.getString("today")))) }
    }

    @Test fun scheduleMasteryNextLesson() {
        val s = C.getJSONArray("schedule")
        assertTrue(s.length() >= 4)
        for (i in 0 until s.length()) { val c = s.getJSONObject(i)
            check("schedule $i", StudyCore.courseSchedule(mods(c.getJSONArray("modules")), c.getInt("minutes"), day(c.getString("start")),
                strs(c.optJSONArray("optional")), strs(c.optJSONArray("done"))).toJson(), c.get("expect")) }
        val m = C.getJSONArray("mastery")
        for (i in 0 until m.length()) { val c = m.getJSONObject(i)
            val st = c.getJSONObject("stats").let { o -> o.keys().asSequence().associateWith { StudyCore.Stat(o.getJSONObject(it).getInt("answered"), o.getJSONObject(it).getInt("correct")) } }
            check("mastery $i", StudyCore.moduleMastery(StudyCore.moduleFrom(c.getJSONObject("module")), strs(c.getJSONArray("done")), st).toJson(), c.get("expect")) }
        val n = C.getJSONArray("next_lesson")
        for (i in 0 until n.length()) { val c = n.getJSONObject(i)
            check("next $i", StudyCore.nextLesson(mods(c.getJSONArray("modules")), strs(c.getJSONArray("done")), strs(c.optJSONArray("optional"))), c.get("expect")) }
    }

    @Test fun adaptGaps() {
        val c = C.getJSONObject("adapt")
        val gl = c.getJSONArray("gaps").let { a -> (0 until a.length()).map { StudyCore.gapFrom(a.getJSONObject(it)) } }
        val rows = c.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("skill") to o.optInt("score", 0) } } }
        check("adapt", JSONArray(StudyCore.adaptGaps(gl, rows).map { it.toJson() }), c.get("expect"))
    }

    // ---------------------------------------------------------------- 7.2.0-dev.2 shared cases
    @Test fun factGuard() {
        val a = C.getJSONArray("fact_guard")
        assertTrue(a.length() >= 3)
        for (i in 0 until a.length()) { val c = a.getJSONObject(i)
            check("fact_guard $i", StudyCore.factGuard(c.getString("source"), c.getString("rewritten"), TAX).toJson(), c.get("expect")) }
    }

    @Test fun mockPlan() {
        val a = C.getJSONArray("mock_plan")
        assertTrue(a.length() >= 3)
        for (i in 0 until a.length()) { val c = a.getJSONObject(i)
            val comps = c.getJSONArray("competencies").let { x -> (0 until x.length()).map { x.getJSONObject(it).let { o -> StudyCore.Theme(o.getString("id"), o.getString("question")) } } }
            check("mock_plan $i", JSONArray(StudyCore.mockPlan(strs(c.getJSONArray("skills")), comps, c.getInt("count")).map { it.toJson() }), c.get("expect")) }
    }

    @Test fun feedback() {
        val a = C.getJSONArray("feedback")
        assertTrue(a.length() >= 4)
        for (i in 0 until a.length()) { val c = a.getJSONObject(i)
            check("feedback $i", StudyCore.checkFeedback(c.get("o"), c.getString("answer"))?.toJson(), c.get("expect")) }
        assertEquals(null, StudyCore.checkFeedback(JSONArray(), "x"))
        assertEquals(null, StudyCore.checkFeedback(JSONObject().put("scores", "4"), "x"))
    }

    @Test fun mockSummary() {
        val a = C.getJSONArray("mock_summary")
        for (i in 0 until a.length()) { val c = a.getJSONObject(i)
            val rows = c.getJSONArray("rows").let { x -> (0 until x.length()).map { StudyCore.feedbackFrom(x.getJSONObject(it).optJSONObject("feedback")) } }
            check("mock_summary $i", StudyCore.mockSummary(rows).toJson(), c.get("expect")) }
    }

    @Test fun qualityVerdict() {
        val a = C.getJSONArray("quality")
        for (i in 0 until a.length()) { val c = a.getJSONObject(i)
            val p = c.getJSONObject("probes").let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } }
            assertEquals("quality $i", c.getString("expect"), StudyCore.qualityVerdict(p)) }
    }

    @Test fun pyRoundIsHalfToEven() = assertEquals(listOf(40, 42, 2, 3, 27), listOf(40.5, 41.5, 2.5, 3.49, 27.0).map(StudyCore::pyRound))

    /** The ready-made courses parse, and every module skill is a taxonomy skill (or Behavioural). */
    @Test fun tracksAssetIsValid() {
        val o = JSONObject(File("src/main/assets/study_tracks.json").readText(Charsets.UTF_8))
        val names = TAX.map { it.name }.toSet()
        val tracks = o.getJSONArray("tracks")
        assertEquals(5, tracks.length())
        val lessonIds = HashSet<String>()
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            val ms = mods(t.getJSONArray("modules"))
            assertTrue(t.getString("id"), ms.isNotEmpty())
            for (m in ms) {
                assertTrue(m.id, m.lessons.isNotEmpty())
                for (s in m.skills) assertTrue("${m.id}: $s", s in names || s == StudyCore.BEHAVIOURAL)
                for (l in m.lessons) assertTrue("duplicate ${l.id}", lessonIds.add(l.id))
            }
        }
        assertEquals(8, o.getJSONArray("competencies").length())
        // identical to the desktop copy
        assertEquals(File(dataDir, "../../study_tracks.json").readText(Charsets.UTF_8).replace("\r\n", "\n"),
            File("src/main/assets/study_tracks.json").readText(Charsets.UTF_8).replace("\r\n", "\n"))
    }
}
