package ai.abhify.android.cloud

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** No connection / timeout / TLS failure: the request never got an HTTP answer. */
class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class HttpResult(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** A finished streamed request: status, response headers (lower-case names) and, on errors, the body. */
data class StreamResult(val code: Int, val headers: Map<String, String> = emptyMap(), val errorBody: String = "")

/** The few HTTP operations the app needs; an interface so the client logic is unit-tested with a fake. */
interface Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
    /** Streams the response body line by line to [onLine]; returns the status code. [onLine] returns false to stop. */
    fun stream(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): Int
    /** Like [stream], plus response headers and the error body (rate limits, Retry-After, provider errors). */
    fun streamFull(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): StreamResult =
        StreamResult(stream(url, headers, body, onLine))
}

class UrlHttp(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 60_000) : Http {
    private fun open(method: String, url: String, headers: Map<String, String>): HttpURLConnection {
        require(url.startsWith("https://") || isLocalDev(url)) { "Only HTTPS servers are allowed." }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = open(method, url, headers)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResult(code, text, headersOf(c))
        } catch (e: IOException) {
            throw NetworkException("Can't reach the server.", e)
        } finally { c.disconnect() }
    }

    override fun stream(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): Int =
        streamFull(url, headers, body, onLine).code

    override fun streamFull(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): StreamResult {
        val c = open("POST", url, headers)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val h = headersOf(c)
            if (code != 200) return StreamResult(code, h, c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r: BufferedReader ->
                while (true) { val line = r.readLine() ?: break; if (line.isNotBlank() && !onLine(line)) break }
            }
            return StreamResult(code, h)
        } catch (e: IOException) {
            throw NetworkException("The connection to the server was interrupted.", e)
        } finally { c.disconnect() }
    }

    private fun headersOf(c: HttpURLConnection): Map<String, String> =
        c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }

    companion object {
        /** Debug builds may talk to a server on the same machine (emulator host) without TLS; release never. */
        fun isLocalDev(url: String): Boolean = ai.abhify.android.BuildConfig.DEBUG &&
            (url.startsWith("http://10.0.2.2:") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))
    }
}
