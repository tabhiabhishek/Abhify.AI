# Acceptance test on a second physical Windows PC

## Status after the final acceptance gate (2026-10-16): **still NOT EXECUTED**
- No second physical PC was available, so every physical step is still **NOT TESTED**. See `FINAL_ACCEPTANCE_DEV3.md` §4.
- **Use 2.0.0-dev.4 for this test, not dev.3:**
  - installer `dist\windows.0.0-dev.4\Abhify.AI-Setup-2.0.0-dev.4.exe`
  - SHA-256 `1f0a61ca60c2d30ea37ab4286d6aba7bdfe5347ef8466e4e45614a348d36b9ee`
  - unsigned
- Reason: dev.3 can fail to start where Windows reserves ports 8000–8010 (D4), and has the model-setup issues D1 and D3. All three are fixed in dev.4.
- **Step 12 (upgrade):** install dev.3 first, then run dev.4 over it. In Windows Sandbox this passed 34/34.
- **Step 7 (model download):** clean-VM evidence: dev.4 22/22, including an interrupted download, a network error and CPU inference.
- **New check:** if the app opens on a port other than 8000, run `netsh interface ipv4 show excludedportrange protocol=tcp` and record the reserved ranges. The app should still start, and keep that port next time.

## Status for 2.0.0-dev.3 (2026-10-16): **NOT EXECUTED**
No second physical PC was available in this session either, so **every step below is still NOT TESTED on physical hardware**. Use the **dev.3** installer for this test, not dev.2:

| Item | Value |
|---|---|
| Installer | `Abhify.AI-Setup-2.0.0-dev.3.exe` (from `dist\windows\2.0.0-dev.3\`) |
| Expected SHA-256 (step 0) | `ccbd42f90d73addd5a3464b879a7a94a4974881d4441b8a94842771065e057e3` |
| Signature | NotSigned (SmartScreen warning expected) |
| Finish-page guide | now `INSTALL.html` (F6 fixed) |

Changes to the procedure and proxy evidence since dev.2:
- **Step 12 (upgrade):** a real N-1 → N upgrade (**dev.2 → dev.3, over a running app**) now passes in Windows Sandbox: version changed, pre-upgrade backup created, memory preserved. It still needs doing on physical hardware: install dev.2 first, then run the dev.3 installer.
- **Step 14 (localhost only):** unchanged in behaviour. Companion access is still off by default; with it on, a valid phone token can no longer read system data (B2).
- **New step 16 (optional, needs a phone or a second device):** turn on companion access, then open `http://<pc-ip>:8000/` on the second device **without** pairing. Expected: "Not paired" (not "AI ready"), and Settings → System shows "only available on the computer running Abhify.AI".
- **B1** (the watcher stalling when you change folders quickly) is fixed. During step 9, changing the knowledge folder twice in a row should answer immediately.

Proxy evidence for dev.3 (Windows Sandbox, not a substitute): clean install 28/28 PASS; upgrade dev.2 → dev.3 34/34 PASS (`docs/RELEASE_BLOCKER_FIXES_DEV3.md`).

---

## Original procedure (written for 2.0.0-dev.2; applies to dev.3 with the changes above)

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

## Defects found so far that affect this test (from the readiness audit; status as of dev.3)
- B1: watcher start/stop deadlock-until-timeout (could appear when changing folders quickly). **Fixed in dev.3.**
- F6: the "Open the installation guide" checkbox opened a `.md` file. **Fixed in dev.3 (opens `INSTALL.html`).**
- F4: the installer is unsigned (SmartScreen warning expected). **Still open.**
