package ai.abhify.android.ui

import ai.abhify.android.ai.AiMode
import ai.abhify.android.cloud.Account
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, withMic: (() -> Unit) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshProgress() }
    var modelsOpen by remember { mutableStateOf(false) }
    var launcherOpen by remember { mutableStateOf(false) }
    val tab = vm.tab.value
    // 6.1: a non-bar tab (Documents/Career/Knowledge/Settings) returns to the bar tab it was opened from (Home tiles or More)
    var lastBarTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AppViewModel.Tab.MORE) }
    LaunchedEffect(tab) { if (tab.inBar) lastBarTab = tab }
    val parentTab = if (lastBarTab == AppViewModel.Tab.HOME) AppViewModel.Tab.HOME else AppViewModel.Tab.MORE
    // 4.5: Back from a tab returns to its parent (Career/Knowledge/Settings → where they came from, other tabs → Home) before leaving the app
    androidx.activity.compose.BackHandler(enabled = vm.stack.size == 1 && tab != AppViewModel.Tab.HOME) {
        vm.tab.value = if (tab.inBar) AppViewModel.Tab.HOME else parentTab
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).padding(horizontal = Dimens.tiny), verticalAlignment = Alignment.CenterVertically) {
            if (!tab.inBar) IconAction(ai.abhify.android.R.drawable.ic_arrow_back, if (parentTab == AppViewModel.Tab.HOME) "Back to Home" else "Back to More", { vm.tab.value = parentTab })
            else {                                                                 // unified workspace: the brand opens every destination
                Spacer(Modifier.width(Dimens.tiny))
                Box(Modifier.size(Dimens.touch).clip(CircleShape).clickable(onClickLabel = "Open navigation") { launcherOpen = true }
                    .semantics { contentDescription = "Abhify.AI navigation" }, contentAlignment = Alignment.Center) { Logo(30) }
            }
            Spacer(Modifier.width(Dimens.small))
            Column(Modifier.weight(1f)) {
                Text(when (tab) { AppViewModel.Tab.HOME, AppViewModel.Tab.CHAT -> BrandText.NAME; AppViewModel.Tab.SETTINGS -> "More"; else -> tab.label },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                StatusLine(vm.status())
            }
            if (tab == AppViewModel.Tab.CHAT) IconAction(ai.abhify.android.R.drawable.ic_history, "Conversation history", { vm.go(AppViewModel.Route.HISTORY) })
            if (tab == AppViewModel.Tab.CHAT) IconAction(ai.abhify.android.R.drawable.ic_add, "New chat", { vm.newChat() })
            IconAction(ai.abhify.android.R.drawable.ic_search, "Search everything", { vm.go(AppViewModel.Route.SEARCH) })
        }
        Box(Modifier.weight(1f)) {
            PageTransition(tab) { t ->
                when (t) {
                    AppViewModel.Tab.HOME -> HomeTab(vm) { modelsOpen = true }
                    AppViewModel.Tab.CHAT -> ChatTab(vm, withMic) { modelsOpen = true }
                    AppViewModel.Tab.CAREER -> CareerTab(vm, withMic)
                    AppViewModel.Tab.STUDY -> LearnTab(vm, withMic)
                    AppViewModel.Tab.DOCS -> DocumentsTab(vm)
                    AppViewModel.Tab.NEWS -> NewsTab(vm)
                    AppViewModel.Tab.KNOWLEDGE -> KnowledgeTab(vm)
                    AppViewModel.Tab.MORE, AppViewModel.Tab.SETTINGS -> SettingsTab(vm)
                }
            }
        }
        TaskStatus(vm)
        // 4.6: the aurora bar: each tab's pill takes its feature colour (animated); bar parent highlighted for Career/Knowledge/Settings
        if (!WindowInsets.isImeVisible) AuroraNavBar(tab.barParent, AppViewModel.Tab.entries.filter { it.inBar }, ::tabIcon) { t -> if (tab != t) vm.tab.value = t }
    }
    if (modelsOpen) ModelSheet(vm) { modelsOpen = false }
    if (launcherOpen) BrandLauncherSheet(vm) { launcherOpen = false }
}

/** Bottom-bar icons: official Material outlined icons (vector drawables), one style for all tabs. */
private fun tabIcon(t: AppViewModel.Tab) = when (t) {
    AppViewModel.Tab.HOME -> ai.abhify.android.R.drawable.ic_home
    AppViewModel.Tab.CHAT -> ai.abhify.android.R.drawable.ic_chat
    AppViewModel.Tab.CAREER -> ai.abhify.android.R.drawable.ic_work_outline
    AppViewModel.Tab.STUDY -> ai.abhify.android.R.drawable.ic_school
    AppViewModel.Tab.DOCS -> ai.abhify.android.R.drawable.ic_description
    AppViewModel.Tab.NEWS -> ai.abhify.android.R.drawable.ic_menu_book
    AppViewModel.Tab.KNOWLEDGE -> ai.abhify.android.R.drawable.ic_folder_open
    AppViewModel.Tab.MORE -> ai.abhify.android.R.drawable.ic_apps
    AppViewModel.Tab.SETTINGS -> ai.abhify.android.R.drawable.ic_settings
}

