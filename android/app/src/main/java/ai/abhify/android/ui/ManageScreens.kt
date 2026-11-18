package ai.abhify.android.ui

import ai.abhify.android.BuildConfig
import ai.abhify.android.SetupController
import ai.abhify.android.cloud.Account
import ai.abhify.android.device.DeviceProbe
import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.Compatibility
import ai.abhify.android.sync.SyncStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

@Composable
private fun Page(title: String, vm: AppViewModel, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTitle(title, { vm.back() })
        androidx.compose.runtime.CompositionLocalProvider(LocalRowAccent provides PageIdentity.of(title)?.first) { PageBody(content = content) }
    }
}

private val FOLDERS = listOf("Personal", "Learning", "Work", "Documents")

private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
internal fun Confirm(title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit) =
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { DialogText(text) },
        confirmButton = { Button({ onDismiss(); onOk() }) { Text(ok) } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })

// ---------------------------------------------------------------------- history
@Composable
fun HistoryScreen(vm: AppViewModel, exportAll: () -> Unit) = Page("Conversation history", vm) {
    LaunchedEffect(vm.historyQuery.value) { vm.loadHistory() }
    var del by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(vm.historyFolder.value) { vm.loadHistory() }
    var saveAs by remember { mutableStateOf<Pair<Long, String>?>(null) }      // conversation id, "md" | "txt"
    var pdfFor by remember { mutableStateOf<Long?>(null) }                     // v4.4: conversation id to save as PDF
    val pdfSaver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = pdfFor; pdfFor = null
        if (uri != null && id != null) scope.launch {
            runCatching {
                val bytes = vm.conversationPdf(id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("couldn't open the file") }
            }.onSuccess { android.widget.Toast.makeText(ctx, "Saved as PDF", android.widget.Toast.LENGTH_SHORT).show() }
             .onFailure { android.widget.Toast.makeText(ctx, "Couldn't save the PDF: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val req = saveAs; saveAs = null
        if (uri != null && req != null) scope.launch {
            val md = vm.conversationMarkdown(req.first)
            val out = if (req.second == "txt") md.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("(?m)^# "), "") else md
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(out.toByteArray(Charsets.UTF_8)) } }
                .onSuccess { android.widget.Toast.makeText(ctx, "Saved", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(ctx, "Couldn't save: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(vm.historyArchived.value, vm.historyFrom.value, vm.historyTo.value, vm.historyPages.intValue) { vm.loadHistory() }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(!vm.historyArchived.value, { vm.historyArchived.value = false; vm.historyPages.intValue = 1 }, { Text("Active") })
        androidx.compose.material3.FilterChip(vm.historyArchived.value, { vm.historyArchived.value = true; vm.historyPages.intValue = 1 }, { Text("Archived") })
    }
    Field(vm.historyQuery.value, { vm.historyQuery.value = it }, "Search titles and messages (active and archived)")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(vm.historyFrom.value, { vm.historyFrom.value = it.take(10) }, "From (2026-11-01)", modifier = Modifier.weight(1f))
        Field(vm.historyTo.value, { vm.historyTo.value = it.take(10) }, "To", modifier = Modifier.weight(1f))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf<String?>(null) + FOLDERS).forEach { f -> androidx.compose.material3.FilterChip(vm.historyFolder.value == f, { vm.historyFolder.value = f }, { Text(f ?: "All") }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(exportAll) { Text("Export all (JSON)") }
        Text("Exports chats and memories only, never keys or settings.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
    }
    if (vm.conversations.isEmpty()) { if (vm.historyQuery.value.isBlank()) EmptyState("💬", "No conversations yet", "Your chats appear here.") else EmptyState("🔍", "Nothing matches", "Try other words or dates.") }
    vm.conversations.forEach { c ->
        Glass(onClick = { vm.loadConversation(c.id); vm.tab.value = AppViewModel.Tab.CHAT; vm.home() }) {
            if (renaming?.first == c.id) {
                Field(renaming!!.second, { renaming = c.id to it.take(80) }, "Title")
                Row { Button({ vm.rename(c.id, renaming!!.second); renaming = null }) { Text("Save") }; TextButton({ renaming = null }) { Text("Cancel") } }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (c.pinned) "📌 " else "") + c.title.ifBlank { "Chat" }, style = MaterialTheme.typography.titleSmall)
                        Text(date(c.updatedAt) + " · " + (c.folder ?: "Personal") + if (c.archived) " · archived" else "", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        c.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton({ vm.pin(c.id, !c.pinned) }) { Text(if (c.pinned) "Unpin" else "Pin") }
                    TextButton({ renaming = c.id to c.title }) { Text("Rename") }
                    TextButton({ scope.launch { val md = vm.conversationMarkdown(c.id)
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, md), "Share chat")) } }) { Text("Share") }
                    TextButton({ del = c.id }) { Text("Delete", color = LocalExtra.current.bad) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    var folderMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ folderMenu = true }) { Text("Folder ▾") }
                        androidx.compose.material3.DropdownMenu(folderMenu, { folderMenu = false }) {
                            FOLDERS.forEach { f -> androidx.compose.material3.DropdownMenuItem({ Text(f) }, { folderMenu = false; vm.setFolder(c.id, f.takeIf { it != "Personal" }) }) }
                        }
                    }
                    TextButton({ vm.summarizeChat(c.id) }, enabled = vm.taskBusy.value == null) { Text("Summarize") }
                    TextButton({ saveAs = c.id to "md"; saver.launch((c.title.ifBlank { "chat" }).take(40) + ".md") }) { Text("Save .md") }
                    TextButton({ saveAs = c.id to "txt"; saver.launch((c.title.ifBlank { "chat" }).take(40) + ".txt") }) { Text("Save .txt") }
                    TextButton({ pdfFor = c.id; pdfSaver.launch((c.title.ifBlank { "chat" }).take(40) + ".pdf") }) { Text("Save .pdf") }
                    TextButton({ vm.archive(c.id, !c.archived) }) { Text(if (c.archived) "Restore" else "Archive") }
                    TextButton({ vm.drive.uploadConversation(c) }, enabled = vm.drive.busy.value == null) { Text("☁ To Drive") }
                }
            }
        }
    }
    if (vm.historyMore.value) TextButton({ vm.historyPages.intValue++ }) { Text("Load more") }
    del?.let { id -> Confirm("Delete this conversation?", "It is removed from this phone (and, if sync is on, from your other devices).", "Delete", { vm.deleteConversation(id) }) { del = null } }
}

