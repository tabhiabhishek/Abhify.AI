package ai.abhify.android.sync

import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.CloudClient
import org.json.JSONArray
import org.json.JSONObject

/** One synced record (conversation, message, memory or preference), as the server sees it. */
data class SyncRecord(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JSONObject, val deviceId: String = "") {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("updated_at", updatedAt).put("deleted", deleted).put("data", data)
    companion object {
        fun fromJson(o: JSONObject) = SyncRecord(o.getString("kind"), o.getString("id"), o.getLong("updated_at"), o.optBoolean("deleted"),
            o.optJSONObject("data") ?: JSONObject(), o.optString("device_id"))
    }
}

/** Local side of sync (implemented by ChatDb; a fake in tests). */
interface SyncStore {
    fun deviceId(): String
    fun cursor(): Long
    fun setCursor(seq: Long)
    /** Records changed on this phone and not yet accepted by the server. */
    fun dirty(limit: Int): List<SyncRecord>
    /** Clears the dirty flag only if the record wasn't changed again since [updatedAt] (no lost edits). */
    fun markClean(kind: String, id: String, updatedAt: Long)
    /** The local version's (updatedAt, dirty), or null if this phone doesn't have it. */
    fun localMeta(kind: String, id: String): Pair<Long, Boolean>?
    /** Writes a remote version locally, not dirty. */
    fun applyRemote(r: SyncRecord)
    /** Keeps both versions when the server's copy differs from an unsent local edit (never silently discarded). */
    fun captureConflict(remote: SyncRecord)
    /** Whether the user syncs this kind of record. */
    fun accepts(kind: String): Boolean
}

/** Pure merge rule, identical to the server's: newest (updatedAt, deviceId) wins. */
object SyncMerge {
    fun remoteWins(local: Pair<Long, Boolean>?, remote: SyncRecord, myDevice: String): Boolean {
        if (local == null) return true
        val (localAt, dirty) = local
        if (!dirty) return remote.updatedAt >= localAt                  // nothing unsent here: take the server's state
        // an unsent local edit survives unless the remote one is strictly newer (ties: higher device id, like the server)
        return remote.updatedAt > localAt || (remote.updatedAt == localAt && remote.deviceId > myDevice)
    }
}

data class SyncReport(val pushed: Int, val pulled: Int, val conflictsResolved: Int, val rejected: List<String>)

/** Push local changes, then pull remote ones. Safe to run repeatedly; every step is idempotent. */
class SyncEngine(private val store: SyncStore, private val api: SyncApi) {
    fun run(): SyncReport {
        var pushed = 0; var conflicts = 0; val rejected = mutableListOf<String>()
        // 1) push, in batches
        while (true) {
            val batch = store.dirty(200)
            if (batch.isEmpty()) break
            val r = api.push(store.deviceId(), batch)
            val byKey = batch.associateBy { it.kind to it.id }
            r.accepted.forEach { (k, id) -> byKey[k to id]?.let { store.markClean(k, id, it.updatedAt); pushed++ } }
            r.unchanged.forEach { id -> batch.filter { it.id == id }.forEach { store.markClean(it.kind, it.id, it.updatedAt) } }
            r.conflicts.forEach { remote ->                                     // the server had a newer version
                val local = store.localMeta(remote.kind, remote.id)
                if (local?.second == true) store.captureConflict(remote)
                if (SyncMerge.remoteWins(local, remote, store.deviceId())) store.applyRemote(remote)
                else byKey[remote.kind to remote.id]?.let { store.markClean(it.kind, it.id, it.updatedAt) }
                conflicts++
            }
            r.rejected.forEach { (k, id, why) -> rejected += "$k $id: $why"; byKey[k to id]?.let { store.markClean(k, id, it.updatedAt) } }
            if (r.accepted.isEmpty() && r.unchanged.isEmpty() && r.conflicts.isEmpty() && r.rejected.isEmpty()) break   // nothing moved: avoid a loop
        }
        // 2) pull, in pages
        var pulled = 0
        var since = store.cursor()
        while (true) {
            val page = api.pull(since)
            page.changes.forEach { remote ->
                if (remote.deviceId == store.deviceId() || !store.accepts(remote.kind)) return@forEach
                val local = store.localMeta(remote.kind, remote.id)
                if (local?.second == true) { store.captureConflict(remote); conflicts++ }     // edited here AND elsewhere
                if (SyncMerge.remoteWins(local, remote, store.deviceId())) { store.applyRemote(remote); pulled++ }
            }
            since = page.next
            store.setCursor(since)
            if (!page.more) break
        }
        return SyncReport(pushed, pulled, conflicts, rejected)
    }
}

data class PushResult(val accepted: List<Pair<String, String>>, val unchanged: List<String>, val conflicts: List<SyncRecord>, val rejected: List<Triple<String, String, String>>)
data class PullPage(val changes: List<SyncRecord>, val next: Long, val more: Boolean)

interface SyncApi {
    fun push(deviceId: String, changes: List<SyncRecord>): PushResult
    fun pull(since: Long): PullPage
}

/** The real API over CloudClient (authenticated, token refresh included). */
class CloudSyncApi(private val cloud: CloudClient) : SyncApi {
    override fun push(deviceId: String, changes: List<SyncRecord>): PushResult {
        val o = cloud.authed("POST", "/api/v1/sync/changes", JSONObject().put("device_id", deviceId).put("changes", JSONArray(changes.map { it.toJson() })))
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        return PushResult(
            (0 until arr("accepted").length()).map { arr("accepted").getJSONObject(it).let { a -> a.getString("kind") to a.getString("id") } },
            (0 until arr("unchanged").length()).map { arr("unchanged").getString(it) },
            (0 until arr("conflicts").length()).map { SyncRecord.fromJson(arr("conflicts").getJSONObject(it)) },
            (0 until arr("rejected").length()).map { arr("rejected").getJSONObject(it).let { r -> Triple(r.getString("kind"), r.getString("id"), r.getString("reason")) } })
    }

    override fun pull(since: Long): PullPage {
        val o = cloud.authed("GET", "/api/v1/sync/changes?since=$since&limit=500")
        val a = o.getJSONArray("changes")
        return PullPage((0 until a.length()).map { SyncRecord.fromJson(a.getJSONObject(it)) }, o.getLong("next"), o.getBoolean("more"))
    }
}

/** Sync status shown in the app. */
sealed class SyncStatus {
    data object Off : SyncStatus()
    data object Idle : SyncStatus()
    data object Running : SyncStatus()
    data class Pending(val count: Int) : SyncStatus()
    data class Done(val at: Long, val report: SyncReport) : SyncStatus()
    data class Failed(val at: Long, val message: String, val authExpired: Boolean) : SyncStatus()
    companion object {
        fun failure(e: Exception, now: Long) = Failed(now, e.message ?: "Sync failed", e is ApiException && e.code == 401)
    }
}
