package ai.abhify.android.ui

import ai.abhify.android.R
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4.6 "Aurora" brand tokens, shared with the desktop/web app (frontend/styles.css :root).
 * A deep-indigo night (dark) or warm lavender-white (light) lit by soft aurora light; electric violet is the brand,
 * and every feature has its own hue (see [Accent]). Names are kept from 4.3 so every screen follows; values are
 * WCAG-checked in ContrastTest.
 */
object Brand {
    // 7.0 "Synapse": obsidian layers, violet intelligence, electric-cyan live data (shared with frontend/styles.css :root)
    val Bg1 = Color(0xFF06070D)          // obsidian: dark background
    val Bg2 = Color(0xFF161A33)          // raised indigo-charcoal (dialogs, sheets, composer)
    val Surface = Color(0xFF0E1020)      // cards
    val Glass = Color(0xE60E1020)        // cards over the ambient light: 90 % surface, the light shows through slightly
    val GlassStrong = Bg2
    val Line = Color(0xFF2C3052)         // violet-tinted hairline
    val Text = Color(0xFFEEF0FF)
    val Muted = Color(0xFFAEB4D6)
    val Violet = Color(0xFF7350F5)       // brand fill: primary buttons, selected chips (white text 5.0:1)
    val Violet2 = Color(0xFFB9A3FF)      // brand-coloured text on obsidian (>= 4.5:1); use colorScheme.tertiary outside this file
    val Synapse = Color(0xFF9D7BFF)      // 7.0 signature violet: the neural core, rings, gradients (not for small text on light)
    val CyanText = Color(0xFF8FEFFF)     // live-data text (source chips, "checked … ago")
    val Azure = Color(0xFF3B82F6)
    val Mint = Color(0xFF2DD4BF)
    val Coral = Color(0xFFFF7A6B)
    val Lavender = Color(0xFFC9B8FF)
    val Cyan = Color(0xFF3DE0FF)         // 7.0 electric cyan: live data, the core's outer ring, waveform
    val LightBg = Color(0xFFF6F5FF)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFFF8A8A)
    val Amber = Color(0xFFFBBF24)
    /** The brand gradient (violet → cyan), used sparingly: the launch ring, the send buttons. Icons only on it (no text). */
    val Accent = Brush.linearGradient(listOf(Synapse, Cyan))
    /** The deep aurora (violet → indigo → azure): surfaces with white text on them (your chat bubbles); >= 6:1 everywhere. */
    val Deep = Brush.linearGradient(listOf(Color(0xFF5B3BF0), Color(0xFF4338CA), Color(0xFF1D4ED8)))
    val Radius = 16.dp                   // cards (7.0 Synapse)
}

/**
 * 4.6 feature identities: each area has one hue, used for its icon tiles, section tabs, progress and highlights.
 * [dark]/[light] are text-safe on each theme's background; [g1]→[g2] is the tile gradient (white icons on top).
 */
enum class Accent(val dark: Color, val light: Color, val g1: Color, val g2: Color) {
    CHAT(Color(0xFFA594FF), Color(0xFF5536E8), Color(0xFF5B3BF0), Color(0xFF9333EA)),
    STUDY(Color(0xFF34D3B4), Color(0xFF0F766E), Color(0xFF0F766E), Color(0xFF0D9488)),
    DOCS(Color(0xFF7DB2FF), Color(0xFF1D5FD1), Color(0xFF1D4ED8), Color(0xFF0284C7)),
    CAREER(Color(0xFFFF9A8C), Color(0xFFB23A0B), Color(0xFFE11D48), Color(0xFFEA580C)),
    KNOWLEDGE(Color(0xFFFBBF24), Color(0xFF92400E), Color(0xFFB45309), Color(0xFFD97706)),
    MEMORY(Color(0xFFE99BFF), Color(0xFFA21CAF), Color(0xFFA21CAF), Color(0xFFC026D3)),
    SYSTEM(Color(0xFF9AA6FF), Color(0xFF4338CA), Color(0xFF4338CA), Color(0xFF0284C7));
    val brush get() = Brush.linearGradient(listOf(g1, g2))
}

/** The current theme's text-safe colour for this accent. */
val Accent.text: Color @Composable get() = if (LocalExtra.current.dark) dark else light

/**
 * Spacing and size tokens (4-pt scale): every page and card uses these instead of literal numbers, so all screens share one
 * rhythm. (dp values; text sizes come from MaterialTheme.typography and follow the user's font scale.)
 */
object Dimens {
    val gutter = 16.dp          // page side padding
    val gap = 12.dp             // between cards / sections on a page
    val cardPadding = 16.dp
    val cardGap = 8.dp          // between items inside a card
    val small = 8.dp
    val tiny = 4.dp
    val large = 24.dp
    val maxWidth = 680.dp       // readable line length on tablets / landscape
    val touch = 48.dp           // minimum touch target (Material / WCAG)
    val rowHeight = 56.dp       // navigation rows
    val fieldRadius = 12.dp
    val composerRadius = 20.dp
    val dialogRadius = 24.dp
}

