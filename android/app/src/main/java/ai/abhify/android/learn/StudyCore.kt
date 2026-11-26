package ai.abhify.android.learn

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Study 7.2: the deterministic rules shared by Windows, Web and Android (port of backend/study_core.py, the reference).
 * Pure functions, no AI, no I/O: skill extraction from a job description, the day-by-day prep plan, readiness scores,
 * the review schedule for missed questions and the per-course schedule / mastery. Must reproduce
 * backend/tests/data/study_cases.json exactly (see StudyCoreTest). Python's round() rounds halves to even: [pyRound].
 */
object StudyCore {
    private val PREFERRED_HEAD = Regex("(?i)\\b(preferred|nice to have|nice-to-have|good to have|bonus|a plus|plus points?|desirable|optional)\\b")
    private val REQUIRED_HEAD = Regex("(?i)\\b(requirements?|required|must have|must-have|qualifications?|what you('|’)ll need|you have|skills|responsibilities)\\b")
    const val BEHAVIOURAL = "Behavioural"
    const val MAX_GAPS = 12
    const val DEFAULT_DAYS = 14
    const val MAX_DAYS = 60
    val REVIEW_STEPS = listOf(1, 3, 7, 14, 30)
    const val LESSON_MINUTES = 30
    const val WEAK_MIN_ANSWERS = 3
    const val WEAK_ACCURACY = 0.6

    /** A taxonomy skill: its canonical name and aliases (assets/skills_taxonomy.json). */
    data class Term(val name: String, val aliases: List<String> = emptyList())
    data class Lesson(val id: String, val title: String = "")
    data class Module(val id: String, val title: String = "", val skills: List<String> = emptyList(), val lessons: List<Lesson> = emptyList())

    data class Skills(val required: List<String>, val preferred: List<String>)
    data class Gap(val skill: String, val why: String)
    data class PlanItem(val type: String, val skill: String, val minutes: Int, val title: String)
    data class PlanDay(val date: String, val day: Int, val items: List<PlanItem>)
    data class Stat(val answered: Int = 0, val correct: Int = 0)
    data class Progress(val done: Int = 0, val total: Int = 0)
    data class SkillRow(val skill: String, val score: Int, val level: String, val answered: Int, val lessonsDone: Int, val lessonsTotal: Int)
    data class Readiness(val overall: Int, val level: String, val skills: List<SkillRow>, val weak: List<String>)
    data class Review(val due: String?, val timesRight: Int, val done: Boolean, val lapses: Int = 0)
    data class Day(val date: String, val kind: String, val lessons: List<String>)
    data class Week(val week: Int, val from: String, val to: String, val lessons: Int, val modulesCompleted: List<String>)
    data class Schedule(val perDay: Int, val days: List<Day>, val weeks: List<Week>)
    data class Mastery(val module: String, val score: Int, val level: String, val answered: Int, val correct: Int, val lessonsDone: Int,
                       val lessonsTotal: Int, val weak: Boolean)

    /** Python's round(): halves go to the even neighbour (round(40.5) == 40). */
    fun pyRound(x: Double): Int = Math.rint(x).toInt()

