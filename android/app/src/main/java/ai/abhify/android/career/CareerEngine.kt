package ai.abhify.android.career

import ai.abhify.android.resume.Resume
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

// ====================================================================== job description parsing
/** What a job description says, field by field. Empty = not found (never guessed). The user can correct every field. */
data class JobFields(
    val title: String = "", val company: String = "", val location: String = "", val employmentType: String = "",
    val minYears: Int? = null, val experience: List<String> = emptyList(),
    val required: List<String> = emptyList(), val preferred: List<String> = emptyList(),
    val responsibilities: List<String> = emptyList(), val qualifications: List<String> = emptyList(),
    val education: List<String> = emptyList(), val certifications: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject().put("title", title).put("company", company).put("location", location).put("employmentType", employmentType)
        .put("minYears", minYears ?: JSONObject.NULL).put("experience", JSONArray(experience)).put("required", JSONArray(required))
        .put("preferred", JSONArray(preferred)).put("responsibilities", JSONArray(responsibilities)).put("qualifications", JSONArray(qualifications))
        .put("education", JSONArray(education)).put("certifications", JSONArray(certifications)).toString()

    companion object {
        fun fromJson(s: String?): JobFields? = s?.let { runCatching {
            val o = JSONObject(it)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList()
            JobFields(o.optString("title"), o.optString("company"), o.optString("location"), o.optString("employmentType"),
                if (o.isNull("minYears")) null else o.optInt("minYears"), list("experience"), list("required"), list("preferred"),
                list("responsibilities"), list("qualifications"), list("education"), list("certifications"))
        }.getOrNull() }
    }
}

object JobParser {
    private enum class Sec { RESP, REQ, PREF, ABOUT, OTHER }
    private val HEAD = listOf(
        Sec.RESP to listOf("responsibilities", "key responsibilities", "what you will do", "what you'll do", "duties", "your role", "the role", "job description"),
        Sec.PREF to listOf("preferred", "preferred qualifications", "preferred skills", "nice to have", "good to have", "bonus", "bonus points", "desired skills", "plus"),
        Sec.REQ to listOf("requirements", "required", "required skills", "qualifications", "must have", "must-have", "skills", "what we are looking for",
            "what we're looking for", "who you are", "minimum qualifications", "basic qualifications", "technical skills"),
        Sec.ABOUT to listOf("about us", "about the company", "benefits", "perks", "why join us", "what we offer"),
    )
    private fun heading(l: String): Sec? {
        val t = l.trim().trimEnd(':').replace(Regex("[*#•]"), "").trim().lowercase()
        if (t.isEmpty() || t.length > 45) return null
        return HEAD.firstOrNull { (_, hs) -> hs.any { it == t } }?.first
    }
    private fun clean(l: String) = l.trim().trimStart('-', '•', '*', '·', '–', ' ').trim()
    private fun field(text: String, vararg keys: String) = text.lines().firstNotNullOfOrNull { l ->
        keys.firstNotNullOfOrNull { k -> Regex("(?i)^\\s*$k\\s*[:：-]\\s*(.+)$").find(l)?.groupValues?.get(1)?.trim() }
    }.orEmpty()

