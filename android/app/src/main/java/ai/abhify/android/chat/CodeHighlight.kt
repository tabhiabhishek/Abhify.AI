package ai.abhify.android.chat

/** Lightweight syntax highlighting for code blocks in answers. Pure (unit-tested); colours are applied in the UI. */
object CodeHighlight {
    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, FUNCTION }
    data class Token(val text: String, val kind: Kind)

    private val keywords = mapOf(
        "python" to "def class return if elif else for while in not and or is import from as with try except finally raise lambda yield pass break continue None True False global nonlocal async await assert del",
        "kotlin" to "fun val var class object interface return if else when for while in is as try catch finally throw import package null true false this super override private public internal data sealed suspend",
        "java" to "public private protected class interface extends implements return if else for while new static final void int long double float boolean char try catch finally throw throws import package null true false this super",
        "js" to "function const let var return if else for while of in new class extends import export from default try catch finally throw await async null undefined true false this typeof instanceof",
        "c" to "int long double float char void return if else for while do switch case break continue struct typedef const static unsigned include define sizeof nullptr auto class public private template namespace using new delete true false",
        "bash" to "if then else elif fi for in do done while case esac function return export local echo exit",
        "sql" to "select from where and or not insert into values update set delete create table index join left right inner outer on group by order having limit as distinct null primary key",
    )
    private val aliases = mapOf("py" to "python", "python3" to "python", "kt" to "kotlin", "kts" to "kotlin", "javascript" to "js", "ts" to "js",
        "typescript" to "js", "jsx" to "js", "tsx" to "js", "cpp" to "c", "c++" to "c", "h" to "c", "sh" to "bash", "shell" to "bash", "zsh" to "bash")

    fun language(tag: String): String? = tag.lowercase().trim().let { aliases[it] ?: it }.takeIf { it in keywords || it == "json" }

    fun tokenize(code: String, tag: String): List<Token> {
        val lang = language(tag) ?: return listOf(Token(code, Kind.PLAIN))
        val kw = keywords[lang]?.split(' ')?.toSet() ?: emptySet()
        val ci = lang == "sql"
        val hashComment = lang in setOf("python", "bash")
        val slashComment = lang in setOf("kotlin", "java", "js", "c")
        val out = mutableListOf<Token>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += Token(plain.toString(), Kind.PLAIN); plain.clear() } }
        fun add(t: String, k: Kind) { flush(); out += Token(t, k) }
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                (hashComment && c == '#') || (slashComment && code.startsWith("//", i)) || (ci && code.startsWith("--", i)) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }; add(code.substring(i, end), Kind.COMMENT); i = end
                }
                slashComment && code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }; add(code.substring(i, end), Kind.COMMENT); i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val triple = lang == "python" && code.startsWith("$c$c$c", i)
                    val quote = if (triple) "$c$c$c" else c.toString()
                    var j = i + quote.length
                    while (j < code.length && !code.startsWith(quote, j)) { if (code[j] == '\\') j++; if (!triple && code[j.coerceAtMost(code.length - 1)] == '\n') break; j++ }
                    val end = (j + quote.length).coerceAtMost(code.length); add(code.substring(i, end), Kind.STRING); i = end
                }
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    var j = i; while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    add(code.substring(i, j), Kind.NUMBER); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i; while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val w = code.substring(i, j)
                    val isKw = if (ci) w.lowercase() in kw else w in kw
                    val isCall = !isKw && j < code.length && code[j] == '('
                    when { isKw -> add(w, Kind.KEYWORD); isCall -> add(w, Kind.FUNCTION); else -> plain.append(w) }
                    i = j
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
