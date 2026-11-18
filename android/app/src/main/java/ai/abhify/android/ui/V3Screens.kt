package ai.abhify.android.ui

import ai.abhify.android.chat.LibraryDoc
import ai.abhify.android.chat.Note
import ai.abhify.android.docs.Documents
import ai.abhify.android.docs.Library
import ai.abhify.android.learn.Studio
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A scrollable tab body with the standard gutters and a max width for tablets. */
@Composable
private fun TabPage(content: @Composable ColumnScope.() -> Unit) = PageBody(content = content)

@Composable
private fun Chips(options: List<String>, selected: Int, onPick: (Int) -> Unit) = SectionChips(options, selected, onPick = onPick)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)

/** Shared: what is running (with Cancel), messages, the consent question, AI results and the document quiz. */
@Composable
fun StudioOverlays(vm: AppViewModel) {
    val s = vm.studio
    vm.consent.value?.let { c ->
        AlertDialog(onDismissRequest = { vm.decide(AppViewModel.Decision.CANCEL) }, title = { Text(c.title) }, text = { DialogText(c.detail, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ vm.decide(AppViewModel.Decision.ONLINE) }) { Text("Send to ${c.destination.substringBefore(" (")}") } },
            dismissButton = {
                Row {
                    if (vm.g.store.active() != null) TextButton({ vm.decide(AppViewModel.Decision.DEVICE) }) { Text("This phone") }
                    TextButton({ vm.decide(AppViewModel.Decision.CANCEL) }) { Text("Cancel") }
                }
            })
    }
    s.result.value?.let { r -> ResultDialog(vm, r) }
    vm.ocrAsk.value?.let { a ->
        var remember by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { vm.answerOcr(null, false) },
            title = { Text(if (a.pages == 1) "Read \"${a.name}\" with text recognition?" else "Read ${a.pages} scanned pages of \"${a.name}\"?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This needs text recognition (OCR). It runs on this phone with Google ML Kit: the image and its text are not sent anywhere. " +
                    "ML Kit sends anonymous usage and performance statistics to Google. The phone's AI pauses while it runs.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(remember, { remember = it }); Text("Remember my choice (change in Settings)") }
            } },
            confirmButton = { TextButton({ vm.answerOcr(true, remember) }) { Text("Read it") } },
            dismissButton = { androidx.compose.foundation.layout.FlowRow {
                if (a.pages > 0 && !a.name.substringAfterLast('.').lowercase().let { it in ai.abhify.android.docs.DocumentReader.IMAGE_EXT })
                    TextButton({ vm.answerOcr(false, false) }) { Text("Skip scanned pages") }
                TextButton({ vm.answerOcr(null, false) }) { Text("Cancel") } } })
    }
    if (s.quiz.isNotEmpty()) QuizDialog(vm)
}

@Composable
fun TaskStatus(vm: AppViewModel) {
    val s = vm.studio
    vm.taskBusy.value?.let { label ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            BusyBanner("Working: $label…", onCancel = vm::cancelTask)
        }
    }
    s.message.value?.let { m ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (m.startsWith("Failed") || m.startsWith("Couldn't")) LocalExtra.current.bad else MaterialTheme.colorScheme.tertiary)
            TextButton({ s.message.value = null }) { Text("OK") }
        }
    }
}

@Composable
private fun ResultDialog(vm: AppViewModel, r: StudioState.Result) {
    val s = vm.studio
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var confirmTasks by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { s.result.value = null },
        title = { Text(r.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.error?.let { Text("⚠ $it", color = LocalExtra.current.bad, style = MaterialTheme.typography.bodyLarge) }
                if (r.text.isNotBlank()) SelectionContainer { MarkdownText(r.text) }
                if (r.status != null) Text("⚠ Partial result (${when (r.status) { "length" -> "length limit"; "stopped" -> "cancelled"; else -> "interrupted" }}). Run it again for a complete one.",
                    color = LocalExtra.current.warn, fontWeight = FontWeight.SemiBold)
                if (r.sources.isNotEmpty()) {
                    val cited = Library.cited(r.text)
                    Text("Sources sent to the AI", style = MaterialTheme.typography.titleSmall)
                    r.sources.forEachIndexed { i, c ->
                        Text("[S${i + 1}] ${c.where}" + if (i + 1 in cited) "  ✓ cited" else "", style = MaterialTheme.typography.bodyMedium,
                            color = if (i + 1 in cited) MaterialTheme.colorScheme.onSurface else LocalExtra.current.muted)
                    }
                    if (cited.isEmpty() && r.text.isNotBlank()) Text("The answer cites no source: treat it as general knowledge, not from your documents.", color = LocalExtra.current.warn)
                }
                r.source?.let { Pill(it.label, if (it.cloud) MaterialTheme.colorScheme.tertiary else LocalExtra.current.ok) }
                if (r.actions.isNotEmpty()) {
                    Text("${r.actions.size} action items found", style = MaterialTheme.typography.titleSmall)
                    r.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton({ confirmTasks = true }) { Text("Add them as tasks…") }
                }
            }
        },
        confirmButton = { TextButton({ s.result.value = null }) { Text("Close") } },
        dismissButton = {
            if (r.text.isNotBlank()) androidx.compose.foundation.layout.FlowRow {
                TextButton({ clip.setText(AnnotatedString(r.text)); android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Copy") }
                TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.title + "\n\n" + r.text), "Share")) }) { Text("Share") }
                TextButton({ s.saveResultAsNote(r); s.result.value = null }) { Text("Save note") }
                TextButton({ vm.life.save("result", r.title, r.text + if (r.sources.isNotEmpty()) "\n\nSources:\n" + r.sources.mapIndexed { i, c -> "[S${i + 1}] ${c.where}" }.joinToString("\n") else "",
                    if (r.sources.isNotEmpty()) "Documents" else "AI answers"); s.result.value = null }) { Text("Save to Knowledge") }
            }
        })
    if (confirmTasks) {
        val ws = s.workspaces.firstOrNull { it.id == r.workspaceId } ?: s.workspace.value
        AlertDialog(onDismissRequest = { confirmTasks = false }, title = { Text("Add ${r.actions.size} tasks?") },
            text = { DialogText("They will be added to the tasks of \"${ws?.name ?: "your workspace"}\" on this phone. Nothing is sent or assigned to anyone.") },
            confirmButton = { TextButton({ confirmTasks = false; ws?.let { s.addTasks(it.id, r.actions) } }) { Text("Add tasks") } },
            dismissButton = { TextButton({ confirmTasks = false }) { Text("Cancel") } })
    }
}

