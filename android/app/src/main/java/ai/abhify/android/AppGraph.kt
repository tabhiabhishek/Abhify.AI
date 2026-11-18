package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.HybridAi
import ai.abhify.android.ai.OnlineTarget
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderClient
import ai.abhify.android.providers.ProviderStore
import ai.abhify.android.chat.ChatDb
import ai.abhify.android.chat.unindexedDocs
import ai.abhify.android.chat.queuedDriveFiles
import ai.abhify.android.cloud.Account
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.KeyValue
import ai.abhify.android.cloud.KeystoreBox
import ai.abhify.android.cloud.TokenStore
import ai.abhify.android.cloud.UrlHttp
import ai.abhify.android.sync.NetworkMonitor
import ai.abhify.android.sync.SyncStatus
import ai.abhify.android.sync.SyncWorker
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.device.DeviceProbe
import ai.abhify.android.learn.LearningBank
import ai.abhify.android.news.NewsRepository
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.Downloader
import ai.abhify.android.model.ModelCatalog
import ai.abhify.android.model.ModelSelector
import ai.abhify.android.model.ModelStore
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AbhifyApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ai.abhify.android.docs.PdfText.install(this)       // PDF text extraction (on the phone)
        runCatching { assets.open("skills_taxonomy.json").use { ai.abhify.android.career.SkillTaxonomy.current = ai.abhify.android.career.SkillTaxonomy.parse(it.readBytes().toString(Charsets.UTF_8)) } }
        if (graph.cloud.session != null && graph.syncEnabled) { SyncWorker.schedulePeriodic(this, graph.wifiOnly); SyncWorker.syncSoon(this, wifiOnly = graph.wifiOnly) }
    }

    /** In the background under memory pressure: free the model (~1 GB) so Android doesn't kill the app. It reloads on the next message. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) graph.heavy.markPressure()     // foreground pressure: no new heavy jobs for a minute
        if (level >= TRIM_MEMORY_BACKGROUND && !graph.setup.running) graph.scope.launch { graph.engine.unload() }
    }

    override fun onLowMemory() { super.onLowMemory(); graph.heavy.markPressure() }
}

val Context.graph: AppGraph get() = (applicationContext as AbhifyApp).graph

/** App-wide singletons. All data lives in app-private storage of this installation. */
class AppGraph(val context: Context) {
    val catalog: ModelCatalog = ModelCatalog.parse(context.assets.open("model_catalog.json").bufferedReader().readText())
    val store = ModelStore(File(context.noBackupFilesDir, "models"))     // large files: never in cloud backups
    val db = ChatDb(context)
    val engine = LlmEngine(context.applicationInfo.nativeLibraryDir)
    val prefs = context.getSharedPreferences("abhify", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val setup = SetupController(this)
    val runtimeLabel get() = "${catalog.runtime.name} ${catalog.runtime.version} (${catalog.runtime.commit.take(8)})"

    /** The local profile's display name (works with no account). */
    var userName: String
        get() = db.profile().displayName
        set(v) { db.setProfileName(v) }
    /** True only when the ACTIVE model passed the GPU check on this device (4.6.2: per model, see GpuPolicy). */
    var gpuEnabled: Boolean
        get() = ai.abhify.android.model.GpuPolicy.usesGpu(gpuModel(), store.activeId())
        set(v) { prefs.edit().putString(ai.abhify.android.model.GpuPolicy.KEY, if (v) store.activeId() else null).remove(ai.abhify.android.model.GpuPolicy.LEGACY_KEY).apply() }
    private fun gpuModel(): String? {
        prefs.getString(ai.abhify.android.model.GpuPolicy.KEY, null)?.let { return it }
        if (!prefs.contains(ai.abhify.android.model.GpuPolicy.LEGACY_KEY)) return null
        val m = ai.abhify.android.model.GpuPolicy.migrateLegacy(prefs.getBoolean(ai.abhify.android.model.GpuPolicy.LEGACY_KEY, false), store.installed().map { it.id }.toSet())
        prefs.edit().putString(ai.abhify.android.model.GpuPolicy.KEY, m).remove(ai.abhify.android.model.GpuPolicy.LEGACY_KEY).apply()
        return m
    }
    var threadsOverride: Int
        get() = prefs.getInt("threads", 0)
        set(v) { prefs.edit().putInt("threads", v).apply() }

    fun threads(): Int = threadsOverride.takeIf { it > 0 } ?: ModelSelector.threads(Runtime.getRuntime().availableProcessors())

    val bank: LearningBank = LearningBank.parse(context.assets.open("learning_bank.json").bufferedReader().readText())
    /** 4.6.2 News Center: its own catalog and cache folder (never the chat database, memory or Knowledge). */
    val newsCenter by lazy {
        ai.abhify.android.news.NewsCenter(ai.abhify.android.news.NewsCatalog.parse(context.assets.open("news_sources.json").bufferedReader().readText()),
            File(context.cacheDir, "news")) { url, read -> news.fetchFeed(ai.abhify.android.news.Feed("news", url), read) }
    }
    val news = NewsRepository(NewsRepository.feeds(context.assets.open("news_feeds.json").bufferedReader().readText()),
        File(context.filesDir, "news_cache.json"))

    // Synced preferences: stored locally and, when sync is on, shared with the user's other devices.
    var themeMode: String                       // "dark" (brand default), "light", "system"
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(v) { prefs.edit().putString("theme", v).apply(); db.setPreference("theme", v); localChanged() }
    var readAloud: Boolean
        get() = prefs.getBoolean("read_aloud", false)
        set(v) { prefs.edit().putBoolean("read_aloud", v).apply(); db.setPreference("read_aloud", v.toString()); localChanged() }
    var aiMode: AiMode
        // v3.4: the phone's model is the default for NEW installs; existing installs keep their mode (see init)
        get() = runCatching { AiMode.valueOf(prefs.getString("ai_mode", "DEVICE")!!) }.getOrDefault(AiMode.DEVICE)
        set(v) { prefs.edit().putString("ai_mode", v.name).apply(); db.setPreference("ai_mode", v.name); localChanged() }

    // ------------------------------------------------------------------ online: account, network, sync, hybrid AI
    private val kv = object : KeyValue {
        private val p = context.getSharedPreferences("abhify_auth", Context.MODE_PRIVATE)
        override fun get(key: String) = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
    val cloud = CloudClient(UrlHttp(), TokenStore(kv, KeystoreBox()))
    val network = NetworkMonitor(context).apply { onRestored = { if (cloud.session != null && syncEnabled) SyncWorker.syncSoon(context, 2, wifiOnly) } }
    val syncStatus = MutableStateFlow<SyncStatus>(if (cloud.session == null) SyncStatus.Off else SyncStatus.Idle)
    /** The server address last used (or the build's, if one was given). Never guessed. */
    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: BuildConfig.CLOUD_URL
        set(v) { prefs.edit().putString("server_url", CloudClient.normalizeServer(v)).apply() }
    /** Cloud sync: OFF until the user turns it on (after seeing what would be uploaded). */
    var syncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(v) {
            prefs.edit().putBoolean("sync_enabled", v).apply()
            if (v && cloud.session != null) { SyncWorker.schedulePeriodic(context, wifiOnly); SyncWorker.syncSoon(context, 1, wifiOnly) }
            else { SyncWorker.cancelAll(context); syncStatus.value = if (cloud.session == null) SyncStatus.Off else SyncStatus.Idle }
        }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("sync_wifi_only", false)
        set(v) { prefs.edit().putBoolean("sync_wifi_only", v).apply(); if (syncEnabled) syncEnabled = true }
    fun syncKind(kind: String): Boolean = prefs.getBoolean("sync_kind_$kind", true)
    fun setSyncKind(kind: String, on: Boolean) { prefs.edit().putBoolean("sync_kind_$kind", on).apply(); applySyncKinds() }
    private fun applySyncKinds() {
        db.syncKinds = buildSet {
            if (syncKind("conversation")) { add("conversation"); add("message") }
            if (syncKind("memory")) add("memory")
            if (syncKind("preference")) add("preference")
        }
    }

    /** Privacy: messages may go to the online AI only after the user allows it; memories need a second, separate permission. */
    var onlineAllowed: Boolean
        get() = prefs.getBoolean("online_ai_allowed", false)
        set(v) { prefs.edit().putBoolean("online_ai_allowed", v).apply() }
    var shareMemoriesOnline: Boolean
        get() = prefs.getBoolean("online_ai_memories", false)
        set(v) { prefs.edit().putBoolean("online_ai_memories", v).apply() }
    // ------------------------------------------------------------------ personal provider keys (this device only)
    private val providerKv = object : KeyValue {
        private val p = context.getSharedPreferences("abhify_providers", Context.MODE_PRIVATE)    // app-private; backups disabled app-wide
        override fun get(key: String) = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
    val providerStore = ProviderStore(providerKv, KeystoreBox("abhify_provider_keys_v1"))
    val providerClient = ProviderClient(UrlHttp(readTimeoutMs = 90_000), providerStore)

    /** Online mode's single target: a personal provider id, "shared", or unset (= first ready provider). */
    var onlineTargetSetting: String?
        get() = prefs.getString("online_target", null)
        set(v) { prefs.edit().apply { if (v == null) remove("online_target") else putString("online_target", v) }.apply() }
    /** The explicitly selected Online provider (persisted). Nothing implicit: unset means "none selected". */
    fun onlineTarget(): OnlineTarget? = when (val t = onlineTargetSetting) {
        "shared" -> OnlineTarget.Shared
        null -> null
        else -> Provider.entries.firstOrNull { it.id == t }?.let { OnlineTarget.Personal(it) }
    }
    /** Auto: may another configured provider be tried when the first fails before answering? Off by default. */
    var fallbackProviders: Boolean
        get() = prefs.getBoolean("fallback_providers", false)
        set(v) { prefs.edit().putBoolean("fallback_providers", v).apply() }
    /** Auto: may this phone's model answer when no online route works? */
    var fallbackToDevice: Boolean
        get() = prefs.getBoolean("fallback_device", true)
        set(v) { prefs.edit().putBoolean("fallback_device", v).apply() }
    /** Memories on/off: when off, nothing is saved by "remember that…" and no memory goes into any prompt. */
    var memoriesEnabled: Boolean
        get() = prefs.getBoolean("memories_enabled", true)
        set(v) { prefs.edit().putBoolean("memories_enabled", v).apply() }
    var customSystemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(v) { prefs.edit().putString("system_prompt", v.take(1500)).apply() }
    /** Custom instructions can be paused without losing the text. */
    var customPromptEnabled: Boolean
        get() = prefs.getBoolean("system_prompt_enabled", true)
        set(v) { prefs.edit().putBoolean("system_prompt_enabled", v).apply() }
    var personality: ai.abhify.android.chat.PromptBuilder.Personality
        get() = ai.abhify.android.chat.PromptBuilder.Personality.of(prefs.getString("personality", null))
        set(v) { prefs.edit().putString("personality", v.name).apply() }
    fun effectiveInstructions() = ai.abhify.android.chat.PromptBuilder.instructions(personality, customSystemPrompt, customPromptEnabled)

    val hybrid = HybridAi(cloud, providerClient, engine, ::ensureModel,
        { store.active()?.let { a -> catalog.byId(a.id)?.name ?: a.id } }, { p -> providerStore.config(p).model }, { p -> providerStore.config(p).maxTokens },
        deviceTemperature = { ai.abhify.android.settings.Personalization.Creativity.of(prefs.getString("creativity", null)).temperature })

    /** Something changed on this phone: upload it soon (if signed in, sync on and online; otherwise it waits). */
    fun localChanged() { if (cloud.session != null && syncEnabled) SyncWorker.syncSoon(context, wifiOnly = wifiOnly) }

    /**
     * Links this phone's local profile to the signed-in account (after the user confirmed). Nothing uploads
     * here: sync stays as the user set it (off by default). A different account than before gets a fresh sync start.
     */
    fun linkProfile() {
        val s = cloud.session ?: return
        db.link(s.serverUrl, s.userId, s.email)
        if (syncEnabled) { SyncWorker.schedulePeriodic(context, wifiOnly); SyncWorker.syncSoon(context, 1, wifiOnly) }
        syncStatus.value = SyncStatus.Idle
    }

    /** Unlink: every chat and memory stays on this phone; sync stops. */
    fun unlinkProfile() { db.unlink(); syncEnabled = false; SyncWorker.cancelAll(context); syncStatus.value = SyncStatus.Off }

    fun mirrorModels() = runCatching {
        db.mirrorModels(store.installed().map { mapOf("id" to it.id, "version" to it.version, "sha256" to it.sha256, "size" to it.sizeBytes,
            "runtime" to it.runtime, "context" to it.context, "selfTest" to it.selfTest, "installedAt" to it.installedAt) }, store.activeId())
    }

    fun onSignedOut() { SyncWorker.cancelAll(context); syncStatus.value = SyncStatus.Off }

    init {
        // v3.4: an existing install that never chose a mode used Auto; keep it Auto (only fresh installs default to the phone)
        if (!prefs.contains("ai_mode") && prefs.getBoolean("onboarded", false)) prefs.edit().putString("ai_mode", "AUTO").apply()
        // documents imported before v3.4 get their retrieval index in the background (text only, small, sequential)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(8_000)
            runCatching { db.unindexedDocs().forEach { id -> runCatching { ai.abhify.android.docs.RagIndex.index(db, id) } } }
        }
        // v3.5: uploads still waiting from before (app closed, phone restarted) are handed to the background worker again
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (db.queuedDriveFiles().isNotEmpty()) ai.abhify.android.drive.DriveUploadWorker.kick(this@AppGraph) }
        }
        // RC.3 -> RC.4: a phone with saved keys but no explicit selection gets its first configured provider selected once
        if (onlineTargetSetting == null) providerStore.priority.firstOrNull { providerStore.config(it).configured }?.let { onlineTargetSetting = it.id }
        applySyncKinds()
        runCatching { db.registerThisDevice(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) }
        // one-time: the pre-v3 name preference moves into the local profile
        prefs.getString("user_name", null)?.let { old ->
            if (old.isNotBlank() && db.profile().displayName.isBlank()) db.setProfileName(old)
            prefs.edit().remove("user_name").apply()
        }
        mirrorModels()
        db.onRemotePreference = { key, value ->
            when (key) {
                "theme" -> prefs.edit().putString("theme", value).apply()
                "read_aloud" -> prefs.edit().putBoolean("read_aloud", value == "true").apply()
                "ai_mode" -> if (runCatching { AiMode.valueOf(value) }.isSuccess) prefs.edit().putString("ai_mode", value).apply()
            }
        }
    }

