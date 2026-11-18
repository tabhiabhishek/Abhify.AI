package ai.abhify.android

import ai.abhify.android.backup.Backup
import ai.abhify.android.backup.BackupException
import ai.abhify.android.backup.BackupIo
import ai.abhify.android.backup.Rows
import ai.abhify.android.chat.MemoryItem
import ai.abhify.android.chat.SchemaV5
import ai.abhify.android.chat.SchemaV6
import ai.abhify.android.chat.SchemaV7
import ai.abhify.android.chat.SchemaV8
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.chat.SearchIndex
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveException
import ai.abhify.android.drive.DriveTransport
import ai.abhify.android.memory.MemoryKit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager

private fun mem(id: Long, content: String, cat: String = "General", imp: Int = 2, confirmed: Boolean = true, expires: Long? = null, updated: Long = id) =
    MemoryItem(id, "u$id", content, cat, imp, confirmed, null, expires, 0, updated)

class PersonalMemoryTest {
    private val items = listOf(
        mem(1, "Prefers PyTorch over TensorFlow for examples", "Preferences", 3),
        mem(2, "Is building an Android app called Abhify with Kotlin", "Active projects"),
        mem(3, "Wants to become an ML engineer by 2027", "Career goals"),
        mem(4, "Lives in Pune", "Personal facts"),
        mem(5, "Old goal: learn Java", "Learning goals", expires = 1_000),
        mem(6, "Unconfirmed: likes jazz", confirmed = false))

    @Test fun onlyRelevantMemoriesAreRetrieved() {
        val r = MemoryKit.relevant("Show me a PyTorch example for image classification", items, now = 10_000).map { it.item.id }
        assertEquals(listOf(1L), r)                                                                  // not the home town, not the career goal
        assertTrue(MemoryKit.relevant("Help me prepare for a job interview", items, now = 10_000).map { it.item.id }.contains(3L))   // category hint
        assertTrue(MemoryKit.relevant("What is the capital of France?", items, now = 10_000).isEmpty())   // unrelated → nothing (no dump)
        assertTrue(MemoryKit.relevant("learn java jazz", items, now = 10_000).none { it.item.id == 5L || it.item.id == 6L })   // expired and unconfirmed never
        val many = (1..20L).map { mem(it, "Kotlin fact number $it about coroutines") }
        val capped = MemoryKit.relevant("kotlin coroutines", many, limit = 6, maxChars = 120, now = 0)
        assertTrue(capped.size <= 6 && capped.sumOf { it.item.content.length } <= 120)
    }

    @Test fun duplicatesAndConsolidation() {
        assertTrue(MemoryKit.similar("Prefers PyTorch over TensorFlow", "prefers pytorch over tensorflow."))
        assertTrue(MemoryKit.similar("Is building an Android app called Abhify", "Is building an Android app called Abhify with Kotlin"))
        assertFalse(MemoryKit.similar("Prefers PyTorch", "Lives in Pune"))
        val g = MemoryKit.duplicateGroups(listOf(mem(1, "Likes short answers", updated = 1), mem(2, "Likes short answers.", updated = 5), mem(3, "Lives in Pune")))
        assertEquals(1, g.size); assertEquals(2L, g[0].first().id)                                   // newest first (kept on merge)
    }

    @Test fun sensitiveDataIsFlagged() {
        assertTrue(MemoryKit.sensitive("my email is a.b@example.com").contains("email address"))
        assertTrue(MemoryKit.sensitive("call me on +91 98765 43210").contains("phone number"))
        assertTrue(MemoryKit.sensitive("card 4111 1111 1111 1111").contains("card number"))
        assertTrue(MemoryKit.sensitive("aadhaar 1234 5678 9012").contains("ID number (12 digits)"))
        assertTrue(MemoryKit.sensitive("PAN ABCDE1234F").contains("PAN number"))
        assertTrue(MemoryKit.sensitive("my wifi password is hunter2").contains("password or PIN"))
        assertTrue(MemoryKit.sensitive("Prefers dark mode and short answers").isEmpty())
    }

