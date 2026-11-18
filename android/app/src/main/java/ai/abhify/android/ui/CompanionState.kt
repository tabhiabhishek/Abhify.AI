package ai.abhify.android.ui

import ai.abhify.android.chat.conversationPage
import ai.abhify.android.chat.deckStats
import ai.abhify.android.chat.planStats
import ai.abhify.android.chat.quizSessionStats
import ai.abhify.android.chat.quizStats
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.workspaces
import ai.abhify.android.intel.Suggestions
import ai.abhify.android.memory.KnowledgeUpdates
import ai.abhify.android.task.TaskStatus
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 5.1 Proactive Companion on Home: suggestions computed from records on this phone when Home opens (no background
 * work, no notifications, no AI call). Dismissed keys and the on/off switch are kept in preferences.
 */
class CompanionState(private val vm: AppViewModel) {
    private val g = vm.g
    val enabled = mutableStateOf(g.prefs.getBoolean("suggestions_enabled", true))
    val items = mutableStateListOf<Suggestions.Suggestion>()
    /** 5.2 "Continue working": where you left off (its own switch). */
    val continueEnabled = mutableStateOf(g.prefs.getBoolean("continue_enabled", true))
    val continueItems = mutableStateListOf<ai.abhify.android.intel.ContinueWorking.Item>()
    fun setContinueEnabled(on: Boolean) { continueEnabled.value = on; g.prefs.edit().putBoolean("continue_enabled", on).apply(); load() }
    private fun dismissed(): Set<String> = g.prefs.getStringSet("suggestions_dismissed", emptySet()).orEmpty()

    fun setEnabled(on: Boolean) { enabled.value = on; g.prefs.edit().putBoolean("suggestions_enabled", on).apply(); if (on) load() else items.clear() }

    fun load() {
        if (!enabled.value) items.clear()
        if (!continueEnabled.value) continueItems.clear()
        if (!enabled.value && !continueEnabled.value) return
        val tasks = vm.tasks.tasks.filter { it.status == TaskStatus.DRAFT || it.status == TaskStatus.READY || (it.status == TaskStatus.RUNNING && !vm.tasks.running) }
            .map { ai.abhify.android.intel.ContinueWorking.TaskSignal(it.id, it.title, it.createdAt, it.status == TaskStatus.RUNNING) }
        val corrections = vm.knowledge.updates.count { it.status == KnowledgeUpdates.Status.PENDING }
        val failed = vm.tasks.tasks.filter { it.status == TaskStatus.FAILED }.map { Suggestions.TaskSignal(it.title, it.completedAt ?: it.createdAt) }
        vm.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val now = System.currentTimeMillis()
                val workspaces = g.db.workspaces()
                val cont = if (!continueEnabled.value) emptyList() else ai.abhify.android.intel.ContinueWorking.items(
                    g.db.conversationPage(false, null, null, 1, 0).map { ai.abhify.android.intel.ContinueWorking.ChatSignal(it.id, it.title, it.updatedAt) },
                    workspaces.map { w -> val m = vm.projects.meta(w.id)
                        ai.abhify.android.intel.ContinueWorking.ProjectSignal(w.id, w.name, m.status.updatedAt, m.status.next.firstOrNull(), m.status.current.ifBlank { null }, m.archived) },
                    tasks, g.db.planStats().map { ai.abhify.android.intel.ContinueWorking.PlanSignal(it.topic, it.done, it.total, it.lastActivity) }, now)
                if (!enabled.value) return@runCatching cont to emptyList()
                val projects = workspaces.map { w ->
                    val m = vm.projects.meta(w.id); val open = g.db.tasks(w.id).filter { !it.done }
                    Suggestions.ProjectSignal(w.id, w.name, m.status.next, m.status.issues, open.size, open.count { it.dueAt != null && it.dueAt < now },
                        m.status.updatedAt, m.archived)
                }
                val study = ai.abhify.android.learn.StudyInsights.suggest(g.db.quizStats(), g.db.deckStats(now), g.db.planStats(), g.db.quizSessionStats(), now,
                    java.time.LocalDate.now().toString()).map { Suggestions.StudySignal(it.topic, it.reason, it.priority) }
                // a project already in "Continue working" doesn't repeat its next action as a suggestion
                val shown = cont.filter { it.kind == ai.abhify.android.intel.ContinueWorking.Kind.PROJECT }.map { "next:${it.ref}:" }
                cont to Suggestions.compute(projects, study, corrections, failed, dismissed(), now).filter { s -> shown.none { s.key.startsWith(it) } }
            }.getOrDefault(emptyList<ai.abhify.android.intel.ContinueWorking.Item>() to emptyList()) }
            continueItems.clear(); continueItems.addAll(result.first)
            items.clear(); items.addAll(result.second)
        }
    }

    fun openContinue(c: ai.abhify.android.intel.ContinueWorking.Item) {
        when (c.kind) {
            ai.abhify.android.intel.ContinueWorking.Kind.CHAT -> { vm.loadConversation(c.ref.toLong()); vm.tab.value = AppViewModel.Tab.CHAT }
            ai.abhify.android.intel.ContinueWorking.Kind.PROJECT -> vm.viewModelScope.launch {
                val w = withContext(Dispatchers.IO) { g.db.workspaces().firstOrNull { it.id == c.ref.toLong() } } ?: return@launch
                vm.projects.openList(); vm.projects.openProject(w)
            }
            ai.abhify.android.intel.ContinueWorking.Kind.TASK -> { vm.tasks.open(vm.tasks.tasks.firstOrNull { it.id == c.ref }); vm.go(AppViewModel.Route.TASKS) }
            ai.abhify.android.intel.ContinueWorking.Kind.STUDY -> vm.tab.value = AppViewModel.Tab.STUDY
        }
    }

    fun dismiss(s: Suggestions.Suggestion) {
        val keep = (dismissed() + s.key).toList().takeLast(200).toSet()          // bounded
        g.prefs.edit().putStringSet("suggestions_dismissed", keep).apply(); items.remove(s)
    }

    fun open(s: Suggestions.Suggestion) {
        when (s.target) {
            Suggestions.Target.PROJECT -> vm.viewModelScope.launch {
                val w = withContext(Dispatchers.IO) { g.db.workspaces().firstOrNull { it.id == s.ref } } ?: return@launch
                vm.projects.openList(); vm.projects.openProject(w)
            }
            Suggestions.Target.STUDY -> vm.tab.value = AppViewModel.Tab.STUDY
            Suggestions.Target.KNOWLEDGE_CENTER -> vm.go(AppViewModel.Route.SEMANTIC)
            Suggestions.Target.TASKS -> vm.go(AppViewModel.Route.TASKS)
        }
    }
}
