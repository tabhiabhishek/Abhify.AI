package ai.abhify.android

import ai.abhify.android.ui.ago
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun ui61(name: String) = File("src/main/java/ai/abhify/android/ui/$name").readText()

/** 6.1 audit fixes: system Back, overlays, stale project data, dead controls, confirmations, loading states, accessibility. */
class V61Test {
    @Test fun systemBackClosesDetailViewsBeforeLeavingTheScreen() {
        assertTrue(ui61("ProjectScreens.kt").contains("BackHandler(enabled = open != null) { p.open.value = null }"))
        assertTrue(ui61("TaskScreens.kt").contains("BackHandler(enabled = ts.current.value != null && !ts.running) { ts.open(null) }"))
        assertTrue(ui61("ResumeScreens.kt").contains("BackHandler(enabled = r.selected.value != null) { r.open(null) }"))
        val life = ui61("LifeScreens.kt")
        assertTrue(life.contains("BackHandler(enabled = l.open.value != null) { l.open.value = null }"))
        assertTrue(life.contains("BackHandler(enabled = l.openItem.value != null) { l.openItem.value = null }"))
    }

    @Test fun overlaysHandleBackAndDontLetTapsThrough() {
        val voice = ui61("VoiceScreens.kt"); val agent = ui61("BrainScreens.kt")
        assertTrue(voice.contains("androidx.activity.compose.BackHandler { v.stop() }"))
        assertTrue(agent.contains("AgentState.Phase.AWAITING_APPROVAL) a.cancel() else a.close()"))     // Back never approves anything
        assertTrue(voice.contains(".pointerInput(Unit) { detectTapGestures { } }") && agent.contains(".pointerInput(Unit) { detectTapGestures { } }"))
    }

    @Test fun nonBarTabsReturnToWhereTheyWereOpenedFrom() {
        val home = ui61("HomeScreens.kt")
        assertTrue(home.contains("LaunchedEffect(tab) { if (tab.inBar) lastBarTab = tab }"))
        assertTrue(home.contains("vm.tab.value = if (tab.inBar) AppViewModel.Tab.HOME else parentTab"))
        assertTrue(home.contains("\"Conversation history\", { vm.go(AppViewModel.Route.HISTORY) }"))           // history from the Assistant tab
    }

    @Test fun projectPageNeverShowsAnotherProjectsData() {
        val p = ui61("ProjectState.kt")
        assertTrue(p.contains("if (open.value?.id == w.id) overview.value = o"))
        assertTrue(p.contains("fun openProject(w: Workspace) { if (open.value?.id != w.id) overview.value = null"))
        assertTrue(p.contains("}.getOrNull()"))                                                                // a database error doesn't crash
    }

    @Test fun noDeadControlsAndDestructiveActionsAsk() {
        val tasks = ui61("TaskScreens.kt"); val v3 = ui61("V3Screens.kt")
        assertFalse(tasks.contains("r.type.replaceFirstChar { it.uppercase() }) {}"))                            // the fake source button is gone
        assertTrue(tasks.contains("Delete this AI task?") && tasks.contains("{ deletingTask = t }"))
        assertTrue(v3.contains("Delete this task?") && v3.contains("{ deleting = t }"))
        assertFalse(v3.contains("reminders and calendar sync are not connected yet"))                            // reminders exist (5.6)
        assertTrue(v3.contains("if (r.text.isNotBlank()) androidx.compose.foundation.layout.FlowRow {"))          // result actions wrap
        assertFalse(tasks.contains("val docs = remember { ts.sourceDocs }"))                                     // no database read in composition
    }

    @Test fun homeShowsLoadingNotFakeEmptyAndOneRecentChatCounts() {
        val home = ui61("V45Screens.kt")
        assertTrue(home.contains("if (data.loaded) sub else \"Loading…\""))
        assertTrue(home.contains("lastHomeData = data") && home.contains("mutableStateOf(lastHomeData ?: HomeData())"))
        assertTrue(home.contains("if (data.recent.isNotEmpty()) BentoCard(\"🕘\", \"Recent chats\""))
    }

    @Test fun oneAgoHelperWithFriendlyOlderDates() {
        assertEquals(1, Regex("""fun ago\(""").findAll(ui61("HomeScreens.kt") + ui61("V45Screens.kt")).count())
        val now = 1_800_000_000_000L
        assertEquals("just now", ago(now - 10_000, now))
        assertEquals("1 day ago", ago(now - 26L * 3_600_000, now))
        assertEquals("3 days ago", ago(now - 3L * 86_400_000, now))
        assertFalse(ago(now - 30L * 86_400_000, now).endsWith("d ago"))                                          // a date, not "30 d ago"
    }

    @Test fun accessibilityAndMotion() {
        val md = ui61("MarkdownText.kt")
        assertTrue(md.contains("if (copied) \"Copied ✓\" else \"Copy\"") && md.contains("Modifier.heightIn(min = 48.dp).clickable(onClickLabel = \"Copy code\")"))
        assertFalse(ui61("HomeScreens.kt").contains("size(46.dp)"))                                              // composer targets are 48dp
        assertTrue(ui61("ProviderScreens.kt").contains("contentDescription = \"Move \${p.title} up\""))
        assertTrue(ui61("V34Screens.kt").contains("contentDescription = \"One hour earlier\""))
        assertTrue(ui61("MainActivity.kt").contains("tween(if (reducedMotion()) 0 else SplashTiming.FADE_OUT)"))
    }

    @Test fun newMessagesAppearOnceAndRespectReducedMotion() {
        val aurora = ui61("Aurora.kt"); val home = ui61("HomeScreens.kt")
        assertTrue(aurora.contains("fun Modifier.messageAppear(animate: Boolean)") && aurora.contains("Animatable(if (animate && !reduced) 0f else 1f)"))
        assertTrue(aurora.contains("Motion.tween(Motion.BASE, reduced)"))                                           // the shared motion spec
        assertTrue(home.contains("remember { i >= firstCount && appeared.add(i) }"))                              // never replayed on scroll
        assertFalse(ui61("V45Screens.kt").contains("0xFFFF7A6B"))                                                   // retired coral accent
    }

    @Test fun unusedDependenciesStayRemoved() {
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("implementation(libs.androidx.lifecycle.viewmodel.compose)"))
        assertFalse(gradle.contains("implementation(libs.compose.ui.tooling.preview)"))
    }

    @Test fun deadComposablesAreGone() {
        assertFalse(ui61("ManageScreens.kt").contains("fun MemoriesScreen("))                                     // PersonalMemoryScreen replaced it
        assertFalse(ui61("Components.kt").contains("fun AiModeChip(") || ui61("Components.kt").contains("fun Spacer8("))
    }
}