    @Test fun extractionIsValidatedAndFlagged() {
        val ok = "```json\n{\"memories\": [{\"content\": \"Prefers PyTorch over TensorFlow.\", \"category\": \"preferences\"}, " +
            "{\"content\": \"Studies NLP every evening\", \"category\": \"Learning progress\"}, {\"content\": \"x\", \"category\": \"General\"}, " +
            "{\"content\": \"Bank account number 1234567890\", \"category\": \"Weird\"}]}\n```"
        val c = MemoryKit.parseCandidates(ok, items)
        assertEquals(3, c.size)                                                                       // too-short item dropped
        assertEquals("Preferences", c[0].category); assertTrue(c[0].duplicateOf != null)              // existing memory
        assertEquals("General", c[2].category); assertTrue(c[2].sensitive.isNotEmpty())               // unknown category normalised; flagged
        assertTrue(MemoryKit.parseCandidates("Sure, here are some facts!", items).isEmpty())          // not JSON → nothing
    }
}

// ---------------------------------------------------------------------------------------------- SQLite (JDBC) helpers
internal fun v8Db(): Connection {
    val c = DriverManager.getConnection("jdbc:sqlite::memory:")
    c.createStatement().use { st ->
        st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, uid TEXT, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, profile_id TEXT, pinned INTEGER NOT NULL DEFAULT 0)")
        st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, stats TEXT, uid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, model TEXT, profile_id TEXT)")
        st.execute("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created_at INTEGER NOT NULL, uid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, profile_id TEXT)")
        listOf(SchemaV5.STATEMENTS, SchemaV6.STATEMENTS, SchemaV7.STATEMENTS, SchemaV8.STATEMENTS).flatten().forEach { st.execute(it) }
    }
    return c
}
internal fun Connection.exec(sql: String, vararg a: Any?) = prepareStatement(sql).use { p -> a.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeUpdate() }
internal fun Connection.one(sql: String, vararg a: Any?): Any? = prepareStatement(sql).use { p -> a.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeQuery().use { r -> if (r.next()) r.getObject(1) else null } }

/** The same [Rows] contract the app implements over Android SQLite, here over JDBC. */
class JdbcRows(private val c: Connection) : Rows {
    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { p ->
        args.forEachIndexed { i, v -> p.setObject(i + 1, v) }
        p.executeQuery().use { r -> val md = r.metaData; generateSequence { if (r.next()) (1..md.columnCount).associate { md.getColumnName(it) to r.getObject(it).let { v -> if (v is Int) v.toLong() else v } } else null }.toList() }
    }
    override fun insert(table: String, values: Map<String, Any?>): Long {
        c.prepareStatement("INSERT INTO $table (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "?" }})").use { p ->
            values.values.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeUpdate() }
        return (c.one("SELECT last_insert_rowid()") as Number).toLong()
    }
    override fun columns(table: String) = query("PRAGMA table_info($table)").map { it["name"].toString() }.toSet()
    override fun <T> transaction(block: () -> T): T { c.autoCommit = false; try { val r = block(); c.commit(); return r } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true } }
}

