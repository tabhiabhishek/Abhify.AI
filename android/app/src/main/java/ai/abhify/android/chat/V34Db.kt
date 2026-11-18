package ai.abhify.android.chat

import android.content.ContentValues
import android.database.Cursor

/** Database v9 access: personal memory metadata, the document chunk index, Google Drive status and backup history. */
data class MemoryItem(val id: Long, val uid: String, val content: String, val category: String, val importance: Int, val confirmed: Boolean,
                      val source: String?, val expiresAt: Long?, val createdAt: Long, val updatedAt: Long,
                      // v3.8 usage bookkeeping (local; not part of sync)
                      val lastUsedAt: Long? = null, val useCount: Int = 0, val reviewedAt: Long? = null) {
    fun expired(now: Long = System.currentTimeMillis()) = expiresAt != null && expiresAt < now
}
data class DriveFile(val id: Long, val kind: String, val localRef: String?, val name: String, val folder: String, val driveId: String?, val md5: String?,
                     val size: Long?, val driveModified: String?, val status: String, val error: String?, val updatedAt: Long,
                     // v3.5 durable queue
                     val mode: String? = null, val attempts: Int = 0, val session: String? = null, val mime: String? = null, val queuedAt: Long? = null,
                     val account: String? = null)
data class BackupRecord(val id: Long, val createdAt: Long, val location: String, val categories: String, val size: Long, val sha256: String, val encrypted: Boolean,
                        val auto: Boolean = false, val uri: String? = null, val status: String = "ok", val error: String? = null)

private fun <T> Cursor.all(row: Cursor.() -> T): List<T> = use { c -> generateSequence { if (c.moveToNext()) c.row() else null }.toList() }
private fun now() = System.currentTimeMillis()
private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)

// ------------------------------------------------------------------ personal memory (v2 metadata on the existing table)
private const val MEM_COLS = "id, uid, content, COALESCE(category, 'General'), importance, confirmed, source, expires_at, created_at, updated_at, last_used_at, use_count, reviewed_at"
private fun Cursor.mem() = MemoryItem(getLong(0), getString(1), getString(2), getString(3), getInt(4), getInt(5) == 1, getString(6), longOrNull(7), getLong(8), getLong(9),
    longOrNull(10), getInt(11), longOrNull(12))

fun ChatDb.memoryItems(): List<MemoryItem> = readableDatabase.rawQuery("SELECT $MEM_COLS FROM memories WHERE deleted = 0 ORDER BY updated_at DESC, id DESC", null).all { mem() }

/** Adds a memory the user confirmed (manual, "remember that…", or an approved suggestion). Synced like before when sync is on. */
fun ChatDb.addMemoryItem(content: String, category: String, importance: Int = 2, source: String? = "manual", expiresAt: Long? = null): Long {
    val id = addMemory(content.trim())
    writableDatabase.execSQL("UPDATE memories SET category = ?, importance = ?, confirmed = 1, source = ?, expires_at = ? WHERE id = ?",
        arrayOf<Any?>(category, importance.coerceIn(1, 3), source, expiresAt, id))
    return id
}
fun ChatDb.updateMemoryItem(id: Long, content: String, category: String, importance: Int, expiresAt: Long?) {
    writableDatabase.execSQL("UPDATE memories SET content = ?, category = ?, importance = ?, expires_at = ?, updated_at = ?, dirty = 1 WHERE id = ?",
        arrayOf<Any?>(content.trim(), category, importance.coerceIn(1, 3), expiresAt, now(), id))
}
fun ChatDb.memoryBytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM memories WHERE deleted = 0", null).use { it.moveToFirst(); it.getLong(0) }

// ------------------------------------------------------------------ conversations: paging and date filters
/** One page of conversations (newest first); [archived] selects the archive; dates are inclusive epoch-millis bounds. */
fun ChatDb.conversationPage(archived: Boolean, from: Long?, to: Long?, limit: Int, offset: Int): List<ChatDb.Conversation> {
    val where = StringBuilder("deleted = 0 AND archived = ?"); val args = mutableListOf(if (archived) "1" else "0")
    from?.let { where.append(" AND updated_at >= ?"); args += it.toString() }
    to?.let { where.append(" AND updated_at <= ?"); args += it.toString() }
    return readableDatabase.rawQuery("SELECT id, uid, title, updated_at, pinned, folder, summary, archived FROM conversations WHERE $where " +
        "ORDER BY pinned DESC, updated_at DESC LIMIT $limit OFFSET $offset", args.toTypedArray())
        .all { ChatDb.Conversation(getLong(0), getString(1), getString(2), getLong(3), getInt(4) == 1, getString(5), getString(6), getInt(7) == 1) }
}
fun ChatDb.archiveBytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(m.content)), 0) FROM messages m JOIN conversations c ON c.id = m.conversation_id " +
    "WHERE c.archived = 1 AND m.deleted = 0", null).use { it.moveToFirst(); it.getLong(0) }

