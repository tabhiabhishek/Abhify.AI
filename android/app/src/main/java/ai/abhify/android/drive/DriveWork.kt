package ai.abhify.android.drive

import ai.abhify.android.AppGraph
import ai.abhify.android.backup.BackupSchedule
import ai.abhify.android.backup.Retention
import ai.abhify.android.chat.DriveFile
import ai.abhify.android.chat.driveFile
import ai.abhify.android.chat.driveFiles
import ai.abhify.android.chat.queuedDriveFiles
import ai.abhify.android.chat.saveDriveFile
import ai.abhify.android.graph
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** The queue on this phone: rows in drive_files, bytes in files/drive_queue/<id>.bin. */
class DbQueueStore(private val g: AppGraph) : QueueStore {
    private val dir get() = File(g.context.filesDir, "drive_queue").apply { mkdirs() }
    override fun pending() = g.db.queuedDriveFiles()
    override fun save(f: DriveFile) { g.db.saveDriveFile(f) }
    override fun bytes(id: Long): ByteArray? = File(dir, "$id.bin").takeIf { it.exists() }?.readBytes()
    override fun dropBytes(id: Long) { File(dir, "$id.bin").delete() }
    fun putBytes(id: Long, bytes: ByteArray) = File(dir, "$id.bin").writeBytes(bytes)
    fun hasBytes(id: Long) = File(dir, "$id.bin").exists()
    fun queuedBytes(): Long = pending().sumOf { File(dir, "${it.id}.bin").length() }
}

/**
 * Folder ids found or created on Drive, per Google account (v4.2), shared by the app screen and the background worker.
 * The 3.4–4.1 single cache ("drive_folders") is adopted once by the first account that connects (it was that phone's
 * only account); ids are re-validated on every use anyway, and another account can't open them.
 */
object DriveFolders {
    private fun read(g: AppGraph, key: String): MutableMap<String, String> = runCatching {
        JSONObject(g.prefs.getString(key, "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap() } }.getOrDefault(HashMap())
    fun load(g: AppGraph, account: String?): MutableMap<String, String> {
        if (account == null) return HashMap()
        val key = DriveAccounts.folderKey(account)
        if (!g.prefs.contains(key) && g.prefs.contains("drive_folders") && !g.prefs.contains("drive_folders_adopted")) {
            g.prefs.edit().putString(key, g.prefs.getString("drive_folders", "{}")).putString("drive_folders_adopted", account).apply()
        }
        return read(g, key)
    }
    fun save(g: AppGraph, account: String?, m: Map<String, String>) { if (account != null) g.prefs.edit().putString(DriveAccounts.folderKey(account), JSONObject(m as Map<*, *>).toString()).apply() }
}

/** The connected Google account as seen by Drive (no password, no token stored): id for bookkeeping, email/name to show. */
object DriveIdentity {
    data class Who(val id: String, val email: String?, val name: String?) { val label get() = DriveAccounts.label(email, name) }
    fun current(g: AppGraph): Who? = g.prefs.getString("drive_account_id", null)?.let { Who(it, g.prefs.getString("drive_account_email", null), g.prefs.getString("drive_account_name", null)) }
    fun set(g: AppGraph, a: DriveApi.Account) {
        g.prefs.edit().putString("drive_account_id", a.permissionId).putString("drive_account_email", a.email).putString("drive_account_name", a.name).apply()
        val known = runCatching { JSONObject(g.prefs.getString("drive_accounts", "{}")!!) }.getOrDefault(JSONObject())
        known.put(a.permissionId, JSONObject().put("email", a.email ?: JSONObject.NULL).put("name", a.name ?: JSONObject.NULL))
        g.prefs.edit().putString("drive_accounts", known.toString()).apply()
    }
    /** Every account that was connected here (for "waiting for <account>" and switching back); never removed by Disconnect. */
    fun known(g: AppGraph): Map<String, Who> = runCatching { JSONObject(g.prefs.getString("drive_accounts", "{}")!!).let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Who(k, it.optString("email").ifEmpty { null }.takeIf { _ -> !it.isNull("email") },
            it.optString("name").ifEmpty { null }.takeIf { _ -> !it.isNull("name") }) } } } }.getOrDefault(emptyMap())
    fun markOk(g: AppGraph) = g.prefs.edit().putLong("drive_last_ok", System.currentTimeMillis()).apply()
}

/**
 * Drains the upload queue in the background (v3.5). Runs only with a network (Wi-Fi only if the user chose that),
 * gets a fresh token silently (never shows UI; if Google needs the user, everything waits for "Connect again"),
 * retries transient failures with exponential backoff, and keeps going after the app is closed. Large queues run as
 * a foreground job with a notification so Android doesn't stop them half-way.
 */
class DriveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val store = DbQueueStore(g)
        if (store.pending().isEmpty()) return Result.success()
        if (!g.prefs.getBoolean("drive_connected", false)) { markAll(store, DriveQueue.NEEDS_SIGN_IN); return Result.success() }
        DriveAuth.unavailable(applicationContext)?.let { markAll(store, it); return Result.success() }
        val token = when (val a = DriveAuth.authorize(applicationContext, DriveIdentity.current(g)?.email)) {
            is DriveAuth.Result.Token -> a.token
            is DriveAuth.Result.NeedsConsent -> { markAll(store, DriveQueue.NEEDS_SIGN_IN); return Result.success() }   // wait for the user, don't loop
            is DriveAuth.Result.Failed -> { markAll(store, a.message); return if (runAttemptCount < 5) Result.retry() else Result.success() }
        }
        if (store.queuedBytes() > FOREGROUND_BYTES) runCatching { setForeground(foreground("Uploading to Google Drive…")) }   // may be refused; then best effort
        val probe = DriveApi(UrlDriveTransport(), { token })
        // whose Drive is this? uploads go only to the account they were made for
        val who = try { probe.account() } catch (e: DriveException) { markAll(store, e.message ?: "Couldn't reach Google Drive."); return if (e.auth) Result.success() else Result.retry() }
        val current = DriveIdentity.current(g)
        if (current == null) DriveIdentity.set(g, who)
        else if (current.id != who.permissionId) { markAll(store, "Waiting: Google returned a different account than the connected one. Open Google Drive in Abhify to reconnect."); return Result.success() }
        val folders = DriveFolders.load(g, who.permissionId)
        val api = DriveApi(UrlDriveTransport(), { token }, folders)
        val summary = try {
            DriveQueue(store).runAll(api, who.permissionId, stopped = { isStopped }) { f, sent, total ->
                if (isStopped) throw java.util.concurrent.CancellationException("stopped")                   // session is saved: resumes next time
                setProgressAsync(workDataOf("name" to f.name, "sent" to sent, "total" to total))
            }
        } finally { DriveFolders.save(g, who.permissionId, folders) }
        if (summary.authStop == null) DriveIdentity.markOk(g)
        // scheduled backups that just reached Drive: confirm them, then apply the user's retention (only Abhify's own)
        summary.synced.filter { it.kind == KIND_AUTO_BACKUP || it.kind == "backup" }.forEach { f ->
            runCatching { ai.abhify.android.backup.BackupRecords.confirmDrive(g, f.id) } }
        if (summary.synced.any { it.kind == KIND_AUTO_BACKUP }) runCatching { pruneDrive(g, api, who.permissionId) }
        return when {
            summary.authStop != null -> Result.success()
            summary.retryLater -> Result.retry()
            else -> Result.success()
        }
    }

    private fun markAll(store: DbQueueStore, why: String) = store.pending().forEach { store.save(it.copy(error = why)) }

    private fun pruneDrive(g: AppGraph, api: DriveApi, account: String) {
        val keep = BackupSchedule.parse(g.prefs.getString(BackupSchedule.PREF, null)).keep
        val owned = g.db.driveFiles().filter { it.kind == KIND_AUTO_BACKUP && it.status == DriveStatus.SYNCED && it.driveId != null && (it.account == null || it.account == account) }
        Retention.select(owned, keep) { Retention.Owned(it.id, it.queuedAt ?: it.updatedAt, auto = true, ok = true) }.forEach { old ->
            api.trash(old.driveId!!)
            g.db.saveDriveFile(old.copy(status = DriveStatus.REMOVED, error = "Moved to Drive's trash: older than your newest $keep scheduled backups."))
            ai.abhify.android.backup.BackupRecords.removedDrive(g, old.id)
        }
    }

    private fun foreground(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Google Drive uploads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Abhify")
            .setContentText(text).setOngoing(true).setSilent(true).build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val WORK = "abhify-drive-upload"
        const val KIND_AUTO_BACKUP = "backup-auto"
        private const val CHANNEL = "drive_uploads"
        private const val NOTIFICATION_ID = 4207
        private const val FOREGROUND_BYTES = 5L * 1024 * 1024

        fun constraints(wifiOnly: Boolean): Constraints =
            Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        /** One drain run after any running one (never two at once); waits for the network; backs off 30 s, 60 s, 120 s… */
        fun enqueue(context: Context, wifiOnly: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DriveUploadWorker>().setConstraints(constraints(wifiOnly))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

        /** Puts bytes in the queue and schedules the background upload. Returns the queue row id. */
        fun queue(g: AppGraph, kind: String, localRef: String, folder: String, name: String, mime: String, bytes: ByteArray): Long {
            val store = DbQueueStore(g)
            val prev = g.db.driveFile(kind, localRef)?.takeIf { it.status != DriveStatus.REMOVED }
            val acct = DriveIdentity.current(g)?.id                                                  // v4.2: the upload belongs to the connected account
            val id = g.db.saveDriveFile(DriveFile(prev?.id ?: 0, kind, localRef, name, DriveApi.canonical(folder)!!, prev?.driveId?.takeIf { prev.account == null || prev.account == acct },
                prev?.md5, bytes.size.toLong(), null, DriveStatus.PENDING_UPLOAD, null, 0, mode = null, attempts = 0, session = null, mime = mime,
                queuedAt = System.currentTimeMillis(), account = acct))
            store.putBytes(id, bytes)
            enqueue(g.context, g.prefs.getBoolean("drive_wifi_only", false))
            return id
        }
        fun kick(g: AppGraph) = enqueue(g.context, g.prefs.getBoolean("drive_wifi_only", false))
    }
}
