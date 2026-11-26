# Changelog

All notable changes to Abhify.AI. Format: [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).
The database schema version is listed separately, because not every release changes it.

## [7.2.1]: final stable release (2026-11-25; physical validation passed 2026-11-26, owner-reported; not production-signed)

**Note:** links in older entries to per-version reports in `docs/` refer to files removed at the 7.2.1 consolidation. Tracked ones remain in Git history.

Android 7.2.1 = versionCode **7020199** (final = 99). SHELL_REV `ui72.5`. Database schemas unchanged: Windows SQLite v7, Android ChatDb v17. **Feature freeze:** this release adds no features beyond 7.2.1-dev.2. It consolidates 7.2.0-dev.1 → 7.2.1-dev.2 into one stable baseline.
- **Included, from the development builds:**
  - Study with courses and interview preparation (7.2.0-dev.1).
  - Study enhancements: mock interview, resume tailoring, report, model check, reminders, offline packs and widget on Android (7.2.0-dev.2).
  - Privacy patch: filtered cloud prompts, project-memory enforcement, Study respects the memory switch, microphone fix, upload delete with 7-day Trash, safer Android defaults, unused install permission removed (7.2.1-dev.1).
  - Windows installer update fix for stray processes (7.2.1-dev.2).
- **Documentation:**
  - New canonical `docs/ABHIFY_AI_COMPLETE_DOCUMENTATION.md` (31 sections, full API route table).
  - Concise root README with architecture, privacy, RAG and install diagrams.
  - `docs/README.md` index classifying the historical reports, which are kept unchanged.
  - The pre-7.2.1 README is kept as `docs/archive_README_pre_7.2.1.md`.
- **Test change, reported:** the API-route documentation guards (`test_readme_documents_every_api_route` and the route checks in `test_desktop_56`, `test_realtime` and `test_voice_71`) now check the complete documentation instead of the README.
- **Release packaging:** `release/Abhify.AI-7.2.1/` holds the installer, portable zip, APK, release notes, checksums, install guide, signing guide and test summary.

## [7.2.1-dev.2]: Windows installer update fix (2026-11-25; development build, not production-signed)

Android 7.2.1-dev.2 = versionCode **7020102**: only the version changes, the Android code is the same as 7.2.1-dev.1. SHELL_REV `ui72.4`.
- **Windows update no longer fails with "Error opening file for writing: …\runtime\_asyncio.pyd".** This was reported on the owner's laptop. Cause: an Abhify.AI process the app's run file didn't know about kept the old program files locked, for example a leftover instance, or an earlier update attempt that had already removed `runtime\python.exe`. The old installer only stopped the recorded process.
  - In a normal install this showed the error.
  - In a silent install it skipped the locked files, leaving a mix of old and new files.
- **How the installer behaves now:**
  - It stops every `python.exe` / `pythonw.exe` running from the Abhify.AI install folder. Nothing else is touched.
  - It removes the old program files and checks they are really gone, retrying.
  - If something still holds them, it asks you to close Abhify.AI and click Retry. It never continues with a mixed install.
  - The uninstaller does the same.
  - Your data (`%LOCALAPPDATA%\AbhifyAI`) is never touched, and it is backed up before the update.
- **Sandbox upgrade test:** now includes a stray Abhify process during the update (`upgrade_clears_stray_process`).

## [7.2.1-dev.1]: privacy patch (2026-11-25; development build, not production-signed)

Android 7.2.1-dev.1 = versionCode **7020101**. SHELL_REV `ui72.3`. No database or data-format migration. Plan: `docs/ABHIFY_7_2_1_DEV1_PRIVACY_PATCH_PLAN.md`. Report: `docs/ABHIFY_7_2_1_DEV1_REPORT.md`.
- **Online models get less (F1, F1b, Windows).** Checked by capturing the real requests the app sends to a provider.
  - Questions about your projects, tasks or this computer are answered by the app itself ("…stay on this computer"); the provider isn't called.
  - Your user name is no longer sent.
  - After switching a chat from a local model to an online one, earlier answers from the local model (which can quote your memories or records) are left out, with a note under the answer.
