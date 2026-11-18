package ai.abhify.android

import ai.abhify.android.update.Ed25519
import ai.abhify.android.update.SignedManifest
import ai.abhify.android.update.UpdatePolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateSignatureTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 section 7.1, tests 1 and 2 (the same vectors backend/tests/test_updates.py uses)
    @Test fun rfc8032Vectors() {
        val v = listOf(
            Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        )
        for ((pk, msg, sig) in v) {
            assertTrue(Ed25519.verify(hex(pk), hex(msg), hex(sig)))
            assertFalse(Ed25519.verify(hex(pk), hex(msg) + byteArrayOf(1), hex(sig)))
            val bad = hex(sig).also { it[63] = (it[63].toInt() xor 1).toByte() }
            assertFalse(Ed25519.verify(hex(pk), hex(msg), bad))
        }
        assertFalse(Ed25519.verify(ByteArray(31), ByteArray(0), ByteArray(64)))
    }

    private val fx = JSONObject(javaClass.getResource("/signed_fixtures.json")!!.readText())
    private val trusted = fx.getJSONObject("trusted").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    @Test fun verifiesEnvelopesSignedByTheWindowsTooling() {
        val m = SignedManifest.verify(fx.getString("valid"), trusted)
        assertEquals("ai.abhify.android", m.getString("packageName"))
        assertTrue(SignedManifest.verify(fx.getString("unicode"), trusted).getString("notes").contains("नमस्ते"))
    }

    @Test fun rejectsTamperedWrongKeyAndUnknownKey() {
        for ((k, expect) in listOf("tampered" to "Signature does not match", "other_key" to "Signature does not match",
            "unknown_key_id" to "doesn't trust")) {
            try { SignedManifest.verify(fx.getString(k), trusted); fail(k) }
            catch (e: SignedManifest.Invalid) { assertTrue("$k: ${e.message}", e.message!!.contains(expect)) }
        }
        try { SignedManifest.verify("{not json", trusted); fail() } catch (e: SignedManifest.Invalid) { }
        try { SignedManifest.verify(fx.getString("valid"), emptyMap()); fail() } catch (e: SignedManifest.Invalid) { }
    }

    private val manifest get() = SignedManifest.verify(fx.getString("valid"), trusted)
    private val cert get() = fx.getString("cert")

    @Test fun updatePolicyAcceptsOnlySameAppSameKeyNewerVersion() {
        val ok = UpdatePolicy.decide(manifest, "ai.abhify.android", 1, setOf(cert))
        assertTrue(ok is UpdatePolicy.Decision.Available)
        assertTrue(UpdatePolicy.decide(manifest, "ai.abhify.android", 2, setOf(cert)) is UpdatePolicy.Decision.UpToDate)
        assertTrue(UpdatePolicy.decide(manifest, "ai.abhify.android", 5, setOf(cert)) is UpdatePolicy.Decision.UpToDate)
        val otherKey = UpdatePolicy.decide(manifest, "ai.abhify.android", 1, setOf("00".repeat(32)))
        assertTrue((otherKey as UpdatePolicy.Decision.Refused).reason.contains("different key"))
        assertTrue(UpdatePolicy.decide(manifest, "com.other.app", 1, setOf(cert)) is UpdatePolicy.Decision.Refused)
        val http = JSONObject(manifest.toString()).apply { getJSONObject("apk").put("url", "http://x.invalid/a.apk") }
        assertTrue(UpdatePolicy.decide(http, "ai.abhify.android", 1, setOf(cert)) is UpdatePolicy.Decision.Refused)
        val tooOld = JSONObject(manifest.toString()).put("minSupportedVersionCode", 2)
        assertTrue((UpdatePolicy.decide(tooOld, "ai.abhify.android", 1, setOf(cert)) as UpdatePolicy.Decision.Refused).reason.contains("intermediate"))
    }

    @Test fun downloadedArchiveMustMatchTheSignedOffer() {
        val offer = (UpdatePolicy.decide(manifest, "ai.abhify.android", 1, setOf(cert)) as UpdatePolicy.Decision.Available).offer
        assertEquals(null, UpdatePolicy.checkArchive(offer, "ai.abhify.android", "ai.abhify.android", 2, setOf(cert)))
        assertTrue(UpdatePolicy.checkArchive(offer, "ai.abhify.android", null, null, emptySet())!!.contains("not a valid"))
        assertTrue(UpdatePolicy.checkArchive(offer, "ai.abhify.android", "evil.app", 2, setOf(cert))!!.contains("different app"))
        assertTrue(UpdatePolicy.checkArchive(offer, "ai.abhify.android", "ai.abhify.android", 3, setOf(cert))!!.contains("version"))
        assertTrue(UpdatePolicy.checkArchive(offer, "ai.abhify.android", "ai.abhify.android", 2, setOf("11".repeat(32)))!!.contains("not signed"))
    }
}
