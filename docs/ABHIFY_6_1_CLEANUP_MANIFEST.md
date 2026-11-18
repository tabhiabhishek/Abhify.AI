# Abhify.AI 6.1 cleanup manifest (2026-11-18)

**How it was checked:** a read-only reference scan of Kotlin, XML, gradle, HTML, CSS, JS, Python, PowerShell, batch, NSIS, Markdown and JSON (excluding `.venv`, build output and `.git`), a duplicate-hash scan, and a manual re-check of every class B item.

**Important:** most of the repo is untracked in git. Only 124 files are tracked; `android/`, `cloud/` and most of `docs/` are not. Git cannot restore a deleted untracked file, so every removal in 6.1 was first copied to `D:\Abhify-archive\pre-6.1-cleanup\` with `SHA256SUMS.txt`.

## Done in 6.1 (classes A/B, allowed by your rules)
| Class | Path | Size | Evidence | Recovery |
|---|---|---|---|---|
| B | `android/TaskRunner.kt` | 0 B | An empty stray file outside `app/src`, never compiled. The real file is `app/src/main/java/ai/abhify/android/task/TaskRunner.kt` | Archive copy |
| B | `android/app/src/main/res/drawable/ic_check_circle.xml`, `ic_dashboard.xml`, `ic_history_edu.xml`, `ic_key.xml`, `ic_more_horiz.xml` | 3.2 KB | Zero `R.drawable`/`@drawable` references and no `getIdentifier`. Re-checked with whole-word grep | Archive copy |
| – | `ic_menu.xml` | – | Removed, then **restored** (hash identical). `V44FeaturesTest` requires it in the set of official licensed Material icons; the scan missed it because the test lists it as the bare word "menu". The test was not changed | Restored |
| B | Dead composables `MemoriesScreen` (superseded by `PersonalMemoryScreen`), `AiModeChip`, `Spacer8`, `Stat`, and the duplicate `ago(ms)` in HomeScreens | code | No callers. All one-argument `ago()` calls now resolve to the better helper ("2 days ago", dates) | Git cannot restore it (untracked); the removed code was small and is described here |
| B | Gradle deps `lifecycle-viewmodel-compose` and `ui-tooling-preview` | – | No `viewModel()` call, import or `@Preview`. The build and all tests pass without them | Uncomment the two lines in `app/build.gradle.kts` |
| B (test fix) | – | – | `test_companion_ai.py`: the test now uses a throw-away database (see the baseline) | – |

**Not removed on purpose (generated, but needed now):**
- `android/app/.cxx` (1.1 GB, CMake plus the downloaded llama.cpp sources) and `android/app/build` (974 MB). Deleting them would force a networked native rebuild for the 6.1 APK. They are safe to delete later, for example with `gradlew clean`.
- Python `__pycache__` and `.pytest_cache` (about 3 MB): regenerated on every run; not worth the churn.

## Class C: potentially obsolete (needs your decision)
| Path | Size | Recommendation |
|---|---|---|
| `dist/windows/INSTALL.md`, `RELEASE_NOTES.md`, `SHA256SUMS.txt`, `latest.json.template` (root level, dated 2026-10-14; `SHA256SUMS` names the deleted dev.2 installer) | 11 KB | Delete (`dist/windows/2.0.0-dev.8/` has the current copies) |
| `build/CLEANUP_REPORT_5.0.md` | 8 KB | Move to `docs/archive/` |
| `packaging/windows/sandbox/run-sandbox-{e2e,modeltest,signlab,test}.ps1` (51–64% shared text) | small | Merge into one launcher with a mode parameter |
| `frontend/brand/logo.svg`, `logo.png`, `logo-mark.svg` (only referenced by docs) | 1.7 MB | Keep as brand masters, or move to a design folder |
| `android/.../training/DatasetPolicy.kt` (groundwork, only tests use it); `intel/RetrievalEval.kt` (eval harness) | 7 KB | Keep (or move RetrievalEval to the test source set) |
| `docs/`: 166 files with overlapping audits and old release reports (V3_*, V4_*, RC4_*, 5.x) | 1.3 MB | Move historical version reports to `docs/archive/` in one reviewed batch |
| `D:\Abhify-archive\apks\android-dev3`, `android-dev4` (unpacked old test kits) | 87 MB | Delete |

## Class D: historical build and release artifacts
### Previous APK deletion manifest (awaiting your approval; nothing deleted)
All of these are package `ai.abhify.android`, signed with the same debug certificate `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`.

**Rollback facts:**
- Android installs a build over an installed app only if its versionCode is equal or higher.
- Every internal build since 5.4 is 5040099, so any of them can replace another without losing data. ChatDb has been v17 throughout.
- 5.0 (5000099) and 5.1 (5010099) **cannot** be installed over 5.4 or later without uninstalling, and uninstalling deletes the app's data. They are history, not rollback.

| # | File | Version / code | Bytes | SHA-256 | Proposal |
|---|---|---|---|---|---|
| 1 | `build/Abhify-5.0-androidTest.apk` | test APK | 873,935 | `e2473239…4c7399` | **Delete** (instrumentation kit for 5.0) |
| 2 | `build/Abhify-5.1.apk` | 5.1 / 5010099 | 73,552,637 | `659053fc…cd9f` | **Delete** (superseded; can't be used for rollback) |
| 3 | `build/Abhify-5.3.apk` | 5.3 / 5030099 | 73,708,493 | `778e5c50…7bd5` | **Delete** (superseded; can't be used for rollback) |
| 4 | `build/internal/Abhify-5.4-dev.1.apk` | 5040099 | 74,272,413 | `9b8a8887…1354` | **Delete** (superseded internal) |
| 5 | `build/internal/Abhify-5.5-dev.1.apk` | 5040099 | 74,392,641 | `46b41771…468e` | **Delete** |
| 6 | `build/internal/Abhify-5.6-dev.1.apk` | 5040099 | 74,483,329 | `fa7aa2fe…6416` | **Delete** |
| 7 | `build/internal/Abhify-5.6-dev.2.apk` | 5040099 | 74,510,741 | `e1df68bc…620c` | **Delete** |
| 8 | `build/internal/Abhify-5.6-dev.3.apk` | 5040099 | 74,506,621 | `189e49e1…1921` | **Delete** |
| – | `build/Abhify-5.0.apk` | 5.0 / 5000099 | 73,163,006 | `746150e5…5423` | Optional. It is the last build you confirmed on the phone, but it can't be installed over 5.4+ without data loss. Keep it as history or delete it |
| – | `build/Abhify-5.4.apk` | 5.4 / 5040099 | 73,904,909 | `c176c90b…2330` | **Keep**: the current official version and a real rollback target |
| – | `build/internal/Abhify-6.0-dev.1.apk` | 5040099 | 74,541,281 | `a1f45679…adb0` | **Keep**: the previous validation build |
| – | `build/internal/Abhify-6.1-dev.1.apk` | 5040099 | new | see the 6.1 build report | **Keep**: the latest |

- **Space recovered by deleting items 1–8:** 520,300,810 bytes (about 496 MB). Adding the optional 5.0 brings it to 593,463,816 bytes (about 566 MB).
- Full hashes are in [ABHIFY_6_1_BASELINE.md](ABHIFY_6_1_BASELINE.md). They are also in the earlier build reports, so the records stay after deletion.
- Recommended: delete items 1–8, keep 5.0, 5.4, 6.0-dev.1 and 6.1-dev.1.

### Other class D
| Path | Size | Recommendation |
|---|---|---|
| `build/windows/staging/` (desktop packaging staging, 2.0.0-dev.8) | 704 MB | Delete after the next desktop build (`build.py` recreates it) |
| `dist/windows/2.0.0-dev.8/` (latest Windows installer) | 126 MB | Keep |
| `D:\Abhify-archive\apks\` (24 APKs, 2.0-rc.4 to 4.3.0) | 865 MB | Your earlier choice was to keep them here. Can be deleted if you no longer need them |

## Class E: never deleted as routine cleanup
- `%LOCALAPPDATA%\AbhifyAI` (the database, migration backups, config, logs) and `D:\Abhify_Backups`.
- `backend/data/` and `backend/.venv/`.
- `C:\Users\Administrator\.android\debug.keystore`: the only signing key for every installed build. **A backup is recommended and needs your approval (R2).**
- Ollama models, `packaging/.cache` (the embedded Python runtime and NSIS), `.env.example`.
- `D:\Abhify-archive\pre-*` source snapshots.
