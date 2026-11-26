package ai.abhify.android

import ai.abhify.android.backup.BackupSchedule
import ai.abhify.android.backup.Retention
import ai.abhify.android.chat.BackupRecord
import ai.abhify.android.chat.DriveFile
import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveQueue
import ai.abhify.android.drive.DriveStatus
import ai.abhify.android.drive.DriveTransport
import ai.abhify.android.drive.QueueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** The queue's rows and bytes in memory (the app uses drive_files + files/drive_queue). */
class MemQueue : QueueStore {
    val rows = LinkedHashMap<Long, DriveFile>(); val blobs = HashMap<Long, ByteArray>(); var next = 1L
    fun add(name: String, bytes: ByteArray, folder: String = "Documents", kind: String = "file"): DriveFile {
        val f = DriveFile(next++, kind, name, name, folder, null, null, bytes.size.toLong(), null, DriveStatus.PENDING_UPLOAD, null, 0, mime = "application/pdf", queuedAt = next)
        rows[f.id] = f; blobs[f.id] = bytes; return f
    }
    override fun pending() = rows.values.filter { it.status == DriveStatus.PENDING_UPLOAD }.sortedBy { it.queuedAt }
    override fun save(f: DriveFile) { rows[f.id] = f }
    override fun bytes(id: Long) = blobs[id]
    override fun dropBytes(id: Long) { blobs.remove(id) }
}

/** Wraps a transport; [killAfterPuts] simulates Android killing the app after that many content chunks were sent. */
class Killable(private val inner: DriveTransport) : DriveTransport {
    var killAfterPuts = -1; var puts = 0; var fail: ((String, String) -> DriveTransport.Resp?)? = null
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): DriveTransport.Resp {
        fail?.invoke(method, url)?.let { return it }
        if (method == "PUT" && body != null && body.isNotEmpty()) { if (killAfterPuts == 0) throw IllegalStateException("process killed"); if (killAfterPuts > 0) killAfterPuts--; puts++ }
        return inner.send(method, url, headers, body)
    }
}

class DriveQueueTest {
    private val big = ByteArray(DriveApi.CHUNK * 3 + 10) { (it % 253).toByte() }
    private fun api(t: DriveTransport) = DriveApi(t, { "token" })

    @Test fun uploadsSkipUnchangedAndDropQueuedBytes() {
        val fake = FakeDrive(); val q = MemQueue(); val dq = DriveQueue(q)
        val a = q.add("notes.pdf", big)
        val s = dq.runAll(api(fake))
        assertEquals(1, s.synced.size); assertEquals(DriveStatus.SYNCED, q.rows[a.id]!!.status); assertNull(q.blobs[a.id])
        val again = q.add("notes.pdf", big)                                                        // same content queued again
        assertTrue(dq.process(api(fake), again) is DriveQueue.Outcome.Synced)
        assertEquals(1, fake.files.values.count { it.name == "notes.pdf" })                        // not uploaded twice
    }

    @Test fun anUploadCutOffByTheAppClosingResumesFromDrivesOffset() {
        val fake = FakeDrive(); val q = MemQueue(); val t = Killable(fake).apply { killAfterPuts = 1 }
        val a = q.add("big.bin", big)
        assertTrue(runCatching { DriveQueue(q).runAll(api(t)) }.isFailure)                         // "killed" after chunk 1
        val stored = q.rows[a.id]!!
        assertEquals(DriveStatus.PENDING_UPLOAD, stored.status); assertTrue(stored.session != null)  // session survived
        // a new process: new transport wrapper, new DriveApi, same queue
        val t2 = Killable(fake)
        val s = DriveQueue(q).runAll(api(t2))
        assertEquals(1, s.synced.size)
        assertEquals(3, t2.puts)                                                                  // chunks 2..4 only, not from the start
        assertTrue(fake.files.values.single { it.name == "big.bin" }.body.contentEquals(big))
        assertNull(q.rows[a.id]!!.session)
    }

    @Test fun conflictsPersistUntilTheUserChooses() {
        val fake = FakeDrive(); val q = MemQueue(); val dq = DriveQueue(q)
        q.add("cv.pdf", byteArrayOf(1, 2, 3)); dq.runAll(api(fake))
        val changed = q.add("cv.pdf", byteArrayOf(9, 9, 9))
        val s = dq.runAll(api(fake))
        assertEquals(1, s.conflicts)
        val c = q.rows[changed.id]!!
        assertEquals(DriveStatus.CONFLICT, c.status); assertTrue(q.blobs.containsKey(c.id)); assertTrue(c.driveId != null)   // nothing overwritten, bytes kept
        assertTrue(fake.files.values.single { it.name == "cv.pdf" }.body.contentEquals(byteArrayOf(1, 2, 3)))
        dq.resolve(c, replace = false); dq.runAll(api(fake))
        assertTrue(fake.files.values.any { it.name == "cv (2).pdf" && it.body.contentEquals(byteArrayOf(9, 9, 9)) })
        val third = q.add("cv.pdf", byteArrayOf(7)); dq.runAll(api(fake))
        dq.resolve(q.rows[third.id]!!, replace = true); dq.runAll(api(fake))
        assertTrue(fake.files.values.single { it.name == "cv.pdf" }.body.contentEquals(byteArrayOf(7)))   // replaced only after "Replace"
    }