class V34DatabaseTest {
    @Test fun schemaV9KeepsEverythingAndAddsDefaults() {
        v8Db().use { c ->
            c.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Old', 1, 2, 'c1')")
            c.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (1, 'user', 'gradient descent question', 1, 'm1')")
            c.exec("INSERT INTO memories (content, created_at, uid) VALUES ('Prefers PyTorch', 1, 'mem1')")
            c.exec("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at) VALUES ('d1', 'a.pdf', 'PDF', 1, 5, 'Hello', 1, 1)")
            c.createStatement().use { st -> SchemaV9.apply { st.execute(it) } }
            assertEquals(1, c.one("SELECT confirmed FROM memories"))
            assertEquals(2, c.one("SELECT importance FROM memories")); assertNull(c.one("SELECT category FROM memories"))
            assertEquals(0, c.one("SELECT archived FROM conversations")); assertEquals("Old", c.one("SELECT title FROM conversations"))
            assertNull(c.one("SELECT sha256 FROM library_docs")); assertNull(c.one("SELECT indexed_at FROM library_docs"))
            for (t in listOf("doc_chunks", "drive_files", "backups")) assertEquals(0, c.one("SELECT COUNT(*) FROM $t"))
        }
        assertTrue(SchemaV9.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME|ON CONFLICT|VIRTUAL)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })   // no FTS in the migration
        val db = File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText()
        assertTrue(Regex("const val VERSION = (\\d+)").find(db)!!.groupValues[1].toInt() >= 9 && db.contains("if (oldVersion < 9) SchemaV9.apply") && db.contains("ftsReady = runCatching"))
    }

    @Test fun fullTextIndexIsIdempotentAndFollowsMessages() {
        v8Db().use { c ->
            c.createStatement().use { st -> SchemaV9.apply { st.execute(it) } }
            c.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Chat', 1, 2, 'c1')")
            c.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (1, 'user', 'Explain backpropagation please', 1, 'm1')")
            repeat(2) { c.createStatement().use { st -> SearchIndex.STATEMENTS.forEach(st::execute); st.execute(SearchIndex.BACKFILL) } }     // twice: no error, no duplicates
            assertEquals(1, c.one("SELECT COUNT(*) FROM messages_fts"))
            assertEquals(1, c.one("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?", SearchIndex.query("backprop")))   // prefix
            c.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (1, 'assistant', 'Backpropagation uses the chain rule', 2, 'm2')")
            assertEquals(2, c.one("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?", "backpropagation"))
            c.exec("UPDATE messages SET content = '' WHERE uid = 'm2'")                               // soft delete clears content
            assertEquals(1, c.one("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?", "backpropagation"))
            assertEquals("chain* rule*", SearchIndex.query("chain-rule!"))
            assertNull(SearchIndex.query("  ?! "))
            assertEquals("python OR sql", SearchIndex.anyOf(listOf("python", "sql", "python", "c++")))
        }
    }
}

class BackupTest {
    private fun tables() = mapOf("memories" to JSONArray().put(JSONObject().put("uid", "m1").put("content", "Prefers PyTorch").put("created_at", 1)))

    @Test fun formatIntegrityEncryptionAndVersions() {
        val bytes = Backup.encode(tables(), listOf("Personal memory"), "3.4.0", now = 5)
        val p = Backup.decode(bytes)
        assertTrue(p.intact); assertEquals(1, p.count("memories")); assertEquals("3.4.0", p.appVersion)
        val tampered = String(bytes).replace("Prefers PyTorch", "Prefers Keras").toByteArray()
        assertFalse(Backup.decode(tampered).intact)                                                  // integrity check catches edits
        val enc = Backup.encrypt(bytes, "correct horse")
        assertFalse(String(enc).contains("PyTorch"))
        assertTrue(Backup.decode(enc, "correct horse").intact)
        assertTrue(runCatching { Backup.decode(enc, "wrong pass!") }.exceptionOrNull() is BackupException)
        assertTrue(runCatching { Backup.decode(enc) }.exceptionOrNull()!!.message!!.contains("passphrase"))
        assertTrue(runCatching { Backup.encrypt(bytes, "short") }.isFailure)
        val newer = String(bytes).replace("\"version\":1", "\"version\":99").toByteArray()
        assertTrue(runCatching { Backup.decode(newer) }.exceptionOrNull()!!.message!!.contains("newer"))
        assertTrue(runCatching { Backup.decode("garbage".toByteArray()) }.exceptionOrNull() is BackupException)
        assertFalse(Backup.SETTINGS_WHITELIST.any { it.contains("key") || it.contains("token") || it == "online_ai_allowed" || it == "online_ai_memories" })
    }

