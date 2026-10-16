# Changelog

All notable changes to Abhify.AI. Format: [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).
The database schema version is listed separately, because not every release changes it.

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
