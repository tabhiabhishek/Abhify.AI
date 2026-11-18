package ai.abhify.android

import ai.abhify.android.learn.CourseDiscovery
import ai.abhify.android.learn.CourseDiscovery.Cost
import ai.abhify.android.news.NewsCatalog
import ai.abhify.android.news.NewsPrefs
import ai.abhify.android.news.NewsQuery
import ai.abhify.android.ui.AppViewModel
import ai.abhify.android.ui.OrbState
import ai.abhify.android.voice.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()
private fun res(name: String) = File("src/test/resources/$name").readText()

/** 5.3: the five approved destinations and the More groups. */
class FiveDestinationsTest {
    @Test fun bottomBarIsExactlyTheFiveApprovedDestinations() {
        assertEquals(listOf("Home", "Assistant", "Study", "News", "More"), AppViewModel.Tab.entries.filter { it.inBar }.map { it.label })
        assertEquals(AppViewModel.Tab.MORE, AppViewModel.Tab.CAREER.barParent)
    }

    @Test fun moreIsGroupedAsSpecified() {
        val more = src("ui/V3Screens.kt").substringAfter("fun SettingsTab(").substringBefore("\n}\n")
        val order = listOf("\"Knowledge\"", "\"Workspace\"", "\"Career\"", "\"AI & System\"", "\"Settings & Privacy\"").map { more.indexOf("SettingsGroup($it") }
        assertTrue(order.all { it >= 0 }); assertEquals(order.sorted(), order)                       // present, in this order
    }

    @Test fun studyHasDiscoverAndTheSameAssessmentScreensAsCareer() {
        val home = src("ui/HomeScreens.kt")
        assertTrue(home.contains("5 -> McqView(vm); 6 -> QuestionBankView(vm, withMic, bankMode)"))
        assertTrue(src("ui/CareerScreens.kt").contains("CareerSection.QUESTIONS -> QuestionBankView(vm, withMic, bankMode)"))   // one screen, two entry points
        assertTrue(home.contains("else -> DiscoverView(vm)"))
        // existing section numbers kept (other screens open Study by number)
        assertTrue(home.contains("0 -> StudyView(vm); 1 -> TutorView(vm); 2 -> PlansView(vm); 3 -> CardsView(vm); 4 -> QuizView(vm)"))
    }
}

/** 5.3 neural core: states come from real activity only. */
class NeuralCoreTest {
    @Test fun allSixRequestedStatesExist() {
        val names = OrbState.entries.map { it.name }
        listOf("IDLE", "LISTENING", "SEARCHING", "THINKING", "GENERATING", "OFFLINE").forEach { assertTrue(it, it in names) }
        assertEquals("Responding", OrbState.GENERATING.label)
    }

    @Test fun searchingAndOfflineAreDrivenByRealState() {
        val vm = src("ui/AppViewModel.kt"); val aurora = src("ui/Aurora.kt")
        assertTrue(vm.contains("searching.value = docIds.isNotEmpty() || knowledge") && vm.contains("finally { searching.value = false }"))
        assertTrue(aurora.contains("vm.searching.value -> OrbState.SEARCHING"))
        assertTrue(aurora.contains("!vm.online.value && (vm.aiMode.value == ai.abhify.android.ai.AiMode.ONLINE || vm.activeModel() == null) -> OrbState.OFFLINE"))
        assertTrue(aurora.contains("state != OrbState.OFFLINE      // static fallback"))                 // offline (and reduced motion) is still
    }
}

/** 5.3 Study → Discover, parsed from real responses saved on 2026-11-18. */
class CourseDiscoveryTest {
    @Test fun mitResultsKeepMitsOwnCostData() {
        val r = CourseDiscovery.parseMit(res("discover_mit.json"))
        assertEquals(3, r.size)
        assertTrue(r.all { it.url.startsWith("https://ocw.mit.edu/courses/") && it.provider == "MIT OpenCourseWare" && it.cost == Cost.FREE })
        assertTrue(r.all { it.license == "CC BY-NC-SA 4.0" && it.source == "MIT Learn" })
        assertEquals("Machine Learning for Healthcare", r[0].title)
    }

    @Test fun costIsNeverAssumed() {
        fun one(free: Boolean, cert: Boolean, prices: String) = CourseDiscovery.parseMit("""{"results":[{"title":"T","url":"https://x.mit.edu/c","free":$free,
            "certification":$cert,"prices":$prices,"offered_by":{"name":"MIT xPRO"}}]}""").single().cost
        assertEquals(Cost.PAID, one(false, true, "[\"11550.00\"]"))
        assertEquals(Cost.FREE_AUDIT, one(true, true, "[]"))
        assertEquals(Cost.UNKNOWN, one(false, false, "[]"))                                  // no price, not free: say "Check the provider"
        assertTrue(CourseDiscovery.parseMit("not json").isEmpty())
    }

