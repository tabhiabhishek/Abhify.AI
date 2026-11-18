# Project cleanup audit (2026-11-06)

**Status:** audited, then the approved actions were carried out (section 7). Nothing was bulk-deleted (no `git clean`, no `rm -rf` of unverified folders).

**Legend:**
- **KEEP:** required, or useful evidence.
- **UPDATE:** fix the content.
- **MERGE:** combine with another doc.
- **ARCHIVE:** move to `docs/archive/` with a note.
- **DELETE:** remove after approval.

## 1. Must be preserved (never touched by cleanup)
- **Source and build files:** all source code (`android/app/src`, `backend/`, `frontend/`, `cloud/`, `desktop/`, `packaging/`), Gradle files, manifests, resources and assets (`skills_taxonomy.json`, the Inter font, logos), CMake and native code, all tests.
- **User data:** `backend/data/` (ChromaDB and `progress.db`: **desktop user data**). Phone data lives on the phone, not in the repo.
- **Model weights:** `D:\AndroidDev\models\*.gguf` (outside the repo; development copies of the phone models). Ollama's model store (outside the repo).
- **Signing and credentials:** `C:\Users\Administrator\.android\debug.keystore` (outside the repo; the debug key that Drive OAuth and installs over older versions depend on). `.env.example` only has placeholders.
- **Git history and branches.**
- **Release evidence:** `dist/windows/` (published or candidate installers with SHA256SUMS), and the latest APKs.

