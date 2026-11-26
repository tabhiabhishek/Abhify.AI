package ai.abhify.android.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abhify.AI vault, sync-op and backup formats, version 1 (7.2.0-dev.1). Kotlin port of backend/vault_crypto.py; normative
 * spec: docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md. Only platform primitives are used (JCA AES/GCM/NoPadding and
 * PBKDF2WithHmacSHA256); nothing here implements a cipher. Keys, Recovery Keys and passphrases are never logged, returned in
 * errors or written anywhere by this file. Deterministic given the random source, so the shared vectors can be reproduced.
 *
 * JSON values are plain Kotlin trees: Map<String, Any?> (insertion ordered), List<Any?>, String, Long, Double, Boolean, null.
 */
typealias RandomBytes = (Int) -> ByteArray

/** Base error. Messages are user-facing and never contain key material. */
open class VaultError(message: String) : Exception(message)
class WrongKey(message: String) : VaultError(message)
class Malformed(message: String) : VaultError(message)
class Unsupported(message: String) : VaultError(message)
class RecoveryKeyTypo(message: String) : VaultError(message)

/** Raised where the Python reference raises a non-VaultError (TypeError, ValueError, KeyError, JSONDecodeError...). */
class FormatFault(message: String) : IllegalArgumentException(message)

object VaultCrypto {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // Crockford base32
    const val ITER_PASSPHRASE = 600_000L
    const val ITER_RECOVERY = 100_000L
    const val MIN_ITER = 100_000L
    const val MAX_ITER = 5_000_000L
    const val MAX_PLAIN = 256L * 1024 * 1024
    const val MIN_BACKUP_PASSPHRASE = 10
    const val VAULT_FORMAT = "abhify-vault"
    const val OPS_FORMAT = "abhify-sync-ops"
    const val BACKUP_FORMAT = "abhify-vault-backup"

    /** Decompression cap; a var only so tests can lower it (the bomb test). */
    @Volatile internal var maxPlain: Long = MAX_PLAIN

    private val rng = SecureRandom()
    val secureRandom: RandomBytes = { n -> ByteArray(n).also { rng.nextBytes(it) } }

