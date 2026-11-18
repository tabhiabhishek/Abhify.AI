package ai.abhify.android.resume

/**
 * Resume Assistant logic that runs without AI (so it can't invent anything): contact details, sections and skills
 * are read by rules from the user's own text; AI output is checked against that text ("grounding") and every number,
 * date or skill that isn't in the resume is flagged for the user.
 */
object Resume {
    enum class Section(val label: String, val headings: List<String>) {
        SUMMARY("Summary", listOf("summary", "professional summary", "profile", "about me", "objective", "career objective")),
        EXPERIENCE("Experience", listOf("experience", "work experience", "professional experience", "employment", "employment history", "work history", "career history")),
        EDUCATION("Education", listOf("education", "academic background", "academics", "qualifications", "academic qualifications")),
        SKILLS("Skills", listOf("skills", "technical skills", "key skills", "core competencies", "competencies", "technologies", "tools")),
        PROJECTS("Projects", listOf("projects", "personal projects", "key projects", "academic projects")),
        CERTIFICATIONS("Certifications", listOf("certifications", "certificates", "licenses", "licenses & certifications", "courses")),
        LANGUAGES("Languages", listOf("languages")),
        ACHIEVEMENTS("Achievements", listOf("achievements", "awards", "honors", "honours", "accomplishments")),
    }

