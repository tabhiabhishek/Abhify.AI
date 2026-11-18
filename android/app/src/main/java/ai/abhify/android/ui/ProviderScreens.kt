package ai.abhify.android.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ai.abhify.android.cloud.Account
import ai.abhify.android.providers.Provider
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun ProvidersScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadProviders() }
    var reset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTitle("AI providers", { vm.back() })
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroup("Hosted AI with your own keys") {
                    Text("Add a free API key from Gemini, Groq or OpenRouter to get stronger answers over your phone's internet. " +
                        "Keys are encrypted with this phone's Android Keystore, stay on this phone only, and are never synced, backed up, " +
                        "exported or sent to the Abhify server. Each new phone asks for its own keys.",
                        style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
                    if (!vm.onlineAllowed.value) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠ Online AI is off in Privacy. Keys can be saved, but nothing is sent until you allow it.",
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn, modifier = Modifier.weight(1f))
                        TextButton({ vm.go(AppViewModel.Route.PRIVACY) }) { Text("Privacy") }
                    }
                }
                vm.providerConfigs.forEach { c -> ProviderCard(vm, c.provider) }
                RoutingCard(vm)
                if (vm.account.value is Account.SignedIn) SharedCard(vm)
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                OutlinedButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(vm.providerDiagnostics())) }) { Text("Copy diagnostics (no keys)") }
                TextButton({ reset = true }) { Text("Reset all provider settings on this phone", color = LocalExtra.current.bad) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset providers?") },
        text = { DialogText("Removes every saved API key and provider setting from this phone. Your chats are not affected.") },
        confirmButton = { Button({ reset = false; vm.resetProviders() }) { Text("Reset") } }, dismissButton = { TextButton({ reset = false }) { Text("Cancel") } })
}

@Composable
private fun ProviderCard(vm: AppViewModel, p: Provider) {
    val c = vm.providerConfigs.first { it.provider == p }
    val ctx = LocalContext.current
    val x = LocalExtra.current
    var editing by remember(c.configured) { mutableStateOf(!c.configured && false) }
    var key by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmCharged by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    val busy = vm.providerBusy.value == p
    val models = vm.providerModels.value[p].orEmpty()
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                !c.configured -> Pill("not configured", x.muted)
                vm.usable(p) -> Pill(if (c.enabled) "ready" else "off", if (c.enabled) x.ok else x.muted)
                else -> Pill("needs attention", x.warn)
            }
        }
        if (c.configured) {
            Text("Key ${c.masked ?: "saved"}", style = MaterialTheme.typography.bodyMedium)
            SwitchRow("Use ${p.title}", null, c.enabled) { vm.setProviderEnabled(p, it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.model, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { pickModel = true }.padding(vertical = 6.dp))
                    DropdownMenu(pickModel, { pickModel = false }) {
                        models.filter { it.free || c.allowCharged }.take(60).forEach { m ->
                            DropdownMenuItem(text = { Column { Text(m.id); Text(m.note, style = MaterialTheme.typography.bodySmall, color = if (m.free) x.ok else x.warn) } },
                                onClick = { pickModel = false; vm.setProviderModel(p, m.id) })
                        }
                        if (models.isEmpty()) DropdownMenuItem(text = { Text("Tap Refresh models first") }, onClick = { pickModel = false })
                    }
                }
                TextButton({ pickModel = true }) { Text("Change") }
            }
            val selected = vm.onlineTargetSetting.value == p.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) Pill("✓ selected for Online", x.ok) else OutlinedButton({ vm.setOnlineTarget(p.id) }, enabled = vm.usable(p)) { Text("Use for Online") }
                Spacer(Modifier.width(8.dp))
                vm.g.providerStore.problem(p)?.let { Text("Not ready: $it", style = MaterialTheme.typography.bodySmall, color = x.warn, modifier = Modifier.weight(1f)) }
            }
            if (p.needsFreeTierConfirmation) Text("Billing: " + p.costNote + " No API can prove a key is free.", style = MaterialTheme.typography.bodySmall, color = x.muted)
            SwitchRow("Allow models that may be charged", "Off: only free models can be chosen or used.", c.allowCharged) { on -> if (on) confirmCharged = true else vm.setAllowCharged(p, false) }
            Text("Answer length limit: ${c.maxTokens} tokens" + if (c.maxTokens == p.defaultMaxTokens) " (default)" else "", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1024, 2048, 4096, 8192, 16384).forEach { n -> androidx.compose.material3.FilterChip(c.maxTokens == n, { vm.setMaxTokens(p, n) }, { Text(if (n >= 1024) "${n / 1024}k" else "$n") }) }
            }
            Text("Reasoning models use part of this for hidden thinking. Answers that hit it are marked partial and can be continued." +
                if (p == Provider.GROQ) " Groq counts it against your per-minute token limit, so very high values can be refused." else "",
                style = MaterialTheme.typography.bodySmall, color = x.muted)
            Text(c.usage ?: "Usage information unavailable", style = MaterialTheme.typography.bodySmall, color = x.muted)
            c.lastOkAt?.let { Text("Last successful connection: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)), style = MaterialTheme.typography.bodySmall, color = x.muted) }
            c.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = x.bad) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ vm.refreshProvider(p) }, enabled = !busy) { Text(if (busy) "Checking…" else "Test & refresh models") }
                TextButton({ editing = true }) { Text("Replace key") }
                TextButton({ confirmRemove = true }) { Text("Remove", color = x.bad) }
            }
        } else Text("Not configured on this phone.", style = MaterialTheme.typography.bodyMedium, color = x.muted)

        if (editing || !c.configured) {
            Text(p.costNote, style = MaterialTheme.typography.bodySmall, color = x.warn)
            Text("Privacy: " + p.privacyNote, style = MaterialTheme.typography.bodySmall, color = x.muted)
            TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.getKeyUrl))) }) { Text("Get a free ${p.title} key ↗") }
            Field(key, { key = it.trim() }, "API key (${p.keyHint})", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(if (busy) "Checking…" else "Validate & save", { vm.validateAndSave(p, key); key = ""; editing = false },
                    Modifier.weight(1f), enabled = key.length >= 16, busy = busy)
                if (c.configured) TextButton({ editing = false; key = "" }) { Text("Cancel") }
            }
        }
        vm.providerResult.value?.takeIf { it.first == p }?.let { Text(it.second, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove the ${p.title} key?") },
        text = { DialogText("The key is deleted from this phone. To use ${p.title} again you'll need to enter a key.") },
        confirmButton = { Button({ confirmRemove = false; vm.removeKey(p) }) { Text("Remove") } }, dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } })
    if (confirmCharged) AlertDialog(onDismissRequest = { confirmCharged = false }, title = { Text("Allow models that may cost money?") },
        text = { DialogText("Models outside the free tier can be charged to your ${p.title} account. Only turn this on if you intend to pay for usage.") },
        confirmButton = { Button({ confirmCharged = false; vm.setAllowCharged(p, true) }) { Text("Allow") } }, dismissButton = { TextButton({ confirmCharged = false }) { Text("Keep free only") } })
}

