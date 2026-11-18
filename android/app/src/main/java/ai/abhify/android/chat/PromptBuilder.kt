package ai.abhify.android.chat

/** Pure prompt logic, ported from the web app's on-device mode (frontend/ondevice.js) so both behave alike. */
object PromptBuilder {
    data class Msg(val role: String, val content: String)

    /** Answer styles the user can pick (Settings). Each adds one instruction; Default adds none. */
    enum class Personality(val label: String, val instruction: String) {
        DEFAULT("Default", ""),
        TUTOR("Patient tutor", "Teach step by step for a beginner, check understanding with one short question at the end."),
        CONCISE("Concise", "Answer in as few words as possible: a direct answer, then at most three bullet points."),
        INTERVIEW("Interview coach", "Act as an ML interview coach: give the answer an interviewer expects, then one likely follow-up question."),
        SOCRATIC("Socratic", "Guide the user to the answer with questions and hints before giving it."),
        ;
        companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: DEFAULT }
    }

    /** What goes into "The user's instructions": the style plus the custom text (if turned on). */
    fun instructions(personality: Personality, custom: String, customEnabled: Boolean): String =
        listOf(personality.instruction, if (customEnabled) custom.trim() else "").filter { it.isNotBlank() }.joinToString("\n")

    const val HISTORY_MESSAGES = 8
    const val MAX_MEMORIES = 20

    /** [onDevice] false = the prompt for the online AI: it doesn't claim to run on the phone (memories are the caller's choice). */
    /** A document attached to the question: its name and the text (or excerpts) the model may read. */
    data class Doc(val name: String, val text: String, val whole: Boolean)

    fun build(userText: String, history: List<Msg>, memories: List<String>, userName: String, onDevice: Boolean = true, custom: String = "",
              doc: Doc? = null, historyMessages: Int = HISTORY_MESSAGES): List<Msg> {
        val sb = StringBuilder(
            (if (onDevice) "You are Abhify.AI, a friendly AI/ML learning companion running entirely on the user's own phone. "
             else "You are Abhify.AI, a friendly AI/ML learning companion. ") +
                "Be concise and clear; use short paragraphs or bullet points. If you are not sure, say so instead of inventing facts. " +
                (if (onDevice) "You have no internet access and no access to files on this phone" else "You have no access to the user's files") +
                (if (doc != null) " except the document the user attached below." else ".")
        )
        if (userName.isNotBlank()) sb.append(" The user's name is ").append(userName.trim()).append('.')
        if (custom.isNotBlank()) sb.append("\n\nThe user's instructions for you:\n").append(custom.trim())
        if (memories.isNotEmpty()) {
            sb.append("\n\nThings the user asked you to remember:\n")
            memories.takeLast(MAX_MEMORIES).forEach { sb.append("- ").append(it).append('\n') }
        }
        if (doc != null) {
            sb.append("\n\nThe user attached the document \"").append(doc.name).append("\"")
                .append(if (doc.whole) ". Its full text:" else ". The sections most relevant to the question ([…] marks skipped parts):")
                .append("\n<document>\n").append(doc.text).append("\n</document>\n")
                .append("Answer from the document when the question is about it, and say so when the document doesn't contain the answer.")
        }
        val recent = history.takeLast(historyMessages.coerceIn(0, 24)).map { Msg(if (it.role == "user") "user" else "assistant", it.content) }
        return listOf(Msg("system", sb.toString().trimEnd())) + recent + Msg("user", userText)
    }

    private val REMEMBER = Regex("""^(?:please\s+)?remember(?:\s+that)?[:,]?\s+(.{3,300})$""", RegexOption.IGNORE_CASE)

    /** "remember that …" / "remember: …" -> the fact to store, else null. */
    fun memoryCommand(text: String): String? =
        REMEMBER.matchEntire(text.trim())?.groupValues?.get(1)?.trim()?.trimEnd('.', '!')
}
