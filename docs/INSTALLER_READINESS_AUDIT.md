# Installer readiness audit: Abhify.AI 2.0.0-dev.2

Audit date: 2026-10-14. Auditor role: release engineer.

Scope: verify installer readiness. No application code was changed, nothing was merged or published, no destructive action was run against the development installation or the real user data. Installs and uninstalls ran only in **Windows Sandbox**, and server tests used **isolated temporary data folders**.

Legend:
- **VERIFIED:** checked during this audit, with evidence.
- **CLAIM:** stated in an earlier report, not re-checked.
- **NOT VERIFIED:** could not be checked here.

## Decision

# 🔴 NO-GO for merge to `main` or any public release (yet)
## 🟢 GO for continued internal testing of the dev build

Reasons:
- one reproducible **thread-safety defect** (B1) makes the automated suite non-deterministic
- one **security/privacy finding** (B2) and one **misleading-UI defect** in the mobile flow (B3)
- the **mandatory acceptance tests on a second physical PC and real phones haven't been done** (B4)

Details and fixes are below.

## 1. Repository identity: VERIFIED
| Item | Value |
|---|---|
| Branch | `feature/m2-knowledge-packaging` |
| HEAD | `35799736649d123aac19bd6ea22dd1d11043430a` (matches the reported `3579973`) |
| Working tree | clean (`git status --short` is empty) |
| History | `574072e` (v1.0.0) → `ddeb5f4` → `a98c622` (M1) → `8197044` → `5b7128d` → `3579973` (M2) |
| Tags | only `v1.0.0` → `574072e44cebbd1a6172ffbc01f756584e29ce86` (unchanged) |
| `main` | still at `574072e` (nothing merged) |

## 2. Installer artifact: VERIFIED
| Item | Value |
|---|---|
| File | `dist\windows\Abhify.AI-Setup-2.0.0-dev.2.exe` |
| Size | **129,048,508 bytes** (123.1 MiB) |
| SHA-256 | `9e299def998a379db51f8a10dbe363ad38f7ea8091c437a52a5434166752f8b2`, **matches** the reported value and `SHA256SUMS.txt` |
| Authenticode | **NotSigned** (`Get-AuthenticodeSignature`) |
| Version resource | ProductName `Abhify.AI`, ProductVersion/FileVersion `2.0.0-dev.2` |
| Provenance | The installer was built *before* commit `3579973`. All **33 app files** in the build staging folder are byte-identical (ignoring line endings) to `HEAD`, and inside Windows Sandbox the **installed** app files also match `HEAD` (33/33). The changes after the build were build-script hash pinning and docs only, which aren't part of the payload |

## 3. Packaged contents: VERIFIED (staging folder + installed copy in the sandbox)
| Component | Result |
|---|---|
| Python runtime | Embeddable 3.11.9 x64 (`python.exe`, `pythonw.exe`, `python311.zip`), with `._pth` enabling `Lib\site-packages`. Input zip SHA-256 `009d6bf7…cfd3b` (pinned) |
| Backend dependencies | Imports in isolated mode (`-I`) on the build machine and on the clean sandbox: fastapi, chromadb, langgraph, faster_whisper, watchdog, pystray and others |
| Native libraries on a clean PC | ChromaDB writes a row; ctranslate2 4.8.3 loads (sandbox) |
| Visual C++ runtime | 9 DLLs present (msvcp140, _1, _2, _atomic_wait, _codecvt_ids, concrt140, vcomp140, vcruntime140, _1). `dll_check.py`: no unmet DLL imports across 189 binaries |
| Launcher / app | `app\desktop\launcher.pyw`, `app\backend\*.py` (no tests), `app\frontend\**` |
| Icons | `Abhify.AI.ico` (7 sizes) plus PWA icons |
| Uninstaller | `Uninstall.exe` is generated at install time (`WriteUninstaller`) and present after install (sandbox) |
| Total payload | 20,956 files, 676.8 MB uncompressed |

### Secrets and personal data scan (whole payload, 20,956 files)
- **Private keys / tokens:** 0 real hits. 1 pattern hit (`AKIA…`) inside a **base64-encoded font** in Pillow's `ImageFont.py`: a false positive. Only `.pem` files are the public CA bundles (`certifi/cacert.pem`, `grpc/roots.pem`).
- **Databases, PDFs, Office documents, logs, model weights, user data:** none. The `.onnx` files are Silero VAD (part of faster-whisper) and three tiny onnxruntime sample models.
- **Personal strings** (`gunjuk`, `tabhiabhishek`, `Gunju`, `C:\Users\Administrator`, test codewords): **0 hits**.
- **Development paths: FOUND (finding F1)**: 9,450 `.pyc` files embed build-machine source paths (`D:\ai_tech_pulse\backend\.venv\…`, `D:\Abhify.AI\…`), including 184 pytest assertion-rewrite caches (`*-pytest-9.1.1.pyc`). Cause: the offline build (`--site-packages-from`) copied the dev venv's `__pycache__` folders (984 folders, ~161 MB uncompressed). No secrets, but it leaks the directory layout and wastes space.

