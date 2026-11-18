package ai.abhify.android.ui

import ai.abhify.android.career.CareerAdvice
import ai.abhify.android.career.DocxWriter
import ai.abhify.android.career.JobFields
import ai.abhify.android.career.JobParser
import ai.abhify.android.career.MatchEngine
import ai.abhify.android.career.ResumeLayout
import ai.abhify.android.career.ResumePdf
import ai.abhify.android.career.ResumeQuality
import ai.abhify.android.career.SectionImprove
import ai.abhify.android.career.SkillTaxonomy
import ai.abhify.android.chat.JobDescription
import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.chat.addVersion
import ai.abhify.android.chat.deleteDoc
import ai.abhify.android.chat.renameDoc
import ai.abhify.android.chat.saveJd
import ai.abhify.android.chat.versions
import ai.abhify.android.docs.DocumentException
import ai.abhify.android.docs.DocumentReader
import ai.abhify.android.docs.OcrNeeded
import ai.abhify.android.learn.Studio
import ai.abhify.android.resume.Resume
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Career Copilot 2.0. Reuses the Resume Assistant (ResumeState: documents, corrections, versions), the job-description
 * and tracker tables, the AI task runner (routing + consent + cancel + one heavy job at a time) and the library's
 * extraction (PDF/DOCX/TXT/MD, OCR when needed). Matching and scores are deterministic and explained; AI only adds
 * text that is labelled as suggestions.
 */
