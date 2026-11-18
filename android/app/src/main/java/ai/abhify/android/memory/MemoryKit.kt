package ai.abhify.android.memory

import ai.abhify.android.career.AiJson
import ai.abhify.android.chat.MemoryItem
import org.json.JSONArray

/**
 * Personal memory rules: what counts as relevant to a question, duplicates, sensitive content, and the user-approved
 * extraction of new memories. Memory is separate from chat history and from documents; nothing here sends anything.
 */
object MemoryKit {
    // 5.1: "Professional profile" and "Corrections" (approved corrections from Knowledge Center) added; existing names unchanged.
    // Temporary conversation context is the chat's recent turns (never stored as memory); project decisions live in the project status.
    val CATEGORIES = listOf("Preferences", "Professional profile", "Technical skills", "Learning goals", "Career goals", "Active projects",
        "Important decisions", "Frequent context", "Personal facts", "Learning progress", "Corrections", "General")

    /** Words that make a category relevant to a question even without shared words. */
    private val CATEGORY_HINTS = mapOf(
        "Professional profile" to setOf("job", "career", "resume", "cv", "interview", "experience", "role", "company", "work", "skills"),
        "Career goals" to setOf("job", "career", "resume", "cv", "interview", "salary", "role", "hiring", "apply", "application"),
        "Learning goals" to setOf("learn", "study", "course", "exam", "revise", "revision", "teach", "explain", "understand"),
        "Learning progress" to setOf("learn", "study", "progress", "quiz", "practice", "practise"),
        "Active projects" to setOf("project", "build", "app", "code", "bug", "deploy", "feature"),
        "Technical skills" to setOf("code", "python", "java", "kotlin", "sql", "model", "api", "framework", "library"),
        "Preferences" to setOf("prefer", "style", "format", "explain", "answer", "short", "detailed"),
    )
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "i", "me", "my", "you", "your", "what", "how", "why", "it",
        "for", "on", "with", "this", "that", "be", "do", "does", "can", "should", "about", "please")
    fun terms(s: String) = Regex("[\\p{L}\\p{N}+#]{2,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    data class Scored(val item: MemoryItem, val score: Double, val why: String)
    /** A memory is kept only if it scores at least this share of the best match for the question. */
    const val RELATIVE_CUTOFF = 0.45

    /**
     * Only memories relevant to [question]: shared words, category hints and importance; confirmed and unexpired only;
     * at most [limit] items and [maxChars] characters. An unrelated question gets none (no "dump everything").
     */
    fun relevant(question: String, items: List<MemoryItem>, limit: Int = 6, maxChars: Int = 900, now: Long = System.currentTimeMillis(), context: String = ""): List<Scored> {
        val q = terms(question)
        val ctx = terms(context) - q                                                          // v3.8: the last turns of this chat (weaker)
        val scored = items.filter { it.confirmed && !it.expired(now) }.mapNotNull { m ->
            val words = terms(m.content)
            val shared = words.count { it in q }
            val hint = CATEGORY_HINTS[m.category]?.count { it in q } ?: 0
            val fromCtx = words.count { it in ctx }
            // the question itself must relate (or two words from the ongoing chat): no memory is added "just because"
            val base = shared * 2.0 + hint * 1.0 + (if (fromCtx >= 2) fromCtx * 0.5 else 0.0)
            if (base <= 0.0) return@mapNotNull null
            val recent = maxOf(m.updatedAt, m.lastUsedAt ?: 0)
            val bonus = (m.importance - 2) * 0.5 + (if (now - recent < 30 * 86_400_000L) 0.3 else 0.0) + minOf(m.useCount, 5) * 0.05
            Scored(m, base + bonus, listOfNotNull(if (shared > 0) "$shared shared word(s)" else null, if (hint > 0) "category ${m.category}" else null,
                if (fromCtx >= 2) "related to this chat" else null).joinToString(", "))
        }.sortedByDescending { it.score }
            // 5.2: with a strong match, weak ones (one generic shared word, a category hint alone) are noise; measured on
            // memory_eval.json (precision 0.765 -> see docs). Questions with only weak matches still get them.
            .let { l -> val top = l.firstOrNull()?.score ?: 0.0; l.filter { it.score >= top * RELATIVE_CUTOFF } }
        val out = mutableListOf<Scored>(); var used = 0
        for (s in scored) { if (out.size >= limit) break; if (used + s.item.content.length > maxChars) continue; out += s; used += s.item.content.length }
        return out
    }

    /** Near-duplicates: one contains the other, or ≥ 70% of words shared. */
    fun similar(a: String, b: String): Boolean {
        val na = a.lowercase().trim().trimEnd('.'); val nb = b.lowercase().trim().trimEnd('.')
        if (na == nb || (na.length > 12 && nb.contains(na)) || (nb.length > 12 && na.contains(nb))) return true
        val ta = terms(a); val tb = terms(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size >= 0.7
    }
    fun duplicateOf(content: String, items: List<MemoryItem>): MemoryItem? = items.firstOrNull { similar(content, it.content) }
    /** Groups of near-duplicate memories (for "consolidate"): each group has ≥ 2 items, newest first. */
    fun duplicateGroups(items: List<MemoryItem>): List<List<MemoryItem>> {
        val left = items.sortedByDescending { it.updatedAt }.toMutableList(); val groups = mutableListOf<List<MemoryItem>>()
        while (left.isNotEmpty()) {
            val head = left.removeAt(0); val same = left.filter { similar(head.content, it.content) }
            left.removeAll(same); if (same.isNotEmpty()) groups += listOf(head) + same
        }
        return groups
    }

    /** Kinds of sensitive data in [text] (the user is warned and must confirm before such a memory is saved). */
    fun sensitive(text: String): List<String> = buildList {
        if (Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").containsMatchIn(text)) add("email address")
        if (Regex("(?<!\\d)(\\+?\\d[\\d\\s-]{8,}\\d)(?!\\d)").findAll(text).any { m -> m.value.count(Char::isDigit) in 10..13 && !luhn(m.value) }) add("phone number")
        if (Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)").findAll(text).any { luhn(it.value) }) add("card number")
        if (Regex("(?<!\\d)\\d{4}\\s?\\d{4}\\s?\\d{4}(?!\\d)").containsMatchIn(text)) add("ID number (12 digits)")
        if (Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b").containsMatchIn(text)) add("PAN number")
        if (Regex("(?i)\\b(password|passcode|pin|otp|cvv)\\b").containsMatchIn(text)) add("password or PIN")
        if (Regex("(?i)\\b(diagnos|medical|disease|medication|religion|caste|sexual|bank account|account number|ifsc)").containsMatchIn(text)) add("health/financial/personal-category detail")
    }
    private fun luhn(s: String): Boolean {
        val d = s.filter(Char::isDigit); if (d.length !in 13..19) return false
        var sum = 0; var alt = false
        for (i in d.indices.reversed()) { var n = d[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    // ------------------------------------------------------------------ user-approved extraction
    fun extractionPrompt(conversation: String) = "From the conversation below, list at most 8 durable facts about the USER that would help personalise " +
        "future answers (preferences, skills, goals, projects, decisions). Only facts the user stated; no guesses, no secrets, no one-off details.\n" +
        "Reply with ONLY JSON: {\"memories\": [{\"content\": \"short fact in third person\", \"category\": \"one of: ${CATEGORIES.joinToString()}\"}]}\n\n" +
        "<conversation>\n$conversation\n</conversation>"

    data class Candidate(val content: String, val category: String, val duplicateOf: String?, val sensitive: List<String>)

    /** Validated suggestions: well-formed, short, categorised, flagged for duplicates and sensitive data. Invalid output → empty. */
    fun parseCandidates(text: String, existing: List<MemoryItem>): List<Candidate> {
        val arr: JSONArray = AiJson.extract(text)?.optJSONArray("memories") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val c = o.optString("content").trim().removeSuffix(".").trim()
            if (c.length !in 6..240) return@mapNotNull null
            val cat = o.optString("category").let { k -> CATEGORIES.firstOrNull { it.equals(k.trim(), true) } ?: "General" }
            Candidate(c, cat, duplicateOf(c, existing)?.content, sensitive(c))
        }.distinctBy { it.content.lowercase() }.take(8)
    }
}
