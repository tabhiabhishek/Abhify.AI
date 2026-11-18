package ai.abhify.android

import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV11
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.docs.Embedder
import ai.abhify.android.docs.EmbeddingModels
import ai.abhify.android.docs.Hybrid
import ai.abhify.android.docs.Library
import ai.abhify.android.docs.Vectors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class HybridRetrievalTest {
    private fun ch(doc: Long, i: Int, t: String) = Library.Chunk(doc, "d$doc", i, null, t)

    @Test fun vectorsAreNormalizedAndSerialized() {
        val v = Vectors.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f); assertEquals(1f, Vectors.cosine(v, v), 1e-6f)
        assertTrue(Vectors.fromBytes(Vectors.toBytes(v)).contentEquals(v))
        assertEquals(0f, Vectors.normalize(floatArrayOf(0f, 0f))[0], 0f)                         // no NaN for empty text
        assertTrue(runCatching { Vectors.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)) }.isFailure)   // different models never mix
        assertEquals(Vectors.sha("a"), Vectors.sha("a")); assertFalse(Vectors.sha("a") == Vectors.sha("b"))
    }

    @Test fun reciprocalRankFusionRewardsAgreementAndKeepsSingleListHits() {
        val a = ch(1, 0, "a"); val b = ch(1, 1, "b"); val c = ch(2, 0, "c")
        val fused = Hybrid.fuse(listOf(Library.Hit(a, 9.0), Library.Hit(b, 5.0)), listOf(b to 0.9f, c to 0.8f), 3)
        assertEquals(listOf(b, a, c), fused.map { it.chunk })                                    // b is in both lists
        assertTrue(Hybrid.fuse(emptyList(), emptyList(), 5).isEmpty())
        val q = Vectors.normalize(floatArrayOf(1f, 0f))
        val sem = Hybrid.semantic(q, listOf(a to Vectors.normalize(floatArrayOf(1f, 0.1f)), b to Vectors.normalize(floatArrayOf(0f, 1f))), 5, minCosine = 0.5f)
        assertEquals(listOf(a), sem.map { it.first })                                            // below the threshold → not a "source"
    }

    @Test fun aParaphraseMissedByKeywordsIsFoundByMeaning() {
        // toy embedder with a tiny synonym table: shows the mechanism, not real-model quality (that is SemanticEvalTest)
        val groups = listOf(setOf("memorise", "memorizing", "overfitting", "memorises"), setOf("examples", "training", "data", "set"), setOf("bread", "sourdough"))
        val e = object : Embedder { override val id = "toy"; override val version = 1; override val dims = groups.size
            override fun embed(texts: List<String>, query: Boolean) = texts.map { t -> val w = Library.terms(t).toSet(); FloatArray(groups.size) { g -> groups[g].count { it in w }.toFloat() } } }
        val chunks = listOf(ch(1, 0, "Overfitting happens when a model memorises noise of the training set."), ch(1, 1, "Kubernetes runs pods on a cluster."))
        val q = "how to stop memorizing examples"
        assertTrue(Library.search(chunks, q, 5).isEmpty())                                        // no shared word
        val vecs = chunks.zip(e.embed(chunks.map { it.text }, false).map(Vectors::normalize))
        val hits = Hybrid.fuse(Library.search(chunks, q, 5), Hybrid.semantic(Vectors.normalize(e.embed(listOf(q), true)[0]), vecs, 5, 0.5f), 5)
        assertEquals(listOf(chunks[0]), hits.map { it.chunk })
    }

    /** 5.0: bge-small was approved by the owner; it is downloaded only on request (pinned in the catalog), never bundled. */
    @Test fun theApprovedModelIsDownloadedOnRequestAndNeverBundled() {
        assertTrue(EmbeddingModels.APPROVED)
        val src = File("src/main/java/ai/abhify/android").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        assertTrue(src.none { it.contains("huggingface.co") && it.contains("bge-small") })                 // the URL lives only in the catalog asset
        assertTrue(File("src/main/assets").walkTopDown().none { it.name.endsWith(".gguf") })               // nothing bundled
    }

    @Test fun schemaV11AddsAnEmptyVectorTable() {
        v8Db().use { c ->
            c.createStatement().use { st -> SchemaV9.apply { st.execute(it) }; SchemaV10.apply { st.execute(it) } }
            c.exec("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at) VALUES ('d', 'a.pdf', 'PDF', 1, 5, 'Hello', 1, 1)")
            c.createStatement().use { st -> SchemaV11.apply { st.execute(it) } }
            assertEquals(0, c.one("SELECT COUNT(*) FROM chunk_vectors")); assertEquals("Hello", c.one("SELECT text FROM library_docs"))
            c.exec("INSERT INTO chunk_vectors (doc_id, idx, model, model_version, dims, content_sha, vector, created_at) VALUES (1, 0, 'm', 1, 2, 's', ?, 1)", Vectors.toBytes(floatArrayOf(1f, 0f)))
            assertTrue(runCatching { c.exec("INSERT INTO chunk_vectors (doc_id, idx, model, model_version, dims, content_sha, vector, created_at) VALUES (1, 0, 'm', 1, 2, 's', x'00', 1)") }.isFailure)   // one per chunk+model
        }
        assertTrue(File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText().contains("if (oldVersion < 11) SchemaV11.apply"))
    }
}

