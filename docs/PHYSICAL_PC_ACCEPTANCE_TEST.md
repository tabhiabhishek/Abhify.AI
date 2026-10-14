# Acceptance test on a second physical Windows PC: Abhify.AI 2.0.0-dev.2

**Status: NOT EXECUTED. No second physical PC was available in this session.**

Every row below is **NOT TESTED** on physical hardware. The closest *proxy* evidence (Windows Sandbox, a clean virtual Windows 11 without network) is listed for reference only; it does **not** replace this test. Run this procedure on a real Windows 10 or 11 PC and fill in the Result column.

## Test record (to fill in)
| Field | Value |
|---|---|
| Tester / date | |
| PC model, CPU, RAM, GPU | |
| Windows edition and build (`winver`) | |
| Account type | Standard user (not admin) |
| Free disk on C: | |
| Internet | yes / no |

## Rules
- Use a **standard (non-admin) Windows account** created for this test, for example `abhify-test`. This gives a disposable profile; delete the account afterwards.
- **Don't copy** your development database, `%LOCALAPPDATA%\AbhifyAI`, memories or knowledge files to the test PC.
- **Don't disable** Defender or SmartScreen. If SmartScreen warns, record it, then use **More info → Run anyway** only after the checksum matches.
- Only delete files that this test created.

## Procedure and results

| # | Step | Expected | Result | Notes / proxy evidence |
|---|---|---|---|---|
| 0 | Get the checksum **independently**: from the release page or a separate channel, not from the same download folder. Run `Get-FileHash .\Abhify.AI-Setup-2.0.0-dev.2.exe` | `9e299def998a379db51f8a10dbe363ad38f7ea8091c437a52a5434166752f8b2` | NOT TESTED | Verified on the build PC |
| 1 | Record Windows version, RAM and free disk; note any SmartScreen or Defender prompt when running the installer | Win10/11 x64. Unsigned → SmartScreen "unknown publisher" warning likely | NOT TESTED | Installer is **NotSigned** |
| 2 | Install as the standard user, with the desktop shortcut ticked | No UAC prompt; installs to `%LOCALAPPDATA%\Programs\Abhify.AI` | NOT TESTED | Sandbox: PASS (32 s) |
| 3 | Check the Start Menu entries (Abhify.AI, Stop Abhify.AI, Uninstall) and the desktop shortcut | All present, Abhify.AI icon | NOT TESTED | Sandbox: PASS |
| 4 | Start from the Start Menu, then run `Get-Process pythonw` | Browser opens the app; exactly **1** `pythonw` | NOT TESTED | Sandbox: PASS |
| 5 | With Ollama **not** installed, look at the setup wizard | "Ollama isn't running", with install/start guidance; no crash | NOT TESTED | Sandbox: `ollama:false`, `degraded` |
| 6 | Click profiles without confirming downloads; watch Task Manager → Network and `%USERPROFILE%\.ollama` | Nothing downloads without the confirm dialog | NOT TESTED | Sandbox: HTTP 400 without consent |
| 7 | Install Ollama from ollama.com, then Re-check; approve the **recommended** profile's download; run the test | Size shown before consent; download completes; test answers | NOT TESTED | **Never tested on a clean PC** (the sandbox has no network). Record any failure exactly |
| 8 | Create `%USERPROFILE%\Abhify_Knowledge\sample.pdf` with harmless text (e.g. "The test codeword is BLUE-HERON-9"), start watching, ask the chat for the codeword | File ✅ indexed; answer cites `sample.pdf` | NOT TESTED | Dev PC (real models): PASS |
| 9 | Edit the PDF's text; rename it; delete it; add a corrupt `.pdf` and use Retry; click Rescan | Only the new text is found; rename keeps 1 chunk; delete removes it; corrupt file ❌ with reason; retry works | NOT TESTED | Dev PC: PASS |
| 10 | Quit from the tray, start again | Chat history, memories and the index are still there | NOT TESTED | Dev PC: PASS |
| 11 | Start Abhify.AI twice; then run Start Menu → **Stop Abhify.AI** | Second start only opens the window; Stop ends the app (`pythonw` gone) | NOT TESTED | Sandbox: PASS |
| 12 | **Upgrade:** first Settings → System & data → **Export** (backup zip). Then run the **next** version's installer (or the same version, as a minimum) | `abhify-backup-pre-upgrade-*.zip` is created; data is preserved | NOT TESTED | Sandbox (same version over a running app): PASS. **A real N-1 → N upgrade has never been tested** |
| 13 | Uninstall, answering **No** to deleting data; check that `%LOCALAPPDATA%\AbhifyAI` still exists; reinstall | Program removed, data kept; after reinstall, memories and history are back | NOT TESTED | Sandbox: PASS |
| 14 | `Get-NetTCPConnection -State Listen -OwningProcess (Get-Content $env:LOCALAPPDATA\AbhifyAI\run\backend.json \| ConvertFrom-Json).pid`; from a **second device** on the same Wi-Fi, try `http://<pc-ip>:8000` | Listens on **127.0.0.1 only**; the second device can't connect | NOT TESTED | Sandbox: only `127.0.0.1:8000` |
| 15 | GPU PC only: during a chat, check Task Manager → GPU, or `ollama ps` | Model runs on the GPU | NOT TESTED | |

**Cleanup:** uninstall and choose **Yes** to delete data (only the test account's data), uninstall Ollama if it was installed only for this test, then delete the test Windows account.

## Defects found so far that affect this test (from the readiness audit)
- B1: watcher start/stop deadlock-until-timeout (could appear when changing folders quickly).
- F6: the "Open the installation guide" checkbox opens a `.md` file.
- F4: the installer is unsigned (SmartScreen warning expected).
