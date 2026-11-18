# Abhify for Android (native app) — 2.0.0-rc.2

A standalone, **local-first** Android app that runs a small AI model **on the phone itself**. An optional online account adds online AI and opt-in cloud sync ([architecture](ANDROID_ARCHITECTURE.md), [accounts](ANDROID_AUTHENTICATION.md), [sync](ANDROID_SYNC.md), [privacy](ANDROID_PRIVACY.md), [deployment](ANDROID_DEPLOYMENT.md)). No PC, no companion server, no Tailscale, no remote AI. After a one-time model download, chat and memories work in airplane mode.

It is a separate project in `android/`. The Windows app, the web app (including its WebLLM mode) and all their data are unchanged.

> **Status:** development build. Not signed with a release key, not published. Real-phone results are recorded in [ANDROID_TEST_MATRIX.md](ANDROID_TEST_MATRIX.md); anything not listed there as run on a physical phone is **NOT TESTED** on one.

## What's in the app (0.1.0-dev.4)
| Tab | What it does | Internet? |
|---|---|---|
| **Chat** | Private AI tutor on the phone. Answers stream with formatting (bold, lists, code); copy or read aloud. Mic input. "remember that …" saves a memory. Chat history. | **No** |
| **Interview** | 30 built-in questions in 8 categories with model answers (tap to reveal). **✨ New question from AI**: the on-phone model writes one; it's validated before it's shown. | **No** |
| **Quiz** | Rounds of 5 multiple-choice questions, instant feedback with explanations, score and daily streak saved on the phone. **✨ Add a question written by the AI**: validated (4 different options, one correct). | **No** |
| **News** | Latest AI/ML stories from 8 public feeds (OpenAI, Google AI, DeepMind, Hugging Face, MIT News, The Verge, TechCrunch, MarkTechPost). Fetched only when you open or refresh the tab; the last result is kept and labelled with its age. **Ask Abhify** explains a story (offline, on the phone). | **Yes**, only when opened |
| Voice | The **on-device** speech recognizer when the phone has one (Android 12+), otherwise Android's recognizer, which may use Google's online service (shown in Settings). Answers are read aloud with the phone's text-to-speech. | Depends on the phone |

**Look and feel:**
- the Abhify logo and colours (blue `#0A67EB`, green `#8BCF44`, navy `#030C1D`);
- dark by default, with a light or system theme in Settings;
- the status line says *"AI ready · runs on this phone (CPU) · no internet needed"*.

**Built-in learning bank:** `assets/learning_bank.json`, 30 interview questions and 30 MCQs. It includes all of the web app's offline samples (checked by a unit test).

## Architecture

```
Jetpack Compose UI (MainActivity)            setup · chat · settings
        │
AppViewModel ── ChatDb (SQLite, app-private)  conversations, messages, memories
        │    └─ PromptBuilder                 system prompt + memories + last 8 messages (same as the web app)
        │
SetupController ── Downloader                 HTTPS, resumable (Range), storage check, size + SHA-256
   (in DownloadService, foreground)  └─ ModelStore     staging → verified → self-test → active; old model kept until then
        │
LlmEngine (one worker thread) ── JNI ── abhify_llm.cpp ── llama.cpp v0.7.0 (C API only)
                                                          CPU: all ARM feature variants, picked at run time
                                                          Vulkan: optional, off by default
DeviceProbe (RAM, storage, ABI, CPU features, Vulkan, thermal) → ModelSelector (pure, unit-tested)
AppUpdater + UpdatePolicy + SignedManifest/Ed25519      signed updates (not configured in this build)
```

## Runtime choice: llama.cpp

Evaluated, Oct 2026:

| Option | Verdict |
|---|---|
| **llama.cpp** (C/C++, GGUF) | **Chosen.** Mature, MIT licensed, maintained Android support (upstream ships an Android binding built with Arm). Ships CPU kernels for every ARM feature level (ARMv8.0 → v9.2 with SVE2/SME) and loads the best one **at run time**, so one APK fits old and new phones. Optional Vulkan GPU backend. Pinned to release **v0.7.0** (commit `620d2225`, archive SHA-256 `1d4a987d…`). |
| MLC-LLM (the engine behind WebLLM) | Would reuse the web models, but needs a heavy TVM compile per model and GPU drivers that vary by phone. |
| MediaPipe LLM Inference | Deprecated by Google. |
| ONNX Runtime GenAI | Fewer ready small models; less Android-specific CPU tuning. |

**CPU is the baseline** on every phone. **GPU (Vulkan) is never assumed**:
- it is built only with `-Pabhify.vulkan=true`,
- it is used only if llama.cpp finds a usable Vulkan device on that phone,
- and only after **Settings → Test GPU** produces the same answer as the CPU, faster.

If GPU loading ever fails, the app falls back to the CPU automatically.

The web app's WebLLM models (MLC format) **can't** be reused here. The conversation logic is shared instead: prompt building, the "remember that…" command and the memory handling are ported 1:1 from `frontend/ondevice.js`.