    // ------------------------------------------------------------------ encoding helpers
    fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    fun b64d(s: Any?, what: String = "value"): ByteArray {
        if (s !is String) throw Malformed("The file is damaged ($what missing).")
        // Python: b64decode(validate=True): alphabet + up to two '=' only, and correct padding.
        if (!Regex("[A-Za-z0-9+/]*={0,2}").matches(s) || s.length % 4 != 0) throw Malformed("The file is damaged ($what unreadable).")
        return try { Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { throw Malformed("The file is damaged ($what unreadable).") }
    }

    /** Crockford base32, MSB first, final bits zero-padded; optionally truncated to [chars] characters. */
    fun b32c(data: ByteArray, chars: Int? = null): String {
        val nbits = data.size * 8
        val n = (nbits + 4) / 5
        val bits = (if (data.isEmpty()) BigInteger.ZERO else BigInteger(1, data)).shiftLeft(n * 5 - nbits)
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(ALPHABET[bits.shiftRight(5 * (n - 1 - i)).toInt() and 31])
        val out = sb.toString()
        return when {
            chars == null || chars == 0 -> out
            chars > 0 -> out.take(chars)
            else -> out.dropLast(-chars)
        }
    }

    private fun b32cDecode(s: String, nbytes: Int): ByteArray {
        var v = BigInteger.ZERO
        for (ch in s) v = v.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(ch).toLong()))
        val extra = s.length * 5 - nbytes * 8
        val raw = v.shiftRight(extra).toByteArray()
        val out = ByteArray(nbytes)
        val take = minOf(raw.size, nbytes)
        System.arraycopy(raw, raw.size - take, out, nbytes - take, take)
        return out
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ primitives
    fun kdf(password: String, salt: ByteArray, iterations: Long): ByteArray {
        if (iterations < MIN_ITER || iterations > MAX_ITER) throw Unsupported("The file uses key settings this app doesn't accept.")
        if (salt.size < 16) throw Malformed("The file is damaged (salt).")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations.toInt(), 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** kdf for an iteration count read from a file: it must be an integer (not a bool or a string) within bounds. */
    private fun kdfAny(password: String, salt: ByteArray, iterations: Any?): ByteArray {
        val it = Py.strictInt(iterations)
        if (it == null || it < BigInteger.valueOf(MIN_ITER) || it > BigInteger.valueOf(MAX_ITER)) {
            throw Unsupported("The file uses key settings this app doesn't accept.")
        }
        return kdf(password, salt, it.toLong())
    }

    fun seal(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(plain)
    }

    /** AEAD open (Python `open_`); a failed tag check becomes WrongKey([wrong]). */
    fun unseal(key: ByteArray, iv: ByteArray, ct: ByteArray, aad: String, wrong: String): ByteArray {
        if (iv.size != 12 || ct.size < 16) throw Malformed("The file is damaged (encryption header).")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return try { c.doFinal(ct) } catch (e: AEADBadTagException) { throw WrongKey(wrong) }
    }

    fun gz(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        val out = bos.toByteArray()
        out[9] = 255.toByte()                 // OS byte "unknown": the same file on every operating system
        return out
    }

    fun gunz(data: ByteArray): ByteArray {
        val cap = maxPlain
        val out = try {
            GZIPInputStream(ByteArrayInputStream(data)).use { inp ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val limit = cap + 1
                while (total < limit) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
                bos.toByteArray()
            }
        } catch (e: Exception) {
            throw Malformed("The file is damaged (content unreadable).")
        }
        if (out.size > cap) throw Malformed("The file is too large to open.")
        return out
    }

    private fun json(o: Any?): ByteArray = VJson.bytes(o)

    /** A decrypted op/backup body: must be a JSON object. */
    private fun body(plain: ByteArray): Map<String, Any?> {
        val o = try { VJson.parseBytes(plain) } catch (e: Exception) {
            throw Malformed("The file's content is damaged; it was not imported.")
        } catch (e: StackOverflowError) {
            throw Malformed("The file's content is damaged; it was not imported.")
        }
        @Suppress("UNCHECKED_CAST")
        return o as? Map<String, Any?> ?: throw Malformed("The file's content is damaged; it was not imported.")
    }

    // ------------------------------------------------------------------ Recovery Key
    fun newRecoveryKey(rnd: RandomBytes = secureRandom): String {
        val raw = rnd(20)
        return b32c(raw) + b32c(sha256(raw).copyOf(3), 4)
    }

    /** Canonical 36-character form, or RecoveryKeyTypo (checked locally, before any network call). */
    fun normalizeRecoveryKey(text: String?): String {
        val up = (text ?: "").uppercase(Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < up.length) {
            val cp = up.codePointAt(i)
            i += Character.charCount(cp)
            if (Py.isSpace(cp) || cp == '-'.code || cp == '_'.code) continue
            sb.appendCodePoint(cp)
        }
        val s = sb.toString().replace('O', '0').replace('I', '1').replace('L', '1')
        if (Py.len(s) != 36 || s.any { it !in ALPHABET }) {
            throw RecoveryKeyTypo("That doesn't look like a Recovery Key (36 letters and digits). Check it and try again.")
        }
        val raw = b32cDecode(s.substring(0, 32), 20)
        if (b32c(sha256(raw).copyOf(3), 4) != s.substring(32)) {
            throw RecoveryKeyTypo("That Recovery Key has a typo (its check doesn't match). Check each group and try again.")
        }
        return s
    }

    fun displayRecoveryKey(canonical: String): String =
        (0 until 36 step 4).joinToString("-") { i -> if (i >= canonical.length) "" else canonical.substring(i, minOf(i + 4, canonical.length)) }

    fun qrPayload(canonical: String): String = "abhify-rk:1:$canonical"

    fun fromQr(payload: String?): String {
        val m = Regex("abhify-rk:1:([0-9A-Z]{36})").matchEntire(Py.strip(payload ?: ""))
            ?: throw RecoveryKeyTypo("That QR code isn't an Abhify.AI Recovery Key.")
        return normalizeRecoveryKey(m.groupValues[1])
    }

    // ------------------------------------------------------------------ vault.json
    private fun wrapAad(vaultId: String, epoch: String, iterations: String, saltB64: String) =
        "abhify-vault|1|$vaultId|$epoch|$iterations|$saltB64"

    private fun chainAad(vaultId: String, oldEpoch: String, epoch: String) = "abhify-vault-chain|1|$vaultId|$oldEpoch|$epoch"

    /** A new vault: (vault.json document, {epoch: vault key}). The keys stay on this device only. */
    fun createVault(recoveryKey: String, nowMs: Long, rnd: RandomBytes = secureRandom): Pair<Map<String, Any?>, MutableMap<Int, ByteArray>> {
        val rk = normalizeRecoveryKey(recoveryKey)
        val vaultId = hex(rnd(16))
        val vk = rnd(32)
        val doc = wrap(vaultId, nowMs, 1, vk, rk, emptyList(), rnd)
        return doc to linkedMapOf(1 to vk)
    }

    private fun wrap(vaultId: String, created: Long, epoch: Int, vk: ByteArray, rk: String, old: List<Pair<Int, ByteArray>>, rnd: RandomBytes): Map<String, Any?> {
        val salt = rnd(16)
        val saltB64 = b64e(salt)
        val kek = kdf(rk, salt, ITER_RECOVERY)
        val iv = rnd(12)
        val chain = ArrayList<Any?>()
        for ((oldEpoch, oldKey) in old.sortedBy { it.first }) {
            val civ = rnd(12)
            chain.add(linkedMapOf<String, Any?>("epoch" to oldEpoch.toLong(), "iv" to b64e(civ),
                "ct" to b64e(seal(vk, civ, oldKey, chainAad(vaultId, oldEpoch.toString(), epoch.toString())))))
        }
        return linkedMapOf(
            "format" to VAULT_FORMAT, "v" to 1L, "vault_id" to vaultId, "created" to created, "epoch" to epoch.toLong(),
            "kdf" to linkedMapOf<String, Any?>("alg" to "PBKDF2-SHA256", "iter" to ITER_RECOVERY, "salt" to saltB64),
            "wrap" to linkedMapOf<String, Any?>("iv" to b64e(iv),
                "ct" to b64e(seal(kek, iv, vk, wrapAad(vaultId, epoch.toString(), ITER_RECOVERY.toString(), saltB64)))),
            "chain" to chain,
        )
    }

    fun checkVaultDoc(doc: Any?): Map<String, Any?> {
        if (doc !is Map<*, *> || doc["format"] != VAULT_FORMAT) throw Malformed("This isn't an Abhify.AI vault file.")
        @Suppress("UNCHECKED_CAST") val d = doc as Map<String, Any?>
        if (!Py.eq(d["v"], 1L)) throw Unsupported("This vault was made by a newer Abhify.AI. Update the app first.")
        val epoch = Py.bigInt(d["epoch"])
        if (!Regex("[0-9a-f]{32}").matches(Py.str(Py.get(d, "vault_id", ""))) || epoch == null || epoch < BigInteger.ONE) {
            throw Malformed("The vault file is damaged.")
        }
        val kv = d["kdf"].let { if (Py.falsy(it)) emptyMap<String, Any?>() else it }
        if (kv !is Map<*, *> || kv["alg"] != "PBKDF2-SHA256" || Py.strictInt(kv["iter"]) == null || kv["salt"] !is String) {
            throw Unsupported("The vault uses key settings this app doesn't accept.")
        }
        if (!Py.isInt(d["created"]) || d["wrap"] !is Map<*, *> || Py.get(d, "chain", emptyList<Any?>()) !is List<*>) {
            throw Malformed("The vault file is damaged.")
        }
        return d
    }

    private fun epochKey(v: Any?): Int {
        val b = Py.bigInt(v) ?: Py.intArg(v)
        return if (b.bitLength() < 32) b.toInt() else throw FormatFault("epoch out of range")
    }

    /** All epoch keys {epoch: key} from vault.json and the Recovery Key. WrongKey if it doesn't open. */
    fun unlockVault(doc: Map<String, Any?>, recoveryKey: String): MutableMap<Int, ByteArray> {
        checkVaultDoc(doc)
        val rk = normalizeRecoveryKey(recoveryKey)
        val k = Py.orDict(doc["kdf"])
        val kek = kdfAny(rk, b64d(k["salt"], "salt"), Py.get(k, "iter", 0L))
        val w = Py.orDict(doc["wrap"])
        val vid = Py.str(doc["vault_id"])
        val ep = Py.str(doc["epoch"])
        val vk = unseal(kek, b64d(w["iv"], "iv"), b64d(w["ct"], "key"), wrapAad(vid, ep, Py.str(k["iter"]), Py.str(k["salt"])),
            "That Recovery Key doesn't open this vault.")
        val keys = linkedMapOf(epochKey(doc["epoch"]) to vk)
        for (c in Py.orList(doc["chain"])) {
            if (c !is Map<*, *>) throw FormatFault("chain entry")
            val iv = b64d(c["iv"], "iv")
            val ct = b64d(c["ct"], "key")
            if (!c.containsKey("epoch")) throw FormatFault("chain epoch")
            val oe = Py.intArg(c["epoch"])
            val key = unseal(vk, iv, ct, chainAad(vid, oe.toString(), ep), "The vault file is damaged.")
            keys[epochKey(oe)] = key
        }
        return keys
    }

    /** New epoch (after revoking a device): new key, rewrapped; older keys chained under the new key. */
    fun rotateVault(doc: Map<String, Any?>, recoveryKey: String, rnd: RandomBytes = secureRandom): Pair<Map<String, Any?>, MutableMap<Int, ByteArray>> {
        val keys = unlockVault(doc, recoveryKey)
        val epoch = epochKey(doc["epoch"]) + 1
        val vk = rnd(32)
        val new = wrap(Py.str(doc["vault_id"]), Py.intArg(doc["created"]).toLong(), epoch, vk, normalizeRecoveryKey(recoveryKey),
            keys.entries.map { it.key to it.value }, rnd)
        keys[epoch] = vk
        return new to keys
    }

    // ------------------------------------------------------------------ op files
    fun opName(device: String, seq: Long): String = "op-$device-${String.format(Locale.ROOT, "%08d", seq)}.abx"

    private val OP_NAME = Regex("op-([0-9a-f]{16})-([0-9]{8})\\.abx")

    fun parseOpName(name: String?): Pair<String, Long>? {
        val m = OP_NAME.matchEntire(name ?: "") ?: return null
        return m.groupValues[1] to m.groupValues[2].toLong()
    }

    private fun opsAad(vaultId: String, epoch: String, device: String, seq: String, created: String) =
        "abhify-sync-ops|1|$vaultId|$epoch|$device|$seq|$created"

    fun sealOps(vaultId: String, epoch: Int, key: ByteArray, device: String, seq: Long, created: Long, records: List<Any?>,
                rnd: RandomBytes = secureRandom): ByteArray {
        val iv = rnd(12)
        val body = gz(json(linkedMapOf<String, Any?>("device" to device, "seq" to seq, "records" to records)))
        val ct = seal(key, iv, body, opsAad(vaultId, epoch.toString(), device, seq.toString(), created.toString()))
        return json(linkedMapOf<String, Any?>("format" to OPS_FORMAT, "v" to 1L, "vault_id" to vaultId, "epoch" to epoch.toLong(),
            "device" to device, "seq" to seq, "created" to created, "iv" to b64e(iv), "ct" to b64e(ct)))
    }

    fun opHeader(data: ByteArray): Map<String, Any?> {
        val h = try { VJson.parseBytes(data) } catch (e: Exception) { throw Malformed("A sync file is damaged.") } catch (e: StackOverflowError) {
            throw Malformed("A sync file is damaged.")
        }
        if (h !is Map<*, *> || h["format"] != OPS_FORMAT) throw Malformed("A sync file is damaged.")
        @Suppress("UNCHECKED_CAST") val m = h as Map<String, Any?>
        if (!Py.eq(m["v"], 1L)) throw Unsupported("A sync file was made by a newer Abhify.AI. Update this app first.")
        if (!(Py.isInt(m["epoch"]) && Py.isInt(m["seq"]) && Py.isInt(m["created"]) &&
                Regex("[0-9a-f]{16}").matches(Py.str(Py.get(m, "device", ""))) && Regex("[0-9a-f]{32}").matches(Py.str(Py.get(m, "vault_id", ""))))) {
            throw Malformed("A sync file is damaged.")
        }
        return m
    }

    /** {device, seq, epoch, created, records} after verifying the tag (header included via AAD). */
    fun openOps(data: ByteArray, keys: Map<Int, ByteArray>, vaultId: String): Map<String, Any?> {
        val h = opHeader(data)
        if (!Py.eq(h["vault_id"], vaultId)) throw Malformed("A sync file belongs to another vault.")
        val eb = Py.bigInt(h["epoch"])!!
        val key = (if (eb.bitLength() < 32) keys[eb.toInt()] else null)
            ?: throw WrongKey("A sync file uses a newer vault key. Enter your Recovery Key to continue.")
        val iv = b64d(h["iv"], "iv")
        val ct = b64d(h["ct"], "content")
        val plain = gunz(unseal(key, iv, ct, opsAad(Py.str(h["vault_id"]), Py.str(h["epoch"]), Py.str(h["device"]), Py.str(h["seq"]), Py.str(h["created"])),
            "A sync file was changed or damaged; it was not imported."))
        val body = body(plain)
        if (!Py.eq(body["device"], h["device"]) || !Py.eq(body["seq"], h["seq"]) || body["records"] !is List<*>) {
            throw Malformed("A sync file is inconsistent; it was not imported.")
        }
        return linkedMapOf("device" to h["device"], "seq" to h["seq"], "epoch" to h["epoch"], "created" to h["created"], "records" to body["records"])
    }

    // ------------------------------------------------------------------ backup v2
    private fun backupAad(platform: String, appVersion: String, created: String, iterations: String, saltB64: String) =
        "abhify-vault-backup|2|$platform|$appVersion|$created|$iterations|$saltB64"

    fun backupPassphraseProblem(passphrase: String?): String? {
        if (passphrase.isNullOrEmpty() || Py.len(passphrase) < MIN_BACKUP_PASSPHRASE) {
            return "Use a passphrase of at least $MIN_BACKUP_PASSPHRASE characters (or your Recovery Key)."
        }
        return null
    }

    fun sealBackup(passphrase: String?, platform: String, appVersion: String, created: Long, records: Map<String, List<Any?>>, categories: List<String>,
                   rnd: RandomBytes = secureRandom, iterations: Long = ITER_PASSPHRASE): ByteArray {
        val problem = backupPassphraseProblem(passphrase)
        if (problem != null) throw VaultError(problem)
        if (platform !in listOf("windows", "android", "web") || "|" in appVersion) throw VaultError("Invalid backup header.")
        val salt = rnd(16)
        val saltB64 = b64e(salt)
        val iv = rnd(12)
        val counts = LinkedHashMap<String, Any?>()
        for ((k, v) in records) counts[k] = v.size.toLong()
        val manifest = linkedMapOf<String, Any?>("categories" to categories.toList(), "counts" to counts,
            "platform" to platform, "app_version" to appVersion, "created" to created)
        val ct = seal(kdf(passphrase!!, salt, iterations), iv, gz(json(linkedMapOf<String, Any?>("manifest" to manifest, "records" to records))),
            backupAad(platform, appVersion, created.toString(), iterations.toString(), saltB64))
        return json(linkedMapOf<String, Any?>("format" to BACKUP_FORMAT, "v" to 2L, "platform" to platform, "app_version" to appVersion, "created" to created,
            "kdf" to linkedMapOf<String, Any?>("alg" to "PBKDF2-SHA256", "iter" to iterations, "salt" to saltB64), "iv" to b64e(iv), "ct" to b64e(ct)))
    }

    fun backupHeader(data: ByteArray): Map<String, Any?> {
        val h = try { VJson.parseBytes(data) } catch (e: Exception) { throw Malformed("This isn't an Abhify.AI backup file.") } catch (e: StackOverflowError) {
            throw Malformed("This isn't an Abhify.AI backup file.")
        }
        if (h !is Map<*, *> || h["format"] != BACKUP_FORMAT) throw Malformed("This isn't an Abhify.AI encrypted backup (v2).")
        @Suppress("UNCHECKED_CAST") val m = h as Map<String, Any?>
        if (!Py.eq(m["v"], 2L)) throw Unsupported("This backup was made by a newer Abhify.AI. Update the app first.")
        val k = m["kdf"].let { if (Py.falsy(it)) emptyMap<String, Any?>() else it }
        if (k !is Map<*, *> || k["alg"] != "PBKDF2-SHA256" || Py.strictInt(k["iter"]) == null || Py.strictInt(m["created"]) == null || k["salt"] !is String) {
            throw Unsupported("This backup uses settings this app doesn't accept.")
        }
        return m
    }

    /** {header, manifest, records} after the tag check; counts must match. Nothing is imported here. */
    fun openBackup(data: ByteArray, passphrase: String?): Map<String, Any?> {
        val h = backupHeader(data)
        val k = Py.orDict(h["kdf"])
        val key = kdfAny(passphrase ?: "", b64d(k["salt"], "salt"), k["iter"])
        val iv = b64d(h["iv"], "iv")
        val ct = b64d(h["ct"], "content")
        val plain = gunz(unseal(key, iv, ct,
            backupAad(Py.str(h["platform"]), Py.str(h["app_version"]), Py.str(h["created"]), Py.str(k["iter"]), Py.str(k["salt"])),
            "Wrong passphrase, or the file was changed."))
        val body = body(plain)
        val manifest = body["manifest"].let { if (Py.falsy(it)) emptyMap<String, Any?>() else it }
        val records = body["records"].let { if (Py.falsy(it)) emptyMap<String, Any?>() else it }
        if (manifest !is Map<*, *> || records !is Map<*, *> || records.values.any { it !is List<*> }) throw Malformed("The backup content is damaged.")
        val counts = manifest["counts"].let { if (Py.falsy(it)) emptyMap<String, Any?>() else it }
        if (counts !is Map<*, *>) throw Malformed("The backup content is damaged.")
        for ((kind, n) in counts) {
            val have = (records[kind] as? List<*>)?.size ?: 0
            if (!Py.eq(have.toLong(), n)) throw Malformed("The backup content is incomplete (counts don't match).")
        }
        return linkedMapOf(
            "header" to linkedMapOf("platform" to h["platform"], "app_version" to h["app_version"], "created" to h["created"]),
            "manifest" to manifest, "records" to records,
        )
    }
}

/** Strict JSON (like Python's json module defaults) over plain Kotlin trees; output compact, insertion order or sorted keys. */
object VJson {
    class ParseError(message: String) : IllegalArgumentException(message)

