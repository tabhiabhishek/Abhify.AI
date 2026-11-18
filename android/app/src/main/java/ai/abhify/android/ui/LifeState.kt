package ai.abhify.android.ui

import ai.abhify.android.ai.Source
import ai.abhify.android.chat.*
import ai.abhify.android.docs.Library
import ai.abhify.android.learn.Studio
import ai.abhify.android.life.Career
import ai.abhify.android.life.StudyKit
import ai.abhify.android.life.VaultKit
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Career Copilot, Study Assistant and Knowledge Vault. Everything is stored on this phone (database v7). AI work goes
 * through AppViewModel.aiTask (routing, consent before private text goes online, cancel, one heavy job at a time).
 */
class LifeState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    private val msg get() = vm.studio.message
    private fun io(block: suspend () -> Unit) = scope.launch {
        try { withContext(Dispatchers.IO) { block() } } catch (e: CancellationException) { throw e } catch (e: Exception) { msg.value = "Failed: ${e.message}" }
    }
    private val localBudget get() = g.memoryProfile().first.localDocChars

    // ================================================================== CAREER: job descriptions and tracker
    val jds = mutableStateListOf<JobDescription>()
    val apps = mutableStateListOf<JobApplication>()
    val appQuery = mutableStateOf("")
    val appStatus = mutableStateOf<Career.Status?>(null)
    val editingApp = mutableStateOf<JobApplication?>(null)
    /** Tracker 2.0 search, filters and sort. */
    val tracker = mutableStateOf(ai.abhify.android.life.Tracker.Query())
    val editingJd = mutableStateOf<JobDescription?>(null)

    fun loadCareer() = scope.launch {
        val (j, a) = withContext(Dispatchers.IO) { g.db.jds() to g.db.applications() }
        jds.clear(); jds.addAll(j); apps.clear(); apps.addAll(a)
    }
    fun visibleApps() = Career.filter(apps, appQuery.value, appStatus.value)
    fun newApp() { editingApp.value = JobApplication(0, "", "", null, null, null, java.time.LocalDate.now().toString(), Career.Status.SAVED.name, null, null, null, 0) }
    /** Returns the validation error (shown in the form) or null after saving. */
    fun saveApp(a: JobApplication): String? {
        Career.validate(a)?.let { return it }
        io { g.db.saveApplication(a.copy(appliedOn = a.appliedOn?.ifBlank { null }, followUpOn = a.followUpOn?.ifBlank { null }, interviewOn = a.interviewOn?.ifBlank { null })); loadCareer() }
        editingApp.value = null
        return null
    }
    fun deleteApp(a: JobApplication) = io { g.db.deleteApplication(a.id); loadCareer() }
    fun saveJd(j: JobDescription): String? {
        if (j.body.isBlank()) return "Paste the job description text."
        io { g.db.saveJd(j.id.takeIf { it > 0 }, j.title.ifBlank { j.body.lineSequence().first().take(60) }, j.company?.ifBlank { null }, j.body); loadCareer() }
        editingJd.value = null
        return null
    }
    fun deleteJd(j: JobDescription) = io { g.db.deleteJd(j.id); loadCareer() }
    /** Opens the Resume Assistant with this job description ready for comparison. */
    fun compareWithResume(j: JobDescription) { vm.resume.jd.value = j.body; vm.go(AppViewModel.Route.RESUME) }

    // ================================================================== CAREER: interview practice
    val topic = mutableStateOf(Career.Topic.TECHNICAL)
    val level = mutableStateOf("intermediate")
    val question = mutableStateOf<Pair<String, String>?>(null)        // question, answer outline
    val questionSource = mutableStateOf<Source?>(null)
    val answer = mutableStateOf("")
    val feedback = mutableStateOf<String?>(null)
    /** For job-based questions: the requirement the question tests (shown so the user knows where it came from). */
    val questionRequirement = mutableStateOf<String?>(null)
    /** Structured feedback that passed validation (scores 1–5 per rubric item). */
    val feedbackScores = mutableStateOf<Map<String, Int>?>(null)
    val attempts = mutableStateListOf<InterviewAttempt>()
    private val asked = mutableListOf<String>()

    fun loadAttempts() = scope.launch { val a = withContext(Dispatchers.IO) { g.db.attempts(300) }; attempts.clear(); attempts.addAll(a) }
    /** v3.7: show only the practice for the selected job (Career → Match). */
    val attemptsForJob = mutableStateOf(false)

    fun nextQuestion() {
        val t = topic.value
        vm.career.ensureOpen()
        val resume = if (t == Career.Topic.PROJECTS) vm.career.effectiveText().takeIf { it.isNotBlank() } else null
        if (t == Career.Topic.PROJECTS && resume == null) { msg.value = "Choose a resume in Career → Resumes first (project questions come from it)."; return }
        val jd = vm.career.selectedJd.value
        if (t == Career.Topic.JOB && jd == null) { msg.value = "Choose a job description in Career → Match first."; return }
        val job = jd?.let { j -> val f = vm.career.fieldsOf(j)
            "Title: ${f.title.ifBlank { j.title }}\nMandatory skills: ${f.required.joinToString()}\nPreferred skills: ${f.preferred.joinToString()}\n" +
                "Experience: ${f.experience.joinToString(" | ")}\nResponsibilities:\n" + f.responsibilities.joinToString("\n") { "- $it" } }
        answer.value = ""; feedback.value = null; feedbackScores.value = null
        val private = when { resume != null -> "your resume"; t == Career.Topic.JOB -> "the job description"; else -> null }
        vm.aiTask("write an interview question", Studio.SYSTEM, Career.questionPrompt(t, level.value, resume?.take(localBudget), asked, job?.take(localBudget)),
            private, localBudget + 800, Career.questionPrompt(t, level.value, resume, asked, job)) { r ->
            val q = Career.parseQuestion(r.text)
            if (q == null) msg.value = r.error ?: "The AI's question wasn't in the expected format. Try again."
            else { question.value = q; questionSource.value = r.source; asked += q.first
                questionRequirement.value = if (t == Career.Topic.JOB) Career.requirementOf(r.text) ?: "from the job description" else null }
        }
    }

    fun submitAnswer() {
        val q = question.value ?: return
        val a = answer.value.trim(); if (a.isEmpty()) return
        feedbackScores.value = null
        vm.aiTask("review your answer", Studio.SYSTEM, Career.feedbackPrompt(q.first, a), "your answer") { r ->
            if (r.error != null) { feedback.value = "⚠ ${r.error}"; return@aiTask }
            val (fb, why) = ai.abhify.android.career.AiJson.feedback(r.text)
            if (fb == null || r.status != null) {
                // malformed or partial output is shown for transparency but never saved; the answer stays for a retry
                feedback.value = "⚠ The feedback couldn't be validated (${why ?: "incomplete"}), so it wasn't saved. Tap \"Get feedback\" to retry.\n\n" + r.text.take(3000)
                return@aiTask
            }
            feedbackScores.value = fb.scores
            val md = buildString {
                append("**Scores (AI estimate, 1–5):** "); append(fb.scores.entries.joinToString(" · ") { "${it.key} ${it.value}" }); append("\n\n")
                if (fb.strengths.isNotEmpty()) { append("## Strengths\n"); fb.strengths.forEach { append("- $it\n") } }
                if (fb.missing.isNotEmpty()) { append("## Missing points\n"); fb.missing.forEach { append("- $it\n") } }
                if (fb.structure.isNotBlank()) append("## Suggested answer structure\n${fb.structure}\n")
                if (fb.recommendations.isNotEmpty()) { append("## Recommendations\n"); fb.recommendations.forEach { append("- $it\n") } }
            }
            feedback.value = md
            val jdId = if (topic.value == Career.Topic.JOB) vm.career.selectedJd.value?.id else null
            io { g.db.addAttempt(topic.value.label + (questionRequirement.value?.let { " · $it" } ?: ""), q.first, a, md, r.source?.label,
                jdId, ai.abhify.android.career.PracticeStats.meanScore(fb.scores.values)); loadAttempts() }
        }
    }
    fun deleteAttempt(a: InterviewAttempt) = io { g.db.deleteAttempt(a.id); loadAttempts() }

    // ================================================================== STUDY
    enum class SourceKind { TOPIC, DOCUMENT, NOTE }
    val sourceKind = mutableStateOf(SourceKind.TOPIC)
    val studyTopic = mutableStateOf("")
    val studyDoc = mutableStateOf<LibraryDoc?>(null)
    val studyNote = mutableStateOf<Note?>(null)
    val sessions = mutableStateListOf<StudySession>()
    val open = mutableStateOf<StudySession?>(null)
    val quiz = mutableStateOf<StudyKit.Quiz?>(null)
    val cardIndex = mutableStateOf(0)
    val reveal = mutableStateOf(false)

    fun loadSessions() = scope.launch { val s = withContext(Dispatchers.IO) { g.db.sessions() }; sessions.clear(); sessions.addAll(s) }
    val notes = mutableStateListOf<Note>()
    val docs = mutableStateListOf<LibraryDoc>()
    fun loadSources() = scope.launch {
        val (n, d) = withContext(Dispatchers.IO) { g.db.workspaces().flatMap { g.db.notes(it.id) } to g.db.docs() }
        notes.clear(); notes.addAll(n); docs.clear(); docs.addAll(d)
    }

    /** The chosen material: title, text (null for a bare topic), the source label for references, whether it is private. */
    private suspend fun material(): Triple<String, String?, String>? = withContext(Dispatchers.IO) {
        when (sourceKind.value) {
            SourceKind.TOPIC -> studyTopic.value.trim().takeIf { it.isNotEmpty() }?.let { Triple(it, null, "topic: $it") }
            SourceKind.DOCUMENT -> studyDoc.value?.let { d -> Triple(d.name, Library.readable(g.db.docText(d.id).orEmpty()), "document: ${d.name}") }
            SourceKind.NOTE -> studyNote.value?.let { n -> Triple(n.title, n.body, "note: ${n.title}") }
        }
    }

    fun study(kind: StudyKit.Kind, days: Int = 7, minutes: Int = 30) = scope.launch {
        val (title, text, ref) = material() ?: run { msg.value = "Choose a topic, document or note first."; return@launch }
        if (kind == StudyKit.Kind.EXPLAIN && text != null) { msg.value = "Explanations are for topics; for a document use Summary."; return@launch }
        val instruction = when (kind) {
            StudyKit.Kind.EXPLAIN -> StudyKit.explain(title)
            StudyKit.Kind.SUMMARY -> StudyKit.SUMMARY
            StudyKit.Kind.QUIZ -> Studio.mcqFromText(6)
            StudyKit.Kind.CARDS -> Studio.cardsFromText(10)
            StudyKit.Kind.PLAN -> StudyKit.revision(title, days, minutes)
        }
        fun body(n: Int) = if (text == null) (if (kind == StudyKit.Kind.EXPLAIN || kind == StudyKit.Kind.PLAN) instruction else "$instruction\n\nTopic: $title")
            else "$instruction\n\n<material name=\"$title\">\n${ai.abhify.android.docs.Documents.excerpt(text, title, n).text}\n</material>"
        vm.aiTask("make a ${kind.label.lowercase()}", Studio.SYSTEM, body(localBudget - 300), if (text != null) "\"$title\"" else null, localBudget + 300, body(20_000)) { r ->
            if (r.text.isBlank() || r.status != null) { msg.value = r.error ?: "The result wasn't complete; it was not saved. Try again."; return@aiTask }
            val sources = listOf(ref) + (r.source?.let { listOf("written by ${it.label}") } ?: emptyList())
            when (kind) {
                StudyKit.Kind.QUIZ -> {
                    val qs = Studio.parseMcqs(r.text)
                    if (qs.isEmpty()) { msg.value = "The AI's questions didn't pass the checks (4 different options, one answer). Try again."; return@aiTask }
                    val q = StudyKit.Quiz(qs, qs.map { null }, 0)
                    saveAndOpen("Quiz: $title", kind, sourceKind.value.name, ref, StudyKit.encodeQuiz(q, sources), 0, qs.size)
                }
                StudyKit.Kind.CARDS -> {
                    val cards = Studio.parseCards(r.text)
                    if (cards.isEmpty()) { msg.value = "The AI's answer had no usable Q:/A: cards. Try again."; return@aiTask }
                    saveAndOpen("Cards: $title", kind, sourceKind.value.name, ref, StudyKit.encodeCards(cards, sources), null, cards.size)
                }
                else -> saveAndOpen("${kind.label}: $title", kind, sourceKind.value.name, ref, StudyKit.encodeText(r.text, sources), null, null)
            }
        }
    }

    private fun saveAndOpen(title: String, kind: StudyKit.Kind, sk: String, ref: String, content: String, score: Int?, total: Int?) = scope.launch {
        val id = withContext(Dispatchers.IO) { g.db.saveSession(null, title, kind.name, sk, ref, content, score, total) }
        loadSessions()
        withContext(Dispatchers.IO) { g.db.sessions().firstOrNull { it.id == id } }?.let(::openSession)
    }

    fun openSession(s: StudySession?) {
        open.value = s; cardIndex.value = 0; reveal.value = false
        quiz.value = if (s?.kind == StudyKit.Kind.QUIZ.name) StudyKit.decodeQuiz(s.content)?.first else null
    }

    /** Answers the current question and saves progress (resume later from history). */
    fun pick(i: Int) {
        val s = open.value ?: return; val q = quiz.value ?: return
        val n = q.pick(i); quiz.value = n
        val sources = StudyKit.decodeQuiz(s.content)?.second ?: emptyList()
        io { g.db.saveSession(s.id, s.title, s.kind, s.sourceKind, s.sourceRef, StudyKit.encodeQuiz(n, sources), n.correct, n.questions.size) }
    }
    fun move(next: Boolean) { val q = quiz.value ?: return; quiz.value = if (next) q.next() else q.prev()
        open.value?.let { s -> io { g.db.saveSession(s.id, s.title, s.kind, s.sourceKind, s.sourceRef, StudyKit.encodeQuiz(quiz.value!!, StudyKit.decodeQuiz(s.content)?.second ?: emptyList()), quiz.value!!.correct, quiz.value!!.questions.size) } } }
    fun reviewWrong() {
        val s = open.value ?: return; val r = quiz.value?.reviewWrong() ?: run { msg.value = "No wrong answers to review."; return }
        saveAndOpen("Review: " + s.title.removePrefix("Quiz: "), StudyKit.Kind.QUIZ, s.sourceKind, s.sourceRef ?: "", StudyKit.encodeQuiz(r, listOf(s.sourceRef ?: "")), 0, r.questions.size)
    }
    fun deleteSession(s: StudySession) = io { g.db.deleteSession(s.id); if (open.value?.id == s.id) open.value = null; loadSessions() }
    /** A revision plan session → a trackable study plan (Learn → Plans) with tick-off lessons. */
    fun planToTracker(s: StudySession) {
        val lessons = Studio.parseList(StudyKit.decodeText(s.content).first)
        if (lessons.size < 3) { msg.value = "This plan isn't a usable day-by-day list."; return }
        io { g.db.addPlan(s.title.removePrefix("Revision plan: "), "Revision", null, null, null, lessons); vm.studio.loadPlans(); msg.value = "Added to Study → Plans with ${lessons.size} days." }
    }

    // ================================================================== KNOWLEDGE VAULT
    val vault = mutableStateListOf<VaultItem>()
    val vaultQuery = mutableStateOf("")
    val vaultCategory = mutableStateOf<String?>(null)
    val favoritesOnly = mutableStateOf(false)
    val vaultSort = mutableStateOf("recent")
    val selected = mutableStateListOf<Long>()
    val openItem = mutableStateOf<VaultItem?>(null)

    fun loadVault() = scope.launch {
        val v = withContext(Dispatchers.IO) { g.db.vault(vaultQuery.value, vaultCategory.value, favoritesOnly.value, vaultSort.value) }
        vault.clear(); vault.addAll(v)
    }
    fun save(kind: String, title: String, body: String, category: String, tags: List<String> = emptyList(), refKind: String? = null, refId: Long? = null) = io {
        g.db.addVault(kind, title.ifBlank { "Untitled" }, body, category, tags, refKind, refId)
        msg.value = "Saved to My Knowledge ($category)."; loadVault()
    }
    fun saveDocument(d: LibraryDoc) = save("document", d.name, "${d.kind} document in your library (${"%,d".format(d.chars)} characters).", "Documents", emptyList(), "doc", d.id)
    fun openVault(i: VaultItem) = scope.launch {
        withContext(Dispatchers.IO) { g.db.touchVault(i.id) }
        // a saved document shows its current library text (one copy of the text, in the library)
        val body = if (i.refKind == "doc" && i.refId != null) withContext(Dispatchers.IO) { g.db.docText(i.refId)?.let(Library::readable) } ?: "(This document was deleted from the library.)" else i.body
        openItem.value = i.copy(body = body); loadVault()
    }
    fun updateVault(i: VaultItem, title: String, category: String, tags: String, favorite: Boolean) = io {
        g.db.updateVault(i.id, title.ifBlank { i.title }, category, VaultKit.tags(tags), favorite); loadVault()
    }
    fun toggleFavorite(i: VaultItem) = io { g.db.updateVault(i.id, i.title, i.category, i.tags, !i.favorite); loadVault() }
    fun deleteVault(i: VaultItem) = io { g.db.deleteVault(i.id); selected.remove(i.id); if (openItem.value?.id == i.id) openItem.value = null; loadVault() }

    /** Q&A over the SELECTED items only, with [S#] sources. */
    fun askVault(question: String) = scope.launch {
        if (question.isBlank()) return@launch
        if (selected.isEmpty()) { msg.value = "Select the items to search first (tick them)."; return@launch }
        val items = withContext(Dispatchers.IO) {
            selected.mapNotNull { g.db.vaultItem(it) }.map { i -> if (i.refKind == "doc" && i.refId != null) i.copy(body = g.db.docText(i.refId).orEmpty()) else i }
        }
        val local = VaultKit.context(items, question, localBudget - 500); val online = VaultKit.context(items, question, 20_000)
        if (online.sources.isEmpty()) {
            vm.studio.result.value = StudioState.Result("Q: $question", "None of the selected items contains these words, so there is nothing to cite. " +
                "Select other items or ask in Chat for a general answer.", null, null, null); return@launch
        }
        fun body(c: Library.Context) = "${Library.CITE}\n\nQuestion: $question\n\nSources:\n${c.text}"
        vm.aiTask("answer from your knowledge", Studio.SYSTEM, body(local), "${items.size} selected items", localBudget + 300, body(online)) { r ->
            vm.studio.result.value = StudioState.Result("Q: $question", r.text, r.source, r.status, r.error, if (r.source?.cloud == true) online.sources else local.sources)
        }
    }
}