## Minimum Android version: Android 10 (API 29)

| Requirement | Needs |
|---|---|
| llama.cpp CPU backend with run-time variant loading | API 26+ (NDK) |
| `PowerManager.currentThermalStatus` (thermal warnings) | API 29 |
| Scoped storage only, foreground-service types, `dataExtractionRules` | API 29+ |
| Jetpack Compose | API 21+ |

API 29 is the lowest version where every feature works the same way; below it, thermal checks and storage behaviour differ. **Android 9 and older**: the app shows *"Abhify needs Android 10 or newer"* and downloads nothing.

**Target SDK 36** (Android 16). **ABI:** release builds are `arm64-v8a` only. Debug builds add `x86_64` so emulators can run UI tests. **32-bit-only phones** can't install a release APK; if one runs a debug build, it shows *"…the on-device AI needs a 64-bit (arm64) processor"*.

## Device-adaptive model selection

At run time `DeviceProbe` reads:
- Android version and ABIs,
- total RAM and **currently free** RAM, the system's low-memory flag, and whether it's a low-RAM (Android Go) device,
- free app storage,
- CPU core count and features (`/proc/cpuinfo`: dot-product, i8mm, FP16, SVE2, SME),
- the Vulkan feature level, and the thermal status.

`ModelSelector` (pure Kotlin, unit-tested) then:
- keeps **app support** (can Abhify run here?) separate from **model support** (can this model run here?);
- offers only models whose RAM, storage, ABI and API requirements are met. The largest compatible one is recommended; the user may pick any other compatible model;
- refuses a download that wouldn't leave 300 MB of free storage;
- checks free memory **again right before loading**. If too little is free, it shows *"Close other apps and try again"* instead of letting Android kill the app;
- turns memory pressure and heat into **warnings, not crashes**;
- uses **4 threads on 6+ core phones** (the big cores; using every core including the little ones is usually slower), 2 on 4-core and 1 on smaller.

## Model catalog (`app/src/main/assets/model_catalog.json`)

Configurable data, validated on load: HTTPS only, 64-hex SHA-256, GGUF format, the default context must be one of the offered context sizes, and a model can't be marked "GPU validated" unless GPU is marked supported.

