# Abhify.AI v3.2: implementation plan

**Audit date:** 2026-11-05.

## 1. Repository findings (verified)

| Item | Finding |
|---|---|
| Git | Branch `feature/m2-knowledge-packaging`; last commit a9325d2 (dev.6). The whole `android/` app and `cloud/` are **uncommitted**, as reported before. Nothing is committed or pushed by this work. |
| Version | `3.1.0` / versionCode `3010099`. Scheme: major·10⁶ + minor·10⁴ + patch·10² + stage (final = 99). **3.2.0 → 3020099.** |
| Database | `ChatDb` v6 (v2–v4 inline, v5 `SchemaV5`, v6 `SchemaV6`; additive, sqlite-jdbc migration tests). |
| Baseline tests (re-run today) | 128 run, 0 failed, 3 opt-in skipped |
| Devices | `adb devices` empty; no emulator possible (WHPX disabled) |
| Signing | Debug key `~/.android/debug.keystore`, cert `1d2d1fdb…246f` (ANDROID_USER_HOME must stay unset) |

## 2. Existing features reused (no duplicates)

| Area | Existing implementation reused |
|---|---|
| Resume Assistant | `ResumeState` / `ResumeScreen` (route RESUME): extraction review, corrections, versions, AI actions, grounding check, JD exact match, export |
| AI task runner | `AppViewModel.aiTask`: routing, consent (Send / This phone / Cancel), cancel |
| Retrieval | `docs/Library` (chunks with pages, BM25, `[S#]` citations) |
| Study | `StudioState`: tutor, study plans, flashcards (decks), quiz from documents; `learn/Studio` strict parsers; the interview bank and AI interview/MCQ |
| Documents | library, OCR pipeline, document actions, ask-all-documents |
| Voice | `Voice` (recogniser, TTS in parts, stop), chat dictation into the input box |
| Work | workspaces, notes, tasks, meeting notes, email, report |
| Memory | `MemoryProfile`, `HeavyGate`, `AppGraph.withOcr` (OCR unloads Qwen; Qwen blocked during OCR) |

## 3. Gaps → implementation

| Phase | What is built |
|---|---|
| A | Audit only; no defects found in the setup and upgrade logic (unit tests pass). Kept unchanged. |
| B, Career | New **Career** tab: Resume (opens the existing assistant), **Jobs** (saved job descriptions plus the application tracker: create, edit, search, filter by status, delete with confirmation, link to a resume version), **Interview** (categories: technical, Python, AI/ML, projects (from the selected resume), behavioural; follow-up questions and answer outlines; **practice**: answer by typing or voice, AI feedback, saved attempts), and the existing question bank. |
| C, Study | New **Study** tab: sources (a library document, a note, or a typed topic) → explanation, summary (short summary, key points, terms), quiz (practice one question at a time, score, review incorrect), flashcards (previous, next, reveal), revision plan (existing plans). **Study history** (resume, review, delete). Plus the existing Tutor, Plans, Cards, Quiz bank and News. |
| D, Voice | TTS **pause and resume** (pause stops at the current part; resume restarts that part). Chat dictation is kept: preview, edit, then send. Voice answers in interview practice. Recognition is released when leaving. Audio is never stored. |
| E, Documents | Q&A over **selected** documents (one or several), with source passages and pages, and an honest "not in your documents" result. OCR is unchanged (ML Kit, opt-in). |
| F, Knowledge Vault | `vault_items`. Save from document results, resume versions, study sessions, quiz results and chat answers. Search, category, tags, favourites, rename, open, delete, recently used. Q&A over **selected** items with sources. |
| G, Sharing | Android share sheet only. Study result card (topic, score, number of questions). Resume-analysis share **redacts** emails, phones and links by default (a toggle includes them). |
| H, Navigation | Tabs: **Chat · Career · Study · Documents · Knowledge**. Settings moves to the header ⚙ (plus the drawer). Work tools move to Knowledge → Workspaces. Nothing is removed. |
| I, Database v7 | Additive: `job_descriptions`, `job_applications`, `interview_attempts`, `study_sessions`, `vault_items`, with indexes. |
| J, Memory | Order unchanged and tested. New study/vault/interview AI work goes through `aiTask`, so the local model obeys `HeavyGate`. |

## 4. Security, privacy and memory

- No new network endpoints and no new dependencies. All new data is local (SQLite v7) and not synced.
- Online use of resume, document, vault or study content always goes through the consent dialog.
- Shared text is redacted by default; there are no public profiles.
- Keys are untouched (Keystore). New code doesn't read the provider store.

## 5. Testing and build

- **JVM tests:**
  - v7 migration on a populated v6 database (sqlite-jdbc);
  - tracker validation, filter and search;
  - study quiz scoring and review, session encoding;
  - vault tags, search and Q&A scoping;
  - redaction;
  - TTS pause and resume queue;
  - interview prompts;
  - regression suite.
- **Build:** `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` with the existing wrapper and JDK 17. Then verify the APK (aapt2 badging, apksigner cert), compute SHA-256, assemble the kit.

## 6. Risks

| Risk | Detail |
|---|---|
| No device | UI, voice, OCR and memory behaviour can't be verified here. |
| Small local models | Weak structured output (quiz, cards). Strict parsers reject it, with a retry message. |
| Android TTS has no true pause | "Resume" restarts the current sentence group. Documented. |
