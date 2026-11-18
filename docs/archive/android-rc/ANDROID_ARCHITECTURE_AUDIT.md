# Android architecture audit (Phase 0) — 2026-11-02

Audit of the repository before the "Independent Local-First Hybrid AI" work. Read-only facts; decisions are in [ANDROID_IMPLEMENTATION_PLAN.md](ANDROID_IMPLEMENTATION_PLAN.md).

> **Honesty note on timing.** The previous instruction ("Online Authentication, Hybrid AI and UI Redesign") arrived first, and implementation had started before this audit was requested. The **baseline below is the app as built and tested on 2026-10-26** (2.0.0-rc.1, the last state that compiled and passed its tests). Files changed since then are listed under *In-progress changes*. Nothing is committed; no model weights, user data or desktop files were deleted.

## Repository state
- **Branch:** `feature/m2-knowledge-packaging` @ `a9325d2`. No remote push. `main` and the `v1.0.0` tag are untouched.
- **Tracked files:** 124. **Uncommitted, tracked (10, modified):** dev.7 web on-device work (`frontend/app.js`, `index.html`, `sw.js`, `backend/version.py`, `backend/tests/test_pwa.py`, `.gitattributes`, `README.md`, `CHANGELOG.md`, two mobile docs).
- **Untracked (new):** `android/` (71 files), `cloud/` (13 files), `frontend/ondevice.js`, `frontend/vendor/`, `backend/tests/test_ondevice.py` and its harness, and the Android/on-device docs.

## Android app (baseline 2.0.0-rc.1)
| Item | Value |
|---|---|
| Package / applicationId | `ai.abhify.android` (permanent once released) |
| minSdk / targetSdk / compileSdk | 29 (Android 10) / 36 / 36 |
| ABIs | release `arm64-v8a`; debug also `x86_64` (emulators) |
| Kotlin / AGP / Gradle | 2.3.0 / 8.13.2 / 8.14.3 (wrapper SHA-256 `bd711022…`) |
| JDK / NDK / CMake | Temurin 17.0.20.1 / r28c (28.2.13676358) / 3.31.6 |
| UI | Jetpack Compose (BOM 2025.12.01), Material 3, single Activity |
| versionCode / versionName | 2000001 / 2.0.0-rc.1 |
| Toolchain location | `D:\AndroidDev` (outside the repo) |

### llama.cpp integration
- **Version:** v0.7.0, commit `620d2225cc436ab0b001e3200e590e3b286ef9ab`, fetched by CMake `FetchContent` with archive SHA-256 `1d4a987d9bd048f6e93107e838026438df70fbde237fc0c69c63d600512b4bd9`.
- **CPU:** `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`, so 7 ARM variants (v8.0 → v9.2 SME) are picked at run time. KleidiAI and OpenMP on arm64.
- **Vulkan:** optional build flag `-Pabhify.vulkan=true` (off). Used only after the per-phone *Test GPU* check; GPU→CPU fallback on load failure.
- **Bridge:** `app/src/main/cpp/abhify_llm.cpp`. It uses the `llama.h` C API only (`llama_decode`, `llama_batch_get_one`, `llama_chat_apply_template`, sampler chain), and passes text as UTF-8 byte arrays. Native libraries are extracted on install (`extractNativeLibs=true`); this fixed the dev.1 self-test failure on the K50i.
- **Kotlin:** `llm/LlmEngine.kt`, with one worker thread, streaming `Flow`, cancellation, and peak-RSS reporting.

### Models (`app/src/main/assets/model_catalog.json`)
| Model | File | HF revision | Size (bytes) | SHA-256 | License |
|---|---|---|---|---|---|
| Qwen2.5 1.5B Instruct Q4_K_M | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `91cad51170dc346986eccefdc2dd33a9da36ead9` | 1,117,320,736 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` | Apache-2.0 |
| Qwen2.5 0.5B Instruct Q4_K_M | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | `9217f5db79a29953eb74d5343926648285ec7e67` | 491,400,032 | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` | Apache-2.0 |

**Model lifecycle:**
- `Downloader` downloads over HTTPS only, resumable with Range, checks storage first, then size and SHA-256.
- `ModelStore` (`noBackupFilesDir/models`) moves the file from staging to a real self-test and only then activates it. A failed self-test keeps the previous model; a failed new model is kept in staging for a retry.
- `DownloadService` runs it as a foreground service. `ModelSelector` chooses by RAM, storage, ABI, API and memory pressure.

