package ai.abhify.android.ui

import ai.abhify.android.chat.Workspace
import ai.abhify.android.chat.workspaces
import ai.abhify.android.workspace.UnifiedSearch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** v4.0: one search over everything on this phone. Results say where they come from; nothing is searched online. */
@Composable
fun UnifiedSearchScreen(vm: AppViewModel) {
    val h = vm.hub
    LaunchedEffect(h.query.value, h.types.size, h.filters.value) { delay(250); h.search() }
    var projects by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    LaunchedEffect(Unit) { projects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.g.db.workspaces() } }
    LaunchedEffect(Unit) { vm.studio.loadWork() }
    var linking by remember { mutableStateOf<UnifiedSearch.Hit?>(null) }
    var newWs by remember { mutableStateOf<UnifiedSearch.Hit?>(null) }
    var memoryFrom by remember { mutableStateOf<UnifiedSearch.Hit?>(null) }
    Screen(vm, "Search everything") {
        Field(h.query.value, { h.query.value = it.take(80) }, "Search chats, memory, documents, notes, study, career…")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(h.types.isEmpty(), { h.types.clear() }, { Text("All") })
            UnifiedSearch.Type.entries.forEach { t -> FilterChip(t in h.types, { if (t in h.types) h.types -= t else h.types += t }, { Text("${t.icon} ${t.label}") }) }
        }
        val f = h.filters.value                                                         // 5.6: date, project, sort
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Any time", 7 to "Last 7 days", 30 to "Last 30 days").forEach { (d, l) -> FilterChip(f.days == d, { h.filters.value = f.copy(days = d) }, { Text(l) }) }
            UnifiedSearch.Sort.entries.forEach { s -> FilterChip(f.sort == s, { h.filters.value = f.copy(sort = s) }, { Text(s.label) }) }
        }
        if (projects.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(f.project == null, { h.filters.value = f.copy(project = null) }, { Text("All projects") })
            projects.take(12).forEach { w -> FilterChip(f.project == w.id, { h.filters.value = f.copy(project = if (f.project == w.id) null else w.id) }, { Text("🗂 ${w.name.take(24)}") }) }
        }
        vm.conversationId.value?.let { id -> vm.conversations.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }?.let { t ->
            TextButton({ h.query.value = t.take(40); h.types.clear(); h.types += UnifiedSearch.Type.CHAT }) { Text("Related to the current chat: \"${t.take(30)}\"") } } }
        Muted("Searched on this phone only. Nothing is sent online." + if (!vm.g.memoriesEnabled) " Memories are not searched while personal memory is off." else "")
        if (h.query.value.isBlank() && h.recent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            h.recent.forEach { q -> AssistChip({ h.query.value = q }, { Text(q) }) }
            TextButton({ h.clearRecent() }) { Text("Clear") }
        }
        h.extras.forEach { e -> Glass(onClick = { e.open(); vm.home() }) {
            Text("${e.kind}" + (if (e.at > 0) " · ${ago(e.at)}" else ""), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted)
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.snippet.isNotBlank()) Text(e.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } }
        if (h.searching.value) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (h.query.value.trim().length >= 2 && !h.searching.value && h.results.isEmpty() && h.extras.isEmpty()) EmptyState("🔍", "Nothing found",
            if (f.active) "Nothing matches with these filters. Try Any time, All projects or another type." else "Try fewer or different words, or another type filter.")
        if (h.query.value.isBlank() && h.recent.isEmpty()) Muted("Tip: search finds chats, documents, notes, to-dos, knowledge, study, career, projects, AI tasks, loaded news and today's briefing.")
        val grouped = if (f.sort == UnifiedSearch.Sort.RECENT) h.results.toList() else h.results.sortedBy { it.type.ordinal }   // 5.5: grouped by type; 5.6: or newest first
        grouped.forEachIndexed { i, r -> if (f.sort != UnifiedSearch.Sort.RECENT && (i == 0 || grouped[i - 1].type != r.type)) Text("${r.type.icon} ${r.type.label} (${grouped.count { it.type == r.type }})",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            Glass(onClick = { h.open(r) }) {
            Text("${r.type.icon} ${r.type.label} · ${ago(r.at)}", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted)
            Text(r.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.snippet.isNotBlank()) Text(r.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ h.open(r) }) { Text("Open") }
                h.kindOf(r.type)?.let { TextButton({ linking = r }) { Text("Add to workspace…") } }
                if (r.type == UnifiedSearch.Type.CHAT || (r.type == UnifiedSearch.Type.MEMORY && r.title == "Active projects")) TextButton({ newWs = r }) { Text("New workspace from this") }
                if (r.type == UnifiedSearch.Type.DOCUMENT) {
                    TextButton({ if (r.id !in vm.chatDocs) vm.chatDocs += r.id; vm.tab.value = AppViewModel.Tab.CHAT; vm.home() }) { Text("Use in chat") }
                    TextButton({ h.studyPlanFrom(r.id) }) { Text("Study plan") }
                    if (r.snippet.isNotBlank()) TextButton({ memoryFrom = r }) { Text("Memory suggestion…") }
                }
            }
        } }
        h.lastMs.value?.let { if (h.results.isNotEmpty()) Muted("${h.results.size} results in $it ms.") }
    }
    linking?.let { r -> WorkspacePicker(vm, "Add to a workspace", null, allowNone = false, note = "The workspace gets a link to this item; nothing is copied.",
        onDismiss = { linking = null }) { ws -> if (ws != null) h.link(ws, h.kindOf(r.type)!!, r.id, r.title.ifBlank { r.snippet }); linking = null } }
    newWs?.let { r -> var name by remember(r) { mutableStateOf((if (r.type == UnifiedSearch.Type.MEMORY) r.snippet else r.title).take(60)) }
        AlertDialog(onDismissRequest = { newWs = null }, title = { Text("New workspace") },
            text = { Column { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") })
                Muted("Creates a work workspace and links this ${r.type.label.lowercase().removeSuffix("s")} to it.") } },
            confirmButton = { TextButton({ h.newWorkspaceFrom(name, h.kindOf(r.type)!!, r.id); newWs = null }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton({ newWs = null }) { Text("Cancel") } }) }
    memoryFrom?.let { r -> var text by remember(r) { mutableStateOf(r.snippet.trim().take(300)) }
        AlertDialog(onDismissRequest = { memoryFrom = null }, title = { Text("Save as a memory?") },
            text = { Column { Muted("From \"${r.title}\". Edit it into one short fact about you; it is checked for sensitive data, duplicates and contradictions before saving.")
                OutlinedTextField(text, { text = it.take(300) }, minLines = 3) } },
            confirmButton = { TextButton({ h.suggestMemory(r, text); memoryFrom = null }, enabled = text.isNotBlank()) { Text("Save memory") } },
            dismissButton = { TextButton({ memoryFrom = null }) { Text("Cancel") } }) }
}

