package ai.abhify.android

import ai.abhify.android.backup.Backup
import ai.abhify.android.backup.BackupPolicy
import ai.abhify.android.backup.BackupSchedule
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveException
import ai.abhify.android.drive.DriveQueue
import ai.abhify.android.drive.DriveStatus
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 7.2.0-dev.1 regression tests: (1) a backup is never uploaded to Google Drive unencrypted (empty passphrase, old
 * queued rows, direct client calls); (2) scheduled backups never upload to Drive (the destination is gone; old queue
 * rows are cancelled, not deleted).
 */
class V72DriveBackupPolicyTest {
    private val plain = Backup.encode(mapOf("memories" to JSONArray().put(org.json.JSONObject().put("content", "my secret"))), listOf("Personal memory"), "7.2.0-dev.1")
    private val sealed = Backup.encrypt(plain, "correct horse")

    @Test fun recognisesOnlyRealEncryptedEnvelopes() {
        assertTrue(BackupPolicy.isEncrypted(sealed))
        assertFalse(BackupPolicy.isEncrypted(plain))
        assertFalse(BackupPolicy.isEncrypted("{\"format\":\"abhify-backup-encrypted\"}".toByteArray()))     // a label alone isn't encryption
        assertFalse(BackupPolicy.isEncrypted(byteArrayOf(1, 2, 3)))
        assertTrue(BackupPolicy.isEncrypted("{\"format\":\"abhify-vault-backup\",\"iv\":\"AA\",\"ct\":\"BB\"}".toByteArray()))
    }

    @Test fun emptyOrShortPassphraseIsRefusedForDrive() {
        assertNotNull(BackupPolicy.passphraseProblem(""))
        assertNotNull(BackupPolicy.passphraseProblem(null))
        assertNotNull(BackupPolicy.passphraseProblem("1234567"))
        assertNull(BackupPolicy.passphraseProblem("12345678"))
    }

    @Test fun driveClientRefusesPlaintextInBackupsBeforeAnyNetworkCall() {
        val fake = FakeDrive(); val api = DriveApi(fake, { "t" })
        for (folder in listOf("Backups", "Application Backups", "Memory Backups")) {
            try { api.upload(folder, "b.json", "application/json", plain); fail("plaintext accepted in $folder") }
            catch (e: DriveException) { assertTrue(e.message!!.contains("encrypted")); assertFalse(e.transient) }
        }
        assertTrue("nothing reached Drive", fake.files.values.none { it.mime != "application/vnd.google-apps.folder" })
        assertTrue(api.upload("Backups", "b.json", "application/json", sealed) is DriveApi.Upload.Done)
        assertTrue(fake.files.values.any { it.name == "b.json" && it.body.contentEquals(sealed) })
        assertTrue(api.upload("Documents", "notes.txt", "text/plain", "hello".toByteArray()) is DriveApi.Upload.Done)   // other folders unchanged
    }

    @Test fun queueRefusesPlaintextBackupsAndKeepsTheirBytes() {
        val fake = FakeDrive(); val q = MemQueue(); val dq = DriveQueue(q)
        val bad = q.add("abhify-backup-old.json", plain, folder = "Backups", kind = "backup")          // e.g. queued by 7.1 with no passphrase
        val good = q.add("abhify-backup-new.json", sealed, folder = "Backups", kind = "backup")
        dq.runAll(DriveApi(fake, { "t" }))
        assertEquals(DriveStatus.FAILED, q.rows.getValue(bad.id).status)
        assertEquals(BackupPolicy.PLAINTEXT_REFUSED, q.rows.getValue(bad.id).error)
        assertNotNull("bytes are kept, not deleted", q.blobs[bad.id])
        assertEquals(DriveStatus.SYNCED, q.rows.getValue(good.id).status)
        assertTrue(fake.files.values.none { it.body.contentEquals(plain) })
        dq.retry(q.rows.getValue(bad.id)); dq.runAll(DriveApi(fake, { "t" }))                     // Retry can't push it through either
        assertEquals(DriveStatus.FAILED, q.rows.getValue(bad.id).status)
    }

    @Test fun scheduledBackupsNeverUploadToDrive() {
        // an old queue row from a 7.1 schedule (encrypted) is cancelled, not sent, not deleted
        val fake = FakeDrive(); val q = MemQueue(); val dq = DriveQueue(q)
        val auto = q.add("abhify-backup-auto-2026.json", sealed, folder = "Backups", kind = "backup-auto")
        dq.runAll(DriveApi(fake, { "t" }))
        assertEquals(DriveStatus.CANCELLED, q.rows.getValue(auto.id).status)
        assertEquals(BackupPolicy.SCHEDULED_DRIVE_OFF, q.rows.getValue(auto.id).error)
        assertNotNull(q.blobs[auto.id])
        assertTrue(fake.files.values.none { it.name.startsWith("abhify-backup-auto") })
        // a saved 7.1 schedule with Drive turned on: Drive is dropped, the user is told, and nothing is saved back with it
        val old = BackupSchedule.parse("""{"enabled":true,"categories":["Personal memory"],"toDrive":true,"keep":5}""")
        assertTrue(old.driveRemoved)
        assertTrue(old.problem(hasPassphrase = true, driveConnected = true)!!.contains("no longer go to Google Drive"))
        assertFalse(old.copy(folderUri = "content://tree/x").json().contains("toDrive"))
        assertNull(old.copy(folderUri = "content://tree/x").problem(true, true))
    }

    @Test fun sourceHasNoScheduledDrivePathLeft() {
        val root = File("src/main/java/ai/abhify/android")
        val work = File(root, "backup/BackupWork.kt").readText()
        assertFalse(work.contains("DriveUploadWorker.queue"))
        assertFalse(work.contains("KIND_AUTO_BACKUP"))
        val schedule = File(root, "backup/Schedule.kt").readText()
        assertFalse(schedule.contains("val toDrive"))
        val state = File(root, "ui/V34State.kt").readText().substringAfter("fun toDrive(").substringBefore("fun ")
        assertTrue("manual Drive backup checks the passphrase first", state.indexOf("passphraseProblem") in 0 until state.indexOf("create(cats)"))
        val screens = File(root, "ui/V34Screens.kt").readText()
        assertFalse(screens.contains("cfg.toDrive"))
    }
}
