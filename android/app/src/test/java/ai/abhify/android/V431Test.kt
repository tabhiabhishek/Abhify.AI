package ai.abhify.android

import ai.abhify.android.chat.Markdown
import ai.abhify.android.chat.Markdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiPolishTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }

    @Test fun dialogsScrollAndButtonRowsWrap() {
        // every plain-text dialog body goes through DialogText (scrolls instead of clipping); only a dropdown item may use Text
        val plain = ui.flatMap { (f, t) -> t.lines().filter { "text = { Text(" in it && "onClick" !in it }.map { "$f: ${it.trim().take(60)}" } }
        assertTrue(plain.toString(), plain.isEmpty())
        assertTrue(ui.values.none { it.contains("dismissButton = { Row {") })                       // several buttons wrap (FlowRow)
        assertTrue(ui.getValue("Components.kt").contains("fun DialogText(") && ui.getValue("Components.kt").contains("verticalScroll(rememberScrollState())"))
    }

    @Test fun splashMatchesTheSystemSplashAndDecorationIsSilent() {
        // 4.5: the reveal uses the app's own theme (and the system splash follows the phone's theme: values / values-night)
        assertTrue(ui.getValue("MainActivity.kt").contains("BrandSplash { splash = false } }"))
        val c = ui.getValue("Components.kt")
        assertTrue(c.contains("RoundedCornerShape(Dimens.fieldRadius)).clearAndSetSemantics { }"))   // NavRow icon tile: decorative
        assertTrue(c.contains("CircleShape).clearAndSetSemantics { }"))                                 // empty-state icon: decorative
        // 4.4: bottom-bar icons are vector icons with no description of their own (the tab label is read)
        // 4.6: the aurora bar draws the same vector icons, silent (the tab label is read)
        assertTrue(ui.getValue("HomeScreens.kt").contains("AuroraNavBar(") && ui.getValue("Aurora.kt").contains("Icon(painterResource(icon(t)), null"))
    }

    @Test fun representativeAnswerRendersAsStructuredBlocks() {
        val sample = """
            # Training a model
            ## Steps
            Prepare the data.<br>Then <b>train</b>.

            1. Split the data
               - train set
               - [x] validation set
            2. Fit

            ```python
            def __init__(self):
                self.lr = 0.01
            ```

            | Optimizer | Learning rate |
            |---|---|
            | Adam | 0.001 |

            > Tip: watch the validation loss.

            ---
            See [PyTorch docs](https://pytorch.org/docs) and use `__init__` or snake_case_name, not \*stars\*.
        """.trimIndent()
        val b = Markdown.parse(sample)
        assertEquals(listOf(Block.Heading::class, Block.Heading::class, Block.Paragraph::class, Block.Numbered::class, Block.Bullet::class, Block.Bullet::class,
            Block.Numbered::class, Block.Code::class, Block.Table::class, Block.Quote::class, Block.Rule::class, Block.Paragraph::class), b.map { it::class })
        assertEquals("Prepare the data.\nThen train.", (b[2] as Block.Paragraph).spans.joinToString("") { it.text })
        assertTrue((b[5] as Block.Bullet).task == true && (b[4] as Block.Bullet).depth == 1)
        assertTrue((b[7] as Block.Code).text.contains("def __init__(self):"))
        val last = (b.last() as Block.Paragraph).spans
        assertEquals("See PyTorch docs and use __init__ or snake_case_name, not *stars*.", last.joinToString("") { it.text })
        assertTrue(last.any { it.link == "https://pytorch.org/docs" } && last.none { it.italic })
    }
}