/** v4.0: what a workspace references (chats, documents, memories). Removing a link never deletes the item. */
@Composable
internal fun LinkedView(vm: AppViewModel) {
    val ws = vm.studio.workspace.value
    LaunchedEffect(ws?.id) { vm.hub.loadLinks(ws) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ws == null) { Muted("Create a workspace first."); return@Column }
        Glass {
            Text("Linked to \"${ws.name}\"", style = MaterialTheme.typography.titleMedium)
            Muted("Add chats, documents or memories with 🔍 Search → \"Add to workspace…\". Links are references: nothing is copied or moved.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.hub.useInChat(ws) }) { Text(if (vm.chatWorkspace.value?.id == ws.id) "In use in chat ✓" else "Use as chat context") }
                OutlinedButton({ vm.go(AppViewModel.Route.SEARCH) }) { Text("🔍 Find items") }
            }
        }
        if (vm.hub.links.isEmpty()) EmptyState("🔗", "Nothing linked yet", "Use 🔍 Search → \"Add to workspace…\".")
        vm.hub.links.forEach { l -> Glass {
            Text(when (l.kind) { "chat" -> "💬 Conversation"; "doc" -> "📚 Document"; else -> "🧠 Memory" }, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted)
            Text(l.title ?: "(deleted since it was linked)", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                fontWeight = if (l.title == null) FontWeight.Normal else FontWeight.SemiBold)
            Row {
                if (l.title != null && l.kind == "chat") TextButton({ vm.loadConversation(l.refId); vm.tab.value = AppViewModel.Tab.CHAT; vm.home() }) { Text("Open") }
                if (l.title != null && l.kind == "doc") TextButton({ vm.hub.studyPlanFrom(l.refId) }) { Text("Study plan") }
                TextButton({ vm.hub.unlink(l) }) { Text("Remove link") }
            }
        } }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun WorkspacePicker(vm: AppViewModel, title: String, current: Workspace?, allowNone: Boolean, note: String, onDismiss: () -> Unit, onPick: (Workspace?) -> Unit) {
    LaunchedEffect(Unit) { vm.studio.loadWork() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Muted(note)
            if (vm.studio.workspaces.isEmpty()) Muted("No workspaces yet (Knowledge → Workspaces → ＋ Workspace).")
            vm.studio.workspaces.forEach { w -> FilterChip(current?.id == w.id, { onPick(w) }, { Text((if (w.kind == "work") "🏢 " else "🏠 ") + w.name) }) }
        } },
        confirmButton = { if (allowNone && current != null) TextButton({ onPick(null) }) { Text("Don't use a workspace") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
