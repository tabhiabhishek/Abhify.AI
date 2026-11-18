package ai.abhify.android

import ai.abhify.android.docs.Library
import ai.abhify.android.learn.Studio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTest {
    // ------------------------------------------------------------------ retrieval and citations
    private val policy = """
        Leave policy

        Employees accrue 1.5 days of annual leave per month. Leave requests go to the line manager at least two weeks ahead.

        Expenses

        Travel expenses are reimbursed within 30 days when receipts are attached. Meals are capped at 40 euros per day.
    """.trimIndent()

    @Test fun retrievalFindsTheRightSectionAndCitesIt() {
        val chunks = Library.chunks(1, "policy.txt", policy, size = 160) + Library.chunks(2, "ml.txt", "Gradient descent minimises a loss by following the negative gradient.", 160)
        val hits = Library.search(chunks, "How many days of annual leave do I get?")
        assertTrue(hits.isNotEmpty()); assertTrue(hits.first().chunk.text.contains("1.5 days"))
        assertEquals("policy.txt", hits.first().chunk.docName)
        val ctx = Library.context(hits, 2000)
        assertTrue(ctx.text.startsWith("[S1] (policy.txt, section")); assertEquals(ctx.sources.first(), hits.first().chunk)
        assertTrue(Library.search(chunks, "quantum chromodynamics").isEmpty())              // no match → nothing to cite, never a made-up source
        assertEquals(setOf(1, 3), Library.cited("As stated [S1] and [S3], …"))
    }

    @Test fun pdfPagesBecomeCitablePages() {
        val text = Library.pageMarker(1) + "\nIntro text.\n\n" + Library.pageMarker(2) + "\nThe warranty lasts 24 months from purchase.\n"
        val chunks = Library.chunks(7, "manual.pdf", text)
        assertEquals(listOf(1, 2), chunks.map { it.page })
        assertEquals("manual.pdf, page 2", Library.search(chunks, "warranty months").first().chunk.where)
        assertEquals("— Page 1 —\nIntro text.\n\n— Page 2 —\nThe warranty lasts 24 months from purchase.\n", Library.readable(text))
    }

    @Test fun budgetIsRespected() {
        val big = (1..50).joinToString("\n\n") { "Paragraph $it about neural networks and training data. ".repeat(10) }
        val ctx = Library.context(Library.search(Library.chunks(1, "a", big), "neural networks training", 50), 3000)
        assertTrue(ctx.text.length <= 3000 + 200); assertTrue(ctx.sources.isNotEmpty())
    }

    // ------------------------------------------------------------------ parsers (strict)
    @Test fun flashcardsParseOnlyCompletePairs() {
        val out = "Here are cards:\nQ: What is overfitting?\nA: Fitting noise in the training data.\n\n**Q2:** What is a ReLU?\n**A:** max(0, x)\nQ: orphan question\n"
        assertEquals(listOf("What is overfitting?" to "Fitting noise in the training data.", "What is a ReLU?" to "max(0, x)"), Studio.parseCards(out))
        assertTrue(Studio.parseCards("I can't do that.").isEmpty())
    }

    @Test fun listsAndActionItems() {
        assertEquals(listOf("Day 1 – Basics", "Day 2 – Practice", "Revision"), Studio.parseList("Plan:\n1. Day 1 – Basics\n2) Day 2 – Practice\n- Revision\nThanks!"))
        assertEquals(listOf("Priya: send the budget (Friday)", "Book the room"), Studio.parseActions("- [ ] Priya: send the budget (Friday)\n- [x] Book the room"))
        assertTrue(Studio.parseActions("No action items.").isEmpty())
    }

    @Test fun multipleChoiceNeedsFourDistinctOptionsAndAValidAnswer() {
        val good = "Q: What does SGD stand for?\nA) Stochastic gradient descent\nB) Simple gradient design\nC) Sparse graph decoding\nD) Standard gain drop\nAnswer: A\nWhy: It samples mini-batches."
        val badLetter = "Q: x?\nA) a\nB) b\nC) c\nD) d\nAnswer: E"
        val dup = "Q: y?\nA) same\nB) same\nC) c\nD) d\nAnswer: B"
        val qs = Studio.parseMcqs("$good\n\n$badLetter\n\n$dup")
        assertEquals(1, qs.size); assertEquals(0, qs[0].correct); assertEquals("It samples mini-batches.", qs[0].why)
    }
    // PDF extraction needs Android's resource loader: see androidTest PdfTextTest (runs on the phone).
}
