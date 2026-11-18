# Abhify.AI Android 3.4.0: test report

**Date:** 2026-11-05

**Build machine:** Windows 11, JDK 17, Gradle 8.14.3 (offline), AGP 8.13.2, Kotlin 2.3.0.

**Physical phone:** none has been tested yet. Nothing in this report comes from a phone.

## Automated results (build machine)

| Step | Result |
|---|---|
| `testDebugUnitTest` (JVM) | **169 tests, 0 failed, 3 skipped** (skipped: the live tests `CloudIntegrationTest`, `LiveFeedsTest` and `RealProviderTest`, which need network credentials) |
| `assembleDebug` | OK. 70,642,207 bytes |
| `assembleDebugAndroidTest` | OK. 870,707 bytes |
| `lintDebug` | **0 errors**, 75 warnings (mostly `UseKtx` style suggestions, plus newer-dependency notices; none new in kind), 6 hints |
| Version | `ai.abhify.android` 3.4.0 (3040099), minSdk/targetSdk unchanged |
| Signing | Debug key unchanged: SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f` |
| Permissions | Identical to 3.3.0 (no diff) |
| Secret scan (APK contents and `android/app/src`) | No API keys, OAuth secrets, Google tokens or private keys found |
| Native libraries | llama.cpp/ggml and ML Kit OCR present for arm64-v8a and x86_64, as in 3.3 |

## New v3.4 JVM tests (`V34Test.kt`)

| Test class | Tests | Covers |
|---|---|---|
| `PersonalMemoryTest` | 4 | Relevant-only retrieval (unrelated questions get no memories; expired and unconfirmed memories are never used; limits on count and characters); duplicates and merge order; sensitive-data detection (email, phone, Luhn card, 12-digit ID, PAN, password); extraction parsing and validation (category normalisation, duplicate and sensitive flags, non-JSON gives nothing) |
| `V34DatabaseTest` | 2 | v8 → v9 migration on a populated database (all data kept, correct defaults, new tables empty, no DROP/DELETE/RENAME/VIRTUAL in the migration, upgrade gated by version); FTS4 index created idempotently, follows insert/update triggers, prefix queries, query sanitising |
| `BackupTest` | 2 | Format, checksum tamper detection, AES-GCM encryption, wrong or short passphrase, newer-version refusal, settings whitelist (no keys); full export → additive restore onto a database that already holds some of the data (ID remapping across 6 relations, skipping, idempotence, damaged backup refused with no change) |
| `DriveClientTest` | 3 | Against a simulated Drive REST v3: lazy folders, reuse, re-creation after deletion; resumable upload in 1 MB parts with progress; identical file is not re-uploaded; changed file gives Conflict (nothing overwritten); keep-both naming; explicit replace; resume after a dropped connection; download; trash; 401 → re-connect message; offline; rate limit; scope is `drive.file` only |
| `V34SafetyTest` | 2 | Source checks: no automatic cloud fallback ("Ask online instead" only after consent); memories are filtered for relevance; new installs default to the phone model while existing installs keep Auto; no secrets or logging in the Drive, backup or memory code; backup tables exclude sync, account and device tables; Drive token never written to preferences |

One 3.3 test (`V33SafetyTest.schemaV8MigratesAPopulatedV7Database`) pinned `VERSION = 8`. It now checks `VERSION >= 8` and that the v8 step is still version-gated.

## Phone tests (androidTest, not yet run)
`DbMigrationTest` now also checks, on a real Android SQLite:
- v2 → v9 in one open
- memories confirmed with normal importance
- chats active (not archived)
- new tables empty
- search finds both chats

Run `RUN-TESTS.cmd` from the kit to execute `DbMigrationTest`, `PdfTextTest`, `OcrBenchmarkTest` and `ResumeExportTest`. None of these tests touches user data.

## Not tested
- **Real Google Drive sign-in and upload.** Needs the OAuth client in [GOOGLE_DRIVE_SETUP.md](../../GOOGLE_DRIVE_SETUP.md). Status: **pending**.
- **The 3.3 → 3.4 upgrade on the user's phone, and every UI flow.** See the acceptance checklist: all items are **NOT RUN**.
- **Speed and quality of memory suggestions and document answers on the phone's Qwen model.**
