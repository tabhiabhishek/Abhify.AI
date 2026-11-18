package ai.abhify.android.ui

import ai.abhify.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * 4.5.1 page kit: the page-level building blocks every redesigned screen uses, so each page gets a clear header,
 * one search pattern, one section-tab pattern, stat tiles, grouped settings and honest loading/error banners.
 */

/** Page title with an optional one-line subtitle and trailing action (e.g. the page's primary button). */
@Composable
fun PageHeader(title: String, subtitle: String? = null, accent: Accent? = null, glyph: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Dimens.tiny), verticalAlignment = Alignment.CenterVertically) {
        if (accent != null && glyph != null) { AccentIcon(accent, glyph, 44); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted) }
        }
        action?.let { Row(verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}

/** The one search box: leading search icon, clear button, rounded like the composer. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(placeholder, color = LocalExtra.current.muted) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, tint = LocalExtra.current.muted, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (value.isNotEmpty()) ({ IconAction(R.drawable.ic_close, "Clear search", { onChange("") }) }) else null,
        shape = RoundedCornerShape(Dimens.composerRadius),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LocalExtra.current.line, focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = LocalExtra.current.glass, focusedContainerColor = LocalExtra.current.glass))
}

/**
 * Section tabs inside a page (Study, Career, Knowledge): a pill track with the selected section filled in brand violet,
 * an icon per section, scrollable when the names don't fit. Replaces the 4.x row of filter chips.
 */
@Composable
fun SegmentedTabs(names: List<String>, selected: Int, modifier: Modifier = Modifier, glyphs: List<String> = emptyList(), accent: Accent = Accent.CHAT, onPick: (Int) -> Unit) {
    val x = LocalExtra.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(50)).background(x.glass)
        .border(1.dp, x.line, RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        names.forEachIndexed { i, n ->
            val on = i == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) accent.g1 else Color.Transparent, Motion.tween(Motion.BASE, reducedMotion()), label = "seg")
            Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(bg)
                .clickable(onClickLabel = n) { onPick(i) }.semantics { this.selected = on; role = Role.Tab }
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                glyphs.getOrNull(i)?.let { GlyphIcon(it, 18, if (on) Color.White else x.muted); Spacer(Modifier.width(6.dp)) }
                Text(n, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** A number with its label (bento stat), e.g. "12 / cards due". */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, glyph: String? = null, tint: Color = MaterialTheme.colorScheme.tertiary, accent: Accent? = null) {
    val x = LocalExtra.current
    Column(modifier.clip(RoundedCornerShape(Brand.Radius))
        .background(if (accent != null) Brush.verticalGradient(listOf(accent.g1.copy(alpha = if (x.dark) 0.22f else 0.10f), x.glass)) else androidx.compose.ui.graphics.SolidColor(x.glass))
        .border(1.dp, x.line, RoundedCornerShape(Brand.Radius))
        .padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        glyph?.let { GlyphIcon(it, 20, tint) }
        Text(value, style = MaterialTheme.typography.titleLarge, color = accent?.text ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, maxLines = 2)
    }
}

/** A titled settings/section group: small caps title above one card of rows. */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, accent: Accent? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.tiny)) {
        title?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = Dimens.small).semantics { heading() }) }
        if (accent != null) androidx.compose.runtime.CompositionLocalProvider(LocalRowAccent provides accent) { Glass(padding = 12, content = content) }
        else Glass(padding = 12, content = content)
    }
}

/** A short explanatory note with an info icon (privacy and limits text; quieter than a full card). */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_info), null, tint = LocalExtra.current.muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
}

/** A small status label with a dot (never colour alone: the text says the state). level 0 ok, 1 warn, 2 bad, -1 neutral. */
@Composable
fun StatusTag(text: String, level: Int) {
    val x = LocalExtra.current
    val c = when (level) { 0 -> x.ok; 1 -> x.warn; 2 -> x.bad; else -> x.muted }
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(c, CircleShape)); Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1)
    }
}

/** Busy banner (with optional Cancel) and error banner (with optional Retry): the same look on every page. */
@Composable
fun BusyBanner(text: String, onCancel: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.fieldRadius)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        onCancel?.let { TextButton(it) { Text("Cancel") } }
    }
}

@Composable
fun ErrorBanner(text: String, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val x = LocalExtra.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.fieldRadius)).background(x.bad.copy(alpha = 0.10f))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_error_outline), null, tint = x.bad, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        onRetry?.let { TextButton(it) { Text("Try again") } }
        onDismiss?.let { TextButton(it) { Text("Dismiss") } }
    }
}

