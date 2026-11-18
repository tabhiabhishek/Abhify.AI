package ai.abhify.android.docs

import ai.abhify.android.chat.ChatDb
import ai.abhify.android.chat.candidateChunks
import ai.abhify.android.chat.chunksWithVectors
import ai.abhify.android.chat.doc
import ai.abhify.android.chat.docMeta
import ai.abhify.android.chat.docText
import ai.abhify.android.chat.storeChunks
import ai.abhify.android.chat.storeVectors

/**
 * Retrieval over the document library (RAG): documents are split once into page-aware chunks and stored (with a
 * full-text index when the device has FTS4); a question finds candidate chunks among the SELECTED documents only and
 * ranks them with BM25. v3.6: when an approved [Embedder] is installed, chunks also get vectors and the ranking is
 * hybrid (BM25 + cosine, Reciprocal Rank Fusion). No embedder is installed in this build, so retrieval is keyword-only.
 */
object RagIndex {
    /** Splits and stores [docId]'s text (idempotent: replaces earlier chunks). Returns the chunk count. */
    fun index(db: ChatDb, docId: Long): Int {
        val d = db.doc(docId) ?: return 0
        val chunks = Library.chunks(docId, d.name, db.docText(docId).orEmpty())
        db.storeChunks(docId, chunks.map { Triple(it.index, it.page, it.text) })
        return chunks.size
    }

    /** Best sections for [question] among [docIds] (indexing any that were never indexed): BM25, or hybrid with [embedder]. */
    fun retrieve(db: ChatDb, docIds: Collection<Long>, question: String, k: Int = 8, embedder: Embedder? = null, minCosine: Float = 0.5f,
                 /** at most this many new chunks are embedded while you wait; the background index build does the rest */
                 embedNow: Int = 48): List<Library.Hit> {
        docIds.filter { db.docMeta(it).third == null }.forEach { index(db, it) }
        val words = Library.terms(question).distinct()
        val names = docIds.associateWith { db.doc(it)?.name ?: "document" }
        val chunks = db.candidateChunks(docIds, words).map { Library.Chunk(it.docId, names[it.docId] ?: "document", it.idx, it.page, it.text) }
        if (embedder == null) return Library.search(chunks, question, k)
        val keyword = Library.search(chunks, question, k * 3)
        val vectors = vectors(db, docIds, names, embedder, limit = embedNow)
        val q = Vectors.normalize(embedder.embed(listOf(question), query = true).single())
        return Hybrid.fuse(keyword, Hybrid.semantic(q, vectors, k * 3, minCosine), k)
    }

    /** All chunk vectors of [docIds] for [e], embedding only chunks that are new or whose text changed. */
    fun vectors(db: ChatDb, docIds: Collection<Long>, names: Map<Long, String>, e: Embedder, batch: Int = 16, limit: Int = Int.MAX_VALUE,
                isStopped: () -> Boolean = { false }): List<Pair<Library.Chunk, FloatArray>> {
        val rows = db.chunksWithVectors(docIds, e.id, e.version)
        val out = ArrayList<Pair<Library.Chunk, FloatArray>>(rows.size)
        val missing = rows.filter { it.vector == null || it.sha != Vectors.sha(it.text) }.take(limit)
        missing.groupBy { it.docId }.forEach { (doc, list) ->
            list.chunked(batch).forEach { part ->
                if (isStopped()) return@forEach                               // paused or cancelled: what's done is kept
                val vs = e.embed(part.map { it.text }, query = false).map(Vectors::normalize)
                db.storeVectors(doc, e.id, e.version, e.dims, part.zip(vs).map { (c, v) -> Triple(c.idx, Vectors.sha(c.text), Vectors.toBytes(v)) })
            }
        }
        val fresh = if (missing.isEmpty()) rows else db.chunksWithVectors(docIds, e.id, e.version)
        fresh.forEach { r -> r.vector?.let { out += Library.Chunk(r.docId, names[r.docId] ?: "document", r.idx, r.page, r.text) to Vectors.fromBytes(it) } }
        return out
    }
}
