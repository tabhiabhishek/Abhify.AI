package ai.abhify.android.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable, verified download of one model file into [part] (a staging file).
 * Pure java.net: no Android dependency, so the whole state machine is unit-tested on the PC.
 */
class Downloader(
    private val allowInsecureForTests: Boolean = false,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    sealed class Result {
        data class Done(val file: File) : Result()
        /** Stopped by the user (pause) or the network; the part file is kept so it can resume. */
        data class Interrupted(val bytes: Long, val reason: String) : Result()
        data class Failed(val reason: String, val partDeleted: Boolean) : Result()
    }

    fun interface Progress { fun on(done: Long, total: Long) }

    companion object {
        const val HEADROOM_BYTES = 300L * 1024 * 1024
        private const val BUF = 256 * 1024

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(BUF)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun download(url: String, expectedSize: Long, expectedSha256: String, part: File,
                 isStopped: () -> Boolean = { false }, progress: Progress = Progress { _, _ -> }): Result {
        if (!url.startsWith("https://") && !allowInsecureForTests) return Result.Failed("Only HTTPS downloads are allowed.", false)
        part.parentFile?.mkdirs()
        if (part.exists() && part.length() > expectedSize) part.delete()          // corrupt leftover
        var have = if (part.exists()) part.length() else 0L

        if (have < expectedSize) {
            val needed = expectedSize - have + HEADROOM_BYTES
            val free = freeBytes(part.parentFile!!)
            if (free < needed) return Result.Failed(
                "Not enough storage: ${mb(needed)} MB needed, ${mb(free)} MB free. Free some space and try again.", false)

            val conn = try { openConnection(URL(url)) } catch (e: IOException) { return Result.Interrupted(have, "No connection: ${e.message}") }
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = try { conn.responseCode } catch (e: IOException) { return Result.Interrupted(have, "No connection: ${e.message}") }
                when {
                    code == 206 -> {}
                    code == 200 -> { if (have > 0) { part.delete(); have = 0 } }     // server ignored the range: start over
                    code == 416 && have == expectedSize -> {}
                    code in 500..599 || code == 429 -> return Result.Interrupted(have, "The download server is busy (HTTP $code). Try again later.")
                    else -> return Result.Failed("The download source answered HTTP $code.", false)
                }
                val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
                if (len != null && code != 416 && have + len != expectedSize) {
                    return Result.Failed("The file on the server has a different size than expected (${have + len} vs $expectedSize bytes). Not using it.", false)
                }
                if (code != 416) {
                    try {
                        conn.inputStream.use { input ->
                            FileOutputStream(part, have > 0).use { out ->
                                val buf = ByteArray(BUF)
                                while (true) {
                                    if (isStopped()) { out.fd.sync(); return Result.Interrupted(have, "Paused") }
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    if (have + n > expectedSize) { out.close(); part.delete(); return Result.Failed("The server sent more data than expected.", true) }
                                    out.write(buf, 0, n)
                                    have += n
                                    progress.on(have, expectedSize)
                                }
                                out.fd.sync()
                            }
                        }
                    } catch (e: IOException) {
                        return Result.Interrupted(part.length(), "Connection lost: ${e.message}. The download can resume.")
                    }
                }
            } finally { conn.disconnect() }
        }

        if (part.length() != expectedSize) return Result.Interrupted(part.length(), "The download ended early. It can resume.")
        val actual = sha256(part)
        if (actual != expectedSha256) {
            part.delete()
            return Result.Failed("The downloaded file is damaged or not the expected model (SHA-256 mismatch). It was deleted; download it again.", true)
        }
        return Result.Done(part)
    }

    private fun mb(b: Long) = b / (1024 * 1024)
}