// ---------------------------------------------------------------------- chat
private val SUGGESTIONS = listOf("Explain gradient descent simply", "What is overfitting and how do I avoid it?",
    "How does attention work in transformers?", "Give me a 4-week plan to learn deep learning")

@Composable
fun ChatTab(vm: AppViewModel, withMic: (() -> Unit) -> Unit, onModels: () -> Unit = {}) {
    var input by remember { mutableStateOf("") }
    var editingLast by remember { mutableStateOf(false) }
    var branchFrom by remember { mutableStateOf<Int?>(null) }                 // v4.4: editing an earlier question (sent as a new chat)
    LaunchedEffect(input) { vm.draft.value = input }
    LaunchedEffect(vm.pendingInput.value) { vm.pendingInput.value?.let { input = it; vm.pendingInput.value = null } }
    val list = rememberLazyListState()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    fun share(text: String) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    // follow the end of the chat while it grows, unless the user scrolled up to read (then the position stays put)
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) follow = !list.canScrollForward }
    LaunchedEffect(list.canScrollForward) { if (!list.canScrollForward) follow = true }
    LaunchedEffect(vm.messages.size) { follow = true }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty() && follow) list.scrollToItem(vm.messages.lastIndex, Int.MAX_VALUE)        // the bottom of the last bubble
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        val firstCount = remember(vm.conversationId.value) { vm.messages.size }   // messages already there when the chat opened don't animate
        val appeared = remember(vm.conversationId.value) { HashSet<Int>() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            if (vm.messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = Dimens.gap, start = Dimens.tiny, end = Dimens.tiny), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    AiOrb(orbStateOf(vm), 96)
                    Text("Ask ${vm.assistantName.value} anything", style = MaterialTheme.typography.headlineSmall,   // 7.1.0: a heading, not a greeting that looks like an answer
                        color = MaterialTheme.colorScheme.onBackground)
                    val st = vm.states()
                    Text(when (vm.aiMode.value) {
                        AiMode.DEVICE -> "Answers come from the AI on this phone. Private and offline."
                        AiMode.ONLINE -> when (val t = vm.g.onlineTarget()) {
                            null -> "Online mode: no provider selected yet."
                            is ai.abhify.android.ai.OnlineTarget.Personal -> "Online mode: answers come from ${t.provider.title} with this phone's own key. No Abhify account needed."
                            ai.abhify.android.ai.OnlineTarget.Shared -> "Online mode: answers come from your Abhify server (shared access)."
                        }
                        AiMode.AUTO -> if (vm.onlineAllowed.value) "Auto: your selected online provider when available" + (if (vm.fallbackToDevice.value) ", otherwise the AI on this phone." else ".")
                            else "Auto: online AI is off in Privacy, so answers come from the AI on this phone."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(max = 520.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.small, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
                        StateChip("🔑", st.provider, st.providerLevel) { vm.go(AppViewModel.Route.PROVIDERS) }
                        StateChip("📱", st.local, if (st.localOk) 0 else 1) { vm.go(AppViewModel.Route.MODELS) }
                        StateChip("👤", st.account, if (vm.account.value is ai.abhify.android.cloud.Account.Expired) 1 else 0) { vm.go(AppViewModel.Route.ACCOUNT) }
                    }
                    if (vm.aiMode.value != AiMode.DEVICE && vm.g.onlineTarget() == null) Glass {
                        Text(ai.abhify.android.ai.RoutePolicy.NO_PROVIDER, style = MaterialTheme.typography.bodyMedium)
                        GradientButton("Open AI Providers", { vm.go(AppViewModel.Route.PROVIDERS) }, Modifier.fillMaxWidth())
                    }
                    if (!vm.g.providerStore.anyConfigured() && !vm.g.prefs.getBoolean("providers_card_dismissed", false)) Glass {
                        Text("Want stronger answers online?", style = MaterialTheme.typography.titleSmall)
                        Text("Add your own free Gemini, Groq or OpenRouter key. It stays on this phone. The AI on this phone keeps working offline either way.",
                            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        Row {
                            TextButton({ vm.go(AppViewModel.Route.PROVIDERS) }) { Text("Set up providers") }
                            TextButton({ vm.g.prefs.edit().putBoolean("providers_card_dismissed", true).apply(); vm.refreshPending() }) { Text("Not now") }
                        }
                    }
                    // v4.3: the same four starters, as a two-column grid of equal-height cards
                    Text("Try asking", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.muted, modifier = Modifier.fillMaxWidth().padding(top = Dimens.tiny))
                    val starterAccents = listOf(Accent.CHAT to "💡", Accent.STUDY to "🎓", Accent.DOCS to "✨", Accent.CAREER to "🗓")
                    SUGGESTIONS.withIndex().chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                            pair.forEach { (i, s) -> val (a, g) = starterAccents[i % starterAccents.size]
                                AccentCard(a, Modifier.weight(1f).fillMaxHeight(), onClick = { vm.send(s) }) {
                                    AccentIcon(a, g, 30)
                                    Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) } }
                        }
                    }
                }
            }
            itemsIndexed(vm.messages) { i, m ->
                val lastUser = m.role == "user" && i == vm.messages.indexOfLast { it.role == "user" }
                val animateIn = remember { i >= firstCount && appeared.add(i) }        // 6.1: only messages that arrive now, once
                Box(Modifier.fillMaxWidth().messageAppear(animateIn)) { Bubble(m, isLast = i == vm.messages.lastIndex, generating = vm.generating.value,
                    onCopy = { clip.setText(AnnotatedString(m.text)) }, onSpeak = { vm.speak(m.text) }, onRegenerate = vm::regenerate,
                    onShare = { share(m.text) }, onEdit = if (vm.generating.value) null else if (lastUser) ({ input = m.text; editingLast = true; branchFrom = null })
                        else if (m.role == "user" && m.dbId != null && !vm.privateChat.value) ({ input = m.text; branchFrom = i; editingLast = false }) else null,
                    onProviders = { vm.go(AppViewModel.Route.PROVIDERS) },
                    onContinue = if (i == vm.messages.lastIndex && vm.canContinue(m)) vm::continueAnswer else null,
                    onFeedback = { v -> vm.setFeedback(i, v) },
                    onSave = { vm.life.save("ai-answer", vm.messages.getOrNull(i - 1)?.text?.take(80) ?: "AI answer", m.text, "AI answers", emptyList(), "message", m.dbId) },
                    onNote = { vm.saveAnswerAsNote(i) },
                    onCorrect = if (vm.privateChat.value) null else ({ fix -> vm.correctAnswer(i, fix) }),
                    searching = i == vm.messages.lastIndex && vm.searching.value,
                    onRefreshLive = if (i == vm.messages.lastIndex && !vm.generating.value && (m.links.isNotEmpty() || m.live != null)) vm::refreshLive else null,
                    onAction = { prompt -> if (!vm.generating.value) vm.send(prompt) },
                    // the phone's model failed: offer the configured provider explicitly (consent dialog first); never automatic
                    onOnline = if (i == vm.messages.lastIndex && !vm.generating.value && m.text.startsWith("⚠️") && m.source?.cloud == false)
                        vm.onlineAlternative()?.let { p -> "Ask ${p.title} instead…" to vm::askOnlineInstead } else null) }
            }
        }
        if (branchFrom != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✏ Editing an earlier question: it will be sent in a new chat; this chat stays as it is.", style = MaterialTheme.typography.labelMedium,
                color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
            TextButton({ branchFrom = null; input = "" }) { Text("Cancel") }
        }
        DestinationLine(vm, editingLast) { editingLast = false; input = "" }
        if (vm.listening.value || vm.voiceError.value != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.listening.value) "🎙 Listening… ${vm.heard.value}" else "🎙 ${vm.voiceError.value}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = if (vm.listening.value) MaterialTheme.colorScheme.tertiary else LocalExtra.current.bad)
            if (vm.listening.value) TextButton(vm::cancelVoice) { Text("Cancel") }
            else { TextButton({ withMic { vm.retryVoice() } }) { Text("Retry") }; TextButton({ vm.voiceError.value = null }) { Text("Dismiss") } }   // 7.1.1
        }
        if (vm.speaking.value || vm.speechPaused.value) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.speechPaused.value) "⏸ Reading paused" else "🔊 Reading the answer aloud", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            if (vm.speechPaused.value) TextButton(vm::resumeSpeaking) { Text("Resume") } else TextButton(vm::pauseSpeaking) { Text("Pause") }
            TextButton(vm::stopSpeaking) { Text("Stop") }
        }
        DocumentBar(vm)
        ChatContextBar(vm)
        Column(Modifier.fillMaxWidth().background(LocalExtra.current.glassStrong)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModelChip(vm, onOpen = onModels)                                     // 4.5: the model that answers next; tap to switch
            Spacer(Modifier.weight(1f))
            // 5.4 Personal Voice Companion: a hands-free conversation (tap-to-talk; the panel shows when the mic is on)
            if (vm.voiceSettings.value.enabled) androidx.compose.material3.AssistChip({ withMic { vm.voiceState.start() } },
                { Text("🗣 Talk", style = MaterialTheme.typography.labelLarge) }, enabled = !vm.voiceState.active.value)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            // voice fills the message box; the user reviews/edits and taps Send (never auto-submitted)
            FilledTonalButton({ if (vm.listening.value) vm.stopVoice() else withMic { vm.startVoice { heard -> input = if (input.isBlank()) heard else "$input $heard" } } },
                shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = if (vm.listening.value) "Stop listening" else "Speak your question" }) {
                if (vm.listening.value) Text("■") else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(ai.abhify.android.R.drawable.ic_mic), null, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(4.dp))
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.attach(uri)
            }
            FilledTonalButton({ picker.launch(ai.abhify.android.docs.Documents.PICKER_TYPES) }, enabled = !vm.docBusy.value && !vm.generating.value,
                shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = "Attach a document" }) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(ai.abhify.android.R.drawable.ic_add), null, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(if (vm.attachment.value != null) "Ask about the document…" else "Ask about AI/ML…", color = LocalExtra.current.muted) }, maxLines = 5, shape = RoundedCornerShape(Dimens.composerRadius),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LocalExtra.current.line.copy(alpha = 0.9f), focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface))
            Spacer(Modifier.width(6.dp))
            val sendable = input.isNotBlank() && !vm.generating.value
            Box(Modifier.size(48.dp).background(if (vm.generating.value || sendable) Brand.Accent else Brush.linearGradient(listOf(LocalExtra.current.line, LocalExtra.current.line)), CircleShape)
                .clickable(onClickLabel = if (vm.generating.value) "Stop" else "Send") { if (vm.generating.value) vm.stop() else if (sendable) { val b = branchFrom; if (b != null) vm.editAsBranch(b, input) else if (editingLast) vm.editAndResend(input) else vm.send(input)
                    input = ""; editingLast = false; branchFrom = null } }
                .semantics { contentDescription = if (vm.generating.value) "Stop generating" else "Send" }, contentAlignment = Alignment.Center) {
                if (vm.generating.value) Text("■", color = Color.White)
                else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(ai.abhify.android.R.drawable.ic_send), null, tint = if (sendable) Color.White else LocalExtra.current.muted, modifier = Modifier.size(20.dp))
            }
        }
        }
    }
}

