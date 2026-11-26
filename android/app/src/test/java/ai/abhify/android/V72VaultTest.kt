package ai.abhify.android

import ai.abhify.android.vault.Engine
import ai.abhify.android.vault.Exists
import ai.abhify.android.vault.Item
import ai.abhify.android.vault.Local
import ai.abhify.android.vault.Malformed
import ai.abhify.android.vault.RecoveryKeyTypo
import ai.abhify.android.vault.Remote
import ai.abhify.android.vault.SyncCore
import ai.abhify.android.vault.SyncError
import ai.abhify.android.vault.Unsupported
import ai.abhify.android.vault.VJson
import ai.abhify.android.vault.VaultCrypto
import ai.abhify.android.vault.VaultError
import ai.abhify.android.vault.WrongKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 7.2.0-dev.1: Kotlin port of the vault/op/backup formats and the manual sync engine, checked against the shared vectors
 * (backend/tests/data/vault_vectors.json, sync_cases.json) that the Python reference also passes. JVM unit tests (desktop
 * JCA), not on-device runtime tests.
 */
@Suppress("UNCHECKED_CAST")
class V72VaultTest {

    // ------------------------------------------------------------------ fixtures
    companion object {
        private val dataDir: File by lazy {
            var d: File? = File("").absoluteFile
            while (d != null && !File(d, "backend/tests/data/vault_vectors.json").isFile) d = d.parentFile
            File(d ?: error("repo root (with backend/) not found"), "backend/tests/data")
        }
        val V: Map<String, Any?> by lazy { VJson.parse(File(dataDir, "vault_vectors.json").readText(Charsets.UTF_8)) as Map<String, Any?> }
        val CASES: Map<String, Any?> by lazy { VJson.parse(File(dataDir, "sync_cases.json").readText(Charsets.UTF_8)) as Map<String, Any?> }

        const val VID = "00000000000000000000000000000001"
        val KEYS: Map<Int, ByteArray> = mapOf(1 to ByteArray(32) { it.toByte() })
    }

    private fun m(v: Any?) = v as Map<String, Any?>
    private fun l(v: Any?) = v as List<Any?>
    private fun s(v: Any?) = v as String
    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = VaultCrypto.hex(b)

