package ai.abhify.android.chat

/**
 * 5.4 Conversation data (Privacy → Conversation data): counts and user-initiated clearing. Same deletion style as
 * ChatDb.deleteAll(): rows become tombstones with their content blanked (so a synced device deletes them too), and only
 * chats/messages or memories are touched — never documents, projects, study, career data, models or keys.
 */
object ConversationData {
    /** (conversations, memories) that are not deleted. */
    fun counts(db: ChatDb): Pair<Int, Int> = db.readableDatabase.run {
        rawQuery("SELECT (SELECT COUNT(*) FROM conversations WHERE deleted = 0), (SELECT COUNT(*) FROM memories WHERE deleted = 0)", null)
            .use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
    }

    fun clearConversations(db: ChatDb, now: Long = System.currentTimeMillis()) = db.writableDatabase.run {
        execSQL("UPDATE messages SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
        execSQL("UPDATE conversations SET deleted = 1, title = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
    }

    fun clearMemories(db: ChatDb, now: Long = System.currentTimeMillis()) =
        db.writableDatabase.execSQL("UPDATE memories SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
}

/** Next build — weekly review: titles of to-dos marked done in the given window (read-only; uses the existing updated_at). */
fun ChatDb.completedTasksSince(since: Long): List<String> = readableDatabase.rawQuery(
    "SELECT title FROM tasks WHERE done = 1 AND updated_at >= ? ORDER BY updated_at DESC LIMIT 50", arrayOf(since.toString()))
    .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