/**
 * Opt-in retrieval evaluation (ABHIFY_EMBED_EVAL=1, Ollama on 127.0.0.1:11434 with the models ALREADY installed on
 * the dev PC; nothing is downloaded). Runs the app's own BM25 and fusion code on src/test/resources/retrieval_eval.json
 * and writes build/retrieval-eval/<model>.json. Latencies are on the PC, not on a phone.
 */
class SemanticEvalTest {
    private class Ollama(val model: String, private val docPrefix: String, private val queryPrefix: String) : Embedder {
        override val id = model; override val version = 1; override var dims = 0
        var calls = 0L; var nanos = 0L
        override fun embed(texts: List<String>, query: Boolean): List<FloatArray> {
            val t0 = System.nanoTime()
            val c = URL("http://127.0.0.1:11434/api/embed").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.readTimeout = 600_000
            c.outputStream.use { it.write(JSONObject().put("model", model).put("input", JSONArray(texts.map { (if (query) queryPrefix else docPrefix) + it })).toString().toByteArray()) }
            val o = JSONObject(c.inputStream.use { String(it.readBytes()) })
            val a = o.getJSONArray("embeddings")
            calls++; nanos += System.nanoTime() - t0
            return (0 until a.length()).map { i -> val r = a.getJSONArray(i); FloatArray(r.length()) { r.getDouble(it).toFloat() } }.also { dims = it.first().size }
        }
    }

    private data class Q(val q: String, val type: String, val rel: Set<Pair<Long, Int>>)

