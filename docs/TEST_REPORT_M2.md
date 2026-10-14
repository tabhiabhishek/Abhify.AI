# Test report: Milestone 2 (2.0.0-dev.2)

Date: 2026-10-14. Machine: Windows 11 Pro 22631, Python 3.11.9, RTX A2000 12 GB, Ollama 0.40.0 (`qwen2.5` 7B, `nomic-embed-text`).
Branch `feature/m2-knowledge-packaging`. Everything below was **actually executed**; nothing is assumed.

## 1. Automated test suite (pytest)

```
cd D:\Abhify.AI\backend
.venv\Scripts\python.exe -m pytest tests -q -rs
```

**Result: 145 passed, 0 failed, 0 skipped** (57.7 s). Ollama, network and downloads are mocked with deterministic fake embeddings, and every test uses a temporary data folder.

| File | Tests | Covers |
|---|---|---|
| test_indexer.py | 38 | **Indexing:** initial index; unchanged files skipped; modified file replaces chunks; a failed re-index keeps the old version; delete; rename without re-embedding; no duplicates. **Safety and failures:** 12 exclusion rules plus ML-notes false positives; excluded folders; secrets in content; private-key blocks; path traversal and outside files; symlinks; corrupt PDF; empty/unsupported/oversized files; locked file retried; model unavailable with backoff; `.py` and encodings; logs never contain document text |
| test_watcher.py | 11 | Real `watchdog` events: initial scan (`.env` never recorded); new/modified/deleted; rename; **partial writes** (indexed only once complete); single instance per folder; clean thread shutdown; pause/resume; automatic retry after the model returns; manual retry; deletions while stopped; folder policy (drive, home, AppData, data dir, relative paths) |
| test_rebuild.py | 5 | Model change blocks mixing; atomic rebuild keeps the old collection; a failed rebuild leaves the old index active; M1 collection adoption; uploads rebuilt |
| test_companion_updates.py | 15 | Remote API blocked when off; token required and only its hash stored; **admin stays local-only even with a token**; tunnel (forwarded) requests count as remote; disable revokes; every API route guarded; update check (not configured, HTTP refused, offline, manifest validation ×5); **v3→v4 upgrade preserves chats, memories and document index**; Whisper CUDA→CPU fallback |
| test_packaging.py | 16 | Launcher binds localhost unless companion is on; port fallback; single-instance detection; pre-upgrade backup (and no-op on a fresh machine); package check rejects `.env`, databases and secrets; build allow-list; installer is per-user with no admin; upgrade order is stop → backup → replace; **uninstall keeps data by default**; no model downloads; Win10+ and x64 checks; proxy bypass for loopback; no-browser option; DLL checker; **staged runtime has no unmet DLL dependencies** |
| test_pwa.py | 10 | Manifest identity; icons exist at their declared sizes; maskable icon is full-bleed; service-worker precache files exist; **the SW never caches any backend API route** (checked against the live route table); SW ignores non-GET and cross-origin requests and cleans old caches; mobile-ready HTML/CSS; offline fallback; no secrets in the frontend; mobile docs cover Android, iPhone, HTTPS and Modes A/B/C |
| test_api.py, test_database.py, test_local_files.py, test_memory.py, test_system.py | 50 | M1 regression suite (unchanged behaviour), plus same-story news detection |

## 2. Live test with real models (isolated server, port 8100, temporary data)

| Step | Result |
|---|---|
| Watch `D:\Abhify_LiveTest_M2`; add a real PDF (printed by Edge), a `secrets_notes.txt` and a `.env` | PDF **indexed** (1 chunk, `nomic-embed-text`); both others **skipped, never read** |
| Chat: "What is the secret codeword in my transformer notes?" | `knowledge_base_search` → **PURPLE-GIRAFFE-42**, source `transformer_notes.pdf, page 1` |
| Replace the PDF with a new version | 1 chunk; only the new codeword present |
| Rename the file | 1 chunk under the new name |
| Delete the file | 0 chunks |
| Knowledge panel at 390 px with a corrupt PDF | Corrupt PDF shown ❌ with reason and retry; 0 px overflow; no JS errors |
| The folder policy refused a folder in `AppData` | Working as designed. It also created an empty default `%USERPROFILE%\Abhify_Knowledge`, which was removed again |

