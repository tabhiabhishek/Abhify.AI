package ai.abhify.android.ui

import ai.abhify.android.chat.Workspace
import ai.abhify.android.chat.addWorkspace
import ai.abhify.android.chat.deleteWorkspaceKeepDocs
import ai.abhify.android.chat.docById
import ai.abhify.android.chat.linkToWorkspace
import ai.abhify.android.chat.notes
import ai.abhify.android.chat.renameWorkspace
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.unlink
import ai.abhify.android.chat.workspaceDocIds
import ai.abhify.android.chat.workspaceLinks
import ai.abhify.android.task.TaskSource
import ai.abhify.android.workspace.ProjectMeta
import ai.abhify.android.workspace.ProjectStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project Workspace (Android): a project is an existing workspace. This adds instructions, archive/restore, export,
 * a safe delete (documents are kept) and entry points into the existing Chat, AI Task Runner and Study, each scoped
 * to the project's own and linked documents. Nothing here changes the database schema.
 */
class ProjectState(private val vm: AppViewModel) {
    private val g = vm.g
    private val store = ProjectStore(File(g.context.filesDir, "projects.json"))
    val metas = mutableStateMapOf<Long, ProjectMeta>().apply { putAll(store.load()) }

    /** The project page being shown (null = the list). */
    val open = androidx.compose.runtime.mutableStateOf<Workspace?>(null)
    data class Overview(val docs: List<ai.abhify.android.chat.LibraryDoc>, val notes: Int, val openTodos: Int, val links: Int,
                        val todoTitles: List<String> = emptyList(), val overdue: Int = 0)
    val overview = androidx.compose.runtime.mutableStateOf<Overview?>(null)

