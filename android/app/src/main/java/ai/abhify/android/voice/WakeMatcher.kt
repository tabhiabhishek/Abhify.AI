package ai.abhify.android.voice

import java.text.Normalizer

/**
 * 7.1.1: does a recognised phrase call the assistant by its configured name? Works for any name the user chose in
 * Assistant identity (no fixed list), in any script. Tolerates capitalisation, punctuation, a greeting before the name
 * ("hey", "hi", "hello", "ok", Hindi "अरे"/"हे"), a merged greeting ("heynova") and small recognition differences
 * (one letter for names of 4–6 letters, two for longer names; none for short names like "Ava", which would otherwise
 * match ordinary words). To avoid triggering on ordinary speech, the name must open the phrase (optionally after a greeting).
 * Returns what followed the name ("Nova what time is it" → "what time is it"). Pure (unit-tested).
 */
object WakeMatcher {
    data class Hit(val command: String)

    private val GREETINGS = setOf("hey", "hi", "hello", "ok", "okay", "hai", "hay", "ey", "yo", "oye", "hallo", "helo", "अरे", "हे", "हाय", "हेलो", "ओके")

    fun normalize(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    private fun tolerance(len: Int) = when { len <= 3 -> 0; len <= 6 -> 1; else -> 2 }

    fun close(heard: String, want: String): Boolean = heard == want || (heard.length >= 3 && distance(heard, want) <= tolerance(want.length))

    fun match(transcript: String, name: String): Hit? {
        val words = normalize(transcript)
        val nameWords = normalize(name)
        if (words.isEmpty() || nameWords.isEmpty() || nameWords.size > 4) return null
        val joinedName = nameWords.joinToString("")
        // greetings first (at most two: "hey hey nova", "ok hello nova")
        var i = 0
        while (i < words.size && i < 2 && words[i] in GREETINGS) i++
        // the name as separate words
        if (i + nameWords.size <= words.size && nameWords.indices.all { close(words[i + it], nameWords[it]) })
            return Hit(words.drop(i + nameWords.size).joinToString(" "))
        // the name written as one word ("heynova", "hinova") or a multi-word name run together
        val first = words.getOrNull(i) ?: return null
        for (g in GREETINGS + "") if (first.startsWith(g) && close(first.removePrefix(g), joinedName) && first.removePrefix(g).isNotEmpty())
            return Hit(words.drop(i + 1).joinToString(" "))
        return null
    }

    /** Levenshtein distance (small strings only). */
    fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }; val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
