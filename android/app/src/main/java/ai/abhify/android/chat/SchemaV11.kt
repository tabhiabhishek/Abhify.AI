package ai.abhify.android.chat

/**
 * Database v11 (Abhify 3.6). Additive only: storage for optional chunk embeddings. Keyed by document + chunk position +
 * content hash (chunks are re-created on re-index; unchanged text keeps its vector and is never embedded again) and
 * tagged with the model id, version and dimensions. Empty unless an approved embedding model is installed.
 */
object SchemaV11 {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS chunk_vectors (doc_id INTEGER NOT NULL, idx INTEGER NOT NULL, model TEXT NOT NULL, model_version INTEGER NOT NULL, " +
            "dims INTEGER NOT NULL, content_sha TEXT NOT NULL, vector BLOB NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (doc_id, idx, model))",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
