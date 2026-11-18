package ai.abhify.android.ui

import ai.abhify.android.ai.Source
import ai.abhify.android.chat.*
import ai.abhify.android.docs.DocText
import ai.abhify.android.docs.DocumentException
import ai.abhify.android.docs.DocumentReader
import ai.abhify.android.docs.Documents
import ai.abhify.android.docs.Library
import ai.abhify.android.learn.Studio
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and actions for the Documents, Learn (tutor, plans, flashcards) and Work areas. Everything is stored on this
 * phone (database v5). AI work goes through AppViewModel.aiTask, which uses the user's routing and asks before any
 * document or note text is sent online.
 */
class StudioState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope

    /** An AI result shown in a sheet: what it is, who wrote it, whether it is complete, and the sources it was given. */
    data class Result(val title: String, val text: String, val source: Source?, val status: String?, val error: String?,
                      val sources: List<Library.Chunk> = emptyList(), val actions: List<String> = emptyList(), val workspaceId: Long? = null)
    val result = mutableStateOf<Result?>(null)
    val message = mutableStateOf<String?>(null)

    private fun io(block: suspend () -> Unit) = scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: CancellationException) { throw e } catch (e: Exception) { message.value = "Failed: ${e.message}" } }
    private fun show(title: String, r: AppViewModel.TaskResult, sources: List<Library.Chunk> = emptyList(), actions: List<String> = emptyList(), ws: Long? = null) {
        result.value = Result(title, r.text, r.source, r.status, r.error, sources, actions, ws)
    }

    // ------------------------------------------------------------------ documents
    val docs = mutableStateListOf<LibraryDoc>()
    val docQuery = mutableStateOf("")
    val importing = mutableStateOf<String?>(null)
    private var importJob: Job? = null
    val viewing = mutableStateOf<Pair<LibraryDoc, String>?>(null)

    fun loadDocs(workspaceId: Long? = null) = scope.launch {
        val (list, todo) = withContext(Dispatchers.IO) { g.db.docs(workspaceId, docQuery.value) to runCatching { g.db.unindexedDocs().toSet() }.getOrDefault(emptySet()) }
        docs.clear(); docs.addAll(sorted(list)); unindexed.value = todo
    }
    /** v4.4 library order (on this phone; remembered). */
    enum class DocSort(val label: String) { NEWEST("Newest"), NAME("Name"), SIZE("Size"), TYPE("Type") }
    val docSort = mutableStateOf(runCatching { DocSort.valueOf(g.prefs.getString("doc_sort", "NEWEST")!!) }.getOrDefault(DocSort.NEWEST))
    fun setDocSort(s: DocSort) { docSort.value = s; g.prefs.edit().putString("doc_sort", s.name).apply(); val l = sorted(docs.toList()); docs.clear(); docs.addAll(l) }
    private fun sorted(l: List<LibraryDoc>) = when (docSort.value) {
        DocSort.NEWEST -> l.sortedByDescending { it.updatedAt }
        DocSort.NAME -> l.sortedBy { it.name.lowercase() }
        DocSort.SIZE -> l.sortedByDescending { it.sizeBytes }
        DocSort.TYPE -> l.sortedWith(compareBy<LibraryDoc> { it.kind }.thenBy { it.name.lowercase() })
    }
    /** v4.4: documents without a search index yet (new, restored, or indexing failed): shown with a Re-index button. */
    val unindexed = mutableStateOf<Set<Long>>(emptySet())

    /** The last import's warnings (OCR pages, unclear lines, skipped pages) for the user to review. */
    val importWarnings = mutableStateListOf<String>()

    /**
     * Reads [uri] on the phone (text layers first, OCR only for scanned pages/images) and adds it to the library.
     * [ocr] null = use the saved OCR permission or ask; the same file is retried after the answer (no re-upload).
     */
    fun importDoc(uri: Uri, workspaceId: Long? = null, role: String? = null, ocr: Boolean? = null, onAdded: (Long) -> Unit = {}) {
        importJob?.cancel(); message.value = null; importWarnings.clear(); importing.value = "Reading the document on this phone…"
        importJob = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { DocumentReader.read(g, uri, ocr) { step -> importing.value = step } }
                val id = save(r, workspaceId, role, "device") ?: return@launch
                message.value = "Added ${r.doc.name} (${"%,d".format(r.doc.chars)} characters" + (if (r.doc.truncated) ", first ${"%,d".format(Documents.MAX_CHARS)} kept" else "") +
                    (if (r.ocrPages > 0) ", ${r.ocrPages} page(s) read by text recognition" else "") + ")." + if (r.warnings.isNotEmpty()) " Review the notes below." else ""
                importWarnings.addAll(r.warnings)
                loadDocs(workspaceId); onAdded(id)
            } catch (e: ai.abhify.android.docs.OcrNeeded) {
                vm.ocrAsk.value = AppViewModel.OcrAsk(e.name, e.pages) { allow -> importDoc(uri, workspaceId, role, allow, onAdded) }
            } catch (e: DocumentException) { message.value = e.message }
            catch (e: CancellationException) { message.value = "Cancelled. Nothing was saved; pick the file again to retry."; throw e }
            catch (e: Exception) { message.value = "Couldn't read the document: ${e.message}" }
            finally { importing.value = null }
        }
    }
    fun cancelImport() { importJob?.cancel(); importing.value = null }

    /**
     * Stores an extracted document and indexes it for retrieval. The same file (same SHA-256) is never imported twice:
     * the existing copy is reused (and reported). Returns the document id, or null if nothing new was saved.
     */
    private suspend fun save(r: DocumentReader.Read, workspaceId: Long?, role: String?, source: String): Long? {
        val dup = if (r.sha256.isEmpty()) null else withContext(Dispatchers.IO) { g.db.docBySha(r.sha256) }
        if (dup != null) {
            message.value = "\"${r.doc.name}\" is already in your library as \"${dup.name}\"; it wasn't imported again."
            if (role != null && dup.role != role) withContext(Dispatchers.IO) { g.db.setRole(dup.id, role) }
            return dup.id
        }
        return withContext(Dispatchers.IO) {
            val id = g.db.addDoc(r.doc.name, r.doc.kind, r.sizeBytes, r.doc.text, r.doc.truncated, workspaceId, r.pages, r.ocrPages, r.warnings, role)
            g.db.setDocSource(id, r.sha256.ifEmpty { null }, source)
            runCatching { ai.abhify.android.docs.RagIndex.index(g.db, id) }                      // retrieval index (re-done on demand if this fails)
            id
        }
    }

    /** A file downloaded from Google Drive (or any bytes): same extraction, duplicate check and indexing as a picked file. */
    fun importBytes(name: String, mime: String?, bytes: ByteArray, source: String, ocr: Boolean? = null, onAdded: (Long) -> Unit = {}) {
        importJob?.cancel(); message.value = null; importWarnings.clear(); importing.value = "Reading $name on this phone…"
        importJob = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { DocumentReader.process(g, name, mime, bytes, ocr) { step -> importing.value = step } }
                val id = save(r, null, null, source) ?: return@launch
                if (message.value == null) message.value = "Added ${r.doc.name} to your library."
                importWarnings.addAll(r.warnings); loadDocs(); onAdded(id)
            } catch (e: ai.abhify.android.docs.OcrNeeded) {
                vm.ocrAsk.value = AppViewModel.OcrAsk(e.name, e.pages) { allow -> importBytes(name, mime, bytes, source, allow, onAdded) }
            } catch (e: DocumentException) { message.value = e.message }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Couldn't read the document: ${e.message}" }
            finally { importing.value = null }
        }
    }

    /** Rebuilds a document's retrieval index (after a change or if search seems off). */
    fun reindex(d: LibraryDoc) = io { val n = ai.abhify.android.docs.RagIndex.index(g.db, d.id); message.value = "Re-indexed ${d.name} ($n sections)."; loadDocs(d.workspaceId) }

    fun view(d: LibraryDoc) = scope.launch { withContext(Dispatchers.IO) { g.db.docText(d.id) }?.let { viewing.value = d to Library.readable(it) } }
    fun rename(d: LibraryDoc, name: String) = io { if (name.isNotBlank()) g.db.renameDoc(d.id, name.trim()); loadDocs(d.workspaceId) }
    fun delete(d: LibraryDoc) = io { g.db.deleteDoc(d.id); loadDocs(d.workspaceId) }

    /** Opens the chat with this document attached (the chat's own consent rules apply). */
    fun askInChat(d: LibraryDoc) = scope.launch {
        val text = withContext(Dispatchers.IO) { g.db.docText(d.id) } ?: return@launch
        vm.attachment.value = DocText(d.name, d.kind, Library.readable(text), d.truncated)
        vm.tab.value = AppViewModel.Tab.CHAT
    }

    enum class DocAction(val label: String, val instruction: String) {
        SUMMARY("Summary", Studio.SUMMARY), KEY_POINTS("Key points", Studio.KEY_POINTS), ACTIONS("Action items", Studio.ACTION_ITEMS),
        NOTES("Study notes", Studio.NOTES), CARDS("Flashcards", Studio.cardsFromText()), QUIZ("Quiz", Studio.mcqFromText()),
    }

    /** The document text that fits a budget: whole when it fits, else its opening and the parts that match the request. */
    private fun fit(text: String, query: String, budget: Int) = Documents.excerpt(Library.readable(text), query, budget).text
    /** The phone's model gets a document budget from the memory profile (online providers get more). */
    private val local get() = g.memoryProfile().first.localDocChars

    fun docAction(d: LibraryDoc, a: DocAction) = scope.launch {
        val text = withContext(Dispatchers.IO) { g.db.docText(d.id) } ?: return@launch
        fun body(budget: Int) = "${a.instruction}\n\n<document name=\"${d.name}\">\n${fit(text, a.label, budget)}\n</document>"
        vm.aiTask("make ${a.label.lowercase()} for ${d.name}", Studio.SYSTEM, body(local - 300), "\"${d.name}\"", local + 300, body(24_000)) { r ->
            when (a) {
                DocAction.CARDS -> {
                    val cards = Studio.parseCards(r.text)
                    if (cards.isEmpty()) show("Flashcards from ${d.name}", r.copy(error = r.error ?: "The AI's answer had no usable Q:/A: cards. Try again."))
                    else { io { g.db.addCards(d.name, cards, "doc:${d.id}") }; message.value = "Saved ${cards.size} flashcards to the deck \"${d.name}\" (Learn → Cards)."; loadDecks() }
                }
                DocAction.QUIZ -> {
                    val qs = Studio.parseMcqs(r.text)
                    if (qs.isEmpty()) show("Quiz from ${d.name}", r.copy(error = r.error ?: "The AI's questions didn't pass the checks (4 different options, one answer). Try again."))
                    else { quiz.clear(); quiz.addAll(qs); quizTitle.value = "Quiz: ${d.name}"; quizIndex.intValue = 0; quizPicked.value = null; quizScore.intValue = 0 }
                }
                DocAction.ACTIONS -> show("${a.label}: ${d.name}", r, actions = Studio.parseActions(r.text))
                else -> show("${a.label}: ${d.name}", r)
            }
        }
    }

    /** Questions over the whole library (or one workspace's documents): local keyword retrieval, answers cite [S#]. */
    /** Documents the user ticked for Q&A (empty = all documents of the library / workspace). */
    val qaSelection = mutableStateListOf<Long>()

    fun askLibrary(question: String, workspaceId: Long? = null, only: Set<Long>? = null) = scope.launch {
        if (question.isBlank()) return@launch
        val ids = withContext(Dispatchers.IO) { g.db.docs(workspaceId).filter { only.isNullOrEmpty() || it.id in only }.map { it.id } }
        if (ids.isEmpty()) { message.value = "Add a document first."; return@launch }
        // stored chunk index (FTS4 candidates when available) + BM25, only within the chosen documents
        val hits = withContext(Dispatchers.IO) { ai.abhify.android.docs.RagIndex.retrieve(g.db, ids, question, 8, g.embedder(), ai.abhify.android.docs.EmbeddingManager.MIN_COSINE) }
        if (hits.isEmpty()) {
            result.value = Result("Q: $question", "No section of your documents matches these words, so there is nothing to cite. " +
                "Try other words, or ask in Chat for a general answer.", null, null, null); return@launch
        }
        fun body(budget: Int) = Library.context(hits, budget).let { "${Library.CITE}\n\nQuestion: $question\n\nSources:\n${it.text}" }
        val local = Library.context(hits, 3000); val online = Library.context(hits, 20_000)
        vm.aiTask("answer from your documents", Studio.SYSTEM, body(3000), "the matching sections of your documents", 3600, body(20_000)) { r ->
            // the device run gets fewer sections; show the sources that run actually received
            show("Q: $question", r, sources = if (r.source?.cloud == true) online.sources else local.sources)
        }
    }

    fun compare(a: LibraryDoc, b: LibraryDoc) = scope.launch {
        val (ta, tb) = withContext(Dispatchers.IO) { g.db.docText(a.id).orEmpty() to g.db.docText(b.id).orEmpty() }
        fun body(budget: Int) = Studio.compare(a.name, b.name) + "\n\n<document name=\"${a.name}\">\n${fit(ta, "", budget / 2)}\n</document>\n\n" +
            "<document name=\"${b.name}\">\n${fit(tb, "", budget / 2)}\n</document>"
        vm.aiTask("compare ${a.name} and ${b.name}", Studio.SYSTEM, body(3200), "\"${a.name}\" and \"${b.name}\"", 3600, body(24_000)) { r -> show("Compare: ${a.name} ↔ ${b.name}", r) }
    }

    // ------------------------------------------------------------------ quiz from documents
    val quiz = mutableStateListOf<Studio.Mcq>()
    val quizTitle = mutableStateOf("")
    val quizIndex = mutableIntStateOf(0)
    val quizPicked = mutableStateOf<Int?>(null)
    val quizScore = mutableIntStateOf(0)
    fun answerQuiz(i: Int) { val q = quiz.getOrNull(quizIndex.intValue) ?: return; if (quizPicked.value != null) return; quizPicked.value = i; if (i == q.correct) quizScore.intValue++ }
    fun nextQuiz() { quizIndex.intValue++; quizPicked.value = null }
    fun closeQuiz() { quiz.clear() }

    // ------------------------------------------------------------------ learn: tutor
    val tutorTopic = mutableStateOf("")
    val tutorLevel = mutableStateOf(Studio.Level.BEGINNER)
    fun teach() { val t = tutorTopic.value.trim(); if (t.isEmpty()) return
        vm.aiTask("explain $t", Studio.SYSTEM, Studio.tutor(t, tutorLevel.value), null) { r -> show("${tutorLevel.value.label}: $t", r) } }
    fun evaluate(question: String, answer: String) { if (answer.isBlank()) return
        vm.aiTask("check your answer", Studio.SYSTEM, Studio.evaluate(question, answer), null) { r -> show("Feedback on your answer", r) } }

    // ------------------------------------------------------------------ learn: study plans
    val plans = mutableStateListOf<StudyPlan>()
    val planItems = mutableStateListOf<PlanItem>()
    val openPlan = mutableStateOf<StudyPlan?>(null)
    val planProgress = mutableStateOf<Map<Long, Pair<Int, Int>>>(emptyMap())
    fun loadPlans() = scope.launch {
        val (ps, prog) = withContext(Dispatchers.IO) { g.db.plans().let { ps -> ps to ps.associate { p -> g.db.planItems(p.id).let { p.id to (it.count { i -> i.done } to it.size) } } } }
        plans.clear(); plans.addAll(ps); planProgress.value = prog
    }
    fun open(p: StudyPlan?) = scope.launch {
        openPlan.value = p; planItems.clear(); planDocNames.clear()
        if (p != null) { planItems.addAll(withContext(Dispatchers.IO) { g.db.planItems(p.id) })
            planDocNames.addAll(withContext(Dispatchers.IO) { g.db.planDocs(p.id).map { g.db.doc(it)?.name ?: "(deleted document)" } }) }
    }
    /**
     * v3.9: the AI drafts lessons (optionally from selected library documents: their names and openings go into the
     * prompt, with the usual consent for online use); the draft is checked against the time available before saving.
     */
    fun makePlan(topic: String, level: Studio.Level, goal: String, target: String, minutes: Int, docIds: List<Long> = emptyList()) {
        if (topic.isBlank()) return
        val pre = ai.abhify.android.learn.PlanCheck.check(listOf("a lesson", "b lesson", "c lesson"), minutes, target)
        pre.error?.let { message.value = it; return }
        scope.launch {
            val material = if (docIds.isEmpty()) null else withContext(Dispatchers.IO) { docIds.mapNotNull { id -> g.db.doc(id)?.let { d ->
                "- ${d.name}: " + ai.abhify.android.docs.Library.readable(g.db.docText(id).orEmpty()).take(400).replace('\n', ' ') } }.joinToString("\n") }
            val prompt = Studio.plan(topic.trim(), level, goal, target, minutes, pre.daysAvailable, material)
            vm.aiTask("make a study plan", Studio.SYSTEM, prompt, if (material != null) "the selected documents" else null) { r ->
                val c = ai.abhify.android.learn.PlanCheck.check(Studio.parseList(r.text), minutes, target)
                if (c.error != null) show("Study plan: $topic", r.copy(error = r.error ?: "The AI's plan wasn't usable: ${c.error} Try again."))
                else io { val id = g.db.addPlan(topic.trim(), level.label, goal.ifBlank { null }, target.ifBlank { null }, minutes, c.lessons)
                    if (docIds.isNotEmpty()) g.db.setPlanDocs(id, docIds); loadPlans()
                    message.value = "Plan saved with ${c.lessons.size} lessons (written by ${r.source?.label ?: "the AI"}; edit any lesson)." +
                        c.warnings.joinToString("") { " ⚠ $it" } }
            }
        }
    }
    fun editLesson(i: PlanItem, text: String) = io { if (text.isNotBlank()) g.db.editLesson(i.id, text); openPlan.value?.let { open(it) } }
    fun deleteLesson(i: PlanItem) = io { g.db.deleteLesson(i.id); openPlan.value?.let { open(it) }; loadPlans() }
    fun addLesson(text: String) { val p = openPlan.value ?: return; if (text.isBlank()) return; io { g.db.addLesson(p.id, text); open(p); loadPlans() } }
    val planDocNames = mutableStateListOf<String>()
    /** v4.0 "document → study plan": pre-selected documents for the next new plan. */
    val planDocPreset = mutableStateListOf<Long>()
    fun toggleLesson(i: PlanItem) = io { g.db.setLessonDone(i.id, i.planId, !i.done); openPlan.value?.let { open(it) }; loadPlans() }
    fun deletePlan(p: StudyPlan) = io { g.db.deletePlan(p.id); openPlan.value = null; loadPlans() }

    // ------------------------------------------------------------------ learn: flashcards
    companion object { const val DUE_ALL = "\u0000due" }
    val decks = mutableStateListOf<Pair<String, Pair<Int, Int>>>()
    val reviewDeck = mutableStateOf<String?>(null)
    val reviewCards = mutableStateListOf<Flashcard>()
    val reviewIndex = mutableIntStateOf(0)
    val showBack = mutableStateOf(false)
    fun loadDecks() = scope.launch { val d = withContext(Dispatchers.IO) { g.db.decks() }; decks.clear(); decks.addAll(d) }
    /** v3.9: spaced-repetition state of the cards in this review (for the interval shown on each button). */
    val reviewStates = mutableStateMapOf<Long, ai.abhify.android.learn.Srs.State>()
    /** True when nothing was due and the whole deck is being practised anyway. */
    val practiceAll = mutableStateOf(false)
    /** [deck] = null closes; [DUE_ALL] = every due card from all decks. Due cards first; if none, the whole deck. */
    fun review(deck: String?) = scope.launch {
        reviewDeck.value = deck; reviewIndex.intValue = 0; showBack.value = false; reviewCards.clear(); reviewStates.clear(); practiceAll.value = false
        if (deck == null) return@launch
        val due = withContext(Dispatchers.IO) { g.db.dueCards(if (deck == DUE_ALL) null else deck) }
        val list = if (due.isNotEmpty() || deck == DUE_ALL) due else withContext(Dispatchers.IO) { g.db.cards(deck).mapNotNull { g.db.srsCard(it.id) } }.also { practiceAll.value = true }
        reviewCards.addAll(list.map { it.card }); list.forEach { reviewStates[it.card.id] = it.state }
    }
    fun rate(r: ai.abhify.android.learn.Srs.Rating) {
        val c = reviewCards.getOrNull(reviewIndex.intValue) ?: return
        io { g.db.rateCard(c.id, r) }
        reviewIndex.intValue++; showBack.value = false
        if (reviewIndex.intValue >= reviewCards.size) { loadDecks(); loadDashboard() }
    }
    /** Pre-3.9 two-button review (kept for compatibility): known = Good, still learning = Again. */
    fun mark(known: Boolean) = rate(if (known) ai.abhify.android.learn.Srs.Rating.GOOD else ai.abhify.android.learn.Srs.Rating.AGAIN)

    // ------------------------------------------------------------------ v3.9 study dashboard (own study records only)
    data class Dashboard(val cards: CardStats, val suggestions: List<ai.abhify.android.learn.StudyInsights.Suggestion>, val plans: List<ai.abhify.android.learn.StudyInsights.PlanStat>,
                         val quiz: List<ai.abhify.android.learn.StudyInsights.QuizStat>, val sessions: List<ai.abhify.android.learn.StudyInsights.SessionStat>, val recentDocs: List<LibraryDoc>)
    val dashboard = mutableStateOf<Dashboard?>(null)
    /** 5.3: a topic handed over from Study → Discover ("Make a learning path"); Plans fills its topic field with it once. */
    val planTopicPrefill = mutableStateOf<String?>(null)
    fun loadDashboard() = scope.launch {
        dashboard.value = withContext(Dispatchers.IO) { runCatching {
            val now = System.currentTimeMillis()
            val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val decks = g.db.deckStats(now); val plans = g.db.planStats(); val quiz = g.db.quizStats(); val sessions = g.db.quizSessionStats()
            Dashboard(g.db.cardStats(now, dayStart), ai.abhify.android.learn.StudyInsights.suggest(quiz, decks, plans, sessions, now, java.time.LocalDate.now().toString()),
                plans, quiz, sessions.take(5), g.db.docs().take(3))
        }.getOrNull() }
    }
    fun cardsFromTopic(topic: String) { if (topic.isBlank()) return
        vm.aiTask("write flashcards", Studio.SYSTEM, Studio.cards(topic.trim()), null) { r ->
            val cards = Studio.parseCards(r.text)
            if (cards.isEmpty()) show("Flashcards: $topic", r.copy(error = r.error ?: "The AI's answer had no usable Q:/A: cards. Try again."))
            else { io { g.db.addCards(topic.trim(), cards, "topic") }; message.value = "Saved ${cards.size} cards to \"${topic.trim()}\"."; loadDecks() }
        } }
    fun deleteDeck(deck: String) = io { g.db.deleteDeck(deck); loadDecks() }

    // ------------------------------------------------------------------ work: workspaces, notes, tasks
    val workspaces = mutableStateListOf<Workspace>()
    val workspace = mutableStateOf<Workspace?>(null)
    val notes = mutableStateListOf<Note>()
    val tasks = mutableStateListOf<Task>()
    val noteQuery = mutableStateOf("")
    val editing = mutableStateOf<Note?>(null)
    fun loadWork() = scope.launch {
        val ws = withContext(Dispatchers.IO) { g.db.workspaces() }
        workspaces.clear(); workspaces.addAll(ws)
        if (workspace.value == null || ws.none { it.id == workspace.value?.id }) workspace.value = ws.firstOrNull { it.kind == "work" } ?: ws.firstOrNull()
        reloadWorkspace()
    }
    fun select(w: Workspace) { workspace.value = w; reloadWorkspace() }
    fun reloadWorkspace() = scope.launch {
        val w = workspace.value ?: return@launch
        val (n, t, d) = withContext(Dispatchers.IO) { Triple(g.db.notes(w.id, noteQuery.value), g.db.tasks(w.id), g.db.docs(w.id)) }
        notes.clear(); notes.addAll(n); tasks.clear(); tasks.addAll(t); workDocs.clear(); workDocs.addAll(d)
    }
    val workDocs = mutableStateListOf<LibraryDoc>()
    fun addWorkspace(name: String, kind: String) = io { if (name.isNotBlank()) { g.db.addWorkspace(name.trim(), kind); loadWork() } }
    fun renameWorkspace(w: Workspace, name: String) = io { if (name.isNotBlank()) { g.db.renameWorkspace(w.id, name.trim()); loadWork() } }
    fun deleteWorkspace(w: Workspace) = io { g.db.deleteWorkspace(w.id); workspace.value = null; loadWork() }
    fun saveNote(id: Long?, title: String, body: String, kind: String = "note") = io {
        val w = workspace.value ?: return@io
        g.db.saveNote(id, w.id, title.ifBlank { body.lineSequence().firstOrNull()?.take(60) ?: "Note" }, body, kind); editing.value = null; reloadWorkspace()
    }
    fun deleteNote(n: Note) = io { g.db.deleteNote(n.id); reloadWorkspace() }
    fun addTask(title: String) = io { val w = workspace.value ?: return@io; g.db.addTasks(w.id, listOf(title.trim())); reloadWorkspace() }
    fun toggleTask(t: Task) = io { g.db.setTaskDone(t.id, !t.done); reloadWorkspace() }
    fun deleteTask(t: Task) = io { g.db.deleteTask(t.id); reloadWorkspace() }
    /** Only after the user confirmed the list shown to them. */
    fun addTasks(workspaceId: Long, titles: List<String>) = io { g.db.addTasks(workspaceId, titles); reloadWorkspace(); message.value = "Added ${titles.size} tasks." }

    fun meetingNotes(raw: String) { val w = workspace.value ?: return; if (raw.isBlank()) return
        fun body(n: Int) = Studio.MEETING + "\n\n<notes>\n" + raw.take(n) + "\n</notes>"
        vm.aiTask("structure the meeting notes", Studio.SYSTEM, body(3200), "your meeting notes", 3600, body(24_000)) { r ->
            if (r.text.isNotBlank()) io { g.db.saveNote(null, w.id, "Meeting " + java.text.DateFormat.getDateInstance().format(java.util.Date()), r.text, "meeting"); reloadWorkspace() }
            show("Meeting notes", r, actions = Studio.parseActions(r.text.substringAfter("## Action items", "").substringBefore("##")), ws = w.id)
        } }
    fun email(purpose: String, tone: String) { if (purpose.isBlank()) return
        vm.aiTask("draft an email", Studio.SYSTEM, Studio.email(purpose.trim(), tone), "your email request") { r -> show("Email draft", r) } }
    fun report(title: String, from: List<Note>) { if (from.isEmpty()) return
        val material = from.joinToString("\n\n") { "### ${it.title}\n${it.body}" }
        fun body(n: Int) = Studio.report(title.ifBlank { "Report" }) + "\n\n<material>\n" + material.take(n) + "\n</material>"
        vm.aiTask("write the report", Studio.SYSTEM, body(3200), "${from.size} notes", 3600, body(24_000)) { r -> show("Report: ${title.ifBlank { "Report" }}", r) } }
    fun saveResultAsNote(r: Result) = io {
        val w = r.workspaceId?.let { id -> workspaces.firstOrNull { it.id == id } } ?: g.db.workspaces().first { it.kind == "personal" }
        g.db.saveNote(null, w.id, r.title.take(120), r.text + if (r.sources.isNotEmpty()) "\n\nSources:\n" + r.sources.mapIndexed { i, c -> "[S${i + 1}] ${c.where}" }.joinToString("\n") else "", "ai")
        message.value = "Saved to ${w.name} → Notes."; reloadWorkspace()
    }
}
