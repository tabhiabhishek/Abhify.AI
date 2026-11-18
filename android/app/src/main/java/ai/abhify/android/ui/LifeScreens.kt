package ai.abhify.android.ui

import ai.abhify.android.chat.JobApplication
import ai.abhify.android.chat.JobDescription
import ai.abhify.android.chat.VaultItem
import ai.abhify.android.chat.versions
import ai.abhify.android.life.Career
import ai.abhify.android.life.ShareKit
import ai.abhify.android.life.StudyKit
import ai.abhify.android.life.VaultKit
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

private fun share(ctx: Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) = PageBody(content = content)

@Composable
private fun Sections(names: List<String>, selected: Int, glyphs: List<String> = emptyList(), onPick: (Int) -> Unit) =
    SegmentedTabs(names, selected, Modifier.padding(horizontal = Dimens.gutter, vertical = Dimens.tiny), glyphs, Accent.KNOWLEDGE, onPick)

@Composable
private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)

// ====================================================================== CAREER
// CareerTab (v3.3) is in CareerScreens.kt; the tracker, job-description form and interview practice below are shared.

@Composable
internal fun JobsView(vm: AppViewModel, onMatch: (JobDescription) -> Unit = {}) {
    val l = vm.life
    val c = vm.career
    var detailsFor by remember { mutableStateOf<JobDescription?>(null) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JobApplication?>(null) }
    var deletingJd by remember { mutableStateOf<JobDescription?>(null) }
    val today = remember { java.time.LocalDate.now() }
    Page {
        val e = l.editingApp.value; val ej = l.editingJd.value
        when {
            e != null -> AppForm(vm, e)
            ej != null -> JdForm(vm, ej)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("＋ Application", l::newApp, Modifier.weight(1f))
                    OutlinedButton({ l.editingJd.value = JobDescription(0, "", null, "", 0) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("＋ Job description") }
                }
                ai.abhify.android.life.Tracker.overdue(l.apps, today).takeIf { it.isNotEmpty() }?.let { od -> Glass {
                    Text("⏰ Overdue follow-ups", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.bad)
                    od.forEach { a -> Text("${a.company} · ${a.title}: follow up was ${a.followUpOn}") }
                } }
                ai.abhify.android.life.Tracker.upcoming(l.apps, today).takeIf { it.isNotEmpty() }?.let { up -> Glass {
                    Text("📅 Follow-ups in the next 7 days", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.warn)
                    up.forEach { a -> Text("${a.company} · ${a.title}: ${a.followUpOn}") }
                } }
                val q = l.tracker.value
                Field(q.text, { l.tracker.value = q.copy(text = it) }, "Search company, title, notes")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(q.status == null, { l.tracker.value = q.copy(status = null) }, { Text("All (${l.apps.size})") })
                    Career.Status.entries.forEach { st -> val n = l.apps.count { it.status == st.name }
                        FilterChip(q.status == st, { l.tracker.value = q.copy(status = st) }, { Text("${st.label} ($n)") }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ai.abhify.android.life.Tracker.Sort.entries.forEach { so -> FilterChip(q.sort == so, { l.tracker.value = q.copy(sort = so) }, { Text("Sort: ${so.label}") }) }
                    TextButton({ filters = !filters }) { Text(if (filters) "Hide filters" else "More filters") }
                }
                if (filters) Glass {
                    Field(q.company, { l.tracker.value = q.copy(company = it) }, "Company contains")
                    Field(q.title, { l.tracker.value = q.copy(title = it) }, "Job title contains")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(q.appliedFrom, { l.tracker.value = q.copy(appliedFrom = it.take(10)) }, "Applied from", modifier = Modifier.weight(1f))
                        Field(q.appliedTo, { l.tracker.value = q.copy(appliedTo = it.take(10)) }, "to", modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(q.followFrom, { l.tracker.value = q.copy(followFrom = it.take(10)) }, "Follow-up from", modifier = Modifier.weight(1f))
                        Field(q.followTo, { l.tracker.value = q.copy(followTo = it.take(10)) }, "to", modifier = Modifier.weight(1f))
                    }
                    Muted("Dates as 2026-11-05. Empty = no limit.")
                    TextButton({ l.tracker.value = ai.abhify.android.life.Tracker.Query() }) { Text("Clear all filters") }
                }
                val list = ai.abhify.android.life.Tracker.apply(l.apps, l.tracker.value)
                if (list.isEmpty()) { if (l.apps.isEmpty()) EmptyState("💼", "No applications yet", "Everything here stays on this phone.") else EmptyState("🔍", "Nothing matches", "Try another search or filter.") }
                list.forEach { a ->
                    Glass(onClick = { l.editingApp.value = a }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${a.title} · ${a.company}", style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(a.appliedOn?.let { "applied $it" }, a.interviewOn?.let { "interview $it" }, a.followUpOn?.let { "follow up $it" })
                                    .joinToString(" · ").ifBlank { "no dates" }, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                            }
                            Pill(Career.Status.of(a.status).label)
                        }
                        Row { TextButton({ l.editingApp.value = a }) { Text("Edit") }; TextButton({ deleting = a }) { Text("Delete", color = LocalExtra.current.bad) } }
                    }
                }
                if (l.jds.isNotEmpty()) {
                    Text("Saved job descriptions", style = MaterialTheme.typography.titleMedium)
                    Field(c.jdQuery.value, { c.jdQuery.value = it }, "Search job descriptions")
                }
                c.visibleJds().forEach { j ->
                    Glass {
                        Text(j.title + (j.company?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                        val f = remember(j.id, j.updatedAt) { c.fieldsOf(j) }
                        Text(listOfNotNull(f.location.ifBlank { null }, f.employmentType.ifBlank { null }, f.minYears?.let { "$it+ years" },
                            "${f.required.size} required · ${f.preferred.size} preferred skills").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton({ onMatch(j) }) { Text("Match with resume") }
                            TextButton({ detailsFor = j }) { Text("Details") }
                            TextButton({ l.editingJd.value = j }) { Text("Edit text") }
                            TextButton({ deletingJd = j }) { Text("Delete", color = LocalExtra.current.bad) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { a -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${a.title} at ${a.company}?") },
        text = { DialogText("This application and its notes are removed from this phone.") },
        confirmButton = { TextButton({ l.deleteApp(a); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
    detailsFor?.let { j -> JdFieldsDialog(vm, j) { detailsFor = null } }
    deletingJd?.let { j -> AlertDialog(onDismissRequest = { deletingJd = null }, title = { Text("Delete \"${j.title}\"?") },
        text = { DialogText("Applications linked to it keep their other details.") },
        confirmButton = { TextButton({ l.deleteJd(j); deletingJd = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deletingJd = null }) { Text("Cancel") } }) }
}

@Composable
private fun AppForm(vm: AppViewModel, a0: JobApplication) {
    val l = vm.life
    var a by remember(a0.id) { mutableStateOf(a0) }
    var error by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<ai.abhify.android.chat.ResumeVersion>>(emptyList()) }
    LaunchedEffect(a.resumeDocId) { versions = a.resumeDocId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.g.db.versions(id) } } ?: emptyList() }
    Glass {
        Text(if (a.id > 0) "Edit application" else "New application", style = MaterialTheme.typography.titleLarge)
        Field(a.company, { a = a.copy(company = it.take(120)) }, "Company *")
        Field(a.title, { a = a.copy(title = it.take(120)) }, "Job title *")
        Text("Status", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Career.Status.entries.forEach { st -> FilterChip(a.status == st.name, { a = a.copy(status = st.name) }, { Text(st.label) }) }
        }
        Field(a.appliedOn.orEmpty(), { a = a.copy(appliedOn = it.take(10)) }, "Application date (2026-11-05)")
        Field(a.interviewOn.orEmpty(), { a = a.copy(interviewOn = it.take(10)) }, "Interview date")
        Field(a.followUpOn.orEmpty(), { a = a.copy(followUpOn = it.take(10)) }, "Follow-up date")
        Text("Job description", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(a.jdId == null, { a = a.copy(jdId = null) }, { Text("None") })
            l.jds.forEach { j -> FilterChip(a.jdId == j.id, { a = a.copy(jdId = j.id) }, { Text(j.title.take(30)) }) }
        }
        Text("Resume used", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(a.resumeDocId == null, { a = a.copy(resumeDocId = null, resumeVersionId = null) }, { Text("None") })
            vm.resume.resumes.forEach { d -> FilterChip(a.resumeDocId == d.id, { a = a.copy(resumeDocId = d.id, resumeVersionId = null) }, { Text(d.name.take(30)) }) }
        }
        if (versions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(a.resumeVersionId == null, { a = a.copy(resumeVersionId = null) }, { Text("Original") })
            versions.filter { it.kind == "draft" || it.kind == ResumeState.CORRECTED }.forEach { v ->
                FilterChip(a.resumeVersionId == v.id, { a = a.copy(resumeVersionId = v.id) }, { Text("${v.title} · ${ago(v.createdAt)}") }) }
        }
        OutlinedTextField(a.notes.orEmpty(), { a = a.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 3, maxLines = 8)
        error?.let { Text(it, color = LocalExtra.current.bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ error = l.saveApp(a) }) { Text("Save") }
            TextButton({ l.editingApp.value = null }) { Text("Cancel") }
        }
    }
}

@Composable
private fun JdForm(vm: AppViewModel, j0: JobDescription) {
    var j by remember(j0.id) { mutableStateOf(j0) }
    var error by remember { mutableStateOf<String?>(null) }
    Glass {
        Text(if (j.id > 0) "Edit job description" else "New job description", style = MaterialTheme.typography.titleLarge)
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.career.importJd(uri) }
        LaunchedEffect(vm.life.editingJd.value) { vm.life.editingJd.value?.let { if (it.body != j.body && it.id == j.id) j = it } }
        OutlinedButton({ picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*")) },
            enabled = !vm.career.importingJd.value) { Text(if (vm.career.importingJd.value) "Reading…" else "Import from file (PDF, DOCX, TXT, MD)") }
        Muted("The file is read on this phone; nothing is uploaded.")
        Field(j.title, { j = j.copy(title = it.take(120)) }, "Title (e.g. Data Analyst)")
        Field(j.company.orEmpty(), { j = j.copy(company = it.take(120)) }, "Company")
        OutlinedTextField(j.body, { j = j.copy(body = it) }, Modifier.fillMaxWidth(), placeholder = { Text("Paste the job description") }, minLines = 8, maxLines = 20)
        error?.let { Text(it, color = LocalExtra.current.bad) }
        Row { Button({ error = vm.life.saveJd(j.copy(fields = j.fields ?: ai.abhify.android.career.SkillTaxonomy.current?.let { t -> ai.abhify.android.career.JobParser.parse(j.body, t).toJson() })) }) { Text("Save") }
            TextButton({ vm.life.editingJd.value = null }) { Text("Cancel") } }
    }
}

@Composable
internal fun InterviewView(vm: AppViewModel, withMic: (() -> Unit) -> Unit) {
    val l = vm.life
    LaunchedEffect(Unit) { l.loadAttempts() }
    DisposableEffect(Unit) { onDispose { if (vm.listening.value) vm.cancelVoice() } }        // leaving stops the microphone
    Page {
        SettingsGroup("Interview practice") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Career.Topic.entries.forEach { t -> FilterChip(l.topic.value == t, { l.topic.value = t }, { Text(t.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("beginner", "intermediate", "senior").forEach { lv -> FilterChip(l.level.value == lv, { l.level.value = lv }, { Text(lv.replaceFirstChar(Char::uppercase)) }) }
            }
            if (l.topic.value == Career.Topic.PROJECTS) Muted("Questions come from your active resume: ${vm.career.active()?.name ?: "none chosen (Career → Resumes)"}.")
            if (l.topic.value == Career.Topic.JOB) Muted("Questions come from the job description chosen in Match: ${vm.career.selectedJd.value?.title ?: "none chosen"}.")
            GradientButton(if (l.question.value == null) "Ask me a question" else "Next question", l::nextQuestion, Modifier.fillMaxWidth(), enabled = vm.taskBusy.value == null)
        }
        l.question.value?.let { (q, outline) ->
            Glass {
                l.questionRequirement.value?.let { Text("📋 From job requirement: $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
                Text(q, style = MaterialTheme.typography.titleMedium)
                l.questionSource.value?.let { Pill(it.label, if (it.cloud) MaterialTheme.colorScheme.tertiary else LocalExtra.current.ok) }
                var showOutline by remember(q) { mutableStateOf(false) }
                TextButton({ showOutline = !showOutline }) { Text(if (showOutline) "Hide answer outline" else "Show answer outline") }
                if (showOutline && outline.isNotBlank()) MarkdownText(outline)
                OutlinedTextField(l.answer.value, { l.answer.value = it }, Modifier.fillMaxWidth(), label = { Text("Your answer (type or speak, then edit)") }, minLines = 4, maxLines = 12)
                if (vm.listening.value) Text("🎙 Listening… ${vm.heard.value}", color = MaterialTheme.colorScheme.tertiary)
                vm.voiceError.value?.let { Text("🎙 $it", color = LocalExtra.current.bad) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        if (vm.listening.value) vm.stopVoice()
                        else withMic { vm.startVoice { heard -> l.answer.value = (l.answer.value + " " + heard).trim() } }
                    }) { Text(if (vm.listening.value) "■ Stop" else "🎙 Speak") }
                    if (vm.listening.value) TextButton(vm::cancelVoice) { Text("Cancel") }
                    Button(l::submitAnswer, enabled = l.answer.value.isNotBlank() && vm.taskBusy.value == null && !vm.listening.value) { Text("Get feedback") }
                }
                Muted("Speech is turned into text on this phone's recognizer; no audio is saved.")
            }
            l.feedback.value?.let { f -> SettingsGroup("Feedback") {
                SelectionContainer { MarkdownText(f) }
                Row { TextButton({ vm.speak(f) }) { Text("🔊 Read") }
                    TextButton({ vm.life.save("interview", q.take(80), "Question: $q\n\nMy answer: ${l.answer.value}\n\nFeedback:\n$f", "Career") }) { Text("⭳ Knowledge") } }
                Muted("AI practice feedback: it can be wrong and is not a hiring decision. Your answer is kept above: edit it and tap Get feedback to retry.")
            } }
        }
        if (l.attempts.isNotEmpty()) Text("Earlier practice", style = MaterialTheme.typography.titleMedium)
        val jd = vm.career.selectedJd.value
        if (jd != null && l.attempts.any { it.jdId == jd.id }) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!l.attemptsForJob.value, { l.attemptsForJob.value = false }, { Text("All") })
            FilterChip(l.attemptsForJob.value, { l.attemptsForJob.value = true }, { Text("This job: ${jd.title.take(30)}") })
        }
        l.attempts.filter { !l.attemptsForJob.value || jd == null || it.jdId == jd.id }.take(30).forEach { a ->
            var openA by remember(a.id) { mutableStateOf(false) }
            Glass(onClick = { openA = !openA }) {
                Text("${a.topic} · ${ago(a.createdAt)}" + (a.score?.let { " · score %.1f/5".format(it) } ?: ""), style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                Text(a.question, style = MaterialTheme.typography.titleSmall, maxLines = if (openA) 20 else 2, overflow = TextOverflow.Ellipsis)
                if (openA) { Text("Your answer: ${a.answer}"); a.feedback?.let { MarkdownText(it) }; TextButton({ l.deleteAttempt(a) }) { Text("Delete", color = LocalExtra.current.bad) } }
            }
        }
    }
}

// ====================================================================== STUDY
@Composable
fun StudyView(vm: AppViewModel) {
    val l = vm.life
    LaunchedEffect(Unit) { l.loadSessions(); l.loadSources() }
    var deleting by remember { mutableStateOf<ai.abhify.android.chat.StudySession?>(null) }
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(enabled = l.open.value != null) { l.open.value = null }   // 6.1: system Back closes the session, not the tab
    Page {
        val s = l.open.value
        if (s != null) SessionView(vm, s) { share(ctx, it) }
        else {
            SkillsCard(vm)                                                // next build: skill graph + adaptive next step
            StudyDashboard(vm)
            SettingsGroup("Create study material") {
                val kinds = listOf(LifeState.SourceKind.TOPIC, LifeState.SourceKind.DOCUMENT, LifeState.SourceKind.NOTE)
                SegmentedTabs(listOf("Topic", "Document", "Note"), kinds.indexOf(l.sourceKind.value), glyphs = listOf("💡", "📄", "📝"), accent = Accent.STUDY) { l.sourceKind.value = kinds[it] }
                when (l.sourceKind.value) {
                    LifeState.SourceKind.TOPIC -> Field(l.studyTopic.value, { l.studyTopic.value = it.take(200) }, "Topic (e.g. gradient descent)")
                    LifeState.SourceKind.DOCUMENT -> if (l.docs.isEmpty()) Muted("No documents yet: add one in Documents.") else
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            l.docs.forEach { d -> FilterChip(l.studyDoc.value?.id == d.id, { l.studyDoc.value = d }, { Text(d.name.take(28)) }) } }
                    LifeState.SourceKind.NOTE -> if (l.notes.isEmpty()) Muted("No notes yet: add one in Knowledge → Workspaces.") else
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            l.notes.forEach { n -> FilterChip(l.studyNote.value?.id == n.id, { l.studyNote.value = n }, { Text(n.title.take(28)) }) } }
                }
                val tiles = listOfNotNull(if (l.sourceKind.value == LifeState.SourceKind.TOPIC) Triple("💡", "Explain", StudyKit.Kind.EXPLAIN) else null,
                    Triple("📝", "Summary", StudyKit.Kind.SUMMARY), Triple("❔", "Quiz", StudyKit.Kind.QUIZ), Triple("🃏", "Flashcards", StudyKit.Kind.CARDS))
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (g, n, k) -> ActionTile(g, n, Modifier.weight(1f), vm.taskBusy.value == null,
                            when (k) { StudyKit.Kind.EXPLAIN -> Accent.CHAT; StudyKit.Kind.SUMMARY -> Accent.DOCS; StudyKit.Kind.QUIZ -> Accent.STUDY; else -> Accent.MEMORY }) { l.study(k) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                var days by rememberSaveable { mutableStateOf("7") }; var minutes by rememberSaveable { mutableStateOf("30") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(days, { days = it.filter(Char::isDigit).take(2) }, "Days", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, modifier = Modifier.weight(1f))
                    Field(minutes, { minutes = it.filter(Char::isDigit).take(3) }, "Min/day", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, modifier = Modifier.weight(1f))
                    OutlinedButton({ l.study(StudyKit.Kind.PLAN, days.toIntOrNull()?.coerceIn(1, 90) ?: 7, minutes.toIntOrNull()?.coerceIn(5, 600) ?: 30) },
                        enabled = vm.taskBusy.value == null) { Text("Revision plan") }
                }
                Muted("AI-generated study material can contain mistakes: check important facts. Document text goes online only after you agree.")
            }
            if (l.sessions.isNotEmpty()) SectionTitle("Study history")
            l.sessions.forEach { s2 ->
                Glass(padding = 12, onClick = { l.openSession(s2) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeTile(when (s2.kind) { StudyKit.Kind.QUIZ.name -> "❔"; StudyKit.Kind.CARDS.name -> "🃏"; StudyKit.Kind.PLAN.name -> "🗓"; else -> "📝" }, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s2.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(ago(s2.updatedAt) + (if (s2.kind == StudyKit.Kind.QUIZ.name && s2.total != null) " · ${s2.score ?: 0}/${s2.total} correct" else "") +
                                (s2.sourceRef?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        }
                        IconAction(ai.abhify.android.R.drawable.ic_delete_outline, "Delete ${s2.title}", { deleting = s2 })
                    }
                }
            }
        }
    }
    deleting?.let { s -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${s.title}\"?") },
        confirmButton = { TextButton({ l.deleteSession(s); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun SessionView(vm: AppViewModel, s: ai.abhify.android.chat.StudySession, share: (String) -> Unit) {
    val l = vm.life
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ l.openSession(null) }) { Text("← History") }
        Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    when (s.kind) {
        StudyKit.Kind.QUIZ.name -> l.quiz.value?.let { q -> QuizCard(vm, s, q, share) }
        StudyKit.Kind.CARDS.name -> {
            val cards = remember(s.id) { StudyKit.decodeCards(s.content) }
            val i = l.cardIndex.value.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
            cards.getOrNull(i)?.let { (f, b) ->
                Glass(onClick = { l.reveal.value = !l.reveal.value }) {
                    Text("Card ${i + 1} of ${cards.size}", color = LocalExtra.current.muted)
                    Text(f, style = MaterialTheme.typography.titleLarge)
                    if (l.reveal.value) { HorizontalDivider(); Text(b, style = MaterialTheme.typography.bodyLarge) } else Text("Tap to reveal", color = MaterialTheme.colorScheme.tertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ l.cardIndex.value = (i - 1).coerceAtLeast(0); l.reveal.value = false }, enabled = i > 0) { Text("← Previous") }
                    Button({ l.cardIndex.value = (i + 1).coerceAtMost(cards.lastIndex); l.reveal.value = false }, enabled = i < cards.lastIndex) { Text("Next →") }
                }
                OutlinedButton({ vm.life.save("cards", s.title, cards.joinToString("\n\n") { "Q: ${it.first}\nA: ${it.second}" }, "Study") }) { Text("⭳ Save to Knowledge") }
            }
        }
        else -> {
            val (text, sources) = remember(s.id) { StudyKit.decodeText(s.content) }
            Glass {
                SelectionContainer { MarkdownText(text) }
                if (sources.isNotEmpty()) Muted("Source: " + sources.joinToString(" · "))
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ vm.speak(text) }) { Text("🔊 Read") }
                TextButton({ l.save("study", s.title, text + "\n\nSource: " + sources.joinToString(" · "), "Study") }) { Text("⭳ Save to Knowledge") }
                if (s.kind == StudyKit.Kind.PLAN.name) TextButton({ l.planToTracker(s) }) { Text("Track in Plans") }
                TextButton({ share("📚 ${s.title}\n\n" + ShareKit.redact(text, includeContact = false).take(3000)) }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun QuizCard(vm: AppViewModel, s: ai.abhify.android.chat.StudySession, q: StudyKit.Quiz, share: (String) -> Unit) {
    val l = vm.life
    val m = q.questions[q.index]; val picked = q.picks[q.index]
    Glass {
        Text("Question ${q.index + 1} of ${q.questions.size} · ${q.correct} correct, ${q.wrong.size} wrong", color = LocalExtra.current.muted)
        Text(m.question, style = MaterialTheme.typography.titleMedium)
        m.options.forEachIndexed { i, o ->
            val c = when { picked == null -> LocalExtra.current.line; i == m.correct -> LocalExtra.current.ok; i == picked -> LocalExtra.current.bad; else -> LocalExtra.current.line }
            OutlinedButton({ l.pick(i) }, Modifier.fillMaxWidth(), border = BorderStroke(if (picked != null && (i == m.correct || i == picked)) 2.dp else 1.dp, c),
                shape = RoundedCornerShape(12.dp)) { Text("${'A' + i}.  $o", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
        }
        if (picked != null) {
            Text(if (picked == m.correct) "✅ Correct" else "❌ The answer is ${'A' + m.correct}.", fontWeight = FontWeight.Bold)
            if (m.why.isNotBlank()) Text(m.why, color = LocalExtra.current.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ l.move(false) }, enabled = q.index > 0) { Text("← Previous") }
            Button({ l.move(true) }, enabled = q.index < q.questions.lastIndex) { Text("Next →") }
        }
    }
    if (q.done) Glass {
        Text("Score: ${q.correct} of ${q.questions.size}", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (q.wrong.isNotEmpty()) Button(l::reviewWrong) { Text("Review ${q.wrong.size} wrong") }
            TextButton({ share(ShareKit.studyResult(s.title.removePrefix("Quiz: ").removePrefix("Review: "), q.correct, q.questions.size)) }) { Text("Share score") }
            TextButton({ l.save("quiz", "${s.title} – ${q.correct}/${q.questions.size}", q.questions.mapIndexed { i, x ->
                "${i + 1}. ${x.question}\nAnswer: ${x.options[x.correct]}" + if (q.picks[i] != x.correct) " (you chose ${q.picks[i]?.let { x.options[it] } ?: "–"})" else "" }.joinToString("\n\n"), "Study") }) { Text("⭳ Save to Knowledge") }
        }
        Muted("Shared text has only the topic and score.")
    }
}

// ====================================================================== KNOWLEDGE
@Composable
fun KnowledgeTab(vm: AppViewModel) {
    val section = vm.knowledgeSection.intValue
    Column(Modifier.fillMaxSize()) {
        Sections(listOf("My Knowledge", "Workspaces"), section, listOf("🔖", "🗂")) { vm.knowledgeSection.intValue = it }
        Box(Modifier.weight(1f)) { if (section == 0) VaultView(vm) else WorkTab(vm) }
    }
}

@Composable
private fun VaultView(vm: AppViewModel) {
    val l = vm.life
    LaunchedEffect(l.vaultQuery.value, l.vaultCategory.value, l.favoritesOnly.value, l.vaultSort.value) { l.loadVault() }
    var ask by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<VaultItem?>(null) }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }
    androidx.activity.compose.BackHandler(enabled = l.openItem.value != null) { l.openItem.value = null }   // 6.1: system Back closes the item, not the tab
    Page {
        val o = l.openItem.value
        if (o != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconAction(ai.abhify.android.R.drawable.ic_arrow_back, "Back to My Knowledge", { l.openItem.value = null })
                Text(o.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(o.category + (if (o.tags.isNotEmpty()) " · #" + o.tags.joinToString(" #") else "") + " · " + ago(o.updatedAt),
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Glass { SelectionContainer { MarkdownText(o.body.take(60_000)) } }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ vm.speak(o.body) }) { Text("🔊 Read") }
                TextButton({ editing = o }) { Text("Edit") }
                TextButton({ deleting = o }) { Text("Delete", color = LocalExtra.current.bad) }
            }
            return@Page
        }
        PageHeader("My Knowledge", if (l.vault.isEmpty()) "Answers, notes and results you saved" else "${l.vault.size} saved item" + (if (l.vault.size == 1) "" else "s"),
            Accent.KNOWLEDGE, "🔖")
        SearchField(l.vaultQuery.value, { l.vaultQuery.value = it }, "Search titles, text and tags")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(l.vaultCategory.value == null, { l.vaultCategory.value = null }, { Text("All") })
            VaultKit.CATEGORIES.forEach { c -> FilterChip(l.vaultCategory.value == c, { l.vaultCategory.value = c }, { Text(c) }) }
            FilterChip(l.favoritesOnly.value, { l.favoritesOnly.value = !l.favoritesOnly.value }, { Text("★ Favourites") })
            FilterChip(l.vaultSort.value == "title", { l.vaultSort.value = if (l.vaultSort.value == "title") "recent" else "title" }, { Text(if (l.vaultSort.value == "title") "A–Z" else "Recent") })
        }
        if (l.selected.isNotEmpty()) Glass {
            Text("Ask ${l.selected.size} selected item(s)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth(), placeholder = { Text("Your question") }, maxLines = 4)
            Row { Button({ l.askVault(ask) }, enabled = ask.isNotBlank() && vm.taskBusy.value == null) { Text("Ask with sources") }
                TextButton({ l.selected.clear() }) { Text("Clear selection") } }
            Muted("Only the selected items are searched (on this phone); answers cite them as [S1]…")
        }
        if (l.vault.isEmpty()) { if (l.vaultQuery.value.isBlank() && l.vaultCategory.value == null && !l.favoritesOnly.value)
            EmptyState("🗂", "Nothing saved yet", "Use \"⭳ Save\" on chat answers, document results, study sessions, quiz results, interview feedback and resume versions.")
            else EmptyState("🔍", "Nothing matches", "Try another search, category or filter.") }
        l.vault.forEach { i ->
            Glass(padding = 10, onClick = { l.openVault(i) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(i.id in l.selected, { on -> if (on) l.selected += i.id else l.selected -= i.id }, Modifier.semantics { contentDescription = "Select ${i.title} for questions" })
                    Column(Modifier.weight(1f)) {
                        Text(i.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(i.category + (if (i.tags.isNotEmpty()) " · #" + i.tags.joinToString(" #") else "") + " · " + ago(i.openedAt ?: i.updatedAt),
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                    TextButton({ l.toggleFavorite(i) }, Modifier.semantics { contentDescription = if (i.favorite) "Remove favourite" else "Add favourite" }) { Text(if (i.favorite) "★" else "☆") }
                }
            }
        }
    }
    editing?.let { i ->
        var title by remember(i.id) { mutableStateOf(i.title) }
        var cat by remember(i.id) { mutableStateOf(i.category) }
        var tags by remember(i.id) { mutableStateOf(i.tags.joinToString(", ")) }
        var fav by remember(i.id) { mutableStateOf(i.favorite) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("Title") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultKit.CATEGORIES.forEach { c -> FilterChip(cat == c, { cat = c }, { Text(c) }) } }
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(fav, { fav = it }); Text("Favourite") }
            } },
            confirmButton = { TextButton({ l.updateVault(i, title, cat, tags, fav); l.openItem.value = null; editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
    deleting?.let { i -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${i.title}\"?") },
        text = { DialogText(if (i.refKind == "doc") "Only the saved entry is removed; the document stays in your library." else "It is removed from this phone.") },
        confirmButton = { TextButton({ l.deleteVault(i); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}
