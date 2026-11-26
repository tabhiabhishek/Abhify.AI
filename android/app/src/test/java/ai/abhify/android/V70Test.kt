package ai.abhify.android

import ai.abhify.android.ui.Brand
import ai.abhify.android.ui.ThemeTokens
import ai.abhify.android.ui.contrast
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 7.0 "Synapse" visual identity (approved Option A, docs/design/concepts/option-a-synapse.html). */
class SynapseIdentityTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }

    @Test fun synapseTokensMatchTheApprovedConcept() {
        assertEquals(Color(0xFF06070D), Brand.Bg1)
        assertEquals(Color(0xFF9D7BFF), Brand.Synapse)
        assertEquals(Color(0xFF3DE0FF), Brand.Cyan)
        assertEquals(Color(0xFFEEF0FF), Brand.Text)
        // readable: body, secondary and live-data text on every dark surface; white on the brand fill
        for (bg in listOf(Brand.Bg1, ThemeTokens.darkExtra.glass, Brand.Bg2)) {
            assertTrue(contrast(Brand.Text, bg, bg) >= 7.0)
            assertTrue(contrast(Brand.Muted, bg, bg) >= 4.5)
            assertTrue(contrast(Brand.CyanText, bg, bg) >= 4.5)
            assertTrue(contrast(Brand.Violet2, bg, bg) >= 4.5)
        }
        assertTrue(contrast(Color.White, Brand.Violet, Brand.Violet) >= 4.5)
    }

    @Test fun threeTypefacesAreBundledAndUsed() {
        val res = File("src/main/res/font")
        listOf("inter.ttf", "space_grotesk.ttf", "jetbrains_mono.ttf").forEach { assertTrue(it, File(res, it).length() > 50_000) }
        listOf("SpaceGrotesk-OFL.txt", "JetBrainsMono-OFL.txt").forEach { assertTrue(it, File("src/main/assets/licenses/$it").exists()) }   // OFL travels with the font
        val theme = ui.getValue("Theme.kt")
        assertTrue(theme.contains("displaySmall = t.displaySmall.grotesk(") && theme.contains("bodyLarge = t.bodyLarge.inter("))   // headings vs body
        assertTrue(theme.contains("val CodeStyle = TextStyle(fontFamily = Mono"))
        assertTrue(ui.getValue("Components.kt").contains("Text(text.uppercase(), style = EyebrowStyle"))                        // mono eyebrows
    }

    @Test fun neuralCoreHasTwoOrbitingRingsAndStillHonoursStateAndReducedMotion() {
        val a = ui.getValue("Aurora.kt")
        assertTrue(a.contains("ring(-18f + spin * 0.25f, ringA") && a.contains("ring(72f - spin * 0.4f, ringB"))
        assertTrue(a.contains("val animate = !reduced && !still && state != OrbState.ERROR"))
        assertTrue(a.contains("contentDescription = \"Abhify.AI: \${state.label}\""))
    }

    @Test fun bottomNavigationKeepsFiveDestinationsWithOneSynapsePill() {
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("HOME(\"Home\", \"🏠\"), CHAT(\"Assistant\", \"💬\"), STUDY(\"Study\", \"🎓\"), NEWS(\"News\", \"📰\"), MORE(\"More\", \"⋯\")"))
        assertTrue(ui.getValue("Aurora.kt").contains("val pillOn = if (x.dark) Brand.Synapse.copy(alpha = 0.24f)"))
    }
}
