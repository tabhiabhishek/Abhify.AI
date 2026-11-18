package ai.abhify.android.ui

import ai.abhify.android.R
import ai.abhify.android.task.QuizItem
import ai.abhify.android.task.StepStatus
import ai.abhify.android.task.Task
import ai.abhify.android.task.TaskRunner
import ai.abhify.android.task.TaskSource
import ai.abhify.android.task.TaskStatus
import ai.abhify.android.task.Template
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun templateAccent(t: Template) = when (t) { Template.SUMMARY -> Accent.DOCS; Template.STUDY -> Accent.STUDY; Template.QUIZ -> Accent.CAREER
    Template.NEWS -> Accent.KNOWLEDGE; Template.CUSTOM -> Accent.CHAT }
private fun templateGlyph(t: Template) = when (t) { Template.SUMMARY -> "📝"; Template.STUDY -> "🗓"; Template.QUIZ -> "❔"; Template.NEWS -> "📰"; Template.CUSTOM -> "✨" }

/** AI Task Runner: pick a workflow, a source and the model, then follow the steps; results stay with their sources. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(vm: AppViewModel) {
    val ts = vm.tasks
    LaunchedEffect(Unit) { vm.life.loadVault(); vm.newsCenter.prefs.value }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ts.preset.value = null } }      // a project preset never sticks to later visits
    var modelsOpen by remember { mutableStateOf(false) }
    var deletingTask by remember { mutableStateOf<ai.abhify.android.task.Task?>(null) }
    androidx.activity.compose.BackHandler(enabled = ts.current.value != null && !ts.running) { ts.open(null) }   // 6.1: system Back closes the open task first
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTitle("AI Task Runner", { if (ts.current.value != null && !ts.running) ts.open(null) else vm.back() })
        PageBody(gap = Dimens.gap) {
            val cur = ts.current.value
            ts.message.value?.let { ErrorBanner(it, onDismiss = { ts.message.value = null }) }
            if (cur != null) TaskView(vm, cur) else NewTask(vm) { modelsOpen = true }
            SectionTitle("Recent tasks")
            if (ts.tasks.isEmpty()) InfoNote("No tasks yet. Results are kept here (on this phone) until you delete them; they are never added to your memory.")
            ts.tasks.take(12).forEach { t ->
                Glass(padding = 10, onClick = { ts.open(t) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(templateAccent(Template.of(t.type)), templateGlyph(Template.of(t.type)), 34); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${t.status.name.lowercase()} · ${ago(t.createdAt)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        }
                        if (!ts.isActive(t)) IconAction(R.drawable.ic_delete_outline, "Delete task ${t.title}", { deletingTask = t })
                    }
                }
            }
        }
    }
    if (modelsOpen) ModelSheet(vm) { modelsOpen = false }
    deletingTask?.let { t -> AlertDialog(onDismissRequest = { deletingTask = null }, title = { Text("Delete this AI task?") },
        text = { DialogText("\"${t.title}\" and its results are removed from this phone. Its sources are not touched.") },
        confirmButton = { TextButton({ ts.delete(t); deletingTask = null }) { Text("Delete", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ deletingTask = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewTask(vm: AppViewModel, onModels: () -> Unit) {
    val ts = vm.tasks
    val preset = ts.preset.value                                         // opened from a project: its documents are the source
    var tpl by rememberSaveable { mutableStateOf(Template.SUMMARY.id) }
    val t = Template.of(tpl)
    var kind by rememberSaveable(tpl) { mutableStateOf(if (preset != null && "project" in t.sources) "project" else t.sources.first()) }
    var request by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var docId by rememberSaveable { mutableStateOf<Long?>(null) }
    var vaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var projectId by rememberSaveable { mutableStateOf(preset?.id) }
    var count by rememberSaveable { mutableStateOf("5") }
    var level by rememberSaveable { mutableStateOf("mixed") }
    val cats = remember { mutableStateListOf("foryou") }
    var docs by remember { mutableStateOf(emptyList<ai.abhify.android.chat.LibraryDoc>()) }
    LaunchedEffect(Unit) { docs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ts.sourceDocs } }   // 6.1: off the main thread
    LaunchedEffect(Unit) { vm.studio.loadWork() }
    PageHeader("New task", "Multi-step AI on your documents, Knowledge, news or text", Accent.CHAT, "✅")
    Template.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { x -> val on = x.id == tpl
                Column(Modifier.weight(1f).heightIn(min = 86.dp).clip(RoundedCornerShape(16.dp))
                    .background(templateAccent(x).g1.copy(alpha = if (on) 0.30f else if (LocalExtra.current.dark) 0.14f else 0.07f))
                    .border(if (on) 2.dp else 1.dp, if (on) templateAccent(x).g1 else LocalExtra.current.line, RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = x.title) { tpl = x.id }.semantics { contentDescription = x.title + if (on) ", selected" else "" }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentIcon(templateAccent(x), templateGlyph(x), 30)
                    Text(x.title, style = MaterialTheme.typography.labelLarge)
                    Text(x.steps.drop(1).joinToString(" → ") { it.title }, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted, maxLines = 2)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (t == Template.CUSTOM) Field(request, { request = it.take(1000) }, "What should the task do?")
    val labels = mapOf("document" to "Document", "knowledge" to "Knowledge", "project" to "Project", "news" to "News", "text" to "Text")
    SegmentedTabs(t.sources.map { labels.getValue(it) }, t.sources.indexOf(kind).coerceAtLeast(0), accent = templateAccent(t)) { kind = t.sources[it] }
    when (kind) {
        "document" -> if (docs.isEmpty()) InfoNote("No documents yet: add one in More → Documents.") else
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { docs.take(30).forEach { d -> FilterChip(docId == d.id, { docId = d.id }, { Text(d.name.take(30)) }) } }
        "project" -> {
            val active = vm.studio.workspaces.filter { !vm.projects.isArchived(it.id) }
            if (active.isEmpty()) InfoNote("No projects yet: create one in Knowledge & workspaces.") else
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { active.forEach { w -> FilterChip(projectId == w.id, { projectId = w.id }, { Text("🗂 ${w.name.take(28)}") }) } }
            InfoNote("Uses the project's own and linked documents only, never other projects.")
        }
        "knowledge" -> if (vm.life.vault.isEmpty()) InfoNote("Nothing saved in Knowledge yet.") else
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { vm.life.vault.take(20).forEach { v -> FilterChip(vaultId == v.id, { vaultId = v.id }, { Text(v.title.take(30)) }) } }
        "news" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.g.newsCenter.catalog.categories.filter { !it.local }.forEach { c -> FilterChip(c.id in cats, { if (c.id in cats) cats -= c.id else cats += c.id }, { Text(c.label) }) } }
        else -> OutlinedTextField(text, { text = it.take(12_000) }, Modifier.fillMaxWidth(), label = { Text("Text to work on") }, minLines = 4, maxLines = 10)
    }
    if (t == Template.QUIZ) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TaskRunner.QUIZ_COUNTS.forEach { n -> FilterChip(count == "$n", { count = "$n" }, { Text("$n questions") }) }
        listOf("mixed" to "Mixed", "easy" to "Easy", "hard" to "Hard").forEach { (k, l) -> FilterChip(level == k, { level = k }, { Text(l) }) }
    }
    if (t == Template.QUIZ && count == "10") InfoNote("The AI on this phone writes at most ${TaskRunner.DEVICE_MAX_QUESTIONS} questions (its answer length is limited); online AI writes all 10.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp))
        ModelChip(vm, onOpen = onModels)
    }
    val source = when (kind) {
        "document" -> docs.firstOrNull { it.id == docId }?.let { TaskSource("document", it.id, it.name) }
        "knowledge" -> vm.life.vault.firstOrNull { it.id == vaultId }?.let { TaskSource("knowledge", it.id, it.title) }
        "project" -> vm.studio.workspaces.firstOrNull { it.id == projectId }?.let { TaskSource("project", it.id, it.name) }
        "news" -> TaskSource("news", categories = cats.toList(), label = "News")
        else -> TaskSource("text", label = "Pasted text", text = text)
    }
    GradientButton("Start task", { source?.let { ts.create(t, request, it, mapOf("count" to count, "difficulty" to level), if (it.type == "project") it.id else preset?.id); ts.preset.value = null } },
        Modifier.fillMaxWidth(),
        enabled = source != null && !ts.running && vm.taskBusy.value == null && (t != Template.CUSTOM || request.isNotBlank()) && (kind != "text" || text.isNotBlank()))
    InfoNote("Steps run one at a time with the model above (your AI mode). Document text goes online only after you agree.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskView(vm: AppViewModel, t: Task) {
    val ts = vm.tasks
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var confirmSave by remember { mutableStateOf<String?>(null) }
    var confirmCards by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val tpl = Template.of(t.type)
    AccentCard(templateAccent(tpl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(if (t.status == TaskStatus.RUNNING) OrbState.THINKING else if (t.status == TaskStatus.FAILED) OrbState.ERROR else OrbState.IDLE, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(when (t.status) { TaskStatus.RUNNING -> "Running · step ${t.steps.indexOfFirst { it.id == t.currentStep } + 1} of ${t.steps.size}"
                    TaskStatus.COMPLETED -> "Completed"; TaskStatus.FAILED -> "Stopped: a step failed"; TaskStatus.CANCELLED -> "Cancelled"; else -> "Ready" } +
                    " · ${t.model}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
        }
        val prog by androidx.compose.animation.core.animateFloatAsState(t.progress / 100f, Motion.tween(Motion.EMPHASIS, reducedMotion()), label = "task")
        LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = templateAccent(tpl).g2,
            trackColor = LocalExtra.current.line, drawStopIndicator = {}, gapSize = 0.dp)
        t.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.status == TaskStatus.RUNNING) TextButton(ts::cancel) { Text("Cancel") }
            if (t.status == TaskStatus.FAILED || t.status == TaskStatus.CANCELLED) TextButton({ ts.retry(t) }) { Text("Retry") }
            if (t.status != TaskStatus.RUNNING) TextButton({ ts.open(null) }) { Text("New task") }
            if (t.status != TaskStatus.RUNNING && t.steps.any { it.ai && it.result != null }) TextButton({         // export: the whole task as Markdown
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, t.title)
                    .putExtra(Intent.EXTRA_TEXT, TaskRunner.markdown(t)), "Export task")) } }) { Text("Share") }
        }
    }
    t.steps.forEach { s ->
        Glass(padding = 12) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val c = when (s.status) { StepStatus.DONE -> LocalExtra.current.ok; StepStatus.FAILED -> LocalExtra.current.bad; StepStatus.RUNNING -> Accent.CHAT.text; else -> LocalExtra.current.muted }
                Box(Modifier.size(24.dp).background(c.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(when (s.status) { StepStatus.DONE -> "✓"; StepStatus.FAILED -> "!"; StepStatus.RUNNING -> "…"; StepStatus.CANCELLED -> "–"; else -> "·" },
                        style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusTag(s.status.name.lowercase(), when (s.status) { StepStatus.DONE -> 0; StepStatus.FAILED -> 2; StepStatus.RUNNING -> 1; else -> -1 })
            }
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.bad) }
            if (s.status == StepStatus.FAILED && t.status != TaskStatus.RUNNING) TextButton({ ts.retry(t, s.id) }) { Text("Retry this step") }
            when {
                s.quiz != null -> TaskQuiz(s.quiz)
                s.result != null && s.ai -> MarkdownText(s.result)
                s.result != null -> Text(s.result, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
            if (s.status == StepStatus.DONE && s.ai) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ clip.setText(AnnotatedString(s.result ?: "")) }) { Text("Copy") }
                TextButton({ confirmSave = s.id }) { Text("Save to Knowledge") }
                if (s.id == "cards") TaskRunner.parseCards(s.result ?: "").takeIf { it.isNotEmpty() }?.let { cards ->
                    TextButton({ confirmCards = cards }) { Text("Add ${cards.size} flashcards") } }
            }
        }
    }
    if (t.refs.isNotEmpty()) SettingsGroup("Sources", accent = templateAccent(tpl)) {
        t.refs.forEach { r ->
            if (r.url != null) LinkRow("🔗", r.label, r.publisher) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } }
            else Text("${when (r.type) { "document" -> "📄"; "knowledge" -> "🔖"; else -> "📝" }}  ${r.label} · ${r.type.replaceFirstChar { it.uppercase() }}",   // 6.1: not a fake button
                Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    InfoNote("AI results can be wrong; they're based only on the source shown. Nothing here is added to your memory.")
    confirmSave?.let { id -> AlertDialog(onDismissRequest = { confirmSave = null }, title = { Text("Save to Knowledge?") },
        text = { DialogText("This result and its sources become a Knowledge entry (My Knowledge). It isn't added to your personal memory.") },
        confirmButton = { TextButton({ ts.saveToKnowledge(t, id); confirmSave = null }) { Text("Save") } },
        dismissButton = { TextButton({ confirmSave = null }) { Text("Cancel") } }) }
    confirmCards?.let { cards -> AlertDialog(onDismissRequest = { confirmCards = null }, title = { Text("Add ${cards.size} flashcards?") },
        text = { DialogText("They go into a Study deck named after the source. You can review or delete them in Study → Cards.") },
        confirmButton = { TextButton({ ts.addCards(t, cards); confirmCards = null }) { Text("Add") } },
        dismissButton = { TextButton({ confirmCards = null }) { Text("Cancel") } }) }
}

/** The quiz: answer once per question; then the explanation and the supporting quote (flagged if it isn't in the source). */
@Composable
private fun TaskQuiz(quiz: List<QuizItem>) {
    val picks = remember(quiz) { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val right = picks.count { (i, p) -> quiz[i].answer == p }
    Text(if (picks.size == quiz.size) "Result: $right of ${quiz.size} correct (${100 * right / quiz.size}%)."
        else if (picks.isEmpty()) "${quiz.size} question${if (quiz.size == 1) "" else "s"}, only from the document." else "$right of ${picks.size} correct so far · ${quiz.size - picks.size} to go",
        style = MaterialTheme.typography.labelLarge, color = if (picks.size == quiz.size) Accent.STUDY.text else LocalExtra.current.muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    quiz.forEachIndexed { i, q ->
        var picked by rememberSaveable(q.q) { mutableStateOf<Int?>(null) }
        LaunchedEffect(picked) { picked?.let { picks[i] = it } }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text("${i + 1}. ${q.q}", style = MaterialTheme.typography.titleSmall)
            q.options.forEachIndexed { j, o ->
                val x = LocalExtra.current
                val c = when { picked == null -> x.line; j == q.answer -> x.ok; j == picked -> x.bad; else -> x.line }
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(12.dp)).border(if (picked != null && (j == q.answer || j == picked)) 2.dp else 1.dp, c, RoundedCornerShape(12.dp))
                    .clickable(enabled = picked == null, onClickLabel = "Answer ${'A' + j}") { picked = j }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${'A' + j}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(22.dp))
                    Text(o, style = MaterialTheme.typography.bodyMedium)
                }
            }
            picked?.let {
                Text(if (it == q.answer) "Correct." else "Not quite: ${'A' + q.answer}.", fontWeight = FontWeight.Bold, color = if (it == q.answer) LocalExtra.current.ok else LocalExtra.current.bad)
                if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
                if (q.quote.isNotBlank()) Text("“${q.quote}” " + if (q.quoteFound) "(found in the source)" else "(not found in the source: check this yourself)",
                    style = MaterialTheme.typography.bodySmall, color = if (q.quoteFound) LocalExtra.current.muted else LocalExtra.current.warn)
            }
        }
    }
}
