package ai.abhify.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import ai.abhify.android.chat.ChatDb
import ai.abhify.android.voice.Voice
import ai.abhify.android.voice.VoiceSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 5.4 voice conversation panel, shown over the app while a conversation is open (so "open Study" visibly opens Study
 * underneath). The core shows the real state; the waveform moves only with the microphone's real level while listening.
 */
@Composable
fun BoxScope.VoiceOverlay(vm: AppViewModel) {
    val v = vm.voiceState
    if (!v.active.value) return
    val x = LocalExtra.current
    val s = vm.voiceSettings.value
    val state = orbStateOf(vm)
    // 5.6: the conversation ends when the app goes to the background (no microphone or speech behind the user's back)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) v.stop() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val answering = v.awaitingAnswer && vm.generating.value
    androidx.activity.compose.BackHandler { v.stop() }                  // 6.1: system Back ends the conversation instead of navigating underneath
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(x.glassStrong)
        .pointerInput(Unit) { detectTapGestures { } }                      // 6.1: taps on the panel never fall through to the app underneath
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = if (vm.speaking.value || answering) "Interrupt and speak" else if (vm.listening.value) "Stop listening" else "Speak") { v.tapCore() }) {
            AiOrb(state, 88)
        }
        Text(state.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (vm.listening.value) Waveform(v.level.floatValue)
        // 5.6: what was heard (the user's last turn) stays visible above the answer
        vm.messages.lastOrNull { it.role == "user" }?.takeIf { !vm.listening.value && v.actionReply.value == null }?.let {
            Text("You: ${it.text.take(120)}", style = MaterialTheme.typography.labelMedium, color = x.muted, maxLines = 2) }
        val last = vm.messages.lastOrNull()
        val text = when {
            vm.listening.value -> vm.heard.value.ifBlank { "Listening… say something, or tap the core to stop." }
            v.actionReply.value != null -> v.actionReply.value
            last != null && last.role == "assistant" && s.responseMode != VoiceSettings.ResponseMode.VOICE_ONLY -> last.text.ifBlank { "…" }
            else -> null
        }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) }
        vm.voiceError.value?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = x.warn)
            if (!vm.listening.value) TextButton({ vm.voiceError.value = null; v.listen() }) { Text("Try again") } }   // 7.1.1: retry without restarting
        v.pending.value?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ v.confirm() }) { Text("Confirm") }; OutlinedButton({ v.cancel() }) { Text("Cancel") } }
        }
        v.undo.value?.let { (label, run) -> TextButton(run) { Text(label) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ v.stop() }, border = BorderStroke(1.dp, x.bad)) { Text("■ End", color = x.bad) }
            if (vm.speaking.value) OutlinedButton({ vm.pauseSpeaking() }) { Text("Pause") }
            else if (vm.speechPaused.value) OutlinedButton({ vm.resumeSpeaking() }) { Text("Resume") }
            Button({ v.tapCore() }) { Text(if (vm.speaking.value || answering) "Interrupt" else if (vm.listening.value) "Stop listening" else "Speak") }
        }
        Text(if (vm.routeExplanation().route.contains("this phone")) "Answers: this phone" else vm.routeExplanation().route,
            style = MaterialTheme.typography.labelSmall, color = x.muted, maxLines = 1)
    }
}

/** Bars that follow the microphone's real level (never animated without sound). */
@Composable
private fun Waveform(level: Float) {
    val bars = remember { mutableStateListOf(*Array(16) { 0f }) }
    LaunchedEffect(level) { bars.removeAt(0); bars.add(level) }
    Row(Modifier.height(28.dp).semantics { contentDescription = "Microphone level" }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { b -> Box(Modifier.width(4.dp).height((4 + 24 * b).dp).clip(RoundedCornerShape(2.dp)).background(Brand.Cyan)) }
    }
}

