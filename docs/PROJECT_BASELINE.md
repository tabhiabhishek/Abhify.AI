# Project baseline (audit of 2026-11-06)

**Read-only audit:** nothing was modified, moved or deleted to produce this document.

## 1. What is in this repository
`D:\Abhify.AI` is a **monorepo with three products**:

| Product | Directory | Version | Git state | AI runtime |
|---|---|---|---|---|
| **Abhify.AI desktop/web** (FastAPI backend, PWA frontend, Windows installer) | `backend/`, `frontend/`, `desktop/`, `packaging/` | `2.0.0-dev.7` (source), last installer `2.0.0-dev.6` | Tracked; 11 files modified (uncommitted) | **Ollama** on the PC (+ optional WebLLM in the browser) |
| **Abhify.AI Android** (Kotlin, Jetpack Compose) | `android/` | **4.3.1** (versionCode 4030199) | **Untracked: never committed** | **llama.cpp on the phone** (Qwen2.5 1.5B/0.5B GGUF); optional online providers with the user's own key (Gemini, Groq, OpenRouter) |
| **Abhify cloud service** (optional accounts, sync, AI gateway) | `cloud/` | `1.0.0-dev.1` | **Untracked** | Ollama on the server (not deployed) |

**The Android app does not use Ollama.** The prompts' "Ollama status / setup" items apply to the desktop app and the optional cloud server, not to the phone.

## 2. Git state
- **Branch:** `feature/m2-knowledge-packaging`. HEAD: `a9325d2` ("dev.6 review: clear resumable error…").
- **Last 5 commits:** a9325d2, 4b7df0f, 82f8d1e, d1d064c, a8c9d6d. These are all desktop 2.0.0-dev.5/dev.6 work.
- **Modified, not staged (desktop dev.7 work):** `.gitattributes`, `.gitignore`, `CHANGELOG.md`, `README.md`, `backend/tests/test_pwa.py`, `backend/version.py`, `docs/MOBILE_ACCEPTANCE_TEST.md`, `docs/MOBILE_INSTALLATION.md`, `frontend/app.js`, `frontend/index.html`, `frontend/sw.js`. That's 246 insertions and 24 deletions.
- **Untracked:**
  - `android/` (the whole app);
  - `cloud/`;
  - `.env.example`;
  - `frontend/ondevice.js`, `frontend/vendor/`;
  - 2 backend test files;
  - about 75 docs (Android/cloud documentation 3.0–4.3.1).
- **Nothing staged.**
- **⚠ Risk:** months of Android and cloud work exist **only on this disk**, with no Git history or backup.
- **⚠ Risk:** `.gitignore` has no Android entries (`android/app/build/`, `android/.gradle/`, `android/app/.cxx/`, `local.properties`). If `android/` were added, about 1 GB of build output would go with it.

## 3. Android app architecture (actual)
- **UI:**
  - single `MainActivity`, Jetpack Compose (Material 3), own back stack (`AppViewModel.Route`), 5 bottom tabs (Chat, Career, Study, Documents, Knowledge), Settings from the header, drawer;
  - branded launch screen (4.3); theme tokens in `ui/Theme.kt`;
  - Markdown parser and renderer: `chat/Markdown.kt`, `ui/MarkdownText.kt`.
- **AI:**
  - `HybridAi` routes each request to the phone (llama.cpp via JNI `cpp/abhify_llm.cpp`) or an optional online provider. There is **no automatic cloud fallback**; consent dialogs come first.
  - Streaming tokens, Stop, Regenerate, Edit last message, Continue (after length cut-off), Copy/Share/Save.
  - Device memory profiles and `HeavyGate`.
- **Models:** `model/` (catalog with pinned SHA-256, resumable downloader, foreground download service, `ModelStore` in `noBackupFilesDir/models`).
- **Data:** `ChatDb` (SQLiteOpenHelper, **DB v16**, additive migrations V5…V16 with JVM migration tests). Tables cover chats, messages, memories, documents with chunks/FTS/vectors, study, career, workspaces, Drive queue and backups.
- **Knowledge:**
  - document import: PDF text, OCR with ML Kit, DOCX/TXT/MD/images;
  - SHA-256 dedupe, page-aware chunks, FTS4 + BM25, citations;
  - semantic search: infrastructure only, no model (approval pending).