    fun decodeUtf8(b: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(b)).toString() } catch (e: CharacterCodingException) { throw ParseError("not UTF-8") }
    }

    fun parseBytes(b: ByteArray): Any? = parse(decodeUtf8(b))

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw ParseError("extra data at ${p.i}")
        return v
    }

    /** Python json.dumps(o, ensure_ascii=False, separators=(",", ":"), sort_keys=[sortKeys]). */
    fun write(v: Any?, sortKeys: Boolean = false): String = StringBuilder().also { w(it, v, sortKeys) }.toString()

    fun bytes(v: Any?, sortKeys: Boolean = false): ByteArray = write(v, sortKeys).toByteArray(Charsets.UTF_8)

    private fun w(sb: StringBuilder, v: Any?, sort: Boolean) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is String -> str(sb, v)
            is Long, is Int, is Short, is Byte, is BigInteger -> sb.append(v.toString())
            is Double -> sb.append(Py.floatRepr(v))
            is Float -> sb.append(Py.floatRepr(v.toDouble()))
            is Map<*, *> -> {
                sb.append('{')
                val keys = v.keys.map { it as String }.let { if (sort) it.sortedWith(Py.cpOrder) else it }
                keys.forEachIndexed { i, k -> if (i > 0) sb.append(','); str(sb, k); sb.append(':'); w(sb, v[k], sort) }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); w(sb, x, sort) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("not JSON-serializable: ${v::class.java.simpleName}")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        fun fail(): Nothing = throw ParseError("invalid JSON at $i")

        fun value(): Any? {
            if (i >= s.length) fail()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", i)) lit("-Infinity", Double.NEGATIVE_INFINITY) else num()
            }
        }

        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) fail(); i += word.length; return v }

        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail() }
            }
        }

        fun arr(): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (i >= s.length) fail()
                when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> fail() }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                val h = s.substring(i, i + 4)
                                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                                sb.append(h.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i].isAsciiDigit()) i++ } else fail()
            var float = false
            if (i + 1 < s.length && s[i] == '.' && s[i + 1].isAsciiDigit()) {
                float = true; i++
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isAsciiDigit()) {
                    float = true
                    i = j
                    while (i < s.length && s[i].isAsciiDigit()) i++
                }
            }
            val t = s.substring(start, i)
            if (float) return t.toDouble()
            return t.toLongOrNull() ?: BigInteger(t)
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/** Small helpers reproducing the Python reference's value semantics where they matter for identical behaviour. */
internal object Py {
    private val SPACES = setOf(0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)

