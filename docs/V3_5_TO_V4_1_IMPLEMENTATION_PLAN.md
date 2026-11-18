# v3.5 → v4.1: implementation plan

The phases are built in order. Each phase ends with:
- JVM tests and lint;
- a clean debug build `build/Abhify-<version>-debug.apk`;
- versionCode = major·1e6 + minor·1e4 + patch·100 + 99.

Database versions are listed in [ROADMAP_IMPLEMENTATION_AUDIT.md](ROADMAP_IMPLEMENTATION_AUDIT.md).

| Phase | Version | Code | DB |
|---|---|---|---|
| 1 | 3.5.0 | 3050099 | 10 |
| 2 | 3.6.0 | 3060099 | 11 |
| 3 | 3.7.0 | 3070099 | 12 (planned 11; one column was needed) |
| 4 | 3.8.0 | 3080099 | 13 |
| 5 | 3.9.0 | 3090099 | 14 |
| 6 | 4.0.0 | 4000099 | 15 |
| 7 | 4.1.0 | 4010099 | 15 |

## Phase 1: background uploads and scheduled backups (3.5)

**Upload queue (`drive/DriveQueue.kt`).** This is pure logic over a `QueueStore` interface, so it can be tested on the JVM.
- **Each item** has its bytes in `drive_queue/<id>.bin`, a mode (normal / replace / keep-both), attempts, the resumable session URI and the mime type.
- **`process()` outcomes:**
  - Done / AlreadyThere → synced, bytes deleted.
  - Conflict → stored, waits for the user.
  - Auth → stops everything with "Connect again".
  - Transient error → retry later.
  - More than 6 attempts → failed, with a Retry button.
- **Resuming.** `DriveApi.upload` gets `session`/`onSession`, so an interrupted upload continues from Drive's reported offset after a restart.

**Upload worker (`DriveUploadWorker`).** A CoroutineWorker with unique work `abhify-drive-upload` and the policy `APPEND_OR_REPLACE`.
- Network required (Wi-Fi only if the user chose that). Exponential backoff from 30 s.
- The token comes from a silent `DriveAuth.authorize`. A consent need becomes an auth stop.
- `setProgress` reports progress. Cancel uses `cancelUniqueWork` and marks the remaining items "cancelled", without deleting their bytes.

**UI.** `DriveState.upload` enqueues the item and schedules the worker. The conflict dialog is built from the stored row, so it survives restarts. A queue section shows Retry, Cancel and Remove.

**Scheduled backups (`backup/Schedule.kt` + `ScheduledBackupWorker`).**
- **Configuration** is stored as JSON in prefs: enabled, daily/weekly, preferred hour, categories, destination (Drive / a folder the user chose / both), keep N (1–30), Wi-Fi only.
- **Passphrase.** Required for Drive and stored with KeystoreBox.
- **Scheduling.** Unique periodic work `abhify-backup-scheduled` (UPDATE policy) with an initial delay to the next preferred hour. The next run is shown as "about".
- **Destinations.** The Drive upload goes through the same queue. The local folder is written via SAF with a persisted permission (`DocumentsContract.createDocument`).
- **Retention (`Retention.select`).**
  - It considers only rows in `backups` with auto = 1, the same destination and status ok.
  - It always keeps the newest backup and at most N in total.
  - Trashing on Drive happens only for recorded drive_ids; local deletion only for recorded document URIs.

**Tests:**
- Queue state machine with the fake Drive: resume across a simulated restart (persisted session), attempt limit, auth stop, conflict persistence and resolution, cancel, duplicates.
- Schedule maths (next run, initial delay).
- Retention (never deletes the newest or unknown files).
- Unique-work and constraint configuration (source checks), and the v10 migration.