    fun openList() { open.value = null; vm.studio.loadWork(); vm.go(AppViewModel.Route.PROJECTS) }
    fun openProject(w: Workspace) { if (open.value?.id != w.id) overview.value = null; open.value = w; refresh(w) }
    fun refresh(w: Workspace) = vm.viewModelScope.launch {
        if (overview.value != null && open.value?.id != w.id) overview.value = null      // 6.1: never show another project's counts
        val o = withContext(Dispatchers.IO) {
            runCatching {
                val open = g.db.tasks(w.id).filter { !it.done }; val now = System.currentTimeMillis()
                Overview(g.db.workspaceDocIds(w.id).mapNotNull { g.db.docById(it) }, g.db.notes(w.id).size, open.size,
                    g.db.workspaceLinks(w.id).count { it.kind != "doc" }, open.map { it.title }, open.count { it.dueAt != null && it.dueAt < now })
            }.getOrNull()
        }
        if (open.value?.id == w.id) overview.value = o                     // 6.1: a slow load for project A never overwrites project B
    }
    fun create(name: String) = vm.viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = withContext(Dispatchers.IO) { g.db.addWorkspace(name.trim(), "project") }
        vm.studio.loadWork(); openProject(Workspace(id, name.trim().take(60), "project"))
    }
    fun rename(w: Workspace, name: String) = vm.viewModelScope.launch {
        if (name.isBlank()) return@launch
        withContext(Dispatchers.IO) { g.db.renameWorkspace(w.id, name.trim()) }
        val n = w.copy(name = name.trim().take(60)); if (open.value?.id == w.id) open.value = n
        if (vm.chatWorkspace.value?.id == w.id) vm.chatWorkspace.value = n
        vm.studio.loadWork()
    }
    /** Associate an existing document (a link: the document is not copied or moved). */
    fun linkDoc(w: Workspace, d: ai.abhify.android.chat.LibraryDoc) = vm.viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.linkToWorkspace(w.id, "doc", d.id) }; refresh(w)
    }
    /** Removes the association only; the document stays in the library. Documents imported into the project can't be unlinked. */
    fun unlinkDoc(w: Workspace, docId: Long) = vm.viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.workspaceLinks(w.id).filter { it.kind == "doc" && it.refId == docId }.forEach { g.db.unlink(it.id) } }; refresh(w)
    }

    fun meta(id: Long) = metas[id] ?: ProjectMeta()
    fun isArchived(id: Long) = meta(id).archived
    private fun put(id: Long, m: ProjectMeta) { metas[id] = m.copy(updatedAt = System.currentTimeMillis()); store.save(metas.toMap()) }

    fun setInstructions(w: Workspace, text: String) = put(w.id, meta(w.id).copy(instructions = text.trim().take(ProjectStore.MAX_INSTRUCTIONS)))
    fun setUseMemory(w: Workspace, on: Boolean) = put(w.id, meta(w.id).copy(useMemory = on))
    /** 5.1: the status the user records (objective, stack, status, decisions, milestones, issues, next actions). */
    fun setStatus(w: Workspace, st: ai.abhify.android.workspace.ProjectStatus) =
        put(w.id, meta(w.id).copy(status = st.copy(updatedAt = System.currentTimeMillis())))
    /** "Where you left off": only from recorded facts (null when nothing was recorded). */
    fun brief(w: Workspace): String? = overview.value.let { ov -> ai.abhify.android.intel.ProjectBrief.summary(ai.abhify.android.intel.ProjectBrief.Facts(
        w.name, meta(w.id).status, ov?.todoTitles.orEmpty(), ov?.overdue ?: 0, ov?.docs?.size ?: 0)) }
    /** Opens the project chat with a continuation question in the box (not sent: you decide). */
    fun continueInChat(w: Workspace) { openChat(w); vm.pendingInput.value = "Where did I leave off on this project, and what should I do next?"; vm.tab.value = AppViewModel.Tab.CHAT }
    fun archive(w: Workspace) {
        put(w.id, meta(w.id).copy(archived = true))
        if (vm.chatWorkspace.value?.id == w.id) vm.chatWorkspace.value = null          // an archived project is never used silently
    }
    fun restore(w: Workspace) = put(w.id, meta(w.id).copy(archived = false))

    /** After the user confirmed: the project, its notes and to-dos go; its documents move back to the library; linked items stay. */
    fun delete(w: Workspace) = vm.viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.deleteWorkspaceKeepDocs(w.id) }
        metas.remove(w.id); store.save(metas.toMap())
        if (vm.chatWorkspace.value?.id == w.id) vm.chatWorkspace.value = null
        vm.studio.workspace.value = null; open.value = null; vm.studio.loadWork()
    }

    suspend fun export(w: Workspace): String = withContext(Dispatchers.IO) {
        ProjectStore.markdown(w.name, meta(w.id), g.db.workspaceDocIds(w.id).mapNotNull { g.db.docById(it)?.name },
            g.db.notes(w.id).map { it.title to it.body }, g.db.tasks(w.id).map { it.title to it.done },
            g.db.workspaceLinks(w.id).filter { it.kind != "doc" && it.title != null }.map { it.kind to it.title!!.take(120) })
    }

    // ---------------------------------------------------------------- the project's work, through the existing features
    /** Project chat: the existing chat with this workspace as its context (notes, open to-dos, documents) plus its instructions. */
    fun openChat(w: Workspace) { vm.newChat(); vm.hub.useInChat(w) }

    /** The AI Task Runner with this project's documents as the source. */
    fun runTask(w: Workspace) { vm.tasks.open(null); vm.tasks.preset.value = TaskSource("project", w.id, w.name); vm.go(AppViewModel.Route.TASKS) }

    /** Study: the existing study-plan builder with the project's documents preselected. */
    fun study(w: Workspace) = vm.viewModelScope.launch {
        val ids = withContext(Dispatchers.IO) { g.db.workspaceDocIds(w.id) }
        vm.studio.planDocPreset.clear(); vm.studio.planDocPreset += ids
        vm.studio.open(null); vm.learnMode.value = 2; vm.tab.value = AppViewModel.Tab.STUDY; vm.home()
    }
}
