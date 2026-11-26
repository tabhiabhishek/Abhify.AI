package ai.abhify.android.ui

import ai.abhify.android.backup.AndroidRows
import ai.abhify.android.backup.Backup
import ai.abhify.android.backup.BackupException
import ai.abhify.android.backup.BackupIo
import ai.abhify.android.backup.Storage
import ai.abhify.android.chat.*
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveAuth
import ai.abhify.android.drive.DriveException
import ai.abhify.android.drive.DriveStatus
import ai.abhify.android.drive.UrlDriveTransport
import ai.abhify.android.learn.Studio
import ai.abhify.android.memory.MemoryKit
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())

// ====================================================================== personal memory
class MemoryState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    private val msg get() = vm.studio.message
    val items = mutableStateListOf<MemoryItem>()
    val query = mutableStateOf("")
    val category = mutableStateOf<String?>(null)
    val candidates = mutableStateListOf<MemoryKit.Candidate>()
    /** A memory waiting for the user's "yes, store it" because it looks sensitive or duplicates another. */
    data class Pending(val content: String, val category: String, val importance: Int, val expiresAt: Long?, val reasons: List<String>,
                       val conflictWith: MemoryItem? = null, val source: String = "manual")
    val pending = mutableStateOf<Pending?>(null)
    val bytes = mutableStateOf(0L)
    /** v3.8: review decisions already made ("keep both"), so the same pair isn't suggested again. */
    val dismissed = mutableStateOf<Set<String>>(emptySet())
    val related: (String, String) -> Boolean = { a, b -> ai.abhify.android.career.SkillTaxonomy.current?.let { t ->
        val x = t.normalize(a); val y = t.normalize(b); x != null && y != null && t.related(x, y) } ?: false }

    fun load() = scope.launch {
        val (l, b) = withContext(Dispatchers.IO) { g.db.memoryItems() to g.db.memoryBytes() }
        dismissed.value = withContext(Dispatchers.IO) { runCatching { g.db.dismissedMemoryPairs() }.getOrDefault(emptySet()) }
        items.clear(); items.addAll(l); bytes.value = b
    }
    fun reviews() = ai.abhify.android.memory.MemoryReview.review(items, dismissed = dismissed.value, related = related)
    fun dashboard() = ai.abhify.android.memory.MemoryReview.dashboard(items, reviews())

    /** "Keep both": remembered, not suggested again. */
    fun keepBoth(r: ai.abhify.android.memory.MemoryReview.Item) = scope.launch { withContext(Dispatchers.IO) { g.db.dismissMemoryPair(r.key, r.kind.name) }; load() }
    /** Conflict: keep [keep], delete the others (after the user chose). */
    fun keepOnly(r: ai.abhify.android.memory.MemoryReview.Item, keep: MemoryItem) = scope.launch {
        withContext(Dispatchers.IO) { r.memories.filter { it.id != keep.id }.forEach { g.db.deleteMemory(it.id) }; g.db.setMemoryReviewed(keep.id) }; load(); g.localChanged() }
    /** Expired / expiring / stale: "Keep" clears the expiry (expired/expiring) or just marks it reviewed (stale). */
    fun keepMemory(r: ai.abhify.android.memory.MemoryReview.Item) = scope.launch {
        val m = r.memories.single()
        withContext(Dispatchers.IO) { g.db.setMemoryReviewed(m.id, clearExpiry = r.kind != ai.abhify.android.memory.MemoryReview.Kind.STALE) }; load() }
    fun provenance(m: MemoryItem): String = ai.abhify.android.memory.MemoryReview.provenance(m.source,
        { id -> vm.conversations.firstOrNull { it.id == id }?.title ?: runCatching { g.db.conversationTitle(id) }.getOrNull() },
        { id -> runCatching { g.db.doc(id)?.name }.getOrNull() })
    fun visible() = items.filter { m -> (category.value == null || m.category == category.value) &&
        (query.value.isBlank() || m.content.contains(query.value.trim(), true) || m.category.contains(query.value.trim(), true)) }

    /** Adds a memory; sensitive-looking content or a near-duplicate needs an explicit second confirmation. */
    fun add(content: String, category: String, importance: Int, expiresInDays: Int?, confirmed: Boolean = false, source: String = "manual") {
        val c = content.trim(); if (c.isEmpty()) return
        val exp = expiresInDays?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 86_400_000L }
        if (!confirmed) {
            val clash = ai.abhify.android.memory.MemoryReview.conflictWith(c, items, related)
            val reasons = MemoryKit.sensitive(c).map { "looks like a $it" } + listOfNotNull(MemoryKit.duplicateOf(c, items)?.let { "similar to: \"${it.content.take(80)}\"" },
                clash?.let { "may contradict: \"${it.content.take(80)}\"" })
            if (reasons.isNotEmpty()) { pending.value = Pending(c, category, importance, exp, reasons, clash, source); return }
        }
        pending.value = null
        scope.launch { withContext(Dispatchers.IO) { g.db.addMemoryItem(c, category, importance, source, exp) }; load(); g.localChanged() }
    }
    fun confirmPending() { val p = pending.value ?: return; pending.value = null
        scope.launch { withContext(Dispatchers.IO) {
            val id = g.db.addMemoryItem(p.content, p.category, p.importance, p.source, p.expiresAt)
            // "keep both" for a possible contradiction: remember the decision
            p.conflictWith?.let { old -> g.db.memoryItems().firstOrNull { it.id == id }?.let { n ->
                g.db.dismissMemoryPair(ai.abhify.android.memory.MemoryReview.pairKey(ai.abhify.android.memory.MemoryReview.Kind.CONFLICT, listOf(old, n)), "CONFLICT") } }
        }; load(); g.localChanged() } }
    /** Conflict at add time: replace the existing memory's text with the new one (no second memory). */
    fun replacePending() { val p = pending.value ?: return; val old = p.conflictWith ?: return; pending.value = null
        scope.launch { withContext(Dispatchers.IO) { g.db.updateMemoryItem(old.id, p.content, p.category, maxOf(p.importance, old.importance), p.expiresAt ?: old.expiresAt) }; load(); g.localChanged() } }
    fun update(m: MemoryItem, content: String, category: String, importance: Int, expiresInDays: Int?) = scope.launch {
        val exp = expiresInDays?.let { if (it > 0) System.currentTimeMillis() + it * 86_400_000L else null } ?: m.expiresAt
        withContext(Dispatchers.IO) { g.db.updateMemoryItem(m.id, content, category, importance, exp) }; load(); g.localChanged()
    }
    fun delete(m: MemoryItem) = scope.launch { withContext(Dispatchers.IO) { g.db.deleteMemory(m.id) }; load(); g.localChanged() }

    /** The AI proposes facts from the current chat; nothing is stored until the user ticks and approves them. */
    fun suggestFromChat() {
        val convo = vm.messages.filter { it.text.isNotBlank() && !it.text.startsWith("⚠️") }.takeLast(24)
            .joinToString("\n") { (if (it.role == "user") "User: " else "Assistant: ") + it.text.take(800) }
        if (convo.isBlank()) { msg.value = "Open a chat with some messages first."; return }
        candidates.clear()
        vm.aiTask("suggest memories from this chat", Studio.SYSTEM, MemoryKit.extractionPrompt(convo.takeLast(3500)), "this conversation", 4000,
            MemoryKit.extractionPrompt(convo.takeLast(16_000))) { r ->
            val c = MemoryKit.parseCandidates(r.text, items)
            if (c.isEmpty()) msg.value = r.error ?: "No usable suggestions (the AI's answer wasn't valid JSON, or it found nothing durable). Nothing was saved."
            else candidates.addAll(c)
        }
    }
    fun approve(selected: List<MemoryKit.Candidate>) = scope.launch {
        val src = vm.conversationId.value?.let { "chat:$it" } ?: "chat"                      // provenance: which chat (v3.8 shows it)
        withContext(Dispatchers.IO) { selected.forEach { g.db.addMemoryItem(it.content, it.category, 2, src) } }
        candidates.clear(); load(); g.localChanged(); msg.value = "Saved ${selected.size} memories."
    }

    fun groups() = MemoryKit.duplicateGroups(items)
    /** Merge: keep one text (edited by the user) on the newest entry, delete the others. */
    fun merge(group: List<MemoryItem>, keep: String) = scope.launch {
        val head = group.first()
        withContext(Dispatchers.IO) { g.db.updateMemoryItem(head.id, keep, head.category, group.maxOf { it.importance }, head.expiresAt); group.drop(1).forEach { g.db.deleteMemory(it.id) } }
        load(); g.localChanged()
    }

    fun exportJson(): String = JSONObject().put("format", "abhify-memory").put("exported_at", System.currentTimeMillis())
        .put("memories", JSONArray(items.map { JSONObject().put("content", it.content).put("category", it.category).put("importance", it.importance)
            .put("created_at", it.createdAt).put("updated_at", it.updatedAt).put("source", it.source ?: JSONObject.NULL).put("expires_at", it.expiresAt ?: JSONObject.NULL) })).toString(2)
    fun exportMd(): String = "# Personal memory (Abhify)\n\n" + items.groupBy { it.category }.entries.joinToString("\n") { (c, l) -> "## $c\n" + l.joinToString("\n") { "- ${it.content}" } + "\n" }
    fun lastBackup(): BackupRecord? = g.db.backupRecords().firstOrNull { "Personal memory" in it.categories }
}

