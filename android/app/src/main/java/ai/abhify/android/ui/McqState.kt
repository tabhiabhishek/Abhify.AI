package ai.abhify.android.ui

import ai.abhify.android.career.McqPractice
import ai.abhify.android.career.McqStore
import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.docText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Career → MCQs state: settings, the running practice (batches added on demand), answers with immediate feedback,
 * and progress (session, this resume/topic, overall) kept in filesDir/career_mcq.json. AI calls go through vm.aiTask
 * (your AI mode and model/provider; documents only after consent); it never switches model or provider by itself.
 */
class McqState(private val vm: AppViewModel) {
    private val g = vm.g
    private val store = McqStore(File(g.context.filesDir, "career_mcq.json"))
    private var data = store.load()

    val mode = mutableStateOf(McqPractice.Mode.RESUME)
    val topic = mutableStateOf("")
    val level = mutableStateOf("junior")
    val difficulty = mutableStateOf("medium")
    val batch = mutableStateOf(3)
    val sourceId = mutableStateOf<Long?>(null)                     // the resume (or document) the questions come from
    val skills = mutableStateListOf<String>()                       // chosen from the resume's skills
    val projects = mutableStateListOf<String>()                     // chosen from the resume's projects
    val review = mutableStateOf(false)                              // show answers before choosing (review mode)

    val questions = mutableStateListOf<McqPractice.Question>()
    val picks = mutableStateMapOf<String, Int>()
    val index = mutableStateOf(0)
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val note = mutableStateOf<String?>(null)
    private var sessionAttempts = mutableStateListOf<McqPractice.Attempt>()
    private var lastRequest: (() -> Unit)? = null

    val sources = mutableStateListOf<LibraryDoc>()
    val sourceText = mutableStateOf("")

    fun loadSources() = vm.viewModelScope.launch {
        val all = withContext(Dispatchers.IO) { runCatching { g.db.allDocs() }.getOrDefault(emptyList()) }
        sources.clear(); sources.addAll(all)
        if (sourceId.value == null) sourceId.value = vm.career.active()?.id ?: all.firstOrNull { it.role == "resume" }?.id
        sourceId.value?.let { pickSource(it) }
    }
    fun pickSource(id: Long) = vm.viewModelScope.launch {
        sourceId.value = id
        sourceText.value = withContext(Dispatchers.IO) { g.db.docText(id).orEmpty() }
        skills.clear(); projects.clear()
    }
    /** Skills and projects written in the chosen resume (read by rules; nothing inferred). */
    fun resumeSkills() = ai.abhify.android.resume.Resume.parse(sourceText.value).skills
    fun resumeProjects() = McqPractice.projectsOf(sourceText.value)

    fun context(): McqPractice.Context = McqPractice.Context(mode.value, topic.value, level.value, difficulty.value, batch.value,
        sourceId.value, sources.firstOrNull { it.id == sourceId.value }?.name.orEmpty(), if (mode.value == McqPractice.Mode.TOPIC) "" else sourceText.value,
        skills.toList(), projects.toList())

    /** Why this context can't be used yet (null = ready). */
    fun problem(): String? {
        val c = context()
        return when {
            c.mode == McqPractice.Mode.TOPIC && c.topic.isBlank() -> "Type a topic, for example SQL joins or Python generators."
            c.mode == McqPractice.Mode.MIXED && c.topic.isBlank() -> "Type a topic to mix with your resume."
            c.needsSource && c.sourceText.isBlank() -> "Choose a resume or document with readable text (add one in Career → Resumes or Documents)."
            c.mode == McqPractice.Mode.SKILLS && c.skills.isEmpty() -> "Choose at least one skill from your resume."
            c.mode == McqPractice.Mode.PROJECTS && c.projects.isEmpty() -> "Choose at least one project from your resume."
            else -> null
        }
    }

