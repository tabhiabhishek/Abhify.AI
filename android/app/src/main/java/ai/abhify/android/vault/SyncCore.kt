package ai.abhify.android.vault

import java.util.UUID

/**
 * Manual, end-to-end encrypted sync engine. Kotlin port of backend/sync_core.py (spec ABHIFY_SYNC_PROTOCOL_V1.md §4–§6).
 * Pure logic over two small interfaces, so the same rules run in tests (in-memory) and in the app (SQLite + Google Drive).
 * Nothing runs on a timer. [Engine.preview] downloads and verifies but changes no records; [Engine.apply] imports
 * (additive, deletions only into "Recently deleted"), then writes this device's changes as one op file through a durable outbox.
 *
 * Records and data are plain JSON trees (see [VJson]): Map<String, Any?>, List<Any?>, String, Long, Boolean, null.
 */
typealias Data = Map<String, Any?>

/** (kind, data) of one local record. */
typealias Item = Pair<String, Data>

/** User-facing; never contains keys or personal content. */
class SyncError(message: String) : Exception(message)

/** Remote.putNew: a file with that name is already there. */
class Exists(name: String) : Exception(name)

interface Remote {
    fun listNames(): List<String>
    fun get(name: String): ByteArray
    fun putNew(name: String, data: ByteArray)
}

interface Local {
    fun readItems(kinds: Collection<String>): Map<String, Item>
    fun add(kind: String, rid: String, data: Data)
    fun update(kind: String, rid: String, data: Data)
    fun remove(kind: String, rid: String)

    /** A JSON-compatible value (as written by [putMeta]) or null when absent. */
    fun getMeta(key: String): Any?
    fun putMeta(key: String, value: Any?)

    /** Runs [block] in one transaction; any exception rolls back every add/update/remove/putMeta made inside it. */
    fun <T> transaction(block: () -> T): T
}

object SyncCore {
    val KINDS = listOf("device", "pref", "conversation", "memory", "message")
    val SELECTABLE = listOf("conversation", "message", "memory", "pref")
    val PREF_KEYS = listOf("user_name", "theme", "answer_detail", "answer_depth", "answer_style", "answer_language")
    val ORDER: Map<String, Int> = KINDS.withIndex().associate { it.value to it.index }
    const val HIST = 32
    val RID = Regex("[A-Za-z0-9_.:\\-]{8,80}")
    private val DEV = Regex("[0-9a-f]{16}")

    fun vid(v: Map<*, *>?): String? = if (v == null) null else "${Py.str(v["dev"])}:${Py.str(v["n"])}"

    /** SHA-256 hex of json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(",", ":")). */
    fun dataHash(data: Any?): String = VaultCrypto.hex(VaultCrypto.sha256(VJson.bytes(data, sortKeys = true)))

    private fun s(v: Any?, n: Int) = v is String && Py.len(v) <= n
    private fun ms(v: Any?) = (v is Long && v >= 0 && v < 100_000_000_000_000L) || (v is Int && v >= 0)
    private fun long(v: Any?): Long? = when (v) { is Long -> v; is Int -> v.toLong(); else -> null }

