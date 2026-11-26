package ai.abhify.android.ui

import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.chat.docText
import ai.abhify.android.learn.LessonPackWorker
import ai.abhify.android.learn.StudyAi
import ai.abhify.android.learn.StudyAndroid
import ai.abhify.android.learn.StudyExtras
import ai.abhify.android.learn.StudyReminderWorker
import ai.abhify.android.learn.StudyCore
import ai.abhify.android.learn.StudyData
import ai.abhify.android.learn.NoNewQuestions
import ai.abhify.android.learn.StudyError
import ai.abhify.android.learn.StudyService
import ai.abhify.android.llm.LlmEngine
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 7.2 Study state: Today, Courses (one learning journey per course), Interview plan and STAR stories, like the desktop's
 * Study tab. Records live in filesDir/study.json (phone only: not backed up, not synced). The AI is the model on this
 * phone (never online, never downloaded by Study); without one, Study says so and points to AI Models.
 * Every AI call runs off the main thread and is cancelled when its screen is left.
 */
class StudyState(private val vm: AppViewModel) {
    private val g = vm.g

    enum class Section(val label: String, val glyph: String) { TODAY("Today", "☀"), COURSES("Courses", "📘"), PLAN("Interview plan", "🗓"), STORIES("STAR stories", "⭐") }
    enum class Screen { HOME, PREFS, COURSE, LESSON, REVISION, STORY, MOCK, TAILOR }
    enum class CourseTab(val label: String) { ROADMAP("Roadmap"), PRACTICE("Practice"), CARDS("Flashcards"), CAPSTONE("Capstone"), PROGRESS("Progress") }

    /** One practice question on screen; [picked] once answered. */
    class PracticeQ(val q: StudyData.Question, val picked: Int? = null)
    /** One revision step: a flashcard or a missed question. */
    sealed class RevItem { class CardItem(val id: String, val card: StudyData.Card) : RevItem(); class Missed(val due: StudyService.DueReview) : RevItem() }
    class TutorTurn(val question: String, val answer: String? = null, val error: String? = null)

    private val deviceAi = StudyAi { prompt, json, maxTokens ->
        if (vm.generating.value || vm.learnBusy.value) throw StudyError("The AI is busy with another answer. Try again when it has finished.")
        if (g.store.active() == null) throw StudyError(NO_MODEL)
        vm.learnBusy.value = true
        try {
            if (!vm.readyDevice()) throw StudyError((vm.engine.value as? AppViewModel.Engine.Error)?.message ?: "The AI on this phone couldn't start.")
            val ctx = g.store.active()?.context ?: 2048
            val budget = maxOf(256, ctx - prompt.length / 3 - 96)                 // the answer must fit the model's context
            val sb = StringBuilder(); var err: String? = null
            g.engine.generate(listOf(PromptBuilder.Msg("system", SYSTEM), PromptBuilder.Msg("user", prompt)), minOf(maxTokens, budget),
                if (json) 0.4f else 0.6f, seed = (System.nanoTime() % 100_000).toInt()).collect { e ->
                when (e) { is LlmEngine.Event.Token -> sb.append(e.text); is LlmEngine.Event.Failed -> err = e.message; is LlmEngine.Event.Finished -> {} }
            }
            err?.let { throw StudyError("The AI on this phone couldn't answer: $it") }
            sb.toString()
        } finally { vm.learnBusy.value = false }
    }

    /** filesDir/study.json, shared (one lock) with the reminder, the lesson-pack worker and the widget, which refreshes after each save. */
    val service = StudyAndroid.service(g.context, deviceAi, StudyAndroid.brainSource(g))

    val section = mutableStateOf(Section.TODAY)
    val screen = mutableStateOf(Screen.HOME)
    val courseTab = mutableStateOf(CourseTab.ROADMAP)
    val loading = mutableStateOf(false)
    val busy = mutableStateOf<String?>(null)                    // an AI task running for the current screen (its label)
    val error = mutableStateOf<String?>(null)                   // why the current screen couldn't load / the last action failed
    val message = mutableStateOf<String?>(null)                 // a short confirmation
    val noModel get() = error.value == NO_MODEL