    /** Python str.isspace() for one code point. */
    fun isSpace(cp: Int) = cp in SPACES

    fun len(s: String) = s.codePointCount(0, s.length)

    fun strip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isSpace(s[a].code)) a++
        while (b > a && isSpace(s[b - 1].code)) b--
        return s.substring(a, b)
    }

    /** " ".join(s.split()) */
    fun squash(s: String): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        var i = 0
        fun flush() { if (cur.isNotEmpty()) { if (out.isNotEmpty()) out.append(' '); out.append(cur); cur.setLength(0) } }
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isSpace(cp)) flush() else cur.appendCodePoint(cp)
        }
        flush()
        return out.toString()
    }

    /** Approximation of str.casefold() (exact for ASCII, Latin, Greek, Cyrillic, ß, ligatures). */
    fun casefold(s: String) = s.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    val cpOrder = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    /** An int that is not a bool (the reference's _is_int), else null. */
    fun strictInt(v: Any?): BigInteger? = if (v is Boolean) null else bigInt(v)

    /** isinstance(v, int) (bool included, as in Python). */
    fun isInt(v: Any?) = bigInt(v) != null

    fun bigInt(v: Any?): BigInteger? = when (v) {
        is Boolean -> if (v) BigInteger.ONE else BigInteger.ZERO
        is Long -> BigInteger.valueOf(v)
        is Int -> BigInteger.valueOf(v.toLong())
        is Short -> BigInteger.valueOf(v.toLong())
        is Byte -> BigInteger.valueOf(v.toLong())
        is BigInteger -> v
        else -> null
    }

    /** Python int(v) for values read from a file. */
    fun intArg(v: Any?): BigInteger {
        bigInt(v)?.let { return it }
        return when (v) {
            is Double -> if (v.isNaN() || v.isInfinite()) throw FormatFault("int()") else BigDecimal(v).toBigInteger()
            is String -> {
                val t = strip(v).replace(Regex("(?<=[0-9])_(?=[0-9])"), "")
                if (!Regex("[+-]?[0-9]+").matches(t)) throw FormatFault("int()")
                BigInteger(t.removePrefix("+"))
            }
            else -> throw FormatFault("int()")
        }
    }

    private fun num(v: Any?): BigDecimal? = bigInt(v)?.let { BigDecimal(it) } ?: when (v) {
        is Double -> if (v.isNaN() || v.isInfinite()) null else BigDecimal(v)
        is Float -> num(v.toDouble())
        else -> null
    }

    /** Python ==, with numeric equality across bool/int/float. */
    fun eq(a: Any?, b: Any?): Boolean {
        val x = num(a)
        val y = num(b)
        if (x != null && y != null) return x.compareTo(y) == 0
        if ((a is Number || a is Boolean) && (b is Number || b is Boolean)) return false   // NaN/inf vs number
        return a == b
    }

    /** Python a > b for numbers; TypeError otherwise. */
    fun gt(a: Any?, b: Any?): Boolean {
        val x = num(a) ?: throw FormatFault("comparison")
        val y = num(b) ?: throw FormatFault("comparison")
        return x > y
    }

    fun falsy(v: Any?): Boolean = when (v) {
        null -> true
        is Boolean -> !v
        is String -> v.isEmpty()
        is Map<*, *> -> v.isEmpty()
        is List<*> -> v.isEmpty()
        is Double -> v == 0.0
        else -> bigInt(v)?.signum() == 0
    }

    fun truthy(v: Any?) = !falsy(v)

    /** `v or {}` followed by dict access. */
    @Suppress("UNCHECKED_CAST")
    fun orDict(v: Any?): Map<String, Any?> = when {
        v is Map<*, *> -> v as Map<String, Any?>
        falsy(v) -> emptyMap()
        else -> throw FormatFault("expected an object")
    }

    fun orList(v: Any?): List<Any?> = when {
        v is List<*> -> v
        falsy(v) -> emptyList()
        else -> throw FormatFault("expected a list")
    }

    /** dict.get(key, default) */
    fun get(m: Map<String, Any?>, key: String, default: Any?): Any? = if (m.containsKey(key)) m[key] else default

    /** str(v) / f-string formatting. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is Boolean -> if (v) "True" else "False"
        is String -> v
        is Double -> floatRepr(v, json = false)
        is Map<*, *>, is List<*> -> v.toString()
        else -> v.toString()
    }

    /** Python repr(float) / json float output (close; JDK 17 Double.toString is not always the shortest form). */
    fun floatRepr(d: Double, json: Boolean = true): String {
        if (d.isNaN()) return if (json) "NaN" else "nan"
        if (d.isInfinite()) return if (d > 0) (if (json) "Infinity" else "inf") else (if (json) "-Infinity" else "-inf")
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val bd = BigDecimal(d.toString()).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale()      // decimal exponent of the first digit
        val sign = if (d < 0) "-" else ""
        return if (exp < -4 || exp >= 16) {
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            sign + mant + "e" + (if (exp < 0) "-" else "+") + String.format(Locale.ROOT, "%02d", kotlin.math.abs(exp))
        } else {
            val plain = bd.abs().toPlainString()
            sign + if (plain.contains('.')) plain else "$plain.0"
        }
    }
}