    @Test fun exportRestoreRoundTripIsAdditiveAndIdempotent() {
        val src = v8Db(); src.createStatement().use { st -> SchemaV9.apply { st.execute(it) } }
        src.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('ML chat', 1, 2, 'c1')")
        src.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (1, 'user', 'What is SGD?', 1, 'm1')")
        src.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (1, 'assistant', 'Stochastic gradient descent…', 2, 'm2')")
        src.exec("INSERT INTO memories (content, created_at, uid, category, importance) VALUES ('Prefers PyTorch', 1, 'mem1', 'Preferences', 3)")
        src.exec("INSERT INTO workspaces (name, kind, created_at) VALUES ('Work', 'work', 1)")
        src.exec("INSERT INTO notes (workspace_id, title, body, created_at, updated_at) VALUES (1, 'Standup', 'Ship v3.4', 3, 3)")
        src.exec("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at, role) VALUES ('d1', 'cv.pdf', 'PDF', 1, 5, 'Hello', 1, 1, 'resume')")
        src.exec("INSERT INTO resume_versions (doc_id, kind, title, body, created_at) VALUES (1, 'corrected', 'Corrected', 'Hello!', 4)")
        src.exec("INSERT INTO job_descriptions (title, body, created_at, updated_at) VALUES ('ML Eng', 'Python', 5, 5)")
        src.exec("INSERT INTO job_applications (company, title, jd_id, resume_doc_id, resume_version_id, status, created_at, updated_at) VALUES ('Contoso', 'ML Eng', 1, 1, 1, 'APPLIED', 6, 6)")
        val cats = Backup.CATEGORIES.keys.toList()
        val bytes = Backup.encode(BackupIo.export(JdbcRows(src), cats, mapOf("theme" to "light", "provider_key" to "SECRET")), cats, "3.4.0")
        assertFalse(String(bytes).contains("SECRET"))                                                // only whitelisted settings
        val parsed = Backup.decode(bytes)

        // target phone already has one of the conversations and some unrelated data, with different ids
        val dst = v8Db(); dst.createStatement().use { st -> SchemaV9.apply { st.execute(it) } }
        dst.exec("INSERT INTO workspaces (name, kind, created_at) VALUES ('Personal', 'personal', 1)")
        dst.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Mine', 1, 1, 'other')")
        dst.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('ML chat', 1, 2, 'c1')")      // same uid as the backup
        dst.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (2, 'user', 'What is SGD?', 1, 'm1')")
        val preview = BackupIo.preview(JdbcRows(dst), parsed).associateBy { it.table }
        assertEquals(1, preview["conversations"]!!.existing); assertEquals(1, preview["messages"]!!.existing); assertEquals(1, preview["messages"]!!.new)
        val r = BackupIo.restore(JdbcRows(dst), parsed, Backup.CATEGORIES.values.flatten().toSet())
        assertEquals(1, r.inserted["messages"]); assertEquals(0, r.inserted["conversations"])
        assertEquals(2, dst.one("SELECT conversation_id FROM messages WHERE uid = 'm2'"))            // remapped onto the existing conversation
        assertEquals("Mine", dst.one("SELECT title FROM conversations WHERE uid = 'other'"))        // existing data untouched
        val wsId = dst.one("SELECT id FROM workspaces WHERE name = 'Work'"); assertEquals(wsId, dst.one("SELECT workspace_id FROM notes WHERE title = 'Standup'"))
        val docId = dst.one("SELECT id FROM library_docs WHERE uid = 'd1'")
        assertEquals(docId, dst.one("SELECT doc_id FROM resume_versions")); assertEquals(docId, dst.one("SELECT resume_doc_id FROM job_applications"))
        assertEquals(dst.one("SELECT id FROM resume_versions"), dst.one("SELECT resume_version_id FROM job_applications"))
        assertEquals(dst.one("SELECT id FROM job_descriptions"), dst.one("SELECT jd_id FROM job_applications"))
        assertEquals("Preferences", dst.one("SELECT category FROM memories WHERE uid = 'mem1'"))
        assertEquals(mapOf("theme" to "light"), r.settings)
        // restoring the same backup again adds nothing
        val again = BackupIo.restore(JdbcRows(dst), parsed, Backup.CATEGORIES.values.flatten().toSet())
        assertTrue(again.inserted.values.all { it == 0 })
        // a damaged backup is refused before any change
        val bad = Backup.decode(String(bytes).replace("Ship v3.4", "Ship v9").toByteArray())
        val before = dst.one("SELECT COUNT(*) FROM notes")
        assertTrue(runCatching { BackupIo.restore(JdbcRows(dst), bad, setOf("notes")) }.exceptionOrNull() is BackupException)
        assertEquals(before, dst.one("SELECT COUNT(*) FROM notes"))
        src.close(); dst.close()
    }
}