    val st = mutableStateOf<StudyService.State?>(null)
    val tracks = mutableStateOf<StudyService.Tracks?>(null)
    val cid = mutableStateOf<String?>(null)
    val dash = mutableStateOf<StudyService.Dashboard?>(null)
    val detail = mutableStateOf<StudyService.CourseDetail?>(null)
    val lessonId = mutableStateOf<String?>(null)
    val lesson = mutableStateOf<StudyService.LessonResult?>(null)
    val tutor = mutableStateListOf<TutorTurn>()
    val cardsMade = mutableStateOf(false)
    val cardsDue = mutableIntStateOf(0)

    // practice (unlimited: the next batch loads when the end of the list is reached)
    val practice = mutableStateListOf<PracticeQ>()
    val practiceModule = mutableStateOf<String?>(null)
    val practiceWeak = mutableStateOf(false)
    val practiceBusy = mutableStateOf(false)
    val practiceError = mutableStateOf<String?>(null)
    val practiceRight = mutableIntStateOf(0)
    val practiceAnswered = mutableIntStateOf(0)
    private var practiceRound = 0
    private var practiceJob: Job? = null

    // revision
    val revItems = mutableStateListOf<RevItem>()
    val revIndex = mutableIntStateOf(0)
    val revRight = mutableIntStateOf(0)
    val revWeekly = mutableStateOf(false)
    val revCourse = mutableStateOf<String?>(null)

    // plan and stories
    val stories = mutableStateListOf<StudyData.Story>()
    val editing = mutableStateOf<StudyData.Story?>(null)        // null + Screen.STORY = a new story
    val coach = mutableStateOf<StudyData.Coach?>(null)
    val planForm = mutableStateOf(false)
    val offerCustom = mutableStateOf<String?>(null)            // a skill no ready-made course covers: offer a custom course

    private var job: Job? = null

    private fun fail(e: Throwable) = (e as? StudyError)?.message ?: e.message ?: e.toString()

