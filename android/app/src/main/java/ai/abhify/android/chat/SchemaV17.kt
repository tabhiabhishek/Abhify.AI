package ai.abhify.android.chat

import android.content.ContentValues

/** Database v17 (Abhify 4.4): the user's saved prompts. Additive only (a new table); nothing existing changes. */
object SchemaV17 {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS saved_prompts (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
            "use_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}

data class SavedPrompt(val id: Long, val title: String, val body: String, val useCount: Int, val updatedAt: Long)

/** Most used first, then most recently changed. */
fun ChatDb.savedPrompts(): List<SavedPrompt> = readableDatabase.rawQuery(
    "SELECT id, title, body, use_count, updated_at FROM saved_prompts ORDER BY use_count DESC, updated_at DESC", null).use { c ->
    generateSequence { if (c.moveToNext()) SavedPrompt(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)) else null }.toList()
}
fun ChatDb.savePrompt(id: Long?, title: String, body: String): Long {
    val now = System.currentTimeMillis()
    val v = ContentValues().apply { put("title", title.trim().take(80)); put("body", body.trim().take(4000)); put("updated_at", now) }
    return if (id == null) writableDatabase.insertOrThrow("saved_prompts", null, v.apply { put("created_at", now) })
        else { writableDatabase.update("saved_prompts", v, "id = ?", arrayOf(id.toString())); id }
}
fun ChatDb.deletePrompt(id: Long) = writableDatabase.delete("saved_prompts", "id = ?", arrayOf(id.toString()))
fun ChatDb.promptUsed(id: Long) = writableDatabase.execSQL("UPDATE saved_prompts SET use_count = use_count + 1 WHERE id = ?", arrayOf<Any>(id))