    private val loadLock = Mutex()

    // ------------------------------------------------------------------ memory profile, heavy-job gate, OCR
    val heavy = ai.abhify.android.device.HeavyGate()

    /** Semantic search (bge-small, owner-approved). Off when memory is under pressure, battery saver is on, or the phone is hot. */
    val embeddings by lazy {
        ai.abhify.android.docs.EmbeddingManager(context.assets.open("embedding_catalog.json").bufferedReader().readText(),
            File(context.noBackupFilesDir, "models/embedding"), engine, { threads().coerceAtMost(4) }) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            when {
                heavy.underPressure() || memoryProfile().first == ai.abhify.android.device.MemoryProfile.LOW -> "low memory"
                pm.isPowerSaveMode -> "battery saver"
                android.os.Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> "the phone is hot"
                else -> null
            }
        }
    }
    /** "Semantic search" switch (on by default once the model is downloaded). */
    var semanticEnabled: Boolean
        get() = prefs.getBoolean("semantic_enabled", true)
        set(v) { prefs.edit().putBoolean("semantic_enabled", v).apply() }
    /** The embedder for this retrieval, or null = keyword search (never blocks a question on the model). */
    fun embedder(): ai.abhify.android.docs.Embedder? = runCatching { embeddings.embedder(semanticEnabled) }.getOrNull()
    /** null = Auto (decided from free memory each time). */
    var memoryChoice: ai.abhify.android.device.MemoryProfile?
        get() = prefs.getString("memory_profile", null)?.let { n -> ai.abhify.android.device.MemoryProfile.entries.firstOrNull { it.name == n } }
        set(v) { prefs.edit().putString("memory_profile", v?.name).apply() }
    fun memorySnapshot() = ai.abhify.android.device.MemoryPolicy.read(context)
    /** The profile in force now, and why it differs from the choice (if it does). */
    fun memoryProfile() = ai.abhify.android.device.MemoryPolicy.pick(memorySnapshot(), memoryChoice, heavy.underPressure())
    /** Text recognition (ML Kit) permission: null = not asked yet, then the user's answer. */
    var ocrAllowed: Boolean?
        get() = if (prefs.contains("ocr_allowed")) prefs.getBoolean("ocr_allowed", false) else null
        set(v) { prefs.edit().apply { if (v == null) remove("ocr_allowed") else putBoolean("ocr_allowed", v) }.apply() }

    /**
     * Runs an OCR job alone: refuses while the phone's AI is answering or memory is short, unloads the language model
     * first (its files stay; it reloads on the next local answer), and blocks local AI until OCR ends.
     */
    suspend fun <T> withOcr(block: suspend () -> T): T {
        if (engine.generating) throw ai.abhify.android.docs.DocumentException("Please wait: the phone's AI is answering. Text recognition starts after it finishes.")
        val snap = memorySnapshot()
        if (snap.availMb < ai.abhify.android.device.MemoryPolicy.MIN_OCR_AVAIL_MB)
            throw ai.abhify.android.docs.DocumentException("Only ${snap.availMb} MB of memory is free. Close other apps and retry.")
        heavy.tryEnter(ai.abhify.android.device.HeavyGate.Kind.OCR)?.let { throw ai.abhify.android.docs.DocumentException(it) }
        try {
            loadLock.withLock { engine.unload() }
            return block()
        } finally { heavy.exit(ai.abhify.android.device.HeavyGate.Kind.OCR) }
    }

    /**
     * Makes sure the active model is loaded for chat, quizzes and interview questions. null = ready,
     * else a user-readable reason. Checks free memory first, and falls back from GPU to CPU.
     */
    suspend fun ensureModel(): String? = loadLock.withLock {
        if (heavy.running == ai.abhify.android.device.HeavyGate.Kind.OCR)
            return@withLock "Text recognition is running. The phone's AI will be available as soon as it finishes (or cancel it)."
        val inst = store.active() ?: return@withLock "No model is installed."
        val model = catalog.byId(inst.id) ?: return@withLock "The installed model is not in this app's catalog."
        val file = store.modelFile(inst.id)
        if (!store.quickCheck(inst)) return@withLock "The model file is missing or damaged. Remove it in Settings and download it again."
        val gpu = gpuEnabled
        val l = engine.loaded
        if (l != null && l.path == file.absolutePath && l.context == inst.context && (l.gpuLayers > 0) == gpu) return@withLock null
        if (l != null) engine.unload()
        ModelSelector.loadBlocker(model, DeviceProbe.read(context).availRamMb)?.let { return@withLock it }
        var err = engine.load(file, inst.context, threads(), gpuLayers = if (gpu) 99 else 0)
        if (err != null && gpu) {                                          // GPU failed: CPU is always the fallback
            gpuEnabled = false
            err = engine.load(file, inst.context, threads(), gpuLayers = 0)?.let { "GPU failed, and the CPU fallback too: $it" }
        }
        err
    }

    fun backendLabel(): String = if ((engine.loaded?.gpuLayers ?: 0) > 0) "GPU" else "CPU"
}

