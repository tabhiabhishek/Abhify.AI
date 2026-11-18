# Abhify.AI: local RAG, personal memory and controlled learning (Android, 2026-11-17)

Status: implemented in code and JVM tests. **Physical-device testing: not performed** (no phone was connected during this work).
Android version unchanged (5.0 / 5000099). Database schema unchanged (v17). No release APK was built.

## 1. What existed and was reused

| Piece | Where | Reused as |
|---|---|---|
| Page-aware chunking, BM25, `[S1]` citations, "cite or say not found" prompt | `docs/Library.kt` | unchanged; keyword half of hybrid search |
| Chunk table + FTS4, vector table keyed by model/version/text checksum | `chat/SchemaV9`, `SchemaV11`, `V36Db.kt` | vector store (no schema change) |
| `Embedder` interface, normalisation, RRF fusion, evaluation harness | `docs/Retrieval.kt`, `V36Test.kt` | the approved model plugs into it |
| Personal memory: relevance, duplicates, contradictions, sensitive-data check | `memory/MemoryKit`, `MemoryReview` | candidate checks for corrections |
| Consent before personal content goes online; explicit routing | `AppViewModel.send` / `docConsent` | knowledge consent (separate key) |
| Memory-pressure gate and device profiles | `HeavyGate`, `MemoryPolicy` | embedder run conditions |
| Resumable, checksum-verified downloader | `model/Downloader.kt` | embedding model download |
| Projects (instructions + linked documents) | `ProjectState` | collections |

Desktop (FastAPI + Chroma + nomic-embed-text through Ollama) already had semantic RAG; this milestone brings the phone to parity.

## 2. Architecture (phone)

```
question ─► Knowledge Chat mode (Normal / Auto / Knowledge)
              │ Auto: only questions about "my notes / my resume / <document name>"
              ▼
   RagIndex.retrieve(all documents)        Knowledge items (BM25)     relevant memories (MemoryKit)
     keyword: FTS4 candidates → BM25          up to 2 passages            unchanged
     semantic: bge-small (on phone) → cosine ≥ 0.59
     fuse: Reciprocal Rank Fusion (k = 60)
              ▼
   context builder: [S1]… passages, fenced as reference material, "cite or say it isn't in your knowledge"
              ▼
   Qwen2.5 on the phone (default)  — or an online provider only if "Online answers may use my knowledge"
                                     is on AND you agreed in this chat; otherwise the question goes alone
              ▼
   answer + "Sources sent: [S1] ✓ …", or "📚 Nothing in your knowledge matched this question"
```

- **Embedding model:** bge-small-en-v1.5 Q8_0 GGUF (MIT, 384 dims, 36,685,152 bytes, SHA-256 `f046db1d…6974804`, Hugging Face revision pinned in `assets/embedding_catalog.json`). It runs through the same llama.cpp library as Qwen (`nativeEmbedLoad/nativeEmbed`, CPU only, 512-token context), on the engine's single worker thread, so it never runs at the same time as generation.
- **Download:** only when you tap Download in Knowledge Center; resumable, verified by size and checksum, stored in `noBackupFilesDir/models/embedding`. Never bundled, never automatic.
- **Run conditions:** the embedder is skipped (keyword search continues) on memory pressure or the LOW memory profile, in battery saver, or at thermal status SEVERE or above. The reason is logged under `AbhifyRag`.
- **Incremental indexing:** each chunk's vector is stored with the SHA-256 of its text; only new or changed chunks are embedded. A question embeds at most 48 missing chunks while you wait; Knowledge Center → Build index does the rest, pausable, continuing where it stopped. The model is unloaded after a build (about 120 MB freed).
- **Collections:** projects (choose a project in chat to ask only its documents); ticked documents; the whole library in Knowledge mode.

## 3. Controlled learning

`✎ Correct` under an answer (not in private chats) opens a box for the right information. It becomes a **candidate** in `filesDir/knowledge_updates.json`, never knowledge by itself. Each candidate is classified with the existing memory rules:
- **New:** approving adds a memory in category Corrections.
- **Duplicate:** approving adds nothing.
- **Updates existing:** it contradicts a memory ("lives in Pune" → "lives in Bengaluru"); approving replaces that memory and keeps the old text.