/** The attached document (read on this phone): progress, errors, view and remove; plus the consent question before it goes online. */
@Composable
private fun DocumentBar(vm: AppViewModel) {
    val x = LocalExtra.current
    var viewing by remember { mutableStateOf(false) }
    val d = vm.attachment.value
    when {
        vm.docBusy.value -> Box(Modifier.padding(horizontal = 16.dp)) {                   // 5.6: the shared loading banner
            BusyBanner(vm.docProgress.value ?: "Reading the document on this phone…", onCancel = vm::cancelAttach) }
        vm.docError.value != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📎 ${vm.docError.value}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = x.bad)
            TextButton(vm::removeAttachment) { Text("Dismiss") }
        }
        d != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).background(x.glass, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📎 ${d.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.kind} · ${"%,d".format(d.chars)} characters" + (if (d.truncated) " (first ${"%,d".format(ai.abhify.android.docs.Documents.MAX_CHARS)} kept)" else "") +
                    " · read on this phone, not saved", style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            TextButton({ viewing = true }) { Text("View") }
            TextButton(vm::removeAttachment) { Text("Remove") }
        }
    }
    if (viewing && d != null) androidx.compose.material3.AlertDialog(onDismissRequest = { viewing = false }, confirmButton = { TextButton({ viewing = false }) { Text("Close") } },
        title = { Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(d.text.take(20_000) + if (d.text.length > 20_000) "\n…" else "", style = MaterialTheme.typography.bodyMedium) } } })
    vm.consentAsk.value?.let {
        val dest = vm.onlineDestination() ?: "the online AI"
        androidx.compose.material3.AlertDialog(onDismissRequest = { vm.confirmDocConsent(false) },
            title = { Text("Send this document online?") },
            text = { DialogText("To answer, the text of \"${d?.name}\" (or its most relevant parts, up to about 24,000 characters) will be sent with your question to $dest. " +
                "Choose No to keep it on this phone; you can switch to On this phone mode to ask about it privately.") },
            confirmButton = { TextButton({ vm.confirmDocConsent(true) }) { Text("Send") } },
            dismissButton = { TextButton({ vm.confirmDocConsent(false) }) { Text("No") } })
    }
}

