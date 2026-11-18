package ai.abhify.android

import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.chat.PromptBuilder.Msg
import ai.abhify.android.intel.Brain
import ai.abhify.android.intel.Intelligence
import ai.abhify.android.intel.Intelligence.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun main6(path: String) = File("src/main/java/ai/abhify/android/$path").readText()

/** 6.0 Personal AI Brain: one plan per turn; modes with real behaviour; honest notices; privacy unchanged. */
class BrainTest {
    private val h = listOf(Msg("user", "How does RAG chunking work in my Abhify app?"), Msg("assistant", "It splits…"))

    @Test fun modesReallyChangeBehaviour() {
        val q = Brain.profile(Focus.QUICK); val d = Brain.profile(Focus.DEEP); val c = Brain.profile(Focus.CREATIVE); val g = Brain.profile(Focus.GENERAL)
        assertEquals(4, q.historyMessages); assertEquals(384, q.deviceMaxTokens); assertEquals(false, q.contextFinder)   // less context, shorter, faster
        assertEquals(12, d.historyMessages); assertTrue(d.scaffold.contains("consider at least one alternative") && d.scaffold.contains("check the key conclusion"))
        assertEquals(0.9f, c.temperature!!, 0f); assertEquals(false, c.contextFinder)
        assertTrue(Brain.profile(Focus.RESEARCH).web && Brain.profile(Focus.RESEARCH).contextFinder == true)
        assertTrue(Brain.profile(Focus.PLANNING).scaffold.contains("never invent deadlines") && Brain.profile(Focus.PLANNING).scaffold.contains("cannot change saved records"))
        assertEquals(Brain.Profile(8, null, null, null, false, ""), g)                                                // General = the pre-6.0 behaviour
        // the scaffold reaches the prompt through the focus format
        assertTrue(Intelligence.format(Intelligence.detect("x", Focus.DEEP)).startsWith("Deep think mode"))
        assertTrue(PromptBuilder.build("q", (1..20).map { Msg("user", "m$it") }, emptyList(), "", historyMessages = 4).size == 6)   // system + 4 + question
    }

    @Test fun autoDetectsPlanningAndCreativeButNeverQuickOrDeep() {
        assertEquals(Focus.PLANNING, Intelligence.detect("Make a roadmap and timeline for my thesis").focus)
        assertEquals(Focus.CREATIVE, Intelligence.detect("Write a poem about the monsoon").focus)
        assertEquals(Focus.LEARNING, Intelligence.detect("make a study plan to learn statistics, explain the order").focus)   // older focus wins ties
        assertTrue(listOf("explain quickly", "think deeply about this").none { Intelligence.detect(it).focus in setOf(Focus.QUICK, Focus.DEEP) })
    }

    @Test fun intentsAndPersonalContextRules() {
        val own = Brain.plan("what are my notes on kotlin", h, Focus.AUTO, false, false, false, true)
        assertEquals(Brain.Intent.OWN_RECORDS, own.intent); assertTrue(own.contextFinder)
        assertFalse(Brain.plan("explain transformers", h, Focus.AUTO, false, false, false, true).contextFinder)            // unrelated: nothing personal retrieved
        assertTrue(Brain.plan("my notes on kotlin", h, Focus.QUICK, false, false, false, true).contextFinder)               // explicit own-records question still answered
        assertFalse(Brain.plan("explain kotlin", h, Focus.QUICK, true, false, false, true).contextFinder)                    // Quick keeps context minimal
        assertFalse(Brain.plan("my notes on kotlin", h, Focus.AUTO, true, false, workspace = true, webAllowed = true).contextFinder)   // project chat has its own
        val st = Brain.plan("thanks!", h, Focus.RESEARCH, true, true, false, true)
        assertEquals(Brain.Intent.SMALL_TALK, st.intent); assertFalse(st.contextFinder || st.web)
        assertEquals("why? ${h[0].content}", Brain.plan("why?", h, Focus.AUTO, false, false, false, true).retrievalQuery)  // continuity
    }

    @Test fun freshInformationIsHonestAndResearchGathersEvidenceOnlyWhenAllowed() {
        val n = Brain.plan("what is the latest news on the Indian budget", h, Focus.AUTO, false, false, false, true)
        assertEquals(Brain.Intent.FRESH_INFO, n.intent); assertFalse(n.web); assertTrue(n.notices.single().contains("don't browse"))
        val r = Brain.plan("latest news on the Indian budget", h, Focus.RESEARCH, false, false, false, webAllowed = true)
        assertTrue(r.web && r.notices.isEmpty())
        val off = Brain.plan("latest news on the Indian budget", h, Focus.RESEARCH, false, false, false, webAllowed = false)
        assertFalse(off.web); assertTrue(off.notices.single().contains("online access is off"))
        assertTrue(Brain.STALE_WARNING.contains("Never invent recent events"))
        val block = Brain.evidenceBlock(listOf(Brain.Evidence("Budget 2026", "Mint", "https://m/1", 1_000L, "Summary"), Brain.Evidence("B", "BBC", "u", 0, "")), now = 1_000L + 3_600_000L)
        assertTrue(block.startsWith("[W1] Budget 2026 — Mint, ") && block.contains("[W2] B — BBC, date not given"))
        assertTrue(Brain.EVIDENCE_RULES.contains("Never invent sources"))
    }

    @Test fun routeAdviceIsShownNeverApplied() {
        assertNotNull(Brain.routeAdvice(Focus.DEEP, nextIsDevice = true, onlineReady = true, onlineAllowed = true))
        assertNull(Brain.routeAdvice(Focus.DEEP, true, true, onlineAllowed = false))                                          // privacy setting respected
        assertNull(Brain.routeAdvice(Focus.GENERAL, true, true, true))
        assertFalse(main6("intel/Brain.kt").contains("RoutePolicy"))                                                         // the brain never routes
    }

    @Test fun wiringKeepsPrivacyRules() {
        val vm = main6("ui/AppViewModel.kt")
        assertTrue(vm.contains("val brainPlan = ai.abhify.android.intel.Brain.plan(question, history, focus.value"))
        assertTrue(vm.contains("val personal = if (!brainPlan.contextFinder) emptyList()"))
        assertTrue(vm.contains("val wsOnline = ai.abhify.android.workspace.ContextOrchestrator.build(wsParts, 4000)"))     // personal records never online
        assertTrue(vm.contains("(if (docsConsented) ragDoc(onlineCtx, wsOnline) else null)"))                                // private docs only after consent
        assertTrue(vm.contains("val webItems = if (!brainPlan.web) emptyList()"))                                            // Google News only in Research
        assertTrue(vm.contains("g.hybrid.tuneNext(") && vm.contains("g.hybrid.generate(local, onlinePrompt, routeInputs())"))
        val hy = main6("ai/HybridAi.kt")
        assertTrue(hy.contains("tuning?.maxTokens?.coerceIn(64, deviceMaxTokens)") && hy.contains("tuning ?: nextTuning.getAndSet(null)"))
    }
}