    fun parse(text: String, tax: SkillTaxonomy): JobFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bySec = linkedMapOf<Sec, MutableList<String>>()
        var cur = Sec.OTHER
        for (l in lines) { val h = heading(l); if (h != null) { cur = h; continue }; bySec.getOrPut(cur) { mutableListOf() } += clean(l) }
        val all = lines.map(::clean)
        val title = field(text, "job title", "title", "position", "role").ifEmpty {
            lines.firstOrNull { it.length in 3..80 && !it.contains(':') && heading(it) == null && !it.endsWith('.') }.orEmpty()
        }
        val company = field(text, "company", "organization", "organisation", "employer")
        val location = field(text, "location", "work location", "based in").ifEmpty {
            Regex("(?i)\\b(remote|hybrid|on-?site)\\b").find(text)?.value?.replaceFirstChar(Char::uppercase).orEmpty()
        }
        val type = Regex("(?i)\\b(full[- ]time|part[- ]time|contract(?:or)?|internship|temporary|freelance)\\b").find(text)?.value?.lowercase()?.replace(' ', '-').orEmpty()
        val years = Regex("(?i)(\\d{1,2})\\s*\\+?\\s*(?:-\\s*\\d{1,2}\\s*)?(?:years?|yrs?)").findAll(text).map { it.groupValues[1].toInt() }.filter { it in 0..40 }.toList()
        val expLines = all.filter { Regex("(?i)\\d{1,2}\\s*\\+?\\s*(?:-\\s*\\d+\\s*)?(?:years?|yrs?)|experience (?:in|with|of)").containsMatchIn(it) }.take(8)
        val eduLines = all.filter { Regex("(?i)\\b(bachelor|master|degree|b\\.?\\s?tech|m\\.?\\s?tech|b\\.?e\\.?|b\\.?sc|m\\.?sc|phd|ph\\.d|mba|graduate)\\b").containsMatchIn(it) }.take(6)
        val certLines = all.filter { Regex("(?i)\\bcertif(ied|ication|icate)").containsMatchIn(it) }.take(6)
        val prefText = (bySec[Sec.PREF].orEmpty() + all.filter { Regex("(?i)\\b(preferred|nice to have|good to have|is a plus|bonus)\\b").containsMatchIn(it) }).joinToString("\n")
        val reqText = (bySec[Sec.REQ].orEmpty() + bySec[Sec.RESP].orEmpty() + bySec[Sec.OTHER].orEmpty())
            .filterNot { l -> Regex("(?i)\\b(preferred|nice to have|good to have|is a plus|bonus)\\b").containsMatchIn(l) }.joinToString("\n")
        val preferredSkills = tax.find(prefText).map { it.skill }
        val required = tax.find(reqText).map { it.skill }.filter { it !in preferredSkills || bySec[Sec.REQ].orEmpty().any { l -> tax.find(l).any { f -> f.skill == it } } }
        return JobFields(title.take(120), company.take(120), location.take(120), type, years.minOrNull(), expLines,
            required, preferredSkills.filter { it !in required }, bySec[Sec.RESP].orEmpty().take(15),
            bySec[Sec.REQ].orEmpty().take(15), eduLines, certLines)
    }
}

// ====================================================================== resume quality
/** A transparent, rule-based score: every point comes from a rule listed in [notes]. Not a prediction of hiring. */
object ResumeQuality {
    data class Category(val name: String, val points: Int, val max: Int, val notes: List<String>) { val ratio get() = points.toDouble() / max }
    data class Report(val total: Int, val categories: List<Category>, val strengths: List<String>, val weaknesses: List<String>, val suggestions: List<String>)

