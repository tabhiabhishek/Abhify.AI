package ai.abhify.android.learn

import ai.abhify.android.learn.StudyCore.Module
import ai.abhify.android.learn.StudyCore.Stat
import ai.abhify.android.learn.StudyData.Capstone
import ai.abhify.android.learn.StudyData.Card
import ai.abhify.android.learn.StudyData.Coach
import ai.abhify.android.learn.StudyData.Course
import ai.abhify.android.learn.StudyData.HistoryItem
import ai.abhify.android.learn.StudyData.LessonEntry
import ai.abhify.android.learn.StudyData.Milestone
import ai.abhify.android.learn.StudyData.Overview
import ai.abhify.android.learn.StudyData.Plan
import ai.abhify.android.learn.StudyData.Question
import ai.abhify.android.learn.StudyData.ReviewEntry
import ai.abhify.android.learn.StudyData.Story
import ai.abhify.android.learn.StudyData.Companion.toJson
import ai.abhify.android.learn.StudyData.Wrong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** A user-readable Study problem (bad input, unusable AI answer, unknown id). */
open class StudyError(message: String) : Exception(message)

/** A practice batch where every question failed the checks or repeated an earlier one. */
class NoNewQuestions : StudyError("The AI's questions didn't pass the checks this time. Scroll again for a new batch.")

/** The AI for Study: one prompt in, the model's text out. On the phone: the on-device model (StudyState). */
fun interface StudyAi {
    suspend fun complete(prompt: String, json: Boolean, maxTokens: Int): String
}

/**
 * 7.2.0-dev.2 Personal AI Brain: the learner's own memories and projects on this phone, offered per course. Study reads
 * only the items the learner ticks for a course; chat history is never part of it.
 */
interface BrainSource {
    class Memory(val id: Long, val text: String, val goal: Boolean, val category: String = "")
    class Project(val id: String, val name: String, val instructions: String)
    fun memories(): List<Memory>
    fun projects(): List<Project>
    /** 7.2.1: the app-wide memory switch. When off, a course's personal context is not used (the ticked items are kept). */
    fun memoryEnabled(): Boolean = true
    companion object {
        val NONE = object : BrainSource { override fun memories() = emptyList<Memory>(); override fun projects() = emptyList<Project>() }
    }
}

/**
 * Study 7.2 on the phone: storage (filesDir/study.json, atomic write) and the AI flows, ported from
 * frontend/study-local.js / backend/study.py with the same validators and messages. Every number it reports comes
 * from recorded progress. The file is read and written under one lock; AI calls run outside it.
 */
