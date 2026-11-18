package ai.abhify.android.docs

import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.Downloader
import ai.abhify.android.model.ModelCatalog
import java.io.File

/**
 * Semantic search on the phone (owner-approved 2026-11-17): bge-small-en-v1.5 (Q8_0 GGUF, 36 MB) from its own catalog
 * (assets/embedding_catalog.json, kept apart from the chat models), downloaded only on request with the existing
 * checksum-verified, resumable Downloader, run by llama.cpp as a second small context on the CPU.
 *
 * Every retrieval falls back to keyword search (BM25, the existing behaviour) whenever semantic search can't run: not
 * downloaded, turned off, memory pressure, battery saver, or the phone is hot. The embedding model is unloaded under
 * memory pressure and after use on low-memory profiles, so it and the chat model don't compete for RAM.
 */
class EmbeddingManager(private val catalogJson: String, private val dir: File, private val engine: LlmEngine, private val threads: () -> Int,
                       private val canRun: () -> String? /* null = ok, else why not (memory, battery saver, heat) */) {
    val model: CatalogModel = ModelCatalog.parse(catalogJson).models.single()
    val file get() = File(dir, "${model.id}.gguf")
    private val part get() = File(dir, "${model.id}.part")
    /** The cosine below which a chunk isn't "about" the question (bge-small, measured on the 31-query set: 0.588). */
    val minCosine = MIN_COSINE

    fun installed() = file.isFile && file.length() == model.sizeBytes

    /** Downloads and verifies (SHA-256) the model. Returns null on success, else why not. Resumable after interruption. */
    fun download(isStopped: () -> Boolean, progress: Downloader.Progress): String? {
        if (installed()) return null
        return when (val r = Downloader().download(model.url, model.sizeBytes, model.sha256, part, isStopped, progress)) {
            is Downloader.Result.Done -> { dir.mkdirs(); if (r.file.renameTo(file) || r.file.copyTo(file, overwrite = true).also { r.file.delete() }.exists()) null else "Couldn't save the model." }
            is Downloader.Result.Interrupted -> "Paused (${r.reason}). Tap Download again to continue."
            is Downloader.Result.Failed -> r.reason
        }
    }

    /** Removes the model file (the stored vectors stay; they are reused if the same model is downloaded again). */
    fun remove() { runCatching { engine.embedUnload() }; file.delete(); part.delete() }

    /** An [Embedder] ready to use, or null with nothing loaded (then retrieval is keyword-only). */
    fun embedder(enabled: Boolean): Embedder? {
        if (!enabled || !installed()) return null
        canRun()?.let { why -> android.util.Log.i("AbhifyRag", "semantic search skipped: $why"); unload(); return null }
        val t0 = System.currentTimeMillis()
        engine.embedLoad(file, threads())?.let { err -> android.util.Log.w("AbhifyRag", "embedding model didn't load: $err"); return null }
        if (System.currentTimeMillis() - t0 > 5) android.util.Log.i("AbhifyRag", "embedding model loaded in ${System.currentTimeMillis() - t0} ms")
        return Llama(engine)
    }

    fun unload() = runCatching { if (engine.embedLoaded != null) engine.embedUnload() }

    /** bge-small through the app's llama.cpp. Queries get bge's instruction prefix; documents don't. */
    class Llama(private val engine: LlmEngine) : Embedder {
        override val id = ID
        override val version = 1
        override val dims = 384
        override fun embed(texts: List<String>, query: Boolean): List<FloatArray> =
            engine.embed(texts.map { if (query) EmbeddingModels.BGE_QUERY_PREFIX + it else it })
    }

    companion object {
        const val ID = "bge-small-en-v1.5-q8_0"
        const val MIN_COSINE = 0.59f
    }
}
