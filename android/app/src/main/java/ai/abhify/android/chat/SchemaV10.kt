package ai.abhify.android.chat

/**
 * Database v10 (Abhify 3.5). Additive only: the Google Drive upload queue becomes durable (mode, attempts, the
 * resumable session, mime, queue time) so background uploads survive the app being closed, and backup history
 * records scheduled backups (where they went, whether they worked). Old rows keep defaults.
 */
object SchemaV10 {
    val STATEMENTS = listOf(
        "ALTER TABLE drive_files ADD COLUMN mode TEXT",                                  // NULL = normal · 'replace' · 'keep_both'
        "ALTER TABLE drive_files ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE drive_files ADD COLUMN session TEXT",                               // resumable upload URI (resume after restart)
        "ALTER TABLE drive_files ADD COLUMN mime TEXT",
        "ALTER TABLE drive_files ADD COLUMN queued_at INTEGER",
        "CREATE INDEX IF NOT EXISTS drive_files_status ON drive_files(status, queued_at)",
        "ALTER TABLE backups ADD COLUMN auto INTEGER NOT NULL DEFAULT 0",                // 1 = made by the schedule
        "ALTER TABLE backups ADD COLUMN uri TEXT",                                       // the document Abhify wrote, or drive-queue:<id>
        "ALTER TABLE backups ADD COLUMN status TEXT NOT NULL DEFAULT 'ok'",              // ok · queued · failed · removed
        "ALTER TABLE backups ADD COLUMN error TEXT",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
