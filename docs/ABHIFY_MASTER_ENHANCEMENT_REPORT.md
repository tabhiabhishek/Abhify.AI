# Abhify.AI enhancement report: stability milestone and master program (2026-11-09)

This report covers two requests:
- the **stability milestone**, which is complete except physical-device validation;
- the **20-area master program**, which was started: its Stage 1 overlaps the milestone and is done, and every other area has an honest status in [ABHIFY_MASTER_ENHANCEMENT_TRACKER.md](ABHIFY_MASTER_ENHANCEMENT_TRACKER.md).

## A. Executive summary
**Fixed:**
- the Android New private chat crash;
- Document summary, Document to study plan and Quiz from document (both platforms);
- web pages stacking when opened from Settings.

**Added:**
- News Listen (both platforms);
- the Project Workspace (both platforms, no database change);
- streamed chat answers with Stop (web);
- voice warm-up (both);
- the AI/System consolidation into Settings (both);
- quiz settings, score and export;
- an improved `start.bat`.

**Measured:** the voice pipeline before and after, on desktop.

**Not done:**
- **Physical Android validation:** no device connected.
- **Master areas not started:** Backup (18), AI evaluation (19), Automation (20).
- **Master areas deferred:** Home dashboard, documents diagnostics, memory suggestions, the models centre, system diagnostics, palette search.

## B. Root causes
| Bug | Root cause | Fix | Files | Regression tests |
|---|---|---|---|---|
| New private chat crash (Android) | `newChat() → stop() → LlmEngine.cancel() → LlamaBridge.nativeCancel()`. The JNI library loads lazily, so before the first on-device answer (and always in online mode) the call threw `UnsatisfiedLinkError` on the main thread. The same applied to `cancelTask()`. | `cancel()` runs only after the library is loaded (`nativeReady`) | `llm/LlmEngine.kt` | `PrivateChatCrashTest`: reproduces the error, checks the guard on every cancel path and the private-chat state |
| Quiz from document | strict JSON parser; one unquoted string rejected the quiz (reproduced live on llama3.2) | schema-constrained output (desktop); tolerant item-by-item parser; phone line format | `task_runner.py`, `task/TaskRunner.kt` | backend parser tests (real failing output + 5 variants); Android `TaskRunnerFixTest` |
| Summary / study plan (Android) | consent asked on every step; documents in workspaces not found; cancel crash | consent once per run; `docById`/`allDocs`; engine guard | `TaskState.kt`, `AppViewModel.kt`, `StudioDb.kt` | `TaskRunnerFixTest` |
| Study plan quality / short sources | generic 5-line plan; no length guard | structured plan with the document title; minimum lengths | both engines | backend and Android tests |
| Microphone latency | cold model load 15.4 s; non-streamed answer 4–15 s; cold Whisper 7.9 s | streaming; warm-up on mic start | `agent.py`, `main.py`, `system_service.py`, `app.js`, `AppViewModel.kt` | 4 backend tests; browser E2E |
| Pages stacking (web, found during testing) | page openers didn't close other pages; only the sidebar did | every opener closes other pages | `app.js` | `web_e2e` `one_page_at_a_time` |

## C. AI Task Runner
| Workflow | Verified | Result |
|---|---|---|
| Document summary | live (2 models × 2 documents × 2 runs), backend and Android unit tests | 8/8 completed |
| Document to study plan | the same | 8/8 completed, all with 6 sections |
| Quiz from document | the same, plus browser (project quiz answered to a score) | 8/8 completed (was failing on llama3.2); 39/40 quotes verbatim |
| News briefing | browser E2E, Task Runner E2E | still works |

Android Task Runner: unit tests and the build only (no device).

## D. News audio
- **Engines:** the browser's `speechSynthesis` and Android's own text-to-speech. Both reuse the briefing on screen, with no new AI request (measured: 0).
- **Controls:** Listen / Pause / Resume / Replay, Previous and Next item, Stop, speed.
- **Live test:** real Edge, 22 items, pause, resume and next, stops when you leave News.
- **Android:** unit tests only. Background playback is not claimed.
- Details: [ABHIFY_NEWS_AUDIO.md](ABHIFY_NEWS_AUDIO.md).

