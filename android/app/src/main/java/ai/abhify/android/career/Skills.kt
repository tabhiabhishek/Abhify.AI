package ai.abhify.android.career

import org.json.JSONObject

/**
 * The local skill taxonomy (assets/skills_taxonomy.json): canonical names, aliases (= the same skill) and explicit
 * related concepts (shown separately, never counted as a match). Ambiguous words ("rest", "spring", "CV", "R") only
 * count when a context word appears in the text, or when they sit in a list with at least two other skills.
 */
class SkillTaxonomy(val skills: List<Skill>) {
    data class Skill(val name: String, val aliases: List<String>, val related: List<String>, val ambiguous: Set<String>, val context: List<String>)
    /** A skill found in a text: [term] is the exact text matched; [alias] when that wasn't the canonical name; [line] the evidence. */
    data class Found(val skill: String, val term: String, val alias: Boolean, val line: String)

    private val byKey = HashMap<String, String>().apply {
        skills.forEach { s -> (listOf(s.name) + s.aliases).forEach { t -> put(key(t), s.name); put(compact(t), s.name) } }
    }
    private val bySkill = skills.associateBy { it.name }
    private val patterns: List<Pair<Skill, List<Pair<String, Regex>>>> = skills.map { s ->
        s to (listOf(s.name) + s.aliases).distinct().map { t -> t to Regex("(?i)(?<![\\w+#.])" + t.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "(?![\\w+#])") }
    }

    /** The canonical skill for a term ("ml", "Fast API", "pytorch" → Machine Learning, FastAPI, PyTorch), or null if unknown. */
    fun normalize(term: String): String? = byKey[key(term)] ?: byKey[compact(term)]

    fun skill(name: String) = bySkill[name]

    /** Skills written in [text], each with the line that shows it. */
    fun find(text: String): List<Found> {
        val lower = text.lowercase()
        val lines = text.lines()
        val out = LinkedHashMap<String, Found>()
        for ((s, terms) in patterns) {
            for ((t, re) in terms) {
                val m = re.find(text) ?: continue
                val line = lines.firstOrNull { re.containsMatchIn(it) }?.trim().orEmpty()
                if (t.lowercase() in s.ambiguous && !contextOk(s, lower, line)) continue
                out.putIfAbsent(s.name, Found(s.name, m.value, !t.equals(s.name, ignoreCase = true), line.take(200)))
                break
            }
        }
        return out.values.toList()
    }

    private fun contextOk(s: Skill, lowerText: String, line: String): Boolean {
        if (s.context.any { lowerText.contains(it) }) return true
        // a skills list ("Python, SQL, Excel, Power BI") is context too: at least two other unambiguous skills on the line
        val others = patterns.count { (o, terms) -> o.name != s.name && o.ambiguous.isEmpty() && terms.any { it.second.containsMatchIn(line) } }
        return others >= 2 && line.count { it == ',' || it == '|' || it == '•' || it == ';' } >= 1
    }

    /** True only for an explicit relationship in the taxonomy (either direction); never for unrelated skills. */
    fun related(a: String, b: String): Boolean = a != b && (bySkill[a]?.related?.contains(b) == true || bySkill[b]?.related?.contains(a) == true)

    companion object {
        fun key(t: String) = t.lowercase().trim().replace(Regex("\\s+"), " ").trim(',', ';', ':', '(', ')', '"', '\'')
        fun compact(t: String) = key(t).replace(Regex("[\\s_\\-.]"), "")

        fun parse(json: String): SkillTaxonomy {
            val a = JSONObject(json).getJSONArray("skills")
            return SkillTaxonomy((0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                fun list(k: String) = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                Skill(o.getString("name"), list("aliases"), list("related"), list("ambiguous").map { it.lowercase() }.toSet(), list("context").map { it.lowercase() })
            })
        }

        /** Loaded once from the app's assets (AppGraph); tests load the same file. */
        @Volatile var current: SkillTaxonomy? = null
    }
}

/** How a resume covers a list of required skills. Exact and alias matches count; related ones are shown, never counted. */
data class SkillMatch(
    val required: List<String>, val resume: List<String>,
    val direct: List<SkillTaxonomy.Found>, val alias: List<SkillTaxonomy.Found>,
    /** required skill → resume skill that is only related to it */
    val related: List<Pair<String, String>>, val missing: List<String>,
) {
    val matched get() = direct.map { it.skill } + alias.map { it.skill }
    /** Whole percent, rounded to 5 (the method doesn't support finer precision). null when there is nothing to match. */
    val coverage: Int? get() = if (required.isEmpty()) null else roundTo5(100.0 * matched.size / required.size)

    companion object {
        fun roundTo5(v: Double) = (Math.round(v / 5.0) * 5).toInt().coerceIn(0, 100)

        fun of(tax: SkillTaxonomy, required: List<String>, resumeText: String): SkillMatch {
            val found = tax.find(resumeText).associateBy { it.skill }
            val req = required.distinct()
            val hit = req.mapNotNull { found[it] }
            val missing = req.filter { it !in found }
            val related = missing.flatMap { r -> found.keys.filter { tax.related(r, it) }.map { r to it } }
            return SkillMatch(req, found.keys.toList(), hit.filter { !it.alias }, hit.filter { it.alias }, related, missing)
        }
    }
}