- **Projects with memory off (F2, Windows).** The AI can no longer save memories there; it is checked in the memory tool itself.
- **Study personal context (F3, Windows and Android).** It follows the app-wide memory switch. When memory is off, nothing personal is used, and your ticked items are kept for later.
- **Documents (F4).** They stay shared knowledge by design (your decision); a test documents the rule.
- **Delete uploaded documents (F5, Windows).**
  - Knowledge → Uploaded documents → Delete removes a document from search and answers at once and from every project.
  - The file is kept in Trash for 7 days.
  - Deletion works on this computer only.
- **Microphone (Desktop and Web).**
  - Very quiet microphones (such as laptop microphone arrays) are no longer cut off with "the microphone isn't picking up sound". Any real sound goes to Whisper.
  - The speech threshold adapts to room noise.
  - Only true silence counts as "no sound", and that message now names the likely causes: mute key or Windows microphone privacy.
  - Test microphone shows the measured level.
- **Android.**
  - The unused "install other apps" permission is removed.
  - New installs start with online chat history off; existing installs keep their setting.

## [7.2.0-dev.2]: Study enhancements (2026-11-24; development build, not production-signed)

Android 7.2.0-dev.2 = versionCode **7020002**. SHELL_REV `ui72.2`. Plan: `docs/ABHIFY_7_2_0_DEV2_PLAN.md`. Owner-approved: Mermaid (vendored, hash-pinned) and the daily reminder's notification permission (already declared; asked only when turned on). The Pyodide code runner was not approved.
- **Mock interview:**
  - Questions come from your interview plan's skills, alternating with behavioural themes.
  - The question is read aloud with your single soft voice, and a per-question timer runs.
  - You answer by typing or by voice. On Windows, speech is transcribed by Whisper on this computer. On the Web, the browser's own speech recognition is used, after a notice.
  - Each answer is scored for relevance, depth and structure, quoting only your own words. Sessions are saved with a summary and a full transcript.
- **Resume tailoring:**
  - Your most relevant bullets are rewritten for a job.
  - A rewrite that adds a number or a skill that isn't in your resume is left out, with the reason.
  - The job's skills that your resume doesn't show are listed. Nothing is saved.
- **Personal context (Personal AI Brain opt-in), per course:**
  - Off by default. You tick goals, notes and projects for one course.
  - Lessons say what they used. Chats are never used.
- **Diagrams:** lessons can include a Mermaid diagram, drawn locally (strict security). The text is shown if drawing fails. Android shows the text.
- **Progress report:** a printable page from recorded numbers only. Save it as PDF from the print dialog.
- **Model quality check:** 3 short probes; the result is cached per model with honest advice. A model is never blocked.
- **Course streak:** now counts any study activity on the course, not only focused time. No time is invented.
- **Drive sync for Study:** the merge rules are prepared and tested in `study_sync.py`. They are not connected; the live sync protocol is unchanged.

## [7.2.0-dev.1]: Study, for courses and interview preparation (2026-11-23; development build, not production-signed)

Android 7.2.0-dev.1 = versionCode **7020001**. SHELL_REV `ui72.1`. No new dependencies and no new Android permissions. Spec: `docs/specs/ABHIFY_STUDY_V1.md`. Report: `docs/ABHIFY_7_2_0_DEV1_STUDY_REPORT.md`.
- **Study tab** (Windows, Web and Android, each standalone): Today, Courses, Interview plan, STAR stories, plus Discover (unchanged).
- **Courses:**
  - 5 ready-made tracks (AI/ML, Software Engineering, Data Science, Cloud, Behavioural) and courses on any topic (AI-written outline).
  - On first open each course asks for your goal (preset role or custom), your level (beginner, intermediate, advanced or custom) and minutes a day.
  - Changing them later keeps every bit of progress. The earlier version is kept, and the course shows what changed. Finished lessons are never assigned again.
- **Lessons** are written on demand for your goal and level, then saved: in simple words, going deeper, advanced, real-world example, code, common mistakes, exercise, interview questions and follow-ups. Each lesson has an AI tutor that sees only the lesson and your goal.
- **Practice and revision:**
  - Unlimited MCQs: the next 5 load as you scroll, with no repeats. Practice stops when nothing new comes back instead of generating forever.
  - You can practise all modules, one module, or weak topics first.
  - Flashcards and missed questions follow spaced repetition (1, 3, 7, 14, 30 days). A question missed twice comes back the same day.
  - Daily and weekly revision.