### Local database (baseline: `ChatDb`, SQLiteOpenHelper, version 2)
| Table | Columns | Since |
|---|---|---|
| `conversations` | id (autoinc), title, created_at, updated_at | v1 |
| `messages` | id, conversation_id → conversations ON DELETE CASCADE, role, content, created_at, stats | v1 |
| `memories` | id, content, created_at | v1 |
| `quiz_answers` | id, category, question, correct, created_at | v2 |
| `interview_views` | question (PK), category, created_at | v2 |

- **Migrations:** v1→v2 is additive (`CREATE TABLE IF NOT EXISTS`). There is no Room.
- **Not in a database:** preferences are in SharedPreferences `abhify`, and the model registry is `models/state.json`.
- **Backups:** `allowBackup=false` and `dataExtractionRules` exclude everything.

### Other Android features (baseline)
- **Tabs:** Chat, Interview, Quiz, News.
  - Interview and quiz content: a 30 + 30 bank, plus AI-generated items that are validated.
  - News: 8 RSS/Atom feeds, online only, cached.
- **Voice:** on-device recognizer when available, plus TTS.
- **Updates:** the signed-update client (Ed25519 `abhify-update-1` envelope, same certificate, Android installer confirmation). Not configured.
- **Tests:** JVM unit tests 41 (see the baseline report), and on-device instrumented tests in `OnDeviceE2ETest`.

## Desktop / web app (not to be changed)
- **Backend:** FastAPI `backend/main.py`, single user by design. "Membership" means **device tokens** (owner devices and guest members) on one PC, not user accounts; documented in `membership.py`. Chat is not streamed. Ollama runs on the PC.
- **Routes:** `/health /ping /chat /news /generate-question /generate-mcq /transcribe /voice /knowledge/* /progress /memory /preferences /conversations /system/* /setup/* /backup/* /companion/* /updates/*`. Admin routes are `local_only`.
- **Conclusion:** online accounts can't be added to the desktop backend without changing its single-user design. They belong in a separate service.

## Brand assets (reused, not reinvented)
| Asset | Location | Use on Android |
|---|---|---|
| Round logo mark (A + circuit), 512 px | `frontend/brand/logo-mark.png` | launcher icon (adaptive foreground), in-app logo |
| Full badge with "Abhify" wordmark + tagline, 768 px | `frontend/brand/logo.png` | splash/onboarding/about |
| SVGs | `frontend/brand/logo*.svg` | each **wraps an embedded PNG**; not vector art, so the PNGs are the originals |
| PWA icons | `frontend/icons/*` | reference |
| Design tokens | `frontend/styles.css :root` | `--bg-1 #0f172a`, `--bg-2 #1e293b`, glass `rgba(30,41,59,.55)`, line `rgba(139,92,246,.22)`, text `#f1f5f9`, muted `#94a3b8`, violet `#8b5cf6`/`#a78bfa`, pink `#ec4899`, green `#22c55e`, red `#ef4444`, amber `#fbbf24`, radius 14px |
| Typography | `styles.css` body font | **Inter** (bundled on Android from the official v4.1 release, OFL) |

**Finding:** the baseline Android theme (blue/green sampled from the logo) did **not** match the web app's violet/pink glass design. The redesign uses the web tokens above.

## In-progress changes since the baseline (uncommitted)
- **`cloud/` (new):** a separate FastAPI service for accounts, sessions, sync and the online-AI gateway.
  - It has its own SQLite database, scrypt password hashing, opaque rotating tokens and rate limits.
  - Deployment files are in `cloud/deploy/`.
  - **35/35 tests pass.** A real-model streaming check against local Ollama was done on this PC with a temporary server, then stopped.
- **Android (new files):** `cloud/` client (`Http`, `TokenStore` + Keystore, `CloudClient`), `ai/HybridAi.kt`, `sync/`, `chat/CodeHighlight.kt`, Inter font, splash theme, debug-only network config.
- **Android (changed):** `ChatDb` (v3 sync columns), `AppGraph`, `AppViewModel`, `Theme.kt`, `MarkdownText.kt`, Gradle files and the manifest theme. **Not yet compiled at the time of this audit.**