/** v3.4: documents chosen as chat context, and what an online request may include. */
@Composable
private fun ChatContextBar(vm: AppViewModel) {
    var picking by remember { mutableStateOf(false) }
    var context by remember { mutableStateOf(false) }
    var pickingWs by remember { mutableStateOf(false) }
    var promptsOpen by remember { mutableStateOf(false) }
    var focusOpen by remember { mutableStateOf(false) }
    if (promptsOpen) PromptLibraryDialog(vm) { promptsOpen = false }
    if (focusOpen) AlertDialog(onDismissRequest = { focusOpen = false }, title = { Text("Mode") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ai.abhify.android.intel.Intelligence.Focus.entries.forEach { f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.setFocus(f); focusOpen = false }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(vm.focus.value == f, { vm.setFocus(f); focusOpen = false })
                    Column { Text("${f.glyph} ${f.label}", style = MaterialTheme.typography.bodyLarge)
                        Text(f.hint, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
                }
            }
            Text("A mode changes how Abhify works on the answer: how much conversation and personal context it uses, answer length and creativity, and the reasoning structure. It never changes which AI answers. Research also searches your own knowledge and, when online access is on, sends your question to Google News to gather recent sources (cited as [W1]…). The mode used is shown under each answer.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        } },
        confirmButton = { TextButton({ focusOpen = false }) { Text("Done") } })
    if (pickingWs) WorkspacePicker(vm, "Workspace as chat context", vm.chatWorkspace.value, allowNone = true,
        note = "Adds the workspace's most relevant notes, open tasks and its documents to answers (bounded; shown under each answer). " +
            "An online AI gets them only after you agree.", onDismiss = { pickingWs = false }) { ws -> vm.hub.useInChat(ws); pickingWs = false }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AssistChipLike(if (vm.chatDocs.isEmpty()) "📚 Documents" else "📚 ${vm.chatDocs.size} document(s)") { picking = true }
        // 5.0 Knowledge Chat: a tap cycles Normal → Auto → Knowledge (details in More → Knowledge Center)
        AssistChipLike("📖 Knowledge: ${vm.knowledgeMode.value.label}") {
            val modes = ai.abhify.android.docs.KnowledgeChat.Mode.entries; vm.setKnowledgeMode(modes[(vm.knowledgeMode.value.ordinal + 1) % modes.size]) }
        AssistChipLike(if (vm.memoriesEnabled.value) "🧠 Memory on" else "🧠 Memory off") { vm.go(AppViewModel.Route.MEMORIES) }
        AssistChipLike(vm.chatWorkspace.value?.let { "🗂 ${it.name.take(18)}" } ?: "🗂 Workspace") { pickingWs = true }
        // 5.1 chat focus: a tap opens the choice (Auto detects coding, learning, career, research per message)
        AssistChipLike("${vm.focus.value.glyph} ${vm.focus.value.label}") { focusOpen = true }
        AssistChipLike("📋 Prompts") { promptsOpen = true }
        AssistChipLike("⚙ Online context") { context = true }
    }
    if (picking) {
        LaunchedEffect(Unit) { vm.studio.loadDocs() }
        AlertDialog(onDismissRequest = { picking = false }, title = { Text("Documents for this chat") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Answers use only the best-matching sections of the documents you tick, with [S1]… citations. Nothing is attached otherwise.",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                if (vm.studio.docs.isEmpty()) Text("No documents in your library yet (Documents tab).")
                vm.studio.docs.forEach { d -> Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(d.id in vm.chatDocs, { on -> if (on) vm.chatDocs += d.id else vm.chatDocs -= d.id })
                    Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            } },
            confirmButton = { TextButton({ picking = false }) { Text("Done") } },
            dismissButton = { if (vm.chatDocs.isNotEmpty()) TextButton({ vm.chatDocs.clear() }) { Text("Remove all") } })
    }
    if (context) AlertDialog(onDismissRequest = { context = false }, title = { Text("What an online AI may receive") },
        text = { Column {
            Text("Your current question is always sent when an online provider answers. Choose what else may go with it:", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Recent messages of this chat", "Off: the provider sees only the question.", vm.onlineHistory.value) { vm.setOnlineHistory(it) }
            SwitchRow("Relevant personal memories", "Off by default. Only memories related to the question.", vm.shareMemories.value, enabled = vm.onlineAllowed.value) { vm.setShareMemories(it) }
            Text("Selected documents: always asked first (\"Send document passages online?\").", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Text("The phone's model is used without any of this leaving the phone.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            // 5.1 routing transparency: the unchanged routing rules, explained
            val why = vm.routeExplanation()
            Spacer(Modifier.height(8.dp))
            Text("Next answer", style = MaterialTheme.typography.labelLarge)
            Text(why.route, style = MaterialTheme.typography.bodySmall)
            Text(why.privacy, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            why.hint?.let { Text("💡 $it", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
        } },
        confirmButton = { TextButton({ context = false }) { Text("Done") } })
}

@Composable
private fun AssistChipLike(label: String, onClick: () -> Unit) =
    androidx.compose.material3.AssistChip(onClick, { Text(label, style = MaterialTheme.typography.labelMedium) })

/** Where the next answer will come from, so online use is never a surprise. */
@Composable
private fun DestinationLine(vm: AppViewModel, editing: Boolean, cancelEdit: () -> Unit) {
    val x = LocalExtra.current
    val first = ai.abhify.android.ai.RoutePolicy.plan(vm.routeInputs()).first()
    val text = when (first) {
        ai.abhify.android.ai.Route.Device -> "📱 Answers on this phone · nothing leaves it"
        is ai.abhify.android.ai.Route.Personal -> "☁ ${first.provider.title} (your key) gets: your question" + (if (vm.onlineHistory.value) " + recent chat" else "") +
            (if (vm.g.shareMemoriesOnline && vm.memoriesEnabled.value) " + relevant memories" else "") +
            (if (vm.attachment.value != null || vm.chatDocs.isNotEmpty() || vm.chatWorkspace.value != null) " · documents/workspace only after you agree" else "")
        ai.abhify.android.ai.Route.Server -> "☁ Sends your question and recent chat to your Abhify server"
        is ai.abhify.android.ai.Route.Unavailable -> "⚠ " + first.reason
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.privateChat.value && !editing) { Text("🕶 Private", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(6.dp)) }
        Text(if (editing) "✏ Editing your last question" else text, style = MaterialTheme.typography.labelMedium,
            color = if (first is ai.abhify.android.ai.Route.Unavailable) x.warn else x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (editing) TextButton(cancelEdit) { Text("Cancel") }
        if (vm.privateChat.value && !editing) TextButton({ vm.setPrivate(false) }) { Text("End") }
    }
}

/** Long answers start collapsed only when they are not the newest message; the stored and copied text is always complete. */
private const val PREVIEW_CHARS = 1600
private const val COLLAPSE_OVER = 2400

/** The preview cut: at a paragraph (or sentence) boundary before [max], never inside a code block. */
internal fun previewOf(text: String, max: Int = PREVIEW_CHARS): String {
    if (text.length <= max) return text
    var cut = text.lastIndexOf("\n\n", max).takeIf { it > max / 2 } ?: text.lastIndexOf(". ", max).takeIf { it > max / 2 }?.plus(1) ?: max
    if (text.substring(0, cut).split("```").size % 2 == 0) cut = text.lastIndexOf("```", cut).coerceAtLeast(0)   // don't end inside a fence
    return text.substring(0, cut).trimEnd()
}

@Composable
private fun Bubble(m: AppViewModel.UiMsg, isLast: Boolean, generating: Boolean, onCopy: () -> Unit, onSpeak: () -> Unit, onRegenerate: () -> Unit,
                   onShare: () -> Unit, onEdit: (() -> Unit)?, onProviders: () -> Unit = {}, onContinue: (() -> Unit)? = null, onFeedback: (Int?) -> Unit = {},
                   onSave: () -> Unit = {}, onOnline: Pair<String, () -> Unit>? = null, onNote: (() -> Unit)? = null, onAction: ((String) -> Unit)? = null,
                   /** 5.0 controlled learning: the correction becomes a candidate for review, never knowledge by itself */ onCorrect: ((String) -> Unit)? = null,
                   /** 5.3: retrieval is running for this answer (neural core SEARCHING) */ searching: Boolean = false,
                   /** 6.2: re-ask with fresh live information */ onRefreshLive: (() -> Unit)? = null) {
    val user = m.role == "user"
    var correcting by remember { mutableStateOf(false) }
    val x = LocalExtra.current
    val long = !m.streaming && m.text.length > COLLAPSE_OVER
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(m.dbId, m.text.length) { mutableStateOf(isLast || !long) }
    var actionsOpen by remember { mutableStateOf(false) }
    var translateOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) { AiOrb(if (m.streaming) (if (m.text.isEmpty()) (if (searching) OrbState.SEARCHING else OrbState.THINKING) else OrbState.GENERATING) else OrbState.IDLE, 28, still = !m.streaming); Spacer(Modifier.width(6.dp)) }
        Column(Modifier.widthIn(max = 360.dp)
            .background(if (user) x.userBubble else Brush.linearGradient(listOf(x.glass, x.glass)),
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (user) 20.dp else 6.dp, bottomEnd = if (user) 6.dp else 20.dp))
            .let { if (user) it else it.border(1.dp, x.line, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)) }
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (user) {
                Text(m.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                onEdit?.let { TextButton(it, contentPadding = PaddingValues(0.dp)) { Text("✏ Edit", color = Color.White, style = MaterialTheme.typography.labelMedium) } }
            }
            else {
                if (m.streaming) m.source?.let { Text("${it.label} · writing…", style = MaterialTheme.typography.labelMedium, color = x.muted) }
                m.note?.takeIf { it.contains("unavailable (") }?.let { Text("ⓘ $it", style = MaterialTheme.typography.labelMedium, color = x.warn) }
                if (m.text.isEmpty() && m.streaming) Text("Thinking…", color = x.muted, style = MaterialTheme.typography.bodyMedium)
                else SelectionContainer { MarkdownText(if (long && !expanded) previewOf(m.text) else m.text) }
                if (long) TextButton({ expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (expanded) "Show less ▲" else "Read more ▼ (${"%,d".format(m.text.length - previewOf(m.text).length)} more characters)",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!m.streaming && m.status != null) {
                    val (icon, what) = when (m.status) {
                        ai.abhify.android.chat.Continuation.Status.LENGTH -> "⚠" to "Partial answer: it reached the answer length limit."
                        ai.abhify.android.chat.Continuation.Status.STOPPED -> "■" to "Partial answer: you stopped it."
                        else -> "⚠" to "Partial answer: the connection or the model ended early."
                    }
                    Text("$icon $what", style = MaterialTheme.typography.bodyMedium, color = x.warn, fontWeight = FontWeight.SemiBold)
                }
                if (m.streaming) m.live?.let { LiveStateLine(it) }                       // 6.2: "Checking current information…"
                if (!m.streaming && m.text.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.source?.let { Pill(it.label, if (it.cloud) MaterialTheme.colorScheme.tertiary else x.ok) }
                        m.note?.takeIf { !it.contains("unavailable (") }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = x.muted) }
                    }
                    if (m.sources.isNotEmpty()) {
                        val cited = ai.abhify.android.docs.Library.cited(m.text)
                        Text("Sources sent: " + m.sources.joinToString(" · ") { it + if (it.removePrefix("[S").substringBefore(']').toIntOrNull() in cited) " ✓" else "" },
                            style = MaterialTheme.typography.labelMedium, color = x.muted)
                        if (cited.isEmpty()) Text("No source cited: treat this as general knowledge, not from your documents.", style = MaterialTheme.typography.labelMedium, color = x.warn)
                    }
                    LiveSources(m, onRefreshLive)                                        // 6.2: source chips, live state, refresh
                    if (m.memoriesUsed.isNotEmpty()) {
                        var showMem by remember(m.dbId) { mutableStateOf(false) }
                        TextButton({ showMem = !showMem }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text("🧠 ${m.memoriesUsed.size} personal memor${if (m.memoriesUsed.size == 1) "y" else "ies"} used", style = MaterialTheme.typography.labelMedium) }
                        if (showMem) m.memoriesUsed.forEach { Text("• $it", style = MaterialTheme.typography.labelMedium, color = x.muted) }
                    }
                    onOnline?.let { (label, act) -> OutlinedButton(act) { Text(label) } }
                    val failed = m.source == null && m.text.startsWith("⚠️")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        onContinue?.let { TextButton(it, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▶ Continue", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) } }
                        // 4.6 AI Action Center: follow-ups on this answer through the normal chat
                        if (onAction != null && !failed && !generating) TextButton({ actionsOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("✨ Actions", style = MaterialTheme.typography.labelLarge, color = Accent.CHAT.text) }
                        if (failed && "AI Providers" in m.text) TextButton(onProviders, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("🔑 Open AI Providers", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onCopy, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Copy", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onSpeak, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("🔊 Read", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onShare, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Share", style = MaterialTheme.typography.labelMedium) }
                        if (m.dbId != null) TextButton(onSave, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⭳ Save", style = MaterialTheme.typography.labelMedium) }
                        if (m.dbId != null && onNote != null) TextButton(onNote, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("📒 Note", style = MaterialTheme.typography.labelMedium) }
                        if (isLast && !generating) TextButton(onRegenerate, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(if (failed || m.status != null) "↻ Retry" else "↻ Regenerate", style = MaterialTheme.typography.labelMedium) }
                        if (actionsOpen && onAction != null) ActionSheet("Do more with this answer", "Sent as a follow-up in this chat, with the same model",
                            listOf(null to ai.abhify.android.chat.AiActions.Action.entries.map { a -> SheetAction(a.glyph, a.label + if (a == ai.abhify.android.chat.AiActions.Action.TRANSLATE) "…" else "") {
                                if (a == ai.abhify.android.chat.AiActions.Action.TRANSLATE) translateOpen = true else onAction(ai.abhify.android.chat.AiActions.prompt(a, m.text)) } })) { actionsOpen = false }
                        if (translateOpen && onAction != null) ActionSheet("Translate into", null,
                            listOf(null to ai.abhify.android.chat.AiActions.LANGUAGES.map { lang -> SheetAction("🔀", lang) {
                                onAction(ai.abhify.android.chat.AiActions.prompt(ai.abhify.android.chat.AiActions.Action.TRANSLATE, m.text, lang)) } })) { translateOpen = false }
                        if (m.dbId != null) {
                            TextButton({ onFeedback(1) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(if (m.feedback == 1) "👍 ✓" else "👍", style = MaterialTheme.typography.labelMedium) }
                            TextButton({ onFeedback(-1) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(if (m.feedback == -1) "👎 ✓" else "👎", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (onCorrect != null && !failed && !generating) TextButton({ correcting = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("✎ Correct", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (correcting && onCorrect != null) {
                        var fix by remember { mutableStateOf("") }
                        AlertDialog(onDismissRequest = { correcting = false },
                            title = { Text("Correct this answer") },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Write the correct information. It waits in Knowledge Center for your approval; nothing is learned until you approve it.",
                                    style = MaterialTheme.typography.bodyMedium, color = x.muted)
                                OutlinedTextField(fix, { fix = it.take(500) }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. My notice period is 60 days, not 30") }, minLines = 2)
                            } },
                            confirmButton = { TextButton({ onCorrect(fix); correcting = false }, enabled = fix.trim().length >= 3) { Text("Save for review") } },
                            dismissButton = { TextButton({ correcting = false }) { Text("Cancel") } })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- learn: tutor, plans, cards, interview, quiz, news
// 4.6.2: News moved out of Study into its own tab (News Center)
// 5.3 Study: the existing sections keep their numbers (other screens open them by number); Discover, MCQs and the
// Question Bank (the same screens as in Career, not copies) are added. Shown order differs from the numbers.
private val LEARN_SECTIONS = listOf("Overview", "Tutor", "Plans", "Cards", "Quiz", "MCQs", "Questions", "Discover", "Prepare")
private val LEARN_GLYPHS = listOf("🏠", "💡", "🗓", "🃏", "❔", "✅", "📚", "🔍", "🎯")
private val LEARN_ORDER = listOf(8, 0, 7, 1, 2, 3, 4, 5, 6)                    // 7.2: Prepare (courses, interview plan, STAR stories) first; then Overview, Discover, Tutor, Plans, Cards, Quiz, MCQs, Questions

@Composable
fun LearnTab(vm: AppViewModel, withMic: (() -> Unit) -> Unit = {}) {
    LaunchedEffect(Unit) { if (vm.quiz.isEmpty()) vm.newQuiz() }
    var bankMode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(LEARN_ORDER.map { LEARN_SECTIONS[it] }, LEARN_ORDER.indexOf(vm.learnMode.value).coerceAtLeast(0),
            Modifier.padding(horizontal = Dimens.gutter, vertical = Dimens.tiny), LEARN_ORDER.map { LEARN_GLYPHS[it] }, Accent.STUDY) { vm.learnMode.value = LEARN_ORDER[it] }
        if (vm.learnMode.value == 4) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("all" to "All") + vm.g.bank.categories).forEach { (id, name) -> FilterChip(selected = vm.category.value == id, onClick = { vm.setCategory(id) }, label = { Text(name) }) }
        }
        Box(Modifier.weight(1f)) {
            when (vm.learnMode.value) {
                0 -> StudyView(vm); 1 -> TutorView(vm); 2 -> PlansView(vm); 3 -> CardsView(vm); 4 -> QuizView(vm)
                5 -> McqView(vm); 6 -> QuestionBankView(vm, withMic, bankMode) { bankMode = it }; 8 -> StudyPrepView(vm); else -> DiscoverView(vm)
            }
        }
    }
}

@Composable
private fun AiWriteButton(vm: AppViewModel, text: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedButton(onClick, enabled = !vm.learnBusy.value && !vm.generating.value, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(Dimens.fieldRadius)) {
            if (vm.learnBusy.value) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            else { GlyphIcon("✨", 18, MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp)) }
            Text(if (vm.learnBusy.value) "The AI on this phone is writing…" else text)
        }
        vm.learnMessage.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.bad) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun InterviewList(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        AiWriteButton(vm, "New question from the AI (offline)") { vm.generateInterview() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.interview.size} questions · curated" + (vm.interview.count { it.generated }.takeIf { it > 0 }?.let { " + $it AI" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
            IconAction(ai.abhify.android.R.drawable.ic_refresh, "Refresh questions", { vm.refreshBank() })
        }
        // pull down (or Refresh) = the same category in a new order; the AI's questions stay on top; nothing is deleted
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(vm.bankRefreshing.value, vm::refreshBank, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(vm.interview, key = { it.question }) { q ->
                val open = q.question in vm.revealed
                Glass(onClick = { vm.reveal(q) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(vm.g.bank.categories.firstOrNull { it.first == q.category }?.second ?: q.category)
                        Pill(q.difficulty, when (q.difficulty) { "easy" -> LocalExtra.current.ok; "hard" -> LocalExtra.current.bad; else -> LocalExtra.current.warn })
                        if (q.generated) Pill("AI", MaterialTheme.colorScheme.secondary)
                    }
                    Text(q.question, style = MaterialTheme.typography.titleSmall)
                    if (open) MarkdownText(q.answer, color = LocalExtra.current.muted)
                    Text(if (open) "Hide answer" else "Tap to show the answer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        }
    }
}

@Composable
private fun QuizView(vm: AppViewModel) {
    val p = vm.progress.value
    PageBody {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
            StatTile("${p.correct}/${p.answered}", "correct", Modifier.weight(1f), accent = Accent.STUDY)
            StatTile(if (p.answered > 0) "${p.correct * 100 / p.answered}%" else "–", "score", Modifier.weight(1f), accent = Accent.DOCS)
            StatTile("${p.streakDays}", "day streak", Modifier.weight(1f), accent = Accent.CAREER)
        }
        val m = vm.quiz.getOrNull(vm.quizIndex.intValue)
        if (m == null) Glass {
            Text("Round done: ${vm.roundScore.intValue} correct", style = MaterialTheme.typography.titleMedium)
            GradientButton("New round", vm::newQuiz, Modifier.fillMaxWidth())
        } else Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Question ${vm.quizIndex.intValue + 1} of ${vm.quiz.size}", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
                if (m.generated) Pill("AI", MaterialTheme.colorScheme.secondary)
            }
            val prog by androidx.compose.animation.core.animateFloatAsState((vm.quizIndex.intValue + 1f) / vm.quiz.size.coerceAtLeast(1),
                Motion.tween(Motion.EMPHASIS, reducedMotion(), Motion.Emphasized), label = "quiz")
            LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Accent.STUDY.g2, trackColor = LocalExtra.current.line, drawStopIndicator = {}, gapSize = 0.dp)
            Text(m.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = Dimens.tiny))
            val picked = vm.picked.value
            m.options.forEachIndexed { i, o ->
                val x = LocalExtra.current
                val c = when { picked == null -> x.line; i == m.correct -> x.ok; i == picked -> x.bad; else -> x.line }
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius))
                    .background(if (picked != null && (i == m.correct || i == picked)) c.copy(alpha = 0.10f) else Color.Transparent)
                    .border(if (picked != null && (i == m.correct || i == picked)) 2.dp else 1.dp, c, RoundedCornerShape(Dimens.fieldRadius))
                    .clickable(enabled = picked == null, onClickLabel = "Answer ${'A' + i}") { vm.answerQuiz(i) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(if (picked != null && i == m.correct) x.ok else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("${'A' + i}", style = MaterialTheme.typography.labelLarge, color = if (picked != null && i == m.correct) Color.White else MaterialTheme.colorScheme.tertiary) }
                    Spacer(Modifier.width(12.dp))
                    Text(o, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                }
            }
            picked?.let {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.fieldRadius)).background((if (it == m.correct) LocalExtra.current.ok else LocalExtra.current.bad).copy(alpha = 0.10f))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (it == m.correct) "Correct" else "Not quite: the answer is ${'A' + m.correct}.", fontWeight = FontWeight.Bold,
                        color = if (it == m.correct) LocalExtra.current.ok else LocalExtra.current.bad)
                    Text(m.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                GradientButton(if (vm.quizIndex.intValue + 1 < vm.quiz.size) "Next question" else "See result", vm::nextQuestion, Modifier.fillMaxWidth())
            }
        }
        AiWriteButton(vm, "Add a question written by the AI (offline)") { vm.generateMcq() }
    }
}

// 6.1: the duplicate ago(ms) lived here; V45Screens.ago(t) (with "2 days ago" / dates) is the one shared helper.
