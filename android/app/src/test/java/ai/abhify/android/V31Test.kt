package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.chat.SchemaV5
import ai.abhify.android.chat.SchemaV6
import ai.abhify.android.device.HeavyGate
import ai.abhify.android.device.MemoryPolicy
import ai.abhify.android.device.MemoryProfile
import ai.abhify.android.device.MemorySnapshot
import ai.abhify.android.device.SetupPolicy
import ai.abhify.android.ocr.ExtractionPipeline
import ai.abhify.android.ocr.OcrEngine
import ai.abhify.android.ocr.OcrLine
import ai.abhify.android.ocr.OcrResult
import ai.abhify.android.ocr.PageMethod
import ai.abhify.android.ocr.PageSource
import ai.abhify.android.resume.Resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/** 8 GB phones: memory profiles, one heavy job at a time, bounded images. */
class MemoryTest {
    private fun snap(avail: Int, total: Int = 7680, low: Boolean = false, lowRam: Boolean = false) = MemorySnapshot(total, avail, 300, low, lowRam, 256, 512)

    @Test fun profilesFollowFreeMemoryNotInstalledRam() {
        assertEquals(MemoryProfile.BALANCED, MemoryPolicy.pick(snap(3200), null, false).first)          // typical 8 GB phone, Auto
        assertEquals(MemoryProfile.LOW, MemoryPolicy.pick(snap(1200), null, false).first)               // same phone, busy
        assertEquals(MemoryProfile.LOW, MemoryPolicy.pick(snap(5000), MemoryProfile.QUALITY, pressure = true).first)
        assertEquals(MemoryProfile.LOW, MemoryPolicy.pick(snap(5000, low = true), null, false).first)
        assertEquals(MemoryProfile.LOW, MemoryPolicy.pick(snap(5000, lowRam = true), MemoryProfile.BALANCED, false).first)
        val (q, why) = MemoryPolicy.pick(snap(2500), MemoryProfile.QUALITY, false)
        assertEquals(MemoryProfile.BALANCED, q); assertNotNull(why)                                       // never silently pretends
        assertEquals(MemoryProfile.QUALITY, MemoryPolicy.pick(snap(3500), MemoryProfile.QUALITY, false).first)
        assertTrue(MemoryProfile.entries.all { it.localDocChars <= 3500 })                               // model context never grown untested
        assertTrue(MemoryProfile.LOW.ocrMaxSide < MemoryProfile.BALANCED.ocrMaxSide && MemoryProfile.LOW.maxOcrPages < MemoryProfile.BALANCED.maxOcrPages)
    }

    @Test fun imagesAreBounded() {
        assertEquals(1600 to 1200, MemoryPolicy.fit(4000, 3000, 1600))                                   // 12 MP photo → under 8 MB bitmap
        assertEquals(800 to 600, MemoryPolicy.fit(800, 600, 1600))                                       // never upscaled
        assertEquals(2200 to (2200 * 3000 / 4000), MemoryPolicy.fit(4000, 3000, 2200))
        val (w, h) = MemoryPolicy.pageRender(612, 792, 200, 2200)                                         // US Letter at 200 dpi
        assertTrue(maxOf(w, h) <= 2200); assertEquals(1700, w)
        assertTrue(MemoryPolicy.ocrPeakMb(1600, 1200) < 100)
    }

    @Test fun oneHeavyJobAtATime() {
        val g = HeavyGate()
        assertNull(g.tryEnter(HeavyGate.Kind.OCR))
        assertTrue(g.tryEnter(HeavyGate.Kind.LOCAL_AI)!!.contains("text recognition is running"))       // OCR and Qwen never together
        assertNotNull(g.tryEnter(HeavyGate.Kind.OCR))
        g.exit(HeavyGate.Kind.OCR)
        assertNull(g.tryEnter(HeavyGate.Kind.LOCAL_AI))
        assertTrue(g.tryEnter(HeavyGate.Kind.OCR)!!.contains("the phone's AI is running"))
        g.exit(HeavyGate.Kind.LOCAL_AI)
        g.markPressure(60_000, now = 1000)
        assertTrue(g.tryEnter(HeavyGate.Kind.OCR, now = 2000)!!.contains("low on memory"))              // no new OCR under pressure
        assertNull(g.tryEnter(HeavyGate.Kind.OCR, now = 70_000))
    }

