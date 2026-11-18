package ai.abhify.android

import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.docs.DocumentException
import ai.abhify.android.docs.Documents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentsTest {
    private fun docx(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="x"><w:body>$body</w:body></w:document>""".toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun refused(name: String, mime: String?, bytes: ByteArray): String =
        try { Documents.extract(name, mime, bytes); throw AssertionError("accepted $name") } catch (e: DocumentException) { e.message!! }

    @Test fun plainTextInCommonEncodings() {
        assertEquals("héllo\nworld", Documents.extract("a.txt", "text/plain", "héllo  \r\nworld\n\n\n".toByteArray()).text)
        assertEquals("bom", Documents.extract("a.md", null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom".toByteArray()).text)
        assertEquals("utf16", Documents.extract("a.txt", null, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "utf16".toByteArray(Charsets.UTF_16LE)).text)
        assertEquals("café", Documents.extract("a.csv", null, byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())).text)      // Latin-1 fallback, no crash
    }

    @Test fun docxParagraphsTabsAndEntities() {
        val d = Documents.extract("notes.docx", null, docx(
            """<w:p><w:r><w:t>Gradient</w:t></w:r><w:r><w:t xml:space="preserve"> descent</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b &amp; c &lt;d&gt;</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""))
        assertEquals("DOCX", d.kind)
        assertEquals("Gradient descent\na\tb & c <d>\ncell", d.text)
    }

    @Test fun unsupportedAndBrokenFilesAreRefusedHonestly() {
        assertTrue(refused("paper.pdf", "application/pdf", ByteArray(10)).contains("PDF reading isn't available yet"))
        assertTrue(refused("scan.jpg", "image/jpeg", ByteArray(10)).contains("OCR"))
        assertTrue(refused("x.exe", "application/octet-stream", ByteArray(10)).contains("isn't a supported document"))
        assertTrue(refused("bad.docx", null, "not a zip".toByteArray()).contains("isn't a valid DOCX"))
        assertTrue(refused("empty.txt", "text/plain", "  \n ".toByteArray()).contains("no readable text"))
        assertTrue(refused("big.txt", "text/plain", ByteArray((Documents.MAX_BYTES + 1).toInt())).contains("larger than 10 MB"))
    }

    @Test fun zipBombIsStopped() {
        val huge = "<w:p><w:r><w:t>" + "a".repeat(31 * 1024 * 1024) + "</w:t></w:r></w:p>"
        assertTrue(refused("bomb.docx", null, docx(huge)).contains("too large when unpacked"))
    }

    @Test fun longDocumentsAreTruncatedAndExcerptedByRelevance() {
        val long = Documents.extract("long.txt", null, ("x".repeat(99) + "\n").repeat(3000).toByteArray())
        assertTrue(long.truncated); assertEquals(Documents.MAX_CHARS, long.chars)

        val text = (1..40).joinToString("\n\n") { i -> if (i == 27) "Section $i: regularization with dropout prevents overfitting." else "Section $i: " + "filler words here. ".repeat(60) }
        val e = Documents.excerpt(text, "How does dropout help?", 3000)
        assertFalse(e.whole); assertTrue(e.text.length <= 3000)
        assertTrue(e.text.contains("dropout prevents overfitting"))
        assertEquals(Documents.Excerpt("short", true), Documents.excerpt("short", "anything", 3000))
    }

    @Test fun promptCarriesTheDocumentOnlyWhenGiven() {
        val doc = PromptBuilder.Doc("notes.txt", "Dropout randomly zeroes activations.", true)
        val withDoc = PromptBuilder.build("What is dropout?", emptyList(), emptyList(), "", onDevice = false, doc = doc)[0].content
        assertTrue(withDoc.contains("<document>\nDropout randomly zeroes activations.\n</document>"))
        assertTrue(withDoc.contains("except the document the user attached"))
        val none = PromptBuilder.build("What is dropout?", emptyList(), emptyList(), "", onDevice = false)[0].content
        assertFalse(none.contains("<document>")); assertTrue(none.contains("no access to the user's files."))
    }

    @Test fun answerStyleAndCustomInstructionsCanBeCombinedOrPaused() {
        val p = PromptBuilder.Personality.CONCISE
        assertEquals(p.instruction + "\nUse PyTorch.", PromptBuilder.instructions(p, " Use PyTorch. ", customEnabled = true))
        assertEquals(p.instruction, PromptBuilder.instructions(p, "Use PyTorch.", customEnabled = false))          // paused: text kept, not sent
        assertEquals("", PromptBuilder.instructions(PromptBuilder.Personality.DEFAULT, "x", customEnabled = false))
        assertEquals(PromptBuilder.Personality.DEFAULT, PromptBuilder.Personality.of("unknown"))
        val sys = PromptBuilder.build("q", emptyList(), emptyList(), "", custom = "")[0].content
        assertFalse(sys.contains("The user's instructions"))
    }
}
