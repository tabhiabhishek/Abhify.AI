package ai.abhify.android.ui

import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.docs.Documents
import ai.abhify.android.resume.Resume
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ResumeScreen(vm: AppViewModel) {
    val r = vm.resume
    LaunchedEffect(Unit) { r.load() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) r.import(uri) }
    androidx.activity.compose.BackHandler(enabled = r.selected.value != null) { r.open(null) }   // 6.1: system Back closes the resume first
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTitle(r.selected.value?.name ?: "Resume Assistant", { if (r.selected.value != null) r.open(null) else vm.back() })
        TaskStatus(vm)
        vm.studio.importing.value?.let { step -> Box(Modifier.padding(horizontal = Dimens.gutter)) { BusyBanner(step, onCancel = vm.studio::cancelImport) } }
        PageBody(Modifier.weight(1f)) {
            val d = r.selected.value
            if (d == null) ResumeHome(vm) { picker.launch(Documents.PICKER_TYPES) } else ResumeDetail(vm, d)
        }
    }
}

@Composable
private fun ResumeHome(vm: AppViewModel, pick: () -> Unit) {
    val r = vm.resume
    var choosing by remember { mutableStateOf(false) }
    SettingsGroup("Resume Assistant") {
        Text("Upload a resume (PDF, DOCX, TXT, or a photo/screenshot: JPEG, PNG, WebP). Text is extracted on this phone; scans and photos use text recognition. " +
            "You review and correct the text, then analyse it. The AI is told never to invent facts, and anything in its output that isn't in your resume is flagged.",
            style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
        GradientButton("＋ Upload resume", pick, Modifier.fillMaxWidth(), enabled = vm.studio.importing.value == null)
        OutlinedButton({ choosing = true }, Modifier.fillMaxWidth()) { Text("Use a document from my library") }
    }
    vm.studio.importWarnings.takeIf { it.isNotEmpty() }?.let { WarningsCard(it) }
    if (r.resumes.isEmpty()) Text("No resumes yet.", color = LocalExtra.current.muted)
    r.resumes.forEach { d ->
        Glass(onClick = { r.open(d) }) {
            Text(d.name, style = MaterialTheme.typography.titleSmall)
            Text("${d.kind} · ${"%,d".format(d.chars)} characters" + (d.pages?.let { " · $it pages" } ?: "") +
                (if (d.ocrPages > 0) " · ${d.ocrPages} read by OCR" else "") + (if (d.warnings.isNotEmpty()) " · ⚠ ${d.warnings.size} notes" else ""),
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
    }
    if (choosing) {
        val docs = remember { r.libraryCandidates() }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Choose a document") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (docs.isEmpty()) Text("Your library has no other documents.")
                docs.forEach { d -> TextButton({ choosing = false; r.useExisting(d) }) { Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            } },
            confirmButton = { TextButton({ choosing = false }) { Text("Cancel") } })
    }
}

@Composable
private fun WarningsCard(w: List<String>) = Glass {
    Text("⚠ Check the extraction", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.warn)
    w.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ResumeDetail(vm: AppViewModel, d: LibraryDoc) {
    val r = vm.resume
    var section by rememberSaveable { mutableIntStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Text", "Details", "Analyse", "Job match", "Saved (${r.versions.size})").forEachIndexed { i, l -> FilterChip(section == i, { section = i }, { Text(l) }) }
    }
    when (section) {
        0 -> TextReview(vm, d); 1 -> DetailsView(vm); 2 -> AnalyseView(vm); 3 -> JobView(vm); else -> VersionsView(vm)
    }
    r.output.value?.let { OutputCard(vm, it) }
}

@Composable
private fun TextReview(vm: AppViewModel, d: LibraryDoc) {
    val r = vm.resume
    var editing by remember(d.id) { mutableStateOf(false) }
    var text by remember(d.id, r.versions.size) { mutableStateOf(r.working) }
    if (d.warnings.isNotEmpty()) WarningsCard(d.warnings)
    Glass {
        Text(if (r.corrected) "Your corrected text (the original extraction is kept)" else "Extracted text" + if (d.ocrPages > 0) " (includes OCR)" else "",
            style = MaterialTheme.typography.titleSmall)
        if (editing) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 10, maxLines = 30)
            Row { Button({ r.saveCorrection(text); editing = false }, enabled = text.isNotBlank()) { Text("Save correction") }; TextButton({ editing = false; text = r.working }) { Text("Cancel") } }
        } else {
            SelectionContainer { Text(r.working.take(40_000), style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton({ editing = true }) { Text("Correct the text") }
        }
    }
    if (r.corrected) SettingsGroup("Original extraction") {
        SelectionContainer { Text(r.original.value.take(20_000), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
    }
    TextButton({ r.unmark(d) }) { Text("Not a resume (keep it in the library)") }
}

@Composable
private fun DetailsView(vm: AppViewModel) {
    val p = vm.resume.profile.value ?: return
    SettingsGroup("Found by rules on this phone (no AI)") {
        Text("⚠ marks values read from text that OCR wasn't sure about: check them against your original.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        @Composable fun row(l: String, v: List<Resume.Field>) = Row {
            Text(l, Modifier.weight(0.3f), fontWeight = FontWeight.SemiBold)
            Text(v.joinToString { it.value + if (it.unclear) " ⚠" else "" }.ifBlank { "[missing]" }, Modifier.weight(0.7f),
                color = if (v.isEmpty()) LocalExtra.current.warn else MaterialTheme.colorScheme.onSurface)
        }
        row("Name", listOfNotNull(p.name)); row("Email", p.emails); row("Phone", p.phones); row("Links", p.links); row("Dates", p.dates)
        Row { Text("Skills", Modifier.weight(0.3f), fontWeight = FontWeight.SemiBold); Text(p.skills.joinToString().ifBlank { "[none recognized]" }, Modifier.weight(0.7f)) }
        if (p.missing.isNotEmpty()) Text("Not found: " + p.missing.joinToString(), color = LocalExtra.current.warn)
    }
    p.sections.forEach { (s, body) -> Glass { Text(s.label, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodyMedium) } }
    if (p.achievements.isNotEmpty()) SettingsGroup("Lines with measurable results") {
        p.achievements.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    ExportButtons(vm, "resume-details", vm.resume.profileReport())
}

@Composable
private fun AnalyseView(vm: AppViewModel) {
    val r = vm.resume
    SettingsGroup("Analyse with AI") {
        Text("Uses your AI mode (${vm.aiMode.value.label}). If the answer would go online you're asked first and can run it on this phone instead.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        listOf(ResumeState.Action.SUMMARY, ResumeState.Action.SUGGESTIONS, ResumeState.Action.QUESTIONS, ResumeState.Action.DRAFT).forEach { a ->
            FilledTonalButton({ r.run(a) }, Modifier.fillMaxWidth(), enabled = vm.taskBusy.value == null) { Text(a.label) }
        }
    }
}

@Composable
private fun JobView(vm: AppViewModel) {
    val r = vm.resume
    Glass {
        OutlinedTextField(r.jd.value, { r.jd.value = it.take(12_000) }, Modifier.fillMaxWidth(), placeholder = { Text("Paste the job description") }, minLines = 6, maxLines = 16)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(r::compareJd, enabled = r.jd.value.isNotBlank()) { Text("Match skills (on this phone)") }
            Button({ r.run(ResumeState.Action.JOB) }, enabled = r.jd.value.isNotBlank() && vm.taskBusy.value == null) { Text("Full comparison (AI)") }
        }
    }
    r.match.value?.let { m ->
        Glass {
            Text("Skills in the job description: ${m.required.size}", style = MaterialTheme.typography.titleSmall)
            Text("✓ In your resume: " + m.matched.joinToString().ifBlank { "none" }, color = LocalExtra.current.ok)
            Text("✗ Not in your resume: " + m.missing.joinToString().ifBlank { "none" }, color = LocalExtra.current.warn)
            Text("Missing skills are never added for you. Add one only if you really have it (then correct the text).", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            if (m.requirements.isNotEmpty()) { Text("Requirements to check yourself", style = MaterialTheme.typography.titleSmall); m.requirements.forEach { Text("• $it") } }
            Text("Exact word matching against a skills list; synonyms are not matched.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
    }
}

@Composable
private fun VersionsView(vm: AppViewModel) {
    val r = vm.resume
    var open by remember { mutableStateOf<Long?>(null) }
    if (r.versions.isEmpty()) Text("Corrections and AI results are saved here. The original extraction is never changed.", color = LocalExtra.current.muted)
    r.versions.forEach { v ->
        Glass(onClick = { open = if (open == v.id) null else v.id }) {
            Text(v.title, style = MaterialTheme.typography.titleSmall)
            Text(ago(v.createdAt) + (v.source?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            if (open == v.id) {
                SelectionContainer { MarkdownText(v.body) }
                ExportButtons(vm, v.kind, v.body)
                TextButton({ r.deleteVersion(v) }) { Text("Delete this version", color = LocalExtra.current.bad) }
            }
        }
    }
}

@Composable
private fun OutputCard(vm: AppViewModel, o: ResumeState.Output) {
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ vm.resume.output.value = null }) { Text("Close") }
        }
        o.error?.let { Text("⚠ $it", color = LocalExtra.current.bad) }
        if (o.unsupported.isNotEmpty()) Text("⚠ Not found in your resume — confirm or remove before using: " + o.unsupported.joinToString(),
            color = LocalExtra.current.warn, fontWeight = FontWeight.SemiBold)
        if (o.status != null) Text("⚠ Partial result (${o.status}). It was not saved; run it again.", color = LocalExtra.current.warn)
        if (o.text.isNotBlank()) SelectionContainer { MarkdownText(o.text) }
        o.source?.let { Pill(it.label, if (it.cloud) MaterialTheme.colorScheme.tertiary else LocalExtra.current.ok) }
        if (o.text.isNotBlank()) ExportButtons(vm, o.kind, o.text + if (o.unsupported.isNotEmpty()) "\n\n> ⚠ Not found in the resume (check): ${o.unsupported.joinToString()}" else "")
    }
}

/** Copy, share, or save as a .md / .txt file (the system file picker chooses where). */
@Composable
private fun ExportButtons(vm: AppViewModel, name: String, text: String) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val body = pending; pending = null
        if (uri != null && body != null) scope.launch {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                .onSuccess { vm.studio.message.value = "Saved." }.onFailure { vm.studio.message.value = "Couldn't save: ${it.message}" }
        }
    }
    val base = (vm.resume.selected.value?.name?.substringBeforeLast('.') ?: "resume") + "-" + name
    var sharing by remember { mutableStateOf(false) }
    if (sharing) {
        var contact by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { sharing = false }, title = { Text("Share") },
            text = { Column { Text("Emails, phone numbers and links are hidden unless you include them. Your tracker and full resume are not shared.")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(contact, { contact = it }); Text("Include contact details") } } },
            confirmButton = { TextButton({ sharing = false
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    ai.abhify.android.life.ShareKit.resumeSummary(text, contact)), "Share")) }) { Text("Share") } },
            dismissButton = { TextButton({ sharing = false }) { Text("Cancel") } })
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Copy") }
        TextButton({ sharing = true }) { Text("Share") }
        TextButton({ vm.life.save("resume", (vm.resume.selected.value?.name ?: "Resume") + " – " + name, text, "Career") }) { Text("⭳ Knowledge") }
        TextButton({ pending = text; saver.launch("$base.md") }) { Text("Save .md") }
        TextButton({ pending = text.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("(?m)^#+ "), ""); saver.launch("$base.txt") }) { Text("Save .txt") }
    }
}