Sensitive-looking text (password, card, phone…) is flagged before you decide. Knowledge Center lists pending corrections (Approve / Reject) and a history where approved changes can be **reverted** (restore the old text, or delete the added memory). AI answers, web and news text are never turned into knowledge automatically, and no model weights change.

## 4. Knowledge Center (Settings → AI → Knowledge Center)

Knowledge Chat mode · "Online answers may use my knowledge" (off by default; turning it off withdraws consents) · counts of documents, knowledge items, saved news, memories, passages and vectors · semantic model card (download with progress and pause, on/off, remove) · index build with progress and pause, per-document Reindex, Clear vectors · corrections to review · knowledge history with revert. The chat's context bar has a `📖 Knowledge: <mode>` chip that cycles the mode.

## 5. RAG quality (measured on the dev PC, same GGUF; `retrieval_eval.json`, 31 queries incl. 4 with no answer)

| Method | Recall@5 | MRR | Answer in the 8 passages sent | No-answer queries correctly empty |
|---|---|---|---|---|
| Keyword (BM25, current default without the model) | 0.870 | 0.782 | 0.907 | 3/4 |
| bge-small, semantic only | 0.926 | 0.889 | – | 4/4 |
| **bge-small + keyword (RRF), as shipped** | **0.981** | 0.861 | **1.000** | 3/4 |
| nomic-embed-text + keyword (desktop model, for comparison) | 0.944 | 0.836 | – | – |

- Threshold 0.59: lowest answerable similarity 0.591, highest no-answer 0.578. The margin is narrow, so the prompt also instructs the model to say when the passages don't contain the answer, and an empty retrieval shows "Nothing in your knowledge matched".
- Limitations: the evaluation set uses fixed sections, not the app's chunker, so chunk size was not tuned. End-to-end answer faithfulness with Qwen 1.5B on the phone was not measured.

## 6. Performance

- PC (indexing the evaluation corpus): bge-small 6.7 s vs nomic-embed-text 22.1 s.
- Phone (Redmi K50i, 8 GB): **not measured**. Expected: about 120 MB extra while the embedder is loaded; it shares the CPU worker with Qwen, so indexing pauses answers and vice versa. Logs to read on a phone: `adb logcat -s AbhifyRag` (retrieval time per question, index build time, skip reasons).

## 7. Security and privacy

- Retrieval, embeddings, the vector store and the correction log are on the phone; the model and vectors live in no-backup storage.
- Knowledge passages go online only with the switch on **and** per-chat consent; otherwise online answers get the question only, and the answer says "🔒 Your knowledge stayed on this phone".
- Only what you add is indexed (no device scan). The citation instruction tells the model that sources are reference text and that instructions written inside them must never be followed (added this milestone; a small model may still be influenced, so the source list stays visible).
- No API keys or credentials enter chunks, vectors or metadata; corrections that look sensitive are flagged; private chats offer no Correct action.
- Backup: the app's JSON files (tasks, projects, career MCQs and questions) are now included on Android and desktop. Restore writes only known file names that are absent (existing data is never overwritten); project keys are remapped between phones.

## 8. Tests

- Android JVM: 320 passed, 0 failed, 4 skipped (11 new in `V51Test.kt`: modes, merge, candidates, history store, catalog pin, no automatic download, privacy contracts, app-files backup; v3.6 "no model approved" test updated to "approved, downloaded on request, never bundled").
- `assembleDebug` (native embedding code) and lint: OK (0 errors).
- Backend: 389 passed, 2 skipped (the dev.2-installer packaging test, after the approved cleanup of old installers, and the live Task Runner test).

## 9. Still to check on a phone

Download and checksum on mobile data/Wi-Fi; Build index time for a real library; answer latency with and without the embedder; Knowledge mode answers with citations; Correct → Approve → Revert; backup and restore of app files between two phones.
