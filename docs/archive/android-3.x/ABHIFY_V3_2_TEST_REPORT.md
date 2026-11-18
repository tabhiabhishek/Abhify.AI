# Abhify.AI 3.2.0: test and build report (2026-11-05)

**Status: BUILD VERIFIED — DEVICE TESTING PENDING.** No phone was connected and no emulator is possible on this PC (Windows Hypervisor Platform disabled).

## Build

| Item | Value (from actual execution) |
|---|---|
| Command | `gradlew --no-daemon --offline testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` (existing wrapper 8.14.3, JDK 17) → **BUILD SUCCESSFUL** |
| APK | `D:\Abhify.AI\build\Abhify-3.2.0-debug.apk`, **68,976,516 bytes** |
| SHA-256 (Get-FileHash) | `C357DE0C37E662FD05B3DA1B4BD730F29870EE60C41F07B8C2CDEE7BAD5CE9E8` |
| Test APK | `Abhify-3.2.0-androidTest.apk`, SHA-256 `D6C90854141C5871AFE1628FD6199F2618C7E087FC196E83AC30D128641859C5` |
| aapt2 badging | `package: name='ai.abhify.android' versionCode='3020099' versionName='3.2.0'` (parsed) |
| Signing | apksigner "Verifies" (v2). Certificate SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`, **identical to 3.1.0, 3.0.0 and RC.4**, so `install -r` upgrades in place. The debug key is unchanged; no release key was created. |
| Permissions | Identical to 3.1.0 |
| Key scan | APK (702 files) and project sources: no matches for Gemini, Groq, OpenRouter or Anthropic key patterns |
| Lint | **0 errors** |
| Kotlin warnings | 4 (3 × deprecated `LocalClipboardManager`, 1 × unnecessary `!!`), as in 3.1.0 |

## Automated tests

| Suite | Result |
|---|---|
| Baseline before changes (re-run 2026-11-05) | 128 run, 0 failed, 3 skipped |
| **After v3.2: JVM unit tests** | **141 run, 0 failed, 3 skipped.** Skipped are the opt-in tests: real providers (no keys), live cloud, live news feeds. |
| New in v3.2 (13 tests) | `CareerTest` (3): tracker validation and dates, search/filter/due, interview question parsing and resume scoping. `StudyKitTest` (3): quiz scoring, locked answers, navigation, review of wrong answers, session save/resume encoding, prompts. `VaultAndShareTest` (3): tags, Q&A restricted to selected items, redaction (contact hidden by default, keys always removed). `VoiceReaderTest` (1): TTS pause/resume position. `V32StructureTest` (3): five main areas plus Settings reachable, new code never reads keys and uses the consent runner, v7 migration on a populated v6 database (idempotent, data kept). |
| Cloud (`cloud/`, unchanged) | 42 passed (unchanged since 3.1; not modified) |
| Instrumented (built, **NOT RUN**) | `DbMigrationTest` (v2 → v7), `PdfTextTest`, `OcrBenchmarkTest` |
| UI, voice, OCR and memory on a phone | **NOT RUN** |
| Real AI providers | **NOT RUN** (no keys supplied) |

## Coverage map (what each requirement is verified by)

| Area | Verified by | Not verified |
|---|---|---|
| Onboarding and upgrade | `SetupTest` (fresh vs upgrade flag, steps, readiness) | device run |
| API key security | `ProviderStoreTest`, `NoKeysInSourceTest`, `V32StructureTest` | |
| Career | `CareerTest`, `ResumeTest` (3.1) | UI on a device |
| Study | `StudyKitTest`, existing parser tests | AI output quality |
| Voice | `VoiceReaderTest` (pause/resume logic) | microphone and TTS on a device |
| Documents and OCR | `ExtractionPipelineTest`, `StudioTest`, `DocumentsTest` | `OcrBenchmarkTest` on a device |
| Knowledge Vault | `VaultAndShareTest`, v7 migration | UI on a device |
| Memory ordering | `MemoryTest` (OCR unloads Qwen; Qwen blocked during OCR) | measured RAM, latency |
| Regression | full suite (chat reliability, providers, routing, DB v4–v7, documents, resume, setup) | |

## Benchmarks (8 GB phone)

OCR latency, model load time, peak memory, document processing time and local answer latency are **PENDING**: no device. The OCR benchmark runs from the kit. Model speed is measured with AI models → Benchmark on the phone.

## Known issues

- Android TTS has no true pause: Resume restarts the current sentence group (≤ 400 characters).
- Skills are matched exactly against a fixed list of about 150; there is no synonym matching.
- Small local models may produce unusable quiz or card formats. These are rejected with a retry message; nothing is saved.
