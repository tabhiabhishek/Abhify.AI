package ai.abhify.android.learn

import ai.abhify.android.chat.PromptBuilder
import org.json.JSONArray
import org.json.JSONObject

data class InterviewQuestion(val category: String, val difficulty: String, val question: String, val answer: String, val generated: Boolean = false)
data class Mcq(val category: String, val question: String, val options: List<String>, val correct: Int, val explanation: String, val generated: Boolean = false)

/** The built-in offline bank (assets/learning_bank.json), validated on load. */
class LearningBank(val categories: List<Pair<String, String>>, val questions: List<InterviewQuestion>, val mcqs: List<Mcq>) {
    fun questions(category: String) = if (category == "all") questions else questions.filter { it.category == category }
    fun mcqs(category: String) = if (category == "all") mcqs else mcqs.filter { it.category == category }

    companion object {
        fun parse(json: String): LearningBank {
            val o = JSONObject(json)
            require(o.getString("format") == "abhify-learning-bank-1") { "unknown bank format" }
            val cats = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
            val ids = cats.map { it.first }.toSet()
            val qs = o.getJSONArray("questions").objects().map {
                InterviewQuestion(it.getString("category"), it.getString("difficulty"), it.getString("question"), it.getString("answer"))
                    .also { q -> require(q.category in ids && q.question.isNotBlank() && q.answer.isNotBlank()) { "bad question: ${q.question}" } }
            }
            val mcqs = o.getJSONArray("mcqs").objects().map {
                val opts = it.getJSONArray("options").let { a -> (0 until a.length()).map(a::getString) }
                Mcq(it.getString("category"), it.getString("question"), opts, it.getInt("correctAnswer"), it.getString("explanation"))
                    .also { m -> require(m.category in ids && Learning.validMcq(m)) { "bad mcq: ${m.question}" } }
            }
            require(qs.map { it.question }.toSet().size == qs.size && mcqs.map { it.question }.toSet().size == mcqs.size) { "duplicates" }
            return LearningBank(cats, qs, mcqs)
        }
        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}

/** Prompts for the on-phone model and strict parsing of what it returns (pure, unit-tested). */
object Learning {
    fun validMcq(m: Mcq) = m.question.isNotBlank() && m.options.size == 4 && m.options.all { it.isNotBlank() } &&
        m.options.map { it.trim().lowercase() }.toSet().size == 4 && m.correct in 0..3 && m.explanation.isNotBlank()

    fun mcqPrompt(categoryLabel: String, avoid: List<String>): List<PromptBuilder.Msg> = listOf(
        PromptBuilder.Msg("system", "You write accurate multiple-choice questions for people learning AI and machine learning. Reply with JSON only."),
        PromptBuilder.Msg("user", "Write ONE new multiple-choice question about $categoryLabel. Exactly 4 options, exactly one correct.\n" +
            (if (avoid.isNotEmpty()) "Do not repeat these questions: ${avoid.takeLast(5).joinToString(" | ")}\n" else "") +
            "Format: {\"question\": \"...\", \"options\": [\"...\", \"...\", \"...\", \"...\"], \"correctAnswer\": <0-3>, \"explanation\": \"one sentence\"}"))

    fun interviewPrompt(categoryLabel: String, avoid: List<String>): List<PromptBuilder.Msg> = listOf(
        PromptBuilder.Msg("system", "You are an experienced AI/ML interviewer. Reply with JSON only."),
        PromptBuilder.Msg("user", "Write ONE interview question about $categoryLabel and a clear model answer of 3 to 5 sentences.\n" +
            (if (avoid.isNotEmpty()) "Do not repeat these questions: ${avoid.takeLast(5).joinToString(" | ")}\n" else "") +
            "Format: {\"question\": \"...\", \"answer\": \"...\", \"difficulty\": \"easy|medium|hard\"}"))

    /** The first complete {...} object in the text, tolerating ```json fences and chatter around it. */
    fun extractJson(text: String): JSONObject? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    fun parseMcq(text: String, category: String): Mcq? {
        val o = extractJson(text) ?: return null
        val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } } ?: return null
        var correct = o.opt("correctAnswer").let { v -> (v as? Number)?.toInt() ?: v?.toString()?.trim()?.toIntOrNull() ?: -1 }
        if (correct !in 0..3) {        // some models answer with the option text instead of its index
            val asText = o.optString("correctAnswer").trim().lowercase()
            correct = opts.indexOfFirst { it.lowercase() == asText }
        }
        val m = Mcq(category, o.optString("question").trim(), opts.map { it.removePrefix("A) ").removePrefix("B) ").removePrefix("C) ").removePrefix("D) ") },
            correct, o.optString("explanation").trim(), generated = true)
        return m.takeIf { validMcq(it) && it.question.length in 10..400 }
    }

    fun parseInterview(text: String, category: String): InterviewQuestion? {
        val o = extractJson(text) ?: return null
        val q = o.optString("question").trim(); val a = o.optString("answer").trim()
        val d = o.optString("difficulty").trim().lowercase().takeIf { it in setOf("easy", "medium", "hard") } ?: "medium"
        return if (q.length in 10..400 && a.length >= 40) InterviewQuestion(category, d, q, a, generated = true) else null
    }
}

/** Daily streak: consecutive days (ending today or yesterday) with at least one answer. Pure. */
object Streak {
    fun days(epochDays: Set<Long>, today: Long): Int {
        var d = if (today in epochDays) today else if (today - 1 in epochDays) today - 1 else return 0
        var n = 0
        while (d in epochDays) { n++; d-- }
        return n
    }
}
