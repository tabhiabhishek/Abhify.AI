package ai.abhify.android.workspace

import ai.abhify.android.chat.Note
import ai.abhify.android.chat.Task

/**
 * Builds the extra context for a chat from sources the user explicitly selected (v4.0) — today: one workspace's notes
 * and open tasks. Bounded: each source has a cap and the whole has a [budget]; the user sees what went in. Linked
 * documents go through the existing RAG retrieval (with citations); memories through the existing relevance filter.
 * Nothing is included unless selected, and online use goes through the existing consent.
 */
object ContextOrchestrator {
    data class Part(val label: String, val text: String)
    data class Built(val text: String, val parts: List<Pair<String, Int>>, val truncated: Boolean)

    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "what", "how", "why", "for", "on", "with", "my", "me", "i", "it", "this", "that")
    private fun terms(s: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    /** The workspace's most relevant notes (by shared words, newest on ties; up to 3) and its open tasks (up to 10). */
    fun workspaceParts(name: String, notes: List<Note>, tasks: List<Task>, question: String): List<Part> {
        val q = terms(question)
        val picked = notes.map { n -> n to terms(n.title + " " + n.body).count { it in q } }.sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(3).filter { it.second > 0 || notes.size <= 3 }.map { it.first }
        val open = tasks.filter { !it.done }.take(10)
        return listOfNotNull(
            picked.takeIf { it.isNotEmpty() }?.let { l -> Part("notes of workspace \"$name\"", l.joinToString("\n\n") { "## ${it.title}\n${it.body}" }) },
            open.takeIf { it.isNotEmpty() }?.let { l -> Part("open tasks of workspace \"$name\"", l.joinToString("\n") { "- ${it.title}" }) })
    }

    /** Concatenates parts in order within [budget] characters (each part at most [cap]); reports what was included. */
    fun build(parts: List<Part>, budget: Int, cap: Int = budget): Built {
        val sb = StringBuilder(); val used = mutableListOf<Pair<String, Int>>(); var cut = false
        for (p in parts) {
            val room = minOf(cap, budget - sb.length - p.label.length - 8)
            if (room <= 40) { cut = true; break }
            val body = if (p.text.length > room) { cut = true; p.text.take(room).substringBeforeLast('\n', p.text.take(room)) + "…" } else p.text
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[").append(p.label).append("]\n").append(body); used += p.label to body.length
        }
        return Built(sb.toString(), used, cut)
    }
}
