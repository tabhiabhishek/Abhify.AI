package ai.abhify.android.ui

import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveAuth
import ai.abhify.android.drive.DriveMigration
import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v4.2: everything the Google sign-in needs on screen — Android's account chooser, Google's consent screen, and the
 * "you have waiting uploads" question before switching accounts. Place once on any screen that can connect Drive.
 */
@Composable
internal fun DriveAuthHost(vm: AppViewModel) {
    val d = vm.drive
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> d.onConsentResult(r.data) }
    LaunchedEffect(d.consent.value) { d.consent.value?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        d.onAccountChosen(r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)) }
    LaunchedEffect(d.chooseAccount.value) { if (d.chooseAccount.value) runCatching { chooser.launch(DriveAuth.chooseAccountIntent(d.account.value?.email)) }
        .onFailure { d.onAccountChosen(null); d.message.value = "Android's account chooser couldn't open: ${it.message}" } }
    d.switchConfirm.value?.let { n -> AlertDialog(onDismissRequest = { d.switchConfirm.value = null }, title = { Text("Switch Google account?") },
        text = { DialogText("$n upload(s) are waiting for ${d.account.value?.label}. They stay with that account and are sent only when you switch back — never to the new account. " +
            "Nothing on this phone or on either Drive is deleted.") },
        confirmButton = { TextButton({ d.switchAccount() }) { Text("Switch") } }, dismissButton = { TextButton({ d.switchConfirm.value = null }) { Text("Cancel") } }) }
}

