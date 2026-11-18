package ai.abhify.android

import ai.abhify.android.career.DocxWriter
import ai.abhify.android.career.ResumeLayout
import ai.abhify.android.career.ResumePdf
import ai.abhify.android.docs.Documents
import ai.abhify.android.docs.PdfText
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Resume export on the phone: the PDF has real text (read back with the app's PDF reader), DOCX reads back, sharing uses the FileProvider. */
@RunWith(AndroidJUnit4::class)
class ResumeExportTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val text = "Asha Verma\nasha@example.com\n\nExperience\n- Built a detection service with Python\n" + (1..80).joinToString("\n") { "- Bullet line number $it" } +
        "\n\nEducation\nB.Tech Computer Science, 2020"

    @Test fun pdfAndDocxContainTheResumeText() {
        PdfText.install(ctx)
        val blocks = ResumeLayout.blocks(text)
        val pdf = ResumePdf.write(blocks)
        assertTrue(pdf.size > 1000)
        val back = PdfText.extract(pdf)
        assertTrue(back.contains("Asha Verma") && back.contains("Bullet line number 80") && back.contains("B.Tech Computer Science"))
        assertTrue("long resume paginates", back.contains("[[Page 2]]"))
        val docx = Documents.extract("r.docx", null, DocxWriter.write(blocks)).text
        assertTrue(docx.contains("Built a detection service with Python"))
    }

    @Test fun sharedFilesGoThroughTheFileProviderOnly() {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "test-abhify.pdf").apply { writeBytes(ResumePdf.write(ResumeLayout.blocks(text))) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        assertEquals("content", uri.scheme)
        assertTrue(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().size } > 0)
        f.delete()
    }
}
