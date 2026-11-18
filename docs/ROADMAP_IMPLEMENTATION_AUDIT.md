# Roadmap v3.5–v4.1: repository audit

**Date:** 2026-11-05.

**Starting point:** Android 3.4.0 (versionCode 3040099), uncommitted on branch `feature/m2-knowledge-packaging`. Everything below was checked in the source, not taken from earlier reports.

## Baseline
- **Build:** AGP 8.13.2, Kotlin 2.3.0, Gradle 8.14.3, JDK 17. compileSdk/targetSdk 36, minSdk 29. Jetpack Compose UI with a single activity. Navigation uses `AppViewModel.Route` (screens) plus `Tab` (Chat, Career, Study, Documents, Knowledge; Settings is opened from the header).
- **Database:** `ChatDb` (`SQLiteOpenHelper`, **not Room**), version **9**. The migrations are `SchemaV5`…`SchemaV9`, all additive and gated by version. The FTS4 index (`SearchIndex`) is created in `onOpen`, outside the versioned migrations, with a LIKE fallback.
- **AI:**
  - **Local model:** Qwen2.5 1.5B/0.5B Q4_K_M via llama.cpp, through JNI (`cpp/abhify_llm.cpp`). The native code holds **one global model and context**.
  - **Routing:** `HybridAi` with `RoutePolicy`, the consent dialog in `aiTask`/`send`, and `HeavyGate` (RAM safety).
  - **Online providers:** Gemini, Groq and OpenRouter, using the user's own keys, which are encrypted with the Keystore (`ProviderStore`).
- **Background work:** WorkManager 2.11.2 is **already a dependency**. It is used only by `SyncWorker` (opt-in cloud sync).
  - Drive uploads run in `viewModelScope`, with a byte queue in `files/drive_queue/<id>.bin`.
  - Conflicts are held in memory as a closure, so they are lost if the app is closed.
- **Signing:** debug only; certificate SHA-256 `1d2d1fdb…6246f`. No release keystore exists.
- **Tests:** 169 JVM tests, 0 failed, 3 skipped (live-network tests). 5 androidTest classes, built but never run on a phone. Lint: 0 errors.
- **Build output:** `build/Abhify-3.0.0` … `Abhify-3.4.0-debug.apk`, with the test kits beside them.

## Feature status against the roadmap

| Roadmap item | Already present | Missing | Reusable |
|---|---|---|---|
| **3.5 Background uploads** | Resumable Drive upload (in-process), MD5 dedupe, conflict detection, `drive_files` status, local byte queue, Retry button | Uploads that survive app death; persisted conflict choice; persisted resumable session; attempt limits and backoff; network constraints; cancellation | `DriveApi`, `DriveAuth` (its silent `authorize` also works from a Worker), `drive_files`, the queue folder, the `SyncWorker` pattern |
| **3.5 Scheduled backups** | Manual backup to a file or Drive, encryption, a `backups` history table (latest 20) | Schedule (daily/weekly/time), an automatic destination, retention, next-run status, failure reason | `Backup`/`BackupIo`/`AndroidRows`, `KeystoreBox` (to store the scheduled backup's passphrase) |
| **3.6 Semantic search** | Page-aware chunks (`doc_chunks`), FTS4 candidates, BM25 ranking (`Library.search`), citations | Embedding model (not approved), vector storage, hybrid ranking, evaluation | `RagIndex`, `Library`; llama.cpp can run BERT-type embedding GGUFs, but the JNI layer supports only one model at a time |
| **3.7 Career** | Complete since 3.3: taxonomy (133 skills), match engine, résumé quality, versions, tracker, interview practice with validated feedback, DOCX/PDF export | Only small gaps (see `V3_7_CAREER_MAINTENANCE.md`) | All of it |
| **3.8 Memory** | Categories, importance, expiry, `source`, duplicate groups and merge, sensitive-data checks, approval of suggestions, relevant-only retrieval (≤6 memories, ≤900 characters), export | Contradiction detection, review queue (keep / replace / keep both / merge), provenance UI (open the source chat), recency and conversation-aware ranking, dashboard | `MemoryKit`, `MemoryState`, the `memories` table |
| **3.9 Study** | Flashcards (`known` and `reviews` only), study plans with lessons (topic, level, goal, target date, minutes per day), study sessions with score/total, quizzes, explanations, streak | Spaced-repetition scheduling, review log, plan difficulty and documents, gap analysis, dashboard | `StudioDb`, `LifeDb`, `StudyKit`, `Studio.plan`, `parseList`, `aiTask` |
| **4.0 Workspace** | Workspaces with notes and tasks; documents have `workspace_id`; "Save as note"; vault; history and library search | Unified search across all sources, workspace links (conversations, memories), bounded context orchestrator, cross-feature actions | All the existing search functions |
| **4.0 Production readiness** | None (debug only) | Evaluation document only (approval-gated) | none |
| **4.1 Fine-tuning** | The empty `Model Training Data` Drive folder name | Research, plus a dataset safety validator (no training) | `MemoryKit.sensitive` |

## Database plan (as built)
All changes are additive. Each version is gated by `oldVersion < N`. **Planned vs built:** the Career phase needed a column, so the later versions moved up by one.

| DB version | App version | Change |
|---|---|---|
| 10 | 3.5 | `drive_files`: mode, attempts, session, mime, queued_at (+ index). `backups`: auto, uri, status, error. |
| 11 | 3.6 | `chunk_vectors` (doc_id, idx, model, model_version, dims, content_sha, vector, created_at). Empty without an approved model. |
| 12 | 3.7 | `interview_attempts`: jd_id, score (+ index). |
| 13 | 3.8 | `memories`: last_used_at, use_count, reviewed_at. `memory_dismissed`. |
| 14 | 3.9 | `flashcards`: due_at, interval_days, ease, lapses, last_reviewed (+ index). `card_reviews`. `study_plans.doc_ids`. |
| 15 | 4.0 | `workspace_links` (unique per workspace, kind and reference). |
| — | 4.1 | No schema change. |

## New dependencies
None are planned. WorkManager, Play services auth, JSON and the crypto APIs are already present.

## Risks

**Performance**
- Background work on low-RAM phones: workers never load the LLM, and upload chunks are 1 MB.
- Retention runs only after a successful backup.
- Vectors would add about 1.5 KB per chunk at 384 dimensions (float32). The table stays empty without an approved model.

**Security**
- **Scheduled backups to Drive** run unattended. They therefore **require encryption**: the passphrase is stored encrypted with the Keystore (`KeystoreBox`), like provider keys.
- **Background Drive access** uses only silent authorization. If Google needs the user (consent revoked), the item stops with "Connect again", with no loop and no notification spam.
- **Retention may delete** only Drive files/documents that Abhify recorded as its own scheduled backups. It never deletes the newest one.

## Approvals required (not given; nothing done for them)
1. Download or bundle an embedding model (3.6), for example bge-small-en-v1.5, about 35–70 MB.
2. Native (JNI) support for a second, embedding-only model context.
3. Production signing key and release (4.0).
4. Any fine-tuning or training run (4.1).
5. Any external service (job search, hosted training).
