# v3.6: optional on-device semantic search, evaluation and approval request

**Status:**
- Infrastructure is implemented and tested: the embedder interface, vector storage (DB v11), hybrid ranking and the settings screen.
- **No embedding model is downloaded, bundled or enabled. That needs the owner's approval.**
- The app still uses the v3.4 keyword search (BM25), unchanged.

## 1. Baseline (what exists)
- **Indexing.** Documents are split into page-aware chunks of about 1,000 characters (`doc_chunks`). Candidates come from the FTS4 index (any query word), ranked with BM25. Up to 8 cited sources are sent.
- **Limitation:** a passage that says the same thing in other words is missed (no shared word, so no candidate).

## 2. Is an embedding model already present?
| Model | Where | Usable for retrieval? |
|---|---|---|
| **Qwen2.5 1.5B/0.5B Instruct (phone)** | llama.cpp via JNI | **Not measured.** The dev PC's Ollama 0.40.13 refuses embeddings for generative models (HTTP 501), so it couldn't be evaluated. It is also a poor fit: (a) it is a chat model not trained for retrieval embeddings; (b) embedding every chunk with a 1.5B model costs far more than a 33M one; (c) the JNI layer holds **one** model/context, so indexing would compete with chat for the same context and RAM. |
| `nomic-embed-text` (F16, 274 MB) | Already installed in Ollama on the **dev PC** | Yes. **Measured below.** It is not on the phone. |

No embedding model exists on the phone.

## 3. Candidates for the phone (none downloaded)
All three run on the llama.cpp version already in the app (BERT and nomic-bert architectures), so no new runtime dependency is needed.

| Model | License | Params | Dims | Max tokens | GGUF size (approx.) | Extra RAM (approx.) |
|---|---|---|---|---|---|---|
| **bge-small-en-v1.5** (recommended) | MIT | 33 M | 384 | 512 | ~36 MB (Q8_0), ~67 MB (F16) | ~100–150 MB |
| all-MiniLM-L6-v2 | Apache-2.0 | 23 M | 384 | 256 | ~25 MB (Q8_0) | ~100 MB |
| nomic-embed-text v1.5 | Apache-2.0 | 137 M | 768 (Matryoshka: 64–768) | 2048–8192 | ~84 MB (Q4_K_M), 274 MB (F16) | ~300 MB |

These sizes are approximate published figures, to be confirmed against the exact file at download time.

Storage per vector: dims × 4 bytes, so **1.5 KB** at 384 dims and **3 KB** at 768. For 10,000 chunks (a large library) that is 15–30 MB.

All of these models are English-focused. Multilingual alternatives (for example multilingual-e5-small, ~118 M) are larger.

## 4. Evaluation
### 4.1 Setup
**Dataset:** `android/app/src/test/resources/retrieval_eval.json`, written for this evaluation.
- 5 technical documents: optimizers, transformers, containers, databases, Android background work.
- 30 sections, one chunk per section.
- 31 labelled queries: 8 exact, 10 paraphrased, 6 concept, 4 with **no answer** in the documents, 3 needing two documents.

**Harness:** `SemanticEvalTest` (opt-in: `ABHIFY_EMBED_EVAL=1`). It runs **the app's own** `Library.search` (BM25) and `Hybrid.fuse`/`Hybrid.semantic` code. Embeddings come from Ollama on the dev PC. The raw results are in [retrieval-eval/](retrieval-eval/).

**Metrics,** over the 27 answerable queries:
- Recall@5 and Precision@5. Precision@5 is capped at about 0.2 because most queries have exactly one relevant chunk.
- MRR.
- Hit@1: the first source, i.e. the one that becomes [S1].
- Sent@8: whether a relevant chunk is among the 8 sources the app would send. This is a proxy for whether a correct citation is possible.

For the 4 no-answer queries, the "returned nothing" rate is measured.

### 4.2 Results
| Method | Recall@5 | MRR | Hit@1 | Sent@8 | No-answer → nothing | Misses (answerable) |
|---|---|---|---|---|---|---|
| **Keyword BM25 (current app)** | 0.870 | 0.782 | 0.704 | 0.907 | 3/4 | 2 |
| Semantic only (nomic, threshold 0.632) | 0.852 | 0.809 | 0.778 | 0.852 | 4/4 | 4 |
| **Hybrid RRF (nomic, threshold 0.632)** | **0.944** | **0.836** | 0.741 | **1.000** | 3/4 | **0** |
| Hybrid RRF, no threshold | 0.944 | 0.838 | 0.741 | 1.000 | 0/4 | 0 |

By query type, Recall@5:

| Method | Exact | Paraphrase | Concept | Multi-document |
|---|---|---|---|---|
| Keyword | 1.00 | 0.90 | 0.67 | 0.83 |
| Hybrid (threshold) | 1.00 | 0.90 | 1.00 | 0.83 |

Precision@5 was 0.193 for keyword and 0.207 for hybrid.

**Misses:**
- Keyword missed *"How does the network know the order of words?"* (positional encoding) and *"Begin with a tiny step size and ramp it up…"* (warmup).
- Hybrid found both.
- Semantic-only missed four, including the exact query *"What is LoRA?"*. That is why semantic search must be **combined** with keywords, not replace them.

**Cosine distributions (nomic):**
- The best chunk for answerable queries scored at least 0.58 (mean 0.719).
- For no-answer queries the best chunk scored up to **0.622**.
- The two ranges **overlap**, so a cosine threshold alone cannot cleanly detect "not in the documents".
- The threshold of 0.632 was tuned **on this same set**, so it is optimistic.
- Without any threshold, hybrid always returns sources, even for "How do I bake sourdough bread?". The app's citation instruction ("say the documents don't cover this") remains the safeguard.

### 4.3 Latency and size
| Item | Measured | Note |
|---|---|---|
| BM25 per query (30 chunks) | 3.5 ms | Dev PC, JVM |
| Fusion per query | ~1 ms | Dev PC |
| nomic embedding, average per Ollama call | 1,421 ms | **Dev PC**, including the cold model load and HTTP. **Not representative of a phone.** |
| Indexing 30 chunks (nomic, batches of 8) | 30.2 s | Dev PC, same caveats |
| Vector storage (30 × 768 × 4 bytes) | 92,160 bytes | Exact |

**Phone latency and RAM were not measured,** because no model is on a phone. They must be measured after approval on the target device. bge-small is about 4× smaller than nomic, so it is expected to be faster, but this is unverified.

### 4.4 Interpretation
- On this small set, **hybrid search fixed every keyword miss** without hurting exact queries: Recall@5 went from 0.870 to 0.944 and Sent@8 from 0.907 to 1.000.
- The set is tiny (27 answerable queries). The differences are a few queries each, so treat them as **indicative, not statistically significant**.
- Results with bge-small (the recommended phone model) **will differ** from nomic and must be re-measured with the same harness. That is one command once the model is approved.

## 5. Implemented infrastructure (in 3.6.0)
| Part | File |
|---|---|
| `Embedder` interface, `Vectors` (normalize, cosine, float32 BLOB), `Hybrid` (RRF k = 60, semantic with threshold) | `docs/Retrieval.kt` |
| `chunk_vectors` table: doc_id, idx, model, model_version, dims, content_sha, vector, created_at; one row per chunk and model | `chat/SchemaV11.kt`, `chat/V36Db.kt` |
| Embedding only new or changed chunks (content SHA); vectors deleted with their document | `RagIndex.vectors`, `StudioDb.deleteDoc` |
| Hybrid path in `RagIndex.retrieve(…, embedder)`; **keyword path when `embedder == null`, which every caller passes** | `docs/RagIndex.kt` |
| Settings → Semantic search: what's active, the evaluated model and its costs, disabled "Download (needs approval)" | `ui/V36Screens.kt` |
| `EmbeddingModels.APPROVED = false`. A test checks that it's false, that no caller passes an embedder, and that no `.gguf` is bundled | `V36Test.kt` |

**Not implemented (blocked on approval):**
- the JNI embedding context (`llama_init_from_model` with `embeddings = true` and mean or CLS pooling) as a **second, small context** separate from Qwen's;
- the model download and SHA-256 verification (reusing the existing `Downloader`/`ModelStore`);
- background indexing via WorkManager.

## 6. Risks
- **RAM.** The embedding model and Qwen loaded together on a 6–8 GB phone. Mitigations: unload the embedder after indexing, and index while the chat model is idle (HeavyGate).
- **Battery and time** for indexing large libraries. Mitigations: incremental indexing, a charging constraint, a progress display.
- **Language:** the recommended model is English-only.
- **No-answer detection:** semantic search always finds a "nearest" passage. This needs a calibrated threshold and the existing "documents don't cover this" instruction.

## 7. Decision needed from the owner
1. **Approve downloading bge-small-en-v1.5 (Q8_0 GGUF, ~36 MB, MIT)** as an optional, user-started download. Choose a different candidate, or decline.
2. **Approve the native change:** a second llama.cpp context for embeddings only, in `cpp/abhify_llm.cpp`.
3. If approved:
   - re-run `SemanticEvalTest` with the chosen model;
   - measure phone latency and RAM on the Redmi K50i;
   - calibrate the threshold;
   - only then enable "Hybrid" in Settings.
