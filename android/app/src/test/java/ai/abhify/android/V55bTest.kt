package ai.abhify.android

import ai.abhify.android.agent.AgentTools
import ai.abhify.android.agent.ProjectInsights
import ai.abhify.android.agent.ProjectInsights.Todo
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.learn.SkillGraph
import ai.abhify.android.voice.AppActions
import ai.abhify.android.workspace.ProjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()
private const val DAY = 86_400_000L
private const val NOW = 1_800_000_000_000L

/** 5.5 agent workflows: status & blockers, priorities, related documents. */
class ProjectInsightsTest {
    private val ds = NOW - NOW % DAY
    private val todos = listOf(Todo(1, "Write README", ds - DAY, DailyPlanner.Meta()), Todo(2, "write README.", null, DailyPlanner.Meta()),
        Todo(3, "Waiting for API key from vendor", null, DailyPlanner.Meta()), Todo(4, "Deploy", ds + 3_600_000, DailyPlanner.Meta(DailyPlanner.Priority.HIGH)),
        Todo(5, "Fix login", null, DailyPlanner.Meta(blocked = true)))
    private val st = ProjectStatus("Ship v1", "", "RAG done", listOf("Use SQLite"), listOf("Docs v0"), listOf("Crash on start"), listOf("Phone test"), NOW - 2 * DAY)

    @Test fun summaryUsesOnlyRecordsAndNamesEverySource() {
        val s = ProjectInsights.summary("P", st, todos, "Notes\n- blocked on design review\n- all good", listOf("spec.pdf"), NOW, ds)
        val all = s.sections.flatMap { it.second }
        assertTrue(all.all { it.source.isNotBlank() })
        assertTrue(all.any { it.text == "Crash on start" && it.source == "open issue" })
        assertTrue(all.any { it.text == "Fix login" && it.source == "to-do marked blocked" })
        assertTrue(all.any { it.text == "blocked on design review" && it.source == "your notes" })
        assertTrue(all.any { it.text.startsWith("5 open to-do(s), 1 past their due date") })
        assertTrue(s.sections.first { it.first == "Possible duplicates" }.second.single().text.contains("Write README"))
        assertTrue(ProjectInsights.summary("P", ProjectStatus(), emptyList(), "", emptyList(), NOW, ds).isEmpty)   // nothing recorded → nothing said
    }

    @Test fun prioritiesComeFromStoredDatesAndWordingOnly() {
        val p = ProjectInsights.prioritize(todos, NOW, ds).associateBy { it.taskId }
        assertEquals(DailyPlanner.Priority.HIGH, p[1L]!!.priority); assertTrue(p[1L]!!.reason.contains("stored due date"))
        assertEquals(true, p[3L]!!.blocked)                                                           // "waiting for" in its title
        assertNull(p[2L]); assertNull(p[4L])                                                           // no date → no suggestion; already High
        assertNull(p[5L])                                                                               // already blocked
    }

    @Test fun relatedDocumentsNeedTwoSharedTermsAndSkipLinked() {
        val docs = listOf(Triple(1L, "voice assistant spec.pdf", "interruption design for the voice assistant"), Triple(2L, "tax.pdf", "income tax"),
            Triple(3L, "voice notes.txt", "assistant ideas"))
        val m = ProjectInsights.relatedDocs("Abhify voice assistant interruption", docs, linked = setOf(3L))
        assertEquals(listOf(1L), m.map { it.docId }); assertTrue(m[0].shared.containsAll(listOf("assistant", "voice")))
    }

    @Test fun newToolsAreRegisteredWithTheRightPermission() {
        assertNull(AgentTools.validate("project.summary", mapOf("projectId" to "1"), false))
        assertNotNull(AgentTools.validate("doc.link", mapOf("projectId" to "1", "docId" to "2"), approved = false))   // reversible → approval
        assertNull(AgentTools.validate("doc.link", mapOf("projectId" to "1", "docId" to "2"), approved = true))
        val a = src("ui/AgentState.kt")
        assertTrue(a.contains("call(\"doc.link\"") && a.contains("call(\"task.meta\"") && a.contains("// read back"))
        assertTrue(a.substringAfter("fun run(id: Long, wf: Workflow)").substringBefore("fun approveItems").let { !it.contains("linkToWorkspace") && !it.contains("setMeta") })  // nothing applied before approval
    }
}

/** 5.5 planner, skills, chat context, news, search, voice. */
class FiftyFiveIntegrationTest {
    @Test fun recentProjectWorkBreaksTiesInTheDailyPlan() {
        val ds = NOW - NOW % DAY
        val p = DailyPlanner.plan(listOf(DailyPlanner.Todo(1, "Old project task", "A", null, false, projectActiveAt = NOW - 30 * DAY),
            DailyPlanner.Todo(2, "Active project task", "B", null, false, projectActiveAt = NOW - DAY)), emptyMap(), emptyList(), 120, NOW, ds)
        assertEquals(listOf("Active project task", "Old project task"), p.entries.map { it.title })
    }

    @Test fun skillThresholdIsConfigurableAndTrendNeedsTenAnswers() {
        val ev = listOf(SkillGraph.Evidence("SQL", 4, 4, "MCQs"))
        assertEquals(SkillGraph.Level.UNKNOWN, SkillGraph.build(ev, emptyList(), emptyList()).single().level)
        assertEquals(SkillGraph.Level.STRONG, SkillGraph.build(ev, emptyList(), emptyList(), minAnswers = 3).single().level)
        assertNull(SkillGraph.trend(List(9) { true }))
        assertEquals("improving", SkillGraph.trend(List(5) { false } + List(5) { true }))
        assertEquals("declining", SkillGraph.trend(List(5) { true } + List(5) { false }))
        assertTrue(src("ui/BrainState.kt").contains("AgentTools.validate(\"tasks.create\"") && src("ui/BrainScreens.kt").contains("Add a revision to-do?"))
    }

    @Test fun chatUsesTheContextFinderLocallyAndShowsWhy() {
        val vm = src("ui/AppViewModel.kt")
        assertTrue(vm.contains("val personal = if (!brainPlan.contextFinder) emptyList()"))      // only for own-material questions (6.0: Brain.plan decides; tested in BrainTest)
        assertTrue(vm.contains("it.record.source in CHAT_CONTEXT_SOURCES"))
        assertFalse(vm.substringAfter("private val CHAT_CONTEXT_SOURCES").substringBefore(")\n").contains("MEMORY"))   // memories keep their own consent path
        assertTrue(vm.contains("(if (e.source.cloud) emptyList() else personal.map { \"🧭 "))                // listed under local answers
        assertTrue(vm.contains("val wsOnline = ai.abhify.android.workspace.ContextOrchestrator.build(wsParts, 4000)"))   // never online
    }

    @Test fun newsShowsCoverageAndSearchGroupsAndVoiceSummarises() {
        assertTrue(src("ui/NewsScreens.kt").contains("Also reported by \$others other source"))
        assertTrue(src("ui/V40Screens.kt").contains("else h.results.sortedBy { it.type.ordinal }"))
        assertEquals("project_summary", AppActions.match("what's blocking my project")?.spec?.id)
        assertEquals("project_summary", AppActions.match("summarise my project")?.spec?.id)
    }
}
