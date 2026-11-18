package ai.abhify.android.chat

import ai.abhify.android.sync.SyncRecord
import ai.abhify.android.sync.SyncStore
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/**
 * Chats, memories, preferences and learning progress in app-private SQLite.
 * v3 adds what sync needs: a stable uid, updated_at, a deleted flag (tombstone) and a dirty flag per
 * record. Data stays on the phone unless the user signs in and keeps sync on.
 */
class ChatDb(context: Context, name: String? = "abhify.db") : SQLiteOpenHelper(context, name, null, VERSION), SyncStore {
    companion object { const val VERSION = 17 }
    data class Conversation(val id: Long, val uid: String, val title: String, val updatedAt: Long, val pinned: Boolean = false,
                            val folder: String? = null, val summary: String? = null, val archived: Boolean = false)
    /** [status]: null = complete; "length", "stopped", "interrupted" (the answer is partial). [feedback]: null, 1, -1. */
    data class Message(val id: Long, val uid: String, val conversationId: Long, val role: String, val content: String,
                       val createdAt: Long, val stats: String?, val model: String?, val status: String? = null, val feedback: Int? = null)
    data class Memory(val id: Long, val uid: String, val content: String, val createdAt: Long)

    /** Called when a synced preference arrives from another device. */
    var onRemotePreference: (key: String, value: String) -> Unit = { _, _ -> }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, stats TEXT)")
        db.execSQL("CREATE INDEX messages_conv ON messages(conversation_id, id)")
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        createV2(db)
        upgradeToV3(db)
        upgradeToV4(db)
        SchemaV5.apply(db::execSQL)
        SchemaV6.apply(db::execSQL)
        SchemaV7.apply(db::execSQL)
        SchemaV8.apply(db::execSQL)
        SchemaV9.apply(db::execSQL)
        SchemaV10.apply(db::execSQL)
        SchemaV11.apply(db::execSQL)
        SchemaV12.apply(db::execSQL)
        SchemaV13.apply(db::execSQL)
        SchemaV14.apply(db::execSQL)
        SchemaV15.apply(db::execSQL)
        SchemaV16.apply(db::execSQL)
        SchemaV17.apply(db::execSQL)
    }

    /** v4: pinned conversations. Additive. */
    private fun upgradeToV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }

    /** v2: learning progress. Additive only. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS quiz_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, question TEXT NOT NULL, correct INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS interview_views (question TEXT PRIMARY KEY, category TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }

    /** v3: sync metadata. Additive: existing rows get a uid and updated_at and are marked to upload once. */
    private fun upgradeToV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN uid TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE messages ADD COLUMN uid TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
        db.execSQL("ALTER TABLE memories ADD COLUMN uid TEXT")
        db.execSQL("ALTER TABLE memories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        for (t in listOf("conversations", "messages", "memories")) {
            db.execSQL("UPDATE $t SET uid = lower(hex(randomblob(16))) WHERE uid IS NULL")
            db.execSQL("CREATE UNIQUE INDEX ${t}_uid ON $t(uid)")
        }
        db.execSQL("UPDATE messages SET updated_at = created_at WHERE updated_at = 0")
        db.execSQL("UPDATE memories SET updated_at = created_at WHERE updated_at = 0")

        // the local profile: works with no account and no internet. Every existing row belongs to it.
        val now = System.currentTimeMillis()
        val profile = "prof-" + UUID.randomUUID().toString().replace("-", "")
        db.execSQL("CREATE TABLE local_profiles (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, avatar TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO local_profiles (id, display_name, created_at, updated_at) VALUES (?, '', ?, ?)", arrayOf<Any>(profile, now, now))
        for (t in listOf("conversations", "messages", "memories")) {
            db.execSQL("ALTER TABLE $t ADD COLUMN profile_id TEXT")
            db.execSQL("UPDATE $t SET profile_id = ?", arrayOf<Any>(profile))
        }
        db.execSQL("CREATE TABLE linked_accounts (profile_id TEXT PRIMARY KEY REFERENCES local_profiles(id), server_url TEXT NOT NULL, user_id TEXT NOT NULL, email TEXT NOT NULL, linked_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, this_device INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE user_preferences (key TEXT PRIMARY KEY, profile_id TEXT NOT NULL, value TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE sync_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO sync_metadata (key, value) VALUES ('profile_id', ?)", arrayOf<Any>(profile))
        // both versions of a concurrent edit are kept here until the user chooses
        db.execSQL("CREATE TABLE sync_conflicts (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, record_id TEXT NOT NULL, local_data TEXT NOT NULL, " +
            "remote_data TEXT NOT NULL, local_updated_at INTEGER NOT NULL, remote_updated_at INTEGER NOT NULL, detected_at INTEGER NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE model_registry (id TEXT PRIMARY KEY, version TEXT NOT NULL, sha256 TEXT NOT NULL, size_bytes INTEGER NOT NULL, runtime TEXT NOT NULL, " +
            "context INTEGER NOT NULL, self_test TEXT, active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE document_metadata (id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, name TEXT NOT NULL, mime TEXT, size_bytes INTEGER, sha256 TEXT, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")   // reserved: no document feature yet
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) upgradeToV3(db)
        if (oldVersion < 4) upgradeToV4(db)
        if (oldVersion < 5) SchemaV5.apply(db::execSQL)
        if (oldVersion < 6) SchemaV6.apply(db::execSQL)
        if (oldVersion < 7) SchemaV7.apply(db::execSQL)
        if (oldVersion < 8) SchemaV8.apply(db::execSQL)
        if (oldVersion < 9) SchemaV9.apply(db::execSQL)
        if (oldVersion < 10) SchemaV10.apply(db::execSQL)
        if (oldVersion < 11) SchemaV11.apply(db::execSQL)
        if (oldVersion < 12) SchemaV12.apply(db::execSQL)
        if (oldVersion < 13) SchemaV13.apply(db::execSQL)
        if (oldVersion < 14) SchemaV14.apply(db::execSQL)
        if (oldVersion < 15) SchemaV15.apply(db::execSQL)
        if (oldVersion < 16) SchemaV16.apply(db::execSQL)
        if (oldVersion < 17) SchemaV17.apply(db::execSQL)
    }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    /** True when the optional full-text indexes exist (FTS4); otherwise searches use LIKE. */
    @Volatile var ftsReady = false; private set

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // optional, idempotent: a failure here only disables full-text search (never the database)
        ftsReady = runCatching {
            if (!db.isReadOnly) { SearchIndex.STATEMENTS.forEach(db::execSQL); db.execSQL(SearchIndex.BACKFILL) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'messages_fts'", null).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun now() = System.currentTimeMillis()
    private fun newUid() = UUID.randomUUID().toString().replace("-", "")

    // ------------------------------------------------------------------ conversations and messages
    fun newConversation(title: String): Long {
        val now = now()
        return writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("uid", newUid()); put("profile_id", profileId()); put("title", title.take(80)); put("created_at", now); put("updated_at", now); put("dirty", 1)
        })
    }

    fun conversations(): List<Conversation> = readableDatabase.rawQuery(
        "SELECT id, uid, title, updated_at, pinned, folder, summary, archived FROM conversations WHERE deleted = 0 ORDER BY pinned DESC, updated_at DESC", null).use { c ->
        generateSequence { if (c.moveToNext()) Conversation(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1,
            c.getString(5), c.getString(6), c.getInt(7) == 1) else null }.toList()
    }

    /** Archive keeps everything (searchable, exportable, restorable); it only moves the chat out of the main list. */
    fun setArchived(id: Long, archived: Boolean) {
        writableDatabase.execSQL("UPDATE conversations SET archived = ?, archived_at = ? WHERE id = ?", arrayOf<Any?>(if (archived) 1 else 0, if (archived) now() else null, id))
    }

    fun setFolder(id: Long, folder: String?) {
        writableDatabase.execSQL("UPDATE conversations SET folder = ? WHERE id = ?", arrayOf<Any?>(folder, id))
    }

    fun setSummary(id: Long, summary: String?) {
        writableDatabase.execSQL("UPDATE conversations SET summary = ? WHERE id = ?", arrayOf<Any?>(summary, id))
    }

    /** Conversations whose title or any message contains [q] (case-insensitive), pinned first. */
    fun search(q: String): List<Conversation> {
        // full-text (word prefixes) when the index exists; titles always by LIKE; LIKE fallback for messages
        val fts = SearchIndex.query(q)
        if (ftsReady && fts != null) runCatching {
            val like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            return readableDatabase.rawQuery(
                "SELECT c.id, c.uid, c.title, c.updated_at, c.pinned, c.folder, c.summary, c.archived FROM conversations c WHERE c.deleted = 0 AND (c.title LIKE ? ESCAPE '\\' " +
                    "OR c.id IN (SELECT m.conversation_id FROM messages m WHERE m.deleted = 0 AND m.id IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH ?))) " +
                    "ORDER BY c.pinned DESC, c.updated_at DESC", arrayOf(like, fts)).use { c ->
                generateSequence { if (c.moveToNext()) Conversation(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1,
                    c.getString(5), c.getString(6), c.getInt(7) == 1) else null }.toList()
            }
        }
        val like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return readableDatabase.rawQuery(
            "SELECT DISTINCT c.id, c.uid, c.title, c.updated_at, c.pinned, c.folder, c.summary, c.archived FROM conversations c LEFT JOIN messages m ON m.conversation_id = c.id AND m.deleted = 0 " +
                "WHERE c.deleted = 0 AND (c.title LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\') ORDER BY c.pinned DESC, c.updated_at DESC", arrayOf(like, like)).use { c ->
            generateSequence { if (c.moveToNext()) Conversation(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1,
                c.getString(5), c.getString(6), c.getInt(7) == 1) else null }.toList()
        }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.execSQL("UPDATE conversations SET pinned = ?, updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(if (pinned) 1 else 0, now(), id))
    }

    /** All chats and memories as JSON. Never includes API keys, tokens, settings or model files. */
    fun exportJson(): String {
        val out = JSONObject().put("format", "abhify-export-1").put("exported_at", now())
        val convs = org.json.JSONArray()
        conversations().forEach { c ->
            convs.put(JSONObject().put("id", c.uid).put("title", c.title).put("updated_at", c.updatedAt).put("pinned", c.pinned)
                .put("messages", org.json.JSONArray(messages(c.id).map { m ->
                    JSONObject().put("role", m.role).put("content", m.content).put("created_at", m.createdAt).put("model", m.model ?: JSONObject.NULL)
                        .put("status", m.status ?: "complete") })).put("folder", c.folder ?: "Personal"))
        }
        return out.put("conversations", convs)
            .put("memories", org.json.JSONArray(memories().map { JSONObject().put("content", it.content).put("created_at", it.createdAt) })).toString(2)
    }

    fun renameConversation(id: Long, title: String) {
        writableDatabase.execSQL("UPDATE conversations SET title = ?, updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(title.take(80), now(), id))
    }

    fun addMessage(conversationId: Long, role: String, content: String, stats: String? = null, model: String? = null, status: String? = null): Long {
        val now = now()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("uid", newUid()); put("profile_id", profileId()); put("conversation_id", conversationId); put("role", role); put("content", content)
                put("created_at", now); put("updated_at", now); put("stats", stats); put("model", model); put("status", status); put("dirty", 1)
            })
            db.execSQL("UPDATE conversations SET updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(now, conversationId))
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun messages(conversationId: Long): List<Message> = readableDatabase.rawQuery(
        "SELECT id, uid, conversation_id, role, content, created_at, stats, model, status, feedback FROM messages WHERE conversation_id = ? AND deleted = 0 ORDER BY created_at, id",
        arrayOf(conversationId.toString())).use { c ->
        generateSequence { if (c.moveToNext()) Message(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6),
            c.getString(7), c.getString(8), if (c.isNull(9)) null else c.getInt(9)) else null }.toList()
    }

    /** Continue generation: the extended answer replaces the stored one (complete text, never truncated). */
    fun updateAnswer(id: Long, content: String, status: String?, stats: String?, model: String?) {
        writableDatabase.execSQL("UPDATE messages SET content = ?, status = ?, stats = ?, model = ?, updated_at = ?, dirty = 1 WHERE id = ?",
            arrayOf<Any?>(content, status, stats, model, now(), id))
    }

    fun setFeedback(id: Long, value: Int?) {
        writableDatabase.execSQL("UPDATE messages SET feedback = ? WHERE id = ?", arrayOf<Any?>(value, id))
    }

    /** Soft delete (a tombstone that syncs). */
    fun deleteMessage(id: Long) {
        writableDatabase.execSQL("UPDATE messages SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(now(), id))
    }

    fun deleteConversation(id: Long) {
        val now = now()
        writableDatabase.apply {
            execSQL("UPDATE messages SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE conversation_id = ? AND deleted = 0", arrayOf<Any>(now, id))
            execSQL("UPDATE conversations SET deleted = 1, title = '', updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(now, id))
        }
    }

    // ------------------------------------------------------------------ memories
    fun addMemory(content: String): Long {
        val now = now()
        return writableDatabase.insertOrThrow("memories", null, ContentValues().apply {
            put("uid", newUid()); put("profile_id", profileId()); put("content", content); put("created_at", now); put("updated_at", now); put("dirty", 1)
        })
    }

    fun memories(): List<Memory> = readableDatabase.rawQuery("SELECT id, uid, content, created_at FROM memories WHERE deleted = 0 ORDER BY created_at, id", null).use { c ->
        generateSequence { if (c.moveToNext()) Memory(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) else null }.toList()
    }

    fun updateMemory(id: Long, content: String) {
        writableDatabase.execSQL("UPDATE memories SET content = ?, updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(content, now(), id))
    }

    fun deleteMemory(id: Long) {
        writableDatabase.execSQL("UPDATE memories SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE id = ?", arrayOf<Any>(now(), id))
    }

    /** Deletes every chat and memory here (as tombstones, so other synced devices delete them too). */
    fun deleteAll() {
        val now = now()
        writableDatabase.apply {
            execSQL("UPDATE messages SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
            execSQL("UPDATE conversations SET deleted = 1, title = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
            execSQL("UPDATE memories SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE deleted = 0", arrayOf<Any>(now))
        }
    }

    // ------------------------------------------------------------------ synced preferences
    fun setPreference(key: String, value: String) {
        // INSERT OR REPLACE, not "ON CONFLICT DO UPDATE": Android 10 ships SQLite 3.22, which has no upsert.
        writableDatabase.execSQL("INSERT OR REPLACE INTO user_preferences (key, profile_id, value, created_at, updated_at, dirty) VALUES (?, ?, ?, ?, ?, 1)", arrayOf<Any>(key, profileId(), value, now(), now()))
    }

    // ------------------------------------------------------------------ local profile, linked account, device, model registry
    data class Profile(val id: String, val displayName: String, val createdAt: Long)
    data class Linked(val serverUrl: String, val userId: String, val email: String, val linkedAt: Long)

    @Volatile private var cachedProfile: String? = null
    fun profileId(): String = cachedProfile ?: (state("profile_id") ?: error("no local profile")).also { cachedProfile = it }

    fun profile(): Profile = readableDatabase.rawQuery("SELECT id, display_name, created_at FROM local_profiles WHERE id = ?", arrayOf(profileId())).use {
        it.moveToFirst(); Profile(it.getString(0), it.getString(1), it.getLong(2))
    }

    fun setProfileName(name: String) {
        writableDatabase.execSQL("UPDATE local_profiles SET display_name = ?, updated_at = ? WHERE id = ?", arrayOf<Any>(name.trim().take(40), now(), profileId()))
    }

    fun linkedAccount(): Linked? = readableDatabase.rawQuery("SELECT server_url, user_id, email, linked_at FROM linked_accounts WHERE profile_id = ?", arrayOf(profileId())).use {
        if (it.moveToFirst()) Linked(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)) else null
    }

    /** Links this phone's profile to an online account. Nothing is uploaded by linking; sync is a separate choice. */
    fun link(serverUrl: String, userId: String, email: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = linkedAccount()
            db.execSQL("INSERT OR REPLACE INTO linked_accounts (profile_id, server_url, user_id, email, linked_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(profileId(), serverUrl, userId, email, now()))
            if (previous?.userId != userId) {                  // another account: start from a clean sync position, re-upload on enable
                db.execSQL("DELETE FROM sync_metadata WHERE key = 'cursor'")
                for (t in listOf("conversations", "messages", "memories", "user_preferences")) db.execSQL("UPDATE $t SET dirty = 1")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Unlinks: local data stays exactly as it is; the next link starts a fresh sync position. */
    fun unlink() {
        writableDatabase.apply {
            execSQL("DELETE FROM linked_accounts WHERE profile_id = ?", arrayOf<Any>(profileId()))
            execSQL("DELETE FROM sync_metadata WHERE key = 'cursor'")
        }
    }

    /** What would be uploaded if sync were turned on now (shown before the user enables it). */
    data class SyncPreview(val conversations: Int, val messages: Int, val memories: Int, val preferences: Int)
    fun syncPreview(): SyncPreview {
        fun n(sql: String) = readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
        return SyncPreview(n("SELECT COUNT(*) FROM conversations WHERE deleted = 0"), n("SELECT COUNT(*) FROM messages WHERE deleted = 0"),
            n("SELECT COUNT(*) FROM memories WHERE deleted = 0"), n("SELECT COUNT(*) FROM user_preferences"))
    }

    fun registerThisDevice(name: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO devices (id, name, this_device, created_at, updated_at) VALUES (?, ?, 1, " +
            "COALESCE((SELECT created_at FROM devices WHERE id = ?), ?), ?)", arrayOf<Any>(deviceId(), name.take(80), deviceId(), now(), now()))
    }

    /** Mirrors models/state.json (the authority) so the model list is queryable with everything else. */
    fun mirrorModels(rows: List<Map<String, Any?>>, activeId: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("model_registry", null, null)
            rows.forEach { r ->
                db.insertOrThrow("model_registry", null, ContentValues().apply {
                    put("id", r["id"] as String); put("version", r["version"] as String); put("sha256", r["sha256"] as String)
                    put("size_bytes", r["size"] as Long); put("runtime", r["runtime"] as String); put("context", r["context"] as Int)
                    put("self_test", r["selfTest"] as String?); put("active", if (r["id"] == activeId) 1 else 0)
                    put("created_at", r["installedAt"] as Long); put("updated_at", now())
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ------------------------------------------------------------------ conflicts (never silently discarded)
    data class Conflict(val id: Long, val kind: String, val recordId: String, val local: JSONObject, val remote: JSONObject, val localAt: Long, val remoteAt: Long)

    /** Called by sync when the server has a different version of a record edited here and not yet uploaded. */
    override fun captureConflict(remote: SyncRecord) {
        if (remote.kind == "message") return                     // messages are append-only: no concurrent edits to keep
        val local = localRecord(remote.kind, remote.id) ?: return
        if (local.deleted == remote.deleted && canonical(local.data) == canonical(remote.data)) return
        writableDatabase.insertOrThrow("sync_conflicts", null, ContentValues().apply {
            put("kind", remote.kind); put("record_id", remote.id)
            put("local_data", JSONObject(local.data.toString()).put("_deleted", local.deleted).toString())
            put("remote_data", JSONObject(remote.data.toString()).put("_deleted", remote.deleted).toString())
            put("local_updated_at", local.updatedAt); put("remote_updated_at", remote.updatedAt); put("detected_at", now())
        })
    }

    /** Key-order-independent form of a JSON object (Android's org.json has no similar()). */
    private fun canonical(o: JSONObject): String = o.keys().asSequence().sorted().joinToString(",") { k ->
        val v = o.opt(k); "$k=" + if (v is JSONObject) "{" + canonical(v) + "}" else v.toString()
    }

    fun conflicts(): List<Conflict> = readableDatabase.rawQuery(
        "SELECT id, kind, record_id, local_data, remote_data, local_updated_at, remote_updated_at FROM sync_conflicts WHERE resolved = 0 ORDER BY id", null).use { c ->
        generateSequence { if (c.moveToNext()) Conflict(c.getLong(0), c.getString(1), c.getString(2), JSONObject(c.getString(3)), JSONObject(c.getString(4)), c.getLong(5), c.getLong(6)) else null }.toList()
    }

    /** The user's choice becomes a new edit (newest timestamp), so every device converges on it. */
    fun resolveConflict(id: Long, keepLocal: Boolean) {
        val c = conflicts().firstOrNull { it.id == id } ?: return
        val chosen = if (keepLocal) c.local else c.remote
        val deleted = chosen.optBoolean("_deleted")
        val now = now()
        val db = writableDatabase
        db.beginTransaction()
        try {
            when (c.kind) {
                "memory" -> db.execSQL("UPDATE memories SET content = ?, deleted = ?, updated_at = ?, dirty = 1 WHERE uid = ?",
                    arrayOf<Any>(chosen.optString("content"), if (deleted) 1 else 0, now, c.recordId))
                "conversation" -> db.execSQL("UPDATE conversations SET title = ?, deleted = ?, updated_at = ?, dirty = 1 WHERE uid = ?",
                    arrayOf<Any>(chosen.optString("title"), if (deleted) 1 else 0, now, c.recordId))
                "preference" -> {
                    db.execSQL("UPDATE user_preferences SET value = ?, updated_at = ?, dirty = 1 WHERE key = ?", arrayOf<Any>(chosen.optString("value"), now, c.recordId.removePrefix("pref-")))
                    onRemotePreference(chosen.optString("key"), chosen.optString("value"))
                }
            }
            db.execSQL("UPDATE sync_conflicts SET resolved = 1 WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun localRecord(kind: String, id: String): SyncRecord? = readRecords(10, id).firstOrNull { it.kind == kind && it.id == id }

    // ------------------------------------------------------------------ SyncStore
    private fun state(key: String): String? = readableDatabase.rawQuery("SELECT value FROM sync_metadata WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun setState(key: String, v: String) = writableDatabase.execSQL("INSERT OR REPLACE INTO sync_metadata (key, value) VALUES (?, ?)", arrayOf<Any>(key, v))

    override fun deviceId(): String = state("device_id") ?: ("dev-" + newUid()).also { setState("device_id", it) }
    override fun cursor(): Long = state("cursor")?.toLongOrNull() ?: 0
    override fun setCursor(seq: Long) = setState("cursor", seq.toString())

    /** Forget the sync position (signing in to another account, or after cloud data was deleted). Everything uploads again. */
    fun resetSync() {
        writableDatabase.apply {
            execSQL("DELETE FROM sync_metadata WHERE key = 'cursor'")
            for (t in listOf("conversations", "messages", "memories", "user_preferences")) execSQL("UPDATE $t SET dirty = 1")
        }
    }

    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM conversations WHERE dirty = 1) + (SELECT COUNT(*) FROM messages WHERE dirty = 1) + " +
            "(SELECT COUNT(*) FROM memories WHERE dirty = 1) + (SELECT COUNT(*) FROM user_preferences WHERE dirty = 1)", null).use { it.moveToFirst(); it.getInt(0) }

    /** Synced kinds the user turned on (conversations include their messages). Default: all. */
    var syncKinds: Set<String> = setOf("conversation", "message", "memory", "preference")

    override fun dirty(limit: Int): List<SyncRecord> = readRecords(limit, null)
    override fun accepts(kind: String): Boolean = kind in syncKinds

    /** Reads records as sync sees them: dirty ones (uid == null) or exactly one record by uid. */
    private fun readRecords(limit: Int, uid: String?): List<SyncRecord> {
        val out = mutableListOf<SyncRecord>()
        val db = readableDatabase
        fun where(alias: String = "") = if (uid == null) "${alias}dirty = 1" else "${alias}uid = ?"
        fun args(rest: Int) = if (uid == null) arrayOf(rest.toString()) else arrayOf(uid, rest.toString())
        if ("conversation" in syncKinds || uid != null) db.rawQuery("SELECT uid, title, created_at, updated_at, deleted, pinned FROM conversations WHERE ${where()} LIMIT ?", args(limit)).use { c ->
            while (c.moveToNext()) out += SyncRecord("conversation", c.getString(0), c.getLong(3), c.getInt(4) == 1,
                JSONObject().put("title", c.getString(1)).put("created_at", c.getLong(2)).put("pinned", c.getInt(5) == 1))
        }
        if (out.size < limit && ("message" in syncKinds || uid != null)) db.rawQuery("SELECT m.uid, c.uid, m.role, m.content, m.created_at, m.updated_at, m.deleted, m.model FROM messages m " +
            "JOIN conversations c ON c.id = m.conversation_id WHERE ${where("m.")} LIMIT ?", args(limit - out.size)).use { c ->
            while (c.moveToNext()) out += SyncRecord("message", c.getString(0), c.getLong(5), c.getInt(6) == 1,
                JSONObject().put("conversation_id", c.getString(1)).put("role", c.getString(2)).put("content", c.getString(3))
                    .put("created_at", c.getLong(4)).put("model", c.getString(7) ?: JSONObject.NULL))
        }
        if (out.size < limit && ("memory" in syncKinds || uid != null)) db.rawQuery("SELECT uid, content, created_at, updated_at, deleted FROM memories WHERE ${where()} LIMIT ?", args(limit - out.size)).use { c ->
            while (c.moveToNext()) out += SyncRecord("memory", c.getString(0), c.getLong(3), c.getInt(4) == 1,
                JSONObject().put("content", c.getString(1)).put("created_at", c.getLong(2)))
        }
        if (out.size < limit && ("preference" in syncKinds || uid != null)) {
            val (w, a) = if (uid == null) "dirty = 1" to arrayOf((limit - out.size).toString()) else "key = ?" to arrayOf(uid.removePrefix("pref-"), (limit - out.size).toString())
            db.rawQuery("SELECT key, value, updated_at FROM user_preferences WHERE $w LIMIT ?", a).use { c ->
                while (c.moveToNext()) out += SyncRecord("preference", "pref-" + c.getString(0), c.getLong(2), false,
                    JSONObject().put("key", c.getString(0)).put("value", c.getString(1)))
            }
        }
        return out
    }

    private fun table(kind: String) = when (kind) { "conversation" -> "conversations"; "message" -> "messages"; "memory" -> "memories"; else -> null }

    override fun markClean(kind: String, id: String, updatedAt: Long) {
        if (kind == "preference") writableDatabase.execSQL("UPDATE user_preferences SET dirty = 0 WHERE key = ? AND updated_at = ?", arrayOf<Any>(id.removePrefix("pref-"), updatedAt))
        else table(kind)?.let { writableDatabase.execSQL("UPDATE $it SET dirty = 0 WHERE uid = ? AND updated_at = ?", arrayOf<Any>(id, updatedAt)) }
    }

    override fun localMeta(kind: String, id: String): Pair<Long, Boolean>? {
        val (sql, arg) = if (kind == "preference") "SELECT updated_at, dirty FROM user_preferences WHERE key = ?" to id.removePrefix("pref-")
            else (table(kind) ?: return null).let { "SELECT updated_at, dirty FROM $it WHERE uid = ?" } to id
        return readableDatabase.rawQuery(sql, arrayOf(arg)).use { if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) == 1) else null }
    }

    override fun applyRemote(r: SyncRecord) {
        val db = writableDatabase
        val d = r.data
        val del = if (r.deleted) 1 else 0
        db.beginTransaction()
        try {
            when (r.kind) {
                "conversation" -> upsert(db, "conversations", r.id, ContentValues().apply {
                    put("title", if (r.deleted) "" else d.optString("title", "Chat")); put("created_at", d.optLong("created_at", r.updatedAt)); put("pinned", if (d.optBoolean("pinned")) 1 else 0)
                    put("updated_at", r.updatedAt); put("deleted", del); put("dirty", 0)
                })
                "message" -> {
                    val convUid = d.optString("conversation_id")
                    val convId = conversationIdFor(db, convUid, r.updatedAt) ?: return
                    upsert(db, "messages", r.id, ContentValues().apply {
                        put("conversation_id", convId); put("role", d.optString("role", "assistant")); put("content", if (r.deleted) "" else d.optString("content"))
                        put("created_at", d.optLong("created_at", r.updatedAt)); put("updated_at", r.updatedAt); put("deleted", del); put("dirty", 0)
                        put("model", if (d.isNull("model")) null else d.optString("model"))
                    })
                }
                "memory" -> {
                    // the same note written on two devices before linking: keep the synced id, retire the local duplicate
                    if (!r.deleted) db.execSQL("UPDATE memories SET deleted = 1, content = '', updated_at = ?, dirty = 1 WHERE uid != ? AND deleted = 0 AND lower(trim(content)) = lower(trim(?))",
                        arrayOf<Any>(r.updatedAt, r.id, d.optString("content")))
                    upsert(db, "memories", r.id, ContentValues().apply {
                    put("content", if (r.deleted) "" else d.optString("content")); put("created_at", d.optLong("created_at", r.updatedAt))
                    put("updated_at", r.updatedAt); put("deleted", del); put("dirty", 0)
                    })
                }
                "preference" -> {
                    val key = d.optString("key").ifEmpty { r.id.removePrefix("pref-") }; val value = d.optString("value")
                    db.execSQL("INSERT OR REPLACE INTO user_preferences (key, profile_id, value, created_at, updated_at, dirty) VALUES (?, ?, ?, ?, ?, 0)", arrayOf<Any>(key, profileId(), value, r.updatedAt, r.updatedAt))
                    onRemotePreference(key, value)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun upsert(db: SQLiteDatabase, table: String, uid: String, v: ContentValues) {
        if (db.update(table, v, "uid = ?", arrayOf(uid)) == 0) db.insertOrThrow(table, null, v.apply { put("uid", uid); put("profile_id", profileId()) })
    }

    /** A message can arrive before its conversation: a placeholder conversation is created and filled in later. */
    private fun conversationIdFor(db: SQLiteDatabase, uid: String, at: Long): Long? {
        if (uid.isEmpty()) return null
        db.rawQuery("SELECT id FROM conversations WHERE uid = ?", arrayOf(uid)).use { if (it.moveToFirst()) return it.getLong(0) }
        return db.insertOrThrow("conversations", null, ContentValues().apply {
            put("uid", uid); put("profile_id", profileId()); put("title", "Synced chat"); put("created_at", at); put("updated_at", 0L); put("dirty", 0)
        })
    }

    // ------------------------------------------------------------------ learning progress (v2)
    data class Progress(val answered: Int, val correct: Int, val interviewViewed: Int, val streakDays: Int)

    fun recordAnswer(category: String, question: String, correct: Boolean) {
        writableDatabase.insertOrThrow("quiz_answers", null, ContentValues().apply {
            put("category", category); put("question", question.take(500)); put("correct", if (correct) 1 else 0); put("created_at", now())
        })
    }

    fun recordInterviewView(category: String, question: String) {
        writableDatabase.insertWithOnConflict("interview_views", null, ContentValues().apply {
            put("question", question.take(500)); put("category", category); put("created_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun progress(): Progress {
        val db = readableDatabase
        val (n, ok) = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(correct), 0) FROM quiz_answers", null).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        val views = db.rawQuery("SELECT COUNT(*) FROM interview_views", null).use { it.moveToFirst(); it.getInt(0) }
        val tz = java.util.TimeZone.getDefault()
        fun day(ms: Long) = Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)
        val days = db.rawQuery("SELECT created_at FROM quiz_answers UNION ALL SELECT created_at FROM interview_views", null).use { c ->
            generateSequence { if (c.moveToNext()) day(c.getLong(0)) else null }.toSet()
        }
        return Progress(n, ok, views, ai.abhify.android.learn.Streak.days(days, day(now())))
    }

    fun resetProgress() { writableDatabase.apply { delete("quiz_answers", null, null); delete("interview_views", null, null) } }
}
