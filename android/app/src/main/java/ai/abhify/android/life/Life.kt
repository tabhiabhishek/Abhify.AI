package ai.abhify.android.life

import ai.abhify.android.chat.JobApplication
import ai.abhify.android.chat.VaultItem
import ai.abhify.android.docs.Library
import ai.abhify.android.learn.Studio
import org.json.JSONArray
import org.json.JSONObject

/** Career Copilot rules (tracker, interview practice). Pure, unit-tested. */
object Career {
    enum class Status(val label: String) { SAVED("Saved"), APPLIED("Applied"), ASSESSMENT("Assessment"), INTERVIEW("Interview"),
        OFFER("Offer"), REJECTED("Rejected"), WITHDRAWN("Withdrawn");
        companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: SAVED } }

    private val DATE = Regex("""^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""")
    /** Dates are stored as yyyy-mm-dd (or empty). Returns an error message, or null when valid. */
    fun dateError(label: String, value: String?): String? = when {
        value.isNullOrBlank() -> null
        !DATE.matches(value.trim()) -> "$label: use the form 2026-11-05."
        else -> null
    }
    fun validate(a: JobApplication): String? = when {
        a.company.isBlank() -> "Company is required."
        a.title.isBlank() -> "Job title is required."
        else -> dateError("Application date", a.appliedOn) ?: dateError("Follow-up date", a.followUpOn) ?: dateError("Interview date", a.interviewOn)
    }

    /** Search over company, title and notes; [status] null = all. Newest change first. */
    fun filter(all: List<JobApplication>, query: String, status: Status?): List<JobApplication> = all.filter { a ->
        (status == null || a.status == status.name) &&
            (query.isBlank() || listOfNotNull(a.company, a.title, a.notes).any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedByDescending { it.updatedAt }

    /** Applications whose follow-up or interview date is on or before [today] (yyyy-mm-dd) and still active. */
    fun due(all: List<JobApplication>, today: String): List<JobApplication> = all.filter { a ->
        a.status !in setOf(Status.REJECTED.name, Status.WITHDRAWN.name, Status.OFFER.name) &&
            listOfNotNull(a.followUpOn, a.interviewOn).any { it.isNotBlank() && it <= today }
    }

    enum class Topic(val label: String, val ask: String) {
        TECHNICAL("Technical", "general technical (software engineering, systems, problem solving)"),
        PYTHON("Python", "Python programming (language features, data structures, performance, testing)"),
        AIML("AI/ML", "AI and machine learning (models, training, evaluation, deployment, LLMs)"),
        ML("Machine Learning", "classical machine learning (features, models, bias/variance, metrics, validation)"),
        DL("Deep Learning", "deep learning (neural networks, training, optimisation, regularisation, architectures)"),
        CV("Computer Vision", "computer vision (image processing, CNNs, detection, segmentation, OpenCV)"),
        NLP("NLP", "natural language processing (tokenisation, embeddings, transformers, LLMs, evaluation)"),
        BACKEND("Backend", "backend development (APIs, databases, caching, scaling, security)"),
        FASTAPI("FastAPI", "FastAPI (routing, Pydantic models, dependency injection, async, testing, deployment)"),
        SQL("SQL", "SQL (joins, aggregation, window functions, indexing, query optimisation)"),
        PROJECTS("My projects", "the candidate's own projects and experience from the resume below"),
        JOB("From job description", "the requirements of the job description below"),
        BEHAVIORAL("Behavioural", "behavioural (teamwork, conflict, ownership, failure, leadership) using the STAR method"),
    }

    /** [resume] is used only for PROJECTS (resume-based); [job] only for JOB (requirement-based, and the question says which requirement). */
    fun questionPrompt(t: Topic, level: String, resume: String?, asked: List<String>, job: String? = null) =
        "Ask ONE $level-level interview question about ${t.ask}." +
            (if (resume != null && t == Topic.PROJECTS) " Base it on a specific line of this resume (technologies, projects, decisions, challenges, trade-offs) and don't invent anything about it:\n<resume>\n${resume.take(6000)}\n</resume>" else "") +
            (if (job != null && t == Topic.JOB) " Base it on one specific requirement (mandatory skill, preferred skill, responsibility or experience):\n<job>\n${job.take(4000)}\n</job>" else "") +
            (if (asked.isNotEmpty()) " Don't repeat these: " + asked.takeLast(8).joinToString(" | ") { it.take(80) } + "." else "") +
            "\nWrite only:\nQuestion: …\n" + (if (t == Topic.JOB) "Requirement: (the job requirement it tests)\n" else "") +
            "Outline: 3–5 bullet points of what a strong answer covers"

    /** Asks for structured, validated feedback (see career.AiJson.feedback). This is practice feedback, not a hiring decision. */
    fun feedbackPrompt(question: String, answer: String) =
        "Interview question: $question\n\nCandidate's answer (may be transcribed speech): $answer\n\n" +
            "Evaluate it for technical correctness, completeness, clarity, practical implementation and communication (1 = poor, 5 = excellent). " +
            "Be specific and kind; don't invent personal facts. This is practice feedback, not a hiring decision.\n" + ai.abhify.android.career.AiJson.FEEDBACK_FORMAT

    /** The requirement line of a job-based question ("Requirement: …"), if any. */
    fun requirementOf(text: String): String? = Regex("(?im)^\\s*Requirement\\s*[:：]\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()

    /** "Question: … / Outline: …" → (question, outline). Null when the model didn't follow the format. */
    fun parseQuestion(text: String): Pair<String, String>? {
        val q = Regex("""(?is)Question\s*[:：]\s*(.+?)(?:\n\s*(?:Outline|Requirement)\s*[:：]|$)""").find(text)?.groupValues?.get(1)?.trim()?.removeSurrounding("**") ?: return null
        if (q.length < 8) return null
        val outline = text.substringAfter("Outline", "").trimStart(':', '：', ' ', '*').trim()
        return q to outline
    }
}

/** Tracker 2.0: filters, sorting, follow-ups and statistics, all computed from local data. */
object Tracker {
    enum class Sort(val label: String) { UPDATED("Last changed"), APPLIED("Latest application"), FOLLOW_UP("Follow-up date") }
    data class Query(val text: String = "", val company: String = "", val title: String = "", val status: Career.Status? = null,
                     val appliedFrom: String = "", val appliedTo: String = "", val followFrom: String = "", val followTo: String = "", val sort: Sort = Sort.UPDATED)

    private fun inRange(v: String?, from: String, to: String) = (from.isBlank() || (v != null && v >= from)) && (to.isBlank() || (v != null && v <= to))

    fun apply(all: List<JobApplication>, q: Query): List<JobApplication> = Career.filter(all, q.text, q.status).filter { a ->
        (q.company.isBlank() || a.company.contains(q.company.trim(), true)) && (q.title.isBlank() || a.title.contains(q.title.trim(), true)) &&
            inRange(a.appliedOn, q.appliedFrom, q.appliedTo) && inRange(a.followUpOn, q.followFrom, q.followTo)
    }.let { l -> when (q.sort) {
        Sort.UPDATED -> l.sortedByDescending { it.updatedAt }
        Sort.APPLIED -> l.sortedWith(compareByDescending<JobApplication> { it.appliedOn ?: "" }.thenByDescending { it.updatedAt })
        Sort.FOLLOW_UP -> l.sortedWith(compareBy<JobApplication> { it.followUpOn.isNullOrBlank() }.thenBy { it.followUpOn ?: "" })
    } }

    private val CLOSED = setOf(Career.Status.REJECTED.name, Career.Status.WITHDRAWN.name, Career.Status.OFFER.name)
    /** Active applications with a follow-up date in the next [days] days (today included). */
    fun upcoming(all: List<JobApplication>, today: java.time.LocalDate, days: Long = 7) = all.filter { a ->
        a.status !in CLOSED && a.followUpOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.let { !it.isBefore(today) && !it.isAfter(today.plusDays(days)) } == true
    }.sortedBy { it.followUpOn }
    fun overdue(all: List<JobApplication>, today: java.time.LocalDate) = all.filter { a ->
        a.status !in CLOSED && a.followUpOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.isBefore(today) == true
    }.sortedBy { it.followUpOn }

    data class Stats(val total: Int, val inProgress: Int, val interviews: Int, val offers: Int, val rejections: Int, val upcomingFollowUps: Int)
    fun stats(all: List<JobApplication>, today: java.time.LocalDate) = Stats(
        all.size,
        all.count { it.status in setOf(Career.Status.APPLIED.name, Career.Status.ASSESSMENT.name, Career.Status.INTERVIEW.name) },
        all.count { a -> a.status !in CLOSED && (a.status == Career.Status.INTERVIEW.name ||
            a.interviewOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.let { !it.isBefore(today) } == true) },
        all.count { it.status == Career.Status.OFFER.name }, all.count { it.status == Career.Status.REJECTED.name }, upcoming(all, today).size)
}

/** Study sessions: what was generated, from which source, and quiz progress. Content is JSON in study_sessions.content. */
object StudyKit {
    enum class Kind(val label: String) { EXPLAIN("Explanation"), SUMMARY("Summary"), QUIZ("Quiz"), CARDS("Flashcards"), PLAN("Revision plan") }

    const val SUMMARY = "Study the material below and write: ## Short summary (3–5 sentences), ## Key points (bullets), ## Important terms (term – one-line meaning). " +
        "Use only the material; if something is unclear, say so."
    fun explain(topic: String) = "Explain \"$topic\" clearly for a learner: intuition first, then the details, one worked example, and common mistakes. Use Markdown."
    fun revision(topic: String, days: Int, minutes: Int) = "Make a practical revision plan for \"$topic\" over $days days, $minutes minutes per day. " +
        "Write ONLY a numbered list, one line per day: \"Day N – what to revise and how to practise\". Include self-tests and a final review."

    /** A quiz in progress: questions, the user's picks (null = not answered), and where they are. */
    data class Quiz(val questions: List<Studio.Mcq>, val picks: List<Int?>, val index: Int) {
        val answered get() = picks.count { it != null }
        val correct get() = questions.indices.count { picks[it] != null && picks[it] == questions[it].correct }
        val wrong get() = questions.indices.filter { picks[it] != null && picks[it] != questions[it].correct }
        val done get() = answered == questions.size
        fun pick(i: Int): Quiz = if (picks[index] != null) this else copy(picks = picks.toMutableList().also { it[index] = i })
        fun next() = copy(index = (index + 1).coerceAtMost(questions.lastIndex))
        fun prev() = copy(index = (index - 1).coerceAtLeast(0))
        /** A new quiz with only the questions answered wrongly (review mode). */
        fun reviewWrong(): Quiz? = wrong.takeIf { it.isNotEmpty() }?.let { w -> Quiz(w.map { questions[it] }, w.map { null }, 0) }
    }

    fun encodeQuiz(q: Quiz, sources: List<String>): String = JSONObject().put("index", q.index).put("sources", JSONArray(sources))
        .put("questions", JSONArray(q.questions.mapIndexed { i, m ->
            JSONObject().put("q", m.question).put("o", JSONArray(m.options)).put("c", m.correct).put("w", m.why).put("p", q.picks[i] ?: JSONObject.NULL)
        })).toString()

    fun decodeQuiz(json: String): Pair<Quiz, List<String>>? = runCatching {
        val o = JSONObject(json); val a = o.getJSONArray("questions")
        val qs = (0 until a.length()).map { a.getJSONObject(it) }
        val quiz = Quiz(qs.map { m -> Studio.Mcq(m.getString("q"), (0 until 4).map { m.getJSONArray("o").getString(it) }, m.getInt("c"), m.optString("w")) },
            qs.map { if (it.isNull("p")) null else it.getInt("p") }, o.optInt("index"))
        quiz to (o.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList())
    }.getOrNull()

    /** Plain session content (explanation, summary, plan) with the sources it came from. */
    fun encodeText(text: String, sources: List<String>) = JSONObject().put("text", text).put("sources", JSONArray(sources)).toString()
    fun decodeText(json: String): Pair<String, List<String>> = runCatching {
        val o = JSONObject(json); o.getString("text") to (o.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList())
    }.getOrDefault(json to emptyList())

    fun encodeCards(cards: List<Pair<String, String>>, sources: List<String>) = JSONObject().put("sources", JSONArray(sources))
        .put("cards", JSONArray(cards.map { JSONObject().put("f", it.first).put("b", it.second) })).toString()
    fun decodeCards(json: String): List<Pair<String, String>> = runCatching {
        val a = JSONObject(json).getJSONArray("cards"); (0 until a.length()).map { a.getJSONObject(it).let { c -> c.getString("f") to c.getString("b") } }
    }.getOrDefault(emptyList())
}

/** Knowledge Vault helpers. */
object VaultKit {
    val CATEGORIES = listOf("Documents", "Career", "Study", "Notes", "AI answers")

    /** "ML, interview , ml, #python" → ["ml", "interview", "python"] (lower case, no '#', no duplicates, max 12). */
    fun tags(input: String): List<String> = input.split(',', ';', '\n').map { it.trim().trimStart('#').lowercase() }
        .filter { it.isNotEmpty() && it.length <= 30 }.distinct().take(12)

    /**
     * Retrieval only over the items the user selected (never the whole vault implicitly): sections ranked by BM25,
     * labelled [S#] with the item title.
     */
    fun context(selected: List<VaultItem>, question: String, budget: Int): Library.Context {
        val chunks = selected.flatMap { Library.chunks(it.id, it.title, it.body) }
        return Library.context(Library.search(chunks, question, 8), budget)
    }
}

/** What leaves the phone through the share sheet: private details are removed unless the user includes them. */
object ShareKit {
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])""")
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)\S+|\b(?:linkedin|github)\.com/\S+""")
    private val KEYS = Regex("""(AIza[0-9A-Za-z_\-]{10,}|gsk_[A-Za-z0-9]{8,}|sk-[A-Za-z0-9_\-]{8,})""")

    /** Emails, phone numbers and links become [hidden]; anything key-like is always removed. */
    fun redact(text: String, includeContact: Boolean): String {
        var t = KEYS.replace(text, "[removed]")
        if (!includeContact) { t = EMAIL.replace(t, "[email hidden]"); t = LINK.replace(t, "[link hidden]"); t = PHONE.replace(t, "[phone hidden]") }
        return t
    }

    fun studyResult(topic: String, score: Int, total: Int, kind: String = "quiz") =
        "📚 I practised \"$topic\" with Abhify.AI: $score/$total correct on a $total-question $kind" + (if (total > 0) " (${score * 100 / total}%)." else ".")

    fun resumeSummary(text: String, includeContact: Boolean) = "📄 Resume analysis (Abhify.AI)\n\n" + redact(text, includeContact).take(4000)
}