    fun parseTaxonomy(json: String): List<Term> {
        val a = JSONObject(json).getJSONArray("skills")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Term(o.getString("name"), strings(o.optJSONArray("aliases"))) } }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private val NON_NAME = Regex("[^a-z0-9+#.]+")
    private val END_DOT = Regex("\\.(?=\\s|$)")
    private val SPACES = Regex("\\s+")
    private fun norm(s: String): String {
        var t = NON_NAME.replace(s.lowercase(), " ")
        t = END_DOT.replace(t, " ")                                     // a sentence's full stop isn't part of a name ("Power BI.")
        return " " + SPACES.replace(t, " ").trim() + " "
    }

    /** Taxonomy skills named in [text], in order of first appearance (whole words, aliases included). */
    fun findSkills(text: String, taxonomy: List<Term>): List<String> {
        val t = norm(text)
        val found = ArrayList<Pair<Int, String>>()
        for (s in taxonomy) {
            val pos = (listOf(s.name) + s.aliases).map { t.indexOf(norm(it)) }.filter { it >= 0 }.minOrNull() ?: -1
            if (pos >= 0) found += pos to s.name
        }
        return found.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }).map { it.second }
    }

    private fun isUpper(s: String) = s.any { it.isUpperCase() } && s.none { it.isLowerCase() }
    private val LINES = Regex("\r\n|\r|\n")
    private val SENTENCES = Regex("(?<=[.;!?])\\s+")

    /** Lines under a 'preferred / nice to have / bonus' heading (or containing those words) are preferred; the rest required. */
    fun extractSkills(jd: String?, taxonomy: List<Term>): Skills {
        val required = ArrayList<String>(); val preferred = ArrayList<String>()
        var mode = "required"
        for (line in (jd ?: "").split(LINES)) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue
            val isHead = stripped.length <= 60 && (stripped.endsWith(":") || isUpper(stripped) || stripped.startsWith("#"))
            if (isHead) {
                mode = if (PREFERRED_HEAD.containsMatchIn(stripped)) "preferred" else if (REQUIRED_HEAD.containsMatchIn(stripped)) "required" else mode
                continue
            }
            for (sentence in stripped.split(SENTENCES)) {          // "SQL is required. Python is a plus." -> judged per sentence
                val target = if (mode == "preferred" || PREFERRED_HEAD.containsMatchIn(sentence)) preferred else required
                for (s in findSkills(sentence, taxonomy)) if (s !in target) target += s
            }
        }
        return Skills(required, preferred.filter { it !in required })
    }

    /** Required skills missing from the resume, then preferred ones missing, then required ones to refresh. At most MAX_GAPS. */
    fun gaps(jd: Skills, resumeSkills: List<String>): List<Gap> {
        val have = resumeSkills.toSet()
        val out = jd.required.filter { it !in have }.map { Gap(it, "required, not on your resume") } +
            jd.preferred.filter { it !in have }.map { Gap(it, "preferred, not on your resume") } +
            jd.required.filter { it in have }.map { Gap(it, "required: refresh") }
        return out.take(MAX_GAPS)
    }

    fun planDays(interview: LocalDate?, today: LocalDate): Int =
        if (interview == null) DEFAULT_DAYS else ChronoUnit.DAYS.between(today, interview).toInt().coerceIn(1, MAX_DAYS)

    /** Day-by-day plan: lesson + MCQs on one skill a day (rotating), a STAR story every 2nd day, review from day 2, a rehearsal last day. */
    fun makePlan(gapList: List<Gap>, interview: LocalDate?, today: LocalDate, minutesIn: Int?): List<PlanDay> {
        val n = planDays(interview, today)
        val minutes = (if (minutesIn == null || minutesIn == 0) 60 else minutesIn).coerceIn(15, 240)
        val skills = gapList.map { it.skill }.ifEmpty { listOf("General interview preparation") }
        return (0 until n).map { d ->
            val day = today.plusDays(d.toLong())
            val items = if (n >= 3 && d == n - 1) listOf(
                PlanItem("mcq", "All skills", pyRound(minutes * 0.5), "Mixed MCQs across all your plan skills"),
                PlanItem("story", BEHAVIOURAL, pyRound(minutes * 0.3), "Rehearse your STAR stories out loud"),
                PlanItem("review", "All skills", minutes - pyRound(minutes * 0.5) - pyRound(minutes * 0.3), "Review everything you missed"))
            else {
                val skill = skills[d % skills.size]
                buildList {
                    add(PlanItem("lesson", skill, pyRound(minutes * 0.45), "Learn: $skill"))
                    add(PlanItem("mcq", skill, pyRound(minutes * 0.25), "Practice MCQs: $skill"))
                    if (d % 2 == 1) add(PlanItem("story", BEHAVIOURAL, pyRound(minutes * 0.2), "Write or improve one STAR story"))
                    if (d >= 1) add(PlanItem("review", "Missed questions", pyRound(minutes * 0.1), "Review questions you missed"))
                }
            }
            PlanDay(day.toString(), d + 1, items)
        }
    }

    /** 0–100: 60% MCQ accuracy (counted fully only after 10 answers), 40% lessons completed. */
    fun skillScore(answered: Int, correct: Int, lessonsDone: Int, lessonsTotal: Int): Int {
        val acc = if (answered != 0) correct.toDouble() / answered else 0.0
        val confidence = minOf(1.0, answered / 10.0)
        val lessons = if (lessonsTotal != 0) lessonsDone.toDouble() / lessonsTotal else 0.0
        return pyRound(100 * (0.6 * acc * confidence + 0.4 * lessons))
    }

    fun level(score: Int) = if (score < 40) "Not ready" else if (score < 70) "Getting there" else "Ready"

    fun readiness(skills: List<String>, mcq: Map<String, Stat>, lessons: Map<String, Progress>): Readiness {
        val rows = skills.map { s ->
            val m = mcq[s] ?: Stat(); val l = lessons[s] ?: Progress()
            val sc = skillScore(m.answered, m.correct, l.done, l.total)
            SkillRow(s, sc, level(sc), m.answered, l.done, l.total)
        }
        val overall = if (rows.isEmpty()) 0 else pyRound(rows.sumOf { it.score }.toDouble() / rows.size)
        val weak = rows.sortedWith(compareBy<SkillRow> { it.score }.thenBy { it.skill }).filter { it.score < 40 }.map { it.skill }
        return Readiness(overall, level(overall), rows, weak)
    }

    /**
     * A missed question comes back after 1 day; each later right answer moves it to 3, 7, 14, 30 days, then it's done.
     * [lapses] = how many times it was missed before this answer: from the second miss on it comes back the same day.
     */
    fun nextReview(timesRight: Int, answeredOn: LocalDate, correct: Boolean, lapses: Int = 0): Review {
        if (!correct) return Review(answeredOn.plusDays(if (lapses >= 1) 0L else REVIEW_STEPS[0].toLong()).toString(), 0, false, lapses + 1)
        val t = timesRight + 1
        if (t >= REVIEW_STEPS.size) return Review(null, t, true, lapses)
        return Review(answeredOn.plusDays(REVIEW_STEPS[t].toLong()).toString(), t, false, lapses)
    }

    /** A new plan version keeps what was already proved: skills scored Ready (70+) move to the end as a light refresh. */
    fun adaptGaps(gapList: List<Gap>, rows: List<Pair<String, Int>>): List<Gap> {
        val ready = rows.filter { it.second >= 70 }.map { it.first }.toSet()
        return gapList.filter { it.skill !in ready } + gapList.filter { it.skill in ready }.map { Gap(it.skill, "already Ready: light refresh") }
    }

    /** Consecutive days with study activity ending today (or yesterday). */
    fun streak(activityDays: Collection<String>, today: LocalDate): Int {
        val days = activityDays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
        val start = if (today in days) today else today.minusDays(1)
        var n = 0
        while (start.minusDays(n.toLong()) in days) n++
        return n
    }

    /** lesson id → the skills of its module, for ready-made tracks. */
    fun lessonSkills(modules: List<Module>): Map<String, List<String>> =
        LinkedHashMap<String, List<String>>().apply { for (m in modules) for (l in m.lessons) put(l.id, m.skills) }

    fun lessonsPerSkill(modules: List<Module>, doneIds: Set<String>): MutableMap<String, Progress> {
        val out = LinkedHashMap<String, Progress>()
        for ((lid, skills) in lessonSkills(modules)) for (s in skills) {
            val r = out[s] ?: Progress()
            out[s] = Progress(r.done + if (lid in doneIds) 1 else 0, r.total + 1)
        }
        return out
    }

    private fun ordered(modules: List<Module>, optional: Set<String>) = modules.filter { it.id !in optional } + modules.filter { it.id in optional }

    /**
     * Daily schedule and weekly milestones from the course's real syllabus: lessons in order (optional modules last),
     * max(1, minutes / 30) lessons a day, a revision day every 7th day. Done lessons are skipped.
     */
    fun courseSchedule(modules: List<Module>, minutes: Int?, start: LocalDate, optional: Collection<String> = emptyList(),
                       done: Collection<String> = emptyList()): Schedule {
        val doneSet = done.toSet()
        val ord = ordered(modules, optional.toSet())
        val queue = ord.flatMap { m -> m.lessons.map { it.id }.filter { it !in doneSet } }
        val perDay = maxOf(1, (if (minutes == null || minutes == 0) 60 else minutes).coerceIn(15, 240) / LESSON_MINUTES)
        val days = ArrayList<Day>()
        var d = 0; var i = 0
        while (i < queue.size) {
            val day = start.plusDays(d.toLong()).toString()
            if (d % 7 == 6) days += Day(day, "revision", emptyList())
            else { days += Day(day, "learn", queue.subList(i, minOf(queue.size, i + perDay)).toList()); i += perDay }
            d++
        }
        val weeks = ArrayList<Week>(); val seen = HashSet<String>(); val covered = HashSet(doneSet)
        for (w in days.indices step 7) {
            val chunk = days.subList(w, minOf(days.size, w + 7))
            val lessons = chunk.flatMap { it.lessons }
            covered += lessons
            val finished = ord.filter { m -> m.id !in seen && m.lessons.all { it.id in covered } }.map { it.id }
            seen += finished
            weeks += Week(w / 7 + 1, chunk.first().date, chunk.last().date, lessons.size, finished)
        }
        return Schedule(perDay, days, weeks)
    }

    /** Mastery of one module from its lessons done and its recorded MCQ answers {module_id: Stat}. */
    fun moduleMastery(module: Module, done: Collection<String>, stats: Map<String, Stat>): Mastery {
        val s = stats[module.id] ?: Stat()
        val doneSet = done.toSet()
        val ld = module.lessons.count { it.id in doneSet }
        val score = skillScore(s.answered, s.correct, ld, module.lessons.size)
        val weak = s.answered >= WEAK_MIN_ANSWERS && s.correct.toDouble() / s.answered < WEAK_ACCURACY
        return Mastery(module.id, score, level(score), s.answered, s.correct, ld, module.lessons.size, weak)
    }

    /** The first lesson not done, core modules before optional ones. */
    fun nextLesson(modules: List<Module>, done: Collection<String>, optional: Collection<String> = emptyList()): String? {
        val doneSet = done.toSet()
        for (m in ordered(modules, optional.toSet())) for (l in m.lessons) if (l.id !in doneSet) return l.id
        return null
    }

    // ---------------------------------------------------------------- 7.2.0-dev.2: shared rules for the enhancements
    data class Guard(val ok: Boolean, val newNumbers: List<String>, val newSkills: List<String>)
    /** A behavioural theme for mock interviews (id + its STAR question). */
    data class Theme(val id: String, val question: String)
    data class MockQ(val kind: String, val skill: String? = null, val competency: String? = null, val question: String? = null)
    data class Feedback(val scores: Map<String, Int>, val total: Int, val evidence: List<String>, val tips: List<String>)
    data class MockSummary(val answered: Int, val percent: Int?, val means: Map<String, Double>, val weakest: String?)
    val RUBRIC = listOf("relevance", "depth", "structure")
    val PROBES = listOf("json_mcq", "lesson_sections", "fact")

    private val NUM = Regex("\\d+(?:[.,]\\d+)?")

    /**
     * A rewrite (resume bullet, STAR version) may only reuse facts from [source]: every number and every taxonomy skill
     * it names must already be in the source. Returns what was added; ok only when nothing was.
     */
    fun factGuard(source: String?, rewritten: String?, taxonomy: List<Term>): Guard {
        val haveN = NUM.findAll(source ?: "").map { it.value }.toSet()
        val newN = NUM.findAll(rewritten ?: "").map { it.value }.filter { it !in haveN }.toSortedSet().toList()
        val haveS = findSkills(source ?: "", taxonomy).toSet()
        val newS = findSkills(rewritten ?: "", taxonomy).filter { it !in haveS }
        return Guard(newN.isEmpty() && newS.isEmpty(), newN, newS)
    }

    /**
     * The questions of a mock interview, in order: technical skills and behavioural themes alternate (technical first),
     * each used once while they last; count is clamped to 3-12 (0 or null = 6).
     */
    fun mockPlan(skills: List<String>, competencies: List<Theme>, count: Int?): List<MockQ> {
        val n = (if (count == null || count == 0) 6 else count).coerceIn(3, 12)
        val tech = skills.map { MockQ("technical", skill = it) }
        val beh = competencies.map { MockQ("behavioural", competency = it.id, question = it.question) }
        val out = ArrayList<MockQ>(); var i = 0; var j = 0
        while (out.size < n && (i < tech.size || j < beh.size)) {
            if ((out.size % 2 == 0 && i < tech.size) || j >= beh.size) out += tech[i++] else out += beh[j++]
        }
        return out
    }

    private val NOT_ALNUM = Regex("[^a-z0-9 ]+")
    private fun squash(s: String?): String = SPACES.replace(NOT_ALNUM.replace((s ?: "").lowercase(), " "), " ").trim()

    /**
     * Mock-interview feedback from the model: relevance, depth and structure must be numbers from 1 to 5 (a boolean is
     * not a number); 'evidence' may only quote the candidate's own answer (quotes not found in it are dropped); a
     * non-list 'evidence' or 'tips' gives none. null when the scores are unusable.
     */
    fun checkFeedback(o: Any?, answer: String?): Feedback? {
        val obj = o as? JSONObject ?: return null
        val sc = obj.opt("scores") as? JSONObject ?: return null
        val scores = LinkedHashMap<String, Int>()
        for (k in RUBRIC) {
            val v = sc.opt(k)
            if (v is Boolean || v !is Number) return null
            val d = v.toDouble()
            if (d.isNaN() || d < 1 || d > 5) return null
            scores[k] = pyRound(d)
        }
        val ans = squash(answer)
        fun items(k: String): List<String> = (obj.opt(k) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.opt(it)?.toString() } } ?: emptyList()
        val evidence = items("evidence").filter { q -> q.trim().isNotEmpty() && squash(q).length >= 4 && squash(q) in ans }.map { it.trim().take(200) }.take(3)
        val tips = items("tips").map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(300) }.take(3)
        return Feedback(scores, scores.values.sum(), evidence, tips)
    }

    /**
     * Session result from scored answers: mean per dimension (1 decimal), overall percent of the maximum, weakest
     * dimension. Unanswered or unscored questions (null) don't count.
     */
    fun mockSummary(rows: List<Feedback?>): MockSummary {
        val scored = rows.filterNotNull()
        if (scored.isEmpty()) return MockSummary(0, null, emptyMap(), null)
        val means = LinkedHashMap<String, Double>()
        for (k in RUBRIC) means[k] = Math.rint(10.0 * scored.sumOf { it.scores[k] ?: 0 } / scored.size) / 10.0
        val percent = pyRound(100.0 * scored.sumOf { it.total } / (15 * scored.size))
        val weakest = means.entries.sortedWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).first().key
        return MockSummary(scored.size, percent, means, weakest)
    }

    /** Model quality check from 3 probes (json_mcq, lesson_sections, fact): all pass = good, 2 = weak, else poor. */
    fun qualityVerdict(probes: Map<String, Boolean>): String {
        val n = PROBES.count { probes[it] == true }
        return if (n == 3) "good" else if (n == 2) "weak" else "poor"
    }

    // ---------------------------------------------------------------- JSON (the same field names as the reference)
    fun Skills.toJson(): JSONObject = JSONObject().put("required", JSONArray(required)).put("preferred", JSONArray(preferred))
    fun Gap.toJson(): JSONObject = JSONObject().put("skill", skill).put("why", why)
    fun PlanItem.toJson(): JSONObject = JSONObject().put("type", type).put("skill", skill).put("minutes", minutes).put("title", title)
    fun PlanDay.toJson(): JSONObject = JSONObject().put("date", date).put("day", day).put("items", JSONArray(items.map { it.toJson() }))
    fun Review.toJson(): JSONObject = JSONObject().put("due", due ?: JSONObject.NULL).put("times_right", timesRight).put("done", done).put("lapses", lapses)
    fun Readiness.toJson(): JSONObject = JSONObject().put("overall", overall).put("level", level).put("weak", JSONArray(weak))
        .put("skills", JSONArray(skills.map { JSONObject().put("skill", it.skill).put("score", it.score).put("level", it.level).put("answered", it.answered)
            .put("lessons_done", it.lessonsDone).put("lessons_total", it.lessonsTotal) }))
    fun Schedule.toJson(): JSONObject = JSONObject().put("per_day", perDay)
        .put("days", JSONArray(days.map { JSONObject().put("date", it.date).put("kind", it.kind).put("lessons", JSONArray(it.lessons)) }))
        .put("weeks", JSONArray(weeks.map { JSONObject().put("week", it.week).put("from", it.from).put("to", it.to).put("lessons", it.lessons)
            .put("modules_completed", JSONArray(it.modulesCompleted)) }))
    fun Mastery.toJson(): JSONObject = JSONObject().put("module", module).put("score", score).put("level", level).put("answered", answered)
        .put("correct", correct).put("lessons_done", lessonsDone).put("lessons_total", lessonsTotal).put("weak", weak)

    fun Guard.toJson(): JSONObject = JSONObject().put("ok", ok).put("new_numbers", JSONArray(newNumbers)).put("new_skills", JSONArray(newSkills))
    fun MockQ.toJson(): JSONObject = JSONObject().put("kind", kind).apply {
        if (kind == "technical") put("skill", skill) else put("competency", competency).put("question", question)
    }
    fun Feedback.toJson(): JSONObject = JSONObject().put("scores", JSONObject(scores as Map<*, *>)).put("total", total)
        .put("evidence", JSONArray(evidence)).put("tips", JSONArray(tips))
    fun MockSummary.toJson(): JSONObject = JSONObject().put("answered", answered).put("percent", percent ?: JSONObject.NULL)
        .put("means", JSONObject().apply { means.forEach { (k, v) -> put(k, v) } }).put("weakest", weakest ?: JSONObject.NULL)
    fun feedbackFrom(o: JSONObject?): Feedback? {
        val sc = o?.optJSONObject("scores") ?: return null
        val scores = LinkedHashMap<String, Int>().apply { RUBRIC.forEach { put(it, sc.optInt(it)) } }
        return Feedback(scores, o.optInt("total", scores.values.sum()), strings(o.optJSONArray("evidence")), strings(o.optJSONArray("tips")))
    }
    fun mockSummaryFrom(o: JSONObject?): MockSummary? {
        if (o == null) return null
        val m = o.optJSONObject("means")
        return MockSummary(o.optInt("answered"), if (o.isNull("percent")) null else o.optInt("percent"),
            LinkedHashMap<String, Double>().apply { m?.keys()?.forEach { put(it, m.optDouble(it)) } }, if (o.isNull("weakest")) null else o.optString("weakest"))
    }

    fun skillsFrom(o: JSONObject?): Skills = Skills(strings(o?.optJSONArray("required")), strings(o?.optJSONArray("preferred")))
    fun gapFrom(o: JSONObject) = Gap(o.optString("skill"), o.optString("why"))
    fun planDayFrom(o: JSONObject): PlanDay {
        val a = o.optJSONArray("items") ?: JSONArray()
        return PlanDay(o.optString("date"), o.optInt("day"), (0 until a.length()).map { a.getJSONObject(it) }
            .map { PlanItem(it.optString("type"), it.optString("skill"), it.optInt("minutes"), it.optString("title")) })
    }
    fun moduleFrom(o: JSONObject): Module {
        val a = o.optJSONArray("lessons") ?: JSONArray()
        return Module(o.optString("id"), o.optString("title"), strings(o.optJSONArray("skills")),
            (0 until a.length()).map { a.getJSONObject(it) }.map { Lesson(it.optString("id"), it.optString("title")) })
    }
    fun Module.toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("skills", JSONArray(skills))
        .put("lessons", JSONArray(lessons.map { JSONObject().put("id", it.id).put("title", it.title) }))
}
