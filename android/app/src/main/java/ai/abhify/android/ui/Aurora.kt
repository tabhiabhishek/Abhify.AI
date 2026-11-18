package ai.abhify.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * 4.6 "Aurora" interaction layer: the AI orb (the assistant's identity), press feedback, feature tiles, the animated
 * bottom navigation and page transitions. Every animation uses the Motion tokens and turns off with reduced motion;
 * infinite animations only exist while their composable is on screen.
 */

/** What the assistant is doing right now: always derived from real app state, never decorative. */
/** 5.3 neural core states. Each one is set only by real activity (never simulated). GENERATING = "Responding". */
enum class OrbState(val label: String) { IDLE("Ready"), LISTENING("Listening"), PROCESSING("Processing what you said"), SEARCHING("Searching your knowledge"),
    THINKING("Thinking"), GENERATING("Responding"), SPEAKING("Speaking"), INTERRUPTED("Interrupted"), OFFLINE("Offline: no AI can answer right now"), ERROR("Needs attention") }

/** The assistant's state from the view model: voice, generation (no text yet = thinking) and the phone model's status. */
fun orbStateOf(vm: AppViewModel): OrbState = when {
    vm.listening.value -> OrbState.LISTENING
    vm.voiceState.interrupted.value -> OrbState.INTERRUPTED                        // 5.4: the user just interrupted speech
    vm.voiceState.processing.value -> OrbState.PROCESSING                          // words received, deciding what to do
    vm.searching.value -> OrbState.SEARCHING                                       // retrieval over your documents/knowledge is running
    vm.generating.value && vm.messages.lastOrNull()?.text.isNullOrEmpty() -> OrbState.THINKING
    vm.generating.value -> OrbState.GENERATING
    vm.speaking.value -> OrbState.SPEAKING                                         // text-to-speech is playing
    vm.voiceState.active.value && vm.voiceError.value != null -> OrbState.ERROR    // 5.6: a real voice failure (mic, recogniser, TTS) shows as such
    vm.engine.value is AppViewModel.Engine.Error && vm.aiMode.value == ai.abhify.android.ai.AiMode.DEVICE -> OrbState.ERROR
    // offline: the network is down and nothing on the phone can answer (Online mode, or no phone model)
    !vm.online.value && (vm.aiMode.value == ai.abhify.android.ai.AiMode.ONLINE || vm.activeModel() == null) -> OrbState.OFFLINE
    else -> OrbState.IDLE
}

/**
 * The Abhify.AI orb: an aurora sphere (violet → azure → mint) with a soft halo.
 * Idle: slow breathing. Listening: an expanding ring. Thinking: the light swirls fast. Generating: it swirls slowly.
 * Error: coral and still. Reduced motion: still in every state (the state is also in its spoken description).
 */
@Composable
fun AiOrb(state: OrbState, size: Int = 48, modifier: Modifier = Modifier, still: Boolean = false) {
    val reduced = reducedMotion()
    val animate = !reduced && !still && state != OrbState.ERROR && state != OrbState.OFFLINE      // static fallback
    val t = rememberInfiniteTransition(label = "orb")
    val breath by if (animate) t.animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.BREATH, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        else remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    val spinMs = when (state) { OrbState.THINKING, OrbState.PROCESSING -> 1400; OrbState.SEARCHING -> 2200; OrbState.GENERATING, OrbState.SPEAKING -> 3200; else -> 9000 }
    val spin by if (animate) t.animateFloat(0f, 360f, infiniteRepeatable(tween(spinMs, easing = LinearEasing)), label = "spin")
        else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val colors = when (state) {
        OrbState.ERROR -> listOf(Color(0xFFFF7A6B), Color(0xFFF43F5E), Color(0xFFFF9A8C), Color(0xFFFF7A6B))
        OrbState.OFFLINE -> listOf(Color(0xFF6B7280), Color(0xFF4B5563), Color(0xFF9CA3AF), Color(0xFF6B7280))
        else -> listOf(Brand.Violet, Color(0xFF4F7BFF), Brand.Cyan, Color(0xFFA855F7), Brand.Violet)   // 5.6 neural core: violet ↔ electric cyan
    }
    Canvas(modifier.size(size.dp).semantics { contentDescription = "Abhify.AI: ${state.label}" }) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val halo = 0.80f + 0.12f * breath
        drawCircle(Brush.radialGradient(listOf(colors[0].copy(alpha = 0.35f), Color.Transparent), c, r), r, c)        // halo
        rotate(spin, c) { drawCircle(Brush.sweepGradient(colors, c), r * halo * 0.78f, c) }                              // aurora body
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), c + Offset(-r * 0.22f, -r * 0.25f), r * 0.45f),
            r * halo * 0.78f, c)                                                                                          // glossy light
        // 5.3 neural layer: three nodes on an inner ring joined by faint links (rotates with the core; still when static)
        val nodes = (0 until 3).map { i -> val a = Math.toRadians((spin * 0.5f + i * 120f).toDouble())
            c + Offset((kotlin.math.cos(a) * r * 0.42f).toFloat(), (kotlin.math.sin(a) * r * 0.42f).toFloat()) }
        val link = Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.18f else 0.32f)
        for (i in nodes.indices) drawLine(link, nodes[i], nodes[(i + 1) % nodes.size], strokeWidth = (size / 48f).coerceAtLeast(0.6f).dp.toPx())
        nodes.forEach { drawCircle(Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.35f else 0.75f), r * 0.07f, it) }
        if (state == OrbState.SPEAKING || state == OrbState.INTERRUPTED) {           // speaking: a soft outer pulse; interrupted: a still ring
            val k = if (animate && state == OrbState.SPEAKING) breath else 0.4f
            drawCircle(Brand.Violet.copy(alpha = 0.5f * (1f - k)), r * (0.84f + 0.14f * k), c, style = Stroke(width = 2.dp.toPx()))
        }
        if (state == OrbState.LISTENING) {
            val k = if (animate) breath else 0.5f
            drawCircle(Brand.Mint.copy(alpha = 0.6f * (1f - k)), r * (0.82f + 0.18f * k), c, style = Stroke(width = 2.dp.toPx()))
        }
        if (state == OrbState.SEARCHING) {                                                // a cyan scan sweeping the core
            drawArc(Brand.Mint.copy(alpha = 0.85f), spin * 2f, 70f, false, Offset(c.x - r * 0.9f, c.y - r * 0.9f),
                androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f), style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** Press feedback for anything clickable: a quick scale to 97 % while held (instant with reduced motion). */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val reduced = reducedMotion()
    val k by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, Motion.tween(Motion.FAST, reduced), label = "press")
    this.scale(k)
}

