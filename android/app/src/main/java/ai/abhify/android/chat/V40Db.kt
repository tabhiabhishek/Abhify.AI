package ai.abhify.android.chat

import ai.abhify.android.workspace.UnifiedSearch
import android.content.ContentValues

/** Database v15 access: unified local search and workspace links. */
fun ChatDb.unifiedSearch(q: String, types: Set<UnifiedSearch.Type> = UnifiedSearch.Type.entries.toSet(), perType: Int = 8): List<UnifiedSearch.Hit> {
    val args = UnifiedSearch.args(q, perType * 2) ?: return emptyList()
    val out = mutableListOf<UnifiedSearch.Hit>()
    for (t in types) {
        val sql = UnifiedSearch.SQL[t] ?: continue
        runCatching {                                                                     // one failing source never hides the others
            readableDatabase.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) out += UnifiedSearch.Hit(t, c.getLong(0), c.getString(1) ?: "", (c.getString(2) ?: "").replace('\n', ' ').trim(),
                    c.getLong(3), if (c.isNull(4)) null else c.getLong(4))
            }
        }
    }
    return UnifiedSearch.rank(out, q, perType)
}

data class WorkspaceLink(val id: Long, val workspaceId: Long, val kind: String, val refId: Long, val title: String?)

/** Links an existing item to a workspace (no copy). Returns false when it was already linked. */
fun ChatDb.linkToWorkspace(workspaceId: Long, kind: String, refId: Long): Boolean =
    writableDatabase.insertWithOnConflict("workspace_links", null, ContentValues().apply {
        put("workspace_id", workspaceId); put("kind", kind); put("ref_id", refId); put("created_at", System.currentTimeMillis()) },
        android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) > 0
fun ChatDb.unlink(id: Long) = writableDatabase.delete("workspace_links", "id = ?", arrayOf(id.toString()))

/** A workspace's links with the current title of each item (null when the item was deleted since). */
fun ChatDb.workspaceLinks(workspaceId: Long): List<WorkspaceLink> = readableDatabase.rawQuery(
    "SELECT l.id, l.workspace_id, l.kind, l.ref_id, CASE l.kind " +
        "WHEN 'chat' THEN (SELECT title FROM conversations WHERE id = l.ref_id AND deleted = 0) " +
        "WHEN 'doc' THEN (SELECT name FROM library_docs WHERE id = l.ref_id) " +
        "WHEN 'memory' THEN (SELECT content FROM memories WHERE id = l.ref_id AND deleted = 0) END FROM workspace_links l WHERE l.workspace_id = ? ORDER BY l.created_at DESC",
    arrayOf(workspaceId.toString())).use { c -> generateSequence { if (c.moveToNext()) WorkspaceLink(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getString(4)) else null }.toList() }

/** Documents of a workspace: the ones imported into it plus the ones linked to it. */
fun ChatDb.workspaceDocIds(workspaceId: Long): List<Long> = readableDatabase.rawQuery(
    "SELECT id FROM library_docs WHERE workspace_id = ?1 UNION SELECT ref_id FROM workspace_links WHERE workspace_id = ?1 AND kind = 'doc' AND ref_id IN (SELECT id FROM library_docs)",
    arrayOf(workspaceId.toString())).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
