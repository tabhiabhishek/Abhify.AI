package ai.abhify.android.ui

import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.Workspace
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Project Workspace: the list of projects (active and archived) and one project's page. A project is an existing
 * workspace; its chat, AI tasks and study open the existing screens, scoped to the project's own and linked documents.
 */
@Composable
fun ProjectsScreen(vm: AppViewModel) {
    val p = vm.projects
    LaunchedEffect(Unit) { vm.studio.loadWork() }
    Column(Modifier.fillMaxSize().imePadding()) {
        val open = p.open.value
        androidx.activity.compose.BackHandler(enabled = open != null) { p.open.value = null }       // 6.1: system Back closes the project first
        ScreenTitle(open?.name ?: "Projects", { if (open != null) p.open.value = null else vm.back() })
        PageBody(gap = Dimens.gap) { if (open == null) ProjectList(vm) else ProjectPage(vm, open) }
    }
}

@Composable
private fun ProjectList(vm: AppViewModel) {
    val p = vm.projects
    var name by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    PageHeader("Projects", "Documents, chats, AI tasks and study, organised per project", Accent.KNOWLEDGE, "🗂")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(name, { name = it.take(60) }, "New project name", modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton({ p.create(name); name = "" }, enabled = name.isNotBlank()) { Text("Create") }
    }
    val (archived, active) = vm.studio.workspaces.partition { p.isArchived(it.id) }
    if (active.isEmpty()) EmptyState("🗂", "No projects yet", "Create one above. Your existing workspaces appear here too.")
    active.forEach { w -> ProjectRow(vm, w) }
    if (archived.isNotEmpty()) {
        TextButton({ showArchived = !showArchived }) { Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})") }
        if (showArchived) archived.forEach { w -> ProjectRow(vm, w) }
    }
    InfoNote("Projects keep their context apart: a project's chat and AI tasks use its own and linked documents only.")
}