    @Test fun evaluate() {
        assumeTrue("set ABHIFY_EMBED_EVAL=1 to run", System.getenv("ABHIFY_EMBED_EVAL") == "1")
        val data = JSONObject(File("src/test/resources/retrieval_eval.json").readText())
        val docs = data.getJSONArray("documents")
        val chunks = (0 until docs.length()).flatMap { d -> val o = docs.getJSONObject(d); val s = o.getJSONArray("sections")
            (0 until s.length()).map { i -> Library.Chunk(d.toLong(), o.getString("name"), i, null, s.getString(i)) } }
        val qs = data.getJSONArray("queries").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Q(o.getString("q"), o.getString("type"), o.getJSONArray("rel").let { r -> (0 until r.length()).map { r.getJSONArray(it).let { p -> p.getLong(0) to p.getInt(1) } }.toSet() }) } }
        val models = (System.getenv("ABHIFY_EMBED_MODELS") ?: "nomic-embed-text").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = File("build/retrieval-eval").apply { mkdirs() }
        val key = { c: Library.Chunk -> c.docId to c.index }

        fun metrics(name: String, run: (Q) -> List<Library.Chunk>): JSONObject {
            val rows = qs.map { q -> val t0 = System.nanoTime(); val r = run(q); Triple(q, r, (System.nanoTime() - t0) / 1e6) }
            val answerable = rows.filter { it.first.rel.isNotEmpty() }
            fun recall(r: List<Library.Chunk>, rel: Set<Pair<Long, Int>>, k: Int) = r.take(k).count { key(it) in rel }.toDouble() / rel.size
            fun prec(r: List<Library.Chunk>, rel: Set<Pair<Long, Int>>, k: Int) = r.take(k).count { key(it) in rel }.toDouble() / k
            fun rr(r: List<Library.Chunk>, rel: Set<Pair<Long, Int>>) = r.indexOfFirst { key(it) in rel }.let { if (it < 0) 0.0 else 1.0 / (it + 1) }
            val byType = answerable.groupBy { it.first.type }.mapValues { (_, l) -> JSONObject().put("n", l.size)
                .put("recall@5", l.map { recall(it.second, it.first.rel, 5) }.average()).put("mrr", l.map { rr(it.second, it.first.rel) }.average()) }
            val none = rows.filter { it.first.rel.isEmpty() }
            return JSONObject().put("method", name)
                .put("recall@5", answerable.map { recall(it.second, it.first.rel, 5) }.average())
                .put("precision@5", answerable.map { prec(it.second, it.first.rel, 5) }.average())
                .put("mrr", answerable.map { rr(it.second, it.first.rel) }.average())
                .put("hit@1", answerable.count { it.second.firstOrNull()?.let(key) in it.first.rel }.toDouble() / answerable.size)
                .put("sent@8", answerable.map { recall(it.second, it.first.rel, 8) }.average())                    // the app sends up to 8 sources
                .put("no_answer_returned_nothing", none.count { it.second.isEmpty() }.toString() + "/" + none.size)
                .put("ms_per_query", rows.map { it.third }.average())
                .put("by_type", JSONObject(byType as Map<*, *>))
                .put("misses", JSONArray(answerable.filter { rr(it.second, it.first.rel) == 0.0 }.map { it.first.q }))
        }

        val keyword = metrics("keyword (BM25)") { Library.search(chunks, it.q, 8).map { h -> h.chunk } }
        File(out, "keyword.json").writeText(keyword.toString(2)); println("KEYWORD " + keyword)

        for (m in models) try {
            val e = when { m.startsWith("nomic") -> Ollama(m, "search_document: ", "search_query: ")
                m.contains("bge") -> Ollama(m, "", ai.abhify.android.docs.EmbeddingModels.BGE_QUERY_PREFIX)        // the app's own prefix
                else -> Ollama(m, "", "") }
            val t0 = System.nanoTime()
            val vecs = chunks.zip(chunks.chunked(8).flatMap { part -> e.embed(part.map { it.text }, false) }.map(Vectors::normalize))
            val indexMs = (System.nanoTime() - t0) / 1e6
            val qv = qs.associate { it.q to Vectors.normalize(e.embed(listOf(it.q), true).single()) }
            // the cosine of the best chunk, answerable vs not: shows whether a "nothing relevant" threshold can work
            val best = qs.associate { q -> q.q to vecs.maxOf { Vectors.cosine(qv.getValue(q.q), it.second) } }
            val ans = qs.filter { it.rel.isNotEmpty() }.map { best.getValue(it.q) }; val non = qs.filter { it.rel.isEmpty() }.map { best.getValue(it.q) }
            val threshold = ((non.maxOrNull() ?: 0f) + 0.01f)                                                         // tuned on this set (optimistic)
            val res = JSONObject().put("model", m).put("dims", e.dims).put("chunks", chunks.size).put("index_ms_total_pc", indexMs)
                .put("vector_bytes_total", chunks.size * e.dims * 4)
                .put("best_cosine_answerable_min", ans.minOrNull()).put("best_cosine_answerable_mean", ans.average())
                .put("best_cosine_no_answer_max", non.maxOrNull()).put("threshold_used", threshold)
                .put("semantic_only", metrics("semantic only") { q -> Hybrid.semantic(qv.getValue(q.q), vecs, 8, threshold).map { it.first } })
                .put("hybrid_rrf", metrics("hybrid RRF") { q -> Hybrid.fuse(Library.search(chunks, q.q, 24), Hybrid.semantic(qv.getValue(q.q), vecs, 24, threshold), 8).map { it.chunk } })
                .put("hybrid_rrf_no_threshold", metrics("hybrid RRF, no threshold") { q -> Hybrid.fuse(Library.search(chunks, q.q, 24), Hybrid.semantic(qv.getValue(q.q), vecs, 24, -1f), 8).map { it.chunk } })
                .put("embed_calls", e.calls).put("embed_ms_avg_call_pc", e.nanos / 1e6 / e.calls)
            File(out, m.replace(Regex("[^\\w.-]"), "_") + ".json").writeText(res.toString(2)); println("MODEL " + res)
        } catch (e: java.io.IOException) {
            // e.g. Ollama refuses embeddings for a generative model (HTTP 501): record it instead of aborting the run
            File(out, m.replace(Regex("[^\\w.-]"), "_") + ".json").writeText(JSONObject().put("model", m).put("error", e.message).toString(2)); println("MODEL $m FAILED: ${e.message}")
        }
    }
}
