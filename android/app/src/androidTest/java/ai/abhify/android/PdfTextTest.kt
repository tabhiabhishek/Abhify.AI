package ai.abhify.android

import ai.abhify.android.docs.DocumentException
import ai.abhify.android.docs.Documents
import ai.abhify.android.docs.Library
import ai.abhify.android.docs.PdfText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** PDF text extraction with pdfbox-android on the phone (needs Android's resource loader). Uses generated PDFs only. */
@RunWith(AndroidJUnit4::class)
class PdfTextTest {
    @Before fun setUp() { PdfText.install(InstrumentationRegistry.getInstrumentation().targetContext) }

    /** A minimal two-page PDF with real text objects (Helvetica, uncompressed), xref offsets computed. */
    private fun twoPagePdf(p1: String, p2: String): ByteArray {
        val objs = mutableListOf<String>()
        objs += "<< /Type /Catalog /Pages 2 0 R >>"
        objs += "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"
        objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 7 0 R >> >> >>"
        objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>"
        fun stream(t: String) = "BT /F1 18 Tf 72 700 Td ($t) Tj ET".let { "<< /Length ${it.length} >>\nstream\n$it\nendstream" }
        objs += stream(p1); objs += stream(p2)
        objs += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val out = ByteArrayOutputStream(); val offsets = mutableListOf<Int>()
        out.write("%PDF-1.4\n".toByteArray())
        objs.forEachIndexed { i, o -> offsets += out.size(); out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) }
        val xref = out.size()
        out.write("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { out.write("%010d 00000 n \n".format(it).toByteArray()) }
        out.write("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    @Test fun pdfTextIsExtractedPerPage() {
        val text = PdfText.extract(twoPagePdf("Hello from page one", "The second page"))
        assertTrue(text, text.contains(Library.pageMarker(1)) && text.contains("Hello from page one"))
        assertTrue(text.contains(Library.pageMarker(2)) && text.contains("The second page"))
        try { PdfText.extract("not a pdf".toByteArray()); throw AssertionError("accepted") } catch (e: DocumentException) { assertTrue(e.message!!.contains("readable PDF")) }
        // scanned-like: a valid PDF with no text
        try { PdfText.extract(twoPagePdf("", "")); throw AssertionError("accepted") } catch (e: DocumentException) { assertTrue(e.message!!.contains("no text layer")) }
    }

    @Test fun pdfThroughTheDocumentPipelineWhenInstalled() {
        run {
            val d = Documents.extract("report.pdf", "application/pdf", twoPagePdf("Revenue grew 12 percent", "Costs fell"))
            assertEquals("PDF", d.kind); assertTrue(d.text.contains("Revenue grew 12 percent"))
            assertFalse(d.truncated)
        }
    }
}
