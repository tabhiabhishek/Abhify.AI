package ai.abhify.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import ai.abhify.android.chat.CodeHighlight
import ai.abhify.android.chat.Markdown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders [text] as formatted Markdown (see chat/Markdown.kt for what is understood). The parse and every styled line
 * are cached per text and theme, so a streaming answer only re-styles when it actually changes. Long content is never
 * cut here; callers decide (chat bubbles collapse very long answers with "Show more").
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val accent = MaterialTheme.colorScheme.tertiary
    val x = LocalExtra.current
    val blocks = remember(text) { Markdown.parse(text) }
    val styled = remember(blocks, accent, x.dark) { blocks.map { b -> styleOf(b, accent, x.codeBg) } }
    val t = MaterialTheme.typography
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(gapBefore(blocks[i - 1], b)))
            when (b) {
                is Markdown.Block.Heading -> Text(styled[i]!!, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() },
                    style = when (b.level) { 1 -> t.titleLarge; 2 -> t.titleMedium; 3 -> t.titleSmall; else -> t.bodyLarge })
                is Markdown.Block.Paragraph -> Text(styled[i]!!, color = color, style = t.bodyLarge)
                is Markdown.Block.Bullet -> ListRow(b.depth, when (b.task) { true -> "☑"; false -> "☐"; null -> listOf("•", "◦", "▪", "▫")[b.depth] }, accent) {
                    Text(styled[i]!!, color = color, style = t.bodyLarge,
                        textDecoration = if (b.task == true) TextDecoration.LineThrough else null) }
                is Markdown.Block.Numbered -> ListRow(b.depth, "${b.number}.", accent) { Text(styled[i]!!, color = color, style = t.bodyLarge) }
                is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(accent.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                    Text(styled[i]!!, color = x.muted, style = t.bodyLarge, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 10.dp))
                }
                Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = x.line)
                is Markdown.Block.Code -> CodeBlock(b.language, b.text)
                is Markdown.Block.Table -> TableBlock(b, accent)
            }
        }
    }
}

/** Space between two blocks: more before headings, little between items of the same list. */
private fun gapBefore(prev: Markdown.Block, cur: Markdown.Block): Dp = when {
    cur is Markdown.Block.Heading -> 14.dp
    (prev is Markdown.Block.Bullet || prev is Markdown.Block.Numbered) && (cur is Markdown.Block.Bullet || cur is Markdown.Block.Numbered) -> 4.dp
    prev is Markdown.Block.Heading -> 6.dp
    else -> 10.dp
}

@Composable
private fun ListRow(depth: Int, marker: String, accent: Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 18).dp)) {
        Text(marker, color = accent, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun styleOf(b: Markdown.Block, accent: Color, codeBg: Color): AnnotatedString? = when (b) {
    is Markdown.Block.Heading -> spans(b.spans, accent, codeBg)
    is Markdown.Block.Paragraph -> spans(b.spans, accent, codeBg)
    is Markdown.Block.Bullet -> spans(b.spans, accent, codeBg)
    is Markdown.Block.Numbered -> spans(b.spans, accent, codeBg)
    is Markdown.Block.Quote -> spans(b.spans, accent, codeBg)
    else -> null
}

@Composable
private fun TableBlock(t: Markdown.Block.Table, accent: Color) {
    val x = LocalExtra.current
    val all = remember(t) { listOf(t.header) + t.rows }
    // each column as wide as its longest cell needs, within 72–240 dp; wide tables scroll sideways instead of squeezing
    val widths = remember(t) { t.header.indices.map { c -> (all.maxOf { r -> r.getOrNull(c)?.sumOf { it.text.length } ?: 0 } * 8 + 20).coerceIn(72, 240).dp } }
    Column(Modifier.fillMaxWidth().border(1.dp, x.line, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState())) {
        all.forEachIndexed { r, row ->
            Row(Modifier.background(if (r == 0) x.glassStrong else Color.Transparent)) {
                row.forEachIndexed { c, cell ->
                    Text(spans(cell, accent, x.codeBg), modifier = Modifier.width(widths.getOrElse(c) { 120.dp }).padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            if (r < all.lastIndex) HorizontalDivider(color = x.line)
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val x = LocalExtra.current
    val clip = LocalClipboardManager.current
    val pal = if (x.dark) darkCode else lightCode
    val styled = remember(code, language, x.dark) {
        val colors = mapOf(CodeHighlight.Kind.KEYWORD to pal.keyword, CodeHighlight.Kind.STRING to pal.string,
            CodeHighlight.Kind.COMMENT to pal.comment, CodeHighlight.Kind.NUMBER to pal.number, CodeHighlight.Kind.FUNCTION to pal.function)
        buildAnnotatedString {
            CodeHighlight.tokenize(code, language).forEach { t ->
                val c = colors[t.kind]
                if (c == null) append(t.text) else withStyle(SpanStyle(color = c, fontStyle = if (t.kind == CodeHighlight.Kind.COMMENT) FontStyle.Italic else FontStyle.Normal)) { append(t.text) }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(x.codeBg, RoundedCornerShape(10.dp)).border(1.dp, x.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = x.muted, modifier = Modifier.weight(1f))
            var copied by androidx.compose.runtime.remember(code) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
            Text(if (copied) "Copied ✓" else "Copy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Copy code") { clip.setText(AnnotatedString(code)); copied = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp))
        }
        Text(styled, color = MaterialTheme.colorScheme.onSurface, style = CodeStyle, softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

private fun spans(list: List<Markdown.Span>, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    list.forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            color = if (s.code || s.link != null) accent else Color.Unspecified,
            background = if (s.code) codeBg else Color.Unspecified,
            textDecoration = when { s.strike -> TextDecoration.LineThrough; s.link != null -> TextDecoration.Underline; else -> null })
        if (s.link != null) withLink(LinkAnnotation.Url(s.link, TextLinkStyles(style))) { append(s.text) }
        else withStyle(style) { append(s.text) }
    }
}
