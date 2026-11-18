package ai.abhify.android

import ai.abhify.android.career.CareerQuestionStore
import ai.abhify.android.career.CareerQuestions
import ai.abhify.android.career.McqPractice
import ai.abhify.android.career.McqStore
import ai.abhify.android.ui.CareerSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()

/** Career milestone: section order, MCQ generation/validation/dedup/labels, generated questions, refresh behaviour. */
class CareerNavigationTest {
    @Test fun sectionsAreInTheOwnersOrderWithJobsAndMatchLast() {
        assertEquals(listOf("Dashboard", "Resumes", "Question Bank", "MCQs", "Jobs", "Match"), CareerSection.entries.map { it.label })
        val tab = src("ui/CareerScreens.kt").substringAfter("fun CareerTab(").substringBefore("\n}\n")
        assertTrue(tab.contains("BackHandler(enabled = section != 0) { section = 0 }"))                 // back: section → Dashboard → leaves Career
        assertTrue(tab.contains("CareerSection.JOBS -> JobsView(vm) { j -> vm.career.selectedJd.value = j; vm.career.match.value = null; section = CareerSection.MATCH.ordinal }"))
        // nothing lost: interview practice and the curated bank are in Question Bank
        // 5.3: the three Question Bank views are one shared screen (also opened from Study → Questions)
        assertTrue(tab.contains("CareerSection.QUESTIONS -> QuestionBankView(vm, withMic, bankMode)"))
        assertTrue(src("ui/CareerScreens.kt").substringAfter("fun QuestionBankView(").contains("0 -> InterviewList(vm); 1 -> QuestionGenView(vm); else -> InterviewView(vm, withMic)"))
        assertTrue(tab.contains("CareerSection.MCQS -> McqView(vm)"))
    }
}

class McqPracticeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val resume = "Jane Doe\nSKILLS\nPython, FastAPI, PyTorch, Docker\nPROJECTS\n- Real-time object detection with YOLO and OpenCV on edge devices\n" +
        "- Speech transcription service using Whisper and FastAPI\nEXPERIENCE\nBuilt REST APIs with FastAPI serving 10k users."
    private val ctx = McqPractice.Context(McqPractice.Mode.RESUME, sourceId = 3, sourceName = "Jane.pdf", sourceText = resume, batch = 3)

    private fun line(q: String, opts: List<String>, ans: Char, why: String = "Because of X.", quote: String? = null) =
        "Q: $q\n" + opts.mapIndexed { i, o -> "${'A' + i}) $o" }.joinToString("\n") + "\nAnswer: $ans\n" + (if (why.isNotEmpty()) "Why: $why\n" else "") + (quote?.let { "Quote: $it\n" } ?: "")

    @Test fun promptsAreGroundedInTheChosenContextOnly() {
        val dev = McqPractice.prompt(ctx, listOf("What is Docker?"), device = true)
        assertTrue(dev.contains("<<<SOURCE") && dev.contains("FastAPI") && dev.contains("never invent experience") && dev.contains("Do not repeat"))
        assertTrue(dev.contains("Answer: the letter") && dev.startsWith("Write 3 medium"))
        val online = McqPractice.prompt(ctx.copy(batch = 5), emptyList(), device = false)
        assertTrue(online.contains("JSON array") && online.startsWith("Write 5"))
        val topic = McqPractice.prompt(McqPractice.Context(McqPractice.Mode.TOPIC, topic = "SQL joins", sourceText = resume), emptyList(), true)
        assertFalse(topic.contains("SOURCE") || topic.contains("Python"))                           // a topic never carries the resume
        val skills = McqPractice.prompt(ctx.copy(mode = McqPractice.Mode.SKILLS, skills = listOf("PyTorch")), emptyList(), true)
        assertTrue(skills.contains("these skills from the candidate's resume: PyTorch"))
        assertEquals(McqPractice.DEVICE_MAX_BATCH, Regex("Write (\\d)").find(McqPractice.prompt(ctx.copy(batch = 5), emptyList(), true))!!.groupValues[1].toInt())
    }

    @Test fun validBatchIsKeptAndLabelledHonestly() {
        val text = line("Which framework did the candidate use to build REST APIs?", listOf("Django", "FastAPI", "Flask", "Spring"), 'B',
            "The resume says the APIs were built with FastAPI; the others aren't mentioned.", "Built REST APIs with FastAPI serving 10k users.") + "\n" +
            line("What does YOLO detect in the candidate's project?", listOf("Objects", "Faces only", "Text", "Audio"), 'A', "YOLO is an object detector.", "made-up quote not in resume")
        val b = McqPractice.validate(text, ctx, emptyList())
        assertEquals(2, b.questions.size)
        assertEquals(McqPractice.Verification.SOURCE_QUOTED, b.questions[0].verification)            // quote really in the resume
        assertEquals(McqPractice.Verification.AI_ONLY, b.questions[1].verification)                  // quote not found: no claim
        assertTrue(McqPractice.Verification.SOURCE_QUOTED.label.contains("isn't independently verified"))
        assertEquals(1, b.questions[0].answer)                                                       // the model's answer is never changed
    }

    @Test fun malformedQuestionsAreDroppedOneByOneNotTheBatch() {
        val good = line("Which tool containerises the candidate's services?", listOf("Docker", "Excel", "Figma", "Jira"), 'A')
        val dupOptions = line("Which language is listed first in the skills?", listOf("Python", "python", "Java", "Go"), 'A')
        val noWhy = line("Which library is used for transcription?", listOf("Whisper", "YOLO", "Pandas", "NumPy"), 'A', why = "")
        val b = McqPractice.validate("$good\n$dupOptions\n$noWhy", ctx, emptyList())
        assertEquals(listOf("Which tool containerises the candidate's services?"), b.questions.map { it.q })
        // the repeated-options question is rejected while reading it (by the existing Study parser), the other at validation
        assertEquals(setOf("unreadable or malformed (e.g. repeated options, no valid answer)", "no explanation"), b.dropped.keys)
        assertEquals("no readable question", McqPractice.validate("Sorry, I can't.", ctx, emptyList()).dropped.keys.single())
    }

    @Test fun duplicatesAcrossBatchesAndRewordingsAreFiltered() {
        val q = "Which framework did the candidate use to build REST APIs?"
        val seen = listOf(McqPractice.normalize(q))
        assertTrue(McqPractice.isDuplicate("Which framework did the candidate use to build REST APIs", seen))
        assertTrue(McqPractice.isDuplicate("Which framework did the candidate use for building REST APIs?", seen))   // near-duplicate
        assertFalse(McqPractice.isDuplicate("What does YOLO detect in the candidate's project?", seen))
        assertFalse(McqPractice.isDuplicate("Which framework did the candidate use to build the frontend?", seen))  // shares words, different question
        val b = McqPractice.validate(line(q, listOf("Django", "FastAPI", "Flask", "Spring"), 'B'), ctx, seen)
        assertTrue(b.questions.isEmpty() && b.dropped.keys.single() == "duplicate of an earlier question")
    }

    @Test fun offTopicQuestionsAreDropped() {
        val sql = McqPractice.Context(McqPractice.Mode.TOPIC, topic = "SQL joins")
        val b = McqPractice.validate(line("Which SQL join returns only matching rows?", listOf("INNER JOIN", "LEFT JOIN", "FULL JOIN", "CROSS JOIN"), 'A') + "\n" +
            line("What is the capital of France?", listOf("Paris", "Rome", "Berlin", "Madrid"), 'A'), sql, emptyList())
        assertEquals(listOf("Which SQL join returns only matching rows?"), b.questions.map { it.q })
        assertEquals("not about the selected topic or skills", b.dropped.keys.single())
        assertEquals(McqPractice.Verification.AI_ONLY, b.questions.single().verification)            // topics have no source to quote
    }

    @Test fun batchesRotateAnglesAndResumeTechnologies() {
        val terms = McqPractice.techTerms(resume)
        assertTrue(terms.containsAll(listOf("python", "fastapi", "pytorch", "docker", "yolo", "opencv")) && "jane" !in terms && "doe" !in terms)
        val general = (0 until 10).mapNotNull { McqPractice.facetTerm(ctx, it) }
        assertTrue(general.toSet().size >= 4 && general.all { it in terms })                    // moves through the technologies
        assertEquals(null, McqPractice.facetTerm(ctx, 0))                                       // round 0: the candidate's own lines
        val p = McqPractice.prompt(ctx, emptyList(), device = true, round = 1)
        assertTrue(p.contains("about ${McqPractice.facetTerm(ctx, 1)} only") && p.contains("Ask general technical questions"))
        val off = McqPractice.validate(line("What does a Kubernetes pod contain?", listOf("Containers", "Tables", "Rows", "Keys"), 'A'), ctx, emptyList(), round = 1)
        assertEquals("doesn't name a technology from your document", off.dropped.keys.single())
        assertTrue(McqPractice.isDuplicate("Why might Jane choose PostgreSQL for the speech transcription service?",
            listOf(McqPractice.normalize("Why might Jane choose PostgreSQL for the speech transcription service job queue?"))))
    }

    @Test fun projectsAndProgressAreReadAndKept() {
        assertEquals(2, McqPractice.projectsOf(resume).size)
        val store = McqStore(File(tmp.root, "career_mcq.json"))
        val a = McqPractice.Attempt("k1", "SQL", "easy", "topic:sql", true, 1)
        store.save(McqStore.Data(listOf(a, a.copy(key = "k2", correct = false)), listOf("q one")))
        val d = store.load()
        assertEquals(2, d.attempts.size); assertEquals(listOf("q one"), d.seen)
        val s = McqPractice.stats(d.attempts)
        assertEquals(1, s.correct); assertEquals(1, s.wrong); assertEquals(50, s.accuracy)
        assertEquals(null, McqPractice.stats(emptyList()).accuracy)                                   // no fake percentage
    }

    @Test fun stateGeneratesInBatchesAndRefreshKeepsAnsweredQuestions() {
        val st = src("ui/McqState.kt")
        assertTrue(st.contains("if (busy.value) return"))                                              // no duplicate requests
        assertTrue(st.contains("val keep = questions.filter { it.key in picks }"))                     // refresh keeps answered ones
        assertTrue(st.contains("if (q.key in picks) return"))                                           // one answer per question
        assertTrue(st.contains("vm.aiTask(\"write practice questions\""))                               // existing AI path (mode, provider, consent)
        assertFalse(st.contains("setAiMode") || st.contains("pickModel"))                               // never switches model/provider itself
        val ui = src("ui/CareerPracticeScreens.kt")
        assertTrue(ui.contains("PullToRefreshBox(isRefreshing = m.busy.value") && ui.contains("Review mode") && ui.contains("q.verification.label"))
        assertTrue(ui.contains("Incorrect: you chose") && ui.contains("The answer is"))                 // immediate feedback with the right option
    }
}

class CareerQuestionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesSeveralQuestionsAndStoresThemApartFromTheBank() {
        val text = "1. Question: How did you deploy the Whisper service?\nOutline:\n- container\n- scaling\n\n**Question:** Why YOLO over Faster R-CNN?\nOutline: speed vs accuracy\n\nQuestion: ok"
        val qs = CareerQuestions.parse(text)
        assertEquals(listOf("How did you deploy the Whisper service?", "Why YOLO over Faster R-CNN?"), qs.map { it.first })
        val store = CareerQuestionStore(File(tmp.root, "career_questions.json"))
        store.save(listOf(CareerQuestions.Generated(qs[0].first, qs[0].second, "Project-based", "Jane.pdf", "From my resume", 1)))
        assertEquals("How did you deploy the Whisper service?", store.load().single().question)
        val p = CareerQuestions.prompt(CareerQuestions.Type.PROJECT, McqPractice.Context(McqPractice.Mode.RESUME, sourceText = "PROJECTS\n- YOLO"), 5, emptyList(), true)
        assertTrue(p.startsWith("Write 3") && p.contains("never invent experience"))
        assertTrue(src("ui/AppViewModel.kt").contains("Question Bank refresh: the curated questions"))   // curated bank refreshed, not replaced
    }
}

/** In-app news reading (Android): extraction, link safety, honest prompts, per-story state, staged Read more. */
class NewsReadingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val page = "<html><script>var a='<p>script text that must never be read as a paragraph of the story</p>'</script>" +
        "<nav><p>Home · World · Business · Sport · Technology · Science · Health · Entertainment</p></nav>" +
        "<p>The city council approved a new flood barrier on Monday after a two-year review of the river defences.</p>" +
        "<p>Short.</p><p>Subscribe to our newsletter today.</p>" +
        "<p>The barrier will cost 40 million and protect about 12,000 homes in the eastern districts, officials said.</p>" +
        "<footer><p>All rights reserved. Copyright 2026 Example News Group Limited and partners.</p></footer></html>"
    private fun art(id: String, provider: String = "rss", url: String = "https://news.example.com/$id") =
        ai.abhify.android.news.NewsArticle(id, "Title $id", "Example News", url, 0, 0, "world", "Short description $id", null, provider)

    @Test fun extractsOnlyTheArticleParagraphs() {
        val p = ai.abhify.android.news.ArticleReader.paragraphs(page)
        assertEquals(listOf("The city council approved", "The barrier will cost 40"), p.map { it.take(25).trim() })
    }

    @Test fun onlyPublicHttpsLinks() {
        val r = ai.abhify.android.news.ArticleReader
        assertTrue(r.safeUrl("https://www.bbc.co.uk/news/1"))
        for (bad in listOf("http://example.com/a", "https://localhost/a", "https://192.168.1.4/a", "https://intranet/a", "javascript:alert(1)", "not a url"))
            assertFalse(bad, r.safeUrl(bad))
        assertEquals("Google News links open the story only in a browser, so the article text isn't available here.",
            r.fetchParagraphs(art("g", "google-news", "https://news.google.com/x")).second)              // never fetched
    }

    @Test fun promptUsesOnlyThisStoryAndIsHonestAboutLimitedText() {
        val r = ai.abhify.android.news.ArticleReader
        val limited = r.prompt(art("a"), "Short description a", full = false, limit = 3000)
        assertTrue(limited.contains("Title a") && limited.contains("do not say you read the full article") && limited.contains("Do not add facts"))
        assertFalse(limited.contains("Title b"))
        assertFalse(r.prompt(art("a"), "x", full = true, limit = 3000).contains("do not say you read"))
        assertTrue(r.hash(art("a"), "one") != r.hash(art("a"), "two"))                                   // a changed text = new summary
        val store = ai.abhify.android.news.ArticleReader.Store(tmp.root)
        store.save(ai.abhify.android.news.ArticleReader.Record("a", listOf("p1"), "h1", "S", null))
        assertEquals("S", store.load("a")!!.summary); assertEquals(null, store.load("b"))                // per story id
    }

    @Test fun readerStateIsPerStoryAndReadMoreIsStaged() {
        val s = src("ui/NewsState.kt").substringAfter("fun openArticle(").substringBefore("    // ---------------------------------------------------------------- AI")
        assertTrue(s.contains("reading.value = Reading(a)"))                                               // a new story starts fresh (stage 0)
        assertTrue(s.contains("if (seq != readSeq) return@aiTask") && s.contains("if (seq != readSeq) return@launch"))   // late answers dropped
        assertTrue(s.contains("if (vm.taskBusy.value?.startsWith(READ_LABEL) == true) vm.cancelTask()"))  // only its own earlier summary
        val more = s.substringAfter("fun readMore()")
        assertTrue(more.indexOf("reading.value = r.copy(stage = 1); return null") < more.indexOf("return r.article.url"))
        assertTrue(more.contains("if (!ArticleReader.safeUrl(r.article.url))"))
        assertFalse(s.contains("startActivity"))                                                           // the state never opens a site
        val ui = src("ui/NewsScreens.kt")
        assertTrue(ui.contains("FeaturedCard(f, onClick = { n.openArticle(f) }") && ui.contains("ArticleCard(a, coverage[a.id] ?: 0, onClick = { n.openArticle(a) }"))
        assertTrue(ui.contains("{ n.readMore()?.let { onOpen(a) } }") && ui.contains("Dialog(onDismissRequest = n::closeArticle"))
        assertFalse(ui.contains("onRead = { read("))                                                       // cards no longer link out
    }
}

class RefreshBehaviourTest {
    @Test fun newsRefreshIsSingleFlightKeepsStoriesOnFailureAndReportsTheResult() {
        val s = src("ui/NewsState.kt").substringAfter("fun open(").substringBefore("\n    fun search")
        assertTrue(s.contains("if (force && refreshing.value && job?.isActive == true) return"))
        assertTrue(s.contains("if (r.articles.isNotEmpty() || result.value == null) result.value = r"))
        assertTrue(s.contains("Updated just now") && s.contains("the previous stories stay"))
        assertTrue(s.contains("if (category.value == cat && !searching.value)"))                         // late answers for another category ignored
        assertFalse(s.contains("aiTask") || s.contains("briefing"))                                       // refresh never regenerates the briefing
        val ui = src("ui/NewsScreens.kt")
        assertTrue(ui.contains("PullToRefreshBox(n.refreshing.value, ::refresh"))
        assertTrue(src("news/News.kt").contains("c.useCaches = false"))
        val bank = src("ui/HomeScreens.kt").substringAfter("internal fun InterviewList(").substringBefore("\n}\n")
        assertTrue(bank.contains("PullToRefreshBox(vm.bankRefreshing.value, vm::refreshBank") && bank.contains("Refresh questions"))
    }
}
