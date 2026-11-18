package ai.abhify.android.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** A document's text, extracted on the phone. Nothing here touches the network. */
data class DocText(val name: String, val kind: String, val text: String, val truncated: Boolean) {
    val chars get() = text.length
}

class DocumentException(message: String) : Exception(message)

/**
 * Local text extraction for chat attachments and the document library. Supported: plain text (TXT, MD, CSV, JSON,
 * code), DOCX, and PDF text layers (through [pdf], set by the app: pdfbox-android). Not supported (refused with a
 * clear message, never faked): images and scanned PDFs, which need OCR.
 */
object Documents {
    /** PDF text extractor (bytes → text with page markers). Null where PDF support isn't wired in (JVM tests). */
    @Volatile var pdf: ((ByteArray) -> String)? = null

    const val MAX_BYTES = 10L * 1024 * 1024          // file size limit
    const val MAX_CHARS = 200_000                   // text kept per document
    private const val MAX_UNZIPPED = 30L * 1024 * 1024  // zip-bomb guard for DOCX

    val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "log", "py", "kt", "java", "js", "ts", "html", "xml", "yaml", "yml", "ipynb")
    /** MIME types offered in the system picker. */
    val PICKER_TYPES = arrayOf("text/*", "application/json", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "image/jpeg", "image/png", "image/webp")

    fun kindOf(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            ext == "pdf" || mime == "application/pdf" -> "PDF"
            ext in TEXT_EXT || mime?.startsWith("text/") == true || mime == "application/json" -> "Text"
            else -> null
        }
    }

    fun extract(name: String, mime: String?, bytes: ByteArray): DocText {
        if (bytes.size > MAX_BYTES) throw DocumentException("$name is larger than ${MAX_BYTES / 1024 / 1024} MB.")
        val ext = name.substringAfterLast('.', "").lowercase()
        if ((ext == "pdf" || mime == "application/pdf") && pdf == null) throw DocumentException("PDF reading isn't available yet. Save the PDF as TXT or DOCX and attach that.")
        if (mime?.startsWith("image/") == true || ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic"))
            throw DocumentException("Reading text from images (OCR) isn't available yet.")
        val kind = kindOf(name, mime) ?: throw DocumentException("$name isn't a supported document. Use TXT, MD, CSV or DOCX.")
        val raw = when (kind) { "DOCX" -> docx(bytes); "PDF" -> pdf!!(bytes); else -> text(bytes) }
        val clean = raw.replace("\r\n", "\n").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) throw DocumentException("$name has no readable text.")
        return DocText(name, kind, clean.take(MAX_CHARS), clean.length > MAX_CHARS)
    }

    /** UTF-8 (with or without BOM) or UTF-16 with BOM; anything else falls back to Latin-1 so nothing throws. */
    fun text(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> {
            val dec = Charsets.UTF_8.newDecoder()
            runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrElse { String(b, Charsets.ISO_8859_1) }
        }
    }.replace("\u0000", "")

    /** DOCX = a zip whose word/document.xml holds paragraphs (w:p) of text runs (w:t). */
    fun docx(bytes: ByteArray): String {
        var xml: String? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var total = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name != "word/document.xml") continue
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = z.read(buf); if (n < 0) break
                        total += n; if (total > MAX_UNZIPPED) throw DocumentException("This DOCX is too large when unpacked.")
                        out.write(buf, 0, n)
                    }
                    xml = out.toString("UTF-8"); break
                }
            }
        } catch (e: DocumentException) { throw e } catch (e: Exception) { throw DocumentException("This file isn't a valid DOCX document.") }
        val x = xml ?: throw DocumentException("This file isn't a valid DOCX document.")
        val sb = StringBuilder()
        // walk the tags in order: text runs, tabs, breaks and paragraph ends
        val tag = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab/>|<w:br/>|<w:cr/>|</w:p>|<w:t(?:\s[^>]*)?/>""")
        for (m in tag.findAll(x)) {
            val v = m.value
            when {
                v.startsWith("</w:p") -> sb.append('\n')
                v == "<w:tab/>" -> sb.append('\t')
                v == "<w:br/>" || v == "<w:cr/>" -> sb.append('\n')
                else -> sb.append(unescape(m.groupValues[1]))
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m -> runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }.getOrDefault("") }
        .replace("&amp;", "&")

    /**
     * The parts of [text] that fit in [budget] characters: the whole document when it fits, otherwise the
     * ~1200-character sections that share the most words with [question], kept in document order.
     */
    fun excerpt(text: String, question: String, budget: Int): Excerpt {
        if (text.length <= budget) return Excerpt(text, whole = true)
        val chunks = chunk(text, 1200)
        val words = terms(question)
        val scored = chunks.withIndex().map { (i, c) ->
            val t = terms(c)
            i to (words.count { it in t } * 10 + (if (i == 0) 1 else 0))          // the opening section breaks ties (titles, abstracts)
        }.sortedByDescending { it.second }
        val picked = mutableListOf<Int>(); var used = 0
        for ((i, _) in scored) { val n = chunks[i].length + 8; if (used + n > budget) continue; picked += i; used += n }
        return Excerpt(picked.sorted().joinToString("\n[…]\n") { chunks[it] }, whole = false)
    }

    data class Excerpt(val text: String, val whole: Boolean)

    private fun chunk(text: String, size: Int): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        for (para in text.split("\n")) {
            if (cur.length + para.length > size && cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }
            if (para.length > size) para.chunked(size).forEach { out += it } else cur.append(para).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "what", "how", "why", "does", "do", "this", "that", "for", "on", "with", "it", "me", "about", "document", "file")
    private fun terms(s: String) = Regex("[\\p{L}\\p{N}]{2,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()
}
