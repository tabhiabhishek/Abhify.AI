package ai.abhify.android.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A refusal from the server with its user-readable message (4xx/5xx). */
class ApiException(val code: Int, message: String) : Exception(message)

sealed class Account {
    data object SignedOut : Account()
    data class SignedIn(val session: Session) : Account()
    /** The refresh token is no longer accepted. Local data stays; the user signs in again. */
    data class Expired(val email: String, val serverUrl: String) : Account()
}

/**
 * Accounts and authenticated calls to the Abhify cloud service. Blocking: call from Dispatchers.IO.
 * Access tokens are refreshed transparently (one refresh at a time); refresh tokens rotate.
 */
class CloudClient(private val http: Http, private val store: TokenStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val _account = MutableStateFlow<Account>(store.load()?.let { Account.SignedIn(it) } ?: Account.SignedOut)
    val account: StateFlow<Account> = _account.asStateFlow()
    private val refreshLock = Any()

    val session: Session? get() = (_account.value as? Account.SignedIn)?.session

    companion object {
        fun normalizeServer(url: String): String = url.trim().trimEnd('/')
        fun detail(body: String, code: Int): String =
            runCatching { JSONObject(body).getString("detail") }.getOrNull()
                ?: runCatching { JSONObject(body).getJSONArray("detail").getJSONObject(0).getString("msg") }.getOrNull()
                ?: "The server answered HTTP $code."
    }

    private fun call(method: String, url: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val h = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val r = http.request(method, url, h, body?.toString())
        if (r.code >= 400) throw ApiException(r.code, detail(r.body, r.code))
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    private fun signedIn(server: String, o: JSONObject): Session {
        val u = o.getJSONObject("user")
        val s = Session(server, u.getString("id"), u.getString("email"), u.getString("display_name"), o.getString("session_id"),
            o.getString("access_token"), clock() + o.getLong("access_expires_in") * 1000 - 30_000, o.getString("refresh_token"))
        store.save(s); _account.value = Account.SignedIn(s)
        return s
    }

    // ------------------------------------------------------------------ account
    fun health(server: String): JSONObject = call("GET", "${normalizeServer(server)}/api/v1/health")

    fun register(server: String, email: String, password: String, name: String, device: String): Session {
        val srv = normalizeServer(server)
        return signedIn(srv, call("POST", "$srv/api/v1/auth/register",
            JSONObject().put("email", email.trim()).put("password", password).put("display_name", name.trim()).put("device_name", device)))
    }

    fun login(server: String, email: String, password: String, device: String): Session {
        val srv = normalizeServer(server)
        return signedIn(srv, call("POST", "$srv/api/v1/auth/login",
            JSONObject().put("email", email.trim()).put("password", password).put("device_name", device)))
    }

    fun forgotPassword(server: String, email: String): String =
        call("POST", "${normalizeServer(server)}/api/v1/auth/forgot-password", JSONObject().put("email", email.trim())).optString("detail")

    fun resetPassword(server: String, email: String, code: String, newPassword: String): String =
        call("POST", "${normalizeServer(server)}/api/v1/auth/reset-password",
            JSONObject().put("email", email.trim()).put("code", code.trim()).put("new_password", newPassword)).optString("detail")

    /** Signs out here and revokes the session on the server when reachable. Local data stays. */
    fun logout() {
        val s = session
        runCatching { if (s != null) authed("POST", "/api/v1/auth/logout") }
        store.clear(); _account.value = Account.SignedOut
    }

    fun me(): JSONObject = authed("GET", "/api/v1/users/me")
    fun updateName(name: String): JSONObject = authed("PATCH", "/api/v1/users/me", JSONObject().put("display_name", name.trim())).also { o ->
        session?.let { s -> val n = s.copy(displayName = o.getJSONObject("user").getString("display_name")); store.save(n); _account.value = Account.SignedIn(n) }
    }
    fun changePassword(current: String, new: String): String =
        authed("POST", "/api/v1/auth/change-password", JSONObject().put("current_password", current).put("new_password", new)).optString("detail")
    fun sessions(): JSONArray = authed("GET", "/api/v1/users/me/sessions").getJSONArray("sessions")
    fun endSession(id: String) { authed("DELETE", "/api/v1/users/me/sessions/$id") }
    fun deleteCloudData(): JSONObject = authed("DELETE", "/api/v1/sync/data")
    fun deleteAccount(password: String) {
        authed("POST", "/api/v1/users/me/delete", JSONObject().put("password", password))
        store.clear(); _account.value = Account.SignedOut
    }

    // ------------------------------------------------------------------ tokens
    /** A valid access token, refreshing it first if it's about to expire. Throws ApiException(401) when signed out/expired. */
    fun accessToken(forceRefresh: Boolean = false): String = synchronized(refreshLock) {
        val s = session ?: throw ApiException(401, "Not signed in.")
        if (!forceRefresh && s.accessExpiresAt > clock()) return s.accessToken
        val r = http.request("POST", "${s.serverUrl}/api/v1/auth/refresh", emptyMap(), JSONObject().put("refresh_token", s.refreshToken).toString())
        if (r.code == 401) {
            store.clear(); _account.value = Account.Expired(s.email, s.serverUrl)
            throw ApiException(401, detail(r.body, r.code))
        }
        if (r.code >= 400) throw ApiException(r.code, detail(r.body, r.code))
        val o = JSONObject(r.body)
        val n = s.copy(accessToken = o.getString("access_token"), refreshToken = o.getString("refresh_token"),
            accessExpiresAt = clock() + o.getLong("access_expires_in") * 1000 - 30_000)
        store.save(n); _account.value = Account.SignedIn(n)
        n.accessToken
    }

    /** An authenticated call; on 401 the access token is refreshed once and the call retried. */
    fun authed(method: String, path: String, body: JSONObject? = null): JSONObject {
        val server = session?.serverUrl ?: throw ApiException(401, "Not signed in.")
        return try { call(method, server + path, body, accessToken()) }
        catch (e: ApiException) { if (e.code == 401) call(method, server + path, body, accessToken(forceRefresh = true)) else throw e }
    }

    /** Streams an authenticated NDJSON response (the online AI); same refresh-once rule. */
    fun stream(path: String, body: JSONObject, onLine: (String) -> Boolean): Int {
        val server = session?.serverUrl ?: throw ApiException(401, "Not signed in.")
        var code = http.stream(server + path, mapOf("Authorization" to "Bearer ${accessToken()}"), body.toString(), onLine)
        if (code == 401) code = http.stream(server + path, mapOf("Authorization" to "Bearer ${accessToken(forceRefresh = true)}"), body.toString(), onLine)
        return code
    }
}