class StudyService(
    private val file: File,
    private val tracksJson: () -> String,
    private val taxonomyJson: () -> String,
    private val ai: StudyAi,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /** 7.2.0-dev.2: the learner's memories and projects, offered for the per-course Personal AI Brain opt-in (none in tests). */
    private val brain: BrainSource = BrainSource.NONE,
    /** 7.2.0-dev.2: called after every successful save (the home-screen widget refreshes). Must not block or call back in. */
    private val onSaved: () -> Unit = {},
) {
    class Track(val id: String, val title: String, val summary: String, val icon: String, val modules: List<Module>)
    class Competency(val id: String, val title: String, val question: String)
    class Tracks(val tracks: List<Track>, val competencies: List<Competency>)
    class PrefsView(val target: String, val levelKey: String, val level: String, val minutes: Int, val optional: List<String>, val chosen: Boolean, val key: String)
    class LessonView(val id: String, val title: String, val done: Boolean, val ready: Boolean)
    class ModuleView(val module: Module, val lessons: List<LessonView>)
    class CourseDetail(val course: Course, val modules: List<ModuleView>)
    class LessonResult(val entry: LessonEntry, val cached: Boolean)
    class McqBatch(val questions: List<Question>, val module: String, val moduleId: String, val dropped: Int)
    class Assessment(val answered: Int, val correct: Int, val percent: Int?)
    class MasteryRow(val mastery: StudyCore.Mastery, val title: String, val optional: Boolean)
    class Revision(val cardsDue: Int, val questionsDue: Int, val wrongThisWeek: Int)
    class NextLesson(val id: String, val title: String, val module: String)
    class CapstoneSummary(val title: String, val done: Int, val total: Int)
    class Dashboard(val id: String, val title: String, val prefs: PrefsView, val overview: Overview?, val progress: Int, val lessonsDone: Int,
                    val lessonsTotal: Int, val modulesDone: Int, val modulesTotal: Int, val seconds: Long, val secondsWeek: Long, val assessment: Assessment,
                    val exercisesDone: Int, val mastery: List<MasteryRow>, val weak: List<Pair<String, String>>, val revision: Revision, val streak: Int,
                    val nextLesson: NextLesson?, val schedule: StudyCore.Schedule, val capstone: CapstoneSummary?, val history: List<HistoryItem>,
                    val planChange: PlanChange?)
    /** After a goal/level change and a new overview: modules that became core or optional, and finished lessons kept. */
    class PlanChange(val previousTarget: String, val previousLevel: String, val nowCore: List<String>, val nowOptional: List<String>, val kept: Int)
    class CourseSummary(val id: String, val title: String, val track: String?, val done: Int, val total: Int)
    class TodayItem(val item: StudyCore.PlanItem, val key: String, val done: Boolean)
    class State(val courses: List<CourseSummary>, val plan: Plan?, val readiness: StudyCore.Readiness, val today: List<TodayItem>, val streak: Int,
                val reviewsDue: Int, val stories: Int, val competencies: Map<String, Int>)
    class DueReview(val key: String, val review: ReviewEntry)
    class StoryInput(val id: String?, val title: String, val competencies: List<String>, val situation: String, val task: String, val action: String, val result: String)

    /** One lock per file for the whole process: the app, the reminder, the lesson-pack worker and the widget share it. */
    private val lock = lockFor(file)
    private var tracksCache: Tracks? = null
    private var taxCache: List<StudyCore.Term>? = null

    // ------------------------------------------------------------------ storage
    private val bak get() = File(file.parentFile, file.name + ".bak")

    /**
     * The saved data. A file that can't be read is never overwritten: it is renamed to study.corrupt-<millis>.json and
     * the last good copy (study.json.bak) is used, or an empty start. A file from a newer app version is refused.
     */
    private fun load(): StudyData {
        if (!file.isFile) return StudyData()
        return try { StudyData.parse(file.readText()) }
        catch (e: StudyData.NewerSchema) { throw StudyError("Your study data was saved by a newer Abhify.AI. Update the app to open it; nothing was changed.") }
        catch (e: Exception) {
            val quarantine = File(file.parentFile, "study.corrupt-${now()}.json")
            if (!file.renameTo(quarantine)) runCatching { file.copyTo(quarantine, overwrite = true); file.delete() }
            val restored = if (bak.isFile) runCatching { StudyData.parse(bak.readText()) }.getOrNull() else null
            restored?.also { save(it) } ?: StudyData()
        }
    }

    /** Atomic: write a temp file, keep the previous readable file as study.json.bak, then move the temp file in place. */
    private fun save(d: StudyData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { out -> out.write(d.toJson().toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
            if (file.isFile && runCatching { JSONObject(file.readText()) }.isSuccess) {
                bak.delete(); if (!file.renameTo(bak)) file.copyTo(bak, overwrite = true)
            }
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw StudyError("Couldn't save your study progress.") }
        } catch (e: java.io.IOException) { throw StudyError("Couldn't save your study progress: the phone's storage may be full.") }
        runCatching { onSaved() }
    }
    private suspend fun <T> edit(block: suspend (StudyData) -> T): T = lock.withLock { val d = load(); val r = block(d); save(d); r }
    private suspend fun <T> read(block: suspend (StudyData) -> T): T = lock.withLock { block(load()) }

    private fun todayIso() = today().toString()
    private fun touch(d: StudyData) { val t = todayIso(); if (t !in d.activity) { d.activity += t; while (d.activity.size > 400) d.activity.removeAt(0) } }
    private fun history(d: StudyData, cid: String, kind: String, detail: String) {
        val c = d.courses[cid] ?: return
        c.history += HistoryItem(now(), kind, detail.take(120)); while (c.history.size > 300) c.history.removeAt(0)
    }
    private fun newId(p: String) = p + (1..10).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    // ------------------------------------------------------------------ catalogue
    fun tracks(): Tracks = tracksCache ?: run {
        val o = JSONObject(tracksJson())
        val ta = o.getJSONArray("tracks"); val ca = o.optJSONArray("competencies") ?: JSONArray()
        Tracks((0 until ta.length()).map { ta.getJSONObject(it) }.map { t ->
            val ms = t.getJSONArray("modules")
            Track(t.getString("id"), t.getString("title"), t.optString("summary"), t.optString("icon"), (0 until ms.length()).map { StudyCore.moduleFrom(ms.getJSONObject(it)) })
        }, (0 until ca.length()).map { ca.getJSONObject(it) }.map { Competency(it.getString("id"), it.getString("title"), it.optString("question")) })
    }.also { tracksCache = it }
    fun taxonomy(): List<StudyCore.Term> = taxCache ?: StudyCore.parseTaxonomy(taxonomyJson()).also { taxCache = it }

    private fun track(tid: String) = tracks().tracks.firstOrNull { it.id == tid } ?: throw StudyError("Unknown course.")
    fun modulesOf(c: Course): List<Module> = if (c.track != null) track(c.track).modules else c.modules
    private fun course(d: StudyData, cid: String) = d.courses[cid] ?: throw StudyError("Unknown course.")
    private fun findLesson(c: Course, lid: String): Pair<Module, StudyCore.Lesson> {
        for (m in modulesOf(c)) for (l in m.lessons) if (l.id == lid) return m to l
        throw StudyError("Unknown lesson.")
    }

    fun prefsOf(c: Course): PrefsView {
        val p = c.prefs
        val target = (p?.target ?: "").trim().ifEmpty { "General knowledge / personal learning" }.take(80)
        val raw = p?.level.orEmpty()
        val levelKey = if (raw in LEVELS) raw else if (raw.isNotEmpty()) "custom" else "intermediate"
        val level = LEVELS[levelKey] ?: raw.take(80)
        return PrefsView(target, levelKey, level, p?.minutes ?: 60, c.overview?.optional.orEmpty(), p != null, "$target|$levelKey|$level")
    }

    // ------------------------------------------------------------------ AI helpers
    /** The model's JSON (an object or an array), tolerating text or code fences around it; two tries. */
    private suspend fun aiJson(prompt: String, maxTokens: Int = JSON_TOKENS): Any {
        repeat(2) {
            val text = ai.complete(prompt, true, maxTokens)
            parseJson(text)?.let { return it }
        }
        throw StudyError("The AI returned something unreadable. Try again.")
    }

    // ------------------------------------------------------------------ courses
    suspend fun createCourse(trackId: String): Course = edit { d ->
        val t = track(trackId)
        d.courses.values.firstOrNull { it.track == trackId } ?: Course("c-$trackId", trackId, t.title, emptyList(), now()).also { d.courses[it.id] = it }
    }

    suspend fun customCourse(topicIn: String): Course {
        val topic = topicIn.trim()
        if (topic.length < 2) throw StudyError("Type a topic for the course, for example \"Kubernetes for beginners\".")
        val o = aiJson("Create a short course outline for learning \"${topic.take(120)}\" for a job interview. Reply with JSON only: " +
            "{\"title\": \"...\", \"modules\": [{\"title\": \"...\", \"lessons\": [\"lesson title\", \"...\"]}]}. " +
            "Use 4 to 6 modules with 3 to 5 lessons each, ordered from basics to advanced. Lesson titles only, no content.")
        val (title, mods) = validateOutline(o, topic, taxonomy())
        return edit { d -> Course(newId("c-"), null, title, mods, now()).also { d.courses[it.id] = it } }
    }

    suspend fun deleteCourse(cid: String) = edit { d ->
        if (d.courses.remove(cid) == null) throw StudyError("Unknown course.")
        d.lessons.keys.removeAll { it.startsWith("$cid/") }
        d.cards.values.removeAll { it.course == cid }
        Unit
    }

    suspend fun courseDetail(cid: String): CourseDetail = read { d ->
        val c = course(d, cid)
        val cached = d.lessons.keys.filter { it.startsWith("$cid/") }.map { it.substringAfter('/') }.toSet()
        CourseDetail(c, modulesOf(c).map { m -> ModuleView(m, m.lessons.map { LessonView(it.id, it.title, it.id in c.done, it.id in cached) }) })
    }

    /** [prefetch]: an offline lesson pack writing ahead; it isn't study activity (no history entry, no streak day). */
    suspend fun lesson(cid: String, lid: String, refresh: Boolean = false, prefetch: Boolean = false): LessonResult {
        class Prep(val c: Course, val pr: PrefsView, val ml: Pair<Module, StudyCore.Lesson>, val cached: LessonEntry?)
        val p = read { d -> val c = course(d, cid); Prep(c, prefsOf(c), findLesson(c, lid), if (refresh) null else d.lessons["$cid/$lid"]) }
        val (ctx, used) = brainContext(p.c)                 // only what the learner ticked for this course (off by default)
        p.cached?.let { return LessonResult(it, true) }
        val c = p.c; val pr = p.pr; val (m, l) = p.ml
        val prompt = ctx + "Write an accurate lesson on \"${l.title}\" (module \"${m.title}\" of the course \"${c.title}\") for a learner whose goal is " +
            "${pr.target} at ${pr.level} level. Use Markdown with exactly these headings, in this order:\n" +
            "## In simple words\n## Going deeper\n## Advanced: production and architecture\n## Real-world example\n" +
            "## Code example (one short, runnable ${LANG[c.track] ?: "Python"} example in a fenced block; skip only if the topic has no code)\n" +
            "## Common mistakes\n## Practice exercise (one hands-on task with what a good solution includes)\n" +
            "## Interview questions (3, each with a 2-3 sentence model answer)\n## Follow-up questions (3 questions an interviewer may ask next)\n" +
            "${DEPTH[pr.levelKey] ?: DEPTH.getValue("intermediate")} Plain, friendly language. Stay on this lesson's topic. If something depends on a tool version, say so instead of guessing."
        var text = normalizeLesson(ai.complete(prompt, false, LESSON_TOKENS))
        if (!lessonOk(text)) text = normalizeLesson(ai.complete(prompt, false, LESSON_TOKENS))     // one automatic retry before asking the user
        if (!lessonOk(text)) throw StudyError("The AI's lesson was incomplete. Try again, or choose a larger model in AI Models.")
        val entry = LessonEntry(cid, lid, l.title, text.trim(), now(), "on-device", pr.key, used)
        edit { d ->
            course(d, cid)
            if (prefetch && d.lessons.containsKey("$cid/$lid")) return@edit             // written meanwhile in the app: keep that one
            d.lessons["$cid/$lid"] = entry
            if (!prefetch) { history(d, cid, "lesson", l.title); touch(d) }
        }
        return LessonResult(entry, false)
    }

    suspend fun setDone(cid: String, lid: String, done: Boolean): Course = edit { d ->
        val c = course(d, cid); val (_, l) = findLesson(c, lid)
        val s = c.done.toMutableSet(); if (done) s += lid else s -= lid; c.done = s.sorted().toMutableList()
        if (done) history(d, cid, "done", l.title)
        touch(d); c
    }

    suspend fun setExercise(cid: String, lid: String, done: Boolean): Course = edit { d ->
        val c = course(d, cid); val (_, l) = findLesson(c, lid)
        val s = c.exercises.toMutableSet(); if (done) s += lid else s -= lid; c.exercises = s.sorted().toMutableList()
        if (done) history(d, cid, "exercise", l.title)
        touch(d); c
    }

    // ------------------------------------------------------------------ preferences, overview, time
    suspend fun setPrefs(cid: String, targetIn: String, levelIn: String, minutes: Int): Course {
        val target = targetIn.trim().take(80); val level = levelIn.trim().take(80)
        if (target.isEmpty() || level.isEmpty()) throw StudyError("Choose a goal and a level.")
        return edit { d ->
            val c = course(d, cid)
            if (c.prefs != null) {                                // the earlier plan version is kept, not replaced
                val j = c.toJson()
                c.prefsHistory += JSONObject().put("prefs", j.get("prefs")).put("overview", j.get("overview")).put("at", now())
                while (c.prefsHistory.size > 20) c.prefsHistory.removeAt(0)
            }
            c.prefs = StudyData.Prefs(target, level, (if (minutes == 0) 60 else minutes).coerceIn(15, 240))
            c.overview = null                                     // rebuilt for the new goal; lessons done, scores and time are kept
            history(d, cid, "prefs", "$target · ${LEVELS[level] ?: level}"); c
        }
    }

    suspend fun buildOverview(cid: String): Overview {
        val (c, pr, mods) = read { d -> val c = course(d, cid); Triple(c, prefsOf(c), modulesOf(c)) }
        val syllabus = mods.joinToString("\n") { m -> "- ${m.id}: ${m.title} (${m.lessons.joinToString(", ") { it.title }})" }
        val o = aiJson("A learner wants to take the course \"${c.title}\". Goal: ${pr.target}. Level: ${pr.level}.\nThe course syllabus (module id: title (lessons)):\n$syllabus\n\n" +
            "Reply with JSON only: {\"summary\": \"2-3 sentences\", \"objectives\": [\"4-6\"], \"prerequisites\": [\"3-5\"], \"optional\": [\"module ids this learner can treat as optional review\"], " +
            "\"focus\": {\"module id\": \"why it matters for this goal\"}, \"completion\": [\"3-4 criteria\"]}. Use only modules from the syllabus.")
        val ov = validateOverview(o, mods, now())
        edit { d -> course(d, cid).overview = ov }
        return ov
    }

    suspend fun addTime(cid: String, seconds: Long): Long = edit { d ->
        val c = course(d, cid); val s = seconds.coerceIn(0, MAX_TIME_REPORT)
        c.seconds += s; val t = todayIso(); c.days[t] = (c.days[t] ?: 0) + s
        touch(d); c.seconds
    }

    /** Any study activity on this course counts (time, lessons, answers, cards, exercises, tutor), on the local date. */
    private fun activeDays(c: Course): List<String> = (c.days.keys + c.history.filter { it.at > 0 }.map { localDate(it.at) } + c.wrong.map { it.at }.filter { it.isNotEmpty() })
        .toSortedSet().toList()
    private fun localDate(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    private fun weakModules(c: Course, mods: List<Module>): List<String> =
        mods.map { StudyCore.moduleMastery(it, c.done, c.scores) }
            .sortedBy { it.correct.toDouble() / maxOf(1, it.answered) }.filter { it.weak }.map { it.module }

    private fun dueReviews(d: StudyData, t: String, cid: String? = null) =
        d.reviews.entries.filter { (_, v) -> !v.done && v.due != null && v.due!! <= t && (cid == null || v.course == cid) }.map { DueReview(it.key, it.value) }

    suspend fun dueReviews(): List<DueReview> = read { d -> dueReviews(d, todayIso()) }

    suspend fun dashboard(cid: String): Dashboard = read { d ->
        val c = course(d, cid); val pr = prefsOf(c); val mods = modulesOf(c); val t = today(); val ts = t.toString()
        val rows = mods.map { StudyCore.moduleMastery(it, c.done, c.scores) }
        val total = mods.sumOf { it.lessons.size }
        val answered = rows.sumOf { it.answered }; val correct = rows.sumOf { it.correct }
        val titles = mods.associate { it.id to it.title }
        val weekAgo = t.minusDays(6).toString()
        val nxt = StudyCore.nextLesson(mods, c.done, pr.optional)
        val nl = nxt?.let { findLesson(c, it) }
        Dashboard(cid, c.title, pr, c.overview,
            if (total > 0) StudyCore.pyRound(100.0 * c.done.size / total) else 0, c.done.size, total,
            rows.count { it.lessonsDone == it.lessonsTotal }, mods.size,
            c.seconds, c.days.filterKeys { it >= weekAgo }.values.sum(),
            Assessment(answered, correct, if (answered > 0) StudyCore.pyRound(100.0 * correct / answered) else null),
            c.exercises.size, rows.map { MasteryRow(it, titles[it.module].orEmpty(), it.module in pr.optional) },
            weakModules(c, mods).map { it to titles[it].orEmpty() },
            Revision(d.cards.values.count { it.course == cid && !it.done && (it.due ?: "") <= ts }, dueReviews(d, ts, cid).size,
                c.wrong.count { it.at >= weekAgo }),
            activeDays(c).let { if (it.isEmpty()) 0 else StudyCore.streak(it, t) },
            if (nxt != null && nl != null) NextLesson(nxt, nl.second.title, nl.first.title) else null,
            StudyCore.courseSchedule(mods, pr.minutes, t, pr.optional, c.done),
            c.capstone?.let { CapstoneSummary(it.title, it.milestones.count { m -> m.done }, it.milestones.size) },
            c.history.asReversed().take(30), planChange(c, mods))
    }

    // ------------------------------------------------------------------ flashcards
    suspend fun makeCards(cid: String, lid: String): List<Pair<String, String>> {
        val (ml, les) = read { d -> val c = course(d, cid); findLesson(c, lid) to d.lessons["$cid/$lid"] }
        if (les == null) throw StudyError("Open the lesson first, then make flashcards from it.")
        val o = aiJson("Make 6 to 8 flashcards for revising this lesson. Reply with JSON only: {\"cards\": [{\"front\": \"a short question\", \"back\": \"a 1-2 sentence answer\"}]}. " +
            "Use only facts from the lesson.\n\n${les.markdown.take(3000)}")
        val cards = validateCards(o)
        val (m, l) = ml
        edit { d ->
            course(d, cid)
            val lk = "$cid/$lid"
            d.cards.values.removeAll { it.lesson == lk }
            for ((f, b) in cards) d.cards[newId("f-")] = Card(f, b, cid, lk, m.id, l.title, todayIso(), 0, false)
            history(d, cid, "cards", l.title)
        }
        return cards
    }

    /** Cards due today (daily), or every card from lessons written in the last 7 days (weekly). */
    suspend fun dueCards(cid: String? = null, weekly: Boolean = false): List<Pair<String, Card>> = read { d ->
        val t = todayIso()
        if (weekly) {
            val since = now() - 7 * 86_400_000L
            val recent = d.lessons.filterValues { it.at >= since }.keys
            d.cards.entries.filter { (_, v) -> v.lesson in recent && (cid == null || v.course == cid) }.map { it.key to it.value }
        } else d.cards.entries.filter { (_, v) -> !v.done && (v.due ?: "") <= t && (cid == null || v.course == cid) }.map { it.key to it.value }
    }

    suspend fun gradeCard(cardId: String, remembered: Boolean): Card = edit { d ->
        val v = d.cards[cardId] ?: throw StudyError("Unknown flashcard.")
        val r = StudyCore.nextReview(if (remembered) v.timesRight else 0, today(), remembered, v.lapses)
        v.due = r.due; v.timesRight = r.timesRight; v.done = r.done; v.lapses = r.lapses
        touch(d); v
    }

    // ------------------------------------------------------------------ capstone and tutor
    suspend fun makeCapstone(cid: String): Capstone {
        val (c, pr, mods) = read { d -> val c = course(d, cid); Triple(c, prefsOf(c), modulesOf(c)) }
        val o = aiJson("Design one capstone project for the course \"${c.title}\" (modules: ${mods.joinToString(", ") { it.title }}) for a learner aiming at ${pr.target}, level ${pr.level}. " +
            "It must use what the course teaches and be doable on a laptop for free. Reply with JSON only: {\"title\": \"...\", \"brief\": \"3-4 sentences\", \"milestones\": [\"4-6 concrete steps\"], \"stretch\": [\"1-3 optional extras\"]}.")
        val cap = validateCapstone(o)
        edit { d -> course(d, cid).capstone = cap; history(d, cid, "capstone", cap.title) }
        return cap
    }

    suspend fun capstoneStep(cid: String, index: Int, done: Boolean): Capstone = edit { d ->
        val cap = d.courses[cid]?.capstone
        if (cap == null || index !in cap.milestones.indices) throw StudyError("That capstone step doesn't exist.")
        cap.milestones[index].done = done
        if (done) history(d, cid, "capstone step", cap.milestones[index].title)
        touch(d); cap
    }

    /** The tutor sees only this lesson's text and the learner's goal and level. */
    suspend fun tutor(cid: String, lid: String, questionIn: String): String {
        val question = questionIn.trim()
        if (question.length < 3) throw StudyError("Type your question.")
        val (c, l, les) = read { d -> val c = course(d, cid); Triple(c, findLesson(c, lid).second, d.lessons["$cid/$lid"]?.markdown.orEmpty()) }
        val pr = prefsOf(c)
        val ctx = brainContext(c).first
        val text = ai.complete(ctx + "The learner is studying the lesson \"${l.title}\" in the course \"${c.title}\" (goal: ${pr.target}, level: ${pr.level}).\n" +
            (if (les.isNotEmpty()) "The lesson:\n${les.take(3000)}\n\n" else "") + "Their question: ${question.take(1500)}\n\nAnswer clearly and kindly in Markdown, with a small example if it helps.",
            false, 700).trim()
        if (text.isEmpty()) throw StudyError("The AI tutor didn't answer. Try again.")
        edit { d -> history(d, cid, "tutor", l.title) }
        return text
    }

    // ------------------------------------------------------------------ unlimited MCQs
    /** Which module the [round]-th batch is about: core modules in rotation, or one module, or the weak ones. */
    fun mcqModule(c: Course, round: Int, moduleId: String?, weak: Boolean): Module {
        val mods = modulesOf(c); val pr = prefsOf(c)
        val core = mods.filter { it.id !in pr.optional }.ifEmpty { mods }
        var m = core[Math.floorMod(round, core.size)]
        if (moduleId != null) m = mods.firstOrNull { it.id == moduleId } ?: m
        else if (weak) { val w = weakModules(c, mods); if (w.isNotEmpty()) m = mods.firstOrNull { it.id == w[Math.floorMod(round, w.size)] } ?: m }
        return m
    }

    suspend fun courseMcq(cid: String, round: Int, avoid: List<String> = emptyList(), moduleId: String? = null, weak: Boolean = false,
                          count: Int = 5, difficulty: String = "medium"): McqBatch {
        val (c, seen) = read { d -> course(d, cid) to d.seen.toSet() }
        val m = mcqModule(c, round, moduleId, weak)
        val level = mapOf("beginner" to "fresher", "intermediate" to "junior", "advanced" to "senior")[prefsOf(c).levelKey] ?: "mid-level"
        val topic = "${m.title} (${c.title})".take(120)
        val o = aiJson("Write $count $difficulty multiple-choice interview questions for a $level candidate about the topic \"$topic\". " +
            "Each has exactly 4 different options and one correct answer that is a well-established fact. " +
            (if (avoid.isNotEmpty()) "Do not repeat these earlier questions: ${avoid.takeLast(15).joinToString(" | ") { it.take(70) }}. " else "") +
            "Answer ONLY with JSON: {\"questions\": [{\"q\": question, \"options\": [4 strings], \"answer\": index 0-3, \"explanation\": why}]}.")
        val batch = validateMcq(o, seen + avoid.map(::normQ), count, topic, difficulty, m.skills.firstOrNull() ?: c.title, m, cid, random)
        if (batch.questions.isEmpty()) throw NoNewQuestions()
        edit { d -> d.seen += batch.questions.map { normQ(it.q) }; while (d.seen.size > 3000) d.seen.removeAt(0) }
        return batch
    }

    /** Records an answer: per skill, per module of its course, the wrong-answer list, and the review schedule. */
    suspend fun answer(q: Question, correct: Boolean, skill: String = q.skill, courseId: String? = q.courseId, moduleId: String? = q.moduleId.ifEmpty { null }): Stat = edit { d ->
        val t = today()
        val s = d.mcq[skill] ?: Stat()
        val ns = Stat(s.answered + 1, s.correct + if (correct) 1 else 0); d.mcq[skill] = ns
        val c = courseId?.let { d.courses[it] }
        if (c != null && moduleId != null) {
            val m = c.scores[moduleId] ?: Stat()
            c.scores[moduleId] = Stat(m.answered + 1, m.correct + if (correct) 1 else 0)
            c.days.putIfAbsent(t.toString(), 0)                  // an active day for the course streak (no time added)
            if (!correct) { c.wrong += Wrong(q, t.toString()); while (c.wrong.size > 200) c.wrong.removeAt(0) }
        }
        val key = q.key.ifEmpty { qKey(q.q) }
        val r = d.reviews[key]
        if (r != null || !correct) {
            val nr = StudyCore.nextReview(r?.timesRight ?: 0, t, correct, r?.lapses ?: 0)
            d.reviews[key] = ReviewEntry(r?.question ?: q, r?.skill ?: skill, r?.course ?: courseId, nr.due, nr.timesRight, nr.done, nr.lapses)
        }
        touch(d); ns
    }

    // ------------------------------------------------------------------ interview plan
    suspend fun makePlan(jd: String, resume: String = "", interview: String? = null, minutes: Int = 60): Plan {
        if (jd.trim().length < 30) throw StudyError("Paste the job description (at least a few lines).")
        val date = interview?.takeIf { it.isNotBlank() }?.let {
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it)) throw StudyError("The interview date isn't valid.")
            runCatching { LocalDate.parse(it) }.getOrElse { throw StudyError("The interview date isn't valid.") }
        }
        val tax = taxonomy(); val skills = StudyCore.extractSkills(jd, tax)
        if (skills.required.isEmpty() && skills.preferred.isEmpty())
            throw StudyError("No known skills were found in this job description. Add the skills section, or pick a ready-made course instead.")
        val resumeSkills = StudyCore.findSkills(resume, tax)
        // a new plan version keeps what was already proved: skills already Ready move to the end as a light refresh
        val g0 = StudyCore.gaps(skills, resumeSkills)
        val g = read { d -> if (d.mcq.isEmpty()) g0
            else StudyCore.adaptGaps(g0, StudyCore.readiness(g0.map { it.skill }, d.mcq, lessonProgress(d)).skills.map { it.skill to it.score }) }
        val plan = Plan(now(), date?.toString(), minutes, skills, resumeSkills, g, StudyCore.makePlan(g, date, today(), minutes), ArrayList(),
            jd.trim().lines().firstOrNull().orEmpty().take(100))
        edit { d ->
            d.plan?.let { old -> d.planHistory += old.toJson().put("archived_at", now()); while (d.planHistory.size > 10) d.planHistory.removeAt(0) }
            d.plan = plan; touch(d)
        }
        return plan
    }

    suspend fun planItem(day: Int, index: Int, done: Boolean): Plan = edit { d ->
        val p = d.plan
        if (p == null || day !in 1..p.days.size || index !in p.days[day - 1].items.indices) throw StudyError("That plan item doesn't exist.")
        val s = p.done.toMutableSet(); val k = "$day:$index"; if (done) s += k else s -= k; p.done = s.sorted().toMutableList()
        touch(d); p
    }

    suspend fun deletePlan() = edit { d -> d.plan = null }

    // ------------------------------------------------------------------ STAR stories
    suspend fun stories(): List<Story> = read { d -> d.stories.toList() }

    suspend fun saveStory(st: StoryInput, coach: Coach? = null): Story {
        val title = st.title.trim().take(120)
        if (title.isEmpty()) throw StudyError("Give the story a short title.")
        val comp = tracks().competencies.map { it.id }.toSet()
        return edit { d ->
            val old = d.stories.firstOrNull { it.id == st.id }
            val s = Story(st.id?.takeIf { it.isNotEmpty() } ?: newId("s-"), title, st.competencies.filter { it in comp }.distinct(),
                st.situation.trim().take(2000), st.task.trim().take(2000), st.action.trim().take(2000), st.result.trim().take(2000), now(), coach ?: old?.coach)
            d.stories.removeAll { it.id == s.id }; d.stories += s; touch(d); s
        }
    }

    suspend fun deleteStory(id: String) = edit { d -> if (!d.stories.removeAll { it.id == id }) throw StudyError("Unknown story.") }

    suspend fun coach(sid: String): Coach {
        val s = read { d -> d.stories.firstOrNull { it.id == sid } } ?: throw StudyError("Unknown story.")
        if (STAR.sumOf { s.field(it).length } < 40) throw StudyError("Write a few sentences in the story first.")
        val o = aiJson("You are an interview coach. Rate this STAR interview story. Reply with JSON only: " +
            "{\"scores\": {\"situation\": 1-5, \"task\": 1-5, \"action\": 1-5, \"result\": 1-5}, \"missing\": [\"...\"], \"tips\": [\"...\"], " +
            "\"improved\": \"a tighter first-person version, under 180 words, keeping only facts given below\"}. Never invent facts.\n\n" +
            STAR.joinToString("\n") { "${it.replaceFirstChar(Char::uppercase)}: ${s.field(it)}" })
        val fb = guardFacts(validateCoach(o, now()), STAR.joinToString("\n") { s.field(it) })
        saveStory(StoryInput(s.id, s.title, s.competencies, s.situation, s.task, s.action, s.result), fb)
        return fb
    }

    private fun planChange(c: Course, mods: List<Module>): PlanChange? {
        val last = c.prefsHistory.lastOrNull() ?: return null
        val ov = c.overview ?: return null
        val titles = mods.associate { it.id to it.title }
        val before = StudyData.strs(last.optJSONObject("overview")?.optJSONArray("optional")).toSet()
        val now = ov.optional.toSet()
        val prev = last.optJSONObject("prefs") ?: JSONObject()
        val lv = prev.optString("level")
        return PlanChange(prev.optString("target"), LEVELS[lv] ?: lv, (before - now).sorted().mapNotNull { titles[it] }, (now - before).sorted().mapNotNull { titles[it] }, c.done.size)
    }

    /** Lessons done / total per skill, over the ready-made tracks and the learner's custom courses. */
    private fun lessonProgress(d: StudyData): Map<String, StudyCore.Progress> {
        val done = d.courses.values.flatMap { it.done }.toSet()
        val perSkill = StudyCore.lessonsPerSkill(tracks().tracks.flatMap { it.modules }, done)
        for (c in d.courses.values) {
            if (c.track != null) continue
            for (m in c.modules) for (s in m.skills) {
                val r = perSkill[s] ?: StudyCore.Progress()
                perSkill[s] = StudyCore.Progress(r.done + m.lessons.count { it.id in c.done }, r.total + m.lessons.size)
            }
        }
        return perSkill
    }

    // ------------------------------------------------------------------ export / import (user-chosen file; no sync)
    class Imported(val courses: Int, val stories: Int, val cards: Int)

    suspend fun exportJson(): String = read { d ->
        JSONObject().put("format", EXPORT_FORMAT).put("exported_at", java.time.LocalDateTime.now().withNano(0).toString()).put("data", d.toJson()).toString()
    }

    /** Restores an export. The current file is kept first as study.before-import-<millis>.json; nothing is merged silently. */
    suspend fun importJson(text: String): Imported {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val data = o?.optJSONObject("data")
        if (o?.optString("format") != EXPORT_FORMAT || data == null || data.optJSONObject("courses") == null) throw StudyError("This file isn't an Abhify Study export.")
        val d = try { StudyData.parse(data.toString()) }
            catch (e: StudyData.NewerSchema) { throw StudyError("This export was made by a newer Abhify.AI. Update the app to import it.") }
            catch (e: Exception) { throw StudyError("This file isn't an Abhify Study export.") }
        return lock.withLock {
            if (file.isFile) file.copyTo(File(file.parentFile, "study.before-import-${now()}.json"), overwrite = true)
            save(d)
            val n = load()
            Imported(n.courses.size, n.stories.size, n.cards.size)
        }
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: Personal AI Brain opt-in (per course, off by default)
    class BrainSources(val goals: List<BrainSource.Memory>, val memories: List<BrainSource.Memory>, val projects: List<BrainSource.Project>,
                       val memoryEnabled: Boolean = true)

    /** What the learner may choose to share with a course: goal memories, other memories (up to 60) and projects. */
    fun brainSources(): BrainSources {
        val mems = runCatching { brain.memories() }.getOrDefault(emptyList())
        return BrainSources(mems.filter { it.goal }, mems.filter { !it.goal }.take(60), runCatching { brain.projects() }.getOrDefault(emptyList()),
            runCatching { brain.memoryEnabled() }.getOrDefault(true))
    }

    suspend fun setBrain(cid: String, enabled: Boolean, memoryIds: List<Long>, projectIds: List<String>): StudyData.Brain = edit { d ->
        val c = course(d, cid)
        val b = StudyData.Brain(enabled, memoryIds.distinct().take(20), projectIds.distinct().take(10))
        c.brain = b
        history(d, cid, "brain", "Personal context " + if (enabled) "on" else "off")
        b
    }

    /**
     * The learner-approved context for this course's prompts, and labels of what was used ("" when off). Only the items
     * ticked for this course; never chat history. Items deleted since are skipped.
     */
    fun brainContext(c: Course): Pair<String, List<String>> {
        val b = c.brain
        if (b == null || !b.enabled) return "" to emptyList()
        if (!runCatching { brain.memoryEnabled() }.getOrDefault(true)) return "" to emptyList()   // 7.2.1: memory switch wins
        val mems = runCatching { brain.memories() }.getOrDefault(emptyList()).associateBy { it.id }
        val projs = runCatching { brain.projects() }.getOrDefault(emptyList()).associateBy { it.id }
        val lines = ArrayList<String>(); val used = ArrayList<String>()
        for (id in b.memories) {
            val m = mems[id] ?: continue
            lines += "- ${if (m.goal) "Goal" else "About me"}: ${m.text.take(200)}"
            used += (if (m.goal) "Goal: " else "Note: ") + m.text.take(60)
        }
        for (id in b.projects) {
            val p = projs[id] ?: continue
            lines += "- Project ${p.name}: ${p.instructions.take(200)}"
            used += "Project: " + p.name
        }
        if (lines.isEmpty()) return "" to emptyList()
        val text = lines.joinToString("\n").take(1200)
        return ("Learner context, shared by the learner for this course only (use it to choose examples; do not repeat it back):\n$text\n\n") to used
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: model quality check (never blocks the model)
    /** Three fixed probes on the model Study uses. The result is cached per model name in study.json. */
    suspend fun qualityCheck(model: String): StudyData.Quality {
        val probes = LinkedHashMap<String, Boolean>(); val seconds = LinkedHashMap<String, Double>()
        for ((k, prompt) in QUALITY_PROBES) {
            val t = System.nanoTime()
            val out = ai.complete(prompt, k == "json_mcq", if (k == "lesson_sections") 700 else if (k == "fact") 40 else JSON_TOKENS)
            probes[k] = probeOk(k, out)
            seconds[k] = Math.rint((System.nanoTime() - t) / 1e8) / 10.0
        }
        val verdict = StudyCore.qualityVerdict(probes)
        val q = StudyData.Quality(model, verdict, probes, seconds, now(), ADVICE.getValue(verdict))
        edit { d -> d.quality[model] = q }
        return q
    }

    suspend fun qualityStatus(model: String?): StudyData.Quality? = if (model == null) null else read { d -> d.quality[model] }

    // ------------------------------------------------------------------ 7.2.0-dev.2: resume tailoring (nothing stored, nothing online)
    class TailoredBullet(val original: String, val rewritten: String?, val rejected: String?, val skills: List<String>)
    class Tailored(val bullets: List<TailoredBullet>, val jobSkills: List<String>, val covered: List<String>, val missing: List<String>, val note: String)

    suspend fun tailor(resume: String, jd: String): Tailored {
        if (jd.trim().length < 30) throw StudyError("Paste the job description (at least a few lines).")
        val bullets = resumeBullets(resume)
        if (bullets.isEmpty()) throw StudyError("No resume bullet points were found. Paste your resume text (each achievement on its own line).")
        val tax = taxonomy()
        val jdSk = StudyCore.extractSkills(jd, tax)
        val jdAll = jdSk.required + jdSk.preferred
        val pick = rankBullets(bullets, jdAll, tax)
        val listing = pick.withIndex().joinToString("\n") { (k, i) -> "$k. ${bullets[i]}" }
        val o = aiJson("Rewrite these resume bullet points so they speak to the job below. Rules: keep every fact exactly as given; never add a number, " +
            "employer, tool or technology that is not in the bullet or the resume; start with a strong verb; one line each. " +
            "Reply with JSON only: {\"bullets\": [{\"i\": number, \"text\": \"rewritten bullet\"}]}.\n\n" +
            "Job skills: ${jdAll.joinToString(", ").ifEmpty { "see the job text" }}\nJob description:\n${jd.take(3000)}\n\nBullets:\n$listing")
        val got = HashMap<Int, String>()
        val arr = (o as? JSONObject)?.optJSONArray("bullets") ?: JSONArray()
        for (n in 0 until arr.length()) {
            val x = arr.optJSONObject(n) ?: continue
            val i = x.opt("i"); val t = text(x, "text")
            if ((i is Int || i is Long) && t.isNotEmpty()) got.putIfAbsent(i.toInt(), t.take(400))
        }
        val out = pick.mapIndexed { k, i ->
            val new = got[k]
            val g = if (new != null) StudyCore.factGuard(resume, new, tax) else null
            val ok = new != null && g!!.ok
            TailoredBullet(bullets[i], if (ok) new else null,
                if (ok) null else if (new == null) "The AI didn't rewrite this one." else "Left out: it added " + (g!!.newNumbers + g.newSkills).joinToString(", ") + ", which isn't in your resume.",
                StudyCore.findSkills(new ?: bullets[i], tax).filter { it in jdAll })
        }
        val covered = bullets.flatMap { StudyCore.findSkills(it, tax) }.filter { it in jdAll }.toSortedSet().toList()
        return Tailored(out, jdAll, covered, jdAll.filter { it !in covered }, "Only facts already in your resume are allowed; nothing was saved or sent online.")
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: mock interview
    class MockListItem(val id: String, val created: Long, val job: String?, val questions: Int, val answered: Int, val summary: StudyCore.MockSummary?)
    class MockAnswer(val feedback: StudyCore.Feedback?, val note: String?)

    /** The skills a mock interview asks about: the interview plan's gaps, else the weakest practice skills. */
    private fun mockSkills(d: StudyData): List<String> {
        d.plan?.let { p -> return p.gaps.map { it.skill }.take(6) }
        return d.mcq.entries.sortedBy { it.value.correct.toDouble() / maxOf(1, it.value.answered) }.map { it.key }.take(6)
    }

    suspend fun mockStart(count: Int = 6, skillsIn: List<String>? = null): StudyData.MockSession {
        val d0 = read { it }
        val skills = (skillsIn ?: mockSkills(d0)).filter { it.isNotEmpty() }.take(8)
        val plan = StudyCore.mockPlan(skills, tracks().competencies.map { StudyCore.Theme(it.id, it.question) }, count)
        val tech = plan.filter { it.kind == "technical" }.mapNotNull { it.skill }
        val written = LinkedHashMap<String, String>()
        if (tech.isNotEmpty()) {
            val o = aiJson("Write one realistic interview question for a mid-level candidate for each of these skills: ${tech.joinToString(", ")}. " +
                "Each question should need a 1-2 minute spoken answer. Reply with JSON only: " +
                "{\"questions\": [{\"skill\": \"skill name\", \"question\": \"...\"}]}.")
            val arr = (o as? JSONObject)?.optJSONArray("questions") ?: (o as? JSONArray) ?: JSONArray()
            for (n in 0 until arr.length()) {
                val x = arr.optJSONObject(n) ?: continue
                val q = text(x, "question"); val sk = x.optString("skill")
                if (q.isNotEmpty() && sk in tech) written.putIfAbsent(sk, q.take(300))
            }
        }
        val qs = plan.mapIndexed { i, q ->
            val t = if (q.kind == "behavioural") q.question.orEmpty() else written[q.skill] ?: "Explain ${q.skill} and describe how you have used it in a real project."
            StudyData.MockQuestion(i, q.kind, q.skill, q.competency, t)
        }
        val s = StudyData.MockSession(newId("m-"), now(), if (d0.plan != null) "plan" else "practice", d0.plan?.jdTitle, qs)
        edit { d -> d.mocks += s; while (d.mocks.size > MAX_MOCKS) d.mocks.removeAt(0); touch(d) }
        return s
    }

    private fun mock(d: StudyData, sid: String) = d.mocks.firstOrNull { it.id == sid } ?: throw StudyError("Unknown interview session.")

    suspend fun mockAnswer(sid: String, i: Int, answerIn: String, seconds: Int): MockAnswer {
        val answer = answerIn.trim().take(4000)
        if (answer.length < 3) throw StudyError("Say or type your answer first.")
        val s = read { d -> mock(d, sid) }
        if (i !in s.questions.indices) throw StudyError("Unknown question.")
        val q = s.questions[i]
        val about = if (q.kind == "technical") "the skill ${q.skill}" else "behavioural (STAR) answers"
        val prompt = "You are a fair interviewer. Score the candidate's answer to: \"${q.question}\" (about $about). " +
            "Reply with JSON only: {\"scores\": {\"relevance\": 1-5, \"depth\": 1-5, \"structure\": 1-5}, " +
            "\"evidence\": [\"short exact quotes from the answer that support the scores\"], \"tips\": [\"1-3 concrete tips\"]}. " +
            "Judge only what the candidate said; never assume experience they did not mention.\n\nAnswer:\n" + answer
        var fb: StudyCore.Feedback? = null
        for (attempt in 0 until 2) { fb = StudyCore.checkFeedback(aiJson(prompt), answer); if (fb != null) break }
        edit { d ->
            val mq = mock(d, sid).questions[i]
            mq.answer = answer; mq.seconds = seconds.coerceIn(0, 3600); mq.feedback = fb
            touch(d)
        }
        return MockAnswer(fb, if (fb == null) "The AI's scores weren't usable this time; your answer is saved. Try scoring again." else null)
    }

    suspend fun mockFinish(sid: String): StudyData.MockSession = edit { d ->
        val s = mock(d, sid)
        s.summary = StudyCore.mockSummary(s.questions.map { it.feedback }); s.finished = true
        s
    }

    suspend fun mockList(): List<MockListItem> = read { d ->
        d.mocks.asReversed().map { m -> MockListItem(m.id, m.created, m.job, m.questions.size, m.questions.count { it.answer != null }, m.summary) }
    }

    suspend fun mockGet(sid: String): StudyData.MockSession = read { d -> mock(d, sid) }

    suspend fun mockDelete(sid: String): Boolean = edit { d -> d.mocks.removeAll { it.id == sid } }

    // ------------------------------------------------------------------ 7.2.0-dev.2: reminder, widget and lesson packs (recorded data only)
    /** What is due today and the next lesson of the most recently active course with one left. */
    class Snapshot(val streak: Int, val cardsDue: Int, val questionsDue: Int, val courses: Int, val nextLesson: String?, val nextCourse: String?)

    suspend fun snapshot(): Snapshot = read { d ->
        val t = todayIso()
        val cards = d.cards.values.count { !it.done && (it.due ?: "") <= t }
        val questions = dueReviews(d, t).size
        val next = d.courses.values.sortedByDescending { c -> maxOf(c.created, c.history.lastOrNull()?.at ?: 0L) }.firstNotNullOfOrNull { c ->
            runCatching { val mods = modulesOf(c); StudyCore.nextLesson(mods, c.done, prefsOf(c).optional)?.let { id -> c to findLesson(c, id).second.title } }.getOrNull()
        }
        Snapshot(StudyCore.streak(d.activity, today()), cards, questions, d.courses.size, next?.second, next?.first?.title)
    }

    /** The courses an offline lesson pack may write for: preferences chosen, with their syllabus, done and already-written lessons. */
    suspend fun packCourses(): List<StudyExtras.PackCourse> = read { d ->
        d.courses.values.mapNotNull { c ->
            runCatching {
                StudyExtras.PackCourse(c.id, c.prefs != null, modulesOf(c), c.done.toSet(), prefsOf(c).optional.toSet(),
                    d.lessons.keys.filter { it.startsWith("${c.id}/") }.map { it.substringAfter('/') }.toSet())
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ Today / overview
    suspend fun state(): State = read { d ->
        val t = tracks(); val td = today(); val tds = td.toString()
        val perSkill = lessonProgress(d)
        val plan = d.plan
        val target = plan?.gaps?.map { it.skill } ?: d.mcq.keys.sorted().take(12)
        val todayItems = plan?.days?.withIndex()?.lastOrNull { it.value.date == tds }?.let { (i, day) ->
            day.items.mapIndexed { j, it -> TodayItem(it, "${i + 1}:$j", "${i + 1}:$j" in plan.done) } } ?: emptyList()
        val courses = d.courses.values.map { c -> CourseSummary(c.id, c.title, c.track, c.done.size, runCatching { modulesOf(c).sumOf { it.lessons.size } }.getOrDefault(0)) }
        val comp = LinkedHashMap<String, Int>().apply { t.competencies.forEach { put(it.id, 0) } }
        for (s in d.stories) for (k in s.competencies) comp[k] = (comp[k] ?: 0) + 1
        State(courses, plan, StudyCore.readiness(target, d.mcq, perSkill), todayItems, StudyCore.streak(d.activity, td), dueReviews(d, tds).size, d.stories.size, comp)
    }

    companion object {
        val TARGETS = listOf("Senior AI/ML Engineer", "LLM / GenAI Engineer", "AI Platform / MLOps Engineer", "AI Technical Lead / Architect",
            "Software Engineer", "Data Scientist / Analyst", "Cloud Engineer", "General knowledge / personal learning")
        val LEVELS = linkedMapOf("beginner" to "Beginner: build strong fundamentals", "intermediate" to "Intermediate: strengthen and advance",
            "advanced" to "Advanced: production and architecture")
        val STAR = listOf("situation", "task", "action", "result")
        const val MAX_TIME_REPORT = 600L
        const val MAX_MOCKS = 20
        private val LOCKS = HashMap<String, Mutex>()
        fun lockFor(f: File): Mutex = synchronized(LOCKS) { LOCKS.getOrPut(f.absolutePath) { Mutex() } }

        val QUALITY_PROBES = linkedMapOf(
            "json_mcq" to ("Write 1 multiple-choice question about Python lists. Answer ONLY with JSON: {\"questions\": [{\"q\": question, \"options\": [4 strings], " +
                "\"answer\": index 0-3, \"explanation\": why}]}."),
            "lesson_sections" to "Write a very short lesson on HTTP caching with exactly these Markdown headings: ## In simple words, ## Going deeper, ## Common mistakes, ## Interview questions.",
            "fact" to "Answer with only the number: what is 17 multiplied by 23?")
        val ADVICE = mapOf("good" to "This model handles Study well.",
            "weak" to "This model sometimes misses the format Study needs. Lessons and questions may need a retry; a larger model gives better results.",
            "poor" to "This model struggles with Study's lessons and questions. Choose a larger model in AI Models for better results.")
        private val DIGITS = Regex("\\d+")
        private val ANY_OBJECT = Regex("\\{[\\s\\S]*\\}")

        /** Whether one quality probe's answer has the shape Study needs (same checks as the desktop's _probe_ok). */
        fun probeOk(name: String, text: String): Boolean = when (name) {
            "json_mcq" -> {
                fun obj(t: String): JSONObject? = runCatching { JSONObject(t.trim()) }.getOrNull()
                val o = obj(text) ?: ANY_OBJECT.find(text)?.let { obj(it.value) }
                val q = o?.optJSONArray("questions")?.optJSONObject(0)
                val opts = q?.optJSONArray("options")
                val ans = q?.opt("answer")
                q != null && opts != null && opts.length() == 4 && (ans is Int || ans is Long) && (ans as Number).toInt() in 0..3 && q.optString("q").isNotBlank()
            }
            "lesson_sections" -> {
                val heads = HEAD.findAll(normalizeLesson(text)).joinToString(" ") { it.groupValues[1].lowercase() }
                listOf("in simple words", "going deeper", "common mistakes", "interview questions").count { it in heads } >= 3
            }
            else -> DIGITS.findAll(text).any { it.value == "391" }
        }

        private val BULLET = Regex("^\\s*(?:[-*•●▪◦]|\\d+[.)])\\s+(.+)$")
        private val SENTENCE_END = Regex("(?<=[.;])\\s+")
        /** Bullet lines of a resume; when there are none, sentences of the longer lines (15-400 characters, at most 60). */
        fun resumeBullets(text: String?): List<String> {
            val lines = (text ?: "").lines().map { it.trimEnd() }
            var bullets = lines.mapNotNull { l -> BULLET.find(l)?.groupValues?.get(1)?.trim() }
            if (bullets.isEmpty()) bullets = lines.filter { it.trim().length > 40 }.flatMap { it.trim().split(SENTENCE_END) }.map { it.trim() }.filter { it.length > 25 }
            return bullets.filter { it.length >= 15 }.map { it.take(400) }.take(60)
        }

        /** The bullets that matter most for the job: most job skills named, then original order. */
        fun rankBullets(bullets: List<String>, jdSkills: List<String>, tax: List<StudyCore.Term>, n: Int = 8): List<Int> {
            val want = jdSkills.toSet()
            return bullets.indices.sortedWith(compareBy<Int> { -StudyCore.findSkills(bullets[it], tax).toSet().intersect(want).size }.thenBy { it }).take(n)
        }
        const val EXPORT_FORMAT = "abhify-study-export-1"
        const val JSON_TOKENS = 900
        const val LESSON_TOKENS = 2200
        val SECTIONS = listOf("in simple words", "going deeper", "advanced", "real-world example", "code example", "common mistakes",
            "practice exercise", "interview questions", "follow-up questions")
        private val SETEXT = Regex("^(?:\\*\\*)?([^\\n#*][^\\n]{1,80}?)(?:\\*\\*)?\\s*\\n[-=]{3,}\\s*$", RegexOption.MULTILINE)
        private val BOLD_HEAD = Regex("^\\s*\\*\\*([^*\\n]{3,80})\\*\\*:?\\s*$", RegexOption.MULTILINE)
        private val NUM_HEAD = Regex("^\\s*#{0,4}\\s*\\d+[.)]\\s+(?=(?:" + SECTIONS.joinToString("|") + "))", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        private val HEAD = Regex("^#{1,4}\\s+(.+)$", RegexOption.MULTILINE)

        /** Small models write headings in other styles ("Title" underlined, **Title**, "1. Title"): make them "## Title". */
        fun normalizeLesson(text: String): String {
            var t = text.trim()
            t = SETEXT.replace(t) { "## " + it.groupValues[1].trim() }
            t = BOLD_HEAD.replace(t) { "## " + it.groupValues[1].trim() }
            t = NUM_HEAD.replace(t, "## ")
            return t
        }

        /** A real lesson: at least 400 characters and at least 4 of the asked-for sections as headings. */
        fun lessonOk(text: String): Boolean {
            val heads = HEAD.findAll(text).joinToString(" ") { it.groupValues[1].lowercase() }
            return text.trim().length >= 400 && SECTIONS.count { it in heads } >= 4
        }
        private val LANG = mapOf("cloud" to "Terraform, Bash or Python", "ds" to "Python (pandas) or SQL", "behav" to "no")
        private val DEPTH = mapOf("beginner" to "Keep 'Advanced' short; spend most words on the simple explanation and the example.",
            "intermediate" to "Balance all sections.",
            "advanced" to "Keep 'In simple words' to 2-3 sentences; spend most words on production trade-offs, scale and architecture.")

        private val NOT_WORD = Regex("[^a-z0-9 ]+")
        private val SPACES = Regex("\\s+")
        fun normQ(s: String) = SPACES.replace(NOT_WORD.replace(s.lowercase(), " "), " ").trim()
        /** The same 32-bit hash as the Web app's qKey (h = h * 31 + code, unsigned). */
        fun qKey(s: String): String { var h = 0; for (ch in normQ(s)) h = h * 31 + ch.code; return "w" + h.toUInt().toString(16) }

        private val FENCE = Regex("^```[a-zA-Z]*\\s*|\\s*```$")
        private val OBJECT = Regex("\\{[\\s\\S]*\\}")
        fun parseJson(text: String): Any? {
            val t = FENCE.replace(text.trim(), "").trim()
            fun tryParse(s: String): Any? = runCatching { JSONTokener(s).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }
            return tryParse(t) ?: OBJECT.find(t)?.let { tryParse(it.value) }
        }

        private val KEYS = setOf("summary", "objectives", "prerequisites", "optional", "focus", "completion")
        private val SPLIT = Regex(";\\s+|\\n+")
        private fun list(o: JSONObject?, k: String, n: Int, len: Int = 200): List<String> {
            if (o == null || o.isNull(k)) return emptyList()
            val raw = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.opt(it)?.toString() } }
                ?: listOf(o.opt(k).toString())
            return raw.flatMap { it.split(SPLIT) }.map { it.trim(' ', '.') }.filter { it.isNotEmpty() && it.lowercase() !in KEYS }.map { it.take(len) }.take(n)
        }
        private fun text(o: JSONObject?, k: String) = if (o == null || o.isNull(k)) "" else o.opt(k)?.toString()?.trim().orEmpty()

        /** Custom course outline: at least 3 modules, each with at least 2 lessons (up to 8 modules, 6 lessons). */
        fun validateOutline(o: Any, topic: String, tax: List<StudyCore.Term>): Pair<String, List<Module>> {
            val obj = o as? JSONObject
            val arr = obj?.optJSONArray("modules") ?: throw StudyError("The AI didn't return a course outline. Try again.")
            val mods = ArrayList<Module>()
            for (i in 0 until minOf(8, arr.length())) {
                val m = arr.optJSONObject(i) ?: continue
                val title = text(m, "title"); if (title.isEmpty()) continue
                val lessons = list(m, "lessons", 6, 120)
                if (lessons.size < 2) continue
                val mid = "m${i + 1}"
                mods += Module(mid, title.take(120), StudyCore.findSkills("$topic $title", tax).take(4),
                    lessons.mapIndexed { j, t -> StudyCore.Lesson("$mid-${j + 1}", t) })
            }
            if (mods.size < 3) throw StudyError("The AI's course outline was too short. Try again, or choose one of the ready-made courses.")
            return text(obj, "title").ifEmpty { topic }.take(120) to mods
        }

        /** Course overview: built from the real syllabus only; 'optional' and 'focus' may name only real module ids. */
        fun validateOverview(o: Any, mods: List<Module>, at: Long): Overview {
            val obj = o as? JSONObject
            val summary = text(obj, "summary")
            if (obj == null || summary.isEmpty()) throw StudyError("The AI's course overview was incomplete. Try again.")
            val ids = mods.map { it.id }.toSet()
            val focus = LinkedHashMap<String, String>()
            obj.optJSONObject("focus")?.let { f -> f.keys().forEach { k -> val v = text(f, k); if (k in ids && v.isNotEmpty()) focus[k] = v.take(200) } }
            val completion = list(obj, "completion", 6).ifEmpty { listOf("Finish every core lesson", "Score 70% or more in each module's MCQs", "Complete the capstone project") }
            val optional = (obj.optJSONArray("optional")?.let { a -> (0 until a.length()).map { a.opt(it).toString() } } ?: emptyList()).filter { it in ids }.distinct().take(maxOf(0, ids.size - 2))
            return Overview(summary.take(800), list(obj, "objectives", 8), list(obj, "prerequisites", 8), completion, optional, focus, at)
        }

        /** Flashcards: front at least 5 characters and a back; at least 3 usable. */
        fun validateCards(o: Any): List<Pair<String, String>> {
            val arr = (o as? JSONObject)?.optJSONArray("cards") ?: (o as? JSONArray) ?: JSONArray()
            val cards = (0 until minOf(10, arr.length())).mapNotNull { arr.optJSONObject(it) }
                .filter { text(it, "front").length >= 5 && text(it, "back").isNotEmpty() }
                .map { text(it, "front").take(300) to text(it, "back").take(600) }
            if (cards.size < 3) throw StudyError("The AI made too few usable flashcards. Try again.")
            return cards
        }

        fun validateCapstone(o: Any): Capstone {
            val obj = o as? JSONObject
            val ms = list(obj, "milestones", 6)
            if (obj == null || text(obj, "title").isEmpty() || ms.size < 3) throw StudyError("The AI's capstone project was incomplete. Try again.")
            return Capstone(text(obj, "title").take(120), text(obj, "brief").take(800), ms.map { Milestone(it, false) }, list(obj, "stretch", 3))
        }

        private val NUM = Regex("\\d+(?:[.,]\\d+)?")
        /** The tighter version may only reuse the story's facts: if it has a number the story doesn't, it is dropped. */
        fun guardFacts(fb: Coach, story: String): Coach {
            val have = NUM.findAll(story).map { it.value }.toSet()
            if (fb.improved.isEmpty() || NUM.findAll(fb.improved).all { it.value in have }) return fb
            return Coach(fb.scores, fb.missing, fb.tips + "The tighter version was left out because it added numbers that aren't in your story.", "", fb.at)
        }

        /** STAR coaching: every score must be a number from 1 to 5. */
        fun validateCoach(o: Any, at: Long): Coach {
            val obj = o as? JSONObject
            val sc = obj?.optJSONObject("scores")
            val scores = LinkedHashMap<String, Int>()
            for (f in STAR) {
                val v = sc?.opt(f) as? Number
                if (v == null || v.toDouble() < 1 || v.toDouble() > 5) throw StudyError("The AI's feedback was incomplete. Try again.")
                scores[f] = Math.round(v.toDouble()).toInt()
            }
            return Coach(scores, list(obj, "missing", 5, 300), list(obj, "tips", 5, 300), text(obj, "improved").take(3000), at)
        }

        /** MCQ batch: 4 different non-empty options, answer 0-3, an explanation, a real question; no repeats (seen or in the batch). */
        fun validateMcq(o: Any, seen: Set<String>, count: Int, topic: String, difficulty: String, skill: String, m: Module, cid: String,
                        random: Random = Random.Default): McqBatch {
            val arr = (o as? JSONObject)?.optJSONArray("questions") ?: (o as? JSONArray) ?: JSONArray()
            val out = ArrayList<Question>(); var dropped = 0
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i)
                if (p == null) { dropped++; continue }
                val q = text(p, "q").ifEmpty { text(p, "question") }
                val opts = p.optJSONArray("options")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) "" else a.opt(it).toString().trim() } } ?: emptyList()
                val ans = when (val a = p.opt("answer")) { is Number -> a.toDouble(); is String -> a.trim().toDoubleOrNull(); else -> null }
                val why = text(p, "explanation")
                val ok = q.length >= 10 && opts.size == 4 && opts.map(::normQ).toSet().size == 4 && opts.none { it.isEmpty() } &&
                    ans != null && ans == Math.floor(ans) && ans in 0.0..3.0 && why.isNotEmpty()
                if (!ok || normQ(q) in seen || out.any { normQ(it.q) == normQ(q) }) { dropped++; continue }
                val correct = opts[ans!!.toInt()]
                val shuffled = opts.shuffled(random)
                out += Question(qKey(q), q, shuffled, shuffled.indexOf(correct), why, topic, difficulty, skill, m.title, m.id, cid)
                if (out.size >= count) break
            }
            return McqBatch(out, m.title, m.id, dropped)
        }
    }
}