    fun score(text: String, p: Resume.Profile = Resume.parse(text), tax: SkillTaxonomy? = SkillTaxonomy.current): Report {
        val s = p.sections
        fun sec(x: Resume.Section) = s[x].orEmpty()
        val bullets = text.lines().count { it.trim().matches(Regex("^[-•*▪◦].{8,}")) }
        val cats = mutableListOf<Category>()
        // completeness (15): name, email, phone, links
        run { val n = mutableListOf<String>(); var pts = 0
            if (p.name != null) pts += 4 else n += "No name found at the top."
            if (p.emails.isNotEmpty()) pts += 4 else n += "No email address."
            if (p.phones.isNotEmpty()) pts += 4 else n += "No phone number."
            if (p.links.isNotEmpty()) pts += 3 else n += "No LinkedIn/GitHub/portfolio link."
            cats += Category("Contact details", pts, 15, n) }
        // summary (10)
        run { val t = sec(Resume.Section.SUMMARY); val n = mutableListOf<String>()
            val pts = when { t.isEmpty() -> { n += "No summary section."; 0 }; t.length < 120 -> { n += "Summary is very short (${t.length} characters)."; 5 }
                t.length > 900 -> { n += "Summary is long (${t.length} characters); 2–4 sentences read better."; 7 }; else -> 10 }
            cats += Category("Professional summary", pts, 10, n) }
        // skills (15)
        run { val count = (tax?.find(sec(Resume.Section.SKILLS).ifBlank { text })?.size) ?: p.skills.size; val n = mutableListOf<String>()
            if (sec(Resume.Section.SKILLS).isEmpty()) n += "No skills section (skills were looked for in the whole text)."
            val pts = when { count >= 10 -> 15; count >= 6 -> 11; count >= 3 -> 7; count > 0 -> 3; else -> 0 } - (if (sec(Resume.Section.SKILLS).isEmpty() && count > 0) 2 else 0)
            if (count < 6) n += "$count recognized skills."
            cats += Category("Skills", pts.coerceAtLeast(0), 15, n) }
        // experience (20)
        run { val t = sec(Resume.Section.EXPERIENCE); val n = mutableListOf<String>(); var pts = 0
            if (t.isEmpty()) n += "No experience section." else {
                pts += 8
                if (p.dates.isNotEmpty()) pts += 6 else n += "No dates (month/year – month/year) found for roles."
                val expBullets = t.lines().count { it.trim().matches(Regex("^[-•*▪◦].{8,}")) || it.length in 30..250 }
                if (expBullets >= 4) pts += 6 else { pts += expBullets.coerceAtMost(3) * 2; n += "Few descriptive lines under experience ($expBullets)." }
            }
            cats += Category("Work experience", pts, 20, n) }
        // achievements (10): lines with measurable results
        run { val k = p.achievements.size; val n = mutableListOf<String>()
            val pts = when { k >= 4 -> 10; k >= 2 -> 7; k == 1 -> 4; else -> 0 }; if (k < 2) n += "$k line(s) with measurable results (numbers, %, scale)."
            cats += Category("Achievements", pts, 10, n) }
        // projects (10)
        run { val t = sec(Resume.Section.PROJECTS); val n = mutableListOf<String>()
            val pts = when { t.isEmpty() -> { n += "No projects section."; 0 }; t.length < 150 -> { n += "Projects are described very briefly."; 5 }; else -> 10 }
            cats += Category("Projects", pts, 10, n) }
        // education (10)
        run { val t = sec(Resume.Section.EDUCATION); val n = mutableListOf<String>()
            val pts = when { t.isEmpty() -> { n += "No education section."; 0 }; !Regex("(19|20)\\d{2}").containsMatchIn(t) -> { n += "No year in education."; 7 }; else -> 10 }
            cats += Category("Education", pts, 10, n) }
        // certifications (5) – optional
        run { val has = sec(Resume.Section.CERTIFICATIONS).isNotEmpty()
            cats += Category("Certifications", if (has) 5 else 0, 5, if (has) emptyList() else listOf("No certifications section (optional; only add real ones).")) }
        // readability (5)
        run { val n = mutableListOf<String>(); var pts = 5
            val len = text.length
            if (len < 1200) { pts -= 2; n += "Short resume (${len} characters)." }
            if (len > 9000) { pts -= 2; n += "Long resume (${len} characters); 1–2 pages is usual." }
            val longLines = text.lines().count { it.length > 300 }
            if (longLines > 2) { pts -= 1; n += "$longLines very long paragraphs; bullets read better." }
            if (bullets < 3) { pts -= 1; n += "Few bullet points." }
            cats += Category("Readability", pts.coerceAtLeast(0), 5, n) }
        val total = SkillMatch.roundTo5(cats.sumOf { it.points }.toDouble())
        val strengths = cats.filter { it.ratio >= 0.8 }.map { "${it.name}: ${it.points}/${it.max}" }
        val weak = cats.filter { it.ratio < 0.5 }
        val suggestions = weak.flatMap { c -> c.notes.map { "${c.name} – $it" + when (c.name) {
            "Achievements" -> " Add numbers you actually have (scale, time saved, results); never estimate."
            "Certifications" -> ""
            else -> "" } } }
        return Report(total, cats, strengths, weak.map { "${it.name}: ${it.points}/${it.max}" }, suggestions)
    }
}

/** Total professional experience from the experience section's date ranges (overlaps merged). null when no dates are found. */
object Experience {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val RANGE = Regex("(?i)\\b((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{4})|(\\d{1,2})/(\\d{4})|((?:19|20)\\d{2}))\\s*(?:[-–—]|to)\\s*((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{4}|\\d{1,2}/\\d{4}|(?:19|20)\\d{2}|present|current|now|till date)")

