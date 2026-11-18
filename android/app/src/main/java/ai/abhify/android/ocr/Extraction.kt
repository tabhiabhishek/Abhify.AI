package ai.abhify.android.ocr

import ai.abhify.android.device.MemoryProfile
import ai.abhify.android.docs.Library
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One recognized line; [confidence] 0..1 when the engine reports it. */
data class OcrLine(val text: String, val confidence: Float?)
data class OcrResult(val lines: List<OcrLine>) { val text get() = lines.joinToString("\n") { it.text } }

/** An OCR engine. Images are opaque here ([I] = Bitmap on Android, anything in tests). */
interface OcrEngine<I> {
    val name: String
    suspend fun recognize(image: I): OcrResult
    /** Frees the recognizer (models, native buffers). Called when the job ends, also on failure or cancel. */
    fun close()
}

/** A document as pages: the text layer of each page (may be empty) and a way to render one page for OCR. */
interface PageSource<I> : AutoCloseable {
    val pageCount: Int
    /** True for a single image (no text layer, one "page"). */
    val isImage: Boolean
    fun text(page: Int): String
    /** Renders page [page] (0-based) at most [maxSide] px on its longest side, or null if it can't be rendered. */
    fun render(page: Int, maxSide: Int, dpi: Int): I?
    /** Releases a rendered image right after OCR (bitmaps are large). */
    fun release(image: I)
}

enum class PageMethod { TEXT, OCR, SKIPPED, FAILED }
data class PageResult(val page: Int, val method: PageMethod, val text: String, val unclear: List<String> = emptyList(), val error: String? = null)

/** What came out of a document, page by page, with everything the user should review. */
data class Extraction(val pages: List<PageResult>, val engine: String?) {
    val ocrPages get() = pages.count { it.method == PageMethod.OCR }
    val complete get() = pages.none { it.method == PageMethod.SKIPPED || it.method == PageMethod.FAILED }
    /** Text with page markers (the library's citation format). Single images have no marker. */
    fun text(isImage: Boolean): String = pages.filter { it.text.isNotBlank() }.joinToString("\n\n") {
        if (isImage) it.text.trim() else Library.pageMarker(it.page + 1) + "\n" + it.text.trim()
    }
    fun warnings(): List<String> = buildList {
        pages.filter { it.method == PageMethod.SKIPPED && it.error == null }.takeIf { it.isNotEmpty() }?.let { s ->
            add("${s.size} scanned page(s) were not read (text recognition off, or the page limit of this memory profile): pages ${s.joinToString { "${it.page + 1}" }}.") }
        pages.filter { it.method == PageMethod.SKIPPED && it.error != null }.groupBy { it.error }.forEach { (why, s) ->
            add("Pages ${s.joinToString { "${it.page + 1}" }} were not read: $why") }
        pages.filter { it.method == PageMethod.FAILED }.forEach { add("Page ${it.page + 1} could not be read: ${it.error}") }
        pages.filter { it.method == PageMethod.OCR && it.text.isBlank() }.forEach { add("Page ${it.page + 1}: no text was recognized.") }
        pages.filter { it.unclear.isNotEmpty() }.forEach { p ->
            add("Page ${p.page + 1}: ${p.unclear.size} unclear line(s) — check: " + p.unclear.take(5).joinToString(" | ") { "\"${it.take(60)}\"" })
        }
        if (pages.any { it.method == PageMethod.OCR }) add("Text from images was recognized by OCR: check names, dates, numbers and emails before using them.")
    }
}

object ExtractionPipeline {
    /** A page whose text layer has fewer letters/digits than this is treated as scanned. */
    const val MIN_TEXT_CHARS = 25
    /** OCR lines below this confidence are listed for review. */
    const val UNCLEAR = 0.6f

    fun needsOcr(pageText: String) = pageText.count { it.isLetterOrDigit() } < MIN_TEXT_CHARS

    /**
     * Reads every page: the text layer when it has text, otherwise OCR (one page at a time, image released right after,
     * at most [MemoryProfile.maxOcrPages] OCR pages). [engine] null = OCR not available/allowed: such pages are
     * reported as SKIPPED. The engine is closed at the end, also on error or cancellation.
     */
    suspend fun <I> run(src: PageSource<I>, engine: OcrEngine<I>?, profile: MemoryProfile, progress: (String) -> Unit = {},
                        interrupt: () -> String? = { null }): Extraction {
        val out = mutableListOf<PageResult>()
        var ocrDone = 0
        try {
            for (p in 0 until src.pageCount) {
                currentCoroutineContext().ensureActive()
                val layer = if (src.isImage) "" else runCatching { src.text(p) }.getOrDefault("")
                if (!needsOcr(layer)) { out += PageResult(p, PageMethod.TEXT, layer); continue }
                if (engine == null || ocrDone >= profile.maxOcrPages) { out += PageResult(p, PageMethod.SKIPPED, layer); continue }
                interrupt()?.let { why -> out += PageResult(p, PageMethod.SKIPPED, layer, error = why); continue }   // e.g. memory pressure: stop OCR, keep what was read
                progress(if (src.isImage) "Recognizing text in the image (${engine.name})…" else "Recognizing page ${p + 1} of ${src.pageCount} (${engine.name})…")
                val image = try { src.render(p, profile.ocrMaxSide, profile.renderDpi) } catch (e: OutOfMemoryError) { null }
                if (image == null) { out += PageResult(p, PageMethod.FAILED, layer, error = "the page couldn't be turned into an image (too large or damaged)"); continue }
                try {
                    val r = engine.recognize(image)
                    out += PageResult(p, PageMethod.OCR, r.text, r.lines.filter { (it.confidence ?: 1f) < UNCLEAR && it.text.isNotBlank() }.map { it.text })
                    ocrDone++
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: OutOfMemoryError) { out += PageResult(p, PageMethod.FAILED, layer, error = "not enough memory") }
                catch (e: Exception) { out += PageResult(p, PageMethod.FAILED, layer, error = e.message ?: "recognition failed") }
                finally { src.release(image) }
            }
        } finally { engine?.close() }
        return Extraction(out, engine?.name)
    }

    // ------------------------------------------------------------------ accuracy against known text (benchmarks)
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    fun <T> distance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1); cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }
    /** Character error rate: edits / reference length, after lowercasing and collapsing whitespace. */
    fun cer(reference: String, hypothesis: String): Double = norm(reference).let { r -> if (r.isEmpty()) 0.0 else distance(r.toList(), norm(hypothesis).toList()).toDouble() / r.length }
    /** Word error rate. */
    fun wer(reference: String, hypothesis: String): Double = norm(reference).split(' ').filter { it.isNotEmpty() }.let { r ->
        if (r.isEmpty()) 0.0 else distance(r, norm(hypothesis).split(' ').filter { it.isNotEmpty() }).toDouble() / r.size }
}
