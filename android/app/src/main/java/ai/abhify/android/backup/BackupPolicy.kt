package ai.abhify.android.backup

import org.json.JSONObject

/**
 * 7.2: what may go to Google Drive as a backup. Pure (JVM-tested), and enforced at two choke points: the upload queue
 * ([ai.abhify.android.drive.DriveQueue.process]) and the Drive client itself ([ai.abhify.android.drive.DriveApi.upload],
 * for anything written into the Backups folder). Rules:
 * - a backup is uploaded only when it is an encrypted Abhify backup (passphrase → AES-256-GCM): plaintext personal
 *   data never reaches Drive, whatever screen or old queue row it came from;
 * - scheduled (automatic) backups never upload to Drive any more; such queue rows from older versions are cancelled,
 *   not deleted (their bytes stay on the phone).
 */
object BackupPolicy {
    /** Queue kinds that carry a backup file. */
    val BACKUP_KINDS = setOf("backup", "backup-auto", "memory-backup")
    const val AUTO_KIND = "backup-auto"
    const val MIN_PASSPHRASE = 8

    const val PLAINTEXT_REFUSED = "Not uploaded: backups sent to Google Drive must be encrypted. Make the backup again with a passphrase (8+ characters)."
    const val SCHEDULED_DRIVE_OFF = "Not uploaded: scheduled backups to Google Drive were turned off in 7.2 (uploads now only happen when you start them). This backup stays on the phone."
    const val NEED_PASSPHRASE = "Backups uploaded to Google Drive are always encrypted. Enter a passphrase (8+ characters) in More → Backup & restore first."

    /** True when [bytes] is an encrypted Abhify backup envelope (v1 "abhify-backup-encrypted" or v2 "abhify-vault-backup"). */
    fun isEncrypted(bytes: ByteArray): Boolean = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        when (o.optString("format")) {
            Backup.ENCRYPTED -> o.optString("data").isNotEmpty() && o.optString("iv").isNotEmpty() && o.optString("salt").isNotEmpty()
            "abhify-vault-backup" -> o.optString("ct").isNotEmpty() && o.optString("iv").isNotEmpty()
            else -> false
        }
    }.getOrDefault(false)

    /** Why a queued upload must not go to Drive, or null when it may. */
    fun driveProblem(kind: String, folder: String?, bytes: ByteArray): String? = when {
        kind == AUTO_KIND -> SCHEDULED_DRIVE_OFF
        (kind in BACKUP_KINDS || folder == ai.abhify.android.drive.DriveApi.BACKUPS) && !isEncrypted(bytes) -> PLAINTEXT_REFUSED
        else -> null
    }

    /** Passphrase check for a backup that will be uploaded (empty or short is refused, never silently unencrypted). */
    fun passphraseProblem(passphrase: String?): String? = if (passphrase == null || passphrase.length < MIN_PASSPHRASE) NEED_PASSPHRASE else null
}