    private fun ym(s: String, today: YearMonth, end: Boolean): YearMonth? {
        val t = s.trim().lowercase()
        if (t in setOf("present", "current", "now", "till date")) return today
        Regex("^([a-z]+)\\.?\\s+(\\d{4})$").find(t)?.let { m -> val i = MONTHS.indexOf(m.groupValues[1].take(3)); if (i >= 0) return YearMonth.of(m.groupValues[2].toInt(), i + 1) }
        Regex("^(\\d{1,2})/(\\d{4})$").find(t)?.let { m -> val mo = m.groupValues[1].toInt(); if (mo in 1..12) return YearMonth.of(m.groupValues[2].toInt(), mo) }
        Regex("^(\\d{4})$").find(t)?.let { return YearMonth.of(it.value.toInt(), if (end) 12 else 1) }
        return null
    }

    fun years(experienceText: String, today: LocalDate = LocalDate.now()): Double? {
        val now = YearMonth.from(today)
        val spans = RANGE.findAll(experienceText).mapNotNull { m ->
            val a = ym(m.groupValues[1], now, false); val b = ym(m.groupValues[6], now, true)
            if (a == null || b == null || b < a || a > now) null else a to minOf(b, now)
        }.sortedBy { it.first }.toList()
        if (spans.isEmpty()) return null
        var months = 0L; var curStart = spans[0].first; var curEnd = spans[0].second
        for ((s, e) in spans.drop(1)) { if (s <= curEnd) { if (e > curEnd) curEnd = e } else { months += curStart.until(curEnd, java.time.temporal.ChronoUnit.MONTHS) + 1; curStart = s; curEnd = e } }
        months += curStart.until(curEnd, java.time.temporal.ChronoUnit.MONTHS) + 1
        return months / 12.0
    }
}

// ====================================================================== resume ↔ job matching
object MatchEngine {
    /** Weights in percent; dimensions the job description doesn't specify are left out and the rest re-weighted. */
    data class Weights(val required: Int = 40, val preferred: Int = 15, val experience: Int = 20, val projects: Int = 10, val education: Int = 10, val responsibilities: Int = 5)

    data class Dimension(val name: String, val weight: Int, val score: Int?, val explanation: String)
    /** One requirement with what the resume shows. */
    data class Evidence(val requirement: String, val result: String, val evidence: String?)
    data class Result(val overall: Int?, val dimensions: List<Dimension>, val skills: SkillMatch, val preferred: SkillMatch,
                      val evidence: List<Evidence>, val strong: List<String>, val gaps: List<String>, val years: Double?)

    private val STOP = setOf("and", "or", "the", "a", "an", "of", "to", "in", "for", "with", "on", "at", "by", "we", "you", "our", "your", "will", "be", "is",
        "are", "as", "from", "that", "this", "using", "work", "working", "team", "teams", "strong", "experience", "ability", "good", "knowledge", "etc")
    private fun terms(s: String) = Regex("[\\p{L}\\p{N}+#.]{3,}").findAll(s.lowercase()).map { it.value.trimEnd('.') }.filter { it !in STOP }.toSet()