/** A scripted Google Drive (REST v3) for the client: files in memory, optional failures. */
class FakeDrive : DriveTransport {
    data class F(val id: String, var name: String, val mime: String, val parent: String?, var body: ByteArray, var trashed: Boolean = false)
    val files = LinkedHashMap<String, F>(); var nextId = 1; val calls = mutableListOf<String>()
    var failNextPut = false; var status401 = false
    var user = Triple("perm-1", "tester@example.com", "Tester")                                  // v4.2: Drive "about"
    private val sessions = HashMap<String, Triple<String?, JSONObject, java.io.ByteArrayOutputStream>>()
    private fun json(o: Any) = DriveTransport.Resp(200, o.toString().toByteArray(), emptyMap())
    private fun meta(f: F) = JSONObject().put("id", f.id).put("name", f.name).put("mimeType", f.mime).put("size", f.body.size.toString())
        .put("md5Checksum", DriveApi.md5(f.body)).put("modifiedTime", "2026-11-05T10:00:00Z")
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): DriveTransport.Resp {
        calls += "$method ${url.substringBefore('?')}"
        if (status401) return DriveTransport.Resp(401, "{}".toByteArray(), emptyMap())
        val q = java.net.URLDecoder.decode(url.substringAfter("q=", "").substringBefore('&'), "UTF-8")
        return when {
            url.contains("/upload/drive/v3/files") && method in setOf("POST", "PATCH") -> {
                val sid = "s${sessions.size + 1}"; sessions[sid] = Triple(url.substringAfter("/files/", "").substringBefore('?').ifEmpty { null }, JSONObject(String(body!!)), java.io.ByteArrayOutputStream())
                DriveTransport.Resp(200, ByteArray(0), mapOf("Location" to "https://upload.example/$sid"))
            }
            url.startsWith("https://upload.example/") -> {
                val sid = url.substringAfterLast('/'); val (replaceId, m, buf) = sessions.getValue(sid)
                val range = headers.getValue("Content-Range")
                if (range.startsWith("bytes */")) {                                                    // status query after an interruption
                    val total = range.substringAfter("*/").toInt()
                    if (buf.size() < total) return DriveTransport.Resp(308, ByteArray(0), if (buf.size() > 0) mapOf("Range" to "bytes=0-${buf.size() - 1}") else emptyMap())
                }
                if (failNextPut) { failNextPut = false; buf.write(body!!, 0, body.size / 2); throw IOException("connection reset") }   // half arrived
                if (!range.startsWith("bytes */")) { val start = range.substringAfter("bytes ").substringBefore('-').toInt(); val b = buf.toByteArray(); buf.reset(); buf.write(b, 0, start); buf.write(body!!) }
                val total = range.substringAfter('/').toInt()
                if (buf.size() < total) DriveTransport.Resp(308, ByteArray(0), mapOf("Range" to "bytes=0-${buf.size() - 1}"))
                else {
                    val f = if (replaceId != null) files.getValue(replaceId).also { it.body = buf.toByteArray() }
                        else F("f${nextId++}", m.getString("name"), headers["Content-Type"] ?: "", m.optJSONArray("parents")?.getString(0), buf.toByteArray()).also { files[it.id] = it }
                    json(meta(f))
                }
            }
            method == "POST" && url.contains("/drive/v3/files") -> { val m = JSONObject(String(body!!))
                val f = F("f${nextId++}", m.getString("name"), m.getString("mimeType"), m.optJSONArray("parents")?.getString(0), ByteArray(0)); files[f.id] = f; json(JSONObject().put("id", f.id)) }
            method == "PATCH" && url.contains("addParents=") -> { val f = files[url.substringAfter("/files/").substringBefore('?')] ?: return DriveTransport.Resp(404, ByteArray(0), emptyMap())
                val to = java.net.URLDecoder.decode(url.substringAfter("addParents=").substringBefore('&'), "UTF-8"); files[f.id] = f.copy(parent = to).also { it.trashed = f.trashed }
                json(JSONObject().put("id", f.id)) }
            method == "PATCH" -> { files[url.substringAfter("/files/").substringBefore('?')]?.trashed = true; json(JSONObject()) }
            method == "GET" && url.contains("/drive/v3/about") -> json(JSONObject().put("user", JSONObject().put("permissionId", user.first).put("emailAddress", user.second).put("displayName", user.third)))
            method == "GET" && url.contains("alt=media") -> files[url.substringAfter("/files/").substringBefore('?')]?.let { DriveTransport.Resp(200, it.body, emptyMap()) } ?: DriveTransport.Resp(404, ByteArray(0), emptyMap())
            method == "GET" && url.contains("/files/") -> files[url.substringAfter("/files/").substringBefore('?')]?.let { json(JSONObject().put("id", it.id).put("trashed", it.trashed).put("parents", JSONArray(listOfNotNull(it.parent)))) } ?: DriveTransport.Resp(404, ByteArray(0), emptyMap())
            method == "GET" -> {
                val name = Regex("name = '([^']*)'").find(q)?.groupValues?.get(1); val parent = Regex("'([^']*)' in parents").find(q)?.groupValues?.get(1)
                val folders = q.contains("google-apps.folder")
                json(JSONObject().put("files", JSONArray(files.values.filter { !it.trashed && (name == null || it.name == name) && (parent == null || it.parent == parent) &&
                    (!folders || it.mime.endsWith("folder")) }.map { meta(it) })))
            }
            else -> DriveTransport.Resp(400, ByteArray(0), emptyMap())
        }
    }
}

