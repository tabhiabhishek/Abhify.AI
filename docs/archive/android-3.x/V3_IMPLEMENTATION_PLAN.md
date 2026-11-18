# Abhify.AI Android v3.0 implementation plan

**Baseline:** 2.0.0-rc.4 (uncommitted, on branch `feature/m2-knowledge-packaging`).

**Audit date:** 2026-11-04.

**Scope:** the native Android app in `android/`. The desktop/web app (`backend/`, `frontend/`) and `cloud/` are not changed by v3.0.

## 1. Existing architecture (audited)

| Area | RC.4 implementation |
|---|---|
| Build | AGP 8.13.2, Kotlin 2.3.0, Gradle 8.14.3 (pinned wrapper), JDK 17, NDK r28c, CMake 3.31.6, compileSdk/targetSdk 36, minSdk 29. Single `app` module. |
| UI | Jetpack Compose with Material 3. There is a single activity (`MainActivity`) and a hand-rolled route stack in `AppViewModel` (no navigation library). It has three tabs (Chat, Learn, News) plus a drawer. Theme tokens live in `Theme.kt` (web violet/pink, Inter). |
| State | One `AppViewModel` (725 lines) with Compose `mutableStateOf`. |
| Local AI | llama.cpp v0.7.0 (commit 620d2225) through JNI (`abhify_llm.cpp`). Qwen2.5 1.5B/0.5B Q4_K_M with pinned SHA-256; context 1024/2048/4096 per model. |
| Online AI | `providers/Providers.kt` has one OpenAI-compatible adapter for Gemini, Groq and OpenRouter. Keys are stored in a Keystore AES-GCM box. `HybridAi` routes Device, Personal and Server. |
| Data | `ChatDb` (SQLiteOpenHelper, **v4**). It holds conversations, messages, memories, quiz_answers, interview_views, sync tables and the reserved `document_metadata`. Migrations are additive (v2–v4). |
| Sync | Optional (off by default) last-writer-wins sync with conflict capture, through `cloud/`. |
| Documents (RC.4) | A single attachment per chat (TXT/MD/CSV/DOCX), extracted in memory and not saved. A consent dialog appears before it is sent online. |
| Tests | 91 JVM unit tests (3 opt-in skipped). In `androidTest`: `DbMigrationTest` and `OnDeviceE2ETest` (the latter deletes the model, so it is not to be run on the owner's phone). |
| Signing | Debug key `C:\Users\Administrator\.android\debug.keystore`, cert SHA-256 `1d2d1fdb…e6246f`. **No release keystore exists.** |

## 2. Incomplete-response root causes (RC.5, P0)

The full response lifecycle was traced: request → HTTP → SSE → accumulation → ViewModel → SQLite → Markdown → bubble.

| # | Cause | Where | Effect |
|---|---|---|---|
| R1 | Online requests hard-capped at **768 output tokens** | `HybridAi.personal()`, `HybridAi.server()` | Long answers stop mid-sentence. |
| R2 | Reasoning models spend output tokens on hidden "thinking": Gemini 3.x Flash, Groq `openai/gpt-oss-*`, many OpenRouter free models | Provider side | Within the 768-token cap, the visible answer is even shorter, or empty. |
| R3 | `finish_reason` is ignored. `"length"` is treated like `"stop"` | `ProviderRules.parseLine` | A cut-off answer is labelled and stored as complete. |
| R4 | A chunk that carries both the last text and `finish_reason` returns only the token | `parseLine` | The finish reason is lost even where it is sent. |
| R5 | Local generation capped at **512 tokens**, and the native `"stopped":"length"` is ignored | `LlmEngine.generate` default, `AppViewModel.answer` | Local answers stop silently. |
| R6 | No message status in the database | `messages` table | After a restart, a partial answer looks complete. |
| R7 | Auto-scroll goes to the *top* of the last item | `ChatTab` `scrollToItem(i)` | While streaming, the end of a long answer is below the fold, so it looks cut off. |

**Not causes (verified):**
- The database has no length limit: the column is TEXT and the whole `answer` string is written.
- `MarkdownText` has no `maxLines`.
- The bubble has no fixed height.
- Copy, Share and Read use `m.text`, which is the full text. Read aloud is capped at 3,000 characters by design; Read aloud is changed to read in parts.

**Fix:**
- Provider-specific output limits: Gemini 8192; Groq 4096 (Groq counts `max_completion_tokens` against the per-minute token limit); OpenRouter 4096. The limit is editable per provider.
- Local: 1024 tokens, capped by the context.
- Parse the finish reason and keep it.
- Store `messages.status` (v5).
- Show explicit states: complete, cut off (length), stopped, interrupted, failed, empty.
- **Continue generating:** for answers cut off by length, stopped, or interrupted with partial text. A continuation request goes to the same source with the partial answer as context. Overlapping text is removed. A message can be continued at most 3 times.
- Read more / Show less for long answers. This affects the view only.
- Scroll to the bottom while streaming.

## 3. Database v5 (additive only)

```
messages      + status TEXT (NULL = complete; old rows stay complete), + feedback INTEGER (NULL, 1, -1)
conversations + folder TEXT (NULL = Personal), + summary TEXT
library_docs  (id, uid, name, kind, size_bytes, chars, text, workspace_id, created_at, updated_at)   -- local only, never synced
flashcards    (id, deck, front, back, known, reviews, source, created_at, updated_at)
study_plans   (id, topic, level, goal, target_date, minutes_per_day, created_at, updated_at)
plan_items    (id, plan_id, position, text, done)
workspaces    (id, name, kind, created_at)                                                          -- 'personal' | 'work'
notes         (id, workspace_id, title, body, created_at, updated_at)
tasks         (id, workspace_id, title, done, due_at, created_at, updated_at)
```

- No table or column is dropped. Old rows are untouched.
- The new tables are local only: the sync code doesn't read them, and the export is extended explicitly.
- The v5 SQL lives in one object (`SchemaV5`). It is tested on a populated v4 schema with the JVM SQLite driver, and on the phone with `DbMigrationTest`.

## 4. Feature dependencies

- Document AI (summary, Q&A, quiz, flashcards, notes) needs: the library table, PDF extraction, chunking, and the generic consent-gated "AI task" runner.
- Learn flashcards from documents needs the library and the flashcards table.
- Work meeting notes → tasks needs the notes and tasks tables and the AI task runner.
- Citations need chunk ids kept through retrieval.
- Vector RAG would need an embedding model: a new GGUF download, about 30–130 MB. **Not in v3.0**; lexical (BM25) retrieval is used instead.
- OCR needs an engine choice that requires owner approval (see §8).

## 5. Security requirements

- Keys only in the Keystore box. Keys never go into logs, diagnostics, exports, sync or APK assets. A source and APK key scan runs on every build.
- Document text goes online only after the consent dialog, once per document per session, naming the provider.
- Documents and library content never enter memory automatically.
- Work data is local, on-device only. **There is no multi-user corporate backend.** Organisation identity, roles, permissions, audit history and revocation require a server (`cloud/` doesn't have them). They are marked "Requires backend" and are not shown as working.

## 6. Implementation phases

| Phase | Content | Done when |
|---|---|---|
| 1 RC.5 | Fixes R1–R7, Continue, statuses, Read more, feedback, DB v5 | Unit tests and APK build pass |
| 2 Navigation | Five tabs: Chat, Learn, Documents, Work, Settings. State kept per tab. Back handling. News moves into Learn. | Every tab works; none is a placeholder |
| 3 Documents | Library: add (TXT/MD/CSV/DOCX/**PDF**), view, rename, delete, search. AI summary, key points, action items, notes, quiz, flashcards, Q&A with section citations, comparison. | Extraction and retrieval unit-tested |
| 4 Learn | Tutor with levels, study plans with lessons, flashcards (review and known tracking), interview, quiz, news, progress | Tests for parsing and storage |
| 5 Work | Workspaces, notes, tasks, meeting-notes → action items (with confirmation), email draft, report | Local only, labelled |
| 6 Chat | Folders, summaries, feedback 👍/👎, export as TXT/Markdown | – |
| 7 Docs and build | V3 docs, version 3.0.0 / 3000099, debug APK signed with the RC.3/RC.4 key, scans, SHA-256 | Report |

## 7. Testing plan

- **JVM:**
  - parser finish reasons for each provider format;
  - client results (complete, length, interrupted);
  - HybridAi done status;
  - continuation overlap removal;
  - v5 migration SQL on sqlite-jdbc;
  - PDF extraction on a generated PDF;
  - chunking and BM25;
  - flashcard and plan parsing;
  - action-item parsing;
  - contrast.
- **Phone (pending owner):** `V3_ACCEPTANCE_CHECKLIST.md`, plus `DbMigrationTest` through the kit's `RUN-TESTS.cmd` (this does not touch the user's data).
- **Real providers:** the opt-in `RealProviderTest`, extended with a long-answer check. It runs only with the owner's keys in environment variables.

## 8. Known risks and decisions needing the owner

1. **Release signing:** there is no release key. v3.0 is delivered as `Abhify-3.0.0-debug.apk`, signed with the same debug key so it upgrades RC.4. A production release needs a release keystore. That key **cannot** upgrade debug installs; a change of key means a reinstall, which loses data unless the user exports first.
2. **OCR engine:**
   - ML Kit: proprietary, and it sends usage metrics to Google.
   - Tesseract: Apache-2.0, needs a 10–20 MB language-data download.

   The owner should choose. Until then, OCR and image attachments are "Requires user approval".
3. **PDF:** `pdfbox-android` 2.0.27.0 (Apache-2.0, last release 2023-01). It adds about 3 MB and reads text layers only; scanned PDFs need OCR.
4. **Web search and tool calling:** need a search provider with an API key (Brave, Tavily and so on), which is an owner decision. Not implemented.
5. **Corporate multi-user:** needs a backend with authorization. Not implemented; the Work tab is local only.
6. **The 0.5B/1.5B local model** produces weak structured output (quiz/flashcard JSON). Parsing validates the output and asks for a retry rather than accepting junk.

## 9. Release plan

1. `3.0.0-debug` APK and test kit.
2. Owner phone acceptance on top of RC.4: no uninstall, no data clearing.
3. Owner keys for the opt-in real-provider test.
4. Owner decisions on the §8 items.
5. Release signing setup.
6. `3.0.0` release build.