## 4. Behaviour on a clean machine: VERIFIED in Windows Sandbox
Windows 11 Enterprise 22621, no Python, no Ollama, **networking disabled**, 6 GB RAM, no GPU. Command: see §9.

**Standard test: 19/19 PASS.**
- silent per-user install (32 s, no admin prompt)
- Start Menu and desktop shortcuts, uninstall entry
- runtime imports, native libraries
- the app starts and reports `degraded` / `ollama: false`
- data in `%LOCALAPPDATA%\AbhifyAI` (outside the install folder)
- diagnostics: 0 GPUs, 6 GB → **Minimal** recommended (CPU fallback)
- model pull without `confirm` → **HTTP 400**
- PWA served; single instance (1 → 1 processes); clean stop; backup command
- uninstall removes the program, **keeps data** and removes the uninstall entry
- reinstall finds the memory again

**Extra audit checks: 13/13 effectively PASS.**

| Check | Evidence |
|---|---|
| installed app matches commit | 33/33 |
| uninstaller present, VC runtime installed, no user data in the install folder | ✓ |
| Start Menu shortcut starts the app | ✓ |
| **localhost-only binding** | only listening socket: `127.0.0.1:8000`. The script printed FAIL because of a PowerShell 5.1 `.Count` bug in the *scratch* check; the evidence line shows the correct result |
| companion off by default | `{"enabled":false,…,"listening_on_lan":false}` |
| run file host | `"host": "127.0.0.1"` |
| **upgrade (installer over a running install)** | app stopped → `abhify-backup-pre-upgrade-*.zip` created → data preserved (memories 1 → 1) |
| Stop shortcut | app stopped, run file removed |

**Backup before migration** (VERIFIED on real data earlier on 2026-10-14): `abhify-pre-schema-v4-20261014-163740.db` was written before migration v4. Covered by automated tests (`test_v1_data_survives_upgrade_and_is_backed_up`, `test_upgrade_from_m1_schema_preserves…`).

## 5. Model setup wizard: VERIFIED (code + tests + sandbox), partly NOT VERIFIED
| Item | Status | Evidence |
|---|---|---|
| Consent before download | VERIFIED | `start_pull` requires `confirm=true` (unit test + sandbox HTTP 400); the UI uses a confirm dialog showing size and folder |
| Disk-space check | VERIFIED | `test_disk_check_blocks_unsafe_download` |
| Missing Ollama | VERIFIED | Sandbox `health.ollama=false`, wizard step shows install/start guidance (code); `test_missing_ollama_is_reported_not_raised` |
| CPU-only fallback | VERIFIED | Sandbox recommends Minimal with no GPU; Whisper CUDA→CPU fallback test |
| Hardware detection | VERIFIED | Real PC: RTX A2000 12 GB → Balanced; sandbox: none → Minimal |
| Failure recovery (failed pull, retry) | **NOT VERIFIED** end to end; the pull state "failed" and re-check exist in code only |
| Actual model download on a clean PC | **NOT VERIFIED** (the sandbox had no network) |

## 6. Automated tests: VERIFIED (re-run during this audit)
```
cd D:\Abhify.AI\backend
.venv\Scripts\python.exe -m pytest tests -q -p no:cacheprovider -rs
```
- Full run on `3579973` during the audit: **1 failed, 144 passed** (81.8 s). The run overlapped a Windows Sandbox VM, which made the machine slow.
- Earlier run (CLAIM in TEST_REPORT_M2, reproduced earlier on 2026-10-14): 145 passed.
- Focused re-runs: `test_single_instance_per_folder` **failed 1 in 8** and later 1 in 4 runs. **The test is flaky, and the flakiness exposes a real defect (B1).**

## 7. Findings

