package ai.abhify.android.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** HttpURLConnection with byte bodies, HTTPS only. Bodies are never logged. */
class UrlDriveTransport(private val timeoutMs: Int = 30_000) : DriveTransport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): DriveTransport.Resp {
        require(url.startsWith("https://")) { "Only HTTPS is allowed." }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = 120_000
            if (method == "PATCH") { requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH") } else requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setFixedLengthStreamingMode(body.size) }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return DriveTransport.Resp(code, bytes, h)
        } finally { c.disconnect() }
    }
}

/**
 * Google sign-in for Drive with the official Android Authorization API (Google Play services). Only the
 * `drive.file` scope is requested. No password is ever typed into Abhify and no client secret exists in the app: the
 * OAuth client is matched by package name + signing certificate in the owner's Google Cloud project. Access tokens
 * are short-lived and kept in memory only; when one expires, authorization is requested again (silently when
 * already granted).
 */
object DriveAuth {
    sealed class Result {
        data class Token(val token: String) : Result()
        data class NeedsConsent(val intent: PendingIntent) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Null when Google Play services can be used, else why not. */
    fun unavailable(ctx: Context): String? = when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)) {
        ConnectionResult.SUCCESS -> null
        ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_INVALID -> "Google Play services aren't available on this phone, so Google Drive can't be connected."
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "Update Google Play services, then try again."
        ConnectionResult.SERVICE_DISABLED -> "Google Play services are disabled on this phone."
        else -> "Google Play services can't be used right now."
    }

    /** Only drive.file. [account] (an email the user picked in Android's account chooser) pins which Google account is asked. */
    private fun request(account: String?) = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveApi.SCOPE)))
        .apply { if (account != null) setAccount(android.accounts.Account(account, "com.google")) }.build()

    /** Blocking (call on Dispatchers.IO). */
    fun authorize(ctx: Context, account: String? = null): Result = try {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request(account)), 30, TimeUnit.SECONDS)
        when {
            r.hasResolution() -> Result.NeedsConsent(r.pendingIntent!!)
            r.accessToken != null -> Result.Token(r.accessToken!!)
            else -> Result.Failed("Google didn't return access.")
        }
    } catch (e: java.util.concurrent.ExecutionException) { Result.Failed(message(e.cause)) }
    catch (e: java.util.concurrent.TimeoutException) { Result.Failed("Google didn't answer in time. Check the internet and retry.") }
    catch (e: Exception) { Result.Failed(message(e)) }

    /** After the consent screen. */
    fun fromIntent(ctx: Context, data: Intent?): Result = try {
        val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Result.Token(it) } ?: Result.Failed("Google Drive access wasn't granted.")
    } catch (e: Exception) { Result.Failed(message(e)) }

    fun message(e: Throwable?): String = (e as? ApiException)?.statusCode?.let { DriveErrors.message(it) }
        ?: if (e is java.io.IOException) DriveErrors.message(7) else "Google sign-in failed" + (e?.message?.let { ": ${it.take(120)}" } ?: "") + "."

    /** Android's own account chooser (no permission needed; can also add an account). Returns the picked email via the result. */
    fun chooseAccountIntent(current: String?): Intent = android.accounts.AccountManager.newChooseAccountIntent(
        current?.let { android.accounts.Account(it, "com.google") }, null, arrayOf("com.google"), null, null, null, null)

    /** Revokes the grant at Google (the app also forgets its local Drive state). */
    fun revoke(token: String): Boolean = runCatching {
        val c = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write("token=$token".toByteArray()) }
        val ok = c.responseCode == 200; c.disconnect(); ok
    }.getOrDefault(false)
}
