# Release-blocker fixes: Abhify.AI 2.0.0-dev.3

Date: 2026-10-16. Branch `feature/m2-knowledge-packaging`. Starting point `8a3f747` (dev.2 code `3579973` plus the audit docs).
Fix commit: **`cbba504`** (`cbba504b1361c863fb3cd22675420e4c78eacae6`); reports in the following docs commit. Nothing was merged, tagged, signed or published.

Legend: **PASS** (checked, with evidence) · **FAIL** · **BLOCKED** (couldn't run here) · **NOT TESTED**.

## Summary

| ID | Severity | Status | Fix | Regression tests |
|---|---|---|---|---|
| B1 | High | **Fixed** | Session-based watcher lifecycle, lock ordering, bounded joins, stale-event rejection | `test_watcher_lifecycle.py` (8) |
| B2 | Medium (security) | **Fixed** | Remote allow-list, local-only system routes, redaction, no `token_sha256` anywhere | `test_companion_privacy.py` (15) |
| B3 | Medium (UI) | **Fixed** | `/health` reports `remote`/`authorized`; the PWA shows nine distinct, honest states | `test_pwa_behaviour.py` (7) |
| F1 | Low | **Fixed** | No copied `__pycache__`; bytecode compiled with a neutral path; the build fails on build paths | `test_packaging_dev3.py` (4) |
| F2 | Low | **Fixed** | `hashchange` + `storage` listeners; "Paired" shown only after the PC accepts the token | `test_pwa_behaviour.py` |
| F3 | Low | **Fixed** | Central log filter (query values, bearer tokens, auth headers, secret params) | `test_log_redaction.py` (10) |
| F6 | Info | **Fixed** | `INSTALL.html` generated from the install guide; the finish page opens it | `test_packaging_dev3.py` (3) |
| README | — | **Fixed** | Rewritten from the code; guard test for routes and version | `test_packaging_dev3.py` (2) |

## B1: knowledge-folder watcher deadlock

**Root cause.**
- `KnowledgeWatcher.start()` held `self._lock` (an RLock) and called `stop()`.
- `stop()` released *its own* acquisition before joining the worker. Because the lock is re-entrant, the outer acquisition from `start()` was still held.
- A worker that was about to enter `_process_ready()` waited for `_lock` while `start()` waited for the worker, until the 10 s join timeout.
- After the timeout, `start()` launched a new worker while the old one was still alive.
- This made `test_single_instance_per_folder` flaky (1 in 4–8 runs), and it could briefly run two indexing threads.

**Fix (`backend/folder_watcher.py`).**
- Every `start()` creates a `_Session` with a generation number, root, its own `stop`/`rescan` events and its own status dict.
- `_life` (a plain lock) serialises `start()`/`stop()`, and the worker never takes it. `_lock` now only guards the queue, for a few statements at a time. **No thread is ever joined while `_lock` is held.**
- Joins are bounded (`JOIN_TIMEOUT_S = 5`). If the old worker is still inside a slow file, `start()` returns anyway, and the **new worker waits for the old one** before doing anything, so at most one worker ever indexes.
- Watchdog handlers, queue items and rescan requests carry the session's generation. Anything from an older or stopped session is dropped, so stale events from the previous folder are never indexed into the new one.
- `drain()`: when the knowledge folder changes, `PUT /knowledge/folder` waits for a still-busy old worker before removing the old folder's documents. On timeout it restarts the old watcher and answers 409, and nothing is changed.

**Tests** (`test_watcher_lifecycle.py`, all synchronised with `threading.Event` gates, not sleeps or longer timeouts):
- `test_switch_while_worker_waits_for_queue_lock`: the exact dev.2 shape (the worker blocked on `_lock` when `start()` begins), with a 60 s join timeout so a deadlock would show as a hang.
- `test_switch_while_worker_needs_the_lock_does_not_deadlock`, `test_start_is_bounded_and_workers_never_overlap` (max concurrent `index_file` calls = 1), `test_stale_events_from_old_session_are_ignored`, `test_events_after_stop_are_ignored`, `test_drain_waits_for_leftover_worker`.
- `test_repeated_folder_switching_keeps_one_worker`: 30 switches with pause/resume/rescan, exactly 1 live worker after each. `test_stop_start_same_folder_loop`: 20 cycles.
- None of the existing watcher tests were weakened (`test_single_instance_per_folder` is unchanged).

**Results:**
- lifecycle tests + `test_single_instance_per_folder`: **10/10 runs PASS**
- `test_single_instance_per_folder` + the lock-shape test in a loop: **40/40 PASS** (dev.2 failed 1 in 4–8)
- the full suite **3×** (below)

## B2: companion API privacy

**Verified before the fix:** with a valid token, a LAN client got HTTP 200 from `/system/diagnostics` (including `settings.companion.token_sha256`, `data_root` with the Windows user name, the Python path and startup info), `/setup/profiles`, `GET /setup/models/pull`, `POST /setup/test`, `/backup` (with `data_root`), `/system/update-check`, `/docs` and `/openapi.json`. `/knowledge/status` and `/knowledge/files` returned absolute paths (`folder`, `default_folder`, `path_key`) and raw error text.

**Fix:**
- **Allow-list** (`companion.REMOTE_ROUTES` / `REMOTE_PREFIXES`): a valid token opens only chat, news, questions, MCQs, transcription and voice, document upload/list/status/files, progress, memory, preferences and conversations.
  - Every other route, **including routes added in the future**, answers `403 "This action is only allowed from the computer running Abhify.AI"`, checked in the middleware.
  - `main.py` adds `local_only` to `/system/diagnostics`, `/setup/profiles`, `GET /setup/models/pull`, `POST /setup/test`, `GET /backup` and `/system/update-check` as a second, per-route check.
- **Redaction for phones:** the knowledge folder is reduced to its name; `default_folder`, `path_key` and path-bearing error text are removed or redacted (`<path>`); 503 error details are redacted for everyone (they contain the user name).
- **`token_sha256` is never returned by any endpoint**, including diagnostics on the PC itself.
- **`/health` for an unpaired remote caller** returns only `app`, `version`, `status`, `ollama`, `llm_ready`, `remote`, `authorized` and `companion_enabled`: no model names, document counts, setup state or internet state.
- Unchanged: all admin actions stay local-only, and requests through tunnels or proxies (forwarding headers) count as remote.

**Tests** (`test_companion_privacy.py`):
- the 7 sensitive GETs × {no token → 401, wrong → 401, valid → 403}
- **every route in the app** with a valid phone token: allow-listed routes are not refused as local-only, and every other route answers 403
- no response a phone can get contains the token hash, the home folder or the data root; `/knowledge/files` has no `path_key`, and errors are redacted
- unpaired `/health` is minimal; local admin still works; the token hash is absent locally too
- Existing companion tests are unchanged and pass.

Headers and alternate routes: the default `uvicorn` server header reveals no host data. `/docs`, `/redoc` and `/openapi.json` are local-only for phones. 422 validation errors only echo the caller's own input.

## B3: honest phone status

`frontend/app.js` `connectionStatus()` returns one of these, together with a banner that explains what to do:

| State | Status bar |
|---|---|
| Not paired (no token) | Not paired |
| Token wrong or revoked | Not paired · access token rejected |
| Companion off on the PC | Companion access is off on your computer |
| Paired, PC ready | AI ready · runs on your computer |
| Paired, Ollama down on the PC | Computer connected · Ollama not running |
| Paired, model missing | Computer connected · AI model not installed |
| Token saved, PC never reached | Paired · your computer isn't reachable |
| Was connected, now unreachable | Connection to your computer lost · retrying |
| Phone has no network | Offline mode |

- The AI only counts as available when `ollama && llm_ready && authorized !== false`.
- `/health` is fetched **with** the token, so the PC can say whether this device is paired.
- Any 401 from another call triggers a re-check.
- The System panel on a phone explains that system details and backups are on the PC, instead of showing a raw 403.
- The old text "AI ready · via your computer" is gone.

**Tests** (`test_pwa_behaviour.py`): the real `connectionStatus()` and `readPairingLink()` functions are extracted from `app.js` and run in Node with stubs (14 status scenarios). These are logic tests, **not** a browser or real-device test.

## F1: build paths in bytecode
- **Cause:** `build.py` skipped only the *top-level* `__pycache__`; `copytree` copied the 984 nested ones (9,450 `.pyc` files recording `D:\ai_tech_pulse\…` and `D:\Abhify.AI\…`, plus 184 pytest caches).
- **Fix:**
  - `copytree(..., ignore=ignore_patterns("__pycache__", "*.pyc", "*.pyo"))`
  - `pip install --no-compile` on the online path
  - `compileall -s <site-packages> -p Abhify.AI\runtime\Lib\site-packages` with the bundled runtime
  - the import check runs with `-I -B`
  - `check_no_build_paths()` **fails the build** if any `.pyc` contains the source folder, the staging folder, the home folder or the dev-venv path
- **Result on the dev.3 payload** (independent scan, 20,774 files): **0** build-path hits, **0** personal-string hits, 9,267 `.pyc` files (all neutral). The 184 pytest assertion-rewrite caches are gone; the 5 remaining files named `*pytest*` are those libraries' own plugin modules.

## F2: pairing link while the app is open
`hashchange` re-runs `readPairingLink()`, and a `storage` listener makes other open windows of the PWA pick up a new token. The success toast only appears once `/health` reports `authorized`. A rejected link says so.

The desktop launcher is the PC host and never needs pairing. A second launch already hands over to the running instance (single-instance check), so no token ever passes through the launcher.

## F3: tokens in logs
`backend/log_redaction.py` is installed on the `uvicorn`, `uvicorn.access`, `uvicorn.error` and `abhify` loggers and on every root handler (console and `abhify.log`). It does two things:
- access-log targets keep the path and parameter names, but every value is replaced with `***`
- any message has bearer tokens, `Authorization` / `X-Abhify-Token` values and `token=` / `access_token=` / `pair=` / `key=` / `password=` / `secret=` values masked

The launcher still disables the access log entirely. End-to-end test: a real `python -m uvicorn main:app` dev server with the access log **on** received `?token=<secret>`, `?access_token=<secret>` and `Authorization: Bearer <secret>`. The secret appeared in neither the console nor `abhify.log`, and the access line read `/memory?token=***`.

## F6: installation guide
`packaging/windows/md2html.py` (no dependencies) converts `docs/WINDOWS_INSTALLATION.md` into a self-contained `INSTALL.html`: no scripts, escaped HTML, dark-mode aware, dead `.md` links turned into text. The NSIS finish page opens it; upgrades and uninstalls delete the old `INSTALL.md`.

## Versioning and output
- `backend/version.py` and the service-worker cache name are both **2.0.0-dev.3**. The installer version resource reads ProductVersion/FileVersion `2.0.0-dev.3`. The CHANGELOG has a dev.3 section, and the README status matches. Consistency is enforced by `test_version_is_consistent`.
- `build.py` now writes to `dist\windows\<version>\`. The dev.2 installer and its `SHA256SUMS.txt` in `dist\windows\` are untouched (SHA-256 still `9e299def…52f8b2`, checked before and after the build).

## Installer artifact (dev.3)

| Item | Value |
|---|---|
| Path | `dist\windows\2.0.0-dev.3\Abhify.AI-Setup-2.0.0-dev.3.exe` |
| Size | 128,987,754 bytes (123.0 MiB) |
| SHA-256 | `ccbd42f90d73addd5a3464b879a7a94a4974881d4441b8a94842771065e057e3` (matches `SHA256SUMS.txt`) |
| Signature | **NotSigned** (`Get-AuthenticodeSignature`) |
| Build | `py -3.11 packaging\windows\build.py --site-packages-from backend\.venv\Lib\site-packages` (offline path; the online path is still NOT TESTED) |
| Provenance | Built from the working tree just before `cbba504`; all **34 app files** in the installer staging and the generated `INSTALL.html` are byte-identical (ignoring line endings) to `cbba504` |
| Build checks | DLL check ✓, compileall ✓, runtime import (`-I -B`) ✓, bytecode build-path check ✓, `check_package.py` 0 problems ✓ |
| Payload | 20,774 files, 674.3 MB uncompressed |
| Secrets scan | 0 real hits. 8 pattern hits, all false positives: `sk-` inside words such as "aster**isk-**linking", "ta**sk-**is", "di**sk-**where"; base64 font data in Pillow; CSS keywords in `primp.pyd`. `.pem` files are only the public CA bundles (certifi, grpc); the `.onnx` files are Silero VAD and onnxruntime samples |
| User data, databases, documents, logs, model weights | none |

## Automated tests

```
backend\.venv\Scripts\python.exe -m pytest backend\tests -q -p no:cacheprovider -rs
```
| Run | Result | Time |
|---|---|---|
| 1 | **196 passed**, 0 failed, 0 skipped, 0 errors | 81.3 s |
| 2 | **196 passed**, 0 failed, 0 skipped, 0 errors | 67.5 s |
| 3 | **196 passed**, 0 failed, 0 skipped, 0 errors | 75.1 s |

This is up from 145 tests at dev.2. Node.js was available, so the PWA logic tests ran instead of skipping. The staged runtime existed, so the bytecode check ran against the real payload.

## Windows Sandbox (clean machine, real installer)

Windows 11 Enterprise 10.0.22621, **no Python, no Ollama, networking disabled**, 6 GB RAM, no GPU. The installer was mapped read-only and its SHA-256 re-checked inside the Sandbox. Results were saved in `build\sandbox-results` (clean run) and `build\sandbox-results-dev3-upgrade` (upgrade run).

**Clean install** (`run-sandbox-test.ps1`): **28/28 PASS**

| Step | Result | Evidence |
|---|---|---|
| installer checksum | PASS | `ccbd42f9…e057e3` |
| silent per-user install, no admin prompt | PASS | 33 s |
| Start Menu + desktop shortcuts, uninstall entry | PASS | DisplayVersion `2.0.0-dev.3` |
| **`INSTALL.html` installed, no `INSTALL.md`** (F6) | PASS | |
| bundled runtime imports; ChromaDB + CTranslate2 native libs | PASS | chroma rows 1, ctranslate2 4.8.3 |
| **no build paths in bytecode** (F1) | PASS | 9,267 `.pyc`, 0 with build paths |
| app starts; missing Ollama reported | PASS | `version 2.0.0-dev.3`, `status degraded`, `ollama false` |
| data folder outside the install folder | PASS | `%LOCALAPPDATA%\AbhifyAI` |
| diagnostics on unknown hardware (CPU fallback) | PASS | 0 GPUs, 6 GB → **Minimal** |
| **diagnostics contain no `token_sha256`** (B2) | PASS | |
| no model download without consent | PASS | HTTP 400 |
| PWA served | PASS | HTTP 200 |
| **unpaired remote `/health` is minimal** (B2/B3) | PASS | only app, version, status, ollama, llm_ready, remote, authorized, companion_enabled |
| **valid phone token can't read system routes** (B2) | PASS | `/system/diagnostics`, `/backup`, `/setup/profiles`, `/setup/models/pull`, `/system/update-check`, `/docs` → all 403 |
| valid phone token can use memory; wrong token → 401 | PASS | |
| no token in the logs (F3) | PASS | *weak check here: the launcher disables the access log; the dev-server test above is the real F3 evidence* |
| single instance; clean stop; backup command | PASS | pythonw 1 → 1 |
| uninstall removes the program, keeps data, removes the entry | PASS | |
| reinstall finds the memory again | PASS | memories=1 |

**Real upgrade dev.2 → dev.3** (`run-sandbox-test.ps1 -UpgradeFrom 2.0.0-dev.2`): **34/34 PASS**. The 6 upgrade steps are below; the other 28 steps are the same as the clean run, all PASS.

| Step | Result | Evidence |
|---|---|---|
| dev.2 installed and running, memory added | PASS | `/health` version `2.0.0-dev.2` |
| dev.3 installer run **while dev.2 is running** | PASS | `2.0.0-dev.2 → 2.0.0-dev.3` in 44 s |
| pre-upgrade backup created | PASS | `abhify-backup-pre-upgrade-*.zip` |
| old `INSTALL.md` removed | PASS | |
| dev.3 starts | PASS | `/health` version `2.0.0-dev.3` |
| memory from dev.2 preserved | PASS | 1 memory, identical text |

This is the first **installer-to-installer upgrade between different versions**; the dev.2 audit had only tested the same version over itself. It ran in Windows Sandbox, not on a physical PC.

## Real-LAN companion test (isolated server, temporary data)
An isolated dev.3 server listened on `0.0.0.0:8410` with a temporary data folder (deleted afterwards). Requests went to the PC's private LAN address, so the server saw a genuine non-loopback client.

| Check | Result |
|---|---|
| Companion off: `/memory` | PASS: 403. `/health` minimal (`authorized false`, `companion_enabled false`) |
| `POST /companion/enable` from the LAN | PASS: refused (401 without a token) |
| `/system/diagnostics`, `/backup`, `/setup/profiles`, `/setup/models/pull`, `/system/update-check`, `/docs`, `/openapi.json` with none / wrong / valid token | PASS: 401 / 401 / **403** for every one |
| `POST /setup/test` with a valid token | PASS: 403 |
| `/memory` with a valid token | PASS: 200 |
| 8 allowed endpoints with a valid token scanned for the home folder, data folder, `USERNAME\` and `token_sha256` | PASS: none found; knowledge folder shown as `Abhify_Knowledge` |
| `?token=<secret>` request and the access token in server and app logs | PASS: absent; access line `/memory?token=***` |

## Not tested / limitations
- **Second physical PC:** NOT TESTED (none available). See `PHYSICAL_PC_ACCEPTANCE_TEST.md`.
- **Real Android or iPhone:** NOT TESTED (no devices). See `MOBILE_ACCEPTANCE_TEST.md`.
- **Model download and inference on a clean PC:** NOT TESTED (the Sandbox has no network).
- **Online build path** (`build.py` without `--site-packages-from`): NOT TESTED (slow internet).
- **SmartScreen / Defender on a downloaded (Mark-of-the-Web) copy:** NOT TESTED.
- The installer is **unsigned**.
