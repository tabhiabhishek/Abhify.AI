package ai.abhify.android.chat

/**
 * Database v14 (Abhify 3.9, Study). Additive only: spaced-repetition state per flashcard (existing cards start "due
 * now" with default ease — their known/reviews counts are kept), a review log, and the documents a study plan was
 * built from. Nothing is reset.
 */
object SchemaV14 {
    val STATEMENTS = listOf(
        "ALTER TABLE flashcards ADD COLUMN due_at INTEGER",                              // NULL = due now (new or pre-3.9)
        "ALTER TABLE flashcards ADD COLUMN interval_days REAL NOT NULL DEFAULT 0",
        "ALTER TABLE flashcards ADD COLUMN ease REAL NOT NULL DEFAULT 2.5",
        "ALTER TABLE flashcards ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE flashcards ADD COLUMN last_reviewed INTEGER",
        "CREATE INDEX IF NOT EXISTS flashcards_due ON flashcards(due_at)",
        "CREATE TABLE IF NOT EXISTS card_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, card_id INTEGER NOT NULL, rating INTEGER NOT NULL, " +
            "interval_days REAL NOT NULL, reviewed_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS card_reviews_card ON card_reviews(card_id, reviewed_at)",
        "ALTER TABLE study_plans ADD COLUMN doc_ids TEXT",                              // comma-separated library ids (names shown; text never copied)
    )

    fun apply(exec: (String) -> Unit) = STATEMENTS.forEach(exec)
}