/** Settings → Voice & Conversation: the six voice characteristics and the conversation options (all saved at once). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceSettingsGroup(vm: AppViewModel, withMic: (() -> Unit) -> Unit = { it() }) {
    val s = vm.voiceSettings.value
    val x = LocalExtra.current
    fun set(n: VoiceSettings) = vm.setVoiceSettings(n)
    var readAloud by remember { mutableStateOf(vm.g.readAloud) }
    var voices by remember { mutableStateOf<List<Voice.VoiceInfo>?>(null) }
    val previewLang = if (s.language == VoiceSettings.Language.HINDI) "hi" else "en"
    LaunchedEffect(previewLang) { vm.voice.voicesFor(previewLang) { list -> voices = list } }
    SettingsGroup("Voice & Conversation", accent = Accent.CHAT) {
        SwitchRow("Voice assistant", "Talk to Abhify with the 🗣 Talk button in the Assistant. Tap-to-talk; the microphone is used only while the listening indicator shows.",
            s.enabled) { set(s.copy(enabled = it)) }
        // 7.1.0 (owner): one friendly, soft voice. Other voices are offered only when this phone really has more than one.
        Text("Voice", style = MaterialTheme.typography.labelLarge)
        val list = voices
        if (list == null) Text("Checking this phone's speech voices…", style = MaterialTheme.typography.bodySmall, color = x.muted)
        else if (list.isEmpty()) Text("No voice for this language is installed. Add one in Android Settings → Text-to-speech; answers are shown as text meanwhile.",
            style = MaterialTheme.typography.bodySmall, color = x.warn)
        else Text("A soft, friendly voice at a calm pace" + if (s.assistantVoice != null) " (your chosen voice)." else ".",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
        var choosing by remember { mutableStateOf(false) }
        if ((list?.size ?: 0) > 1) TextButton({ choosing = !choosing }) { Text(if (choosing) "Done" else "Try another voice (${list!!.size} on this phone)") }
        if (choosing && list != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val sample = "Hi! I'm ${vm.assistantName.value}. It's lovely to talk with you."
            FilterChip(s.assistantVoice == null, { set(s.copy(style = VoiceSettings.Style.ASSISTANT).withPicked(VoiceSettings.Style.ASSISTANT, null)) }, { Text("Automatic") })
            list.take(24).forEachIndexed { i, vi ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(s.assistantVoice == vi.name, { set(s.copy(style = VoiceSettings.Style.ASSISTANT).withPicked(VoiceSettings.Style.ASSISTANT, vi.name)) },
                        { Text("Voice ${i + 1}  (${vi.languageTag}${if (vi.needsNetwork) " · online" else ""})") }, modifier = Modifier.weight(1f))
                    TextButton({ vm.voice.preview(sample, VoiceSettings.Style.ASSISTANT, s.rate, vi.name) }) { Text("▶") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ vm.voice.preview(if (previewLang == "hi") "नमस्ते, मैं ${vm.assistantName.value} हूँ। मैं आपकी कैसे मदद कर सकती हूँ?"
                else "Hi! I'm ${vm.assistantName.value}. It's lovely to talk with you. What shall we do today?", VoiceSettings.Style.ASSISTANT, s.rate) }, enabled = list?.isNotEmpty() == true) { Text("▶ Preview") }
            TextButton({ vm.voice.stopSpeaking() }) { Text("■ Stop preview") }
            TextButton({ set(s.copy(style = VoiceSettings.Style.ASSISTANT, speed = VoiceSettings.Speed.NATURAL, voiceName = null, maleVoice = null, femaleVoice = null, assistantVoice = null)) }) { Text("Restore defaults") }
        }

        var engineLine by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(previewLang) { vm.voice.engineInfo(previewLang) { engineLine = it } }
        engineLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        Row { TextButton({ withMic { vm.testMic() } }) { Text("🎙 Test microphone") } }
        vm.micTest.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // 7.1.1: call the assistant by its name (foreground only, on-device recognition only, off by default)
        Text("Wake word (optional, off by default)", style = MaterialTheme.typography.labelLarge)
        val wakeWhy = vm.wakeSupport()
        SwitchRow("Listen for \"${vm.assistantName.value}\" while Abhify is open",
            wakeWhy ?: ("Say \"${vm.assistantName.value}\" or \"Hey ${vm.assistantName.value}\" to start talking. Only while Abhify is open on screen; " +
                "stops when you leave the app. Speech is recognised on this phone (nothing is uploaded), and a pill at the top shows when the microphone listens. Uses more battery."),
            s.wakeWord && wakeWhy == null) { on -> if (wakeWhy == null) { if (on) withMic { vm.setWake(true) } else vm.setWake(false) } }
        vm.wakeNote.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.warn) }
        // 7.1.0: offline wake word engine (Vosk), installed only on request
        LaunchedEffect(Unit) { vm.refreshVosk() }
        val prog = vm.voskProgress.value
        when {
            prog != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloading the offline wake word… ${prog.first / 1_048_576} of ${prog.second / 1_048_576} MB", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ vm.cancelVoskInstall() }) { Text("Cancel") } }
            vm.voskInstalled.value -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Offline wake word installed (Vosk, English). Works best with a name that is a common English word or name.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = x.muted)
                TextButton({ withMic { vm.testWake() } }) { Text("Test") }
                TextButton({ vm.removeVosk() }) { Text("Remove") } }
            else -> Column {
                Text("Offline wake word for any phone: the Vosk speech engine with its English model (about 41 MB, downloaded once from alphacephei.com, " +
                    "checked before it is installed). It runs on this phone; no audio is uploaded.", style = MaterialTheme.typography.bodySmall, color = x.muted)
                TextButton({ vm.installVosk() }) { Text("Install offline wake word (41 MB)") } }
        }
        vm.wakeTest.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Not available: listening in the background or with the screen off (Android would need a permanent microphone notification and a new permission).",
            style = MaterialTheme.typography.bodySmall, color = x.muted)

        // 7.1.1: the last crash, if there was one (stored only on this phone; for troubleshooting)
        val crash = remember { ai.abhify.android.device.CrashNote.last(vm.g.context.filesDir) }
        if (crash != null) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Text("Last crash report (only on this phone)", style = MaterialTheme.typography.labelLarge)
            Text(crash.lineSequence().take(3).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 3)
            Row { TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(crash)) }) { Text("Copy report") }
                TextButton({ ai.abhify.android.device.CrashNote.clear(vm.g.context.filesDir) }) { Text("Delete report") } }
        }

        Text("Tone (how answers are worded; the engine's voice sound doesn't change)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VoiceSettings.Tone.entries.forEach { t -> FilterChip(s.tone == t, { set(s.copy(tone = t)) }, { Text(t.label) }) } }

        Text("Speaking speed", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VoiceSettings.Speed.entries.forEach { p -> FilterChip(s.speed == p, { set(s.copy(speed = p)) }, { Text(p.label) }) } }
        if (s.speed == VoiceSettings.Speed.CUSTOM) Slider(s.customRate, { set(s.copy(customRate = (it * 20).toInt() / 20f)) }, valueRange = 0.5f..2f)
        Text("${"%.2f".format(s.rate)}× · also used by Read aloud and News Listen", style = MaterialTheme.typography.bodySmall, color = x.muted)

        Text("Language", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VoiceSettings.Language.entries.forEach { l -> FilterChip(s.language == l, { set(s.copy(language = l)) }, { Text(l.label) }) } }
        Text(when (s.language) {
            VoiceSettings.Language.ENGLISH -> "Listens in English (India), replies in English, speaks with an English voice."
            VoiceSettings.Language.HINDI -> "Listens in Hindi, replies in Hindi, speaks with a Hindi voice (if one is installed)."
            else -> "Listens in the phone's language (Android recognises one language at a time), replies in the language you spoke, and speaks Hindi text with a Hindi voice."
        }, style = MaterialTheme.typography.bodySmall, color = x.muted)

        Text("Response", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VoiceSettings.ResponseMode.entries.forEach { m -> FilterChip(s.responseMode == m, { set(s.copy(responseMode = m)) }, { Text(m.label) }) } }
        SwitchRow("Allow interrupting", "Tap the core or Interrupt while Abhify speaks: it stops at once and listens. (Talking over it isn't detected automatically on Android.) The End button always works.",
            s.interruptions) { set(s.copy(interruptions = it)) }
        SwitchRow("Keep listening after each answer", "A natural back-and-forth until you tap End.", s.autoListen) { set(s.copy(autoListen = it)) }
        SwitchRow("Read typed answers aloud", "Answers to typed questions are spoken too (voice conversations always follow Response above).", readAloud) { readAloud = it; vm.setReadAloud(it) }
        SwitchRow("Screen help", "\"What is this screen?\" uses the name and purpose of the open Abhify screen — never a screenshot, and nothing else on the phone.", s.screenHelp) { set(s.copy(screenHelp = it)) }
        SwitchRow("Spoken notifications", "Off by default. Says \"An AI task finished\" while Abhify is open, outside quiet hours ${s.quietFrom}:00–${s.quietTo}:00 (no task content is read).",
            s.spokenNotifications) { set(s.copy(spokenNotifications = it)) }
        Text("Wake phrase: not available. Hands-free wake words need the microphone on all the time (a permanent notification and battery use), and Android's speech recogniser isn't built for that. Use the 🗣 Talk button.",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
        Text("Microphone and speaker: Android chooses them (a connected Bluetooth or wired headset is used automatically). " + vm.voice.description,
            style = MaterialTheme.typography.bodySmall, color = x.muted)
        Text("Privacy: Abhify never stores audio. What you say becomes a normal chat message on this phone; it goes to an online AI only under your AI mode and consent (see Online context in the chat).",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/** Privacy → Conversation data: where it is, how much, and user-initiated deletion (each confirmed; nothing else is touched). */