// ====================================================================== Google Drive
class DriveState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    val connected = mutableStateOf(g.prefs.getBoolean("drive_connected", false))
    val busy = mutableStateOf<String?>(null)
    val progress = mutableStateOf<Float?>(null)
    val message = mutableStateOf<String?>(null)
    val consent = mutableStateOf<PendingIntent?>(null)
    val files = mutableStateListOf<DriveFile>()
    val remote = mutableStateMapOf<String, List<DriveApi.Item>>()
    val lastSync = mutableStateOf(g.prefs.getLong("drive_last_sync", 0L).takeIf { it > 0 })
    /** A same-name, different-content file on Drive (a stored queue row): the user chooses; nothing was changed yet. */
    val conflict = mutableStateOf<DriveFile?>(null)
    val wifiOnly = mutableStateOf(g.prefs.getBoolean("drive_wifi_only", false))
    /** Background upload in progress (from WorkManager): file name and fraction. */
    val uploading = mutableStateOf<Pair<String, Float?>?>(null)
    private val store = ai.abhify.android.drive.DbQueueStore(g)
    private val queue = ai.abhify.android.drive.DriveQueue(store)

    init {
        scope.launch {
            androidx.work.WorkManager.getInstance(g.context).getWorkInfosForUniqueWorkFlow(ai.abhify.android.drive.DriveUploadWorker.WORK).collect { infos ->
                val running = infos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                val was = uploading.value
                uploading.value = running?.progress?.getString("name")?.let { n -> val p = running.progress; val t = p.getLong("total", 0)
                    n to (if (t > 0) p.getLong("sent", 0).toFloat() / t else null) } ?: if (running != null) ("the queue" to null) else null
                if (was != null && uploading.value == null) loadFiles()
            }
        }
    }

    private var token: String? = null; private var tokenAt = 0L
    private var afterAuth: (() -> Unit)? = null

    // ------------------------------------------------------------------ v4.2: account, folder status, setup
    /** The Google account Drive reported (email/name shown; the id keeps each account's folders and uploads apart). */
    val account = mutableStateOf(ai.abhify.android.drive.DriveIdentity.current(g))
    /** "available" (My Drive/Abhify.AI found), "missing" (deleted or no access), "unknown" (not checked yet). */
    val rootState = mutableStateOf("unknown")
    val lastOk = mutableStateOf(g.prefs.getLong("drive_last_ok", 0L).takeIf { it > 0 })
    /** The UI opens Android's account chooser when this is true (first connection, or Switch account). */
    val chooseAccount = mutableStateOf(false)
    /** Switch requested while this account still has waiting uploads: the UI asks first (they stay with this account). */
    val switchConfirm = mutableStateOf<Int?>(null)
    private var pickedAccount: String? = null
    private var cache: MutableMap<String, String> = ai.abhify.android.drive.DriveFolders.load(g, account.value?.id)
    private fun api() = DriveApi(UrlDriveTransport(), { token ?: throw DriveException("Not connected to Google Drive.", auth = true) }, cache)
    private fun saveCache() = ai.abhify.android.drive.DriveFolders.save(g, account.value?.id, cache)
    fun rootId(): String? = cache[DriveApi.ROOT]

    fun loadFiles() = scope.launch { val f = withContext(Dispatchers.IO) { g.db.driveFiles() }; files.clear(); files.addAll(f) }

    /**
     * Starts (or renews) Google authorization for the drive.file scope. A first connection lets the user pick the
     * account in Android's own chooser; an existing connection is renewed for the same account (silently when granted).
     */
    fun connect(then: (() -> Unit)? = null) {
        DriveAuth.unavailable(g.context)?.let { message.value = it; return }
        if (!vm.online.value) { message.value = "No internet connection. Google Drive needs one to connect; everything else keeps working offline."; return }
        afterAuth = then
        val email = pickedAccount ?: account.value?.email
        if (email == null && !connected.value) { chooseAccount.value = true; return }
        authorize(email)
    }
    /** Result of Android's account chooser (null = cancelled: nothing changes). */
    fun onAccountChosen(email: String?) {
        chooseAccount.value = false
        if (email == null) { afterAuth = null; message.value = ai.abhify.android.drive.DriveErrors.message(12501); return }
        pickedAccount = email; authorize(email)
    }
    private fun authorize(email: String?) {
        scope.launch {
            busy.value = "Connecting to Google…"
            when (val r = withContext(Dispatchers.IO) { DriveAuth.authorize(g.context, email) }) {
                is DriveAuth.Result.Token -> granted(r.token)
                is DriveAuth.Result.NeedsConsent -> consent.value = r.intent
                is DriveAuth.Result.Failed -> { message.value = r.message; afterAuth = null }
            }
            busy.value = null
        }
    }
    fun onConsentResult(data: Intent?) {
        consent.value = null
        when (val r = DriveAuth.fromIntent(g.context, data)) {
            is DriveAuth.Result.Token -> granted(r.token)
            is DriveAuth.Result.Failed -> { message.value = r.message; afterAuth = null }        // cancelled or denied: never "connected"
            else -> {}
        }
    }
    /** Access granted: confirm whose Drive it is, then find or create My Drive/Abhify.AI and its sub-folders (never duplicated). */
    private fun granted(t: String) {
        token = t; tokenAt = System.currentTimeMillis()
        scope.launch {
            busy.value = "Setting up My Drive → Abhify.AI…"
            try {
                val who = withContext(Dispatchers.IO) { DriveApi(UrlDriveTransport(), { t }).account() }
                ai.abhify.android.drive.DriveIdentity.set(g, who); account.value = ai.abhify.android.drive.DriveIdentity.current(g); pickedAccount = null
                cache = ai.abhify.android.drive.DriveFolders.load(g, who.permissionId)
                withContext(Dispatchers.IO) { val a = api(); a.folder(null); DriveApi.FOLDERS.forEach { a.folder(it) } }
                saveCache(); rootState.value = "available"; ai.abhify.android.drive.DriveIdentity.markOk(g); lastOk.value = System.currentTimeMillis()
                val first = !connected.value
                connected.value = true; g.prefs.edit().putBoolean("drive_connected", true).apply()
                if (first) message.value = "Google Drive connected as ${account.value?.label}. Your Abhify.AI files are organized in My Drive → Abhify.AI."
            } catch (e: DriveException) { if (e.auth) token = null; message.value = e.message; afterAuth = null; return@launch }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Couldn't finish connecting to Google Drive: ${e.message}"; afterAuth = null; return@launch }
            finally { busy.value = null }
            afterAuth?.let { afterAuth = null; it() }
            ai.abhify.android.drive.DriveUploadWorker.kick(g)                  // this account's waiting uploads continue
        }
    }
    /**
     * Forgets the connection here and revokes this app's access at Google. Files on Drive and on the phone are not
     * touched; each account's folder settings are kept for reconnecting; waiting uploads stay queued.
     */
    fun disconnect() = scope.launch {
        token?.let { t -> withContext(Dispatchers.IO) { DriveAuth.revoke(t) } }
        token = null; connected.value = false; remote.clear(); rootState.value = "unknown"
        g.prefs.edit().putBoolean("drive_connected", false).apply()
        message.value = "Disconnected. Your files on Google Drive and on this phone were not changed."
    }
    /** Switch account: asks first when this account has waiting uploads (they stay with it and continue if you switch back). */
    fun switchAccount() = scope.launch {
        val me = account.value?.id
        val waiting = withContext(Dispatchers.IO) { g.db.queuedDriveFiles().count { it.account == null || it.account == me } }
        if (waiting > 0 && switchConfirm.value == null) { switchConfirm.value = waiting; return@launch }
        switchConfirm.value = null; token = null; pickedAccount = null; chooseAccount.value = true; afterAuth = { refresh() }
    }
    fun waitingForOtherAccounts(): Int = ai.abhify.android.drive.DriveAccounts.waitingForOthers(files.map { it.account to it.status }, account.value?.id)
    fun accountLabel(id: String?): String = id?.let { ai.abhify.android.drive.DriveIdentity.known(g)[it]?.label } ?: "an earlier account"

    /** Checks the connection and the folder without creating anything. */
    fun check() = run("Checking Google Drive") { api ->
        val who = api.account()
        if (who.permissionId != account.value?.id) { ai.abhify.android.drive.DriveIdentity.set(g, who); withContext(Dispatchers.Main) { account.value = ai.abhify.android.drive.DriveIdentity.current(g) } }
        val root = api.findRoot()
        withContext(Dispatchers.Main) {
            rootState.value = if (root != null) "available" else "missing"
            ai.abhify.android.drive.DriveIdentity.markOk(g); lastOk.value = System.currentTimeMillis()
            message.value = if (root != null) "Connected as ${account.value?.label}. My Drive → Abhify.AI is available." else
                "Connected as ${account.value?.label}, but My Drive → Abhify.AI wasn't found (deleted or moved). Tap \"Recreate folder\"; nothing else is changed."
        }
    }
    /** Finds or re-creates My Drive/Abhify.AI and its sub-folders (existing files are never deleted or moved). */
    fun recreateFolder() = run("Creating My Drive → Abhify.AI") { api ->
        cache.clear(); api.folder(null); DriveApi.FOLDERS.forEach { api.folder(it) }
        withContext(Dispatchers.Main) { rootState.value = "available"; message.value = "My Drive → Abhify.AI is ready." }
    }

    // ------------------------------------------------------------------ v4.2: optional move of files from the 3.4–4.1 folders
    val migration = mutableStateListOf<ai.abhify.android.drive.DriveMigration.Move>()
    val migrationResult = mutableStateOf<String?>(null)
    fun scanLegacy() = run("Looking for files in older Abhify folders") { api ->
        val root = api.findRoot()
        if (root == null) { withContext(Dispatchers.Main) { message.value = "My Drive → Abhify.AI wasn't found." }; return@run }
        val legacy = api.list(root, foldersOnly = true).filter { it.name in DriveApi.LEGACY }.associate { it.name to (it.id to api.list(it.id)) }
        val plan = ai.abhify.android.drive.DriveMigration.plan(legacy)
        withContext(Dispatchers.Main) { migration.clear(); migration.addAll(plan); migrationResult.value = null
            if (plan.isEmpty()) message.value = "Nothing to organize: no files in older Abhify folders." }
    }
    /** Moves only the files the user selected and confirmed; file ids stay the same; failures leave the file where it was. */
    fun migrate(selected: List<ai.abhify.android.drive.DriveMigration.Move>) = run("Organizing ${selected.size} file(s)") { api ->
        val ok = mutableListOf<ai.abhify.android.drive.DriveMigration.Move>(); val bad = mutableListOf<Pair<ai.abhify.android.drive.DriveMigration.Move, String>>()
        for (m in selected) try {
            api.move(m.item.id, api.folder(m.toFolder), m.fromId); ok += m
            g.db.driveFiles().filter { it.driveId == m.item.id }.forEach { g.db.saveDriveFile(it.copy(folder = m.toFolder)) }
        } catch (e: DriveException) { bad += m to (e.message ?: "failed") }
        val r = ai.abhify.android.drive.DriveMigration.Result(ok, bad)
        withContext(Dispatchers.Main) { migration.removeAll(ok.toSet()); migrationResult.value = r.summary + (bad.firstOrNull()?.let { " — e.g. ${it.first.item.name}: ${it.second}" } ?: "") +
            ". Old folders were left in place (empty ones can be deleted in Google Drive if you like)." }
    }

    /** Runs a Drive job with a fresh token (re-authorizing silently or with consent), one job at a time. */
    private fun run(label: String, block: suspend (DriveApi) -> Unit) {
        if (busy.value != null) { message.value = "Please wait: ${busy.value}"; return }
        if (!connected.value) { connect { run(label, block) }; return }
        if (token == null || System.currentTimeMillis() - tokenAt > 45 * 60_000) { token = null; connect { run(label, block) }; return }
        scope.launch {
            cache = ai.abhify.android.drive.DriveFolders.load(g, account.value?.id)    // the background worker may have found folders
            busy.value = label; message.value = null
            try { withContext(Dispatchers.IO) { block(api()) } }
            catch (e: DriveException) { if (e.auth) { token = null }; message.value = e.message }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Google Drive error: ${e.message}" }
            finally { saveCache(); busy.value = null; progress.value = null; loadFiles() }
        }
    }

    /** Lists what Abhify has on Drive and updates the status of known files. Never deletes or downloads anything. */
    fun refresh() = run("Checking Google Drive") { api ->
        val root = api.folder(null)
        val folders = api.list(root, foldersOnly = true)
        remote.clear()
        folders.forEach { f -> remote[f.name] = api.list(f.id) }
        val onDrive = remote.values.flatten().associateBy { it.id }
        g.db.driveFiles().forEach { r ->
            val item = r.driveId?.let { onDrive[it] }
            val status = when { r.status in setOf(DriveStatus.FAILED, DriveStatus.PENDING_UPLOAD, DriveStatus.CANCELLED, DriveStatus.CONFLICT, DriveStatus.REMOVED) -> r.status
                item == null && r.driveId != null -> DriveStatus.LOCAL_ONLY; item != null -> DriveStatus.SYNCED; else -> r.status }
            if (status != r.status) g.db.saveDriveFile(r.copy(status = status, error = if (status == DriveStatus.LOCAL_ONLY) "Not found on Drive any more (nothing was deleted here)." else r.error))
        }
        val now = System.currentTimeMillis(); g.prefs.edit().putLong("drive_last_sync", now).apply(); lastSync.value = now
    }

    /**
     * v3.5: every upload goes into the durable queue and a background job sends it (also after the app is closed),
     * when there is a network — Wi-Fi only if chosen. Unchanged files are skipped; a different file with the same name
     * becomes a conflict for the user to resolve. Returns the queue row id.
     */
    suspend fun enqueue(kind: String, localRef: String, folder: String, name: String, mime: String, bytes: ByteArray): Long? {
        if (!connected.value) { message.value = "Connect Google Drive first."; return null }
        val id = withContext(Dispatchers.IO) { ai.abhify.android.drive.DriveUploadWorker.queue(g, kind, localRef, folder, name, mime, bytes) }
        message.value = "Queued \"$name\" for Abhify.AI/$folder. It uploads in the background" + (if (wifiOnly.value) " on Wi-Fi" else " when online") + ", even if you close the app."
        loadFiles(); return id
    }
    fun upload(kind: String, localRef: String, folder: String, name: String, mime: String, bytes: ByteArray) {
        scope.launch { enqueue(kind, localRef, folder, name, mime, bytes) }
    }

    /** The user's choice for a stored conflict: replace the Drive file's content, or upload as a numbered copy. */
    fun resolve(f: DriveFile, replace: Boolean) = scope.launch {
        conflict.value = null
        withContext(Dispatchers.IO) { queue.resolve(f, replace) }
        ai.abhify.android.drive.DriveUploadWorker.kick(g); loadFiles()
        message.value = if (replace) "Queued: replace ${f.name} on Drive." else "Queued: upload ${f.name} as a numbered copy."
    }

    /** Retries a failed, cancelled or stuck upload from the local queue. */
    fun retry(f: DriveFile) = scope.launch {
        if (!withContext(Dispatchers.IO) { store.hasBytes(f.id) }) { message.value = "The data for this upload is gone; upload it again from its screen."; return@launch }
        withContext(Dispatchers.IO) { queue.retry(f) }; ai.abhify.android.drive.DriveUploadWorker.kick(g); loadFiles()
    }
    /** Stops waiting uploads (they stay listed with Retry) and the running one at its next chunk. */
    fun cancelAll() = scope.launch {
        withContext(Dispatchers.IO) { queue.cancelAll() }; ai.abhify.android.drive.DriveUploadWorker.cancel(g.context); loadFiles()
        message.value = "Uploads cancelled. Nothing was deleted; tap Retry on an item to send it later."
    }
    fun setWifiOnly(on: Boolean) { wifiOnly.value = on; g.prefs.edit().putBoolean("drive_wifi_only", on).apply(); ai.abhify.android.drive.DriveUploadWorker.kick(g) }

    fun forget(f: DriveFile) = scope.launch { withContext(Dispatchers.IO) { g.db.forgetDriveFile(f.id); store.dropBytes(f.id) }; loadFiles() }

    /** Downloads a Drive file: documents go into the library (offline, indexed); backups open the restore preview. */
    fun download(folder: String, item: DriveApi.Item) = run("Downloading ${item.name}") { api ->
        val bytes = api.download(item.id)
        File(g.context.filesDir, "drive").apply { mkdirs() }.let { File(it, item.name.replace(Regex("[^\\w. ()-]"), "_")).writeBytes(bytes) }
        withContext(Dispatchers.Main) {
            if (folder.endsWith("Backups")) vm.backup.load(bytes, item.name)
            else vm.studio.importBytes(item.name, item.mime, bytes, "drive:${item.id}")
        }
    }
    /** Moves one Abhify file on Drive to Drive's trash (after the user confirmed). Local copies stay. */
    fun trash(item: DriveApi.Item) = run("Deleting ${item.name} on Drive") { api ->
        api.trash(item.id)
        g.db.driveFiles().filter { it.driveId == item.id }.forEach { g.db.saveDriveFile(it.copy(status = DriveStatus.LOCAL_ONLY, driveId = null, error = "Deleted on Drive by you.")) }
        remote.keys.toList().forEach { k -> remote[k] = remote[k].orEmpty().filter { it.id != item.id } }
        message.value = "Moved ${item.name} to Google Drive's trash. Nothing on this phone was deleted."
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) { "pdf" -> "application/pdf"; "md" -> "text/markdown"; "json" -> "application/json"
        "txt" -> "text/plain"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "abhifybak" -> "application/octet-stream"; else -> "application/octet-stream" }

    // ------------------------------------------------------------------ what can be uploaded (always a user action)
    fun uploadDocText(d: LibraryDoc) = scope.launch {
        val text = withContext(Dispatchers.IO) { g.db.docText(d.id) } ?: return@launch
        upload("doc", d.id.toString(), DriveApi.DOCUMENTS, d.name.substringBeforeLast('.') + ".txt", "text/plain", ai.abhify.android.docs.Library.readable(text).toByteArray())
    }
    fun uploadFile(name: String, mime: String?, bytes: ByteArray) = upload("file", "$name:${Backup.sha256(bytes).take(12)}", DriveApi.IMPORTS, name, mime ?: mimeOf(name), bytes)
    fun uploadConversation(c: ChatDb.Conversation) = scope.launch {
        val md = vm.conversationMarkdown(c.id)
        upload("conversation", c.uid, DriveApi.EXPORTS, (c.title.ifBlank { "chat" }.take(50) + ".md").replace('/', '-'), "text/markdown", md.toByteArray())
    }
    fun uploadNotes() = scope.launch {
        val md = withContext(Dispatchers.IO) { g.db.workspaces().joinToString("\n\n") { w -> "# ${w.name}\n\n" + g.db.notes(w.id).joinToString("\n\n") { "## ${it.title}\n${it.body}" } } }
        upload("notes", "all", DriveApi.EXPORTS, "abhify-notes-${stamp()}.md", "text/markdown", md.toByteArray())
    }
}

