package ai.abhify.android.ui

import ai.abhify.android.chat.docText
import ai.abhify.android.career.JobFields
import ai.abhify.android.career.ResumeLayout
import ai.abhify.android.chat.JobDescription
import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.life.Tracker
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

@Composable
private fun CPage(content: @Composable ColumnScope.() -> Unit) = PageBody(content = content)
@Composable private fun Note(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
@Composable private fun Chips(items: List<String>, sel: Int, onPick: (Int) -> Unit) = SectionChips(items, sel, onPick = onPick)

// ====================================================================== tab
/** Career sections, in the owner's order: Jobs and Match last. Interview practice lives in Question Bank → Practise. */
enum class CareerSection(val label: String, val glyph: String) {
    DASHBOARD("Dashboard", "🏠"), RESUMES("Resumes", "📄"), QUESTIONS("Question Bank", "❔"), MCQS("MCQs", "✅"), JOBS("Jobs", "💼"), MATCH("Match", "🔀")
}

@Composable
fun CareerTab(vm: AppViewModel, withMic: (() -> Unit) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var bankMode by rememberSaveable { mutableIntStateOf(0) }                 // Question Bank: 0 Bank · 1 Generate · 2 Practise
    LaunchedEffect(Unit) { vm.life.loadCareer(); vm.resume.load() }
    // back from a section returns to the Dashboard first, then leaves Career as before
    androidx.activity.compose.BackHandler(enabled = section != 0) { section = 0 }
    fun go(s: CareerSection, mode: Int? = null) { section = s.ordinal; mode?.let { bankMode = it } }
    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(CareerSection.entries.map { it.label }, section,
            Modifier.padding(horizontal = Dimens.gutter, vertical = Dimens.tiny), CareerSection.entries.map { it.glyph }, Accent.CAREER) { section = it }
        Box(Modifier.weight(1f)) {
            when (CareerSection.entries[section]) {
                CareerSection.DASHBOARD -> DashboardView(vm, ::go)
                CareerSection.RESUMES -> ResumesView(vm)
                CareerSection.QUESTIONS -> QuestionBankView(vm, withMic, bankMode) { bankMode = it }
                CareerSection.MCQS -> McqView(vm)
                CareerSection.JOBS -> JobsView(vm) { j -> vm.career.selectedJd.value = j; vm.career.match.value = null; section = CareerSection.MATCH.ordinal }
                CareerSection.MATCH -> MatchView(vm)
            }
        }
    }
}

/** Question Bank (Bank · Generate · Practise): one screen, shown in Career and in Study → Questions (5.3). */
@Composable
fun QuestionBankView(vm: AppViewModel, withMic: (() -> Unit) -> Unit, mode: Int, onMode: (Int) -> Unit) = Column(Modifier.fillMaxSize()) {
    SegmentedTabs(listOf("Bank", "Generate", "Practise"), mode, Modifier.padding(horizontal = Dimens.gutter), listOf("📚", "✨", "🎤"), Accent.CHAT) { onMode(it) }
    Box(Modifier.weight(1f)) { when (mode) { 0 -> InterviewList(vm); 1 -> QuestionGenView(vm); else -> InterviewView(vm, withMic) } }
}

