package ai.abhify.android.chat

import android.content.ContentValues
import android.database.Cursor
import java.util.UUID

/** Local data for Career, Study and the Knowledge Vault (database v7). Never synced; deleting removes the rows. */
/** [fields]: the extracted and user-corrected structure (JSON, see career.JobFields); null until analysed. */
data class JobDescription(val id: Long, val title: String, val company: String?, val body: String, val updatedAt: Long, val fields: String? = null)
data class JobApplication(val id: Long, val company: String, val title: String, val jdId: Long?, val resumeDocId: Long?, val resumeVersionId: Long?,
                          val appliedOn: String?, val status: String, val notes: String?, val followUpOn: String?, val interviewOn: String?, val updatedAt: Long)
data class InterviewAttempt(val id: Long, val topic: String, val question: String, val answer: String, val feedback: String?, val source: String?, val createdAt: Long,
                            val jdId: Long? = null, val score: Double? = null)
data class StudySession(val id: Long, val title: String, val kind: String, val sourceKind: String, val sourceRef: String?, val content: String,
                        val score: Int?, val total: Int?, val updatedAt: Long)
data class VaultItem(val id: Long, val kind: String, val title: String, val body: String, val category: String, val tags: List<String>,
                     val favorite: Boolean, val refKind: String?, val refId: Long?, val updatedAt: Long, val openedAt: Long?)

private fun <T> Cursor.rows(row: Cursor.() -> T): List<T> = use { c -> generateSequence { if (c.moveToNext()) c.row() else null }.toList() }
private fun now() = System.currentTimeMillis()
private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)
private fun Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)
private fun like(q: String) = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

// ------------------------------------------------------------------ job descriptions
fun ChatDb.saveJd(id: Long?, title: String, company: String?, body: String, fields: String? = null): Long {
    val v = ContentValues().apply { put("title", title.take(120)); put("company", company?.take(120)); put("body", body); put("fields", fields); put("updated_at", now()) }
    return if (id == null) writableDatabase.insertOrThrow("job_descriptions", null, v.apply { put("created_at", now()) })
    else { writableDatabase.update("job_descriptions", v, "id = ?", arrayOf(id.toString())); id }
}
fun ChatDb.jds(): List<JobDescription> = readableDatabase.rawQuery("SELECT id, title, company, body, updated_at, fields FROM job_descriptions ORDER BY updated_at DESC", null)
    .rows { JobDescription(getLong(0), getString(1), getString(2), getString(3), getLong(4), getString(5)) }
fun ChatDb.deleteJd(id: Long) = writableDatabase.apply {
    beginTransaction()
    try { execSQL("UPDATE job_applications SET jd_id = NULL WHERE jd_id = ?", arrayOf<Any>(id)); execSQL("UPDATE interview_attempts SET jd_id = NULL WHERE jd_id = ?", arrayOf<Any>(id))
        delete("job_descriptions", "id = ?", arrayOf(id.toString())); setTransactionSuccessful() }
    finally { endTransaction() }
}

// ------------------------------------------------------------------ application tracker
private const val APP_COLS = "id, company, title, jd_id, resume_doc_id, resume_version_id, applied_on, status, notes, follow_up_on, interview_on, updated_at"
private fun Cursor.app() = JobApplication(getLong(0), getString(1), getString(2), longOrNull(3), longOrNull(4), longOrNull(5), getString(6), getString(7),
    getString(8), getString(9), getString(10), getLong(11))

fun ChatDb.saveApplication(a: JobApplication): Long {
    val v = ContentValues().apply {
        put("company", a.company.take(120)); put("title", a.title.take(120)); put("jd_id", a.jdId); put("resume_doc_id", a.resumeDocId)
        put("resume_version_id", a.resumeVersionId); put("applied_on", a.appliedOn); put("status", a.status); put("notes", a.notes)
        put("follow_up_on", a.followUpOn); put("interview_on", a.interviewOn); put("updated_at", now())
    }
    return if (a.id <= 0) writableDatabase.insertOrThrow("job_applications", null, v.apply { put("created_at", now()) })
    else { writableDatabase.update("job_applications", v, "id = ?", arrayOf(a.id.toString())); a.id }
}
fun ChatDb.applications(): List<JobApplication> = readableDatabase.rawQuery("SELECT $APP_COLS FROM job_applications ORDER BY updated_at DESC", null).rows { app() }
fun ChatDb.deleteApplication(id: Long) = writableDatabase.delete("job_applications", "id = ?", arrayOf(id.toString()))

// ------------------------------------------------------------------ interview practice
fun ChatDb.addAttempt(topic: String, question: String, answer: String, feedback: String?, source: String?, jdId: Long? = null, score: Double? = null): Long =
    writableDatabase.insertOrThrow("interview_attempts", null, ContentValues().apply {
        put("topic", topic.take(80)); put("question", question); put("answer", answer); put("feedback", feedback); put("source", source); put("created_at", now())
        put("jd_id", jdId); put("score", score)
    })
