package ai.abhify.android.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the app keeps about a signed-in account. Tokens only; never the password. */
data class Session(
    val serverUrl: String, val userId: String, val email: String, val displayName: String, val sessionId: String,
    val accessToken: String, val accessExpiresAt: Long, val refreshToken: String,
)

/** Encrypts small secrets. Android: an AES-256-GCM key inside the Android Keystore (it never leaves it). */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

class KeystoreBox(private val alias: String = "abhify_session_v1") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)                                 // 12-byte IV, then ciphertext + tag
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** Key-value persistence (SharedPreferences on Android, a map in tests). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** The session, sealed by [box] before it is stored. A record that can't be opened is treated as signed out. */
class TokenStore(private val kv: KeyValue, private val box: SecretBox) {
    private val enc = Base64.getEncoder(); private val dec = Base64.getDecoder()

    fun load(): Session? = runCatching {
        val sealed = kv.get("session") ?: return null
        val o = JSONObject(String(box.open(dec.decode(sealed)), Charsets.UTF_8))
        Session(o.getString("server"), o.getString("uid"), o.getString("email"), o.getString("name"), o.getString("sid"),
            o.getString("at"), o.getLong("atExp"), o.getString("rt"))
    }.getOrNull()

    fun save(s: Session) {
        val json = JSONObject().put("server", s.serverUrl).put("uid", s.userId).put("email", s.email).put("name", s.displayName)
            .put("sid", s.sessionId).put("at", s.accessToken).put("atExp", s.accessExpiresAt).put("rt", s.refreshToken).toString()
        kv.put("session", enc.encodeToString(box.seal(json.toByteArray(Charsets.UTF_8))))
    }

    fun clear() = kv.put("session", null)
}
