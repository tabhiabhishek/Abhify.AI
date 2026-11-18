package ai.abhify.android.workspace

/**
 * One local search over everything the user keeps in Abhify (v4.0): conversations, memories, documents, notes, tasks,
 * the knowledge vault, study material and career records. It runs on this phone's database only — never online.
 * Each type is one SQL query returning (id, title, snippet, at, ref); ?1 = LIKE pattern, ?2 = lower-case term, ?3 = limit.
 * Plain SQL (SQLite 3.22) so the JVM tests run every query against the real schema.
 */
object UnifiedSearch {
    enum class Type(val label: String, val icon: String) {
        CHAT("Conversations", "💬"), MEMORY("Memory", "🧠"), DOCUMENT("Documents", "📚"), NOTE("Notes", "📝"), TASK("Tasks", "☑"),
        KNOWLEDGE("Knowledge", "🗂"), STUDY("Study", "🎯"), CARD("Flashcards", "🃏"), PLAN("Study plans", "🗓"), CAREER("Career", "💼")
    }
    data class Hit(val type: Type, val id: Long, val title: String, val snippet: String, val at: Long, val ref: Long?)

    private fun snip(col: String) = "substr($col, max(1, instr(lower($col), ?2) - 60), 180)"
    private const val L = "LIKE ?1 ESCAPE '\\'"

    val SQL: Map<Type, String> = mapOf(
        Type.CHAT to "SELECT c.id, c.title, COALESCE((SELECT ${snip("m.content")} FROM messages m WHERE m.conversation_id = c.id AND m.deleted = 0 AND m.content $L LIMIT 1), ''), " +
            "c.updated_at, NULL FROM conversations c WHERE c.deleted = 0 AND (c.title $L OR EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND m.deleted = 0 AND m.content $L)) " +
            "ORDER BY c.updated_at DESC LIMIT ?3",
        Type.MEMORY to "SELECT id, COALESCE(category, 'General'), content, updated_at, NULL FROM memories WHERE deleted = 0 AND (content $L OR category $L) ORDER BY updated_at DESC LIMIT ?3",
        Type.DOCUMENT to "SELECT id, name, ${snip("text")}, updated_at, NULL FROM library_docs WHERE name $L OR text $L ORDER BY updated_at DESC LIMIT ?3",
        Type.NOTE to "SELECT n.id, n.title, ${snip("n.body")}, n.updated_at, n.workspace_id FROM notes n WHERE n.title $L OR n.body $L ORDER BY n.updated_at DESC LIMIT ?3",
        Type.TASK to "SELECT t.id, t.title, CASE WHEN t.done = 1 THEN 'done' ELSE 'open' END, t.updated_at, t.workspace_id FROM tasks t WHERE t.title $L ORDER BY t.updated_at DESC LIMIT ?3",
        Type.KNOWLEDGE to "SELECT id, title, ${snip("body")}, updated_at, NULL FROM vault_items WHERE title $L OR body $L OR tags $L ORDER BY updated_at DESC LIMIT ?3",
        Type.STUDY to "SELECT id, title, ${snip("content")}, updated_at, NULL FROM study_sessions WHERE title $L OR content $L ORDER BY updated_at DESC LIMIT ?3",
        Type.CARD to "SELECT id, deck, front || ' — ' || back, updated_at, NULL FROM flashcards WHERE front $L OR back $L OR deck $L ORDER BY updated_at DESC LIMIT ?3",
        Type.PLAN to "SELECT p.id, p.topic, COALESCE((SELECT i.text FROM plan_items i WHERE i.plan_id = p.id AND i.text $L LIMIT 1), COALESCE(p.goal, '')), p.updated_at, NULL " +
            "FROM study_plans p WHERE p.topic $L OR p.goal $L OR EXISTS (SELECT 1 FROM plan_items i WHERE i.plan_id = p.id AND i.text $L) ORDER BY p.updated_at DESC LIMIT ?3",
        Type.CAREER to "SELECT id, 'Job: ' || title || COALESCE(' · ' || company, ''), ${snip("body")}, updated_at, NULL FROM job_descriptions WHERE title $L OR company $L OR body $L " +
            "UNION ALL SELECT id, 'Application: ' || company || ' · ' || title, COALESCE(notes, status), updated_at, -1 FROM job_applications WHERE company $L OR title $L OR notes $L " +
            "UNION ALL SELECT id, 'Interview practice: ' || topic, question, created_at, -2 FROM interview_attempts WHERE question $L OR answer $L LIMIT ?3",
    )

    /** LIKE pattern with %, _ and \ escaped (the user's text is never interpreted as a wildcard). Blank → null (no search). */
    fun pattern(q: String): String? = q.trim().takeIf { it.length >= 2 }?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
    fun args(q: String, limit: Int): Array<String>? = pattern(q)?.let { arrayOf(it, q.trim().lowercase(), limit.toString()) }

    /** 5.6 Search 2.0 filters, applied after the per-type SQL (which already enforces the memory permission). */
    enum class Sort(val label: String) { RELEVANCE("Relevance"), RECENT("Recent activity") }
    data class Filters(val days: Int = 0, val sort: Sort = Sort.RELEVANCE, val project: Long? = null) {
        val active get() = days > 0 || sort != Sort.RELEVANCE || project != null
    }
    private const val DAY = 86_400_000L

    /**
     * [days] > 0 keeps items changed in that window; [project] keeps only the project's own notes and to-dos and the chats and
     * documents linked to it ([projectChats], [projectDocs]) — other types have no project, so they're left out.
     */
    fun filter(hits: List<Hit>, q: String, f: Filters, now: Long, projectChats: Set<Long> = emptySet(), projectDocs: Set<Long> = emptySet()): List<Hit> {
        val since = if (f.days > 0) now - f.days * DAY else Long.MIN_VALUE
        val kept = hits.filter { it.at >= since }.filter { h ->
            f.project == null || when (h.type) {
                Type.NOTE, Type.TASK -> h.ref == f.project
                Type.CHAT -> h.id in projectChats
                Type.DOCUMENT -> h.id in projectDocs
                else -> false
            }
        }
        return if (f.sort == Sort.RECENT) kept.sortedByDescending { it.at } else rank(kept, q, Int.MAX_VALUE)
    }

    /** Title matches first, then the newest; at most [perType] per type so one busy type can't hide the others. */
    fun rank(hits: List<Hit>, q: String, perType: Int = 8): List<Hit> {
        val t = q.trim().lowercase()
        return hits.groupBy { it.type }.flatMap { (_, l) -> l.sortedWith(compareByDescending<Hit> { it.title.lowercase().contains(t) }.thenByDescending { it.at }).take(perType) }
            .sortedWith(compareBy<Hit> { if (it.title.lowercase().contains(t)) 0 else 1 }.thenBy { it.type.ordinal }.thenByDescending { it.at })
    }
}
