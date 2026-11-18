package ai.abhify.android

import ai.abhify.android.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unified Aurora workspace (Android): the brand launcher reaches every destination using existing tabs/routes only. */
class BrandLauncherTest {
    private val aurora = File("src/main/java/ai/abhify/android/ui/Aurora.kt").readText()
    private val sheet = aurora.substringAfter("fun BrandLauncherSheet(").substringBefore("\n}\n")

    @Test fun everyMajorDestinationIsInTheLauncher() {
        for (t in listOf("HOME", "CHAT", "STUDY", "NEWS", "DOCS", "KNOWLEDGE", "MORE")) assertTrue("tab $t", sheet.contains("tab = AppViewModel.Tab.$t"))
        for (r in listOf("TASKS", "MEMORIES", "MODELS", "SETTINGS", "ACCOUNT", "ABOUT")) assertTrue("route $r", sheet.contains("route = AppViewModel.Route.$r"))
        for (g in listOf("\"Workspace\"", "\"AI System\"", "\"Application\"")) assertTrue(g, sheet.contains(g))
        // only existing destinations: every referenced route/tab really exists
        Regex("Route\\.([A-Z]+)").findAll(sheet).forEach { assertTrue(it.value, AppViewModel.Route.entries.any { r -> r.name == it.groupValues[1] }) }
        Regex("Tab\\.([A-Z]+)").findAll(sheet).forEach { assertTrue(it.value, AppViewModel.Tab.entries.any { t -> t.name == it.groupValues[1] }) }
    }

    @Test fun openingTheLauncherStartsNothing() {
        for (bad in listOf("aiTask(", "vm.send(", "ensureModel", "pickModel", "readyDevice", ".load(", "startDownload", "refresh", "newsCenter.open")) assertFalse(bad, sheet.contains(bad))
        assertTrue(sheet.contains("onClose()") && sheet.contains("vm.home(); vm.tab.value = it.tab") && sheet.contains("it.route?.let(vm::go)"))   // closes, then navigates
        assertTrue(sheet.contains("AiOrb(orbStateOf(vm), 36, still = true)"))                                                  // status from existing state
    }

    @Test fun reachedFromTheHeaderLogoAndTheBottomBarStays() {
        val home = File("src/main/java/ai/abhify/android/ui/HomeScreens.kt").readText()
        assertTrue(home.contains("onClickLabel = \"Open navigation\") { launcherOpen = true }") && home.contains("BrandLauncherSheet(vm) { launcherOpen = false }"))
        assertEquals(listOf("Home", "Assistant", "Study", "News", "More"), AppViewModel.Tab.entries.filter { it.inBar }.map { it.label })
    }
}