@Composable
private fun QuizDialog(vm: AppViewModel) {
    val s = vm.studio
    val q = s.quiz.getOrNull(s.quizIndex.intValue)
    AlertDialog(onDismissRequest = s::closeQuiz, title = { Text(s.quizTitle.value) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q == null) Text("Done: ${s.quizScore.intValue} of ${s.quiz.size} correct.", style = MaterialTheme.typography.titleMedium)
                else {
                    Text("Question ${s.quizIndex.intValue + 1} of ${s.quiz.size}", color = LocalExtra.current.muted)
                    Text(q.question, style = MaterialTheme.typography.titleMedium)
                    q.options.forEachIndexed { i, o ->
                        val p = s.quizPicked.value
                        val c = when { p == null -> LocalExtra.current.line; i == q.correct -> LocalExtra.current.ok; i == p -> LocalExtra.current.bad; else -> LocalExtra.current.line }
                        OutlinedButton({ s.answerQuiz(i) }, Modifier.fillMaxWidth(), border = BorderStroke(if (p != null && (i == q.correct || i == p)) 2.dp else 1.dp, c),
                            shape = RoundedCornerShape(12.dp)) { Text("${'A' + i}.  $o", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface) }
                    }
                    s.quizPicked.value?.let { p ->
                        Text(if (p == q.correct) "✅ Correct" else "❌ The answer is ${'A' + q.correct}.", fontWeight = FontWeight.Bold)
                        if (q.why.isNotBlank()) Text(q.why, color = LocalExtra.current.muted)
                    }
                }
            }
        },
        confirmButton = { if (q != null && s.quizPicked.value != null) TextButton(s::nextQuiz) { Text("Next") } else TextButton(s::closeQuiz) { Text("Close") } })
}

