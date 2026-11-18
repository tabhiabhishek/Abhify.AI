# Abhify.AI comprehensive enhancement audit (2026-11-09)

Scope: desktop/web (FastAPI backend 2.0.0-dev.8 + PWA) and Android (4.6.1, versionCode 4060199, database v17), on branch `feature/m2-knowledge-packaging` (HEAD `a9325d2`, uncommitted work from the dev.8 / 4.6.x milestones, including the Unified Aurora Workspace).
This document records the baseline (before any change in this milestone), the root causes found, and, at the end, what was changed and what remains.

## 1. Baseline (before changes)

| Suite | Kind | Result |
|---|---|---|
| Backend `pytest backend/tests` | unit + integration (FastAPI TestClient, fake embeddings, no Ollama) | **347 passed, 1 skipped** (the opt-in live Ollama test), 2 deprecation warnings, 123 s |
| Android `testDebugUnitTest` | JVM unit + source-contract tests | **275 tests, 0 failures, 0 errors, 4 skipped** (opt-in) |
| Task Runner live reproduction (`task_repro.py`) | live Ollama 0.40.13, isolated data folder, real uploads | 11 of 12 document runs completed; **Quiz from document failed with llama3.2** on the Markdown note |
| Physical Android device | – | **none connected** (`adb devices` empty during the whole milestone) |

Live reproduction details (desktop, one multi-page PDF and one Markdown note uploaded through `/knowledge/upload`, both models installed locally):

| Model | Document | Summary | Study plan | Quiz |
|---|---|---|---|---|
| llama3.2:latest | calvin-cycle.md | completed 14.3 s | completed 7.9 s | **failed**: "The model didn't return a usable quiz" |
| llama3.2:latest | photosynthesis-notes.pdf | completed 8.3 s | completed 9.9 s | completed 7.3 s |
| qwen2.5:latest | calvin-cycle.md | completed 26.7 s | completed 14.6 s | completed 12.0 s |
| qwen2.5:latest | photosynthesis-notes.pdf | completed 13.0 s | completed 19.8 s | completed 12.5 s |

The failed quiz output was five good questions in almost-JSON: `"explanation": The Calvin cycle takes place in the stroma.` (string without quotes). The strict parser rejected the whole quiz.

## 2. Root causes found

### 2.1 Android: New private chat crashes (critical)
- **Path:** More → New private chat → `vm.setPrivate(true)` → `newChat()` → `stop()` → `g.engine.cancel()` → `LlamaBridge.nativeCancel()`.
- **Cause:** `nativeCancel` is a JNI method of `libabhify_llm`, and that library is loaded **lazily** (`System.loadLibrary` inside `LlmEngine.backend()`, only when a phone model is loaded or diagnostics run). Before that, the call throws `java.lang.UnsatisfiedLinkError` on the main thread, and the app crashes.
- **When it happens:**
  - every cold start before the first on-device answer;
  - always in Online / Auto mode with a provider, where the library is never loaded.
- **Same defect elsewhere:** the same unguarded call is in `cancelTask()`, used by Cancel in the Task Runner, Study, News AI and document tools, and by the Task Runner's coroutine cancellation.

### 2.2 Task Runner document workflows (high)
Desktop and Android share the same template design. News briefing works because:
- its source is fetched by the app (no user document);
- it has a single free-text AI step;
- on Android it is never "sensitive", so it never asks for consent.

The document workflows differ in each of those respects, and each difference has a defect.

