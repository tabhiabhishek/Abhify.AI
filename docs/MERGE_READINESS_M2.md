# Merge readiness: `feature/m2-knowledge-packaging` → `main`

Date: 2026-10-14

# 🔴 NO-GO. No merge was performed.

This follows the merge procedure's own stop rule: *"If any critical acceptance test is missing or any data-loss/security blocker remains, stop before merging."* Both conditions apply.

## Pre-merge checks

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Branch and commit identity | ✅ VERIFIED | `feature/m2-knowledge-packaging` @ `35799736649d123aac19bd6ea22dd1d11043430a` = reported `3579973`; `main` @ `574072e` |
| 2 | Audit and acceptance reports read | ✅ | `INSTALLER_READINESS_AUDIT.md` (NO-GO), `PHYSICAL_PC_ACCEPTANCE_TEST.md` (**not executed**), `MOBILE_ACCEPTANCE_TEST.md` (server and simulated tests; **devices not tested**; 3 defects) |
| 3 | Working tree clean / everything committed | ✅ before this review's docs; the new review docs are committed separately (docs only) | `git status --short` was empty at `3579973` |
| 4 | Full automated suite passes | ❌ **not reliably** | 1 failed / 144 passed during the audit; `test_single_instance_per_folder` fails intermittently, caused by real defect **B1** |
| 5 | Secrets, model files, databases, installer artifacts in Git | ✅ none | 71 tracked files; 0 tracked `.db/.sqlite/.pfx/.pem/.key/.gguf/.safetensors/.exe/.zip`; `.git` = 3.2 MB; `build/`, `dist/` and `packaging/.cache/` are ignored |
| 6 | Baseline tag `v1.0.0` unchanged | ✅ | → `574072e44cebbd1a6172ffbc01f756584e29ce86` |
| 7 | Backups and migration recovery instructions | ✅ | `D:\Abhify_Backups\ai_tech_pulse_v1.0.0_data`; automatic `abhify-pre-schema-v2/v3/v4-*.db` in `%LOCALAPPDATA%\AbhifyAI\data\backups`; recovery documented in `WINDOWS_INSTALLATION.md` (Upgrade) and `RELEASE_PROCESS.md` (Rollback) |
| 8 | Merge conflicts | n/a | `main` is an ancestor of the branch, so it would fast-forward. **Not executed** |

## Blockers to clear before asking for GO again
1. **B1 (High):** fix the watcher `start()`→`stop()` lock ordering; add a regression test; get **3 consecutive green full runs**.
2. **B2 (Medium, security/privacy):** local-only or redacted `/system/diagnostics`, `/backup` and `/setup/*` for remote clients; never return `token_sha256`.
3. **B3 (Medium, misleading UI):** show "Not paired" when the token is missing or wrong.
4. **B4 (Process):** run `PHYSICAL_PC_ACCEPTANCE_TEST.md` on a real second PC, at least steps 0–14 except the GPU step. Phone tests are strongly recommended, but can be listed as a known limitation for a *dev* prerelease if you explicitly accept that.
5. Recommended in the same pass: F1 (no `.pyc` with build paths), F2 (`hashchange` pairing), F3 (access-log redaction), F6 (HTML install guide).

## When those are done: proposed merge procedure (needs your approval)
```powershell
cd D:\Abhify.AI
git checkout feature/m2-knowledge-packaging
backend\.venv\Scripts\python.exe -m pytest backend\tests -q      # 3x green
git checkout main
git merge --no-ff feature/m2-knowledge-packaging -m "Merge M1+M2: Abhify.AI 2.0 development line"
backend\.venv\Scripts\python.exe -m pytest backend\tests -q      # on the merge commit
git rev-parse HEAD                                               # record the main commit hash
# keep feature branches and all backups; no tag yet
```

## Proposed prerelease (task 5): **blocked**, needs merge approval first
- **Version: `2.0.0-dev.3`, not `2.0.0-dev.2`.** The fixes above change the payload, and the `2.0.0-dev.2` installer (`9e299def…`) has already been built and documented under that name. A version must never be reused for different content. Bump `backend/version.py`, rebuild, and get a new checksum.
- Tag `v2.0.0-dev.3` on the **merge commit** on `main`; GitHub **pre-release** (not latest, not stable).
- Title: *Abhify.AI 2.0.0-dev.3 (development prerelease, Windows, unsigned)*.
- Assets: `Abhify.AI-Setup-2.0.0-dev.3.exe`, `SHA256SUMS.txt`, `RELEASE_NOTES.md`, `INSTALL.md`, plus `latest.json` with the real asset URL.
- Mandatory disclosures in the notes:
  - the installer is **unsigned** (SmartScreen warning expected)
  - Ollama is required separately, and models are downloaded in-app with consent
  - not available in any app store
  - no native phone apps (PWA via your PC only)
  - list every untested scenario from the audit
- Before publishing:
  1. Show you the exact version, commit, title, notes and assets.
  2. After your approval, upload the assets and compare the SHA-256 of the *downloaded* assets with the local ones.
- GitHub credentials on this PC were removed at your request, so publishing would be done by you, or after you re-authenticate. The exact manual steps are in `RELEASE_PROCESS.md` → Publish.

## Release checklist (for the future GO)
- [ ] B1–B3 fixed; F1–F3 and F6 fixed or explicitly accepted
- [ ] `pytest` green 3× on the release commit
- [ ] `version.py` = `2.0.0-dev.3`; CHANGELOG section added
- [ ] Clean build (prefer the online build from `requirements.txt`; F5)
- [ ] `check_package.py`, `dll_check.py` and the payload secret scan all clean
- [ ] Windows Sandbox: standard 19/19 and the audit extras pass
- [ ] Physical-PC acceptance test executed and recorded
- [ ] Phone tests executed, or accepted as a disclosed limitation
- [ ] Merge approved and executed; `main` hash recorded
- [ ] Release contents shown to you and **explicitly approved**
- [ ] Assets uploaded, then downloaded again and checksums verified
- [ ] Marked **pre-release**; unsigned disclosure present
