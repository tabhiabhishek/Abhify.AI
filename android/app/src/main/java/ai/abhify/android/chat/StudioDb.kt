package ai.abhify.android.chat

import android.content.ContentValues
import android.database.Cursor
import java.util.UUID

/**
 * Local-only data for the Documents, Learn and Work areas (database v5). None of it is synced, exported to the
 * account or added to memory; deleting removes the rows from this phone.
 */
data class LibraryDoc(val id: Long, val name: String, val kind: String, val sizeBytes: Long, val chars: Int, val truncated: Boolean,
                      val workspaceId: Long?, val createdAt: Long, val updatedAt: Long,
                      val pages: Int? = null, val ocrPages: Int = 0, val warnings: List<String> = emptyList(), val role: String? = null)
/** A saved piece of resume work: a correction of the extracted text, or an AI output. The original stays in library_docs. */
data class ResumeVersion(val id: Long, val docId: Long, val kind: String, val title: String, val body: String, val source: String?, val createdAt: Long)
data class Flashcard(val id: Long, val deck: String, val front: String, val back: String, val known: Boolean, val reviews: Int, val source: String?)
data class StudyPlan(val id: Long, val topic: String, val level: String, val goal: String?, val targetDate: String?, val minutesPerDay: Int?, val createdAt: Long)
data class PlanItem(val id: Long, val planId: Long, val position: Int, val text: String, val done: Boolean)
data class Workspace(val id: Long, val name: String, val kind: String)
data class Note(val id: Long, val workspaceId: Long, val title: String, val body: String, val kind: String, val updatedAt: Long)
data class Task(val id: Long, val workspaceId: Long, val title: String, val done: Boolean, val dueAt: Long?)

private fun <T> Cursor.all(row: Cursor.() -> T): List<T> = use { c -> generateSequence { if (c.moveToNext()) c.row() else null }.toList() }
private fun now() = System.currentTimeMillis()

// ------------------------------------------------------------------ document library
private const val DOC_COLS = "id, name, kind, size_bytes, chars, truncated, workspace_id, created_at, updated_at, pages, ocr_pages, warnings, role"
private fun Cursor.doc() = LibraryDoc(getLong(0), getString(1), getString(2), getLong(3), getInt(4), getInt(5) == 1, if (isNull(6)) null else getLong(6), getLong(7), getLong(8),
    if (isNull(9)) null else getInt(9), getInt(10), getString(11)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(), getString(12))

fun ChatDb.addDoc(name: String, kind: String, sizeBytes: Long, text: String, truncated: Boolean, workspaceId: Long? = null,
                  pages: Int? = null, ocrPages: Int = 0, warnings: List<String> = emptyList(), role: String? = null): Long =
    writableDatabase.insertOrThrow("library_docs", null, ContentValues().apply {
        put("uid", UUID.randomUUID().toString().replace("-", "")); put("name", name.take(200)); put("kind", kind); put("size_bytes", sizeBytes)
        put("chars", text.length); put("text", text); put("truncated", if (truncated) 1 else 0); put("workspace_id", workspaceId)
        put("created_at", now()); put("updated_at", now())
        put("pages", pages); put("ocr_pages", ocrPages); put("warnings", warnings.joinToString("\n").ifEmpty { null }); put("role", role)
    })

fun ChatDb.doc(id: Long): LibraryDoc? = readableDatabase.rawQuery("SELECT $DOC_COLS FROM library_docs WHERE id = ?", arrayOf(id.toString())).all { doc() }.firstOrNull()
fun ChatDb.resumes(): List<LibraryDoc> = readableDatabase.rawQuery("SELECT $DOC_COLS FROM library_docs WHERE role = 'resume' ORDER BY updated_at DESC", null).all { doc() }
fun ChatDb.setRole(id: Long, role: String?) = writableDatabase.execSQL("UPDATE library_docs SET role = ?, updated_at = ? WHERE id = ?", arrayOf<Any?>(role, now(), id))