@Composable
fun ConversationDataGroup(vm: AppViewModel) {
    val x = LocalExtra.current
    var counts by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    val g = vm.g
    LaunchedEffect(ask) { counts = withContext(Dispatchers.IO) { runCatching { ai.abhify.android.chat.ConversationData.counts(g.db) }.getOrNull() }
        ?.let { (c, m) -> Triple(c, m, vm.knowledge.updates.size) } }
    SettingsGroup("Conversation data", accent = Accent.MEMORY) {
        Text("Your conversations, voice transcripts, memories and voice settings are stored only in Abhify's private storage on this phone " +
            "(${g.context.filesDir.parent ?: "the app's data folder"}). Other apps can't read it, it's excluded from Android cloud backup, and it survives app updates. " +
            "Cloud sync is ${if (vm.g.syncEnabled && vm.g.cloud.session != null) "ON (only the kinds you chose in Sync)" else "off"}.",
            style = MaterialTheme.typography.bodySmall)
        counts?.let { (c, m, k) -> Text("$c conversation(s) · $m memor${if (m == 1) "y" else "ies"} · $k correction record(s)", style = MaterialTheme.typography.bodyMedium) }
        NavRow("🕘", "Manage conversations", "Search, export or delete single chats") { vm.go(AppViewModel.Route.HISTORY) }
        NavRow("🧠", "Review memories", "Approve, edit or delete what Abhify remembers") { vm.go(AppViewModel.Route.MEMORIES) }
        OutlinedButton({ ask = "chats" }, border = BorderStroke(1.dp, x.bad)) { Text("Clear all conversations", color = x.bad) }
        OutlinedButton({ ask = "memories" }, border = BorderStroke(1.dp, x.bad)) { Text("Clear all memories", color = x.bad) }
        OutlinedButton({ ask = "all" }, border = BorderStroke(1.dp, x.bad)) { Text("Clear all conversational data", color = x.bad) }
        Text("These never delete documents, projects, study or career data, AI models or your provider keys.", style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
    when (ask) {
        "chats" -> Confirm("Clear all conversations?", "Every chat and voice transcript on this phone is permanently deleted. Memories are kept. This can't be undone.",
            "Delete", { vm.clearConversationData(chats = true, memories = false, extras = false) }) { ask = null }
        "memories" -> Confirm("Clear all memories?", "Everything Abhify remembers about you is permanently deleted. Chats are kept. This can't be undone.",
            "Delete", { vm.clearConversationData(chats = false, memories = true, extras = false) }) { ask = null }
        "all" -> Confirm("Clear all conversational data?", "Deletes all chats and voice transcripts, all memories, the corrections history and dismissed suggestions. " +
            "Voice settings return to their defaults. Documents, projects, study, career data, models and keys are not touched. This can't be undone.",
            "Delete everything listed", { vm.clearConversationData(chats = true, memories = true, extras = true) }) { ask = null }
    }
}


/** 7.1.1: shown on every screen while the microphone listens for the assistant's name, with an obvious Stop. */
@Composable
fun BoxScope.WakeIndicator(vm: AppViewModel) {
    if (!vm.wakeListening.value) return
    Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(RoundedCornerShape(50)).background(LocalExtra.current.glassStrong)
        .padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Text("👂 Listening for \"${vm.assistantName.value}\"", style = MaterialTheme.typography.labelMedium)
        TextButton({ vm.setWake(false) }) { Text("Stop") }
    }
}