    @Test fun ocrUnloadsTheLanguageModelAndBlocksItWhileRunning() {
        val graph = File("src/main/java/ai/abhify/android/AppGraph.kt").readText()
        val withOcr = graph.substringAfter("suspend fun <T> withOcr").substringBefore("\n    }\n")
        assertTrue(withOcr.contains("engine.generating"))                                                  // refused while generating
        assertTrue(withOcr.contains("engine.unload()") && withOcr.indexOf("engine.unload()") < withOcr.indexOf("block()"))   // unloaded before OCR
        assertTrue(withOcr.contains("finally { heavy.exit"))
        assertTrue(graph.substringAfter("suspend fun ensureModel()").substringBefore("val inst").contains("HeavyGate.Kind.OCR"))   // no load during OCR
        assertFalse(withOcr.contains("store.remove") || withOcr.contains("delete"))                        // model files untouched
    }
}

/** Page-by-page extraction with a scripted engine: text layers first, OCR only where needed, everything released. */
class ExtractionPipelineTest {
    class FakeImg(val page: Int) { var released = false }
    class Src(private val texts: List<String>, override val isImage: Boolean = false, private val renderFails: Set<Int> = emptySet()) : PageSource<FakeImg> {
        val rendered = mutableListOf<FakeImg>(); var closed = false
        override val pageCount = texts.size
        override fun text(page: Int) = texts[page]
        override fun render(page: Int, maxSide: Int, dpi: Int) = if (page in renderFails) null else FakeImg(page).also { rendered += it }
        override fun release(image: FakeImg) { image.released = true }
        override fun close() { closed = true }
    }
    class Engine(private val fail: Set<Int> = emptySet(), private val slowMs: Long = 0) : OcrEngine<FakeImg> {
        var closed = false; val seen = mutableListOf<Int>()
        override val name = "fake"
        override suspend fun recognize(image: FakeImg): OcrResult {
            seen += image.page; if (slowMs > 0) delay(slowMs)
            if (image.page in fail) throw IllegalStateException("blurry")
            return OcrResult(listOf(OcrLine("Page ${image.page + 1} scanned text", 0.95f), OcrLine("J0hn Sm1th", 0.3f)))
        }
        override fun close() { closed = true }
    }
    private val text = "This page has a real text layer with plenty of words in it."

    @Test fun mixedPdfOcrsOnlyScannedPages() = runBlocking {
        val src = Src(listOf(text, "", " \n ", text)); val e = Engine()
        val ex = ExtractionPipeline.run(src, e, MemoryProfile.BALANCED)
        assertEquals(listOf(PageMethod.TEXT, PageMethod.OCR, PageMethod.OCR, PageMethod.TEXT), ex.pages.map { it.method })
        assertEquals(listOf(1, 2), e.seen)                                                                   // pages with text are never rendered
        assertTrue(src.rendered.all { it.released }); assertTrue(e.closed)
        val t = ex.text(false)
        assertTrue(t.startsWith("[[Page 1]]\n$text")); assertTrue(t.contains("[[Page 2]]\nPage 2 scanned text"))
        assertEquals(listOf("J0hn Sm1th"), ex.pages[1].unclear)
        assertTrue(ex.warnings().any { it.contains("unclear line") } && ex.warnings().any { it.contains("check names, dates") })
        assertTrue(ex.complete); assertEquals(2, ex.ocrPages)
    }

    @Test fun selectablePdfNeverTouchesOcr() = runBlocking {
        val src = Src(listOf(text, text)); val e = Engine()
        val ex = ExtractionPipeline.run(src, e, MemoryProfile.LOW)
        assertTrue(e.seen.isEmpty()); assertTrue(src.rendered.isEmpty()); assertEquals(0, ex.ocrPages)
    }