    @Test fun openStaxAndWikibooksMatchTheTopic() {
        val books = CourseDiscovery.parseOpenStax(res("discover_openstax.json"), "Statistics")
        assertTrue(books.isNotEmpty() && books.all { "Statistics" in it.title && it.url.startsWith("https://openstax.org/details/books/") })
        assertTrue(CourseDiscovery.parseOpenStax(res("discover_openstax.json"), "Cooking").isEmpty())
        val wb = CourseDiscovery.parseWikibooks(res("discover_wikibooks.json"))
        assertEquals(listOf("Python Programming"), wb.map { it.title })                      // sub-pages are skipped
        assertEquals("https://en.wikibooks.org/wiki/Python_Programming", wb[0].url)
    }

    @Test fun providerLinksAreSearchPagesNotInventedCourses() {
        val links = CourseDiscovery.providerLinks("Machine Learning")
        assertEquals(CourseDiscovery.PROVIDERS.size, links.size)
        assertTrue(links.all { it.kind == CourseDiscovery.Kind.SEARCH && it.cost == Cost.UNKNOWN && it.url.startsWith("https://") })
        assertTrue(links.first { it.provider == "Coursera" }.url.endsWith("query=Machine+Learning"))
        val free = CourseDiscovery.combine(CourseDiscovery.parseMit(res("discover_mit.json")), "ml", freeOnly = true)
        assertTrue(free.isNotEmpty() && free.all { it.cost == Cost.FREE })                       // "Free only" drops search links and unknown costs
    }

    @Test fun learningPathIsHandedToPlansAndLabelledAiGenerated() {
        assertTrue(src("ui/DiscoverState.kt").contains("vm.studio.planTopicPrefill.value = query.value; vm.learnMode.value = 2"))
        assertTrue(src("ui/DiscoverScreens.kt").contains("It's AI-generated advice; the resources below are real."))
    }
}

/** 5.3 News: English, Hindi or both, from the shared catalog. */
class MultilingualNewsTest {
    private val catalog = NewsCatalog.parse(File("src/main/assets/news_sources.json").readText())

    @Test fun catalogIsSharedWithTheDesktopAndHasVerifiedHindiFeeds() {
        assertEquals(File("../../backend/news_sources.json").readText(), File("src/main/assets/news_sources.json").readText())
        val hi = catalog.categories.flatMap { c -> c.feeds.filter { it.lang == "hi" } }
        assertTrue(hi.size >= 20 && hi.all { it.url.startsWith("https://") })
        assertEquals("भारत", catalog.byId("india")!!.label("hi")); assertEquals("India", catalog.byId("india")!!.label("en"))
    }

    @Test fun languageChoosesFeedsAndGoogleEdition() {
        val en = NewsQuery.plan(catalog, "india", NewsPrefs(language = "en")).feeds
        val hi = NewsQuery.plan(catalog, "india", NewsPrefs(language = "hi")).feeds
        val both = NewsQuery.plan(catalog, "india", NewsPrefs(language = "both")).feeds
        assertTrue(en.none { it.first in setOf("Amar Ujala", "Live Hindustan") } && en.any { it.second.contains("ceid=IN:en") })
        assertTrue(hi.any { it.first == "Amar Ujala" } && hi.none { it.first == "The Hindu" } && hi.any { it.second.contains("ceid=IN:hi") })
        assertEquals((en + hi).map { it.second }.toSet(), both.map { it.second }.toSet())
        // Hindi without a Hindi publisher feed or a Google topic: Google News (Hindi) search
        assertTrue(NewsQuery.plan(catalog, "ai", NewsPrefs(language = "hi")).feeds.single().second.contains("hl=hi-IN"))
    }

    @Test fun englishCacheKeyIsUnchangedSoOldCachesStayValid() {
        assertEquals("cat-india", NewsQuery.cacheKey("india", NewsPrefs()))
        assertEquals("cat-india-hi", NewsQuery.cacheKey("india", NewsPrefs(language = "hi")))
        assertEquals("cat-india-both", NewsQuery.cacheKey("india", NewsPrefs(language = "both")))
    }

    @Test fun translationIsLabelledAndHindiIsReadWithAHindiVoiceOnly() {
        assertTrue(Voice.isHindi("भारत में आज बारिश हुई")); assertFalse(Voice.isHindi("Rain in India today")); assertFalse(Voice.isHindi(""))
        val state = src("ui/NewsState.kt")
        assertTrue(state.contains("AI translation into Hindi · not the publisher's text"))
        assertTrue(src("news/NewsCenter.kt").contains("Translate only: add, remove or "))
        assertTrue(src("voice/Voice.kt").contains("No Hindi voice on this phone"))              // never read Hindi with an English voice
    }

    @Test fun homeShowsTheSavedNewsWithItsTimeNeverAsBreaking() {
        val home = src("ui/V45Screens.kt")
        assertTrue(home.contains("vm.g.newsCenter.cached(") && home.contains("\"News · saved \${ago(data.newsAt)}\""))
        assertFalse(home.substringAfter("5.3 command center").substringBefore("AI Task Runner entry").contains("newsCenter.load("))   // Home never fetches
    }
}
