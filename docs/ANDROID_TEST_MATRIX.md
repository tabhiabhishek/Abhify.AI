# Abhify for Android: compatibility and test matrix

Rules:
- A device class is **supported** only after the app has been installed and tested on a **physical phone** of that class.
- Emulator runs prove installation, the UI and the code paths, **not** speed, memory limits, heat or GPU behaviour.
- Every result names the phone (or "emulator"), the date, and where the evidence is.

## Build and PC-side results (2026-10-26, development PC, no phone involved)
- `gradlew testDebugUnitTest`: **31 passed, 0 failed** (ModelLifecycleTest 15, ModelSelectionTest 11, UpdateSignatureTest 5).
- `gradlew assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**.
  - `app-debug.apk`: 68,387,100 bytes, arm64-v8a + x86_64.
  - It contains 7 ARM CPU variants (ARMv8.0 → ARMv9.2 SME) and 14 x86_64 variants, and **no model weights**.
- llama.cpp `llama-bench` / `llama-completion` cross-built for arm64: a CPU build, plus a separate Vulkan build (33 MB backend) for the optional GPU check.

## Physical phone: Redmi K50i (Xiaomi 22041216I), 2026-10-26
- Read over adb by the test script: Android 14 (API 34), MediaTek MT6895Z (Dimensity 8100), arm64. 8 GB RAM per the owner.
- **0.1.0-dev.1:** the model downloaded and the SHA-256 check passed, but the **self-test FAILED**.
  - Cause: native libraries were not extracted on install, so llama.cpp found no CPU backend.
  - Fixed in dev.2 (`extractNativeLibs=true`), matching upstream's Android example.
- **0.1.0-dev.2:** installed with `adb install` (Success). Owner reports that Qwen2.5 1.5B **downloaded and passed the real test answer**, and that the app opens and chats.
  - Owner-reported only. No benchmark or log has been received yet.
- Harness fixes found on this phone:
  - Xiaomi refuses `adb install -g` without "USB debugging (Security settings)"; removed.
  - adb's error text was hidden by PowerShell 5.1; now captured.
- **0.1.0-dev.3:** wording only. The status says "runs on this phone · no internet needed" instead of the misleading "offline".

**0.1.0-dev.4** (2026-10-26):
- Adds the Abhify design, the Interview, Quiz and News tabs, voice, and learning progress (database v2, additive migration).
- PC results:
  - **41 unit tests passed, 0 failed**;
  - **live feed test 8/8 feeds parsed** (run from the PC with `ABHIFY_LIVE=1`; this found and fixed a gzip-only server and a nested-title bug);
  - APK 45,165,981 bytes.
- On the phone: **NOT TESTED** yet (the AI-written questions, voice and news on a real phone).

## 2.0.0-rc.3: hosted providers (Gemini, Groq, OpenRouter) with per-device keys (2026-11-03, development PC)
| Suite | Result |
|---|---|
| Android JVM unit tests | **77 run, 0 failed** (2 opt-in skipped). New: provider rules 7, key store 4, provider client 4, hybrid flow 2, no-keys scans 2, routing rewritten (6) |
| Cloud service | **42 passed** (6 new shared-provider tests) |
| Provider results | **all mocked** (formats from the official docs). Real Gemini/Groq/OpenRouter calls: **NOT TESTED** (no keys) |
| Built APK credential scan | 0 key-shaped strings (191 files) |
| Lint | 0 errors |
| Phone acceptance (A1–A19) | **NOT TESTED**. See [RC3_ACCEPTANCE_CHECKLIST.md](archive/android-rc/RC3_ACCEPTANCE_CHECKLIST.md) |

## 2.0.0-rc.2: local-first + optional account/sync + hybrid AI (2026-11-02, development PC)
| Suite | Result |
|---|---|
| Android JVM unit tests | **57 passed, 0 failed** (41 baseline + 16 new: hybrid routing 7, cloud client 3, sync engine 6). 2 opt-in network tests skipped in normal runs |
| `CloudIntegrationTest` (opt-in, the real Kotlin client against the real `cloud/` server on 127.0.0.1, plus local Ollama `qwen2.5:latest`) | **PASS**: register, second device, refresh rotation + reuse revocation, two-device sync with a conflict preserved, cross-user isolation, remote sign-out, a real streamed online answer labelled with its model |
| Cloud service (`cloud/tests`) | **36 passed, 0 failed** |
| Desktop/web backend (unchanged) | **319 passed, 0 failed** (baseline run) |
| `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug` | BUILD SUCCESSFUL, **0 lint errors** |
| `DbMigrationTest` (v2 → v3 on real Android SQLite) | written; **NOT RUN**: needs a phone or emulator |
| On the phone (onboarding, profile, link, online/offline, sync, airplane mode) | **NOT TESTED** |
| Online features on a phone | **pending deployment**: no server exists yet |

## Device classes

| Class | Example | RAM | Expected model | Status |
|---|---|---|---|---|
| Minimum OS | any phone on Android 10 (API 29) | any | depends on RAM | NOT TESTED |
| Below minimum | Android 9 | any | none: "needs Android 10" | unit-tested only |
| Old, low memory | 3 GB, ARMv8.0/8.2, Android 10–11 | 3 GB | Qwen2.5 0.5B | NOT TESTED |
| Android Go / 2 GB | low-RAM device | ≤ 2 GB | none (clear message) | unit-tested only |
| Mid-range | 4–6 GB, Android 12–13 | 4–6 GB | 0.5B (4 GB) / 1.5B (6 GB) | NOT TESTED |
| Modern, 8 GB+ | owner's Redmi K50i (Android 14, 8 GB) | 8 GB | Qwen2.5 1.5B | NOT TESTED (phone not connected yet) |
| 32-bit only | armeabi-v7a phones | any | none: "needs a 64-bit processor" | unit-tested only |
| x86_64 | emulator | any | depends on RAM | NOT TESTED |

## Scenarios

| Scenario | How | Physical phone | Emulator |
|---|---|---|---|
| Install, launcher icon, first-run screen | `adb install`, launch | NOT TESTED | NOT TESTED |
| Consent shows model, size, source, license before download | setup screen | NOT TESTED | NOT TESTED |
| Real download, SHA-256 verify, self-test, activation | `OnDeviceE2ETest#setup` | NOT TESTED | NOT TESTED |
| Interrupted download resumes (Range) | `#interruptedDownload`; unit test | NOT TESTED | unit: PASS (PC) |
| Corrupted download rejected and deleted | unit test | — | unit: PASS (PC) |
| Low storage refused before downloading | unit test | NOT TESTED on device | unit: PASS (PC) |
| Memory pressure: warning + "close other apps" instead of a crash | unit test + manual | NOT TESTED | unit: PASS (PC) |
| CPU-only inference | `#offlineChatAndMemory` | NOT TESTED | NOT TESTED |
| Accelerated (Vulkan) inference, validated against the CPU | `phone-bench.ps1` + Settings → Test GPU | NOT TESTED | n/a |
| **Airplane mode:** launch, new chat, answer, memory | `#offlineChatAndMemory` + UI step | NOT TESTED | NOT TESTED |
| Close and reopen offline: history + memory persist | `#persistenceAfterRestart` | NOT TESTED | NOT TESTED |
| Thermal throttling (sustained load) | `#sustained`, `phone-bench.ps1` | NOT TESTED | n/a |
| Backgrounding: model freed under memory pressure, reloads | manual (`onTrimMemory`) | NOT TESTED | NOT TESTED |
| OS kills the process mid-download → resume offered | manual (`adb shell am kill`) | NOT TESTED | NOT TESTED |
| Model removal frees storage, chats kept | `#removeModel` | NOT TESTED | NOT TESTED |
| Reinstall of the model after removal | `#setup` again | NOT TESTED | NOT TESTED |
| Failed model update keeps the working model | unit test | — | unit: PASS (PC) |
| Signed update: tampered/wrong key/other app/other signer refused | unit tests | — | unit: PASS (PC) |
| App update install via Android's confirmation | needs a release key + host (owner) | NOT TESTED | NOT TESTED |

## Benchmarks (physical phones only)

| Phone | SoC | Model | Backend | Threads | First token | Tokens/s | Peak memory | Sustained (8 runs) | Battery temp Δ |
|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — |

No benchmark has been run on a phone yet.
