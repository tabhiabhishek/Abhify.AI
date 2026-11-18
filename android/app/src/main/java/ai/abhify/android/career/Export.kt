package ai.abhify.android.career

import ai.abhify.android.resume.Resume
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A resume as simple blocks for export (no IDs, keys or app data; only the text the user chose). */
object ResumeLayout {
    sealed class Block { data class Title(val text: String) : Block(); data class Heading(val text: String) : Block()
        data class Bullet(val text: String) : Block(); data class Para(val text: String) : Block() }
    enum class Template(val label: String, val bodySize: Float, val headingSize: Float, val titleSize: Float) {
        SIMPLE("Simple", 11f, 13f, 20f), COMPACT("Compact", 10f, 11.5f, 17f)
    }

    fun blocks(text: String): List<Block> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[[Page") }
        val out = mutableListOf<Block>()
        lines.forEachIndexed { i, l ->
            val heading = Resume.Section.entries.any { s -> s.headings.any { h -> l.trimEnd(':').lowercase() == h } } ||
                (l.length in 3..40 && l == l.uppercase() && l.any { it.isLetter() } && !l.contains('@'))
            out += when {
                i == 0 && !heading && l.length <= 60 -> Block.Title(l)
                heading -> Block.Heading(l.trimEnd(':').lowercase().replaceFirstChar(Char::uppercase).let { h -> if (l == l.uppercase()) l.trimEnd(':') else h })
                l.matches(Regex("^[-•*▪◦·]\\s*.+")) -> Block.Bullet(l.replace(Regex("^[-•*▪◦·]\\s*"), ""))
                else -> Block.Para(l)
            }
        }
        return out
    }

    /** Plain-text preview of what will be exported. */
    fun preview(blocks: List<Block>) = blocks.joinToString("\n") { b -> when (b) {
        is Block.Title -> b.text.uppercase(); is Block.Heading -> "\n" + b.text.uppercase(); is Block.Bullet -> "  • " + b.text; is Block.Para -> b.text } }
}

/** A minimal, valid .docx (Office Open XML) written without any library: one document part, inline formatting only. */
object DocxWriter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }                                   // XML 1.0 forbids most control characters
    private fun run(text: String, bold: Boolean, halfPoints: Int) =
        "<w:r><w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"$halfPoints\"/></w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    private fun para(content: String, spacingAfter: Int = 80, indent: Int = 0) =
        "<w:p><w:pPr><w:spacing w:after=\"$spacingAfter\"/>${if (indent > 0) "<w:ind w:left=\"$indent\" w:hanging=\"240\"/>" else ""}</w:pPr>$content</w:p>"

    fun write(blocks: List<ResumeLayout.Block>, t: ResumeLayout.Template = ResumeLayout.Template.SIMPLE): ByteArray {
        val body = blocks.joinToString("") { b -> when (b) {
            is ResumeLayout.Block.Title -> para(run(b.text, true, (t.titleSize * 2).toInt()), 160)
            is ResumeLayout.Block.Heading -> para(run(b.text, true, (t.headingSize * 2).toInt()), 60)
            is ResumeLayout.Block.Bullet -> para(run("•\t" + b.text, false, (t.bodySize * 2).toInt()), 40, 360)
            is ResumeLayout.Block.Para -> para(run(b.text, false, (t.bodySize * 2).toInt()))
        } }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1000\" w:right=\"1000\" w:bottom=\"1000\" w:left=\"1000\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun entry(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
            entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            entry("word/document.xml", doc)
        }
        return out.toByteArray()
    }
}
