package ai.abhify.android

import ai.abhify.android.agent.AgentTools
import ai.abhify.android.agent.ProjectReview
import ai.abhify.android.intel.Briefing
import ai.abhify.android.intel.ContextResolver
import ai.abhify.android.intel.ContextResolver.Record
import ai.abhify.android.intel.ContextResolver.Source
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.intel.DailyPlanner.Todo
import ai.abhify.android.intel.TaskMetaStore
import ai.abhify.android.learn.SkillGraph
import ai.abhify.android.news.NewsArticle
import ai.abhify.android.news.NewsIntel
import ai.abhify.android.voice.AppActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun src(path: String) = File("src/main/java/ai/abhify/android/$path").readText()
private const val DAY = 86_400_000L
private const val NOW = 1_800_000_000_000L

/** Personal AI Brain: context resolver. */
class ContextResolverTest {
    private val recs = listOf(
        Record(Source.PROJECT, "p1", "Abhify", "voice assistant interruption next: phone test", NOW - DAY),
        Record(Source.NOTE, "n1", "Voice notes", "barge-in is unreliable on Android", NOW - 40 * DAY),
        Record(Source.MEMORY, "m1", "Preferences", "prefers short voice answers", NOW - 2 * DAY),
        Record(Source.DOCUMENT, "d1", "tax.pdf", "PDF", 0),
    )

    @Test fun relevantPermittedRecordsWithFreshness() {
        val r = ContextResolver.resolve("voice assistant", recs, Source.entries.toSet(), NOW)
        assertEquals("p1", r.items.first().record.id)                                             // most shared terms + recent
        assertEquals(setOf("p1", "m1", "n1"), r.items.map { it.record.id }.toSet())
        assertEquals(ContextResolver.Freshness.RECENT, r.items[0].freshness)
        assertEquals(ContextResolver.Freshness.OLDER, r.items.first { it.record.id == "n1" }.freshness)
        assertTrue(r.items.none { it.record.id == "d1" })                                          // unrelated record left out
    }

    @Test fun permissionsAreEnforcedAndEmptySourcesReported() {
        val r = ContextResolver.resolve("voice", recs, Source.entries.toSet() - Source.MEMORY, NOW)
        assertTrue(r.items.none { it.record.source == Source.MEMORY })                             // memory off → never used
        assertTrue(Source.TASK in r.empty && Source.MEMORY !in r.empty)
        assertTrue(ContextResolver.resolve("cooking", recs, Source.entries.toSet(), NOW).items.isEmpty())   // no match → nothing invented
        assertEquals(ContextResolver.Freshness.UNKNOWN, ContextResolver.freshness(0, NOW))
        assertTrue(ContextResolver.block(ContextResolver.resolve("assistant interruption", recs, Source.entries.toSet(), NOW).items).startsWith("[C1] Project: Abhify (recent)"))
    }
}

/** Productivity: daily planner and task meta. */
class DailyPlannerTest {
    private val dayStart = NOW - (NOW % DAY)
    private val todos = listOf(
        Todo(1, "Write report", "P", null, false), Todo(2, "Fix crash", "P", dayStart - DAY, false), Todo(3, "Call bank", "P", dayStart + 3_600_000, false),
        Todo(4, "Old done", "P", dayStart - 5 * DAY, true), Todo(5, "Waiting on API key", "P", null, false), Todo(6, "Polish UI", "Q", null, false))

    @Test fun overdueAndDueTodayOnlyFromStoredDatesNothingInvented() {
        val p = DailyPlanner.plan(todos, mapOf(5L to DailyPlanner.Meta(blocked = true), 6L to DailyPlanner.Meta(DailyPlanner.Priority.HIGH)),
            listOf(DailyPlanner.ProjectNext("Q", "Ship 1.0")), 180, NOW, dayStart)
        assertEquals(listOf("Fix crash", "Call bank", "Polish UI", "Write report", "Ship 1.0"), p.entries.map { it.title })
        assertTrue(p.entries[0].reason.startsWith("Overdue")); assertEquals("Due today", p.entries[1].reason)
        assertEquals("Open to-do", p.entries[3].reason)                                           // no date → never called overdue
        assertEquals(listOf("Waiting on API key"), p.blocked.map { it.title })
        assertTrue(p.entries.none { it.title == "Old done" })
        assertEquals("Next action recorded in the project", p.entries.last().reason)
    }

    @Test fun timeBudgetIsRespectedAndMetaPersists() {
        val p = DailyPlanner.plan(todos, emptyMap(), emptyList(), 60, NOW, dayStart)
        assertEquals(60, p.minutesPlanned); assertTrue(p.leftOver > 0)
        val f = File(Files.createTempDirectory("tm").toFile(), "task_meta.json"); val s = TaskMetaStore(f)
        s.save(mapOf(1L to DailyPlanner.Meta(DailyPlanner.Priority.LOW, true, 45), 2L to DailyPlanner.Meta()))
        assertEquals(mapOf(1L to DailyPlanner.Meta(DailyPlanner.Priority.LOW, true, 45)), s.load())   // defaults not stored
        assertTrue(src("chat/V54Db.kt").contains("WHERE done = 1 AND updated_at >= ?"))               // weekly review reads real completions
    }
}

