package ai.abhify.android.chat

/**
 * The Markdown that AI models actually produce: headings (1–6, also "Title\n---"), paragraphs with line breaks,
 * nested bullet / numbered / task lists, block quotes, rules, fenced code (``` or ~~~), GitHub tables, and inline
 * **bold**, *italic*, ***both***, ~~strike~~, `code`, [links](https://…), bare https:// links and \escapes. A few
 * HTML tags small models emit (<br>, <b>, <i>, <code>…) become their Markdown meaning; any other tag stays visible text
 * (never rendered as HTML). Pure Kotlin (unit-tested); rendering is in ui/MarkdownText.kt.
 */
object Markdown {
    sealed class Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block()
        /** A paragraph; single line breaks inside it are kept as "\n" (chat-style), not joined into one long line. */
        data class Paragraph(val spans: List<Span>) : Block()
        /** [depth] 0 = top level; [task] = null for a normal item, else checked or not ("- [x] …"). */
        data class Bullet(val spans: List<Span>, val depth: Int = 0, val task: Boolean? = null) : Block()
        data class Numbered(val number: Int, val spans: List<Span>, val depth: Int = 0) : Block()
        data class Code(val language: String, val text: String) : Block()
        data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block()
        data class Quote(val spans: List<Span>) : Block()
        object Rule : Block()
    }

    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false,
                    val strike: Boolean = false, val link: String? = null)

    private val fence = Regex("^\\s*(```|~~~)\\s*([\\w+#.-]*)")
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val setext1 = Regex("^\\s{0,3}=+\\s*$")
    private val setext2 = Regex("^\\s{0,3}-{2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private fun isTableRow(l: String) = l.contains('|') && l.isNotBlank()
    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()) }
    private fun depthOf(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Paragraph(inline(para.toString().trim('\n', ' '))); para.clear() }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            fence.find(raw)?.let { m ->
                flush()
                val marker = m.groupValues[1]; val lang = m.groupValues[2]
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) { code.appendLine(lines[i]); i++ }
                out += Block.Code(lang, code.toString().trimEnd('\n'))
                i++                                   // skip the closing fence (or run off the end of an unfinished stream)
                return@let
            }?.let { continue }
            val line = html(raw)
            if (isTableRow(line) && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('-')) {   // GitHub-style table
                flush()
                val header = cells(line)
                val rows = mutableListOf<List<List<Span>>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i])) {
                    rows += cells(html(lines[i])).let { r -> r.take(header.size) + List((header.size - r.size).coerceAtLeast(0)) { emptyList() } }; i++
                }
                out += Block.Table(header, rows)
                continue
            }
            // "Title" underlined with === / --- (setext): the paragraph above becomes a heading
            if (para.isNotBlank() && !para.contains('\n') && (setext1.matches(line) || setext2.matches(line))) {
                out += Block.Heading(if (setext1.matches(line)) 1 else 2, inline(para.toString().trim())); para.clear(); i++; continue
            }
            if (rule.matches(line)) { flush(); out += Block.Rule; i++; continue }
            quote.matchEntire(line)?.let {
                flush()
                val q = StringBuilder(it.groupValues[1])
                while (i + 1 < lines.size && quote.matches(lines[i + 1])) { i++; q.append('\n').append(quote.matchEntire(html(lines[i]))!!.groupValues[1].removePrefix(">").trimStart()) }
                out += Block.Quote(inline(q.toString().trim()))
                i++; return@let
            }?.let { continue }
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                h != null -> { flush(); out += Block.Heading(h.groupValues[1].length, inline(h.groupValues[2].trim())) }
                b != null -> {
                    flush()
                    val t = task.matchEntire(b.groupValues[2].trim())
                    out += if (t != null) Block.Bullet(inline(t.groupValues[2].trim()), depthOf(b.groupValues[1]), t.groupValues[1] != " ")
                        else Block.Bullet(inline(b.groupValues[2].trim()), depthOf(b.groupValues[1]))
                }
                n != null -> { flush(); out += Block.Numbered(n.groupValues[2].toInt(), inline(n.groupValues[3].trim()), depthOf(n.groupValues[1])) }
                line.isBlank() -> flush()
                // an indented line right after a list item continues that item (it isn't a new paragraph)
                para.isEmpty() && line.startsWith("  ") && out.lastOrNull().let { it is Block.Bullet || it is Block.Numbered } -> {
                    val extra = inline(line.trim())
                    out[out.lastIndex] = when (val last = out.last()) {
                        is Block.Bullet -> last.copy(spans = last.spans + Span("\n") + extra)
                        is Block.Numbered -> last.copy(spans = last.spans + Span("\n") + extra)
                        else -> last
                    }
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line.trim()) }
            }
            i++
        }
        flush()
        return out
    }

    /** The few HTML tags models emit, outside `code`: <br> → line break, <b>/<strong>, <i>/<em>, <code>, <s>/<del>; <p>, <u> dropped. */
    fun html(line: String): String {
        if (!line.contains('<')) return line
        val parts = line.split('`')
        return parts.mapIndexed { idx, p ->
            if (idx % 2 == 1) p else p.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</?(strong|b)>"), "**").replace(Regex("(?i)</?(em|i)>"), "*")
                .replace(Regex("(?i)</?code>"), "`").replace(Regex("(?i)</?(s|del|strike)>"), "~~")
                .replace(Regex("(?i)</?(p|u|span)>"), "")
        }.joinToString("`")
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~<"
    private val bareUrl = Regex("https?://[^\\s<>()\\[\\]]+")

    /** Inline spans. Unclosed markers (common while a reply is still streaming) are kept as text. */
    fun inline(s: String): List<Span> {
        val out = mutableListOf<Span>()
        val buf = StringBuilder()
        fun plain() { if (buf.isNotEmpty()) { out += Span(buf.toString()); buf.clear() } }
        fun styled(inner: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false) {
            plain(); inline(inner).forEach { out += it.copy(bold = it.bold || bold, italic = it.italic || italic, strike = it.strike || strike) }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> { buf.append(s[i + 1]); i += 2 }
                c == '`' && s.indexOf('`', i + 1) > i + 1 -> {
                    val end = s.indexOf('`', i + 1); plain(); out += Span(s.substring(i + 1, end), code = true); i = end + 1
                }
                s.startsWith("***", i) && s.indexOf("***", i + 3) > i + 3 -> { val end = s.indexOf("***", i + 3); styled(s.substring(i + 3, end), bold = true, italic = true); i = end + 3 }
                s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> { val end = s.indexOf("**", i + 2); styled(s.substring(i + 2, end), bold = true); i = end + 2 }
                // __bold__ only around words with spaces, so python's __init__ stays as written
                s.startsWith("__", i) && (i == 0 || !s[i - 1].isLetterOrDigit()) && s.indexOf("__", i + 2).let { e -> e > i + 2 && s.substring(i + 2, e).contains(' ') } -> {
                    val end = s.indexOf("__", i + 2); styled(s.substring(i + 2, end), bold = true); i = end + 2
                }
                s.startsWith("~~", i) && s.indexOf("~~", i + 2) > i + 2 -> { val end = s.indexOf("~~", i + 2); styled(s.substring(i + 2, end), strike = true); i = end + 2 }
                c == '[' && Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").find(s.substring(i))?.let { m ->
                    plain(); inline(m.groupValues[1]).forEach { out += it.copy(link = m.groupValues[2]) }; i += m.value.length; true } == true -> {}
                (c == 'h') && (i == 0 || !s[i - 1].isLetterOrDigit()) && bareUrl.find(s, i)?.takeIf { it.range.first == i }?.let { m ->
                    val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
                    plain(); out += Span(url, link = url); i += url.length; true } == true -> {}
                // single * or _ italic: not inside words or runs of the same marker (snake_case, __init__, 2 * 3 stay as written)
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != c &&
                    (i == 0 || !(s[i - 1].isLetterOrDigit() || s[i - 1] == c)) && s.indexOf(c, i + 1).let { e ->
                        e > i + 1 && s[e - 1] != ' ' && (e + 1 >= s.length || !(s[e + 1].isLetterOrDigit() || s[e + 1] == c)) } -> {
                    val end = s.indexOf(c, i + 1); styled(s.substring(i + 1, end), italic = true); i = end + 1
                }
                else -> { buf.append(c); i++ }
            }
        }
        plain()
        return out
    }

    /** Plain text for text-to-speech: no markers, no code blocks read aloud character by character. */
    fun toSpeech(text: String): String = parse(text).mapNotNull { b ->
        when (b) {
            is Block.Code -> "There is a code example on screen"
            is Block.Heading -> b.spans.joinToString("") { it.text }
            is Block.Paragraph -> b.spans.joinToString("") { it.text }
            is Block.Bullet -> b.spans.joinToString("") { it.text }
            is Block.Numbered -> b.spans.joinToString("") { it.text }
            is Block.Quote -> b.spans.joinToString("") { it.text }
            is Block.Table -> "There is a table on screen"
            Block.Rule -> null
        }
    }.joinToString(". ").replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()
}