    /** A value read from the resume; [unclear] when it came from a line OCR wasn't sure about. */
    data class Field(val value: String, val unclear: Boolean = false)
    data class Profile(
        val name: Field?, val emails: List<Field>, val phones: List<Field>, val links: List<Field>,
        val sections: Map<Section, String>, val skills: List<String>, val achievements: List<String>, val dates: List<Field>,
    ) {
        /** What the resume doesn't show (to be labelled as missing, never filled in). */
        val missing: List<String> get() = buildList {
            if (name == null) add("name"); if (emails.isEmpty()) add("email"); if (phones.isEmpty()) add("phone")
            listOf(Section.SUMMARY, Section.EXPERIENCE, Section.EDUCATION, Section.SKILLS).filter { it !in sections }.forEach { add(it.label.lowercase() + " section") }
        }
    }

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""(?<![\w])(\+?\d[\d\s().-]{7,}\d)(?![\w])""")
    private val LINK = Regex("""(?i)\b((?:https?://)?(?:www\.)?(?:linkedin\.com|github\.com|gitlab\.com)/[^\s,;)]+|https?://[^\s,;)]+)""")
    private val DATE = Regex("""(?i)\b((?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+\d{4}|\d{1,2}/\d{4}|(?:19|20)\d{2})\s*(?:[-–—]|to)\s*((?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+\d{4}|\d{1,2}/\d{4}|(?:19|20)\d{2}|present|current|now|till date)\b""")
    private val METRIC = Regex("""(?i)(\d[\d,.]*\s*(?:%|percent|x\b|k\b|m\b|lakh|crore|users|customers|clients|people|members|managers|employees|engineers|developers|students|teams|projects|stores|countries|cities|reports|dashboards|tickets|orders|leads|transactions|records|hours|days|weeks|months|years)|[$₹€£]\s?\d[\d,.]*\s*(?:k|m|bn|lakh|crore)?)""")

    /** Common skills, matched as whole words (case-insensitive). Only skills written in the text are ever reported. */
    val SKILLS = listOf(
        "Python", "Java", "Kotlin", "JavaScript", "TypeScript", "C++", "C#", "Golang", "Rust", "Swift", "PHP", "Ruby", "Scala", "MATLAB", "SQL", "NoSQL",
        "HTML", "CSS", "React", "Angular", "Vue", "Node.js", "Express", "Django", "Flask", "FastAPI", "Spring", "Spring Boot", ".NET", "ASP.NET", "Android", "iOS",
        "Jetpack Compose", "Flutter", "React Native", "REST", "GraphQL", "gRPC", "Microservices", "Docker", "Kubernetes", "Terraform", "Ansible", "Jenkins", "Git",
        "GitHub Actions", "CI/CD", "Linux", "Bash", "AWS", "Azure", "GCP", "Google Cloud", "Firebase", "PostgreSQL", "MySQL", "MongoDB", "Redis", "Elasticsearch",
        "Kafka", "Spark", "Hadoop", "Airflow", "Snowflake", "Databricks", "Tableau", "Power BI", "Excel", "Pandas", "NumPy", "scikit-learn", "TensorFlow", "PyTorch",
        "Keras", "OpenCV", "NLP", "LLM", "Machine Learning", "Deep Learning", "Computer Vision", "Data Analysis", "Data Science", "Statistics", "MLOps", "LangChain",
        "Selenium", "JUnit", "Jira", "Confluence", "Figma", "Agile", "Scrum", "Kanban", "Project Management", "Product Management", "Stakeholder Management",
        "Leadership", "Team Leadership", "Communication", "Problem Solving", "Negotiation", "Sales", "Marketing", "SEO", "Content Writing", "Customer Service",
        "Accounting", "Financial Analysis", "SAP", "Salesforce", "ERP", "Six Sigma", "ITIL", "PMP", "Networking", "Cybersecurity", "Penetration Testing",
    )

    private fun hasSkill(text: String, skill: String): Boolean {
        val e = Regex.escape(skill)
        return Regex("""(?i)(?<![\w+#.])$e(?![\w+#])""").containsMatchIn(text)
    }
    /** Skills from [SKILLS] written in [text] (single letters and everyday words like "Go" or "R" are left out: too ambiguous). */
    fun skillsIn(text: String): List<String> = SKILLS.filter { hasSkill(text, it) }

    private fun headingOf(line: String): Section? {
        val l = line.trim().trimEnd(':').replace(Regex("[^\\p{L}& ]"), "").trim().lowercase()
        if (l.isEmpty() || l.length > 40) return null
        return Section.entries.firstOrNull { s -> s.headings.any { it == l } }
    }

    /** Parses [text]; [unclearLines] are lines OCR flagged, so values taken from them are marked for confirmation. */
    fun parse(text: String, unclearLines: Collection<String> = emptyList()): Profile {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[[Page") }
        fun unclear(v: String) = unclearLines.any { u -> u.isNotBlank() && (u.contains(v) || v.contains(u.trim())) }
        val sections = linkedMapOf<Section, StringBuilder>()
        var cur: Section? = null
        for (l in lines) { val h = headingOf(l); if (h != null) { cur = h; sections.getOrPut(h) { StringBuilder() }; continue }; cur?.let { sections[it]!!.append(l).append('\n') } }
        val name = lines.take(5).firstOrNull { l ->
            !EMAIL.containsMatchIn(l) && !PHONE.containsMatchIn(l) && headingOf(l) == null && l.split(' ').size in 2..4 &&
                l.split(' ').all { w -> w.firstOrNull()?.isUpperCase() == true && w.all { it.isLetter() || it in ".-'" } }
        }?.let { Field(it, unclear(it)) }
        val skillSource = sections[Section.SKILLS]?.toString().orEmpty().ifBlank { text }
        return Profile(
            name = name,
            emails = EMAIL.findAll(text).map { it.value }.distinct().map { Field(it, unclear(it)) }.toList(),
            phones = PHONE.findAll(text).map { it.groupValues[1].trim() }.filter { p -> p.count(Char::isDigit) in 8..15 && !DATE.matches(p) }
                .distinct().map { Field(it, unclear(it)) }.toList(),
            links = LINK.findAll(text).map { it.value.trimEnd('.') }.distinct().map { Field(it, unclear(it)) }.toList(),
            sections = sections.mapValues { it.value.toString().trim() }.filterValues { it.isNotEmpty() },
            skills = skillsIn(skillSource).ifEmpty { skillsIn(text) },
            achievements = lines.filter { METRIC.containsMatchIn(it) && it.length > 15 }.take(12),
            dates = DATE.findAll(text).map { it.value }.distinct().map { Field(it, unclear(it)) }.toList(),
        )
    }

    // ------------------------------------------------------------------ grounding: nothing new may appear unflagged
    /**
     * Facts in [generated] that the resume [source] doesn't contain: numbers (metrics, years, counts), date ranges,
     * known skills and email addresses. These must be confirmed by the user or removed. (Words the AI rephrases are fine;
     * new facts are not.) [userAdded] is text the user explicitly provided (e.g. the job description is NOT included).
     */
    fun unsupported(generated: String, source: String, userAdded: String = ""): List<String> {
        val known = (source + "\n" + userAdded)
        val knownLower = known.lowercase()
        val knownNumbers = Regex("""\d+(?:[.,]\d+)?""").findAll(known).map { it.value.replace(",", "") }.toSet()
        val out = linkedSetOf<String>()
        Regex("""\d+(?:[.,]\d+)?\s*%?""").findAll(generated).map { it.value.trim() }.forEach { n ->
            val digits = n.removeSuffix("%").trim().replace(",", "")
            if (digits.length >= 1 && digits !in knownNumbers && !(digits.length == 1 && !n.endsWith("%"))) out += n
        }
        skillsIn(generated).filter { !hasSkill(known, it) }.forEach { out += it }
        EMAIL.findAll(generated).map { it.value }.filter { it.lowercase() !in knownLower }.forEach { out += it }
        return out.toList()
    }

    // ------------------------------------------------------------------ job description match (local)
    data class Match(val required: List<String>, val matched: List<String>, val missing: List<String>, val requirements: List<String>)

    /** Skills named in the job description, which of them the resume shows, and requirement lines (years, degrees). */
    fun match(resume: String, jd: String): Match {
        // v3.3: with the skill taxonomy loaded, aliases and ambiguity rules apply (ML = Machine Learning; "my CV" isn't Computer Vision)
        ai.abhify.android.career.SkillTaxonomy.current?.let { t ->
            val req = t.find(jd).map { it.skill }; val have = t.find(resume).map { it.skill }.toSet()
            return Match(req, req.filter { it in have }, req.filterNot { it in have }, requirementLines(jd))
        }
        val req = skillsIn(jd)
        return Match(req, req.filter { hasSkill(resume, it) }, req.filterNot { hasSkill(resume, it) }, requirementLines(jd))
    }

    private fun requirementLines(jd: String): List<String> {
        return jd.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { l ->
            Regex("""(?i)(\d+\+?\s*(?:years?|yrs?)|bachelor|master|degree|b\.?tech|m\.?tech|mba|phd|certif|must|required|mandatory|preferred|nice to have)""").containsMatchIn(l)
        }.filter { it.length in 8..220 }.take(15)
    }

    // ------------------------------------------------------------------ AI prompts (the rules are in the system message AND every prompt: small models forget)
    const val RULES = "Rules: use ONLY facts written in the resume below. Never invent or change employers, job titles, dates, degrees, " +
        "certifications, skills, projects, numbers or achievements. If something is missing, write [missing: …]. If text looks garbled " +
        "(OCR), write [unclear: …] instead of guessing. Use Markdown."
    const val SYSTEM = "You are a careful career coach. $RULES"

    fun summaryPrompt(r: String) = "$RULES\n\n" + "Write a 3–4 sentence professional summary of this candidate using only the resume.\n\n<resume>\n$r\n</resume>"
    fun suggestionsPrompt(r: String) = "$RULES\n\n" + "Review this resume. List concrete improvements: structure, clarity, missing sections, weak bullet points " +
        "(show the original line and a rewritten line that keeps the same facts), and where measurable results would help " +
        "(ask the user for the number; never make one up).\n\n<resume>\n$r\n</resume>"
    fun questionsPrompt(r: String) = "$RULES\n\n" + "Write 10 interview questions an interviewer would ask THIS candidate, each tied to a specific line of the resume " +
        "(quote it briefly). Mix technical, behavioural and project questions.\n\n<resume>\n$r\n</resume>"
    fun draftPrompt(r: String) = "$RULES\n\n" + "Rewrite this resume as a clean, well-structured draft (Summary, Experience, Education, Skills, Projects, Certifications). " +
        "Improve wording only; keep every fact exactly as written. Where a bullet would benefit from a number the resume doesn't give, " +
        "write [add number] instead of a number.\n\n<resume>\n$r\n</resume>"
    fun jdPrompt(r: String, jd: String, m: Match) = "$RULES\n\n" + "Compare the resume with the job description. The app already found by exact match: " +
        "matched skills: ${m.matched.joinToString().ifBlank { "none" }}; skills in the job description not in the resume: ${m.missing.joinToString().ifBlank { "none" }}.\n" +
        "Write: ## Strong matches (quote the resume), ## Gaps and unclear matches, ## Wording suggestions (only using resume facts), " +
        "## Interview questions to prepare. Do NOT add missing skills to the resume; list them as gaps for the user to decide.\n\n" +
        "<resume>\n$r\n</resume>\n\n<job_description>\n$jd\n</job_description>"
}
