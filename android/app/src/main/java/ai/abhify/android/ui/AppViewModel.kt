package ai.abhify.android.ui

import androidx.core.content.edit
import ai.abhify.android.AppGraph
import ai.abhify.android.DownloadService
import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.OnlineTarget
import ai.abhify.android.ai.RouteInputs
import ai.abhify.android.providers.ModelInfo
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderConfig
import ai.abhify.android.ai.ChatEvent
import ai.abhify.android.ai.Source
import ai.abhify.android.chat.allDocs
import ai.abhify.android.chat.ChatDb
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.chat.deleteStudioData
import ai.abhify.android.chat.deleteLifeData
import ai.abhify.android.chat.addMemoryItem
import ai.abhify.android.chat.memoryItems
import ai.abhify.android.chat.conversationPage
import ai.abhify.android.chat.markMemoriesUsed
import ai.abhify.android.chat.workspaceDocIds
import ai.abhify.android.chat.notes
import ai.abhify.android.chat.tasks
import ai.abhify.android.chat.workspaces
import ai.abhify.android.chat.saveNote
import ai.abhify.android.chat.savedPrompts
import ai.abhify.android.chat.savePrompt
import ai.abhify.android.chat.deletePrompt
import ai.abhify.android.chat.promptUsed
import ai.abhify.android.chat.conversationTitle
import ai.abhify.android.cloud.Account
import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.device.DeviceProbe
import ai.abhify.android.graph
import ai.abhify.android.learn.InterviewQuestion
import ai.abhify.android.learn.Learning
import ai.abhify.android.learn.Mcq
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.Compatibility
import ai.abhify.android.model.ModelSelector
import ai.abhify.android.news.NewsItem
import ai.abhify.android.news.NewsRepository
import ai.abhify.android.sync.SyncStatus
import ai.abhify.android.sync.SyncWorker
import ai.abhify.android.voice.Voice
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val g: AppGraph = app.graph

    // ------------------------------------------------------------------ navigation
    enum class Route { WELCOME, PROFILE, LOGIN, REGISTER, FORGOT, LINK, HOME, HISTORY, MEMORIES, MODELS, PROVIDERS, SETTINGS, ACCOUNT, SYNC, PRIVACY, ABOUT, SETUP, RESUME,
        DRIVE, STORAGE, BACKUP, SEMANTIC, SEARCH, CONNECTION, TASKS, PROJECTS }
    /** The five main areas (v3). News moved into Learn. */
    /** v3.2 main areas; Settings opens from the header ⚙ (not in the bottom bar). Work tools live in My Knowledge. */
    /** 4.6.2 navigation: Home · Chat · Study · News · More in the bar; Documents (moved from the bar), Career, Knowledge and Settings open from More/Home. */
    enum class Tab(val label: String, val icon: String, val inBar: Boolean = true) {
        HOME("Home", "🏠"), CHAT("Assistant", "💬"), STUDY("Study", "🎓"), NEWS("News", "📰"), MORE("More", "⋯"),
        DOCS("Documents", "📚", false), CAREER("Career", "💼", false), KNOWLEDGE("Knowledge", "🗂", false), SETTINGS("Settings", "⚙", false);
        /** The bar item that is highlighted while this tab is open (Career, Knowledge and Settings live under More). */
        val barParent get() = if (inBar) this else MORE
    }

    val stack = mutableStateListOf(if (g.prefs.getBoolean("onboarded", false)) Route.HOME else Route.WELCOME)
    val route get() = stack.last()
    val tab = mutableStateOf(Tab.HOME)
    /** Documents, Learn (tutor, plans, cards) and Work state; kept across tab switches. */
    val studio = StudioState(this)
    val resume = ResumeState(this)
    val life = LifeState(this)
    val career = CareerState(this)
    val memory = MemoryState(this)
    val drive = DriveState(this)
    val tasks by lazy { TaskState(this) }
    val projects by lazy { ProjectState(this) }
    val mcq by lazy { McqState(this) }
    val newsCenter by lazy { NewsState(this) }
    val knowledge by lazy { KnowledgeCenterState(this) }
    val companion by lazy { CompanionState(this) }
    val discover by lazy { DiscoverState(this) }
    // next build: Personal AI Brain data side, Agent 2.0, briefing
    val brain by lazy { BrainState(this) }
    val agent by lazy { AgentState(this) }
    /** 7.2 Study: courses, interview plan, STAR stories (filesDir/study.json; AI on this phone). */
    val study by lazy { StudyState(this) }
    val briefingOpen = mutableStateOf(false)
    fun openBriefing() { briefingOpen.value = true }
    val backup = BackupState(this)
    val storage = StorageState(this)
    fun go(r: Route) { if (route != r) stack += r }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { stack.clear(); stack += Route.HOME }
    /** Leaves the welcome flow: into the app, or to model download first if this phone has none. */
    /** After the profile or sign-in: the setup steps (model, providers, OCR, privacy). Each install chooses its own. */
    fun finishOnboarding() { setupStep.intValue = 0; stack.clear(); stack += Route.SETUP }
    val setupStep = mutableIntStateOf(0)
    /** Setup finished (or skipped): the app opens normally from now on, also after updates. Nothing is reset. */
    fun completeSetup() {
        g.prefs.edit().putBoolean("onboarded", true).apply(); home()
    }
    /** Settings → "Run setup again": the same steps over the current configuration (nothing is cleared). */
    fun rerunSetup() { setupStep.intValue = 0; go(Route.SETUP) }

    // ------------------------------------------------------------------ memory profile and OCR settings
    val memoryChoice = mutableStateOf(g.memoryChoice)
    fun setMemoryChoice(p: ai.abhify.android.device.MemoryProfile?) { g.memoryChoice = p; memoryChoice.value = p }
    val ocrAllowed = mutableStateOf(g.ocrAllowed)
    fun setOcrAllowed(v: Boolean?) { g.ocrAllowed = v; ocrAllowed.value = v }
    val ocrTest = mutableStateOf<String?>(null)
    /** Draws known text into an image, recognizes it with the OCR engine and reports accuracy and time (on this phone). */
    fun ocrSelfTest() = viewModelScope.launch {
        ocrTest.value = "Running…"
        ocrTest.value = try {
            withContext(Dispatchers.Default) { ai.abhify.android.ocr.OcrSelfTest.run(g) }
        } catch (e: ai.abhify.android.docs.DocumentException) { "Not run: ${e.message}" }
        catch (e: Exception) { "Failed: ${e.message}" }
    }


    // ------------------------------------------------------------------ state shared by screens
    val device = mutableStateOf(DeviceProbe.read(app))
    val theme = mutableStateOf(g.themeMode)
    val aiMode = mutableStateOf(g.aiMode)
    val engine = mutableStateOf<Engine>(Engine.NotLoaded)
    val online = mutableStateOf(g.network.online.value)
    val account = mutableStateOf(g.cloud.account.value)
    val sync = mutableStateOf(g.syncStatus.value)
    val pending = mutableIntStateOf(0)
    val toolResult = mutableStateOf<String?>(null)
    val toolBusy = mutableStateOf(false)

    sealed class Engine {
        data object NotLoaded : Engine()
        data object Loading : Engine()
        data class Ready(val backend: String) : Engine()
        data class Error(val message: String) : Engine()
    }

    init {
        viewModelScope.launch { g.network.online.collectLatest { online.value = it; refreshPending() } }
        viewModelScope.launch { g.cloud.account.collectLatest { account.value = it } }
        viewModelScope.launch { g.syncStatus.collectLatest { sync.value = it; refreshPending(); if (it is SyncStatus.Done) reloadAfterSync() } }
        refreshPending()
    }

    fun refreshDevice() { device.value = DeviceProbe.read(getApplication()) }
    fun refreshPending() = viewModelScope.launch { pending.intValue = withContext(Dispatchers.IO) { g.db.pendingCount() } }

    /** One honest line for the header: internet first; account/sync only matter when an Abhify account is used. */
    data class Status(val text: String, val level: Int)            // 0 ok, 1 info/warn, 2 problem
    fun status(): Status {
        val acct = account.value
        val s = sync.value
        return when {
            !online.value -> Status(if (acct is Account.SignedIn && pending.intValue > 0) "Offline · ${pending.intValue} changes waiting to sync" else "Offline · on-device AI available", 1)
            acct is Account.Expired -> Status("Internet connected · Abhify sign-in expired (your own providers still work)", 1)
            acct !is Account.SignedIn -> Status("Internet connected", 0)
            s is SyncStatus.Running -> Status("Online · syncing…", 0)
            s is SyncStatus.Failed -> Status(if (s.authExpired) "Sign-in expired" else "Online · sync failed (will retry)", 2)
            !g.syncEnabled -> Status("Online · sync off", 0)
            pending.intValue > 0 -> Status("Online · sync pending (${pending.intValue})", 1)
            else -> Status("Online · synced", 0)
        }
    }

    fun setTheme(mode: String) { g.themeMode = mode; theme.value = mode }
    fun setAiMode(m: AiMode) { g.aiMode = m; aiMode.value = m; refreshPending() }

    // ------------------------------------------------------------------ accounts
    val authBusy = mutableStateOf(false)
    val authError = mutableStateOf<String?>(null)
    val authInfo = mutableStateOf<String?>(null)
    val server = mutableStateOf(g.serverUrl)
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun authTask(block: suspend () -> Unit) = viewModelScope.launch {
        if (authBusy.value) return@launch
        authBusy.value = true; authError.value = null; authInfo.value = null
        try { block() }
        catch (e: ApiException) { authError.value = e.message }
        catch (e: NetworkException) { authError.value = "Can't reach the server. Check the address and your internet connection." }
        catch (e: IllegalArgumentException) { authError.value = e.message }
        catch (e: Exception) { authError.value = "Something went wrong: ${e.message}" }
        finally { authBusy.value = false }
    }

    private fun requireServer(): String {
        val s = server.value.trim()
        require(s.isNotEmpty()) { "Enter your Abhify server address (ask whoever runs your server)." }
        require(s.startsWith("https://") || ai.abhify.android.cloud.UrlHttp.isLocalDev(s)) { "The server address must start with https://" }
        g.serverUrl = s; setServerEnabled(true)
        return s
    }

    // ------------------------------------------------------------------ v4.2 local-first: the Abhify server is optional
    /** Result of the last "Verify server" (null = not checked in this session; nothing is checked automatically). */
    val serverCheck = mutableStateOf<ai.abhify.android.cloud.ServerConnection.Health?>(null)
    val serverChecking = mutableStateOf(false)
    val serverEnabled = mutableStateOf(g.prefs.getBoolean("server_enabled", true))
    private fun setServerEnabled(on: Boolean) { serverEnabled.value = on; g.prefs.edit().putBoolean("server_enabled", on).apply() }
    fun serverMode() = ai.abhify.android.cloud.ServerConnection.mode(g.serverUrl, serverEnabled.value, serverCheck.value?.ok, account.value is Account.SignedIn)
    /** Checks an address (format, then the server's /api/v1/health). [save] keeps it and turns the server connection on when it works. */
    fun verifyServer(input: String, save: Boolean) {
        if (serverChecking.value) return
        when (val c = ai.abhify.android.cloud.ServerConnection.validate(input, ai.abhify.android.BuildConfig.DEBUG)) {
            is ai.abhify.android.cloud.ServerConnection.Check.Invalid -> serverCheck.value = ai.abhify.android.cloud.ServerConnection.Health(false, c.reason)
            is ai.abhify.android.cloud.ServerConnection.Check.Ok -> viewModelScope.launch {
                serverChecking.value = true
                val h = withContext(Dispatchers.IO) {
                    try { ai.abhify.android.cloud.ServerConnection.interpret(200, g.cloud.health(c.url).toString()) }
                    catch (e: ApiException) { ai.abhify.android.cloud.ServerConnection.interpret(e.code, null) }
                    catch (e: Exception) { ai.abhify.android.cloud.ServerConnection.interpret(null, null) }
                }
                serverCheck.value = h.copy(message = h.message + (c.note?.let { " Note: $it" } ?: ""))
                if (save && h.ok) { g.serverUrl = c.url; server.value = c.url; setServerEnabled(true) }
                serverChecking.value = false
            }
        }
    }
    /** Stops using the server (signs out there if signed in). The address is kept; local data and Google Drive are untouched. */
    fun disconnectServer() { if (account.value is Account.SignedIn) logout(); setServerEnabled(false); serverCheck.value = null }
    /** Forgets the address too. Local data and Google Drive are untouched. */
    fun removeServerAddress() { disconnectServer(); g.prefs.edit().remove("server_url").apply(); server.value = g.serverUrl }

    fun login(email: String, password: String) = authTask {
        val srv = requireServer()
        withContext(Dispatchers.IO) { g.cloud.login(srv, email, password, deviceName) }
        afterSignIn()
    }

    fun register(name: String, email: String, password: String, confirm: String) = authTask {
        require(password == confirm) { "The two passwords don't match." }
        val srv = requireServer()
        withContext(Dispatchers.IO) { g.cloud.register(srv, email, password, name, deviceName) }
        if (g.userName.isBlank()) g.userName = name
        afterSignIn()
    }

    /** Same account as the linked one: continue. Otherwise the user sees what's on this phone and confirms linking. */
    private fun afterSignIn() {
        val linked = g.db.linkedAccount()
        if (linked != null && linked.userId == g.cloud.session?.userId) { g.linkProfile(); finishOnboarding() }
        else { preview.value = g.db.syncPreview(); go(Route.LINK) }
    }

    val preview = mutableStateOf<ChatDb.SyncPreview?>(null)
    /** Links the local profile; nothing is uploaded (sync stays off until enabled in Sync settings). */
    fun confirmLink() { g.linkProfile(); finishOnboarding() }
    /** Declines linking: signs out of the account; the local profile and all its data stay as they were. */
    fun declineLink() = authTask { withContext(Dispatchers.IO) { g.cloud.logout() }; g.onSignedOut(); back() }
    fun unlink() = authTask { withContext(Dispatchers.IO) { g.cloud.logout() }; g.unlinkProfile(); home(); authInfo.value = "Unlinked. Everything stays on this phone." }
    fun linked() = g.db.linkedAccount()

    /** Onboarding without an account: a local profile (just a name) and straight into the app. */
    fun createLocalProfile(name: String) { g.userName = name; finishOnboarding() }

    // ------------------------------------------------------------------ privacy and sync settings
    val onlineAllowed = mutableStateOf(g.onlineAllowed)
    val shareMemories = mutableStateOf(g.shareMemoriesOnline)
    fun setOnlineAllowed(on: Boolean) { g.onlineAllowed = on; onlineAllowed.value = on; if (!on) { g.shareMemoriesOnline = false; shareMemories.value = false } }
    fun setShareMemories(on: Boolean) { g.shareMemoriesOnline = on; shareMemories.value = on }
    val syncOn = mutableStateOf(g.syncEnabled)
    val wifiOnly = mutableStateOf(g.wifiOnly)
    val conflicts = mutableStateListOf<ChatDb.Conflict>()
    fun loadSyncScreen() = viewModelScope.launch {
        preview.value = withContext(Dispatchers.IO) { g.db.syncPreview() }
        val c = withContext(Dispatchers.IO) { g.db.conflicts() }; conflicts.clear(); conflicts.addAll(c)
        refreshPending()
    }
    fun setWifiOnly(on: Boolean) { g.wifiOnly = on; wifiOnly.value = on }
    fun setSyncKind(kind: String, on: Boolean) { g.setSyncKind(kind, on) }
    fun resolveConflict(id: Long, keepLocal: Boolean) = viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.resolveConflict(id, keepLocal) }; loadSyncScreen(); g.localChanged()
    }

    fun forgot(email: String) = authTask {
        val srv = requireServer()
        authInfo.value = withContext(Dispatchers.IO) { g.cloud.forgotPassword(srv, email) }
    }

    fun reset(email: String, code: String, newPassword: String) = authTask {
        val srv = requireServer()
        authInfo.value = withContext(Dispatchers.IO) { g.cloud.resetPassword(srv, email, code, newPassword) } + " Sign in now."
    }

    /** Signs out of the account here (the session is revoked on the server). The profile stays linked and all data stays. */
    fun logout() = authTask {
        withContext(Dispatchers.IO) { g.cloud.logout() }
        g.onSignedOut(); home()
    }

    val sessions = mutableStateListOf<JSONObject>()
    fun loadSessions() = authTask {
        val a: JSONArray = withContext(Dispatchers.IO) { g.cloud.sessions() }
        sessions.clear(); sessions.addAll((0 until a.length()).map(a::getJSONObject))
    }
    fun endSession(id: String) = authTask { withContext(Dispatchers.IO) { g.cloud.endSession(id) }; sessions.removeAll { it.getString("id") == id } }
    fun rename(name: String) = authTask { withContext(Dispatchers.IO) { g.cloud.updateName(name) }; authInfo.value = "Saved." }
    fun changePassword(current: String, new: String) = authTask { authInfo.value = withContext(Dispatchers.IO) { g.cloud.changePassword(current, new) } }
    fun deleteCloudData() = authTask {
        val r = withContext(Dispatchers.IO) { g.cloud.deleteCloudData() }
        withContext(Dispatchers.IO) { g.db.resetSync() }
        g.syncEnabled = false; syncOn.value = false
        authInfo.value = "${r.optInt("deleted")} cloud records deleted. Sync is now off; data on this phone is untouched."
    }
    fun deleteAccount(password: String) = authTask {
        withContext(Dispatchers.IO) { g.cloud.deleteAccount(password) }
        g.onSignedOut(); home(); authInfo.value = "Account deleted. Everything on this phone is still here."
    }

    fun setSync(on: Boolean) { g.syncEnabled = on; syncOn.value = on; sync.value = g.syncStatus.value }
    fun syncNow() = SyncWorker.syncSoon(getApplication(), 0)

    // ------------------------------------------------------------------ setup / models
    fun compat(): List<Compatibility> = g.catalog.models.map { ModelSelector.check(it, device.value, installed = g.store.modelFile(it.id).exists()) }
    fun recommended(): CatalogModel? = (ModelSelector.recommend(g.catalog, device.value) as? Compatibility.Ok)?.model
    fun activeModel(): CatalogModel? = g.store.active()?.let { g.catalog.byId(it.id) }
    fun appUnsupported(): String? = ModelSelector.appUnsupportedReason(device.value)
    fun startDownload(m: CatalogModel) { g.setup.reset(); DownloadService.start(getApplication(), m.id) }
    fun pauseDownload() = g.setup.pause()
    fun cancelDownload(id: String) = g.setup.cancel(id)
    fun finishSetup() { g.setup.reset(); engine.value = Engine.NotLoaded; modelRev.intValue++; viewModelScope.launch(Dispatchers.IO) { g.mirrorModels() }; home() }

    /** 4.6.2: tries to start the active phone model again after a failure (the error shows in the picker, never hidden). */
    fun retryModel() = viewModelScope.launch {
        if (generating.value || modelSwitching.value != null) return@launch
        modelPickError.value = null
        g.engine.unload(); engine.value = Engine.NotLoaded
        modelSwitching.value = activeModel()?.name ?: "the phone model"
        if (!readyDevice()) modelPickError.value = "${modelSwitching.value} could not start: ${(engine.value as? Engine.Error)?.message ?: "unknown error"}"
        modelSwitching.value = null; modelRev.intValue++
    }

    /** 4.6: the speed of the last answer, as measured by the engine/provider stream (null until one finished). */
    data class LastSpeed(val source: String, val tokensPerSecond: Double, val cloud: Boolean)
    val lastSpeed = mutableStateOf<LastSpeed?>(null)

    // ------------------------------------------------------------------ 4.5 chat model picker
    /** Bumped whenever the active model or the chat's model choice changes, so the chip and the picker recompose. */
    val modelRev = mutableIntStateOf(0)
    /** Name of the phone model being loaded after a switch (null = not switching). */
    val modelSwitching = mutableStateOf<String?>(null)
    val modelPickError = mutableStateOf<String?>(null)

    /** What the picker needs, read from the real stores (catalog, installed models, provider keys, privacy, account). */
    fun modelInputs(): ai.abhify.android.ai.ModelChoices.Inputs {
        modelRev.intValue                                                           // recompose on changes
        return ai.abhify.android.ai.ModelChoices.Inputs(aiMode.value, g.catalog.models, g.store.installed().filter { g.store.modelFile(it.id).exists() }.map { it.id }.toSet(),
            g.store.activeId(), onlineAllowed.value,
            Provider.entries.map { p -> val c = g.providerStore.config(p); ai.abhify.android.ai.ModelChoices.ProviderInfo(p, c.model, c.configured, g.providerStore.problem(p)) },
            onlineTargetSetting.value, g.cloud.session != null)
    }

    /**
     * Uses the picked model for the next messages. A phone model becomes the active model (persisted: it is also the
     * default for new chats), the previous one is unloaded first so only one model is ever in memory, and the new one is
     * loaded right away so a failure shows here instead of on the next send. Nothing is downloaded and nothing switches
     * to online AI by itself. Earlier answers keep the model label they were written with.
     */
    fun pickModel(key: String) = viewModelScope.launch {
        modelPickError.value = null
        if (generating.value) { modelPickError.value = "Wait for the current answer to finish (or stop it), then switch models."; return@launch }
        val choice = ai.abhify.android.ai.ModelChoices.build(modelInputs()).firstOrNull { it.key == key }
        if (choice == null || !choice.selectable) { modelPickError.value = choice?.reason ?: "That model is not available on this phone."; return@launch }
        when (choice.kind) {
            ai.abhify.android.ai.ModelChoices.Kind.AUTO -> setAiMode(AiMode.AUTO)
            ai.abhify.android.ai.ModelChoices.Kind.ONLINE -> { setOnlineTarget(key.removePrefix("online:")); setAiMode(AiMode.ONLINE) }
            ai.abhify.android.ai.ModelChoices.Kind.DEVICE -> {
                val id = key.removePrefix("device:")
                val changed = g.store.activeId() != id
                if (changed) {
                    val err = withContext(Dispatchers.IO) { runCatching { g.store.setActive(id) }.exceptionOrNull() }
                    if (err != null) { modelPickError.value = "${choice.title} is no longer installed. Open AI models to download it."; modelRev.intValue++; return@launch }
                    withContext(Dispatchers.IO) { g.mirrorModels() }
                    g.engine.unload(); engine.value = Engine.NotLoaded
                }
                setAiMode(AiMode.DEVICE)
                modelRev.intValue++
                if (changed) {
                    modelSwitching.value = choice.title
                    if (!readyDevice()) modelPickError.value = "${choice.title} could not start: ${(engine.value as? Engine.Error)?.message ?: "unknown error"}"
                    modelSwitching.value = null
                }
            }
        }
        modelRev.intValue++
    }

    internal suspend fun readyDevice(): Boolean {
        if (g.engine.loaded == null) engine.value = Engine.Loading
        val err = g.ensureModel()
        engine.value = if (err == null) Engine.Ready(g.backendLabel()) else Engine.Error(err)
        return err == null
    }

    // ------------------------------------------------------------------ chat
    /**
     * One chat bubble. [text] is always the complete text received (the UI may show a preview, never less in storage).
     * [status]: null = complete; Continuation.Status.* when the answer is partial. [continues]: Continue taps so far.
     */
    data class UiMsg(val dbId: Long?, val role: String, val text: String, val source: Source? = null, val note: String? = null, val streaming: Boolean = false,
                     val status: String? = null, val feedback: Int? = null, val continues: Int = 0,
                     /** Personal memories given to the model for this answer (shown on request). */
                     val memoriesUsed: List<String> = emptyList(),
                     /** "[S1] file.pdf, page 3" for answers grounded in selected documents. */
                     val sources: List<String> = emptyList(),
                     /** 6.2 live information: source chips ([W#]/[L#] label, url or null, detail) and the live state line. */
                     val links: List<LiveLink> = emptyList(), val live: String? = null)
    data class LiveLink(val label: String, val url: String?, val detail: String)

    val messages = mutableStateListOf<UiMsg>()
    val generating = mutableStateOf(false)
    val conversationId = mutableStateOf<Long?>(g.prefs.getLong("conversation", -1).takeIf { it > 0 })
    private var genJob: Job? = null

    init { conversationId.value?.let { loadConversation(it) } }

    /** Private chat: nothing of it is written to the database (no history, no sync, no new memories). Ends with the chat. */
    val privateChat = mutableStateOf(false)
    fun setPrivate(on: Boolean) { newChat(); privateChat.value = on }

    fun newChat() { stop(); removeAttachment(); privateChat.value = false; conversationId.value = null; g.prefs.edit().remove("conversation").apply(); messages.clear(); tab.value = Tab.CHAT; home() }

    fun loadConversation(id: Long) = viewModelScope.launch {
        val msgs = withContext(Dispatchers.IO) { g.db.messages(id) }
        conversationId.value = id; g.prefs.edit().putLong("conversation", id).apply()
        messages.clear(); messages.addAll(msgs.map { UiMsg(it.id, it.role, it.content, Source.decode(it.model), it.stats, status = it.status, feedback = it.feedback) })
    }

    private fun reloadAfterSync() { if (!generating.value) conversationId.value?.let { loadConversation(it) } }

    // ------------------------------------------------------------------ documents (extracted on the phone)
    /** The attached document stays attached for follow-up questions until removed or the chat changes. It isn't saved. */
    val attachment = mutableStateOf<ai.abhify.android.docs.DocText?>(null)
    val docBusy = mutableStateOf(false)
    val docError = mutableStateOf<String?>(null)
    /** Documents the user agreed to send to an online provider (by name+size, this session only). */
    private val docConsent = mutableSetOf<String>()
    /** A question waiting for the "send document online?" answer. */
    val consentAsk = mutableStateOf<String?>(null)
    private var docJob: kotlinx.coroutines.Job? = null
    private fun docKey(d: ai.abhify.android.docs.DocText) = d.name + ":" + d.chars

    /** Asks before text recognition reads scanned pages or an image (answer: true = read them, false = skip them). */
    data class OcrAsk(val name: String, val pages: Int, val retry: (Boolean) -> Unit)
    val ocrAsk = mutableStateOf<OcrAsk?>(null)
    fun answerOcr(allow: Boolean?, remember: Boolean) {
        val a = ocrAsk.value ?: return; ocrAsk.value = null
        if (remember && allow != null) g.ocrAllowed = allow
        if (allow != null) a.retry(allow)
    }
    val docProgress = mutableStateOf<String?>(null)

    fun attach(uri: android.net.Uri, ocr: Boolean? = null) {
        docJob?.cancel(); docError.value = null; docBusy.value = true
        docJob = viewModelScope.launch {
            try {
                attachment.value = withContext(Dispatchers.IO) { ai.abhify.android.docs.DocumentReader.read(g, uri, ocr) { docProgress.value = it }.doc }
            } catch (e: ai.abhify.android.docs.OcrNeeded) { ocrAsk.value = OcrAsk(e.name, e.pages) { allow -> attach(uri, allow) } }
            catch (e: ai.abhify.android.docs.DocumentException) { docError.value = e.message }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { docError.value = "Couldn't read the document: ${e.message}" }
            finally { docBusy.value = false; docProgress.value = null }
        }
    }
    fun cancelAttach() { docJob?.cancel(); docBusy.value = false }
    fun removeAttachment() { attachment.value = null; docError.value = null }

    /** Would the next answer leave the phone? (decides whether the document needs consent) */
    private fun nextIsOnline() = when (ai.abhify.android.ai.RoutePolicy.plan(routeInputs()).first()) {
        is ai.abhify.android.ai.Route.Personal, ai.abhify.android.ai.Route.Server -> true
        else -> false
    }
    fun confirmDocConsent(ok: Boolean) {
        val q = consentAsk.value ?: return
        consentAsk.value = null
        if (ok) { attachment.value?.let { docConsent += docKey(it) }; send(q) }
    }

    fun send(textIn: String) {
        val text = textIn.trim()
        if (text.isEmpty() || generating.value) return
        if (attachment.value == null) ai.abhify.android.chat.Identity.answer(text, g.assistantName)?.let { answerByApp(text, it); return }   // 7.1.0-dev.2
        attachment.value?.let { d -> if (nextIsOnline() && docKey(d) !in docConsent) { consentAsk.value = text; return } }
        if ((chatDocs.isNotEmpty() || chatWorkspace.value != null) && nextIsOnline() && contextKey() !in docConsent) {
            val what = listOfNotNull(if (chatDocs.isNotEmpty()) "the best-matching sections of ${chatDocs.size} selected document(s)" else null,
                chatWorkspace.value?.let { "the relevant notes, open tasks and linked documents of the workspace \"${it.name}\" (bounded)" }).joinToString(" and ")
            consent.value = Consent(if (chatWorkspace.value != null) "Send workspace context online?" else "Send document passages online?",
                "To answer, $what (up to about 24,000 characters) will be sent with your question to " +
                    "${onlineDestination() ?: "the online AI"}. \"This phone\" answers with the local model instead; Cancel sends nothing.", onlineDestination() ?: "online AI") { dec ->
                when (dec) {
                    Decision.ONLINE -> { docConsent += contextKey(); send(text) }
                    Decision.DEVICE -> { messages += UiMsg(null, "user", text); answer(text, regenerate = false, force = ai.abhify.android.ai.Route.Device) }
                    Decision.CANCEL -> {}
                }
            }
            return
        }
        // Knowledge Chat online: only when allowed in the Knowledge Center, and then after this question (once per chat)
        if (knowledgeOnline.value && nextIsOnline() && knowledgeWanted(text, attachment.value != null) && contextKey(true) !in docConsent) {
            consent.value = Consent("Send knowledge passages online?",
                "To answer, the best-matching passages of your documents and saved knowledge (up to about 20,000 characters) will be sent with your question to " +
                    "${onlineDestination() ?: "the online AI"}. \"This phone\" answers with the local model instead; Cancel sends nothing.", onlineDestination() ?: "online AI") { dec ->
                when (dec) {
                    Decision.ONLINE -> { docConsent += contextKey(true); send(text) }
                    Decision.DEVICE -> { messages += UiMsg(null, "user", text); answer(text, regenerate = false, force = ai.abhify.android.ai.Route.Device) }
                    Decision.CANCEL -> {}
                }
            }
            return
        }
        tab.value = Tab.CHAT
        voice.stopSpeaking()
        val shownText = attachment.value?.let { "📎 ${it.name}\n\n$text" } ?: text
        messages += UiMsg(null, "user", shownText)
        answer(shownText, regenerate = false, question = text)
    }

    /** 7.1.0-dev.2: "what is your name?" answered by the app with the current name (no model), shown, saved and spoken like an answer. */
    private fun answerByApp(question: String, reply: String) {
        tab.value = Tab.CHAT
        voice.stopSpeaking()
        messages += UiMsg(null, "user", question)
        messages += UiMsg(null, "assistant", reply, note = "Answered by the app")
        val voiceTurn = voiceState.active.value && voiceState.awaitingAnswer
        if (!privateChat.value) viewModelScope.launch {
            val c = withContext(Dispatchers.IO) {
                val c = conversationId.value ?: g.db.newConversation(question.take(60))
                g.db.addMessage(c, "user", question); g.db.addMessage(c, "assistant", reply); c
            }
            conversationId.value = c; g.prefs.edit().putLong("conversation", c).apply()
        }
        if (voiceTurn) voiceState.onAnswerDone(reply, true) else if (g.readAloud) voice.speak(reply)
    }

    /** 6.2: re-asks the last question with fresh live information (the cache is bypassed once). */
    fun refreshLive() { if (generating.value) return; refreshLiveNext = true; regenerate() }
    private var refreshLiveNext = false

    /** 6.2 live-information settings (Settings → Live information). News uses the existing news search; weather is on when
     *  asked (only the city name goes to Open-Meteo); exchange rates stay off until the user turns them on. */
    fun realtimeSettings() = ai.abhify.android.intel.RealtimeEngine.Settings(g.prefs.getBoolean("rt_enabled", true),
        g.prefs.getBoolean("rt_weather", true), g.prefs.getBoolean("rt_fx", false), g.prefs.getString("rt_place", "").orEmpty(),
        g.prefs.getString("rt_home", "INR").orEmpty().ifBlank { "INR" })
    fun setRealtime(key: String, value: Any) { g.prefs.edit { when (value) { is Boolean -> putBoolean(key, value); else -> putString(key, value.toString().take(80)) } }
        realtimeVersion.intValue++ }
    val realtimeVersion = androidx.compose.runtime.mutableIntStateOf(0)
    val realtime by lazy { ai.abhify.android.intel.RealtimeEngine(http = ::realtimeHttp, news = { q ->
        g.newsCenter.search(q, newsCenter.prefs.value).articles.map { a -> ai.abhify.android.intel.RealtimeEngine.NewsItem(a.title, a.publisher, a.url, a.publishedAt, a.summary) } },
        local = realtimeLocal).also { e -> e.onPlaceLearned = { p -> setRealtime("rt_place", p) } } }
    private val realtimeHosts = setOf("geocoding-api.open-meteo.com", "api.open-meteo.com", "api.frankfurter.dev")
    /** Only the fixed provider hosts, https, short timeouts, at most 256 KB (never a URL taken from retrieved content). */
    private suspend fun realtimeHttp(url: String): String = withContext(Dispatchers.IO) {
        val u = java.net.URL(url)
        require(u.protocol == "https" && u.host in realtimeHosts) { "host not allowed" }
        val c = u.openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 6_000; c.readTimeout = 8_000; c.instanceFollowRedirects = false; c.useCaches = false
            c.setRequestProperty("User-Agent", "Abhify-Android (live information)")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { s -> val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n = 0
                while (out.size() < 256 * 1024 && s.read(buf).also { n = it } > 0) out.write(buf, 0, n); out.toString("UTF-8") }
        } finally { c.disconnect() }
    }
    /** The user's own records and this phone's status, read from the app's stores (never model guesses). */
    private val realtimeLocal = object : ai.abhify.android.intel.RealtimeEngine.Local {
        override fun projects() = runCatching { g.db.workspaces().filter { !projects.isArchived(it.id) }.take(6).map { w ->
            val st = projects.meta(w.id).status; val open = g.db.tasks(w.id).count { !it.done }
            w.name to listOfNotNull(st.current.ifBlank { null }?.let { "Current: $it" }, st.next.firstOrNull()?.let { "Next: $it" },
                st.issues.firstOrNull()?.let { "Issue: $it" }, "$open open to-do(s)",
                st.updatedAt.takeIf { it > 0 }?.let { "status updated ${ai.abhify.android.intel.ProjectBrief.age(System.currentTimeMillis() - it)} ago" } ?: "no status recorded")
                .joinToString(" · ") } }.getOrDefault(emptyList())
        override fun tasks() = runCatching { val now = System.currentTimeMillis()
            g.db.workspaces().flatMap { w -> g.db.tasks(w.id).filter { !it.done }.map { t -> t to w.name } }
                .sortedBy { it.first.dueAt ?: Long.MAX_VALUE }.take(10).map { (t, ws) -> t.title to ("in $ws" + (t.dueAt?.let { d ->
                    if (d < now) " · overdue" else " · due " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(d)) } ?: "")) } }
            .getOrDefault(emptyList())
        override fun system(): List<Pair<String, String>> {
            val am = g.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val free = g.context.filesDir.usableSpace
            return listOf("Memory (RAM)" to "%.1f GB free of %.1f GB%s".format(mi.availMem / 1e9, mi.totalMem / 1e9, if (mi.lowMemory) " (low)" else ""),
                "Storage" to "%.1f GB free".format(free / 1e9),
                "On-phone AI" to (activeModel()?.name?.let { "$it installed · ${g.backendLabel()}" } ?: "no local model installed"),
                "GPU / Ollama" to "GPU use isn't measured on the phone; Ollama runs in the desktop app on your PC, not on this phone")
        }
    }

    /** Re-asks the last question; the old answer is removed (and the removal syncs). */
    fun regenerate() {
        if (generating.value) return
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        val lastAnswer = messages.lastOrNull()?.takeIf { it.role == "assistant" }
        if (lastAnswer != null) {
            messages.removeAt(messages.lastIndex)
            lastAnswer.dbId?.let { id -> viewModelScope.launch(Dispatchers.IO) { g.db.deleteMessage(id) } }
        }
        answer(lastUser.text, regenerate = true)
    }

    /** [force]: one explicit route (e.g. "Ask Groq instead" after a local failure); null = the user's routing. */
    private fun answer(text: String, regenerate: Boolean, question: String = if (text.startsWith("📎 ")) text.substringAfter("\n\n") else text,
                       force: ai.abhify.android.ai.Route? = null) {
        generating.value = true
        genJob = viewModelScope.launch {
            val idx = messages.size
            messages += UiMsg(null, "assistant", "", streaming = true)
            var conv: Long = -1
            val sb = StringBuilder()
            var source: Source? = null; var stats: String? = null; var failure: String? = null; var notice: String? = null
            var fact: String? = null; var attempt: Source? = null; var finish: String? = null; var finished = false
            var memUsed: List<String> = emptyList(); var srcs: List<String> = emptyList()
            var links: List<LiveLink> = emptyList(); var liveLine: String? = null; var webLinks: List<LiveLink> = emptyList()
            var citeSources: List<ai.abhify.android.intel.Citations.Src> = emptyList()      // 7.0.1: this answer's source registry
            val refreshLive = refreshLiveNext.also { refreshLiveNext = false }
            val voiceTurn = voiceState.active.value && voiceState.awaitingAnswer
            try {
                val (c, history, memories, f) = if (privateChat.value) withContext(Dispatchers.IO) {
                    // private: the history is what is on screen; nothing is stored
                    Quad(-1L, messages.take(if (regenerate) idx - 1 else idx - 1).filter { it.text.isNotBlank() && !it.text.startsWith("⚠️") }
                        .map { PromptBuilder.Msg(it.role, it.text) }, if (g.memoriesEnabled) relevantMemories(question, recentContext(), mark = false) else emptyList(), null as String?)
                } else withContext(Dispatchers.IO) {
                    val c = conversationId.value ?: g.db.newConversation(text.take(60))
                    val cmd = if (regenerate || !g.memoriesEnabled) null else PromptBuilder.memoryCommand(question)
                    // "remember that …" is explicit, but anything that looks sensitive is not stored silently
                    val sens = cmd?.let { ai.abhify.android.memory.MemoryKit.sensitive(it) }.orEmpty()
                    val f = cmd?.takeIf { sens.isEmpty() }
                    if (f != null) g.db.addMemoryItem(f, "General", 2, "remember:$c")              // provenance: the chat it was said in
                    if (cmd != null && sens.isNotEmpty()) notice = "Not saved to memory: it looks like a ${sens.joinToString()}. Add it in Personal memory if you really want to."
                    val all = g.db.messages(c)
                    val history = (if (regenerate) all.dropLast(1) else all).map { PromptBuilder.Msg(it.role, it.content) }
                    if (!regenerate) g.db.addMessage(c, "user", text)
                    Quad(c, history, if (g.memoriesEnabled) relevantMemories(question, history.takeLast(4).joinToString("\n") { it.content.take(400) }) else emptyList(), f)
                }
                conv = c; fact = f
                if (c > 0) {
                    conversationId.value = c; g.prefs.edit().putLong("conversation", c).apply()
                    if (messages.getOrNull(idx - 1)?.role == "user" && !regenerate) refreshUserIds(c)
                }
                // an attached document: the whole text when it fits, else the most relevant sections (small budget on the phone's model)
                val d = attachment.value
                val consented = d != null && docKey(d) in docConsent
                fun doc(budget: Int) = d?.let { val e = ai.abhify.android.docs.Documents.excerpt(it.text, question, budget); PromptBuilder.Doc(it.name, e.text, e.whole) }
                // selected library documents (RAG): only the best-matching sections, numbered for citations
                // v4.0: a workspace chosen as context adds its documents (imported or linked) to retrieval and its relevant notes/open tasks (bounded)
                val ws = chatWorkspace.value
                // Knowledge Chat: all documents + saved Knowledge items, when nothing more specific was chosen and the mode asks for it
                val knowledge = knowledgeWanted(question, d != null)
                // 5.6 My Friendly AI: a follow-up ("why?", "and the second one?") retrieves for the active topic, not just its own words
                val rq = ai.abhify.android.chat.Companion.retrievalQuery(question, history)
                val docIds = (chatDocs.toList() + (ws?.let { w -> withContext(Dispatchers.IO) { g.db.workspaceDocIds(w.id) } } ?: emptyList()) +
                    (if (knowledge) withContext(Dispatchers.IO) { g.db.allDocs().map { it.id } } else emptyList())).distinct()
                // 5.1 Personal Intelligence: the project's recorded status (never inferred); 6.0: focus comes from the Brain plan below
                val wsParts = ws?.let { w -> withContext(Dispatchers.IO) {
                    val tasks = g.db.tasks(w.id)
                    listOfNotNull(projectBriefPart(w, tasks)) + ai.abhify.android.workspace.ContextOrchestrator.workspaceParts(w.name, g.db.notes(w.id), tasks, question) } } ?: emptyList()
                // Auto Intelligence: a question naming one of your projects gets that project's recorded status, on this phone only
                val mentioned = if (ws != null || knowledgeMode.value == ai.abhify.android.docs.KnowledgeChat.Mode.NORMAL) null else withContext(Dispatchers.IO) {
                    ai.abhify.android.intel.ProjectBrief.mentioned(question, g.db.workspaces().filter { !projects.isArchived(it.id) }.map { it to it.name })
                        ?.let { w -> projectBriefPart(w, g.db.tasks(w.id)) } }
                // 5.5 Personal AI context finder in chat: for questions about your own material (Knowledge Auto rules) or a named
                // project, the relevant records (projects, to-dos, notes, saved knowledge/news, AI tasks) with freshness labels.
                // Memories and documents keep their own paths (MemoryKit, RAG). Local prompt only; listed under the answer.
                // 5.6: also plain chat questions about your own records ("my notes on …"); a relevance floor keeps loose matches out.
                // A workspace chat already carries its project's context, so it isn't repeated there.
                // 6.0 Personal AI Brain: one plan for this turn — the mode's real behaviour, intent, which personal context is
                // retrieved, whether recent web evidence is gathered (Research, online allowed), and honest notices
                // (app actions are matched only from the user's spoken words in VoiceState — never here)
                val brainPlan = ai.abhify.android.intel.Brain.plan(question, history, focus.value, knowledge, mentioned != null, ws != null,
                    g.onlineAllowed && online.value)
                val detection = brainPlan.detection
                val personal = if (!brainPlan.contextFinder) emptyList() else withContext(Dispatchers.IO) { runCatching {
                    brain.resolve(rq, ai.abhify.android.intel.ContextResolver.chatMinShared(question)).items.filter { it.record.source in CHAT_CONTEXT_SOURCES }.take(5) }.getOrDefault(emptyList()) }
                val personalPart = personal.takeIf { it.isNotEmpty() }?.let { ai.abhify.android.workspace.ContextOrchestrator.Part(
                    "your records (context finder: sources with dates; use only if relevant)", ai.abhify.android.intel.ContextResolver.block(it, 900)) }
                val wsLocal = ai.abhify.android.workspace.ContextOrchestrator.build(listOfNotNull(mentioned, personalPart) + wsParts, if (personalPart != null) 1800 else 1200)
                val wsOnline = ai.abhify.android.workspace.ContextOrchestrator.build(wsParts, 4000)
                // 6.0 Research: recent public sources from the existing news search (the question goes to Google News only in Research
                // mode with online access on); dated, cited as [W#], the same evidence for the phone's and the online model
                val webItems = if (!brainPlan.web) emptyList() else withContext(Dispatchers.IO) { runCatching {
                    g.newsCenter.search(rq.take(120), newsCenter.prefs.value).articles.take(6) }.getOrDefault(emptyList()) }
                val webNow = System.currentTimeMillis()
                fun webCtx(max: Int) = webItems.takeIf { it.isNotEmpty() }?.let { ai.abhify.android.intel.Brain.EVIDENCE_RULES + "\n" +
                    ai.abhify.android.intel.Brain.evidenceBlock(it.map { a -> ai.abhify.android.intel.Brain.Evidence(a.title, a.publisher, a.url, a.publishedAt, a.summary) }, webNow).take(max) }
                if (brainPlan.web && webItems.isEmpty()) notice = listOfNotNull(notice, "🌐 No recent sources found for this; the answer may be out of date.").joinToString(" · ")
                // 6.2 Real-Time Intelligence: the live categories this question needs (same for typed and spoken questions), retrieved
                // through approved connectors whatever model answers; Research's own news evidence is kept and numbered first
                val liveNeeds = ai.abhify.android.intel.Realtime.resolve(question, brainPlan.detection.focus == ai.abhify.android.intel.Intelligence.Focus.RESEARCH)
                val live = if (liveNeeds.isEmpty()) null else {
                    messages[idx] = UiMsg(null, "assistant", "", streaming = true, live = if (liveNeeds.any { it.external }) "🌐 Checking current information…" else "🗂 Reading your records…")
                    searching.value = true
                    try { realtime.gather(question, liveNeeds, realtimeSettings(), online.value, refreshLive, skipNews = brainPlan.web, webOffset = webItems.size) }
                    finally { searching.value = false }
                }
                val liveExternal = live?.external.orEmpty()
                // 7.1.0: current information was needed but none could be retrieved: the app answers honestly and the
                // model is not asked (an offline model would invent today's weather or news from old knowledge)
                val externalNeeded = liveNeeds.filter { it.external }
                if (externalNeeded.isNotEmpty() && liveExternal.isEmpty() && webItems.isEmpty()) {
                    sb.append(ai.abhify.android.intel.Realtime.unavailableReply(externalNeeded, live?.notes.orEmpty(), online.value))
                    source = Source(Source.Kind.DEVICE, "Abhify (live information unavailable)"); finished = true
                    liveLine = "⚠ Current information unavailable"
                    ai.abhify.android.device.VoiceLog.add("live", "unavailable: ${externalNeeded.joinToString { it.name }}")
                    return@launch
                }
                citeSources = webItems.mapIndexed { i, a -> ai.abhify.android.intel.Citations.Src("W${i + 1}", a.title, a.publisher, ai.abhify.android.intel.Realtime.validUrl(a.url), a.summary,
                        a.publishedAt.takeIf { it > 0 }, webNow) } +
                    live?.items.orEmpty().map { e -> ai.abhify.android.intel.Citations.Src(e.id, e.title, e.publisher, e.url, e.snippet, e.publishedAt, e.retrievedAt) }
                webLinks = webItems.mapIndexed { i, a -> LiveLink("[W${i + 1}] ${a.publisher}", ai.abhify.android.intel.Realtime.validUrl(a.url), a.title + " · " +
                    (if (a.publishedAt > 0) "published ${ai.abhify.android.intel.Realtime.age(System.currentTimeMillis() - a.publishedAt)} ago" else "date not given") + " · Research") }
                brainPlan.notices.filter { n -> !(liveExternal.isNotEmpty() && n.startsWith("🌐 This may need current information")) }
                    .forEach { n -> notice = listOfNotNull(notice, n).joinToString(" · ") }
                if (live != null) {
                    liveLine = when (live.state) { "verified" -> "✓ Sources checked"; "cached" -> "🕘 Using cached information"; "partial" -> "◐ Some live information found"
                        else -> "⚠ Live information unavailable" } + live.missing.takeIf { it.isNotEmpty() }?.let { m -> " · couldn't check: " + m.joinToString { it.label } }.orEmpty() +
                        (if (live.items.any { it.sourceId == "google-news" }) " · topic searched on Google News" else "")   // disclosed: what left the phone
                    links = live.items.map { e -> LiveLink("[${e.id}] ${e.publisher}", e.url, e.title + " · " + (e.publishedAt?.let { "published ${ai.abhify.android.intel.Realtime.age(System.currentTimeMillis() - it)} ago" }
                        ?: "date not given") + " · " + if (e.freshness == ai.abhify.android.intel.Realtime.Freshness.CACHED) "cached" else "retrieved just now") }
                }
                ai.abhify.android.intel.Brain.routeAdvice(brainPlan.detection.focus, !nextIsOnline(), g.providerStore.priority.any(g.providerStore::usable), g.onlineAllowed)
                    ?.let { a -> notice = listOfNotNull(notice, a).joinToString(" · ") }
                val t0 = System.currentTimeMillis()
                searching.value = docIds.isNotEmpty() || knowledge                            // 5.3 neural core: SEARCHING while retrieval runs
                val docHits = try { if (docIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                    ai.abhify.android.docs.RagIndex.retrieve(g.db, docIds, rq, 8, g.embedder(), ai.abhify.android.docs.EmbeddingManager.MIN_COSINE) } }
                    finally { searching.value = false }
                val itemHits = if (!knowledge) emptyList() else ai.abhify.android.docs.Library.search(
                    ai.abhify.android.docs.KnowledgeChat.itemChunks(life.vault.map { Triple(it.id, it.title, it.body) }), rq, 2)
                val hits = ai.abhify.android.docs.KnowledgeChat.merge(docHits, itemHits, 8)
                android.util.Log.i("AbhifyRag", "retrieval ${System.currentTimeMillis() - t0} ms: ${hits.size} passages from ${docIds.size} documents (semantic=${g.embeddings.let { it.installed() && g.semanticEnabled }})")
                if (knowledge && hits.isEmpty()) notice = ai.abhify.android.docs.KnowledgeChat.NOTHING_FOUND
                val useRag = docIds.isNotEmpty() || knowledge
                val localCtx = ai.abhify.android.docs.Library.context(hits, maxOf(600, g.memoryProfile().first.localDocChars - wsLocal.text.length))
                val onlineCtx = ai.abhify.android.docs.Library.context(hits, 20_000)
                // knowledge goes online only when allowed in the Knowledge Center, and then after consent (as documents always did)
                val docsConsented = contextKey(knowledge) in docConsent && (!knowledge || g.prefs.getBoolean("knowledge_online", false))
                fun ragDoc(c: ai.abhify.android.docs.Library.Context, w: ai.abhify.android.workspace.ContextOrchestrator.Built) =
                    if (!useRag && w.text.isBlank()) null else PromptBuilder.Doc(if (ws != null) "your workspace \"${ws.name}\"" else if (knowledge) "your knowledge (documents and saved items)" else "your selected documents",
                        w.text.let { if (it.isBlank()) "" else it + "\n\n" } + (if (!useRag) "" else
                            ai.abhify.android.docs.Library.CITE + "\n\n" + c.text.ifBlank { if (knowledge) "(Nothing in the user's knowledge matches this question. Say that " +
                                "their documents and saved knowledge don't contain it, then answer from general knowledge only if that is clearly marked.)"
                                else "(No section of the selected documents matches this question.)" }), false)
                // a project (workspace) chat: its instructions are added; personal memories only if the project allows them
                val project = ws?.let { projects.meta(it.id) }
                val mems = if (project?.useMemory == false) emptyList() else memories
                // 5.4: a turn of a voice conversation is answered in spoken style, in the chosen tone and reply language
                val custom = listOf(g.effectiveInstructions(), ai.abhify.android.intel.Intelligence.instructions(detection, responsePrefs.value),
                    if (voiceTurn) voiceSettings.value.conversationInstruction() else "",
                    ws?.let { ai.abhify.android.workspace.ProjectStore.instructionBlock(it.name, project!!.instructions) }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                ai.abhify.android.intel.Intelligence.trace(detection)?.let { t -> notice = listOfNotNull(t, notice).joinToString(" · ") }
                memUsed = mems
                // 5.6 companion layer: personality, earlier-turn summary, follow-up context (online: only with chat history allowed)
                val win = brainPlan.profile.historyMessages
                val stale = if (brainPlan.intent == ai.abhify.android.intel.Brain.Intent.FRESH_INFO && webItems.isEmpty() && liveExternal.isEmpty()) ai.abhify.android.intel.Brain.STALE_WARNING else ""
                val liveNow = System.currentTimeMillis()
                fun evidenceCtx(max: Int, online: Boolean): String? = listOfNotNull(webCtx(max),
                    live?.let { r -> if (online) ai.abhify.android.intel.Realtime.assemble(r.copy(items = r.external), liveNow, max) +
                        (if (r.local.isNotEmpty()) "(The user's own records and phone status were used on the phone only and are not shown here.)" else "")
                        else ai.abhify.android.intel.Realtime.assemble(r, liveNow, max) }?.ifBlank { null }).joinToString("\n\n").ifBlank { null }
                val companionLocal = listOf(ai.abhify.android.chat.Companion.turn(question, history, win), stale).filter { it.isNotBlank() }.joinToString("\n")
                val companionOnline = listOf(ai.abhify.android.chat.Companion.turn(question, if (g.prefs.getBoolean("online_history", ai.abhify.android.settings.PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL)) history else emptyList(), win), stale)
                    .filter { it.isNotBlank() }.joinToString("\n")
                val local = PromptBuilder.buildNamed(g.assistantName, question, history, mems, g.userName, custom = listOf(custom, companionLocal).filter { it.isNotBlank() }.joinToString("\n\n"),
                    doc = withWeb(ragDoc(localCtx, wsLocal) ?: doc(g.memoryProfile().first.localDocChars), evidenceCtx(900, online = false)), historyMessages = win)
                // what may leave the phone: recent chat only if allowed, memories only if allowed, documents only after consent
                val onlinePrompt = PromptBuilder.buildNamed(g.assistantName, question, if (g.prefs.getBoolean("online_history", ai.abhify.android.settings.PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL)) history else emptyList(),
                    if (g.shareMemoriesOnline) mems else emptyList(), g.userName, onDevice = false,
                    custom = listOf(custom, companionOnline).filter { it.isNotBlank() }.joinToString("\n\n"),
                    // private material only after consent (unchanged); the public web evidence is added on top
                    doc = withWeb(if (useRag || wsParts.isNotEmpty()) (if (docsConsented) ragDoc(onlineCtx, wsOnline) else null) else if (consented) doc(24_000) else null, evidenceCtx(2400, online = true)),
                    historyMessages = win)
                if (knowledge && nextIsOnline() && !docsConsented && force != ai.abhify.android.ai.Route.Device)
                    notice = listOfNotNull(notice, "🔒 Your knowledge stayed on this phone (online answers can't use it: Knowledge Center)").joinToString(" · ")
                // 6.0: the mode's settings for the phone's model (length, creativity); which AI answers is unchanged
                g.hybrid.tuneNext(ai.abhify.android.ai.HybridAi.Tuning(brainPlan.profile.deviceMaxTokens, brainPlan.profile.temperature))
                ai.abhify.android.device.VoiceLog.add("model", "generate (route ${if (force != null) force.javaClass.simpleName else "auto"})")
                val flow = if (force != null) g.hybrid.generateOn(force, local, onlinePrompt) else g.hybrid.generate(local, onlinePrompt, routeInputs())
                flow.collect { e ->
                    when (e) {
                        is ChatEvent.Attempt -> { attempt = e.source; messages[idx] = UiMsg(null, "assistant", sb.toString(), e.source, notice, streaming = true, live = liveLine) }
                        is ChatEvent.Token -> { sb.append(e.text); messages[idx] = UiMsg(null, "assistant", sb.toString(), attempt, notice, streaming = true, live = liveLine)
                            if (voiceTurn) voiceState.onPartialAnswer(sb.toString()) }                 // 5.6: speak finished sentences while generating
                        is ChatEvent.Notice -> { notice = e.text; messages[idx] = UiMsg(null, "assistant", sb.toString(), null, notice, streaming = true) }
                        is ChatEvent.Done -> { source = e.source; finish = e.finish; finished = true
                            if (useRag && (!e.source.cloud || docsConsented)) srcs = (if (e.source.cloud) onlineCtx else localCtx).sources.mapIndexed { i, c -> "[S${i + 1}] ${c.where}" }
                            srcs = srcs + (if (e.source.cloud) wsOnline else wsLocal).parts.filter { !it.first.startsWith("your records") }.map { "📝 ${it.first}" } +
                                (if (e.source.cloud) emptyList() else personal.map { "🧭 ${it.record.source.label}: ${it.record.title.take(50)} (${it.freshness.label})" }) +
                                webItems.mapIndexed { i, a -> "🌐 [W${i + 1}] ${a.publisher}: ${a.title.take(60)}" + (if (a.publishedAt > 0) " (${ai.abhify.android.intel.ProjectBrief.age(webNow - a.publishedAt)} ago)" else "") }
                            if (e.source.cloud) links = links.filter { it.label.startsWith("[W") }       // own records weren't sent online
                            if (e.source.cloud && !g.shareMemoriesOnline) memUsed = emptyList()
                            stats = e.tokensPerSecond?.let { "%.1f tokens/s".format(it) }; if (!e.source.cloud) engine.value = Engine.Ready(g.backendLabel())
                            e.tokensPerSecond?.let { tps -> lastSpeed.value = LastSpeed(e.source.label, tps, e.source.cloud) } }
                        is ChatEvent.Failed -> { failure = e.message; source = e.source }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                ai.abhify.android.device.VoiceLog.add("answer", "failed: ${e.javaClass.simpleName}")
                // 7.1.1: any unexpected failure while preparing or receiving the answer is shown as a message, never a crash
                android.util.Log.w("AbhifyChat", "answer failed: ${e.javaClass.simpleName}")
                failure = "The answer couldn't be completed (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}). Try again."
            } finally {
                // the complete text received, whatever happened: nothing is dropped or shortened before storing, except that
                // 7.0.1 citation integrity keeps only citations backed by this answer's sources (and removes invented links)
                val raw = sb.toString().trim()
                // 7.1.0: this runs in `finally`, outside the catch above: it must never throw (an Android-only regex error here crashed every answer)
                val (checked, cite) = runCatching { if (citeSources.isNotEmpty() || ai.abhify.android.intel.Citations.MARK.containsMatchIn(raw))
                    ai.abhify.android.intel.Citations.check(raw, citeSources) else raw to null }.getOrElse { raw to null }
                cite?.summary()?.let { s -> notice = listOfNotNull(notice, s).joinToString(" · ") }
                val (answer, unverified) = runCatching { if (citeSources.isEmpty()) ai.abhify.android.intel.Citations.labelUnverified(checked) else checked to emptyList() }
                    .getOrElse { checked to emptyList() }
                if (unverified.isNotEmpty()) notice = listOfNotNull(notice, "🔎 ${unverified.size} source link${if (unverified.size > 1) "s" else ""} from the model's memory marked unverified").joinToString(" · ")
                val status = ai.abhify.android.chat.Continuation.statusOf(answer.isEmpty(), failure != null, finished, finish)
                val by = source ?: attempt
                val shown = answer.ifEmpty { failure?.let { "⚠️ $it" } ?: "…" }
                val note = listOfNotNull(notice, "🧠 Saved to memory".takeIf { fact != null }, stats,
                    failure?.takeIf { answer.isNotEmpty() }?.let { "⚠️ $it" }).joinToString(" · ").ifEmpty { null }
                var id: Long? = null
                if (conv > 0 && answer.isNotEmpty()) withContext(Dispatchers.IO + NonCancellable) {
                    id = g.db.addMessage(conv, "assistant", answer, note, by?.encode(), status)
                }
                messages[idx] = UiMsg(id, "assistant", shown, by, note, status = status, memoriesUsed = if (answer.isEmpty()) emptyList() else memUsed, sources = srcs,
                    links = links + webLinks, live = liveLine)
                if (voiceTurn) voiceState.onAnswerDone(answer, status == null && failure == null && source != null)     // conversation: speak, then listen
                else if (status == null && failure == null && source != null && g.readAloud) voice.speak(answer)
                generating.value = false
                if (!voiceState.active.value && !speaking.value) resumeWakeSoon()
                g.localChanged(); refreshPending()
            }
        }
    }

    fun routeInputs() = RouteInputs(aiMode.value, online.value, g.onlineAllowed, g.cloud.session != null, g.store.active() != null,
        g.providerStore.priority.filter(g.providerStore::usable), g.onlineTarget(), g.fallbackToDevice, g.fallbackProviders,
        Provider.entries.mapNotNull { p -> g.providerStore.problem(p)?.let { p to it } }.toMap())

    /** The separate states the home screen shows (mode, provider, account, local model). */
    data class States(val mode: String, val provider: String, val providerLevel: Int, val account: String, val local: String, val localOk: Boolean)
    fun states(): States {
        val sel = g.onlineTarget()
        val (prov, lvl) = when (sel) {
            null -> (if (g.providerStore.anyConfigured()) "No provider selected" else "No provider configured") to 2
            OnlineTarget.Shared -> "Shared access" to if (g.cloud.session != null) 0 else 2
            is OnlineTarget.Personal -> {
                val c = g.providerStore.config(sel.provider)
                val problem = g.providerStore.problem(sel.provider)
                when {
                    problem != null -> "${sel.provider.title}: $problem" to 2
                    c.lastError != null && (c.lastOkAt == null) -> "${sel.provider.title} · last test failed" to 1
                    else -> "${sel.provider.title} · ${c.model}" to 0
                }
            }
        }
        val acct = when (val a = account.value) { is Account.SignedIn -> "Abhify account: ${a.session.email}"; is Account.Expired -> "Abhify account: sign-in expired"; else -> "No Abhify account (optional)" }
        val model = activeModel()
        return States(aiMode.value.label, prov, lvl, acct, model?.name ?: "No local model", model != null)
    }

    /** Edit the last question and ask again: the old question and its answer are removed (removals sync). */
    // ------------------------------------------------------------------ v4.4 personalization
    val creativity = mutableStateOf(ai.abhify.android.settings.Personalization.Creativity.of(g.prefs.getString("creativity", null)))
    fun setCreativity(c: ai.abhify.android.settings.Personalization.Creativity) { g.prefs.edit().putString("creativity", c.name).apply(); creativity.value = c }
    val textSize = mutableStateOf(ai.abhify.android.settings.Personalization.TextSize.of(g.prefs.getString("text_size", null)))
    fun setTextSize(t: ai.abhify.android.settings.Personalization.TextSize) { g.prefs.edit().putString("text_size", t.name).apply(); textSize.value = t }

    // ------------------------------------------------------------------ v4.4 prompt library
    val prompts = mutableStateListOf<ai.abhify.android.chat.SavedPrompt>()
    /** The chat's current draft (mirrored by the composer) and text to put into it (from a saved prompt). */
    val draft = mutableStateOf("")
    val pendingInput = mutableStateOf<String?>(null)
    fun loadPrompts() = viewModelScope.launch { val l = withContext(Dispatchers.IO) { g.db.savedPrompts() }; prompts.clear(); prompts.addAll(l) }
    fun savePrompt(id: Long?, title: String, body: String) = viewModelScope.launch {
        if (title.isBlank() || body.isBlank()) return@launch
        withContext(Dispatchers.IO) { g.db.savePrompt(id, title, body) }; loadPrompts()
    }
    fun deletePrompt(p: ai.abhify.android.chat.SavedPrompt) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.deletePrompt(p.id) }; loadPrompts() }
    fun usePrompt(p: ai.abhify.android.chat.SavedPrompt) { pendingInput.value = p.body; viewModelScope.launch(Dispatchers.IO) { g.db.promptUsed(p.id) } }

    /**
     * v4.4: edit an EARLIER question without touching the original chat: the messages before it are copied into a
     * new conversation ("… (edited)") and the edited question is sent there. (The last question keeps the in-place edit.)
     */
    fun editAsBranch(index: Int, newText: String) {
        if (generating.value || newText.isBlank() || privateChat.value || index !in messages.indices) return
        val before = messages.take(index).filter { it.text.isNotBlank() && !it.streaming }
        val from = conversationId.value
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                val title = (from?.let { g.db.conversationTitle(it) } ?: newText.take(50)).take(70)
                val c = g.db.newConversation("$title (edited)")
                before.forEach { m -> g.db.addMessage(c, m.role, m.text, m.note, m.source?.encode(), m.status) }
                c
            }
            loadConversation(id).join()
            send(newText)
            studio.message.value = "Sent in a new chat (\"… (edited)\"). The original chat is unchanged."
        }
    }

    fun editAndResend(newText: String) {
        if (generating.value || newText.isBlank()) return
        val i = messages.indexOfLast { it.role == "user" }
        if (i < 0) return
        val removed = messages.subList(i, messages.size).toList()
        repeat(messages.size - i) { messages.removeAt(messages.lastIndex) }
        viewModelScope.launch(Dispatchers.IO) { removed.mapNotNull { it.dbId }.forEach(g.db::deleteMessage) }
        send(newText)
    }

    private suspend fun refreshUserIds(conv: Long) {
        val rows = withContext(Dispatchers.IO) { g.db.messages(conv) }
        val lastUser = rows.lastOrNull { it.role == "user" } ?: return
        val i = messages.indexOfLast { it.role == "user" && it.dbId == null }
        if (i >= 0) messages[i] = messages[i].copy(dbId = lastUser.id)
    }

    fun stop() { genJob?.cancel(); g.engine.cancel() }

    // ------------------------------------------------------------------ v3.4: memory retrieval, documents in chat, explicit cloud alternative
    /**
     * Only memories relevant to this question (never the whole memory), ranked with the last turns of this chat as
     * weaker context; see MemoryKit.relevant. [mark] records the use (not in private chats: nothing is stored there).
     */
    private fun relevantMemories(question: String, context: String = "", mark: Boolean = true): List<String> {
        val picked = ai.abhify.android.memory.MemoryKit.relevant(question, g.db.memoryItems(), context = context)
        if (mark) runCatching { g.db.markMemoriesUsed(picked.map { it.item.id }) }
        return picked.map { it.item.content }
    }
    private fun recentContext(): String = messages.takeLast(5).dropLast(1).joinToString("\n") { it.text.take(400) }

    /** Library documents chosen as context for this chat (empty = none; never the whole library implicitly). */
    val chatDocs = mutableStateListOf<Long>()
    private fun docsKey(ids: Collection<Long>) = "docs:" + ids.sorted().joinToString(",")
    /** v4.0: a workspace chosen as chat context (its notes, open tasks and documents; bounded). Null = none. */
    val chatWorkspace = mutableStateOf<ai.abhify.android.chat.Workspace?>(null)
    /** What an online consent covers: these documents and this workspace (changing either asks again). */
    private fun contextKey(knowledge: Boolean = false) = docsKey(chatDocs) + (chatWorkspace.value?.let { "|ws:${it.id}" } ?: "") + (if (knowledge) "|knowledge" else "")

    // ------------------------------------------------------------------ Knowledge Chat (local RAG over all your knowledge)
    val knowledgeMode = mutableStateOf(runCatching { ai.abhify.android.docs.KnowledgeChat.Mode.valueOf(g.prefs.getString("knowledge_mode", "AUTO")!!) }
        .getOrDefault(ai.abhify.android.docs.KnowledgeChat.Mode.AUTO))
    fun setKnowledgeMode(m: ai.abhify.android.docs.KnowledgeChat.Mode) { knowledgeMode.value = m; g.prefs.edit().putString("knowledge_mode", m.name).apply() }
    val knowledgeOnline = mutableStateOf(g.prefs.getBoolean("knowledge_online", false))
    fun setKnowledgeOnline(on: Boolean) { knowledgeOnline.value = on; g.prefs.edit().putBoolean("knowledge_online", on).apply(); if (!on) docConsent.removeAll { it.endsWith("|knowledge") } }
    /** Should this question search all your knowledge? Only when nothing more specific (ticked documents, a project, a file) is in use. */
    private fun knowledgeWanted(question: String, hasAttachment: Boolean) = chatWorkspace.value == null && chatDocs.isEmpty() && !hasAttachment &&
        (ai.abhify.android.docs.KnowledgeChat.wants(knowledgeMode.value, question, studio.docs.map { it.name }) ||
            // 5.1 Research focus looks in your own knowledge too (unless Knowledge Chat is Normal)
            (knowledgeMode.value != ai.abhify.android.docs.KnowledgeChat.Mode.NORMAL && studio.docs.isNotEmpty() &&
                ai.abhify.android.intel.Intelligence.detect(question, focus.value).focus == ai.abhify.android.intel.Intelligence.Focus.RESEARCH))

    // ------------------------------------------------------------------ 5.1 Personal Intelligence: focus, response preferences, routing transparency
    /** 5.5: what the context finder may add to chat (memories and documents have their own tested paths). */
    /** 6.0: public web evidence (Research) in front of whatever document context the prompt already has. */
    private fun withWeb(d: PromptBuilder.Doc?, web: String?): PromptBuilder.Doc? = when {
        web == null -> d
        d == null -> PromptBuilder.Doc("recent web evidence", web, false)
        else -> d.copy(text = web + "\n\n" + d.text)
    }
    private val CHAT_CONTEXT_SOURCES = setOf(ai.abhify.android.intel.ContextResolver.Source.PROJECT, ai.abhify.android.intel.ContextResolver.Source.TASK,
        ai.abhify.android.intel.ContextResolver.Source.NOTE, ai.abhify.android.intel.ContextResolver.Source.KNOWLEDGE,
        ai.abhify.android.intel.ContextResolver.Source.NEWS, ai.abhify.android.intel.ContextResolver.Source.AI_TASK)
    /** True only while retrieval over your documents/knowledge runs (drives the neural core's SEARCHING state). */
    val searching = mutableStateOf(false)
    val focus = mutableStateOf(runCatching { ai.abhify.android.intel.Intelligence.Focus.valueOf(g.prefs.getString("chat_focus", "AUTO")!!) }
        .getOrDefault(ai.abhify.android.intel.Intelligence.Focus.AUTO))
    fun setFocus(f: ai.abhify.android.intel.Intelligence.Focus) { focus.value = f; g.prefs.edit().putString("chat_focus", f.name).apply() }
    val responsePrefs = mutableStateOf(ai.abhify.android.intel.Intelligence.Prefs(
        runCatching { ai.abhify.android.intel.Intelligence.Detail.valueOf(g.prefs.getString("resp_detail", "AUTO")!!) }.getOrDefault(ai.abhify.android.intel.Intelligence.Detail.AUTO),
        runCatching { ai.abhify.android.intel.Intelligence.Depth.valueOf(g.prefs.getString("resp_depth", "AUTO")!!) }.getOrDefault(ai.abhify.android.intel.Intelligence.Depth.AUTO),
        runCatching { ai.abhify.android.intel.Intelligence.Style.valueOf(g.prefs.getString("resp_style", "AUTO")!!) }.getOrDefault(ai.abhify.android.intel.Intelligence.Style.AUTO),
        g.prefs.getString("resp_language", "").orEmpty()))
    fun setResponsePrefs(p: ai.abhify.android.intel.Intelligence.Prefs) {
        val clean = p.copy(language = ai.abhify.android.intel.Intelligence.cleanLanguage(p.language).orEmpty())
        responsePrefs.value = clean
        g.prefs.edit().putString("resp_detail", clean.detail.name).putString("resp_depth", clean.depth.name).putString("resp_style", clean.style.name)
            .putString("resp_language", clean.language).apply()
    }
    /** Where the next answer goes and what goes with it (shown in chat; reads the unchanged routing rules). */
    fun routeExplanation() = ai.abhify.android.intel.RouteExplain.explain(routeInputs(), focus.value, g.shareMemoriesOnline && g.memoriesEnabled,
        knowledgeOnline.value, g.prefs.getBoolean("online_history", ai.abhify.android.settings.PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL))
    /** A project's recorded status as a context part (null when nothing was recorded). */
    private fun projectBriefPart(w: ai.abhify.android.chat.Workspace, tasks: List<ai.abhify.android.chat.Task>): ai.abhify.android.workspace.ContextOrchestrator.Part? {
        val now = System.currentTimeMillis(); val open = tasks.filter { !it.done }
        return ai.abhify.android.intel.ProjectBrief.summary(ai.abhify.android.intel.ProjectBrief.Facts(w.name, projects.meta(w.id).status, open.map { it.title },
            open.count { it.dueAt != null && it.dueAt < now }, 0), now, 900)?.let { ai.abhify.android.workspace.ContextOrchestrator.Part("status of project \"${w.name}\"",
                ai.abhify.android.intel.ProjectBrief.forPrompt(it)) }
    }
    val hub by lazy { WorkspaceHub(this) }
    /** v4.0: saves an answer as a note in the chat's workspace (or the first one), on this phone only. */
    fun saveAnswerAsNote(i: Int) {
        val m = messages.getOrNull(i) ?: return
        val q = messages.getOrNull(i - 1)?.text?.take(80) ?: "AI answer"
        viewModelScope.launch {
            val ws = chatWorkspace.value ?: withContext(Dispatchers.IO) { g.db.workspaces().firstOrNull() } ?: return@launch
            withContext(Dispatchers.IO) { g.db.saveNote(null, ws.id, q, m.text) }
            studio.message.value = "Saved as a note in \"${ws.name}\" (Knowledge → Workspaces → Notes)."
        }
    }
    val onlineHistory = mutableStateOf(g.prefs.getBoolean("online_history", ai.abhify.android.settings.PrivacyDefaults.ONLINE_HISTORY_NEW_INSTALL))
    fun setOnlineHistory(on: Boolean) { g.prefs.edit().putBoolean("online_history", on).apply(); onlineHistory.value = on }

    /** The provider a failed local answer could be retried with (only when online AI is allowed and a key is ready). */
    fun onlineAlternative(): Provider? = if (!g.onlineAllowed || !online.value) null else
        (g.onlineTarget() as? OnlineTarget.Personal)?.provider?.takeIf { g.providerStore.usable(it) } ?: g.providerStore.priority.firstOrNull { g.providerStore.usable(it) }

    /** After the phone's model failed: ask before sending anything, then retry this question once with the provider. */
    fun askOnlineInstead() {
        val p = onlineAlternative() ?: return
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return
        val parts = listOfNotNull("your question", "recent messages of this chat".takeIf { onlineHistory.value }, "relevant personal memories".takeIf { g.shareMemoriesOnline },
            "matching document passages".takeIf { chatDocs.isNotEmpty() && contextKey() in docConsent },
            "workspace notes and tasks".takeIf { chatWorkspace.value != null && contextKey() in docConsent })
        consent.value = Consent("Ask ${p.title} instead?", "The phone's AI couldn't answer. This sends ${parts.joinToString(", ")} to ${p.title} " +
            "(model ${g.providerStore.config(p).model}) with this phone's own key. Nothing else is sent.", p.title) { d ->
            if (d != Decision.ONLINE) return@Consent
            messages.lastOrNull()?.takeIf { it.role == "assistant" && it.dbId == null }?.let { messages.removeAt(messages.lastIndex) }
            answer(lastUser.text, regenerate = true, force = ai.abhify.android.ai.Route.Personal(p))
        }
    }

    // ------------------------------------------------------------------ continue generation, feedback
    fun canContinue(m: UiMsg) = m.role == "assistant" && m.dbId != null && m.status in ai.abhify.android.chat.Continuation.PARTIAL &&
        m.continues < ai.abhify.android.chat.Continuation.MAX && m.source != null && !generating.value

    /** The route that wrote [s], if it can be used now (Continue never switches to another provider). */
    private fun routeFor(s: Source): ai.abhify.android.ai.Route? = when (s.kind) {
        Source.Kind.DEVICE -> ai.abhify.android.ai.Route.Device.takeIf { g.store.active() != null }
        Source.Kind.SERVER -> ai.abhify.android.ai.Route.Server.takeIf { g.cloud.session != null && online.value && g.onlineAllowed }
        else -> Provider.valueOf(s.kind.name).let { p -> ai.abhify.android.ai.Route.Personal(p).takeIf { g.providerStore.usable(p) && online.value && g.onlineAllowed } }
    }

    /** Extends the last partial answer with the same source; the text is appended to the same message and stored complete. */
    fun continueAnswer() {
        val idx = messages.indexOfLast { it.role == "assistant" }
        val m = messages.getOrNull(idx) ?: return
        if (idx != messages.lastIndex || !canContinue(m)) return
        val src = m.source!!
        val route = routeFor(src) ?: run {
            messages[idx] = m.copy(note = listOfNotNull(m.note, "Continue needs ${src.kind.label}, which isn't available now (offline, no key or no model). Use Retry instead.").joinToString(" · "))
            return
        }
        val conv = conversationId.value ?: return
        generating.value = true
        voice.stopSpeaking()
        genJob = viewModelScope.launch {
            val add = StringBuilder(); var finish: String? = null; var finished = false; var failure: String? = null; var stats: String? = null
            try {
                val rows = withContext(Dispatchers.IO) { g.db.messages(conv) }
                val history = rows.takeWhile { it.id != m.dbId }.map { PromptBuilder.Msg(it.role, it.content) }
                val memories = if (g.memoriesEnabled) withContext(Dispatchers.IO) { relevantMemories(history.lastOrNull { it.role == "user" }?.content.orEmpty()) } else emptyList()
                val instr = ai.abhify.android.chat.Continuation.INSTRUCTION
                val local = PromptBuilder.buildNamed(g.assistantName, instr, history + PromptBuilder.Msg("assistant", ai.abhify.android.chat.Continuation.tail(m.text, 1500)),
                    memories, g.userName, custom = g.effectiveInstructions())
                val onlinePrompt = PromptBuilder.buildNamed(g.assistantName, instr, history + PromptBuilder.Msg("assistant", m.text),
                    if (g.shareMemoriesOnline) memories else emptyList(), g.userName, onDevice = false, custom = g.effectiveInstructions())
                messages[idx] = m.copy(streaming = true)
                g.hybrid.generateOn(route, local, onlinePrompt).collect { e ->
                    when (e) {
                        is ChatEvent.Token -> { add.append(e.text); messages[idx] = m.copy(text = ai.abhify.android.chat.Continuation.join(m.text, add.toString()), streaming = true) }
                        is ChatEvent.Done -> { finish = e.finish; finished = true; stats = e.tokensPerSecond?.let { "%.1f tokens/s".format(it) } }
                        is ChatEvent.Failed -> failure = e.message
                        is ChatEvent.Notice, is ChatEvent.Attempt -> {}
                    }
                }
            } finally {
                val full = ai.abhify.android.chat.Continuation.join(m.text, add.toString().trimEnd())
                val status = if (add.isBlank()) m.status
                    else ai.abhify.android.chat.Continuation.statusOf(false, failure != null, finished, finish)
                val note = listOfNotNull(stats ?: m.note?.takeIf { !it.startsWith("⚠️") }, failure?.let { "⚠️ $it" }).joinToString(" · ").ifEmpty { null }
                if (add.isNotBlank()) withContext(Dispatchers.IO + NonCancellable) { g.db.updateAnswer(m.dbId!!, full, status, note, src.encode()) }
                messages[idx] = m.copy(text = full, status = status, note = note, streaming = false, continues = m.continues + 1)
                generating.value = false
                g.localChanged(); refreshPending()
            }
        }
    }

    /** 5.0: a correction to answer [i] becomes a pending knowledge update (Knowledge Center → approve / reject). */
    fun correctAnswer(i: Int, fix: String) {
        val question = messages.take(i).lastOrNull { it.role == "user" }?.text.orEmpty()
        knowledge.propose(fix, question, "Correction to an answer in chat ${conversationId.value ?: "(unsaved)"}")
        messages.getOrNull(i)?.let { m -> messages[i] = m.copy(note = listOfNotNull(m.note, "✎ Correction saved for review (More → Knowledge Center)").joinToString(" · ")) }
    }

    fun setFeedback(i: Int, value: Int?) {
        val m = messages.getOrNull(i) ?: return
        val id = m.dbId ?: return
        val v = if (m.feedback == value) null else value
        messages[i] = m.copy(feedback = v)
        viewModelScope.launch(Dispatchers.IO) { g.db.setFeedback(id, v) }
    }

    // ------------------------------------------------------------------ one-shot AI tasks (documents, learning, work)
    /** A question to the user before content leaves the phone: send to [destination], use this phone's AI, or cancel. */
    data class Consent(val title: String, val detail: String, val destination: String, val onDecision: (Decision) -> Unit)
    enum class Decision { ONLINE, DEVICE, CANCEL }
    val consent = mutableStateOf<Consent?>(null)
    fun decide(d: Decision) { val c = consent.value ?: return; consent.value = null; c.onDecision(d) }

    data class TaskResult(val text: String, val source: Source?, val status: String?, val error: String?)
    val taskBusy = mutableStateOf<String?>(null)                     // the running task's label
    private var taskJob: Job? = null
    fun cancelTask() { taskJob?.cancel(); g.engine.cancel(); taskBusy.value = null }

    /**
     * Runs one AI task through the user's routing. When the answer would go online and [sensitive] content (a
     * document, notes) is included, the user is asked first; "use this phone" runs it on the local model instead.
     */
    fun aiTask(label: String, system: String, user: String, sensitive: String?, maxDeviceChars: Int = 3500, userOnline: String = user,
               /** A multi-step task's earlier answer to the consent question (asked once per run, not once per step). */
               decided: Decision? = null, onDecided: (Decision) -> Unit = {}, onDone: (TaskResult) -> Unit) {
        if (taskBusy.value != null || generating.value) { onDone(TaskResult("", null, null, "The AI is busy with another answer.")); return }
        val first = ai.abhify.android.ai.RoutePolicy.plan(routeInputs()).first()
        fun start(forceDevice: Boolean) {
            taskBusy.value = label
            taskJob = viewModelScope.launch {
                val sb = StringBuilder(); var src: Source? = null; var finish: String? = null; var finished = false; var err: String? = null
                try {
                    val local = listOf(PromptBuilder.Msg("system", system), PromptBuilder.Msg("user", user.take(maxDeviceChars)))
                    val remote = listOf(PromptBuilder.Msg("system", system), PromptBuilder.Msg("user", userOnline))
                    val flow = if (forceDevice) g.hybrid.generateOn(ai.abhify.android.ai.Route.Device, local, local) else g.hybrid.generate(local, remote, routeInputs())
                    flow.collect { e ->
                        when (e) {
                            is ChatEvent.Attempt -> src = e.source
                            is ChatEvent.Token -> sb.append(e.text)
                            is ChatEvent.Done -> { src = e.source; finish = e.finish; finished = true }
                            is ChatEvent.Failed -> err = e.message
                            is ChatEvent.Notice -> {}
                        }
                    }
                } finally {
                    val text = sb.toString().trim()
                    taskBusy.value = null
                    withContext(NonCancellable + Dispatchers.Main) {
                        onDone(TaskResult(text, src, ai.abhify.android.chat.Continuation.statusOf(text.isEmpty(), err != null, finished, finish),
                            err ?: if (text.isEmpty() && !finished) "Cancelled." else null))
                    }
                }
            }
        }
        val goesOnline = first is ai.abhify.android.ai.Route.Personal || first is ai.abhify.android.ai.Route.Server
        if (sensitive != null && goesOnline && decided != null && decided != Decision.CANCEL) start(decided == Decision.DEVICE)
        else if (sensitive != null && goesOnline) {
            consent.value = Consent("Send $sensitive online?",
                "To $label, the text of $sensitive (up to about ${"%,d".format(userOnline.length)} characters) will be sent to ${onlineDestination() ?: "the online AI"}. " +
                    "Nothing is sent if you choose \"This phone\" or Cancel.", onlineDestination() ?: "online AI") { d ->
                if (d != Decision.CANCEL) onDecided(d)
                when (d) { Decision.ONLINE -> start(false); Decision.DEVICE -> start(true)
                    Decision.CANCEL -> onDone(TaskResult("", null, null, "Cancelled: nothing was sent.")) }   // callers always hear back
            }
        } else start(false)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // ------------------------------------------------------------------ history and memories
    val conversations = mutableStateListOf<ChatDb.Conversation>()
    val memories = mutableStateListOf<ChatDb.Memory>()
    val historyQuery = mutableStateOf("")
    /** v3.4: active or archived chats, optional date range (yyyy-mm-dd), paged 50 at a time; search covers both (full-text when available). */
    val historyArchived = mutableStateOf(false)
    val historyFrom = mutableStateOf("")
    val historyTo = mutableStateOf("")
    val historyPages = mutableIntStateOf(1)
    val historyMore = mutableStateOf(false)
    private fun day(s: String, end: Boolean): Long? = runCatching { java.time.LocalDate.parse(s.trim()) }.getOrNull()?.let { d ->
        (if (end) d.plusDays(1) else d).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - (if (end) 1 else 0) }
    fun loadHistory() = viewModelScope.launch {
        val q = historyQuery.value; val f = historyFolder.value
        val from = day(historyFrom.value, false); val to = day(historyTo.value, true); val limit = 50 * historyPages.intValue
        val c = withContext(Dispatchers.IO) {
            if (q.isBlank()) g.db.conversationPage(historyArchived.value, from, to, limit + 1, 0)
            else g.db.search(q).filter { (from == null || it.updatedAt >= from) && (to == null || it.updatedAt <= to) }
        }.filter { f == null || (it.folder ?: "Personal") == f }
        historyMore.value = q.isBlank() && c.size > limit
        conversations.clear(); conversations.addAll(c.take(limit))
    }
    fun archive(id: Long, on: Boolean) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.setArchived(id, on) }; loadHistory() }
    fun pin(id: Long, on: Boolean) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.setPinned(id, on) }; loadHistory(); g.localChanged() }
    fun rename(id: Long, title: String) = viewModelScope.launch { if (title.isNotBlank()) { withContext(Dispatchers.IO) { g.db.renameConversation(id, title.trim()) }; loadHistory(); g.localChanged() } }
    suspend fun exportJson(): String = withContext(Dispatchers.IO) { g.db.exportJson() }
    suspend fun conversationMarkdown(id: Long): String = withContext(Dispatchers.IO) {
        val c = g.db.conversations().firstOrNull { it.id == id }
        "# " + (c?.title ?: "Chat") + "\n\n" + g.db.messages(id).joinToString("\n\n") { m ->
            (if (m.role == "user") "**You:** " else "**Abhify** (" + (Source.decode(m.model)?.label ?: "AI") + "):\n") + m.content }
    }
    /** v4.4: the conversation as a PDF (made on this phone). */
    suspend fun conversationPdf(id: Long): ByteArray = withContext(Dispatchers.IO) {
        val c = g.db.conversations().firstOrNull { it.id == id }
        ai.abhify.android.career.ChatPdf.render(c?.title ?: "Chat", g.db.messages(id).filter { it.content.isNotBlank() }.map { m ->
            (if (m.role == "user") "You" else "Abhify.AI (" + (Source.decode(m.model)?.label ?: "AI") + ")") to m.content })
    }
    fun deleteConversation(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.deleteConversation(id) }; conversations.removeAll { it.id == id }
        if (conversationId.value == id) newChat()
        g.localChanged(); refreshPending()
    }
    fun loadMemories() = viewModelScope.launch { val m = withContext(Dispatchers.IO) { g.db.memories() }; memories.clear(); memories.addAll(m) }
    fun addMemory(text: String) = viewModelScope.launch { if (text.isNotBlank()) { withContext(Dispatchers.IO) { g.db.addMemory(text.trim()) }; loadMemories(); g.localChanged() } }
    fun editMemory(id: Long, text: String) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.updateMemory(id, text.trim()) }; loadMemories(); g.localChanged() }
    fun deleteMemory(id: Long) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.deleteMemory(id) }; loadMemories(); g.localChanged() }
    /** 5.4 spoken notifications: off by default; only while Abhify is in the foreground, outside quiet hours, never over other speech. */
    fun spokenNotice(text: String) {
        val s = voiceSettings.value
        if (!s.spokenNotifications || !s.enabled || speaking.value || listening.value || voiceState.active.value) return
        if (s.isQuiet(java.time.LocalTime.now().hour)) return
        val st = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        if (st.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return
        speak(text)
    }

    /** 5.4 Privacy → Conversation data: user-confirmed clearing; [extras] = corrections history, dismissed suggestions, voice settings. */
    fun clearConversationData(chats: Boolean, memories: Boolean, extras: Boolean) = viewModelScope.launch {
        if (voiceState.active.value) voiceState.stop()
        withContext(Dispatchers.IO) {
            if (chats) ai.abhify.android.chat.ConversationData.clearConversations(g.db)
            if (memories) ai.abhify.android.chat.ConversationData.clearMemories(g.db)
        }
        if (extras) {
            knowledge.clearHistory()
            g.prefs.edit().remove("suggestions_dismissed").remove("voice_settings").apply()
            voiceSettings.value = ai.abhify.android.voice.VoiceSettings(); applyVoiceSettings()
        }
        if (chats) newChat()
        loadHistory(); loadMemories(); g.localChanged()
        studio.message.value = "Deleted. Documents, projects, study and career data, models and keys were not touched."
    }

    fun deleteAllChats() = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.deleteAll() }; newChat(); loadHistory(); loadMemories(); g.localChanged() }

    // ------------------------------------------------------------------ AI providers (personal keys on this device)
    val providerConfigs = mutableStateListOf<ProviderConfig>()
    val providerModels = mutableStateOf<Map<Provider, List<ModelInfo>>>(emptyMap())
    val providerBusy = mutableStateOf<Provider?>(null)
    val providerResult = mutableStateOf<Pair<Provider, String>?>(null)
    val priority = mutableStateListOf<Provider>()
    val onlineTargetSetting = mutableStateOf(g.onlineTargetSetting)
    val fallbackToDevice = mutableStateOf(g.fallbackToDevice)
    val memoriesEnabled = mutableStateOf(g.memoriesEnabled)
    val customPrompt = mutableStateOf(g.customSystemPrompt)
    val shared = mutableStateOf<JSONObject?>(null)

    fun loadProviders() {
        providerConfigs.clear(); providerConfigs.addAll(Provider.entries.map(g.providerStore::config))
        providerModels.value = Provider.entries.associateWith(g.providerStore::cachedModels)
        priority.clear(); priority.addAll(g.providerStore.priority)
        if (g.cloud.session != null) viewModelScope.launch { shared.value = runCatching { withContext(Dispatchers.IO) { g.cloud.authed("GET", "/api/v1/providers") } }.getOrNull() }
    }
    fun usable(p: Provider) = g.providerStore.usable(p)

    /** Checks a key with a free request (model list / key info) before it is saved. Nothing is generated. */
    fun validateAndSave(p: Provider, key: String) = viewModelScope.launch {
        providerBusy.value = p; providerResult.value = null
        val check = withContext(Dispatchers.IO) { g.providerClient.validate(p, key.trim()) }
        if (check.ok) {
            runCatching { g.providerStore.saveKey(p, key) }.onFailure { providerResult.value = p to (it.message ?: "Invalid key"); providerBusy.value = null; return@launch }
            g.providerStore.adoptModels(p, check.models); g.providerStore.recordOk(p, check.usage)
            if (g.onlineTargetSetting == null) setOnlineTarget(p.id)                 // the first saved key becomes the selected provider
        }
        providerResult.value = p to (if (check.ok) "✅ ${check.message}. Saved on this phone only." else "❌ ${check.message}. Not saved.")
        providerBusy.value = null; loadProviders()
    }
    fun refreshProvider(p: Provider) = viewModelScope.launch {
        providerBusy.value = p
        val r = withContext(Dispatchers.IO) { g.providerClient.refresh(p) }
        providerResult.value = p to (if (r.ok) "✅ ${r.message}" else "❌ ${r.message}")
        providerBusy.value = null; loadProviders()
    }
    fun removeKey(p: Provider) { g.providerStore.removeKey(p); if (g.onlineTargetSetting == p.id) setOnlineTarget(null); providerResult.value = p to "Key removed from this phone."; loadProviders() }
    fun setProviderEnabled(p: Provider, on: Boolean) { g.providerStore.setEnabled(p, on); loadProviders() }
    fun setProviderModel(p: Provider, model: String) { g.providerStore.setModel(p, model); loadProviders() }
    fun setAllowCharged(p: Provider, on: Boolean) { g.providerStore.setAllowCharged(p, on); loadProviders() }
    fun setMaxTokens(p: Provider, n: Int) { g.providerStore.setMaxTokens(p, n); loadProviders() }
    /** Removes everything in Documents, Learn (plans, flashcards) and Work from this phone. */
    fun deleteStudioData() = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.deleteStudioData(); g.db.deleteLifeData() }
        life.loadCareer(); life.loadSessions(); life.loadVault()
        studio.docs.clear(); studio.plans.clear(); studio.decks.clear(); studio.workspace.value = null; studio.loadWork() }
    fun setFreeTierConfirmed(p: Provider, on: Boolean) { g.providerStore.setFreeTierConfirmed(p, on); loadProviders() }
    fun move(p: Provider, up: Boolean) {
        val l = g.providerStore.priority.toMutableList(); val i = l.indexOf(p); val j = if (up) i - 1 else i + 1
        if (j in l.indices) { l[i] = l[j].also { l[j] = l[i] }; g.providerStore.priority = l }
        loadProviders()
    }
    fun setOnlineTarget(t: String?) { g.onlineTargetSetting = t; onlineTargetSetting.value = t; modelRev.intValue++ }
    fun setFallbackToDevice(on: Boolean) { g.fallbackToDevice = on; fallbackToDevice.value = on }
    val fallbackProviders = mutableStateOf(g.fallbackProviders)
    fun setFallbackProviders(on: Boolean) { g.fallbackProviders = on; fallbackProviders.value = on }
    /** Security and privacy state for Settings → Diagnostics (counts and switches only, never secrets). */
    fun securityDiagnostics(): String = buildString {
        val keys = Provider.entries.count { g.providerStore.config(it).configured }
        append("Provider keys on this phone: $keys (Android Keystore AES-256-GCM, excluded from backups and exports) · ")
        append("online AI ${if (g.onlineAllowed) "allowed" else "off (local only)"} · memories online ${if (g.shareMemoriesOnline) "shared" else "not shared"} · ")
        append("sync ${if (g.cloud.session != null && g.syncEnabled) "on" else "off"} · database v${ai.abhify.android.chat.ChatDb.VERSION}")
    }

    // ------------------------------------------------------------------ chat folders and summaries
    val historyFolder = mutableStateOf<String?>(null)                  // null = all
    fun setFolder(id: Long, folder: String?) = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.setFolder(id, folder) }; loadHistory() }
    fun summarizeChat(id: Long) = viewModelScope.launch {
        val md = conversationMarkdown(id)
        aiTask("summarize this chat", ai.abhify.android.learn.Studio.SYSTEM, ai.abhify.android.learn.Studio.summarizeChat() + "\n\n" + md.takeLast(3200),
            "this conversation", 3600, ai.abhify.android.learn.Studio.summarizeChat() + "\n\n" + md.takeLast(24_000)) { r ->
            if (r.text.isNotBlank() && r.status == null) viewModelScope.launch { withContext(Dispatchers.IO) { g.db.setSummary(id, r.text) }; loadHistory() }
            else studio.result.value = StudioState.Result("Chat summary", r.text, r.source, r.status, r.error ?: "The summary wasn't complete; it was not saved.")
        }
    }

    /** Sanitized provider diagnostics for support (never keys). */
    fun providerDiagnostics(): String = buildString {
        appendLine("Abhify ${ai.abhify.android.BuildConfig.VERSION_NAME} · mode ${aiMode.value} · online allowed ${g.onlineAllowed} · internet ${online.value}")
        appendLine("Selected: ${g.onlineTargetSetting ?: "none"} · fallback providers ${g.fallbackProviders} · fallback phone ${g.fallbackToDevice}")
        Provider.entries.forEach { p -> val c = g.providerStore.config(p)
            appendLine("${p.title}: configured=${c.configured} enabled=${c.enabled} model=${c.model} allowCharged=${c.allowCharged} " +
                "problem=${g.providerStore.problem(p) ?: "none"} lastOk=${c.lastOkAt ?: "never"} lastError=${c.lastError ?: "none"}") }
    }
    fun setMemoriesEnabled(on: Boolean) { g.memoriesEnabled = on; memoriesEnabled.value = on }
    fun setCustomPrompt(text: String) { g.customSystemPrompt = text; customPrompt.value = g.customSystemPrompt }
    val customPromptEnabled = mutableStateOf(g.customPromptEnabled)
    fun setCustomPromptEnabled(on: Boolean) { g.customPromptEnabled = on; customPromptEnabled.value = on }
    val personality = mutableStateOf(g.personality)
    fun setPersonality(p: PromptBuilder.Personality) { g.personality = p; personality.value = p }
    /** Exactly the instructions an online provider would receive (no memories, no chat), for the preview. */
    fun instructionsPreview(): String = PromptBuilder.buildNamed(g.assistantName, "(your question)", emptyList(), emptyList(), g.userName, onDevice = false, custom = g.effectiveInstructions())[0].content
    fun clearAllMemories() = viewModelScope.launch {
        withContext(Dispatchers.IO) { g.db.memories().forEach { g.db.deleteMemory(it.id) } }       // soft deletes, so the removal also syncs
        loadMemories(); g.localChanged()
    }
    fun resetProviders() { g.providerStore.resetAll(); setOnlineTarget(null); loadProviders() }
    /** Where Online / Auto would send the next question (for the chat header and the disclosure). */
    fun onlineDestination(): String? = when (val t = g.onlineTarget()) {
        is OnlineTarget.Personal -> "${t.provider.title} (${g.providerStore.config(t.provider).model}) with this phone's own key"
        OnlineTarget.Shared -> "your Abhify server (shared access)"
        null -> null
    }

    // ------------------------------------------------------------------ voice
    val voice = Voice(app)
    val listening = mutableStateOf(false)
    val heard = mutableStateOf("")
    val voiceError = mutableStateOf<String?>(null)
    /** 7.1.1: what the last voice attempt was for, so Retry can start it again without restarting the app. */
    private var lastVoiceRequest: Pair<String, (String) -> Unit>? = null

    /** Tap-to-talk. Independent of the AI: listening works (and shows its own errors) whether or not an AI is set up. Never throws. */
    fun startVoice(languageTag: String = java.util.Locale.getDefault().toLanguageTag(), onText: (String) -> Unit) {
        if (listening.value) return                                            // one microphone session at a time
        if (wakeListening.value) stopWake()                                    // tap-to-talk takes the microphone from name listening
        if (voice.isListening) return
        voiceError.value = null; heard.value = ""; listening.value = true
        lastVoiceRequest = languageTag to onText
        val t0 = System.currentTimeMillis()
        runCatching { warmForVoice() }
        voice.nameHint = g.assistantName
        ai.abhify.android.device.VoiceLog.add("mic", "tap-to-talk start (recognizer ${voice.mode})")
        try {
            voice.listen(onPartial = { heard.value = it }, onResult = { text -> listening.value = false; voiceState.level.floatValue = 0f
                    ai.abhify.android.device.VoiceLog.add("recognizer", if (text.isBlank()) "result: empty" else "result: ${text.length} chars")
                    android.util.Log.i("AbhifyVoice", "speech recognised in ${System.currentTimeMillis() - t0} ms after the mic started")   // timing only, no text
                    if (!voiceState.active.value) resumeWakeSoon()
                    if (text.isNotBlank()) runCatching { onText(text) }.onFailure { voiceError.value = "Something went wrong with what was heard. Your words: \"${text.take(200)}\""; pendingInput.value = text }
                    else { voiceError.value = ai.abhify.android.voice.VoiceErrors.NOT_HEARD; if (voiceState.active.value) voiceState.onSpeechFinished() } },
                onError = { listening.value = false; voiceError.value = it; voiceState.level.floatValue = 0f; ai.abhify.android.device.VoiceLog.add("recognizer", "error"); if (!voiceState.active.value) resumeWakeSoon() },
                languageTag = languageTag)
        } catch (e: Exception) {
            listening.value = false; voiceError.value = ai.abhify.android.voice.VoiceErrors.START_FAILED
        }
    }
    // ------------------------------------------------------------------ 7.1.1 wake word (foreground only, off by default)
    /** True while the microphone listens for the assistant's name (a pill on every screen shows it, with Stop). */
    val wakeListening = mutableStateOf(false)
    val wakeNote = mutableStateOf<String?>(null)
    private var foreground = false
    private var wakeErrors = 0

    /** 7.1.0: the offline Vosk wake-word engine (installed only when the user taps Install). */
    val vosk by lazy { ai.abhify.android.voice.VoskWake(getApplication()) }
    val voskInstalled = mutableStateOf(false)
    val voskProgress = mutableStateOf<Pair<Long, Long>?>(null)
    fun refreshVosk() { voskInstalled.value = runCatching { vosk.installed() }.getOrDefault(false) }
    fun installVosk() {
        if (voskProgress.value != null) return
        voskProgress.value = 0L to ai.abhify.android.voice.VoskWake.BYTES; wakeNote.value = null
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { vosk.install { got, total -> voskProgress.value = got to total } }.getOrElse { "Install failed: ${it.message}" } }
            voskProgress.value = null; refreshVosk()
            wakeNote.value = err ?: "✓ Offline wake word installed. Turn on \"Listen for…\" to use it."
        }
    }
    fun removeVosk() { stopWake(); runCatching { vosk.remove() }; refreshVosk() }
    fun cancelVoskInstall() { vosk.cancel = true }
    /** 7.1.0: "Test wake word": listens up to 10 s for the name with the offline engine and says whether it heard it. */
    val wakeTest = mutableStateOf<String?>(null)
    fun testWake() {
        if (!voskInstalled.value || !micGranted()) { wakeTest.value = "Install the offline wake word and allow the microphone first."; return }
        stopWake(); wakeTest.value = "Say \"Hey ${g.assistantName}\" now…"
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { vosk.prepare() }
            if (err != null) { wakeTest.value = err; return@launch }
            var done = false
            vosk.start(g.assistantName, onHit = { heard -> done = true; wakeTest.value = "✓ Heard \"$heard\". The wake word works." },
                onError = { msg -> done = true; wakeTest.value = msg })
            delay(10_000)
            if (!done) { vosk.stop(); wakeTest.value = "Didn't hear \"${g.assistantName}\" in 10 seconds. Speak clearly, closer to the phone; or choose a name that is a common English word or name." }
            resumeWakeSoon()
        }
    }
    /** 7.1.0: "Test microphone": 3 s of real input level (nothing is recorded or kept). */
    val micTest = mutableStateOf<String?>(null)
    fun testMic() {
        if (!micGranted()) { micTest.value = "Allow the microphone first (tap the microphone in the Assistant once)."; return }
        if (listening.value) { micTest.value = "Stop listening first."; return }
        stopWake(); micTest.value = "Listening for 3 seconds: say something…"
        viewModelScope.launch {
            micTest.value = withContext(Dispatchers.IO) { runCatching {
                val rate = 16000
                val size = maxOf(android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT), 4096)
                @Suppress("MissingPermission")
                val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, size)
                var peak = 0
                try {
                    rec.startRecording(); val buf = ShortArray(size / 2); val end = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < end) { val n = rec.read(buf, 0, buf.size); for (i in 0 until maxOf(n, 0)) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
                } finally { runCatching { rec.stop() }; rec.release() }
                when { peak < 300 -> "No sound reached the app. Check that no other app uses the microphone and that it isn't blocked in Android's privacy settings."
                    peak < 2000 -> "The microphone works but is quiet. Speak closer to the phone."
                    else -> "✓ The microphone works and picks up your voice." }
            }.getOrElse { "The microphone couldn't be opened (${it.javaClass.simpleName})." } }
            resumeWakeSoon()
        }
    }

    /** Why name listening can't be used on this phone (null = it can). Audio never leaves the phone: Vosk, or the on-device recogniser. */
    fun wakeSupport(): String? = when {
        !voiceSettings.value.enabled -> "Turn on the voice assistant above first."
        voskInstalled.value -> null
        voice.mode == ai.abhify.android.voice.Voice.Mode.ON_DEVICE -> null
        else -> "Install the offline wake word below (about 41 MB). This phone has no on-device speech recognizer, and its own recognizer may send audio to Google, so Abhify doesn't use it for this."
    }
    private fun micGranted() = androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun wakeAllowed() = foreground && voiceSettings.value.wakeWord && wakeSupport() == null && micGranted() &&
        !listening.value && !voiceState.active.value && !speaking.value && !generating.value
    fun setWake(on: Boolean) {
        setVoiceSettings(voiceSettings.value.copy(wakeWord = on)); wakeNote.value = null
        if (on) resumeWake() else stopWake()
    }
    fun onAppForeground() { foreground = true; refreshVosk(); resumeWakeSoon() }
    /** Leaving the app: every microphone use stops (tap-to-talk, conversation, name listening). */
    fun releaseMicrophone() {
        foreground = false
        stopWake()
        if (listening.value) cancelVoice()
        if (voiceState.active.value) voiceState.stop()
    }
    fun resumeWakeSoon() { viewModelScope.launch { delay(600); resumeWake() } }
    fun resumeWake() { if (!wakeAllowed() || wakeListening.value) return; wakeErrors = 0; wakeLoop() }
    fun stopWake() { if (wakeListening.value) { wakeListening.value = false; runCatching { vosk.stop() }; voice.cancelListening() } }
    private fun wakeLoop() {
        if (!wakeAllowed()) { wakeListening.value = false; return }
        wakeListening.value = true
        if (voskInstalled.value) {                                            // 7.1.0: offline Vosk engine (any phone)
            viewModelScope.launch {
                val err = withContext(Dispatchers.IO) { vosk.prepare() }
                if (!wakeListening.value) return@launch
                if (err != null) { wakeListening.value = false; wakeNote.value = err; return@launch }
                vosk.start(g.assistantName, onHit = { wakeListening.value = false; voiceState.startFromWake("") },
                    onError = { msg -> if (wakeListening.value) { if (++wakeErrors >= 5) { wakeListening.value = false; wakeNote.value = "Stopped listening for the name: $msg" } else wakeAgain(3000) } })
            }
            return
        }
        voice.nameHint = g.assistantName
        runCatching {
            voice.listen(onPartial = {}, onResult = { text ->
                val hit = ai.abhify.android.voice.WakeMatcher.match(text, g.assistantName)
                if (hit != null) { wakeListening.value = false; voiceState.startFromWake(hit.command) } else wakeAgain(250)
            }, onError = { msg ->
                if (msg == ai.abhify.android.voice.VoiceErrors.NOT_HEARD) wakeAgain(250)
                else if (++wakeErrors >= 5) { wakeListening.value = false; wakeNote.value = "Stopped listening for the name: $msg" }
                else wakeAgain(3000)
            }, languageTag = voiceSettings.value.recognitionTag(java.util.Locale.getDefault().toLanguageTag()))
        }.onFailure { wakeListening.value = false; wakeNote.value = "Couldn't listen for the name on this phone." }
    }
    private fun wakeAgain(ms: Long) { viewModelScope.launch { delay(ms); if (wakeListening.value) wakeLoop() } }

    /** 7.1.1: the Retry button after a voice error: the same request again. */
    fun retryVoice() { val (tag, cb) = lastVoiceRequest ?: return; voiceError.value = null; startVoice(tag, cb) }

    /** 7.1.1: why a question can't be answered right now (no AI model, no provider, offline…), or null when it can. */
    fun assistantProblem(): String? = runCatching {
        (ai.abhify.android.ai.RoutePolicy.plan(routeInputs()).singleOrNull() as? ai.abhify.android.ai.Route.Unavailable)?.reason
    }.getOrElse { "The AI isn't ready (${it.message ?: "unknown problem"})." }

    // ------------------------------------------------------------------ 5.4 Personal Voice Companion
    val voiceState by lazy { VoiceState(this) }
    val voiceSettings = mutableStateOf(ai.abhify.android.voice.VoiceSettings.fromJson(g.prefs.getString("voice_settings", null),
        g.prefs.getFloat("speech_rate", 1.0f)))
    fun setVoiceSettings(s: ai.abhify.android.voice.VoiceSettings) {
        voiceSettings.value = s; g.prefs.edit().putString("voice_settings", s.toJson()).apply(); applyVoiceSettings()
        if (!s.enabled && voiceState.active.value) voiceState.stop()
    }
    private fun applyVoiceSettings() {
        val s = voiceSettings.value; voice.preferredVoice = null; voice.preferredStyle = ai.abhify.android.voice.VoiceSettings.Style.ASSISTANT   // 7.1.0: one voice
        voice.picked = ai.abhify.android.voice.VoiceSettings.Style.entries.associateWith { s.picked(it) }
        voice.setRate(s.rate); voice.setPitch(s.pitch); speechRate.value = s.rate
    }
    /** 7.1.0-dev.2: the assistant's name on this phone (Settings → AI personalization). */
    val assistantName = mutableStateOf(g.assistantName)
    fun setAssistantName(name: String): String? = runCatching { g.assistantName = name; assistantName.value = g.assistantName; null }
        .getOrElse { it.message ?: "That name can't be used." }

    /**
     * Voice warm-up: when the next answer comes from the phone's model and it isn't loaded yet, it is loaded while the
     * user speaks (the same model the chat would load; nothing is generated and no setting changes). A send during the
     * load waits for it (ensureModel holds a lock) instead of loading twice.
     */
    private fun warmForVoice() {
        if (g.engine.loaded != null || g.store.active() == null || generating.value || taskBusy.value != null) return
        // 7.1.1: on a low-memory phone, loading the model (~1 GB) while the speech recogniser runs can get the app killed;
        // there the model loads when the question is sent instead
        if (g.memoryProfile().first == ai.abhify.android.device.MemoryProfile.LOW) return
        if (ai.abhify.android.ai.RoutePolicy.plan(routeInputs()).first() !is ai.abhify.android.ai.Route.Device) return
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val ok = readyDevice()
            android.util.Log.i("AbhifyVoice", "phone model warm-up ${if (ok) "done" else "failed"} in ${System.currentTimeMillis() - t0} ms")
        }
    }
    fun stopVoice() { voice.stopListening(); listening.value = false }
    fun cancelVoice() { voice.cancelListening(); listening.value = false; heard.value = "" }
    val speaking = mutableStateOf(false)
    init {
        voice.onSpeaking = { on -> viewModelScope.launch(Dispatchers.Main) { speaking.value = on; if (!on && voiceState.active.value) voiceState.onSpeechFinished()
            if (!on && !voiceState.active.value) resumeWakeSoon() } }
        voice.onLevel = { l -> viewModelScope.launch(Dispatchers.Main) { voiceState.level.floatValue = l } }
        voice.onSpeakError = { m -> viewModelScope.launch(Dispatchers.Main) { speaking.value = false; voiceError.value = m } }
        voice.onProgress = { i, n -> viewModelScope.launch(Dispatchers.Main) { speechProgress.value = i to n } }
    }
    fun speak(text: String) { speechPaused.value = false; voice.speak(text); speechOwner.value = voice.owner }
    fun stopSpeaking() { speechPaused.value = false; voice.stopSpeaking(); speechOwner.value = null }
    /** 5.6: queue more of the same answer after what is being spoken (Talk to Me streaming). */
    fun speakAppend(parts: List<String>) { speechPaused.value = false; voice.appendParts(parts, "chat"); speechOwner.value = voice.owner }
    /** Who the current reading belongs to ("chat", "news") and where it is: (part, parts). */
    val speechOwner = mutableStateOf<String?>(null)
    val speechProgress = mutableStateOf(0 to 0)
    val speechRate = mutableStateOf(1.0f)
    init { applyVoiceSettings() }                                  // 5.4: saved voice and speed (after speechRate exists)
    /** News: read the briefing/summary already on screen (no new AI request). One part per item. */
    fun listenNews(title: String, text: String) {
        val parts = Voice.newsParts(title, text)
        if (parts.isEmpty()) { voiceError.value = "There is nothing to read yet."; return }
        speechPaused.value = false; speechProgress.value = 0 to parts.size
        voice.speakParts(parts, "news"); speechOwner.value = "news"
    }
    fun skipSpeech(delta: Int) { speechPaused.value = false; voice.skip(delta) }
    fun setSpeechRate(r: Float) { speechRate.value = r; voice.setRate(r)
        setVoiceSettings(voiceSettings.value.copy(speed = ai.abhify.android.voice.VoiceSettings.Speed.CUSTOM, customRate = r)) }   // 5.4: persisted
    val speechPaused = mutableStateOf(false)
    fun pauseSpeaking() { if (voice.pauseSpeaking()) speechPaused.value = true }
    fun resumeSpeaking() { speechPaused.value = false; voice.resumeSpeaking() }
    fun setReadAloud(on: Boolean) { g.readAloud = on; if (!on) voice.stopSpeaking() }

    // ------------------------------------------------------------------ learn (interview + quiz), offline on the phone
    val learnMode = mutableStateOf(8)                              // index into LEARN_SECTIONS (7.2: opens on Prepare)
    val knowledgeSection = mutableIntStateOf(0)                    // Knowledge tab: 0 My Knowledge, 1 Workspaces (v4.0: search can open either)
    val category = mutableStateOf("all")
    val interview = mutableStateListOf<InterviewQuestion>().apply { addAll(g.bank.questions.shuffled()) }
    val revealed = mutableStateListOf<String>()
    val learnBusy = mutableStateOf(false)
    val learnMessage = mutableStateOf<String?>(null)
    val progress = mutableStateOf(ChatDb.Progress(0, 0, 0, 0))
    fun refreshProgress() = viewModelScope.launch { progress.value = withContext(Dispatchers.IO) { g.db.progress() } }
    fun setCategory(c: String) { category.value = c; interview.clear(); interview.addAll(g.bank.questions(c).shuffled()); newQuiz() }
    /**
     * Question Bank refresh: the curated questions of the current category in a new order, with the AI-written ones kept
     * at the top. Nothing is deleted and the category filter stays. One refresh at a time.
     */
    val bankRefreshing = mutableStateOf(false)
    fun refreshBank() {
        if (bankRefreshing.value) return
        bankRefreshing.value = true
        viewModelScope.launch {
            val ai = interview.filter { it.generated }
            val curated = withContext(Dispatchers.Default) { g.bank.questions(category.value).shuffled() }
            interview.clear(); interview.addAll(ai + curated.filter { c -> ai.none { it.question == c.question } })
            revealed.clear()
            kotlinx.coroutines.delay(350)                                        // the indicator is visible: the refresh happened
            bankRefreshing.value = false
        }
    }
    fun reveal(q: InterviewQuestion) {
        if (q.question in revealed) { revealed.remove(q.question); return }
        revealed += q.question
        viewModelScope.launch(Dispatchers.IO) { g.db.recordInterviewView(q.category, q.question); progress.value = g.db.progress() }
    }
    private fun label(c: String) = g.bank.categories.firstOrNull { it.first == c }?.second ?: "AI and machine learning"
    private fun pickCategory() = category.value.takeIf { it != "all" } ?: g.bank.categories.random().first
    fun generateInterview() = learnTask {
        val c = pickCategory()
        Learning.parseInterview(complete(Learning.interviewPrompt(label(c), interview.map { it.question }), 320), c)
            ?.let { interview.add(0, it); null } ?: "The AI's answer wasn't usable this time. Try again, or use the built-in questions."
    }
    val quiz = mutableStateListOf<Mcq>()
    val quizIndex = mutableIntStateOf(0)
    val picked = mutableStateOf<Int?>(null)
    val roundScore = mutableIntStateOf(0)
    fun newQuiz() { quiz.clear(); quiz.addAll(g.bank.mcqs(category.value).shuffled().take(5)); quizIndex.intValue = 0; picked.value = null; roundScore.intValue = 0 }
    fun answerQuiz(i: Int) {
        val m = quiz.getOrNull(quizIndex.intValue) ?: return
        if (picked.value != null) return
        picked.value = i; if (i == m.correct) roundScore.intValue++
        viewModelScope.launch(Dispatchers.IO) { g.db.recordAnswer(m.category, m.question, i == m.correct); progress.value = g.db.progress() }
    }
    fun nextQuestion() { quizIndex.intValue++; picked.value = null }
    fun generateMcq() = learnTask {
        val c = pickCategory()
        Learning.parseMcq(complete(Learning.mcqPrompt(label(c), quiz.map { it.question }), 260), c)
            ?.let { quiz.add(quizIndex.intValue + if (picked.value != null) 1 else 0, it); null }
            ?: "The AI's question didn't pass the checks (4 different options, one correct). Try again."
    }
    /** Learning content is written by the on-phone model (works offline). */
    private suspend fun complete(prompt: List<PromptBuilder.Msg>, maxTokens: Int): String {
        val sb = StringBuilder()
        g.engine.generate(prompt, maxTokens, 0.7f, seed = (System.nanoTime() % 100000).toInt()).collect { if (it is LlmEngine.Event.Token) sb.append(it.text) }
        return sb.toString()
    }
    private fun learnTask(block: suspend () -> String?) = viewModelScope.launch {
        if (learnBusy.value || generating.value) return@launch
        learnBusy.value = true; learnMessage.value = null
        try { learnMessage.value = if (!readyDevice()) (engine.value as? Engine.Error)?.message else block() }
        catch (e: Exception) { learnMessage.value = "Failed: ${e.message}" }
        finally { learnBusy.value = false }
    }

    // ------------------------------------------------------------------ news (online only)
    val news = mutableStateOf<NewsRepository.Result?>(g.news.cached())
    val newsLoading = mutableStateOf(false)
    fun refreshNews() = viewModelScope.launch {
        if (newsLoading.value) return@launch
        newsLoading.value = true
        news.value = runCatching { g.news.refresh() }.getOrElse { news.value }
        newsLoading.value = false
    }
    fun askAbout(item: NewsItem) = send("Explain this AI news for a learner, and why it matters: \"${item.title}\". ${item.summary}")

    // ------------------------------------------------------------------ model tools
    fun runBenchmark() = tool {
        if (!readyDevice()) return@tool "Benchmark not possible: ${(engine.value as? Engine.Error)?.message}"
        val inst = g.store.active()!!
        val battery = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempBefore = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0)
        val prompt = listOf(PromptBuilder.Msg("system", "You are a concise AI tutor."), PromptBuilder.Msg("user", "Explain what a neural network is in about 100 words."))
        val runs = (1..3).mapNotNull { _ ->
            var s: LlmEngine.Stats? = null
            g.engine.generate(prompt, maxTokens = 128, temperature = 0f).collect { if (it is LlmEngine.Event.Finished) s = it.stats }
            s
        }
        val battery2 = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refreshDevice()
        val d = device.value
        buildString {
            appendLine("Abhify benchmark · ${d.manufacturer} ${d.model} · ${d.socModel} · Android ${d.release} (API ${d.sdkInt})")
            appendLine("Model ${inst.id} · context ${inst.context} · ${g.threads()} threads · ${g.backendLabel()} · ${g.runtimeLabel}")
            runs.forEachIndexed { i, s -> appendLine("Run ${i + 1}: prompt ${s.promptTokens} tok, first token %.0f ms, %d tok at %.1f tok/s".format(s.firstTokenMs, s.generatedTokens, s.tokensPerSecond)) }
            appendLine("Peak app memory ${g.engine.peakMemoryMb()} MB · now ${g.engine.currentMemoryMb()} MB · free RAM ${d.availRamMb} MB")
            appendLine("Battery temperature ${tempBefore ?: "?"} → ${battery2?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0) ?: "?"} °C · thermal status ${d.thermalStatus}")
            append("CPU: ${DeviceProbe.cpuSummary(d)}")
        }
    }

    fun checkGpu() = tool {
        val inst = g.store.active() ?: return@tool "No model installed."
        val model = g.catalog.byId(inst.id)!!
        val backend = g.engine.backend()
        if (!backend.hasGpu) return@tool "No usable GPU backend on this phone/build (devices: ${backend.devices.joinToString { it.name }}). The CPU is used."
        if (!model.vulkanSupported) return@tool "${model.name} has no GPU path. The CPU is used."
        val file = g.store.modelFile(inst.id)
        val prompt = listOf(PromptBuilder.Msg("user", "List three uses of machine learning, one line each."))
        suspend fun run(gpu: Int): Pair<String, LlmEngine.Stats?>? {
            g.engine.load(file, inst.context, g.threads(), gpu)?.let { return null }
            val sb = StringBuilder(); var s: LlmEngine.Stats? = null
            g.engine.generate(prompt, 48, 0f).collect { e -> if (e is LlmEngine.Event.Token) sb.append(e.text) else if (e is LlmEngine.Event.Finished) s = e.stats }
            return sb.toString() to s
        }
        val cpu = run(0) ?: return@tool "The CPU run failed; GPU check skipped."
        val gpu = run(99)
        g.engine.unload(); engine.value = Engine.NotLoaded
        val same = gpu != null && gpu.first.take(40) == cpu.first.take(40)
        val faster = gpu?.second != null && cpu.second != null && gpu.second!!.tokensPerSecond > cpu.second!!.tokensPerSecond * 1.1
        g.gpuEnabled = same && faster
        "CPU: %.1f tok/s · GPU: %s\nSame answer: %s · GPU %s".format(cpu.second?.tokensPerSecond ?: 0.0,
            gpu?.second?.let { "%.1f tok/s".format(it.tokensPerSecond) } ?: "failed to load", if (same) "yes" else "NO",
            if (g.gpuEnabled) "enabled for ${model.name} on this phone (other models stay on the CPU until they pass this test)" else "not enabled for ${model.name} (CPU stays in use)")
    }

    fun verifyModel() = tool(Dispatchers.IO) {
        val inst = g.store.active() ?: return@tool "No model installed."
        if (g.store.verifyFully(inst)) "✅ The model file matches its SHA-256 (${inst.sha256.take(16)}…)."
        else "❌ The model file does NOT match its SHA-256. Remove it and download it again."
    }

    fun removeModel(id: String) = viewModelScope.launch {
        stop(); g.engine.unload(); engine.value = Engine.NotLoaded
        withContext(Dispatchers.IO) { g.store.remove(id); g.mirrorModels() }
        modelRev.intValue++
        refreshDevice()
    }

    fun setContext(ctx: Int) = viewModelScope.launch { g.store.active()?.let { g.store.setContext(it.id, ctx) }; g.engine.unload(); engine.value = Engine.NotLoaded }
    fun resetProgress() = viewModelScope.launch { withContext(Dispatchers.IO) { g.db.resetProgress() }; refreshProgress() }

    private fun tool(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main, block: suspend () -> String) =
        viewModelScope.launch(dispatcher) {
            if (toolBusy.value) return@launch
            toolBusy.value = true; toolResult.value = "Running…"
            toolResult.value = runCatching { block() }.getOrElse { "Failed: ${it.message}" }
            toolBusy.value = false
        }

    override fun onCleared() { stop(); voice.release() }
}
