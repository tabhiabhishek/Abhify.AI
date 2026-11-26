package ai.abhify.android.learn

import ai.abhify.android.learn.StudyCore.Module
import ai.abhify.android.learn.StudyCore.Stat
import ai.abhify.android.learn.StudyCore.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Study 7.2 records kept in filesDir/study.json (the same shape as the desktop's study.json and the Web app's
 * 'abhify.study.v1'). Parsed fresh for every operation, so objects handed to the UI are never changed underneath it.
 * Phone-only: not part of backup, Drive or any sync.
 */
class StudyData(
    val courses: LinkedHashMap<String, Course> = LinkedHashMap(),
    val lessons: LinkedHashMap<String, LessonEntry> = LinkedHashMap(),      // "courseId/lessonId" -> the written lesson
    var plan: Plan? = null,
    val stories: MutableList<Story> = ArrayList(),
    val mcq: LinkedHashMap<String, Stat> = LinkedHashMap(),                 // per skill
    val reviews: LinkedHashMap<String, ReviewEntry> = LinkedHashMap(),      // missed questions, by question key
    val activity: MutableList<String> = ArrayList(),                        // ISO days with any study activity
    val cards: LinkedHashMap<String, Card> = LinkedHashMap(),
    val seen: MutableList<String> = ArrayList(),                            // normalised question texts already asked
    val planHistory: MutableList<JSONObject> = ArrayList(),                 // earlier interview plans (last 10, each with archived_at)
    val mocks: MutableList<MockSession> = ArrayList(),                      // 7.2.0-dev.2: mock interview sessions (last 20)
    val quality: LinkedHashMap<String, Quality> = LinkedHashMap(),          // 7.2.0-dev.2: model quality check, by model name
) {
    class Prefs(val target: String, val level: String, val minutes: Int)
    class Overview(val summary: String, val objectives: List<String>, val prerequisites: List<String>, val completion: List<String>,
                   val optional: List<String>, val focus: Map<String, String>, val at: Long)
    class Milestone(val title: String, var done: Boolean)
    class Capstone(val title: String, val brief: String, val milestones: List<Milestone>, val stretch: List<String>)
    class HistoryItem(val at: Long, val kind: String, val detail: String)
    class Question(val key: String, val q: String, val options: List<String>, val answer: Int, val explanation: String, val topic: String,
                   val difficulty: String, val skill: String, val module: String, val moduleId: String, val courseId: String?)
    class Wrong(val question: Question, val at: String)
    class Course(
        val id: String, val track: String?, var title: String, var modules: List<Module>, val created: Long,
        var done: MutableList<String> = ArrayList(), var prefs: Prefs? = null, var overview: Overview? = null,
        var seconds: Long = 0, val days: LinkedHashMap<String, Long> = LinkedHashMap(), var exercises: MutableList<String> = ArrayList(),
        val scores: LinkedHashMap<String, Stat> = LinkedHashMap(), val wrong: MutableList<Wrong> = ArrayList(), var capstone: Capstone? = null,
        val history: MutableList<HistoryItem> = ArrayList(),
        val prefsHistory: MutableList<JSONObject> = ArrayList(),            // earlier {prefs, overview, at}: a goal change keeps the old version
        var brain: Brain? = null,                                           // 7.2.0-dev.2: Personal AI Brain opt-in (off by default)
    )
    /** What the learner chose to share with this course's prompts: ticked memory ids and project ids only. */
    class Brain(val enabled: Boolean, val memories: List<Long>, val projects: List<String>)
    class MockQuestion(val i: Int, val kind: String, val skill: String?, val competency: String?, val question: String,
                       var answer: String? = null, var seconds: Int? = null, var feedback: StudyCore.Feedback? = null)
    class MockSession(val id: String, val created: Long, val source: String, val job: String?, val questions: List<MockQuestion>,
                      var finished: Boolean = false, var summary: StudyCore.MockSummary? = null)
    class Quality(val model: String, val verdict: String, val probes: Map<String, Boolean>, val seconds: Map<String, Double>, val at: Long, val advice: String)
    class LessonEntry(val course: String, val lesson: String, val title: String, val markdown: String, val at: Long, val model: String, val prefs: String,
                      val used: List<String> = emptyList())             // 7.2.0-dev.2: labels of the personal context the lesson used
    class ReviewEntry(val question: Question, val skill: String, val course: String?, var due: String?, var timesRight: Int, var done: Boolean, var lapses: Int = 0)
    class Card(val front: String, val back: String, val course: String, val lesson: String, val module: String, val title: String,
               var due: String?, var timesRight: Int, var done: Boolean, var lapses: Int = 0)
    class Plan(val created: Long, val interview: String?, val minutes: Int, val skills: StudyCore.Skills, val resumeSkills: List<String>,
               val gaps: List<StudyCore.Gap>, val days: List<StudyCore.PlanDay>, var done: MutableList<String>, val jdTitle: String)
    class Coach(val scores: Map<String, Int>, val missing: List<String>, val tips: List<String>, val improved: String, val at: Long)
    class Story(val id: String, val title: String, val competencies: List<String>, val situation: String, val task: String, val action: String,
                val result: String, val updated: Long, val coach: Coach?) {
        fun field(f: String) = when (f) { "situation" -> situation; "task" -> task; "action" -> action; else -> result }
    }

    fun toJson(): JSONObject = JSONObject().put("schema", SCHEMA)
        .put("courses", JSONObject().apply { courses.forEach { (k, v) -> put(k, v.toJson()) } })
        .put("lessons", JSONObject().apply { lessons.forEach { (k, v) -> put(k, JSONObject().put("course", v.course).put("lesson", v.lesson)
            .put("title", v.title).put("markdown", v.markdown).put("at", v.at).put("model", v.model).put("prefs", v.prefs).put("used", JSONArray(v.used))) } })
        .put("plan", plan?.toJson() ?: JSONObject.NULL)
        .put("stories", JSONArray(stories.map { it.toJson() }))
        .put("mcq", stats(mcq))
        .put("reviews", JSONObject().apply { reviews.forEach { (k, v) -> put(k, JSONObject().put("question", v.question.toJson()).put("skill", v.skill)
            .put("course", v.course ?: JSONObject.NULL).put("due", v.due ?: JSONObject.NULL).put("times_right", v.timesRight).put("done", v.done).put("lapses", v.lapses)) } })
        .put("activity", JSONArray(activity))
        .put("cards", JSONObject().apply { cards.forEach { (k, v) -> put(k, JSONObject().put("front", v.front).put("back", v.back).put("course", v.course)
            .put("lesson", v.lesson).put("module", v.module).put("title", v.title).put("due", v.due ?: JSONObject.NULL)
            .put("times_right", v.timesRight).put("done", v.done).put("lapses", v.lapses)) } })
        .put("seen", JSONArray(seen))
        .put("plan_history", JSONArray(planHistory))
        .put("mocks", JSONArray(mocks.map { it.toJson() }))
        .put("quality", JSONObject().apply { quality.forEach { (k, v) -> put(k, v.toJson()) } })

    /** The file was written by a newer app version: it is refused, never downgraded or overwritten. */
    class NewerSchema : Exception()

    companion object {
        const val SCHEMA = 1

        /** Lenient: empty text is an empty start; text that isn't a JSON object throws (the caller keeps the file aside). */
        fun parse(text: String?): StudyData {
            if (text.isNullOrBlank()) return StudyData()
            val o = JSONObject(text)
            if (o.optInt("schema", 0) > SCHEMA) throw NewerSchema()
            val d = StudyData()
            o.optJSONObject("courses")?.let { c -> c.keys().forEach { k -> runCatching { courseFrom(c.getJSONObject(k)) }.getOrNull()?.let { d.courses[k] = it } } }
            o.optJSONObject("lessons")?.let { l -> l.keys().forEach { k -> l.optJSONObject(k)?.let { v ->
                d.lessons[k] = LessonEntry(v.optString("course"), v.optString("lesson"), v.optString("title"), v.optString("markdown"),
                    v.optLong("at"), v.optString("model"), v.optString("prefs"), strs(v.optJSONArray("used"))) } } }
            d.plan = o.optJSONObject("plan")?.let { runCatching { planFrom(it) }.getOrNull() }
            o.optJSONArray("stories")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { d.stories += storyFrom(it) } }
            d.mcq.putAll(statsFrom(o.optJSONObject("mcq")))
            o.optJSONObject("reviews")?.let { r -> r.keys().forEach { k -> r.optJSONObject(k)?.let { v ->
                d.reviews[k] = ReviewEntry(questionFrom(v.optJSONObject("question") ?: JSONObject()), v.optString("skill"), str(v, "course"),
                    str(v, "due"), v.optInt("times_right"), v.optBoolean("done"), v.optInt("lapses")) } } }
            d.activity += strs(o.optJSONArray("activity"))
            o.optJSONObject("cards")?.let { c -> c.keys().forEach { k -> c.optJSONObject(k)?.let { v ->
                d.cards[k] = Card(v.optString("front"), v.optString("back"), v.optString("course"), v.optString("lesson"), v.optString("module"),
                    v.optString("title"), str(v, "due"), v.optInt("times_right"), v.optBoolean("done"), v.optInt("lapses")) } } }
            d.seen += strs(o.optJSONArray("seen"))
            o.optJSONArray("plan_history")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { d.planHistory += it } }
            o.optJSONArray("mocks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { m -> runCatching { mockFrom(m) }.getOrNull()?.let { d.mocks += it } } }
            o.optJSONObject("quality")?.let { q -> q.keys().forEach { k -> q.optJSONObject(k)?.let { d.quality[k] = qualityFrom(it) } } }
            return d
        }

        /** A string field, or null when missing or JSON null. */
        fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k)
        fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.optString(it) }
        private fun stats(m: Map<String, Stat>) = JSONObject().apply { m.forEach { (k, v) -> put(k, JSONObject().put("answered", v.answered).put("correct", v.correct)) } }
        private fun statsFrom(o: JSONObject?): Map<String, Stat> = LinkedHashMap<String, Stat>().apply {
            o?.keys()?.forEach { k -> o.optJSONObject(k)?.let { put(k, Stat(it.optInt("answered"), it.optInt("correct"))) } }
        }

        fun Course.toJson(): JSONObject = JSONObject().put("id", id).put("track", track ?: JSONObject.NULL).put("title", title)
            .put("modules", if (track == null) JSONArray(modules.map { it.toJson() }) else JSONObject.NULL).put("created", created)
            .put("done", JSONArray(done)).put("prefs", prefs?.let { JSONObject().put("target", it.target).put("level", it.level).put("minutes", it.minutes) } ?: JSONObject.NULL)
            .put("overview", overview?.let { v -> JSONObject().put("summary", v.summary).put("objectives", JSONArray(v.objectives))
                .put("prerequisites", JSONArray(v.prerequisites)).put("completion", JSONArray(v.completion)).put("optional", JSONArray(v.optional))
                .put("focus", JSONObject(v.focus as Map<*, *>)).put("at", v.at) } ?: JSONObject.NULL)
            .put("seconds", seconds).put("days", JSONObject(days as Map<*, *>)).put("exercises", JSONArray(exercises)).put("scores", stats(scores))
            .put("wrong", JSONArray(wrong.map { it.question.toJson().put("at", it.at) }))
            .put("capstone", capstone?.let { c -> JSONObject().put("title", c.title).put("brief", c.brief)
                .put("milestones", JSONArray(c.milestones.map { JSONObject().put("title", it.title).put("done", it.done) })).put("stretch", JSONArray(c.stretch)) } ?: JSONObject.NULL)
            .put("history", JSONArray(history.map { JSONObject().put("at", it.at).put("kind", it.kind).put("detail", it.detail) }))
            .put("prefs_history", JSONArray(prefsHistory))
            .put("brain", brain?.let { b -> JSONObject().put("enabled", b.enabled).put("memories", JSONArray(b.memories)).put("projects", JSONArray(b.projects)) } ?: JSONObject.NULL)

        private fun courseFrom(o: JSONObject): Course {
            val mods = o.optJSONArray("modules")?.let { a -> (0 until a.length()).map { StudyCore.moduleFrom(a.getJSONObject(it)) } } ?: emptyList()
            val c = Course(o.getString("id"), str(o, "track")?.takeIf { it.isNotEmpty() }, o.optString("title"), mods, o.optLong("created"))
            c.done = strs(o.optJSONArray("done")).toMutableList()
            c.prefs = o.optJSONObject("prefs")?.let { Prefs(it.optString("target"), it.optString("level"), it.optInt("minutes", 60)) }
            c.overview = o.optJSONObject("overview")?.let { v ->
                val f = v.optJSONObject("focus")
                Overview(v.optString("summary"), strs(v.optJSONArray("objectives")), strs(v.optJSONArray("prerequisites")), strs(v.optJSONArray("completion")),
                    strs(v.optJSONArray("optional")), LinkedHashMap<String, String>().apply { f?.keys()?.forEach { k -> put(k, f.optString(k)) } }, v.optLong("at"))
            }
            c.seconds = o.optLong("seconds")
            o.optJSONObject("days")?.let { dd -> dd.keys().forEach { k -> c.days[k] = dd.optLong(k) } }
            c.exercises = strs(o.optJSONArray("exercises")).toMutableList()
            c.scores.putAll(statsFrom(o.optJSONObject("scores")))
            o.optJSONArray("wrong")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c.wrong += Wrong(questionFrom(it), it.optString("at")) } }
            c.capstone = o.optJSONObject("capstone")?.let { cp ->
                val ms = cp.optJSONArray("milestones") ?: JSONArray()
                Capstone(cp.optString("title"), cp.optString("brief"), (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }
                    .map { Milestone(it.optString("title"), it.optBoolean("done")) }, strs(cp.optJSONArray("stretch")))
            }
            o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c.history += HistoryItem(it.optLong("at"), it.optString("kind"), it.optString("detail")) } }
            o.optJSONArray("prefs_history")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c.prefsHistory += it } }
            c.brain = o.optJSONObject("brain")?.let { b ->
                val ms = b.optJSONArray("memories") ?: JSONArray()
                Brain(b.optBoolean("enabled"), (0 until ms.length()).mapNotNull { (ms.opt(it) as? Number)?.toLong() ?: ms.optString(it).toLongOrNull() }, strs(b.optJSONArray("projects")))
            }
            return c
        }

        fun MockSession.toJson(): JSONObject = JSONObject().put("id", id).put("created", created).put("source", source).put("job", job ?: JSONObject.NULL)
            .put("questions", JSONArray(questions.map { q -> JSONObject().put("i", q.i).put("kind", q.kind).put("skill", q.skill ?: JSONObject.NULL)
                .put("competency", q.competency ?: JSONObject.NULL).put("question", q.question).put("answer", q.answer ?: JSONObject.NULL)
                .put("seconds", q.seconds ?: JSONObject.NULL).put("feedback", q.feedback?.let { with(StudyCore) { it.toJson() } } ?: JSONObject.NULL) }))
            .put("finished", finished).put("summary", summary?.let { with(StudyCore) { it.toJson() } } ?: JSONObject.NULL)
        private fun mockFrom(o: JSONObject): MockSession {
            val qa = o.optJSONArray("questions") ?: JSONArray()
            return MockSession(o.getString("id"), o.optLong("created"), o.optString("source"), str(o, "job"), (0 until qa.length()).mapNotNull { qa.optJSONObject(it) }.map { q ->
                MockQuestion(q.optInt("i"), q.optString("kind"), str(q, "skill"), str(q, "competency"), q.optString("question"), str(q, "answer"),
                    if (q.isNull("seconds")) null else q.optInt("seconds"), StudyCore.feedbackFrom(q.optJSONObject("feedback")))
            }, o.optBoolean("finished"), StudyCore.mockSummaryFrom(o.optJSONObject("summary")))
        }
        fun Quality.toJson(): JSONObject = JSONObject().put("model", model).put("verdict", verdict).put("probes", JSONObject(probes as Map<*, *>))
            .put("seconds", JSONObject().apply { seconds.forEach { (k, v) -> put(k, v) } }).put("at", at).put("advice", advice)
        private fun qualityFrom(o: JSONObject): Quality {
            val p = o.optJSONObject("probes"); val sec = o.optJSONObject("seconds")
            return Quality(o.optString("model"), o.optString("verdict"), LinkedHashMap<String, Boolean>().apply { p?.keys()?.forEach { put(it, p.optBoolean(it)) } },
                LinkedHashMap<String, Double>().apply { sec?.keys()?.forEach { put(it, sec.optDouble(it)) } }, o.optLong("at"), o.optString("advice"))
        }

        fun Question.toJson(): JSONObject = JSONObject().put("key", key).put("q", q).put("options", JSONArray(options)).put("answer", answer)
            .put("explanation", explanation).put("topic", topic).put("difficulty", difficulty).put("verification", "AI-generated: not independently verified")
            .put("skill", skill).put("module", module).put("module_id", moduleId).put("course_id", courseId ?: JSONObject.NULL)
        fun questionFrom(o: JSONObject) = Question(o.optString("key"), o.optString("q"), strs(o.optJSONArray("options")), o.optInt("answer"),
            o.optString("explanation"), o.optString("topic"), o.optString("difficulty"), o.optString("skill"), o.optString("module"),
            o.optString("module_id"), str(o, "course_id"))

        fun Plan.toJson(): JSONObject = JSONObject().put("created", created).put("interview", interview ?: JSONObject.NULL).put("minutes", minutes)
            .put("skills", skills.toJson()).put("resume_skills", JSONArray(resumeSkills)).put("gaps", JSONArray(gaps.map { it.toJson() }))
            .put("days", JSONArray(days.map { it.toJson() })).put("done", JSONArray(done)).put("jd_title", jdTitle)
        private fun planFrom(o: JSONObject): Plan {
            val g = o.optJSONArray("gaps") ?: JSONArray(); val dd = o.optJSONArray("days") ?: JSONArray()
            return Plan(o.optLong("created"), str(o, "interview"), o.optInt("minutes", 60), StudyCore.skillsFrom(o.optJSONObject("skills")),
                strs(o.optJSONArray("resume_skills")), (0 until g.length()).map { StudyCore.gapFrom(g.getJSONObject(it)) },
                (0 until dd.length()).map { StudyCore.planDayFrom(dd.getJSONObject(it)) }, strs(o.optJSONArray("done")).toMutableList(), o.optString("jd_title"))
        }

        private fun Story.toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("competencies", JSONArray(competencies))
            .put("situation", situation).put("task", task).put("action", action).put("result", result).put("updated", updated)
            .put("coach", coach?.let { c -> JSONObject().put("scores", JSONObject(c.scores as Map<*, *>)).put("missing", JSONArray(c.missing))
                .put("tips", JSONArray(c.tips)).put("improved", c.improved).put("at", c.at) } ?: JSONObject.NULL)
        private fun storyFrom(o: JSONObject): Story = Story(o.optString("id"), o.optString("title"), strs(o.optJSONArray("competencies")),
            o.optString("situation"), o.optString("task"), o.optString("action"), o.optString("result"), o.optLong("updated"),
            o.optJSONObject("coach")?.let { c -> val s = c.optJSONObject("scores") ?: JSONObject()
                Coach(LinkedHashMap<String, Int>().apply { s.keys().forEach { k -> put(k, s.optInt(k)) } }, strs(c.optJSONArray("missing")),
                    strs(c.optJSONArray("tips")), c.optString("improved"), c.optLong("at")) })
    }
}
