package ai.abhify.android.ui

import ai.abhify.android.backup.Backup
import ai.abhify.android.backup.Storage
import ai.abhify.android.chat.MemoryItem
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveStatus
import ai.abhify.android.memory.MemoryKit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun Screen(vm: AppViewModel, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTitle(title, { vm.back() })
        TaskStatus(vm)
        androidx.compose.runtime.CompositionLocalProvider(LocalRowAccent provides PageIdentity.of(title)?.first) { PageBody(content = content) }
    }
}
@Composable private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
internal fun when_(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** "Save to a file" helper: the system picker chooses where; the text/bytes are written only after it returns. */
@Composable
private fun rememberSaver(onDone: (String) -> Unit): (String, ByteArray) -> Unit {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val b = pending; pending = null
        if (uri != null && b != null) scope.launch {
            onDone(runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) } ?: error("couldn't open") }
                "Saved (${Storage.human(b.size.toLong())})." }.getOrElse { "Couldn't save: ${it.message}" })
        }
    }
    return { name, bytes -> pending = bytes; l.launch(name) }
}

// ====================================================================== personal memory
@Composable
fun PersonalMemoryScreen(vm: AppViewModel) {
    val m = vm.memory
    LaunchedEffect(Unit) { m.load() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var deleting by remember { mutableStateOf<MemoryItem?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeGroup by remember { mutableStateOf<List<MemoryItem>?>(null) }
    var pendingMerge by remember { mutableStateOf(false) }
    val save = rememberSaver { vm.studio.message.value = it }
    Screen(vm, "Personal memory") {
        Glass {
            Text("Personal memory stores selected information that helps Abhify.AI personalize responses. It is separate from your complete chat history and learning documents.",
                style = MaterialTheme.typography.bodyMedium)
            Muted("Only memories related to your question are given to the AI (shown under each answer). Online providers get them only if you allow it in Privacy. Stored on this phone.")
            SwitchRow("Use personal memory", "Off: nothing is stored from chats and no memory is used.", vm.memoriesEnabled.value) { vm.setMemoriesEnabled(it) }
            Text("${m.items.size} memories · ${Storage.human(m.bytes.value)} · last backup: " + (m.lastBackup()?.let { when_(it.createdAt) + " (" + it.location + ")" } ?: "never"),
                style = MaterialTheme.typography.labelLarge)
        }
        if (m.items.isNotEmpty()) MemoryOverview(vm)
        MemoryReviewCard(vm) { mergeGroup = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ adding = true }) { Text("＋ Add") }
            OutlinedButton(m::suggestFromChat, enabled = vm.taskBusy.value == null) { Text("Suggest from current chat") }
            if (m.groups().isNotEmpty()) OutlinedButton({ merging = true }) { Text("Merge duplicates (${m.groups().size})") }
        }
        Field(m.query.value, { m.query.value = it }, "Search memories")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(m.category.value == null, { m.category.value = null }, { Text("All") })
            MemoryKit.CATEGORIES.forEach { c -> val n = m.items.count { it.category == c }; if (n > 0) FilterChip(m.category.value == c, { m.category.value = c }, { Text("$c ($n)") }) }
        }
        if (m.candidates.isNotEmpty()) CandidatesCard(vm)
        val list = m.visible()
        if (list.isEmpty()) { if (m.items.isEmpty()) EmptyState("🧠", "No memories yet", "Add one, say \"remember that …\" in chat, or let the AI suggest some from a chat (you approve each).")
            else EmptyState("🔍", "Nothing matches", "Try another word or category.") }
        list.forEach { it2 ->
            Glass(onClick = { editing = it2 }) {
                Text(it2.content, style = MaterialTheme.typography.bodyLarge)
                Text("${it2.category} · " + listOf("low", "normal", "high")[(it2.importance - 1).coerceIn(0, 2)] + " importance · updated " + when_(it2.updatedAt) +
                    (it2.expiresAt?.let { e -> if (it2.expired()) " · expired" else " · expires " + DateFormat.getDateInstance().format(Date(e)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = if (it2.expired()) LocalExtra.current.warn else LocalExtra.current.muted)
                MemoryProvenance(vm, it2)
                Row { TextButton({ editing = it2 }) { Text("Edit") }; TextButton({ deleting = it2 }) { Text("Delete", color = LocalExtra.current.bad) } }
            }
        }
        SettingsGroup("Export and backup") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ save("abhify-memory.json", m.exportJson().toByteArray()) }) { Text("Export JSON") }
                OutlinedButton({ save("abhify-memory.md", m.exportMd().toByteArray()) }) { Text("Export Markdown") }
                OutlinedButton({ vm.backup.toDrive(memoryOnly = true) }, enabled = vm.drive.busy.value == null) { Text("Back up to Drive") }
            }
            Muted("A memory backup contains only your memories (set a passphrase in Backup & restore to encrypt it). Nothing is uploaded automatically.")
            if (m.items.isNotEmpty()) TextButton({ clearAll = true }) { Text("Delete all memories", color = LocalExtra.current.bad) }
        }
    }
    editing?.let { e -> MemoryDialog(e, onClose = { editing = null }) { c, cat, imp, days -> m.update(e, c, cat, imp, days); editing = null } }
    if (adding) MemoryDialog(null, onClose = { adding = false }) { c, cat, imp, days -> m.add(c, cat, imp, days); adding = false }
    m.pending.value?.let { p -> if (p.conflictWith != null && !pendingMerge) AlertDialog(onDismissRequest = { m.pending.value = null }, title = { Text("This may contradict a memory") },
        text = { DialogText("Existing: \"${p.conflictWith.content}\"\n(${m.provenance(p.conflictWith)})\n\nNew: \"${p.content}\"\n\n" +
            (p.reasons.filterNot { it.startsWith("may contradict") }.takeIf { it.isNotEmpty() }?.let { "It also " + it.joinToString("; ") + ".\n\n" } ?: "") + "Nothing changes until you choose.") },
        confirmButton = { Column(horizontalAlignment = Alignment.End) {
            TextButton(m::replacePending) { Text("Replace the existing one") }
            TextButton(m::confirmPending) { Text("Keep both") }
            TextButton({ pendingMerge = true }) { Text("Merge…") } } },
        dismissButton = { TextButton({ m.pending.value = null }) { Text("Keep existing") } })
    else if (p.conflictWith == null) AlertDialog(onDismissRequest = { m.pending.value = null }, title = { Text("Save this memory?") },
        text = { DialogText("\"${p.content}\"\n\nIt " + p.reasons.joinToString("; ") + ". Save it only if you really want it remembered.") },
        confirmButton = { TextButton(m::confirmPending) { Text("Save anyway") } }, dismissButton = { TextButton({ m.pending.value = null }) { Text("Don't save") } }) }
    // merge for a conflict at add time: edit one text that replaces the existing memory
    m.pending.value?.takeIf { pendingMerge }?.let { p -> p.conflictWith?.let { old ->
        MemoryDialog(old.copy(content = old.content.trimEnd('.') + "; " + p.content), onClose = { pendingMerge = false; m.pending.value = null }) { c, cat, imp, days ->
            m.update(old, c, cat, imp, days); pendingMerge = false; m.pending.value = null } } }
    mergeGroup?.let { grp -> MemoryDialog(grp.first().copy(content = grp.joinToString("; ") { it.content.trimEnd('.') }), onClose = { mergeGroup = null }) { c, cat, imp, days ->
        m.update(grp.first(), c, cat, imp, days); grp.drop(1).forEach { m.delete(it) }; mergeGroup = null } }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this memory?") }, text = { DialogText(d.content) },
        confirmButton = { TextButton({ m.delete(d); deleting = null }) { Text("Delete", color = LocalExtra.current.bad) } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
    if (clearAll) AlertDialog(onDismissRequest = { clearAll = false }, title = { Text("Delete all ${m.items.size} memories?") },
        text = { DialogText("They are removed from this phone (and from your account if sync is on). Chats and documents are not affected.") },
        confirmButton = { TextButton({ vm.clearAllMemories(); clearAll = false; m.load() }) { Text("Delete all", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ clearAll = false }) { Text("Cancel") } })
    if (merging) MergeDialog(vm) { merging = false }
}