## Phase 2: semantic search infrastructure (3.6)
- **`docs/Retrieval.kt`.** The `Embedder` interface (`id`, `dims`, `embed`) and a `VectorMath` helper (normalise, cosine, float↔bytes). `HybridRanker` combines BM25 and vector rankings with Reciprocal Rank Fusion (k = 60).
- **Off by default.** `Retrieval.mode()` is `KEYWORD` unless an approved embedder is installed. None is, so the app behaves exactly as in 3.4.
- **`chunk_vectors` table (v11).** `RagIndex.retrieve` uses hybrid ranking only when the vectors for that model/version exist.
- **Settings → Semantic search.** Shows "Not installed: needs your approval", the evaluated candidate, size and RAM. There is no download button; the action is disabled.
- **Evaluation.** A dataset of technical documents and labelled queries (`test/resources/retrieval_eval.json`) and the opt-in test `SemanticEvalTest` (`ABHIFY_EMBED_EVAL=1`). It runs the shipped BM25 and fusion code with embeddings from the models **already installed** on the dev PC (Ollama `nomic-embed-text`; the phone's Qwen2.5-1.5B GGUF imported locally). It reports Recall@5, Precision@5, MRR, citation hits, no-answer behaviour and latency. Results go into `V3_6_SEMANTIC_SEARCH_EVALUATION.md`.

## Phase 3: Career maintenance (3.7)
- Audit plus regression tests across the parser, match engine, tracker and export.
- Fix only gaps found in the audit.

## Phase 4: advanced memory (3.8)
- **`MemoryKit.review()`.** Finds duplicates, similar memories, outdated ones (expired, or last used more than 180 days ago with low importance) and contradictions (same "slot" with a different value, for example "prefers X" vs "prefers Y", "lives in A" vs "lives in B").
- **Review screen.** Choices: keep existing / replace / keep both / merge. Nothing happens automatically. Adding a memory that conflicts with an existing one opens the same choice.
- **Ranking.** The retrieval score adds recency, the current conversation's last turns and use count. The bounds stay at 6 memories and 900 characters. `last_used_at` and `use_count` are updated when a memory is used.
- **Dashboard.** Counts, categories, expiring soon, duplicates, conflicts, recently updated, size, last backup.
- **Provenance.** Shows the stored source (chat, document, manual, suggestion, restore) with an "Open chat" link when the chat still exists. Nothing is fabricated.

## Phase 5: Study (3.9)
- **Spaced repetition (`learn/Srs.kt`).** A transparent SM-2-style scheduler with Again / Hard / Good / Easy and a capped interval. Reviews are recorded in `card_reviews`. Old cards get due_at = now.
- **Plans.** Difficulty, selected documents (their names go into the prompt), validation (at most minutes/day × days until the target; lesson count bounded) and manual editing of lessons.
- **Gap analysis (`StudyInsights`).** Uses quiz accuracy per topic, cards with lapses, overdue cards and topics not studied for 14 days or more. Each item gets a reason sentence.
- **Dashboard.** Review queue count, active plans and progress, quiz history, card statistics, recommended next session.
- **Reminders.** None (no notifications are added). This is documented.

## Phase 6: unified workspace (4.0)
- **`workspace/UnifiedSearch.kt`.** Local search across conversations, memories, documents, notes, vault, study sessions, cards and career data. Results are typed and labelled with their source, and the search never goes online.
- **`workspace_links` (v15).** Attach conversations, documents and memories to a workspace.
- **`ContextOrchestrator`.** Builds bounded context from explicitly chosen sources (workspace notes, linked documents, memories) with character budgets. It is shown in the context bar and passes through the existing consent rules.
- **Cross-feature actions.** Answer → study note / flashcards; document → study plan; passage → memory suggestion (approval); search → open.
- **`V4_0_PRODUCTION_READINESS.md`.** Evaluation only.

## Phase 7: fine-tuning research (4.1)
- **`V4_1_FINE_TUNING_RESEARCH.md`.**
- **`training/DatasetPolicy.kt`.** Validates a user-selected export: sensitive-data filter, a minimum number of examples, deduplication and a version manifest. Pure logic and tests only.
  - No export UI that touches Drive. No training. No new dependencies.

## Acceptance
See [ROADMAP_ACCEPTANCE_CHECKLIST.md](ROADMAP_ACCEPTANCE_CHECKLIST.md). Phone and real-Drive checks stay **pending** until they are run.
