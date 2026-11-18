package ai.abhify.android.ui

import ai.abhify.android.chat.addTasks
import ai.abhify.android.chat.addWorkspace
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.completedTasksSince
import ai.abhify.android.chat.deckStats
import ai.abhify.android.chat.memoryItems
import ai.abhify.android.chat.notes
import ai.abhify.android.chat.planStats
import ai.abhify.android.chat.quizStats
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.vault
import ai.abhify.android.chat.workspaces
import ai.abhify.android.intel.Briefing
import ai.abhify.android.intel.ContextResolver
import ai.abhify.android.intel.ContextResolver.Source
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.intel.TaskMetaStore
import ai.abhify.android.learn.SkillGraph
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Next build — the Personal AI Brain's data side on Android: gathers records from the existing stores (no copies, no
 * schema change) for the context resolver, the daily planner, the briefing and the skill graph. Read-only except the
 * user's own priority/blocked choices (task_meta.json). Runs on demand (Home, Study, voice), never in the background.
 */
class BrainState(private val vm: AppViewModel) {
    private val g = vm.g
    private val metaStore = TaskMetaStore(File(g.context.filesDir, "task_meta.json"))
    val meta = mutableStateMapOf<Long, DailyPlanner.Meta>().apply { putAll(metaStore.load()) }
    val minutes = mutableIntStateOf(g.prefs.getInt("plan_minutes", 120))
    /** 5.5: answers needed before a skill level is shown (3 / 5 / 10; default 5). */
    val minAnswers = mutableIntStateOf(g.prefs.getInt("skill_min_answers", SkillGraph.MIN_ANSWERS))
    fun setMinAnswers(n: Int) { minAnswers.intValue = n.coerceIn(3, 20); g.prefs.edit().putInt("skill_min_answers", minAnswers.intValue).apply(); refresh() }
    /** 5.5: a revision to-do for a skill, in a project named "Study" (created if missing); called only after the user confirms. */
    fun addRevisionTodo(skill: String, onDone: (String) -> Unit) = vm.viewModelScope.launch {
        val msg = withContext(Dispatchers.IO) { runCatching {
            val ws = g.db.workspaces().firstOrNull { it.name.equals("Study", true) } ?: ai.abhify.android.chat.Workspace(g.db.addWorkspace("Study", "project"), "Study", "project")
            val title = "Revise ${skill.replaceFirstChar { it.uppercase() }}"
            ai.abhify.android.agent.AgentTools.validate("tasks.create", mapOf("projectId" to "${ws.id}", "titles" to title), approved = true)?.let { error(it) }
            g.db.addTasks(ws.id, listOf(title))
            if (g.db.tasks(ws.id).any { it.title == title && !it.done }) "Added \"$title\" to the Study project." else error("couldn't verify")
        }.getOrElse { "Couldn't add the revision to-do: ${it.message}" } }
        vm.studio.loadWork(); refresh(); onDone(msg)
    }
    val plan = mutableStateOf<DailyPlanner.Plan?>(null)
    val week = mutableStateOf<DailyPlanner.Week?>(null)
    val skills = mutableStateListOf<SkillGraph.Node>()
    val skillNext = mutableStateOf<SkillGraph.Next?>(null)
    val brief = mutableStateOf<Briefing.Brief?>(null)
    private fun skipped(): Set<String> = g.prefs.getStringSet("briefing_skipped", emptySet()).orEmpty()

    fun setMeta(id: Long, m: DailyPlanner.Meta) { meta[id] = m; metaStore.save(meta.toMap()); refresh() }
    fun setMinutes(m: Int) { minutes.intValue = m.coerceIn(15, 720); g.prefs.edit().putInt("plan_minutes", minutes.intValue).apply(); refresh() }

    private fun dayStart() = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private data class Snapshot(val plan: DailyPlanner.Plan, val week: DailyPlanner.Week, val projects: List<Pair<String, String>>,
                                val study: List<String>, val nodes: List<SkillGraph.Node>, val next: SkillGraph.Next?)

    private fun snapshot(): Snapshot {
        val now = System.currentTimeMillis(); val ds = dayStart()
        val ws = g.db.workspaces().filter { !vm.projects.isArchived(it.id) }
        val todos = ws.flatMap { w -> val active = vm.projects.meta(w.id).status.updatedAt
            g.db.tasks(w.id).map { DailyPlanner.Todo(it.id, it.title, w.name, it.dueAt, it.done, active) } }
        val nexts = ws.mapNotNull { w -> vm.projects.meta(w.id).status.next.firstOrNull()?.let { DailyPlanner.ProjectNext(w.name, it) } }
        val p = DailyPlanner.plan(todos, meta.toMap(), nexts, minutes.intValue, now, ds)
        val wk = DailyPlanner.week(g.db.completedTasksSince(now - 7 * 86_400_000L), todos, ds)
        val projects = ws.mapNotNull { w -> val st = vm.projects.meta(w.id).status
            (st.next.firstOrNull()?.let { "next: $it" } ?: st.current.ifBlank { null })?.let { w.name to it } }
        // skill graph: quiz + MCQ answers per topic, decks, plans (goals)
        val evidence = g.db.quizStats().map { SkillGraph.Evidence(it.topic, it.answered, it.correct, "quiz") } +
            vm.mcq.attempts().groupBy { it.topic }.map { (t, l) -> SkillGraph.Evidence(t, l.size, l.count { it.correct }, "MCQs", l.sortedBy { it.at }.takeLast(10).map { it.correct }) }
        val decks = g.db.deckStats(now).map { SkillGraph.Deck(it.deck, it.due, it.oftenMissed) }
        val goals = g.db.planStats().map { SkillGraph.Goal(it.topic, it.done, it.total) }
        val nodes = SkillGraph.build(evidence, decks, goals, minAnswers.intValue)
        val study = listOfNotNull(SkillGraph.next(nodes)?.let { "${it.action}: ${it.reason}" },
            decks.sumOf { it.due }.takeIf { it > 0 }?.let { "$it flashcards due" })
        return Snapshot(p, wk, projects, study, nodes, SkillGraph.next(nodes))
    }