/** One action in an [ActionSheet]. */
data class SheetAction(val glyph: String, val label: String, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * A bottom sheet of grouped actions for one item (document, note, resume…): replaces rows of a dozen inline buttons.
 * Each action closes the sheet first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, subtitle: String? = null, groups: List<Pair<String?, List<SheetAction>>>, onClose: () -> Unit) {
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LocalExtra.current.glassStrong,
        shape = RoundedCornerShape(topStart = Dimens.dialogRadius, topEnd = Dimens.dialogRadius)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
            groups.forEach { (name, actions) ->
                name?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, modifier = Modifier.padding(top = Dimens.gap, bottom = 2.dp)) }
                actions.forEach { a ->
                    val c = if (a.danger) LocalExtra.current.bad else MaterialTheme.colorScheme.onSurface
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius))
                        .clickable(enabled = a.enabled, onClickLabel = a.label) { onClose(); a.onClick() }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clearAndSetSemantics { }) { GlyphIcon(a.glyph, 20, if (a.enabled) (if (a.danger) c else MaterialTheme.colorScheme.tertiary) else LocalExtra.current.muted) }
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.enabled) c else LocalExtra.current.muted)
                    }
                }
            }
            Spacer(Modifier.height(Dimens.gap))
        }
    }
}

/** A square icon tile for a file or item type (PDF, Word, image, text…). */
@Composable
fun TypeTile(kind: String, size: Int = 44) {
    // 4.6: each file type has its own colour (PDF coral, Word azure, image orchid, text mint; study items keep their glyph)
    val (glyph, accent) = when (kind) { "PDF" -> "📕" to Accent.CAREER; "Image" -> "🖼" to Accent.MEMORY; "DOCX" -> "📘" to Accent.DOCS
        "TXT", "MD", "CSV" -> "📄" to Accent.STUDY; else -> (if (kind.length <= 2) kind else "📄") to Accent.DOCS }
    AccentIcon(accent, glyph, size)
}

/** List / grid switch for libraries. */
@Composable
fun ViewToggle(grid: Boolean, onChange: (Boolean) -> Unit) {
    IconAction(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view, if (grid) "Show as list" else "Show as grid", { onChange(!grid) })
}

/** Small helper: a clickable text-and-icon row used for "Ask", "Open" style links inside cards. */
@Composable
fun LinkRow(glyph: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius)).clickable(onClickLabel = title, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = title + (subtitle?.let { ". $it" } ?: "") }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clearAndSetSemantics { }) { GlyphIcon(glyph, 20, MaterialTheme.colorScheme.tertiary) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = LocalExtra.current.muted, modifier = Modifier.size(20.dp))
    }
}

/** A large icon + label button for a page's main choices (e.g. Explain / Summary / Quiz / Flashcards). */
@Composable
fun ActionTile(glyph: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Accent = Accent.CHAT, onClick: () -> Unit) {
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val x = LocalExtra.current
    Column(modifier.heightIn(min = 76.dp).pressScale(src).clip(RoundedCornerShape(Dimens.fieldRadius + 4.dp))
        .background(Brush.linearGradient(listOf(accent.g1.copy(alpha = if (!enabled) 0.04f else if (x.dark) 0.24f else 0.11f), x.glass)))
        .clickable(src, indication = androidx.compose.material3.ripple(), enabled = enabled, onClickLabel = label, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (enabled) AccentIcon(accent, glyph, 32) else Box(Modifier.clearAndSetSemantics { }) { GlyphIcon(glyph, 22, x.muted) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalExtra.current.muted)
    }
}


/** 4.6: the accent rows inside a group or page use (NavRow icon tiles); null = the quiet brand tile. */
val LocalRowAccent = androidx.compose.runtime.compositionLocalOf<Accent?> { null }

/** Feature identity of every sub-page, by its title: the title bar shows the icon, the rows take the colour. */
object PageIdentity {
    private val map = listOf(
        "Conversation history" to (Accent.CHAT to "🕘"), "Memories" to (Accent.MEMORY to "🧠"), "Personal memory" to (Accent.MEMORY to "🧠"),
        "AI models" to (Accent.SYSTEM to "📦"), "Account" to (Accent.SYSTEM to "👤"), "Sync & network" to (Accent.DOCS to "🔄"),
        "Privacy" to (Accent.SYSTEM to "🔒"), "Answer style" to (Accent.CHAT to "🎛"), "About" to (Accent.CHAT to "ⓘ"),
        "AI providers" to (Accent.CHAT to "🔑"), "Google Drive" to (Accent.DOCS to "☁"), "Storage" to (Accent.DOCS to "📊"),
        "Backup" to (Accent.DOCS to "♻"), "Semantic search" to (Accent.KNOWLEDGE to "🧩"), "Search everything" to (Accent.KNOWLEDGE to "🔎"),
        "Connection mode" to (Accent.SYSTEM to "🔌"), "Resume" to (Accent.CAREER to "📄"), "Your profile" to (Accent.CHAT to "👤"),
        "Sign in" to (Accent.SYSTEM to "👤"), "Create account" to (Accent.SYSTEM to "👤"), "Reset password" to (Accent.SYSTEM to "🔑"),
        "Link this phone" to (Accent.SYSTEM to "🔗"), "AI Task Runner" to (Accent.CHAT to "✅"),
    )
    fun of(title: String): Pair<Accent, String>? = map.firstOrNull { title.startsWith(it.first) }?.second
}