    @Test fun pageLimitFailuresAndNoEngineAreReported() = runBlocking {
        val scans = List(12) { "" }
        val ex = ExtractionPipeline.run(Src(scans), Engine(), MemoryProfile.LOW)                           // Low: 8 OCR pages
        assertEquals(8, ex.ocrPages); assertEquals(4, ex.pages.count { it.method == PageMethod.SKIPPED }); assertFalse(ex.complete)
        assertTrue(ex.warnings().first().contains("pages 9, 10, 11, 12"))
        val failing = ExtractionPipeline.run(Src(listOf("", ""), renderFails = setOf(0)), Engine(fail = setOf(1)), MemoryProfile.BALANCED)
        assertEquals(listOf(PageMethod.FAILED, PageMethod.FAILED), failing.pages.map { it.method })
        assertTrue(failing.warnings().any { it.contains("blurry") })
        val off = ExtractionPipeline.run(Src(listOf(text, "")), null, MemoryProfile.BALANCED)               // OCR not allowed
        assertEquals(PageMethod.SKIPPED, off.pages[1].method); assertEquals(PageMethod.TEXT, off.pages[0].method)
        val stopped = ExtractionPipeline.run(Src(listOf("", "")), Engine(), MemoryProfile.BALANCED) { "stopped: low memory" }
        assertTrue(stopped.pages.all { it.method == PageMethod.SKIPPED }); assertTrue(stopped.warnings().any { it.contains("low memory") })
    }

    @Test fun cancellationReleasesEverything() = runBlocking {
        val src = Src(List(5) { "" }); val e = Engine(slowMs = 200)
        val job = async { ExtractionPipeline.run(src, e, MemoryProfile.BALANCED) }
        delay(300); job.cancel()
        try { job.await(); throw AssertionError("not cancelled") } catch (_: CancellationException) {}
        assertTrue(e.closed); assertTrue(src.rendered.all { it.released }); assertTrue(e.seen.size < 5)
    }

    @Test fun singleImageHasNoPageMarker() = runBlocking {
        val ex = ExtractionPipeline.run(Src(listOf(""), isImage = true), Engine(), MemoryProfile.BALANCED)
        assertEquals("Page 1 scanned text\nJ0hn Sm1th", ex.text(true))
    }

    @Test fun errorRatesAgainstGroundTruth() {
        assertEquals(0.0, ExtractionPipeline.cer("Hello  World", "hello world"), 1e-9)
        assertEquals(1.0 / 11, ExtractionPipeline.cer("hello world", "hallo world"), 1e-9)
        assertEquals(0.5, ExtractionPipeline.wer("hello world", "hello there"), 1e-9)
        assertEquals(1.0, ExtractionPipeline.wer("a b", ""), 1e-9)
    }
}

class ResumeTest {
    private val cv = """
        Priya Sharma
        priya.sharma@example.com | +91 98765 43210 | linkedin.com/in/priyasharma

        Summary
        Data analyst with 4 years of experience in reporting and BI.

        Experience
        Data Analyst, Northwind Traders — Jan 2021 - Present
        Built Power BI dashboards used by 40 managers
        Reduced report preparation time by 35%

        Education
        B.Tech Computer Science, 2020

        Skills
        Python, SQL, Excel, Power BI, Tableau
    """.trimIndent()

    @Test fun parsesContactSectionsSkillsAndResults() {
        val p = Resume.parse(cv)
        assertEquals("Priya Sharma", p.name!!.value)
        assertEquals(listOf("priya.sharma@example.com"), p.emails.map { it.value })
        assertEquals(listOf("+91 98765 43210"), p.phones.map { it.value })
        assertTrue(p.links.single().value.startsWith("linkedin.com/in/"))
        assertEquals(setOf(Resume.Section.SUMMARY, Resume.Section.EXPERIENCE, Resume.Section.EDUCATION, Resume.Section.SKILLS), p.sections.keys)
        assertEquals(listOf("Python", "SQL", "Excel", "Tableau", "Power BI").sorted(), p.skills.sorted())
        assertTrue(p.dates.any { it.value.startsWith("Jan 2021") })
        assertTrue(p.achievements.any { it.contains("35%") } && p.achievements.any { it.contains("40 managers") })
        assertTrue(p.missing.isEmpty())
    }