// ------------------------------------------------------------------ document library: hashes and the chunk index
fun ChatDb.docBySha(sha: String): LibraryDoc? = readableDatabase.rawQuery("SELECT id FROM library_docs WHERE sha256 = ?", arrayOf(sha)).use { if (it.moveToFirst()) it.getLong(0) else null }?.let { doc(it) }
fun ChatDb.setDocSource(id: Long, sha: String?, source: String?) = writableDatabase.execSQL("UPDATE library_docs SET sha256 = COALESCE(?, sha256), source = COALESCE(?, source) WHERE id = ?", arrayOf<Any?>(sha, source, id))
fun ChatDb.docMeta(id: Long): Triple<String?, String?, Long?> = readableDatabase.rawQuery("SELECT sha256, source, indexed_at FROM library_docs WHERE id = ?", arrayOf(id.toString()))
    .use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.longOrNull(2)) else Triple(null, null, null) }
fun ChatDb.unindexedDocs(): List<Long> = readableDatabase.rawQuery("SELECT id FROM library_docs WHERE indexed_at IS NULL", null).all { getLong(0) }
fun ChatDb.libraryBytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM library_docs", null).use { it.moveToFirst(); it.getLong(0) }

/** Replaces a document's chunks (and their full-text rows) in one transaction; marks it indexed. */
fun ChatDb.storeChunks(docId: Long, chunks: List<Triple<Int, Int?, String>>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        clearChunks(docId, db)
        chunks.forEach { (idx, page, text) ->
            val cid = db.insertOrThrow("doc_chunks", null, ContentValues().apply { put("doc_id", docId); put("idx", idx); put("page", page); put("text", text) })
            if (ftsReady) runCatching { db.execSQL("INSERT INTO doc_chunks_fts(docid, text) VALUES (?, ?)", arrayOf<Any>(cid, text)) }
        }
        db.execSQL("UPDATE library_docs SET indexed_at = ? WHERE id = ?", arrayOf<Any>(now(), docId))
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
private fun ChatDb.clearChunks(docId: Long, db: android.database.sqlite.SQLiteDatabase = writableDatabase) {
    if (ftsReady) runCatching { db.execSQL("DELETE FROM doc_chunks_fts WHERE docid IN (SELECT id FROM doc_chunks WHERE doc_id = ?)", arrayOf<Any>(docId)) }
    db.delete("doc_chunks", "doc_id = ?", arrayOf(docId.toString()))
}
/** Called by deleteDoc callers: the index never outlives its document. */
fun ChatDb.deleteChunks(docId: Long) = clearChunks(docId)

data class StoredChunk(val docId: Long, val idx: Int, val page: Int?, val text: String)
/**
 * Retrieval candidates among [docIds]: chunks containing any of the query words (full-text index when available,
 * else LIKE), at most [limit]. BM25 ranking happens afterwards in Kotlin.
 */
fun ChatDb.candidateChunks(docIds: Collection<Long>, words: List<String>, limit: Int = 300): List<StoredChunk> {
    if (docIds.isEmpty() || words.isEmpty()) return emptyList()
    val inDocs = docIds.joinToString(",")                                                     // ids are Longs (safe to inline)
    val fts = SearchIndex.anyOf(words)
    if (ftsReady && fts != null) runCatching {
        return readableDatabase.rawQuery("SELECT doc_id, idx, page, text FROM doc_chunks WHERE doc_id IN ($inDocs) AND id IN " +
            "(SELECT docid FROM doc_chunks_fts WHERE doc_chunks_fts MATCH ?) LIMIT $limit", arrayOf(fts)).all { StoredChunk(getLong(0), getInt(1), if (isNull(2)) null else getInt(2), getString(3)) }
    }
    val likes = words.take(8).map { "%" + it.replace("%", "").replace("_", "") + "%" }
    return readableDatabase.rawQuery("SELECT doc_id, idx, page, text FROM doc_chunks WHERE doc_id IN ($inDocs) AND (" + likes.joinToString(" OR ") { "text LIKE ?" } +
        ") LIMIT $limit", likes.toTypedArray()).all { StoredChunk(getLong(0), getInt(1), if (isNull(2)) null else getInt(2), getString(3)) }
}

// ------------------------------------------------------------------ Google Drive status
private const val DF = "id, kind, local_ref, name, folder, drive_id, md5, size, drive_modified, status, error, updated_at, mode, attempts, session, mime, queued_at, account"
private fun Cursor.df() = DriveFile(getLong(0), getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), longOrNull(7), getString(8), getString(9), getString(10), getLong(11),
    getString(12), getInt(13), getString(14), getString(15), longOrNull(16), getString(17))