fun ChatDb.addVersion(docId: Long, kind: String, title: String, body: String, source: String?): Long =
    writableDatabase.insertOrThrow("resume_versions", null, ContentValues().apply {
        put("doc_id", docId); put("kind", kind); put("title", title.take(120)); put("body", body); put("source", source); put("created_at", now())
    })
fun ChatDb.versions(docId: Long): List<ResumeVersion> = readableDatabase.rawQuery(
    "SELECT id, doc_id, kind, title, body, source, created_at FROM resume_versions WHERE doc_id = ? ORDER BY created_at DESC, id DESC", arrayOf(docId.toString()))
    .all { ResumeVersion(getLong(0), getLong(1), getString(2), getString(3), getString(4), getString(5), getLong(6)) }
fun ChatDb.deleteVersion(id: Long) = writableDatabase.delete("resume_versions", "id = ?", arrayOf(id.toString()))

/** Documents, newest first; [workspaceId] null = the personal library, else that workspace's documents. */
fun ChatDb.docs(workspaceId: Long? = null, query: String = ""): List<LibraryDoc> {
    val where = StringBuilder(if (workspaceId == null) "workspace_id IS NULL" else "workspace_id = ?")
    val args = mutableListOf<String>().apply { if (workspaceId != null) add(workspaceId.toString()) }
    if (query.isNotBlank()) {
        where.append(" AND (name LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\')")
        val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        args += like; args += like
    }
    return readableDatabase.rawQuery("SELECT $DOC_COLS FROM library_docs WHERE $where ORDER BY updated_at DESC", args.toTypedArray()).all { doc() }
}