    /** make_vault_vectors.det: deterministic bytes for vectors only. */
    private fun det(seed: String): (Int) -> ByteArray {
        var i = 0
        return { n ->
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < n) { out.write(MessageDigest.getInstance("SHA-256").digest("$seed:$i".toByteArray())); i++ }
            out.toByteArray().copyOf(n)
        }
    }

    private inline fun <reified T : Throwable> raises(block: () -> Unit): T {
        try { block() } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got ${e::class.java.simpleName}: ${e.message}", e)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing thrown")
    }

    private fun deep(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k as String] = deep(x) } }
        is List<*> -> v.mapTo(ArrayList()) { deep(it) }
        else -> v
    }

    private fun stripCt(fileJson: String) = LinkedHashMap(m(VJson.parse(fileJson))).apply { remove("ct") }

    // ------------------------------------------------------------------ encoding, Recovery Key
    @Test fun base32AndRecoveryKeyEncoding() {
        for (c in l(V["b32c"]).map(::m)) assertEquals(c["out"], VaultCrypto.b32c(unhex(s(c["hex"]))))
        for (c in l(V["recovery_keys"]).map(::m)) {
            val raw = unhex(s(c["raw"]))
            assertEquals(c["canonical"], VaultCrypto.newRecoveryKey { n -> raw.copyOf(n) })
            assertEquals(c["display"], VaultCrypto.displayRecoveryKey(s(c["canonical"])))
            assertEquals(c["qr"], VaultCrypto.qrPayload(s(c["canonical"])))
            assertEquals(c["canonical"], VaultCrypto.normalizeRecoveryKey(s(c["display"])))
            assertEquals(c["canonical"], VaultCrypto.fromQr(s(c["qr"])))
        }
    }

    @Test fun normalizationAndTypoDetection() {
        for (c in l(V["normalize"]).map(::m)) {
            if (c.containsKey("out")) assertEquals(c["out"], VaultCrypto.normalizeRecoveryKey(s(c["in"])))
            else raises<RecoveryKeyTypo> { VaultCrypto.normalizeRecoveryKey(s(c["in"])) }
        }
        raises<RecoveryKeyTypo> { VaultCrypto.normalizeRecoveryKey(null) }
        raises<RecoveryKeyTypo> { VaultCrypto.fromQr("https://example.com/abhify-rk:1:" + m(V["vault"])["recovery_key"]) }
    }

    @Test fun kdfAndAeadKnownAnswers() {
        for (c in l(V["kdf"]).map(::m)) {
            assertEquals(c["key"], hex(VaultCrypto.kdf(s(c["password"]), unhex(s(c["salt"])), (c["iter"] as Long))))
        }
        for (c in l(V["aead"]).map(::m)) {
            val key = unhex(s(c["key"])); val iv = unhex(s(c["iv"])); val pt = unhex(s(c["pt"]))
            assertEquals(c["ct"], VaultCrypto.b64e(VaultCrypto.seal(key, iv, pt, s(c["aad"]))))
            assertArrayEquals(pt, VaultCrypto.unseal(key, iv, VaultCrypto.b64d(c["ct"]), s(c["aad"]), "x"))
            raises<WrongKey> { VaultCrypto.unseal(key, iv, VaultCrypto.b64d(c["ct"]), s(c["aad"]) + "!", "x") }
        }
        raises<Unsupported> { VaultCrypto.kdf("p", ByteArray(16) { 's'.code.toByte() }, 99_999) }
        raises<Unsupported> { VaultCrypto.kdf("p", ByteArray(16) { 's'.code.toByte() }, 5_000_001) }
    }

    @Test fun base64IsStrict() {
        raises<Malformed> { VaultCrypto.b64d(null, "iv") }
        raises<Malformed> { VaultCrypto.b64d("YQ", "iv") }       // missing padding (Python validate=True refuses it)
        raises<Malformed> { VaultCrypto.b64d("Y Q==", "iv") }
        assertArrayEquals("a".toByteArray(), VaultCrypto.b64d("YQ=="))
    }

    // ------------------------------------------------------------------ vault.json
    @Test fun vaultUnlockRotationAndWrongKey() {
        val vt = m(V["vault"])
        val k1 = VaultCrypto.unlockVault(m(vt["doc_epoch1"]), s(vt["recovery_key"]))
        assertEquals(vt["keys_epoch1"], k1.entries.associate { it.key.toString() to hex(it.value) })
        val k2 = VaultCrypto.unlockVault(m(vt["doc_epoch2"]), VaultCrypto.displayRecoveryKey(s(vt["recovery_key"])).lowercase())
        assertEquals(vt["keys_epoch2"], k2.entries.associate { it.key.toString() to hex(it.value) })
        raises<WrongKey> { VaultCrypto.unlockVault(m(vt["doc_epoch2"]), s(vt["wrong_recovery_key"])) }
        val bad = deep(vt["doc_epoch1"]) as MutableMap<String, Any?>
        bad["epoch"] = 3L                                          // header is bound by the AAD
        raises<WrongKey> { VaultCrypto.unlockVault(bad, s(vt["recovery_key"])) }
        raises<Malformed> { VaultCrypto.unlockVault((deep(vt["doc_epoch1"]) as MutableMap<String, Any?>).apply { put("format", "x") }, s(vt["recovery_key"])) }
        raises<Unsupported> { VaultCrypto.unlockVault((deep(vt["doc_epoch1"]) as MutableMap<String, Any?>).apply { put("v", "x") }, s(vt["recovery_key"])) }
        val text = VJson.write(vt["doc_epoch2"])                    // no plain key or Recovery Key in the document
        for (kh in m(vt["keys_epoch2"]).values) { assertFalse(text.contains(s(kh))); assertFalse(text.contains(VaultCrypto.b64e(unhex(s(kh))))) }
        assertFalse(text.contains(s(vt["recovery_key"])))
    }

    /** create → rotate → ops → backup with the generator's deterministic randomness: vault docs byte-exact, headers equal. */
    @Test fun deterministicGenerationReproducesVectors() {
        val vt = m(V["vault"]); val o = m(V["ops"]); val b = m(V["backup"])
        val r = det("vault")
        val (doc1, keys1) = VaultCrypto.createVault(s(vt["recovery_key"]), 1763900000000L, r)
        assertEquals(VJson.write(vt["doc_epoch1"]), VJson.write(doc1))
        val (doc2, keys2) = VaultCrypto.rotateVault(doc1, s(vt["recovery_key"]), r)
        assertEquals(VJson.write(vt["doc_epoch2"]), VJson.write(doc2))
        assertEquals(vt["keys_epoch1"], keys1.entries.associate { it.key.toString() to hex(it.value) })
        val vid = s(doc2["vault_id"])
        val records = l(m(o["expect"])["records"])
        val op = VaultCrypto.sealOps(vid, 2, keys2.getValue(2), "a".repeat(16), 7, 1763900000010L, records, r)
        assertEquals(stripCt(s(o["file"])), stripCt(String(op, Charsets.UTF_8)))     // gzip bytes may differ; header and IV equal
        gzipExact = s(o["file"]) == String(op, Charsets.UTF_8)
        assertEquals(o["expect"], VaultCrypto.openOps(op, keys2, vid))
        val opOld = VaultCrypto.sealOps(vid, 1, keys2.getValue(1), "b".repeat(16), 1, 1763800000000L, records.subList(1, 3), r)
        assertEquals(stripCt(s(o["file_epoch1"])), stripCt(String(opOld, Charsets.UTF_8)))
        val bkRecords = m(m(b["expect"])["records"]) as Map<String, List<Any?>>
        val bk = VaultCrypto.sealBackup("correct horse battery", "windows", "7.2.0-dev.1", 1763900000020L, bkRecords,
            listOf("Conversations", "Personal memory"), r, iterations = 100_000)
        assertEquals(stripCt(s(b["file"])), stripCt(String(bk, Charsets.UTF_8)))
        assertEquals(b["expect"], VaultCrypto.openBackup(bk, "correct horse battery"))
        // compact JSON writer = Python json.dumps(separators=(",",":")) for the published files
        assertEquals(s(o["file"]), VJson.write(VJson.parse(s(o["file"]))))
        assertEquals(s(b["file"]), VJson.write(VJson.parse(s(b["file"]))))
    }

    private var gzipExact = false

    /** Informational: with the OS byte fixed to 255, JDK zlib level 6 may now give Python's exact op-file bytes. */
    @Test fun opFileByteExactWithPythonWhereZlibAgrees() {
        deterministicGenerationReproducesVectors()
        org.junit.Assume.assumeTrue("JDK deflate output differs from Python's zlib (allowed by the spec)", gzipExact)
    }

    @Test fun typeConfusionAndDamagedBodiesRaiseVaultErrors() {
        val vt = m(V["vault"])
        for ((k, v) in listOf("iter" to "100000", "iter" to null, "iter" to true, "salt" to 5L)) {
            val bad = deep(vt["doc_epoch1"]) as MutableMap<String, Any?>
            (bad["kdf"] as MutableMap<String, Any?>)[k] = v
            raises<VaultError> { VaultCrypto.unlockVault(bad, s(vt["recovery_key"])) }
        }
        for ((k, v) in listOf("created" to "x", "wrap" to null, "chain" to null)) {
            val bad = deep(vt["doc_epoch1"]) as MutableMap<String, Any?>
            bad[k] = v
            assertEquals("The vault file is damaged.", raises<Malformed> { VaultCrypto.checkVaultDoc(bad) }.message)
        }
        assertEquals(null, VaultCrypto.parseOpName("op-" + "a".repeat(16) + "-0000000١.abx"))
        val key = ByteArray(32); val iv = ByteArray(12)
        for (body in listOf("[1]", "not json", "\"x\"")) {
            val ct = VaultCrypto.seal(key, iv, VaultCrypto.gz(body.toByteArray()), "abhify-sync-ops|1|" + "0".repeat(32) + "|1|" + "a".repeat(16) + "|1|1")
            val f = VJson.bytes(linkedMapOf("format" to "abhify-sync-ops", "v" to 1L, "vault_id" to "0".repeat(32), "epoch" to 1L, "device" to "a".repeat(16),
                "seq" to 1L, "created" to 1L, "iv" to VaultCrypto.b64e(iv), "ct" to VaultCrypto.b64e(ct)))
            assertEquals("The file's content is damaged; it was not imported.", raises<Malformed> { VaultCrypto.openOps(f, mapOf(1 to key), "0".repeat(32)) }.message)
        }
        val short = deep(VJson.parse(s(m(V["ops"])["file"]))) as MutableMap<String, Any?>
        short["iv"] = VaultCrypto.b64e("1234".toByteArray())
        assertEquals("The file is damaged (encryption header).",
            raises<Malformed> { VaultCrypto.openOps(VJson.bytes(short), mapOf(2 to ByteArray(32)), s(short["vault_id"])) }.message)
        assertEquals(255, VaultCrypto.gz("x".toByteArray())[9].toInt() and 0xff)
        raises<Malformed> { VaultCrypto.kdf("p", ByteArray(8), 100_000) }
        // backup header type confusion and damaged backup bodies
        val bh = deep(VJson.parse(s(m(V["backup"])["file"]))) as MutableMap<String, Any?>
        for ((k, v) in listOf("iter" to true, "iter" to "100000", "salt" to 5L)) {
            val b2 = deep(bh) as MutableMap<String, Any?>; (b2["kdf"] as MutableMap<String, Any?>)[k] = v
            raises<Unsupported> { VaultCrypto.openBackup(VJson.bytes(b2), "correct horse battery") }
        }
        raises<Unsupported> { VaultCrypto.openBackup(VJson.bytes((deep(bh) as MutableMap<String, Any?>).apply { put("created", true) }), "x") }
        for (body in listOf("[1]", """{"manifest":5,"records":{}}""", """{"manifest":{"counts":[1]},"records":{}}""", """{"records":{"memory":1}}""")) {
            val r = det("bk-$body")
            val salt = r(16); val biv = r(12)
            val aad = "abhify-vault-backup|2|web|7|1|100000|" + VaultCrypto.b64e(salt)
            val ct = VaultCrypto.seal(VaultCrypto.kdf("a long passphrase", salt, 100_000), biv, VaultCrypto.gz(body.toByteArray()), aad)
            val f = VJson.bytes(linkedMapOf("format" to "abhify-vault-backup", "v" to 2L, "platform" to "web", "app_version" to "7", "created" to 1L,
                "kdf" to linkedMapOf("alg" to "PBKDF2-SHA256", "iter" to 100_000L, "salt" to VaultCrypto.b64e(salt)), "iv" to VaultCrypto.b64e(biv), "ct" to VaultCrypto.b64e(ct)))
            raises<Malformed> { VaultCrypto.openBackup(f, "a long passphrase") }
        }
    }

    // ------------------------------------------------------------------ op files
    @Test fun opsOpenTamperAndOldEpoch() {
        val vt = m(V["vault"]); val o = m(V["ops"])
        val keys = m(vt["keys_epoch2"]).entries.associate { it.key.toInt() to unhex(s(it.value)) }
        val vid = s(m(vt["doc_epoch2"])["vault_id"])
        assertEquals(o["expect"], VaultCrypto.openOps(s(o["file"]).toByteArray(), keys, vid))
        assertEquals(o["expect_epoch1_records"], VaultCrypto.openOps(s(o["file_epoch1"]).toByteArray(), keys, vid)["records"])
        assertEquals("a".repeat(16) to 7L, VaultCrypto.parseOpName(s(o["name"])))
        assertEquals(o["name"], VaultCrypto.opName("a".repeat(16), 7))
        assertEquals(null, VaultCrypto.parseOpName("op-xyz-00000001.abx"))
        assertEquals(null, VaultCrypto.parseOpName("../op-" + "a".repeat(16) + "-00000001.abx"))
        val tampered = l(o["tampered"]).map(::m)
        assertEquals(6, tampered.size)
        for (t in tampered) {
            val e = raises<VaultError> { VaultCrypto.openOps(s(t["file"]).toByteArray(), keys, vid) }
            val want = if (t["error"] == "wrong_key") WrongKey::class.java else Malformed::class.java
            assertEquals("tampered ${t["what"]}", want, e::class.java)
        }
        raises<WrongKey> { VaultCrypto.openOps(s(o["file"]).toByteArray(), mapOf(1 to keys.getValue(1)), vid) }
        raises<Malformed> { VaultCrypto.openOps("not json".toByteArray(), keys, vid) }
        val newer = VJson.write((deep(VJson.parse(s(o["file"]))) as MutableMap<String, Any?>).apply { put("v", 2L) })
        raises<Unsupported> { VaultCrypto.openOps(newer.toByteArray(), keys, vid) }
    }

    // ------------------------------------------------------------------ backup v2
    @Test fun backupOpenWrongPassphraseAndTamper() {
        val b = m(V["backup"])
        val file = s(b["file"]).toByteArray()
        assertEquals(b["expect"], VaultCrypto.openBackup(file, s(b["passphrase"])))
        raises<WrongKey> { VaultCrypto.openBackup(file, s(b["wrong_passphrase"])) }
        raises<WrongKey> { VaultCrypto.openBackup(file, "") }
        val h = deep(VJson.parse(s(b["file"]))) as MutableMap<String, Any?>
        h["platform"] = "android"                                  // header bound by AAD
        raises<WrongKey> { VaultCrypto.openBackup(VJson.bytes(h), s(b["passphrase"])) }
        val e = raises<Malformed> { VaultCrypto.openBackup("not a backup".toByteArray(), "x") }
        assertEquals("This isn't an Abhify.AI backup file.", e.message)
    }

    @Test fun backupRoundTripRealRandomAndPassphraseRules() {
        val recs = mapOf("memory" to listOf<Any?>(linkedMapOf("rid" to "mem-abc-12345", "kind" to "memory",
            "ver" to linkedMapOf("dev" to "1".repeat(16), "n" to 1L), "base" to null, "at" to 1L, "del" to false,
            "data" to linkedMapOf("content" to "x", "created" to 1L))))
        for (bad in listOf("", null, "short", "123456789")) {
            raises<VaultError> { VaultCrypto.sealBackup(bad, "web", "7.2.0-dev.1", 1, recs, listOf("Personal memory"), iterations = 100_000) }
        }
        raises<VaultError> { VaultCrypto.sealBackup("a long passphrase", "mac", "7.2.0-dev.1", 1, recs, emptyList(), iterations = 100_000) }
        raises<VaultError> { VaultCrypto.sealBackup("a long passphrase", "web", "7|2", 1, recs, emptyList(), iterations = 100_000) }
        val data = VaultCrypto.sealBackup("a long passphrase", "web", "7.2.0-dev.1", 5, recs, listOf("Personal memory"), iterations = 100_000)
        val text = String(data, Charsets.UTF_8)
        assertFalse(text.contains("mem-abc") || text.contains("content"))
        assertEquals(recs, VaultCrypto.openBackup(data, "a long passphrase")["records"])
        val a = VaultCrypto.sealBackup("a long passphrase", "web", "7.2.0-dev.1", 5, recs, emptyList(), iterations = 100_000)
        val a2 = VaultCrypto.sealBackup("a long passphrase", "web", "7.2.0-dev.1", 5, recs, emptyList(), iterations = 100_000)
        assertFalse(a.contentEquals(a2))                            // fresh salt and IV every time
        assertEquals(null, VaultCrypto.backupPassphraseProblem("ten chars!"))
    }

    @Test fun realVaultAndOpsRoundTrip() {
        val rk = VaultCrypto.newRecoveryKey()
        val (doc, keys) = VaultCrypto.createVault(rk, 1L)
        val (doc2, keys2) = VaultCrypto.rotateVault(VJson.parse(VJson.write(doc)) as Map<String, Any?>, rk)
        assertArrayEquals(keys.getValue(1), VaultCrypto.unlockVault(VJson.parse(VJson.write(doc2)) as Map<String, Any?>, rk).getValue(1))
        val vid = s(doc2["vault_id"])
        val recs = listOf<Any?>(linkedMapOf("rid" to "mem-00000001", "kind" to "memory", "data" to linkedMapOf("content" to "नमस्ते ✈ \"q\"\n")))
        val f = VaultCrypto.sealOps(vid, 2, keys2.getValue(2), "c".repeat(16), 3, 9, recs)
        assertEquals(recs, VaultCrypto.openOps(f, keys2, vid)["records"])
        raises<Malformed> { VaultCrypto.openOps(f, keys2, "f".repeat(32)) }
    }

    @Test fun realRecoveryKeysAreRandomAndValid() {
        val keys = (1..50).map { VaultCrypto.newRecoveryKey() }.toSet()
        assertEquals(50, keys.size)
        for (k in keys) assertEquals(k, VaultCrypto.normalizeRecoveryKey(VaultCrypto.displayRecoveryKey(k)))
    }

    @Test fun decompressionBombIsRefused() {
        val before = VaultCrypto.maxPlain
        try {
            VaultCrypto.maxPlain = 1000
            raises<Malformed> { VaultCrypto.gunz(VaultCrypto.gz(ByteArray(5000) { '0'.code.toByte() })) }
            assertEquals(1000, VaultCrypto.gunz(VaultCrypto.gz(ByteArray(1000))).size)
        } finally {
            VaultCrypto.maxPlain = before
        }
        raises<Malformed> { VaultCrypto.gunz("not gzip".toByteArray()) }
    }

    // ------------------------------------------------------------------ records
    @Test fun dataHashMatchesPython() {
        // expected values from sync_core.data_hash in the Python reference
        val cases = listOf(
            """{"content": "नमस्ते — hello ✈", "created": 1763900000000, "category": "x"}""" to
                "6f620293f64f9ff7ae0b46e3d8bf0d060510a4e8b7353a829d238acf9f266c8a",
            """{"b": true, "a": false, "z": null, "q": "quote\" back\\ nl\n tab\t ctl\u0001 del\u007f 😀  ", "n": -5, "nested": {"y": [1, "two", {"k": 0}], "x": {}}}""" to
                "c405969727492646b9e03b0b347cff42d3cf5b572ead3d51f625abfff45bbc96",
            """{"😀": 1, "￿": 2, "a": 3, "é": 4}""" to "5b90b6848ca05cb36cf259e9e243c0b8ae84a5ab541efcc53618d415b2d28126",
            "{}" to "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
        )
        for ((json, want) in cases) assertEquals(json, want, SyncCore.dataHash(VJson.parse(json)))
    }

    @Test fun validateAndNaturalKeys() {
        val dev = "a".repeat(16)
        fun rec(rid: String, kind: String, data: Map<String, Any?>) =
            linkedMapOf<String, Any?>("rid" to rid, "kind" to kind, "ver" to linkedMapOf("dev" to dev, "n" to 1L), "base" to null, "data" to data)
        val ok = SyncCore.validateRecord(rec("mem-00000001", "memory", linkedMapOf("content" to "x", "created" to 1L, "extra" to 5L, "category" to null)))!!
        assertEquals(linkedMapOf("content" to "x", "created" to 1L), ok["data"])
        assertEquals(0L, ok["at"]); assertEquals(false, ok["del"])
        assertEquals(null, SyncCore.validateRecord(rec("mem-00000001", "memory", linkedMapOf("content" to "x", "created" to 1L, "pinned" to null))))
        assertEquals(null, SyncCore.validateRecord(rec("pref:theme", "pref", linkedMapOf("key" to "user_name", "value" to "x"))))
        assertEquals(null, SyncCore.validateRecord(rec(dev, "device", linkedMapOf("name" to "x", "platform" to "mac", "added" to 1L))))
        assertTrue(SyncCore.validateRecord(rec("b".repeat(16), "device", linkedMapOf("name" to "x", "revoked" to true, "revoked_after_seq" to 2L))) != null)
        assertEquals("m|lives in pune", SyncCore.naturalKey("memory", mapOf("content" to "  Lives in   PUNE ")))
        assertEquals("m|strasse", SyncCore.naturalKey("memory", mapOf("content" to "STRAßE")))
        assertEquals("g|c|user|5|hi", SyncCore.naturalKey("message", mapOf("conv" to "c", "role" to "user", "created" to 5L, "content" to "hi")))
    }

    // ------------------------------------------------------------------ in-memory doubles (port of the Python tests)
    class MemRemote : Remote {
        val files = sortedMapOf<String, ByteArray>()
        var failPuts = 0
        var loseReply = false
        override fun listNames(): List<String> = files.keys.toList()
        override fun get(name: String): ByteArray = files[name] ?: throw NoSuchElementException(name)
        override fun putNew(name: String, data: ByteArray) {
            if (failPuts > 0) { failPuts--; throw IOException("network down") }
            if (files.containsKey(name)) throw Exists(name)
            files[name] = data
            if (loseReply) { loseReply = false; throw IOException("connection reset") }
        }
    }

    class MemLocal : Local {
        var rows = LinkedHashMap<String, Item>()
        var meta = LinkedHashMap<String, Any?>()
        var failAddAfter: Int? = null

        private fun deep(v: Any?): Any? = when (v) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k as String] = deep(x) } }
            is List<*> -> v.mapTo(ArrayList()) { deep(it) }
            else -> v
        }
        private fun dd(d: Map<String, Any?>) = deep(d) as Map<String, Any?>

        override fun readItems(kinds: Collection<String>): Map<String, Item> {
            val out = LinkedHashMap<String, Item>()
            for ((r, it) in rows) if (it.first in kinds) out[r] = it.first to dd(it.second)
            return out
        }
        override fun add(kind: String, rid: String, data: Map<String, Any?>) {
            failAddAfter?.let { if (it == 0) throw RuntimeException("disk full"); failAddAfter = it - 1 }
            check(rid !in rows)
            rows[rid] = kind to dd(data)
        }
        override fun update(kind: String, rid: String, data: Map<String, Any?>) { check(rid in rows); rows[rid] = kind to dd(data) }
        override fun remove(kind: String, rid: String) { rows.remove(rid) ?: error("no row") }
        override fun getMeta(key: String): Any? = deep(meta[key])
        override fun putMeta(key: String, value: Any?) { meta[key] = deep(value) }
        override fun <T> transaction(block: () -> T): T {
            val snapRows = LinkedHashMap(rows.mapValues { it.value.first to dd(it.value.second) })
            val snapMeta = deep(meta) as LinkedHashMap<String, Any?>
            try {
                return block()
            } catch (e: Throwable) {
                rows = snapRows; meta = snapMeta
                throw e
            }
        }
    }

    inner class Dev(remote: MemRemote, hexchar: String, platform: String = "windows", private val clock: LongArray = longArrayOf(1763900000000L)) {
        val local = MemLocal().apply { putMeta("kinds", listOf("conversation", "message", "memory", "pref")) }
        val e = Engine(local, remote, hexchar.repeat(16), platform, "dev-$hexchar") { clock[0] += 1; clock[0] }
        fun sync(direction: String = "both", approve: List<String> = emptyList(), keep: List<String> = emptyList()) =
            e.apply(KEYS, VID, 1, direction, approve, keep)
        fun preview() = e.preview(KEYS, VID)
        fun mem(rid: String, content: String, created: Long = 1) { local.rows[rid] = "memory" to linkedMapOf("content" to content, "created" to created) }
        fun rows(kind: String): Map<String, Map<String, Any?>> = local.rows.filter { it.value.first == kind }.mapValues { it.value.second }
    }

    private fun world(): List<Any> {
        val remote = MemRemote(); val clock = longArrayOf(1763900000000L)
        return listOf(remote, Dev(remote, "a", clock = clock), Dev(remote, "b", "android", clock), Dev(remote, "c", "web", clock))
    }

    private fun cnt(r: Map<String, Any>, k: String) = (r[k] as Number).toLong()

    // ------------------------------------------------------------------ shared conformance cases
    @Test fun sharedSyncCases() {
        val cases = l(CASES["cases"]).map(::m)
        assertEquals(9, cases.size)
        val failures = mutableListOf<String>()
        for (case in cases) {
            try { runCase(case) } catch (e: Throwable) { failures += "${case["name"]}: ${e::class.java.simpleName}: ${e.message}" }
        }
        if (failures.isNotEmpty()) fail("sync cases failed:\n" + failures.joinToString("\n"))
    }

    private fun runCase(case: Map<String, Any?>) {
        val remote = MemRemote(); val clock = longArrayOf(1763900000000L)
        val devs = "abc".map { it.toString() }.associateWith { Dev(remote, it, clock = clock) }
        for ((i, st0) in l(case["steps"]).withIndex()) {
            val st = m(st0)
            val d = devs.getValue(s(st["dev"]))
            val where = "step $i: ${VJson.write(st)}"
            when (st["op"]) {
                "set" -> d.local.rows[s(st["rid"])] = s(st["kind"]) to m(st["data"])
                "del" -> d.local.rows.remove(s(st["rid"])) ?: error("$where: no row")
                "kinds" -> d.local.putMeta("kinds", st["kinds"])
                "sync" -> {
                    val dir = (st["direction"] as String?) ?: "both"
                    val approve = (st["approve"] as List<String>?) ?: emptyList()
                    val keep = (st["keep"] as List<String>?) ?: emptyList()
                    if (st.containsKey("error")) {
                        val e = raises<SyncError> { d.sync(dir, approve, keep) }
                        assertTrue(where, e.message!!.contains(s(st["error"])))
                        continue
                    }
                    val r = d.sync(dir, approve, keep)
                    for ((k, v) in m(st["expect"] ?: emptyMap<String, Any?>())) assertEquals("$where ($k)", (v as Number).toLong(), cnt(r, k))
                }
                "rows" -> assertEquals(where, st["rows"], d.rows(s(st["kind"])))
                "contents" -> assertEquals(where, st["values"], d.rows(s(st["kind"])).values.map { s(it["content"]) }.sorted())
                "conflicts" -> assertEquals(where, (st["n"] as Number).toInt(), d.e.conflicts().size)
                "resolve" -> d.e.resolve(s(d.e.conflicts()[(st["index"] as Number).toInt()]["id"]), s(st["choice"]))
                "restore" -> d.e.restoreDeleted(s(d.e.recentlyDeleted().last()["id"]))
                "deleted" -> assertEquals(where, st["data"], d.e.recentlyDeleted().map { it["data"] })
                "redownload" -> d.e.redownloadAll()
                "revoke" -> d.e.revoke(s(st["target"]).repeat(16))
                "pending_deletions" -> assertEquals(where, st["rids"], l(d.preview()["local_deletions"]).map { m(it)["rid"] })
                else -> error("unknown op ${st["op"]}")
            }
        }
    }

    // ------------------------------------------------------------------ engine scenarios (ports of the Python tests)
    @Test fun uploadIsEncryptedAndPreviewChangesNothing() {
        val (remote, a, b) = world().let { Triple(it[0] as MemRemote, it[1] as Dev, it[2] as Dev) }
        a.local.rows["conv-00000001"] = "conversation" to linkedMapOf("title" to "Trip", "created" to 5L)
        a.mem("mem-00000001", "Likes tea")
        assertEquals(2L, cnt(a.sync(), "uploaded"))
        assertEquals(listOf(VaultCrypto.opName("a".repeat(16), 1)), remote.files.keys.toList())
        for (f in remote.files.values) { val t = String(f, Charsets.UTF_8); assertFalse(t.contains("Likes tea") || t.contains("Trip")) }
        b.mem("mem-0000000b", "mine")
        val rowsBefore = LinkedHashMap(b.local.rows); val statesBefore = b.local.meta["states"]
        val p = b.preview()
        assertEquals(mapOf("memory" to 1), p["upload"])
        assertEquals(mapOf("conversation" to 1, "memory" to 1), m(p["incoming"])["add"])
        assertEquals(rowsBefore, b.local.rows); assertEquals(statesBefore, b.local.meta["states"]); assertEquals(null, b.local.meta["seen"])
        assertEquals(setOf("a".repeat(16)), b.sync().let { b.e.devices().keys })
    }

    @Test fun interruptedUploadResumesWithIdenticalBytes() {
        val (remote, a, b) = world().let { Triple(it[0] as MemRemote, it[1] as Dev, it[2] as Dev) }
        a.mem("mem-00000001", "x")
        remote.failPuts = 1
        raises<IOException> { a.sync() }
        assertTrue(remote.files.isEmpty())
        val outbox = l(a.local.meta["outbox"])
        assertEquals(1, outbox.size)
        val pending = m(outbox[0])["data"]
        a.mem("mem-00000002", "later")
        a.sync()
        assertEquals(listOf(VaultCrypto.opName("a".repeat(16), 1), VaultCrypto.opName("a".repeat(16), 2)), remote.files.keys.toList())
        assertEquals(pending, VaultCrypto.b64e(remote.files.getValue(VaultCrypto.opName("a".repeat(16), 1))))
        remote.loseReply = true
        a.mem("mem-00000003", "y")
        raises<IOException> { a.sync() }
        assertEquals(1, a.e.flushOutbox())                         // already on Drive: confirmed, not duplicated
        assertEquals(3, remote.files.size)
        assertEquals(3L, cnt(b.sync(), "added"))
    }

    @Test fun clonedIdentityIsDetectedNeverOverwritten() {
        val (remote, a) = world().let { (it[0] as MemRemote) to (it[1] as Dev) }
        val clone = Dev(remote, "a")
        a.mem("mem-00000001", "x"); a.sync()
        clone.mem("mem-00000009", "other")
        val before = remote.files.mapValues { it.value.copyOf() }
        val e = raises<SyncError> { clone.sync() }
        assertTrue(e.message!!.contains("sync identity"))
        assertEquals(before.keys, remote.files.keys)
        for ((k, v) in before) assertArrayEquals(v, remote.files[k])
    }

    @Test fun tamperedOrMissingFilesAreNotImported() {
        val (remote, a, b) = world().let { Triple(it[0] as MemRemote, it[1] as Dev, it[2] as Dev) }
        a.mem("mem-00000001", "one"); a.sync()
        a.mem("mem-00000002", "two"); a.sync()
        a.mem("mem-00000003", "three"); a.sync()
        val n2 = VaultCrypto.opName("a".repeat(16), 2)
        val good2 = remote.files.getValue(n2)
        remote.files[n2] = good2.copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 1).toByte() }
        val r = b.sync()
        assertEquals(setOf("mem-00000001"), b.rows("memory").keys)
        assertTrue((r["notes"] as List<*>).isNotEmpty())
        assertEquals(1L, b.e.seen()["a".repeat(16)])
        remote.files.remove(n2)
        b.sync()
        assertEquals(setOf("mem-00000001"), b.rows("memory").keys)   // a gap is never skipped
        remote.files[n2] = good2
        b.sync()
        assertEquals(setOf("mem-00000001", "mem-00000002", "mem-00000003"), b.rows("memory").keys)
    }

    @Test fun duplicateFileNameOnDriveDoesNotBlockTheDevice() {
        val (remote, a) = world().let { (it[0] as MemRemote) to (it[1] as Dev) }
        for (i in 1..3) { a.mem("mem-0000000$i", "$i"); a.sync() }
        val dup = object : Remote by remote {
            override fun listNames() = (remote.listNames() + VaultCrypto.opName("a".repeat(16), 1)).sorted()
        }
        val b = MemLocal().apply { putMeta("kinds", listOf("memory")) }
        var t = 1763900100000L
        Engine(b, dup, "b".repeat(16), "android", "dev-b") { ++t }.apply(KEYS, VID, 1)
        assertEquals(setOf("mem-00000001", "mem-00000002", "mem-00000003"), b.rows.filter { it.value.first == "memory" }.keys)
    }

    @Test fun damagedBodyStopsOnlyThatDevice() {
        val w = world(); val remote = w[0] as MemRemote; val b = w[2] as Dev; val c = w[3] as Dev
        val iv = ByteArray(12)
        val ct = VaultCrypto.seal(KEYS.getValue(1), iv, VaultCrypto.gz("[1, 2, 3]".toByteArray()), "abhify-sync-ops|1|$VID|1|${"a".repeat(16)}|1|5")
        remote.files[VaultCrypto.opName("a".repeat(16), 1)] = VJson.bytes(linkedMapOf("format" to "abhify-sync-ops", "v" to 1L, "vault_id" to VID,
            "epoch" to 1L, "device" to "a".repeat(16), "seq" to 1L, "created" to 5L, "iv" to VaultCrypto.b64e(iv), "ct" to VaultCrypto.b64e(ct)))
        c.mem("mem-0000000c", "from c"); c.sync()
        val r = b.sync()
        assertTrue((r["notes"] as List<*>).isNotEmpty())
        assertTrue("mem-0000000c" in b.rows("memory"))
    }

    @Test fun invalidRecordsAreRefused() {
        val (remote, b) = world().let { (it[0] as MemRemote) to (it[2] as Dev) }
        val dev = "a".repeat(16)
        fun ver() = linkedMapOf<String, Any?>("dev" to dev, "n" to 1L)
        val bad = listOf<Any?>(
            linkedMapOf("rid" to "x", "kind" to "memory", "ver" to ver(), "base" to null, "data" to linkedMapOf("content" to "short rid", "created" to 1L)),
            linkedMapOf("rid" to "mem-00000001", "kind" to "secrets", "ver" to ver(), "base" to null, "data" to linkedMapOf<String, Any?>()),
            linkedMapOf("rid" to "mem-00000002", "kind" to "memory", "ver" to ver(), "base" to null, "data" to linkedMapOf("content" to 5L, "created" to 1L)),
            linkedMapOf("rid" to "pref:api_key", "kind" to "pref", "ver" to ver(), "base" to null, "data" to linkedMapOf("key" to "api_key", "value" to "sk-1")),
            linkedMapOf("rid" to "msg-00000001", "kind" to "message", "ver" to ver(), "base" to null,
                "data" to linkedMapOf("conv" to "c".repeat(10), "role" to "system", "content" to "x", "created" to 1L)),
        )
        remote.files[VaultCrypto.opName(dev, 1)] = VaultCrypto.sealOps(VID, 1, KEYS.getValue(1), dev, 1, 1, bad)
        val r = b.sync()
        assertEquals(5L, cnt(r, "invalid"))
        assertTrue(b.local.rows.isEmpty())
    }

    @Test fun revokedDeviceIsIgnoredAndTold() {
        val w = world(); val remote = w[0] as MemRemote; val a = w[1] as Dev; val b = w[2] as Dev; val c = w[3] as Dev
        c.mem("mem-0000000c", "from c"); c.sync(); a.sync(); b.sync()
        a.e.revoke("c".repeat(16)); a.sync()
        c.mem("mem-000000c2", "after revoke")
        val before = remote.files.size
        assertTrue(raises<SyncError> { c.sync() }.message!!.contains("removed"))
        assertEquals(before, remote.files.size)
        remote.files[VaultCrypto.opName("c".repeat(16), 2)] = VaultCrypto.sealOps(VID, 1, KEYS.getValue(1), "c".repeat(16), 2, 9, listOf<Any?>(
            linkedMapOf("rid" to "mem-000000c3", "kind" to "memory", "ver" to linkedMapOf("dev" to "c".repeat(16), "n" to 1L), "base" to null,
                "at" to 9L, "del" to false, "data" to linkedMapOf("content" to "evil", "created" to 1L))))
        b.sync()
        assertTrue("mem-0000000c" in b.rows("memory") && "mem-000000c3" !in b.rows("memory"))
        assertEquals(true, b.e.devices().getValue("c".repeat(16))["revoked"])
        raises<SyncError> { a.e.revoke("a".repeat(16)) }
    }

    @Test fun failedImportChangesNothing() {
        val (a, b) = world().let { (it[1] as Dev) to (it[2] as Dev) }
        a.mem("mem-00000001", "1"); a.mem("mem-00000002", "2"); a.sync()
        b.local.failAddAfter = 1
        raises<RuntimeException> { b.sync() }
        assertTrue(b.local.rows.isEmpty())
        assertTrue(b.local.meta["seen"].let { it == null || (it as Map<*, *>).isEmpty() })
        b.local.failAddAfter = null
        assertEquals(2L, cnt(b.sync(), "added"))
    }

    @Test fun downloadOnlyAndUploadOnlyAndKeepElsewhere() {
        val (remote, a, b) = world().let { Triple(it[0] as MemRemote, it[1] as Dev, it[2] as Dev) }
        a.mem("mem-00000001", "x"); a.sync()
        b.mem("mem-0000000b", "mine")
        assertEquals(1L, cnt(b.sync(direction = "download"), "added"))
        assertFalse(remote.files.keys.any { it.startsWith("op-" + "b".repeat(16)) })
        b.sync(direction = "upload")
        assertTrue(remote.files.keys.any { it.startsWith("op-" + "b".repeat(16)) })
        a.local.rows.remove("mem-00000001")
        a.sync(keep = listOf("mem-00000001"))
        assertEquals(emptyList<Any?>(), a.preview()["local_deletions"])
        b.sync()
        assertTrue("mem-00000001" in b.rows("memory"))
        raises<IllegalArgumentException> { a.sync(direction = "sideways") }
    }

    @Test fun conflictKeepMineConvergesAndHistoryIsKept() {
        val (a, b) = world().let { (it[1] as Dev) to (it[2] as Dev) }
        a.mem("mem-00000001", "v1"); a.sync(); b.sync()
        a.mem("mem-00000001", "A edit"); b.mem("mem-00000001", "B edit")
        a.sync()
        assertEquals(1L, cnt(b.sync(), "conflicts"))
        assertEquals(1, b.preview()["conflicts_waiting"])
        val c = b.e.conflicts()[0]
        assertEquals("B edit", m(c["local"])["content"]); assertEquals("A edit", m(m(c["remote"])["data"])["content"])
        b.e.resolve(s(c["id"]), "keep_mine"); b.sync(); a.sync()
        assertEquals("B edit", a.rows("memory").getValue("mem-00000001")["content"])
        assertTrue(a.e.conflicts().isEmpty())
        raises<SyncError> { b.e.resolve(s(c["id"]), "keep_mine") }
        assertTrue(l(b.local.meta["history"]).isNotEmpty())
        assertNotEquals(0, l(a.local.meta["history"]).size)
    }
}