/** 4.5 motion tokens. Every animation uses one of these; reducedMotion() turns them into instant changes. */
object Motion {
    const val FAST = 120          // press, toggles
    const val BASE = 220          // tab switch, chips, expand, navigation indicator
    const val EMPHASIS = 340      // dialogs, sheets, cards entering, page changes
    const val BREATH = 2600       // the AI orb's idle breathing period (only while visible, off with reduced motion)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    fun <T> tween(ms: Int, reduced: Boolean, easing: Easing = Standard) = androidx.compose.animation.core.tween<T>(if (reduced) 0 else ms, easing = easing)
}

/** True when the user turned animations off (Accessibility → Remove animations, or animator duration scale 0). */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Colours that Material's scheme has no slot for (card surface, hairlines, status, accent, aurora lights). */
data class Extra(val glass: Color, val glassStrong: Color, val line: Color, val muted: Color, val ok: Color, val warn: Color, val bad: Color,
                 val bg: Brush, val userBubble: Brush, val codeBg: Color, val accent: Color, val dark: Boolean = true,
                 val auroraA: Color = Color.Transparent, val auroraB: Color = Color.Transparent, val auroraC: Color = Color.Transparent)

val LocalExtra = staticCompositionLocalOf { darkExtra }

internal val darkExtraPublic get() = darkExtra
internal val lightExtraPublic get() = lightExtra
// text-safe status colours (WCAG AA on the card surfaces of each theme)
private val darkExtra = Extra(Brand.Glass, Brand.GlassStrong, Brand.Line, Brand.Muted, Brand.Green, Brand.Amber, Brand.Red,
    SolidColor(Brand.Bg1), Brand.Deep, Color(0xFF0A0B11), Brand.Cyan,
    // 7.0 Synapse: a violet glow top-left and a cyan glow low right, restrained so surfaces read as premium, not noisy
    auroraA = Brand.Synapse.copy(alpha = 0.16f), auroraB = Brand.Cyan.copy(alpha = 0.07f), auroraC = Brand.Cyan.copy(alpha = 0.06f))

private val lightExtra = Extra(Color(0xF2FFFFFF), Color.White, Color(0xFFE6E3F7), Color(0xFF5A5C7A), Color(0xFF047857), Color(0xFFA14A06), Color(0xFFB91C1C),
    SolidColor(Brand.LightBg), Brand.Deep, Color(0xFFEFEDFB), Color(0xFF0F766E), dark = false,
    auroraA = Brand.Violet.copy(alpha = 0.14f), auroraB = Brand.Azure.copy(alpha = 0.10f), auroraC = Color(0xFFFF7A6B).copy(alpha = 0.08f))

// Dark: Material uses primary as a text colour too (text buttons, links, checkboxes), so it is the lighter violet with dark
// text on it; the brand violet fills the primary action buttons (PrimaryButton) with white text.
private val dark: ColorScheme = darkColorScheme(
    primary = Brand.Violet2, onPrimary = Color(0xFF140E3D), primaryContainer = Color(0xFF2C2470), onPrimaryContainer = Color(0xFFE8E4FF),
    secondary = Brand.Lavender, onSecondary = Color(0xFF1E1240), secondaryContainer = Color(0xFF2C2470), onSecondaryContainer = Color(0xFFE8E4FF),
    tertiary = Brand.Violet2,
    background = Brand.Bg1, onBackground = Brand.Text, surface = Brand.Bg1, onSurface = Brand.Text,
    surfaceVariant = Brand.Bg2, onSurfaceVariant = Brand.Muted, surfaceContainerLowest = Color(0xFF090A13), surfaceContainerLow = Brand.Surface,
    surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.Bg2, surfaceContainerHighest = Color(0xFF1E2240),
    outline = Color(0xFF3A3F66), outlineVariant = Brand.Line,
    error = Brand.Red, onError = Color(0xFF2A0A0A), errorContainer = Color(0xFF3B1218), onErrorContainer = Color(0xFFFECACA),
)

private val light: ColorScheme = lightColorScheme(
    primary = Color(0xFF5A3BF0), onPrimary = Color.White, primaryContainer = Color(0xFFEAE5FF), onPrimaryContainer = Color(0xFF1F1470),
    secondary = Color(0xFF7C3AED), onSecondary = Color.White, secondaryContainer = Color(0xFFEDE9FE), onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF5536E8),
    background = Brand.LightBg, onBackground = Color(0xFF13142B), surface = Brand.LightBg, onSurface = Color(0xFF13142B),
    surfaceVariant = Color(0xFFEFEDFB), onSurfaceVariant = Color(0xFF5A5C7A), surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFEFEDFB),
    outline = Color(0xFFCAC6E4), outlineVariant = Color(0xFFE6E3F7), error = Color(0xFFB91C1C),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** 7.0 Synapse display face: headings, big numbers, the wordmark. */
@OptIn(ExperimentalTextApi::class)
val Grotesk = FontFamily(grotesk(500), grotesk(600), grotesk(700))

