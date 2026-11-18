package ai.abhify.android.chat

/**
 * 4.6 AI Action Center: reusable follow-up actions on any answer (same wording as the web app's AI_ACTIONS).
 * Each action is only a prompt sent through the normal chat (same model choice, consent, memory and history rules),
 * so nothing here is a separate or simulated AI. Long answers are clipped so the follow-up stays a reasonable size.
 */
object AiActions {
    enum class Action(val label: String, val glyph: String) {
        SUMMARIZE("Summarize", "📝"), SIMPLIFY("Explain simply", "💡"), EXTRACT("Key points", "📋"),
        TRANSLATE("Translate", "🔀"), QUIZ("Quiz me", "❔"), CARDS("Flashcards", "🃏"), CHECKLIST("Checklist", "✅"), REWRITE("Rewrite clearly", "✏"),
    }

    const val MAX_SOURCE = 3_300
    val LANGUAGES = listOf("English", "Hindi", "Spanish", "French", "German", "Japanese")

    fun prompt(a: Action, answer: String, language: String = "English"): String {
        val t = answer.trim().take(MAX_SOURCE)
        return when (a) {
            Action.SUMMARIZE -> "Summarize this in 3 to 5 bullet points:\n\n$t"
            Action.SIMPLIFY -> "Explain this more simply, for a beginner, with one everyday example:\n\n$t"
            Action.EXTRACT -> "Extract the key facts and terms from this as a short list:\n\n$t"
            Action.TRANSLATE -> "Translate this into $language. Keep code and names unchanged:\n\n$t"
            Action.QUIZ -> "Write 3 multiple-choice questions (A–D) that test understanding of this, then list the answers with one-line explanations:\n\n$t"
            Action.CARDS -> "Make 5 flashcards from this, one per line as \"Q: … | A: …\":\n\n$t"
            Action.CHECKLIST -> "Turn this into a short, practical checklist of steps (\"- [ ] …\"):\n\n$t"
            Action.REWRITE -> "Rewrite this more clearly and concisely, keeping every fact:\n\n$t"
        }
    }
}
