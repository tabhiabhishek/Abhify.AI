# Abhify.AI 7.2.1: final release report

**Date:** 2026-11-25.

**Classification:** **stable.** The owner validated it on a real laptop (W1–W14) and phone (A1–A8) on 2026-11-26. It is **not production-signed**, so it is not yet ready for distribution to others.

## 1. Summary

7.2.1 consolidates the 7.2 line (7.2.0-dev.1 → 7.2.1-dev.2) into one frozen baseline:
- **Versions** are normalised to `7.2.1` on every platform.
- **Packages** are built and verified on all three platforms.
- **Documentation** is rewritten around one canonical reference.
- **Obsolete build artifacts** (4.79 GB) are moved out of the repository. Nothing was deleted.

All automated suites pass on the final code and packages. That includes Windows Sandbox fresh install and two upgrade paths, each with a stray-process scenario (the laptop failure).

**Feature freeze:** no feature was added in this release. 7.3 has not been started.

## 2. Stages

| Stage | Result |
|---|---|
| A. Audit | `docs/ABHIFY_7_2_1_FINAL_AUDIT.md`: Git state, versions, releases, artifacts, platforms, docs, tests, signing, risks |
| B. Validation gaps | The 7.2.1-dev.2 installer fix was the only code change after the last full run. Everything was re-run on the final code in E |
| C. Documentation | Complete documentation (31 sections, 213 routes), new README with 5 Mermaid diagrams, docs index (226 files classified, none moved), signing guide, install guide |
| D. Version and packages | 7.2.1 / Android 7020199. Builds, release folder, archive move |
| E. Verification | Section 5 |
| F. Report | This document |

## 3. Version metadata

| Place | Value |
|---|---|
| `backend/version.py` (API, `/health`, logs, installer ProductVersion, uninstall DisplayVersion) | `7.2.1` |
| `frontend/sw.js` | `CACHE_VERSION abhify-v7.2.1`, `SHELL_REV ui72.5` |
| `frontend/app.js` | `CACHE_HINT v7.2.1` |
| Android | versionCode **7020199** (> 7020102, the previous highest), versionName `7.2.1`. About screen, crash notes and backups read `BuildConfig.VERSION_NAME` |
| Update manifest | `dist/windows/7.2.1/latest.json.template` generated. In-app updates stay unconfigured (off) |
| Database schemas | Unchanged: Windows v7, Android v17 |

## 4. Packages: `release/Abhify.AI-7.2.1/`

| File | Bytes | SHA-256 | Signing |
|---|---|---|---|
| `windows/installer/Abhify.AI-7.2.1-Setup.exe` | 127,705,839 | `e48cfc062a13d2ce89a905e944b269c26c2e9503ecdf35bce035d5d7c7e18f0f` | Not signed |
| `windows/portable/Abhify.AI-7.2.1-Portable.zip` | — | `709529c1499cfa3a51b2d108432d9f554f65f762a8f6ea2a0c457a7f3448c8d8` | Not signed |
| `android/Abhify.AI-7.2.1-debug.apk` | 83,663,863 | `54b8ec90b94cb6a55b962b77606eea51bde06cf6ce678156b35dcea56263157a` | Development certificate `1d2d1fdb…` |
| `web/standalone/` | 40 files | per-file in `web/SHA256SUMS.txt` | — |

**Also in the folder:**
- `RELEASE_NOTES.md`, `INSTALL.md`, `SIGNING_GUIDE.md`, `TEST_SUMMARY.md`;
- `SHA256SUMS.txt` (top level) and the per-platform `*/SHA256SUMS.txt`;
- `manifests/release-manifest.json`;
- `docs/`: the complete documentation, this report, the audit, the validation checklists, the integrity report and the changelog.

**Contents checks:**
- No signing keys, secrets or user data are in the release folder. The build's package check passed, and the keystore stays only at `C:\Users\Administrator\.android\debug.keystore` (untouched).
- **The 7.2.1-dev.2 installer** (the laptop fix, sha `3b768ebb…`) is superseded by 7.2.1. It was moved to the archive (section 7), not deleted.

## 5. Verification results (final code)

Details are in `release/Abhify.AI-7.2.1/TEST_SUMMARY.md`.

| Suite | Result |
|---|---|
| Backend + JS harnesses | 897 passed / 0 failed / 3 skipped (2 opt-in live Ollama tests; 1 needs an old installer) |
| Android unit / lint | 567 / 0 failures / 4 skipped; lint 0 errors |
| Browser E2E Study / enhancements | 44/44, 20/20 (the latter re-run on another port after TeamViewer held port 8785) |
| Web smoke (release build) | 10/10 |
| Privacy captures F1/F1b | Pass: no planted marker reached the provider |
| Upload delete E2E | 7/7 |
| Microphone matrix (desktop and Web) | Pass |
| Sandbox: fresh install | 28/28 |
| Sandbox: upgrade 7.2.1-dev.1 → 7.2.1, stray process | 35/35 |
| Sandbox: upgrade 7.2.0-dev.2 → 7.2.1, stray process | 35/35 |
| Data integrity: released 7.2.1-dev.1 data opened by the released 7.2.1 runtime | Identical content, schema v7 → v7 |
| 7.2.1 release checksums | 15,535 / 15,535 files verify; release files unchanged after the runtime test |

**Usability items you asked for:**

| Item | How it was verified |
|---|---|
| Fresh install | Sandbox |
| Upgrade | Sandbox ×2 |
| Data preservation | Sandbox memory checks, integrity comparison, uninstall keeps data |
| Offline | The Sandbox has no internet. The app starts, reports "degraded" honestly, and Web export works offline |
| Backup | Sandbox backup command, plus the pre-upgrade backup |
| Restore | Covered by backend tests. **Not repeated on real data** (W-checklist) |
| No silent privacy changes | Android `online_history` keeps existing installs' behaviour (V721PrivacyTest). No setting is changed by an upgrade |