    /** Runs [block] off the main thread for the current screen; a newer task (or leaving) cancels it. */
    private fun task(label: String?, block: suspend () -> Unit) {
        job?.cancel()
        busy.value = label; error.value = null
        job = vm.viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = fail(e) }
            finally { if (busy.value == label) busy.value = null }
        }
    }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    fun cancel() { job?.cancel(); job = null; busy.value = null; stopPractice() }

    // ------------------------------------------------------------------ navigation
    fun open(s: Section) { cancel(); section.value = s; screen.value = Screen.HOME; error.value = null; refresh() }
    fun refresh() = task(null) {
        loading.value = true
        try {
            tracks.value = io { service.tracks() }
            st.value = io { service.state() }
            cardsDue.intValue = io { service.dueCards().size }
            quality.value = io { service.qualityStatus(modelId()) }
            if (section.value == Section.STORIES) { stories.clear(); stories.addAll(io { service.stories() }) }
        } finally { loading.value = false }
    }
    /** System Back inside Study: lesson → course → list. Returns false when there's nothing to go back to. */
    fun back(): Boolean {
        when (screen.value) {
            Screen.LESSON -> { cancel(); openCourse(cid.value ?: return false) }
            Screen.COURSE, Screen.PREFS -> { cancel(); screen.value = Screen.HOME; section.value = Section.COURSES; refresh() }
            Screen.REVISION -> { cancel(); if (revCourse.value != null) openCourse(revCourse.value!!, CourseTab.CARDS) else { screen.value = Screen.HOME; refresh() } }
            Screen.STORY -> { cancel(); screen.value = Screen.HOME; refresh() }
            Screen.MOCK -> { cancel(); if (mock.value != null) { mock.value = null; loadMocks() } else { screen.value = Screen.HOME; refresh() } }
            Screen.TAILOR -> { cancel(); screen.value = Screen.HOME; tailored.value = null; refresh() }
            Screen.HOME -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ courses
    fun addTrack(trackId: String) = task(null) { val c = io { service.createCourse(trackId) }; openCourse(c.id) }
    fun customCourse(topic: String) = task("Writing a course outline for \"${topic.trim().take(60)}\"…") {
        val c = io { service.customCourse(topic) }; openCourse(c.id)
    }
    fun deleteCourse(id: String) = task(null) { io { service.deleteCourse(id) }; message.value = "Course deleted"; screen.value = Screen.HOME; refresh() }

    fun openCourse(id: String, tab: CourseTab? = null) {
        cancel()
        if (cid.value != id) { practice.clear(); practiceModule.value = null; practiceWeak.value = false; lesson.value = null }
        cid.value = id; tab?.let { courseTab.value = it }
        task(null) {
            loading.value = true
            try {
                detail.value = io { service.courseDetail(id) }; dash.value = io { service.dashboard(id) }
                screen.value = if (dash.value?.prefs?.chosen == true) Screen.COURSE else Screen.PREFS
                if (screen.value == Screen.COURSE && courseTab.value == CourseTab.PRACTICE && practice.isEmpty()) startPractice(practiceModule.value, practiceWeak.value)
            } finally { loading.value = false }
        }
    }
    private suspend fun reloadCourse() { val id = cid.value ?: return; detail.value = io { service.courseDetail(id) }; dash.value = io { service.dashboard(id) } }
    fun editPrefs() { cancel(); screen.value = Screen.PREFS }
    fun cancelPrefs() { screen.value = Screen.COURSE }

    fun savePrefs(target: String, level: String, minutes: Int) {
        val id = cid.value ?: return
        if (target.isBlank() || level.isBlank()) { error.value = "Choose a goal and a level (or type your own)."; return }
        task("Building your learning plan from this course's syllabus…") {
            io { service.setPrefs(id, target, level, minutes) }
            try { io { service.buildOverview(id) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Your choices are saved. The overview couldn't be written now (${fail(e)}); try \"Build overview\" later." }
            reloadCourse(); screen.value = Screen.COURSE; courseTab.value = CourseTab.ROADMAP
        }
    }
    fun buildOverview() { val id = cid.value ?: return; task("Building the course overview…") { io { service.buildOverview(id) }; reloadCourse() } }

    fun pickTab(t: CourseTab) {
        if (t != CourseTab.PRACTICE) stopPractice()
        courseTab.value = t
        when (t) {
            CourseTab.PRACTICE -> if (practice.isEmpty()) startPractice(null, false)
            CourseTab.CARDS -> openRevision(weekly = false, course = cid.value)
            else -> {}
        }
    }

    fun makeCapstone() { val id = cid.value ?: return; task("Designing a capstone project for your goal…") { io { service.makeCapstone(id) }; reloadCourse() } }
    fun capstoneStep(i: Int, done: Boolean) {
        val id = cid.value ?: return
        vm.viewModelScope.launch { runCatching { io { service.capstoneStep(id, i, done) }; reloadCourse() }.onFailure { error.value = fail(it) } }
    }

    // ------------------------------------------------------------------ lessons
    fun openLesson(lid: String, refresh: Boolean = false) {
        val id = cid.value ?: return
        cancel(); lessonId.value = lid; screen.value = Screen.LESSON; tutor.clear(); cardsMade.value = false
        if (!refresh) lesson.value = null
        val ready = detail.value?.modules?.flatMap { it.lessons }?.firstOrNull { it.id == lid }?.ready == true
        task(if (ready && !refresh) "Opening the lesson…" else "Writing your lesson for your goal and level… on this phone this can take a few minutes.") {
            lesson.value = io { service.lesson(id, lid, refresh) }
            detail.value = io { service.courseDetail(id) }
        }
    }
    fun toggleLessonDone() {
        val id = cid.value ?: return; val lid = lessonId.value ?: return
        val done = detail.value?.course?.done?.contains(lid) != true
        vm.viewModelScope.launch { runCatching { io { service.setDone(id, lid, done) }; reloadCourse() }.onFailure { error.value = fail(it) }
            .onSuccess { if (done) message.value = "Lesson done. Nice work!" } }
    }
    fun toggleExercise() {
        val id = cid.value ?: return; val lid = lessonId.value ?: return
        val done = detail.value?.course?.exercises?.contains(lid) != true
        vm.viewModelScope.launch { runCatching { io { service.setExercise(id, lid, done) }; reloadCourse() }.onFailure { error.value = fail(it) } }
    }
    fun makeCards() {
        val id = cid.value ?: return; val lid = lessonId.value ?: return
        task("Making flashcards…") { val n = io { service.makeCards(id, lid) }.size; cardsMade.value = true; message.value = "$n flashcards added to today's revision" }
    }
    fun askTutor(question: String) {
        val id = cid.value ?: return; val lid = lessonId.value ?: return
        val q = question.trim(); if (q.isEmpty()) return
        tutor.add(0, TutorTurn(q))
        task("Thinking…") {
            val r = runCatching { io { service.tutor(id, lid, q) } }
            r.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val i = tutor.indexOfFirst { it.question == q && it.answer == null && it.error == null }
            if (i >= 0) tutor[i] = TutorTurn(q, r.getOrNull(), r.exceptionOrNull()?.let(::fail))
        }
    }
    fun nextLessonAfter(lid: String): String? {
        val all = detail.value?.modules?.flatMap { it.lessons } ?: return null
        return all.getOrNull(all.indexOfFirst { it.id == lid } + 1)?.id
    }

    /** Real study time: the screen reports seconds while a lesson or practice is visible (at most 600 s per report). */
    fun reportTime(courseId: String, seconds: Long) {
        if (seconds <= 0) return
        vm.viewModelScope.launch(NonCancellable) { runCatching { io { service.addTime(courseId, minOf(StudyService.MAX_TIME_REPORT, seconds)) } } }
    }

    // ------------------------------------------------------------------ practice
    fun startPractice(moduleId: String?, weak: Boolean) {
        stopPractice()
        practice.clear(); practiceRound = 0; emptyRuns = 0; practiceRight.intValue = 0; practiceAnswered.intValue = 0
        practiceModule.value = moduleId; practiceWeak.value = weak; practiceError.value = null
        morePractice()
    }
    fun practiseModule(moduleId: String?, weak: Boolean = false) {
        val id = cid.value ?: return
        cancel(); courseTab.value = CourseTab.PRACTICE
        if (screen.value == Screen.COURSE) { startPractice(moduleId, weak); return }
        practice.clear(); practiceModule.value = moduleId; practiceWeak.value = weak; practiceError.value = null
        openCourse(id, CourseTab.PRACTICE)                       // starts this practice once the course is open
    }
    fun stopPractice() { practiceJob?.cancel(); practiceJob = null; practiceBusy.value = false }

    /**
     * The next batch of 5 (one request at a time; after an error only Retry asks again). A batch with no new question
     * is retried once automatically; after 2 such batches in a row auto-loading stops with [NO_NEW].
     */
    fun morePractice(force: Boolean = false) {
        val id = cid.value ?: return
        if (practiceBusy.value || (practiceError.value != null && !force)) return
        if (force) emptyRuns = 0
        practiceBusy.value = true; practiceError.value = null
        val round = practiceRound
        practiceJob = vm.viewModelScope.launch {
            var again = false
            try {
                val b = io { service.courseMcq(id, round, practice.map { it.q.q }.takeLast(40), practiceModule.value, practiceWeak.value) }
                practiceRound = round + 1; emptyRuns = 0
                practice.addAll(b.questions.map { PracticeQ(it) })
            } catch (e: CancellationException) { throw e }
            catch (e: NoNewQuestions) {
                practiceRound = round + 1; emptyRuns++
                if (emptyRuns >= 2) practiceError.value = NO_NEW else again = true
            }
            catch (e: Exception) { practiceError.value = fail(e) }
            finally { practiceBusy.value = false }
            if (again) morePractice()
        }
    }
    private var emptyRuns = 0
    fun pick(index: Int, k: Int) {
        val p = practice.getOrNull(index) ?: return
        if (p.picked != null) return
        practice[index] = PracticeQ(p.q, k)
        val ok = k == p.q.answer
        practiceAnswered.intValue++; if (ok) practiceRight.intValue++
        vm.viewModelScope.launch(NonCancellable) {
            runCatching { io { service.answer(p.q, ok, p.q.skill.ifEmpty { detail.value?.course?.title ?: "General" }, p.q.courseId ?: cid.value) } }
                .onFailure { message.value = "Your answer couldn't be saved." }
        }
    }

    // ------------------------------------------------------------------ revision (flashcards + missed questions)
    fun openRevision(weekly: Boolean, course: String? = null) {
        cancel()
        revWeekly.value = weekly; revCourse.value = course; revIndex.intValue = 0; revRight.intValue = 0; revItems.clear()
        if (course == null) screen.value = Screen.REVISION
        task(null) {
            loading.value = true
            try {
                val cards = io { service.dueCards(course, weekly) }
                val due = if (!weekly && course == null) io { service.dueReviews() } else emptyList()
                revItems.addAll(cards.map { RevItem.CardItem(it.first, it.second) } + due.map { RevItem.Missed(it) })
            } finally { loading.value = false }
        }
    }
    fun gradeCard(id: String, remembered: Boolean) {
        vm.viewModelScope.launch {
            runCatching { io { service.gradeCard(id, remembered) } }.onFailure { error.value = fail(it); return@launch }
            if (remembered) revRight.intValue++
            revIndex.intValue++
        }
    }
    fun answerMissed(r: StudyService.DueReview, k: Int) {
        val ok = k == r.review.question.answer
        if (ok) revRight.intValue++
        vm.viewModelScope.launch(NonCancellable) { runCatching { io { service.answer(r.review.question, ok, r.review.skill.ifEmpty { "General" }, r.review.course) } } }
    }
    fun nextRevision() { revIndex.intValue++ }

    // ------------------------------------------------------------------ interview plan
    fun makePlan(jd: String, resume: String, date: String?, minutes: Int) = task("Making your plan…") {
        io { service.makePlan(jd, resume, date, minutes) }; planForm.value = false; st.value = io { service.state() }
    }
    fun planItem(key: String, done: Boolean) {
        val (d, i) = key.split(':').map { it.toInt() }
        vm.viewModelScope.launch { runCatching { io { service.planItem(d, i, done) } }.onFailure { error.value = fail(it) }; st.value = io { service.state() } }
    }
    fun deletePlan() = task(null) { io { service.deletePlan() }; st.value = io { service.state() } }
    /** The text of the resume chosen in Career (to compare with the job), read on this phone. */
    fun resumeText(onText: (String) -> Unit) {
        val id = vm.career.active()?.id ?: run { message.value = "No resume yet: add one in Career → Resumes, or paste your skills."; return }
        vm.viewModelScope.launch { onText(io { runCatching { g.db.docText(id) }.getOrNull().orEmpty() }) }
    }

    /**
     * From the plan or weak areas: open the ready-made course that teaches [skill] (its module's first lesson not done,
     * or practice on that module). Without one, offer a custom course.
     */
    fun studySkill(skill: String, practise: Boolean = false): Boolean {
        val t = tracks.value ?: runCatching { service.tracks() }.getOrNull() ?: return false
        for (tr in t.tracks) {
            val mod = tr.modules.firstOrNull { skill in it.skills } ?: continue
            cancel(); section.value = Section.COURSES
            task(null) {
                val c = io { service.createCourse(tr.id) }
                cid.value = c.id
                detail.value = io { service.courseDetail(c.id) }; dash.value = io { service.dashboard(c.id) }
                if (dash.value?.prefs?.chosen != true) { screen.value = Screen.PREFS; message.value = "Choose your goal and level first; then the course opens."; return@task }
                screen.value = Screen.COURSE
                if (practise) { courseTab.value = CourseTab.PRACTICE; startPractice(mod.id, false); return@task }
                val m = detail.value?.modules?.firstOrNull { it.module.id == mod.id } ?: return@task
                openLesson((m.lessons.firstOrNull { !it.done } ?: m.lessons.first()).id)
            }
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ export / import (a file the user picks)
    fun exportTo(uri: android.net.Uri) = task(null) {
        io { val json = service.exportJson(); g.context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: throw StudyError("Couldn't write the file.") }
        message.value = "Study data exported"
    }
    fun importFrom(uri: android.net.Uri) = task("Importing…") {
        val text = io { g.context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw StudyError("Couldn't read the file.") }
        val r = io { service.importJson(text) }
        message.value = "Imported ${r.courses} course${if (r.courses == 1) "" else "s"}, ${r.stories} stor${if (r.stories == 1) "y" else "ies"} and ${r.cards} flashcard${if (r.cards == 1) "" else "s"}."
        screen.value = Screen.HOME; refresh()
    }

    // ------------------------------------------------------------------ STAR stories
    fun newStory() { cancel(); editing.value = null; coach.value = null; screen.value = Screen.STORY }
    fun editStory(s: StudyData.Story) { cancel(); editing.value = s; coach.value = s.coach; screen.value = Screen.STORY }
    fun saveStory(input: StudyService.StoryInput, getCoaching: Boolean) = task(if (getCoaching) "Your coach is reading the story…" else null) {
        val s = io { service.saveStory(input) }
        editing.value = s
        if (!getCoaching) { message.value = "Story saved"; screen.value = Screen.HOME; refresh(); return@task }
        coach.value = io { service.coach(s.id) }
        editing.value = io { service.stories() }.firstOrNull { it.id == s.id }
    }
    fun deleteStory(id: String) = task(null) { io { service.deleteStory(id) }; screen.value = Screen.HOME; refresh() }

    // ------------------------------------------------------------------ 7.2.0-dev.2: Personal AI Brain opt-in (per course, off by default)
    val brainSources = mutableStateOf<StudyService.BrainSources?>(null)
    fun loadBrainSources() { vm.viewModelScope.launch { brainSources.value = runCatching { io { service.brainSources() } }.getOrNull() } }
    fun saveBrain(enabled: Boolean, memories: List<Long>, projects: List<String>) {
        val id = cid.value ?: return
        task(null) { io { service.setBrain(id, enabled, memories, projects) }; reloadCourse()
            message.value = if (enabled) "Personal context saved for this course. New lessons and tutor answers use only what you ticked." else "Personal context is off for this course." }
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: model quality check
    val quality = mutableStateOf<StudyData.Quality?>(null)
    fun modelId(): String? = g.store.active()?.id
    fun modelName(): String? = modelId()?.let { id -> g.catalog.byId(id)?.name ?: id }
    fun checkQuality() = task("Checking the model on this phone with 3 short tests… this can take a few minutes.") {
        val m = modelId() ?: throw StudyError(NO_MODEL)
        quality.value = io { service.qualityCheck(m) }
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: daily reminder and offline lesson packs (off by default)
    val reminderOn = mutableStateOf(g.prefs.getBoolean(StudyReminderWorker.PREF_ON, false))
    val reminderHour = mutableIntStateOf(g.prefs.getInt(StudyReminderWorker.PREF_HOUR, 19))
    val reminderMinute = mutableIntStateOf(g.prefs.getInt(StudyReminderWorker.PREF_MINUTE, 0))
    fun setReminder(on: Boolean, hour: Int = reminderHour.intValue, minute: Int = reminderMinute.intValue) {
        reminderOn.value = on; reminderHour.intValue = hour; reminderMinute.intValue = minute
        g.prefs.edit().putBoolean(StudyReminderWorker.PREF_ON, on).putInt(StudyReminderWorker.PREF_HOUR, hour).putInt(StudyReminderWorker.PREF_MINUTE, minute).apply()
        StudyReminderWorker.schedule(g.context, on, hour, minute)
    }
    val packsOn = mutableStateOf(g.prefs.getBoolean(LessonPackWorker.PREF_ON, false))
    fun setPacks(on: Boolean) { packsOn.value = on; g.prefs.edit().putBoolean(LessonPackWorker.PREF_ON, on).apply(); LessonPackWorker.schedule(g.context, on) }
    /** When the last pack ran and what it wrote (null before the first run). */
    fun packStatus(): String? {
        val at = g.prefs.getLong(LessonPackWorker.PREF_LAST, 0L).takeIf { it > 0 } ?: return null
        val n = g.prefs.getInt(LessonPackWorker.PREF_COUNT, 0)
        val note = g.prefs.getString(LessonPackWorker.PREF_NOTE, null)
        val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))
        return "Last run $whenText: $n lesson${if (n == 1) "" else "s"} written." + (note?.let { " $it" } ?: "")
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: course progress report (PDF, recorded numbers only)
    fun reportFile(onReady: (File) -> Unit) {
        val d = dash.value ?: return
        task("Preparing the progress report…") {
            val f = io { StudyAndroid.writeReport(g.context, StudyExtras.reportLines(d, System.currentTimeMillis()), "study-report-${d.id.filter { it.isLetterOrDigit() || it == '-' }}.pdf") }
            onReady(f)
        }
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: mock interview
    val mocks = mutableStateListOf<StudyService.MockListItem>()
    val mock = mutableStateOf<StudyData.MockSession?>(null)
    val mockIndex = mutableIntStateOf(0)
    val mockNote = mutableStateOf<String?>(null)
    fun openMock() { cancel(); screen.value = Screen.MOCK; mock.value = null; loadMocks() }
    fun loadMocks() = task(null) { loading.value = true; try { val l = io { service.mockList() }; mocks.clear(); mocks.addAll(l) } finally { loading.value = false } }
    fun startMock(count: Int) = task("Writing your interview questions… on this phone this can take a minute.") {
        val m = io { service.mockStart(count) }; mockNote.value = null; mockIndex.intValue = 0; mock.value = m
    }
    fun openMockSession(id: String) = task(null) {
        val m = io { service.mockGet(id) }; mockNote.value = null
        mockIndex.intValue = m.questions.indexOfFirst { it.answer == null }.let { if (it < 0) m.questions.lastIndex else it }; mock.value = m
    }
    fun answerMock(text: String, seconds: Int) {
        val m = mock.value ?: return; val i = mockIndex.intValue
        task("Scoring your answer…") {
            val r = io { service.mockAnswer(m.id, i, text, seconds) }
            mockNote.value = r.note; mock.value = io { service.mockGet(m.id) }
        }
    }
    fun nextMockQuestion() { mockNote.value = null; mockIndex.intValue++ }
    fun finishMock() { val m = mock.value ?: return; task(null) { mock.value = io { service.mockFinish(m.id) } } }
    fun deleteMock(id: String) = task(null) {
        io { service.mockDelete(id) }; if (mock.value?.id == id) mock.value = null
        val l = io { service.mockList() }; mocks.clear(); mocks.addAll(l); message.value = "Interview deleted"
    }

    // ------------------------------------------------------------------ 7.2.0-dev.2: resume tailoring (nothing stored)
    val tailored = mutableStateOf<StudyService.Tailored?>(null)
    fun openTailor() { cancel(); tailored.value = null; screen.value = Screen.TAILOR }
    fun tailor(resume: String, jd: String) = task("Rewriting your bullet points for this job… on this phone this can take a few minutes.") {
        tailored.value = io { service.tailor(resume, jd) }
    }

    companion object {
        const val NO_NEW = "No new questions for this module right now. Try another module or come back later."
        const val SYSTEM = "You are a patient, accurate tutor helping someone prepare for job interviews."
        const val NO_MODEL = "No AI model is on this phone yet. Study writes lessons and questions with the model on this phone: download one in AI Models (Study never downloads anything by itself)."
        val MINUTES = listOf(30, 45, 60, 90, 120)
        fun duration(sec: Long): String { val m = Math.round(sec / 60.0); return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
        fun levelLabel(r: StudyCore.Mastery) = "${r.lessonsDone}/${r.lessonsTotal} · ${r.level}"
    }
}