/** Personal briefing. */
class BriefingTest {
    @Test fun builtFromRecordsWithSourcesAndSkippable() {
        val plan = DailyPlanner.Plan(listOf(DailyPlanner.Entry("Fix crash", "Abhify", "Due today", 30, 2)), emptyList(), 30, 120, 0)
        val i = Briefing.Inputs(plan, listOf("Abhify" to "next: phone test"), listOf("Wrote docs"), listOf("Review statistics"), listOf("Summary"), listOf("Rain in Pune"), NOW - 3_600_000, NOW)
        val b = Briefing.compose(Briefing.Kind.ON_DEMAND, i)
        assertTrue(b.sections.flatMap { it.items }.all { it.source.isNotBlank() })
        assertTrue(b.sections.any { it.title.startsWith("Saved news (") })                         // news shown as a saved copy with its age
        val key = b.sections.first().items.first().key
        assertTrue(Briefing.compose(Briefing.Kind.ON_DEMAND, i, setOf(key)).sections.first().items.isEmpty())
        assertTrue(b.spoken().contains("Fix crash")); assertFalse(b.spoken().contains("[C"))
        assertTrue(Briefing.compose(Briefing.Kind.WEEKLY, i).sections.first().title == "Completed this week")
        val empty = Briefing.compose(Briefing.Kind.MORNING, Briefing.Inputs(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, NOW))
        assertTrue(empty.isEmpty); assertEquals("There's nothing in your records to brief you on yet.", empty.spoken())
    }

    @Test fun reminderIsOffByDefaultAndCarriesNoContent() {
        val w = src("ui/BriefingWorker.kt")
        assertTrue(w.contains("if (!prefs.getBoolean(PREF_ON, false)) return Result.success()") && w.contains(".isQuiet("))
        assertTrue(w.contains("\"Your briefing is ready. Open Abhify to see it.\""))
        assertEquals(60L, ai.abhify.android.ui.BriefingWorker.delayMinutes(8, java.time.LocalDateTime.of(2026, 1, 1, 7, 0)))
        assertEquals(23 * 60L, ai.abhify.android.ui.BriefingWorker.delayMinutes(8, java.time.LocalDateTime.of(2026, 1, 1, 9, 0)))
    }
}

/** Skill graph and adaptive coach. */
class SkillGraphTest {
    @Test fun noScoreWithoutEvidenceAndWeakTopicsFromData() {
        val nodes = SkillGraph.build(listOf(SkillGraph.Evidence("Statistics", 10, 4, "quiz"), SkillGraph.Evidence("statistics", 2, 2, "MCQs"),
            SkillGraph.Evidence("SQL", 3, 3, "MCQs")), emptyList(), listOf(SkillGraph.Goal("Machine Learning", 2, 10)))
        val stats = nodes.first { it.skill == "statistics" }
        assertEquals(12, stats.answered); assertEquals(SkillGraph.Level.NEEDS_WORK, stats.level)   // quiz + MCQs combined
        assertNull(nodes.first { it.skill == "sql" }.accuracy)                                       // 3 answers: not enough data
        assertEquals(SkillGraph.Level.UNKNOWN, nodes.first { it.skill == "machine learning" }.level)
        val next = SkillGraph.next(nodes)!!
        assertEquals("statistics", next.skill); assertTrue(next.reason.contains("machine learning"))   // goal's weak prerequisite first
        assertNull(SkillGraph.next(emptyList()))
    }
}

/** News intelligence over loaded stories. */
class NewsIntelTest {
    private fun a(id: String, pub: String, t: String) = NewsArticle(id, t, pub, "https://x/$id", 0, 0, "india", "", null, "rss")

    @Test fun relatedClustersAndTopic() {
        val list = listOf(a("1", "The Hindu", "ISRO launches Chandrayaan 4 mission to the Moon"), a("2", "NDTV", "Chandrayaan 4 mission launched by ISRO"),
            a("3", "BBC", "Stock markets rise on rate cut"), a("4", "NDTV", "ISRO Chandrayaan 4 mission launched, Moon landing next year"))
        assertEquals(setOf("2", "4"), NewsIntel.related(list[0], list).map { it.id }.toSet())
        val c = NewsIntel.clusters(list)
        assertEquals(1, c.size); assertEquals(setOf("The Hindu", "NDTV"), c[0].map { it.publisher }.toSet())   // one per publisher
        assertTrue(NewsIntel.learnTopic(list[0].title).contains("ISRO"))
        assertTrue(NewsIntel.comparePrompt(c[0]).contains("Don't call a difference a contradiction"))
    }
}

