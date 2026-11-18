package ai.abhify.android.chat

import android.content.ContentValues

/** Database v11 access: optional chunk vectors (empty without an approved embedding model). */
data class ChunkVector(val docId: Long, val idx: Int, val page: Int?, val text: String, val sha: String?, val vector: ByteArray?)

/** Chunks of [docId] with their stored vector for [model] (null when missing or the text changed). */
fun ChatDb.chunksWithVectors(docIds: Collection<Long>, model: String, version: Int): List<ChunkVector> {
    if (docIds.isEmpty()) return emptyList()
    return readableDatabase.rawQuery("SELECT c.doc_id, c.idx, c.page, c.text, v.content_sha, v.vector FROM doc_chunks c LEFT JOIN chunk_vectors v " +
        "ON v.doc_id = c.doc_id AND v.idx = c.idx AND v.model = ? AND v.model_version = ? WHERE c.doc_id IN (${docIds.joinToString(",")}) ORDER BY c.doc_id, c.idx",
        arrayOf(model, version.toString())).use { c ->
        generateSequence { if (c.moveToNext()) ChunkVector(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getInt(2), c.getString(3), c.getString(4), c.getBlob(5)) else null }.toList()
    }
}

fun ChatDb.storeVectors(docId: Long, model: String, version: Int, dims: Int, rows: List<Triple<Int, String, ByteArray>>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        rows.forEach { (idx, sha, v) -> db.insertWithOnConflict("chunk_vectors", null, ContentValues().apply {
            put("doc_id", docId); put("idx", idx); put("model", model); put("model_version", version); put("dims", dims); put("content_sha", sha); put("vector", v)
            put("created_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

fun ChatDb.deleteVectors(docId: Long) = writableDatabase.delete("chunk_vectors", "doc_id = ?", arrayOf(docId.toString()))
fun ChatDb.vectorStats(): Pair<Int, Long> = readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(vector)), 0) FROM chunk_vectors", null)
    .use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }

/** Knowledge Center (no schema change): stored vectors per document for [model]. */
fun ChatDb.vectorCounts(model: String): Map<Long, Int> = readableDatabase.rawQuery("SELECT doc_id, COUNT(*) FROM chunk_vectors WHERE model = ? GROUP BY doc_id", arrayOf(model))
    .use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getInt(1) else null }.toMap() }
/** Chunks per document (documents never split are absent). */
fun ChatDb.chunkCounts(): Map<Long, Int> = readableDatabase.rawQuery("SELECT doc_id, COUNT(*) FROM doc_chunks GROUP BY doc_id", null)
    .use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getInt(1) else null }.toMap() }
/** Deletes every stored vector (documents and chunks stay); returns how many were removed. */
fun ChatDb.clearVectors(): Int = writableDatabase.delete("chunk_vectors", null, null)
