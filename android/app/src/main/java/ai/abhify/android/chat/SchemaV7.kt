package ai.abhify.android.chat

/**
 * Database v7 (Abhify 3.2). Additive only: Career (job descriptions, application tracker, interview practice),
 * Study (sessions with their results) and the Knowledge Vault. All local to this phone, never synced.
 * Statements are SQLite 3.22-compatible (Android 10).
 */
object SchemaV7 {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS job_descriptions (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, company TEXT, body TEXT NOT NULL, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS job_applications (id INTEGER PRIMARY KEY AUTOINCREMENT, company TEXT NOT NULL, title TEXT NOT NULL, jd_id INTEGER, " +
            "resume_doc_id INTEGER, resume_version_id INTEGER, applied_on TEXT, status TEXT NOT NULL, notes TEXT, follow_up_on TEXT, interview_on TEXT, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS job_applications_status ON job_applications(status, updated_at)",
        "CREATE TABLE IF NOT EXISTS interview_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, " +
            "feedback TEXT, source TEXT, created_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS interview_attempts_time ON interview_attempts(created_at)",
        "CREATE TABLE IF NOT EXISTS study_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL, source_kind TEXT NOT NULL, " +
            "source_ref TEXT, content TEXT NOT NULL, score INTEGER, total INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS study_sessions_time ON study_sessions(updated_at)",
        "CREATE TABLE IF NOT EXISTS vault_items (id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, title TEXT NOT NULL, " +
            "body TEXT NOT NULL, category TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '', favorite INTEGER NOT NULL DEFAULT 0, ref_kind TEXT, ref_id INTEGER, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, opened_at INTEGER)",
        "CREATE INDEX IF NOT EXISTS vault_items_recent ON vault_items(opened_at, updated_at)",
        "CREATE INDEX IF NOT EXISTS vault_items_category ON vault_items(category)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