    /** The record in normalized form, or null when it must not be imported (counted as invalid). */
    fun validateRecord(r: Any?): MutableMap<String, Any?>? {
        if (r !is Map<*, *>) return null
        val kind = r["kind"]
        val rid = r["rid"]
        if (kind !is String || kind !in KINDS || rid !is String || !RID.matches(rid)) return null
        val ver = r["ver"]
        if (ver !is Map<*, *>) return null
        val vdev = Py.str(if (ver.containsKey("dev")) ver["dev"] else "")
        val vn = long(ver["n"])
        if (!DEV.matches(vdev) || vn == null || vn < 1) return null
        val base = r["base"]
        if (base != null && !(base is Map<*, *> && long(base["n"]) != null && base["dev"] is String)) return null
        val at = r["at"]
        val out = linkedMapOf<String, Any?>(
            "rid" to rid, "kind" to kind, "ver" to linkedMapOf<String, Any?>("dev" to ver["dev"], "n" to vn),
            "base" to if (base is Map<*, *> && base.isNotEmpty()) linkedMapOf<String, Any?>("dev" to base["dev"], "n" to long(base["n"])) else null,
            "at" to if (ms(at)) long(at) else 0L, "del" to (r["del"] == true),
        )
        if (out["del"] == true) {
            out["data"] = linkedMapOf<String, Any?>()
            return out
        }
        val d = r["data"] as? Map<*, *> ?: return null
        val ok: Boolean
        val keep: List<String>
        when (kind) {
            "conversation" -> {
                ok = s(d["title"], 500) && ms(d["created"])
                keep = listOf("title", "created", "pinned", "archived")
            }
            "message" -> {
                val conv = d["conv"]
                ok = conv is String && RID.matches(conv) && d["role"] in listOf("user", "assistant") &&
                    s(d["content"], 1_000_000) && ms(d["created"]) && (d["model"] == null || s(d["model"], 200))
                keep = listOf("conv", "role", "content", "created", "model")
            }
            "memory" -> {
                ok = s(d["content"], 20_000) && ms(d["created"]) && (d["category"] == null || s(d["category"], 40))
                keep = listOf("content", "created", "category", "pinned")
            }
            "pref" -> {
                ok = d["key"] in PREF_KEYS && s(d["value"], 200) && rid == "pref:${Py.str(d["key"])}"
                keep = listOf("key", "value")
            }
            else -> {   // device
                ok = (s(d["name"], 80) && d["platform"] in listOf("windows", "android", "web") && ms(d["added"]) && rid == ver["dev"]) ||
                    (d["revoked"] == true && s(d["name"], 80))
                keep = listOf("name", "platform", "added", "revoked", "revoked_after_seq")
            }
        }
        if (!ok) return null
        for (b in listOf("pinned", "archived", "revoked")) if (d.containsKey(b) && d[b] !is Boolean) return null
        val data = linkedMapOf<String, Any?>()
        for (kk in keep) if (d.containsKey(kk) && d[kk] != null) data[kk] = d[kk].let { if (it is Int) it.toLong() else it }
        out["data"] = data
        return out
    }

    fun naturalKey(kind: String, data: Data): String? = when (kind) {
        "memory" -> "m|" + Py.casefold(Py.squash(Py.str(if (data.containsKey("content")) data["content"] else "")))
        "message" -> "g|${Py.str(data["conv"])}|${Py.str(data["role"])}|${Py.str(data["created"])}|${Py.str(data["content"])}"
        else -> null
    }

    internal fun parseVer(s: String?): MutableMap<String, Any?>? {
        if (s.isNullOrEmpty()) return null
        val i = s.lastIndexOf(':')
        return linkedMapOf("dev" to s.substring(0, i), "n" to s.substring(i + 1).toLong())
    }

    internal fun n(v: Map<*, *>): Long = (v["n"] as Number).toLong()
}

/** Per-rid sync bookkeeping (local only). */
class State(
    var kind: String,
    var ver: String? = null,           // "dev:n" last applied/created here
    var hist: MutableList<String> = mutableListOf(),
    var maxn: Long = 0,
    var hash: String? = null,          // local canonical data hash at last sync (null: nothing local to compare)
    var deleted: Boolean = false,
    var aliasOf: String? = null,
    var detached: Boolean = false,     // deleted here, user chose not to delete elsewhere
) {
    fun push(newVer: Map<*, *>) {
        val old = ver
        val nv = SyncCore.vid(newVer)
        if (!old.isNullOrEmpty() && old != nv) hist = (listOf(old) + hist.filter { it != old }).take(SyncCore.HIST).toMutableList()
        ver = nv
        maxn = maxOf(maxn, SyncCore.n(newVer))
    }

    fun knows(v: Map<*, *>): Boolean { val x = SyncCore.vid(v); return x == ver || x in hist }

    fun copy() = State(kind, ver, hist.toMutableList(), maxn, hash, deleted, aliasOf, detached)

    fun toMap(): Map<String, Any?> = linkedMapOf("kind" to kind, "ver" to ver, "hist" to hist.toList(), "maxn" to maxn, "hash" to hash,
        "deleted" to deleted, "alias_of" to aliasOf, "detached" to detached)

    companion object {
        fun fromMap(m: Map<*, *>) = State(
            m["kind"] as String, m["ver"] as String?, (m["hist"] as? List<*>)?.map { it as String }?.toMutableList() ?: mutableListOf(),
            (m["maxn"] as? Number)?.toLong() ?: 0L, m["hash"] as String?, m["deleted"] == true, m["alias_of"] as String?, m["detached"] == true,
        )
    }
}

/** op: add | update | delete | tombstone | skip | alias | adopt | conflict | hold | invalid | revoked */
class Action(val op: String, val record: Map<String, Any?>?, val detail: String = "")

