package ai.abhify.android.chat

/**
 * Database v15 (Abhify 4.0, unified workspace). Additive only: a workspace can reference existing conversations,
 * library documents and memories (links, not copies — nothing is duplicated, and removing a link never deletes the item).
 */
object SchemaV15 {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS workspace_links (id INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id INTEGER NOT NULL, kind TEXT NOT NULL, " +
            "ref_id INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE (workspace_id, kind, ref_id))",
        "CREATE INDEX IF NOT EXISTS workspace_links_ref ON workspace_links(kind, ref_id)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