// ====================================================================== DOCUMENTS
@Composable
fun DocumentsTab(vm: AppViewModel) {
    val s = vm.studio
    LaunchedEffect(s.docQuery.value) { s.loadDocs() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) s.importDoc(uri) }
    var ask by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<LibraryDoc?>(null) }
    var renaming by remember { mutableStateOf<Pair<LibraryDoc, String>?>(null) }
    var deleting by remember { mutableStateOf<LibraryDoc?>(null) }
    var compareFirst by remember { mutableStateOf<LibraryDoc?>(null) }
    var pdfView by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pdfView = uri to "PDF pages" }
    pdfView?.let { (u, t) -> PdfPagesDialog(u, t) { pdfView = null } }
    // 4.5.1 Documents: header + primary action, search/sort/view, a focused "Ask" card, document cards; the dozen
    // per-document actions moved from inline button rows into a grouped action sheet. Every action is unchanged.
    var grid by rememberSaveable { mutableStateOf(vm.g.prefs.getString("doc_view", "list") == "grid") }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    TabPage {
        PageHeader("Documents", if (s.docs.isEmpty()) "Read, search and ask on this phone" else "${s.docs.size} in your library · read on this phone", Accent.DOCS, "📚") {
            GradientButton("Add", { picker.launch(Documents.PICKER_TYPES) }, Modifier.width(96.dp), enabled = s.importing.value == null)
        }
        s.importing.value?.let { BusyBanner(it, s::cancelImport) }
        if (s.importWarnings.isNotEmpty()) Glass {
            Text("Check the last document", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.warn)
            s.importWarnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            TextButton({ s.importWarnings.clear() }) { Text("OK") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(s.docQuery.value, { s.docQuery.value = it }, "Search names and text", Modifier.weight(1f))
            if (s.docs.size > 1) ViewToggle(grid) { grid = it; vm.g.prefs.edit().putString("doc_view", if (it) "grid" else "list").apply() }
        }
        if (s.docs.size > 1) SectionChips(StudioState.DocSort.entries.map { it.label }, s.docSort.value.ordinal) { s.setDocSort(StudioState.DocSort.entries[it]) }
        if (s.docs.isNotEmpty() || s.docQuery.value.isNotEmpty()) Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon("✨", 20, MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp))
                Text(if (s.qaSelection.isEmpty()) "Ask your documents" else "Ask ${s.qaSelection.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (s.qaSelection.isNotEmpty()) TextButton({ s.qaSelection.clear() }) { Text("Clear") }
            }
            OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. What does the policy say about leave?") }, minLines = 1, maxLines = 4,
                shape = RoundedCornerShape(Dimens.fieldRadius))
            Button({ s.askLibrary(ask, only = s.qaSelection.toSet()) }, enabled = ask.isNotBlank() && vm.taskBusy.value == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.qaSelection.isEmpty()) "Ask all documents" else "Ask ${s.qaSelection.size} selected") }
            InfoNote("Tick documents to ask only those. Answers cite matching sections as [S1], [S2]… with PDF pages (keyword search on this phone).")
        }
        compareFirst?.let { a -> ErrorBanner("Comparing \"${a.name}\": pick another document.", onDismiss = { compareFirst = null }) }
        if (s.docs.isEmpty()) {
            if (s.docQuery.value.isBlank()) EmptyState("📚", "No documents yet", "Add a PDF, Word or text file, or a photo of a page. Text is read on this phone.")
            else EmptyState("🔍", "Nothing matches", "Try another name or word.")
        }
        fun open(d: LibraryDoc) { val a = compareFirst; if (a != null && a.id != d.id) { compareFirst = null; s.compare(a, d) } else menuFor = d }
        if (grid) s.docs.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                pair.forEach { d -> Glass(Modifier.weight(1f).fillMaxHeight(), padding = 14, onClick = { open(d) }) {
                    Row(verticalAlignment = Alignment.Top) {
                        TypeTile(d.kind, 40); Spacer(Modifier.weight(1f))
                        Checkbox(d.id in s.qaSelection, { on -> if (on) s.qaSelection += d.id else s.qaSelection -= d.id },
                            Modifier.semantics { contentDescription = "Select ${d.name} for questions" })
                    }
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${d.kind} · ${size(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    if (d.id in s.unindexed.value) StatusTag("Not indexed", 1)
                } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        } else s.docs.forEach { d ->
            Glass(padding = 12, onClick = { open(d) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeTile(d.kind); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${d.kind} · ${size(d.sizeBytes)} · ${"%,d".format(d.chars)} characters" + (if (d.truncated) " (cut at limit)" else "") +
                            (if (d.ocrPages > 0) " · OCR ${d.ocrPages} p." else "") + (if (d.role == "resume") " · resume" else "") + " · ${ago(d.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (d.id in s.unindexed.value) StatusTag("Not indexed yet: questions can't find it (open → Re-index)", 1)
                            if (d.warnings.isNotEmpty()) StatusTag("${d.warnings.size} warning" + (if (d.warnings.size > 1) "s" else ""), 1)
                        }
                    }
                    Checkbox(d.id in s.qaSelection, { on -> if (on) s.qaSelection += d.id else s.qaSelection -= d.id },
                        Modifier.semantics { contentDescription = "Select ${d.name} for questions" })
                }
            }
        }
        SettingsGroup("More tools") {
            LinkRow("📄", "Resume Assistant", "Upload a resume, check details, get suggestions and a job match") { vm.go(AppViewModel.Route.RESUME) }
            LinkRow("📕", "View PDF pages", "Open a PDF file as page images with zoom") { pdfPicker.launch(arrayOf("application/pdf")) }
            LinkRow("ⓘ", "About documents and privacy", if (aboutOpen) null else "What's supported and where text goes") { aboutOpen = !aboutOpen }
            if (aboutOpen) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint("PDF, DOCX, TXT, MD, CSV and photos/screenshots (JPEG, PNG, WebP) up to 10 MB. Text is extracted on this phone and kept here only: " +
                    "not synced, not added to memory. Scanned pages and images use text recognition (asks first). Online AI only gets document text after you agree.")
                Hint("Not available: handwriting and non-Latin scripts in OCR, and semantic (embedding) search.")
            }
        }
    }
    menuFor?.let { d ->
        ActionSheet(d.name, "${d.kind} · ${size(d.sizeBytes)} · ${ago(d.updatedAt)}", listOf(
            "Use" to listOf(
                SheetAction("💬", "Chat about it") { s.askInChat(d) },
                SheetAction("🔗", "Use in chat") { if (d.id !in vm.chatDocs) vm.chatDocs += d.id; vm.tab.value = AppViewModel.Tab.CHAT },
                SheetAction("🗓", "Study plan from it") { vm.hub.studyPlanFrom(d.id) },
            ) + StudioState.DocAction.entries.map { a -> SheetAction("✨", a.label, enabled = vm.taskBusy.value == null) { s.docAction(d, a) } },
            "View" to listOfNotNull(
                SheetAction("👁", "View text") { s.view(d) },
                if (d.kind == "PDF") SheetAction("📕", "View pages…") { pdfPicker.launch(arrayOf("application/pdf")) } else null,
            ),
            "Organize" to listOf(
                SheetAction("🔖", "Save to Knowledge") { vm.life.saveDocument(d) },
                SheetAction("☁", "Copy text to Google Drive", enabled = vm.drive.busy.value == null) { vm.drive.uploadDocText(d) },
                SheetAction("⟳", "Re-index") { s.reindex(d) },
                SheetAction("🔀", "Compare with…") { compareFirst = d },
                SheetAction("✏", "Rename") { renaming = d to d.name },
            ),
            null to listOf(SheetAction("🗑", "Delete", danger = true) { deleting = d }),
        )) { menuFor = null }
    }
    s.viewing.value?.let { (d, text) ->
        AlertDialog(onDismissRequest = { s.viewing.value = null }, confirmButton = { TextButton({ s.viewing.value = null }) { Text("Close") } },
            title = { Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { DocumentText(text) })
    }
    renaming?.let { (d, name) ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { renaming = d to it.take(200) }, singleLine = true) },
            confirmButton = { TextButton({ s.rename(d, name); renaming = null }) { Text("Save") } }, dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { d ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${d.name}?") },
            text = { DialogText("The document and its extracted text are removed from this phone. Flashcards and notes made from it stay.") },
            confirmButton = { TextButton({ s.delete(d); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
}

private fun size(b: Long) = when { b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

// ====================================================================== LEARN additions
@Composable
fun TutorView(vm: AppViewModel) {
    val s = vm.studio
    var practiceQ by rememberSaveable { mutableStateOf("") }
    var practiceA by rememberSaveable { mutableStateOf("") }
    TabPage {
        // 4.6: the tutor as a conversation partner: orb + topic + level, then a separate "check my answer" studio
        PageHeader("AI tutor", "Explanations at your level, each with a practice question", Accent.CHAT, "💡")
        AccentCard(Accent.CHAT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiOrb(orbStateOf(vm), 40); Spacer(Modifier.width(10.dp))
                Text("What should I explain?", style = MaterialTheme.typography.titleMedium)
            }
            Field(s.tutorTopic.value, { s.tutorTopic.value = it.take(200) }, "Topic (e.g. backpropagation)")
            SegmentedTabs(Studio.Level.entries.map { it.label }, s.tutorLevel.value.ordinal, accent = Accent.CHAT) { s.tutorLevel.value = Studio.Level.entries[it] }
            GradientButton("Explain it", { s.teach() }, Modifier.fillMaxWidth(), enabled = s.tutorTopic.value.isNotBlank() && vm.taskBusy.value == null)
            InfoNote("Uses your AI mode (${vm.aiMode.value.label}). Each explanation ends with a practice question.")
        }
        SettingsGroup("Check my answer") {
            Field(practiceQ, { practiceQ = it.take(500) }, "The practice question")
            OutlinedTextField(practiceA, { practiceA = it.take(3000) }, Modifier.fillMaxWidth(), label = { Text("Your answer") }, minLines = 3, maxLines = 8)
            Button({ s.evaluate(practiceQ, practiceA) }, enabled = practiceQ.isNotBlank() && practiceA.isNotBlank() && vm.taskBusy.value == null) { Text("Evaluate") }
            Hint("Feedback is the AI's opinion, not a validated grade.")
        }
    }
}

@Composable
fun PlansView(vm: AppViewModel) {
    val s = vm.studio
    LaunchedEffect(Unit) { s.loadPlans() }
    var topic by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.planTopicPrefill.value) { s.planTopicPrefill.value?.let { topic = it; s.open(null); s.planTopicPrefill.value = null } }
    var goal by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    var level by rememberSaveable { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<ai.abhify.android.chat.StudyPlan?>(null) }
    val planDocs = remember { mutableStateListOf<Long>() }
    var editingLesson by remember { mutableStateOf<ai.abhify.android.chat.PlanItem?>(null) }
    var newLesson by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { s.loadDocs() }
    LaunchedEffect(s.planDocPreset.size) { if (s.planDocPreset.isNotEmpty()) { planDocs.clear(); planDocs.addAll(s.planDocPreset); s.planDocPreset.clear()
        s.message.value = "Document selected for a new plan: enter a topic and tap Create plan." } }
    TabPage {
        val open = s.openPlan.value
        if (open != null) {
            // 4.6: plan hero with a progress ring, then the lessons as a checklist timeline
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(ai.abhify.android.R.drawable.ic_arrow_back, "Back to plans", { s.open(null) })
                Text("Plans", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
            }
            val done = s.planItems.count { it.done }
            AccentCard(Accent.STUDY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(if (s.planItems.isEmpty()) 0f else done / s.planItems.size.toFloat(), Accent.STUDY, 64, "$done of ${s.planItems.size} lessons done")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(open.topic, style = MaterialTheme.typography.titleLarge)
                        Text("${open.level} · $done of ${s.planItems.size} lessons" + (open.targetDate?.let { " · target $it" } ?: "") + (open.minutesPerDay?.let { " · $it min/day" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                }
                if (s.planDocNames.isNotEmpty()) Text("From: " + s.planDocNames.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
            Glass(padding = 8) {
                s.planItems.forEach { i ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i.done, { s.toggleLesson(i) }, Modifier.semantics { contentDescription = "Lesson ${i.position + 1} done" },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Accent.STUDY.g1))
                        Text(i.text, style = MaterialTheme.typography.bodyLarge, color = if (i.done) LocalExtra.current.muted else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (i.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                        IconAction(ai.abhify.android.R.drawable.ic_edit, "Edit lesson ${i.position + 1}", { editingLesson = i })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(newLesson, { newLesson = it.take(300) }, "Add a lesson", modifier = Modifier.weight(1f))
                TextButton({ s.addLesson(newLesson); newLesson = "" }, enabled = newLesson.isNotBlank()) { Text("Add") }
            }
            TextButton({ deleting = open }) { Text("Delete this plan", color = LocalExtra.current.bad) }
        } else {
            PageHeader("Study plans", if (s.plans.isEmpty()) "A day-by-day path for any topic" else "${s.plans.size} plan" + (if (s.plans.size == 1) "" else "s"), Accent.STUDY, "🗓")
            SettingsGroup("New study plan") {
                Field(topic, { topic = it.take(120) }, "Topic")
                Chips(Studio.Level.entries.map { it.label }, level) { level = it }
                Field(goal, { goal = it.take(200) }, "Goal (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(target, { target = it.take(30) }, "Target date", modifier = Modifier.weight(1f))
                    Field(minutes, { minutes = it.filter(Char::isDigit).take(3) }, "Min/day", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, modifier = Modifier.weight(1f))
                }
                if (s.docs.isNotEmpty()) {
                    Text("Base it on documents (optional)", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.docs.take(20).forEach { d -> FilterChip(d.id in planDocs, { if (d.id in planDocs) planDocs -= d.id else planDocs += d.id }, { Text(d.name.take(28)) }) } }
                }
                Hint("Target date as 2026-12-31. The plan is checked against the time you have; you can edit, add or remove lessons afterwards.")
                GradientButton("Create plan with AI", { s.makePlan(topic, Studio.Level.entries[level], goal, target, minutes.toIntOrNull() ?: 30, planDocs.toList()) },
                    Modifier.fillMaxWidth(), enabled = topic.isNotBlank() && vm.taskBusy.value == null)
            }
            if (s.plans.isEmpty()) EmptyState("🗓", "No plans yet", "Enter a topic above and tap Create plan.")
            s.plans.forEach { p ->
                val (d, n) = s.planProgress.value[p.id] ?: (0 to 0)
                AccentCard(Accent.STUDY, onClick = { s.open(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(if (n == 0) 0f else d / n.toFloat(), Accent.STUDY, 48, "$d of $n lessons done")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.topic, style = MaterialTheme.typography.titleSmall)
                            Text("${p.level} · $d of $n lessons done", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    editingLesson?.let { i -> var t by remember(i.id) { mutableStateOf(i.text) }
        AlertDialog(onDismissRequest = { editingLesson = null }, title = { Text("Edit lesson") },
            text = { OutlinedTextField(t, { t = it.take(500) }, minLines = 2) },
            confirmButton = { TextButton({ s.editLesson(i, t); editingLesson = null }, enabled = t.isNotBlank()) { Text("Save") } },
            dismissButton = { androidx.compose.foundation.layout.FlowRow { TextButton({ s.deleteLesson(i); editingLesson = null }) { Text("Delete", color = LocalExtra.current.bad) }; TextButton({ editingLesson = null }) { Text("Cancel") } } }) }
    deleting?.let { p -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete the plan \"${p.topic}\"?") },
        confirmButton = { TextButton({ s.deletePlan(p); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
fun CardsView(vm: AppViewModel) {
    val s = vm.studio
    LaunchedEffect(Unit) { s.loadDecks(); s.loadDashboard() }
    var topic by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    TabPage {
        val deck = s.reviewDeck.value
        if (deck != null) {
            val c = s.reviewCards.getOrNull(s.reviewIndex.intValue)
            Row(verticalAlignment = Alignment.CenterVertically) { IconAction(ai.abhify.android.R.drawable.ic_arrow_back, "Back to decks", { s.review(null) })
                Text(if (deck == StudioState.DUE_ALL) "All due cards" else deck, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (s.reviewCards.isNotEmpty()) Text("${(s.reviewIndex.intValue + 1).coerceAtMost(s.reviewCards.size)} / ${s.reviewCards.size}", style = MaterialTheme.typography.labelLarge, color = Accent.MEMORY.text) }
            if (s.reviewCards.isNotEmpty()) {
                val prog by androidx.compose.animation.core.animateFloatAsState(s.reviewIndex.intValue / s.reviewCards.size.toFloat(), Motion.tween(Motion.EMPHASIS, reducedMotion()), label = "deck")
                LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(6.dp), color = Accent.MEMORY.g2, trackColor = LocalExtra.current.line, drawStopIndicator = {}, gapSize = 0.dp)
            }
            if (s.practiceAll.value) Hint("Nothing in this deck is due yet, so you're practising all of it. Ratings still update the schedule.")
            if (c == null) Glass { Text(if (s.reviewCards.isEmpty()) "Nothing is due. Come back later." else "Review done.", style = MaterialTheme.typography.titleMedium)
                Button({ s.review(null) }) { Text("Back to decks") } }
            else {
                // 4.6: the card flips (rotationY) to reveal the answer; instant with reduced motion
                val turn by androidx.compose.animation.core.animateFloatAsState(if (s.showBack.value) 180f else 0f, Motion.tween(420, reducedMotion(), Motion.Emphasized), label = "flip")
                val x = LocalExtra.current
                Column(Modifier.fillMaxWidth().heightIn(min = 200.dp).graphicsLayer { rotationY = turn; cameraDistance = 12f * density }
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (turn <= 90f) Accent.MEMORY.brush else Brush.linearGradient(listOf(Accent.MEMORY.g1.copy(alpha = if (x.dark) 0.25f else 0.12f), x.glass)))
                    .clickable(onClickLabel = if (s.showBack.value) "Show the question" else "Show the answer") { s.showBack.value = !s.showBack.value }.padding(20.dp),
                    verticalArrangement = Arrangement.Center) {
                    Column(Modifier.fillMaxWidth().graphicsLayer { rotationY = if (turn > 90f) 180f else 0f }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (turn <= 90f) {
                            Text("Question" + if (c.known) " · known before" else "", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                            Text(c.front, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("Tap to flip", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                        } else {
                            Text("Answer", style = MaterialTheme.typography.labelLarge, color = Accent.MEMORY.text)
                            Text(c.back, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (s.showBack.value) {
                    // v3.9: four ratings; each shows when the card would come back
                    val st = s.reviewStates[c.id] ?: ai.abhify.android.learn.Srs.State()
                    val now = System.currentTimeMillis()
                    Text("How well did you know it?", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tones = listOf(Accent.CAREER, Accent.KNOWLEDGE, Accent.STUDY, Accent.DOCS)
                        ai.abhify.android.learn.Srs.Rating.entries.forEachIndexed { k, r ->
                            val a = tones[k % tones.size]
                            Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(Dimens.fieldRadius))
                                .background(a.g1.copy(alpha = if (LocalExtra.current.dark) 0.22f else 0.10f)).clickable(onClickLabel = r.label) { s.rate(r) }
                                .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(r.label, style = MaterialTheme.typography.labelLarge, color = a.text)
                                Text(ai.abhify.android.learn.Srs.label(ai.abhify.android.learn.Srs.next(st, r, now), now), style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
                            }
                        }
                    }
                }
            }
        } else {
            PageHeader("Flashcards", "Spaced repetition: cards come back just before you'd forget", Accent.MEMORY, "🃏")
            SettingsGroup("Flashcards") {
                Field(topic, { topic = it.take(120) }, "Make cards about a topic")
                GradientButton("Write 10 cards with AI", { s.cardsFromTopic(topic) }, Modifier.fillMaxWidth(), enabled = topic.isNotBlank() && vm.taskBusy.value == null)
                Hint("You can also make cards from a document (Documents → a document → Flashcards).")
                s.dashboard.value?.cards?.let { st -> if (st.total > 0) Button({ s.review(StudioState.DUE_ALL) }, Modifier.fillMaxWidth(), enabled = st.due > 0) {
                    Text(if (st.due > 0) "Review ${st.due} due card(s) from all decks" else "No cards due") } }
                Hint("Spaced repetition: cards you know come back after longer gaps (Again 10 min → Good 1 day, 3 days, then longer; at most 180 days).")
            }
            if (s.decks.isEmpty()) EmptyState("🃏", "No decks yet", "Write cards about a topic above, or make them from a document.")
            s.decks.forEach { (name, counts) ->
                AccentCard(Accent.MEMORY, onClick = { s.review(name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(Accent.MEMORY, "🃏", 40); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text("${counts.first} cards · ${counts.second} known", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconAction(ai.abhify.android.R.drawable.ic_delete_outline, "Delete deck $name", { deleting = name })
                    }
                }
            }
        }
    }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete the deck \"$d\"?") },
        confirmButton = { TextButton({ s.deleteDeck(d); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

// ====================================================================== WORK
@Composable
fun WorkTab(vm: AppViewModel) {
    val s = vm.studio
    LaunchedEffect(Unit) { s.loadWork() }
    LaunchedEffect(s.noteQuery.value) { s.reloadWorkspace() }
    var section by rememberSaveable { mutableStateOf(0) }
    var newWs by remember { mutableStateOf<String?>(null) }
    val w = s.workspace.value
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                s.workspaces.forEach { ws -> FilterChip(w?.id == ws.id, { s.select(ws) }, { Text((if (ws.kind == "work") "🏢 " else "🏠 ") + ws.name) }) }
                TextButton({ newWs = "" }) { Text("＋ Workspace") }
            }
            SegmentedTabs(listOf("Tasks", "Notes", "Meeting notes", "Knowledge", "Write", "Linked"), section,
                glyphs = listOf("✅", "📝", "🗓", "🔖", "✏", "🔗"), accent = Accent.KNOWLEDGE) { section = it }
        }
        Box(Modifier.weight(1f)) {
            when (section) {
                0 -> TasksView(vm); 1 -> NotesView(vm); 2 -> MeetingView(vm); 3 -> KnowledgeView(vm); 4 -> WriteView(vm); else -> LinkedView(vm)
            }
        }
    }
    newWs?.let { name ->
        var kind by remember { mutableStateOf("work") }
        AlertDialog(onDismissRequest = { newWs = null }, title = { Text("New workspace") },
            text = { Column { OutlinedTextField(name, { newWs = it.take(60) }, singleLine = true, label = { Text("Name") })
                Row { FilterChip(kind == "work", { kind = "work" }, { Text("Work") }); Spacer(Modifier.width(6.dp)); FilterChip(kind == "personal", { kind = "personal" }, { Text("Personal") }) }
                Hint("Workspaces live on this phone. Shared team workspaces, roles and permissions need an organisation server, which isn't available yet.") } },
            confirmButton = { TextButton({ s.addWorkspace(name, kind); newWs = null }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton({ newWs = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TasksView(vm: AppViewModel) {
    val s = vm.studio
    var title by rememberSaveable { mutableStateOf("") }
    TabPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(title, { title = it.take(300) }, "New task", modifier = Modifier.weight(1f))
            Button({ s.addTask(title); title = "" }, enabled = title.isNotBlank()) { Text("Add") }
        }
        val open = s.tasks.count { !it.done }
        var deleting by remember { mutableStateOf<ai.abhify.android.chat.Task?>(null) }
        Hint("$open open · ${s.tasks.size - open} done · reminders are set from Home → Today; calendar sync isn't connected")
        s.tasks.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t.done, { s.toggleTask(t) }, Modifier.semantics { contentDescription = "Done: ${t.title}" })
                Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (t.done) LocalExtra.current.muted else MaterialTheme.colorScheme.onSurface)
                IconAction(ai.abhify.android.R.drawable.ic_delete_outline, "Delete task ${t.title}", { deleting = t })
            }
        }
        deleting?.let { t -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this task?") }, text = { DialogText("\"${t.title}\" is removed from this workspace.") },
            confirmButton = { TextButton({ s.deleteTask(t); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
    }
}

@Composable
private fun NotesView(vm: AppViewModel) {
    val s = vm.studio
    var reportTitle by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    TabPage {
        val e = s.editing.value
        if (e != null) NoteEditor(vm, e)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(s.noteQuery.value, { s.noteQuery.value = it }, "Search notes", modifier = Modifier.weight(1f))
                Button({ s.editing.value = Note(-1, s.workspace.value?.id ?: -1, "", "", "note", 0) }) { Text("＋ Note") }
            }
            if (s.notes.isEmpty()) EmptyState("📝", "No notes here yet")
            s.notes.forEach { n ->
                Glass(onClick = { s.editing.value = n }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n.id in selected, { if (it) selected += n.id else selected -= n.id }, Modifier.semantics { contentDescription = "Use ${n.title} in a report" })
                        Column(Modifier.weight(1f)) {
                            Text((when (n.kind) { "meeting" -> "🗓 "; "ai" -> "✨ "; else -> "" }) + n.title, style = MaterialTheme.typography.titleSmall)
                            Text(n.body.take(140), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) Glass {
                Text("Report from ${selected.size} selected notes", style = MaterialTheme.typography.titleMedium)
                Field(reportTitle, { reportTitle = it.take(100) }, "Report title")
                GradientButton("Write report with AI", { s.report(reportTitle, s.notes.filter { it.id in selected }) }, Modifier.fillMaxWidth(), enabled = vm.taskBusy.value == null)
            }
        }
    }
}

@Composable
private fun NoteEditor(vm: AppViewModel, n: Note) {
    val s = vm.studio
    var title by remember(n.id) { mutableStateOf(n.title) }
    var body by remember(n.id) { mutableStateOf(n.body) }
    var deleting by remember { mutableStateOf(false) }
    Glass {
        Field(title, { title = it.take(120) }, "Title")
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 6, maxLines = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ s.saveNote(n.id.takeIf { it > 0 }, title, body, n.kind) }, enabled = body.isNotBlank() || title.isNotBlank()) { Text("Save") }
            TextButton({ s.editing.value = null }) { Text("Cancel") }
            if (n.id > 0) TextButton({ deleting = true }) { Text("Delete", color = LocalExtra.current.bad) }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this note?") },
        confirmButton = { TextButton({ s.deleteNote(n); s.editing.value = null; deleting = false }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

@Composable
private fun MeetingView(vm: AppViewModel) {
    val s = vm.studio
    var raw by rememberSaveable { mutableStateOf("") }
    TabPage {
        SettingsGroup("Meeting notes → summary and action items") {
            OutlinedTextField(raw, { raw = it }, Modifier.fillMaxWidth(), placeholder = { Text("Paste or type the raw notes…") }, minLines = 8, maxLines = 20)
            GradientButton("Structure with AI", { s.meetingNotes(raw) }, Modifier.fillMaxWidth(), enabled = raw.isNotBlank() && vm.taskBusy.value == null)
            Hint("The result is saved as a note in this workspace. Action items become tasks only after you confirm the list.")
        }
    }
}

@Composable
private fun KnowledgeView(vm: AppViewModel) {
    val s = vm.studio
    val w = s.workspace.value
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && w != null) s.importDoc(uri, w.id).also { s.reloadWorkspace() } }
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.importing.value) { if (s.importing.value == null) s.reloadWorkspace() }
    TabPage {
        Glass {
            Text("${w?.name ?: "Workspace"} knowledge", style = MaterialTheme.typography.titleMedium)
            Hint("Documents added here (SOPs, policies, manuals) stay in this workspace on this phone and are searched separately from your personal library.")
            GradientButton("＋ Add document to this workspace", { picker.launch(Documents.PICKER_TYPES) }, Modifier.fillMaxWidth(), enabled = w != null && s.importing.value == null)
            s.importing.value?.let { Text(it, color = LocalExtra.current.muted) }
        }
        if (s.workDocs.isNotEmpty()) Glass {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ask about these documents (e.g. the leave approval steps)") }, maxLines = 4)
            Button({ s.askLibrary(q, w?.id) }, enabled = q.isNotBlank() && vm.taskBusy.value == null) { Text("Ask with sources") }
        }
        s.workDocs.forEach { d ->
            Glass {
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                Text("${d.kind} · ${"%,d".format(d.chars)} characters", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Row { TextButton({ s.docAction(d, StudioState.DocAction.SUMMARY) }) { Text("Summary") }; TextButton({ s.view(d) }) { Text("View") }
                    TextButton({ s.delete(d); s.reloadWorkspace() }) { Text("Remove", color = LocalExtra.current.bad) } }
            }
        }
        SettingsGroup("Organisation features") {
            Hint("Team workspaces, organisation sign-in, roles, document permissions, audit history and training assignments need an organisation server. " +
                "They are not available in this version; nothing here is shared with anyone.")
        }
    }
}

@Composable
private fun WriteView(vm: AppViewModel) {
    val s = vm.studio
    var purpose by rememberSaveable { mutableStateOf("") }
    var tone by rememberSaveable { mutableStateOf(0) }
    val tones = listOf("Professional", "Friendly", "Concise", "Formal")
    TabPage {
        SettingsGroup("Email draft") {
            OutlinedTextField(purpose, { purpose = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("What should the email say? (e.g. ask the team to submit timesheets by Friday)") }, minLines = 3)
            Chips(tones, tone) { tone = it }
            GradientButton("Draft with AI", { s.email(purpose, tones[tone]) }, Modifier.fillMaxWidth(), enabled = purpose.isNotBlank() && vm.taskBusy.value == null)
            Hint("Abhify never sends email. Copy or share the draft into your mail app.")
        }
        Glass(onClick = { vm.go(AppViewModel.Route.RESUME) }) {
            Text("📄 Resume Assistant", style = MaterialTheme.typography.titleMedium)
            Hint("Analyse and improve your resume without inventing anything; compare it with a job description.")
        }
        SettingsGroup("Reports") {
            Hint("Select notes in Notes and tap \"Write report with AI\".")
        }
    }
}

// ====================================================================== SETTINGS
/**
 * 5.3 "More", the fifth destination, grouped as the owner specified: Knowledge · Workspace · Career · AI & System ·
 * Settings & Privacy. Every destination appears exactly once across More and Settings: the screens to open live here,
 * while Settings keeps the settings themselves (AI mode, answer style, appearance, voice, performance, diagnostics).
 */
@Composable
fun SettingsTab(vm: AppViewModel) {
    val acct = vm.account.value
    var promptsOpen by remember { mutableStateOf(false) }
    TabPage {
        PageHeader("More", "Knowledge, workspace, career, AI & system, settings & privacy", Accent.SYSTEM, "⚙")
        val pending = vm.knowledge.updates.count { it.status == ai.abhify.android.memory.KnowledgeUpdates.Status.PENDING }
        SettingsGroup("Knowledge", accent = Accent.KNOWLEDGE) {
            NavRow("📚", "Documents", "Your library: import, search, ask, PDF pages") { vm.tab.value = AppViewModel.Tab.DOCS }
            NavRow("🔖", "Knowledge & collections", "Your knowledge vault, workspaces, notes, meeting notes") { vm.tab.value = AppViewModel.Tab.KNOWLEDGE }
            NavRow("🧩", "Knowledge Center", "Local RAG, semantic search, index, corrections" + (if (pending > 0) " · $pending to review" else "")) { vm.go(AppViewModel.Route.SEMANTIC) }
            NavRow("🧠", "Personal memory", if (vm.memoriesEnabled.value) "On · only relevant memories are used" else "Off") { vm.go(AppViewModel.Route.MEMORIES) }
            NavRow("🔎", "Search everything", "Chats, documents, knowledge, notes") { vm.go(AppViewModel.Route.SEARCH) }
        }
        SettingsGroup("Workspace", accent = Accent.CHAT) {
            NavRow("🗂", "Projects", "Status, milestones, documents, chat, AI tasks and study per project") { vm.projects.openList() }
            NavRow("✅", "AI Task Runner", "Summaries, study plans, quizzes and briefings in steps") { vm.go(AppViewModel.Route.TASKS) }
            NavRow("🕘", "Conversation history & export", null) { vm.go(AppViewModel.Route.HISTORY) }
            NavRow("📋", "Saved prompts", "${vm.prompts.size} saved") { vm.loadPrompts(); promptsOpen = true }
            NavRow("🕶", "New private chat", "Not saved, not synced, no new memories") { vm.setPrivate(true); vm.tab.value = AppViewModel.Tab.CHAT }
        }
        SettingsGroup("Career", accent = Accent.CAREER) {
            NavRow("💼", "Career", "Dashboard, resumes, jobs, match · Question Bank and MCQs are also in Study") { vm.tab.value = AppViewModel.Tab.CAREER }
            NavRow("📄", "Resume Assistant (classic)", null) { vm.go(AppViewModel.Route.RESUME) }
        }
        SettingsGroup("AI & System", accent = Accent.STUDY) {
            NavRow("📦", "AI models on this phone", (vm.activeModel()?.name ?: "None downloaded") + " · download, switch, speed test") { vm.go(AppViewModel.Route.MODELS) }
            NavRow("🔑", "AI providers (online, your keys)", vm.states().provider) { vm.go(AppViewModel.Route.PROVIDERS) }
            NavRow("🔌", "Connection mode", vm.serverMode().label) { vm.go(AppViewModel.Route.CONNECTION) }
            NavRow("🔄", "Sync & network", vm.status().text) { vm.go(AppViewModel.Route.SYNC) }
            NavRow("🧭", "Run setup again", "Walk through model, providers, OCR and privacy (nothing is reset)") { vm.rerunSetup() }
        }
        SettingsGroup("Documents and text recognition") {
            Text("OCR engine: Google ML Kit, Latin script, included in the app (no download). Images and their text stay on this phone; ML Kit sends anonymous usage statistics to Google.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Pair<Boolean?, String>>(null to "Ask each time", true to "Allow", false to "Off").forEach { (v, l) -> FilterChip(vm.ocrAllowed.value == v, { vm.setOcrAllowed(v) }, { Text(l) }) }
            }
            OutlinedButton(vm::ocrSelfTest) { Text("Run OCR self-test") }
            vm.ocrTest.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        SettingsGroup("Settings & Privacy", accent = Accent.SYSTEM) {
            NavRow("⚙", "Settings", "AI mode, answer style, appearance, voice, performance, diagnostics") { vm.go(AppViewModel.Route.SETTINGS) }
            NavRow("🔒", "Privacy & data deletion", if (vm.onlineAllowed.value) "Online AI allowed" else "Local only") { vm.go(AppViewModel.Route.PRIVACY) }
            NavRow("♻", "Backup & restore", "Local file or Google Drive · restore adds only") { vm.go(AppViewModel.Route.BACKUP) }
            NavRow("☁", "Google Drive", if (vm.drive.connected.value) "Connected · ${vm.drive.account.value?.label ?: "your Google account"}" else "Not connected · optional") { vm.go(AppViewModel.Route.DRIVE) }
            NavRow("📊", "Storage", "Space used, clear cache") { vm.go(AppViewModel.Route.STORAGE) }
            NavRow("👤", "Abhify account", (acct as? ai.abhify.android.cloud.Account.SignedIn)?.session?.email ?: "Not signed in · not needed for your own keys") { vm.go(AppViewModel.Route.ACCOUNT) }
            NavRow("ⓘ", "About Abhify", ai.abhify.android.BuildConfig.VERSION_NAME) { vm.go(AppViewModel.Route.ABOUT) }
        }
    }
    if (promptsOpen) PromptLibraryDialog(vm) { promptsOpen = false }
}


/**
 * v4.3 document viewer body: the text split into paragraphs and rendered lazily (only what is on screen is laid out),
 * page markers shown as small headings, normal paragraph spacing. Same content and the same 60,000-character view limit.
 */
@Composable
private fun DocumentText(text: String) {
    val parts = remember(text) {
        text.take(60_000).split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() } +
            (if (text.length > 60_000) listOf("… (${"%,d".format(text.length - 60_000)} more characters stored; the AI features use all of it)") else emptyList())
    }
    val page = remember { Regex("^— Page (\\d+) —\\s*") }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
        items(parts.size) { i ->
            val p = parts[i]
            val m = page.find(p)
            SelectionContainer { Column {
                if (m != null) Text("Page ${m.groupValues[1]}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Dimens.small))
                val body = if (m != null) p.substring(m.value.length) else p
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
            } }
        }
    }
}
