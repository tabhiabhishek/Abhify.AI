package ai.abhify.android.ui

import ai.abhify.android.R
import ai.abhify.android.ai.ModelChoices
import ai.abhify.android.chat.ChatDb
import ai.abhify.android.chat.conversationPage
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.chunkCounts
import ai.abhify.android.chat.planStats
import ai.abhify.android.chat.vectorCounts
import ai.abhify.android.chat.workspaces
import androidx.compose.material3.LinearProgressIndicator
import ai.abhify.android.chat.deckStats
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ====================================================================== 4.5 chat model chip and picker

/**
 * The model that answers the next message, shown next to the chat composer (and on Home). Tapping opens the picker.
 * Shows a spinner while a phone model loads after a switch, and a warning colour when the phone model failed to start.
 */
@Composable
fun ModelChip(vm: AppViewModel, onDark: Boolean = false, onOpen: () -> Unit) {
    val inputs = vm.modelInputs()
    val label = ModelChoices.chipLabel(inputs)
    val engine = vm.engine.value
    val switching = vm.modelSwitching.value != null || (engine is AppViewModel.Engine.Loading && inputs.mode != ai.abhify.android.ai.AiMode.ONLINE)
    val failed = engine is AppViewModel.Engine.Error && inputs.mode == ai.abhify.android.ai.AiMode.DEVICE
    val fg = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface
    val icon = when (inputs.mode) { ai.abhify.android.ai.AiMode.DEVICE -> R.drawable.ic_smartphone; ai.abhify.android.ai.AiMode.ONLINE -> R.drawable.ic_cloud_queue
        ai.abhify.android.ai.AiMode.AUTO -> R.drawable.ic_auto_awesome }
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).border(1.dp, if (onDark) Color.White.copy(alpha = 0.45f) else if (failed) LocalExtra.current.bad else LocalExtra.current.line, RoundedCornerShape(50))
        .clickable(onClickLabel = "Choose the AI model", onClick = onOpen)
        .semantics { contentDescription = "AI model: $label" + if (switching) ", loading" else if (failed) ", failed to start" else ""; role = Role.Button }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (switching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
        else Icon(painterResource(icon), null, tint = if (failed) LocalExtra.current.bad else if (onDark) fg else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Icon(painterResource(R.drawable.ic_expand_more), null, tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

/**
 * The model picker (bottom sheet): Auto, the phone models from the catalog (installed ones selectable, others marked
 * "not installed" with a link to AI models), and the online providers configured with the user's own key. Only real,
 * existing options; opening it never downloads anything or turns online AI on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(vm: AppViewModel, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inputs = vm.modelInputs()
    val choices = remember(inputs) { ModelChoices.build(inputs) }
    ModalBottomSheet(onClose, sheetState = state, containerColor = LocalExtra.current.glassStrong, shape = RoundedCornerShape(topStart = Dimens.dialogRadius, topEnd = Dimens.dialogRadius)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            Text("AI model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Used for your next messages in every chat. Earlier answers keep the model they were written with.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            vm.modelSwitching.value?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(Dimens.small))
                    Text("Loading $name on this phone…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            vm.modelPickError.value?.let { ErrorBanner(it, onRetry = if (inputs.mode == ai.abhify.android.ai.AiMode.DEVICE) ({ vm.retryModel(); Unit }) else null) }
            (vm.engine.value as? AppViewModel.Engine.Error)?.takeIf { inputs.mode == ai.abhify.android.ai.AiMode.DEVICE && vm.modelPickError.value == null }
                ?.let { ErrorBanner("The phone model could not start: ${it.message}", onRetry = { vm.retryModel(); Unit }) }
            val busy = vm.modelSwitching.value != null || vm.generating.value
            ModelGroup("Automatic", choices.filter { it.kind == ModelChoices.Kind.AUTO }, busy, vm)
            ModelGroup("On this phone · private, offline", choices.filter { it.kind == ModelChoices.Kind.DEVICE }, busy, vm, onGet = { onClose(); vm.go(AppViewModel.Route.MODELS) }) {
                if (choices.none { it.kind == ModelChoices.Kind.DEVICE && it.state != ModelChoices.State.NOT_INSTALLED })
                    Text("No model is installed on this phone yet.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
            val online = choices.filter { it.kind == ModelChoices.Kind.ONLINE }
            ModelGroup("Online · your own keys", online, busy, vm) {
                if (!inputs.onlineAllowed) Text("Online AI is off in Privacy, so only this phone's models can answer.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                else if (online.isEmpty()) Text("No provider is set up. Add a free key in AI providers to use online models.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
            FlowRow2 {
                TextButton({ onClose(); vm.go(AppViewModel.Route.MODELS) }) { Text("Manage models") }
                TextButton({ onClose(); vm.go(AppViewModel.Route.PROVIDERS) }) { Text("AI providers") }
                TextButton(onClose) { Text("Close") }
            }
            Spacer(Modifier.height(Dimens.small))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.tiny)) { content() }

@Composable
private fun ModelGroup(title: String, items: List<ModelChoices.Choice>, busy: Boolean, vm: AppViewModel, onGet: (() -> Unit)? = null, footer: @Composable ColumnScope.() -> Unit = {}) {
    SectionTitle(title)
    Glass(padding = 4) {
        items.forEach { c -> ModelRow(c, enabled = !busy, onGet = onGet) { vm.pickModel(c.key) } }
        Column(Modifier.padding(horizontal = 12.dp)) { footer() }
    }
}

@Composable
private fun ModelRow(c: ModelChoices.Choice, enabled: Boolean, onGet: (() -> Unit)? = null, onPick: () -> Unit) {
    val selected = c.state == ModelChoices.State.ACTIVE
    val can = c.selectable && enabled
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).clip(RoundedCornerShape(Dimens.fieldRadius))
        .selectable(selected, enabled = can, role = Role.RadioButton, onClick = onPick)
        .semantics { stateDescription = when (c.state) { ModelChoices.State.ACTIVE -> "Selected"; ModelChoices.State.AVAILABLE -> "Available"
            ModelChoices.State.NOT_INSTALLED -> "Not installed"; ModelChoices.State.UNAVAILABLE -> "Unavailable" } }
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = c.selectable, modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(Dimens.small))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (c.selectable) MaterialTheme.colorScheme.onSurface else LocalExtra.current.muted)
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            c.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (c.state == ModelChoices.State.NOT_INSTALLED) LocalExtra.current.muted else LocalExtra.current.warn) }
        }
        if (selected) Pill("In use", LocalExtra.current.ok)
        // 4.6.2: a catalog model that isn't on the phone yet: one tap to AI models (the download is confirmed there, never here)
        if (c.state == ModelChoices.State.NOT_INSTALLED && onGet != null) TextButton(onGet) { Text("Get it") }
    }
}

// ====================================================================== 4.5 Home (bento dashboard)

/** Everything Home shows, read from the existing stores (no invented numbers). */
private data class HomeData(val recent: List<ChatDb.Conversation> = emptyList(), val dueCards: Int = 0, val decks: Int = 0, val loaded: Boolean = false,
                            // 5.3 command center (all from records on this phone)
                            val projects: List<Pair<ai.abhify.android.chat.Workspace, String>> = emptyList(),
                            val plans: List<ai.abhify.android.learn.StudyInsights.PlanStat> = emptyList(),
                            val docs: Int = 0, val passages: Int = 0, val vectors: Int = 0,
                            val news: List<ai.abhify.android.news.NewsArticle> = emptyList(), val newsAt: Long = 0)

/**
 * 4.6 Home ("Aurora"): a hero with the living AI orb, greeting and the Ask box; "Your spaces", a colourful grid of the
 * six areas, each with one real fact; then continue/recent chats, study progress, the phone's AI, saved prompts and Drive.
 * Every number comes from the stores on this phone; empty areas say what to do next.
 */
/** 6.1: the last Home snapshot, so returning to Home shows real numbers at once instead of flashing empty placeholders. */
private var lastHomeData: HomeData? = null

@Composable
fun HomeTab(vm: AppViewModel, onModels: () -> Unit) {
    var data by remember { mutableStateOf(lastHomeData ?: HomeData()) }
    LaunchedEffect(Unit) {
        vm.loadPrompts(); vm.studio.loadDocs(); vm.refreshProgress(); vm.life.loadCareer(); vm.life.loadVault(); vm.loadMemories(); vm.companion.load()
        data = withContext(Dispatchers.IO) {
            val recent = runCatching { vm.g.db.conversationPage(false, null, null, 3, 0) }.getOrDefault(emptyList())
            val decks = runCatching { vm.g.db.deckStats() }.getOrDefault(emptyList())
            val projects = runCatching { vm.g.db.workspaces().filter { !vm.projects.isArchived(it.id) }.map { w -> val st = vm.projects.meta(w.id).status
                w to (st.next.firstOrNull()?.let { "Next: $it" } ?: st.current.ifBlank { null } ?: "No status recorded yet") }
                .sortedByDescending { vm.projects.meta(it.first.id).status.updatedAt }.take(3) }.getOrDefault(emptyList())
            val plans = runCatching { vm.g.db.planStats().filter { it.total > 0 && it.done < it.total } }.getOrDefault(emptyList())
            val docs = runCatching { vm.g.db.allDocs().size }.getOrDefault(0)
            val passages = runCatching { vm.g.db.chunkCounts().values.sum() }.getOrDefault(0)
            val vectors = runCatching { vm.g.db.vectorCounts(ai.abhify.android.docs.EmbeddingManager.ID).values.sum() }.getOrDefault(0)
            // recent news = the last saved copy (never fetched here, never shown as breaking): its own retrieval time is shown
            val n = runCatching { vm.g.newsCenter.cached(ai.abhify.android.news.NewsQuery.cacheKey(vm.newsCenter.category.value, vm.newsCenter.prefs.value)) }.getOrNull()
            HomeData(recent, decks.sumOf { it.due }, decks.size, true, projects, plans, docs, passages, vectors, n?.articles.orEmpty().take(3), n?.retrievedAt ?: 0)
        }
        lastHomeData = data
    }
    PageBody(gap = Dimens.gap) {
        HeroCard(vm, onModels)
        TodayCard(vm)                                                     // next build: today's plan from real to-dos + briefing
        // 5.2 Continue working: where you left off (last chat, project, unfinished AI task, study plan), from records on this phone
        if (vm.companion.continueEnabled.value && vm.companion.continueItems.isNotEmpty()) BentoCard("🕘", "Continue working", accent = Accent.STUDY) {
            vm.companion.continueItems.toList().forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Dimens.fieldRadius)).clickable(onClickLabel = "Continue ${c.title}") { vm.companion.openContinue(c) }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.kind.glyph, Modifier.padding(end = 10.dp).clearAndSetSemantics { })
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = LocalExtra.current.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        // 5.1 Proactive Companion: from your own records only; dismiss each, or turn off in Settings
        if (vm.companion.enabled.value && vm.companion.items.isNotEmpty()) BentoCard("💡", "Suggestions", accent = Accent.CHAT) {
            vm.companion.items.toList().forEach { s ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(Dimens.fieldRadius)).clickable { vm.companion.open(s) }.padding(vertical = 4.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.reason, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ vm.companion.open(s) }) { Text(s.action) }
                    TextButton({ vm.companion.dismiss(s) }, Modifier.semantics { contentDescription = "Dismiss suggestion: ${s.title}" }) { Text("✕") }
                }
            }
        }
        SectionTitle("Your spaces")
        val last = data.recent.firstOrNull()
        val tiles = listOf(
            Triple(Accent.CHAT, "💬", "Chat") to (last?.title ?: "Start a conversation") to { if (last != null) { vm.loadConversation(last.id) }; vm.tab.value = AppViewModel.Tab.CHAT },
            Triple(Accent.STUDY, "🎓", "Study") to (when { data.dueCards > 0 -> "${data.dueCards} cards due"; data.decks > 0 -> "All cards reviewed"; else -> "Tutor, plans, cards, quizzes" }) to { vm.tab.value = AppViewModel.Tab.STUDY },
            Triple(Accent.DOCS, "📚", "Documents") to (if (vm.studio.docs.isEmpty()) "Add a PDF, Word file or photo" else "${vm.studio.docs.size} in your library") to { vm.tab.value = AppViewModel.Tab.DOCS },
            Triple(Accent.CAREER, "💼", "Career") to (if (vm.life.apps.isEmpty()) "Resumes, jobs, interview practice" else "${vm.life.apps.size} application" + (if (vm.life.apps.size == 1) "" else "s") + " tracked") to { vm.tab.value = AppViewModel.Tab.CAREER },
            Triple(Accent.KNOWLEDGE, "🔖", "Knowledge") to (if (vm.life.vault.isEmpty()) "Save answers and notes here" else "${vm.life.vault.size} saved item" + (if (vm.life.vault.size == 1) "" else "s")) to { vm.tab.value = AppViewModel.Tab.KNOWLEDGE },
            Triple(Accent.MEMORY, "🧠", "Memory") to (if (!vm.memoriesEnabled.value) "Personal memory is off" else if (vm.memories.isEmpty()) "Nothing remembered yet" else "${vm.memories.size} memor" + (if (vm.memories.size == 1) "y" else "ies")) to { vm.go(AppViewModel.Route.MEMORIES) },
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                row.forEach { (head, go) -> val (meta, sub) = head; val (accent, glyph, title) = meta
                    FeatureTile(accent, glyph, title, if (data.loaded) sub else "Loading…", Modifier.weight(1f).fillMaxHeight(), onClick = go) }   // 6.1: no fake "empty" on first load
            }
        }
        // 5.3 command center: active projects, learning, knowledge status and the last saved news (all real records)
        if (data.projects.isNotEmpty()) BentoCard("🗂", "Active projects", action = "All", accent = Accent.KNOWLEDGE, onClick = { vm.projects.openList() }) {
            data.projects.forEach { (w, line) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Dimens.fieldRadius))
                    .clickable { vm.projects.openList(); vm.projects.openProject(w) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = LocalExtra.current.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            AccentCard(Accent.STUDY, Modifier.weight(1f).fillMaxHeight(), onClick = { vm.tab.value = AppViewModel.Tab.STUDY; vm.learnMode.value = if (data.plans.isEmpty()) 7 else 2 }) {
                Text("Learning", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                val p = data.plans.maxByOrNull { it.lastActivity }
                Text(p?.topic ?: "No plan in progress", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p?.let { "${it.done} of ${it.total} lessons" + (if (data.plans.size > 1) " · ${data.plans.size} plans" else "") } ?: "Discover a topic in Study",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                if (p != null) LinearProgressIndicator({ p.done / p.total.toFloat() }, Modifier.fillMaxWidth())
            }
            AccentCard(Accent.KNOWLEDGE, Modifier.weight(1f).fillMaxHeight(), onClick = { vm.go(AppViewModel.Route.SEMANTIC) }) {
                Text("Knowledge", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                Text("${data.docs} document" + (if (data.docs == 1) "" else "s"), style = MaterialTheme.typography.titleSmall)
                Text(when { data.docs == 0 -> "Add documents to ask about them"; data.vectors > 0 -> "${data.passages} passages · semantic search on"
                    else -> "${data.passages} passages · keyword search" }, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2)
                val pending = vm.knowledge.updates.count { it.status == ai.abhify.android.memory.KnowledgeUpdates.Status.PENDING }
                if (pending > 0) Text("$pending correction(s) to review", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.warn)
            }
        }
        if (data.news.isNotEmpty()) BentoCard("📰", "News · saved ${ago(data.newsAt)}", action = "Open", accent = Accent.DOCS, onClick = { vm.tab.value = AppViewModel.Tab.NEWS }) {
            data.news.forEach { a ->
                Text("• ${a.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.publisher + (if (a.publishedAt > 0) " · published ${ago(a.publishedAt)}" else ""), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
        }
        // AI Task Runner entry (Home): multi-step workflows without another bottom-bar item
        BentoCard("✅", "AI Task Runner", action = "Open", accent = Accent.CHAT, onClick = { vm.go(AppViewModel.Route.TASKS) }) {
            Text(vm.tasks.tasks.firstOrNull()?.let { "Last: ${it.title} · ${it.status.name.lowercase()}" } ?: "Summarize a document, make a study plan or quiz, or brief the news, step by step",
                style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (data.recent.isNotEmpty()) BentoCard("🕘", "Recent chats", onClick = { vm.go(AppViewModel.Route.HISTORY) }, action = "History") {
            data.recent.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Dimens.fieldRadius))
                    .clickable { vm.loadConversation(c.id); vm.tab.value = AppViewModel.Tab.CHAT }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ago(c.updatedAt), style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
                }
            }
        }
        // progress + the phone's AI, side by side
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            val p = vm.progress.value
            AccentCard(Accent.STUDY, Modifier.weight(1f).fillMaxHeight(), onClick = { vm.tab.value = AppViewModel.Tab.STUDY; vm.learnMode.value = 4 }) {
                Text("Quiz score", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(if (p.answered > 0) p.correct / p.answered.toFloat() else 0f, Accent.STUDY, 56,
                        if (p.answered > 0) "${p.correct} of ${p.answered} correct" else "No quiz answers yet")
                    Spacer(Modifier.width(10.dp))
                    Column { Text("${p.correct}/${p.answered}", style = MaterialTheme.typography.titleMedium)
                        Text(if (p.streakDays > 1) "${p.streakDays}-day streak" else "correct", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
                }
            }
            val m = vm.activeModel()
            val e = vm.engine.value
            AccentCard(Accent.SYSTEM, Modifier.weight(1f).fillMaxHeight(), onClick = onModels) {
                Text("Phone AI", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiOrb(if (m == null) OrbState.ERROR else orbStateOf(vm), 36); Spacer(Modifier.width(8.dp))
                    Text(m?.name ?: "Not installed", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(when { m == null -> "Download one in AI models"; e is AppViewModel.Engine.Ready -> "Ready · offline"; e is AppViewModel.Engine.Loading -> "Loading…"
                    e is AppViewModel.Engine.Error -> "Could not start"; else -> "Starts on first question" }, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            }
        }
        if (vm.prompts.isNotEmpty()) BentoCard("📋", "Saved prompts", accent = Accent.MEMORY) {
            vm.prompts.take(3).forEach { p ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Dimens.fieldRadius))
                    .clickable { vm.usePrompt(p); vm.tab.value = AppViewModel.Tab.CHAT }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = LocalExtra.current.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (vm.drive.connected.value) BentoCard("☁", "Google Drive", accent = Accent.DOCS, onClick = { vm.go(AppViewModel.Route.DRIVE) }) {
            Text("Connected · ${vm.drive.account.value?.label ?: "your Google account"}", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
            Shortcut("🔎", "Search", Modifier.weight(1f)) { vm.go(AppViewModel.Route.SEARCH) }
            Shortcut("🔍", "Discover", Modifier.weight(1f)) { vm.tab.value = AppViewModel.Tab.STUDY; vm.learnMode.value = 7 }
            Shortcut("🕘", "History", Modifier.weight(1f)) { vm.go(AppViewModel.Route.HISTORY) }
            Shortcut("📦", "Models", Modifier.weight(1f)) { vm.go(AppViewModel.Route.MODELS) }
        }
    }
}

/** The deep aurora behind the hero: violet → indigo → azure (white text >= 6:1 everywhere on it). */
private val HeroBrush = Brush.linearGradient(listOf(Color(0xFF5B3BF0), Color(0xFF4338CA), Color(0xFF1D4ED8)))

/** Hero: the orb (real state), greeting, the Ask box (sends a new chat) and the model chip. */
@Composable
private fun HeroCard(vm: AppViewModel, onModels: () -> Unit) {
    var q by remember { mutableStateOf("") }
    fun go() { val t = q.trim(); if (t.isEmpty() || vm.generating.value) return; vm.newChat(); vm.send(t); q = ""; vm.tab.value = AppViewModel.Tab.CHAT }
    val hour = remember { java.time.LocalTime.now().hour }
    val hello = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val state = orbStateOf(vm)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(HeroBrush)
        .drawBehind {                                                            // two soft lights inside the hero (static)
            drawCircle(Brush.radialGradient(listOf(Brand.Mint.copy(alpha = 0.35f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.95f, 0f), radius = size.width * 0.55f))
            drawCircle(Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, size.height), radius = size.width * 0.5f))
        }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(state, 60)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (vm.g.userName.isNotBlank()) "$hello, ${vm.g.userName}" else hello, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    modifier = Modifier.semantics { heading() })
                Text(remember { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")) } + " · " + state.label,
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.composerRadius)).background(Color.White).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Ask Abhify.AI anything…", color = Color(0xFF5A5C7A)) },
                maxLines = 4, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF13142B)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { go() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = Brand.Violet,
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
            val can = q.isNotBlank() && !vm.generating.value
            Box(Modifier.size(Dimens.touch).clip(CircleShape).background(if (can) Brand.Accent else androidx.compose.ui.graphics.SolidColor(Color(0xFFE6E3F7)), CircleShape)
                .clickable(enabled = can, onClickLabel = "Send", onClick = ::go).semantics { contentDescription = "Send"; role = Role.Button }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_send), null, tint = if (can) Color.White else Color(0xFF5A5C7A), modifier = Modifier.size(20.dp))
            }
        }
        ModelChip(vm, onDark = true, onOpen = onModels)
    }
}

/** One bento tile: icon + label header, then content. */
@Composable
fun BentoCard(glyph: String, label: String, modifier: Modifier = Modifier, action: String? = null, accent: Accent = Accent.CHAT, onClick: (() -> Unit)? = null,
              content: @Composable ColumnScope.() -> Unit) {
    AccentCard(accent, modifier, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(accent, glyph, 32)
            Spacer(Modifier.width(Dimens.small))
            Text(label, style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted, modifier = Modifier.weight(1f).semantics { heading() })
            action?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = accent.text) }
        }
        content()
    }
}

@Composable
private fun Shortcut(glyph: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(Brand.Radius)).background(LocalExtra.current.glass).border(1.dp, LocalExtra.current.line, RoundedCornerShape(Brand.Radius))
        .clickable(onClickLabel = label, onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlyphIcon(glyph, 24, MaterialTheme.colorScheme.tertiary)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", else the date. */
fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 7 * 24 * 60 -> "${m / (24 * 60)} days ago".replace("1 days", "1 day")
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