@Suppress("UNCHECKED_CAST")
private fun asMap(v: Any?): Map<String, Any?> = v as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun mapOr(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

private fun listOr(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

class Engine(
    private val local: Local,
    private val remote: Remote,
    private val device: String,
    private val platform: String,
    private val deviceName: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val me = device

    init {
        require(Regex("[0-9a-f]{16}").matches(device)) { "device id" }
    }

    // ---- bookkeeping in Local meta
    private fun states(): MutableMap<String, State> {
        val out = LinkedHashMap<String, State>()
        for ((k, v) in mapOr(local.getMeta("states"))) out[k] = State.fromMap(v as Map<*, *>)
        return out
    }

    private fun saveStates(st: Map<String, State>) {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in st) m[k] = v.toMap()
        local.putMeta("states", m)
    }

    internal fun seen(): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in mapOr(local.getMeta("seen"))) out[k] = (v as Number).toLong()
        return out
    }

    fun selected(): List<String> = listOr(local.getMeta("kinds")).filter { it in SyncCore.SELECTABLE }.map { it as String }

    fun devices(): MutableMap<String, MutableMap<String, Any?>> {
        val out = LinkedHashMap<String, MutableMap<String, Any?>>()
        for ((k, v) in mapOr(local.getMeta("devices"))) out[k] = LinkedHashMap(mapOr(v))
        return out
    }

    // ---- remote reading
    /** Verified op bodies for every other device, contiguous after what was applied. Staged locally (resumable). */
    private fun incoming(keys: Map<Int, ByteArray>, vaultId: String): Pair<List<Map<String, Any?>>, List<String>> {
        val seen = seen()
        val names = remote.listNames()
        val per = LinkedHashMap<String, MutableList<Long>>()
        for (n in names) {
            val p = VaultCrypto.parseOpName(n)
            if (p != null && p.first != me) per.getOrPut(p.first) { mutableListOf() }.add(p.second)
        }
        val staged = LinkedHashMap(mapOr(local.getMeta("staged")))
        val ops = mutableListOf<Map<String, Any?>>()
        val notes = mutableListOf<String>()
        for (dev in per.keys.sortedWith(Py.cpOrder)) {
            var want = (seen[dev] ?: 0L) + 1
            for (s in per.getValue(dev).filter { it >= want }.toSortedSet()) {
                if (s != want) {
                    notes += "waiting for an earlier sync file from device ${dev.take(6)}"
                    break
                }
                val name = VaultCrypto.opName(dev, s)
                val body: Map<String, Any?>
                if (staged.containsKey(name)) {
                    body = asMap(staged[name])
                } else {
                    val opened = try {
                        VaultCrypto.openOps(remote.get(name), keys, vaultId)
                    } catch (e: VaultError) {
                        notes += (e.message ?: "")
                        break
                    }
                    if (!Py.eq(opened["device"], dev) || !Py.eq(opened["seq"], s)) {
                        notes += "a sync file has the wrong name; it was not imported"
                        break
                    }
                    staged[name] = opened
                    body = opened
                }
                ops += body
                want += 1
            }
        }
        local.putMeta("staged", staged)
        return ops to notes
    }

    // ---- planning (pure simulation over copies)
    private fun plan(ops: List<Map<String, Any?>>, states: MutableMap<String, State>, items: MutableMap<String, Item>, kinds: Set<String>): List<Action> {
        val devices = devices()
        val nat = LinkedHashMap<String, String>()
        for ((rid, it) in items) SyncCore.naturalKey(it.first, it.second)?.let { k -> nat[k] = rid }
        val knownConv = HashSet<String>()
        for ((rid, it) in items) if (it.first == "conversation") knownConv += rid
        for ((rid, s) in states) if (s.kind == "conversation" && !s.deleted) knownConv += rid
        val acts = mutableListOf<Action>()
        val held = listOr(local.getMeta("held"))
        val queue = ops + listOf(linkedMapOf<String, Any?>("device" to "held", "seq" to 0L, "records" to held))   // held messages after the files
        for (op in ops) {                                                                                       // revocations first
            for (r in listOr(op["records"])) {
                val v = SyncCore.validateRecord(r)
                if (v != null && v["kind"] == "device" && asMap(v["data"])["revoked"] == true && v["rid"] != op["device"]) {
                    val rid = v["rid"] as String
                    devices[rid] = LinkedHashMap(devices[rid] ?: emptyMap<String, Any?>()).apply { putAll(asMap(v["data"])) }
                }
            }
        }
        for (op in queue) {
            val dev = op["device"] as String
            val records = listOr(op["records"])
            val cut = devices[dev]?.get("revoked_after_seq")
            if (dev != "held" && cut != null && Py.gt(op["seq"], cut)) {
                repeat(records.size) { acts += Action("revoked", null, "from removed device ${dev.take(6)}") }
                continue
            }
            val recs = records.map { SyncCore.validateRecord(it) }
            for (x in recs) if (x == null) acts += Action("invalid", null, "a record failed validation")
            val sorted = recs.filterNotNull().sortedWith(compareBy({ SyncCore.ORDER.getValue(it["kind"] as String) }, { if (it["base"] != null) 0 else 1 }))
            for (r in sorted) acts += decide(r, states, items, nat, knownConv, kinds)
        }
        return acts
    }

    private fun decide(r: Map<String, Any?>, states: MutableMap<String, State>, items: MutableMap<String, Item>, nat: MutableMap<String, String>,
                       knownConv: MutableSet<String>, kinds: Set<String>): Action {
        val rid = r["rid"] as String
        val kind = r["kind"] as String
        val ver = asMap(r["ver"])
        val base = r["base"] as Map<*, *>?
        val del = r["del"] == true
        val data = mapOr(r["data"])
        if (kind == "device") {                                    // bookkeeping, never a user conflict; "revoked" is sticky
            var s = states[rid]
            if (s == null) {
                s = State("device"); states[rid] = s
            } else if (s.knows(ver)) {
                return Action("skip", r, "already here")
            }
            if (SyncCore.n(ver) > s.maxn || s.ver == null) {
                s.push(ver)
            } else {
                s.hist = (listOf(SyncCore.vid(ver)!!) + s.hist).take(SyncCore.HIST).toMutableList()
                s.maxn = maxOf(s.maxn, SyncCore.n(ver))
            }
            return Action("update", r)
        }
        if (kind !in kinds) return Action("skip", r, "category not selected")
        val s = states[rid]
        val loc = items[rid]
        if (s == null) {
            if (del) {
                val st = State(kind, deleted = true)
                states[rid] = st
                st.push(ver)
                return Action("tombstone", r)
            }
            if (kind == "message" && data["conv"] !in knownConv) return Action("hold", r, "its conversation hasn't arrived yet")
            val nk = SyncCore.naturalKey(kind, data)
            if (nk != null && nat.containsKey(nk) && nat[nk] != rid) {
                val st = State(kind, aliasOf = nat[nk])
                states[rid] = st
                st.push(ver)
                return Action("alias", r, "same as an item already here")
            }
            val st = State(kind)
            st.push(ver)
            st.hash = SyncCore.dataHash(data)
            states[rid] = st
            items[rid] = kind to data
            if (kind == "conversation") knownConv += rid
            if (nk != null) nat[nk] = rid
            return Action("add", r)
        }
        if (s.knows(ver)) return Action("skip", r, "already here")
        s.maxn = maxOf(s.maxn, SyncCore.n(ver))
        if (!s.aliasOf.isNullOrEmpty()) {
            s.push(ver)
            return Action("skip", r, "duplicate of an item already here")
        }
        val localChanged = loc != null && s.hash != null && SyncCore.dataHash(loc.second) != s.hash
        val locallyGone = loc == null && !s.deleted && !s.detached && s.hash != null && kind != "device"
        if (SyncCore.vid(base) == s.ver) {
            if (localChanged || (locallyGone && !del)) {
                return Action("conflict", r, if (localChanged) "changed here and on another device" else "deleted here, changed on another device")
            }
            s.push(ver)
            if (del) {
                s.deleted = true; s.hash = null
                if (loc == null && kind != "device") return Action("tombstone", r)
                renat(nat, kind, rid, loc?.second, null)
                items.remove(rid)
                return Action("delete", r)
            }
            s.deleted = false; s.detached = false
            s.hash = SyncCore.dataHash(data)
            if (loc == null && kind != "device") {
                items[rid] = kind to data
                return Action("add", r, "restored from another device")
            }
            renat(nat, kind, rid, loc?.second, data)
            items[rid] = kind to data
            return Action("update", r)
        }
        // concurrent
        val curN = SyncCore.parseVer(s.ver)?.let { SyncCore.n(it) } ?: 0L
        if (del && (loc == null || s.deleted)) {
            if (SyncCore.n(ver) >= curN) s.push(ver) else s.hist = (listOf(SyncCore.vid(ver)!!) + s.hist).take(SyncCore.HIST).toMutableList()
            s.deleted = true
            return Action("adopt", r, "already deleted here")
        }
        if (!del && loc != null && SyncCore.dataHash(loc.second) == SyncCore.dataHash(data)) {
            if (SyncCore.n(ver) > curN) s.push(ver) else s.hist = (listOf(SyncCore.vid(ver)!!) + s.hist).take(SyncCore.HIST).toMutableList()
            return Action("adopt", r, "same content")
        }
        return Action("conflict", r, "changed on two devices")
    }

    private fun renat(nat: MutableMap<String, String>, kind: String, rid: String, old: Data?, new: Data?) {
        if (old != null) {
            val k = SyncCore.naturalKey(kind, old)
            if (k != null && nat[k] == rid) nat.remove(k)
        }
        if (new != null) SyncCore.naturalKey(kind, new)?.let { nat[it] = rid }
    }

    // ---- local changes
    /** This device's changes as new versions. Records with an open conflict wait until the user resolves it. */
    private fun collect(states: MutableMap<String, State>, items: Map<String, Item>, kinds: Set<String>, approveDeletes: Set<String>,
                        blocked: Set<String> = emptySet()): Pair<MutableList<Map<String, Any?>>, MutableList<Map<String, Any?>>> {
        val out = mutableListOf<Map<String, Any?>>()
        val pendingDel = mutableListOf<Map<String, Any?>>()
        val t = now()
        for ((rid, item) in items.entries.sortedBy { SyncCore.ORDER.getValue(it.value.first) }) {
            val (kind, data) = item
            if ((kind != "device" && kind !in kinds) || rid in blocked || (kind == "device" && rid != me)) continue
            var s = states[rid]
            val h = SyncCore.dataHash(data)
            if (s != null && (!s.aliasOf.isNullOrEmpty() || s.hash == h)) continue
            if (s == null) { s = State(kind); states[rid] = s }
            val base = SyncCore.parseVer(s.ver)
            val v = linkedMapOf<String, Any?>("dev" to me, "n" to s.maxn + 1)
            s.push(v)
            s.hash = h; s.deleted = false; s.detached = false
            out += linkedMapOf("rid" to rid, "kind" to kind, "ver" to v, "base" to base, "at" to t, "del" to false, "data" to data)
        }
        for ((rid, s) in states.entries.sortedWith { a, b -> Py.cpOrder.compare(a.key, b.key) }) {
            if (s.kind in kinds && rid !in items && rid !in blocked && !s.deleted && !s.detached && s.aliasOf.isNullOrEmpty() && s.hash != null) {
                if (rid in approveDeletes) {
                    val base = SyncCore.parseVer(s.ver)
                    val v = linkedMapOf<String, Any?>("dev" to me, "n" to s.maxn + 1)
                    s.push(v)
                    s.deleted = true; s.hash = null
                    out += linkedMapOf("rid" to rid, "kind" to s.kind, "ver" to v, "base" to base, "at" to t, "del" to true, "data" to linkedMapOf<String, Any?>())
                } else {
                    pendingDel += linkedMapOf<String, Any?>("rid" to rid, "kind" to s.kind)
                }
            }
        }
        return out to pendingDel
    }

    private fun checkNotRevoked() {
        if (Py.truthy(devices()[me]?.get("revoked"))) {
            throw SyncError("This device was removed from sync on another device. Your data here is unchanged; to sync again, reset this device's sync identity.")
        }
    }

    /**
     * Marks another device removed (its files after the last one applied here are ignored everywhere). The caller rotates the
     * vault key with the Recovery Key; the record uploads on the next Sync Now.
     */
    fun revoke(target: String): Map<String, Any?> {
        if (target == me) throw SyncError("To stop syncing this device, use Disconnect instead.")
        return local.transaction {
            val devices = devices()
            val states = states()
            val d = LinkedHashMap<String, Any?>(devices[target]?.takeIf { it.isNotEmpty() }
                ?: linkedMapOf("name" to "device", "platform" to "windows", "added" to 0L))
            d["revoked"] = true
            d["revoked_after_seq"] = seen()[target] ?: 0L
            devices[target] = d
            val s = states.getOrPut(target) { State("device") }
            val base = SyncCore.parseVer(s.ver)
            val v = linkedMapOf<String, Any?>("dev" to me, "n" to s.maxn + 1)
            s.push(v)
            val extra = listOr(local.getMeta("extra")).toMutableList()
            extra += linkedMapOf("rid" to target, "kind" to "device", "ver" to v, "base" to base, "at" to now(), "del" to false, "data" to d)
            local.putMeta("extra", extra)
            local.putMeta("devices", devices)
            saveStates(states)
            d
        }
    }

    private fun items(kinds: Set<String>): MutableMap<String, Item> {
        val items = LinkedHashMap(local.readItems(kinds.sorted()))
        val stored = local.getMeta("device_info")
        val info = if (Py.truthy(stored)) mapOr(stored) else linkedMapOf("name" to deviceName, "platform" to platform, "added" to now())
        local.putMeta("device_info", info)
        items[me] = "device" to info
        return items
    }

    // ---- public API
    /** What Sync Now would do. Downloads and verifies; changes no records. */
    fun preview(keys: Map<Int, ByteArray>, vaultId: String): Map<String, Any?> {
        val kinds = selected().toSet()
        if (kinds.isEmpty()) throw SyncError("Choose at least one category to sync.")
        checkNotRevoked()
        val (ops, notes) = incoming(keys, vaultId)
        val states = states()
        val items = items(kinds)
        val acts = plan(ops, states, items, kinds)
        val blocked = conflicts().map { it["rid"] as String }.toSet() + acts.filter { it.op == "conflict" }.map { it.record!!["rid"] as String }
        val (loc, pendingDel) = collect(states, items, kinds, emptySet(), blocked)
        val summary = LinkedHashMap<String, MutableMap<String, Int>>()
        for (a in acts) {
            val k = a.record?.get("kind") as String? ?: "other"
            if (k == "device") continue
            val row = summary.getOrPut(a.op) { LinkedHashMap() }
            row[k] = (row[k] ?: 0) + 1
        }
        val up = LinkedHashMap<String, Int>()
        for (r in loc) { val k = r["kind"] as String; if (k != "device") up[k] = (up[k] ?: 0) + 1 }
        return linkedMapOf("incoming" to summary, "files" to ops.size, "upload" to up, "local_deletions" to pendingDel,
            "conflicts_waiting" to listOr(local.getMeta("conflicts")).size, "notes" to notes, "outbox" to listOr(local.getMeta("outbox")).size)
    }

    /** Result keys: added, updated, deleted, conflicts, held, skipped, invalid, uploaded (Int) and notes (List<String>). */
    fun apply(keys: Map<Int, ByteArray>, vaultId: String, epoch: Int, direction: String = "both", approveDeletes: Collection<String> = emptyList(),
              keepElsewhere: Collection<String> = emptyList()): MutableMap<String, Any> {
        require(direction in listOf("both", "upload", "download")) { direction }
        val kinds = selected().toSet()
        if (kinds.isEmpty()) throw SyncError("Choose at least one category to sync.")
        checkNotRevoked()
        val result = linkedMapOf<String, Any>("added" to 0, "updated" to 0, "deleted" to 0, "conflicts" to 0, "held" to 0, "skipped" to 0,
            "invalid" to 0, "uploaded" to 0, "notes" to mutableListOf<String>())
        @Suppress("UNCHECKED_CAST") val resultNotes = result["notes"] as MutableList<String>
        var ops: List<Map<String, Any?>> = emptyList()
        if (direction == "both" || direction == "download") {     // network first, outside the database transaction
            val (o, notes) = incoming(keys, vaultId)
            ops = o
            resultNotes += notes
        }
        val revokedHere = local.transaction {
            val states = states()
            if (direction == "both" || direction == "download") {
                val items = items(kinds)
                val planStates = LinkedHashMap<String, State>().also { m -> states.forEach { (k, v) -> m[k] = v.copy() } }
                val acts = plan(ops, planStates, LinkedHashMap(items), kinds)
                execute(acts, states, items, result)
                val seen = seen()
                for (op in ops) seen[op["device"] as String] = (op["seq"] as Number).toLong()
                local.putMeta("seen", seen)
                val staged = LinkedHashMap(mapOr(local.getMeta("staged")))
                for (op in ops) staged.remove(VaultCrypto.opName(op["device"] as String, (op["seq"] as Number).toLong()))
                local.putMeta("staged", staged)
            }
            for (rid in keepElsewhere) states[rid]?.detached = true
            val revoked = Py.truthy(devices()[me]?.get("revoked"))
            if ((direction == "both" || direction == "upload") && !revoked) {
                val items = items(kinds)
                val (records, _) = collect(states, items, kinds, approveDeletes.toSet(), conflicts().map { it["rid"] as String }.toSet())
                for (x in listOr(local.getMeta("extra"))) records += asMap(x)
                local.putMeta("extra", emptyList<Any?>())
                if (records.isNotEmpty()) {
                    val seq = ((local.getMeta("seq") as? Number)?.toLong() ?: 0L) + 1
                    val data = VaultCrypto.sealOps(vaultId, epoch, keys.getValue(epoch), me, seq, now(), records)
                    val outbox = listOr(local.getMeta("outbox")).toMutableList()
                    outbox += linkedMapOf<String, Any?>("name" to VaultCrypto.opName(me, seq), "data" to VaultCrypto.b64e(data),
                        "count" to records.count { it["kind"] != "device" }.toLong())
                    local.putMeta("outbox", outbox)
                    local.putMeta("seq", seq)
                }
            }
            saveStates(states)
            revoked
        }
        if (revokedHere) {
            history(direction, result)
            throw SyncError("This device was removed from sync on another device. Nothing more was uploaded; your data here is unchanged.")
        }
        result["uploaded"] = flushOutbox()
        history(direction, result)
        return result
    }

    /** Uploads waiting op files in order. Same bytes are retried after an interruption; never overwrites. */
    fun flushOutbox(): Int {
        var sent = 0
        val outbox = listOr(local.getMeta("outbox")).toMutableList()
        while (outbox.isNotEmpty()) {
            val item = asMap(outbox[0])
            val data = VaultCrypto.b64d(item["data"])
            val name = item["name"] as String
            try {
                remote.putNew(name, data)
            } catch (e: Exists) {
                if (!remote.get(name).contentEquals(data)) {
                    throw SyncError("Another installation is using this device's sync identity (for example a copied data folder). " +
                        "Nothing was overwritten. Reset this device's sync identity in Data & Sync.")
                }
            }
            sent += ((item["count"] as? Number)?.toInt() ?: 0)
            outbox.removeAt(0)
            local.putMeta("outbox", outbox.toList())
        }
        return sent
    }

    private fun inc(result: MutableMap<String, Any>, key: String) { result[key] = (result[key] as Int) + 1 }

    private fun execute(acts: List<Action>, states: MutableMap<String, State>, items: MutableMap<String, Item>, result: MutableMap<String, Any>) {
        val held = mutableListOf<Any?>()
        val conflicts = listOr(local.getMeta("conflicts")).toMutableList()
        val trash = listOr(local.getMeta("trash")).toMutableList()
        val devices = devices()
        val nat = LinkedHashMap<String, String>()
        for ((rid, it) in items) SyncCore.naturalKey(it.first, it.second)?.let { k -> nat[k] = rid }
        val knownConv = HashSet<String>()
        for ((rid, it) in items) if (it.first == "conversation") knownConv += rid
        for ((rid, s) in states) if (s.kind == "conversation" && !s.deleted) knownConv += rid
        for (a in acts) {
            if (a.op == "invalid" || a.op == "revoked") {
                inc(result, if (a.op == "invalid") "invalid" else "skipped")
                continue
            }
            val r = a.record!!
            // re-decide against the live state (the plan ran on copies; this keeps one code path for both)
            val act = decide(r, states, LinkedHashMap(items), nat, knownConv, selected().toSet())
            val rid = r["rid"] as String
            val kind = r["kind"] as String
            val data = mapOr(r["data"])
            if (kind == "device" && (act.op == "add" || act.op == "update")) {
                val old = devices[rid] ?: emptyMap<String, Any?>()
                val merged = LinkedHashMap(old).apply { putAll(data) }
                if (Py.truthy(old["revoked"])) {
                    merged["revoked"] = true
                    merged["revoked_after_seq"] = if (old.containsKey("revoked_after_seq")) old["revoked_after_seq"]
                        else if (data.containsKey("revoked_after_seq")) data["revoked_after_seq"] else 0L
                }
                if (rid != me || Py.truthy(merged["revoked"])) devices[rid] = merged
                continue                                           // bookkeeping: not counted as the user's data
            }
            when (act.op) {
                "add" -> {
                    if (kind == "device") devices[rid] = LinkedHashMap(data) else {
                        local.add(kind, rid, data)
                        items[rid] = kind to data
                        states.getValue(rid).hash = rehash(kind, rid)
                    }
                    inc(result, "added")
                }
                "update" -> {
                    if (kind == "device") devices[rid] = LinkedHashMap(data) else {
                        local.update(kind, rid, data)
                        items[rid] = kind to data
                        states.getValue(rid).hash = rehash(kind, rid)
                    }
                    inc(result, "updated")
                }
                "delete" -> {
                    if (kind == "device") devices.remove(rid) else {
                        val old = items.remove(rid)
                        if (old != null) {
                            trash += linkedMapOf<String, Any?>("id" to newId(), "rid" to rid, "kind" to kind, "data" to old.second,
                                "reason" to "deleted on another device", "at" to now())
                            local.remove(kind, rid)
                        }
                    }
                    inc(result, "deleted")
                }
                "conflict" -> {
                    conflicts += linkedMapOf<String, Any?>("id" to newId(), "rid" to rid, "kind" to kind, "local" to items[rid]?.second,
                        "remote" to r, "why" to act.detail, "at" to now())
                    inc(result, "conflicts")
                }
                "hold" -> { held += r; inc(result, "held") }
                else -> inc(result, "skipped")
            }
        }
        local.putMeta("held", held)
        local.putMeta("conflicts", conflicts)
        local.putMeta("trash", trash)
        local.putMeta("devices", devices)
    }

    private fun rehash(kind: String, rid: String): String? = local.readItems(listOf(kind))[rid]?.let { SyncCore.dataHash(it.second) }

    // ---- conflicts, recently deleted, history
    fun conflicts(): List<Map<String, Any?>> = listOr(local.getMeta("conflicts")).map { asMap(it) }

    /** keep_mine | keep_theirs | keep_both. The result uploads on the next Sync Now. */
    fun resolve(conflictId: String, choice: String) {
        require(choice in listOf("keep_mine", "keep_theirs", "keep_both")) { choice }
        local.transaction {
            val cs = conflicts()
            val c = cs.firstOrNull { it["id"] == conflictId } ?: throw SyncError("That conflict was already resolved.")
            val states = states()
            val r = asMap(c["remote"])
            val rid = c["rid"] as String
            val kind = c["kind"] as String
            val rdel = r["del"] == true
            val rdata = mapOr(r["data"])
            val s = states.getOrPut(rid) { State(kind) }
            val items = local.readItems(listOf(kind))
            var loc = items[rid]
            s.push(asMap(r["ver"]))                                // the other version is now known here
            if (choice == "keep_theirs") {
                if (loc != null) trash(rid, kind, loc.second, "replaced by the other device's version")
                if (rdel) {
                    if (loc != null) local.remove(kind, rid)
                    s.deleted = true; s.hash = null
                } else {
                    if (loc != null) local.update(kind, rid, rdata) else local.add(kind, rid, rdata)
                    s.deleted = false; s.detached = false; s.hash = rehash(kind, rid)
                }
            } else {
                if (choice == "keep_both" && !rdel) {
                    local.add(kind, newId(), LinkedHashMap(rdata))   // new record: uploads as new next time
                }
                val cl = c["local"]
                if (loc == null && Py.truthy(cl)) {
                    local.add(kind, rid, mapOr(cl))
                    loc = kind to mapOr(cl)
                }
                if (loc == null && !rdel) {
                    s.hash = SyncCore.dataHash(rdata); s.deleted = false; s.detached = false   // kept deleted here: offered as a deletion next preview
                } else {
                    s.hash = null; s.deleted = false; s.detached = false                       // forces a new local version based on the other one
                }
            }
            saveStates(states)
            local.putMeta("conflicts", cs.filter { it["id"] != conflictId })
        }
    }

    private fun trash(rid: String, kind: String, data: Data, reason: String) {
        val trash = listOr(local.getMeta("trash")).toMutableList()
        trash += linkedMapOf<String, Any?>("id" to newId(), "rid" to rid, "kind" to kind, "data" to data, "reason" to reason, "at" to now())
        local.putMeta("trash", trash)
    }

    fun recentlyDeleted(days: Int = 30): List<Map<String, Any?>> {
        val cutoff = now() - days * 86_400_000L
        return listOr(local.getMeta("trash")).map { asMap(it) }.filter { (it["at"] as Number).toLong() >= cutoff }
    }

    fun restoreDeleted(trashId: String) {
        local.transaction {
            val trash = listOr(local.getMeta("trash")).map { asMap(it) }
            val t = trash.firstOrNull { it["id"] == trashId } ?: throw SyncError("That item is no longer in Recently deleted.")
            val kind = t["kind"] as String
            val rid = t["rid"] as String
            if (local.readItems(listOf(kind)).containsKey(rid)) throw SyncError("That item is already here.")
            local.add(kind, rid, mapOr(t["data"]))
            val states = states()
            val s = states.getOrPut(rid) { State(kind) }
            s.deleted = false; s.detached = false; s.hash = null   // uploads as a new version (undelete) next time
            saveStates(states)
            local.putMeta("trash", trash.filter { it["id"] != trashId })
        }
    }

    /** After choosing more categories: read every device's files again (already-known records are skipped). */
    fun redownloadAll() = local.putMeta("seen", linkedMapOf<String, Any?>())

    private fun history(direction: String, result: Map<String, Any>) {
        val h = listOr(local.getMeta("history")).toMutableList()
        val entry = linkedMapOf<String, Any?>("at" to now(), "direction" to direction)
        for ((k, v) in result) if (k != "notes") entry[k] = v
        entry["notes"] = (result["notes"] as List<*>).take(5)
        h.add(0, entry)
        local.putMeta("history", h.take(100))
    }
}
