package ai.abhify.android.voice

import ai.abhify.android.chat.Markdown

/**
 * 5.6 Talk to Me streaming speech: while the answer is still being generated, each completed sentence is handed to TTS in
 * order (so Abhify starts speaking after the first sentence instead of after the whole answer). One stream per voice turn;
 * an interrupted or superseded turn's stream is [cancel]led, so its later text can never be spoken (no stale audio, no
 * overlap). Pure (no Android types), unit-tested.
 */
class SpeechStream(val turn: Int, private val minChars: Int = 40) {
    private var consumed = 0
    var cancelled = false; private set
    /** Text already handed to TTS, in order. */
    val spoken = mutableListOf<String>()

    /** Given the answer so far, the newly completed sentences to speak (in order); empty while a sentence is unfinished. */
    fun feed(answerSoFar: String): List<String> {
        if (cancelled || answerSoFar.length <= consumed) return emptyList()
        val rest = answerSoFar.substring(consumed)
        if (inCodeBlock(answerSoFar.substring(0, consumed + rest.length))) return emptyList()   // never read half a code block
        var cut = -1
        for (m in BOUNDARY.findAll(rest)) { if (m.range.last + 1 >= minChars) { cut = m.range.last + 1; break } }
        if (cut < 0) return emptyList()
        consumed += cut
        return emit(rest.substring(0, cut))
    }

    /** The remainder once the answer is complete. */
    fun finish(answer: String): List<String> {
        if (cancelled || answer.length <= consumed) return emptyList()
        val rest = answer.substring(consumed); consumed = answer.length
        return emit(rest)
    }

    fun cancel() { cancelled = true }

    private fun emit(chunk: String): List<String> {
        // 6.2: citations ([S1], [W2], [L1]) and links are never read aloud; the chips stay in the conversation
        val t = Markdown.toSpeech(chunk.replace(Regex("\\s*\\[(?:S|C|W|L)?\\d+]"), "").replace(Regex("\\s*\\[unsupported]"), "").replace(Regex("\\s*\\(?https?://\\S+"), ""))
            .replace(Regex("\\s+"), " ").trim()
        if (t.none { it.isLetterOrDigit() }) return emptyList()
        return ai.abhify.android.voice.Voice.parts(t, Voice.MAX_PART).also { spoken += it }
    }

    private fun inCodeBlock(s: String) = Regex("```").findAll(s).count() % 2 == 1

    companion object {
        /** Sentence ends: . ! ? and the Devanagari danda (।), followed by space/newline; or a paragraph break. */
        val BOUNDARY = Regex("(?<=[.!?।])[\"')\\]]?\\s+|\\n\\s*\\n")
    }
}
