# Abhify.AI 3.0.0: build and test report (2026-11-04)

## Build

| Item | Value |
|---|---|
| APK | `D:\Abhify.AI\build\Abhify-3.0.0-debug.apk`, 52,634,646 bytes |
| SHA-256 (Get-FileHash) | `E89B6CD109C034DA1B3010FD03B967A2A2E4B6AE04949A117259B6BD632EAACF` |
| Test APK | `Abhify-3.0.0-androidTest.apk`, SHA-256 `FCCDD6D7F6E357B3731A205196D2332F5F4CFB3161BDADD590B13EEC279BCB5A` |
| Application ID / version | `ai.abhify.android` · 3.0.0 · versionCode 3000099 (RC.4 was 2000004) |
| Signing | Debug key `C:\Users\Administrator\.android\debug.keystore`. Certificate SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`, **identical to RC.3/RC.4**, so an in-place upgrade works. No release key exists. |
| Toolchain | AGP 8.13.2, Kotlin 2.3.0, Gradle 8.14.3, JDK 17, NDK r28c; minSdk 29, target 36 |
| Native | llama.cpp v0.7.0 unchanged (same `.so` set). The model files and catalog are unchanged. |
| New dependency | `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0). BouncyCastle 1.72 is excluded (known CVEs; only needed for certificate-encrypted PDFs). |
| Test-only dependency | `org.xerial:sqlite-jdbc:3.46.1.3`, to run the v5 migration SQL on a real SQLite |
| Lint | 0 errors (warnings: dependency updates, UseKtx and the like) |
| Kotlin warnings | 3 × deprecated `LocalClipboardManager`, 1 × unnecessary `!!` |
| Key scan | APK (304 files) and project sources: **no matches** for Gemini, Groq, OpenRouter or Anthropic key patterns |

## Tests

| Suite | Result |
|---|---|
| Android JVM unit tests | **107 run, 0 failed, 3 skipped** (opt-in: CloudIntegrationTest, LiveFeedsTest, RealProviderTest) |
| New in v3 | `ResponseReliabilityTest` (10): finish reasons in each provider shape; length vs complete vs interrupted vs cancelled; per-provider limit field; 5,000-chunk stream through the router; Continue routing and overlap removal; status rules; read-aloud parts; preview; v5 migration on a populated v4 database with a 200,000-character answer. `StudioTest` (6): BM25 retrieval and citations, PDF page citations, budget, flashcard, list, action and MCQ parsers. |
| Cloud (FastAPI) | 42 passed (code unchanged) |
| Instrumented (phone) | `DbMigrationTest` (v2 → v5) and `PdfTextTest` (generated 2-page PDF, scanned-like and invalid PDFs) are **built, not run**: no device or emulator |
| Real providers | **Not run.** No keys. |
| UI / font scale / tabs on a device | **Not run** |

## Bug found and fixed by the new tests

`HybridAi` and `LlmEngine` used `trySend(...).isSuccess` as the "keep generating" signal. When the 64-event channel buffer was full (a fast provider), `trySend` failed, and the provider client treated that as the user pressing Stop. The answer ended early and was **stored as complete**. Now `trySendBlocking` waits for buffer space and fails only when the collector is really gone.