## E. Navigation
**Moved to Settings:**
- **Android:** AI (mode, providers, memory, semantic search), Models & performance (AI models, memory profile), System (connection, sync, run setup), Diagnostics.
- **Web:** AI Models, Performance, System & data, Memory, Knowledge, as Settings sections.

**Stays in More (Android):** Workspace (with Projects and New private chat), Documents/OCR, Data, and Application (Settings, Account, About).

**Unchanged:** the bottom bar, the launcher (System now opens Settings; Projects and More added), and the palette (Projects added).

Details: [ABHIFY_NAVIGATION_SETTINGS.md](ABHIFY_NAVIGATION_SETTINGS.md).

## F. Private chat
- **Root cause and fix:** see B.
- **Tests:** 3 JVM tests, including a reproduction of the `UnsatisfiedLinkError` without the guard.
- **Physical device:** **not validated.**
- **To check on the phone:** install the preview, then without asking anything first open More → New private chat (and New chat, and Cancel in the Task Runner).

## G. Microphone chat performance (desktop, measured)
| Stage | Baseline | After optimization |
|---|---|---|
| Audio capture | not measured separately: synthetic audio files were used | the same |
| Speech recognition (Whisper, private mode) | cold 7.92 s, warm 0.21–0.42 s | cold **0.62 s** (warmed during speech), warm 0.30–0.58 s |
| Prompt preparation | milliseconds (not a bottleneck); prompt evaluation 0.02–0.56 s | unchanged |
| Time to first visible token | warm 4.17–14.87 s (whole answer); cold 21.3 s | warm **0.11–0.37 s**; cold 13.7 s after the transcript |
| Total response time | warm 4.1–14.9 s; cold, from end of speech, 29.2 s | warm 4.1–13.5 s (same model); cold, from end of speech, **18.3 s** (first words 14.3 s) |

**Not measured:**
- **Android:** no device; timing logs were added.
- **Browser Web Speech recognition:** it needs a microphone.

## H. Project Workspace
**Both platforms:**
- create, open, rename, archive and restore, export;
- instructions and a memory switch;
- document associations (references only);
- project chat with retrieval from the project's documents only;
- the Task Runner project source and Study entry.

