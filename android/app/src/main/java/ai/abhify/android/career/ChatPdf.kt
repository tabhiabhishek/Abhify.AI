package ai.abhify.android.career

import ai.abhify.android.chat.Markdown
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * v4.4: a conversation as a simple, readable A4 PDF made on the phone (Android's PdfDocument, as for résumés; no
 * library). Markdown is turned into plain styled lines: headings bold, list markers kept, code in monospace.
 */
object ChatPdf {
    data class Line(val text: String, val bold: Boolean = false, val mono: Boolean = false, val indent: Int = 0, val gapBefore: Int = 0)

    /** Pure: one message's Markdown as printable lines (tested on the JVM). */
    fun lines(markdown: String): List<Line> = Markdown.parse(markdown).flatMap { b ->
        fun t(spans: List<Markdown.Span>) = spans.joinToString("") { it.text }
        when (b) {
            is Markdown.Block.Heading -> listOf(Line(t(b.spans), bold = true, gapBefore = 6))
            is Markdown.Block.Paragraph -> listOf(Line(t(b.spans), gapBefore = 4))
            is Markdown.Block.Bullet -> listOf(Line((when (b.task) { true -> "☑ "; false -> "☐ "; null -> "• " }) + t(b.spans), indent = 12 + b.depth * 12))
            is Markdown.Block.Numbered -> listOf(Line("${b.number}. " + t(b.spans), indent = 12 + b.depth * 12))
            is Markdown.Block.Quote -> listOf(Line("“" + t(b.spans) + "”", indent = 12, gapBefore = 4))
            is Markdown.Block.Code -> listOf(Line(b.text, mono = true, indent = 8, gapBefore = 4))
            is Markdown.Block.Table -> (listOf(b.header) + b.rows).map { r -> Line(r.joinToString("  |  ") { t(it) }, mono = true, indent = 8) }
            Markdown.Block.Rule -> listOf(Line("—".repeat(20), gapBefore = 4))
        }
    }

    /** [messages]: (speaker label, Markdown text). Returns the PDF bytes. */
    fun render(title: String, messages: List<Pair<String, String>>): ByteArray {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 48f; val width = (w - 2 * margin).toInt()
        val body = TextPaint().apply { isAntiAlias = true; textSize = 10.5f; color = Color.rgb(15, 23, 42) }
        val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val mono = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9.5f }
        val head = TextPaint(bold).apply { textSize = 16f }
        val who = TextPaint(bold).apply { textSize = 11f; color = Color.rgb(109, 40, 217) }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create()); var y = margin
        fun newPage() { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create()); y = margin }
        fun draw(text: String, paint: TextPaint, indent: Int = 0, gap: Int = 0) {
            if (text.isEmpty()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            y += gap
            for (i in 0 until layout.lineCount) {                           // line by line, so long messages flow onto new pages
                val lh = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + lh > h - margin) newPage()
                page.canvas.drawText(text, layout.getLineStart(i), layout.getLineEnd(i).let { e -> if (e > layout.getLineStart(i) && text[e - 1] == '\n') e - 1 else e },
                    margin + indent, y - layout.getLineTop(i) + layout.getLineBaseline(i), paint)
                y += lh
            }
        }
        draw(title, head); y += 6
        messages.forEach { (speaker, text) ->
            draw(speaker, who, gap = 10)
            lines(text).forEach { l -> draw(l.text, if (l.mono) mono else if (l.bold) bold else body, l.indent, l.gapBefore) }
        }
        doc.finishPage(page)
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }
}
