package ai.abhify.android.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 7.1.0 offline wake word (owner-approved): the Vosk speech engine with its small English model, listening only for
 * the assistant's name (a grammar of the name plus greetings, everything else is "[unk]"). Audio is processed on the
 * phone; nothing is uploaded. The model (≈41 MB) is downloaded only when the user taps Install, from Vosk's official
 * site, and its SHA-256 is checked before it is unpacked. Listening happens only while the app is open (the caller
 * stops it when the app leaves the screen).
 */
class VoskWake(private val context: Context) {
    companion object {
        const val URL_MODEL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        const val BYTES = 41_205_931L
        const val NAME = "vosk-model-small-en-us-0.15"
        private const val RATE = 16000f

        /** The grammar for [name]: greetings + the name, alone or together; anything else is "[unk]" (pure, tested). */
        fun grammar(name: String): String {
            val n = WakeMatcher.normalize(name).joinToString(" ")
            if (n.isBlank()) return JSONArray(listOf("[unk]")).toString()
            val phrases = listOf(n) + listOf("hey", "hi", "hello", "ok", "okay").map { "$it $n" } + "[unk]"
            return JSONArray(phrases).toString()
        }

        /** The text in a Vosk result or partial result JSON ({"text": …} / {"partial": …}); never throws. */
        fun textOf(json: String?): String = runCatching { JSONObject(json ?: "").let { it.optString("text").ifBlank { it.optString("partial") } } }.getOrDefault("")
    }

    private val root get() = File(context.noBackupFilesDir, "vosk")
    private val modelDir get() = File(root, NAME)
    fun installed(): Boolean = File(modelDir, "am/final.mdl").isFile && File(modelDir, "conf/model.conf").isFile

    /** Set to stop a running download (the partial file is deleted; Install starts again). */
    @Volatile var cancel = false

    /** Downloads, verifies and unpacks the model. Blocking (call on Dispatchers.IO). Returns null or a user-facing error. */
    fun install(progress: (Long, Long) -> Unit): String? {
        cancel = false
        val tmp = File(context.cacheDir, "$NAME.zip.part")
        try {
            val c = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000 }
            if (c.responseCode != 200) return "The download failed (HTTP ${c.responseCode}). Try again later."
            val md = MessageDigest.getInstance("SHA-256"); var got = 0L
            c.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) { if (cancel) return "Download cancelled. Nothing was installed."; val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); got += n; progress(got, BYTES) }
            } }
            c.disconnect()
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) return "The downloaded file didn't match the expected checksum, so it wasn't installed. Try again."
            val staging = File(root, "staging").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val target = File(staging, e.name).canonicalFile
                    if (!target.path.startsWith(staging.canonicalPath + File.separator)) return "The model archive is invalid."   // zip-slip guard
                    if (e.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { z.copyTo(it) } }
                }
            }
            modelDir.deleteRecursively()
            if (!File(staging, NAME).renameTo(modelDir)) return "The model couldn't be unpacked."
            staging.deleteRecursively()
            return if (installed()) null else "The model is incomplete."
        } catch (e: java.io.IOException) {
            return "No connection to the download site. Check the internet and try again."
        } finally { tmp.delete() }
    }

    fun remove() { stop(); runCatching { model?.close() }; model = null; root.deleteRecursively() }

    private var model: org.vosk.Model? = null
    private var recognizer: org.vosk.Recognizer? = null
    private var service: org.vosk.android.SpeechService? = null
    val listening get() = service != null

    /** Loads the model if needed (blocking; call on Dispatchers.IO). Returns null or an error. */
    fun prepare(): String? = runCatching {
        if (model == null) { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS); model = org.vosk.Model(modelDir.absolutePath) }
        null
    }.getOrElse { "The offline wake word engine couldn't start (${it.javaClass.simpleName})." }

    /** Starts listening for [name] (main thread, after [prepare]). Calls [onHit] once with what was heard. */
    fun start(name: String, onHit: (String) -> Unit, onError: (String) -> Unit): Boolean {
        stop()
        val m = model ?: run { onError("The offline wake word engine isn't loaded."); return false }
        return runCatching {
            val r = org.vosk.Recognizer(m, RATE, grammar(name))
            val s = org.vosk.android.SpeechService(r, RATE)
            recognizer = r; service = s
            var fired = false
            fun check(json: String?) {
                if (fired) return
                val text = textOf(json)
                if (text.isNotBlank() && WakeMatcher.match(text, name) != null) { fired = true; stop(); onHit(text) }
            }
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis)
                override fun onResult(hypothesis: String?) = check(hypothesis)
                override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                override fun onError(e: Exception?) { stop(); onError("The microphone stopped (${e?.javaClass?.simpleName ?: "error"}).") }
                override fun onTimeout() {}
            })
        }.getOrElse { stop(); onError("The microphone couldn't be used for the wake word."); false }
    }

    /** Stops listening and releases the microphone at once. */
    fun stop() {
        service?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }; service = null
        recognizer?.let { runCatching { it.close() } }; recognizer = null
    }
}
