# Abhify.AI v2: implementation plan

Each milestone ends with tests, a summary and **your approval** before the next one. Nothing is published publicly without approval.

| Milestone | Phases | Status |
|---|---|---|
| **M1: Foundation** | 0 audit · 1 rebrand · 2 hardware-adaptive setup · 3 local data dir · 4 memory engine · 8 SQLite state (most of it) | ✅ **done** (branch `feature/abhify-v2`) |
| **M2: Knowledge folder + installer** | 5 folder watcher · Windows installer · mobile PWA modes · release framework | ✅ **done** (branch `feature/m2-knowledge-packaging`) |
| M3: Local voice out | 7 pyttsx3 / SAPI5 `POST /tts` | planned |
| M4: Desktop app | Signed installer, auto-start options (a Tauri shell is optional; NSIS + launcher ships in M2) | planned |
| M5: Releases & updates | 10 versioning, update manifest, checksums/signing, CI, lockfiles, release checklist | planned |

## M2: Knowledge folder watcher (Phases 5–6)

**Add**
- `backend/document_indexer.py`: parse PDF/TXT/MD/PY; chunk; embed; upsert or replace by `sha256` + path; remove stale chunks; retry queue.
- `backend/folder_watcher.py`: `watchdog` observer as a singleton with start/stop/status; debounce until writes finish; ignore symlinks.
- Built-in exclusions: `.env*`, `*.key`, `*.pem`, `id_rsa*`, `*.db`, `*.sqlite*`, `.git/`, `node_modules/`, `venv/`, files over 20 MB.

**Modify**
- `database.py`: migration v4 (watcher state, failure count, last error).
- `main.py`: `/knowledge/folder` (GET/PUT), `/knowledge/watcher/{start|stop}`, `/knowledge/reindex`, `/knowledge/retry`.
- Frontend: a "Knowledge folder" section in System & data.

**Tests**
- A new file is indexed once.
- A modified file replaces its chunks.
- A deleted file's chunks are removed.
- Locked or corrupt files are retried, and excluded files are never read.
- Rebuild after an embedding-model change.

**Default folder:** `%USERPROFILE%\Abhify_Knowledge` (created only when you enable the watcher).

## M3: Local text-to-speech (Phase 7)

**Add**
- `backend/tts_engine.py`: `pyttsx3` with SAPI5, run in a worker thread, writing to a temp WAV in `cache/` that is deleted after sending.
- `POST /tts`: limited to 1,500 characters; returns `audio/wav`; falls back to browser `speechSynthesis` when unavailable.

**Note:** `pyttsx3` is Windows-only reliable (SAPI5). On macOS/Linux it uses `nsss`/`espeak` when present. It's an optional feature with a settings switch.

## M4: Desktop packaging (Phase 9)

**Recommended: Tauri v2** (small installer, built-in updater with signature verification), not Electron (100 MB+, bundles Chromium).
- The frontend stays plain HTML/JS.
- The Python backend becomes a **sidecar**: a `PyInstaller`-built `abhify-backend.exe` that Tauri starts, health-checks and stops.
- The installer (NSIS/MSI) creates shortcuts and an uninstall entry, and never deletes `%LOCALAPPDATA%\AbhifyAI` unless you choose to.
- Ollama is **not** bundled. The setup wizard detects it and links to the official installer.
- No models are downloaded during install.
- Faster-Whisper on CPU works out of the box. CUDA needs the NVIDIA runtime libraries, which the wizard detects; GPU use stays optional.

**Unverified until built:** sidecar size (Whisper + Chroma likely 300–600 MB), antivirus false positives on PyInstaller binaries, and macOS (needs a Mac to build, sign and notarize).

## M5: Versioned releases & safe updates (Phase 10)

- SemVer in `backend/version.py`. Schema version comes from the `MIGRATIONS` list (append-only).
- `latest.json` manifest (Tauri updater format): version, notes, URL, signature per platform. Hosted on GitHub Releases (URL is a config placeholder until you choose).
- The updater checks only when online and **never forces** an update. Every update is signature-verified. A DB backup is taken before migrations (already implemented in M1).
- CI on GitHub Actions: tests on Windows, build the installer, compute SHA-256, attach to a draft release.
- Pinned lockfile (`pip-compile` / `uv lock`).
- `RELEASE_CHECKLIST.md` plus an upgrade test that installs v(N-1), adds data, upgrades and verifies the data.