| ID | Severity | Finding | Evidence | Recommended fix |
|---|---|---|---|---|
| **B1** | **High (blocker)** | **Watcher deadlock-until-timeout.** `KnowledgeWatcher.start()` holds `self._lock` (an RLock) while calling `stop()`, which joins the worker thread. The worker is blocked acquiring the same lock in `_process_ready()`, so `start()` hangs for 10 s and the old worker can still be alive when the new one starts, briefly giving **two indexing threads**. The earlier fix only moved the join outside `stop()`'s own `with`, not the caller's | Thread dump captured with `-o faulthandler_timeout=7`: worker at `folder_watcher.py:208 _process_ready` waiting for the lock; main thread at `:126 stop` ← `:96 start` | In `start()`, call `self.stop()` **before** acquiring `self._lock` (or release the lock, stop, re-acquire, and re-check). Add a regression test that loops start(folderA) → start(folderB) 50× with an assertion that each switch takes < 2 s |
| **B2** | **Medium (security/privacy)** | Remote companion clients **with a valid token** can read system endpoints that should be local-only: `/system/diagnostics` (returns `settings.companion.token_sha256`, the Windows **user name** inside `data_root`, the Python path, startup details), `/backup` (backup names and `data_root`), `/setup/profiles` | LAN-IP test against an isolated server (port 8400): HTTP 200 for each; diagnostics JSON contained the token hash and paths | Make `/system/diagnostics`, `/backup`, `/setup/*` and `/knowledge/status` path fields local-only, **or** return a redacted view to remote clients. Never return `token_sha256` from any endpoint |
| **B3** | **Medium (misleading UI)** | An **unpaired** phone shows **"AI ready · via your computer"** although every API call returns 401, because the open `/health` is treated as "connected" | Simulated phone at 412 px over the LAN | After `/health`, call an authenticated endpoint (e.g. `/companion` `authorized`). If unauthorized, show "Not paired: enter the access token" and keep the offline banner |
| B4 | **Process blocker** | Required acceptance tests not done: second physical PC, real model download on a clean PC, installer-to-installer upgrade between *different* versions, real Android and iPhone | See `PHYSICAL_PC_ACCEPTANCE_TEST.md`, `MOBILE_ACCEPTANCE_TEST.md` | Run those procedures |
| F1 | Low | Build-machine paths in 9,450 `.pyc` files, plus 184 pytest caches in the payload | Payload scan | In `build.py`, copy with `ignore_patterns("__pycache__", "*.pyc")`, then run `python -m compileall -q -d "<app>" runtime\Lib\site-packages` (or ship without `.pyc`) |
| F2 | Low | The pairing link is **ignored if the app is already open** (only read at start-up; a `#pair=` hash change in the same tab doesn't re-run it) | Simulated phone: second navigation didn't store the token. A fresh load works | Listen for `hashchange` and call `readPairingLink()` |
| F3 | Low | The uvicorn access log records full query strings. A token wrongly placed in `?token=` (the app never does this) would be written to the log | Isolated server log line (from a deliberate negative test) | Disable the access log, or use a filter that redacts query strings |
| F4 | Info | The installer is **unsigned**, so SmartScreen and antivirus warnings are likely | `NotSigned` | See `CODE_SIGNING.md`. Disclose in release notes |
| F5 | Info | The release build used `--site-packages-from` (offline). The online build path from `requirements.txt` is **NOT VERIFIED** | Build log | Run the online build once on a fast network, and add a hashed lock file |
| F6 | Info | NSIS `MUI_FINISHPAGE_SHOWREADME` opens `INSTALL.md`, which Windows may not have an app for | Code review | Ship `INSTALL.html` or link to the online docs |

## 8. Not tested / NOT VERIFIED
- Installing on a **second physical Windows PC** (Windows 10 and 11, different users, standard and non-admin accounts).
- **Model download and inference on a clean PC** (the sandbox has no network).
- **Installer-to-installer upgrade between different versions** (only same-version reinstall over a running app was tested).
- **Real phones:** Android Chrome and iPhone Safari installs, icon, microphone, offline behaviour, Tailscale HTTPS.
- The **online build** path.
- SmartScreen and Microsoft Defender reputation on a downloaded (Mark-of-the-Web) copy.
- Behaviour on a **real NVIDIA PC after installation** (GPU use was only verified on the development environment).
- Failure recovery of a model download that's interrupted mid-way.

## 9. Exact commands used
```powershell
# identity
git -C D:\Abhify.AI status --short; git -C D:\Abhify.AI log --oneline --decorate; git -C D:\Abhify.AI rev-list -n1 v1.0.0
# artifact
Get-FileHash D:\Abhify.AI\dist\windows\Abhify.AI-Setup-2.0.0-dev.2.exe -Algorithm SHA256
Get-AuthenticodeSignature D:\Abhify.AI\dist\windows\Abhify.AI-Setup-2.0.0-dev.2.exe
# payload checks
python packaging\windows\check_package.py build\windows\staging\app
python packaging\windows\dll_check.py build\windows\staging\runtime
python <scratch>\audit_scan.py build\windows\staging          # secrets / personal / dev-path scan
# tests
backend\.venv\Scripts\python.exe -m pytest backend\tests -q -p no:cacheprovider -rs
backend\.venv\Scripts\python.exe -m pytest "backend\tests\test_watcher.py::test_single_instance_per_folder" -o faulthandler_timeout=7
# clean machine
powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1
# + extra audit checks (scratch audit-extra.ps1: installed-file hashes vs HEAD, listening sockets,
#   companion default, upgrade over running install, Stop shortcut)
```

## 10. Path to GO
1. Fix **B1**, add the regression test, and get **3 consecutive green full-suite runs**.
2. Fix **B2** and **B3**, plus F1 and F2 (small changes). Rebuild, then re-run the Sandbox test and this audit's payload scan.
3. Run the physical-PC acceptance test, and the phone tests if devices are available.
4. Then the merge readiness review can return GO.