/** Every document, top-level and inside workspaces (the Task Runner and projects pick from all of them). */
fun ChatDb.allDocs(): List<LibraryDoc> = readableDatabase.rawQuery("SELECT $DOC_COLS FROM library_docs ORDER BY updated_at DESC", null).all { doc() }
fun ChatDb.docById(id: Long): LibraryDoc? = readableDatabase.rawQuery("SELECT $DOC_COLS FROM library_docs WHERE id = ?", arrayOf(id.toString())).all { doc() }.firstOrNull()
fun ChatDb.docText(id: Long): String? = readableDatabase.rawQuery("SELECT text FROM library_docs WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
fun ChatDb.renameDoc(id: Long, name: String) = writableDatabase.execSQL("UPDATE library_docs SET name = ?, updated_at = ? WHERE id = ?", arrayOf<Any>(name.take(200), now(), id))
/** Removes the document and its text from this phone (the search index is built from this table, so nothing remains). */
fun ChatDb.deleteDoc(id: Long) = writableDatabase.apply {
    deleteChunks(id); deleteVectors(id)                                                       // the retrieval index never outlives its document
    delete("resume_versions", "doc_id = ?", arrayOf(id.toString())); delete("library_docs", "id = ?", arrayOf(id.toString()))
}

// ------------------------------------------------------------------ flashcards
private fun Cursor.card() = Flashcard(getLong(0), getString(1), getString(2), getString(3), getInt(4) == 1, getInt(5), getString(6))
fun ChatDb.addCards(deck: String, cards: List<Pair<String, String>>, source: String?) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        cards.forEach { (f, b) ->
            db.insertOrThrow("flashcards", null, ContentValues().apply {
                put("deck", deck.take(80)); put("front", f.take(500)); put("back", b.take(2000)); put("source", source); put("created_at", now()); put("updated_at", now())
            })
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
fun ChatDb.decks(): List<Pair<String, Pair<Int, Int>>> = readableDatabase.rawQuery(
    "SELECT deck, COUNT(*), SUM(known) FROM flashcards GROUP BY deck ORDER BY MAX(updated_at) DESC", null).all { getString(0) to (getInt(1) to getInt(2)) }
fun ChatDb.cards(deck: String): List<Flashcard> = readableDatabase.rawQuery(
    "SELECT id, deck, front, back, known, reviews, source FROM flashcards WHERE deck = ? ORDER BY known, reviews, id", arrayOf(deck)).all { card() }
fun ChatDb.reviewCard(id: Long, known: Boolean) =
    writableDatabase.execSQL("UPDATE flashcards SET known = ?, reviews = reviews + 1, updated_at = ? WHERE id = ?", arrayOf<Any>(if (known) 1 else 0, now(), id))
fun ChatDb.deleteDeck(deck: String) = writableDatabase.apply {
    execSQL("DELETE FROM card_reviews WHERE card_id IN (SELECT id FROM flashcards WHERE deck = ?)", arrayOf<Any>(deck))   // v3.9 review log goes with its cards
    delete("flashcards", "deck = ?", arrayOf(deck))
}

// ------------------------------------------------------------------ study plans
fun ChatDb.addPlan(topic: String, level: String, goal: String?, targetDate: String?, minutes: Int?, lessons: List<String>): Long {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val id = db.insertOrThrow("study_plans", null, ContentValues().apply {
            put("topic", topic.take(120)); put("level", level); put("goal", goal); put("target_date", targetDate); put("minutes_per_day", minutes)
            put("created_at", now()); put("updated_at", now())
        })
        lessons.forEachIndexed { i, t -> db.insertOrThrow("plan_items", null, ContentValues().apply { put("plan_id", id); put("position", i); put("text", t.take(500)) }) }
        db.setTransactionSuccessful()
        return id
    } finally { db.endTransaction() }
}
fun ChatDb.plans(): List<StudyPlan> = readableDatabase.rawQuery(
    "SELECT id, topic, level, goal, target_date, minutes_per_day, created_at FROM study_plans ORDER BY updated_at DESC", null)
    .all { StudyPlan(getLong(0), getString(1), getString(2), getString(3), getString(4), if (isNull(5)) null else getInt(5), getLong(6)) }
fun ChatDb.planItems(planId: Long): List<PlanItem> = readableDatabase.rawQuery(
    "SELECT id, plan_id, position, text, done FROM plan_items WHERE plan_id = ? ORDER BY position", arrayOf(planId.toString()))
    .all { PlanItem(getLong(0), getLong(1), getInt(2), getString(3), getInt(4) == 1) }
fun ChatDb.setLessonDone(itemId: Long, planId: Long, done: Boolean) = writableDatabase.apply {
    execSQL("UPDATE plan_items SET done = ?, done_at = ? WHERE id = ?", arrayOf<Any?>(if (done) 1 else 0, if (done) now() else null, itemId))
    execSQL("UPDATE study_plans SET updated_at = ? WHERE id = ?", arrayOf<Any>(now(), planId))
}
fun ChatDb.deletePlan(id: Long) = writableDatabase.apply { delete("plan_items", "plan_id = ?", arrayOf(id.toString())); delete("study_plans", "id = ?", arrayOf(id.toString())) }

// ------------------------------------------------------------------ workspaces, notes, tasks
fun ChatDb.workspaces(): List<Workspace> {
    val list = readableDatabase.rawQuery("SELECT id, name, kind FROM workspaces ORDER BY id", null).all { Workspace(getLong(0), getString(1), getString(2)) }
    if (list.isNotEmpty()) return list
    // first use: one personal and one work space (both on this phone)
    addWorkspace("Personal", "personal"); addWorkspace("Work", "work")
    return workspaces()
}
fun ChatDb.addWorkspace(name: String, kind: String): Long = writableDatabase.insertOrThrow("workspaces", null, ContentValues().apply {
    put("name", name.take(60)); put("kind", kind); put("created_at", now())
})
fun ChatDb.renameWorkspace(id: Long, name: String) = writableDatabase.execSQL("UPDATE workspaces SET name = ? WHERE id = ?", arrayOf<Any>(name.take(60), id))
fun ChatDb.deleteWorkspace(id: Long) = writableDatabase.apply {
    delete("notes", "workspace_id = ?", arrayOf(id.toString())); delete("tasks", "workspace_id = ?", arrayOf(id.toString()))
    // documents imported INTO this workspace go with it, including their retrieval index (v4.0 fix: chunks/vectors were left behind)
    docs(id).forEach { d -> deleteChunks(d.id); deleteVectors(d.id); delete("resume_versions", "doc_id = ?", arrayOf(d.id.toString())) }
    delete("library_docs", "workspace_id = ?", arrayOf(id.toString()))
    delete("workspace_links", "workspace_id = ?", arrayOf(id.toString()))       // links only: linked chats, documents and memories stay
    delete("workspaces", "id = ?", arrayOf(id.toString()))
}

/**
 * Projects: deleting a project never deletes documents. Documents imported into it move back to the main library first
 * (with their retrieval index); then only the project, its own notes and to-dos, and its links are removed.
 */
fun ChatDb.deleteWorkspaceKeepDocs(id: Long) = writableDatabase.apply {
    execSQL("UPDATE library_docs SET workspace_id = NULL, updated_at = ? WHERE workspace_id = ?", arrayOf<Any>(now(), id))
    deleteWorkspace(id)
}

fun ChatDb.notes(workspaceId: Long, query: String = ""): List<Note> {
    val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    return readableDatabase.rawQuery("SELECT id, workspace_id, title, body, kind, updated_at FROM notes WHERE workspace_id = ? " +
        "AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\') ORDER BY updated_at DESC", arrayOf(workspaceId.toString(), like, like))
        .all { Note(getLong(0), getLong(1), getString(2), getString(3), getString(4), getLong(5)) }
}
fun ChatDb.saveNote(id: Long?, workspaceId: Long, title: String, body: String, kind: String = "note"): Long {
    val v = ContentValues().apply { put("workspace_id", workspaceId); put("title", title.take(120)); put("body", body); put("kind", kind); put("updated_at", now()) }
    return if (id == null) writableDatabase.insertOrThrow("notes", null, v.apply { put("created_at", now()) })
    else { writableDatabase.update("notes", v, "id = ?", arrayOf(id.toString())); id }
}
fun ChatDb.deleteNote(id: Long) = writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))

