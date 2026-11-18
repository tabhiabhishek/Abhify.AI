package ai.abhify.android.update

import ai.abhify.android.BuildConfig
import ai.abhify.android.model.Downloader
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Signed app updates within Android's rules: the user starts the check, the package must match the
 * signed manifest (size + SHA-256), keep the same package name and signing key, and Android's own
 * installer always asks the user to confirm. Nothing installs silently. No update source or trusted
 * key is configured in this build (owner decision), so [check] reports NotConfigured.
 */
class AppUpdater(private val context: Context) {
    val updateUrl: String = BuildConfig.UPDATE_URL
    val trustedKeys: Map<String, String> = BuildConfig.UPDATE_KEYS.split(',').filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    val configured get() = updateUrl.startsWith("https://") && trustedKeys.isNotEmpty()
    private val staging = File(context.noBackupFilesDir, "updates")

    fun installedCertSha256(): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return certs(info)
    }

    private fun certs(info: PackageInfo?): Set<String> {
        val si = info?.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Blocking (call off the main thread). */
    fun check(): UpdatePolicy.Decision {
        if (!configured) return UpdatePolicy.Decision.Refused("No update source is configured in this build.")
        val body = try {
            (URL(updateUrl).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000; readTimeout = 20_000
                if (responseCode != 200) return UpdatePolicy.Decision.Refused("The update source answered HTTP $responseCode.")
                inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: Exception) { return UpdatePolicy.Decision.Refused("Couldn't reach the update source (offline?).") }
        val manifest = try { SignedManifest.verify(body, trustedKeys) } catch (e: SignedManifest.Invalid) {
            return UpdatePolicy.Decision.Refused(e.message ?: "Invalid signature.")
        }
        val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return UpdatePolicy.decide(manifest, context.packageName, current, installedCertSha256())
    }

    /** Downloads into staging and verifies hash + package identity. Returns the verified APK or an error. */
    fun download(offer: UpdatePolicy.Offer, isStopped: () -> Boolean, progress: Downloader.Progress): Result<File> {
        val part = File(staging, "update-${offer.versionCode}.apk")
        return when (val r = Downloader().download(offer.apkUrl, offer.apkSize, offer.apkSha256, part, isStopped, progress)) {
            is Downloader.Result.Done -> {
                val info = context.packageManager.getPackageArchiveInfo(r.file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                UpdatePolicy.checkArchive(offer, context.packageName, info?.packageName, info?.longVersionCode, certs(info))
                    ?.let { r.file.delete(); Result.failure(IllegalStateException(it)) } ?: Result.success(r.file)
            }
            is Downloader.Result.Interrupted -> Result.failure(IllegalStateException(r.reason))
            is Downloader.Result.Failed -> Result.failure(IllegalStateException(r.reason))
        }
    }

    /** Android requires the user to allow this app to install packages; returns false and opens that setting if needed. */
    fun canInstallOrAsk(): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    /** Hands the verified APK to Android's installer; Android shows its confirmation dialog. Data is kept (same app + key). */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pi.intentSender)
        }
    }

    fun clearStaging() { staging.deleteRecursively() }
}

/** Receives the installer's status; on "user action needed" shows Android's own confirmation screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
