package ai.abhify.android.update

import org.json.JSONObject
import java.util.Base64

/**
 * The "abhify-update-1" envelope, identical to the Windows app's (backend/updates.py):
 *   {"format": "abhify-update-1", "key_id": "...", "manifest": "<base64 manifest JSON bytes>",
 *    "signature": "<base64 Ed25519 signature of exactly those bytes>"}
 * Only keys in [trustedKeys] (key_id -> base64 public key) are accepted. A hash from an
 * unauthenticated source proves nothing; the signature proves origin.
 */
object SignedManifest {
    const val FORMAT = "abhify-update-1"

    class Invalid(message: String) : Exception(message)

    /** Returns the verified manifest JSON, or throws [Invalid] with a user-readable reason. */
    fun verify(envelopeJson: String, trustedKeys: Map<String, String>): JSONObject {
        val env = try { JSONObject(envelopeJson) } catch (e: Exception) { throw Invalid("The update information is not valid JSON.") }
        if (env.optString("format") != FORMAT) throw Invalid("Unknown update format.")
        val keyB64 = trustedKeys[env.optString("key_id")]
            ?: throw Invalid("The update is signed with a key this app doesn't trust.")
        val dec = Base64.getDecoder()
        val (body, sig, key) = try {
            Triple(dec.decode(env.getString("manifest")), dec.decode(env.getString("signature")), dec.decode(keyB64))
        } catch (e: Exception) { throw Invalid("The update information is malformed.") }
        if (!Ed25519.verify(key, body, sig)) throw Invalid("Signature does not match: the update was altered or not signed by the trusted key.")
        return try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { throw Invalid("The signed manifest is not valid JSON.") }
    }
}
