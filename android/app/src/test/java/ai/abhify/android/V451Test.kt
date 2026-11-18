package ai.abhify.android

import ai.abhify.android.ui.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4.5.1 page redesigns: each main page has its own layout built from the page kit, and nothing was dropped. */
class PageRedesignTest {
    private val ui = File("src/main/java/ai/abhify/android/ui").listFiles()!!.associate { it.name to it.readText() }
    private fun fn(file: String, name: String) = ui.getValue(file).substringAfter("fun $name(").substringBefore("\n}\n")

    @Test fun sectionTabsAreSegmentedWithIcons() {
        assertTrue(fn("HomeScreens.kt", "LearnTab").contains("SegmentedTabs(LEARN_ORDER.map { LEARN_SECTIONS[it] }"))   // 5.3: same sections, shown in display order
        assertTrue(fn("CareerScreens.kt", "CareerTab").contains("SegmentedTabs("))
        assertTrue(fn("V3Screens.kt", "WorkTab").contains("SegmentedTabs("))
        assertTrue(ui.getValue("LifeScreens.kt").contains("SegmentedTabs(names, selected"))                        // Knowledge sections
    }

    @Test fun documentsKeepsEveryActionInTheSheet() {
        val d = fn("V3Screens.kt", "DocumentsTab")
        assertTrue(d.contains("PageHeader(\"Documents\"") && d.contains("SearchField(") && d.contains("ViewToggle(") && d.contains("ActionSheet("))
        // every action the 4.4 inline buttons offered is still reachable
        for (call in listOf("s.askInChat(d)", "vm.chatDocs += d.id", "vm.hub.studyPlanFrom(d.id)", "s.docAction(d, a)", "s.view(d)", "vm.life.saveDocument(d)",
            "vm.drive.uploadDocText(d)", "s.reindex(d)", "compareFirst = d", "renaming = d to d.name", "deleting = d", "vm.go(AppViewModel.Route.RESUME)"))
            assertTrue(call, d.contains(call))
        assertFalse(d.contains("FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {\n                        StudioState.DocAction"))
    }

    @Test fun studyCareerAndQuizHaveDashboards() {
        val study = ui.getValue("V39Screens.kt")
        assertTrue(study.contains("PageHeader(\"Study\"") && study.contains("StatTile(") && study.contains("BentoCard(\"✨\", \"Recommended next\""))
        assertTrue(fn("LifeScreens.kt", "StudyView").contains("ActionTile("))
        val career = ui.getValue("CareerScreens.kt")
        assertTrue(career.contains("PageHeader(\"Career\"") && career.contains("StatTile(\"\$v\", k") && career.contains("ActionTile(\"🔀\", \"Match a job\""))
        val quiz = fn("HomeScreens.kt", "QuizView")
        assertTrue(quiz.contains("LinearProgressIndicator(") && quiz.contains("StatTile(") && quiz.contains("vm.answerQuiz(i)") && quiz.contains("vm::nextQuestion"))
    }

    @Test fun settingsPagesUseGroupsAndSetupHasAStepper() {
        val groups = ui.values.sumOf { Regex("SettingsGroup\\(\"").findAll(it).count() }
        assertTrue("groups: $groups", groups >= 60)
        // stability milestone: the personalization page became Settings (AI, models, system and diagnostics moved in from More)
        assertTrue(ui.getValue("ManageScreens.kt").contains("Page(\"Settings\", vm)"))
        val setup = ui.getValue("SetupScreens.kt")
        assertTrue(setup.contains("contentDescription = \"Step \${i + 1} of \${steps.size}") && setup.contains("widthIn(max = Dimens.maxWidth)"))
    }

    @Test fun pageKitGlyphsAllHaveIcons() {
        val used = ui.values.flatMap { t -> Regex("(?:SheetAction|ActionTile|BentoCard|LinkRow|TypeTile)\\(\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(used.size >= 15)
        for (g in used) assertTrue("no icon for $g", AppIcons.of(g) != null)
        assertEquals(null, AppIcons.of("🔊"))                                                        // never mapped to a wrong icon
    }
}
