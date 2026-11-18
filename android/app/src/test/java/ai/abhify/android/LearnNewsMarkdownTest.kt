package ai.abhify.android

import ai.abhify.android.chat.Markdown
import ai.abhify.android.learn.Learning
import ai.abhify.android.learn.LearningBank
import ai.abhify.android.learn.Streak
import ai.abhify.android.news.FeedParser
import ai.abhify.android.news.NewsItem
import ai.abhify.android.news.NewsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class LearnNewsMarkdownTest {
    // ---------------------------------------------------------------- markdown
    @Test fun markdownBlocksAndInline() {
        val b = Markdown.parse("## Gradient descent\nIt **minimizes** the *loss* using `lr`.\n\n- step one\n- step two\n1. first\n2) second\n```python\nx = 1\n```\nend")
        assertEquals(Markdown.Block.Heading::class, b[0]::class)
        val p = b[1] as Markdown.Block.Paragraph
        assertTrue(p.spans.any { it.bold && it.text == "minimizes" } && p.spans.any { it.italic && it.text == "loss" } && p.spans.any { it.code && it.text == "lr" })
        assertEquals(2, b.count { it is Markdown.Block.Bullet })
        assertEquals(listOf(1, 2), b.filterIsInstance<Markdown.Block.Numbered>().map { it.number })
        assertEquals(Markdown.Block.Code("python", "x = 1"), b.first { it is Markdown.Block.Code })
        assertEquals("end", (b.last() as Markdown.Block.Paragraph).spans.single().text)
    }

    @Test fun markdownSurvivesStreamingAndOddInput() {
        assertEquals("**half", Markdown.inline("**half").joinToString("") { it.text })          // unclosed bold stays text
        assertEquals("2 * 3 = 6", Markdown.inline("2 * 3 = 6").joinToString("") { it.text })
        assertEquals("snake_case_name", Markdown.inline("snake_case_name").joinToString("") { it.text })
        val unfinished = Markdown.parse("Here:\n```\ncode still streaming")
        assertEquals("code still streaming", (unfinished.last() as Markdown.Block.Code).text)
        assertTrue(Markdown.parse("").isEmpty())
        assertEquals("Hi there. one. There is a code example on screen", Markdown.toSpeech("**Hi** there\n\n- one\n```\nx=1\n```"))
    }

    // ---------------------------------------------------------------- learning
    private val bank = LearningBank.parse(File("src/main/assets/learning_bank.json").readText())

    @Test fun builtInBankIsValidAndCoversEveryCategory() {
        assertEquals(30, bank.questions.size); assertEquals(30, bank.mcqs.size)
        bank.categories.forEach { (id, _) -> assertTrue(id, bank.questions(id).isNotEmpty() && bank.mcqs(id).isNotEmpty()) }
        bank.mcqs.forEach { assertTrue(it.question, Learning.validMcq(it)) }
        val web = File("../../frontend/mock-data.js").readText()                    // the web app's samples are all included
        Regex("question: \"([^\"]+)\"").findAll(web).map { it.groupValues[1] }.forEach { q ->
            assertTrue(q, bank.questions.any { it.question == q } || bank.mcqs.any { it.question == q })
        }
    }

    @Test fun parsesMcqFromMessyModelOutput() {
        val fenced = "Sure! Here it is:\n```json\n{\"question\": \"What does an epoch mean in training?\", \"options\": [\"One batch\", \"One full pass over the data\", \"One layer\", \"One weight\"], \"correctAnswer\": 1, \"explanation\": \"An epoch is one full pass.\"}\n```\nHope it helps"
        val m = Learning.parseMcq(fenced, "dl")!!
        assertEquals(1, m.correct); assertEquals("dl", m.category); assertTrue(m.generated)
        val byText = """{"question": "Which optimizer uses moment estimates?", "options": ["SGD", "Adam", "Grid", "None"], "correctAnswer": "Adam", "explanation": "Adam tracks moments."}"""
        assertEquals(1, Learning.parseMcq(byText, "dl")!!.correct)
        val asString = """{"question": "Which optimizer uses moment estimates?", "options": ["SGD", "Adam", "Grid", "None"], "correctAnswer": "1", "explanation": "x"}"""
        assertEquals(1, Learning.parseMcq(asString, "dl")!!.correct)
        assertNull(Learning.parseMcq("""{"question": "Q long enough?", "options": ["a", "b", "c"], "correctAnswer": 0, "explanation": "x"}""", "ml"))   // 3 options
        assertNull(Learning.parseMcq("""{"question": "Q long enough?", "options": ["a", "a", "c", "d"], "correctAnswer": 0, "explanation": "x"}""", "ml"))   // duplicates
        assertNull(Learning.parseMcq("""{"question": "Q long enough?", "options": ["a", "b", "c", "d"], "correctAnswer": 7, "explanation": "x"}""", "ml"))   // bad index
        assertNull(Learning.parseMcq("I can't do that.", "ml"))
    }

    @Test fun parsesInterviewQuestion() {
        val q = Learning.parseInterview("""{"question": "What is early stopping?", "answer": "Early stopping halts training when validation loss stops improving, which limits overfitting.", "difficulty": "Easy"}""", "ml")!!
        assertEquals("easy", q.difficulty)
        assertNull(Learning.parseInterview("""{"question": "Too short?", "answer": "No."}""", "ml"))
        assertNotNull(Learning.extractJson("""noise {"a": "brace } inside", "b": {"c": 1}} tail"""))
    }

    @Test fun streakCountsConsecutiveDays() {
        assertEquals(0, Streak.days(emptySet(), 100))
        assertEquals(3, Streak.days(setOf(98, 99, 100), 100))
        assertEquals(2, Streak.days(setOf(98, 99), 100))           // yesterday still counts
        assertEquals(0, Streak.days(setOf(97, 98), 100))
        assertEquals(1, Streak.days(setOf(100, 98), 100))
    }

    // ---------------------------------------------------------------- news
    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>X</title>
        <item><title>New &amp; faster model released</title><link>https://ex.com/a</link>
        <description><![CDATA[<p>The model is <b>2x</b> faster&nbsp;and open.</p>]]></description><pubDate>Fri, 23 Oct 2026 16:15:50 GMT</pubDate></item>
        <item><title>Second</title><link>https://ex.com/b</link><pubDate>Thu, 22 Oct 2026 14:30:24 +0000</pubDate></item>
        <item><title>Insecure</title><link>http://ex.com/c</link></item>
        </channel></rss>"""
    private val atom = """<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>V</title>
        <entry><title type="html">Atom story about agents</title><link rel="alternate" type="text/html" href="https://v.com/1"/>
        <link rel="self" href="https://v.com/self"/><updated>2026-10-25T13:52:12+00:00</updated><summary type="html">&lt;p&gt;Agents ship.&lt;/p&gt;</summary></entry></feed>"""

    private fun parse(xml: String, src: String) = FeedParser.parse(xml.byteInputStream(), src, 8, KXmlParser())

    @Test fun parsesRssAndAtom() {
        val r = parse(rss, "OpenAI")
        assertEquals(2, r.size)                                     // the http:// item is dropped
        assertEquals("New & faster model released", r[0].title)
        assertEquals("The model is 2x faster and open.", r[0].summary)
        assertEquals(1792772150000L, r[0].publishedAt)
        assertTrue(r[1].publishedAt > 0)
        val a = parse(atom, "The Verge").single()
        assertEquals("https://v.com/1", a.url); assertEquals("Agents ship.", a.summary); assertTrue(a.publishedAt > 0)
    }

    @Test fun stopsEarlyOnHugeFeeds() {
        val big = "<rss><channel>" + (1..1500).joinToString("") { "<item><title>T$it</title><link>https://e.com/$it</link></item>" } + "</channel></rss>"
        assertEquals(8, parse(big, "big").size)
    }

    @Test fun mergeSortsDedupesAndDropsOld() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val a = listOf(NewsItem("OpenAI releases new reasoning model for developers", "", "OpenAI", "https://a/1", now - day))
        val b = listOf(NewsItem("OpenAI releases new reasoning model to developers today", "", "Verge", "https://b/1", now - 2 * day),
            NewsItem("Old story", "", "Verge", "https://b/2", now - 40 * day),
            NewsItem("Fresh hardware news", "", "Verge", "https://b/3", now - 3600_000))
        val m = FeedParser.merge(listOf(a, b), now)
        assertEquals(listOf("https://b/3", "https://a/1"), m.map { it.url })
    }

    @Test fun feedListIsHttpsAndParsable() {
        val feeds = NewsRepository.feeds(File("src/main/assets/news_feeds.json").readText())
        assertEquals(8, feeds.size); assertTrue(feeds.all { it.url.startsWith("https://") })
    }
}

/** Opt-in, real network: ABHIFY_LIVE=1 gradlew testDebugUnitTest --tests '*LiveFeedsTest*' */
class LiveFeedsTest {
    @Test fun allConfiguredFeedsParse() {
        org.junit.Assume.assumeTrue(System.getenv("ABHIFY_LIVE") == "1")
        val feeds = NewsRepository.feeds(File("src/main/assets/news_feeds.json").readText())
        val lists = feeds.map { f ->
            val repo = NewsRepository(feeds, File.createTempFile("news", ".json"))
            val items = repo.fetchFeed(f) { FeedParser.parse(it, f.name, 8, KXmlParser()) }      // the app's own fetch code
            println("LIVE ${f.name}: ${items.size} items, newest '${items.firstOrNull()?.title?.map { c -> if (c.code < 128) c.toString() else "U+%04X".format(c.code) }?.joinToString("")}'")
            assertTrue(f.name, items.isNotEmpty())
            items
        }
        val merged = FeedParser.merge(lists, System.currentTimeMillis())
        println("LIVE merged: ${merged.size} items; top: ${merged.take(3).map { it.source + ": " + it.title }}")
        assertTrue(merged.size >= 10)
    }
}
