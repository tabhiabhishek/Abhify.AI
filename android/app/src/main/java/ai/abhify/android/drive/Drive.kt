package ai.abhify.android.drive

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** HTTP with byte bodies (Drive uploads/downloads binary files). The Android implementation uses HttpURLConnection. */
interface DriveTransport {
    data class Resp(val code: Int, val body: ByteArray, val headers: Map<String, String>) { val text get() = String(body, Charsets.UTF_8) }
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Resp
}

/** [auth]: the user must connect again. [transient]: worth retrying later (network, rate limit, server error). */
class DriveException(message: String, val auth: Boolean = false, val transient: Boolean = false) : Exception(message)

/**
 * Google Drive REST v3 with the narrow `drive.file` scope: the app sees only files it created (or the user opened
 * with it). Everything lives under one app folder, "Abhify.AI", with sub-folders created only when first needed.
 * Folder ids are cached ([folderCache]) and re-created if the user deleted them. Uploads are resumable and retried;
 * an identical file (same name and MD5) is never uploaded twice; a different file with the same name is never
 * overwritten unless the caller explicitly asks to replace it.
 */
class DriveApi(private val http: DriveTransport, private val token: () -> String, val folderCache: MutableMap<String, String> = HashMap()) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val ROOT = "Abhify.AI"
        /** v4.2 layout: My Drive/Abhify.AI/{Backups, Documents, Imports, Exports}. */
        const val BACKUPS = "Backups"; const val DOCUMENTS = "Documents"; const val IMPORTS = "Imports"; const val EXPORTS = "Exports"
        val FOLDERS = listOf(BACKUPS, DOCUMENTS, IMPORTS, EXPORTS)
        /** Sub-folders used by 3.4–4.1 → where the same kind of file goes now (old folders are never deleted; files move only on request). */
        val LEGACY = mapOf("Application Backups" to BACKUPS, "Memory Backups" to BACKUPS, "Learning Library" to DOCUMENTS,
            "Archived Conversations" to EXPORTS, "Exported Notes" to EXPORTS, "Model Training Data" to EXPORTS)
        fun canonical(sub: String?): String? = sub?.let { LEGACY[it] ?: it }
        /** Where each kind of app-managed upload belongs. */
        fun folderFor(kind: String): String = when (kind) {
            "backup", "backup-auto", "memory-backup" -> BACKUPS
            "doc" -> DOCUMENTS
            "file" -> IMPORTS
            else -> EXPORTS                                                                       // conversations, notes, other generated files
        }
        fun folderUrl(id: String) = "https://drive.google.com/drive/folders/$id"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val CHUNK = 256 * 1024 * 4                                                          // resumable chunks: multiples of 256 KB
        fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun q(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    }

    data class Item(val id: String, val name: String, val mime: String, val size: Long?, val md5: String?, val modified: String?)
    /** The Google account behind the token. [permissionId] is stable per account and is used to keep folder ids apart. */
    data class Account(val permissionId: String, val email: String?, val name: String?)

    /** Who is signed in (Drive "about"; works with the drive.file scope). Nothing is stored by this call. */
    fun account(): Account {
        val r = call("GET", "$API/about?fields=${enc("user(permissionId,emailAddress,displayName)")}")
        if (r.code != 200) throw DriveException("Couldn't read the Google account (${r.code}).", transient = r.code >= 500)
        val u = JSONObject(r.text).getJSONObject("user")
        return Account(u.getString("permissionId"), u.optString("emailAddress").ifEmpty { null }, u.optString("displayName").ifEmpty { null })
    }

    private fun call(method: String, url: String, body: ByteArray? = null, ctype: String? = null, extra: Map<String, String> = emptyMap()): DriveTransport.Resp {
        val h = HashMap<String, String>(extra); h["Authorization"] = "Bearer " + token(); ctype?.let { h["Content-Type"] = it }
        val r = try { http.send(method, url, h, body) } catch (e: java.io.IOException) { throw DriveException("No connection to Google Drive. Check the internet and retry.", transient = true) }
        when (r.code) {
            401 -> throw DriveException("Google Drive sign-in expired or was revoked. Connect again in More → Google Drive.", auth = true)
            403 -> { val limited = r.text.contains("rateLimit", true) || r.text.contains("userRateLimit", true)
                throw DriveException(if (limited) "Google Drive is limiting requests. Retry in a minute."
                    else if (r.text.contains("storageQuota", true)) "Your Google Drive is full." else "Google Drive refused the request (permission).", transient = limited) }
            429 -> throw DriveException("Google Drive is limiting requests. Retry in a minute.", transient = true)
            in 500..599 -> throw DriveException("Google Drive is temporarily unavailable (${r.code}). Retry later.", transient = true)
        }
        return r
    }
    private fun item(o: JSONObject) = Item(o.getString("id"), o.optString("name"), o.optString("mimeType"), o.optString("size").toLongOrNull(),
        o.optString("md5Checksum").ifEmpty { null }, o.optString("modifiedTime").ifEmpty { null })

    fun list(parentId: String, foldersOnly: Boolean = false): List<Item> {
        val query = "'${q(parentId)}' in parents and trashed = false" + if (foldersOnly) " and mimeType = '$FOLDER_MIME'" else ""
        val out = mutableListOf<Item>(); var page: String? = null
        do {
            val r = call("GET", "$API/files?q=${enc(query)}&fields=${enc("nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)")}&pageSize=200" +
                (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            if (r.code != 200) throw DriveException("Couldn't list Google Drive files (${r.code}).")
            val o = JSONObject(r.text); val a = o.optJSONArray("files")
            if (a != null) for (i in 0 until a.length()) out += item(a.getJSONObject(i))
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** A usable folder: exists, not trashed, and (when [parent] is given) still inside that parent. */
    private fun valid(id: String, parent: String?): Boolean {
        val r = call("GET", "$API/files/${enc(id)}?fields=${enc("id,trashed,parents")}")
        if (r.code != 200) return false
        val o = JSONObject(r.text)
        if (o.optBoolean("trashed")) return false
        if (parent == null) return true
        val ps = o.optJSONArray("parents") ?: return true                                       // parents not reported: accept
        return (0 until ps.length()).any { ps.getString(it) == parent }
    }

    /** Oldest matching folder (names aren't unique): the root only directly in My Drive, a sub-folder only inside [parent]. */
    private fun findFolder(name: String, parent: String?): String? {
        val query = "name = '${q(name)}' and mimeType = '$FOLDER_MIME' and trashed = false and '${q(parent ?: "root")}' in parents"
        val r = call("GET", "$API/files?q=${enc(query)}&orderBy=createdTime&fields=${enc("files(id,name)")}")
        if (r.code != 200) throw DriveException("Couldn't search Google Drive (${r.code}).", transient = r.code >= 500)
        return JSONObject(r.text).optJSONArray("files")?.optJSONObject(0)?.optString("id")
    }
    private fun createFolder(name: String, parent: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", org.json.JSONArray().put(parent ?: "root"))
        val r = call("POST", "$API/files?fields=id", meta.toString().toByteArray(), "application/json; charset=UTF-8")
        if (r.code !in 200..299) throw DriveException("Couldn't create the folder \"$name\" on Google Drive (${r.code}).")
        return JSONObject(r.text).getString("id")
    }

    /**
     * The id of "My Drive/Abhify.AI/[sub]" (or of the root when [sub] is null): cached and still valid (exists, not
     * trashed, still under the right parent), else found (oldest of that name), else created once — never duplicated.
     * Legacy sub-folder names map to the v4.2 layout.
     */
    fun folder(subIn: String?): String {
        val sub = canonical(subIn)
        val key = sub ?: ROOT
        val root = if (sub == null) null else folder(null)
        folderCache[key]?.let { if (valid(it, root)) return it else folderCache.remove(key) }           // deleted or moved by the user → find / re-create
        val id = findFolder(sub ?: ROOT, root) ?: createFolder(sub ?: ROOT, root)
        folderCache[key] = id
        return id
    }
    /** Root status without creating anything: the id when "Abhify.AI" exists in My Drive, else null. */
    fun findRoot(): String? = folderCache[ROOT]?.takeIf { valid(it, null) } ?: findFolder(ROOT, null)?.also { folderCache[ROOT] = it }

    /** Moves a file into [toParent], keeping its id (only files Abhify can see: the ones it created). */
    fun move(id: String, toParent: String, fromParent: String) {
        val r = call("PATCH", "$API/files/${enc(id)}?addParents=${enc(toParent)}&removeParents=${enc(fromParent)}&fields=${enc("id,parents")}",
            "{}".toByteArray(), "application/json; charset=UTF-8")
        if (r.code != 200) throw DriveException(if (r.code == 404) "That file is no longer on Google Drive." else "Couldn't move the file (${r.code}).", transient = r.code >= 500)
    }

    sealed class Upload {
        data class Done(val item: Item) : Upload()
        data class AlreadyThere(val item: Item) : Upload()
        /** A different file with this name exists: the caller decides (keep both / replace). Nothing was changed. */
        data class Conflict(val existing: Item) : Upload()
    }

    /**
     * Uploads [bytes] as [name] into "Abhify.AI/[sub]". [replaceId] = overwrite that file's content (only after the user
     * chose "Replace"); [keepBoth] = upload under a new numbered name. [progress] gets bytes sent.
     */
    fun upload(sub: String, name: String, mime: String, bytes: ByteArray, replaceId: String? = null, keepBoth: Boolean = false,
               session: String? = null, onSession: (String?) -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): Upload {
        val parent = folder(sub)
        val md5 = md5(bytes)
        val siblings = list(parent)
        var finalName = name
        if (replaceId == null) {
            siblings.firstOrNull { it.name == name && it.md5 == md5 }?.let { return Upload.AlreadyThere(it) }
            siblings.firstOrNull { it.name == name }?.let { existing ->
                if (!keepBoth) return Upload.Conflict(existing)
                val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                finalName = (2..999).map { "$base ($it)$ext" }.first { n -> siblings.none { it.name == n } }
            }
        }
        // v3.5: an upload interrupted earlier (even by the app being closed) continues from what Drive already has
        if (session != null) resume(session, bytes, mime, progress, onSession)?.let { return it }
        // resumable session: metadata first, then the content in chunks (a dropped chunk is resumed, not restarted)
        val meta = JSONObject().put("name", finalName).apply { if (replaceId == null) put("parents", org.json.JSONArray().put(parent)) }
        val start = if (replaceId == null) call("POST", "$UPLOAD?uploadType=resumable&fields=${enc("id,name,mimeType,size,md5Checksum,modifiedTime")}", meta.toString().toByteArray(),
                "application/json; charset=UTF-8", mapOf("X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to bytes.size.toString()))
            else call("PATCH", "$UPLOAD/${enc(replaceId)}?uploadType=resumable&fields=${enc("id,name,mimeType,size,md5Checksum,modifiedTime")}", meta.toString().toByteArray(),
                "application/json; charset=UTF-8", mapOf("X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to bytes.size.toString()))
        if (start.code !in 200..299) throw DriveException("Google Drive didn't accept the upload (${start.code}).")
        val uri = start.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value ?: throw DriveException("Google Drive didn't start the upload.")
        onSession(uri)
        return send(uri, bytes, 0L, mime, progress).also { onSession(null) }
    }

    /** Continues a stored session: null when Drive no longer knows it (expired after about a week) → start again. */
    private fun resume(session: String, bytes: ByteArray, mime: String, progress: (Long, Long) -> Unit, onSession: (String?) -> Unit): Upload? {
        val st = call("PUT", session, ByteArray(0), null, mapOf("Content-Range" to "bytes */${bytes.size}"))
        return when (st.code) {
            200, 201 -> { onSession(null); progress(bytes.size.toLong(), bytes.size.toLong()); Upload.Done(item(JSONObject(st.text))) }
            308 -> { val from = st.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L
                send(session, bytes, from, mime, progress).also { onSession(null) } }
            else -> { onSession(null); null }
        }
    }

    private fun send(session: String, bytes: ByteArray, from: Long, mime: String, progress: (Long, Long) -> Unit): Upload {
        var sent = from; var attempts = 0
        while (true) {
            val end = minOf(sent + CHUNK, bytes.size.toLong())
            val r = try {
                call("PUT", session, bytes.copyOfRange(sent.toInt(), end.toInt()), mime, mapOf("Content-Range" to (if (bytes.isEmpty()) "bytes */0" else "bytes $sent-${end - 1}/${bytes.size}")))
            } catch (e: DriveException) {
                if (e.auth || ++attempts > 4) throw e
                // ask the server how much it has, then continue from there
                val st = runCatching { call("PUT", session, ByteArray(0), null, mapOf("Content-Range" to "bytes */${bytes.size}")) }.getOrNull()
                if (st != null && st.code in 200..201) return Upload.Done(item(JSONObject(st.text)))
                sent = st?.headers?.entries?.firstOrNull { it.key.equals("Range", true) }?.value?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: sent
                continue
            }
            when (r.code) {
                200, 201 -> { progress(bytes.size.toLong(), bytes.size.toLong()); return Upload.Done(item(JSONObject(r.text))) }
                308 -> { sent = r.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: end
                    attempts = 0; progress(sent, bytes.size.toLong()) }
                else -> throw DriveException("The upload failed (${r.code}).", transient = r.code == 404 || r.code == 410)   // expired session: start again
            }
        }
    }

    fun download(id: String): ByteArray {
        val r = call("GET", "$API/files/${enc(id)}?alt=media")
        if (r.code == 404) throw DriveException("That file is no longer on Google Drive.")
        if (r.code != 200) throw DriveException("Download failed (${r.code}).")
        return r.body
    }

    /** Deletes one app file on Drive (moves it to Drive's trash). Local copies are never touched. */
    fun trash(id: String) {
        val r = call("PATCH", "$API/files/${enc(id)}", JSONObject().put("trashed", true).toString().toByteArray(), "application/json; charset=UTF-8")
        if (r.code != 200 && r.code != 404) throw DriveException("Couldn't delete the file on Google Drive (${r.code}).")
    }
}

/** Sync states shown per file. Nothing is deleted or overwritten automatically, except older *scheduled* backups beyond the user's retention count. */
object DriveStatus {
    const val LOCAL_ONLY = "local"; const val DRIVE_ONLY = "drive"; const val SYNCED = "synced"
    const val PENDING_UPLOAD = "pending-upload"; const val PENDING_DOWNLOAD = "pending-download"; const val CONFLICT = "conflict"; const val FAILED = "failed"
    const val CANCELLED = "cancelled"; const val REMOVED = "removed"
    fun label(s: String) = when (s) { LOCAL_ONLY -> "On this phone only"; DRIVE_ONLY -> "On Drive only"; SYNCED -> "On phone and Drive"
        PENDING_UPLOAD -> "Waiting to upload"; PENDING_DOWNLOAD -> "Waiting to download"; CONFLICT -> "Different version on Drive"
        CANCELLED -> "Cancelled (tap Retry to upload)"; REMOVED -> "Removed by backup retention"; else -> "Failed" }
}