/**
 * 6.1 message appear: a new chat message fades in and rises 12dp once (Motion.BASE, standard easing). [animate] is decided
 * by the caller when the item is first composed, so scrolling back to old messages never replays it; off with reduced motion.
 */
fun Modifier.messageAppear(animate: Boolean): Modifier = composed {
    val reduced = reducedMotion()
    val a = remember { androidx.compose.animation.core.Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) { if (a.value < 1f) a.animateTo(1f, Motion.tween(Motion.BASE, reduced)) }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * rise }
}

/** A rounded gradient icon tile in a feature's colours (white icon). */
@Composable
fun AccentIcon(accent: Accent, glyph: String, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(accent.brush).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, (size * 0.52f).toInt(), Color.White)
    }
}

/**
 * A feature tile for Home: gradient icon, title, one honest line from the real data, a soft wash of the feature colour.
 * Presses scale; the whole tile is one button for TalkBack.
 */
@Composable
fun FeatureTile(accent: Accent, glyph: String, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val x = LocalExtra.current
    Column(modifier.pressScale(src).clip(RoundedCornerShape(Brand.Radius))
        .background(Brush.linearGradient(listOf(accent.g1.copy(alpha = if (x.dark) 0.22f else 0.10f), x.glass, x.glass)))
        .background(Color.Transparent)
        .clickable(src, indication = androidx.compose.material3.ripple(), onClickLabel = title, onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.Button }
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentIcon(accent, glyph, 38)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The colour identity of each bottom-bar tab (Home uses the brand aurora). */
fun accentOf(t: AppViewModel.Tab): Accent = when (t) {
    AppViewModel.Tab.HOME, AppViewModel.Tab.CHAT -> Accent.CHAT
    AppViewModel.Tab.STUDY -> Accent.STUDY
    AppViewModel.Tab.DOCS, AppViewModel.Tab.NEWS -> Accent.DOCS
    AppViewModel.Tab.CAREER -> Accent.CAREER
    AppViewModel.Tab.KNOWLEDGE -> Accent.KNOWLEDGE
    AppViewModel.Tab.MORE, AppViewModel.Tab.SETTINGS -> Accent.SYSTEM
}

/**
 * 4.6 bottom navigation: a floating aurora bar. The selected tab grows a pill in its own feature colour (animated colour
 * and width) with a white icon; others stay quiet. Labels always shown; 64 dp tall like Material's bar.
 */
@Composable
fun AuroraNavBar(selected: AppViewModel.Tab, items: List<AppViewModel.Tab>, icon: (AppViewModel.Tab) -> Int, onPick: (AppViewModel.Tab) -> Unit) {
    val x = LocalExtra.current
    val reduced = reducedMotion()
    Row(Modifier.fillMaxWidth().background(x.glassStrong).navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { t ->
            val on = selected == t
            val a = accentOf(t)
            val pill by animateColorAsState(if (on) a.g1 else Color.Transparent, Motion.tween(Motion.BASE, reduced), label = "pill")
            val tint by animateColorAsState(if (on) Color.White else x.muted, Motion.tween(Motion.BASE, reduced), label = "tint")
            val w by animateFloatAsState(if (on) 60f else 44f, Motion.tween(Motion.BASE, reduced, Motion.Emphasized), label = "w")
            Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = t.label) { onPick(t) }.semantics { this.selected = on; role = Role.Tab },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Spacer(Modifier.height(2.dp))
                Box(Modifier.width(w.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(pill), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon(t)), null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (on) a.text else x.muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** Page change: fade with a short horizontal glide in the direction of travel (just a fade with reduced motion). */
@Composable
fun <T : Enum<T>> PageTransition(target: T, content: @Composable (T) -> Unit) {
    val reduced = reducedMotion()
    AnimatedContent(target, transitionSpec = {
        if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        else {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(tween(Motion.EMPHASIS, easing = Motion.Emphasized)) + slideInHorizontally(tween(Motion.EMPHASIS, easing = Motion.Emphasized)) { it / 12 * dir }) togetherWith
                (fadeOut(tween(Motion.FAST)) + slideOutHorizontally(tween(Motion.FAST)) { -it / 24 * dir })
        }
    }, label = "page") { content(it) }
}

/** A card with a soft wash of a feature colour across the top (section highlight cards). */
@Composable
fun AccentCard(accent: Accent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val x = LocalExtra.current
    val src = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(src) else it }.clip(RoundedCornerShape(Brand.Radius))
        .background(Brush.verticalGradient(listOf(accent.g1.copy(alpha = if (x.dark) 0.20f else 0.09f), x.glass)))
        .let { if (onClick != null) it.clickable(src, indication = androidx.compose.material3.ripple(), onClick = onClick) else it }
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(Dimens.cardGap), content = content)
}

/** A progress ring with an animated sweep (study progress, quiz score). [value] 0..1. */
@Composable
fun ProgressRing(value: Float, accent: Accent, size: Int = 56, label: String) {
    val reduced = reducedMotion()
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Motion.tween(700, reduced, Motion.Emphasized), label = "ring")
    val track = LocalExtra.current.line
    Box(Modifier.size(size.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val st = Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = st, topLeft = Offset(st.width / 2, st.width / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - st.width, this.size.height - st.width))
            drawArc(accent.brush, -90f, 360f * v, false, style = st, topLeft = Offset(st.width / 2, st.width / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - st.width, this.size.height - st.width))
        }
        Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** One destination in the brand launcher: an existing tab or route (nothing new is created). */
private data class LaunchItem(val glyph: String, val label: String, val accent: Accent, val tab: AppViewModel.Tab? = null, val route: AppViewModel.Route? = null)

/**
 * Unified workspace: tapping the Abhify logo opens every destination, grouped like the desktop launcher
 * (Workspace / AI System / Application). Each entry opens the screen that already exists; opening the sheet itself
 * starts nothing (no model load, no request) and the bottom bar and More stay as they are.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrandLauncherSheet(vm: AppViewModel, onClose: () -> Unit) {
    val groups = listOf(
        "Workspace" to listOf(LaunchItem("🏠", "Home", Accent.CHAT, tab = AppViewModel.Tab.HOME), LaunchItem("💬", "Chat", Accent.CHAT, tab = AppViewModel.Tab.CHAT),
            LaunchItem("✅", "AI Task Runner", Accent.CHAT, route = AppViewModel.Route.TASKS), LaunchItem("🗂", "Projects", Accent.KNOWLEDGE, route = AppViewModel.Route.PROJECTS),
            LaunchItem("🎓", "Study", Accent.STUDY, tab = AppViewModel.Tab.STUDY),
            LaunchItem("📰", "News", Accent.DOCS, tab = AppViewModel.Tab.NEWS), LaunchItem("📚", "Documents", Accent.DOCS, tab = AppViewModel.Tab.DOCS),
            LaunchItem("🔖", "Knowledge", Accent.KNOWLEDGE, tab = AppViewModel.Tab.KNOWLEDGE), LaunchItem("🧠", "Memory", Accent.MEMORY, route = AppViewModel.Route.MEMORIES)),
        "AI System" to listOf(LaunchItem("📦", "AI Models", Accent.SYSTEM, route = AppViewModel.Route.MODELS),
            LaunchItem("📈", "Performance (in AI models)", Accent.STUDY, route = AppViewModel.Route.MODELS),
            LaunchItem("🖥", "System (in Settings)", Accent.SYSTEM, route = AppViewModel.Route.SETTINGS)),
        "Application" to listOf(LaunchItem("🎛", "Settings", Accent.SYSTEM, route = AppViewModel.Route.SETTINGS),
            LaunchItem("⋯", "More", Accent.SYSTEM, tab = AppViewModel.Tab.MORE),
            LaunchItem("👤", "Account", Accent.SYSTEM, route = AppViewModel.Route.ACCOUNT), LaunchItem("ⓘ", "About", Accent.CHAT, route = AppViewModel.Route.ABOUT)),
    )
    val currentTab = vm.tab.value
    androidx.compose.material3.ModalBottomSheet(onClose, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalExtra.current.glassStrong, shape = RoundedCornerShape(topStart = Dimens.dialogRadius, topEnd = Dimens.dialogRadius)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiOrb(orbStateOf(vm), 36, still = true); Spacer(Modifier.width(10.dp))
                Column { Text("Abhify.AI", style = MaterialTheme.typography.titleMedium)
                    Text(ai.abhify.android.ai.ModelChoices.chipLabel(vm.modelInputs()), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
            }
            groups.forEach { (name, items) ->
                Text(name.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp))
                items.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { it ->
                            val on = (it.tab != null && it.tab == currentTab && vm.stack.size == 1)
                            Row(Modifier.weight(1f).heightIn(min = Dimens.touch).clip(RoundedCornerShape(14.dp))
                                .background(if (on) it.accent.g1.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable(onClickLabel = it.label) {
                                    onClose()
                                    if (it.tab != null) { vm.home(); vm.tab.value = it.tab } else it.route?.let(vm::go)
                                }.semantics { this.selected = on; role = Role.Button }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AccentIcon(it.accent, it.glyph, 30); Spacer(Modifier.width(10.dp))
                                Text(it.label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(Dimens.gap))
        }
    }
}