fun ChatDb.tasks(workspaceId: Long): List<Task> = readableDatabase.rawQuery(
    "SELECT id, workspace_id, title, done, due_at FROM tasks WHERE workspace_id = ? ORDER BY done, COALESCE(due_at, 9e18), id", arrayOf(workspaceId.toString()))
    .all { Task(getLong(0), getLong(1), getString(2), getInt(3) == 1, if (isNull(4)) null else getLong(4)) }
fun ChatDb.addTasks(workspaceId: Long, titles: List<String>, dueAt: Long? = null) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        titles.filter { it.isNotBlank() }.forEach { t ->
            db.insertOrThrow("tasks", null, ContentValues().apply { put("workspace_id", workspaceId); put("title", t.take(300)); put("due_at", dueAt); put("created_at", now()); put("updated_at", now()) })
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
fun ChatDb.setTaskDone(id: Long, done: Boolean) = writableDatabase.execSQL("UPDATE tasks SET done = ?, updated_at = ? WHERE id = ?", arrayOf<Any>(if (done) 1 else 0, now(), id))
fun ChatDb.deleteTask(id: Long) = writableDatabase.delete("tasks", "id = ?", arrayOf(id.toString()))

/** Removes everything in the Documents, Learn (cards, plans) and Work areas from this phone. Chats and memories are separate. */
fun ChatDb.deleteStudioData() = writableDatabase.apply {
    if (ftsReady) runCatching { execSQL("DELETE FROM doc_chunks_fts") }
    for (t in listOf("plan_items", "study_plans", "card_reviews", "flashcards", "tasks", "notes", "workspace_links", "resume_versions", "doc_chunks", "chunk_vectors", "library_docs", "workspaces")) delete(t, null, null)
}