/** v4.2 Settings → Google Drive: status, account, folder, actions; or the optional first-time setup. */
@Composable
internal fun DriveStatusCard(vm: AppViewModel) {
    val d = vm.drive
    val ctx = LocalContext.current
    var guide by remember { mutableStateOf(false) }
    if (!d.connected.value) SettingsGroup("Connect Google Drive") {
        Text("Connect your Google account to organize your Abhify.AI backups, documents and supported files.", style = MaterialTheme.typography.bodyMedium)
        listOf("Optional: Abhify works fully without it.", "Each person connects their own Google account.",
            "Limited access: only files Abhify creates or you choose (drive.file).", "No Google Cloud setup needed on your side; no password is typed into Abhify.",
            "Disconnect at any time; your files stay where they are.").forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Prereqs(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientButton("Connect Google Drive", { d.connect { d.refresh() } }, Modifier.weight(1f), enabled = d.busy.value == null)
            TextButton({ vm.back() }) { Text("Not now") }
        }
        TextButton({ guide = true }) { Text("Learn more") }
        d.account.value?.let { Text("Last account: ${it.label} (Connect uses it again; Switch to choose another).", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
    } else Glass {
        Text("✓ Google Drive connected", style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.ok)
        Text("Your Abhify.AI files are organized in My Drive → Abhify.AI.", style = MaterialTheme.typography.bodyMedium)
        Row { Text("Account", Modifier.weight(1f), color = LocalExtra.current.muted); Text(d.account.value?.label ?: "checking…", fontWeight = FontWeight.SemiBold) }
        Row { Text("Folder", Modifier.weight(1f), color = LocalExtra.current.muted)
            Text(when (d.rootState.value) { "available" -> "My Drive → Abhify.AI ✓"; "missing" -> "not found"; else -> "My Drive → Abhify.AI (not checked yet)" },
                color = if (d.rootState.value == "missing") LocalExtra.current.warn else MaterialTheme.colorScheme.onSurface) }
        Row { Text("Last successful check", Modifier.weight(1f), color = LocalExtra.current.muted); Text(d.lastOk.value?.let { when_(it) } ?: "never") }
        Text("Sub-folders: Backups · Documents · Imports · Exports", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        if (d.rootState.value == "missing") Glass {
            Text("The Abhify.AI folder is unavailable", fontWeight = FontWeight.SemiBold, color = LocalExtra.current.warn)
            Text("It was deleted, moved, or access was removed. Recreate it (nothing else on your Drive is changed or deleted), or reconnect.", style = MaterialTheme.typography.bodySmall)
            Row { Button(d::recreateFolder, enabled = d.busy.value == null) { Text("Recreate folder") }; TextButton({ d.connect { d.check() } }) { Text("Reconnect") } }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({
                val id = d.rootId()
                if (id == null) { d.message.value = "The folder isn't known yet: tap Check connection first."; return@Button }
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DriveApi.folderUrl(id))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { d.message.value = "Couldn't open Google Drive here. Open the Google Drive app and go to My Drive → Abhify.AI." }
            }, enabled = d.rootId() != null) { Text("Open Abhify.AI folder") }
            OutlinedButton(d::check, enabled = d.busy.value == null) { Text("Check connection") }
            OutlinedButton({ d.refresh() }, enabled = d.busy.value == null) { Text("List files") }
            OutlinedButton({ d.switchAccount() }, enabled = d.busy.value == null) { Text("Switch account") }
            OutlinedButton({ d.connect { d.check() } }, enabled = d.busy.value == null) { Text("Reconnect") }
            TextButton({ d.disconnect() }) { Text("Disconnect") }
            TextButton({ guide = true }) { Text("Setup guide & troubleshooting") }
        }
        d.waitingForOtherAccounts().takeIf { it > 0 }?.let { Text("$it upload(s) are waiting for another Google account and will go there only when you switch back.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn) }
    }
    if (guide) DriveGuideDialog { guide = false }
}

@Composable
private fun Prereqs(vm: AppViewModel) {
    val ctx = LocalContext.current
    val play = remember { DriveAuth.unavailable(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Before you start", style = MaterialTheme.typography.labelLarge)
        Text((if (vm.online.value) "✓ " else "✗ ") + "Internet " + if (vm.online.value) "available" else "— connect to Wi-Fi or mobile data",
            style = MaterialTheme.typography.bodySmall, color = if (vm.online.value) LocalExtra.current.ok else LocalExtra.current.warn)
        Text((if (play == null) "✓ Google Play services available" else "✗ $play"), style = MaterialTheme.typography.bodySmall,
            color = if (play == null) LocalExtra.current.ok else LocalExtra.current.warn)
        Text("• A Google account on this phone (you can add one in the account picker)", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
}

/** v4.2: the step-by-step guide and troubleshooting, for non-technical users. */
@Composable
internal fun DriveGuideDialog(onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Google Drive: how it works") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connect", fontWeight = FontWeight.SemiBold)
            listOf("Open Abhify.AI → More → Google Drive.", "Tap Connect Google Drive.", "Choose your Google account (or add one).",
                "Review the permission: Abhify can see and change only files it creates or that you choose — not the rest of your Drive.",
                "Tap Allow.", "You're back in Abhify: the status shows Connected.", "Tap \"Open Abhify.AI folder\" to see your files in My Drive → Abhify.AI.")
                .forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            Text("Your folder", fontWeight = FontWeight.SemiBold)
            Text("Everything Abhify puts on Drive is in My Drive → Abhify.AI: Backups (app and memory backups), Documents (library documents' text), " +
                "Imports (files you choose to copy to Drive), Exports (conversations and notes).", style = MaterialTheme.typography.bodySmall)
            Text("No Google account on the phone?", fontWeight = FontWeight.SemiBold)
            Text("Android Settings → Passwords & accounts (or Accounts) → Add account → Google. Abhify never asks for your Google password.", style = MaterialTheme.typography.bodySmall)
            Text("Troubleshooting", fontWeight = FontWeight.SemiBold)
            listOf("No internet: connect, then tap Connect or Retry.",
                "Google Play services missing or outdated: update them in the Play Store.",
                "\"Access wasn't granted\": you chose Cancel or Deny; tap Connect again and choose Allow.",
                "\"Sign-in expired or was revoked\": tap Reconnect.",
                "\"Isn't set up for this app build\": a developer configuration issue with this version of the app (not your phone). Use the app without Drive meanwhile.",
                "\"Access blocked\" / app not verified: while Abhify's Google project is in testing, only accounts added as test users can connect.",
                "Work or school accounts may block third-party apps: try a personal account.",
                "Folder not found: tap Recreate folder (nothing else is changed).")
                .forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text("Drive is optional. Disconnecting never deletes files on Drive or on this phone.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        } }, confirmButton = { TextButton(onClose) { Text("Close") } })
}

/** v4.2: optional, confirmed move of files from the 3.4–4.1 sub-folders into Backups / Documents / Exports. */
@Composable
internal fun DriveMigrationCard(vm: AppViewModel) {
    val d = vm.drive
    if (!d.connected.value) return
    val picked = remember(d.migration.size) { mutableStateListOf<DriveMigration.Move>().apply { addAll(d.migration) } }
    var confirm by remember { mutableStateOf(false) }
    SettingsGroup("Organize older Abhify files (optional)") {
        Text("Earlier versions used other sub-folders (Application Backups, Memory Backups, Learning Library, Archived Conversations, Exported Notes). " +
            "New files already go to Backups / Documents / Imports / Exports. Moving older files is up to you.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        if (d.migration.isEmpty()) OutlinedButton(d::scanLegacy, enabled = d.busy.value == null) { Text("Find older files") }
        d.migration.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(m in picked, { on -> if (on) picked += m else picked -= m })
            Column { Text(m.item.name, style = MaterialTheme.typography.bodyMedium); Text("Abhify.AI/${m.fromFolder} → Abhify.AI/${m.toFolder}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
        } }
        if (d.migration.isNotEmpty()) Row { Button({ confirm = true }, enabled = picked.isNotEmpty() && d.busy.value == null) { Text("Move ${picked.size} selected…") }
            TextButton({ d.migration.clear() }) { Text("Not now") } }
        d.migrationResult.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Move ${picked.size} file(s)?") },
        text = { DialogText("They move inside My Drive → Abhify.AI to their new sub-folders. Their contents and Drive links stay the same; nothing is deleted or overwritten. Files that can't be moved stay where they are.") },
        confirmButton = { TextButton({ d.migrate(picked.toList()); confirm = false }) { Text("Move") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/**
 * v4.2 Settings → Connection mode: three independent parts — the app on this phone (always works), Google Drive
 * (your own Google account) and an optional Abhify server. None of them depends on another.
 */
@Composable
fun ConnectionScreen(vm: AppViewModel) {
    val mode = vm.serverMode()
    var url by remember { mutableStateOf(vm.g.serverUrl) }
    var editing by remember { mutableStateOf(false) }
    Screen(vm, "Connection mode") {
        SettingsGroup("Local application") {
            Text("Connection mode: ${if (mode == ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE) "Local Mode" else mode.label}", fontWeight = FontWeight.SemiBold)
            Text(if (mode == ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE) "Abhify.AI is running locally. No Abhify server is connected."
                else "Local features keep working whatever the server does.", style = MaterialTheme.typography.bodySmall)
            Text("AI on this phone: ${vm.activeModel()?.name ?: "no model downloaded yet"} · chats, memory, documents, study and career are stored on this phone.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Row { TextButton({ vm.go(AppViewModel.Route.MODELS) }) { Text("Models") }; TextButton({ vm.go(AppViewModel.Route.STORAGE) }) { Text("Storage") } }
        }
        Glass(onClick = { vm.go(AppViewModel.Route.DRIVE) }) {
            Text("Google Drive", style = MaterialTheme.typography.titleMedium)
            Text(if (vm.drive.connected.value) "Connected · ${vm.drive.account.value?.label ?: "your Google account"}" else "Not connected (optional)",
                color = if (vm.drive.connected.value) LocalExtra.current.ok else MaterialTheme.colorScheme.onSurface)
            Text("Uses your own Google account; independent of any Abhify server. Tap to connect, switch or disconnect.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
        SettingsGroup("Abhify server (optional)") {
            Text(when (mode) { ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE -> "Not connected"; else -> mode.label },
                color = if (mode == ai.abhify.android.cloud.ServerConnection.Mode.SERVER_DISCONNECTED) LocalExtra.current.warn else MaterialTheme.colorScheme.onSurface)
            if (vm.g.serverUrl.isNotBlank()) Text("Address: ${vm.g.serverUrl}" + if (!vm.serverEnabled.value) " (not in use)" else "", style = MaterialTheme.typography.bodySmall)
            vm.serverCheck.value?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = if (it.ok) LocalExtra.current.ok else LocalExtra.current.warn) }
            if (vm.serverChecking.value) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (mode == ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE && !editing) {
                Text("Connect to an Abhify server to access server-based features when available.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                Button({ editing = true }) { Text("Connect to Abhify Server") }
            }
            if (editing || mode != ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE) {
                OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server URL (https://…)") })
                Text("Real servers use https://. Developer builds also accept http://10.0.2.2:PORT (emulator → PC) and http://127.0.0.1:PORT (this phone, e.g. with adb reverse). " +
                    "On a phone, 127.0.0.1 is the phone itself, not your PC.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ vm.verifyServer(url, save = true) }, enabled = !vm.serverChecking.value) { Text("Connect") }
                    OutlinedButton({ vm.verifyServer(url, save = false) }, enabled = !vm.serverChecking.value) { Text("Verify server") }
                    if (vm.serverCheck.value?.ok == false && vm.g.serverUrl.isNotBlank()) OutlinedButton({ vm.verifyServer(vm.g.serverUrl, save = false) }) { Text("Retry") }
                    if (mode != ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE) TextButton({ vm.disconnectServer(); editing = false }) { Text("Disconnect server") }
                    if (vm.g.serverUrl.isNotBlank()) TextButton({ vm.removeServerAddress(); url = ""; editing = false }) { Text("Remove address") }
                    TextButton({ editing = false; vm.serverCheck.value = null }) { Text(if (mode == ai.abhify.android.cloud.ServerConnection.Mode.LOCAL_MODE) "Cancel" else "Continue in Local Mode") }
                }
            }
            if (mode == ai.abhify.android.cloud.ServerConnection.Mode.SERVER_CONNECTED || mode == ai.abhify.android.cloud.ServerConnection.Mode.SERVER_CONFIGURED)
                Row { TextButton({ vm.go(AppViewModel.Route.LOGIN) }) { Text("Sign in") }; TextButton({ vm.go(AppViewModel.Route.REGISTER) }) { Text("Create account") } }
            if (mode == ai.abhify.android.cloud.ServerConnection.Mode.SERVER_AUTHENTICATED) TextButton({ vm.go(AppViewModel.Route.ACCOUNT) }) { Text("Account") }
            Text("Signing in to an Abhify server is separate from Google Drive: Google sign-in never creates or signs in to an Abhify account.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
    }
}
