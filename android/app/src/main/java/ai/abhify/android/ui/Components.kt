package ai.abhify.android.ui

import ai.abhify.android.R
import ai.abhify.android.ai.AiMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

@Composable
fun Logo(size: Int) {
    Image(painterResource(R.drawable.abhify_logo), contentDescription = "Abhify.AI", modifier = Modifier.size(size.dp).clip(CircleShape))
}

/** The product name and the official tagline (v4.3). One place, so every screen says the same thing. */
object BrandText {
    const val NAME = "Abhify.AI"
    const val TAGLINE = "Learn AI. Think Deeper. Build the Future"
}

/** Logo mark, "Abhify.AI" and the tagline, centred: launch screen, welcome, about. Uses the existing logo asset. */
@Composable
fun BrandLockup(logoSize: Int = 112, showTagline: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
        Logo(logoSize)
        Text(BrandText.NAME, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (showTagline) Text(BrandText.TAGLINE, style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * The one scrolling page body used by every screen and tab section (v4.3): same gutters, readable max width, same gap
 * between cards, room at the bottom so the last card isn't stuck to the navigation bar.
 */
@Composable
fun PageBody(modifier: Modifier = Modifier, maxWidth: androidx.compose.ui.unit.Dp = Dimens.maxWidth, gap: androidx.compose.ui.unit.Dp = Dimens.gap,
             content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter, vertical = Dimens.small),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) { content(); Spacer(Modifier.height(24.dp)) }
    }
}

/** A glyph button (emoji/symbol) with a spoken description and a 48 dp touch target. */
@Composable
fun IconAction(glyph: String, description: String, onClick: () -> Unit, size: androidx.compose.ui.unit.TextUnit = 20.sp) {
    Box(Modifier.size(Dimens.touch).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = size, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** v4.4: an icon button using one of the app's vector icons (res/drawable/ic_*), with a description and a 48 dp target. */
@Composable
fun IconAction(icon: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(Dimens.touch).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}

/** A friendly empty list: what's missing and what to do next (same information as before, easier to see). */
@Composable
fun EmptyState(icon: String, title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.tiny)) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape).clearAndSetSemantics { },
            contentAlignment = Alignment.Center) { GlyphIcon(icon, 28, MaterialTheme.colorScheme.tertiary) }
        Spacer(Modifier.height(Dimens.tiny))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

/** One horizontal row of section chips (tabs inside a tab), shared by Career, Study, Knowledge and Work. */
@Composable
fun SectionChips(names: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
        names.forEachIndexed { i, n -> androidx.compose.material3.FilterChip(selected == i, { onPick(i) },
            { Text(n, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1) },
            shape = RoundedCornerShape(50),
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Violet, selectedLabelColor = Color.White,
                labelColor = MaterialTheme.colorScheme.onSurface),
            border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(true, selected == i, borderColor = LocalExtra.current.line,
                selectedBorderColor = Color.Transparent)) }
    }
}

@Composable
fun Badge(size: Int) {
    Image(painterResource(R.drawable.abhify_badge), contentDescription = "Abhify · Learn, Build, Share, Grow", modifier = Modifier.size(size.dp).clip(CircleShape))
}

/** The card surface shared with the web app (4.5): opaque surface, hairline border, 16 dp corners. Name kept from 4.3. */
@Composable
fun Glass(modifier: Modifier = Modifier, padding: Int = 16, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val x = LocalExtra.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(src) else it }.clip(RoundedCornerShape(Brand.Radius)).background(x.glass)
        .border(1.dp, x.line, RoundedCornerShape(Brand.Radius))
        .let { if (onClick != null) it.clickable(src, indication = androidx.compose.material3.ripple(), onClick = onClick) else it }.padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardGap), content = content)
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.muted,
    modifier = Modifier.padding(top = Dimens.small, start = 2.dp).semantics { heading() })

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.tertiary) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** The primary action: the Chat accent gradient (violet → purple) with white text, press feedback (4.6; name kept from 4.3). */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.pressScale(src).heightIn(min = 48.dp), interactionSource = src, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent), shape = RoundedCornerShape(Dimens.fieldRadius)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (enabled) Accent.CHAT.brush else androidx.compose.ui.graphics.SolidColor(LocalExtra.current.line), RoundedCornerShape(Dimens.fieldRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text(text, color = if (enabled) Color.White else LocalExtra.current.muted, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.fieldRadius),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LocalExtra.current.line, focusedBorderColor = MaterialTheme.colorScheme.primary))
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun NavRow(icon: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).clip(RoundedCornerShape(Dimens.fieldRadius)).clickable(onClick = onClick)
        .padding(horizontal = 6.dp, vertical = Dimens.small), verticalAlignment = Alignment.CenterVertically) {
        val accent = LocalRowAccent.current                                     // 4.6: rows wear their group's colour
        if (accent != null) AccentIcon(accent, icon, 40)
        else Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(Dimens.fieldRadius)).clearAndSetSemantics { },
            contentAlignment = Alignment.Center) { GlyphIcon(icon, 22, MaterialTheme.colorScheme.tertiary) }   // decorative: TalkBack reads the title only
        Spacer(Modifier.width(Dimens.gap))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        androidx.compose.material3.Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = LocalExtra.current.muted, modifier = Modifier.size(20.dp))
    }
}

/** A small labelled state ("Provider: Groq · model") with an icon, so colour is never the only signal. */
@Composable
fun StateChip(icon: String, text: String, level: Int, onClick: (() -> Unit)? = null) {
    val x = LocalExtra.current
    val c = when (level) { 0 -> x.ok; 1 -> x.warn; else -> x.bad }
    Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(50))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clearAndSetSemantics { }) { GlyphIcon(icon, 16, c) }; Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Header status: a coloured dot and one honest line (network + account + sync). */
@Composable
fun StatusLine(status: AppViewModel.Status) {
    val x = LocalExtra.current
    val c = when (status.level) { 0 -> x.ok; 1 -> x.warn; else -> x.bad }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(c, CircleShape)); Spacer(Modifier.width(6.dp))
        Text(status.text, style = MaterialTheme.typography.labelSmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ScreenTitle(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).padding(horizontal = Dimens.tiny), verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_arrow_back, "Back", onBack)
        Spacer(Modifier.width(Dimens.tiny))
        PageIdentity.of(title)?.let { (a, g) -> AccentIcon(a, g, 32); Spacer(Modifier.width(10.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

@Composable
fun Message(text: String?, error: Boolean) {
    if (text == null) return
    Text(text, color = if (error) LocalExtra.current.bad else LocalExtra.current.ok, style = MaterialTheme.typography.bodyMedium)
}


/**
 * v4.3.1 dialog body: scrolls when long (small screens, large system font) instead of clipping, readable body size.
 * Used for every AlertDialog whose content is plain text.
 */
@Composable
fun DialogText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = style, color = MaterialTheme.colorScheme.onSurface) }
}
