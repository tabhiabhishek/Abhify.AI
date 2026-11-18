# Abhify.AI 3.3.0: test report (Career Copilot 2.0)

| Item | Value |
|---|---|
| Version | 3.3.0, versionCode **3030099** (installed 3.2.0 = 3020099). App ID `ai.abhify.android` (unchanged). |
| Build date | 2026-11-05 |
| Status | **BUILD VERIFIED — DEVICE TESTING PENDING** |

## 1. Automated tests (JVM, executed)

| Run | Tests | Passed | Failed | Skipped |
|---|---|---|---|---|
| Baseline (repository before changes, re-run 2026-11-05) | 141 | 138 | 0 | 3 |
| **Final** | **156** | **153** | **0** | **3** |

**Skipped** are the 3 opt-in tests that need external resources: `RealProviderTest` (provider keys), `CloudIntegrationTest` (a live server) and `LiveFeedsTest` (internet feeds). They were **not run**.

### New tests (15, in `V33Test.kt`)

| Test class | Covers |
|---|---|
| `SkillTaxonomyTest` (4) | Normalisation and aliases (ML, AI, NLP, CV, Fast API, nodejs, sklearn); unrelated skills never equal (Python/Java, TensorFlow/PyTorch, ML/DL, FastAPI/Flask, SQL/MongoDB); related-only relationships; ambiguous words need context ("my CV", "Spring 2020", "excel at", "the rest", "R&D", "Go to market"); **the spec example** (direct Python, FastAPI, PyTorch; missing SQL and Docker; coverage 60%; OpenCV never covers SQL) |
| `JobAndMatchTest` (4) | Job-description fields (title, company, location, type, years, required vs preferred, responsibilities, education, certifications, JSON round trip, nothing guessed); experience years with overlaps merged; explainable match (evidence wording, "doesn't mean you lack it", rounding to 5, not-assessed dimensions excluded); advice entries complete; learning priorities; quality score (deterministic, 100-point rubric, optional certifications) |
| `ResumeImproveExportTest` (2) | Section rewrite parsing; section replacement keeps the other sections; grounding flags; DOCX written and read back with the app's own DOCX reader (escaping, invalid XML characters removed, no app data) |
| `AiJsonTest` (2) | Fenced and embedded JSON extraction; range, missing-field and empty validation; interview prompt scoping (resume and job description only where intended); requirement labels; all 12 topics |
| `TrackerTest` (1) | Company, title, date-range filters; three sorts; overdue and upcoming follow-ups (closed applications excluded); dashboard statistics |
| `V33SafetyTest` (2) | **v8 migration on a populated v7 database:** job descriptions, applications, interview attempts, resumes and resume versions preserved; new column NULL; version-gated. Career code never touches keys or logs; all AI goes through `aiTask`; FileProvider not exported and limited to the export folder. |

**Regression:** the full earlier suite is unchanged and still passes. It covers chat reliability, routing, providers, documents, OCR pipeline logic, resume, setup, study, voice reader and v5–v7 migrations.

## 2. Lint and warnings

- **Lint: 0 errors.**
- **Kotlin warnings:** 4, the same as 3.2 (3 × deprecated `LocalClipboardManager`, 1 × unnecessary `!!`).

## 3. Database migration

| Item | Result |
|---|---|
| Version | v7 → **v8**: one additive column, `job_descriptions.fields` |
| JVM test | Migration on a populated v7 database passes, data preserved |
| On-device `DbMigrationTest` (v2 → v8) | Built, **not run** |
| Repeat safety | `onUpgrade` applies it once (`oldVersion < 8`). `ALTER TABLE ADD COLUMN` is not repeatable by design, and the version gate prevents a second run. |

## 4. Memory-related checks

- **Code-level test (existing `MemoryTest`):** OCR and Qwen never run together.
- **Career analysis:** matching, quality and export run without the model. All AI work uses `aiTask`, which goes through `HeavyGate`.
- **Measured RAM:** **not measured** (no device).

## 5. Build verification (executed)

| Item | Value |
|---|---|
| APK | `D:\Abhify.AI\build\Abhify-3.3.0-debug.apk`, **69,471,543 bytes** (+0.5 MB versus 3.2.0) |
| SHA-256 | `DA8C140F175E73B37338F61EA5F459944B5AAFE10E3B2130CF6B67360FE057F0` |
| Test APK | `Abhify-3.3.0-androidTest.apk`, SHA-256 `07C65EE5282680C8A91BCB525047452D3111AA86CDB007EF54477CAB4A66704A` |
| aapt2 | `package: name='ai.abhify.android' versionCode='3030099' versionName='3.3.0'` |
| apksigner | Verifies. Certificate SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`, **identical to 3.2.0**, so it installs as an in-place upgrade. |
| Permissions | Identical to 3.2.0 (the FileProvider adds none) |
| Key scans | APK (704 files) and project sources: no matches |
| 3.2.0 APK | Untouched (SHA-256 still `C357DE0C…CE9E8`) |

## 6. Physical device

**None connected** (`adb devices` empty). No emulator is possible on this PC (Windows Hypervisor Platform disabled).

| Phone test | Status |
|---|---|
| Install | NOT RUN |
| UI | NOT RUN |
| Export on a device | NOT RUN |
| `ResumeExportTest`, `DbMigrationTest`, `PdfTextTest`, `OcrBenchmarkTest` | Built, NOT RUN |

## 7. Real providers

**Not tested.** No keys were supplied.

## 8. Known limitations

- Skill matching knows the 133 skills in `assets/skills_taxonomy.json`. Unknown skills are not recognised (extend the file to add more).
- The experience estimate uses date ranges in the experience section. Undated roles count as 0.
- "Responsibility alignment" is word overlap, not semantic understanding.
- AI feedback and suggestions depend on the model. Small local models often fail the JSON validation: such output is shown with a warning and **not saved**.
- DOCX export is plain formatting (bold headings, bullet characters), without Word styles or numbering.
