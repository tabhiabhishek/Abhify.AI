package ai.abhify.android.chat

/**
 * Database v12 (Abhify 3.7, Career maintenance). Additive only: an interview attempt remembers which saved job it was
 * practised for (job-specific history) and the average of its validated AI scores (practice progress). Old attempts
 * keep NULL for both (shown as "not scored" / no job).
 */
object SchemaV12 {
    val STATEMENTS = listOf(
        "ALTER TABLE interview_attempts ADD COLUMN jd_id INTEGER",
        "ALTER TABLE interview_attempts ADD COLUMN score REAL",                          // mean of the 1–5 scores; NULL = not scored
        "CREATE INDEX IF NOT EXISTS interview_attempts_jd ON interview_attempts(jd_id, created_at)",
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