| | Qwen2.5 1.5B Instruct | Qwen2.5 0.5B Instruct |
|---|---|---|
| File | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` |
| Quantization | Q4_K_M (4-bit) | Q4_K_M (4-bit) |
| Size | 1,117,320,736 bytes (1066 MB) | 491,400,032 bytes (469 MB) |
| SHA-256 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| Source (pinned revision) | `huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF` @ `91cad511` | `huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF` @ `9217f5db` |
| License | Apache-2.0 | Apache-2.0 |
| Offered when total RAM ≥ | 5.5 GB (6 GB phones) | 2.7 GB (3 GB phones) |
| Runtime memory (estimate, pending measurement) | ~1450 MB | ~750 MB |
| Context options | 1024 / 2048 (default) / 4096 | 1024 / 2048 (default) / 4096 |
| GPU | supported by the runtime, **not validated** | supported by the runtime, **not validated** |

**Licensing and distribution:**
- The models are **downloaded from their publisher, not bundled**. The APK contains no model weights.
- Apache-2.0 allows redistribution with the license and notices. If a future build bundles a model, include the LICENSE file and notices.
- llama.cpp is MIT licensed.
- Other model families (for example Llama, which has its own community license) need a license review before being added to the catalog.

Both models were verified on the PC: their SHA-256 hashes match. A file fetched from the wrong revision was rejected by the hash check during development, the same protection the app has.

## First-run setup
1. **Device check:** phone, Android version, CPU features, RAM (and how much is free now), storage, and GPU support.
2. **Choose a model:** the recommended one is preselected. Incompatible ones are shown with the reason (✗) and can't be selected.
3. **Consent:**
   - The dialog shows the exact size in MB and bytes, the memory needed, the source, the pinned version, the license and the SHA-256.
   - It says "Wi-Fi recommended".
   - Nothing downloads before **Download** is tapped.
4. **Download:**
   - Runs in a foreground service with a notification, so it continues when you leave the app.
   - **Pause / Resume / Cancel.**
   - A network drop leaves it resumable (HTTP Range).
   - If Android kills the app, the next start offers *Resume*.
5. **Verify:**
   - The exact size and the SHA-256 must match.
   - A mismatch deletes the file and says so. It is never used.
6. **Self-test:**
   - The model must give a real answer on this phone ("Reply with exactly one word: OK").
   - **Setup is complete only after that.**
   - If the test fails, the new file is removed and any previous model stays active and untouched.
7. **Record:** `state.json` stores the model id, pinned version, SHA-256, size, runtime (`llama.cpp v0.7.0 (620d2225)`), context size and the self-test result.

## Storage and data
- **Models:** `noBackupFilesDir/models/` (app-private, never in cloud backups).
- **Chats and memories:** an app-private SQLite database. **Each installation is independent**; nothing syncs anywhere.
- `allowBackup="false"` and `dataExtractionRules` exclude everything from cloud backup and device-to-device transfer.
- **Settings → Remove model** frees the space and keeps chats. **Delete all chats and memories** wipes the conversation data. Uninstalling the app removes everything.

## Offline guarantee
- **Work with no network at all:** launching the app, a new chat, generating an answer, "remember that…", reading memories, chat history, and closing and reopening the app.
- **Use the network only when you ask:** the model download, and the update check (not configured in this build).
- **Don't exist in this app:** news, web search and document search.
- **HTTPS only**, with system certificate authorities (`network_security_config.xml`).

## Updates (Phase 5 design; implemented, not configured)
- **App updates** follow Android's rules:
  1. The user starts **Check for updates**.
  2. The app downloads a signed manifest from the configured HTTPS URL. It uses the same `abhify-update-1` Ed25519 envelope as the Windows app (verified in Kotlin, cross-tested against envelopes signed by the Windows tooling).
  3. The manifest must name this package, a higher `versionCode`, and the SHA-256 of the **same signing certificate** as the installed app; Android itself refuses anything else.
  4. The APK is downloaded to staging, checked against the signed size and SHA-256, and its own package name, version and signing certificate are read and compared.
  5. Android's PackageInstaller then shows **its own confirmation**. The user must also have allowed "install unknown apps" for Abhify.
  6. App data survives because the package and key are the same.
  - **Nothing installs silently.**
- **Model updates:**
  - A new model version is downloaded and verified like a first install, then must pass the self-test.
  - Only then does it replace the old file. Until then the working model stays active.
  - A failed update leaves the old model untouched (unit-tested).
- **Not configured:** there is no update URL, no trusted key and no production signing key in this build. Each needs the owner's decision: `-Pabhify.updateUrl=…` and `-Pabhify.updateKeys=keyId=base64` at build time. The release signing key must be created and kept by the owner, and losing it means users can't update.

## Build

Requirements (all installed on the development PC under `D:\AndroidDev`):
- JDK 17 (Temurin 17.0.20.1),
- Android SDK (platform 36, build-tools 36.1.0),
- NDK r28c (28.2.13676358),
- CMake 3.31.6,
- Gradle 8.14.3 (wrapper with a pinned SHA-256).

```powershell
$env:JAVA_HOME = "D:\AndroidDev\jdk17"; $env:GRADLE_USER_HOME = "D:\AndroidDev\gradle-home"
cd android
# local.properties (not committed):  sdk.dir=D\:/AndroidDev/sdk
.\gradlew testDebugUnitTest                         # JVM unit tests
.\gradlew assembleDebug assembleDebugAndroidTest    # app + on-device test APKs
# offline / faster native build from a local llama.cpp checkout of the same commit:
.\gradlew assembleDebug -Pabhify.llamaDir=D:/AndroidDev/src/llama.cpp
```

Install on a phone (USB debugging on): `D:\AndroidDev\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`

**Release APK:** `.\gradlew assembleRelease` produces an **unsigned** APK. Signing needs the owner's release key, which is deliberately not created here.

## Tests
- **JVM unit tests** (`app/src/test`, run on the PC):
  - catalog validation and pinning;
  - model selection for every device class: 8/6/4/3/2 GB, Android Go, Android 9, 32-bit, low storage, memory pressure, heat;
  - prompts and memories;
  - downloads: full, interrupted + Range resume, pause, corrupted, wrong size, low storage, server ignoring Range, 503/404, HTTP refused, no network;
  - model lifecycle: activation only after the self-test, a failed new model keeps the old one, a failed update of the same model keeps the old file, corruption detection, removal fallback, damaged state file;
  - Ed25519 RFC 8032 vectors, envelopes signed by the Windows tooling (valid, tampered, wrong key, unknown key, Unicode);
  - update policy and archive checks.
- **On-device tests** (`app/src/androidTest`, run by `android/tools/phone-e2e.ps1`):
  - device facts;
  - real setup (download + verify + self-test), pause/resume of a real download;
  - **airplane-mode** chat and memory in a fresh process, then persistence after another restart;
  - the real UI offline (launch, send, answer, screenshot);
  - sustained load with battery temperature and thermal status;
  - model removal.
- **Runtime benchmark without the app:** `android/tools/phone-bench.ps1` runs llama.cpp's own `llama-bench` and completion tools on the phone. It measures CPU tokens/s by thread count, first-token latency and peak memory, sustained throttling, and the optional Vulkan check.

## Limitations
- **Small models:** answers are useful for explanations and Q&A but less reliable than the PC app's larger models.
- **Not in this app:** document search, web news and voice.
- **Speed and heat** depend on the phone. Long sessions warm the phone and slow it down; the benchmark records this.
- **GPU:** off until validated per phone. Many phone GPU drivers are slower than the CPU for this workload, or produce wrong results.
- **Process death:** if Android kills the app during generation, that partial answer is lost. The question and everything before it are saved.
- **Unsigned:** this is a development build. Distribution needs a release key and a hosting decision; neither is done.
