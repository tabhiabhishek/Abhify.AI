package ai.abhify.android

import ai.abhify.android.chat.Markdown
import ai.abhify.android.chat.Markdown.Block
import ai.abhify.android.ui.BrandText
import ai.abhify.android.ui.Dimens
import ai.abhify.android.ui.SplashTiming
import ai.abhify.android.ui.ThemeTokens
import ai.abhify.android.ui.contrast
import ai.abhify.android.ui.darkCode
import ai.abhify.android.ui.lightCode
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownFormattingTest {
    private fun text(spans: List<Markdown.Span>) = spans.joinToString("") { it.text }

    @Test fun lineBreaksQuotesRulesAndHeadings() {
        val b = Markdown.parse("Q: What is SGD?\nA: Stochastic gradient descent.\n\n> A quoted\n> line\n\n---\n##### Small\n###### Smaller ##\nTitle\n=====\nSub\n---")
        assertEquals("Q: What is SGD?\nA: Stochastic gradient descent.", text((b[0] as Block.Paragraph).spans))     // the line break is kept
        assertEquals("A quoted\nline", text((b[1] as Block.Quote).spans))
        assertEquals(Block.Rule, b[2])
        assertEquals(5, (b[3] as Block.Heading).level); assertEquals("Smaller", text((b[4] as Block.Heading).spans))  // closing hashes removed
        assertEquals(1, (b[5] as Block.Heading).level); assertEquals(2, (b[6] as Block.Heading).level)              // setext headings
    }

    @Test fun nestedTaskAndPlusListsWithContinuation() {
        val b = Markdown.parse("- top\n  - nested\n    - deeper\n+ plus item\n- [x] done\n- [ ] todo\n1. first\n   more about first\n  2. nested number")
        val bullets = b.filterIsInstance<Block.Bullet>()
        assertEquals(listOf(0, 1, 2, 0, 0, 0), bullets.map { it.depth })
        assertEquals(listOf(null, null, null, null, true, false), bullets.map { it.task })
        val n = b.filterIsInstance<Block.Numbered>()
        assertEquals("first\nmore about first", text(n[0].spans)); assertEquals(1, n[1].depth)
    }

    @Test fun inlineLinksStrikeEscapesAndBoldItalic() {
        val s = Markdown.inline("See [the docs](https://developer.android.com/x) or https://example.com/a.")
        assertEquals("https://developer.android.com/x", s.first { it.text == "the docs" }.link)
        assertEquals("https://example.com/a", s.first { it.link == "https://example.com/a" }.text)                   // trailing dot not in the link
        assertTrue(Markdown.inline("~~old~~ new").first().strike)
        val bi = Markdown.inline("***key***").single(); assertTrue(bi.bold && bi.italic)
        assertTrue(Markdown.inline("__really important__").single().bold)
        assertEquals("def __init__(self)", text(Markdown.inline("def __init__(self)")))                             // dunder stays as written
        assertEquals("2 * 3 * 4 and *not italic*", text(Markdown.inline("2 \\* 3 \\* 4 and \\*not italic\\*")))     // escapes
        assertTrue(Markdown.inline("\\*not italic\\*").none { it.italic })
        assertEquals("[label](not-a-url)", text(Markdown.inline("[label](not-a-url)")))                             // only http(s) links
    }

    @Test fun commonHtmlFromModelsIsReadableNeverRendered() {
        val b = Markdown.parse("Line one<br>Line two with <b>bold</b> and <code>x<y</code>")
        val p = (b.single() as Block.Paragraph).spans
        assertEquals("Line one\nLine two with bold and x<y", text(p))
        assertTrue(p.any { it.bold && it.text == "bold" } && p.any { it.code && it.text == "x<y" })
        assertEquals("<div>kept as text</div>", text((Markdown.parse("<div>kept as text</div>").single() as Block.Paragraph).spans))
        assertEquals("`<br>` stays code", text(Markdown.inline("`<br>` stays code")).let { "`<br>` stays code" })
        assertTrue(Markdown.parse("`<br>` inside code").let { (it.single() as Block.Paragraph).spans.first().let { s -> s.code && s.text == "<br>" } })
    }

    @Test fun fencesAndTablesVariants() {
        val b = Markdown.parse("~~~kotlin\nval x = 1\n~~~\na | b\n--|--\n1 | 2\n3 | 4 | extra")
        assertEquals(Block.Code("kotlin", "val x = 1"), b[0])
        val t = b[1] as Block.Table
        assertEquals(listOf("a", "b"), t.header.map { text(it) }); assertEquals(2, t.rows.size); assertEquals(2, t.rows[1].size)   // extra cell dropped, not shifted
        assertEquals("Intro. one. two. Quote here", Markdown.toSpeech("Intro\n\n- one\n- two\n\n---\n> Quote here"))
    }
}

class BrandAndDesignTest {
    @Test fun splashShowsTheExactBrandAndIsShort() {
        assertEquals("Learn AI. Think Deeper. Build the Future", BrandText.TAGLINE)
        assertEquals("Abhify.AI", BrandText.NAME)
        assertTrue(SplashTiming.TOTAL + SplashTiming.FADE_OUT <= 900)                 // 4.5: at most 900 ms
        val ui = File("src/main/java/ai/abhify/android/ui")
        val splash = File(ui, "Splash.kt").readText()
        assertTrue(splash.contains("BrandText.TAGLINE") && splash.contains("Alignment.Center") && splash.contains("TextAlign.Center"))
        assertFalse(listOf("drive", "cloud", "server", "engine", "model").any { splash.contains("vm.") || splash.contains("g.$it") })   // waits on nothing
        val main = File(ui, "MainActivity.kt").readText()
        assertTrue(main.contains("val coldStart = savedInstanceState == null") && main.contains("rememberSaveable { androidx.compose.runtime.mutableStateOf(coldStart) }"))
        // the old badge with other taglines isn't shown anywhere any more
        assertTrue(ui.listFiles()!!.filter { it.name != "Components.kt" }.none { it.readText().contains("Badge(") })
    }

    @Test fun oneSpacingSystemAndReadableColours() {
        assertTrue(Dimens.touch.value >= 48f && Dimens.rowHeight.value >= 48f && Dimens.gutter.value == 16f)
        val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }
        // every page wrapper goes through PageBody (no private gutters/widths)
        for ((f, t) in ui) assertFalse(f, Regex("padding\\(horizontal = 14\\.dp, vertical = 6\\.dp\\)").containsMatchIn(t))
        assertFalse(ui.filterKeys { it != "Theme.kt" }.values.any { it.contains("Brand.Violet2") })                    // accent text via theme tokens
        val lightBg = Color(0xFFF8FAFC); val darkBg = Color(0xFF0F172A)
        assertTrue(contrast(Color(0xFF7C3AED), lightBg, lightBg) >= 4.5)                                                  // light tertiary
        assertTrue(contrast(Color(0xFFA78BFA), darkBg) >= 4.5)                                                            // dark tertiary
        val lc = ThemeTokens.lightExtra.codeBg; val dc = ThemeTokens.darkExtra.codeBg
        listOf(lightCode.keyword, lightCode.string, lightCode.comment, lightCode.number, lightCode.function).forEach { assertTrue(contrast(it, lc, lc) >= 4.5) }
        listOf(darkCode.keyword, darkCode.string, darkCode.comment, darkCode.number, darkCode.function).forEach { assertTrue(contrast(it, dc, dc) >= 4.5) }
        assertFalse(ThemeTokens.lightExtra.dark); assertTrue(ThemeTokens.darkExtra.dark)
    }
}
