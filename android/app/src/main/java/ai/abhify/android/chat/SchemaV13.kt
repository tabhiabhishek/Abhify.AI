package ai.abhify.android.chat

/**
 * Database v13 (Abhify 3.8, advanced personal memory). Additive only: when a memory was last given to the AI and how
 * often (ranking, "not used for a long time"), when the user last reviewed it, and review decisions the user made
 * ("keep both" for a possible conflict or duplicate) so the same pair is not suggested again. Existing memories keep
 * their content; the new columns start empty.
 */
object SchemaV13 {
    val STATEMENTS = listOf(
        "ALTER TABLE memories ADD COLUMN last_used_at INTEGER",
        "ALTER TABLE memories ADD COLUMN use_count INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE memories ADD COLUMN reviewed_at INTEGER",
        "CREATE TABLE IF NOT EXISTS memory_dismissed (id INTEGER PRIMARY KEY AUTOINCREMENT, pair_key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, created_at INTEGER NOT NULL)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