    fun match(resumeText: String, jd: JobFields, tax: SkillTaxonomy, w: Weights = Weights(), today: LocalDate = LocalDate.now()): Result {
        val p = Resume.parse(resumeText)
        val sec = { s: Resume.Section -> p.sections[s].orEmpty() }
        val req = SkillMatch.of(tax, jd.required, resumeText)
        val pref = SkillMatch.of(tax, jd.preferred, resumeText)
        val dims = mutableListOf<Dimension>()

        dims += Dimension("Required skills", w.required, req.coverage, if (jd.required.isEmpty()) "The job description lists no recognizable required skills."
            else "${req.matched.size} of ${jd.required.size} required skills appear in the resume (exact or alias).")
        dims += Dimension("Preferred skills", w.preferred, pref.coverage, if (jd.preferred.isEmpty()) "No preferred skills listed." else "${pref.matched.size} of ${jd.preferred.size} preferred skills appear.")

        val years = Experience.years(sec(Resume.Section.EXPERIENCE).ifBlank { resumeText }, today)
        val titleTerms = terms(jd.title)
        val titleHit = if (titleTerms.isEmpty()) null else titleTerms.count { it in terms(sec(Resume.Section.EXPERIENCE) + " " + sec(Resume.Section.SUMMARY)) }.toDouble() / titleTerms.size
        val yearsScore = jd.minYears?.let { need -> if (need == 0) 1.0 else years?.let { (it / need).coerceAtMost(1.0) } ?: 0.0 }
        val expScore = when { yearsScore != null && titleHit != null -> 0.7 * yearsScore + 0.3 * titleHit; yearsScore != null -> yearsScore; titleHit != null -> titleHit; else -> null }
        dims += Dimension("Experience relevance", w.experience, expScore?.let { SkillMatch.roundTo5(it * 100) }, buildString {
            if (jd.minYears != null) append("Job asks for ${jd.minYears}+ years; " + (years?.let { "resume dates add up to about %.1f years. ".format(it) } ?: "no dated roles found in the resume. "))
            if (titleHit != null) append("${(titleHit * titleTerms.size).toInt()} of ${titleTerms.size} job-title words appear in your experience or summary.")
            if (isEmpty()) append("The job description gives no years or title to compare.")
        })

        val projectsText = sec(Resume.Section.PROJECTS)
        val projScore = when { jd.required.isEmpty() -> null; projectsText.isEmpty() -> 0.0
            else -> jd.required.count { r -> tax.find(projectsText).any { it.skill == r } }.toDouble() / jd.required.size }
        dims += Dimension("Project relevance", w.projects, projScore?.let { SkillMatch.roundTo5(it * 100) },
            if (projectsText.isEmpty()) "No projects section found." else "Required skills used in your projects section.")

        val degreeJd = jd.education.joinToString(" ").lowercase()
        val eduRes = (sec(Resume.Section.EDUCATION) + " " + sec(Resume.Section.CERTIFICATIONS)).lowercase()
        val wantsMaster = Regex("master|m\\.?\\s?tech|m\\.?sc|mba|\\bms\\b").containsMatchIn(degreeJd)
        val wantsBachelor = Regex("bachelor|b\\.?\\s?tech|b\\.?e\\b|b\\.?sc|degree|graduate").containsMatchIn(degreeJd)
        val wantsPhd = Regex("phd|ph\\.d|doctor").containsMatchIn(degreeJd)
        val hasMaster = Regex("master|m\\.?\\s?tech|m\\.?sc|mba|\\bms\\b|m\\.s\\.").containsMatchIn(eduRes)
        val hasBachelor = hasMaster || Regex("bachelor|b\\.?\\s?tech|b\\.?e\\b|b\\.?sc|b\\.?a\\b|b\\.?com|bca|degree").containsMatchIn(eduRes)
        val hasPhd = Regex("phd|ph\\.d|doctor").containsMatchIn(eduRes)
        val certTerms = jd.certifications.flatMap { terms(it) }.toSet() - setOf("certification", "certified", "certificate", "preferred", "required")
        val eduScore: Double? = when {
            !wantsMaster && !wantsBachelor && !wantsPhd && certTerms.isEmpty() -> null
            else -> {
                val deg = when { wantsPhd -> if (hasPhd) 1.0 else 0.0; wantsMaster -> if (hasMaster) 1.0 else if (hasBachelor) 0.5 else 0.0; wantsBachelor -> if (hasBachelor) 1.0 else 0.0; else -> null }
                val cert = if (certTerms.isEmpty()) null else certTerms.count { it in eduRes }.toDouble() / certTerms.size
                listOfNotNull(deg, cert).average()
            }
        }
        dims += Dimension("Education and certifications", w.education, eduScore?.let { SkillMatch.roundTo5(it * 100) },
            if (eduScore == null) "No degree or certification requirement found." else "Degree level and certification names compared with your education section.")

        val resTerms = terms(sec(Resume.Section.EXPERIENCE) + " " + sec(Resume.Section.PROJECTS) + " " + sec(Resume.Section.SUMMARY))
        val respAligned = jd.responsibilities.count { r -> terms(r).count { it in resTerms } >= 2 }
        dims += Dimension("Responsibility alignment", w.responsibilities,
            if (jd.responsibilities.isEmpty()) null else SkillMatch.roundTo5(100.0 * respAligned / jd.responsibilities.size),
            if (jd.responsibilities.isEmpty()) "No responsibilities section found." else "$respAligned of ${jd.responsibilities.size} responsibilities share key words with your experience or projects.")

        val assessed = dims.filter { it.score != null }
        val overall = if (assessed.isEmpty()) null else SkillMatch.roundTo5(assessed.sumOf { it.score!! * it.weight }.toDouble() / assessed.sumOf { it.weight })

        val evidence = jd.required.map { r ->
            val d = req.direct.firstOrNull { it.skill == r } ?: req.alias.firstOrNull { it.skill == r }
            val rel = req.related.filter { it.first == r }.map { it.second }
            when {
                d != null && !d.alias -> Evidence(r, "Direct evidence found.", d.line)
                d != null -> Evidence(r, "Evidence found under another name (\"${d.term}\").", d.line)
                rel.isNotEmpty() -> Evidence(r, "Related experience only (${rel.joinToString()}), not the same skill.", null)
                else -> Evidence(r, "No supporting evidence found in the resume. This doesn't mean you lack it: add it only if you have it.", null)
            }
        }
        val strong = req.matched + pref.matched
        val gaps = req.missing.map { "Required: $it" } + pref.missing.map { "Preferred: $it" } +
            (if (jd.minYears != null && (years ?: 0.0) < jd.minYears) listOf("Experience: the job asks for ${jd.minYears}+ years" + (years?.let { "; your dated roles add up to about %.1f".format(it) } ?: "; no dated roles found")) else emptyList()) +
            (if (eduScore != null && eduScore < 1.0) listOf("Education/certifications: not fully shown in your resume") else emptyList())
        return Result(overall, dims, req, pref, evidence, strong, gaps, years)
    }
}