@Composable
private fun RoutingCard(vm: AppViewModel) {
    val x = LocalExtra.current
    SettingsGroup("Order and fallback") {
        Text("Auto uses the first ready provider in this order. Other providers are tried only if you allow it below, and only before an answer starts.",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
        vm.priority.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${p.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (vm.usable(p)) FontWeight.SemiBold else FontWeight.Normal)
                if (!vm.usable(p)) Text("not ready  ", style = MaterialTheme.typography.bodySmall, color = x.muted)
                TextButton({ vm.move(p, true) }, Modifier.semantics { contentDescription = "Move ${p.title} up" }, enabled = i > 0) { Text("↑") }
                TextButton({ vm.move(p, false) }, Modifier.semantics { contentDescription = "Move ${p.title} down" }, enabled = i < vm.priority.lastIndex) { Text("↓") }
            }
        }
        // 5.6: measured reliability and speed this session (counts and timings only; nothing about what was asked)
        val measured = ai.abhify.android.ai.RouteStats.lines()
        Text("Measured this session", style = MaterialTheme.typography.labelLarge)
        if (measured.isEmpty()) Text("No answers yet in this session.", style = MaterialTheme.typography.bodySmall, color = x.muted)
        else measured.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        SwitchRow("Try other providers in Auto", "Off by default: your text only goes to the first provider. On: if it fails before answering, the next ready provider is tried (shown in the chat).",
            vm.fallbackProviders.value) { vm.setFallbackProviders(it) }
        SwitchRow("Fall back to this phone's AI", "When online AI isn't available in Auto mode.", vm.fallbackToDevice.value) { vm.setFallbackToDevice(it) }
        Text("Online mode uses exactly one:", style = MaterialTheme.typography.labelLarge)
        val options = Provider.entries.map<Provider, Pair<String?, String>> { it.id to it.title } +
            (if (vm.account.value is Account.SignedIn) listOf("shared" to "Shared access (Abhify server)") else emptyList())
        options.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { vm.setOnlineTarget(id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.onlineTargetSetting.value == id, { vm.setOnlineTarget(id) }); Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SharedCard(vm: AppViewModel) {
    val s = vm.shared.value
    SettingsGroup("Shared access (your Abhify server)") {
        when {
            s == null -> Text("Your server didn't answer (it may not be deployed yet). Shared access is unavailable.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            !s.optBoolean("shared_enabled") -> Text("Your administrator hasn't enabled shared provider access.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            else -> {
                val list = s.optJSONArray("providers")
                Text("Provided by your administrator. Their keys stay on the server; this phone never sees them. Limit: ${s.optInt("daily_limit")} requests per day.",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.forEach { p ->
                    Text("• ${p.optString("title")} · ${p.optString("model")}" + if (p.optBoolean("available")) "" else " (unavailable)", style = MaterialTheme.typography.bodyMedium)
                }
                s.optJSONObject("usage")?.let { Text("Used today: ${it.optInt("used")} of ${it.optInt("limit")}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