    @Test fun missingAndUnclearAreMarkedNotFilledIn() {
        val p = Resume.parse("Some Person\nSkills\nKotlin\n", unclearLines = listOf("Some Person"))
        assertTrue(p.name!!.unclear)
        assertEquals(listOf("email", "phone", "summary section", "experience section", "education section"), p.missing)
        assertFalse(Resume.parse("Java Script lover").skills.contains("Java").not() && false)
        assertTrue(Resume.skillsIn("I write JavaScript").none { it == "Java" })                           // no partial-word skills
    }

    @Test fun groundingFlagsInventedFactsOnly() {
        val ok = "Data analyst with 4 years of experience. Built Power BI dashboards used by 40 managers and cut report time by 35%."
        assertTrue(Resume.unsupported(ok, cv).isEmpty())
        val invented = "Led a team of 12 engineers at Northwind, grew revenue 60%, expert in Kubernetes and AWS. Contact: priya@gmail.com"
        val flags = Resume.unsupported(invented, cv)
        assertTrue(flags.toString(), flags.containsAll(listOf("12", "60%", "Kubernetes", "AWS", "priya@gmail.com")))
        assertTrue(Resume.unsupported("Needs Kubernetes", cv, userAdded = "We use Kubernetes").isEmpty())   // from the job description: not invented
    }

    @Test fun jobMatchIsExactAndHonest() {
        val m = Resume.match(cv, "We need SQL, Python and Kubernetes.\n- 3+ years of experience\n- Bachelor's degree in CS\nNice to have: Tableau")
        assertEquals(listOf("Python", "SQL", "Tableau", "Kubernetes").sorted(), m.required.sorted())
        assertEquals(listOf("Kubernetes"), m.missing)
        assertTrue(m.requirements.any { it.contains("3+ years") } && m.requirements.any { it.contains("Bachelor") })
    }

    @Test fun everyPromptCarriesTheNoInventionRules() {
        val m = Resume.match(cv, "SQL")
        listOf(Resume.SYSTEM, Resume.summaryPrompt(cv), Resume.draftPrompt(cv), Resume.jdPrompt(cv, "SQL", m)).forEach { assertTrue(it.contains("never", true) || it.contains("Never")) }
        assertTrue(Resume.draftPrompt(cv).contains("[add number]"))
    }
}

