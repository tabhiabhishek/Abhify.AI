package ai.abhify.android.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned backup format ("abhify-backup" v1): JSON with one array of rows per table, grouped in user-chosen
 * categories, plus a SHA-256 per table for an integrity check. Optionally encrypted with a passphrase
 * (PBKDF2-HMAC-SHA256 → AES-256-GCM). Never contains keys, tokens, passwords or signing material: only the tables
 * listed in [CATEGORIES] and a whitelist of non-secret settings.
 */
object Backup {
    const val FORMAT = "abhify-backup"; const val ENCRYPTED = "abhify-backup-encrypted"; const val VERSION = 1

    /** Category → the tables it contains (parents before children, the restore order). */
    val CATEGORIES: Map<String, List<String>> = linkedMapOf(
        "Personal memory" to listOf("memories"),
        "Conversations" to listOf("conversations", "messages"),
        "Notes and tasks" to listOf("workspaces", "notes", "tasks", "saved_prompts"),
        "Knowledge" to listOf("vault_items"),
        "Documents" to listOf("library_docs"),
        "Study" to listOf("study_plans", "plan_items", "flashcards", "study_sessions"),
        "Career" to listOf("job_descriptions", "resume_versions", "job_applications", "interview_attempts"),
        "Settings" to listOf("settings"),
        // JSON files of later features (no database change): AI tasks, project settings, career practice; see AppFiles
        "App files" to listOf("app_files"),
    )
    /**
     * Settings that may be backed up: preferences only (real keys of the app's "abhify" preferences). Never keys, tokens,
     * account or device data, and not the two online-privacy switches (online_ai_allowed, online_ai_memories): those stay
     * a fresh choice on each phone, so a restore can never turn online sending on.
     */
    val SETTINGS_WHITELIST = setOf("ai_mode", "theme", "memories_enabled", "read_aloud", "memory_profile", "ocr_allowed",
        "personality", "system_prompt", "system_prompt_enabled", "fallback_device", "fallback_providers", "online_history", "creativity", "text_size", "doc_sort")

    data class Parsed(val createdAt: Long, val appVersion: String, val tables: Map<String, JSONArray>, val integrity: Map<String, Boolean>, val categories: List<String>) {
        val intact get() = integrity.values.all { it }
        fun count(table: String) = tables[table]?.length() ?: 0
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun encode(tables: Map<String, JSONArray>, categories: List<String>, appVersion: String, now: Long = System.currentTimeMillis()): ByteArray {
        val sums = JSONObject(); val data = JSONObject()
        tables.forEach { (t, rows) -> data.put(t, rows); sums.put(t, sha256(rows.toString().toByteArray(Charsets.UTF_8))) }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("created_at", now).put("app_version", appVersion)
            .put("categories", JSONArray(categories)).put("checksums", sums).put("tables", data).toString().toByteArray(Charsets.UTF_8)
    }

    /** Reads a backup (decrypting with [passphrase] when needed) and checks every table against its checksum. */
    fun decode(bytes: ByteArray, passphrase: String? = null): Parsed {
        var o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw BackupException("This isn't an Abhify backup file.") }
        if (o.optString("format") == ENCRYPTED) {
            if (passphrase.isNullOrEmpty()) throw BackupException("This backup is encrypted. Enter its passphrase.")
            o = JSONObject(String(decrypt(o, passphrase), Charsets.UTF_8))
        }
        if (o.optString("format") != FORMAT) throw BackupException("This isn't an Abhify backup file.")
        if (o.optInt("version") > VERSION) throw BackupException("This backup was made by a newer Abhify (format ${o.optInt("version")}). Update the app first.")
        val t = o.optJSONObject("tables") ?: JSONObject(); val sums = o.optJSONObject("checksums") ?: JSONObject()
        val tables = t.keys().asSequence().associateWith { t.getJSONArray(it) }
        val integrity = tables.mapValues { (k, rows) -> sums.optString(k) == sha256(rows.toString().toByteArray(Charsets.UTF_8)) }
        val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Parsed(o.optLong("created_at"), o.optString("app_version"), tables, integrity, cats)
    }

    private const val ITER = 210_000
    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= 8) { "Use a passphrase of at least 8 characters." }
        val rnd = SecureRandom(); val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        val b64 = Base64.getEncoder()
        return JSONObject().put("format", ENCRYPTED).put("version", VERSION).put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITER)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(c.doFinal(plain))).toString().toByteArray(Charsets.UTF_8)
    }
    private fun decrypt(o: JSONObject, passphrase: String): ByteArray = try {
        val d = Base64.getDecoder()
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(passphrase, d.decode(o.getString("salt")), o.optInt("iterations", ITER)), GCMParameterSpec(128, d.decode(o.getString("iv"))))
        }.doFinal(d.decode(o.getString("data")))
    } catch (e: javax.crypto.AEADBadTagException) { throw BackupException("Wrong passphrase, or the file was changed.") }
    private fun key(p: String, salt: ByteArray, iter: Int = ITER) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(p.toCharArray(), salt, iter, 256)).encoded, "AES")
}

