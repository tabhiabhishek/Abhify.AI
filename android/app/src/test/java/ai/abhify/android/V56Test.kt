package ai.abhify.android

import ai.abhify.android.agent.ProjectInsights
import ai.abhify.android.agent.ProjectInsights.Todo
import ai.abhify.android.ai.RouteStats
import ai.abhify.android.ai.Source
import ai.abhify.android.intel.ContextResolver
import ai.abhify.android.intel.ContextResolver.Record
import ai.abhify.android.intel.ContextResolver.Source as S
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.intel.RetrievalEval
import ai.abhify.android.learn.SkillGraph
import ai.abhify.android.ui.TaskReminders
import ai.abhify.android.workspace.UnifiedSearch
import ai.abhify.android.workspace.UnifiedSearch.Hit
import ai.abhify.android.workspace.UnifiedSearch.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

private fun code(path: String) = File("src/main/java/ai/abhify/android/$path").readText()
private const val D = 86_400_000L
private const val T = 1_800_000_000_000L

/** 5.6 Search 2.0: date, project and sort filters over the existing local search. */
class SearchFiltersTest {
    private val hits = listOf(Hit(Type.NOTE, 1, "Kotlin notes", "", T - D, 7), Hit(Type.NOTE, 2, "Kotlin other", "", T - 40 * D, 8),
        Hit(Type.CHAT, 3, "kotlin chat", "", T - 2 * D, null), Hit(Type.DOCUMENT, 4, "spec", "kotlin", T - 3 * D, null),
        Hit(Type.MEMORY, 5, "General", "likes kotlin", T, null), Hit(Type.TASK, 6, "Learn kotlin", "open", T - 10 * D, 7))

    @Test fun dateWindowProjectAndSort() {
        assertEquals(setOf(1L, 3L, 4L, 5L), UnifiedSearch.filter(hits, "kotlin", UnifiedSearch.Filters(days = 7), T).map { it.id }.toSet())
        val proj = UnifiedSearch.filter(hits, "kotlin", UnifiedSearch.Filters(project = 7), T, projectChats = setOf(3), projectDocs = emptySet())
        assertEquals(setOf(1L, 3L, 6L), proj.map { it.id }.toSet())                                   // own notes/to-dos + linked chat; no memory
        assertEquals(listOf(5L, 1L, 3L, 4L, 6L, 2L), UnifiedSearch.filter(hits, "kotlin", UnifiedSearch.Filters(sort = UnifiedSearch.Sort.RECENT), T).map { it.id })
        assertFalse(UnifiedSearch.Filters().active)
    }

    @Test fun memoryPermissionIsStillEnforcedBeforeFiltersAndNewSourcesStayLocal() {
        val st = code("ui/V40State.kt")
        assertTrue(st.contains("(if (g.memoriesEnabled) emptySet() else setOf(UnifiedSearch.Type.MEMORY))"))
        assertTrue(st.contains("g.db.unifiedSearch(q, allowed, perType"))                             // filters apply to the permitted set only
        assertTrue(st.contains("vm.newsCenter.result.value?.articles.orEmpty()") && !st.contains("g.newsCenter.load("))   // loaded stories only, no fetch
        val ui = code("ui/V40Screens.kt")
        assertTrue(ui.contains("\"Last 7 days\"") && ui.contains("\"All projects\"") && ui.contains("Nothing matches with these filters"))
    }
}

/** 5.6 smarter duplicates: normalisation, containment, completed items; suggestions only. */
class SmartDuplicatesTest {
    private fun t(id: Long, title: String, done: Boolean = false) = Todo(id, title, null, DailyPlanner.Meta(), done)

    @Test fun normalisedWordingAndContainmentMatch() {
        assertEquals(ProjectInsights.norm("Writing the tests"), ProjectInsights.norm("write tests"))
        val d = ProjectInsights.duplicates(listOf(t(1, "Fix login bug"), t(2, "fix the login bug on Android"), t(3, "Buy milk")))
        assertEquals(1, d.size); assertEquals("one includes the other", d[0].why)
        assertTrue(ProjectInsights.duplicates(listOf(t(1, "Deploy server"), t(2, "Deploy website"))).isEmpty())   // 1 of 3 words
    }