/** Deterministic, explainable recommendations from a match (AI can add project ideas, always labelled as suggestions). */
object CareerAdvice {
    data class Rec(val kind: String, val recommendation: String, val reason: String, val requirement: String, val evidence: String, val nextAction: String)

    fun of(m: MatchEngine.Result, jd: JobFields, quality: ResumeQuality.Report?): List<Rec> = buildList {
        fun reqLine(skill: String) = (jd.qualifications + jd.responsibilities + jd.experience).firstOrNull { it.contains(skill, true) } ?: "Required skill: $skill"
        m.skills.missing.forEach { s ->
            val rel = m.skills.related.filter { it.first == s }.map { it.second }
            add(Rec("Skill to improve", "Strengthen $s", "The job requires $s and the resume shows no evidence of it.", reqLine(s),
                if (rel.isEmpty()) "None found" else "Related: ${rel.joinToString()}",
                "If you already use $s, describe where and how in your experience or projects (only if true). Otherwise make it a learning priority."))
        }
        m.preferred.missing.take(5).forEach { s ->
            add(Rec("Preferred topic", "Consider $s", "Listed as preferred (not mandatory).", "Preferred skill: $s", "None found", "Lower priority than the required skills."))
        }
        quality?.categories?.filter { it.ratio < 0.5 }?.forEach { c ->
            add(Rec("Resume section", "Improve: ${c.name}", c.notes.joinToString(" ").ifBlank { "Low score in the quality check." }, "General resume quality",
                "${c.points}/${c.max} in the quality check", "Use Improve → ${c.name} to get a rewrite you can review."))
        }
        m.skills.matched.take(5).forEach { s ->
            add(Rec("Interview topic", "Prepare to discuss $s", "It is required and your resume claims it, so expect questions.", reqLine(s),
                (m.skills.direct + m.skills.alias).firstOrNull { it.skill == s }?.line ?: "",
                "Practice in Interview with topic \"From job description\"."))
        }
    }

    /** Learning priorities: missing required skills first (by how often the JD mentions them), then preferred. */
    fun learningPriorities(m: MatchEngine.Result, jdText: String): List<String> =
        m.skills.missing.sortedByDescending { s -> Regex("(?i)" + Regex.escape(s)).findAll(jdText).count() } + m.preferred.missing
}