**Delete:** needs confirmation and keeps documents. **Isolation:** tested in the backend (another project's documents never appear) and in the live browser.

Details: [ABHIFY_PROJECT_WORKSPACE.md](ABHIFY_PROJECT_WORKSPACE.md).

## I. Feature audit
| Feature | Status before | Changes | Tests | Remaining issues |
|---|---|---|---|---|
| Home | works | Projects chip (web) | browser | continue-working cards (deferred) |
| Chat | works; not streamed; Android private chat crash | streaming + Stop, project chat, crash fix, warm-up | backend, browser, Android | rename/search/archive in web chat |
| Task Runner | quiz fragile; Android unreliable | B and C | all | new templates (deferred) |
| Study | works | project study plan; document quiz score | browser, Android | adaptive practice |
| News | works; no audio | Listen | Node, browser, Android | per-article audio |
| Documents & knowledge | works | workspace documents usable by tasks (Android) | Android | diagnostics UI |
| Memory | works | per-project switch | backend, Android | suggestions |
| AI models | works | prompt time in stats | backend | control centre |
| Performance | works | moved into Settings | browser, Android | warnings |
| System | works | moved into Settings; Android diagnostics in Settings | browser, Android | diagnostics run |
| Settings | partial | sections (both) | browser, Android | – |
| More | crash; mixed | fixed; reorganized | Android | device check |
| Projects | missing (web), partial (Android) | complete | all | device check |
| Palette | works | Projects | browser | search projects/chats/tasks |

## J. Testing
| Suite | Kind | Baseline | Final |
|---|---|---|---|
| Backend `pytest backend/tests` | unit + integration (fake model and embeddings) | 347 passed, 1 skipped | **368 passed, 1 skipped** (+21 new); the README/changelog/PWA guard tests were re-run after the last doc edits: 53/53 |
| Android `testDebugUnitTest` | JVM unit + source contracts | 275 / 0 failed / 4 skipped | **294 / 0 failed / 4 skipped** |
| Android lint | static | 0 errors | **0 errors** |
| Node PWA harness (`test_pwa_behaviour.py`) | real `app.js` functions | existing tests passing | existing tests + **4 new news-audio tests**, all passing (included in the backend count) |
| Browser `stability_e2e` | real Edge + live Ollama | – | **25/25** |
| Browser `web_e2e` | real Edge + live Ollama | 43/43 | **43/43** (3 checks moved to the new navigation paths) |
| Browser `launcher_e2e` | real Edge | 17/17 | **17/17** (Projects added to the expected list) |
| Browser `task_e2e` | real Edge + live Ollama | 11/11 | **11/11** |
| Live Task Runner reproduction | live Ollama | 11/12 | **24/24** |
| Live voice latency | live Ollama + Whisper | measured | measured (G) |
| Physical Android device | – | not available | **not run** |

**Changed existing tests:**
- `V47Test`: the fake model keys on the online prompt.
- `V45Test`: "reachable" searches More + Settings + the projects list.
- `V451Test`: the Settings title.

All reflect intended behaviour changes; no assertion was weakened.

**Opt-in tests:**
- Backend `test_task_runner_live` is skipped unless `ABHIFY_LIVE_OLLAMA=1`.
- Android has 4 opt-in tests.

## K. Build artifacts
| Artifact | Path | Status |
|---|---|---|
| Android preview | `build/stability-preview/Abhify-4.6.1-stability-preview-debug.apk` | built and verified. SHA-256 `92e5a7267df30063fcf0d8ebb3a7a43cb4181f41188ce2217f3883e6cd7ce2df`. 4.6.1 / 4060199. Same debug key. Same permissions as 4.5.1. |
| Android test APK | `build/stability-preview/Abhify-4.6.1-stability-preview-debug-androidTest.apk` | built |
| Desktop/web | runs from source (`start.bat`). The installer was **not** rebuilt, because the build would overwrite `dist/windows/2.0.0-dev.8`. | backend and frontend checks above |

**Preserved:**
- `build/unified/`;
- `build/Abhify-4.5.1-debug.apk` (last phone-validated);
- `dist/windows/2.0.0-dev.8/`;
- versions unchanged.

## L. Safety and data preservation
- **User data, databases, models, settings:**
  - No user data deleted. No database reset or schema change (desktop v5, Android v17).
  - No Ollama model deleted or downloaded.
  - Settings preserved.
  - All live tests used isolated scratch data folders.
- **One incident, verified harmless:**
  - While testing `start.bat`, its first version tried to start a second backend on your real data folder, because your own backend was already running on port 8000 and the "already running" check timed out.
  - That process ran its startup and stopped immediately (port taken).
  - `abhify.db`, its backups and `settings.json` kept their earlier timestamps.
  - The check was fixed (IPv4 address) and re-tested.
- **Git and releases:**
  - No commit, push, tag change, release, deployment or visibility change.
  - `start.bat` was updated in place (it is tracked by Git, so the old version is recoverable).

## Recommended next milestone
1. **Phone validation of this preview:**
   - private chat;
   - the three document tasks with the phone model and with a provider;
   - News Listen with the screen locked;
   - the Settings and More navigation;
   - projects.
2. **A release decision:** version bump (which also refreshes installed PWAs) and signing.
3. **Master program Stage 2:**
   - Home "continue working";
   - palette search over projects, chats and tasks;
   - web chat rename and search;
   - then Backup of `projects.json` and Android `projects.json` (needs your approval of the backup scope).
