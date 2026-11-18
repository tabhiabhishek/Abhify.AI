package ai.abhify.android

import ai.abhify.android.ui.Brand
import ai.abhify.android.ui.ThemeTokens
import ai.abhify.android.ui.contrast
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA on the real 4.5 theme tokens: 4.5:1 for body text, 3:1 for large text and non-text indicators. */
class ContrastTest {
    private fun check(name: String, fg: Color, bg: Color, min: Double, base: Color = bg) {
        val r = contrast(fg, bg, base)
        assertTrue("$name contrast %.2f < $min".format(r), r >= min)
    }

    @Test fun darkTheme() {
        val x = ThemeTokens.darkExtra
        for ((bgName, bg) in listOf("background" to Brand.Bg1, "card" to x.glass, "raised" to x.glassStrong)) {
            check("text on $bgName", Brand.Text, bg, 4.5)
            check("muted on $bgName", x.muted, bg, 4.5)
            check("brand text on $bgName", Brand.Violet2, bg, 4.5)
            check("lavender on $bgName", Brand.Lavender, bg, 4.5)
            check("ok on $bgName", x.ok, bg, 4.5)
            check("warn on $bgName", x.warn, bg, 4.5)
            check("bad on $bgName", x.bad, bg, 4.5)
            check("accent on $bgName", x.accent, bg, 3.0)
            check("hairline visible on $bgName", x.line, bg, 1.2)
        }
        check("white on brand button", Color.White, Brand.Violet, 4.5)
        check("dark text on dark primary", Color(0xFF14123A), Brand.Violet2, 4.5)
    }

    @Test fun lightTheme() {
        val x = ThemeTokens.lightExtra
        val primary = Color(0xFF5A51F5); val tertiary = Color(0xFF4B44D6)
        for ((bgName, bg) in listOf("background" to Brand.LightBg, "card" to x.glass)) {
            check("text on $bgName", Brand.Bg1, bg, 4.5)
            check("muted on $bgName", x.muted, bg, 4.5)
            check("primary text on $bgName", primary, bg, 4.5)
            check("brand text on $bgName", tertiary, bg, 4.5)
            check("ok on $bgName", x.ok, bg, 4.5)
            check("warn on $bgName", x.warn, bg, 4.5)
            check("bad on $bgName", x.bad, bg, 4.5)
            check("accent on $bgName", x.accent, bg, 3.0)
        }
        check("white on brand button", Color.White, Brand.Violet, 4.5)
        check("white on light primary", Color.White, primary, 4.5)
    }
}