## 2. Build outputs: `build/` (9.5 GB, git-ignored) and `dist/` (594 MB, git-ignored)
| Path | Size | Purpose | Referenced by | Contains user data? | Recommendation | Risk |
|---|---|---|---|---|---|---|
| `build/sandbox-modeltest`, `-dev3`, `-dev4` | 1.6 GB each | Windows Sandbox model-setup tests of desktop dev.2–dev.4 installers; the `inputs/` copies are the bulk | `docs/FINAL_ACCEPTANCE_DEV3.md`, `packaging/windows/sandbox/run-sandbox-modeltest.ps1` (recreates them) | No | **ARCHIVE** the small results (`result.json`, `log.txt`, `done.txt`) into `build/evidence/`, then **DELETE** each `inputs/` copy | Low: the script recreates the folders, and the results are kept |
| `build/taskb-input` | 1.5 GB | `OllamaSetup.exe` + `sample.pdf` inputs for sandbox tests | Sandbox scripts | No | **DELETE** after approval (re-downloadable) | Low |
| `build/discarded-dev4-build1-24d75f81`, `discarded-dev6-build1-78ec538e` | 116 MB each | Installers explicitly discarded during dev.4/dev.6 reviews | Changelog/notes only | No | **DELETE** | Very low |
| `build/windows` | 631 MB | Intermediate PyInstaller/NSIS build tree of the last desktop build | Recreated by `packaging/windows/build.py` | No | **KEEP** until the next desktop build (it speeds up rebuilds), or DELETE | Low |
| `build/wip-snapshot-dev6-20261019-161857` | small | Safety snapshot taken during dev.6 | — | No | **KEEP** (manual review) | — |
| `build/android-dev3`, `android-dev4` | 43–44 MB | Unpacked old Android kits (2.0 era) | — | No | **DELETE** (superseded by later kits) | Low |
| `build/Abhify-Android-Test*` folders **and** their `.zip` files | 68–86 MB each | Phone test kits RC.2–4.1. Each folder is the unzipped copy of its zip. | Docs mention the zips | No | **DELETE the unzipped folders** where the zip exists (keep every zip) | Very low |
| `build/Abhify-<ver>-debug.apk` + `-androidTest.apk` (2.0-rc.4 … 4.3.1) | 68–71 MB each (about 2.1 GB total) | Every Android build of this project | Release reports (SHA-256 values) | No | **KEEP 3.4.0** (the version your phone ran), **4.1.0** (device-tested by you), **4.2.0**, **4.3.1**. ARCHIVE the rest **outside the repo** (for example `D:\Abhify-archive\apks`), keeping their SHA-256 lists. | Low; you asked earlier never to delete APKs, so they're moved, not deleted |
| `build/*.log`, `*-result.json` (about 40 files) | small | Logs of desktop builds, test suites and sandbox runs | Some cited as evidence in acceptance docs | No | **KEEP** (small; evidence) | — |
| `dist/windows/*` | 594 MB | Desktop installers dev.2–dev.6 + release files | Release docs | No | **KEEP** | — |
| `android/app/build` | 944 MB | Gradle output | — | No | KEEP (regenerated on build; it's the working cache) | — |
| `android/app/.cxx` | large | CMake/NDK intermediates, **including downloaded llama.cpp sources** | Native build | No | **KEEP.** Deleting it forces a re-download of llama.cpp, which breaks offline builds. | Medium if deleted |
| `backend/server.log` | 48 KB | Desktop server log (git-ignored) | — | No | KEEP (harmless) | — |
| `__pycache__/` folders | small | Python bytecode | — | No | DELETE (always safe) | None |

**Space recoverable with the approved recommendations:** about 6.5 GB (sandbox inputs 4.8, taskb 1.5, discarded installers 0.23, unzipped kits about 0.6), plus about 1.6 GB moved out of the repo if old APKs are archived.

## 3. Repository hygiene (no deletions)
| Item | Recommendation |
|---|---|
| `.gitignore` lacks Android entries | **UPDATE:** add `android/.gradle/`, `android/app/build/`, `android/app/.cxx/`, `android/local.properties`, `android/**/build/`, `*.apk`, `*.aab`, `*.jks`, `*.keystore` (`*.keystore` would also keep any future release key out). This is non-destructive. It's a tracked file that is already modified, so the change is shown to you first. |
| `android/` and `cloud/` untracked | **Owner decision:** commit them on a branch (no automatic commit). |
| `frontend/mock-data.js` | KEEP (used by the desktop PWA demo mode; it needs checking before any change) |

## 4. Dependencies (Android)
| Dependency | Used by | Recommendation |
|---|---|---|
| `kxml2` | `testImplementation` only: a real XML pull parser for the news-feed tests (`News.kt` uses Android's built-in XmlPullParser at runtime) | KEEP (test-only; not in the APK) |
| `pdfbox-android` | PDF text extraction | KEEP |
| `mlkit-text` | OCR | KEEP |
| `play-services-auth` | Google Drive sign-in | KEEP |
| `work-runtime-ktx` | Sync, Drive uploads, scheduled backups | KEEP |
| `core-splashscreen` | System splash | KEEP |
| `sqlite-jdbc` | JVM tests only (`testImplementation`) | KEEP |

**No unused dependency was confirmed** by this audit; static search alone isn't enough to remove one. Lint reports 6 newer versions available: update only in a dedicated, tested step.

## 5. Documentation (105 files in `docs/`, plus the root README and CHANGELOG)
| Group | Files | Recommendation |
|---|---|---|
| **Current user and developer guides:** GOOGLE_DRIVE_SETUP, BACKUP_AND_RESTORE, PERSONAL_MEMORY, RAG_DOCUMENT_LIBRARY, AI_PROVIDER_CONFIGURATION, ABHIFY_NEW_PHONE_SETUP, ABHIFY_CONFIGURATION_SECURITY, ON_DEVICE_AI | 8 | **KEEP** (UPDATE version references where stale) |
| **Current Android 3.5–4.3.1 docs:** V3_5*, V3_6*, V3_7, V3_8, V3_9, V4_0*, V4_1*, V4_2*, ROADMAP_*, V3_5_TO_V4_1_IMPLEMENTATION_PLAN, UI_UX_*, UI_POLISH_REPORT_V4_3_1 | about 20 | **KEEP** |
| **Desktop docs:** WINDOWS_INSTALLATION, MOBILE_*, RELEASE_PROCESS, CODE_SIGNING, SIGNING_PROVIDER_DECISION, AUTOMATIC_*, DATA_AND_MIGRATION, M2_KNOWLEDGE_FOLDER, OFFLINE_OPERATION, MEMBERSHIP_AND_REGISTRATION, PUBLIC_*, HOSTING_COST_ESTIMATE, README_MAINTENANCE, ROADMAP (desktop v2), INSTALLER_READINESS_AUDIT | about 20 | **KEEP.** README and CHANGELOG guard tests reference this structure. |
| **Desktop milestone evidence:** AUDIT_v1.0.0, FINAL_ACCEPTANCE_DEV3/4/5, RELEASE_BLOCKER_FIXES_DEV3, TEST_REPORT_M2, MERGE_READINESS_M2, PHYSICAL_PC_ACCEPTANCE_TEST, UPDATE_RECOVERY_TEST | 9 | **KEEP** (release evidence) |
| **Android RC.2–RC.4 plans, reports and checklists:** ANDROID_ARCHITECTURE_AUDIT, ANDROID_IMPLEMENTATION_PLAN, ANDROID_BASELINE_TEST_REPORT, RC3_*, RC4_* (except setup and troubleshooting) | about 14 | **ARCHIVE** to `docs/archive/android-rc/`. They're superseded by 3.x–4.x, and the unique facts already live in ANDROID_ARCHITECTURE, ANDROID_PRIVACY and AI_PROVIDER_CONFIGURATION. |
| **Android 3.0–3.4 plans, test reports and checklists:** V3_IMPLEMENTATION_PLAN, V3_BUILD_AND_TEST_REPORT, V3_ACCEPTANCE_CHECKLIST, V3_FEATURE_STATUS, ABHIFY_V3_1_*, ABHIFY_V3_2_* (plans, tests, checklists), V3_3_*, V3.4_IMPLEMENTATION_PLAN, V3_4_TEST_REPORT, V3_4_ACCEPTANCE_CHECKLIST | about 22 | **ARCHIVE** to `docs/archive/android-3.x/` (history). Keep the feature guides (career, study, resume, OCR) in place, or **MERGE** them into one ANDROID_FEATURES guide. |
| **Overlapping Android architecture docs:** ANDROID_APP.md, ANDROID_ARCHITECTURE.md | 2 | **UPDATE/MERGE** into the new `PROJECT_ARCHITECTURE.md` (as of 4.3.1) |
| **Release notes:** V3_RELEASE_NOTES, V3.4_RELEASE_NOTES, V3_5/V3_6_RELEASE_NOTES | 4 | **MERGE** into an Android section of the changelog; keep the files as history until merged |

**No document contains secrets** (a scan for API-key and token patterns found none).

**Before any archive move:** links will be checked. The root README and docs link to some of these files, and two desktop guard tests read README and CHANGELOG. Links get updated in the same step.

## 6. Proposed order (after your approval)
1. `.gitignore` Android entries (non-destructive).
2. `build/` deletions in the table above, one folder at a time, each listed in this file afterwards.
3. Move old APKs outside the repo.
4. Archive the docs, with link updates and a `docs/archive/README.md` index.

## 7. Actions performed (2026-11-06, approved by the owner)

Each item was checked first: zips tested with `unzip -t`, installer duplicates compared by SHA-256, old APKs checksummed into `D:\Abhify-archivepks\SHA256SUMS.txt` before moving.

**Result:** `build/` went from **9.5 GB to 3.3 GB** (3.3 GB includes the new 4.4.0 build): about 5.4 GB deleted (3 × 1.58 GB duplicate installers, 2 discarded installers, 11 unzipped kit copies), and **0.87 GB** of old APKs moved to `D:Abhify-archivepks`.

```
DELETED build/sandbox-modeltest/inputs/OllamaSetup.exe (1.58 GB duplicate; identical copy kept in build/taskb-input, SHA-256 ea0b5068…a519)
DELETED build/sandbox-modeltest-dev3/inputs/OllamaSetup.exe (1.58 GB duplicate; identical copy kept in build/taskb-input, SHA-256 ea0b5068…a519)
DELETED build/sandbox-modeltest-dev4/inputs/OllamaSetup.exe (1.58 GB duplicate; identical copy kept in build/taskb-input, SHA-256 ea0b5068…a519)
DELETED build/discarded-dev4-build1-24d75f81/ (installer marked discarded in its review)
DELETED build/discarded-dev6-build1-78ec538e/ (installer marked discarded in its review)
DELETED build/Abhify-Android-Test-dev2/ (unzipped copy; Abhify-Android-Test-dev2.zip verified and kept)
DELETED build/Abhify-Android-Test-rc2/ (unzipped copy; Abhify-Android-Test-rc2.zip verified and kept)
DELETED build/Abhify-Android-Test-rc3/ (unzipped copy; Abhify-Android-Test-rc3.zip verified and kept)
DELETED build/Abhify-Android-Test-rc4/ (unzipped copy; Abhify-Android-Test-rc4.zip verified and kept)
DELETED build/Abhify-Android-Test-v3.1/ (unzipped copy; Abhify-Android-Test-v3.1.zip verified and kept)
DELETED build/Abhify-Android-Test-v3.2/ (unzipped copy; Abhify-Android-Test-v3.2.zip verified and kept)
DELETED build/Abhify-Android-Test-v3.3/ (unzipped copy; Abhify-Android-Test-v3.3.zip verified and kept)
DELETED build/Abhify-Android-Test-v3.4/ (unzipped copy; Abhify-Android-Test-v3.4.zip verified and kept)
DELETED build/Abhify-Android-Test-v3/ (unzipped copy; Abhify-Android-Test-v3.zip verified and kept)
DELETED build/Abhify-Android-Test-v4.1/ (unzipped copy; Abhify-Android-Test-v4.1.zip verified and kept)
DELETED build/Abhify-Android-Test/ (unzipped copy; Abhify-Android-Test.zip verified and kept)
MOVED build/Abhify-2.0.0-rc.4-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-2.0.0-rc.4-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.0.0-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.0.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.1.0-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.1.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.2.0-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.2.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.3.0-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.3.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.5.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.5.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.6.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.6.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.7.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.7.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.8.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.8.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.9.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-3.9.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-4.0.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-4.0.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-4.3.0-debug-androidTest.apk → D:\Abhify-archive\apks\
MOVED build/Abhify-4.3.0-debug.apk → D:\Abhify-archive\apks\
MOVED build/android-dev3/ → D:\Abhify-archive\apks$d\ (old 2.0-era kit)
MOVED build/android-dev4/ → D:\Abhify-archive\apks$d\ (old 2.0-era kit)
DELETED backend/tests/__pycache__ (Python bytecode cache)
DELETED backend/__pycache__ (Python bytecode cache)
DELETED desktop/__pycache__ (Python bytecode cache)
DELETED cloud/app/__pycache__ (Python bytecode cache)
DELETED cloud/tests/__pycache__ (Python bytecode cache)
ARCHIVED docs/ANDROID_ARCHITECTURE_AUDIT.md → docs/archive/android-rc/ANDROID_ARCHITECTURE_AUDIT.md
ARCHIVED docs/ANDROID_IMPLEMENTATION_PLAN.md → docs/archive/android-rc/ANDROID_IMPLEMENTATION_PLAN.md
ARCHIVED docs/ANDROID_BASELINE_TEST_REPORT.md → docs/archive/android-rc/ANDROID_BASELINE_TEST_REPORT.md
ARCHIVED docs/RC3_ACCEPTANCE_CHECKLIST.md → docs/archive/android-rc/RC3_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/RC3_AUDIT_AND_PLAN.md → docs/archive/android-rc/RC3_AUDIT_AND_PLAN.md
ARCHIVED docs/RC3_DB_MIGRATION.md → docs/archive/android-rc/RC3_DB_MIGRATION.md
ARCHIVED docs/RC3_SECURITY_AUDIT.md → docs/archive/android-rc/RC3_SECURITY_AUDIT.md
ARCHIVED docs/RC3_PROVIDER_SETUP.md → docs/archive/android-rc/RC3_PROVIDER_SETUP.md
ARCHIVED docs/RC4_ACCEPTANCE_CHECKLIST.md → docs/archive/android-rc/RC4_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/RC4_FEATURE_STATUS.md → docs/archive/android-rc/RC4_FEATURE_STATUS.md
ARCHIVED docs/V3_IMPLEMENTATION_PLAN.md → docs/archive/android-3.x/V3_IMPLEMENTATION_PLAN.md
ARCHIVED docs/V3_BUILD_AND_TEST_REPORT.md → docs/archive/android-3.x/V3_BUILD_AND_TEST_REPORT.md
ARCHIVED docs/V3_ACCEPTANCE_CHECKLIST.md → docs/archive/android-3.x/V3_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/V3_FEATURE_STATUS.md → docs/archive/android-3.x/V3_FEATURE_STATUS.md
ARCHIVED docs/ABHIFY_V3_1_AUDIT.md → docs/archive/android-3.x/ABHIFY_V3_1_AUDIT.md
ARCHIVED docs/ABHIFY_V3_1_ACCEPTANCE_CHECKLIST.md → docs/archive/android-3.x/ABHIFY_V3_1_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/ABHIFY_V3_2_IMPLEMENTATION_PLAN.md → docs/archive/android-3.x/ABHIFY_V3_2_IMPLEMENTATION_PLAN.md
ARCHIVED docs/ABHIFY_V3_2_TEST_REPORT.md → docs/archive/android-3.x/ABHIFY_V3_2_TEST_REPORT.md
ARCHIVED docs/ABHIFY_V3_2_ACCEPTANCE_CHECKLIST.md → docs/archive/android-3.x/ABHIFY_V3_2_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/V3_3_ACCEPTANCE_CHECKLIST.md → docs/archive/android-3.x/V3_3_ACCEPTANCE_CHECKLIST.md
ARCHIVED docs/V3_3_TEST_REPORT.md → docs/archive/android-3.x/V3_3_TEST_REPORT.md
ARCHIVED docs/V3.4_IMPLEMENTATION_PLAN.md → docs/archive/android-3.x/V3.4_IMPLEMENTATION_PLAN.md
ARCHIVED docs/V3_4_TEST_REPORT.md → docs/archive/android-3.x/V3_4_TEST_REPORT.md
ARCHIVED docs/V3_4_ACCEPTANCE_CHECKLIST.md → docs/archive/android-3.x/V3_4_ACCEPTANCE_CHECKLIST.md
LINKS UPDATED docs/ANDROID_TEST_MATRIX.md
LINKS UPDATED docs/V3_3_IMPLEMENTATION.md
LINKS UPDATED docs/V3_4_KNOWN_LIMITATIONS.md
LINKS UPDATED docs/V3_KNOWN_LIMITATIONS.md
COMMENT UPDATED android/.../providers/Providers.kt (doc path)
DELETED 1064 __pycache__ folders inside backend/.venv/ (unintended: the cleanup pattern also matched the desktop virtualenv; bytecode caches only,
        rebuilt automatically on import; verified afterwards: `python -c "import fastapi, chromadb, langchain_community, onnxruntime"` OK)
```

**Kept on purpose:**
- `build/taskb-input/OllamaSetup.exe`: the one remaining copy, for sandbox reproducibility.
- The sandbox results and their installer copies.
- `build/windows`, all logs and evidence, `dist/`.
- APKs 3.4.0, 4.1.0, 4.2.0, 4.3.1 and 4.4.0.
- `android/app/.cxx`.
- All source code, user data and model files.
