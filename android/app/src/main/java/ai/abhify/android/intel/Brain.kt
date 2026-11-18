package ai.abhify.android.intel

import ai.abhify.android.intel.Intelligence.Focus

/**
 * 6.0 Personal AI Brain — the one planner every chat and voice turn goes through before any model is called. It decides,
 * deterministically and in microseconds (no model call, no network):
 *  - the interaction mode's real behaviour ([Profile]): history window, whether personal context is retrieved, output
 *    length and creativity on the phone's model, whether recent web evidence is gathered, and the reasoning structure;
 *  - the intent ([Intent]): small talk, an app action, a question about the user's own records, a question that needs
 *    current information, or an ordinary task;
 *  - honest notices: "this needs current information and the models don't browse" unless Research gathers evidence;
 *    a route hint when a stronger configured model would suit Deep think / Technical work (never a silent switch).
 * The existing pieces still do the work: Companion (continuity), ContextResolver (records), RagIndex (documents),
 * MemoryKit (memories), HybridAi (routing with consent).
 */
object Brain {
    /** What a mode really changes. [contextFinder] null = decided by the question (own-material rules). */
    data class Profile(val historyMessages: Int, val contextFinder: Boolean?, val deviceMaxTokens: Int?, val temperature: Float?, val web: Boolean,
                       val scaffold: String)

    fun profile(f: Focus): Profile = when (f) {
        Focus.QUICK -> Profile(4, false, 384, 0.2f, false,
            "Quick answer mode: answer directly in one to four sentences or a short list. No preamble, no recap, no follow-up questions.")
        Focus.DEEP -> Profile(12, null, null, 0.2f, false,
            "Deep think mode: restate the problem in one line; break it into parts and work through each; consider at least one alternative " +
                "or counter-argument; check the key conclusion (numbers, logic, edge cases) before stating it; finish with the answer and how " +
                "confident you are and why. Show the reasoning briefly, not every thought.")
        Focus.RESEARCH -> Profile(8, true, null, 0.2f, true, "")      // format comes from Intelligence.format (sources, uncertainty)
        Focus.CODING -> Profile(10, null, null, 0.2f, false, "")
        Focus.LEARNING -> Profile(8, null, null, 0.4f, false, "")
        Focus.PLANNING -> Profile(8, true, null, 0.3f, false,
            "Planning mode: give a practical plan — the goal, ordered steps with dependencies, rough effort, risks and trade-offs, and the first " +
                "next action. Use the user's records when given and never invent deadlines or progress. You cannot change saved records: offer the " +
                "change and let the user approve it in the app.")
        Focus.CREATIVE -> Profile(8, false, null, 0.9f, false,
            "Creative mode: be imaginative and original; when useful offer two or three clearly different options; keep any facts accurate.")
        else -> Profile(8, null, null, null, false, "")
    }

    enum class Intent { SMALL_TALK, ACTION, OWN_RECORDS, FRESH_INFO, TASK }

    data class Plan(val intent: Intent, val detection: Intelligence.Detection, val profile: Profile, val retrievalQuery: String,
                    val contextFinder: Boolean, val web: Boolean, val notices: List<String>)

    /** Needs information newer than any model's training ("latest", "today", prices, scores, news, recent years). */
    val FRESH = Regex("(?i)\\b(latest|today'?s|right now|currently|breaking|news|headlines?|price of|stock price|share price|exchange rate|" +
        "match score|weather|election results?|this week'?s?|202[6-9])\\b")

    /**
     * [knowledge]: Knowledge Chat applies (own documents); [mentioned]: a project was named; [workspace]: a project chat
     * (already carries its context); [webAllowed]: online access allowed by the user (Research may then query news search).
     */
    fun plan(q: String, history: List<ai.abhify.android.chat.PromptBuilder.Msg>, chosen: Focus, knowledge: Boolean, mentioned: Boolean,
             workspace: Boolean, webAllowed: Boolean, isAction: Boolean = false): Plan {
        val d = Intelligence.detect(q, chosen)
        val p = profile(d.focus)
        val own = !workspace && ContextResolver.OWN_MATERIAL.containsMatchIn(q)
        val small = ai.abhify.android.chat.Companion.smallTalk(q)
        val fresh = FRESH.containsMatchIn(q) && !small
        val intent = when {
            small -> Intent.SMALL_TALK
            isAction -> Intent.ACTION
            own || mentioned -> Intent.OWN_RECORDS
            fresh -> Intent.FRESH_INFO
            else -> Intent.TASK
        }
        // personal records: never for small talk or in a project chat (it has its own context); a mode can force it on (Research,
        // Planning) or keep it minimal (Quick, Creative) — but an explicit question about your own records always gets them
        val finder = when {
            small || workspace -> false
            p.contextFinder == true -> true
            p.contextFinder == false -> mentioned || own
            else -> knowledge || mentioned || own
        }
        val web = p.web && webAllowed && !small
        val notices = buildList {
            if (fresh && !web) add(if (d.focus == Focus.RESEARCH && !webAllowed) "🌐 Research can't search recent sources while online access is off."
                else "🌐 This may need current information. The AI models don't browse the web: choose 🔬 Research to gather recent news sources first.")
            if (intent == Intent.ACTION) add("⚙ That sounds like an app action. Say it in 🗣 Talk (changes need your confirmation), or use the app's buttons.")
        }
        return Plan(intent, d, p, ai.abhify.android.chat.Companion.retrievalQuery(q, history), finder, web, notices)
    }

    /** Instruction for the model when the question needs current information that wasn't gathered. */
    const val STALE_WARNING = "The question may need current information that you were not given: say plainly that your knowledge may be out of date " +
        "and suggest checking a live source. Never invent recent events, prices, scores or dates."

    /**
     * Route hint (shown, never applied): Deep think or Technical work about to run on the phone's small model while a
     * stronger online model is configured and allowed.
     */
    fun routeAdvice(focus: Focus, nextIsDevice: Boolean, onlineReady: Boolean, onlineAllowed: Boolean): String? =
        if ((focus == Focus.DEEP || focus == Focus.CODING) && nextIsDevice && onlineReady && onlineAllowed)
            "💡 ${focus.label}: a stronger online model is set up. Pick it from the model chip for harder problems (your choice, nothing switches by itself)."
        else null

    /** Recent web evidence as a cited block ([W#]) with publisher and age; never more than [max] items. */
    data class Evidence(val title: String, val publisher: String, val url: String, val publishedAt: Long, val summary: String)
    fun evidenceBlock(items: List<Evidence>, now: Long, max: Int = 6): String = items.take(max).mapIndexed { i, e ->
        val age = if (e.publishedAt <= 0) "date not given" else ProjectBrief.age(now - e.publishedAt) + " ago"
        "[W${i + 1}] ${e.title} — ${e.publisher}, $age\n${e.summary.take(240)}"
    }.joinToString("\n")
    const val EVIDENCE_RULES = "Web evidence rules: these are recent news search results with their dates. Cite them as [W1], [W2]. Compare what " +
        "different sources say and point out disagreement. Separate reported facts from interpretation. If the evidence doesn't answer the question, " +
        "say so. Never invent sources, quotes, numbers or dates."
}