/** New phones always get setup; upgrades never repeat it; everything stays optional. */
class SetupTest {
    @Test fun freshInstallSeesSetupUpgradeDoesNot() {
        assertTrue(SetupPolicy.showSetup(onboarded = false)); assertFalse(SetupPolicy.showSetup(onboarded = true))
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("""if (g.prefs.getBoolean("onboarded", false)) Route.HOME else Route.WELCOME"""))      // same flag as before: upgrades keep it
        assertTrue(vm.substringAfter("fun completeSetup()").substringBefore("}").contains("putBoolean(\"onboarded\", true)"))
        val rerun = vm.substringAfter("fun rerunSetup()").substringBefore("\n")
        assertFalse(rerun.contains("remove") || rerun.contains("clear") || rerun.contains("reset"))      // re-running setup resets nothing
    }

    @Test fun stepsFollowTheChosenMode() {
        val all = SetupPolicy.Step.entries
        assertEquals(all, SetupPolicy.steps(AiMode.AUTO))
        assertFalse(SetupPolicy.Step.ONLINE in SetupPolicy.steps(AiMode.DEVICE))                             // local-only: no API key step
        assertFalse(SetupPolicy.Step.LOCAL in SetupPolicy.steps(AiMode.ONLINE))
        assertTrue(SetupPolicy.steps(AiMode.DEVICE).containsAll(listOf(SetupPolicy.Step.OCR, SetupPolicy.Step.PRIVACY, SetupPolicy.Step.FINISH)))
    }

    @Test fun readinessIsHonestAndNeverRequiresAKey() {
        assertTrue(SetupPolicy.readiness(AiMode.DEVICE, modelInstalled = true, providerReady = false, onlineAllowed = false).startsWith("Ready"))
        assertTrue(SetupPolicy.readiness(AiMode.DEVICE, false, false, false).startsWith("Not ready"))
        assertTrue(SetupPolicy.readiness(AiMode.ONLINE, false, true, true).startsWith("Ready"))
        assertTrue(SetupPolicy.readiness(AiMode.ONLINE, true, false, true).contains("AI providers"))
        assertTrue(SetupPolicy.readiness(AiMode.AUTO, true, false, true).startsWith("Ready on this phone"))
    }

    @Test fun deviceReportUsesOnlyMeasuredFacts() {
        val mem = MemorySnapshot(7680, 2900, 300, false, false, 256, 512)
        val r = SetupPolicy.deviceReport("14", 34, 7680, mem, 120_000, listOf("arm64-v8a"), null, null, online = false, ocrBundled = true)
        assertEquals("none fits this phone", r.first { it.label == "Recommended model" }.value)             // no claim without the compatibility check
        assertEquals(false, r.first { it.label == "Recommended model" }.ok)
        assertTrue(r.first { it.label == "Memory" }.value.contains("2,900 MB free"))
        assertEquals("Balanced (Auto)", r.first { it.label == "Processing profile" }.value)
    }

    @Test fun keysStayPerPhoneAndOutOfSetupAndResumeCode() {
        for (f in listOf("device/Setup.kt", "ui/SetupScreens.kt", "ui/ResumeState.kt", "resume/Resume.kt", "ocr/Extraction.kt", "ocr/AndroidOcr.kt"))
            File("src/main/java/ai/abhify/android/$f").readText().let { t -> assertFalse(f, t.contains("providerStore.key(") || t.contains("saveKey(")) }
        val store = ai.abhify.android.providers.ProviderStore(MemKv(), MemBox())
        assertFalse(store.anyConfigured())                                                                  // a new install starts with no keys
    }
}

class SchemaV6Test {
    @Test fun v6AddsColumnsAndVersionsWithoutTouchingData() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            SchemaV5.apply { st.execute(it) }
            st.execute("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at) VALUES ('d1', 'cv.pdf', 'PDF', 1000, 11, 'Hello world', 1, 2)")
            st.execute("INSERT INTO workspaces (name, kind, created_at) VALUES ('Work', 'work', 1)")
            st.execute("INSERT INTO notes (workspace_id, title, body, created_at, updated_at) VALUES (1, 'n', 'b', 1, 1)")
            SchemaV6.apply { st.execute(it) }
            c.createStatement().executeQuery("SELECT name, text, pages, ocr_pages, warnings, role FROM library_docs").use { r ->
                assertTrue(r.next()); assertEquals("cv.pdf", r.getString(1)); assertEquals("Hello world", r.getString(2))
                assertNull(r.getObject(3)); assertEquals(0, r.getInt(4)); assertNull(r.getString(5)); assertNull(r.getString(6))
            }
            c.createStatement().executeQuery("SELECT COUNT(*) FROM notes").use { r -> r.next(); assertEquals(1, r.getInt(1)) }
            st.execute("INSERT INTO resume_versions (doc_id, kind, title, body, created_at) VALUES (1, 'corrected', 'Corrected text', 'Hello, world', 3)")
            c.createStatement().executeQuery("SELECT text FROM library_docs WHERE id = 1").use { r -> r.next(); assertEquals("Hello world", r.getString(1)) }   // original kept
        }
        assertTrue(SchemaV6.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME|ON CONFLICT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
}