fun ChatDb.attempts(limit: Int = 50): List<InterviewAttempt> = readableDatabase.rawQuery(
    "SELECT id, topic, question, answer, feedback, source, created_at, jd_id, score FROM interview_attempts ORDER BY created_at DESC LIMIT $limit", null)
    .rows { InterviewAttempt(getLong(0), getString(1), getString(2), getString(3), getString(4), getString(5), getLong(6),
        if (isNull(7)) null else getLong(7), if (isNull(8)) null else getDouble(8)) }
fun ChatDb.deleteAttempt(id: Long) = writableDatabase.delete("interview_attempts", "id = ?", arrayOf(id.toString()))

// ------------------------------------------------------------------ study sessions
fun ChatDb.saveSession(id: Long?, title: String, kind: String, sourceKind: String, sourceRef: String?, content: String, score: Int?, total: Int?): Long {
    val v = ContentValues().apply {
        put("title", title.take(120)); put("kind", kind); put("source_kind", sourceKind); put("source_ref", sourceRef); put("content", content)
        put("score", score); put("total", total); put("updated_at", now())
    }
    return if (id == null) writableDatabase.insertOrThrow("study_sessions", null, v.apply { put("created_at", now()) })
    else { writableDatabase.update("study_sessions", v, "id = ?", arrayOf(id.toString())); id }
}
fun ChatDb.sessions(): List<StudySession> = readableDatabase.rawQuery(
    "SELECT id, title, kind, source_kind, source_ref, content, score, total, updated_at FROM study_sessions ORDER BY updated_at DESC", null)
    .rows { StudySession(getLong(0), getString(1), getString(2), getString(3), getString(4), getString(5), intOrNull(6), intOrNull(7), getLong(8)) }
fun ChatDb.deleteSession(id: Long) = writableDatabase.delete("study_sessions", "id = ?", arrayOf(id.toString()))

// ------------------------------------------------------------------ knowledge vault
private const val VAULT_COLS = "id, kind, title, body, category, tags, favorite, ref_kind, ref_id, updated_at, opened_at"
private fun Cursor.vault() = VaultItem(getLong(0), getString(1), getString(2), getString(3), getString(4),
    getString(5).split(',').map { it.trim() }.filter { it.isNotEmpty() }, getInt(6) == 1, getString(7), longOrNull(8), getLong(9), longOrNull(10))

fun ChatDb.addVault(kind: String, title: String, body: String, category: String, tags: List<String>, refKind: String? = null, refId: Long? = null): Long =
    writableDatabase.insertOrThrow("vault_items", null, ContentValues().apply {
        put("uid", UUID.randomUUID().toString().replace("-", "")); put("kind", kind); put("title", title.take(160)); put("body", body)
        put("category", category); put("tags", tags.joinToString(",")); put("ref_kind", refKind); put("ref_id", refId)
        put("created_at", now()); put("updated_at", now())
    })

/** [sort] "recent" (last opened or changed) or "title". [category] null = all. Text search covers title, body and tags. */
fun ChatDb.vault(query: String = "", category: String? = null, favoritesOnly: Boolean = false, sort: String = "recent"): List<VaultItem> {
    val where = mutableListOf<String>(); val args = mutableListOf<String>()
    if (query.isNotBlank()) { where += "(title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')"; repeat(3) { args += like(query) } }
    if (category != null) { where += "category = ?"; args += category }
    if (favoritesOnly) where += "favorite = 1"
    val order = if (sort == "title") "title COLLATE NOCASE" else "COALESCE(opened_at, updated_at) DESC"
    return readableDatabase.rawQuery("SELECT $VAULT_COLS FROM vault_items" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY $order",
        args.toTypedArray()).rows { vault() }
}
fun ChatDb.vaultItem(id: Long): VaultItem? = readableDatabase.rawQuery("SELECT $VAULT_COLS FROM vault_items WHERE id = ?", arrayOf(id.toString())).rows { vault() }.firstOrNull()
fun ChatDb.touchVault(id: Long) = writableDatabase.execSQL("UPDATE vault_items SET opened_at = ? WHERE id = ?", arrayOf<Any>(now(), id))
fun ChatDb.updateVault(id: Long, title: String, category: String, tags: List<String>, favorite: Boolean) =
    writableDatabase.execSQL("UPDATE vault_items SET title = ?, category = ?, tags = ?, favorite = ?, updated_at = ? WHERE id = ?",
        arrayOf<Any>(title.take(160), category, tags.joinToString(","), if (favorite) 1 else 0, now(), id))
fun ChatDb.deleteVault(id: Long) = writableDatabase.delete("vault_items", "id = ?", arrayOf(id.toString()))

/** Removes all Career, Study and Vault data from this phone (part of Privacy → delete documents, notes …). */
fun ChatDb.deleteLifeData() = writableDatabase.apply {
    for (t in listOf("job_applications", "job_descriptions", "interview_attempts", "study_sessions", "vault_items")) delete(t, null, null)
}
