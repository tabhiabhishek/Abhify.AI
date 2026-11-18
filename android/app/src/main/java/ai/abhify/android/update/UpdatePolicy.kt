package ai.abhify.android.update

import org.json.JSONObject

/**
 * Pure rules for an app update offered by a verified manifest. Android itself also refuses an APK
 * with another package name or another signing key; checking first gives a clear message and never
 * downloads a package that couldn't be installed.
 *
 * Signed manifest body ("abhify-android-update-1"):
 *   {"format":"abhify-android-update-1","packageName":"ai.abhify.android","versionCode":2,"versionName":"0.1.0-dev.2",
 *    "minSupportedVersionCode":1,"notes":"...",
 *    "apk":{"url":"https://...","size":123,"sha256":"<hex>","signingCertSha256":"<hex>"}}
 */
object UpdatePolicy {
    const val FORMAT = "abhify-android-update-1"
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    data class Offer(val versionCode: Long, val versionName: String, val notes: String,
                     val apkUrl: String, val apkSize: Long, val apkSha256: String, val signingCertSha256: String)

    sealed class Decision {
        data class Available(val offer: Offer) : Decision()
        data class UpToDate(val current: Long) : Decision()
        data class Refused(val reason: String) : Decision()
    }

    fun decide(manifest: JSONObject, packageName: String, currentVersionCode: Long, installedCertSha256: Set<String>): Decision {
        if (manifest.optString("format") != FORMAT) return Decision.Refused("Unknown update format.")
        if (manifest.optString("packageName") != packageName) return Decision.Refused("The update is for a different app.")
        val vc = manifest.optLong("versionCode", -1)
        if (vc <= currentVersionCode) return Decision.UpToDate(currentVersionCode)
        if (currentVersionCode < manifest.optLong("minSupportedVersionCode", 0))
            return Decision.Refused("This version is too old to update directly; install the intermediate version first.")
        val apk = manifest.optJSONObject("apk") ?: return Decision.Refused("The update has no package.")
        val url = apk.optString("url"); val sha = apk.optString("sha256").lowercase(); val cert = apk.optString("signingCertSha256").lowercase()
        val size = apk.optLong("size", -1)
        if (!url.startsWith("https://")) return Decision.Refused("The update package must be served over HTTPS.")
        if (!HEX64.matches(sha) || !HEX64.matches(cert) || size <= 0) return Decision.Refused("The update description is incomplete.")
        if (cert !in installedCertSha256)
            return Decision.Refused("The update is signed with a different key than this app. Android would refuse it, so it was not downloaded.")
        return Decision.Available(Offer(vc, manifest.optString("versionName"), manifest.optString("notes"), url, size, sha, cert))
    }

    /** Checks the downloaded APK's own identity (read by PackageManager) against the offer. null = OK. */
    fun checkArchive(offer: Offer, packageName: String, archivePackage: String?, archiveVersionCode: Long?, archiveCerts: Set<String>): String? = when {
        archivePackage == null -> "The downloaded file is not a valid Android package."
        archivePackage != packageName -> "The downloaded package is a different app."
        archiveVersionCode != offer.versionCode -> "The downloaded package's version doesn't match the signed update."
        offer.signingCertSha256 !in archiveCerts -> "The downloaded package is not signed with this app's key."
        else -> null
    }
}