fun ChatDb.driveFiles(): List<DriveFile> = readableDatabase.rawQuery("SELECT $DF FROM drive_files ORDER BY updated_at DESC", null).all { df() }
fun ChatDb.driveFile(kind: String, localRef: String): DriveFile? = readableDatabase.rawQuery("SELECT $DF FROM drive_files WHERE kind = ? AND local_ref = ? ORDER BY id DESC LIMIT 1", arrayOf(kind, localRef)).all { df() }.firstOrNull()
fun ChatDb.saveDriveFile(f: DriveFile): Long {
    val v = ContentValues().apply { put("kind", f.kind); put("local_ref", f.localRef); put("name", f.name); put("folder", f.folder); put("drive_id", f.driveId); put("md5", f.md5)
        put("size", f.size); put("drive_modified", f.driveModified); put("status", f.status); put("error", f.error?.take(300)); put("updated_at", now())
        put("mode", f.mode); put("attempts", f.attempts); put("session", f.session); put("mime", f.mime); put("queued_at", f.queuedAt)
        put("account", f.account) }
    return if (f.id <= 0) writableDatabase.insertOrThrow("drive_files", null, v) else { writableDatabase.update("drive_files", v, "id = ?", arrayOf(f.id.toString())); f.id }
}
fun ChatDb.forgetDriveFile(id: Long) = writableDatabase.delete("drive_files", "id = ?", arrayOf(id.toString()))

// ------------------------------------------------------------------ backups
fun ChatDb.addBackupRecord(location: String, categories: String, size: Long, sha256: String, encrypted: Boolean,
                           auto: Boolean = false, uri: String? = null, status: String = "ok", error: String? = null): Long =
    writableDatabase.insertOrThrow("backups", null, ContentValues().apply { put("created_at", now()); put("location", location); put("categories", categories)
        put("size", size); put("sha256", sha256); put("encrypted", if (encrypted) 1 else 0); put("auto", if (auto) 1 else 0); put("uri", uri); put("status", status); put("error", error?.take(300)) })
fun ChatDb.backupRecords(limit: Int = 50): List<BackupRecord> = readableDatabase.rawQuery(
    "SELECT id, created_at, location, categories, size, sha256, encrypted, auto, uri, status, error FROM backups ORDER BY created_at DESC, id DESC LIMIT $limit", null)
    .all { BackupRecord(getLong(0), getLong(1), getString(2), getString(3), getLong(4), getString(5), getInt(6) == 1, getInt(7) == 1, getString(8), getString(9), getString(10)) }
fun ChatDb.setBackupStatus(id: Long, status: String, error: String? = null) =
    writableDatabase.execSQL("UPDATE backups SET status = ?, error = ? WHERE id = ?", arrayOf<Any?>(status, error?.take(300), id))
/** v3.5 queue: items waiting to upload, oldest first. */
fun ChatDb.queuedDriveFiles(): List<DriveFile> = readableDatabase.rawQuery(
    "SELECT $DF FROM drive_files WHERE status = 'pending-upload' ORDER BY COALESCE(queued_at, updated_at), id", null).all { df() }
fun ChatDb.driveFileById(id: Long): DriveFile? = readableDatabase.rawQuery("SELECT $DF FROM drive_files WHERE id = ?", arrayOf(id.toString())).all { df() }.firstOrNull()