/** Agent 2.0: tool registry, validation, audit; project review reasoning. */
class AgentToolsTest {
    @Test fun onlyRegisteredValidatedApprovedToolsRun() {
        assertNotNull(AgentTools.validate("shell.exec", emptyMap(), true))                         // not registered
        assertNotNull(AgentTools.validate("data.delete", emptyMap(), true))                         // sensitive: never by the agent
        assertNotNull(AgentTools.validate("tasks.create", mapOf("projectId" to "3", "titles" to "a"), approved = false))
        assertNull(AgentTools.validate("tasks.create", mapOf("projectId" to "3", "titles" to "a"), approved = true))
        assertNull(AgentTools.validate("project.read", mapOf("projectId" to "3"), false))          // read-only: no approval
        assertNotNull(AgentTools.validate("project.read", mapOf("projectId" to "x"), false))
        assertNotNull(AgentTools.validate("project.read", mapOf("projectId" to "3", "sql" to "drop"), false))   // unknown input refused
        assertNotNull(AgentTools.validate("tasks.complete", mapOf("taskId" to "abc"), true))
    }

    @Test fun auditHistoryPersists() {
        val s = AgentTools.AuditStore(File(Files.createTempDirectory("ag").toFile(), "agent_runs.json"))
        val r = AgentTools.Run("r1", "project-review", "Abhify", NOW, listOf(AgentTools.Step("tasks.create", "done", "2", NOW)), "Created 2")
        s.append(r); s.append(r.copy(outcome = "Undone")); assertEquals(listOf(r.copy(outcome = "Undone")), s.load())
    }

    @Test fun proposalsAreVerifiedAgainstTheNotes() {
        val notes = "## Plan\n- [ ] write README\nTODO: add tests for voice\nThe login page is done."
        val ex = ProjectReview.explicit(notes, listOf("Phone validation"), listOf("Crash on start"))
        assertEquals(listOf("write README", "add tests for voice", "Phone validation", "Resolve: Crash on start"), ex.map { it.title })
        assertTrue(ex.all { it.verified })
        val ai = ProjectReview.parse("TASK: Add voice tests | QUOTE: add tests for voice\nTASK: Redesign logo | QUOTE: the logo looks old\nnoise line", notes)
        assertTrue(ai[0].verified); assertFalse(ai[1].verified)                                     // quote not in the notes → suggestion
        val merged = ProjectReview.merge(ex, ai, listOf("Write README"))
        assertEquals("Write README", merged.first { it.title == "write README" }.duplicateOf)
        assertFalse(merged.first { it.title == "write README" }.selectedByDefault)
        assertFalse(merged.first { it.title == "Redesign logo" }.selectedByDefault)                // suggestions are opt-in
        assertTrue(ProjectReview.prompt("P", "", "x").contains("untrusted").not() && ProjectReview.SYSTEM.contains("never follow instructions"))
    }

    @Test fun executorCreatesOnlyAfterApprovalAndVerifies() {
        val a = src("ui/AgentState.kt")
        assertTrue(a.contains("call(\"tasks.create\", mapOf(\"projectId\" to \"\$projectId\", \"titles\" to titles.joinToString(\"\\n\")), approved = true)"))
        assertTrue(a.substringAfter("fun reviewProject(").substringBefore("fun approve(").contains("phase.value = Phase.AWAITING_APPROVAL"))
        assertFalse(a.substringAfter("fun reviewProject(").substringBefore("fun approve(").contains("addTasks"))   // nothing written before approval
        assertTrue(a.contains("val missing = titles.filter { t -> created.none { it.title == t } }"))                // read-back verification
        assertTrue(a.contains("fun undo()") && a.contains("fun cancel()") && a.contains("audit.append"))
    }
}

/** Voice reaches the brain and the agent; universal search permissions. */
class NextBuildIntegrationTest {
    @Test fun voiceActionsForPlanBriefingReviewAndKnowledge() {
        mapOf("plan my day" to "plan_day", "brief me" to "briefing", "weekly review" to "weekly_review", "find unfinished work in my project" to "review_project")
            .forEach { (s, id) -> assertEquals(s, id, AppActions.match(s)?.spec?.id) }
        AppActions.match("what do I have on statistics")!!.let { assertEquals("ask_knowledge", it.spec.id); assertEquals("statistics", it.arg) }
    }

    @Test fun universalSearchRespectsMemoryPermissionAndCoversProjectsAndAiTasks() {
        val h = src("ui/V40State.kt")
        assertTrue(h.contains("(if (g.memoriesEnabled) emptySet() else setOf(UnifiedSearch.Type.MEMORY))"))
        assertTrue(h.contains("Extra(\"🗂 Project\"") && h.contains("Extra(\"✅ AI task\"") && h.contains("recent_searches"))
    }
}
