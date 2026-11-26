package ai.abhify.android.llm

import ai.abhify.android.chat.PromptBuilder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** JNI declarations; implemented in src/main/cpp/abhify_llm.cpp. */
internal object LlamaBridge {
    fun interface ByteSink { fun onBytes(bytes: ByteArray): Boolean }

    external fun nativeInit(nativeLibDir: String): String
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, gpuLayers: Int): String?
    external fun nativeUnload()
    external fun nativeCancel()
    external fun nativeModelDesc(): String?
    external fun nativeGenerate(roles: Array<String>, contents: Array<ByteArray>, maxTokens: Int, temp: Float, seed: Int, sink: ByteSink): String
    external fun nativeEmbedLoad(path: String, nThreads: Int): String?
    external fun nativeEmbedUnload()
    external fun nativeEmbed(text: ByteArray): FloatArray?
}

/**
 * The on-device model. Every native call runs on one dedicated thread (llama.cpp contexts are not
 * thread-safe). The native library is loaded lazily, so a phone without a model never touches it.
 */
class LlmEngine(private val nativeLibDir: String) {
    data class Backend(val systemInfo: String, val devices: List<Device>) {
        data class Device(val name: String, val type: String, val description: String, val totalMb: Long)
        val hasGpu get() = devices.any { it.type == "gpu" || it.type == "igpu" }
    }

    data class Stats(val promptTokens: Int, val generatedTokens: Int, val promptMs: Double, val firstTokenMs: Double,
                     val generationMs: Double, val tokensPerSecond: Double, val stopped: String)

    sealed class Event {
        data class Token(val text: String) : Event()
        data class Finished(val stats: Stats) : Event()
        data class Failed(val message: String) : Event()
    }

    data class Loaded(val path: String, val context: Int, val threads: Int, val gpuLayers: Int)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "abhify-llm") }
    private val thread = executor.asCoroutineDispatcher()
    @Volatile var loaded: Loaded? = null; private set
    private var backend: Backend? = null
    /** True once libabhify_llm is loaded. Until then no native method may be called (UnsatisfiedLinkError = crash). */
    @Volatile var nativeReady = false; private set

    suspend fun backend(): Backend = withContext(thread) { ensureBackend() }

    /** Loads the native library once. Only on the engine thread (callers already are, or switch to it). */
    private fun ensureBackend(): Backend =
        backend ?: run {
            System.loadLibrary("abhify_llm")
            nativeReady = true
            val o = JSONObject(LlamaBridge.nativeInit(nativeLibDir))
            val arr = o.getJSONArray("devices")
            val b = Backend(o.getString("system"), (0 until arr.length()).map {
                val d = arr.getJSONObject(it)
                Backend.Device(d.getString("name"), d.getString("type"), d.getString("description"), d.getLong("total_mb"))
            })
            if (b.devices.none { it.type == "cpu" }) {
                val found = File(nativeLibDir).list()?.filter { it.startsWith("libggml") }?.sorted()
                throw IllegalStateException("The AI engine found no usable CPU backend in $nativeLibDir (files: ${found ?: "folder missing"}).")
            }
            b.also { backend = it }
        }

    /** null on success, else a user-readable error. Reuses the loaded model if nothing changed. */
    suspend fun load(file: File, context: Int, threads: Int, gpuLayers: Int = 0): String? = withContext(thread) {
        backend()
        val want = Loaded(file.absolutePath, context, threads, gpuLayers)
        if (loaded == want) return@withContext null
        loaded = null
        val err = runCatching { LlamaBridge.nativeLoad(file.absolutePath, context, threads, gpuLayers) }
            .getOrElse { "The AI engine could not start: ${it.message}" }
        if (err == null) loaded = want
        err
    }

    suspend fun unload() = withContext(thread) { if (loaded != null) { LlamaBridge.nativeUnload(); loaded = null } }

    /**
     * Stops a running generation. Safe at any time: before the library was ever loaded (no phone model used yet, or
     * online mode) there is nothing to stop. Calling the JNI method then crashed New chat / New private chat / Cancel.
     */
    fun cancel() { if (nativeReady) LlamaBridge.nativeCancel() }

    /** True while an answer is being generated (OCR waits for it: one heavy job at a time). */
    @Volatile var generating = false; private set

    fun generate(messages: List<PromptBuilder.Msg>, maxTokens: Int = 512, temperature: Float = 0.3f, seed: Int = 42): Flow<Event> = callbackFlow {
        if (loaded == null) { send(Event.Failed("No model is loaded.")); close(); return@callbackFlow }
        generating = true
        launch {
            val json = LlamaBridge.nativeGenerate(
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                maxTokens, temperature, seed,
            ) { bytes -> runCatching { trySendBlocking(Event.Token(String(bytes, Charsets.UTF_8))).isSuccess }.getOrDefault(false) }   // waits for buffer space; never drops text; 7.1.0: never throws into native code
            val o = JSONObject(json)
            if (o.has("error") && o.optString("stopped") != "cancelled") send(Event.Failed(o.getString("error")))
            else send(Event.Finished(Stats(o.optInt("prompt_tokens"), o.optInt("generated_tokens"), o.optDouble("prompt_ms"),
                o.optDouble("first_token_ms"), o.optDouble("generation_ms"), o.optDouble("tokens_per_second"), o.optString("stopped"))))
            close()
        }
        // A stopped collector also stops generation at the next token: the channel is closed, so onBytes returns false.
        // (RC.4 used trySend: when the 64-event buffer was full it failed too, which silently ended the answer early.)
        awaitClose { LlamaBridge.nativeCancel(); generating = false }
    }.flowOn(thread)

    // ------------------------------------------------------------------ embeddings (semantic search)
    /** The loaded embedding model file, or null. Separate from the chat model: loading one never unloads the other. */
    @Volatile var embedLoaded: String? = null; private set

    /** Loads the embedding model (null on success, else a message). Runs on the engine's one thread. */
    fun embedLoad(file: File, threads: Int): String? = executor.submit<String?> {
        if (embedLoaded == file.absolutePath) return@submit null
        runCatching { ensureBackend() }.exceptionOrNull()?.let { return@submit "The AI engine could not start: ${it.message}" }   // already on the engine thread
        val err = runCatching { LlamaBridge.nativeEmbedLoad(file.absolutePath, threads) }.getOrElse { "The embedding model could not start: ${it.message}" }
        embedLoaded = if (err == null) file.absolutePath else null
        err
    }.get()

    fun embedUnload() { executor.submit { if (embedLoaded != null) { LlamaBridge.nativeEmbedUnload(); embedLoaded = null } }.get() }

    /** One vector per text (blocking; call from a background thread). Throws when the model isn't loaded or fails. */
    fun embed(texts: List<String>): List<FloatArray> = executor.submit<List<FloatArray>> {
        check(embedLoaded != null) { "The embedding model isn't loaded." }
        texts.map { LlamaBridge.nativeEmbed(it.toByteArray(Charsets.UTF_8)) ?: error("The embedding model couldn't read a text.") }
    }.get()

    /** Peak resident memory of this process so far (VmHWM), in MB. */
    fun peakMemoryMb(): Int = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.filter(Char::isDigit).toInt() / 1024
    }.getOrDefault(-1)

    fun currentMemoryMb(): Int = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter(Char::isDigit).toInt() / 1024
    }.getOrDefault(-1)
}
