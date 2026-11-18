package ai.abhify.android

import ai.abhify.android.ui.Accent
import ai.abhify.android.ui.Brand
import ai.abhify.android.ui.ThemeTokens
import ai.abhify.android.ui.contrast
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4.6 "Aurora": feature colours, the AI orb, the animated navigation and the motion rules. */
class AuroraDesignTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }

    @Test fun featureColoursAreReadableInBothThemes() {
        val darkBgs = listOf(Brand.Bg1, Brand.Surface, Brand.Bg2)
        val lightBgs = listOf(Brand.LightBg, Color.White, Color(0xFFEFEDFB))
        for (a in Accent.entries) {
            darkBgs.forEach { assertTrue("${a.name} dark text", contrast(a.dark, it, it) >= 4.5) }
            lightBgs.forEach { assertTrue("${a.name} light text", contrast(a.light, it, it) >= 4.5) }
            // white icons on the tile gradient (non-text: 3:1), and white labels on g1 (section tabs, nav pill: 4.5:1)
            assertTrue("${a.name} g1", contrast(Color.White, a.g1, a.g1) >= 4.5)
            assertTrue("${a.name} g2", contrast(Color.White, a.g2, a.g2) >= 3.0)
        }
        // the deep aurora under white text (chat bubbles, Home hero) stays >= 6:1 on all three stops
        listOf(Color(0xFF5B3BF0), Color(0xFF4338CA), Color(0xFF1D4ED8)).forEach { assertTrue(contrast(Color.White, it, it) >= 6.0) }
        assertTrue(ThemeTokens.darkExtra.auroraA.alpha in 0.05f..0.35f && ThemeTokens.lightExtra.auroraA.alpha in 0.05f..0.2f)   // light, not paint
    }

    @Test fun orbShowsRealStateOnly() {
        val a = ui.getValue("Aurora.kt")
        val f = a.substringAfter("fun orbStateOf(").substringBefore("\n}\n")
        // every state comes from the view model: voice, generation, the phone model's error
        assertTrue(f.contains("vm.listening.value") && f.contains("vm.generating.value") && f.contains("Engine.Error"))
        assertTrue(a.contains("val animate = !reduced && !still && state != OrbState.ERROR"))        // reduced motion and errors are still
        assertTrue(a.contains("contentDescription = \"Abhify.AI: \${state.label}\""))                  // the state is spoken too
        // history messages use a still orb: only the message being written animates
        assertTrue(ui.getValue("HomeScreens.kt").contains("still = !m.streaming"))
    }

    @Test fun backgroundIsStaticAndCheap() {
        val t = ui.getValue("Theme.kt").substringAfter("fun BrandBackground(").substringBefore("\n}\n")
        assertTrue(t.contains("drawWithCache"))
        assertFalse(t.contains("rememberInfiniteTransition") || t.contains("animate"))
    }

    @Test fun navigationIsColouredAnimatedAndComplete() {
        val nav = ui.getValue("Aurora.kt").substringAfter("fun AuroraNavBar(").substringBefore("\n}\n")
        assertTrue(nav.contains("animateColorAsState") && nav.contains("a.g1") && nav.contains("role = Role.Tab") && nav.contains("this.selected = on"))
        val home = ui.getValue("HomeScreens.kt")
        assertTrue(home.contains("AuroraNavBar(tab.barParent, AppViewModel.Tab.entries.filter { it.inBar }") && home.contains("PageTransition(tab)"))
        val pt = ui.getValue("Aurora.kt").substringAfter("fun <T : Enum<T>> PageTransition(").substringBefore("\n}\n")
        assertTrue(pt.contains("if (reduced) fadeIn(tween(0))"))
    }

    @Test fun homeTilesUseRealData() {
        val h = ui.getValue("V45Screens.kt").substringAfter("fun HomeTab(").substringBefore("\n}\n")
        for (src in listOf("vm.studio.docs", "vm.life.apps", "vm.life.vault", "vm.memories", "data.dueCards", "vm.progress.value", "vm.activeModel()"))
            assertTrue(src, h.contains(src))
        assertFalse(Regex("\"\\d+ (cards|items|applications)").containsMatchIn(h))                      // no hard-coded counts
    }

    @Test fun flashcardsFlipAndRatingsKeepTheirSchedule() {
        val c = ui.getValue("V3Screens.kt").substringAfter("fun CardsView(").substringBefore("\n}\n")
        assertTrue(c.contains("rotationY = turn") && c.contains("reducedMotion()") && c.contains("s.rate(r)") && c.contains("Srs.next(st, r, now)"))
    }
}

/** 4.6 AI Action Center and the performance panel. */
class ActionCenterTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }

    @Test fun actionsAreFollowUpPromptsThroughTheNormalChat() {
        val long = "x".repeat(10_000)
        for (a in ai.abhify.android.chat.AiActions.Action.entries) {
            val p = ai.abhify.android.chat.AiActions.prompt(a, long, "Hindi")
            assertTrue(a.name, p.length < ai.abhify.android.chat.AiActions.MAX_SOURCE + 200)        // clipped
        }
        assertTrue(ai.abhify.android.chat.AiActions.prompt(ai.abhify.android.chat.AiActions.Action.TRANSLATE, "hi", "Hindi").contains("into Hindi"))
        val home = ui.getValue("HomeScreens.kt")
        assertTrue(home.contains("onAction = { prompt -> if (!vm.generating.value) vm.send(prompt) }"))      // the real send path, never mid-answer
        assertTrue(home.contains("if (onAction != null && !failed && !generating)"))
    }

    @Test fun performanceShowsOnlyMeasuredValues() {
        val m = ui.getValue("ManageScreens.kt").substringAfter("SettingsGroup(\"Performance on this phone\"").substringBefore("TextButton(vm::refreshDevice)")
        assertTrue(m.contains("vm.g.engine.currentMemoryMb()") && m.contains("d.availRamMb") && m.contains("vm.lastSpeed.value") && m.contains("vm.g.engine.peakMemoryMb()"))
        assertTrue(m.contains("\"–\""))                                                                          // a dash, never a guess, when not measured
        assertTrue(ui.getValue("AppViewModel.kt").contains("e.tokensPerSecond?.let { tps -> lastSpeed.value = LastSpeed("))
    }
}
