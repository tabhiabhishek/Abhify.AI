package ai.abhify.android.chat

import ai.abhify.android.learn.Srs
import ai.abhify.android.learn.StudyInsights
import android.content.ContentValues
import android.database.Cursor

/** Database v14 access: spaced repetition, plan editing and the study dashboard's numbers. */
data class SrsCard(val card: Flashcard, val dueAt: Long?, val state: Srs.State)

private fun now() = System.currentTimeMillis()
private fun <T> Cursor.each(row: Cursor.() -> T): List<T> = use { c -> generateSequence { if (c.moveToNext()) c.row() else null }.toList() }
private const val SRS_COLS = "id, deck, front, back, known, reviews, source, due_at, interval_days, ease, lapses"
private fun Cursor.srs() = SrsCard(Flashcard(getLong(0), getString(1), getString(2), getString(3), getInt(4) == 1, getInt(5), getString(6)),
    if (isNull(7)) null else getLong(7), Srs.State(getDouble(8), getDouble(9), getInt(10), getInt(5)))

/** Cards due now, oldest due first (never-reviewed last, limited so a big new deck doesn't flood a session). */
fun ChatDb.dueCards(deck: String? = null, limit: Int = 50, newLimit: Int = 20, at: Long = now()): List<SrsCard> {
    val where = if (deck != null) "deck = ? AND " else ""
    val args = listOfNotNull(deck).toTypedArray()
    val due = readableDatabase.rawQuery("SELECT $SRS_COLS FROM flashcards WHERE ${where}due_at IS NOT NULL AND due_at <= $at ORDER BY due_at LIMIT $limit", args).each { srs() }
    val fresh = readableDatabase.rawQuery("SELECT $SRS_COLS FROM flashcards WHERE ${where}due_at IS NULL ORDER BY id LIMIT ${minOf(newLimit, limit - due.size).coerceAtLeast(0)}", args).each { srs() }
    return due + fresh
}
fun ChatDb.srsCard(id: Long): SrsCard? = readableDatabase.rawQuery("SELECT $SRS_COLS FROM flashcards WHERE id = ?", arrayOf(id.toString())).each { srs() }.firstOrNull()

/** Applies a rating: new schedule, a review log row, and the old "known" flag kept meaningful (Good/Easy = known). */
fun ChatDb.rateCard(id: Long, r: Srs.Rating, at: Long = now()) {
    val c = srsCard(id) ?: return
    val n = Srs.next(c.state, r, at)
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.execSQL("UPDATE flashcards SET due_at = ?, interval_days = ?, ease = ?, lapses = ?, reviews = reviews + 1, last_reviewed = ?, updated_at = ?, " +
            "known = CASE WHEN ? >= 3 THEN 1 WHEN ? = 1 THEN 0 ELSE known END WHERE id = ?",
            arrayOf<Any>(n.dueAt, n.state.intervalDays, n.state.ease, n.state.lapses, at, at, r.ordinal + 1, r.ordinal + 1, id))
        db.insertOrThrow("card_reviews", null, ContentValues().apply { put("card_id", id); put("rating", r.ordinal + 1); put("interval_days", n.state.intervalDays); put("reviewed_at", at) })
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

data class CardStats(val total: Int, val due: Int, val fresh: Int, val mature: Int, val reviewsToday: Int)
fun ChatDb.cardStats(at: Long = now(), dayStart: Long): CardStats = readableDatabase.rawQuery(
    "SELECT COUNT(*), SUM(CASE WHEN due_at IS NULL OR due_at <= $at THEN 1 ELSE 0 END), SUM(CASE WHEN due_at IS NULL THEN 1 ELSE 0 END), " +
        "SUM(CASE WHEN interval_days >= 21 THEN 1 ELSE 0 END), (SELECT COUNT(*) FROM card_reviews WHERE reviewed_at >= $dayStart) FROM flashcards", null)
    .use { it.moveToFirst(); CardStats(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4)) }

fun ChatDb.deckStats(at: Long = now()): List<StudyInsights.DeckStat> = readableDatabase.rawQuery(
    "SELECT deck, COUNT(*), SUM(CASE WHEN due_at IS NOT NULL AND due_at <= $at THEN 1 ELSE 0 END), SUM(CASE WHEN lapses >= 2 THEN 1 ELSE 0 END), MAX(last_reviewed) " +
        "FROM flashcards GROUP BY deck", null).each { StudyInsights.DeckStat(getString(0), getInt(1), getInt(2), getInt(3), if (isNull(4)) null else getLong(4)) }

fun ChatDb.quizStats(): List<StudyInsights.QuizStat> = readableDatabase.rawQuery(
    "SELECT category, COUNT(*), SUM(correct) FROM quiz_answers GROUP BY category", null).each { StudyInsights.QuizStat(getString(0), getInt(1), getInt(2)) }

fun ChatDb.planStats(): List<StudyInsights.PlanStat> = readableDatabase.rawQuery(
    "SELECT p.topic, (SELECT COUNT(*) FROM plan_items i WHERE i.plan_id = p.id AND i.done = 1), (SELECT COUNT(*) FROM plan_items i WHERE i.plan_id = p.id), " +
        "p.target_date, MAX(p.updated_at, COALESCE((SELECT MAX(done_at) FROM plan_items i WHERE i.plan_id = p.id), 0)) FROM study_plans p", null)
    .each { StudyInsights.PlanStat(getString(0), getInt(1), getInt(2), getString(3), getLong(4)) }

fun ChatDb.quizSessionStats(): List<StudyInsights.SessionStat> = readableDatabase.rawQuery(
    "SELECT title, score, total, updated_at FROM study_sessions WHERE score IS NOT NULL AND total IS NOT NULL AND total > 0 ORDER BY updated_at DESC LIMIT 100", null)
    .each { StudyInsights.SessionStat(getString(0), getInt(1), getInt(2), getLong(3)) }

// ------------------------------------------------------------------ plans: documents and manual editing
fun ChatDb.setPlanDocs(planId: Long, docIds: List<Long>) =
    writableDatabase.execSQL("UPDATE study_plans SET doc_ids = ? WHERE id = ?", arrayOf<Any?>(docIds.joinToString(",").ifEmpty { null }, planId))
fun ChatDb.planDocs(planId: Long): List<Long> = readableDatabase.rawQuery("SELECT doc_ids FROM study_plans WHERE id = ?", arrayOf(planId.toString()))
    .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0).split(',').mapNotNull { s -> s.trim().toLongOrNull() } else emptyList() }
fun ChatDb.editLesson(id: Long, text: String) = writableDatabase.execSQL("UPDATE plan_items SET text = ? WHERE id = ?", arrayOf<Any>(text.trim().take(500), id))
fun ChatDb.deleteLesson(id: Long) = writableDatabase.delete("plan_items", "id = ?", arrayOf(id.toString()))
fun ChatDb.addLesson(planId: Long, text: String) = writableDatabase.execSQL(
    "INSERT INTO plan_items (plan_id, position, text) VALUES (?, (SELECT COALESCE(MAX(position), -1) + 1 FROM plan_items WHERE plan_id = ?), ?)", arrayOf<Any>(planId, planId, text.trim().take(500)))