@Composable
private fun ProjectRow(vm: AppViewModel, w: Workspace) {
    val m = vm.projects.meta(w.id)
    Glass(padding = 12, onClick = { vm.projects.openProject(w) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(Accent.KNOWLEDGE, if (m.archived) "🗄" else "🗂", 34); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull("archived".takeIf { m.archived }, "instructions set".takeIf { m.instructions.isNotBlank() },
                    "in use in chat".takeIf { vm.chatWorkspace.value?.id == w.id }).joinToString(" · ").ifBlank { w.kind },
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectPage(vm: AppViewModel, w: Workspace) {
    val p = vm.projects
    val m = p.meta(w.id)
    val ov = p.overview.value
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var instructions by remember(w.id) { mutableStateOf(m.instructions) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    LaunchedEffect(w.id) { p.refresh(w) }
    PageHeader(w.name, if (m.archived) "Archived project" else "Project overview", Accent.KNOWLEDGE, "🗂")
    if (ov != null) Text("${ov.docs.size} document(s) · ${ov.notes} note(s) · ${ov.openTodos} open to-do(s) · ${ov.links} linked chat/memory item(s)",
        style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
    if (m.archived) InfoNote("Archived: hidden from pickers and not used as chat context. Restore it to work on it again.")
    // 5.1 Project Intelligence: a continuation summary from what you recorded (never inferred), and the status editor
    var editingStatus by remember { mutableStateOf(false) }
    SettingsGroup("Where you left off", accent = Accent.CHAT) {
        val brief = p.brief(w)
        if (brief == null) Text("Nothing recorded yet. Add the objective, current status and next actions, and Abhify can summarise where you left off " +
            "and use it in this project's chats.", style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
        else Text(brief.substringAfter('\n').replace("- ", "• "), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ editingStatus = true }) { Text(if (m.status.isEmpty) "Add status" else "Update status") }
            if (brief != null) TextButton({ p.continueInChat(w) }) { Text("Continue in chat") }
            // next build: Agent 2.0 — reads this project's status and notes, proposes to-dos, creates only what you approve
            TextButton({ vm.agent.reviewProject(w.id) }, enabled = !vm.agent.open) { Text("🤖 Find unfinished work") }
            TextButton({ vm.agent.run(w.id, AgentState.Workflow.STATUS) }, enabled = !vm.agent.open) { Text("🤖 Status & blockers") }
            TextButton({ vm.agent.run(w.id, AgentState.Workflow.PRIORITIZE) }, enabled = !vm.agent.open) { Text("🤖 Suggest priorities") }
            TextButton({ vm.agent.run(w.id, AgentState.Workflow.DOCS) }, enabled = !vm.agent.open) { Text("🤖 Related documents") }
        }
        Text("Written by you, from your records only. Used in this project's chats (on this phone; online only after you agree).",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    if (editingStatus) ProjectStatusDialog(m.status, { p.setStatus(w, it); editingStatus = false }) { editingStatus = false }
    SettingsGroup("Work on this project", accent = Accent.CHAT) {
        NavRow("💬", "Project chat", "Chat with this project's instructions, documents, notes and to-dos") { p.openChat(w) }
        NavRow("✅", "AI task on the project's documents", "Summary, study plan, quiz or a custom task") { p.runTask(w) }
        NavRow("🎓", "Study plan from the project's documents", "Opens Study with them selected") { p.study(w) }
    }
    SettingsGroup("Documents", accent = Accent.DOCS) {
        ov?.docs?.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 ${d.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.workspaceId == w.id) Text("in project", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
                else TextButton({ p.unlinkDoc(w, d.id) }) { Text("Remove link") }
            }
        }
        if (ov?.docs.isNullOrEmpty()) Text("No documents yet.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        OutlinedButton({ linking = true }) { Text("Add existing documents") }
        Text("Adding links a document (nothing is copied); removing a link never deletes the document.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    SettingsGroup("Project instructions", accent = Accent.STUDY) {
        OutlinedTextField(instructions, { instructions = it.take(ai.abhify.android.workspace.ProjectStore.MAX_INSTRUCTIONS) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
            placeholder = { Text("e.g. This is my thesis on photosynthesis. Answer at master's level and cite the documents.") })
        TextButton({ p.setInstructions(w, instructions) }, enabled = instructions != m.instructions) { Text(if (instructions == m.instructions) "Saved" else "Save instructions") }
        Text("Added to this project's chats and AI tasks. They never override the app's safety rules.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    SettingsGroup("Project settings") {
        SwitchRow("Use personal memory in this project", "Only when personal memory is on in Settings. Off keeps this project to its own material.", m.useMemory) { p.setUseMemory(w, it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ renaming = w.name }) { Text("Rename") }
            if (m.archived) TextButton({ p.restore(w) }) { Text("Restore") } else TextButton({ p.archive(w) }) { Text("Archive") }
            TextButton({ scope.launch { val md = p.export(w)
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, w.name)
                    .putExtra(Intent.EXTRA_TEXT, md), "Export project")) } } }) { Text("Export") }
            TextButton({ confirmDelete = true }) { Text("Delete…", color = LocalExtra.current.bad) }
        }
    }
    renaming?.let { r -> var v by remember { mutableStateOf(r) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename project") }, text = { Field(v, { v = it.take(60) }, "Name") },
            confirmButton = { TextButton({ p.rename(w, v); renaming = null }, enabled = v.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } }) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete \"${w.name}\"?") },
        text = { DialogText("The project, its ${ov?.notes ?: 0} note(s) and its to-dos are deleted. Its documents are kept: documents imported into it move back to your " +
            "library, and linked documents, chats and memories are not touched. Archive instead if you may need it again.") },
        confirmButton = { TextButton({ confirmDelete = false; p.delete(w) }) { Text("Delete project", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (linking) {
        var all by remember { mutableStateOf<List<ai.abhify.android.chat.LibraryDoc>>(emptyList()) }
        LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { vm.g.db.allDocs() } }
        val have = ov?.docs?.map { it.id }?.toSet().orEmpty()
        AlertDialog(onDismissRequest = { linking = false }, title = { Text("Add documents to ${w.name}") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text("No documents in your library yet (Documents).")
                all.filter { it.id !in have }.forEach { d -> TextButton({ p.linkDoc(w, d) }) { Text("➕ ${d.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            } },
            confirmButton = { TextButton({ linking = false }) { Text("Done") } })
    }
}

/** 5.1: edit a project's recorded status. Lists take one item per line. */
@Composable
private fun ProjectStatusDialog(st: ai.abhify.android.workspace.ProjectStatus, onSave: (ai.abhify.android.workspace.ProjectStatus) -> Unit, onDismiss: () -> Unit) {
    val L = ai.abhify.android.workspace.ProjectStatus
    var objective by remember { mutableStateOf(st.objective) }; var stack by remember { mutableStateOf(st.stack) }
    var current by remember { mutableStateOf(st.current) }; var milestones by remember { mutableStateOf(st.milestones.joinToString("\n")) }
    var issues by remember { mutableStateOf(st.issues.joinToString("\n")) }; var next by remember { mutableStateOf(st.next.joinToString("\n")) }
    var decisions by remember { mutableStateOf(st.decisions.joinToString("\n")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Project status") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun field(label: String, v: String, lines: Int, set: (String) -> Unit) =
                    OutlinedTextField(v, { set(it.take(if (lines > 1) 2_000 else L.MAX_FIELD)) }, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = lines + 3)
                field("Objective", objective, 1) { objective = it }
                field("Technology", stack, 1) { stack = it }
                field("Current status", current, 2) { current = it }
                field("Completed milestones (one per line)", milestones, 2) { milestones = it }
                field("Open issues / blockers (one per line)", issues, 2) { issues = it }
                field("Next actions (one per line)", next, 2) { next = it }
                field("Decisions (one per line)", decisions, 2) { decisions = it }
            }
        },
        confirmButton = { TextButton({ onSave(ai.abhify.android.workspace.ProjectStatus(objective.trim(), stack.trim(), current.trim().take(L.MAX_FIELD),
            L.list(decisions), L.list(milestones), L.list(issues), L.list(next))) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
