# Testing guide (Android)

## Automated (build machine)
| Suite | Command (in `android/`) | What it covers |
|---|---|---|
| **JVM unit tests** (231 at 4.4.0, 4 opt-in skipped) | `./gradlew testDebugUnitTest` | Parsers and rules (Markdown, retrieval, memory, study, career, Drive queue with a simulated Drive, backup format and restore, schedules, unified search SQL), **database migrations on real SQLite** (sqlite-jdbc), and source checks for privacy and safety rules |
| **Lint** | `./gradlew lintDebug` | 0 errors expected |
| **Build** | `./gradlew assembleDebug assembleDebugAndroidTest` | — |

Tests live in `app/src/test/java/ai/abhify/android/`, one file per release (`V34Test.kt` … `V44Test.kt`), plus the older suites. The shared helpers `v8Db()`, `currentDb()`, `JdbcRows` and `FakeDrive` live in `V34Test.kt` and `V40Test.kt`.

### Opt-in tests (skipped by default)
| Test | Enable with |
|---|---|
| `RealProviderTest` | `ABHIFY_REAL_PROVIDERS=1` and `ABHIFY_<PROVIDER>_KEY` |
| `CloudIntegrationTest` | A local cloud server (see the test) |
| `LiveFeedsTest` | Network |
| `SemanticEvalTest` | `ABHIFY_EMBED_EVAL=1` and Ollama with an embedding model (dev PC) |

## On a phone (not automated here)
1. `adb install -r` the APK (keeps data).
2. Optionally, the instrumented tests: `adb install -r Abhify-4.4.0-debug-androidTest.apk`, then:
   ```
   adb shell am instrument -w -r -e class ai.abhify.android.DbMigrationTest,ai.abhify.android.PdfTextTest,ai.abhify.android.OcrBenchmarkTest,ai.abhify.android.ResumeExportTest ai.abhify.android.test/androidx.test.runner.AndroidJUnitRunner
   ```
   None of them touches the user's data (they use scratch databases and generated files).
3. Manual checks: [ROADMAP_ACCEPTANCE_CHECKLIST.md](ROADMAP_ACCEPTANCE_CHECKLIST.md), and the 4.4.0 list in [MASTER_PROJECT_ENHANCEMENT_REPORT.md](MASTER_PROJECT_ENHANCEMENT_REPORT.md).

## Rules
- Don't delete or weaken a test to make a build pass. Fix the cause, or document why a test's expectation changed (the earlier cases are listed in the release reports).
- Never claim a phone or real-Drive check unless it was done on a phone or real account.