## 6. Previous releases (preserved)

Every earlier release folder was checked against its own checksum files on 2026-11-25:

| Release | Entries | Problems |
|---|---|---|
| 7.0.0 | 73 | 0 |
| 7.0.1 | 73 | 0 |
| 7.0.2 | 15,283 | 0 |
| 7.1.0-dev.1 | 6 | 0 |
| 7.1.0-dev.2 | 15,250 | 0 |
| 7.1.0-rc.1 | 15,525 | 0 |
| 7.1.0-rc.2 | 15,526 | 0 |
| 7.2.0-dev.1 | 15,527 | 0 |
| 7.2.0-dev.2 | 15,535 | 0 |
| 7.2.1-dev.1 | 15,535 | 0 |

None were modified.

## 7. Cleanup: archived build artifacts

**Moved, not deleted:** everything went to `D:\Abhify_Backups\archive-pre-7.2.1-build-artifacts\`. `MANIFEST.json` there records the original path, size and per-file SHA-256, and `README.md` explains how to restore. References were checked first: no code, script or test reads these paths (only `build/windows/staging` is used, and it was kept). After the move, every file was verified at its destination.

| Original path | Size | Note |
|---|---|---|
| `dist/windows/2.0.0-dev.8`, `7.0.0-dev.1` | 125 + 118 MB | Only copies (no release folder) |
| `dist/windows/7.0.0` … `7.2.0-dev.1` (8 folders) | ≈ 955 MB | Copies of installers in `release/` (hash-matched) |
| `dist/windows/7.2.1-dev.2` | 122 MB | Superseded by 7.2.1 |
| `build/internal` | 711 MB | Internal APKs 5.4-dev.1 … 7.0-dev.1 |
| `build/portable` | 2,470 MB | Unpacked 7.0.x portables (60,942 files) |
| `build/windows/7.0.2-fixed` | 118 MB | Same installer as `release/Abhify.AI-7.0.2` |
| `build/Abhify-5.0/5.1/5.3/5.4.apk`, `5.0-androidTest.apk` | 281 MB | Historical APKs |
| **Total** | **4.79 GB** (19 items) | |

**Kept in place:**
- `dist/windows/7.2.0-dev.2` and `7.2.1-dev.1`: sources for the Sandbox upgrade tests.
- `dist/windows/7.2.1`.
- `build/windows/staging` and `build/sandbox-results`.
- All of `release/`.

**Also kept:** the pre-7.2.1 README, in `…\docs-snapshots\`.

**Stable consolidation (2026-11-26, owner-approved):** these were permanently deleted:
- the previous release folders 7.0.0 to 7.2.1-dev.1;
- the superseded `dist/windows` builds;
- 433 historical files in `docs/`.

The 7.2.1 release, the canonical docs, the roadmap and specs, and the docs that code or tests read were kept.

## 8. Changes made in this release step

| Change | Files |
|---|---|
| Version | `backend/version.py`, `frontend/sw.js`, `frontend/app.js`, `android/app/build.gradle.kts`; test pins in `test_web_desktop_61.py`, `test_web_desktop_70.py` |
| Docs | `README.md` (rewritten); `CHANGELOG.md` (`[7.2.1]`, plus one corrupted character fixed in the dev.2 entry); new: `docs/ABHIFY_AI_COMPLETE_DOCUMENTATION.md`, `docs/README.md`, `docs/ABHIFY_7_2_1_FINAL_AUDIT.md`, this report, `docs/ABHIFY_ROADMAP.md` |
| Tests (reported change) | API documentation guards in `test_packaging_dev3.py`, `test_desktop_56.py`, `test_realtime.py`, `test_voice_71.py` now read the complete documentation |

No application behaviour changed.

## 9. Risks and limitations

1. **Physical validation:** passed (owner-reported, 2026-11-26), including the laptop install that failed on 7.2.1-dev.1.
2. **Not production-signed.**
   - Windows: SmartScreen warnings.
   - Android: sideload only.
   - Moving to a production key needs APK v3 key rotation, or existing installs can't upgrade. See `SIGNING_GUIDE.md`.
   - `signtool` is likely missing on the build PC.
3. **Feature gaps are documented** (complete documentation §29): Web limits, Drive hidden, 7.3 Brain features, no Windows to-dos.
4. **The repository has no commits since `592df89`:** 82 modified and 158 untracked files hold the whole 7.0–7.2.1 line. A disk failure would lose them. Committing is strongly recommended (section 10).

## 10. Proposed Git operations (need your approval; nothing has been run)

`release/`, `dist/` and `build/` are git-ignored, so packages are not committed. No secrets or keystores are among the changed files.

1. `git add -A` on the source tree. First review the untracked list (`git status`) together.
2. `git commit -m "Abhify.AI 7.2.1: final stable release (7.0.0 – 7.2.1 consolidated)"` on branch `release/7.0.0`. Per your rule, the commit message has no tool attribution.
3. `git tag -a v7.2.1 -m "Abhify.AI 7.2.1"`.
4. Optional, later and only on request:
   - rename the branch to `release/7.2` or merge into `main`;
   - push to a remote (none is configured).

There will be no force-push, no history rewrite and no visibility change.

## 11. Next steps for you

1. Install `Abhify.AI-7.2.1-Setup.exe` on the laptop over the failed 7.2.1-dev.1 install, then work through W1–W14.
2. Install the APK over the phone app, then work through A1–A8.
3. Approve, or change, the Git plan in section 10.
4. After validation: start 7.3 M1 (unified consent) as a separate milestone.
