package ai.abhify.android

import ai.abhify.android.device.MemoryPolicy
import ai.abhify.android.device.MemoryProfile
import ai.abhify.android.docs.PdfText
import ai.abhify.android.ocr.ExtractionPipeline
import ai.abhify.android.ocr.ImagePages
import ai.abhify.android.ocr.MlKitOcr
import ai.abhify.android.ocr.OcrSamples
import ai.abhify.android.ocr.PageMethod
import ai.abhify.android.ocr.PdfPages
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OCR benchmark on the phone with synthetic, anonymised resumes (known text = ground truth). Measures time, memory
 * (PSS sampled during the run), character/word error rate and which pages needed OCR. Uses no user data and doesn't
 * load the language model. Results: logcat tag AbhifyOcrBench and files/ocr-benchmark.json (app-private).
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val truth = OcrSamples.RESUME.joinToString("\n")
    init { PdfText.install(ctx) }

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun jpeg(b: Bitmap, q: Int) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** PDF pages: true = real text (selectable), false = an image of the text (scanned). */
    private fun pdf(pages: List<Boolean>): ByteArray {
        val doc = PdfDocument()
        pages.forEachIndexed { i, text ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, i + 1).create())
            val c = page.canvas; c.drawColor(Color.WHITE)
            if (text) { val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = Color.BLACK }
                OcrSamples.RESUME.forEachIndexed { k, l -> c.drawText(l, 40f, 60f + k * 22f, p) } }
            else { val img = OcrSamples.render(OcrSamples.RESUME, width = 1700, size = 44f)
                c.drawBitmap(img, Matrix().apply { setScale(532f / img.width, 532f / img.width); postTranslate(40f, 40f) }, null); img.recycle() }
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    private fun measure(name: String, format: String, bytes: ByteArray, expectOcrPages: Int, pages: Int): JSONObject = runBlocking {
        Runtime.getRuntime().gc()
        val before = Debug.getPss()
        var peak = before
        val sampler = Thread { try { while (!Thread.currentThread().isInterrupted) { peak = maxOf(peak, Debug.getPss()); Thread.sleep(50) } } catch (_: InterruptedException) {} }.apply { start() }
        val profile = MemoryProfile.BALANCED
        val t0 = System.nanoTime()
        val src = if (format == "pdf") PdfPages(bytes, ctx.cacheDir) else ImagePages(bytes)
        val ex = src.use { ExtractionPipeline.run(it, MlKitOcr(), profile) }
        val ms = (System.nanoTime() - t0) / 1e6
        sampler.interrupt(); sampler.join()
        val text = ex.text(format != "pdf").replace(Regex("""\[\[Page \d+]]\n?"""), "")
        val perPageTruth = (1..pages).joinToString("\n") { truth }
        val r = JSONObject().put("input", name).put("format", format).put("bytes", bytes.size).put("pages", pages)
            .put("ocr_pages", ex.ocrPages).put("text_pages", ex.pages.count { it.method == PageMethod.TEXT })
            .put("failed_or_skipped", ex.pages.count { it.method == PageMethod.FAILED || it.method == PageMethod.SKIPPED })
            .put("ms_total", "%.0f".format(ms).toInt()).put("ms_per_ocr_page", if (ex.ocrPages > 0) (ms / ex.ocrPages).toInt() else 0)
            .put("pss_before_kb", before).put("pss_peak_kb", peak).put("pss_delta_mb", (peak - before) / 1024)
            .put("cer", "%.4f".format(ExtractionPipeline.cer(perPageTruth, text)).toDouble()).put("wer", "%.4f".format(ExtractionPipeline.wer(perPageTruth, text)).toDouble())
            .put("email_ok", text.contains("priya.sharma@example.com")).put("phone_ok", text.contains("98765 43210"))
            .put("date_ok", text.contains("Jan 2021")).put("percent_ok", text.contains("35%"))
            .put("unclear_lines", ex.pages.sumOf { it.unclear.size }).put("profile", profile.name)
        Log.i("AbhifyOcrBench", r.toString())
        assertEquals("$name: pages needing OCR", expectOcrPages, ex.ocrPages)
        r
    }

    @Test fun benchmark() {
        val shot = OcrSamples.render(OcrSamples.RESUME)                                      // screenshot-like, 1080 px wide
        val photo = OcrSamples.render(OcrSamples.RESUME, width = 2400, size = 70f, rotateDeg = 2f, noise = true)   // photo-like
        val general = OcrSamples.render(OcrSamples.RESUME, width = 1240, size = 30f)         // small print
        val results = JSONArray().apply {
            put(measure("resume, selectable PDF", "pdf", pdf(listOf(true)), 0, 1))
            put(measure("resume, scanned PDF", "pdf", pdf(listOf(false)), 1, 1))
            put(measure("mixed PDF (text + scan)", "pdf", pdf(listOf(true, false)), 1, 2))
            put(measure("resume screenshot PNG", "png", png(shot), 1, 1))
            put(measure("resume photo JPEG (rotated 2°, noise, q60)", "jpeg", jpeg(photo, 60), 1, 1))
            put(measure("small-print document PNG", "png", png(general), 1, 1))
            put(measure("3-page scanned PDF", "pdf", pdf(listOf(false, false, false)), 3, 3))
        }
        listOf(shot, photo, general).forEach { it.recycle() }
        val snap = MemoryPolicy.read(ctx)
        val out = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("ram_total_mb", snap.totalMb).put("ram_free_mb", snap.availMb).put("engine", "ML Kit text-recognition 16.0.1 (bundled, Latin)")
            .put("qwen_loaded_during_ocr", false).put("results", results)
        File(ctx.filesDir, "ocr-benchmark.json").writeText(out.toString(2))
        Log.i("AbhifyOcrBench", out.toString())
        // the selectable PDF must be exact without OCR; OCR inputs must be close (synthetic, clean text)
        assertEquals(0.0, results.getJSONObject(0).getDouble("cer"), 0.02)
        assertTrue("screenshot CER", results.getJSONObject(3).getDouble("cer") < 0.05)
    }
}
