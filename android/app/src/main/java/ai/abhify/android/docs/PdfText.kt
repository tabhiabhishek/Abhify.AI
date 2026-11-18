package ai.abhify.android.docs

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * PDF text extraction on the phone with pdfbox-android (Apache-2.0). Reads the text layer page by page and marks
 * each page, so answers can cite real page numbers. Scanned (image-only) PDFs have no text layer: they are refused
 * with a message, because OCR isn't available. Nothing leaves the phone.
 */
object PdfText {
    const val MAX_PAGES = 1000

    fun install(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
        Documents.pdf = ::extract
    }

    fun extract(bytes: ByteArray): String {
        val doc = try { PDDocument.load(bytes) }
            catch (e: InvalidPasswordException) { throw DocumentException("This PDF is password-protected. Remove the password and try again.") }
            catch (e: Throwable) { throw DocumentException(if (e is LinkageError) "This PDF uses certificate encryption, which isn't supported." else "This file isn't a readable PDF.") }
        doc.use { d ->
            val pages = d.numberOfPages
            if (pages > MAX_PAGES) throw DocumentException("This PDF has $pages pages; the limit is $MAX_PAGES.")
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val sb = StringBuilder()
            for (p in 1..pages) {
                stripper.startPage = p; stripper.endPage = p
                val t = runCatching { stripper.getText(d) }.getOrElse { "" }.trim()
                if (t.isNotEmpty()) sb.append(Library.pageMarker(p)).append('\n').append(t).append("\n\n")
                if (sb.length > Documents.MAX_CHARS + 10_000) break
            }
            if (sb.isBlank()) throw DocumentException("This PDF has no text layer (it looks like a scanned image). Reading scans needs OCR, which isn't available yet.")
            return sb.toString()
        }
    }
}