// ====================================================================== backup and restore
class BackupState(private val vm: AppViewModel) {
    private val g = vm.g
    private val scope get() = vm.viewModelScope
    val selected = mutableStateListOf<String>().apply { addAll(Backup.CATEGORIES.keys.filter { it != "Settings" }) }
    val passphrase = mutableStateOf("")
    val busy = mutableStateOf<String?>(null)
    val message = mutableStateOf<String?>(null)
    /** A backup opened for restore: its contents, integrity, and what a restore would add or skip. */
    data class Loaded(val name: String, val bytes: ByteArray, val parsed: Backup.Parsed?, val preview: List<BackupIo.Plan>, val error: String?)
    val loaded = mutableStateOf<Loaded?>(null)
    val result = mutableStateOf<String?>(null)
    val history = mutableStateListOf<BackupRecord>()

    fun loadHistory() = scope.launch { val h = withContext(Dispatchers.IO) { g.db.backupRecords() }; history.clear(); history.addAll(h) }

    private fun settings(): Map<String, Any?> = g.prefs.all.filterKeys { it in Backup.SETTINGS_WHITELIST }

    /** Builds the backup bytes (encrypted when a passphrase is set). Contains no keys, tokens or passwords. */
    suspend fun create(categories: List<String> = selected.toList()): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val tables = ai.abhify.android.backup.backupTables(g, categories, settings())
        var bytes = Backup.encode(tables, categories, ai.abhify.android.BuildConfig.VERSION_NAME)
        val enc = passphrase.value.isNotEmpty()
        if (enc) bytes = Backup.encrypt(bytes, passphrase.value)
        "abhify-backup-${stamp()}.json" to bytes
    }
    fun record(location: String, categories: List<String>, bytes: ByteArray) = scope.launch {
        withContext(Dispatchers.IO) { g.db.addBackupRecord(location, categories.joinToString(", "), bytes.size.toLong(), Backup.sha256(bytes), passphrase.value.isNotEmpty()) }; loadHistory()
    }
    fun toDrive(memoryOnly: Boolean = false) = scope.launch {
        val cats = if (memoryOnly) listOf("Personal memory") else selected.toList()
        if (cats.isEmpty()) { message.value = "Choose what to back up."; return@launch }
        // 7.2: a backup for Google Drive is always encrypted; an empty passphrase is refused, never uploaded as plaintext
        ai.abhify.android.backup.BackupPolicy.passphraseProblem(passphrase.value)?.let { message.value = it; return@launch }
        val (name, bytes) = try { create(cats) } catch (e: IllegalArgumentException) { message.value = e.message; return@launch }
        val qid = vm.drive.enqueue("backup", name, DriveApi.BACKUPS, if (memoryOnly) name.replace("abhify-backup", "abhify-memory") else name,
            "application/json", bytes) ?: return@launch
        withContext(Dispatchers.IO) { g.db.addBackupRecord("Google Drive", cats.joinToString(", "), bytes.size.toLong(), Backup.sha256(bytes), passphrase.value.isNotEmpty(),
            uri = "drive-queue:$qid", status = "queued") }
        loadHistory()
    }

    // ------------------------------------------------------------------ v3.5 scheduled backups
    val schedule = mutableStateOf(ai.abhify.android.backup.BackupSchedule.parse(g.prefs.getString(ai.abhify.android.backup.BackupSchedule.PREF, null)))
    val schedulePassSet = mutableStateOf(ai.abhify.android.backup.SchedulePassphrase.isSet(g))
    fun lastRun(): Triple<Long, Boolean, String?>? = g.prefs.getLong("backup_last_run", 0L).takeIf { it > 0 }?.let {
        Triple(it, g.prefs.getBoolean("backup_last_ok", true), g.prefs.getString("backup_last_error", null)) }
    fun nextRun(): Long? = schedule.value.takeIf { it.enabled }?.nextRun(java.time.ZonedDateTime.now())?.toInstant()?.toEpochMilli()

    /** Saves and applies the schedule. A new passphrase (if typed) replaces the stored one; Drive requires one. */
    fun saveSchedule(cfg: ai.abhify.android.backup.BackupSchedule, newPassphrase: String?): Boolean {
        if (!newPassphrase.isNullOrEmpty() && newPassphrase.length < 8) { message.value = "The passphrase needs at least 8 characters."; return false }
        val hasPass = !newPassphrase.isNullOrEmpty() || schedulePassSet.value
        cfg.problem(hasPass, vm.drive.connected.value)?.let { message.value = it; return false }
        if (!newPassphrase.isNullOrEmpty()) { ai.abhify.android.backup.SchedulePassphrase.set(g, newPassphrase); schedulePassSet.value = true }
        g.prefs.edit().putString(ai.abhify.android.backup.BackupSchedule.PREF, cfg.json()).apply(); schedule.value = cfg
        ai.abhify.android.backup.ScheduledBackupWorker.apply(g.context, cfg)
        message.value = if (cfg.enabled) "Scheduled. Next backup about " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(nextRun()!!)) +
            " (Android may run it later, e.g. when the battery is low)." else "Scheduled backups are off."
        return true
    }
    fun forgetSchedulePassphrase() {
        ai.abhify.android.backup.SchedulePassphrase.set(g, null); schedulePassSet.value = false
    }
    fun backupNow() {
        val cfg = schedule.value
        cfg.copy(enabled = true).problem(schedulePassSet.value, vm.drive.connected.value)?.let { message.value = it; return }
        ai.abhify.android.backup.ScheduledBackupWorker.runNow(g.context, cfg.copy(enabled = true))
        message.value = "Backing up now with the scheduled settings. The history below updates when it's done."
    }

    /** Opens a backup for restore: decrypts if needed, checks integrity, and previews (nothing is changed yet). */
    fun load(bytes: ByteArray, name: String) = scope.launch {
        result.value = null
        loaded.value = withContext(Dispatchers.IO) {
            try {
                val p = Backup.decode(bytes, passphrase.value.ifEmpty { null })
                Loaded(name, bytes, p, BackupIo.preview(AndroidRows(g.db.readableDatabase), p), null)
            } catch (e: BackupException) { Loaded(name, bytes, null, emptyList(), e.message) }
            catch (e: Exception) { Loaded(name, bytes, null, emptyList(), "This file can't be read as a backup.") }
        }
    }
    fun retryWithPassphrase() { loaded.value?.let { load(it.bytes, it.name) } }

    /** Additive restore of the chosen categories (existing rows are kept; duplicates skipped). Settings only if asked. */
    fun restore(categories: List<String>, applySettings: Boolean) = scope.launch {
        val l = loaded.value ?: return@launch; val p = l.parsed ?: return@launch
        busy.value = "Restoring"
        try {
            val tables = categories.flatMap { Backup.CATEGORIES[it].orEmpty() }.toSet()
            val r = withContext(Dispatchers.IO) { BackupIo.restore(AndroidRows(g.db.writableDatabase), p, tables) }
            val files = if ("App files" in categories) withContext(Dispatchers.IO) { ai.abhify.android.backup.restoreAppFiles(g, p) } else null
            if (applySettings) r.settings.forEach { (k, v) -> g.prefs.edit().apply { when (v) { is Boolean -> putBoolean(k, v); is Number -> putLong(k, v.toLong()); null -> {}; else -> putString(k, v.toString()) } }.apply() }
            result.value = "Restored: " + r.inserted.filterValues { it > 0 }.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "nothing new" } +
                ". Already present (skipped): " + r.skipped.values.sum() + (if (applySettings && r.settings.isNotEmpty()) ". Settings applied (restart the app to see all of them)." else ".") +
                (files?.let { " $it. Restart the app to load them." } ?: "")
            loaded.value = null; g.localChanged()
            // restored documents have no retrieval index yet: build it now (text only, small)
            withContext(Dispatchers.IO) { g.db.unindexedDocs().forEach { runCatching { ai.abhify.android.docs.RagIndex.index(g.db, it) } } }
            vm.memory.load(); vm.loadHistory()
        } catch (e: BackupException) { message.value = e.message }
        catch (e: Exception) { message.value = "Restore failed and nothing was changed: ${e.message}" }
        finally { busy.value = null }
    }
}

// ====================================================================== storage
class StorageState(private val vm: AppViewModel) {
    val report = mutableStateOf<Storage.Report?>(null)
    fun measure() = vm.viewModelScope.launch { report.value = withContext(Dispatchers.IO) { Storage.measure(vm.g) } }
    fun clearCache() = vm.viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { Storage.clearCache(vm.g) }
        vm.studio.message.value = "Cleared ${Storage.human(freed)} of temporary files."; measure()
    }
}
