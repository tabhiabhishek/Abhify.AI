package ai.abhify.android.ui

import ai.abhify.android.chat.Workspace
import ai.abhify.android.chat.WorkspaceLink
import ai.abhify.android.chat.addWorkspace
import ai.abhify.android.chat.linkToWorkspace
import ai.abhify.android.chat.unifiedSearch
import ai.abhify.android.chat.unlink
import ai.abhify.android.chat.workspaceDocIds
import ai.abhify.android.chat.workspaceLinks
import ai.abhify.android.chat.workspaces
import ai.abhify.android.workspace.UnifiedSearch
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.0 unified workspace: one local search across everything, workspace links (references, not copies) and the
 * cross-feature actions. Every action that changes data is a tap by the user; nothing here sends anything online.
 */
class WorkspaceHub(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    private val msg get() = vm.studio.message

    // ------------------------------------------------------------------ unified search (local only)
    val query = mutableStateOf("")
    val types = mutableStateListOf<UnifiedSearch.Type>()                                 // empty = all types
    /** 5.6: date window, project and sort (Search 2.0). */
    val filters = mutableStateOf(UnifiedSearch.Filters())
    val results = mutableStateListOf<UnifiedSearch.Hit>()
    val searching = mutableStateOf(false)
    val lastMs = mutableStateOf<Long?>(null)
    /** Next build: projects (recorded status) and AI task history, which live in JSON stores beside the database. */
    data class Extra(val kind: String, val title: String, val snippet: String, val at: Long, val open: () -> Unit)
    val extras = mutableStateListOf<Extra>()
    val recent = mutableStateListOf<String>().apply { addAll(g.prefs.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotBlank() }) }
    private fun remember(q: String) {
        val t = q.trim(); if (t.length < 2) return
        recent.remove(t); recent.add(0, t); while (recent.size > 8) recent.removeAt(recent.lastIndex)
        g.prefs.edit().putString("recent_searches", recent.joinToString("\n")).apply()
    }
    fun clearRecent() { recent.clear(); g.prefs.edit().remove("recent_searches").apply() }

    fun search() = scope.launch {
        val q = query.value
        if (UnifiedSearch.pattern(q) == null) { results.clear(); extras.clear(); return@launch }
        searching.value = true
        val t0 = System.currentTimeMillis()
        // permission: memories are searched only while personal memory is on
        val allowed = (if (types.isEmpty()) UnifiedSearch.Type.entries.toSet() else types.toSet()) - (if (g.memoriesEnabled) emptySet() else setOf(UnifiedSearch.Type.MEMORY))
        val f = filters.value
        val r = withContext(Dispatchers.IO) {
            val raw = g.db.unifiedSearch(q, allowed, perType = if (f.active) 30 else 8)
            if (!f.active) raw else {
                val pid = f.project
                val links = if (pid == null) emptyList() else g.db.workspaceLinks(pid)
                UnifiedSearch.filter(raw, q, f, System.currentTimeMillis(), links.filter { it.kind == "chat" }.map { it.refId }.toSet(),
                    if (pid == null) emptySet() else g.db.workspaceDocIds(pid).toSet()).let { l -> l.groupBy { it.type }.flatMap { it.value.take(8) } }
            }
        }
        val words = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        fun hit(s: String) = words.all { s.lowercase().contains(it) }
        val since = if (f.days > 0) System.currentTimeMillis() - f.days * 86_400_000L else Long.MIN_VALUE
        val ex = if (types.isNotEmpty()) emptyList() else withContext(Dispatchers.IO) {
            g.db.workspaces().mapNotNull { w -> val st = vm.projects.meta(w.id).status
                val text = listOf(w.name, st.objective, st.current, st.next.joinToString(" "), st.issues.joinToString(" "), st.decisions.joinToString(" ")).joinToString(" ")
                if (hit(text)) Extra("🗂 Project", w.name, (st.next.firstOrNull()?.let { "Next: $it" } ?: st.current), st.updatedAt) { vm.projects.openList(); vm.projects.openProject(w) } else null } +
            vm.tasks.tasks.filter { hit(it.title + " " + it.request) }.take(5).map { t -> Extra("✅ AI task", t.title, t.status.name.lowercase(), t.completedAt ?: t.createdAt) { vm.tasks.open(t); vm.go(AppViewModel.Route.TASKS) } } +
            // 5.6: loaded news stories and today's briefing (both already on the phone; nothing is fetched for search)
            vm.newsCenter.result.value?.articles.orEmpty().filter { hit(it.title + " " + it.summary) }.take(5).map { a ->
                Extra("📰 News · ${a.publisher}", a.title, a.summary.take(160), a.publishedAt) { vm.tab.value = AppViewModel.Tab.NEWS; vm.newsCenter.openArticle(a) } } +
            vm.brain.brief.value?.sections.orEmpty().flatMap { s -> s.items.map { s.title to it } }.filter { hit(it.second.text) }.take(3).map { (t, i) ->
                Extra("☀ Briefing · $t", i.text, i.source, 0) { vm.tab.value = AppViewModel.Tab.HOME } }
        }.filter { f.project == null && (it.at == 0L || it.at >= since) }.let { l -> if (f.sort == UnifiedSearch.Sort.RECENT) l.sortedByDescending { it.at } else l }
        lastMs.value = System.currentTimeMillis() - t0
        if (q == query.value) { results.clear(); results.addAll(r); extras.clear(); extras.addAll(ex); if (r.isNotEmpty() || ex.isNotEmpty()) remember(q) }
        searching.value = false
    }

    /** Opens the item where it lives. */
    fun open(h: UnifiedSearch.Hit) {
        when (h.type) {
            UnifiedSearch.Type.CHAT -> { vm.loadConversation(h.id); vm.tab.value = AppViewModel.Tab.CHAT }
            UnifiedSearch.Type.MEMORY -> { vm.memory.query.value = h.snippet.take(40); vm.go(AppViewModel.Route.MEMORIES); return }
            UnifiedSearch.Type.DOCUMENT -> { vm.studio.docQuery.value = h.title.take(40); vm.tab.value = AppViewModel.Tab.DOCS }
            UnifiedSearch.Type.NOTE, UnifiedSearch.Type.TASK -> { openWorkspace(h.ref); vm.studio.noteQuery.value = if (h.type == UnifiedSearch.Type.NOTE) h.title.take(40) else "" }
            UnifiedSearch.Type.KNOWLEDGE -> { vm.life.vaultQuery.value = h.title.take(40); vm.knowledgeSection.intValue = 0; vm.tab.value = AppViewModel.Tab.KNOWLEDGE }
            UnifiedSearch.Type.STUDY -> { vm.learnMode.value = 0; vm.tab.value = AppViewModel.Tab.STUDY }
            UnifiedSearch.Type.CARD -> { vm.learnMode.value = 3; vm.studio.review(h.title); vm.tab.value = AppViewModel.Tab.STUDY }
            UnifiedSearch.Type.PLAN -> { vm.learnMode.value = 2; vm.studio.plans.firstOrNull { it.id == h.id }?.let { vm.studio.open(it) }; vm.tab.value = AppViewModel.Tab.STUDY }
            UnifiedSearch.Type.CAREER -> vm.tab.value = AppViewModel.Tab.CAREER
        }
        vm.home()
    }
    private fun openWorkspace(id: Long?) = scope.launch {
        val ws = withContext(Dispatchers.IO) { g.db.workspaces() }.firstOrNull { it.id == id } ?: return@launch
        vm.studio.loadWork(); vm.studio.select(ws); vm.knowledgeSection.intValue = 1; vm.tab.value = AppViewModel.Tab.KNOWLEDGE
    }

    // ------------------------------------------------------------------ workspace links (references, not copies)
    val links = mutableStateListOf<WorkspaceLink>()
    fun loadLinks(ws: Workspace?) = scope.launch { val l = if (ws == null) emptyList() else withContext(Dispatchers.IO) { g.db.workspaceLinks(ws.id) }; links.clear(); links.addAll(l) }
    fun kindOf(t: UnifiedSearch.Type) = when (t) { UnifiedSearch.Type.CHAT -> "chat"; UnifiedSearch.Type.DOCUMENT -> "doc"; UnifiedSearch.Type.MEMORY -> "memory"; else -> null }
    fun link(ws: Workspace, kind: String, refId: Long, label: String) = scope.launch {
        val added = withContext(Dispatchers.IO) { g.db.linkToWorkspace(ws.id, kind, refId) }
        msg.value = if (added) "Linked \"${label.take(50)}\" to \"${ws.name}\" (a reference; nothing was copied)." else "Already linked to \"${ws.name}\"."
        if (vm.studio.workspace.value?.id == ws.id) loadLinks(ws)
    }
    fun unlink(l: WorkspaceLink) = scope.launch { withContext(Dispatchers.IO) { g.db.unlink(l.id) }; loadLinks(vm.studio.workspace.value) }
    /** "Create a workspace from an existing project": a new workspace that links the chat / memory / document it came from. */
    fun newWorkspaceFrom(name: String, kind: String, refId: Long) = scope.launch {
        val ws = withContext(Dispatchers.IO) { val id = g.db.addWorkspace(name.trim().take(60).ifBlank { "Project" }, "work"); g.db.linkToWorkspace(id, kind, refId)
            g.db.workspaces().first { it.id == id } }
        vm.studio.loadWork(); msg.value = "Created the workspace \"${ws.name}\" and linked it."
    }
    /** Use a workspace as chat context (its notes, open tasks and documents; bounded; online only after consent). */
    fun useInChat(ws: Workspace?) { vm.chatWorkspace.value = ws; if (ws != null) { vm.tab.value = AppViewModel.Tab.CHAT; vm.home() } }

    // ------------------------------------------------------------------ cross-feature actions
    /** A document passage becomes a memory SUGGESTION: the usual checks (sensitive, duplicate, conflict) and the user's OK apply. */
    fun suggestMemory(h: UnifiedSearch.Hit, text: String) {
        vm.memory.add(text.trim().take(300), "General", 2, null, source = "doc:${h.id}")
        vm.memory.load(); vm.go(AppViewModel.Route.MEMORIES)
    }
    /** Pre-selects a document for a new study plan and opens Study → Plans. */
    fun studyPlanFrom(docId: Long) { vm.studio.planDocPreset.clear(); vm.studio.planDocPreset += docId; vm.studio.open(null); vm.learnMode.value = 2; vm.tab.value = AppViewModel.Tab.STUDY; vm.home() }
}