- **Course dashboard** from recorded data only:
  - progress, lessons and modules done, real study time and score;
  - exercises done, weak topics, items to revise and streak;
  - next lesson, weekly milestones, capstone project and learning history.
- **Interview plan:**
  - Paste a job description to get its required and preferred skills and your gaps against your resume. The resume is used only if you paste it.
  - You also get a day-by-day plan to your interview date.
  - A new plan keeps the old one as a version, and moves skills you already have at Ready to the end.
- **STAR stories:** a bank covering 8 behavioural themes, with AI coaching. The tighter version is dropped if it adds any number that isn't in your story.
- **Data:**
  - Windows saves to `data/study.json`: atomic writes, a `.bak` of the last good file, and an unreadable file kept as `study.corrupt-*.json` (never overwritten). It has a schema version and is included in backups.
  - The Web app saves in this browser, with the same protections.
  - Export and Import are on every platform. Nothing syncs between devices.
- **Privacy:** Study uses local models only. A cloud chat model choice falls back to the local default, so lessons, answers, resumes and stories never go online. The Personal AI Brain link is deferred.
- **Voice:** one soft, friendly voice (Android): calmer pace and a warmer tone. Other voices are offered only when the phone really has them.
- **Robustness for small local models:**
  - AI output length is capped;
  - lesson headings in other styles are normalised;
  - a lesson must contain its sections, with one automatic retry;
  - list items written as one string are split.

## [7.1.0-rc.2]: fixes from the owner's real-phone test (2026-11-23; release candidate, not production-signed)