class DriveClientTest {
    private fun api(f: FakeDrive, cache: MutableMap<String, String> = HashMap()) = DriveApi(f, { "test-token" }, cache)

    @Test fun foldersAreLazyReusedAndRecreated() {
        val f = FakeDrive(); val cache = HashMap<String, String>(); val a = api(f, cache)
        val id = a.folder("Learning Library")
        assertEquals(2, f.files.values.count { it.mime.endsWith("folder") })                         // Abhify.AI + Learning Library only
        assertEquals(id, api(f, HashMap()).folder("Learning Library"))                               // found again, not duplicated
        assertEquals(2, f.files.values.count { it.mime.endsWith("folder") })
        f.files.getValue(id).trashed = true                                                           // the user deleted it on Drive
        val id2 = a.folder("Learning Library"); assertTrue(id2 != id); assertEquals(id2, cache["Documents"])   // v4.2: "Learning Library" maps to Documents
    }

    @Test fun uploadDedupConflictAndResume() {
        val f = FakeDrive(); val a = api(f)
        val bytes = ByteArray(DriveApi.CHUNK * 2 + 100) { (it % 251).toByte() }
        var last = 0L
        val up = a.upload("Documents", "notes.pdf", "application/pdf", bytes) { s, _ -> last = s }
        assertTrue(up is DriveApi.Upload.Done); assertEquals(bytes.size.toLong(), last)
        assertTrue(f.files.values.any { it.name == "notes.pdf" && it.body.contentEquals(bytes) })
        assertTrue(a.upload("Documents", "notes.pdf", "application/pdf", bytes) is DriveApi.Upload.AlreadyThere)     // unchanged: not uploaded twice
        val changed = bytes.copyOf().also { it[0] = 9 }
        val c = a.upload("Documents", "notes.pdf", "application/pdf", changed)
        assertTrue(c is DriveApi.Upload.Conflict); assertEquals(1, f.files.values.count { it.name == "notes.pdf" })  // nothing overwritten
        val copy = a.upload("Documents", "notes.pdf", "application/pdf", changed, keepBoth = true) as DriveApi.Upload.Done
        assertEquals("notes (2).pdf", copy.item.name)
        val existing = (c as DriveApi.Upload.Conflict).existing
        a.upload("Documents", "notes.pdf", "application/pdf", changed, replaceId = existing.id)
        assertTrue(f.files.getValue(existing.id).body.contentEquals(changed))                         // replaced only when asked
        f.failNextPut = true                                                                          // the connection drops mid-chunk
        val resumed = a.upload("Documents", "big.bin", "application/octet-stream", bytes)
        assertTrue(resumed is DriveApi.Upload.Done); assertTrue(f.files.values.first { it.name == "big.bin" }.body.contentEquals(bytes))
        assertTrue(a.download(existing.id).contentEquals(changed))
        a.trash(existing.id); assertTrue(f.files.getValue(existing.id).trashed)
    }