    @Test fun completedItemsAreFlaggedNotDuplicatedAndNeverChanged() {
        val d = ProjectInsights.duplicates(listOf(t(1, "Update README", done = true), t(2, "update the readme")))
        assertTrue(d.single().alreadyDone); assertEquals(1L, d.single().a.id)
        assertTrue(ProjectInsights.duplicates(listOf(t(1, "Update README", true), t(2, "update readme", true))).isEmpty())
        val s = ProjectInsights.summary("P", ai.abhify.android.workspace.ProjectStatus(), listOf(t(1, "Update README", true), t(2, "update readme")), "", emptyList(), T, T)
        assertTrue(s.sections.first { it.first == "Work" }.second.single().text.startsWith("1 open to-do"))   // completed not counted as open
        assertTrue(s.sections.first { it.first == "Possible duplicates" }.second.single().text.contains("may already be done"))
        assertTrue(ProjectInsights.prioritize(listOf(t(1, "waiting for keys", true)), T, T).isEmpty())
        assertFalse(code("agent/ProjectInsights.kt").contains("db."))                                  // pure: never writes
    }
}

/** 5.6 per-to-do reminders: opt-in, quiet hours, completed to-dos, no new permission. */
class TaskRemindersTest {
    @Test fun quietHoursMoveTheReminderToTheirEnd() {
        val night = LocalDateTime.of(2026, 11, 18, 23, 30)
        assertEquals(LocalDateTime.of(2026, 11, 19, 7, 0), TaskReminders.adjustForQuiet(night, 22, 7))
        assertEquals(LocalDateTime.of(2026, 11, 19, 7, 0), TaskReminders.adjustForQuiet(LocalDateTime.of(2026, 11, 19, 3, 0), 22, 7))
        val day = LocalDateTime.of(2026, 11, 18, 15, 0); assertEquals(day, TaskReminders.adjustForQuiet(day, 22, 7))
        assertEquals(night, TaskReminders.adjustForQuiet(night, 0, 0))                                   // quiet hours off
        assertEquals(listOf("In 1 hour", "Tomorrow 09:00"), TaskReminders.presets(LocalDateTime.of(2026, 11, 18, 19, 0)).map { it.first })
    }

    @Test fun completedOrDeletedNeverNotifyAndLockScreenHidesTitle() {
        val c = code("ui/TaskReminders.kt")
        assertTrue(c.contains("if (row == null || row.second) { TaskReminders.forget(c, id); return Result.success() }"))
        assertTrue(c.contains("setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)"))
        assertTrue(c.contains("if (s.isQuiet(LocalDateTime.now().hour))"))
        assertTrue(code("ui/MainActivity.kt").contains("TaskReminders.snooze(this, snooze)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM") || manifest.contains("USE_EXACT_ALARM"))   // no new permission
        assertEquals(6, Regex("<uses-permission ").findAll(manifest).count())                   // 7.2.1: REQUEST_INSTALL_PACKAGES removed (libraries add the other 3)
    }
}

/** 5.6 context finder: relevance floor, own-material questions in plain chat, evaluation. */
class ContextFinderCompletionTest {
    private val recs = listOf(
        Record(S.PROJECT, "p1", "Abhify voice", "interruption design for the voice assistant", T - D),
        Record(S.NOTE, "n1", "Groceries", "milk eggs bread", T - D),
        Record(S.TASK, "t1", "Voice: barge-in test", "open", T - 2 * D),
        Record(S.NEWS, "w1", "Assistant market news", "voice assistants grow", T - 40 * D),
        Record(S.MEMORY, "m1", "likes voice", "", T))