/** Download → verify (size + SHA-256) → real self-test → activate. Never marks setup complete before a real answer. */
class SetupController(private val g: AppGraph) {
    sealed class State {
        data object Idle : State()
        data class Downloading(val modelId: String, val done: Long, val total: Long) : State()
        data class Paused(val modelId: String, val done: Long, val total: Long, val reason: String) : State()
        data class Verifying(val modelId: String) : State()
        data class SelfTesting(val modelId: String) : State()
        data class Failed(val modelId: String, val reason: String, val canResume: Boolean) : State()
        data class Installed(val modelId: String, val detail: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile var running = false; private set

    /** A partial download left from before (paused, network drop or the app was closed). */
    fun leftover(): Pair<CatalogModel, Long>? = g.catalog.models.firstNotNullOfOrNull { m ->
        g.store.partFile(m.id).takeIf { it.exists() && it.length() > 0 }?.let { m to it.length() }
    }

    fun pause() { stopRequested = true }
    fun cancel(modelId: String) {
        cancelRequested = true; stopRequested = true
        if (!running) { g.store.partFile(modelId).delete(); _state.value = State.Idle }
    }

    /** Blocking; runs inside DownloadService. */
    fun run(m: CatalogModel) {
        if (running) return
        running = true; stopRequested = false; cancelRequested = false
        try {
            val part = g.store.partFile(m.id)
            _state.value = State.Downloading(m.id, if (part.exists()) part.length() else 0, m.sizeBytes)
            val r = Downloader().download(m.url, m.sizeBytes, m.sha256, part, isStopped = { stopRequested }) { done, total ->
                _state.value = if (done == total) State.Verifying(m.id) else State.Downloading(m.id, done, total)
            }
            when (r) {
                is Downloader.Result.Interrupted -> {
                    if (cancelRequested) { part.delete(); _state.value = State.Idle }
                    else _state.value = State.Paused(m.id, r.bytes, m.sizeBytes, r.reason)
                }
                is Downloader.Result.Failed -> _state.value = State.Failed(m.id, r.reason, canResume = !r.partDeleted && part.exists())
                is Downloader.Result.Done -> {
                    _state.value = State.SelfTesting(m.id)
                    val res = g.store.install(m, r.file, g.runtimeLabel, m.defaultContext) { file -> selfTest(file) }
                    _state.value = if (res.passed) State.Installed(m.id, res.detail)
                    else State.Failed(m.id, "The model downloaded correctly but did not pass the test answer: ${res.detail}. " +
                        "Your previous model (if any) is unchanged.", canResume = true)
                }
            }
        } finally { running = false }
    }

    /** A real answer from the model, on the CPU. */
    fun selfTest(file: File): ModelStore.SelfTestResult = runBlocking {
        val err = g.engine.load(file, context = 1024, threads = g.threads(), gpuLayers = 0)
        if (err != null) return@runBlocking ModelStore.SelfTestResult(false, err)
        val text = StringBuilder()
        var stats: LlmEngine.Stats? = null
        var failure: String? = null
        g.engine.generate(listOf(PromptBuilder.Msg("system", "You are a helpful assistant."),
            PromptBuilder.Msg("user", "Reply with exactly one word: OK")), maxTokens = 8, temperature = 0f).collect { e ->
            when (e) {
                is LlmEngine.Event.Token -> text.append(e.text)
                is LlmEngine.Event.Finished -> stats = e.stats
                is LlmEngine.Event.Failed -> failure = e.message
            }
        }
        g.engine.unload()
        val s = stats
        when {
            failure != null -> ModelStore.SelfTestResult(false, failure!!)
            s == null || text.isBlank() -> ModelStore.SelfTestResult(false, "the model produced no text")
            else -> ModelStore.SelfTestResult(true, "answered \"${text.trim().take(20)}\" · first token %.0f ms · %.1f tokens/s"
                .format(s.firstTokenMs, s.tokensPerSecond))
        }
    }

    fun reset() { if (!running) _state.value = State.Idle }
}
