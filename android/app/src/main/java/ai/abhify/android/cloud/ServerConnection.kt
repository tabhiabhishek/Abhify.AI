package ai.abhify.android.cloud

import org.json.JSONObject
import java.net.URI

/**
 * Local-first connection model (v4.2). Abhify works fully in LOCAL_MODE (no Abhify server); a server is an optional
 * extra the user adds in Settings. Google Drive is separate and never depends on it. Pure, so it's tested on the JVM.
 */
object ServerConnection {
    enum class Mode(val label: String) {
        LOCAL_MODE("Local Mode"), SERVER_CONFIGURED("Server added (not checked yet)"), SERVER_CONNECTED("Server reachable"),
        SERVER_DISCONNECTED("Server unreachable"), SERVER_AUTHENTICATED("Signed in to the server")
    }

    /** No address (or switched off) = Local Mode; otherwise what the last check and the session say. Never an error. */
    fun mode(url: String?, enabled: Boolean, lastCheckOk: Boolean?, signedIn: Boolean): Mode = when {
        url.isNullOrBlank() || !enabled -> Mode.LOCAL_MODE
        signedIn -> Mode.SERVER_AUTHENTICATED
        lastCheckOk == true -> Mode.SERVER_CONNECTED
        lastCheckOk == false -> Mode.SERVER_DISCONNECTED
        else -> Mode.SERVER_CONFIGURED
    }

    sealed class Check {
        data class Ok(val url: String, val note: String? = null) : Check()
        data class Invalid(val reason: String) : Check()
    }
    private val DEV_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")
    private fun privateLan(h: String) = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)").containsMatchIn(h)

    /**
     * Address rules: https:// for real servers. Plain http:// only in debug builds and only to the emulator host
     * (10.0.2.2) or this phone (127.0.0.1 / localhost) — the debug network config allows exactly those. Never a downgrade.
     */
    fun validate(input: String, debug: Boolean): Check {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return Check.Invalid("Enter the server address, for example https://abhify.example.com.")
        if (!raw.contains("://")) return Check.Invalid("Add the protocol: https://$raw")
        val u = runCatching { URI(raw) }.getOrNull() ?: return Check.Invalid("That isn't a valid web address.")
        val host = u.host ?: return Check.Invalid("The address has no server name.")
        if (u.rawQuery != null || u.rawFragment != null) return Check.Invalid("Use only the server address, without ?… or #….")
        return when (u.scheme?.lowercase()) {
            "https" -> Check.Ok(raw, if (host in DEV_HOSTS) "On a phone, $host means the phone itself, not your PC." else null)
            "http" -> when {
                !debug -> Check.Invalid("The server address must start with https:// (plain http is only allowed in developer builds for local testing).")
                host == "10.0.2.2" -> Check.Ok(raw, "10.0.2.2 reaches the PC only from the Android emulator.")
                host in DEV_HOSTS -> Check.Ok(raw, "127.0.0.1 / localhost is this phone itself: it works only if the server runs on the phone (e.g. via adb reverse tcp:8000 tcp:8000).")
                privateLan(host) -> Check.Invalid("Android blocks plain http to network addresses like $host. Serve the PC over https (e.g. a local reverse proxy), " +
                    "or on the emulator use http://10.0.2.2:PORT, or forward the port with \"adb reverse tcp:8000 tcp:8000\" and use http://127.0.0.1:8000.")
                else -> Check.Invalid("Use https:// for this server.")
            }
            else -> Check.Invalid("Only https:// addresses are supported.")
        }
    }

    data class Health(val ok: Boolean, val message: String, val version: String? = null, val aiAvailable: Boolean? = null)

    /** Interprets GET /api/v1/health (the Abhify cloud's existing endpoint). [code] null = no answer (unreachable / timeout). */
    fun interpret(code: Int?, body: String?): Health {
        if (code == null) return Health(false, "Unable to connect to the Abhify server. You can continue using local features.")
        if (code == 404) return Health(false, "This address answered, but it has no Abhify server (no /api/v1/health).")
        if (code >= 500) return Health(false, "The server reported an error ($code). Try again later; local features keep working.")
        if (code != 200) return Health(false, "The server answered HTTP $code.")
        val o = runCatching { JSONObject(body ?: "") }.getOrNull() ?: return Health(false, "This address answered, but not like an Abhify server.")
        if (o.optString("service") != "abhify-cloud") return Health(false, "This address answered, but it isn't an Abhify server.")
        val v = o.optString("version").ifEmpty { null }
        val major = v?.substringBefore('.')?.toIntOrNull()
        if (major == null || major < 1) return Health(false, "This Abhify server's version (${v ?: "unknown"}) isn't supported by this app.", v)
        return Health(true, "Abhify server ${v} is reachable" + if (o.optBoolean("ai_available")) " (server AI available)." else ".", v, o.optBoolean("ai_available"))
    }
}