@OptIn(ExperimentalTextApi::class)
private fun mono(w: Int) = Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** 7.0 Synapse data face: anything factual (times, sources, model names, eyebrow labels) and code. */
@OptIn(ExperimentalTextApi::class)
val Mono = FontFamily(mono(400), mono(500), mono(600))

/** Eyebrow label: mono, uppercase by the caller, wide tracking (section labels on cards). */
val EyebrowStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight(500))
/** Metadata: source chips, timestamps, model names. */
val MetaStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(400))

private val type = Typography().let { t ->
    fun TextStyle.inter(size: Int, weight: Int, line: Int) = copy(fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp)
    fun TextStyle.grotesk(size: Int, weight: Int, line: Int) = copy(fontFamily = Grotesk, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = (-0.2).sp)
    Typography(
        // 4.5 scale: display 34 (launch, greeting), page title 22–24, section 18, card title 16, body 16, secondary 14, caption 13
        // 7.0 Synapse: Space Grotesk for display, headlines and titles; Inter for body and labels
        displaySmall = t.displaySmall.grotesk(34, 700, 40), headlineMedium = t.headlineMedium.grotesk(28, 700, 34),
        headlineSmall = t.headlineSmall.grotesk(24, 600, 30), titleLarge = t.titleLarge.grotesk(22, 600, 28),
        titleMedium = t.titleMedium.grotesk(18, 600, 24), titleSmall = t.titleSmall.inter(16, 600, 22),
        bodyLarge = t.bodyLarge.inter(16, 400, 24), bodyMedium = t.bodyMedium.inter(15, 400, 22), bodySmall = t.bodySmall.inter(14, 400, 20),
        labelLarge = t.labelLarge.inter(16, 600, 20), labelMedium = t.labelMedium.inter(14, 500, 18), labelSmall = t.labelSmall.inter(13, 500, 16),
    )
}

@Composable
fun AbhifyTheme(mode: String, content: @Composable () -> Unit) {
    val isDark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    CompositionLocalProvider(LocalExtra provides if (isDark) darkExtra else lightExtra) {
        MaterialTheme(colorScheme = if (isDark) dark else light, typography = type,
            shapes = Shapes(small = RoundedCornerShape(Dimens.fieldRadius), medium = RoundedCornerShape(Brand.Radius), large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(Dimens.dialogRadius)),
            content = content)
    }
}

/**
 * 4.6 Aurora background: the theme's base colour lit by three soft radial lights (violet top-left, azure top-right,
 * mint/coral low). Drawn once per size (drawWithCache), never animated, so it costs nothing while the AI works.
 * It also provides the theme's text colour: RC.3 drew on this Box without a Material Surface, so any Text without
 * an explicit colour fell back to LocalContentColor's default (black) — dark-on-dark text in the dark theme.
 */
@Composable
fun BrandBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val x = LocalExtra.current
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(modifier.fillMaxSize().background(x.bg).drawWithCache {
            val r = size.maxDimension
            val a = Brush.radialGradient(listOf(x.auroraA, Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.02f), radius = r * 0.55f)
            val b = Brush.radialGradient(listOf(x.auroraB, Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.95f, size.height * 0.12f), radius = r * 0.5f)
            val c = Brush.radialGradient(listOf(x.auroraC, Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 1.02f), radius = r * 0.6f)
            onDrawBehind { drawRect(a); drawRect(b); drawRect(c) }
        }, content = content)
    }
}

/** WCAG 2.x contrast ratio of [fg] drawn over [bg] (alpha composited), for tests and checks. */
fun contrast(fg: Color, bg: Color, base: Color = Brand.Bg1): Double {
    fun over(top: Color, under: Color) = Color(top.red * top.alpha + under.red * (1 - top.alpha), top.green * top.alpha + under.green * (1 - top.alpha),
        top.blue * top.alpha + under.blue * (1 - top.alpha), 1f)
    fun lum(c: Color): Double {
        fun ch(v: Float) = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    val b = over(bg, base); val f = over(fg, b)
    val (l1, l2) = lum(f) to lum(b)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}

/** The colour pairs every screen uses, so tests check the real tokens (dark and light). */
object ThemeTokens {
    val darkExtra get() = darkExtraPublic
    val lightExtra get() = lightExtraPublic
}

val glassBorder @Composable get() = BorderStroke(1.dp, LocalExtra.current.line)

/** Monospace text for code (blocks and inline), sized to stay readable on phones. */
val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp)

/** Syntax colours that stay readable on both code backgrounds (dark: #0A0C1C, light: #EFEDFB). */
data class CodeColors(val keyword: Color, val string: Color, val comment: Color, val number: Color, val function: Color)
val darkCode = CodeColors(Brand.Violet2, Color(0xFF86EFAC), Brand.Muted, Brand.Amber, Color(0xFF7DD3FC))
val lightCode = CodeColors(Color(0xFF6D28D9), Color(0xFF166534), Color(0xFF475569), Color(0xFF92400E), Color(0xFF0369A1))