// ====================================================================== dashboard
@Composable
private fun DashboardView(vm: AppViewModel, go: (CareerSection, Int?) -> Unit) = CPage {
    val today = remember { java.time.LocalDate.now() }
    val s = Tracker.stats(vm.life.apps, today)
    // 4.5.1: header, six stat tiles (from the tracker on this phone), alerts, then the three next steps as tiles
    PageHeader("Career", "Applications, resumes and interview practice", Accent.CAREER, "💼")
    listOf(listOf("Applications" to s.total, "In progress" to s.inProgress, "Interviews" to s.interviews),
        listOf("Offers" to s.offers, "Rejections" to s.rejections, "Follow-ups (7 days)" to s.upcomingFollowUps)).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (k, v) -> StatTile("$v", k, Modifier.weight(1f), accent = Accent.CAREER) } }
    }
    Note("Counted from your tracker on this phone.")
    Tracker.overdue(vm.life.apps, today).takeIf { it.isNotEmpty() }?.let { od ->
        ErrorBanner("${od.size} overdue follow-up(s): " + od.take(3).joinToString(" · ") { "${it.company} (${it.followUpOn})" })
        TextButton({ go(CareerSection.JOBS, null) }) { Text("Open Jobs") }
    }
    BentoCard("📄", "Active resume", action = "Change", accent = Accent.CAREER, onClick = { go(CareerSection.RESUMES, null) }) {
        val active = vm.career.active()
        Text(active?.name ?: "None yet: add one in Resumes.", style = MaterialTheme.typography.titleSmall)
        if (active != null) {
            var skills by remember(active.id) { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(active.id) { skills = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ai.abhify.android.resume.Resume.parse(vm.g.db.docText(active.id).orEmpty()).skills } }
            Text(if (skills.isEmpty()) "No known skills found in its text." else "Skills found in it: " + skills.take(10).joinToString(), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
    }
    // quick actions (existing sections; Jobs and Match are last in the bar too)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("✨", "Interview questions", Modifier.weight(1f), accent = Accent.CHAT) { go(CareerSection.QUESTIONS, 1) }
        ActionTile("✅", "MCQ practice", Modifier.weight(1f), accent = Accent.STUDY) { go(CareerSection.MCQS, null) }
        ActionTile("🎤", "Practise answers", Modifier.weight(1f), accent = Accent.CAREER) { go(CareerSection.QUESTIONS, 2) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("📚", "Question Bank", Modifier.weight(1f), accent = Accent.DOCS) { go(CareerSection.QUESTIONS, 0) }
        ActionTile("💼", "Jobs", Modifier.weight(1f), accent = Accent.KNOWLEDGE) { go(CareerSection.JOBS, null) }
        ActionTile("🔀", "Match a job", Modifier.weight(1f), accent = Accent.CAREER) { go(CareerSection.MATCH, null) }
    }
    // MCQ practice progress: real answers from career_mcq.json (never estimated)
    val m = vm.mcq; val all = m.overallStats()
    if (all.attempted > 0) Glass(onClick = { go(CareerSection.MCQS, null) }) {
        Text("MCQ practice (all time)", style = MaterialTheme.typography.titleSmall)
        Text("${all.attempted} answered · ${all.correct} correct · ${all.wrong} wrong · accuracy ${all.accuracy}%", style = MaterialTheme.typography.bodyMedium)
        m.topics().take(5).forEach { (t, s) -> Text("$t: ${s.correct}/${s.attempted} correct", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
    }
    // v3.7: practice progress from saved attempts (AI estimates, 1–5)
    LaunchedEffect(Unit) { vm.life.loadAttempts(); vm.memory.load() }
    val topics = ai.abhify.android.career.PracticeStats.byTopic(vm.life.attempts)
    if (topics.isNotEmpty()) Glass(onClick = { go(CareerSection.QUESTIONS, 2) }) {
        Text("Interview practice", style = MaterialTheme.typography.titleSmall)
        topics.take(6).forEach { t -> Text("${t.name}: ${t.attempts} answer(s)" + (t.average?.let { " · average %.1f/5".format(it) } ?: " · not scored") +
            (t.trend?.let { d -> if (d > 0.2) " · improving ↑" else if (d < -0.2) " · lower lately ↓" else " · steady" } ?: ""), style = MaterialTheme.typography.bodySmall) }
        val jobs = ai.abhify.android.career.PracticeStats.byJob(vm.life.attempts, vm.life.jds.associate { it.id to it.title })
        jobs.take(3).forEach { j -> Text("For \"${j.name}\": ${j.attempts} answer(s)" + (j.average?.let { " · %.1f/5".format(it) } ?: ""), style = MaterialTheme.typography.bodySmall) }
        Note("Scores are the AI's estimate of your practice answers, not a hiring prediction.")
    }
    // v3.7: career goals live in Personal memory (one place); shown here read-only
    val goals = vm.memory.items.filter { it.category == "Career goals" && !it.expired() }
    Glass(onClick = { vm.memory.category.value = "Career goals"; vm.go(AppViewModel.Route.MEMORIES) }) {
        Text("Career goals", style = MaterialTheme.typography.titleSmall)
        if (goals.isEmpty()) Note("None yet. Add one in Personal memory with the category \"Career goals\" (tap here).")
        goals.take(5).forEach { Text("• ${it.content}", style = MaterialTheme.typography.bodySmall) }
    }
}

// ====================================================================== resumes
@Composable
private fun ResumesView(vm: AppViewModel) {
    val c = vm.career; val r = vm.resume
    var tool by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<LibraryDoc?>(null) }
    var deleting by remember { mutableStateOf<LibraryDoc?>(null) }
    LaunchedEffect(r.resumes.size) { c.ensureOpen() }
    CPage {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("＋ Add or import a resume", { vm.go(AppViewModel.Route.RESUME); r.open(null) }, Modifier.weight(1f))
        }
        if (r.resumes.isEmpty()) Note("No resumes yet. Upload one (PDF, DOCX, TXT, MD or a photo) in the Resume Assistant.")
        r.resumes.forEach { d ->
            val isActive = c.active()?.id == d.id
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(isActive, { c.setActive(d) }, Modifier.semantics { contentDescription = "Make ${d.name} the active resume" })
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${d.kind} · ${"%,d".format(d.chars)} characters" + (if (d.ocrPages > 0) " · OCR" else "") + if (isActive) " · active" else "",
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ r.open(d); vm.go(AppViewModel.Route.RESUME) }) { Text("Open (text, versions)") }
                    TextButton({ renaming = d }) { Text("Rename") }
                    TextButton({ deleting = d }) { Text("Delete", color = LocalExtra.current.bad) }
                }
            }
        }
        if (r.selected.value != null) {
            Text("Tools for: ${r.selected.value?.name}", style = MaterialTheme.typography.titleMedium)
            Chips(listOf("Quality", "Edit details", "Improve a section", "Export"), tool) { tool = it }
            when (tool) { 0 -> QualityView(vm); 1 -> DetailsEditor(vm); 2 -> ImproveView(vm); else -> ExportView(vm) }
        }
    }
    renaming?.let { d -> var name by remember(d.id) { mutableStateOf(d.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename resume") },
            text = { OutlinedTextField(name, { name = it.take(200) }, singleLine = true, label = { Text("Name (e.g. Data Analyst – 2026)") }) },
            confirmButton = { TextButton({ c.rename(d, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } }) }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${d.name}\"?") },
        text = { DialogText("The resume, its extracted text and all its saved versions are removed from this phone. Applications that used it keep their other details.") },
        confirmButton = { TextButton({ c.delete(d); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun QualityView(vm: AppViewModel) {
    val c = vm.career
    LaunchedEffect(vm.resume.selected.value?.id, vm.resume.versions.size) { c.analyzeQuality() }
    val q = c.quality.value ?: return
    Glass {
        Text("Resume quality: about ${q.total} / 100", style = MaterialTheme.typography.titleLarge)
        Note("Rule-based and explained below. It checks completeness and presentation only; it does not predict hiring.")
        q.categories.forEach { cat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cat.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${cat.points}/${cat.max}", color = when { cat.ratio >= 0.8 -> LocalExtra.current.ok; cat.ratio < 0.5 -> LocalExtra.current.warn; else -> MaterialTheme.colorScheme.onSurface })
            }
            LinearProgressIndicator({ cat.ratio.toFloat() }, Modifier.fillMaxWidth())
            cat.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (q.strengths.isNotEmpty()) Glass { Text("Strengths", style = MaterialTheme.typography.titleSmall); q.strengths.forEach { Text("✓ $it") } }
    if (q.suggestions.isNotEmpty()) Glass { Text("Suggestions", style = MaterialTheme.typography.titleSmall); q.suggestions.forEach { Text("→ $it") }
        Note("Add only true information. Use \"Improve a section\" for a rewrite you review first.") }
}

@Composable
private fun DetailsEditor(vm: AppViewModel) {
    val c = vm.career
    val p = vm.resume.profile.value
    val edits = remember(vm.resume.versions.size) { c.profileEdits() }
    val extracted = remember(vm.resume.versions.size) { c.FIELDS.associate { (k, _) -> k to when (k) {
        "name" -> p?.name?.value.orEmpty(); "skills" -> p?.skills?.joinToString(", ").orEmpty()
        "achievements" -> p?.achievements?.joinToString("\n").orEmpty()
        else -> p?.sections?.entries?.firstOrNull { it.key.name.lowercase() == k }?.value.orEmpty()
    } } }
    val values = remember(vm.resume.versions.size) { mutableStateMapOf<String, String>().apply { c.FIELDS.forEach { (k, _) -> put(k, edits[k] ?: extracted[k].orEmpty()) } } }
    SettingsGroup("Extracted details (edit to correct)") {
        Note("Empty = not found in your resume (shown as unavailable, never guessed). Saving keeps the extracted original as it was.")
        c.FIELDS.forEach { (k, label) ->
            OutlinedTextField(values[k].orEmpty(), { values[k] = it }, Modifier.fillMaxWidth(), label = { Text(label + if (values[k].isNullOrBlank()) " (unavailable)" else "") },
                minLines = if (k == "name") 1 else 2, maxLines = if (k == "name") 1 else 10)
        }
        p?.dates?.takeIf { it.isNotEmpty() }?.let { Note("Employment dates found: " + it.joinToString { d -> d.value + if (d.unclear) " ⚠" else "" }) }
        Button({ c.saveProfile(values.toMap()) }) { Text("Save details") }
    }
}

@Composable
private fun ImproveView(vm: AppViewModel) {
    val c = vm.career
    var sec by rememberSaveable { mutableIntStateOf(0) }
    var facts by rememberSaveable { mutableStateOf("") }
    val imp = c.improvement.value
    if (imp == null) SettingsGroup("Improve one section") {
        Chips(c.IMPROVABLE.map { it.label }, sec) { sec = it }
        val original = c.sectionText(c.IMPROVABLE[sec])
        Text(original.ifBlank { "(This section isn't in your resume.)" }, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(facts, { facts = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("Extra true facts to use (optional)") }, minLines = 2)
        GradientButton("Suggest an improved version", { c.improve(c.IMPROVABLE[sec], facts) }, Modifier.fillMaxWidth(), enabled = original.isNotBlank() && vm.taskBusy.value == null)
        Note("Nothing changes until you accept. The AI is told to keep every fact and to ask ([ask: …]) instead of inventing numbers.")
    } else {
        var edited by remember(imp) { mutableStateOf(imp.improved) }
        Glass {
            Text("${imp.section.label}: original", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(imp.original, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted) }
        }
        SettingsGroup("Suggested (edit freely)") {
            if (imp.unsupported.isNotEmpty()) Text("⚠ Not found in your resume: ${imp.unsupported.joinToString()}. Remove them unless they're true.",
                color = LocalExtra.current.warn, fontWeight = FontWeight.SemiBold)
            if (edited.contains("[ask:")) Text("Replace each [ask: …] with your real detail or delete it before accepting.", color = LocalExtra.current.warn)
            OutlinedTextField(edited, { edited = it }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 20)
            if (imp.changes.isNotBlank()) { Text("What changed", style = MaterialTheme.typography.labelLarge); MarkdownText(imp.changes) }
            imp.source?.let { Pill(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ c.accept(edited) }, enabled = edited.isNotBlank() && !edited.contains("[ask:")) { Text("Accept as new version") }
                TextButton(c::reject) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun ExportView(vm: AppViewModel) {
    val c = vm.career
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember(vm.resume.versions.size) { c.exportSources() }
    var srcIdx by rememberSaveable { mutableIntStateOf(0) }
    val text = sources.getOrNull(srcIdx)?.second.orEmpty()
    var pendingPdf by remember { mutableStateOf<Boolean?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val pdf = pendingPdf; pendingPdf = null
        if (uri != null && pdf != null) scope.launch {
            vm.studio.message.value = try {
                val bytes = c.render(text, pdf)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("couldn't open the file") }
                "Saved ${if (pdf) "PDF" else "DOCX"} (${"%,d".format(bytes.size)} bytes). Your original resume is unchanged."
            } catch (e: Exception) { "Export failed: ${e.message}. Try another location or format." }
        }
    }
    fun share(pdf: Boolean) = scope.launch {
        try {
            val f = c.cacheFile(text, pdf)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (pdf) "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share resume"))
        } catch (e: Exception) { vm.studio.message.value = "Couldn't prepare the file: ${e.message}" }
    }
    SettingsGroup("Export") {
        Text("Content", style = MaterialTheme.typography.labelLarge)
        Chips(sources.map { it.first.take(40) }, srcIdx) { srcIdx = it }
        Text("Template", style = MaterialTheme.typography.labelLarge)
        Chips(ResumeLayout.Template.entries.map { it.label }, c.exportTemplate.value.ordinal) { c.exportTemplate.value = ResumeLayout.Template.entries[it] }
        Text("Preview", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(ResumeLayout.preview(c.blocks(text)), style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ pendingPdf = true; saver.launch((vm.resume.selected.value?.name?.substringBeforeLast('.') ?: "resume") + "-abhify.pdf") }, enabled = text.isNotBlank()) { Text("Save PDF") }
            Button({ pendingPdf = false; saver.launch((vm.resume.selected.value?.name?.substringBeforeLast('.') ?: "resume") + "-abhify.docx") }, enabled = text.isNotBlank()) { Text("Save DOCX") }
            OutlinedButton({ share(true) }, enabled = text.isNotBlank()) { Text("Share PDF") }
            OutlinedButton({ share(false) }, enabled = text.isNotBlank()) { Text("Share DOCX") }
        }
        Note("A new file is written; the uploaded original is never overwritten. Only the resume text is included (no app data, IDs or keys).")
    }
}

// ====================================================================== match
@Composable
private fun MatchView(vm: AppViewModel) {
    val c = vm.career; val l = vm.life
    LaunchedEffect(Unit) { c.ensureOpen() }
    CPage {
        SettingsGroup("Resume vs job description") {
            Text("Resume", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.resume.resumes.forEach { d -> FilterChip(c.active()?.id == d.id, { c.setActive(d); c.match.value = null }, { Text(d.name.take(28)) }) }
            }
            if (vm.resume.resumes.isEmpty()) Note("Add a resume first (Resumes).")
            Text("Job description", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                l.jds.forEach { j -> FilterChip(c.selectedJd.value?.id == j.id, { c.selectedJd.value = j; c.match.value = null }, { Text(j.title.take(28)) }) }
            }
            if (l.jds.isEmpty()) Note("Save a job description first (Jobs).")
            GradientButton("Compare", c::runMatch, Modifier.fillMaxWidth(), enabled = c.selectedJd.value != null && vm.resume.selected.value != null)
            Note("Matching runs on this phone with a skills list and fixed rules; nothing is sent anywhere.")
        }
        val m = c.match.value ?: return@CPage
        Glass {
            Text(m.overall?.let { "Overall match: about $it%" } ?: "Not enough information to score", style = MaterialTheme.typography.titleLarge)
            Note("An estimate of how well the resume TEXT covers the job's listed requirements (rounded to 5). It is not a hiring prediction.")
            m.dimensions.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.name} (weight ${d.weight}%)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(d.score?.let { "~$it%" } ?: "not assessed", color = if (d.score == null) LocalExtra.current.muted else MaterialTheme.colorScheme.onSurface)
                }
                Text(d.explanation, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
        }
        SettingsGroup("Skills") {
            Text("Required: ${m.skills.required.joinToString().ifBlank { "none recognized" }}" + (m.skills.coverage?.let { " · coverage ~$it%" } ?: ""))
            if (m.skills.direct.isNotEmpty()) Text("✓ Direct matches: " + m.skills.direct.joinToString { it.skill }, color = LocalExtra.current.ok)
            if (m.skills.alias.isNotEmpty()) Text("✓ Matches under another name: " + m.skills.alias.joinToString { "${it.skill} (\"${it.term}\")" }, color = LocalExtra.current.ok)
            if (m.skills.related.isNotEmpty()) Text("≈ Related only (not counted): " + m.skills.related.joinToString { "${it.second} → ${it.first}" }, color = LocalExtra.current.warn)
            if (m.skills.missing.isNotEmpty()) Text("✗ No evidence for: " + m.skills.missing.joinToString(), color = LocalExtra.current.bad)
            if (m.preferred.required.isNotEmpty()) Text("Preferred: ${m.preferred.matched.size} of ${m.preferred.required.size} shown" +
                (if (m.preferred.missing.isNotEmpty()) " · not shown: " + m.preferred.missing.joinToString() else ""))
            Note("Your resume skills: ${m.skills.resume.joinToString().ifBlank { "none recognized" }}")
        }
        SettingsGroup("Requirement evidence") {
            m.evidence.forEach { e ->
                Text("• ${e.requirement}: ${e.result}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                e.evidence?.let { Text("   Resume: \"${it.take(160)}\"", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
            }
        }
        if (m.gaps.isNotEmpty()) Glass { Text("Potential gaps", style = MaterialTheme.typography.titleSmall); m.gaps.forEach { Text("• $it") } }
        if (c.advice.value.isNotEmpty()) SettingsGroup("Improvement plan") {
            Note("Generated by rules from the comparison. Nothing is added to your resume; you decide what is true and what to change.")
            c.advice.value.forEach { a ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${a.kind}: ${a.recommendation}", fontWeight = FontWeight.SemiBold)
                    Text("Why: ${a.reason}", style = MaterialTheme.typography.bodySmall)
                    Text("Job: ${a.requirement.take(160)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    Text("Resume evidence: ${a.evidence.ifBlank { "–" }.take(160)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    Text("Next: ${a.nextAction}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (c.priorities.value.isNotEmpty()) Text("Learning priorities: " + c.priorities.value.joinToString(" → "))
            OutlinedButton(c::suggestProjects, enabled = vm.taskBusy.value == null) { Text("AI: suggest practice projects") }
        }
        c.aiIdeas.value?.let { r -> Glass {
            Text(r.title, style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.warn)
            r.error?.let { Text("⚠ $it", color = LocalExtra.current.bad) }
            SelectionContainer { MarkdownText(r.text) }
            r.source?.let { Pill(it.label) }
            TextButton({ l.save("career", "Project ideas: ${c.selectedJd.value?.title}", r.text, "Career") }) { Text("⭳ Save to Knowledge") }
        } }
    }
}

// ====================================================================== job description fields
@Composable
internal fun JdFieldsDialog(vm: AppViewModel, j: JobDescription, onClose: () -> Unit) {
    val c = vm.career
    val f0 = remember(j.id, j.updatedAt) { c.fieldsOf(j) }
    var f by remember(j.id) { mutableStateOf(f0) }
    var showOriginal by remember { mutableStateOf(false) }
    fun list(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Job details") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Extracted on this phone. Correct anything; empty = not found.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            OutlinedTextField(f.title, { f = f.copy(title = it) }, label = { Text("Job title") }, singleLine = true)
            OutlinedTextField(f.company, { f = f.copy(company = it) }, label = { Text("Company") }, singleLine = true)
            OutlinedTextField(f.location, { f = f.copy(location = it) }, label = { Text("Location") }, singleLine = true)
            OutlinedTextField(f.employmentType, { f = f.copy(employmentType = it) }, label = { Text("Employment type") }, singleLine = true)
            OutlinedTextField(f.minYears?.toString().orEmpty(), { f = f.copy(minYears = it.filter(Char::isDigit).take(2).toIntOrNull()) }, label = { Text("Minimum years of experience") }, singleLine = true)
            OutlinedTextField(f.required.joinToString(", "), { f = f.copy(required = list(it)) }, label = { Text("Mandatory skills (comma separated)") })
            OutlinedTextField(f.preferred.joinToString(", "), { f = f.copy(preferred = list(it)) }, label = { Text("Preferred skills") })
            OutlinedTextField(f.responsibilities.joinToString("\n"), { f = f.copy(responsibilities = list(it)) }, label = { Text("Responsibilities (one per line)") }, minLines = 3)
            OutlinedTextField(f.qualifications.joinToString("\n"), { f = f.copy(qualifications = list(it)) }, label = { Text("Qualifications") }, minLines = 2)
            OutlinedTextField(f.experience.joinToString("\n"), { f = f.copy(experience = list(it)) }, label = { Text("Experience requirements") }, minLines = 2)
            OutlinedTextField(f.education.joinToString("\n"), { f = f.copy(education = list(it)) }, label = { Text("Education") }, minLines = 1)
            OutlinedTextField(f.certifications.joinToString("\n"), { f = f.copy(certifications = list(it)) }, label = { Text("Certifications") }, minLines = 1)
            TextButton({ showOriginal = !showOriginal }) { Text(if (showOriginal) "Hide original text" else "Show original text") }
            if (showOriginal) SelectionContainer { Text(j.body, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton({ c.saveFields(j, f.copy(required = f.required.map { s -> ai.abhify.android.career.SkillTaxonomy.current?.normalize(s) ?: s },
            preferred = f.preferred.map { s -> ai.abhify.android.career.SkillTaxonomy.current?.normalize(s) ?: s })); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Close") } })
}
