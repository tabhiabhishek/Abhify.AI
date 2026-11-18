package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.ModelChoices
import ai.abhify.android.ai.Source
import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.ModelCatalog
import ai.abhify.android.model.ModelStore
import ai.abhify.android.providers.Provider
import ai.abhify.android.ui.AppIcons
import ai.abhify.android.ui.AppViewModel
import ai.abhify.android.ui.SplashTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 4.5 chat model picker: built from the real catalog, installed models and configured providers only. */
class ModelPickerTest {
    private val catalog = ModelCatalog.parse(File("src/main/assets/model_catalog.json").readText())
    private val big = "qwen2.5-1.5b-instruct-q4_k_m"; private val small = "qwen2.5-0.5b-instruct-q4_k_m"

    private fun inputs(mode: AiMode = AiMode.DEVICE, installed: Set<String> = setOf(big), active: String? = big, onlineAllowed: Boolean = true,
                       providers: List<ModelChoices.ProviderInfo> = emptyList(), target: String? = null, signedIn: Boolean = false) =
        ModelChoices.Inputs(mode, catalog.models, installed, active, onlineAllowed, providers, target, signedIn)

    @Test fun listsTheRealCatalogAndMarksWhatIsInstalled() {
        val c = ModelChoices.build(inputs())
        assertEquals(listOf("auto", "device:$big", "device:$small"), c.map { it.key })          // nothing invented
        assertEquals(ModelChoices.State.ACTIVE, c.first { it.key == "device:$big" }.state)
        val s = c.first { it.key == "device:$small" }
        assertEquals(ModelChoices.State.NOT_INSTALLED, s.state); assertFalse(s.selectable)
        assertTrue(s.reason!!.contains("AI models"))                                            // never downloaded from the picker
        assertEquals("Qwen2.5 1.5B Instruct", ModelChoices.chipLabel(inputs()))
    }

    @Test fun bothInstalledModelsAreSelectableAndTheChoiceFollowsTheActiveOne() {
        val c = ModelChoices.build(inputs(installed = setOf(big, small), active = small))
        assertEquals(ModelChoices.State.ACTIVE, c.first { it.key == "device:$small" }.state)
        assertEquals(ModelChoices.State.AVAILABLE, c.first { it.key == "device:$big" }.state)
        assertEquals("Qwen2.5 0.5B Instruct", ModelChoices.chipLabel(inputs(installed = setOf(big, small), active = small)))
    }

    @Test fun onlineEntriesOnlyForConfiguredProvidersAndOnlyWhenAllowed() {
        val p = listOf(ModelChoices.ProviderInfo(Provider.GROQ, "llama-3.1-8b-instant", true, null),
            ModelChoices.ProviderInfo(Provider.GEMINI, "gemini-x", true, "free tier not confirmed"),
            ModelChoices.ProviderInfo(Provider.OPENROUTER, "m", false, "no key"))
        val c = ModelChoices.build(inputs(mode = AiMode.ONLINE, providers = p, target = "groq"))
        assertEquals(ModelChoices.State.ACTIVE, c.first { it.key == "online:groq" }.state)
        assertEquals(ModelChoices.State.UNAVAILABLE, c.first { it.key == "online:gemini" }.state)
        assertTrue(c.none { it.key == "online:openrouter" })                                   // not configured: not listed
        assertEquals("Groq · llama-3.1-8b-instant", ModelChoices.chipLabel(inputs(mode = AiMode.ONLINE, providers = p, target = "groq")))
        assertTrue(ModelChoices.build(inputs(onlineAllowed = false, providers = p, signedIn = true)).none { it.kind == ModelChoices.Kind.ONLINE })
        assertTrue(ModelChoices.build(inputs(providers = p, signedIn = true)).any { it.key == "online:shared" })
    }

    @Test fun emptyAndUnselectedStatesAreHonest() {
        val none = inputs(installed = emptySet(), active = null)
        assertTrue(ModelChoices.build(none).filter { it.kind == ModelChoices.Kind.DEVICE }.none { it.selectable })
        assertEquals("No phone model", ModelChoices.chipLabel(none))
        assertEquals("No online provider", ModelChoices.chipLabel(inputs(mode = AiMode.ONLINE)))
        assertEquals("Auto", ModelChoices.chipLabel(inputs(mode = AiMode.AUTO)))
        assertNull(ModelChoices.selectedKey(none))
    }

    @Test fun pickerIsWiredToRealSwitchingAndNeverDownloads() {
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        val pick = vm.substringAfter("fun pickModel(").substringBefore("\n    }\n")
        assertTrue(pick.contains("g.store.setActive(id)") && pick.contains("g.engine.unload()") && pick.contains("readyDevice()"))
        assertFalse(pick.contains("startDownload") || pick.contains("DownloadService"))
        assertTrue(pick.contains("generating.value"))                                         // never switches mid-answer
        val sheet = File("src/main/java/ai/abhify/android/ui/V45Screens.kt").readText()
        assertTrue(sheet.contains("Route.MODELS") && !sheet.contains("startDownload"))
        // the engine loads whatever ModelStore says is active, so the switch reaches real inference
        val graph = File("src/main/java/ai/abhify/android/AppGraph.kt").readText()
        assertTrue(graph.substringAfter("suspend fun ensureModel()").contains("val inst = store.active()"))
    }
}

