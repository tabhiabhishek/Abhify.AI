package ai.abhify.android.ui

import ai.abhify.android.ai.Source
import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.chat.ResumeVersion
import ai.abhify.android.chat.addVersion
import ai.abhify.android.chat.deleteVersion
import ai.abhify.android.chat.doc
import ai.abhify.android.chat.docText
import ai.abhify.android.chat.docs
import ai.abhify.android.chat.resumes
import ai.abhify.android.chat.setRole
import ai.abhify.android.chat.versions
import ai.abhify.android.docs.Library
import ai.abhify.android.resume.Resume
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resume Assistant: resumes are ordinary library documents marked role='resume' (one storage, one extraction path).
 * The extracted original is never changed; corrections and AI outputs are saved as separate versions.
 */
class ResumeState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope

    val resumes = mutableStateListOf<LibraryDoc>()
    val selected = mutableStateOf<LibraryDoc?>(null)
    val original = mutableStateOf("")
    val versions = mutableStateListOf<ResumeVersion>()
    val profile = mutableStateOf<Resume.Profile?>(null)
    val jd = mutableStateOf("")
    val match = mutableStateOf<Resume.Match?>(null)

    /** A finished AI output, with every fact in it that the resume doesn't contain. */
    data class Output(val kind: String, val title: String, val text: String, val source: Source?, val status: String?, val error: String?, val unsupported: List<String>)
    val output = mutableStateOf<Output?>(null)

    /** The text analysis uses: the user's latest correction, else the extracted original. */
    val working: String get() = versions.firstOrNull { it.kind == CORRECTED }?.body ?: original.value
    val corrected get() = versions.any { it.kind == CORRECTED }

    fun load() = scope.launch { val r = withContext(Dispatchers.IO) { g.db.resumes() }; resumes.clear(); resumes.addAll(r) }

    fun open(d: LibraryDoc?) = scope.launch {
        selected.value = d; output.value = null; match.value = null; versions.clear()
        if (d == null) { original.value = ""; profile.value = null; return@launch }
        val (t, v) = withContext(Dispatchers.IO) { Library.readable(g.db.docText(d.id).orEmpty()) to g.db.versions(d.id) }
        original.value = t; versions.addAll(v)
        reparse()
    }

    private fun reparse() { profile.value = Resume.parse(working, selected.value?.warnings.orEmpty()) }

    /** New resume file → the library (marked as a resume) → opened. OCR permission is asked by the shared flow. */
    fun import(uri: Uri) = vm.studio.importDoc(uri, role = ROLE) { id -> scope.launch { load(); withContext(Dispatchers.IO) { g.db.doc(id) }?.let { open(it) } } }

    fun libraryCandidates(): List<LibraryDoc> = g.db.docs().filter { it.role != ROLE }
    fun useExisting(d: LibraryDoc) = scope.launch { withContext(Dispatchers.IO) { g.db.setRole(d.id, ROLE) }; load(); open(d.copy(role = ROLE)) }
    fun unmark(d: LibraryDoc) = scope.launch { withContext(Dispatchers.IO) { g.db.setRole(d.id, null) }; if (selected.value?.id == d.id) open(null); load() }

    /** Saves the user's corrected text as a version (the extracted original stays as it was). */
    fun saveCorrection(text: String) = scope.launch {
        val d = selected.value ?: return@launch
        if (text.isBlank() || text == working) return@launch
        withContext(Dispatchers.IO) { g.db.addVersion(d.id, CORRECTED, "Corrected text", text, "you") }
        versions.clear(); versions.addAll(withContext(Dispatchers.IO) { g.db.versions(d.id) }); reparse()
        vm.studio.message.value = "Correction saved. Analysis now uses your corrected text; the original extraction is kept."
    }

    fun compareJd() { match.value = if (jd.value.isBlank()) null else Resume.match(working, jd.value) }

    enum class Action(val label: String, val kind: String) {
        SUMMARY("Professional summary", "summary"), SUGGESTIONS("Improvement suggestions", "suggestions"),
        QUESTIONS("Interview questions", "questions"), DRAFT("Improved draft", "draft"), JOB("Compare with job description", "job"),
    }

    fun run(a: Action) {
        val d = selected.value ?: return
        val text = working
        val m = if (a == Action.JOB) Resume.match(text, jd.value).also { match.value = it } else null
        if (a == Action.JOB && jd.value.isBlank()) { vm.studio.message.value = "Paste a job description first."; return }
        fun prompt(r: String) = when (a) {
            Action.SUMMARY -> Resume.summaryPrompt(r); Action.SUGGESTIONS -> Resume.suggestionsPrompt(r); Action.QUESTIONS -> Resume.questionsPrompt(r)
            Action.DRAFT -> Resume.draftPrompt(r); Action.JOB -> Resume.jdPrompt(r, jd.value.take(6000), m!!)
        }
        val localBudget = g.memoryProfile().first.localDocChars
        vm.aiTask(a.label.lowercase(), Resume.SYSTEM, prompt(text.take(localBudget)), "your resume" + if (a == Action.JOB) " and the job description" else "",
            localBudget + 1500, prompt(text.take(24_000))) { r ->
            // facts in the output that are not in the resume (the job description counts only for the comparison)
            val flags = if (r.text.isBlank()) emptyList() else Resume.unsupported(r.text, text, if (a == Action.JOB || a == Action.QUESTIONS) jd.value else "")
            output.value = Output(a.kind, a.label, r.text, r.source, r.status, r.error, flags)
            if (r.text.isNotBlank() && r.status == null) scope.launch {
                withContext(Dispatchers.IO) { g.db.addVersion(d.id, a.kind, a.label, r.text, r.source?.label) }
                versions.clear(); versions.addAll(withContext(Dispatchers.IO) { g.db.versions(d.id) })
            }
        }
    }

    fun deleteVersion(v: ResumeVersion) = scope.launch {
        withContext(Dispatchers.IO) { g.db.deleteVersion(v.id) }; versions.remove(v); if (v.kind == CORRECTED) reparse()
    }

    /** A Markdown report of the parsed details (rule-based, no AI) for export. */
    fun profileReport(): String = profile.value?.let { p ->
        buildString {
            appendLine("# Resume details: ${selected.value?.name}")
            appendLine("Extracted on this phone by rules (no AI). ⚠ = from text that OCR marked unclear: please verify.\n")
            fun f(l: String, v: List<Resume.Field>) = appendLine("- **$l:** " + v.joinToString { it.value + if (it.unclear) " ⚠" else "" }.ifBlank { "[missing]" })
            f("Name", listOfNotNull(p.name)); f("Email", p.emails); f("Phone", p.phones); f("Links", p.links)
            appendLine("- **Skills found:** " + p.skills.joinToString().ifBlank { "[missing]" })
            appendLine("- **Dates:** " + p.dates.joinToString { it.value + if (it.unclear) " ⚠" else "" }.ifBlank { "[none found]" })
            if (p.missing.isNotEmpty()) appendLine("- **Missing:** " + p.missing.joinToString())
            p.sections.forEach { (s, body) -> appendLine("\n## ${s.label}\n$body") }
            if (p.achievements.isNotEmpty()) appendLine("\n## Lines with measurable results\n" + p.achievements.joinToString("\n") { "- $it" })
        }
    } ?: ""

    companion object { const val ROLE = "resume"; const val CORRECTED = "corrected" }
}
