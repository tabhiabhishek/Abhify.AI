package ai.abhify.android.ocr

import ai.abhify.android.device.MemoryPolicy
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Google ML Kit text recognition v2, Latin script, bundled model: runs on this phone, offline. Per Google's ML Kit
 * terms the image and the recognized text are not sent to Google; the SDK does send API usage/performance metrics.
 * One recognizer per job; [close] frees it.
 */
class MlKitOcr : OcrEngine<Bitmap> {
    private var client: TextRecognizer? = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override val name = "ML Kit on this phone"

    override suspend fun recognize(image: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val c = client ?: error("the recognizer was closed")
        val t = try { Tasks.await(c.process(InputImage.fromBitmap(image, 0)), 90, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) { throw IllegalStateException("text recognition timed out on this page") }
            catch (e: java.util.concurrent.ExecutionException) { throw IllegalStateException(e.cause?.message ?: "text recognition failed") }
        OcrResult(t.textBlocks.flatMap { b -> b.lines.map { OcrLine(it.text, it.confidence) } })
    }

    override fun close() { client?.close(); client = null }
}

/** A PDF: pdfbox reads each page's text layer; Android's PdfRenderer draws a page only when it needs OCR. */
class PdfPages(bytes: ByteArray, cacheDir: File) : PageSource<Bitmap> {
    private val doc: PDDocument = PDDocument.load(bytes)
    private val stripper = PDFTextStripper().apply { sortByPosition = true }
    private val file = File.createTempFile("ocr-", ".pdf", cacheDir).apply { writeBytes(bytes) }       // PdfRenderer needs a file
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    override val pageCount = doc.numberOfPages
    override val isImage = false
    val encrypted get() = doc.isEncrypted

    override fun text(page: Int): String { stripper.startPage = page + 1; stripper.endPage = page + 1; return stripper.getText(doc) }

    override fun render(page: Int, maxSide: Int, dpi: Int): Bitmap? {
        val r = renderer ?: PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { fd = it }).also { renderer = it }
        return r.openPage(page).use { pg ->
            val (w, h) = MemoryPolicy.pageRender(pg.width, pg.height, dpi, maxSide)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }

    override fun release(image: Bitmap) = image.recycle()

    override fun close() {
        runCatching { renderer?.close() }; runCatching { fd?.close() }; runCatching { doc.close() }
        file.delete()
    }
}

/** One photo or screenshot, decoded no larger than needed (EXIF rotation applied by ImageDecoder). */
class ImagePages(private val bytes: ByteArray) : PageSource<Bitmap> {
    override val pageCount = 1
    override val isImage = true
    var originalSize: Pair<Int, Int>? = null; private set
    override fun text(page: Int) = ""
    override fun render(page: Int, maxSide: Int, dpi: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            originalSize = info.size.width to info.size.height
            if (info.size.width.toLong() * info.size.height > MAX_PIXELS) throw IllegalArgumentException("the image is too large (${info.size.width}×${info.size.height})")
            val (w, h) = MemoryPolicy.fit(info.size.width, info.size.height, maxSide)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE      // ML Kit reads pixels from the CPU
        }
    override fun release(image: Bitmap) = image.recycle()
    override fun close() {}
    companion object { const val MAX_PIXELS = 100_000_000L }
}