class CareerState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    private val msg get() = vm.studio.message
    private val tax get() = SkillTaxonomy.current

    // ------------------------------------------------------------------ resume profiles
    /** The resume used by default for matching, interview questions and export. Stored per phone. */
    val activeResumeId = mutableStateOf(g.prefs.getLong("active_resume", -1).takeIf { it > 0 })
    fun setActive(d: LibraryDoc) { g.prefs.edit().putLong("active_resume", d.id).apply(); activeResumeId.value = d.id; vm.resume.open(d) }
    fun active(): LibraryDoc? = vm.resume.resumes.firstOrNull { it.id == activeResumeId.value } ?: vm.resume.resumes.firstOrNull()
    /** Ensures the Resume Assistant has the active resume open (its text, versions, details). */
    fun ensureOpen() { val a = active() ?: return; if (vm.resume.selected.value?.id != a.id) vm.resume.open(a) }
    fun rename(d: LibraryDoc, name: String) = scope.launch { if (name.isNotBlank()) { withContext(Dispatchers.IO) { g.db.renameDoc(d.id, name.trim()) }; vm.resume.load() } }
    fun delete(d: LibraryDoc) = scope.launch {
        withContext(Dispatchers.IO) { g.db.deleteDoc(d.id) }
        if (activeResumeId.value == d.id) { g.prefs.edit().remove("active_resume").apply(); activeResumeId.value = null }
        if (vm.resume.selected.value?.id == d.id) vm.resume.open(null)
        vm.resume.load(); msg.value = "Resume deleted from this phone (with its versions)."
    }

    /** Manual corrections of extracted details, stored as a resume version ("profile"); the original stays. */
    val FIELDS = listOf("name" to "Name", "summary" to "Professional summary", "skills" to "Skills", "experience" to "Work experience",
        "projects" to "Projects", "education" to "Education", "certifications" to "Certifications", "achievements" to "Achievements")
    fun profileEdits(): Map<String, String> = vm.resume.versions.firstOrNull { it.kind == PROFILE }?.body?.let { b ->
        runCatching { val o = JSONObject(b); FIELDS.map { it.first }.filter { o.has(it) }.associateWith { o.getString(it) } }.getOrNull() } ?: emptyMap()
    fun saveProfile(values: Map<String, String>) = scope.launch {
        val d = vm.resume.selected.value ?: return@launch
        val o = JSONObject(); values.filterValues { it.isNotBlank() }.forEach { (k, v) -> o.put(k, v.trim()) }
        withContext(Dispatchers.IO) { g.db.addVersion(d.id, PROFILE, "Edited details", o.toString(), "you") }
        vm.resume.open(d); msg.value = "Details saved as your correction; the extracted original is kept."
    }

    private val SECTION_OF = mapOf("summary" to Resume.Section.SUMMARY, "skills" to Resume.Section.SKILLS, "experience" to Resume.Section.EXPERIENCE,
        "projects" to Resume.Section.PROJECTS, "education" to Resume.Section.EDUCATION, "certifications" to Resume.Section.CERTIFICATIONS,
        "achievements" to Resume.Section.ACHIEVEMENTS)
    /** The text the analysis uses: the latest correction (or original) with the user's edited sections applied. */
    fun effectiveText(): String {
        var t = vm.resume.working
        profileEdits().forEach { (k, v) -> SECTION_OF[k]?.let { t = SectionImprove.replaceSection(t, it, v) } }
        profileEdits()["name"]?.let { n -> if (!t.trimStart().startsWith(n)) t = n + "\n" + t }
        return t
    }

    // ------------------------------------------------------------------ quality
    val quality = mutableStateOf<ResumeQuality.Report?>(null)
    fun analyzeQuality() { if (vm.resume.selected.value == null) { msg.value = "Choose a resume first."; return }; quality.value = ResumeQuality.score(effectiveText()) }

    // ------------------------------------------------------------------ job descriptions
    val selectedJd = mutableStateOf<JobDescription?>(null)
    val jdQuery = mutableStateOf("")
    fun fieldsOf(j: JobDescription): JobFields = JobFields.fromJson(j.fields) ?: tax?.let { JobParser.parse(j.body, it) } ?: JobFields()
    fun visibleJds() = vm.life.jds.filter { j -> jdQuery.value.isBlank() || listOfNotNull(j.title, j.company, j.body).any { it.contains(jdQuery.value.trim(), true) } }
    /** Saves the user's corrected fields with the description (the original text is kept unchanged). */
    fun saveFields(j: JobDescription, f: JobFields) = scope.launch {
        withContext(Dispatchers.IO) { g.db.saveJd(j.id, f.title.ifBlank { j.title }, f.company.ifBlank { j.company }, j.body, f.toJson()) }
        vm.life.loadCareer(); msg.value = "Job details saved."
    }
    val importingJd = mutableStateOf(false)
    /** Reads a PDF/DOCX/TXT/MD (OCR if needed, asked first) into the job description form; nothing is uploaded. */
    fun importJd(uri: Uri, ocr: Boolean? = null) {
        importingJd.value = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { DocumentReader.read(g, uri, ocr) {} }
                val f = tax?.let { JobParser.parse(r.doc.text, it) } ?: JobFields()
                vm.life.editingJd.value = JobDescription(0, f.title.ifBlank { r.doc.name.substringBeforeLast('.') }, f.company.ifBlank { null }, r.doc.text, 0, f.toJson())
                if (r.warnings.isNotEmpty()) msg.value = "Imported with notes: " + r.warnings.first()
            } catch (e: OcrNeeded) { vm.ocrAsk.value = AppViewModel.OcrAsk(e.name, e.pages) { allow -> importJd(uri, allow) } }
            catch (e: DocumentException) { msg.value = e.message }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { msg.value = "Couldn't read the file: ${e.message}" }
            finally { importingJd.value = false }
        }
    }

    // ------------------------------------------------------------------ matching and advice
    val match = mutableStateOf<MatchEngine.Result?>(null)
    val advice = mutableStateOf<List<CareerAdvice.Rec>>(emptyList())
    val priorities = mutableStateOf<List<String>>(emptyList())
    val aiIdeas = mutableStateOf<StudioState.Result?>(null)
    fun runMatch() {
        val j = selectedJd.value ?: run { msg.value = "Choose a job description."; return }
        if (vm.resume.selected.value == null) { msg.value = "Choose a resume."; return }
        val t = tax ?: run { msg.value = "The skill list couldn't be loaded."; return }
        val f = fieldsOf(j); val text = effectiveText()
        val m = MatchEngine.match(text, f, t)
        match.value = m; advice.value = CareerAdvice.of(m, f, ResumeQuality.score(text)); priorities.value = CareerAdvice.learningPriorities(m, j.body)
        aiIdeas.value = null
    }
    /** Optional AI suggestions (project ideas, learning plan), clearly labelled; never added to the resume. */
    fun suggestProjects() {
        val m = match.value ?: return; val j = selectedJd.value ?: return
        val prompt = "${Resume.RULES}\n\nThe candidate is applying for \"${j.title}\". Skills the job requires that the resume doesn't show: ${m.skills.missing.joinToString().ifBlank { "none" }}. " +
            "Skills shown: ${m.skills.matched.joinToString()}.\nSuggest 3 small practice projects the candidate COULD build to learn the missing skills " +
            "(label each \"Suggested project – not done yet\"), and a 4-week learning order. Do not claim the candidate has done anything new."
        vm.aiTask("suggest learning projects", Studio.SYSTEM, prompt, null) { r ->
            aiIdeas.value = StudioState.Result("AI suggestions (not part of your resume)", r.text, r.source, r.status, r.error)
        }
    }

    // ------------------------------------------------------------------ section improvement
    data class Improvement(val section: Resume.Section, val original: String, val improved: String, val changes: String, val unsupported: List<String>, val source: String?)
    val improvement = mutableStateOf<Improvement?>(null)
    val IMPROVABLE = listOf(Resume.Section.SUMMARY, Resume.Section.SKILLS, Resume.Section.EXPERIENCE, Resume.Section.PROJECTS, Resume.Section.ACHIEVEMENTS)
    fun sectionText(s: Resume.Section): String = Resume.parse(effectiveText()).sections[s].orEmpty()

    fun improve(s: Resume.Section, extraFacts: String) {
        val original = sectionText(s)
        if (original.isBlank()) { msg.value = "Your resume has no ${s.label} section to improve. Add it in Details first."; return }
        val budget = g.memoryProfile().first.localDocChars
        vm.aiTask("improve the ${s.label.lowercase()} section", Studio.SYSTEM, SectionImprove.prompt(s.label, original.take(budget), extraFacts), "this resume section",
            budget + 800, SectionImprove.prompt(s.label, original, extraFacts)) { r ->
            val parsed = SectionImprove.parse(r.text)
            when {
                r.status != null || r.error != null -> msg.value = r.error ?: "The result wasn't complete. Try again."
                parsed == null -> msg.value = "The AI didn't use the expected format (## Improved / ## Changes). Nothing was changed. Try again."
                else -> improvement.value = Improvement(s, original, parsed.first, parsed.second,
                    Resume.unsupported(parsed.first, effectiveText(), extraFacts), r.source?.label)
            }
        }
    }
    /** Accepting creates a new resume version with this section replaced; the original and earlier versions stay. */
    fun accept(edited: String) = scope.launch {
        val imp = improvement.value ?: return@launch; val d = vm.resume.selected.value ?: return@launch
        val full = SectionImprove.replaceSection(effectiveText(), imp.section, edited)
        withContext(Dispatchers.IO) { g.db.addVersion(d.id, ACCEPTED, "Improved resume (${imp.section.label})", full, imp.source) }
        improvement.value = null; vm.resume.open(d); msg.value = "Saved as a new version \"Improved resume\". Your original is unchanged."
    }
    fun reject() { improvement.value = null }

    // ------------------------------------------------------------------ export
    val exportTemplate = mutableStateOf(ResumeLayout.Template.SIMPLE)
    /** Export choices: the working text, plus every accepted improvement or draft version. */
    fun exportSources(): List<Pair<String, String>> = listOf("Current resume (with your corrections)" to effectiveText()) +
        vm.resume.versions.filter { it.kind == ACCEPTED || it.kind == "draft" }.map { "${it.title} · ${ago(it.createdAt)}" to it.body }

    fun blocks(text: String): List<ResumeLayout.Block> {
        val b = ResumeLayout.blocks(text)
        val name = profileEdits()["name"] ?: return b
        return if (b.firstOrNull() is ResumeLayout.Block.Title) listOf(ResumeLayout.Block.Title(name)) + b.drop(1) else listOf(ResumeLayout.Block.Title(name)) + b
    }
    suspend fun render(text: String, pdf: Boolean): ByteArray = withContext(Dispatchers.Default) {
        val bl = blocks(text); if (pdf) ResumePdf.write(bl, exportTemplate.value) else DocxWriter.write(bl, exportTemplate.value)
    }
    /** Writes the export to the app's cache (for sharing through FileProvider). Returns the file only if it was really written. */
    suspend fun cacheFile(text: String, pdf: Boolean): File = withContext(Dispatchers.IO) {
        val dir = File(g.context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }       // only our own previous exports
        val name = (vm.resume.selected.value?.name?.substringBeforeLast('.') ?: "resume").replace(Regex("[^\\w.-]+"), "_") + "-abhify." + if (pdf) "pdf" else "docx"
        File(dir, name).also { f -> f.writeBytes(render(text, pdf)); if (f.length() == 0L) throw IllegalStateException("the file is empty") }
    }

    companion object { const val PROFILE = "profile"; const val ACCEPTED = "accepted" }
}