@Composable
private fun MemoryDialog(m: MemoryItem?, onClose: () -> Unit = {}, onSave: (String, String, Int, Int?) -> Unit) {
    var text by remember(m?.id) { mutableStateOf(m?.content.orEmpty()) }
    var cat by remember(m?.id) { mutableStateOf(m?.category ?: "General") }
    var imp by remember(m?.id) { mutableIntStateOf(m?.importance ?: 2) }
    var days by remember(m?.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (m == null) "Add memory" else "Edit memory") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(text, { text = it.take(300) }, label = { Text("A short fact (e.g. I prefer PyTorch examples)") }, minLines = 2)
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryKit.CATEGORIES.forEach { c -> FilterChip(cat == c, { cat = c }, { Text(c) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "Low", 2 to "Normal", 3 to "High").forEach { (v, l) -> FilterChip(imp == v, { imp = v }, { Text(l) }) } }
            OutlinedTextField(days, { days = it.filter(Char::isDigit).take(4) }, label = { Text("Expires after N days (optional)") }, singleLine = true)
        } },
        confirmButton = { TextButton({ onSave(text, cat, imp, days.toIntOrNull()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

@Composable
private fun CandidatesCard(vm: AppViewModel) {
    val m = vm.memory
    val clash = remember(m.candidates.size) { m.candidates.map { ai.abhify.android.memory.MemoryReview.conflictWith(it.content, m.items, m.related) } }
    val picked = remember(m.candidates.size) { mutableStateListOf<Int>().apply { m.candidates.forEachIndexed { i, c -> if (c.sensitive.isEmpty() && c.duplicateOf == null && clash[i] == null) add(i) } } }
    SettingsGroup("Suggested memories (nothing is saved until you approve)") {
        m.candidates.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(i in picked, { on -> if (on) picked += i else picked -= i }, Modifier.semantics { contentDescription = "Keep: ${c.content}" })
                Column {
                    Text(c.content, style = MaterialTheme.typography.bodyMedium); Text(c.category, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    c.duplicateOf?.let { Text("Similar to an existing memory: \"${it.take(80)}\"", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn) }
                    if (c.sensitive.isNotEmpty()) Text("Looks like a ${c.sensitive.joinToString()}: not selected", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn)
                    clash.getOrNull(i)?.let { Text("May contradict: \"${it.content.take(80)}\" — not selected; add it with ＋ Add to choose replace / keep both / merge", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn) }
                }
            }
        }
        Row { Button({ m.approve(picked.map { m.candidates[it] }) }, enabled = picked.isNotEmpty()) { Text("Save ${picked.size} selected") }
            TextButton({ m.candidates.clear() }) { Text("Discard") } }
    }
}

@Composable
private fun MergeDialog(vm: AppViewModel, onClose: () -> Unit) {
    val groups = vm.memory.groups()
    var idx by remember { mutableIntStateOf(0) }
    val g = groups.getOrNull(idx) ?: run { onClose(); return }
    var keep by remember(idx) { mutableStateOf(g.first().content) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Merge similar memories (${idx + 1} of ${groups.size})") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.forEach { Text("• ${it.content}", style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(keep, { keep = it.take(300) }, label = { Text("Keep this text") }, minLines = 2)
        } },
        confirmButton = { TextButton({ vm.memory.merge(g, keep); if (idx + 1 >= groups.size) onClose() else idx++ }) { Text("Merge") } },
        dismissButton = { TextButton({ if (idx + 1 >= groups.size) onClose() else idx++ }) { Text("Skip") } })
}

// ====================================================================== Google Drive
@Composable
fun DriveScreen(vm: AppViewModel) {
    val d = vm.drive
    LaunchedEffect(Unit) { d.loadFiles(); if (d.connected.value && d.rootState.value == "unknown") d.check() }
    DriveAuthHost(vm)
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "file"
            Triple(name, ctx.contentResolver.getType(uri), ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
        }.onSuccess { (n, mime, b) -> if (b.size > 25 * 1024 * 1024) d.message.value = "$n is larger than 25 MB." else d.uploadFile(n, mime, b) }
         .onFailure { d.message.value = "Couldn't read the file: ${it.message}" } } }
    var trashing by remember { mutableStateOf<DriveApi.Item?>(null) }
    Screen(vm, "Google Drive") {
        DriveStatusCard(vm)
        d.busy.value?.let { BusyBanner(it + (d.progress.value?.let { p -> " · ${(p * 100).toInt()}%" } ?: "")) }
        d.message.value?.let { Text(it, color = if (it.contains("fail", true) || it.contains("couldn't", true) || it.contains("isn't configured", true)) LocalExtra.current.bad else MaterialTheme.colorScheme.tertiary) }
        // v3.5: the background upload queue
        val waiting = d.files.filter { it.status in setOf(DriveStatus.PENDING_UPLOAD, DriveStatus.CONFLICT, DriveStatus.FAILED, DriveStatus.CANCELLED) }
        if (waiting.isNotEmpty() || d.uploading.value != null) Glass {
            Text("Upload queue (${waiting.size})", style = MaterialTheme.typography.titleSmall)
            d.uploading.value?.let { (n, f) -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text("Uploading $n" + (f?.let { " · ${(it * 100).toInt()}%" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
            waiting.forEach { f -> Column(Modifier.padding(vertical = 2.dp)) {
                Text("${f.name} → ${f.folder}" + (f.size?.let { " · ${Storage.human(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(DriveStatus.label(f.status) + (if (f.attempts > 0) " · attempt ${f.attempts}" else "") + (f.error?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = if (f.status == DriveStatus.PENDING_UPLOAD) LocalExtra.current.muted else LocalExtra.current.warn)
                Row {
                    if (f.status == DriveStatus.CONFLICT) TextButton({ d.conflict.value = f }) { Text("Resolve…") }
                    if (f.status == DriveStatus.FAILED || f.status == DriveStatus.CANCELLED || (f.status == DriveStatus.PENDING_UPLOAD && f.error != null)) TextButton({ d.retry(f) }) { Text("Retry") }
                    TextButton({ d.forget(f) }) { Text("Remove") }
                }
            } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(d.wifiOnly.value, d::setWifiOnly); Spacer(Modifier.width(8.dp)); Text("Upload on Wi-Fi only", Modifier.weight(1f))
                if (waiting.any { it.status == DriveStatus.PENDING_UPLOAD }) TextButton(d::cancelAll) { Text("Cancel uploads") }
            }
            Muted("Uploads continue in the background, also after you close Abhify, whenever there is a connection. Android decides the exact moment. " +
                "Remove only takes an item off this list; nothing is deleted on the phone or on Drive.")
        }
        if (d.connected.value) {
            SettingsGroup("Upload (your choice; sent in the background)") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = d.busy.value == null) { Text("A file → Imports") }
                    OutlinedButton(d::uploadNotes, enabled = d.busy.value == null) { Text("All notes → Exports") }
                    OutlinedButton({ vm.backup.toDrive() }, enabled = d.busy.value == null) { Text("App backup → Backups") }
                }
                Muted("Library documents: Documents tab → a document → \"☁ To Drive\". Chats: History → \"☁ To Drive\". Memory: Personal memory → Back up to Drive.")
            }
            d.remote.entries.sortedBy { it.key }.forEach { (folder, items) ->
                Glass {
                    Text("Abhify.AI / $folder (${items.size})", style = MaterialTheme.typography.titleSmall)
                    if (items.isEmpty()) Muted("Empty.")
                    items.forEach { it2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(it2.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(it2.size?.let { s -> Storage.human(s) }, it2.modified?.take(16)?.replace('T', ' ')).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
                        TextButton({ d.download(folder, it2) }, enabled = d.busy.value == null) { Text(if (folder.endsWith("Backups")) "Restore…" else "Download") }
                        TextButton({ trashing = it2 }) { Text("Delete", color = LocalExtra.current.bad) }
                    } }
                }
            }
        }
        if (d.files.isNotEmpty()) SettingsGroup("Upload history and status") {
            d.files.take(40).forEach { f -> Column(Modifier.padding(vertical = 2.dp)) {
                Text("${f.name} → ${f.folder}", style = MaterialTheme.typography.bodyMedium)
                Text(DriveStatus.label(f.status) + (f.error?.let { " · $it" } ?: "") + " · " + when_(f.updatedAt), style = MaterialTheme.typography.bodySmall,
                    color = when (f.status) { DriveStatus.SYNCED -> LocalExtra.current.ok; DriveStatus.FAILED, DriveStatus.CONFLICT -> LocalExtra.current.warn; else -> LocalExtra.current.muted })
                Row { if (f.status == DriveStatus.FAILED || f.status == DriveStatus.CANCELLED) TextButton({ d.retry(f) }) { Text("Retry") }
                    TextButton({ d.forget(f) }) { Text("Remove from list") } }
            } }
            Muted("Status only. Deleting here never deletes a file on the phone or on Drive.")
        }
        DriveMigrationCard(vm)
    }
    d.conflict.value?.let { c -> AlertDialog(onDismissRequest = { d.conflict.value = null }, title = { Text("\"${c.name}\" is already on Drive") },
        text = { DialogText("A different file with this name is in Abhify.AI/${c.folder} (modified ${c.driveModified?.take(16)?.replace('T', ' ') ?: "?"}). Nothing was changed. Keep both (upload as a numbered copy) or replace the Drive file?") },
        confirmButton = { TextButton({ d.resolve(c, replace = false) }) { Text("Keep both") } },
        dismissButton = { androidx.compose.foundation.layout.FlowRow { TextButton({ d.resolve(c, replace = true) }) { Text("Replace on Drive") }; TextButton({ d.conflict.value = null }) { Text("Later") } } }) }
    trashing?.let { t -> AlertDialog(onDismissRequest = { trashing = null }, title = { Text("Delete \"${t.name}\" on Google Drive?") },
        text = { DialogText("It moves to Drive's trash (restorable there for 30 days). Nothing on this phone is deleted.") },
        confirmButton = { TextButton({ d.trash(t); trashing = null }) { Text("Delete on Drive", color = LocalExtra.current.bad) } },
        dismissButton = { TextButton({ trashing = null }) { Text("Cancel") } }) }
}

// ====================================================================== storage
@Composable
fun StorageScreen(vm: AppViewModel) {
    val s = vm.storage
    LaunchedEffect(Unit) { s.measure() }
    var clearing by remember { mutableStateOf(false) }
    Screen(vm, "Storage") {
        val r = s.report.value
        if (r == null) { CircularProgressIndicator(); return@Screen }
        Glass {
            Text("Abhify on this phone: ${Storage.human(r.total)}", style = MaterialTheme.typography.titleLarge)
            listOf("Database (chats, memory, documents' text, notes, study, career)" to r.database, "  of which archived conversations" to r.archive,
                "  of which personal memory" to r.memory, "  of which imported documents' text" to r.documents, "Google Drive downloads" to r.driveDownloads,
                "AI model files" to r.models, "Temporary cache" to r.cache).forEach { (k, v) ->
                Row { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(Storage.human(v), fontWeight = FontWeight.SemiBold) }
            }
            Muted("Measured now. The model is never deleted automatically.")
        }
        SettingsGroup("Actions") {
            OutlinedButton({ clearing = true }) { Text("Clear temporary cache (${Storage.human(r.cache)})") }
            NavRow("📚", "Delete imported documents", "Documents tab") { vm.tab.value = AppViewModel.Tab.DOCS; vm.home() }
            NavRow("🗄", "Archived conversations", "Delete or restore in History") { vm.historyArchived.value = true; vm.go(AppViewModel.Route.HISTORY) }
            NavRow("💾", "Export or back up data", null) { vm.go(AppViewModel.Route.BACKUP) }
            NavRow("📦", "Model storage", vm.activeModel()?.name ?: "No model") { vm.go(AppViewModel.Route.MODELS) }
            NavRow("☁", "Google Drive", if (vm.drive.connected.value) "Connected" else "Not connected") { vm.go(AppViewModel.Route.DRIVE) }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Clear temporary cache?") },
        text = { DialogText("Removes temporary files only (shared exports, OCR scratch). Chats, documents, memory and the model are kept.") },
        confirmButton = { TextButton({ s.clearCache(); clearing = false }) { Text("Clear") } }, dismissButton = { TextButton({ clearing = false }) { Text("Cancel") } })
}

// ====================================================================== backup and restore
@Composable
fun BackupScreen(vm: AppViewModel) {
    val b = vm.backup
    LaunchedEffect(Unit) { b.loadHistory() }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val save = rememberSaver { b.message.value = it }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } } }
            .onSuccess { b.load(it, "backup file") }.onFailure { b.message.value = "Couldn't read the file: ${it.message}" } } }
    Screen(vm, "Backup & restore") {
        SettingsGroup("Create a backup") {
            Backup.CATEGORIES.keys.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c in b.selected, { on -> if (on) b.selected += c else b.selected -= c }); Text(c) } }
            OutlinedTextField(b.passphrase.value, { b.passphrase.value = it }, Modifier.fillMaxWidth(), label = { Text("Passphrase (optional, 8+ characters, encrypts the file)") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
            Muted("Never included: API keys, Google tokens, passwords, signing keys, the AI model. Without a passphrase the file is readable by anyone who has it.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ scope.launch { try { val (n, bytes) = b.create(); save(n, bytes); b.record("This phone (file)", b.selected.toList(), bytes) } catch (e: IllegalArgumentException) { b.message.value = e.message } } },
                    enabled = b.selected.isNotEmpty()) { Text("Save to a file") }
                OutlinedButton({ b.toDrive() }, enabled = b.selected.isNotEmpty() && vm.drive.busy.value == null) { Text("Upload to Drive") }
            }
        }
        b.message.value?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        ScheduleCard(vm)
        SettingsGroup("Restore") {
            Muted("Restore only ADDS: what you already have stays, and items already present are skipped. Nothing is overwritten or deleted.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ opener.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Open a backup file") }
                OutlinedButton({ vm.go(AppViewModel.Route.DRIVE) }) { Text("From Google Drive") }
            }
        }
        b.loaded.value?.let { l -> RestoreCard(vm, l) }
        b.result.value?.let { Glass { Text("✓ $it", color = LocalExtra.current.ok) } }
        if (b.history.isNotEmpty()) SettingsGroup("Recent backups") {
            b.history.forEach { h -> Text("${when_(h.createdAt)} · ${if (h.auto) "scheduled" else "manual"} · ${h.location} · ${Storage.human(h.size)}" + (if (h.encrypted) " · encrypted" else "") +
                " · " + when (h.status) { "ok" -> "✓ saved"; "queued" -> "waiting to upload (see Google Drive)"; "removed" -> "removed by retention"; else -> "✗ failed" } +
                (h.error?.let { "\n  $it" } ?: "") + "\n  ${h.categories}", style = MaterialTheme.typography.bodySmall,
                color = if (h.status == "failed") LocalExtra.current.warn else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun RestoreCard(vm: AppViewModel, l: BackupState.Loaded) {
    val b = vm.backup
    val p = l.parsed
    var cats by remember(l) { mutableStateOf(p?.categories?.filter { it != "Settings" }?.toSet() ?: emptySet()) }
    var settings by remember(l) { mutableStateOf(false) }
    Glass {
        Text("Backup: ${l.name}", style = MaterialTheme.typography.titleSmall)
        if (p == null) {
            Text("⚠ ${l.error}", color = LocalExtra.current.bad)
            if (l.error?.contains("passphrase", true) == true) {
                OutlinedTextField(b.passphrase.value, { b.passphrase.value = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(b::retryWithPassphrase) { Text("Open") }
            }
            return@Glass
        }
        Text("Made ${when_(p.createdAt)} by Abhify ${p.appVersion} · integrity: " + if (p.intact) "✓ OK" else "✗ FAILED (${p.integrity.filterValues { !it }.keys.joinToString()})",
            color = if (p.intact) LocalExtra.current.ok else LocalExtra.current.bad)
        l.preview.forEach { pl -> Text("${pl.table}: ${pl.total} in backup · ${pl.new} new · ${pl.existing} already here", style = MaterialTheme.typography.bodySmall) }
        Text("Restore", style = MaterialTheme.typography.labelLarge)
        p.categories.filter { it != "Settings" }.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(c in cats, { on -> cats = if (on) cats + c else cats - c }); Text(c) } }
        if ("Settings" in p.categories) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(settings, { settings = it })
            Text("Also apply settings (AI mode, theme, style…; never privacy switches or keys)") }
        Button({ b.restore(cats.toList(), settings) }, enabled = p.intact && (cats.isNotEmpty() || settings) && b.busy.value == null) { Text("Restore selected (add only)") }
    }
}

/** v3.5: scheduled backups (off until the user turns them on). */
@Composable
private fun ScheduleCard(vm: AppViewModel) {
    val b = vm.backup
    var cfg by remember { mutableStateOf(b.schedule.value) }
    var pass by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "chosen folder" } }.getOrDefault("chosen folder")
        cfg = cfg.copy(folderUri = uri.toString(), folderName = name) } }
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scheduled backups", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(cfg.enabled, { cfg = cfg.copy(enabled = it) })
        }
        Muted("Off by default. Android runs scheduled work at about the chosen time — sometimes later (deep sleep, low battery). Only the categories you tick are included.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!cfg.weekly, { cfg = cfg.copy(weekly = false) }, { Text("Daily") }); FilterChip(cfg.weekly, { cfg = cfg.copy(weekly = true) }, { Text("Weekly") })
        }
        if (cfg.weekly) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d -> FilterChip(cfg.weekday == i + 1, { cfg = cfg.copy(weekday = i + 1) }, { Text(d) }) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("About"); TextButton({ cfg = cfg.copy(hour = (cfg.hour + 23) % 24) }, Modifier.semantics { contentDescription = "One hour earlier" }) { Text("−") }
            Text("%02d:00".format(cfg.hour), fontWeight = FontWeight.SemiBold); TextButton({ cfg = cfg.copy(hour = (cfg.hour + 1) % 24) }, Modifier.semantics { contentDescription = "One hour later" }) { Text("+") }
        }
        Text("Include", style = MaterialTheme.typography.labelLarge)
        Backup.CATEGORIES.keys.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(c in cfg.categories, { on -> cfg = cfg.copy(categories = if (on) cfg.categories + c else cfg.categories - c) }); Text(c) } }
        Text("Save to", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(cfg.toDrive, { cfg = cfg.copy(toDrive = it) }, enabled = vm.drive.connected.value)
            Text("Google Drive (Abhify.AI/Application Backups)" + if (!vm.drive.connected.value) " — connect Drive first" else "", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(cfg.folderUri != null, { on -> if (on) folderPicker.launch(null) else cfg = cfg.copy(folderUri = null, folderName = null) })
            Text("A folder on this phone" + (cfg.folderName?.let { ": $it" } ?: ""), Modifier.weight(1f))
            if (cfg.folderUri != null) TextButton({ folderPicker.launch(null) }) { Text("Change") }
        }
        if (cfg.toDrive) Row(verticalAlignment = Alignment.CenterVertically) { Switch(cfg.wifiOnly, { cfg = cfg.copy(wifiOnly = it) }); Spacer(Modifier.width(8.dp)); Text("Wi-Fi only") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keep the newest"); TextButton({ cfg = cfg.copy(keep = (cfg.keep - 1).coerceAtLeast(1)) }, Modifier.semantics { contentDescription = "Keep one fewer" }) { Text("−") }
            Text("${cfg.keep}", fontWeight = FontWeight.SemiBold); TextButton({ cfg = cfg.copy(keep = (cfg.keep + 1).coerceAtMost(30)) }, Modifier.semantics { contentDescription = "Keep one more" }) { Text("+") }
            Text("per place")
        }
        Muted("Older scheduled backups beyond this number are removed — only ones Abhify made for this schedule (Drive: moved to trash). Manual backups and other files are never touched. The newest always stays.")
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (b.schedulePassSet.value) "Passphrase is saved (type to change)" else "Passphrase (8+; required for Drive)") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Muted("Stored encrypted with this phone's Keystore so backups can run unattended. Without it, a backup can't be restored — keep it somewhere safe.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (b.saveSchedule(cfg, pass.ifEmpty { null })) pass = "" }) { Text("Save schedule") }
            OutlinedButton(b::backupNow, enabled = cfg.categories.isNotEmpty()) { Text("Back up now") }
            if (b.schedulePassSet.value) TextButton(b::forgetSchedulePassphrase) { Text("Forget passphrase") }
        }
        b.nextRun()?.let { Text("Next: about " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)),
            style = MaterialTheme.typography.bodySmall) }
        b.lastRun()?.let { (t, ok, err) -> Text("Last scheduled run: ${when_(t)} · " + if (ok) "✓ OK" else "✗ ${err ?: "failed"}", style = MaterialTheme.typography.bodySmall,
            color = if (ok) LocalExtra.current.ok else LocalExtra.current.warn) }
    }
}
