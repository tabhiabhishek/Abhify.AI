package ai.abhify.android.chat

/**
 * Database v8 (Abhify 3.3). One additive column: the job description's extracted (and user-corrected) fields as JSON.
 * Everything else in Career Copilot 2.0 reuses existing tables (resume edits and accepted improvements are resume
 * versions; the active resume is a preference). Nothing is dropped or rewritten.
 */
object SchemaV8 {
    val STATEMENTS = listOf(
        "ALTER TABLE job_descriptions ADD COLUMN fields TEXT",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