## 3. Clean-machine installation test: Windows Sandbox

```
py -3.11 packaging\windows\build.py --site-packages-from backend\.venv\Lib\site-packages
powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1
```

Environment: fresh Windows 11 Enterprise 22621 in Windows Sandbox; **no Python, no Ollama, networking disabled**, 6 GB RAM, no GPU.

| Run | Result | What it found |
|---|---|---|
| 1 | 13/18 | **The Visual C++ runtime was missing** (`msvcp140.dll`), so ctranslate2 (voice), onnxruntime and **ChromaDB's core** could not load. **Single-instance failure:** a second launch started a second server, which then caused the stop/uninstall/reinstall steps to fail |
| 2 | 16/19 | The VC++ runtime fix works. Remaining: two mistakes in the test script, plus a real bug: `/health` took ~3 s while offline (blocking internet probe), so the launcher's 2 s check thought the app was dead |
| **3** | **19/19** | All pass (table below) |

Run 3 details:

| Step | Detail |
|---|---|
| install_silent | 29 s, no admin prompt |
| start_menu_shortcut, desktop_shortcut, uninstall_entry | DisplayVersion 2.0.0-dev.2 |
| runtime_imports, native_libs_work | ChromaDB writes a row; ctranslate2 4.8.3 loads |
| app_starts, missing_ollama_reported | `status: degraded`, `ollama: false`, `internet: false` |
| data_dir_outside_install | `%LOCALAPPDATA%\AbhifyAI` |
| diagnostics | 0 GPUs, 6 GB RAM → **Minimal** recommended (CPU fallback) |
| no_download_without_consent | HTTP 400 without `confirm` |
| pwa_served, single_instance, clean_stop, backup_command | |
| uninstall_removes_program, uninstall_keeps_data, uninstall_removes_entry | |
| reinstall_preserves_memory | The memory written before uninstall is back after reinstall |

**Fixes that came out of the clean-machine test:**
- The build now bundles the VC++ runtime DLLs, and `dll_check.py` **fails any build** whose binaries need a DLL that is neither bundled nor part of Windows.
- A new instant `/ping` endpoint is used by the launcher.
- The internet probe no longer blocks after the first check.
- Loopback calls bypass system proxies.
- The launcher waits for a live process before declaring it dead.

## 4. Browser regression of the whole UI (Edge via Playwright, isolated server, port 8300)
Setup wizard (3 profiles, live inference test 14.1 s on a cold model), memory panel, system panel and backup, chat with tool badge, chat history after reload, service worker active, offline banner (and wizard hidden while offline): 0 px overflow at 390 px and 1280 px, **no JS errors**.

## 5. Real user data
The app on port 8000 was restarted with M2. **Migration v4 was applied to the real database after an automatic backup** (`abhify-pre-schema-v4-20261014-163740.db`); health is OK and setup choices were kept.

## Not tested (needs real hardware or accounts)
- Installing on a second **physical** PC; behaviour with a real NVIDIA GPU after install; model download through the wizard on a clean PC (the sandbox has no network).
- **Upgrading from a previous installer.** This is the first installer, so the upgrade path is covered by the pre-upgrade backup test and the v3→v4 migration test, not by a real installer-over-installer upgrade.
- SmartScreen/antivirus behaviour (the installer is **unsigned**).
- Phones: Android Chrome and iPhone Safari installation over Tailscale HTTPS (no devices available here).
- The online build path (`pip install` from `requirements.txt`). The network here was ~10 KB/s, so the offline `--site-packages-from` build was used.
