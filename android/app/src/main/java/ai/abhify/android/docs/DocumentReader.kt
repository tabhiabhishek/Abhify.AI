package ai.abhify.android.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.abhify.android.graph
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A document needs text recognition (scanned pages or an image) and the user hasn't allowed it yet. */
class OcrNeeded(val name: String, val pages: Int) : Exception("$name needs text recognition")

/** Reads a file the user picked (system picker: only that file is accessible) and extracts its text on the phone. */
object DocumentReader {
    /** [pages] null for non-paged formats; [ocrPages] pages read by OCR; [warnings] what the user should review; [sha256] of the file bytes (duplicate check). */
    data class Read(val doc: DocText, val sizeBytes: Long, val pages: Int? = null, val ocrPages: Int = 0, val warnings: List<String> = emptyList(), val sha256: String = "")

    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    fun isImage(name: String, mime: String?) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT ||
        mime in setOf("image/jpeg", "image/png", "image/webp")

    suspend fun read(context: Context, uri: Uri): Read = read(context.graph, uri, false) {}

    /**
     * [ocr]: null = recognize scanned pages/images only if the user has allowed text recognition (else [OcrNeeded]
     * is thrown so the UI can ask), true = allowed for this job, false = don't recognize (scanned pages are skipped
     * and reported). [progress] gets user-readable steps.
     */
    suspend fun read(g: ai.abhify.android.AppGraph, uri: Uri, ocr: Boolean?, progress: (String) -> Unit): Read {
        val context = g.context
        val cr = context.contentResolver
        var name = "document"; var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        val limit = "$name is larger than ${Documents.MAX_BYTES / 1024 / 1024} MB."
        if (size > Documents.MAX_BYTES) throw DocumentException(limit)
        val bytes = cr.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buf); if (n < 0) break
                out.write(buf, 0, n); if (out.size() > Documents.MAX_BYTES) throw DocumentException(limit)
            }
            out.toByteArray()
        } ?: throw DocumentException("Couldn't open $name.")
        currentCoroutineContext().ensureActive()
        return process(g, name, cr.getType(uri), bytes, ocr, progress)
    }

    /** Extracts text from file bytes already in memory (picked file or a Google Drive download), with the same rules. */
    suspend fun process(g: ai.abhify.android.AppGraph, name: String, mime: String?, bytes: ByteArray, ocr: Boolean?, progress: (String) -> Unit): Read {
        if (bytes.size > Documents.MAX_BYTES) throw DocumentException("$name is larger than ${Documents.MAX_BYTES / 1024 / 1024} MB.")
        val context = g.context
        val sha = ai.abhify.android.backup.Backup.sha256(bytes)
        val pdf = name.endsWith(".pdf", true) || mime == "application/pdf"
        if (!pdf && !isImage(name, mime)) return Read(Documents.extract(name, mime, bytes), bytes.size.toLong(), sha256 = sha)

        val (profile, _) = g.memoryProfile()
        val src: ai.abhify.android.ocr.PageSource<android.graphics.Bitmap> = try {
            if (pdf) ai.abhify.android.ocr.PdfPages(bytes, context.cacheDir).also { if (it.encrypted) { it.close(); throw DocumentException("This PDF is password-protected. Remove the password and try again.") } }
            else ai.abhify.android.ocr.ImagePages(bytes)
        } catch (e: DocumentException) { throw e } catch (e: Throwable) {
            throw DocumentException(if (pdf) "This file isn't a readable PDF." else "This image can't be read (supported: JPEG, PNG, WebP).")
        }
        src.use { s ->
            val scanned = if (s.isImage) 1 else (0 until s.pageCount).count { ai.abhify.android.ocr.ExtractionPipeline.needsOcr(runCatching { s.text(it) }.getOrDefault("")) }
            val allowed = when { scanned == 0 -> false; ocr != null -> ocr; else -> g.ocrAllowed ?: throw OcrNeeded(name, scanned) }
            val ex = if (allowed) g.withOcr {
                ai.abhify.android.ocr.ExtractionPipeline.run(s, ai.abhify.android.ocr.MlKitOcr(), profile, progress) {
                    if (g.heavy.underPressure()) "stopped because the phone ran low on memory (retry later)" else null
                }
            } else ai.abhify.android.ocr.ExtractionPipeline.run(s, null, profile, progress)
            val text = ex.text(s.isImage).replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
            if (text.isBlank()) throw DocumentException(when {
                s.isImage && !allowed -> "$name is an image: its text can only be read with text recognition, which is off."
                s.isImage -> "No text was found in $name. Try a sharper, well-lit photo taken straight on."
                !allowed -> "$name has no text layer (scanned). Allow text recognition to read it."
                else -> "No text could be read from $name."
            })
            val kind = if (s.isImage) "Image" else "PDF"
            return Read(DocText(name, kind, text.take(Documents.MAX_CHARS), text.length > Documents.MAX_CHARS), bytes.size.toLong(),
                if (s.isImage) null else s.pageCount, ex.ocrPages, ex.warnings(), sha)
        }
    }
}
