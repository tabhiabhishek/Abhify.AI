package ai.abhify.android.backup

import ai.abhify.android.AppGraph
import ai.abhify.android.chat.addBackupRecord
import ai.abhify.android.chat.backupRecords
import ai.abhify.android.chat.setBackupStatus
import ai.abhify.android.cloud.KeystoreBox
import ai.abhify.android.graph
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The scheduled-backup passphrase, sealed by an Android Keystore key (like provider keys). Never in a backup or log. */
object SchedulePassphrase {
    private const val PREF = "backup_schedule_pass"
    private val box get() = KeystoreBox("abhify_backup_schedule_v1")
    fun get(g: AppGraph): String? = runCatching { g.prefs.getString(PREF, null)?.let { String(box.open(Base64.getDecoder().decode(it)), Charsets.UTF_8) } }.getOrNull()
    fun set(g: AppGraph, p: String?) {
        if (p.isNullOrEmpty()) g.prefs.edit().remove(PREF).apply()
        else g.prefs.edit().putString(PREF, Base64.getEncoder().encodeToString(box.seal(p.toByteArray(Charsets.UTF_8)))).apply()
    }
    fun isSet(g: AppGraph) = g.prefs.contains(PREF)
}

/** Backup history rows that follow a queued Drive upload ("drive-queue:<id>"). */
object BackupRecords {
    private fun byQueue(g: AppGraph, queueId: Long) = g.db.backupRecords(500).filter { it.uri == "drive-queue:$queueId" }
    fun confirmDrive(g: AppGraph, queueId: Long) = byQueue(g, queueId).filter { it.status == "queued" }.forEach { g.db.setBackupStatus(it.id, "ok") }
    fun removedDrive(g: AppGraph, queueId: Long) = byQueue(g, queueId).forEach { g.db.setBackupStatus(it.id, "removed", "Removed by retention (Drive trash).") }
}

/**
 * Makes one backup from the user's schedule: the chosen categories only, encrypted with the stored passphrase
 * (when set), written to the chosen folder on this phone. Then the folder's retention runs. 7.2: scheduled backups are
 * never uploaded to Google Drive (Drive uploads only happen when the user starts them, always encrypted). Never touches files Abhify didn't record as its own scheduled backups.
 */
class ScheduledBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val cfg = BackupSchedule.parse(g.prefs.getString(BackupSchedule.PREF, null))
        if (!cfg.enabled) return Result.success()
        val pass = SchedulePassphrase.get(g)
        cfg.problem(pass != null, g.prefs.getBoolean("drive_connected", false))?.let { why -> lastRun(g, false, why); return Result.success() }
        val started = System.currentTimeMillis()
        return try {
            val tables = backupTables(g, cfg.categories, g.prefs.all.filterKeys { it in Backup.SETTINGS_WHITELIST })
            var bytes = Backup.encode(tables, cfg.categories, ai.abhify.android.BuildConfig.VERSION_NAME)
            val encrypted = pass != null
            if (pass != null) bytes = Backup.encrypt(bytes, pass)
            val name = "abhify-backup-auto-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".json"
            val cats = cfg.categories.joinToString(", "); val sha = Backup.sha256(bytes)
            val problems = mutableListOf<String>()
            cfg.folderUri?.let { tree ->
                try {
                    val doc = writeToFolder(Uri.parse(tree), name, bytes)
                    g.db.addBackupRecord("Folder: ${cfg.folderName ?: "chosen folder"}", cats, bytes.size.toLong(), sha, encrypted, auto = true, uri = doc.toString())
                    pruneFolder(g, cfg.keep)
                } catch (e: Exception) {
                    val why = if (e is SecurityException) "Abhify can no longer write to that folder. Choose it again in Backup & restore." else "Couldn't write to the folder: ${e.message}"
                    g.db.addBackupRecord("Folder: ${cfg.folderName ?: "chosen folder"}", cats, bytes.size.toLong(), sha, encrypted, auto = true, status = "failed", error = why)
                    problems += why
                }
            }
            lastRun(g, problems.isEmpty(), problems.firstOrNull(), System.currentTimeMillis() - started)
            Result.success()
        } catch (e: Exception) {
            lastRun(g, false, "Backup failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun writeToFolder(tree: Uri, name: String, bytes: ByteArray): Uri {
        val cr = applicationContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, "application/json", name) ?: throw IllegalStateException("the folder refused a new file")
        cr.openOutputStream(doc)!!.use { it.write(bytes) }
        return doc
    }

    /** Folder retention: only documents Abhify wrote for the schedule (their URIs are recorded); the newest [keep] stay. */
    private fun pruneFolder(g: AppGraph, keep: Int) {
        val owned = g.db.backupRecords(500).filter { it.auto && it.status == "ok" && it.uri != null && it.location.startsWith("Folder") }
        Retention.select(owned, keep) { Retention.Owned(it.id, it.createdAt, it.auto, it.status == "ok") }.forEach { old ->
            val gone = runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, Uri.parse(old.uri)) }.getOrDefault(false)
            g.db.setBackupStatus(old.id, "removed", if (gone) "Removed by retention (kept the newest $keep)." else "Already gone from the folder.")
        }
    }

    companion object {
        const val PERIODIC = "abhify-backup-scheduled"
        const val NOW = "abhify-backup-now"

        private fun lastRun(g: AppGraph, ok: Boolean, error: String?, ms: Long? = null) = g.prefs.edit().putLong("backup_last_run", System.currentTimeMillis())
            .putBoolean("backup_last_ok", ok).putString("backup_last_error", error).apply { ms?.let { putLong("backup_last_ms", it) } }.apply()

        @Suppress("UNUSED_PARAMETER")
        private fun constraints(cfg: BackupSchedule) = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

        /** Applies the user's schedule: one unique periodic job (UPDATE keeps a single schedule), or none when disabled. */
        fun apply(context: Context, cfg: BackupSchedule, now: ZonedDateTime = ZonedDateTime.now()) {
            val wm = WorkManager.getInstance(context)
            if (!cfg.enabled) { wm.cancelUniqueWork(PERIODIC); return }
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScheduledBackupWorker>(cfg.periodHours, TimeUnit.HOURS)
                    .setInitialDelay(cfg.initialDelay(now).toMinutes(), TimeUnit.MINUTES).setConstraints(constraints(cfg)).build())
        }
        /** "Back up now" with the scheduled settings (local folder only). */
        fun runNow(context: Context, cfg: BackupSchedule) = WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScheduledBackupWorker>().setConstraints(constraints(cfg)).build())
    }
}