    @Test fun errorsAreClear() {
        val f = FakeDrive().apply { status401 = true }
        val e = runCatching { api(f).folder(null) }.exceptionOrNull() as DriveException
        assertTrue(e.auth && e.message!!.contains("expired or was revoked"))
        val offline = DriveApi(object : DriveTransport { override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?) = throw IOException("no route") }, { "t" })
        assertTrue(runCatching { offline.folder(null) }.exceptionOrNull()!!.message!!.contains("No connection"))
        val limited = DriveApi(object : DriveTransport { override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            DriveTransport.Resp(403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}".toByteArray(), emptyMap()) }, { "t" })
        assertTrue(runCatching { limited.folder(null) }.exceptionOrNull()!!.message!!.contains("limiting"))
        assertEquals("https://www.googleapis.com/auth/drive.file", DriveApi.SCOPE)                    // narrow scope only
    }
}

class V34SafetyTest {
    private fun src(f: String) = File("src/main/java/ai/abhify/android/$f").readText()

    @Test fun noSilentCloudFallbackAndLocalDefault() {
        val vm = src("ui/AppViewModel.kt")
        val alt = vm.substringAfter("fun askOnlineInstead()").substringBefore("\n    }\n")
        assertTrue(alt.contains("consent.value = Consent(") && alt.contains("if (d != Decision.ONLINE) return@Consent"))   // only after explicit consent
        assertTrue(vm.contains("ai.abhify.android.memory.MemoryKit.relevant(question"))                             // no "all memories" in prompts
        assertFalse(vm.contains("g.db.memories().map { it.content }"))
        val graph = src("AppGraph.kt")
        assertTrue(graph.contains("prefs.getString(\"ai_mode\", \"DEVICE\")"))                                     // new installs: the phone's model
        assertTrue(graph.contains("if (!prefs.contains(\"ai_mode\") && prefs.getBoolean(\"onboarded\", false)) prefs.edit().putString(\"ai_mode\", \"AUTO\")"))   // existing installs keep Auto
    }

    @Test fun driveAndBackupNeverCarrySecrets() {
        for (f in listOf("drive/Drive.kt", "drive/DriveAndroid.kt", "backup/Backup.kt", "backup/BackupAndroid.kt", "ui/V34State.kt", "memory/MemoryKit.kt"))
            src(f).let { t -> assertFalse(f, t.contains("providerStore") || t.contains("client_secret") || t.contains("Log.")) }
        val backupTables = Backup.CATEGORIES.values.flatten().toSet()
        assertFalse(backupTables.any { it in setOf("sync_metadata", "linked_accounts", "devices", "user_preferences", "drive_files") })
        assertFalse(src("ui/V34State.kt").contains("putString(\"drive_token\"") )                      // tokens are memory-only
    }
}