class BackupException(message: String) : Exception(message)

/**
 * The app's JSON files (no database change) in a backup, as rows {name, content}. Restore is additive like the tables:
 * a file is written only when this phone doesn't have it yet. Project settings are keyed by workspace id, and restored
 * workspaces get new ids, so projects.json is stored by workspace "name|kind" and mapped back on restore (an entry whose
 * workspace isn't on this phone is left out, never attached to another project).
 */
object AppFiles {
    const val TABLE = "app_files"
    const val PROJECTS = "projects.json"
    val NAMES = listOf("tasks.json", PROJECTS, "career_mcq.json", "career_questions.json")

    fun export(dir: java.io.File, workspaceKey: (Long) -> String?): JSONArray = JSONArray().apply {
        for (n in NAMES) {
            val f = java.io.File(dir, n); if (!f.isFile) continue
            var content = f.readText()
            if (n == PROJECTS) content = rekey(content) { it.toLongOrNull()?.let(workspaceKey) }
            put(JSONObject().put("name", n).put("content", content))
        }
    }

    /** Writes the files this phone doesn't have. Returns (written, skipped) names. */
    fun restore(dir: java.io.File, rows: JSONArray, workspaceId: (String) -> Long?): Pair<List<String>, List<String>> {
        val written = mutableListOf<String>(); val skipped = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i); val n = o.optString("name")
            if (n !in NAMES) continue                                         // only known files, never a path from the backup
            val f = java.io.File(dir, n)
            if (f.exists()) { skipped += n; continue }
            var content = o.optString("content")
            if (n == PROJECTS) content = rekey(content) { workspaceId(it)?.toString() }
            dir.mkdirs(); f.writeText(content); written += n
        }
        return written to skipped
    }

    /** projects.json with every key mapped by [map]; keys it can't map are left out. */
    private fun rekey(json: String, map: (String) -> String?): String = runCatching {
        val o = JSONObject(json); val out = JSONObject()
        o.keys().forEach { k -> map(k)?.let { out.put(it, o.get(k)) } }
        out.toString()
    }.getOrDefault("{}")
}

/** The few database operations backup and restore need, so the same code runs on Android and in JVM tests. */
interface Rows {
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>
    fun insert(table: String, values: Map<String, Any?>): Long
    fun columns(table: String): Set<String>
    fun <T> transaction(block: () -> T): T
}

/**
 * Export and additive restore. Restore never updates or deletes existing rows: rows already present (same uid, or the
 * same natural key below) are skipped and their ids reused for children; everything else is inserted with new ids,
 * with parent references remapped. Restoring the same backup twice changes nothing the second time.
 */
object BackupIo {
    /** Columns that identify a row across devices/backups when there is no uid. */
    val NATURAL_KEY = mapOf(
        "workspaces" to listOf("name", "kind"), "notes" to listOf("workspace_id", "title", "created_at"), "tasks" to listOf("workspace_id", "title", "created_at"),
        "saved_prompts" to listOf("title", "created_at"),
        "study_plans" to listOf("topic", "created_at"), "plan_items" to listOf("plan_id", "position"), "flashcards" to listOf("deck", "front", "created_at"),
        "study_sessions" to listOf("title", "created_at"), "job_descriptions" to listOf("title", "created_at"), "resume_versions" to listOf("doc_id", "kind", "created_at"),
        "job_applications" to listOf("company", "title", "created_at"), "interview_attempts" to listOf("question", "created_at"),
    )
    /** child table → (column → parent table) */
    val PARENTS = mapOf(
        "messages" to mapOf("conversation_id" to "conversations"), "notes" to mapOf("workspace_id" to "workspaces"), "tasks" to mapOf("workspace_id" to "workspaces"),
        "library_docs" to mapOf("workspace_id" to "workspaces"), "plan_items" to mapOf("plan_id" to "study_plans"), "resume_versions" to mapOf("doc_id" to "library_docs"),
        "job_applications" to mapOf("jd_id" to "job_descriptions", "resume_doc_id" to "library_docs", "resume_version_id" to "resume_versions"),
    )
    /** Never exported (derived or device-specific). */
    private val SKIP_COLUMNS = setOf("dirty")