// ---------------------------------------------------------------------- models
@Composable
fun ModelsScreen(vm: AppViewModel, askNotifications: () -> Unit) = Page("AI models", vm) {
    val state by vm.g.setup.state.collectAsStateWithLifecycle()
    val d = vm.device.value
    val leftover = remember(state) { vm.g.setup.leftover() }
    var selected by remember { mutableStateOf(vm.recommended()?.id ?: leftover?.first?.id) }
    var confirm by remember { mutableStateOf<CatalogModel?>(null) }
    var removeId by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    val busy = state is SetupController.State.Downloading || state is SetupController.State.Verifying || state is SetupController.State.SelfTesting
    val inst = vm.g.store.active()

    SettingsGroup("This phone") {
        Text("${d.manufacturer} ${d.model} · Android ${d.release} (API ${d.sdkInt})", style = MaterialTheme.typography.bodyMedium)
        Text("${d.socModel} · ${DeviceProbe.cpuSummary(d)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        Text("RAM %.1f GB (%d MB free) · %.1f GB free storage · GPU: %s".format(d.totalRamMb / 1024.0, d.availRamMb, d.freeStorageMb / 1024.0,
            d.vulkanVersion?.let { "Vulkan $it (used only after Test GPU)" } ?: "not reported"), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }

    if (inst != null) {
        val m = vm.activeModel()
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(m?.name ?: inst.id, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill("in use", LocalExtra.current.ok) }
            Text("Used by chat on this phone and for new chats (the default). Switch any time from the model chip above the chat box.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Text("Version ${inst.version.take(12)} · SHA-256 ${inst.sha256.take(16)}…\nRuntime ${inst.runtime}\n${vm.g.threads()} threads · ${if (vm.g.gpuEnabled) "GPU (checked on this phone)" else "CPU"} · test: ${inst.selfTest}",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Text("Context size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (m?.contextOptions ?: listOf(inst.context)).forEach { c -> FilterChip(inst.context == c, { vm.setContext(c) }, { Text("$c") }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(vm::runBenchmark, enabled = !vm.toolBusy.value) { Text("Benchmark") }
                OutlinedButton(vm::verifyModel, enabled = !vm.toolBusy.value) { Text("Check file") }
                OutlinedButton(vm::checkGpu, enabled = !vm.toolBusy.value) { Text("Test GPU") }
            }
            vm.toolResult.value?.let { r ->
                SelectionContainer { Text(r, style = MaterialTheme.typography.bodySmall) }
                TextButton({ clip.setText(AnnotatedString(r)) }) { Text("Copy result") }
            }
            TextButton({ removeId = inst.id }) { Text("Remove this model", color = LocalExtra.current.bad) }
        }
    }

    // 4.6 performance on this phone: only values the app measures (engine memory, Android's free RAM, the last answer's speed)
    if (inst != null) SettingsGroup("Performance on this phone", accent = Accent.STUDY) {
        val loaded = vm.g.engine.loaded != null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(if (loaded) "${vm.g.engine.currentMemoryMb()} MB" else "–", if (loaded) "app memory now" else "model not loaded", Modifier.weight(1f), accent = Accent.STUDY)
            StatTile("${d.availRamMb} MB", "free RAM", Modifier.weight(1f), accent = Accent.DOCS)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(vm.lastSpeed.value?.let { "%.1f".format(it.tokensPerSecond) } ?: "–", vm.lastSpeed.value?.let { "tokens/s · ${it.source}" } ?: "no answer yet", Modifier.weight(1f), accent = Accent.CHAT)
            StatTile(if (loaded) "${vm.g.engine.peakMemoryMb()} MB" else "–", "peak app memory", Modifier.weight(1f), accent = Accent.MEMORY)
        }
        Text("${vm.g.threads()} threads · ${vm.g.backendLabel()} · measured by the app; Benchmark below runs a fixed test.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        TextButton(vm::refreshDevice) { Text("Refresh") }
    }

    val others = remember(vm.modelRev.intValue, inst?.id) { vm.g.store.installed().filter { it.id != inst?.id && vm.g.store.modelFile(it.id).exists() } }
    if (others.isNotEmpty()) {
        SectionTitle("Also installed on this phone")
        others.forEach { o ->
            val om = vm.g.catalog.byId(o.id)
            Glass {
                Text(om?.name ?: o.id, fontWeight = FontWeight.SemiBold)
                Text("${om?.parameters ?: ""} · ${om?.quantization ?: ""} · context ${o.context} · test: ${o.selfTest}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Use for chat", { vm.pickModel(ai.abhify.android.ai.ModelChoices.deviceKey(o.id)) }, Modifier.weight(1f),
                        enabled = !vm.generating.value, busy = vm.modelSwitching.value != null)
                    TextButton({ removeId = o.id }) { Text("Remove", color = LocalExtra.current.bad) }
                }
            }
        }
        vm.modelPickError.value?.let { Text(it, color = LocalExtra.current.bad, style = MaterialTheme.typography.bodyMedium) }
    }

    when (val s = state) {
        is SetupController.State.Downloading -> Progress("Downloading ${vm.g.catalog.byId(s.modelId)?.name}", s.done, s.total) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(vm::pauseDownload) { Text("Pause") }; TextButton({ vm.cancelDownload(s.modelId) }) { Text("Cancel") } }
        }
        is SetupController.State.Verifying -> BusyCard("Checking the download (size and SHA-256)…")
        is SetupController.State.SelfTesting -> BusyCard("Testing the model with a real answer on this phone…")
        is SetupController.State.Installed -> Glass {
            Text("✅ Ready", fontWeight = FontWeight.Bold, color = LocalExtra.current.ok)
            Text("The model passed its test: ${s.detail}")
            GradientButton("Start chatting", vm::finishSetup, Modifier.fillMaxWidth())
        }
        is SetupController.State.Failed -> Glass {
            Text("Setup did not finish", fontWeight = FontWeight.Bold, color = LocalExtra.current.bad); Text(s.reason)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.g.catalog.byId(s.modelId)?.let { vm.startDownload(it) } }) { Text(if (s.canResume) "Try again" else "Download again") }
                TextButton({ vm.cancelDownload(s.modelId) }) { Text("Discard") }
            }
        }
        is SetupController.State.Paused -> Progress("Paused: ${s.reason}", s.done, s.total) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ vm.g.catalog.byId(s.modelId)?.let { vm.startDownload(it) } }) { Text("Resume") }; TextButton({ vm.cancelDownload(s.modelId) }) { Text("Cancel download") } }
        }
        SetupController.State.Idle -> leftover?.let { (m, bytes) ->
            Progress("A download of ${m.name} was interrupted", bytes, m.sizeBytes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ vm.startDownload(m) }) { Text("Resume") }; TextButton({ vm.cancelDownload(m.id) }) { Text("Discard") } }
            }
        }
    }

    if (!busy && state !is SetupController.State.Installed) {
        SectionTitle(if (inst == null) "Download an AI model (one time, then works offline)" else "Other models")
        vm.compat().forEach { c ->
            val m = when (c) { is Compatibility.Ok -> c.model; is Compatibility.No -> c.model }
            val ok = c is Compatibility.Ok
            Glass(onClick = if (ok) ({ selected = m.id }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (m.id == vm.recommended()?.id) Pill("recommended", LocalExtra.current.ok)
                    if (m.id == inst?.id) { Spacer(Modifier.width(6.dp)); Pill("in use", LocalExtra.current.ok) }
                    else if (others.any { it.id == m.id }) { Spacer(Modifier.width(6.dp)); Pill("installed") }
                    if (selected == m.id && ok) { Spacer(Modifier.width(6.dp)); Pill("selected") }
                }
                Text("${m.sizeMb} MB download · ${m.quantization} · ${m.license}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Text(m.notes, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                when (c) {
                    is Compatibility.No -> c.reasons.forEach { Text("✗ $it", color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall) }
                    is Compatibility.Ok -> c.warnings.forEach { Text("⚠ $it", color = LocalExtra.current.warn, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        val pick = vm.g.catalog.byId(selected ?: "")
        if (pick == null) Text("No model fits this phone. Nothing will be downloaded.", color = LocalExtra.current.bad)
        else if (others.any { it.id == pick.id }) GradientButton("Use ${pick.name} for chat", { vm.pickModel(ai.abhify.android.ai.ModelChoices.deviceKey(pick.id)) }, Modifier.fillMaxWidth(),
            enabled = !vm.generating.value, busy = vm.modelSwitching.value != null)
        else if (pick.id != inst?.id) GradientButton("Download ${pick.name} (${pick.sizeMb} MB)…", { confirm = pick }, Modifier.fillMaxWidth())
    }

    confirm?.let { m -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Download ${m.name}?") },
        text = { DialogText("Size: ${m.sizeMb} MB (${m.sizeBytes} bytes), one time. Wi-Fi recommended.\nMemory while running: about ${m.estimatedRuntimeMb} MB.\n" +
            "Source: ${m.source}\nVersion: ${m.version.take(12)}\nLicense: ${m.license}\nChecked with SHA-256 ${m.sha256.take(16)}… then tested with a real answer.\n\n" +
            "After this the AI works offline. Your current model (if any) stays until the new one passes its test.") },
        confirmButton = { Button({ confirm = null; askNotifications(); vm.startDownload(m) }) { Text("Download") } },
        dismissButton = { TextButton({ confirm = null }) { Text("Not now") } }) }
    removeId?.let { id -> Confirm("Remove the AI model?", "Frees its storage. Chats and memories are kept. You can download it again later.", "Remove", { vm.removeModel(id) }) { removeId = null } }
}

@Composable
private fun Progress(title: String, done: Long, total: Long, actions: @Composable () -> Unit) = Glass {
    Text(title, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
    Text("%d of %d MB".format(done / 1048576, total / 1048576), color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
    actions()
}

@Composable
private fun BusyCard(text: String) = BusyBanner(text)                                    // 5.6: one loading style everywhere

// ---------------------------------------------------------------------- account
@Composable
fun AccountScreen(vm: AppViewModel) = Page("Account", vm) {
    var name by remember { mutableStateOf(vm.g.userName) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var pwCurrent by remember { mutableStateOf("") }
    var pwNew by remember { mutableStateOf("") }
    var deletePw by remember { mutableStateOf("") }
    val acct = vm.account.value
    SettingsGroup("Local profile (this phone)") {
        Field(name, { name = it.take(40); vm.g.userName = name }, "Your name")
        Text("Works with no account. Chats, memories and progress are stored on this phone.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    when (acct) {
        is Account.SignedIn -> {
            LaunchedEffect(Unit) { vm.loadSessions() }
            SettingsGroup("Online account") {
                Text("${acct.session.displayName} · ${acct.session.email}\nServer: ${acct.session.serverUrl}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                vm.linked()?.let { Text("Linked to this profile since ${date(it.linkedAt)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.logout() }) { Text("Sign out") }
                    OutlinedButton({ confirm = "unlink" }) { Text("Unlink") }
                }
            }
            SettingsGroup("Signed-in devices") {
                vm.sessions.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.optString("device_name") + if (s.optBoolean("current")) "  (this phone)" else "")
                            Text("Last seen ${date(s.optLong("last_seen_at") * 1000)}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        }
                        if (!s.optBoolean("current")) TextButton({ vm.endSession(s.getString("id")) }) { Text("Sign out", color = LocalExtra.current.bad) }
                    }
                }
            }
            SettingsGroup("Change password") {
                Field(pwCurrent, { pwCurrent = it }, "Current password", password = true)
                Field(pwNew, { pwNew = it }, "New password", password = true)
                Button({ vm.changePassword(pwCurrent, pwNew); pwCurrent = ""; pwNew = "" }, enabled = pwCurrent.isNotBlank() && pwNew.isNotBlank()) { Text("Change password") }
            }
            Glass {
                Text("Delete account", fontWeight = FontWeight.SemiBold, color = LocalExtra.current.bad)
                Text("Deletes the online account and all cloud copies. Everything on this phone stays.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Field(deletePw, { deletePw = it }, "Password to confirm", password = true)
                OutlinedButton({ confirm = "delete" }, enabled = deletePw.isNotBlank(), border = BorderStroke(1.dp, LocalExtra.current.bad)) { Text("Delete my account", color = LocalExtra.current.bad) }
            }
        }
        is Account.Expired -> Glass {
            Text("Sign-in expired", fontWeight = FontWeight.SemiBold, color = LocalExtra.current.warn)
            Text("${acct.email}: sign in again to use online AI and sync. Everything on this phone still works.", style = MaterialTheme.typography.bodySmall)
            GradientButton("Sign in again", { vm.server.value = acct.serverUrl; vm.go(AppViewModel.Route.LOGIN) }, Modifier.fillMaxWidth())
        }
        Account.SignedOut -> SettingsGroup("Online account (optional)") {
            Text("An account adds online AI and optional sync between your devices. The app works fully without one.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            GradientButton("Sign in", { vm.go(AppViewModel.Route.LOGIN) }, Modifier.fillMaxWidth())
            TextButton({ vm.go(AppViewModel.Route.REGISTER) }) { Text("Create an account") }
        }
    }
    Message(vm.authError.value, true); Message(vm.authInfo.value, false)
    when (confirm) {
        "unlink" -> Confirm("Unlink this phone?", "Signs out and disconnects this profile from the account. Every chat and memory stays on this phone; sync stops.", "Unlink", { vm.unlink() }) { confirm = null }
        "delete" -> Confirm("Delete your online account?", "This permanently deletes the account and its cloud data. This phone's data is not touched. This can't be undone.", "Delete account", { vm.deleteAccount(deletePw); deletePw = "" }) { confirm = null }
    }
}

// ---------------------------------------------------------------------- sync & network
@Composable
fun SyncScreen(vm: AppViewModel) = Page("Sync & network", vm) {
    LaunchedEffect(Unit) { vm.loadSyncScreen() }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmEnable by remember { mutableStateOf(false) }
    val signedIn = vm.account.value is Account.SignedIn
    SettingsGroup("Status") {
        StatusLine(vm.status())
        Text("Internet: ${if (vm.online.value) "connected" else "offline"} · Account: ${when (vm.account.value) { is Account.SignedIn -> "signed in"; is Account.Expired -> "sign-in expired"; else -> "not signed in" }}",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        Text("Having internet doesn't mean your Abhify server is reachable; errors from the server are shown separately.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        when (val s = vm.sync.value) {
            is SyncStatus.Done -> Text("Last sync ${date(s.at)}: ↑${s.report.pushed} ↓${s.report.pulled}" + if (s.report.conflictsResolved > 0) " · ${s.report.conflictsResolved} conflicts" else "",
                style = MaterialTheme.typography.bodySmall)
            is SyncStatus.Failed -> Text("Last attempt ${date(s.at)} failed: ${s.message}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.bad)
            else -> {}
        }
        if (vm.pending.intValue > 0) Text("${vm.pending.intValue} changes on this phone not uploaded yet", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn)
    }
    SettingsGroup("Cloud sync") {
        if (!signedIn) Text("Sign in to an account to sync between devices. Without sync, everything stays on this phone.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        SwitchRow("Sync with my account", "Off by default. Local data always works without it.", vm.syncOn.value, enabled = signedIn) { on -> if (on) confirmEnable = true else vm.setSync(false) }
        SwitchRow("Conversations", null, vm.g.syncKind("conversation"), enabled = signedIn) { vm.setSyncKind("conversation", it) }
        SwitchRow("Memories", null, vm.g.syncKind("memory"), enabled = signedIn) { vm.setSyncKind("memory", it) }
        SwitchRow("Preferences (theme, AI mode, read aloud)", null, vm.g.syncKind("preference"), enabled = signedIn) { vm.setSyncKind("preference", it) }
        SwitchRow("Only on Wi-Fi", null, vm.wifiOnly.value, enabled = signedIn) { vm.setWifiOnly(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(vm::syncNow, enabled = signedIn && vm.syncOn.value && vm.online.value) { Text("Sync now") }
            OutlinedButton({ confirmDelete = true }, enabled = signedIn) { Text("Delete cloud data") }
        }
        Text("Never uploaded: AI model files and documents.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    if (vm.conflicts.isNotEmpty()) {
        SectionTitle("Edited on two devices (${vm.conflicts.size}): choose which to keep")
        vm.conflicts.forEach { c ->
            Glass {
                Text(c.kind.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                Text("This phone (${date(c.localAt)}): " + conflictText(c.local), style = MaterialTheme.typography.bodySmall)
                Text("Other device (${date(c.remoteAt)}): " + conflictText(c.remote), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.resolveConflict(c.id, true) }) { Text("Keep this phone's") }
                    OutlinedButton({ vm.resolveConflict(c.id, false) }) { Text("Keep other") }
                }
            }
        }
    }
    Message(vm.authError.value, true); Message(vm.authInfo.value, false)
    if (confirmEnable) { val p = vm.preview.value
        Confirm("Turn on cloud sync?", "These will be uploaded to your account and kept in sync with your other devices:\n" +
            "• ${p?.conversations ?: 0} conversations (${p?.messages ?: 0} messages)\n• ${p?.memories ?: 0} memories\n• preferences\n\n" +
            "Turn off any kind above first if you don't want it synced. Model files and documents are never uploaded.", "Turn on", { vm.setSync(true) }) { confirmEnable = false } }
    if (confirmDelete) Confirm("Delete cloud data?", "Deletes every cloud copy of your chats, memories and preferences, and turns sync off. Nothing on this phone is deleted.",
        "Delete cloud data", { vm.deleteCloudData() }) { confirmDelete = false }
}

private fun conflictText(o: org.json.JSONObject) = when {
    o.optBoolean("_deleted") -> "(deleted)"
    o.has("content") -> o.optString("content")
    o.has("title") -> o.optString("title")
    else -> o.optString("value")
}.take(160)

// ---------------------------------------------------------------------- privacy
@Composable
fun PrivacyScreen(vm: AppViewModel) = Page("Privacy", vm) {
    SettingsGroup("Online AI") {
        SwitchRow("Allow online AI", "When on, your messages (and recent chat context) are sent to your Abhify server to be answered. Off: everything stays on this phone.",
            vm.onlineAllowed.value) { vm.setOnlineAllowed(it) }
        SwitchRow("Also send my memories to the online AI", "Off by default. When off, online answers don't see your saved memories.",
            vm.shareMemories.value, enabled = vm.onlineAllowed.value) { vm.setShareMemories(it) }
        SwitchRow("Use memories at all", "Off: nothing is remembered or added to prompts (on this phone or online).", vm.memoriesEnabled.value) { vm.setMemoriesEnabled(it) }
        NavRow("🔑", "AI providers and their data policies") { vm.go(AppViewModel.Route.PROVIDERS) }
    }
    SettingsGroup("What leaves this phone") {
        Text("• On-device answers: nothing.\n• Online answers (only if allowed): your question, recent messages of that chat, your name, your custom instructions, and memories only if allowed. " +
            "They go to the provider shown above the message box: Gemini, Groq or OpenRouter with this phone's own key (directly, never via the Abhify server), or your Abhify server.\n" +
            "• API keys: stay on this phone, encrypted with the Android Keystore; never synced, backed up or exported.\n" +
            "• Cloud sync (only if you turn it on): the kinds you choose in Sync settings.\n• News: the app downloads public feeds; it sends nothing about you.\n" +
            "• Model download: a file request to the model publisher.\n• Never: AI model files, documents, your password (only tokens are kept, encrypted with the Android Keystore).",
            style = MaterialTheme.typography.bodySmall)
        Text("On this phone, data is in the app's private storage and excluded from Android cloud backups. The database file itself is not app-encrypted (the phone's storage encryption applies).",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    ConversationDataGroup(vm)                                     // 5.4: where conversations live and how to delete them
    var wipe by remember { mutableStateOf(false) }
    OutlinedButton({ wipe = true }, border = BorderStroke(1.dp, LocalExtra.current.bad)) { Text("Delete all chats and memories on this phone", color = LocalExtra.current.bad) }
    if (wipe) Confirm("Delete all chats and memories?", "This can't be undone. If sync is on, they're also removed from your other devices.", "Delete", { vm.deleteAllChats() }) { wipe = false }
    var wipeStudio by remember { mutableStateOf(false) }
    OutlinedButton({ wipeStudio = true }, border = BorderStroke(1.dp, LocalExtra.current.bad)) { Text("Delete all documents, notes, tasks, plans, flashcards, career, study and saved-knowledge data", color = LocalExtra.current.bad) }
    if (wipeStudio) Confirm("Delete Documents, Learn and Work data?", "Your document library (with extracted text and resume versions), workspaces, notes, tasks, study plans, flashcards, job applications, " +
        "job descriptions, interview practice, study history and My Knowledge items are removed from this phone. " +
        "They were never synced. Chats, memories, keys and the AI model are not affected.", "Delete", { vm.deleteStudioData() }) { wipeStudio = false }
    Text("Your provider keys are deleted in AI providers (Remove or Reset). The downloaded AI model is removed in AI models.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
}

// ---------------------------------------------------------------------- settings
@Composable
fun SettingsScreen(vm: AppViewModel) = Page("Settings", vm) {
    // Since the stability milestone Settings is the one place for AI, models, performance and system configuration
    // (moved from More); the existing pages are opened, never duplicated.
    SettingsGroup("AI", accent = Accent.CHAT) {
        SegmentedTabs(ai.abhify.android.ai.AiMode.entries.map { it.label }, vm.aiMode.value.ordinal) { vm.setAiMode(ai.abhify.android.ai.AiMode.entries[it]) }
        // 5.3: the AI screens (providers, memory, Knowledge Center, models, connection, sync) open from More → Knowledge / AI & System
        SwitchRow("Suggestions on Home", "From your projects, to-dos, study and corrections on this phone. No notifications, no AI runs in the background.",
            vm.companion.enabled.value) { vm.companion.setEnabled(it) }
        SwitchRow("Continue working on Home", "Your last chat, the project you last updated, an unfinished AI task and a study plan in progress (last 14 days).",
            vm.companion.continueEnabled.value) { vm.companion.setContinueEnabled(it) }
    }
    SettingsGroup("Performance", accent = Accent.STUDY) {
        MemoryProfileCard(vm)
    }
    SettingsGroup("Appearance") {
        val themes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        SegmentedTabs(themes.map { it.second }, themes.indexOfFirst { it.first == vm.theme.value }.coerceAtLeast(0)) { vm.setTheme(themes[it].first) }
        Text("Text size", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.settings.Personalization.TextSize.entries.forEach { t -> FilterChip(vm.textSize.value == t, { vm.setTextSize(t) }, { Text(t.label) }) }
        }
        Text("On top of your phone's own font size.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    SettingsGroup("Answer style") {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.chat.PromptBuilder.Personality.entries.forEach { p -> FilterChip(vm.personality.value == p, { vm.setPersonality(p) }, { Text(p.label) }) }
        }
        vm.personality.value.instruction.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
        // 5.1 adaptive responses: preferences added to every answer's instructions (Auto = nothing added, the previous behaviour)
        val rp = vm.responsePrefs.value
        val I = ai.abhify.android.intel.Intelligence
        Text("Detail", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.intel.Intelligence.Detail.entries.forEach { d -> FilterChip(rp.detail == d, { vm.setResponsePrefs(rp.copy(detail = d)) }, { Text(d.label) }) } }
        Text("Technical depth", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.intel.Intelligence.Depth.entries.forEach { d -> FilterChip(rp.depth == d, { vm.setResponsePrefs(rp.copy(depth = d)) }, { Text(d.label) }) } }
        Text("Explanation style", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.intel.Intelligence.Style.entries.forEach { d -> FilterChip(rp.style == d, { vm.setResponsePrefs(rp.copy(style = d)) }, { Text(d.label) }) } }
        var lang by remember { mutableStateOf(rp.language) }
        OutlinedTextField(lang, { lang = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Preferred language (empty = the language you write in)") },
            isError = lang.isNotBlank() && I.cleanLanguage(lang) == null)
        if (lang != rp.language) TextButton({ vm.setResponsePrefs(rp.copy(language = lang)) }, enabled = lang.isBlank() || I.cleanLanguage(lang) != null) { Text("Save language") }
        Text("Chat focus (Auto, Coding, Learning, Career, Research) is chosen in the chat bar.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        Text("Creativity (AI on this phone)", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.abhify.android.settings.Personalization.Creativity.entries.forEach { c -> FilterChip(vm.creativity.value == c, { vm.setCreativity(c) }, { Text(c.label) }) }
        }
        Text(vm.creativity.value.hint + " Online providers keep their own settings.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    SettingsGroup("Custom instructions") {
        var prompt by remember { mutableStateOf(vm.customPrompt.value) }
        var preview by remember { mutableStateOf<String?>(null) }
        SwitchRow("Use custom instructions", "Off keeps the text but doesn't add it to chats.", vm.customPromptEnabled.value) { vm.setCustomPromptEnabled(it) }
        androidx.compose.material3.OutlinedTextField(prompt, { prompt = it.take(1500) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
            placeholder = { Text("e.g. Explain like I'm a beginner and give one code example.") }, supportingText = { Text("${prompt.length} / 1500") })
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({ vm.setCustomPrompt(prompt) }, enabled = prompt != vm.customPrompt.value) { Text(if (prompt == vm.customPrompt.value) "Saved" else "Save") }
            TextButton({ prompt = ""; vm.setCustomPrompt("") }) { Text("Clear") }
            TextButton({ vm.setCustomPrompt(prompt); preview = vm.instructionsPreview() }) { Text("Preview") }
        }
        Text("Added to every chat (on this phone and online) while turned on.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        preview?.let { pv -> androidx.compose.material3.AlertDialog(onDismissRequest = { preview = null }, confirmButton = { TextButton({ preview = null }) { Text("Close") } },
            title = { Text("What the AI is told") },
            text = { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(pv, style = MaterialTheme.typography.bodyMedium) } }) }
    }
    // 5.4 Voice & Conversation (replaces "Voice & audio": read aloud and speed are inside, now saved)
    VoiceSettingsGroup(vm)
    LiveInfoSettingsGroup(vm)                                          // 6.2 real-time information (what may be looked up, which services)
    BriefingSettingsGroup(vm)                                          // next build: personal briefing (reminder off by default)
    SettingsGroup("Diagnostics") {
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        OutlinedButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(vm.providerDiagnostics() + "\n" + vm.securityDiagnostics())) }) { Text("Copy diagnostics (no keys)") }
        Text(vm.securityDiagnostics(), style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
    }
    SettingsGroup("Learning progress") {
        OutlinedButton(vm::resetProgress) { Text("Reset quiz score") }
    }
}

// ---------------------------------------------------------------------- about
@Composable
fun AboutScreen(vm: AppViewModel) = Page("About", vm) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrandLockup(112)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
    }
    SettingsGroup("Open-source components") {
        Text("• llama.cpp ${vm.g.catalog.runtime.version} (${vm.g.catalog.runtime.commit.take(8)}), MIT\n• AI models: Qwen2.5 Instruct (Apache-2.0), downloaded from the publisher\n" +
            "• Inter typeface v4.1, SIL Open Font License\n• AndroidX / Jetpack Compose, Apache-2.0", style = MaterialTheme.typography.bodySmall)
    }
}