    @Test fun relevanceFloorAndOwnMaterial() {
        val loose = ContextResolver.resolve("voice interruption design", recs, S.entries.toSet() - S.MEMORY, T, minShared = 2).items.map { it.record.id }
        assertEquals(listOf("p1"), loose)                                                              // one-word matches are not padding
        assertEquals(2, ContextResolver.chatMinShared("voice interruption design")); assertEquals(1, ContextResolver.chatMinShared("voice"))
        assertTrue(ContextResolver.OWN_MATERIAL.containsMatchIn("what are my notes on kotlin"))
        assertTrue(ContextResolver.OWN_MATERIAL.containsMatchIn("What did I decide about the API?"))
        assertFalse(ContextResolver.OWN_MATERIAL.containsMatchIn("explain kotlin coroutines"))
        val vm = code("ui/AppViewModel.kt")
        assertTrue(vm.contains("val personal = if (!brainPlan.contextFinder) emptyList()"))                       // 6.0: decided by the Brain plan (own-material rules in Brain.plan)
        assertTrue(vm.contains("val wsOnline = ai.abhify.android.workspace.ContextOrchestrator.build(wsParts, 4000)"))   // still never online
    }

    @Test fun evaluationOnSyntheticCases() {
        val s = RetrievalEval.run(listOf(RetrievalEval.Case("voice interruption design", setOf("p1")), RetrievalEval.Case("barge-in test", setOf("t1")),
            RetrievalEval.Case("quantum chemistry", emptySet())), recs, S.entries.toSet() - S.MEMORY, k = 3, now = T)
        assertEquals(1.0, s.precisionAtK, 0.001); assertEquals(1.0, s.recall, 0.001); assertEquals(1.0, s.emptyWhenNothingRelevant, 0.001)
    }
}

/** 5.6 skill graph: revision plan and learning path from evidence, with answer counts. */
class SkillPathTest {
    @Test fun revisionPlanAndLearningPath() {
        val nodes = SkillGraph.build(listOf(SkillGraph.Evidence("python", 10, 4, "MCQs"), SkillGraph.Evidence("statistics", 10, 9, "MCQs", List(5) { true } + List(5) { false })),
            listOf(SkillGraph.Deck("sql", 6, 0)), listOf(SkillGraph.Goal("machine learning", 0, 5)))
        val plan = SkillGraph.revisionPlan(nodes)
        assertEquals(listOf("python", "statistics", "sql"), plan.map { it.skill })
        assertEquals("4 of 10 answers correct", plan[0].reason)
        val path = SkillGraph.learningPath("Machine Learning", nodes)
        assertEquals(listOf("python", "probability", "statistics", "linear algebra", "machine learning"), path.map { it.skill })
        assertEquals("Needs work (10 answers)", path[0].status); assertEquals("not measured yet", path[1].status)
        assertTrue(code("ui/BrainScreens.kt").contains("SkillGraph.revisionPlan(b.skills)"))
    }
}

/** 5.6 model routing quality: counts and timings only. */
class RouteStatsTest {
    @Test fun statsHoldNoContentAndAreRecordedPerAttempt() {
        RouteStats.clear()
        RouteStats.record(Source.Kind.GROQ, false, null, 900, 0, false)
        RouteStats.record(Source.Kind.DEVICE, true, 1400, 6000, 120, true)
        val s = RouteStats.snapshot()
        assertEquals(100, s[Source.Kind.GROQ]!!.failureRate); assertEquals(1400L, s[Source.Kind.DEVICE]!!.avgFirstTokenMs); assertEquals(1, s[Source.Kind.DEVICE]!!.fallbacks)
        assertEquals(listOf("On this phone: 1 answered, 0 failed, first words after 1.4 s on average, 1 as fallback", "Groq: 0 answered, 1 failed (100 %)"), RouteStats.lines())
        assertTrue(RouteStats.Entry::class.java.declaredFields.none { it.type == String::class.java })   // no text stored
        assertTrue(code("ai/HybridAi.kt").contains("RouteStats.record(it.kind, failure == null, firstAt"))
        RouteStats.clear()
    }
}