    /**
     * Next batch. [refresh] = replace the questions not answered yet (answered ones and all progress stay); otherwise
     * the batch is added after the current ones ("Generate more"). One request at a time.
     */
    fun generate(refresh: Boolean = false) {
        if (busy.value) return
        problem()?.let { error.value = it; return }
        val c = context()
        lastRequest = { generate(refresh) }
        busy.value = true; error.value = null; note.value = null
        val seen = data.seen + questions.map { McqPractice.normalize(it.q) }
        val avoid = questions.map { it.q }.ifEmpty { data.seen.takeLast(15) }
        val t0 = System.currentTimeMillis()
        // a new angle for every batch of this resume/topic, also across sessions (the desktop measured: the same angle repeats itself)
        val roundKey = "mcq_round_" + c.scope.hashCode().toUInt().toString(16)
        val round = g.prefs.getInt(roundKey, 0).also { g.prefs.edit().putInt(roundKey, it + 1).apply() }
        vm.aiTask("write practice questions", McqPractice.SYSTEM, McqPractice.prompt(c, avoid, device = true, round = round),
            if (c.needsSource) "your resume or document" else null, 4_200, McqPractice.prompt(c, avoid, device = false, round = round)) { r ->
            busy.value = false
            if (r.error != null) { error.value = r.error; return@aiTask }
            val b = McqPractice.validate(r.text, c, seen, round)
            android.util.Log.i("AbhifyCareer", "MCQ batch: ${b.questions.size} kept, ${b.dropped.values.sum()} dropped in ${System.currentTimeMillis() - t0} ms")
            if (b.questions.isEmpty()) {
                error.value = "No usable question in this batch (" + b.dropped.entries.joinToString { "${it.value} ${it.key}" } + "). Retry, or choose another model."
                return@aiTask
            }
            if (refresh) { val keep = questions.filter { it.key in picks }; questions.clear(); questions.addAll(keep); index.value = keep.size }
            else if (questions.isNotEmpty() && questions.getOrNull(index.value)?.key in picks) index.value = questions.size
            questions.addAll(b.questions)
            if (b.dropped.isNotEmpty()) note.value = "${b.dropped.values.sum()} question(s) failed the checks and were left out: " + b.dropped.keys.joinToString() +
                (if (b.questions.size * 2 < c.batch && c.mode in setOf(McqPractice.Mode.RESUME, McqPractice.Mode.DOCUMENT))
                    ". This document has been practised a lot: try \"Mixed: resume + topic\" or \"A topic\" for more variety." else "")
            data = data.copy(seen = (data.seen + b.questions.map { McqPractice.normalize(it.q) }).takeLast(3_000)); store.save(data)
        }
    }
    fun retry() { lastRequest?.invoke() ?: generate() }

    /** An answer (user tap): recorded once; the feedback shows immediately. */
    fun answer(q: McqPractice.Question, i: Int) {
        if (q.key in picks) return
        picks[q.key] = i
        val a = McqPractice.Attempt(q.key, q.topic, q.difficulty, context().scope, i == q.answer, System.currentTimeMillis())
        sessionAttempts += a
        data = data.copy(attempts = data.attempts + a); store.save(data)
    }
    fun next() { if (index.value < questions.size - 1) index.value++ else generate() }
    fun previous() { if (index.value > 0) index.value-- }
    /** Stop practice: the questions go, the progress stays. */
    fun stop() { questions.clear(); picks.clear(); index.value = 0; sessionAttempts.clear(); error.value = null; note.value = null }

    // ------------------------------------------------------------------ Question Bank → Generate (open questions)
    private val qStore = ai.abhify.android.career.CareerQuestionStore(File(g.context.filesDir, "career_questions.json"))
    val generated = mutableStateListOf<ai.abhify.android.career.CareerQuestions.Generated>().apply { addAll(qStore.load()) }
    val qType = mutableStateOf(ai.abhify.android.career.CareerQuestions.Type.TECHNICAL)
    val qCount = mutableStateOf(3)
    val qBusy = mutableStateOf(false)
    val qError = mutableStateOf<String?>(null)

    fun generateQuestions() {
        if (qBusy.value) return
        problem()?.let { qError.value = it; return }
        val c = context(); val t = qType.value
        if (t in setOf(ai.abhify.android.career.CareerQuestions.Type.RESUME, ai.abhify.android.career.CareerQuestions.Type.PROJECT) && c.sourceText.isBlank()) {
            qError.value = "Resume-based and project questions need a resume: choose one above."; return
        }
        qBusy.value = true; qError.value = null
        val avoid = generated.take(15).map { it.question }
        vm.aiTask("write interview questions", McqPractice.SYSTEM, ai.abhify.android.career.CareerQuestions.prompt(t, c, qCount.value, avoid, true),
            if (c.sourceText.isNotBlank() && c.mode != McqPractice.Mode.TOPIC) "your resume or document" else null, 4_200,
            ai.abhify.android.career.CareerQuestions.prompt(t, c, qCount.value, avoid, false)) { r ->
            qBusy.value = false
            if (r.error != null) { qError.value = r.error; return@aiTask }
            val seen = generated.map { McqPractice.normalize(it.question) }
            val fresh = ai.abhify.android.career.CareerQuestions.parse(r.text).filter { !McqPractice.isDuplicate(it.first, seen) }
            if (fresh.isEmpty()) { qError.value = "The AI's answer had no new question in the expected format. Retry, or choose another model."; return@aiTask }
            val now = System.currentTimeMillis()
            generated.addAll(0, fresh.map { (q, o) -> ai.abhify.android.career.CareerQuestions.Generated(q, o, t.label, c.topicLabel, c.mode.label, now) })
            qStore.save(generated.toList())
        }
    }
    fun deleteGenerated(q: ai.abhify.android.career.CareerQuestions.Generated) { generated.remove(q); qStore.save(generated.toList()) }

    fun sessionStats() = McqPractice.stats(sessionAttempts)
    fun scopeStats() = McqPractice.stats(data.attempts.filter { it.scope == context().scope })
    fun overallStats() = McqPractice.stats(data.attempts)
    /** Next build — read-only, for the skill graph (answers per topic). */
    fun attempts(): List<McqPractice.Attempt> = data.attempts
    fun topics() = McqPractice.byTopic(data.attempts)
    fun recent(n: Int = 5) = data.attempts.takeLast(n).reversed()
}