/** Switching the active phone model in the real store: only installed models, persisted, one active at a time. */
class ModelSwitchStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = ByteArray(64) { it.toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private fun model(id: String) = CatalogModel(id, id, "1B", "v1", "https://x.invalid/$id", "test", "Apache-2.0", "", "",
        "gguf", "Q4_K_M", payload.size.toLong(), sha, 1000, 2000, listOf(2048), 2048, 29, listOf("arm64-v8a"), false, false, "")

    @Test fun setActiveSwitchesBetweenInstalledModelsAndPersists() {
        val root = tmp.newFolder("models")
        val store = ModelStore(root)
        for (id in listOf("a", "b")) {
            val part = store.partFile(id).also { it.parentFile!!.mkdirs(); it.writeBytes(payload) }
            store.install(model(id), part, "rt", 2048) { ModelStore.SelfTestResult(true, "ok") }
        }
        assertEquals("b", store.activeId())                                                    // the last install
        store.setActive("a")
        assertEquals("a", ModelStore(root).activeId())                                          // persisted (default for new chats)
        assertEquals(store.modelFile("a").absolutePath, store.modelFile(store.active()!!.id).absolutePath)
        assertTrue(runCatching { store.setActive("missing") }.isFailure)                         // not installed: refused
        assertEquals("a", store.activeId())
        assertEquals(2, store.installed().size)                                                 // switching removes nothing
    }

    @Test fun historicalAnswersKeepTheirModelLabel() {
        val s = Source(Source.Kind.DEVICE, "Qwen2.5 1.5B Instruct")
        assertEquals(s, Source.decode(s.encode()))                                              // stored per message, never rewritten
    }
}

/** 4.5 navigation, launch, icons and colour rules. */
class PremiumRedesignTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }

    @Test fun everyDestinationIsReachableFromHomeOrMore() {
        // More + Settings (opened from More; the AI and System groups moved there in the stability milestone) + the project list
        val nav = ui.getValue("V3Screens.kt").substringAfter("fun SettingsTab(").substringBefore("\n}\n") + ui.getValue("V45Screens.kt") +
            ui.getValue("ManageScreens.kt").substringAfter("fun SettingsScreen(").substringBefore("\n}\n") +
            ui.getValue("ProjectState.kt").substringAfter("fun openList()").substringBefore("\n")
        assertTrue(ui.getValue("V3Screens.kt").substringAfter("fun SettingsTab(").contains("vm.go(AppViewModel.Route.SETTINGS)"))   // Settings is reachable from More
        val onboarding = setOf("WELCOME", "PROFILE", "LOGIN", "REGISTER", "FORGOT", "LINK", "HOME", "SETUP")
        for (r in AppViewModel.Route.entries.map { it.name } - onboarding) assertTrue("Route $r", nav.contains("Route.$r"))
        for (t in listOf("CAREER", "KNOWLEDGE")) assertTrue("Tab $t", nav.contains("Tab.$t"))
        assertTrue(nav.contains("rerunSetup()"))
        assertEquals(AppViewModel.Tab.MORE, AppViewModel.Tab.CAREER.barParent)
        assertEquals(AppViewModel.Tab.HOME, AppViewModel.Tab.entries.first())
        assertFalse(ui.getValue("HomeScreens.kt").contains("ModalNavigationDrawer("))           // the drawer moved into More
    }

    @Test fun launchIsShortSkippableThemeAwareAndRespectsReducedMotion() {
        assertTrue(SplashTiming.TOTAL + SplashTiming.FADE_OUT <= 900)
        assertTrue(SplashTiming.REDUCED <= 300)
        val splash = ui.getValue("Splash.kt")
        assertTrue(splash.contains("reducedMotion()") && splash.contains("onClickLabel = \"Skip\""))
        assertFalse(splash.contains("ensureModel") || splash.contains("drive") || splash.contains("cloud"))   // never waits for I/O
        val res = File("src/main/res")
        assertTrue(File(res, "values-night/colors.xml").readText().contains("#171827"))
        assertTrue(File(res, "values/colors.xml").readText().contains("#F7F7FC"))
    }

    @Test fun gradientIsSelectiveAndPinkIsRetired() {
        assertFalse(ui.values.any { it.contains("Brand.Pink") })
        val users = ui.filter { (n, t) -> n != "Theme.kt" && t.contains("Brand.Accent") }.keys
        assertEquals(setOf("Splash.kt", "V45Screens.kt", "HomeScreens.kt"), users)               // 4.6: launch ring, send buttons (icons only)
        assertEquals(1, Regex("Brand\\.Accent").findAll(ui.getValue("HomeScreens.kt")).count())
        assertTrue(ui.getValue("Theme.kt").contains("SolidColor(Brand.Bg1), Brand.Deep,"))                // your chat bubble: white text >= 6:1
        assertTrue(ui.getValue("Components.kt").contains("background(if (enabled) Accent.CHAT.brush else"))   // primary buttons: violet -> purple
    }

    @Test fun navigationRowsUseVectorIcons() {
        val res = File("src/main/res/drawable")
        val glyphs = ui.values.flatMap { Regex("NavRow\\(\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.toSet()
        assertTrue(glyphs.isNotEmpty())
        for (g in glyphs) assertTrue("no icon for $g", AppIcons.of(g) != null)
        for (name in listOf("ic_home", "ic_apps", "ic_send", "ic_mic", "ic_smartphone", "ic_cloud_queue", "ic_auto_awesome", "ic_expand_more"))
            assertTrue(name, File(res, "$name.xml").readText().contains("Apache License 2.0"))
    }
}
