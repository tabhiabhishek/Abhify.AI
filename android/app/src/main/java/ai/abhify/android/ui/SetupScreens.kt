package ai.abhify.android.ui

import ai.abhify.android.ai.AiMode
import ai.abhify.android.device.MemoryPolicy
import ai.abhify.android.device.MemoryProfile
import ai.abhify.android.device.SetupPolicy
import ai.abhify.android.device.SetupPolicy.Step
import ai.abhify.android.model.ModelSelector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * First-run setup (also reachable later from Settings → Run setup again). Every step can be skipped; nothing is
 * downloaded or enabled without the user choosing it; the real configuration screens (AI models, AI providers)
 * are the same ones used later in Settings.
 */
@Composable
fun SetupScreen(vm: AppViewModel) {
    val steps = SetupPolicy.steps(vm.aiMode.value)
    val i = vm.setupStep.intValue.coerceIn(0, steps.lastIndex)
    val step = steps[i]
    LaunchedEffect(step) { vm.refreshDevice() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(32); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Setup · step ${i + 1} of ${steps.size}", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                Text(step.title, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(vm::completeSetup) { Text("Skip setup") }
        }
        // 4.5.1 stepper: one segment per step (done = brand, current = brand, upcoming = hairline), read as "step x of y"
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = Dimens.tiny)
            .semantics(mergeDescendants = true) { contentDescription = "Step ${i + 1} of ${steps.size}: ${step.title}" },
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.indices.forEach { k -> Box(Modifier.weight(1f).height(4.dp).background(if (k <= i) Brand.Violet else LocalExtra.current.line, RoundedCornerShape(2.dp))) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
          Column(Modifier.widthIn(max = Dimens.maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                Step.DEVICE -> DeviceStep(vm)
                Step.AI_MODE -> ModeStep(vm)
                Step.LOCAL -> LocalStep(vm)
                Step.ONLINE -> OnlineStep(vm)
                Step.OCR -> OcrStep(vm)
                Step.DRIVE -> DriveStep(vm)
                Step.PRIVACY -> PrivacyStep(vm)
                Step.FINISH -> FinishStep(vm)
            }
          }
        }
        Row(Modifier.fillMaxWidth().background(LocalExtra.current.glassStrong).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (i > 0) OutlinedButton({ vm.setupStep.intValue = i - 1 }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Back") }
            if (step == Step.FINISH) GradientButton("Finish", vm::completeSetup, Modifier.weight(1f))
            else GradientButton("Next", { vm.setupStep.intValue = i + 1 }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)

@Composable
private fun DeviceStep(vm: AppViewModel) {
    val d = vm.device.value
    val mem = remember(d) { vm.g.memorySnapshot() }
    val lines = remember(d) {
        SetupPolicy.deviceReport(d.release, d.sdkInt, d.totalRamMb, mem, d.freeStorageMb, d.abis, ModelSelector.recommend(vm.g.catalog, d),
            vm.activeModel()?.name, vm.online.value, ocrBundled = true, device = d, executionMode = ModelSelector.executionMode(d, false))
    }
    SettingsGroup("Checked on this phone just now") {
        lines.forEach { l ->
            Row(verticalAlignment = Alignment.Top) {
                Text(when (l.ok) { true -> "✓"; false -> "⚠"; null -> "•" }, Modifier.width(24.dp), color = when (l.ok) { true -> LocalExtra.current.ok; false -> LocalExtra.current.warn; null -> LocalExtra.current.muted })
                Text(l.label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(l.value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Note("Nothing is downloaded automatically. Text recognition and the phone's AI run one at a time, so they never need memory together." +
        if (d.totalRamMb in 6000..9000) " On an 8 GB phone that matters: usually 2–4 GB are actually free." else "")
}

@Composable
private fun ModeStep(vm: AppViewModel) {
    val modes = listOf(
        Triple(AiMode.DEVICE, "On this phone only", "Private and offline. Needs a one-time model download (about 1 GB). No account or API key."),
        Triple(AiMode.ONLINE, "Online with my own key", "Gemini, Groq or OpenRouter with a free key you create. Needs internet. No Abhify account."),
        Triple(AiMode.AUTO, "Auto", "Your online provider when available; the phone's AI if you allow it as fallback."),
    )
    modes.forEach { (m, title, sub) ->
        Glass(onClick = { vm.setAiMode(m); if (m != AiMode.DEVICE && !vm.onlineAllowed.value) vm.setOnlineAllowed(true) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.aiMode.value == m, null); Spacer(Modifier.width(8.dp))
                Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted) }
            }
        }
    }
    Note("Choosing Online or Auto turns on \"Allow online AI\" (Privacy). You can change all of this later in Settings.")
}

@Composable
private fun LocalStep(vm: AppViewModel) {
    val active = vm.activeModel()
    val rec = vm.recommended()
    Glass {
        Text(if (active != null) "Installed: ${active.name}" else "No model on this phone yet", style = MaterialTheme.typography.titleMedium)
        Note(if (active != null) "It stays as it is. You can run its self-test or choose another model in AI models."
            else rec?.let { "Recommended for this phone: ${it.name}, about ${"%.1f".format(it.sizeBytes / 1e9)} GB. The download asks for confirmation, can be cancelled and is verified before use." }
                ?: "No model in the catalog fits this phone's memory or storage. You can still use online AI.")
        GradientButton(if (active != null) "Open AI models" else "Choose and download a model", { vm.go(AppViewModel.Route.MODELS) }, Modifier.fillMaxWidth())
    }
    Note("You can skip this and download later in More → AI models on this phone.")
}

@Composable
private fun OnlineStep(vm: AppViewModel) {
    val st = vm.states()
    Glass {
        Text(st.provider, style = MaterialTheme.typography.titleMedium)
        Note("Each phone uses its own keys: they are typed on this phone, encrypted with its Android Keystore, never synced, exported or shared " +
            "with another phone or the Abhify server. The model list comes from the provider for your key.")
        GradientButton("Open AI providers", { vm.go(AppViewModel.Route.PROVIDERS) }, Modifier.fillMaxWidth())
    }
    Note("Optional. Without a key, the phone's AI (if downloaded) answers everything.")
}

@Composable
private fun OcrStep(vm: AppViewModel) {
    SettingsGroup("Text recognition: Google ML Kit (included)") {
        Note("Reads photos, screenshots and scanned PDF pages on this phone; no model download. Your images and their text are not sent anywhere. " +
            "Google's ML Kit SDK does send anonymous usage and performance statistics to Google. PDFs with a text layer and DOCX files never need it.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Pair<Boolean?, String>>(null to "Ask each time", true to "Allow", false to "Off").forEach { (v, l) ->
                FilterChip(vm.ocrAllowed.value == v, { vm.setOcrAllowed(v) }, { Text(l) }) }
        }
        OutlinedButton(vm::ocrSelfTest) { Text("Run OCR self-test") }
        vm.ocrTest.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** v4.2: optional Google Drive during setup (skipping is fine; More → Google Drive later). No Abhify server is involved. */
@Composable
private fun DriveStep(vm: AppViewModel) {
    DriveAuthHost(vm)
    val d = vm.drive
    Glass {
        Text("Connect your own Google account to keep Abhify.AI backups and files in My Drive → Abhify.AI. Optional: everything works without it.",
            style = MaterialTheme.typography.bodyMedium)
        if (d.connected.value) Text("✓ Connected as ${d.account.value?.label ?: "your Google account"}", color = LocalExtra.current.ok)
        else Button({ d.connect() }, enabled = d.busy.value == null) { Text("Connect Google Drive") }
        d.busy.value?.let { Note(it) }
        d.message.value?.let { Note(it) }
        Note("Not now? Tap Next. You can connect any time in More → Google Drive.")
    }
}

@Composable
private fun PrivacyStep(vm: AppViewModel) {
    Glass {
        SwitchRow("Allow online AI", "Off = nothing is sent to any provider; the phone's AI answers.", vm.onlineAllowed.value) { vm.setOnlineAllowed(it) }
        SwitchRow("Share memories with online AI", "Off by default.", vm.shareMemories.value, enabled = vm.onlineAllowed.value) { vm.setShareMemories(it) }
        Note("Documents, resumes and notes are never sent online without a confirmation that names the provider (this can't be turned off). Model downloads always ask first.")
    }
    MemoryProfileCard(vm)
}

@Composable
fun MemoryProfileCard(vm: AppViewModel) {
    val (now, why) = remember(vm.memoryChoice.value) { vm.g.memoryProfile() }
    val snap = remember(vm.memoryChoice.value) { vm.g.memorySnapshot() }
    SettingsGroup("Processing profile (documents and OCR)") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<MemoryProfile?>(null) + MemoryProfile.entries).forEach { p -> FilterChip(vm.memoryChoice.value == p, { vm.setMemoryChoice(p) }, { Text(p?.label ?: "Auto") }) }
        }
        Text("In use now: ${now.label} · OCR images up to ${now.ocrMaxSide}px · up to ${now.maxOcrPages} scanned pages per document · " +
            "${now.renderDpi} dpi PDF pages · ${"%,d".format(now.localDocChars)} characters of a document for the phone's AI",
            style = MaterialTheme.typography.bodyMedium)
        why?.let { Text(it, color = LocalExtra.current.warn, style = MaterialTheme.typography.bodyMedium) }
        Note("Memory now: ${"%,d".format(snap.availMb)} MB free of ${"%,d".format(snap.totalMb)} MB · app heap limit ${snap.heapClassMb} MB" +
            (if (snap.lowMemory) " · Android reports low memory" else "") + ". Quality needs ${MemoryPolicy.QUALITY_AVAIL_MB} MB free.")
    }
}

@Composable
private fun FinishStep(vm: AppViewModel) {
    val st = vm.states()
    SettingsGroup("Your setup") {
        listOf("AI mode" to st.mode, "Model on this phone" to st.local, "Online provider" to st.provider,
            "Online AI" to (if (vm.onlineAllowed.value) "allowed" else "off"),
            "Text recognition" to when (vm.ocrAllowed.value) { true -> "allowed (ML Kit)"; false -> "off"; null -> "asks each time" },
            "Processing profile" to (vm.memoryChoice.value?.label ?: "Auto")).forEach { (k, v) ->
            Row { Text(k, Modifier.weight(0.45f), fontWeight = FontWeight.SemiBold); Text(v, Modifier.weight(0.55f)) }
        }
    }
    Text(SetupPolicy.readiness(vm.aiMode.value, vm.activeModel() != null, vm.states().providerLevel == 0, vm.onlineAllowed.value),
        style = MaterialTheme.typography.bodyLarge)
    Note("Everything here stays changeable in Settings. Finishing doesn't download or send anything.")
}
