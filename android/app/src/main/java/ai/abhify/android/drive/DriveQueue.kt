package ai.abhify.android.drive

import ai.abhify.android.chat.DriveFile

/** Where the queue keeps its rows and the bytes waiting to upload (Android: drive_files + files/drive_queue). */
interface QueueStore {
    fun pending(): List<DriveFile>
    fun save(f: DriveFile)
    fun bytes(id: Long): ByteArray?
    fun dropBytes(id: Long)
}

/**
 * The durable Google Drive upload queue (v3.5). Every upload — started by the user, or by a scheduled backup the
 * user enabled — is a row plus its bytes; a background worker drains it. The rules:
 * - unchanged files are never sent twice; a different file with the same name is never overwritten: it becomes a
 *   CONFLICT that waits for the user (replace / keep both), even if the app was closed meanwhile;
 * - the resumable session is stored, so an upload cut off by the app being closed continues from Drive's offset;
 * - transient failures (network, rate limit, 5xx) retry with backoff up to [maxAttempts]; then FAILED (Retry button);
 * - an authorization problem stops the whole run: nothing more is attempted until the user connects again.
 */
class DriveQueue(private val store: QueueStore, private val maxAttempts: Int = 6) {
    sealed class Outcome {
        data class Synced(val item: DriveApi.Item, val already: Boolean) : Outcome()
        data class Conflict(val existing: DriveApi.Item) : Outcome()
        data class Retry(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
        data class AuthStop(val message: String) : Outcome()
        object Missing : Outcome()
    }
    data class Summary(val synced: List<DriveFile>, val conflicts: Int, val failed: Int, val retryLater: Boolean, val authStop: String?)

    companion object {
        const val REPLACE = "replace"; const val KEEP_BOTH = "keep_both"
        const val NEEDS_SIGN_IN = "Google Drive needs you to connect again (More → Google Drive). Waiting uploads are kept."
    }

    /** Uploads one queued item and records the result. */
    fun process(api: DriveApi, f: DriveFile, progress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        val bytes = store.bytes(f.id) ?: run {
            store.save(f.copy(status = DriveStatus.FAILED, error = "The data for this upload is gone; upload it again from its screen.")); return Outcome.Missing }
        // 7.2: plaintext backups and scheduled backups never go to Drive (the row and its bytes are kept, not deleted)
        ai.abhify.android.backup.BackupPolicy.driveProblem(f.kind, DriveApi.canonical(f.folder), bytes)?.let { why ->
            val status = if (f.kind == ai.abhify.android.backup.BackupPolicy.AUTO_KIND) DriveStatus.CANCELLED else DriveStatus.FAILED
            store.save(f.copy(status = status, error = why, session = null)); return Outcome.Failed(why) }
        var cur = f
        return try {
            val res = api.upload(f.folder, f.name, f.mime ?: "application/octet-stream", bytes,
                replaceId = if (f.mode == REPLACE) f.driveId else null, keepBoth = f.mode == KEEP_BOTH, progress = progress,
                session = f.session, onSession = { s -> cur = cur.copy(session = s); store.save(cur) })
            when (res) {
                is DriveApi.Upload.Done, is DriveApi.Upload.AlreadyThere -> {
                    val item = if (res is DriveApi.Upload.Done) res.item else (res as DriveApi.Upload.AlreadyThere).item
                    store.save(cur.copy(name = item.name, driveId = item.id, md5 = item.md5, size = item.size, driveModified = item.modified, status = DriveStatus.SYNCED,
                        error = null, session = null, mode = null, attempts = 0))
                    store.dropBytes(f.id); Outcome.Synced(item, res is DriveApi.Upload.AlreadyThere)
                }
                is DriveApi.Upload.Conflict -> {
                    store.save(cur.copy(driveId = res.existing.id, md5 = res.existing.md5, size = res.existing.size, driveModified = res.existing.modified,
                        status = DriveStatus.CONFLICT, error = "A different file with this name is on Drive. Choose: replace it, or keep both.", session = null))
                    Outcome.Conflict(res.existing)
                }
            }
        } catch (e: DriveException) {
            when {
                e.auth -> { store.save(cur.copy(error = NEEDS_SIGN_IN)); Outcome.AuthStop(NEEDS_SIGN_IN) }
                e.transient && cur.attempts + 1 < maxAttempts -> { store.save(cur.copy(attempts = cur.attempts + 1, error = e.message)); Outcome.Retry(e.message ?: "Temporary problem.") }
                else -> { val m = (e.message ?: "Upload failed.") + if (e.transient) " (gave up after $maxAttempts attempts — tap Retry)" else ""
                    store.save(cur.copy(status = DriveStatus.FAILED, attempts = cur.attempts + 1, error = m)); Outcome.Failed(m) }
            }
        }
    }

    /**
     * Drains the queue oldest-first. Stops early on [stopped] (Android asked the worker to stop) or an auth problem.
     * v4.2: with [account] set, only that account's uploads are sent (rows from before account tracking are adopted);
     * uploads made for another Google account stay queued for it.
     */
    fun runAll(api: DriveApi, account: String? = null, stopped: () -> Boolean = { false }, progress: (DriveFile, Long, Long) -> Unit = { _, _, _ -> }): Summary {
        val synced = mutableListOf<DriveFile>(); var conflicts = 0; var failed = 0; var retry = false
        val seen = HashSet<Long>()
        while (!stopped()) {
            val found = store.pending().firstOrNull { it.id !in seen && (account == null || DriveAccounts.mayUpload(it.account, account)) } ?: break
            seen += found.id
            val next = if (account != null && found.account == null) found.copy(account = account).also(store::save) else found
            when (val o = process(api, next) { s, t -> progress(next, s, t) }) {
                is Outcome.Synced -> synced += next
                is Outcome.Conflict -> conflicts++
                is Outcome.Retry -> retry = true
                is Outcome.Failed, Outcome.Missing -> failed++
                is Outcome.AuthStop -> return Summary(synced, conflicts, failed, false, o.message)
            }
        }
        return Summary(synced, conflicts, failed, retry || stopped(), null)
    }

    /** The user's choice for a stored conflict: upload again replacing the Drive file, or as a numbered copy. */
    fun resolve(f: DriveFile, replace: Boolean): DriveFile =
        f.copy(status = DriveStatus.PENDING_UPLOAD, mode = if (replace) REPLACE else KEEP_BOTH, error = null, attempts = 0, session = null).also(store::save)

    /** Retry a failed or cancelled item (its bytes are still queued). */
    fun retry(f: DriveFile): DriveFile = f.copy(status = DriveStatus.PENDING_UPLOAD, error = null, attempts = 0).also(store::save)

    /** Cancel: waiting items stop (bytes kept for Retry). An upload already in progress stops at its next chunk. */
    fun cancelAll() = store.pending().forEach { store.save(it.copy(status = DriveStatus.CANCELLED, error = "Cancelled by you.")) }
}
