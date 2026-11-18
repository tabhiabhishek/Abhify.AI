package ai.abhify.android.chat

/**
 * 5.6 My Friendly AI — the companion layer every chat turn goes through (normal, project, knowledge and voice), so the
 * personality and conversational continuity are the same everywhere instead of being re-invented per screen:
 *  - [CHARTER]: one consistent, honest personality (an AI, not a person; no invented feelings or memories);
 *  - [isFollowUp] / [retrievalQuery]: a short follow-up ("why?", "and in Hindi?", "what about the second one") is resolved
 *    against the active topic, so memory, document and context-finder retrieval search for the topic, not just "why";
 *  - [earlier]: turns older than the prompt window are summarised extractively (the user's own questions, never invented),
 *    so long conversations — also after an app restart, from saved history — keep their thread;
 *  - [smallTalk]: greetings and thanks get a short, natural reply instead of being forced into a workflow.
 * Pure functions; the existing answer styles (Intelligence.instructions, Personality) still decide format and depth.
 */
object Companion {
    const val CHARTER = "How you talk: friendly, calm, clear and honest — like a knowledgeable friend, not a generic chatbot. " +
        "Match the length to the question: brief for simple things, detailed only when asked or needed. If a request is genuinely ambiguous, " +
        "ask one short clarifying question instead of guessing. Say when you are unsure. Continue the current topic naturally and don't ask for " +
        "information the user already gave. No repeated greetings, no flattery, at most one emoji and only when it fits. You are an AI: never " +
        "claim feelings, experiences or memories you don't have; only what is in this conversation and the context below is known to you."

    private val FOLLOW_START = Regex("(?i)^(and|but|so|also|then|why|how come|what about|how about|and what|ok(ay)?|really|more|explain (it|that|this)|in (hindi|english)|" +
        "give (me )?(an )?example|which one|the (first|second|third|last) one)\\b")
    private val ANAPHORA = Regex("(?i)\\b(it|its|that|this|those|these|they|them|he|she|the same|above|previous|earlier|last one)\\b")
    private val SMALL_TALK = Regex("(?i)^(hi|hello|hey|namaste|good (morning|afternoon|evening|night)|thanks?( you)?|thank you( so much)?|ok(ay)?|cool|great|nice|bye|see you)[.!? ]*$")

    fun smallTalk(q: String) = SMALL_TALK.matches(q.trim())

    /** A short question that leans on the previous turn (starts like a follow-up, or is ≤ 6 words and points back with "it/that/…"). */
    fun isFollowUp(q: String, history: List<PromptBuilder.Msg>): Boolean {
        if (history.none { it.role == "user" }) return false
        val t = q.trim(); val words = t.split(Regex("\\s+")).size
        return words <= 12 && (FOLLOW_START.containsMatchIn(t) || (words <= 6 && ANAPHORA.containsMatchIn(t)))
    }

    /** The topic a follow-up refers to: the latest user message that was not itself a follow-up or small talk. */
    fun activeTopic(history: List<PromptBuilder.Msg>): String? {
        val users = history.filter { it.role == "user" }.map { it.content.trim() }
        for (i in users.indices.reversed()) {
            val u = users[i]
            if (u.isBlank() || smallTalk(u)) continue
            if (isFollowUp(u, users.take(i).map { PromptBuilder.Msg("user", it) })) continue
            return u.take(200)
        }
        return null
    }

    /** What retrieval should search for: the question, plus the active topic when the question is a follow-up. */
    fun retrievalQuery(q: String, history: List<PromptBuilder.Msg>): String =
        if (!isFollowUp(q, history)) q else activeTopic(history)?.let { "$q $it" } ?: q

    /** Older turns outside the prompt window, as the user's own earlier questions (newest last, at most 5). Null when nothing is older. */
    fun earlier(history: List<PromptBuilder.Msg>, window: Int = PromptBuilder.HISTORY_MESSAGES): String? {
        val older = history.dropLast(window).filter { it.role == "user" && it.content.isNotBlank() && !smallTalk(it.content) }
        if (older.isEmpty()) return null
        return "Earlier in this conversation (outside the recent messages) the user asked: " +
            older.takeLast(5).joinToString("; ") { "\"" + it.content.replace('\n', ' ').trim().take(90) + "\"" } + "."
    }

    /** The per-turn guidance appended to the system prompt. */
    fun turn(q: String, history: List<PromptBuilder.Msg>, window: Int = PromptBuilder.HISTORY_MESSAGES): String = listOfNotNull(
        CHARTER,
        earlier(history, window),
        if (isFollowUp(q, history)) activeTopic(history)?.let { "The user's message is a follow-up to: \"${it.take(160)}\". Answer it in that context." } else null,
        if (smallTalk(q)) "The user is just chatting: reply in one or two friendly sentences; don't list features or start a task." else null,
    ).joinToString("\n")
}