| Workflow | Platform | Root cause |
|---|---|---|
| Quiz from document | both | The quiz step needs strict JSON. One malformed field (unquoted string, trailing comma, letter answer "B", answer as string "1", truncated output) rejects the entire quiz. The desktop doesn't use Ollama's schema-constrained output. Phone models (0.5B–1.5B, 1,024 output tokens, small context) produce strict JSON unreliably. |
| Summary / Study plan / Quiz | Android | Every AI step calls `vm.aiTask` with the document marked sensitive. With an online provider active, the consent dialog appears **once per step**: 3 times for a summary or study plan. Cancel stops the task ("Cancelled: nothing was sent"). "This phone" without a downloaded model fails. In practice the workflow keeps stopping. |
| Summary / Study plan / Quiz | Android | Task cancellation (and the step's coroutine cancellation) calls `cancelTask()`, which crashes as in 2.1 when the native library isn't loaded. |
| Document sources | Android | The Task Runner lists and loads documents with `docs()`, which only returns documents **outside** workspaces (`workspace_id IS NULL`). Documents imported into a workspace can't be chosen. |
| Study plan | both | Works, but the plan is a 5-line "day N" list with no objectives, sessions, revision or practice, and no document title. |
| Short / empty sources | both | Empty text is rejected. A very short text (a few words) still runs a 5-question quiz and a study plan, which invites invented content. |

### 2.3 Microphone chat latency
- **Desktop/web:**
  - The browser's Web Speech API (or Whisper on the backend in Private voice mode) produces the transcript.
  - The question then goes to `/chat`, a **non-streamed** LangGraph ReAct agent. The page waits for the whole answer, including any tool round and the model load when the model is cold.
  - Whisper itself is loaded lazily on the first transcription.
  - Read-aloud starts only after the complete answer and is cut to 600 characters.
- **Android:**
  - Android's recognizer fills the message box, and the user taps Send.
  - The phone model is loaded lazily on the first on-device answer (no preload), so the first voice question after a cold start also pays the model load.

### 2.4 Navigation
- **Android:** More holds the groups Workspace, AI, Documents & OCR, Data, Memory profile, System and Diagnostics. The Settings screen (route `SETTINGS`) is titled "Answer style & personalization" and only links to AI models and providers.
- **Web:** Performance and System & data are separate sidebar entries. The Settings dialog links only to Memory, System and Knowledge.
- **AI / system configuration** is therefore spread over three places.

### 2.5 News audio
- **Web:** `speak()` reads only the first 600 characters of a chat answer. There is no pause, resume, speed control, item navigation or News integration.
- **Android:** `Voice` already reads text in sentence parts with pause and resume (chat answers), but News has no Listen action.

### 2.6 Projects
- **Android:** has workspaces (`workspaces`, `notes`, `tasks`, `workspace_links`, `library_docs.workspace_id`):
  - create, rename, delete;
  - link documents, chats and memories;
  - "use as chat context".
  
  There are no project instructions, no archive/restore, no export, and no Task Runner or Study integration.
- **Desktop:** has no project concept.

## 3. Feature inventory (status before changes)

| Feature | Desktop/web | Android |
|---|---|---|
| Home (bento, customization, quick actions) | works | works |
| Chat (text) | works, not streamed | works, streamed |
| Chat microphone | works; slow path (2.3) | works; cold model load on first answer |
| New private chat | n/a (web has no private chat) | **crashes** (2.1) |
| Task Runner: News briefing | works | works |
| Task Runner: Summary / Study plan | works (live) | **unreliable** (2.2) |
| Task Runner: Quiz from document | **fails intermittently** | **fails often** |
| Study: Interview / Quiz | works | works |
| News Center | works | works |
| News audio | missing | missing |
| Documents & Knowledge | works (PDF/TXT/MD) | works |
| Memory | works | works |
| AI Models / Performance / System | work, scattered | work, scattered in More |
| Settings | dialog, partial | personalization only |
| Projects | missing | partial (workspaces) |

## 4. Results (end of the milestone)
| Area | Result | Details |
|---|---|---|
| New private chat crash | fixed: `LlmEngine.cancel()` calls the JNI method only once the library is loaded | [ABHIFY_TASK_RUNNER_FIXES.md](ABHIFY_TASK_RUNNER_FIXES.md) (shared root cause) |
| Document summary / study plan / quiz | fixed; live 24/24, 39/40 quotes verbatim | [ABHIFY_TASK_RUNNER_FIXES.md](ABHIFY_TASK_RUNNER_FIXES.md) |
| News audio | added on both platforms | [ABHIFY_NEWS_AUDIO.md](ABHIFY_NEWS_AUDIO.md) |
| Navigation | AI and System moved into Settings | [ABHIFY_NAVIGATION_SETTINGS.md](ABHIFY_NAVIGATION_SETTINGS.md) |
| Microphone latency | warm: first words 0.11–0.37 s (was 4–15 s); cold: 14.3 s (was 29.2 s) | [ABHIFY_CHAT_PERFORMANCE.md](ABHIFY_CHAT_PERFORMANCE.md) |
| Projects | both platforms, no schema change | [ABHIFY_PROJECT_WORKSPACE.md](ABHIFY_PROJECT_WORKSPACE.md) |

**Final tests:**
- **Backend:** see the master report.
- **Android:** 294 run, 0 failed, 4 skipped; lint 0 errors.
- **Browser:** stability 25/25, web 43/43, launcher 17/17, Task Runner 11/11, all live on Ollama.
- **Physical Android device:** not run.

**Deferred:** see [ABHIFY_MASTER_ENHANCEMENT_TRACKER.md](ABHIFY_MASTER_ENHANCEMENT_TRACKER.md).

## 5. Plan (as executed)
The work follows the prompt's milestone order:
1. Critical fixes: the private chat crash, then the three document workflows.
2. News audio.
3. Navigation into Settings.
4. Microphone latency, measured before and after.
5. The Project Workspace.
6. Feature audit, regression and builds.

No database schema change is planned:
- **Desktop:** projects live in `<data>/data/projects.json`, like `tasks.json`.
- **Android:** project instructions and archive state live in `filesDir/projects.json`, next to the existing workspace tables, which are reused unchanged.