    fun export(db: Rows, categories: List<String>, settings: Map<String, Any?>): Map<String, JSONArray> {
        val out = LinkedHashMap<String, JSONArray>()
        categories.flatMap { Backup.CATEGORIES[it].orEmpty() }.forEach { t ->
            if (t == "settings") { out[t] = JSONArray().put(JSONObject().apply { settings.filterKeys { it in Backup.SETTINGS_WHITELIST }.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }); return@forEach }
            if (t == AppFiles.TABLE) return@forEach                          // files, not a table: added by the caller (AppFiles.export)
            if (db.columns(t).isEmpty()) return@forEach                     // a table this database doesn't have yet: nothing to export
            val where = when (t) { "conversations", "messages", "memories" -> " WHERE deleted = 0"; else -> "" }
            out[t] = JSONArray(db.query("SELECT * FROM $t$where").map { r -> JSONObject().apply { r.filterKeys { it !in SKIP_COLUMNS }.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } } })
        }
        return out
    }

    data class Plan(val table: String, val total: Int, val new: Int, val existing: Int)
    data class Result(val inserted: Map<String, Int>, val skipped: Map<String, Int>, val settings: Map<String, Any?>)

    private fun val0(o: JSONObject, k: String): Any? = if (!o.has(k) || o.isNull(k)) null else o.get(k)
    private fun findExisting(db: Rows, t: String, row: JSONObject, cols: Set<String>, idMap: Map<String, Map<Long, Long>>): Long? {
        if ("uid" in cols && row.has("uid") && !row.isNull("uid"))
            return db.query("SELECT id FROM $t WHERE uid = ?", listOf(row.getString("uid"))).firstOrNull()?.get("id")?.toString()?.toLong()
        val key = NATURAL_KEY[t] ?: return null
        val values = key.map { k -> val v = val0(row, k); PARENTS[t]?.get(k)?.let { p -> (v as? Number)?.toLong()?.let { idMap[p]?.get(it) } } ?: v }
        if (values.any { it == null }) return null
        return db.query("SELECT id FROM $t WHERE " + key.joinToString(" AND ") { "$it = ?" }, values).firstOrNull()?.get("id")?.toString()?.toLong()
    }

    /** What a restore would do, without changing anything. */
    fun preview(db: Rows, b: Backup.Parsed): List<Plan> {
        val idMap = HashMap<String, MutableMap<Long, Long>>()
        return b.tables.filterKeys { it != "settings" && it != AppFiles.TABLE }.map { (t, rows) ->
            val cols = db.columns(t)
            var existing = 0
            for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); val e = findExisting(db, t, r, cols, idMap)
                if (e != null) { existing++; (r.opt("id") as? Number)?.toLong()?.let { idMap.getOrPut(t) { HashMap() }[it] = e } } }
            Plan(t, rows.length(), rows.length() - existing, existing)
        }
    }

    /** Additive restore of the chosen [tables] (in dependency order), in one transaction. Settings are returned, not applied. */
    fun restore(db: Rows, b: Backup.Parsed, tables: Set<String>): Result {
        if (!b.intact) throw BackupException("The backup failed its integrity check (${b.integrity.filterValues { !it }.keys.joinToString()}); nothing was restored.")
        val order = Backup.CATEGORIES.values.flatten().filter { it in tables && it in b.tables && it != "settings" && it != AppFiles.TABLE }
        val ins = LinkedHashMap<String, Int>(); val skip = LinkedHashMap<String, Int>(); val idMap = HashMap<String, MutableMap<Long, Long>>()
        db.transaction {
            for (t in order) {
                val cols = db.columns(t); val rows = b.tables.getValue(t); var n = 0; var s = 0
                if (cols.isEmpty()) { ins[t] = 0; skip[t] = rows.length(); continue }      // this database has no such table (older app): skipped, nothing changed
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i); val oldId = (r.opt("id") as? Number)?.toLong()
                    val existing = findExisting(db, t, r, cols, idMap)
                    if (existing != null) { s++; oldId?.let { idMap.getOrPut(t) { HashMap() }[it] = existing }; continue }
                    val v = LinkedHashMap<String, Any?>()
                    r.keys().forEach { k -> if (k != "id" && k in cols) v[k] = val0(r, k) }
                    PARENTS[t]?.forEach { (col, parent) ->
                        val old = (v[col] as? Number)?.toLong() ?: return@forEach
                        v[col] = idMap[parent]?.get(old) ?: if (parent in tables) null else old.takeIf { db.query("SELECT id FROM $parent WHERE id = ?", listOf(it)).isNotEmpty() }
                    }
                    if (t == "messages" && v["conversation_id"] == null) { s++; continue }        // a message needs its conversation
                    if (t == "plan_items" && v["plan_id"] == null) { s++; continue }
                    if ((t == "notes" || t == "tasks") && v["workspace_id"] == null) { s++; continue }
                    if (t == "resume_versions" && v["doc_id"] == null) { s++; continue }
                    if ("dirty" in cols) v["dirty"] = 1                                            // restored rows sync like new ones (if sync is on)
                    val newId = db.insert(t, v); oldId?.let { idMap.getOrPut(t) { HashMap() }[it] = newId }; n++
                }
                ins[t] = n; skip[t] = s
            }
        }
        val settings = b.tables["settings"]?.optJSONObject(0)?.let { o -> o.keys().asSequence().filter { it in Backup.SETTINGS_WHITELIST }.associateWith { val0(o, it) } } ?: emptyMap()
        return Result(ins, skip, settings)
    }
}
