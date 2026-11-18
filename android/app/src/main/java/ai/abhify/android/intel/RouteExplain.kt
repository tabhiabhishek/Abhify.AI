package ai.abhify.android.intel

import ai.abhify.android.ai.Route
import ai.abhify.android.ai.RouteInputs
import ai.abhify.android.ai.RoutePolicy

/**
 * 5.1 Provider transparency: explains, before you ask, where the next answer will come from and what may go with it.
 * It reads the existing RoutePolicy (unchanged: explicit mode, no silent provider switch) and the privacy switches;
 * for a focus that a small phone model handles less well it adds a hint, never a switch.
 */
object RouteExplain {
    data class Explanation(val route: String, val privacy: String, val hint: String?)

    fun label(r: Route) = when (r) {
        Route.Device -> "this phone (Qwen, offline)"
        Route.Server -> "your Abhify server"
        is Route.Personal -> r.provider.title
        is Route.Unavailable -> "unavailable: ${r.reason}"
    }

    fun explain(i: RouteInputs, focus: Intelligence.Focus, memoriesOnline: Boolean, knowledgeOnline: Boolean, historyOnline: Boolean): Explanation {
        val plan = RoutePolicy.plan(i)
        val route = when {
            plan.size == 1 -> "${i.mode.label}: ${label(plan[0])}"
            else -> "${i.mode.label}: ${label(plan[0])}, then ${plan.drop(1).joinToString(", then ") { label(it) }} if it fails before answering"
        }
        val online = plan.any { it is Route.Personal || it is Route.Server }
        val privacy = if (!online) "Everything stays on this phone." else listOf(
            "Online answers get your question" + (if (historyOnline) " and recent chat" else ""),
            if (memoriesOnline) "relevant memories" else null,
            if (knowledgeOnline) "knowledge passages after you agree" else null,
        ).filterNotNull().joinToString(", ") + ". " + listOfNotNull(if (!memoriesOnline) "Memories" else null, if (!knowledgeOnline) "documents/knowledge" else null)
            .takeIf { it.isNotEmpty() }?.let { it.joinToString(" and ") + " stay on this phone." }.orEmpty()
        val heavy = focus == Intelligence.Focus.CODING || focus == Intelligence.Focus.RESEARCH
        val hint = if (heavy && plan.firstOrNull() == Route.Device && i.usableProviders.isNotEmpty() && i.online && i.onlineAllowed)
            "Long code reviews and research may be better with ${i.usableProviders.first().title}: switch to Online AI in the header if you want (it isn't switched for you)." else null
        return Explanation(route, privacy.trim(), hint)
    }
}
