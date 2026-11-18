# Changelog

All notable changes to Abhify.AI. Format: [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).
The database schema version is listed separately, because not every release changes it.

## [2.0.0-dev.8]: premium redesign and chat model choice, in progress (not built)

Database schema: unchanged (**v5**). No new dependencies. Design: `docs/PREMIUM_REDESIGN_SPEC.md`.

### Internal 6.2: Real-Time Intelligence Engine (2026-11-18; version unchanged, SHELL_REV `ui62.1`)
- **`backend/realtime.py`:** works out which current information a question needs (news, web, weather, fx, projects, tasks, system) and retrieves it **before** the model runs, so it no longer depends on a small model choosing the `web_search` tool. It dates and validates the evidence and cites it as [W#]/[L#] for whichever model is selected (the model never changes).
- **Connectors:**
  - Google News; web search via DuckDuckGo (existing), or Brave/Tavily with `BRAVE_API_KEY` / `TAVILY_API_KEY` from the environment;
  - Open-Meteo weather and Frankfurter (ECB) rates, both **off by default**;
  - projects, tasks, and read-only system status.
- **Safety and behaviour:**
  - Freshness states (live/recent/historical/cached/unavailable); a per-category cache; a timeout, one retry and a rate limit.
  - Retrieved text is untrusted: turns containing it run without tools. Guests get external sources only.
- **API:** `GET /realtime/status`, `PUT /realtime/settings`, `POST /realtime/lookup`. `/chat` and `/chat/stream` return `live` (the stream sends `{"type":"live"}` events before tokens) and accept `refresh_live`.
- **Web:** live indicator, source chips with details and original links, Refresh, and a Settings → Live information section. A model's "[W4](#)" renders as the citation.
- **Fixed:** `news_center.feed()` reported a recent cached copy as not cached.
- **Tests:** 533 passed, 2 skipped.

### Internal 6.1: web/desktop audit fixes (2026-11-18; version unchanged, SHELL_REV `ui61.1`)
- **Fixed:**
  - An endless request loop when the backend stopped with Home open.
  - Stop vanishing during streaming.
  - Empty answers from tool-bound models: one retry with the same model, without tools.
  - Project "Continue" loading the chat twice.
  - Esc and focus in dialogs.
  - Direct links and refresh (`/home`, `/assistant`, `/study`, `/more`, `?tab=`).
  - Overflow at 360 px.
  - Offline states in Task Runner and System.
- **New in the Assistant:** Retry and Regenerate (`regenerate: true`), copy message and copy code, built-in code highlighting, headings and tables, source chips, follow-ups, and a context indicator.
- **Interaction modes** (Quick, Deep, Research, Coding, Learning, Planning, Creative) ported from Android. They never change the model.
- **Motion tokens** aligned with Android (120/220/340 ms).
- **Packaging:** `news_sources.json` is now included (the dev.8 package returned 500 on `/news/categories`).
- **Tests:** 460 passed, 2 skipped. Details: `docs/ABHIFY_6_1_WEB_DESKTOP_REPORT.md`.

### Internal 5.6/6.0: desktop insights, agent, premium UI, companion (2026-11-18)
- **Grouped search:** `/search` gains filters (`types`, `days`, `project`, `sort`) and grouped results.
- **Read-only project overview:** `/projects/{id}/overview`.
- **Desktop Agent 2.0:** `/agent/tools`, `/agent/plan`, `/agent/apply` (needs approval, verified), `/agent/undo`, and a counts-only audit log.
- **Briefings:** morning, evening and weekly. Skills gain a learning path and revision plan. Local evaluation at `/eval/retrieval`. All routes are PC-only.
- **Web redesign (Neural Futuristic):** one design system shared with Android's tokens; five destinations (sidebar on desktop, bottom bar on phones); Home dashboard; editorial News; fixes for the Settings sheet scroll, the palette at 390 px, the active News category and the hard-coded "Abhi" greeting. The service worker shell revision `ui56.1` makes installed browsers pick it up.
- **News images:** a duplicate story keeps its own image (`news_center.merge`), with JS-harness regression tests.
- **Friendly AI on the desktop:** `companion_ai.py` adds a charter, follow-up retrieval and an earlier-turn summary to the chat. Backend tests: 443 passed, 2 skipped.

### Internal 5.5: desktop Today and Skills (2026-11-18)
- `GET /plan`: today's plan from projects' recorded issues and next actions, most recently updated projects first. No due dates are invented, because desktop projects have none.
- `GET /skills` (`min_answers` 3–20): MCQ skill levels, accuracy, trend after 10 answers, and the next topic to practise.
- Both routes are PC-only and never cached by the service worker. The web shows Today in the Home briefing and Skills in Study. Backend tests: 409 passed, 2 skipped.

### Next internal build: desktop parity and Personal AI Brain (2026-11-18)
- **Web: five destinations**, Home · Assistant · Study · News · More.
  - Study holds Discover and opens the existing Question Bank and MCQs pages; More groups every existing page.
- **Backend `brain.py`:**
  - `GET /discover` (live MIT OCW + OpenStax, provider links; cost only as the source states);
  - `GET /briefing` (records only);
  - `GET /search` (conversations, memories only when memory is on, projects, AI tasks, knowledge files, saved news).

  All are `local_only`, never cached by the service worker, and documented in the README.
- **Web:** News English/Hindi/Both selector, Home briefing, universal search in the Ctrl+K palette. Browser check 11/11.
- **Android:** internal build `build/internal/Abhify-5.4-dev.1.apk` (see [docs/ABHIFY_NEXT_BUILD_REPORT.md](docs/ABHIFY_NEXT_BUILD_REPORT.md)).

### Android 5.4 Personal Voice Companion (2026-11-18)
- Android only (`build/Abhify-5.4.apk`): hands-free voice conversation, voice commands through a typed App Action Registry, Voice & Conversation settings, Conversation data controls. The desktop backend is unchanged. See [docs/ABHIFY_PERSONAL_VOICE_ASSISTANT.md](docs/ABHIFY_PERSONAL_VOICE_ASSISTANT.md).

### Android 5.3 and shared news catalog (2026-11-18)
- Android 5.3 (`build/Abhify-5.3.apk`): five destinations, grouped More, neural core states, Home command center, Study Discover, News in English/Hindi/Both. See [docs/ABHIFY_5_2_IMPLEMENTATION_REPORT.md](docs/ABHIFY_5_2_IMPLEMENTATION_REPORT.md).
- Desktop backend:
  - `news_sources.json` (shared) gains Hindi feeds marked `"lang": "hi"`, Hindi labels and Hindi search terms.
  - `news_center.plan` now picks feeds by the `language` preference (`en`, `hi` or `both`), so the English desktop feed is unchanged.
  - Hindi results are cached under their own keys.
  - The web UI language selector is the next step.
- Audit: [docs/ABHIFY_5_2_PRODUCT_AUDIT.md](docs/ABHIFY_5_2_PRODUCT_AUDIT.md). Design: [docs/ABHIFY_NEURAL_DESIGN_SYSTEM.md](docs/ABHIFY_NEURAL_DESIGN_SYSTEM.md).

### Android 5.1 Personal Intelligence (2026-11-17)
- Android 5.1 (`build/Abhify-5.1.apk`): chat focus with adaptive answers, response preferences, project status with continuation summaries, Continue working and suggestions on Home, routing explanation, more precise memory. See [docs/ABHIFY_5_1_IMPLEMENTATION_REPORT.md](docs/ABHIFY_5_1_IMPLEMENTATION_REPORT.md).
- **Desktop parity:**
  - `backend/intelligence.py`: the same focus rules as Android; on the shared labelled set it scores 57/60 with the same misses.
  - `/chat` and `/chat/stream` take an optional `focus` (auto by default; when it isn't chosen the call is unchanged) and return a `focus` label.
  - Web: a focus selector next to the model chip, and the label under answers.
  - Projects: `PATCH /projects/{id}` takes `status`; project chats use the brief; the project page shows "Where you left off" and the status editor.
  - Answer style: `PUT /preferences` takes `answer_detail`, `answer_depth`, `answer_style`, `answer_language` (same choices as Android; defaults change nothing); Settings → Answer style on the web.
  - Home: `GET /suggestions` and a "Continue working" + suggestions section (project you last updated, unfinished AI task, open issues, a failed task); dismiss with ✕.
  - Checked live with Ollama (llama3.2) and in headless Edge (12/12) on an isolated data folder.

### Backup coverage and command palette (2026-11-17)
- **Backup:** `data/tasks.json`, `data/projects.json` and `data/career_mcq.json` are included in local backups and restored with them.
- **Command palette:** also finds projects and AI tasks by name (loaded locally when the palette opens).
- Android Local RAG, Knowledge Center and controlled learning: see [docs/CHANGELOG.md](docs/CHANGELOG.md) and [docs/ABHIFY_LOCAL_RAG.md](docs/ABHIFY_LOCAL_RAG.md).

### Career milestone (2026-11-10)
- **Career MCQ practice:**
  - **Endpoints:** `POST /career/mcq`, `POST /career/mcq/answer`, `GET /career/mcq/stats`.
  - **Sources:** a resume or document from the knowledge folder, any topic, or both.
  - **Batches on demand,** with schema-constrained output on the chosen model.
  - **Validation and deduplication:** validated question by question; duplicates filtered across batches; a new angle each batch.
  - **Labels:** answers labelled AI-generated (never "verified").
  - **Progress:** in `career_mcq.json`.
- **MCQs tab:**
  - **Answering:** a tap answers, with the right option and explanation; Review mode.
  - **Batches:** "Generate more" and "Refresh unanswered" keep progress.
- **Question Bank:**
  - **Refresh:** keeps filters.
  - **Generated and curated:** generated questions are added to the curated ones instead of replacing them.
  - **Renamed tabs:** Question Bank and MCQs.
- **News:**
  - **In-app reader:** `GET /news/article/{id}` reads the story's own text from the publisher's page (public https only, redirects checked) and summarises exactly that story.
  - **Cards:** open the reader instead of the website.
  - **Read more:** the first shows the full text, the second opens the original.
  - **Refresh:** single-flight, a stale-response guard, the previous stories kept on failure, a status line.
- **Pull-to-refresh** on News, Question Bank and MCQs (touch screens), without reloading the page.
- Details: `docs/ABHIFY_CAREER_*.md`, `docs/ABHIFY_REFRESH_BEHAVIOR.md`, `docs/ABHIFY_NEWS_IN_APP_READING.md`.

### Stability milestone (2026-11-09)
- **Fixed: Quiz from document.**
  - The quiz step now uses Ollama's schema-constrained output on the same chosen model.
  - The parser reads malformed small-model JSON item by item, so a single unquoted string no longer rejects the whole quiz.
  - Quiz settings (3/5/10 questions, difficulty), a running score, and answers recorded in quiz progress.
- **Study plan:** objectives, topic order, sessions, revision, practice and a progress checklist. Study plans and quizzes need a minimum source length. Task results can be exported as Markdown.
- **Streamed chat:** `POST /chat/stream` shows the answer while it is written, with Stop. Warm answers now show first words in 0.1–0.4 s instead of after the whole answer.
- **Voice warm-up:** `POST /chat/warm` loads the chosen chat model (and Whisper in Private voice mode) when the microphone starts. Cold voice question: first words 14.3 s after speaking (was 29.2 s).
- **News Listen:** the browser's speech synthesis reads the briefing on screen. Pause, resume, previous/next item, stop, replay and speed. No new AI request. Voice answers are read in full (no 600-character cut).
- **Projects:**
  - `projects.json` (no database change), `/projects` API;
  - instructions, archive/restore, export, document associations (references);
  - project chat retrieving only from the project's documents;
  - Task Runner project source.
  - Delete needs confirmation and keeps everything else.
- **Settings:** sections for AI & models, System & your data, General, Voice & audio, Application. Performance and System & data left the sidebar (still in the launcher and palette). Pages opened from Settings no longer stack.
- **`start.bat`:**
  - opens the app if it is already running;
  - starts Ollama if needed;
  - opens the browser only when the backend answers;
  - `start.bat stop`.
- Details: `docs/ABHIFY_MASTER_ENHANCEMENT_REPORT.md`.

### Added
- **Choose the AI model in chat.** A model chip above the chat box lists the chat models really installed in Ollama on this computer (`GET /models`, from Ollama's `/api/tags`; embedding models left out). The choice belongs to the conversation (kept in this browser), is sent with each message (`POST /chat` takes an optional `model`) and is checked by the backend: a model that isn't installed is refused with a clear message (`400`), never silently replaced. Each new answer shows the model that wrote it.
- **Default chat model** in Settings (`PUT /models/default`, saved in `settings.json`; the embedding model is unchanged). If `LLM_MODEL` is set in the environment, the setting is shown as locked. Paired phones keep using the computer's default model (model choice is PC only).
- **Light, dark and system themes** (header button; applied before the first paint, no flash).
- **Sidebar on wide screens** (icon rail from 600 px, full sidebar from 1024 px, collapsible): Learn sections, Knowledge folder, Memory & history, System & data, Settings. Phones keep the tab bar.
- **A short logo reveal on the first load of a session** (no delay; nothing waits for it; off with reduced motion).
- **Home** (new start page):
  - an "Ask Abhify.AI" box that starts a new chat;
  - cards built from real data: continue the last conversation, quiz score, cached AI headlines, knowledge-folder size, AI status and default model, interview practice.
- **Full pages on wide screens:**
  - Memory & history, System & data (with companion access) and Knowledge folder open in the main area beside the sidebar, one at a time, with the sidebar showing which is open.
  - Phones keep the bottom sheets. The element IDs are unchanged.
- **Switching chat models frees memory:** when chat moves to another model, the previous chat model is released in Ollama straight away (`keep_alive: 0`), so two chat models don't sit in GPU memory together. The embedding model is never touched.

- **"Aurora" visual identity** (shared with Android 4.6):
  - a deep-indigo night or a warm lavender-white, lit by a static aurora background;
  - a colour per feature on the sidebar, tabs and Home cards;
  - the **AI orb** in the Home hero and the chat welcome, showing the real state (ready, listening, thinking, AI unavailable; still with reduced motion);
  - orb avatars on answers;
  - colour-coded starter prompts;
  - the deep aurora on your messages;
  - page glides and press feedback.
  - Design system: `docs/AURORA_DESIGN_SYSTEM.md`.
- **AI Action Center:** Summarize, Explain simply, Key points, Translate…, Quiz me and Flashcards under every new answer. Each is a follow-up through the normal chat (same chosen model and checks).
- **Performance page** (sidebar, and System & data → Performance; `GET /system/performance`, PC only), from real measurements only:
  - CPU and RAM (psutil);
  - NVIDIA GPU use and VRAM (nvidia-smi);
  - the models Ollama has loaded, with their VRAM and unload time;
  - the last answer's speed.
  - It samples every 3 s only while the page is open.
- **Each answer shows its speed** (tokens/s, with token count and total time in the tooltip), taken from Ollama's own response metadata; nothing is shown when Ollama doesn't report it.

- **News Center** (`docs/NEWS_CENTER.md`; `GET /news/categories`, `GET /news/feed`, `GET /news/search`, `POST /news/ai`, `PUT /news/prefs`):
  - 15 categories from publisher RSS feeds and Google News (same catalog as Android), images, Read more to the original story;
  - search with dates, offline copy;
  - source manager: city, edition, language, topics, publisher priority and hiding;
  - an AI briefing / summary / explanation on the local model with numbered sources.
  - News AI runs with no tools, history or memory, and never writes personal memory (tested).
  - The classic AI-summarized `/news` view stays as the fallback.
- **Unified workspace** (`docs/UNIFIED_AURORA_WORKSPACE.md`):
  - the **Abhify brand opens a navigation launcher** with every destination (Workspace / AI System / Application), including direct **AI Models**, **Performance**, **System** and **Settings**;
  - it works in the expanded sidebar, the collapsed sidebar, the rail and on phones (header logo);
  - Esc, outside click and the keyboard work; opening it makes no request and loads no model.
  - Also: home quick actions; new Aurora tokens (sidebar surface, cyan focus ring, restrained glow); new command-palette entries.
- **AI Task Runner** (`docs/AI_TASK_RUNNER.md`; `/tasks…`, PC only): multi-step workflows (Document summary, Study plan, Quiz with source-checked quotes, News briefing, Custom) on knowledge-index documents, current News stories or pasted text.
  - Execution: one model call at a time on the chosen installed Ollama model (validated, never swapped) with the one-model-in-memory release; progress, cancel, retry from a step, restart-safe history in `<data>/data/tasks.json`.
  - Entry points: sidebar, Home and the command palette.
  - Results are never written to memory or chat history; Save to Knowledge requires `confirm=true`.
  - Opt-in live Ollama test: `ABHIFY_LIVE_OLLAMA=1`.
- **Command palette (Ctrl+K / ⌘K):** pages, commands, models, news categories, news search, ask the AI, and past conversations by title.
- **Customize Home:** show, hide and reorder the cards (this browser).
- **Action Center:** adds Checklist and Rewrite clearly.
- News briefings and the quiz/interview generators now go through the same "one chat model in memory" release as chat (found in the live browser test: a briefing left a second model loaded).

### Changed
- After a backend restart, the first switch away from the default chat model now releases the default, so two chat models never stay in GPU memory together (found and re-tested against live Ollama).
- New design tokens shared with the Android app; the pink accent and the glow backgrounds of dev.7 are retired. Opaque cards with hairlines instead of glass blur; chat answers read as text, your messages keep the brand gradient.
- Navigation and header icons (including Install) are official Material outlined icons (Apache-2.0) instead of emoji.

## [2.0.0-dev.7]: on-device AI on phones ("Abhify Lite"), in progress (not built)

Database schema: unchanged (**v5**). The PC app is unchanged; this release only adds an optional mode to the web app. Details: `docs/ON_DEVICE_AI.md`.

### Added
- **The AI on each person's own phone, no PC needed.** Settings → *Where the AI runs* → **This device** runs a small model in the browser on the phone's GPU (WebLLM 0.2.85 over WebGPU): Qwen 2.5 1.5B on phones that report 6 GB+ of memory, Llama 3.2 1B otherwise (and on iPhones). The default stays *My computer*.
  - One-time model download (~869 MB or ~695 MB) only after a confirmation that shows the size, memory use and source; a storage check runs first. Then it works offline.
  - Chats and "remember that …" memories are stored in the browser's IndexedDB on the phone; nothing typed leaves the phone.
  - Honest statuses (*not supported here* with the reason, *not set up*, download %, *AI ready · runs on this device*); *Remove on-device AI model* and *Delete on-device chats* in Settings.
- WebLLM and its two model libraries are vendored in `frontend/vendor/webllm/` with pinned SHA-256 hashes (`VENDORED.md`); no code is fetched from third-party hosts. Loaded only in this mode.
- `test_ondevice.py` (+ a Node harness): hashes, model choice, prompts, consent, no network in the chat path, PC mode unchanged.

### Changed
- `test_pwa.py`'s secret scan skips the hash-pinned `frontend/vendor/` (its embedded WebAssembly is random base64 that can look like a key).

### Not done
- Real-phone tests (Android, iPhone): NOT TESTED. Hosting the PWA for other people: not set up (needs the owner's decision).

## [2.0.0-dev.6]: invite-only device membership, unreleased (branch `feature/m2-knowledge-packaging`)

Database schema: **v5** (additive: `members` and `invitations` tables; v4 databases upgrade automatically with a backup taken first). Installer built from commit `a9325d2` (unsigned, not published). Details: `docs/MEMBERSHIP_AND_REGISTRATION.md`.

### Added
- **Per-device membership.** Every paired phone has its own revocable credential (only a SHA-256 hash is stored); "last seen" and expiry are tracked.
- **Invitations:** single-use, 128-bit, hashed, expiring (1 h to 30 days), created on the PC only, with rate-limited redemption (per client and global) and the same vague error for every failure.
- **Roles:**
  - *Your device*: the owner's phones, with the same access as before.
  - *Guest*: someone else's phone. News, interview questions, quizzes, transcription, and a guest chat with no access to the owner's memories, documents or history; nothing is saved.
  - Neither role can use admin actions.
- **Registration modes:** `invite_only` (the default, and the fallback for any invalid value) and `public` (anyone who can reach this PC can join as a *guest*).
  - Switching to `public` only works on the PC, after a confirmation warning; one click returns to invite-only.
  - Switching never deletes devices or data.
- **Owner panel** in Settings → System & data → Companion access: mode, devices, invitations, revoke, the actual port, and the Tailscale command.
- **Phone:**
  - `#invite=<code>` links are redeemed once and removed from the address bar.
  - "Join as guest" appears only in public mode.
  - Guest devices see an honest status, and personal controls are hidden (the server enforces this).

- **Automatic first-run setup** (`setup_manager.py`, `AUTOMATIC_SETUP.md`):
  - One confirmed plan (components, sizes, disk check), then everything runs in the background: the Ollama runtime if missing (official installer, Authenticode **Valid + Ollama Inc.** required, otherwise deleted and never run), the profile's models, and real validation (a chat answer + an embedding).
  - Versions and digests are recorded in `config/components.json`.
  - Pause, cancel, resume and retry; nothing is downloaded twice.
- **Offline-first test:** the full app with every non-local connection refused (`test_offline.py`). ChromaDB telemetry is switched off explicitly.
- **Update manager** (`updates.py`, `AUTOMATIC_UPDATES.md`):
  - It accepts only **Ed25519-signed manifests** from publisher keys configured in settings (no key in the repo).
  - Downloads are verified against size + SHA-256 (+ Authenticode) and resumable; installs need confirmation, re-verify the file, and archive it for rollback.
  - Model updates keep a backup and restore it if validation fails.
  - The publisher tool `packaging/windows/release_manifest.py` refuses keys inside the repo.

### Changed
- **Network binding:** with companion access on, Abhify.AI now still listens on **127.0.0.1 only** (Tailscale Serve forwards there).
  - Listening on the whole local network (plain HTTP) is a separate opt-in with a warning.
  - **If you used a phone over plain Wi-Fi with dev.5, turn on "Allow local-network access" and restart.**
- The dev.5 shared token is moved automatically into the device list as "Legacy paired device", so already-paired phones keep working until revoked.
- "Turn off companion access" revokes every device and open invitation (the rows are kept for the record).

### Known limitations
- Data is still **single-owner**: guests are kept away from the owner's data, rather than getting their own chats, memories or documents (that needs per-user isolation, a separate decision).
- The phone keeps its device credential in browser storage (owner's decision); it's revocable, and guest credentials expire after 30 days by default.
- **Update check behaviour change:** unsigned manifests are rejected since dev.6. No release channel or publisher key exists yet.
- The Whisper model is still downloaded on first use of private voice mode (not part of automatic setup).
- Automatic setup and the update flow are tested with stand-ins only; real runs: `UPDATE_RECOVERY_TEST.md` (NOT TESTED).
- Not tested on a real Android phone or iPhone.

## [2.0.0-dev.5]: hash-locked dependencies and a signing-ready pipeline, unreleased (branch `feature/m2-knowledge-packaging`)

Database schema: **v4** (unchanged). **No application code changes since dev.4**: the same 118 dependency versions, now installed from a lock. Details: `docs/FINAL_ACCEPTANCE_DEV4.md`.

### Added
- **`packaging\windows\requirements.lock`:** every direct and transitive dependency of the Windows build, pinned to the exact versions in the verified dev.4 installer (118 packages, 3,235 SHA-256 hashes).
  - The online build installs from it by default with `--require-hashes --only-binary :all:`; pip refuses any file whose hash differs.
  - `build.py --no-lock` resolves fresh, for example to refresh it.
  - `packaging\windows\lock.ps1` regenerates it in a throw-away venv.
  - The lock covers Windows x64 and Python 3.11 only (the bundled runtime); Docker, macOS and Linux keep `requirements.txt`.
- **Signing pipeline (not used yet; no certificate exists):**
  - `packaging\windows\sign.ps1` signs and timestamps one file, then verifies chain, timestamp and publisher.
  - `build.py --sign` makes NSIS sign `Uninstall.exe` (`!uninstfinalize`) and the installer (`!finalize`), and writes checksums **only** if the final file verifies as Valid; otherwise it deletes the output.
  - All configuration comes from `ABHIFY_SIGN_*` environment variables, never from files in the repo.
- `build.py --out-dir` for experiments. `build.py` refuses to overwrite an existing `dist\windows\<version>\` installer.

## [2.0.0-dev.4]: model-setup and reproducible-build fixes, unreleased (branch `feature/m2-knowledge-packaging`)

Database schema: **v4** (unchanged). Found during the dev.3 final acceptance run; details in `docs/FINAL_ACCEPTANCE_DEV3.md`.

### Fixed
- **D1, false "model installed".** A model download whose connection was cut, or whose stream ended early (Ollama restarted, network dropped), was reported as **done**.
  - A download now counts as done only after Ollama's final `success` event **and** after the model shows up in Ollama's model list.
  - Failures get plain-language messages ("Ollama isn't reachable", "the connection was interrupted, press Download again to resume", Ollama's own error text).
  - The setup test passes only for a real, non-empty answer, and shows Ollama's reason when a model can't load (for example, not enough memory).
- **D2, builds from `requirements.txt` produced an app that crashed on start.** `langchain-text-splitters` (used by `knowledge.py`) was not declared; it was only present in the dev environment.
  - It's now pinned in `backend/requirements.txt`.
  - `build.py` imports the app's own modules with the bundled runtime and fails the build if that doesn't work.
  - A test checks that every third-party import is declared, directly or through a declared package.

- **D3, document questions on the Minimal profile.** On a clean CPU-only PC, `qwen2.5:1.5b` answered "I don't have access to your documents" without searching them, although the PDF was indexed.
  - When a question explicitly refers to the user's own material ("my notes", "my documents", "the uploaded PDF", "knowledge base"), matching excerpts are now added to the prompt (like memories already are), and the answer cites them.
  - General questions are unchanged.
- **D4, the app couldn't start when Windows reserved ports 8000–8010.** Hyper-V, WSL2, Docker and Windows Sandbox reserve dynamic blocks of 100 ports; on the dev PC, 7914–8213 was reserved, so the launcher stopped with "No free port between 8000 and 8010".
  - The launcher now tries the last port that worked, then 8000–8010, then 18000 / 28000 / 38000, and finally any free port.
  - The working port is remembered, so the browser keeps the same address (saved settings, pairing and the installed PWA stay).
- **F7, online builds carried the builder's path.** `pip install --target` wrote 37 `bin\*.exe` console-script launchers embedding the build machine's `python.exe` path (and user name).
  - They are now removed; the app never used them.
  - The build-path check now scans every runtime file, not only `.pyc`.
  - The first dev.4 build (`24d75f81…`) had this problem. It was set aside in `build\` before any test or distribution and replaced by a rebuild.

### Changed
- Releases are built with the **online** build (`build.py` without `--site-packages-from`). It now works end to end. The offline build copies the whole dev environment, which also shipped unused packages (for example `langchain_community`, `sqlalchemy`).

### Added
- `packaging\windows\sandbox\run-sandbox-modeltest.ps1`: a clean-PC model-setup test in Windows Sandbox **with networking**. It installs the app and Ollama, then checks consent, a network error, an interrupted download and resume, a model that can't start, real inference, document Q&A from the knowledge folder, and a restart.

### Known limitations
- Unchanged from dev.3: unsigned; no second-physical-PC or real-phone acceptance yet.

## [2.0.0-dev.3]: release-blocker fixes, unreleased (branch `feature/m2-knowledge-packaging`)

Database schema: **v4** (unchanged). Fixes the blockers from `docs/INSTALLER_READINESS_AUDIT.md`. Details, root causes and test results: `docs/RELEASE_BLOCKER_FIXES_DEV3.md`.

### Fixed
- **B1, knowledge-folder watcher deadlock (high).** Switching or restarting the watcher could block for 10 s and briefly run two indexing threads (`start()` joined the worker while holding a lock the worker needed). The watcher now has explicit sessions: no join ever happens under that lock, joins are bounded, a new worker waits for a still-busy old one (never two indexing at once), and events or queue items from a previous folder are ignored. New deterministic regression tests.
- **B2, companion privacy (security).** A paired phone could read `/system/diagnostics` (including the token hash and the Windows user name in paths), `/backup`, `/setup/*` and the API docs.
  - Companion access is now an **allow-list**: a token only opens chat, learning content, voice, documents, memory, preferences, history and progress. Everything else, including any future route, is local-only.
  - Path fields are reduced to names, and error details are redacted for phones.
  - `/health` tells an unpaired caller only whether the AI is reachable.
  - `token_sha256` is never returned by any endpoint.
- **B3, honest phone status.** An unpaired phone no longer shows "AI ready". The status distinguishes not paired, token rejected, companion off, Ollama or model missing on the PC, PC unreachable, connection lost and offline, with an explanation banner. It says "runs on your computer", never that the AI runs on the phone.
- **F1:** the installer no longer ships 9,450 `.pyc` files containing build-machine paths. Bytecode is compiled with a neutral path, and the build fails if a build path is found.
- **F2:** `#pair=` links now work while the app is already open (`hashchange`). Other open windows pick up the token, and "Paired" is only shown once the PC accepts it.
- **F3:** logs never contain tokens. A central filter removes query-string values from access-log lines and masks bearer tokens, `Authorization` headers and `token=` / `pair=` / `key=` / `password=` values.
- **F6:** the installer's finish page opens `INSTALL.html` (generated from the install guide), not a `.md` file.

### Changed
- Version `2.0.0-dev.3`; the service-worker cache is versioned with it, so installed PWAs update.
- Builds go to `dist\windows\<version>\`, so the dev.2 installer, its `SHA256SUMS.txt` and its notes are never overwritten.
- The Sandbox test takes `-UpgradeFrom <version>` for a real installer-to-installer upgrade, and also checks the companion privacy, log redaction, bytecode paths and the HTML guide on the installed build.
- README rewritten to match the actual app: API, knowledge watcher, companion access, installer and phone setup.

### Known limitations
- The installer is still **unsigned** (SmartScreen warning expected).
- **Not yet tested** on a second physical PC, with a real model download on a clean PC, or on real Android or iPhone devices. Phone behaviour was checked with automated logic tests and server-side tests only.
- Changing the knowledge folder while a very large file is being indexed can answer "still finishing a large file, try again" (after up to 2 minutes). The old folder keeps being watched; nothing is lost.

## [2.0.0-dev.2]: Milestone 2, unreleased (branch `feature/m2-knowledge-packaging`)

Database schema: **v4** (adds per-file index tracking; v1–v3 databases upgrade automatically, with a backup taken first).

### Added
- **Watched knowledge folder** (`%USERPROFILE%\Abhify_Knowledge` by default, changeable).
  - Indexes PDF, TXT, Markdown and Python files.
  - Picks up new, modified, renamed and deleted files, and waits until copying has finished.
  - Detects changes by SHA-256; renames reuse vectors without re-embedding.
  - Replace-then-delete updates, so the previous version stays searchable if embedding fails.
  - Retries with backoff (locked files, Ollama down), plus a manual retry.
  - Pause/resume, rescan, per-file status and errors, live progress.
  - Exclusions: secrets by name and by content, VCS/venv/caches, temp files, databases, model weights, symlinks/junctions, anything outside the folder.
- **Safe embedding-model changes:** a mismatch pauses document search ("rebuild required"). The rebuild builds a new collection, validates it, switches atomically and keeps the old one.
- **Knowledge panel** in Settings (and from the 📚 chunk count in Chat).
- **Companion access (Mode B):** opt-in phone access to this PC.
  - Hashed access token and a `#pair=` pairing link.
  - Admin actions stay local-only, and requests through a tunnel or proxy count as remote.
  - The status bar says "via your computer" on a phone.
- **Update check:** opt-in HTTPS manifest. It validates the version, URL and SHA-256 and never installs anything.
- **Windows installer:**
  - per-user NSIS installer, no admin needed, with a bundled Python 3.11 runtime
  - desktop launcher with tray icon, single instance, port fallback, and a pre-upgrade backup
  - uninstall keeps user data unless you choose otherwise
  - build script with checksums and a package secret/user-data check
  - Windows Sandbox clean-machine test
- Docs: M2 knowledge folder, Windows installation, mobile installation (Modes A/B/C), release process, M2 test report.

### Fixed
- Watcher shutdown could wait on its own lock, and periodic retries could re-queue a file before it was processed (both found by the new tests).

Database schema: **v3** (v1.0.0 databases are upgraded automatically, with a backup taken first).

### Added
- **Abhify.AI branding:** logo, logo mark, favicon, PWA icons (normal, maskable, Apple) and new app name everywhere.
- **First-run setup wizard:** detects OS, CPU, RAM, NVIDIA GPU/VRAM, disk and Ollama, then recommends a model profile (Minimal / Balanced / Quality). It checks free disk space before any download, downloads only with explicit consent, and runs a local inference test. "Limited mode" lets you skip setup.
- **Per-user data directory** (`%LOCALAPPDATA%\AbhifyAI`), separate from program files, with `settings.json`, logs, cache and backups. Location can be set with `ABHIFY_DATA_DIR` or moved in the app.
- **Personal memory:** you control it. Add, edit, pin and delete memories, or switch memory off. The agent saves a memory only when you explicitly ask. Retrieval uses stemmed SQLite FTS5 and runs offline.
- **Persistent conversations:** chat history in SQLite survives restarts. "New chat", retention setting, delete-all.
- **Backups:** automatic before schema migrations, plus manual backup, export (zip download) and restore (validated, staged, applied on restart; previous data kept).
- **Data relocation:** copy, verify and switch on restart; the old folder is kept.
- **System & data panel** and **Memory & history panel** in Settings.
- `/system/diagnostics`, `/setup/*`, `/memory`, `/preferences`, `/conversations`, `/backup*` endpoints.
- 50 automated tests (pytest), with external services mocked.

### Changed
- Model names come from the selected profile (`settings.json`); `LLM_MODEL` / `EMBED_MODEL` env vars still override.
- The agent tells you when it's offline instead of waiting for web-search timeouts, and has `search_memory` / `save_memory` tools.
- Document uploads are de-duplicated by SHA-256, and originals are kept for future re-indexing.
- News: similar headlines from different outlets are merged; when offline, the app says so instead of retrying.
- Start scripts listen on **127.0.0.1** by default (`ABHIFY_HOST=0.0.0.0` to share on your LAN).
- Admin endpoints (backup, restore, data move, model download) only accept requests from the local machine.
- PDFs are read with `pypdf` directly; `langchain-community` was dropped (sunset upstream).
- Browser storage keys renamed `aitp_*` → `abhify_*`, with a one-time, non-destructive copy.

### Fixed
- SQLite connections were not closed after backups (`with sqlite3.connect()` doesn't close), which locked files on Windows.
- Chat history didn't load if the Chat tab was opened before the first connection check finished.

## [1.0.0]: AI Tech Pulse (baseline, Git tag `v1.0.0`)
- PWA with News, Interview, MCQ, Chat and Voice; FastAPI + LangGraph + Ollama + ChromaDB + SQLite progress; Faster-Whisper; offline fallback; Docker files (unverified).