- **Google Drive:** `drive.file` scope, per-account folders `My Drive/Abhify.AI/{Backups,Documents,Imports,Exports}`, a WorkManager upload queue with resume/retry/conflicts, scheduled backups (off by default), and an optional confirmed migration.
- **Server:** optional (Local Mode by default); `cloud/` client for accounts and sync.
- **Security:**
  - provider keys, session and backup passphrase sealed with the Android Keystore;
  - HTTPS-only release network config;
  - `allowBackup=false`;
  - a secret scan runs on every build.

## 4. Build, test and lint (run 2026-11-06, Android)
| Check | Result |
|---|---|
| `./gradlew --offline testDebugUnitTest lintDebug` | **BUILD SUCCESSFUL** |
| JVM tests | **226 run, 0 failed, 4 skipped.** The skipped ones are opt-in live tests: real providers, cloud integration, live feeds, the semantic evaluation. |
| Lint | **0 errors, 82 warnings** (66 UseKtx, 6 NewerVersionAvailable, 2 UsableSpace, 2 ObsoleteSdkInt, and one each of MonochromeLauncherIcon, GradleDependency, ChromeOsAbiSupport, AndroidGradlePluginVersion), 6 hints |
| Debug APK | `build/Abhify-4.3.1-debug.apk` (SHA-256 `85c2aa76…78ca15`), built earlier today from this source |
| Instrumented tests (`androidTest`, 5 classes) | Built, **never run** (no device) |
| Desktop backend tests (`pytest`) | Not run in this audit (unchanged by the Android work) |

**Toolchain:**
- JDK 17 (`D:\AndroidDev\jdk17`), Gradle 8.14.3 (`GRADLE_USER_HOME=D:\AndroidDev\gradle-home`), AGP 8.13.2, Kotlin 2.3.0, NDK r28c;
- compileSdk/targetSdk 36, minSdk 29;
- **`ANDROID_USER_HOME` must stay unset**, or a different debug key is used.

## 5. Known issues
1. **Not committed:** `android/` and `cloud/`.
2. **Device testing:** UI/visual validation of 4.3.x is **pending**; no device was connected during the last three builds. Device tests of 3.5–4.2 were reported by the owner for some versions.
3. **Real Google Drive:** confirmed working by the owner on 3.4.0. 4.2's account/folder changes are untested on a device.
4. **Icons:** emoji, not vectors (a decision is pending).
5. **Documentation sprawl:** 105 Markdown files in `docs/`. Many are per-version plans, test reports and checklists. The root README and CHANGELOG describe only the desktop app.
6. **Disk use:** `build/` is 9.5 GB (see the cleanup audit).

## 6. Documentation inventory (summary)
| Group | Files | State |
|---|---|---|
| Desktop v1/v2 (installer, signing, hosting, updates, membership, PC/mobile acceptance) | about 30 | Current for the desktop line; README guard tests depend on README/CHANGELOG |
| Android RC.2–RC.4 (architecture, auth, sync, privacy, providers, security, test matrix) | about 22 | Partly superseded by 3.x–4.x |
| Android 3.0–3.4 (plans, feature status, test reports, checklists, user guides) | about 30 | Historical; user guides (Drive, backup, memory, RAG, providers) are current |
| Android 3.5–4.3.1 (roadmap, phase docs, production readiness, fine-tuning research, UI audits) | about 25 | Current |

Details and recommendations are in [PROJECT_CLEANUP_AUDIT.md](PROJECT_CLEANUP_AUDIT.md).

## 7. Risks and recommended next steps
1. **Owner action: put `android/` and `cloud/` under version control** (a commit on a branch, after adding Android entries to `.gitignore`). This needs your approval; nothing is committed automatically.
2. Approve the cleanup candidates (about 6.5 GB of reproducible test and build artifacts).
3. Connect the phone for 4.3.x visual validation.
4. Then follow [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md). Most of the requested chat, model, knowledge and Drive capabilities **already exist**; the remaining work is small and listed there.
