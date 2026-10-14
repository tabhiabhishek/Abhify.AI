# Changelog

All notable changes to Abhify.AI. Format: [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).
The database schema version is listed separately, because not every release changes it.

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
