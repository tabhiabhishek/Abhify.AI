# Abhify.AI 7.2.1: Stage A release audit (before any change)

**Date:** 2026-11-25. **Method:** read-only inspection of the repository, artifacts and machine. No file was modified for this audit.

## 1. Git

| Item | Value |
|---|---|
| Branch | `release/7.0.0` (local only; there is no remote configured) |
| Last commit | `592df89` (2.0.0-dev.8 / Android 6.2-dev.1); everything after it is uncommitted |
| Status | **80 modified, 154 untracked** entries; no stashes |
| Pre-existing work | All modified and untracked files from 7.0.0 to 7.2.1-dev.2 are uncommitted. They belong to this release line and none are disposable. They are listed by `git status` and were reconciled on 2026-11-25 (`ABHIFY_7_2_1_DEV1_PRIVACY_PATCH_PLAN.md` §0.4) |
| Planned Git operations | **None.** Committing or tagging 7.2.1 needs your separate approval; the proposed commit plan is in the final report |

## 2. Versions found (source tree)

The tree is at **7.2.1-dev.2**: the installer fix, built and Sandbox-tested today. It is not in any release folder.

| Place | Value |
|---|---|
| `backend/version.py` | `7.2.1-dev.2` (the API `FastAPI(version=__version__)` and `/health` read it) |
| `frontend/sw.js` | `CACHE_VERSION abhify-v7.2.1-dev.2`, `SHELL_REV ui72.4` |
| `frontend/app.js` | `CACHE_HINT v7.2.1-dev.2` |
| `android/app/build.gradle.kts` | versionCode `7020102`, versionName `7.2.1-dev.2`. Scheme: major·10⁶ + minor·10⁴ + patch·10² + stage, where dev.N = N, rc.N = 50+N and **final = 99** |
| Installer | `VERSION` from `version.py` (ProductVersion and FileVersion) |
| Update manifest | `dist/windows/latest.json.template` is generated per build. In-app updates are **not configured** (`manifest_url: None`, no trusted keys, so it's opt-in and off) |

**Final version plan:** `7.2.1` everywhere. Android versionCode **7020199** (final = 99), which is greater than every earlier code (7020102, 7020101, 7020002, …).

## 3. Releases (all preserved)

| Folder | Size | Note |
|---|---|---|
| Abhify.AI-7.0.0 / 7.0.1 | 444 MB each | old layout (`Android/`, `Windows/`, `checksums.txt`) |
| Abhify.AI-7.0.2 | 1,095 MB | |
| Abhify.AI-7.1.0-dev.1 / dev.2 | 190 / 1,064 MB | |
| Abhify.AI-7.1.0-rc.1 / rc.2 | 1,084 / 1,091 MB | |
| Abhify.AI-7.2.0-dev.1 / dev.2 | 1,095 / 1,100 MB | |
| Abhify.AI-7.2.1-dev.1 | 1,066 MB | Its installer fails to update when a stray Abhify process runs (laptop report); fixed in dev.2 source |

## 4. Build and intermediate artifacts (cleanup candidates)

| Path | Size | Referenced by | Proposed action |
|---|---|---|---|
| `dist/windows/7.2.0-dev.2`, `7.2.1-dev.1` | 122 MB each | **Sandbox upgrade test** (`Find-Installer`) | **Keep** |
| `dist/windows/7.2.1-dev.2` | 122 MB | Today's tested fix | Keep until 7.2.1 is built, then archive |
| `dist/windows/2.0.0-dev.8 … 7.2.0-dev.1` (10 folders) | ≈ 1.2 GB | Nothing at run time; copies of release installers (2.0.0-dev.8 and 7.0.0-dev.1 have no release folder) | Archive (move) after hash comparison |
| `build/internal/*.apk` (5.4-dev.1 … 7.0-dev.1) | 711 MB | Historical build reports (paths and hashes as evidence) | Archive (move) with a path map |
| `build/Abhify-5.0/5.1/5.3/5.4.apk` (+ androidTest) | 281 MB | Historical reports | Archive (move) |
| `build/portable/Abhify.AI-7.0.0 … 7.0.2-fixed` | 2,470 MB | Nothing; unpacked copies of the 7.0.x portables | Archive (move) |
| `build/windows/7.0.2-fixed` | ≈ 120 MB | The fixed 7.0.2 installer (also in `release/Abhify.AI-7.0.2`) | Archive (move) after hash check |
| `build/windows/staging`, `build/sandbox-results` | — | `build.py` and the Sandbox scripts | **Keep** |

The archive target is `D:\Abhify_Backups\archive-pre-7.2.1-build-artifacts\`: outside the repository, next to your existing backup snapshots. Every move is recorded with its source path, size and SHA-256. Nothing is deleted.

## 5. Platforms (verified in code)

| Platform | Entry points | Data | AI runtime |
|---|---|---|---|
| Windows desktop | `desktop/launcher.pyw` (tray, Edge `--app` window) → FastAPI `backend/main.py` (218 routes) → PWA `frontend/` | `%LOCALAPPDATA%\AbhifyAI\` (SQLite schema 7, Chroma, JSON stores, `secrets/` DPAPI) | Ollama (local; installed: `llama3.2`, `qwen2.5`, `nomic-embed-text`); Whisper (`transcribe.py`); optional cloud providers (off by default) |
| Android | `ui/MainActivity.kt` / `AppViewModel.kt`; Kotlin + Compose; minSdk 29, targetSdk 36 | `abhify.db` (ChatDb v17), `filesDir/*.json`, Keystore | llama.cpp via JNI (Qwen2.5 1.5B / 0.5B Q4_K_M, downloaded on confirm); bge-small embeddings (optional); platform STT/TTS; optional providers |
| Web (standalone) | Same `frontend/` with `abhify-platform=web`; no backend | localStorage, IndexedDB | WebLLM (Qwen2.5 1.5B / Llama 3.2 1B, WebGPU, confirmed download) |

There is no shared runtime and no automatic sync. Encrypted Drive portability is **implemented in code but hidden** (`DATASYNC_VISIBLE=false`), pending your drive.file check.

## 6. Documentation

- `docs/`: 220 Markdown files. These are historical reports, specs and guides from 1.0 to 7.2.1.
- Some are referenced by tests and code comments (`WINDOWS_INSTALLATION.md`, `MOBILE_INSTALLATION.md`, `specs/*`, `CODE_SIGNING.md`, …).
- **Plan:**
  - New canonical `docs/ABHIFY_AI_COMPLETE_DOCUMENTATION.md`.
  - A new `docs/README.md` index that classifies the historical reports as archive/evidence.
  - The originals stay where they are, so no references break.
- **README guard test:** `backend/tests/test_packaging_dev3.py::test_readme_documents_every_api_route` requires every one of the 218 routes to be listed in the root README. A concise README can't hold that table, so the full API table moves to the canonical documentation and the test is pointed there. This is an explicit, reported test change.
- **Name in the request:** the report you listed as `docs/ABHIFY_7_2_1_INTEGRITY_REPORT.md` is actually named `docs/ABHIFY_7_2_1_DEV1_INTEGRITY_REPORT.md`.

## 7. Tests: latest real results (to be re-run in Stage B/E on the final code)

| Suite | Latest result | Code state |
|---|---|---|
| Backend full (with portable Node v22.23.5) | 896 passed, 0 failed, 3 skipped | 7.2.1-dev.1 code (before the installer fix) |
| Android unit + lint | 567 / 0 failures / 4 skipped; lint 0 errors | 7.2.1-dev.1 (Android code unchanged since) |
| Sandbox upgrade 7.2.0-dev.2 → 7.2.1-dev.2, **with a stray process** | 35/35 | dev.2 installer |
| Browser E2E (llama3.2) | 43/43, 20/20 | 7.2.1-dev.1 |
| Privacy captures F1 / F1b | Pass | 7.2.1-dev.1 |

## 8. Signing

| Item | State |
|---|---|
| Android | Debug certificate `1d2d1fdb…` (`C:\Users\Administrator\.android\debug.keystore`; a git-ignored copy is at the repo root). There is **no release signing config** and no production keystore |
| Windows | Installer and portable **not Authenticode-signed**. `packaging/windows/sign.ps1` exists but needs a certificate. `signtool` was likely removed with the Windows SDK on 2026-11-24 |
| Rule | I do not create, request or store production keys. Packages will be labelled honestly |

## 9. Risks found by the audit

1. **Physical validation is pending.** W1–W14 (laptop) and A1–A8 (Android) are not done, so 7.2.1 can be labelled "stable for own use, validation pending" but not production-ready.
2. **The 7.2.1-dev.1 installer has a known update bug** (fixed in source). The final 7.2.1 installer includes the fix and the stray-process Sandbox test.
3. **Not production-signed.**
4. **C: has 19 GB free.** D: has 234 GB.

## 10. Plan for the remaining stages

- **B:** final-code regression (backend with Node, Android, E2E, privacy captures, microphone matrix).
- **C:** canonical documentation, README with diagrams, docs index, signing guide.
- **D:** version `7.2.1` (Android 7020199), builds, `release/Abhify.AI-7.2.1/`, archive moves.
- **E:** fresh install, upgrade from 7.2.0-dev.2 and 7.2.1-dev.1 (with a stray process), integrity before/after, checksums of all releases.
- **F:** final report.
