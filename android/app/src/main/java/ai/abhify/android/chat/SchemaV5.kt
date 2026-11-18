package ai.abhify.android.chat

/**
 * Database v5 (Abhify 3.0). Additive only: two columns on existing tables (old rows keep NULL = complete /
 * Personal / no feedback) and new local-only tables for the document library, learning and the Work area.
 * Nothing is dropped or rewritten. Kept as plain SQL so the JVM tests run it on a populated v4 database.
 * Uses only SQL that Android 10's SQLite 3.22 supports.
 */
object SchemaV5 {
    val STATEMENTS = listOf(
        // RC.5 response reliability: how an answer ended (NULL = complete; "length", "stopped", "interrupted", "failed")
        "ALTER TABLE messages ADD COLUMN status TEXT",
        "ALTER TABLE messages ADD COLUMN feedback INTEGER",                 // NULL, 1 (👍) or -1 (👎); stays on this phone
        "ALTER TABLE conversations ADD COLUMN folder TEXT",                 // NULL = Personal; Learning, Work, Documents
        "ALTER TABLE conversations ADD COLUMN summary TEXT",
        // document library: extracted text, on this phone only (never synced, never added to memory)
        "CREATE TABLE IF NOT EXISTS library_docs (id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE, name TEXT NOT NULL, kind TEXT NOT NULL, " +
            "size_bytes INTEGER NOT NULL, chars INTEGER NOT NULL, text TEXT NOT NULL, truncated INTEGER NOT NULL DEFAULT 0, workspace_id INTEGER, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        // learning
        "CREATE TABLE IF NOT EXISTS flashcards (id INTEGER PRIMARY KEY AUTOINCREMENT, deck TEXT NOT NULL, front TEXT NOT NULL, back TEXT NOT NULL, " +
            "known INTEGER NOT NULL DEFAULT 0, reviews INTEGER NOT NULL DEFAULT 0, source TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS flashcards_deck ON flashcards(deck)",
        "CREATE TABLE IF NOT EXISTS study_plans (id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT NOT NULL, level TEXT NOT NULL, goal TEXT, " +
            "target_date TEXT, minutes_per_day INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS plan_items (id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id INTEGER NOT NULL REFERENCES study_plans(id) ON DELETE CASCADE, " +
            "position INTEGER NOT NULL, text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, done_at INTEGER)",
        // Work area (local workspaces; no organisation server)
        "CREATE TABLE IF NOT EXISTS workspaces (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, " +
            "title TEXT NOT NULL, body TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'note', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, " +
            "title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, due_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