    /** For voice and the agent: computed now from the stores (IO thread). */
    suspend fun planNow(): DailyPlanner.Plan? = withContext(Dispatchers.IO) { runCatching { snapshot().plan }.getOrNull() }
    suspend fun weekNow(): DailyPlanner.Week? = withContext(Dispatchers.IO) { runCatching { snapshot().week }.getOrNull() }
    suspend fun resolve(query: String, minShared: Int = 1): ContextResolver.Result = withContext(Dispatchers.IO) { ContextResolver.resolve(query, records(), permitted(), minShared = minShared) }

    fun refresh() = vm.viewModelScope.launch {
        val s = withContext(Dispatchers.IO) { runCatching { snapshot() }.getOrNull() } ?: return@launch
        plan.value = s.plan; week.value = s.week; skills.clear(); skills.addAll(s.nodes); skillNext.value = s.next
    }

    /** A briefing from the same records (+ the last saved news copy and failed AI tasks). */
    fun makeBriefing(kind: Briefing.Kind, then: (Briefing.Brief) -> Unit = {}) = vm.viewModelScope.launch {
        val b = withContext(Dispatchers.IO) { runCatching {
            val s = snapshot(); val now = System.currentTimeMillis()
            val n = runCatching { g.newsCenter.cached(ai.abhify.android.news.NewsQuery.cacheKey(vm.newsCenter.category.value, vm.newsCenter.prefs.value)) }.getOrNull()
            val failed = vm.tasks.tasks.filter { it.status == ai.abhify.android.task.TaskStatus.FAILED && now - (it.completedAt ?: it.createdAt) < 3 * 86_400_000L }.map { it.title }
            Briefing.compose(kind, Briefing.Inputs(s.plan, s.projects, s.week.completed, s.study, failed, n?.articles.orEmpty().map { it.title }, n?.retrievedAt ?: 0, now), skipped())
        }.getOrNull() } ?: return@launch
        brief.value = b; then(b)
    }
    fun skip(key: String) { g.prefs.edit().putStringSet("briefing_skipped", (skipped() + key).toList().takeLast(300).toSet()).apply(); brief.value?.let { makeBriefing(it.kind) } }

    /** Records for the context resolver (permission: memories only when personal memory is on). */
    fun records(): List<ContextResolver.Record> {
        val ws = g.db.workspaces().filter { !vm.projects.isArchived(it.id) }
        val out = mutableListOf<ContextResolver.Record>()
        ws.forEach { w -> val st = vm.projects.meta(w.id).status
            out += ContextResolver.Record(Source.PROJECT, "p${w.id}", w.name, listOf(st.objective, st.current, st.next.joinToString("; "), st.issues.joinToString("; ")).joinToString(" "), st.updatedAt)
            g.db.tasks(w.id).filter { !it.done }.forEach { out += ContextResolver.Record(Source.TASK, "t${it.id}", it.title, "To-do in ${w.name}", 0) }
            g.db.notes(w.id).forEach { out += ContextResolver.Record(Source.NOTE, "n${it.id}", it.title, it.body, it.updatedAt) } }
        if (g.memoriesEnabled) g.db.memoryItems().filter { it.confirmed }.forEach { out += ContextResolver.Record(Source.MEMORY, "m${it.id}", it.category, it.content, it.updatedAt) }
        g.db.allDocs().forEach { out += ContextResolver.Record(Source.DOCUMENT, "d${it.id}", it.name, it.kind, it.updatedAt) }
        g.db.vault("", null, false, "recent").forEach { v -> out += ContextResolver.Record(if (v.kind == "news") Source.NEWS else Source.KNOWLEDGE, "v${v.id}", v.title, v.body.take(400), v.updatedAt) }
        vm.tasks.tasks.forEach { t -> out += ContextResolver.Record(Source.AI_TASK, "a${t.id}", t.title, t.status.name.lowercase(), t.completedAt ?: t.createdAt) }
        return out
    }
    fun permitted(): Set<Source> = Source.entries.toSet() - (if (g.memoriesEnabled) emptySet() else setOf(Source.MEMORY)) - Source.CONVERSATION
}