Android 7.1.0-rc.2 = versionCode **7010052**. SHELL_REV `ui71.4`. New Android library: Vosk 0.3.47 + JNA 5.13.0 (owner-approved; offline wake word). No new Android permissions. Report: `docs/ABHIFY_7_1_0_RC2_REPORT.md`.
- **Android crash (found from the phone's crash report):** the citation checker used a regex flag, `(?U)`, that Android's ICU engine rejects; desktop Java (and so every unit test) accepts it. Every answer crashed about a second in, typed or spoken. Fixed with explicit Unicode classes. The answer's cleanup step can no longer throw, and a test forbids the flag.
- **Android hardening:**
  - the native engine's token callback can't leave a pending Java exception;
  - native crashes, low-memory closes and ANRs are recorded from Android's exit records;
  - a local voice-pipeline log (no personal text) is attached to crash reports.
- **Weather and live questions (Android):** when current information can't be fetched (offline, no city), the app says so itself and the model isn't asked, so it can't invent today's weather.
- **Android voices:** Male / Female / Assistant each use the voice you pick (with ▶ preview) when the phone's speech engine doesn't label voices by gender; before, all three fell back to the same default (female) voice. The engine name and voice count are shown. Test microphone.
- **Android wake word:** offline Vosk engine, optional (41 MB, verified SHA-256, from alphacephei.com), with Install / Cancel / Test / Remove. Off by default, listens only while the app is open.
- **Android chat:** the empty screen shows a heading ("Ask <name> anything") instead of a greeting that looked like an answer.
- **Windows/Web microphone:**
  - after an update the page reloads once, so the new code runs (before, the first visit still ran the old cached code);
  - Whisper's invented words on silence ("Thank you.") are dropped;
  - "The microphone isn't picking up sound (device)" when nothing is heard;
  - Settings → Voice → Test microphone with a live meter;
  - short phrases with "chat"/"assistant" are no longer swallowed as a command that answered "How can I help?".
- **Windows:**
  - online AI is offered in the model picker ("+ Add Gemini, Groq or OpenRouter");
  - Model Library: "Available to download", 11 more models read from the Ollama registry (sizes, digests, licences), offered but never auto-chosen;
  - opens in its own app window (Edge app mode; tray option to go back to a browser tab);
  - the installer has an update mode ("Update Abhify.AI <old> → <new>", skips the setup pages, keeps all data).

## [7.1.0-rc.1]: voice and microphone stabilization (2026-11-23; release candidate, not production-signed)

Android 7.1.0-rc.1 = versionCode **7010051** (new stage rule: dev.N = N, rc.N = 50+N, final = 99, so it installs over 7.1.0-dev.2). SHELL_REV `ui71.3`. No new Android permissions. Windows database: schema v7 adds three empty bookkeeping tables for the 7.2 Data & Sync work (additive; a backup is made first). That work is included but its entry is hidden in this release (owner decision). New Python dependency `cryptography` (owner-approved, used by the hidden 7.2 work). Report: `docs/ABHIFY_7_1_0_RC1_VOICE_REPORT.md`.
- **Windows desktop:** the large orb in the middle of Home and the Assistant is now a real "tap to talk" button. Before, it looked like the microphone but did nothing. Speech recognition on this computer (Whisper) no longer depends on which AI answers: with "On-device AI" chosen, the microphone used to say the computer wasn't reachable. Two quick clicks no longer open two microphone sessions.
- **Standalone Web:** the microphone works without a computer. It uses the browser's own recognition after a one-time notice that the browser's maker processes the audio online. Before, it always asked for a computer that a standalone Web app never has. Browsers without recognition (Firefox) get a clear message, and typing still works. Microphone errors no longer give Windows-specific advice on phones.
- **Android crash fixes:**
  - Recognition can't crash the app: a stale result from an earlier session is ignored, and every recogniser error has a plain message. If the on-device recogniser fails, Android's standard recogniser is tried once.
  - If no AI is set up, the voice companion says so, puts your words in the message box and stops listening, instead of looping.
  - Any failure while preparing an answer becomes a message, never an uncaught exception.
  - Low-memory phones no longer load the AI model while the microphone listens.
  - Leaving the app releases the microphone.
  - Retry and Dismiss buttons for voice errors.
  - A local crash report (Settings → Voice & Conversation) for diagnosing future crashes on the phone; it is never uploaded.
- **Android voice:** exactly three output voices, Male, Female and Assistant. They are resolved against the voices the phone's speech engine really has installed. When there is no match, the app says so and uses the default voice. Preview and Stop preview. Older saved styles map to the nearest of the three.
- **Android wake word (optional, off by default):**
  - "Listen for <your assistant's name> while Abhify is open", using the name from Assistant identity, any script, with small misrecognitions tolerated. The name must open the phrase, so ordinary speech doesn't trigger it.
  - Only on phones with on-device recognition, so audio never leaves the phone. Only while the app is open on screen; a pill on every screen shows it, with Stop.
  - Not available: background or screen-off listening (it would need a new permission and a permanent notification).
- **Tests:** backend `test_voice_711.py`; Android `V711VoiceTest`; browser checks with a simulated microphone (desktop end to end, Web consent flow). No physical phone or laptop microphone was tested.

## [7.1.0-dev.2]: assistant identity, voice styles, Windows wake phrase, faster answers, cross-origin security (2026-11-20; development build)

Database schema: unchanged (v6; new settings are key/value preferences). No new dependencies. No new Android permissions. SHELL_REV `ui71.2`. Android 7.1.0-dev.2 = versionCode **7010002**. Reports: `docs/ABHIFY_7_1_0_DEV2_REPORT.md`, `docs/ABHIFY_7_1_0_DEV1_STABILIZATION.md`.
- **Independent platforms:** Windows, Android and the Web app each keep their own name, voice and settings; the standalone Web build (`abhify-platform=web`) never contacts a backend and runs the AI in the browser.
- **Assistant identity** (`backend/identity.py`, `chat/Identity.kt`, web `validateAssistantName`/`identityAnswer`): suggested names or a custom one (validated, any script), used in prompts, the UI and voice; "What is your name?" / "Who are you?" answered by the app with the current name. Shared cases `backend/tests/data/identity_cases.json` on all three.
- **Voice & speech:** styles Female / Male / AI Robot / Assistant / Friendly / Deep / Soft / Energetic / Custom mapped onto the real installed voices (gender only from the voice's own name or engine marks; Robot is a labelled pitch effect), language, voice, speed 0.75–1.5×, pitch and volume (Custom), preview / stop / restore; desktop profile stored by the backend, Web in the browser, Android in its own settings (pitch added).
- **Wake phrase (Windows):** "Hey <name>" with the Windows built-in offline recognizer (`backend/wakeword.py`; no dependency, no download, no audio leaves the PC): off by default, explicit confirmation, listens only while an Abhify.AI window is visible, pauses after each activation, paused again after an update, "Test my wake phrase". Measured on synthetic speech: 6/6 detected, 0/24 false activations for "Hey/Hello Nova"; per-name results (some names, e.g. Abhify, Abhi, Aira, weren't recognised). Web: honestly unsupported (no offline engine in browsers). Android: not implemented (needs an approved engine/permission).
- **Performance:** the agent's tools are offered only when a message asks for one (memory, documents, the web); general questions skip the tool round. Warm first token 5.5 s → 0.95 s (llama3.2, measured).
- **Hardware report:** CUDA version supported by the NVIDIA driver and the Vulkan runtime.
- **Security (from the stabilization pass):** CORS no longer trusts `*.netlify.app` / `*.vercel.app`; cross-site requests to the API are refused (CSRF); a loopback connection counts as this computer only with a loopback Host (DNS rebinding).

## [7.1.0-dev.1]: voice, cloud providers on Windows/Web, branding, real-time honesty (2026-11-20; development build, not released)

Database schema: unchanged (v6). No new dependencies. No new Android permissions. SHELL_REV `ui71.1`. Android 7.1.0-dev.1 = versionCode **7010001** (source; only place detection changed). Report: `docs/ABHIFY_7_1_0_DEV1_REPORT.md`.
- **Voice (Windows desktop / web):** speech recognition runs on this computer by default (Whisper); the browser's recognition (audio sent to Google/Microsoft) only when chosen. The Whisper model is never downloaded silently any more (before: a hidden ~145 MB download on first use, or a raw error offline): `GET /voice/status`, `POST /voice/model/download` (confirm, this computer only), a clear 409 when missing. Listening: visible state with a live level meter, stops on silence after speech (also when you start talking at once), at 60 s (was 15 s), when the window is hidden or the microphone disappears; Cancel discards. The transcript goes into the message box to check, edit, send or discard; Hands-free conversation (off by default) sends it and listens again after the answer is read. Microphone choice (falls back to the default if unplugged), clear messages for blocked / missing / busy microphones. Read aloud: per-answer Listen, a Stop chip, a choice of system voice (local voices first), "Read answers aloud: spoken questions / every answer / never".
- **Cloud AI providers on Windows/Web** (`backend/providers.py`): Google Gemini, Groq, OpenRouter (same endpoints and free-model rules as Android) and a custom OpenAI-compatible endpoint (https, or a server on this computer; private/link-local addresses refused, re-checked before each request, no redirects). Keys tested with the provider before saving, encrypted with Windows DPAPI in `<data>/secrets/` (never in backups, never returned, only masked). Saved providers are checked at start. Cloud is used only for a model picked in the chat (one-time consent per provider) or the opt-in Auto router (coding / complex questions; off by default). No fallback between providers. Cloud chats never receive memories or documents; project chats stay local. Every answer is tagged provider · model · cloud. Optional, skippable setup step; More → AI & System → AI providers.
- **Branding:** the header and sidebar show the official Abhify.AI mark again (`brand/logo-mark-96.png`) and the full name "Abhify.AI"; 7.0 had replaced it with a drawn orbit symbol.
- **Real-time:** "weather in my location / here / near me" no longer geocodes the word "location" (it answered for a US town and saved it as your city); it uses the saved city or asks (same on Android). A missing reading (weather, exchange rate, system status) gets the app's own answer instead of a remembered number. Calculations and code questions are answered without a web search tool.

## [7.0.2]: hardware-aware local model setup (2026-11-19; packages in `release/Abhify.AI-7.0.2/`)

Database schema: unchanged (v6). Android 7.0.2 = versionCode **7000299** (development certificate, updates 7.0.1/7.0.0). SHELL_REV `ui70.6`. No new dependencies, no new Android permissions.
- **Windows/desktop:** `backend/hardware_advisor.py` + `backend/model_catalog.json` (sizes, digests, quantization and licences read from the Ollama registry). `GET /setup/advice` reports this computer (each fact labelled verified / estimate / unavailable: Windows version from the build, CPU name from the registry, RAM, NVIDIA GPU + driver via nvidia-smi, other adapters via WMI, disk, Ollama) and recommends one model: a model that is comfortable (GPU <= 70 % of VRAM; CPU <= 50 % of RAM left after a max(4 GB, 30 %) reserve, up to the 7-8B tier), preferring the GPU and an already-installed model (no duplicate download), never the largest that merely fits. `POST /setup/use-model` runs a real inference first and only then makes the model the default. The setup wizard and Model Library show it; downloads still need confirmation (progress, cancel, resume).
- **Android:** the recommendation is now stable, not the largest that fits (the best compatible model leaving 3.5 GB for Android and apps, within the memory free now, and not while the phone overheats); low battery is a warning; the device step shows phone, SoC, CPU features, Vulkan, battery and the execution mode (CPU until the GPU passes a per-model test). Download, verification and the self-test before activation are unchanged.
- **Web:** the browser capability report (secure context, WebAssembly, WebGPU adapter, buffer limit, logical processors, memory estimate, storage quota, isolation), each labelled; a model is offered only when browser storage holds it; a real test answer before the browser AI is marked ready.
- **Citations** (from 7.0.2-dev.1): remarks about source ages checked against real publication times; the publisher/date attribution after a marker no longer counts as a claim (23 shared cases).

## [7.0.2-dev.1]: source-age checks (2026-11-19; source, not built)

Database schema: unchanged (v6). Android 7.0.2 (7000201) in source.
- A remark that names sources ("the information on [W1] and [W2] is from 1 and 2 days ago") is checked against the sources' real publication times (±1 unit) instead of being marked unsupported; a wrong age is still unsupported. Chained references ("on [W1] and [W2]") recognised. Seen live with llama3.2 after the 7.0.1 build; 21 shared cases.

## [7.0.1]: citation integrity, internal release (2026-11-19; packages in `release/Abhify.AI-7.0.1/`)

Android 7.0.1 = versionCode **7000199** (debug certificate, updates 7.0.0). SHELL_REV `ui70.5`. Also in 7.0.1: a marker used as the NAME of a source ("the information on [W1] is from…", "see [W2]") is kept as a reference instead of being marked unsupported (live false positive), while "According to [W1], …", "In [W1], X…" and any sentence whose numbers aren't in the source stay fully checked (18 shared cases).

### 7.0.1-dev.1

Database schema: unchanged (**v6**). No new dependencies. SHELL_REV `ui70.4`. Report: `docs/ABHIFY_7_0_0_CITATION_INTEGRITY.md`.
- **`backend/citations.py`** (and `Citations` in Android `intel/Realtime.kt`, same rules): after generation, an answer keeps only citations backed by its retrieved sources. Ids not in the source registry are removed; a sentence whose cited source lacks its facts (numbers must match) is moved to the one other source that has them (small local models misnumber facts), else marked `[unsupported]`; links that are not one of the sources are removed. Immutable registry with a fingerprint; `live.integrity` reports what changed and the page shows it. Applied before the answer is shown or saved.
- **Sources:** links into private/loopback/`.local` hosts are never shown; a publication time after the retrieval time becomes "date not given".
- **Voice:** `[unsupported]` is never read aloud (web and Android).
- **Tests:** shared cases `backend/tests/data/citation_cases.json` (11) run on both platforms, plus fuzzing (no marker outside the registry survives).

## [7.0.0]: Abhify.AI 7.0 Synapse release (2026-11-19)

Everything in 7.0.0-dev.1 and 7.0.0-dev.2 below, packaged for Windows (installer and portable ZIP), Android (7.0.0, versionCode 7000099) and web (backend package, Docker, configuration template) in `release/Abhify.AI-7.0.0/` (kept out of Git). Database schema **v6**. SHELL_REV `ui70.3`. Also: the Workspace items fit the icon rail at 600–1023 px; the Model Library shows badges in one row.

## [7.0.0-dev.2]: in-chat model switching and Model Library (2026-11-19; source)

Database schema: **v6** (additive: `conversations.model`, `messages.model`; `migrate()` backs the database up first). No new dependencies.
- **Root cause fixed:** typing "switch the model to llama3.2" went to the current model, which replied "Sure, I'll switch the model to Llama3.2" while the response's own `model` field still said qwen2.5. Model commands ("switch/change the model to …", "use the … model", "set the default model to …", "which model are you?") are now recognised by the app (`backend/model_switch.py`) and carried out for real, checked against Ollama's installed chat models; a failure (not installed, ambiguous, Ollama down) says why and keeps the previous model. Loose phrasing ("switch to X") only counts when X is an installed model.
- **One source of truth:** each conversation's model is stored on the backend (`conversations.model`). The model for a turn is the one sent by the picker, else the conversation's, else the global default; switching conversations restores it; new chats inherit the default. `PUT /models/conversation/{id}` (picker). A stored model that was removed falls back to the default with a notice.
- **Proof of the serving model:** answers carry `served_by`, the model name Ollama returns in its own response metadata, stored per message (`messages.model`) and shown under each answer. Live check: `tests/test_model_switch_live.py` (opt-in `ABHIFY_LIVE_OLLAMA=1`) passed against the local Ollama (llama3.2 ⇄ qwen2.5).
- **Model picker:** installed status, in memory (`/api/ps`), Ollama's own capabilities (`/api/show`, never assumed), parameter size, quantization, disk size, a "good for" note for known families, Active/Default badges; "Model switched to …" confirmation; errors keep the previous model.
- **Model Library** (More → AI Models, sidebar → Models): installed models, Make default, Remove (confirmation; the default and the embedding model are refused), Download a named model only after confirmation with live progress and Cancel (`POST /setup/models/pull/cancel`; Ollama resumes later), Refresh.
- **Sidebar:** a Workspace group (Tasks, Projects, Knowledge, Models, Tools, System) under the five destinations; phones keep the five-item bar.
- **Version:** desktop/web `7.0.0-dev.2` (SHELL_REV `ui70.2`). `7.0.0-dev.1`'s installer stays as built.

## [7.0.0-dev.1]: Synapse redesign, web and desktop (2026-11-18; installer built: `dist/windows/7.0.0-dev.1/`)

Database schema: unchanged (**v5**). No new dependencies, no API change. SHELL_REV `ui70.1`. Report: `docs/ABHIFY_7_0_WEB_DESKTOP_REDESIGN.md`; reference mockup `docs/design/concepts/option-a-synapse.html`.
- **Look:** the approved "Synapse" identity. Obsidian background (#06070D) with soft violet and cyan glows; glass cards with a violet hairline, 16 px radius, an inset highlight and a soft shadow; violet (#9D7BFF, dark text on it) as the primary colour and electric cyan (#3DE0FF) for live data. A matching light palette (#F6F5FF background, #5B3FE0 violet); text contrast is at least 4.5:1 in both themes, and a test checks it.
- **Type:** Space Grotesk (headings, big numbers, wordmark), Inter (body) and JetBrains Mono (times, sources, model names, eyebrow labels). The three variable fonts are bundled in `frontend/fonts/` with their SIL OFL licences and precached by the service worker, so the shell still renders offline. No CDN.
- **Neural core:** an SVG sphere with two orbiting rings (18 s / 11 s) and a breathing pulse replaces the old ring orb in the brand mark, the Home hero, the briefing card, the assistant welcome, News and Talk (voice overlay). Its states are idle, thinking (faster), listening (cyan) and unavailable (grey and still). It stays still with reduced motion.
- **Navigation:** the same five destinations, with line icons. The sidebar shows the core, the "Abhify" wordmark and "AI Assistant" (no wrapping); the active item is a violet pill with an inset ring. A new **Active model** card shows the model that answers next and the real connection state (for example "Local · ready"). Collapse still works; phones keep the bottom bar with the active item in a violet pill.
- **Components:** primary, glass-outline secondary and ghost buttons; `.qa` quick actions; mono source chips with freshness colours (live cyan, recent violet, cached amber, unavailable grey); violet→cyan progress bars; restyled inputs, dialogs, toasts, skeletons, and empty and error states; a subtle message-appear animation and a card hover lift (existing motion tokens).
- **Version:** desktop/web `7.0.0-dev.1` (`backend/version.py`, service-worker cache `abhify-v7.0.0-dev.1+ui70.1`).
- **Tests:** 550 passed, 2 skipped (was 541 passed, 2 skipped; 9 new in `test_web_desktop_70.py`; `test_web_desktop_61.py` now pins `ui70.1` / `abhify-v7.0.0-dev.1`).

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