// ====================================================================== structured AI output
/** Safe parsing of model JSON: fenced or embedded objects, type and range checks; invalid output is never saved. */
object AiJson {
    /** The first balanced {...} object in [text] (ignores ```json fences and prose), or null. */
    fun extract(text: String): JSONObject? {
        val t = text.replace("```json", "```").let { s -> Regex("(?s)```\\s*(\\{.*?})\\s*```").find(s)?.groupValues?.get(1) ?: s }
        var start = t.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var esc = false
            for (i in start until t.length) {
                val c = t[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { runCatching { return JSONObject(t.substring(start, i + 1)) }; break } } }
            }
            start = t.indexOf('{', start + 1)
        }
        return null
    }

    data class Feedback(val scores: Map<String, Int>, val strengths: List<String>, val missing: List<String>, val structure: String, val recommendations: List<String>)
    val RUBRIC = listOf("correctness", "completeness", "clarity", "practical", "communication")

    /** Validates the interview-feedback JSON. Returns the feedback, or the reason it was rejected. */
    fun feedback(text: String): Pair<Feedback?, String?> {
        val o = extract(text) ?: return null to "The AI didn't return the expected JSON."
        val sc = o.optJSONObject("scores") ?: return null to "Missing \"scores\"."
        val scores = RUBRIC.associateWith { k -> if (!sc.has(k)) return null to "Missing score \"$k\"."
            val v = sc.optInt(k, -1); if (v !in 1..5) return null to "Score \"$k\" must be 1–5 (was ${sc.opt(k)})."; v }
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 600 } }
        val strengths = list("strengths") ?: return null to "Missing \"strengths\"."
        val missing = list("missing") ?: return null to "Missing \"missing\"."
        val recs = list("recommendations") ?: emptyList()
        val structure = o.optString("structure").trim()
        if (strengths.isEmpty() && missing.isEmpty()) return null to "Empty feedback."
        return Feedback(scores, strengths, missing, structure, recs) to null
    }

    const val FEEDBACK_FORMAT = "Reply with ONLY this JSON (no other text): {\"scores\": {\"correctness\": 1-5, \"completeness\": 1-5, \"clarity\": 1-5, " +
        "\"practical\": 1-5, \"communication\": 1-5}, \"strengths\": [\"…\"], \"missing\": [\"…\"], \"structure\": \"a suggested answer structure\", " +
        "\"recommendations\": [\"…\"]}"
}

// ====================================================================== section improvement
object SectionImprove {
    fun prompt(section: String, original: String, extraFacts: String) = "${Resume.RULES}\n\nRewrite ONLY this \"$section\" section of a resume to be clearer and more " +
        "professional. Keep every fact, name, date and number exactly; add nothing new; no keyword stuffing; keep the user's meaning. " +
        "If a stronger version needs a detail you don't have (a number, a tool), write [ask: …] instead of inventing it.\n" +
        (if (extraFacts.isNotBlank()) "Extra facts the user confirmed: $extraFacts\n" else "") +
        "Reply in exactly two parts:\n## Improved\n(the rewritten section)\n## Changes\n- (each important change and why)\n\n<section>\n$original\n</section>"

    /** (improved, changes) from the model's two-part reply, or null when the format wasn't followed. */
    fun parse(text: String): Pair<String, String>? {
        val imp = Regex("(?is)##\\s*Improved\\s*\\n(.*?)(?:\\n##\\s*Changes|$)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length > 10 } ?: return null
        val ch = Regex("(?is)##\\s*Changes\\s*\\n(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return imp to ch
    }

    /** The full resume with one section's body replaced (the original stays untouched elsewhere). */
    fun replaceSection(resume: String, section: Resume.Section, newBody: String): String {
        val lines = resume.lines()
        val heads = lines.indices.filter { i -> Resume.Section.entries.any { s -> s.headings.any { h -> lines[i].trim().trimEnd(':').lowercase() == h } } }
        val start = heads.firstOrNull { i -> section.headings.any { lines[i].trim().trimEnd(':').lowercase() == it } } ?: return resume.trimEnd() + "\n\n${section.label}\n$newBody\n"
        val end = heads.firstOrNull { it > start } ?: lines.size
        return (lines.subList(0, start + 1) + newBody.trim().lines() + listOf("") + lines.subList(end, lines.size)).joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }
}
