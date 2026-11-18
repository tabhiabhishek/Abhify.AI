package ai.abhify.android.docs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Optional semantic retrieval (v3.6). An [Embedder] turns text into vectors; chunks get vectors once (keyed by
 * position + content hash, so unchanged text is never embedded again) and a question is matched by cosine similarity.
 * [Hybrid] fuses that ranking with the existing BM25 ranking. Without an installed, user-approved embedding model
 * (the case in this build) none of this runs and retrieval is exactly the v3.4 keyword (BM25) search.
 */
interface Embedder {
    val id: String          // model identifier stored with every vector
    val version: Int        // bump when preprocessing changes (forces re-embedding)
    val dims: Int
    /** [query] = true for questions (some models want a different prefix for queries and documents). */
    fun embed(texts: List<String>, query: Boolean): List<FloatArray>
}

object Vectors {
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0; for (x in v) n += x * x
        val s = if (n > 0) (1.0 / Math.sqrt(n)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * s }
    }
    /** Cosine of two normalized vectors. */
    fun cosine(a: FloatArray, b: FloatArray): Float { require(a.size == b.size) { "dimension mismatch" }; var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()
    fun fromBytes(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

object Hybrid {
    /** Reciprocal Rank Fusion constant (Cormack et al. 2009 used 60); larger = flatter. */
    const val RRF_K = 60

    /** Chunks ranked by cosine to [query]; only those at or above [minCosine] (so "nothing relevant" stays possible). */
    fun semantic(query: FloatArray, chunks: List<Pair<Library.Chunk, FloatArray>>, top: Int, minCosine: Float): List<Pair<Library.Chunk, Float>> =
        chunks.map { (c, v) -> c to Vectors.cosine(query, v) }.filter { it.second >= minCosine }.sortedByDescending { it.second }.take(top)

    /**
     * Reciprocal Rank Fusion: score = Σ 1/(RRF_K + rank) over the keyword and the semantic list. Documented, simple, no
     * score calibration between BM25 and cosine needed. A chunk found by only one method still counts.
     */
    fun fuse(keyword: List<Library.Hit>, semantic: List<Pair<Library.Chunk, Float>>, k: Int): List<Library.Hit> {
        val key = { c: Library.Chunk -> c.docId to c.index }
        val score = LinkedHashMap<Pair<Long, Int>, Double>(); val chunk = HashMap<Pair<Long, Int>, Library.Chunk>()
        keyword.forEachIndexed { r, h -> score.merge(key(h.chunk), 1.0 / (RRF_K + r + 1), Double::plus); chunk[key(h.chunk)] = h.chunk }
        semantic.forEachIndexed { r, (c, _) -> score.merge(key(c), 1.0 / (RRF_K + r + 1), Double::plus); chunk[key(c)] = c }
        return score.entries.sortedByDescending { it.value }.take(k).map { Library.Hit(chunk.getValue(it.key), it.value) }
    }
}

/**
 * The embedding models evaluated for phones (see docs/V3_6_SEMANTIC_SEARCH_EVALUATION.md). Figures are published model
 * facts and approximate GGUF file sizes; none of these is downloaded or bundled — that needs the owner's approval.
 */
object EmbeddingModels {
    data class Candidate(val name: String, val source: String, val license: String, val params: String, val dims: Int, val maxTokens: Int,
                         val approxDownloadMb: Int, val approxRamMb: Int, val minCosine: Float /* provisional until calibrated on the eval set */, val note: String)
    val RECOMMENDED = Candidate("bge-small-en-v1.5 (Q8_0 GGUF)", "BAAI/bge-small-en-v1.5", "MIT", "33 M", 384, 512, 36, 120, 0.55f,
        "English only. Runs on the llama.cpp already in the app (BERT architecture), as a second, small context.")
    val ALTERNATIVES = listOf(
        Candidate("all-MiniLM-L6-v2 (Q8_0 GGUF)", "sentence-transformers/all-MiniLM-L6-v2", "Apache-2.0", "23 M", 384, 256, 25, 100, 0.35f, "Smallest; shorter context."),
        Candidate("nomic-embed-text v1.5 (Q4_K_M GGUF)", "nomic-ai/nomic-embed-text-v1.5", "Apache-2.0", "137 M", 768, 2048, 84, 300, 0.63f,
            "Measured on the dev PC (F16 via Ollama); threshold 0.63 tuned on the 31-query set. Larger; long context."),
    )
    /** bge models want this instruction in front of queries (not documents). */
    const val BGE_QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
    /** Approved by the owner on 2026-11-17: bge-small-en-v1.5 Q8_0, downloaded only when the user taps Download. */
    const val APPROVED = true
}
