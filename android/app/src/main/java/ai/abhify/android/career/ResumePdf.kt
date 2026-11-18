package ai.abhify.android.career

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Resume → PDF with Android's own PdfDocument (A4, real selectable text, no library). One page at a time, then freed. */
object ResumePdf {
    private const val W = 595; private const val H = 842; private const val M = 48

    fun write(blocks: List<ResumeLayout.Block>, t: ResumeLayout.Template = ResumeLayout.Template.SIMPLE): ByteArray {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() { page?.let { doc.finishPage(it) }; pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); y = M.toFloat() }
        fun draw(text: String, size: Float, bold: Boolean, indent: Int = 0, before: Float = 0f, after: Float = 4f) {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val width = W - 2 * M - indent
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            if (page == null || y + before + layout.height > H - M) newPage()
            y += before
            val c = page!!.canvas
            c.save(); c.translate((M + indent).toFloat(), y); layout.draw(c); c.restore()
            y += layout.height + after
        }
        newPage()
        blocks.forEach { b -> when (b) {
            is ResumeLayout.Block.Title -> draw(b.text, t.titleSize, true, after = 10f)
            is ResumeLayout.Block.Heading -> draw(b.text, t.headingSize, true, before = 8f, after = 3f)
            is ResumeLayout.Block.Bullet -> draw("•  " + b.text, t.bodySize, false, indent = 10)
            is ResumeLayout.Block.Para -> draw(b.text, t.bodySize, false)
        } }
        page?.let { doc.finishPage(it) }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }
}
