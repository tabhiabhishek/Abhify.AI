package ai.abhify.android.chat

/**
 * Database v9 (Abhify 3.4). Additive only: memory metadata, conversation archive, document hashes and a persistent
 * chunk store for retrieval, Google Drive sync status, and backup history. Old rows keep defaults (memories stay
 * confirmed, conversations unarchived). SQLite 3.22-compatible. The optional full-text indexes are NOT here (see
 * [SearchIndex]): a device without FTS4 must never fail the upgrade.
 */
object SchemaV9 {
    val STATEMENTS = listOf(
        // personal memory metadata
        "ALTER TABLE memories ADD COLUMN category TEXT",                                 // NULL = General
        "ALTER TABLE memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 2",         // 1 low · 2 normal · 3 high
        "ALTER TABLE memories ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 1",          // existing memories were added by the user
        "ALTER TABLE memories ADD COLUMN source TEXT",                                   // e.g. "chat:<uid>", "doc:<id>", "manual"
        "ALTER TABLE memories ADD COLUMN expires_at INTEGER",
        // conversation archive
        "ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE conversations ADD COLUMN archived_at INTEGER",
        // document library: duplicate detection and indexing state
        "ALTER TABLE library_docs ADD COLUMN sha256 TEXT",
        "ALTER TABLE library_docs ADD COLUMN source TEXT",                               // "device", "drive:<fileId>"
        "ALTER TABLE library_docs ADD COLUMN indexed_at INTEGER",
        "CREATE TABLE IF NOT EXISTS doc_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id INTEGER NOT NULL, idx INTEGER NOT NULL, page INTEGER, text TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS doc_chunks_doc ON doc_chunks(doc_id, idx)",
        // Google Drive: what is where (never used to delete anything automatically)
        "CREATE TABLE IF NOT EXISTS drive_files (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, local_ref TEXT, name TEXT NOT NULL, " +
            "folder TEXT NOT NULL, drive_id TEXT, md5 TEXT, size INTEGER, drive_modified TEXT, status TEXT NOT NULL, error TEXT, updated_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS drive_files_ref ON drive_files(kind, local_ref)",
        // backup history (for "last backup")
        "CREATE TABLE IF NOT EXISTS backups (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, location TEXT NOT NULL, " +
            "categories TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, encrypted INTEGER NOT NULL DEFAULT 0)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}

/**
 * Optional full-text search (FTS4, present in Android's SQLite). Created idempotently after the database opens;
 * if anything fails, search falls back to LIKE and nothing else is affected. The message index follows the messages
 * table through triggers; the chunk index is written together with the chunks.
 */
object SearchIndex {
    val STATEMENTS = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(content)",
        "CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(docid, content) VALUES (new.id, new.content); END",
        "CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE OF content ON messages BEGIN DELETE FROM messages_fts WHERE docid = old.id; " +
            "INSERT INTO messages_fts(docid, content) VALUES (new.id, new.content); END",
        "CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages BEGIN DELETE FROM messages_fts WHERE docid = old.id; END",
        "CREATE VIRTUAL TABLE IF NOT EXISTS doc_chunks_fts USING fts4(text)",
    )
    /** Fills the message index once (rows written before the triggers existed). */
    const val BACKFILL = "INSERT INTO messages_fts(docid, content) SELECT id, content FROM messages WHERE deleted = 0 AND id NOT IN (SELECT docid FROM messages_fts)"

    /** Turns user input into a safe FTS query: words only, each as a prefix term, AND-ed. Empty → null (use LIKE). */
    fun query(input: String): String? {
        val words = Regex("[\\p{L}\\p{N}]{2,}").findAll(input.lowercase()).map { it.value }.take(8).toList()
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }
    /** OR-query for retrieval candidates (any word may match; BM25 ranks them afterwards). */
    fun anyOf(words: List<String>): String? = words.filter { it.length >= 2 && it.all { c -> c.isLetterOrDigit() } }.distinct().take(16)
        .takeIf { it.isNotEmpty() }?.joinToString(" OR ")
}
