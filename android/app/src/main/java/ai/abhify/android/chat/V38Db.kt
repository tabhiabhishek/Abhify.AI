package ai.abhify.android.chat

import android.content.ContentValues

/** Database v13 access: memory usage, review state and the user's "keep both" decisions. */

/** Records that these memories were given to the AI (local bookkeeping only: not marked for sync, not "edited"). */
fun ChatDb.markMemoriesUsed(ids: Collection<Long>, at: Long = System.currentTimeMillis()) {
    if (ids.isEmpty()) return
    writableDatabase.execSQL("UPDATE memories SET last_used_at = ?, use_count = use_count + 1 WHERE id IN (${ids.joinToString(",")})", arrayOf<Any>(at))
}
fun ChatDb.setMemoryReviewed(id: Long, clearExpiry: Boolean = false, at: Long = System.currentTimeMillis()) =
    writableDatabase.execSQL("UPDATE memories SET reviewed_at = ?" + (if (clearExpiry) ", expires_at = NULL, updated_at = ?, dirty = 1" else "") + " WHERE id = ?",
        if (clearExpiry) arrayOf<Any>(at, at, id) else arrayOf<Any>(at, id))

fun ChatDb.dismissedMemoryPairs(): Set<String> = readableDatabase.rawQuery("SELECT pair_key FROM memory_dismissed", null)
    .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
fun ChatDb.dismissMemoryPair(key: String, kind: String) {
    writableDatabase.insertWithOnConflict("memory_dismissed", null, ContentValues().apply { put("pair_key", key); put("kind", kind); put("created_at", System.currentTimeMillis()) },
        android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
}
fun ChatDb.conversationTitle(id: Long): String? = readableDatabase.rawQuery("SELECT title FROM conversations WHERE id = ? AND deleted = 0", arrayOf(id.toString()))
    .use { if (it.moveToFirst()) it.getString(0) else null }
