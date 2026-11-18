package ai.abhify.android.chat

/**
 * Database v6 (Abhify 3.1). Additive only: extraction details on library documents and a separate table for
 * resume work, so the original resume text in library_docs is never overwritten (corrections and AI outputs are
 * stored as versions). Nothing is dropped; Android 10's SQLite 3.22 supports every statement.
 */
object SchemaV6 {
    val STATEMENTS = listOf(
        "ALTER TABLE library_docs ADD COLUMN pages INTEGER",                       // NULL for formats without pages
        "ALTER TABLE library_docs ADD COLUMN ocr_pages INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE library_docs ADD COLUMN warnings TEXT",                       // newline-separated, for the user to review
        "ALTER TABLE library_docs ADD COLUMN role TEXT",                           // NULL, or 'resume'
        "CREATE TABLE IF NOT EXISTS resume_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id INTEGER NOT NULL, kind TEXT NOT NULL, " +
            "title TEXT NOT NULL, body TEXT NOT NULL, source TEXT, created_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS resume_versions_doc ON resume_versions(doc_id, created_at)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