    @Test fun transientErrorsRetryThenGiveUpAuthStopsEverything() {
        val fake = FakeDrive(); val q = MemQueue()
        val t = Killable(fake).apply { fail = { _, _ -> DriveTransport.Resp(503, ByteArray(0), emptyMap()) } }
        val a = q.add("a.pdf", byteArrayOf(1)); val dq = DriveQueue(q, maxAttempts = 3)
        repeat(2) { assertTrue(dq.runAll(api(t)).retryLater) }
        assertEquals(2, q.rows[a.id]!!.attempts); assertEquals(DriveStatus.PENDING_UPLOAD, q.rows[a.id]!!.status)
        assertFalse(dq.runAll(api(t)).retryLater)
        assertEquals(DriveStatus.FAILED, q.rows[a.id]!!.status); assertTrue(q.rows[a.id]!!.error!!.contains("gave up after 3"))
        assertTrue(q.blobs.containsKey(a.id))                                                     // Retry stays possible
        dq.retry(q.rows[a.id]!!); assertEquals(0, q.rows[a.id]!!.attempts)
        // revoked access: stop the whole run, keep everything queued, no retry loop
        val b = q.add("b.pdf", byteArrayOf(2))
        val auth = Killable(fake).apply { fail = { _, _ -> DriveTransport.Resp(401, ByteArray(0), emptyMap()) } }
        val s = dq.runAll(api(auth))
        assertEquals(DriveQueue.NEEDS_SIGN_IN, s.authStop); assertFalse(s.retryLater)
        assertEquals(DriveStatus.PENDING_UPLOAD, q.rows[a.id]!!.status); assertEquals(DriveStatus.PENDING_UPLOAD, q.rows[b.id]!!.status)
        // a permanent refusal (quota) fails at once
        val full = Killable(fake).apply { fail = { _, _ -> DriveTransport.Resp(403, "{\"reason\":\"storageQuotaExceeded\"}".toByteArray(), emptyMap()) } }
        DriveQueue(q).runAll(api(full)); assertEquals(DriveStatus.FAILED, q.rows[b.id]!!.status)
    }

    @Test fun cancelKeepsBytesAndMissingBytesFailClearly() {
        val q = MemQueue(); val dq = DriveQueue(q)
        val a = q.add("a.pdf", byteArrayOf(1)); dq.cancelAll()
        assertEquals(DriveStatus.CANCELLED, q.rows[a.id]!!.status); assertTrue(q.blobs.containsKey(a.id)); assertTrue(q.pending().isEmpty())
        val b = q.add("b.pdf", byteArrayOf(2)); q.blobs.clear()
        assertTrue(dq.process(api(FakeDrive()), b) is DriveQueue.Outcome.Missing); assertEquals(DriveStatus.FAILED, q.rows[b.id]!!.status)
    }
}

class ScheduledBackupTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun nextRunIsTheNextPreferredHour() {
        val daily = BackupSchedule(enabled = true, hour = 2)
        assertEquals(ZonedDateTime.of(2026, 11, 6, 2, 0, 0, 0, zone), daily.nextRun(ZonedDateTime.of(2026, 11, 5, 15, 30, 0, 0, zone)))
        assertEquals(ZonedDateTime.of(2026, 11, 5, 2, 0, 0, 0, zone), daily.nextRun(ZonedDateTime.of(2026, 11, 5, 1, 59, 0, 0, zone)))
        val weekly = BackupSchedule(enabled = true, weekly = true, weekday = 7, hour = 21)   // Sunday 21:00; 2026-11-05 is a Thursday
        assertEquals(ZonedDateTime.of(2026, 11, 8, 21, 0, 0, 0, zone), weekly.nextRun(ZonedDateTime.of(2026, 11, 5, 15, 0, 0, 0, zone)))
        assertEquals(ZonedDateTime.of(2026, 11, 15, 21, 0, 0, 0, zone), weekly.nextRun(ZonedDateTime.of(2026, 11, 8, 21, 0, 0, 0, zone)))
        assertEquals(24L * 7, weekly.periodHours); assertEquals(24L, daily.periodHours)
    }

    @Test fun scheduleRulesAndRoundTrip() {
        val c = BackupSchedule(enabled = true, categories = listOf("Personal memory"), folderUri = "content://tree/x")
        assertNull(c.problem(true, true))
        assertTrue(c.copy(folderUri = null).problem(true, true)!!.contains("folder on this phone"))       // 7.2: a local folder is the only destination
        assertNull(c.problem(hasPassphrase = false, driveConnected = false))                            // local may be unencrypted
        assertTrue(c.copy(categories = emptyList()).problem(true, true)!!.contains("at least one"))
        assertNull(BackupSchedule().problem(false, false))                                                // disabled: nothing to check
        assertFalse(BackupSchedule().enabled)                                                           // off by default
        val back = BackupSchedule.parse(c.copy(folderUri = "content://t", folderName = "Docs", keep = 7, weekly = true).json())
        assertEquals(c.copy(folderUri = "content://t", folderName = "Docs", keep = 7, weekly = true), back)
        assertFalse(c.json().contains("toDrive"))
        assertEquals(BackupSchedule(), BackupSchedule.parse("not json"))
    }

    @Test fun retentionOnlyRemovesOlderScheduledBackupsAndNeverTheNewest() {
        fun r(id: Long, t: Long, auto: Boolean = true, status: String = "ok") = BackupRecord(id, t, "Folder: x", "Personal memory", 1, "s", true, auto, "content://$id", status)
        val all = listOf(r(1, 100), r(2, 200), r(3, 300, auto = false), r(4, 400), r(5, 500, status = "failed"), r(6, 600))
        val del = Retention.select(all, keep = 2) { Retention.Owned(it.id, it.createdAt, it.auto, it.status == "ok") }
        assertEquals(listOf(2L, 1L), del.map { it.id })                                             // newest two scheduled (6, 4) stay; manual and failed untouched
        assertEquals(listOf(4L, 2L, 1L), Retention.select(all, keep = 0) { Retention.Owned(it.id, it.createdAt, it.auto, it.status == "ok") }.map { it.id })   // keep ≥ 1
        assertTrue(Retention.select(listOf(r(9, 1)), 1) { Retention.Owned(it.id, it.createdAt, it.auto, true) }.isEmpty())   // the only backup is never removed
    }
}

class V35DatabaseAndWiringTest {
    @Test fun schemaV10KeepsQueueAndHistoryRows() {
        v8Db().use { c ->
            c.createStatement().use { st -> SchemaV9.apply { st.execute(it) } }
            c.exec("INSERT INTO drive_files (kind, local_ref, name, folder, status, updated_at) VALUES ('doc', '1', 'a.txt', 'Learning Library', 'failed', 5)")
            c.exec("INSERT INTO backups (created_at, location, categories, size, sha256) VALUES (1, 'Google Drive', 'Personal memory', 10, 'x')")
            c.createStatement().use { st -> SchemaV10.apply { st.execute(it) } }
            assertEquals("failed", c.one("SELECT status FROM drive_files")); assertEquals(0, c.one("SELECT attempts FROM drive_files")); assertNull(c.one("SELECT session FROM drive_files"))
            assertEquals("ok", c.one("SELECT status FROM backups")); assertEquals(0, c.one("SELECT auto FROM backups")); assertEquals("Google Drive", c.one("SELECT location FROM backups"))
        }
        assertTrue(SchemaV10.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        val db = File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText()
        assertTrue(db.contains("if (oldVersion < 10) SchemaV10.apply"))
    }

    @Test fun workIsUniqueConstrainedAndUserControlled() {
        val src = { f: String -> File("src/main/java/ai/abhify/android/$f").readText() }
        val dw = src("drive/DriveWork.kt")
        assertTrue(dw.contains("enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE"))           // never two drains at once
        assertTrue(dw.contains("NetworkType.UNMETERED else NetworkType.CONNECTED") && dw.contains("BackoffPolicy.EXPONENTIAL"))
        assertTrue(dw.contains("is DriveAuth.Result.NeedsConsent -> { markAll(store, DriveQueue.NEEDS_SIGN_IN); return Result.success() }"))   // no UI, no loop
        val bw = src("backup/BackupWork.kt")
        assertTrue(bw.contains("enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE") && bw.contains("if (!cfg.enabled) { wm.cancelUniqueWork(PERIODIC); return }"))
        assertTrue(bw.contains("if (!cfg.enabled) return Result.success()"))                           // a disabled schedule never backs up
        val st = src("ui/V34State.kt")
        assertFalse(st.substringAfter("class DriveState").substringBefore("class BackupState").contains("api.upload("))   // manual uploads use the same queue
        for (f in listOf("drive/DriveWork.kt", "drive/DriveQueue.kt", "backup/BackupWork.kt", "backup/Schedule.kt"))
            assertFalse(f, src(f).contains("Log.") || src(f).contains("providerStore"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("androidx.work.impl.foreground.SystemForegroundService"))
    }
}
