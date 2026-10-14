# Installing Abhify.AI on Windows

## Requirements

| | Minimum | Notes |
|---|---|---|
| Windows | 10 or 11, 64-bit | The installer refuses older or 32-bit Windows |
| Disk | ~1.5 GB for the app | Plus 1.3–9 GB for AI models, chosen later in the app |
| RAM | 8 GB | 16 GB recommended for the Balanced profile on CPU |
| GPU | **Not required** | An NVIDIA GPU makes answers much faster (see below) |
| Admin rights | **Not needed** | Installs for the current user only |
| Ollama | Required for AI features | Free; the app's setup wizard links to the official installer |

## Install

1. Run **`Abhify.AI-Setup-<version>.exe`**.
   - Windows SmartScreen may warn "Windows protected your PC", because the installer is not code-signed yet. Click **More info → Run anyway** only if you got the file from a source you trust.
   - Check its SHA-256 against `SHA256SUMS.txt` first: in PowerShell, run `Get-FileHash .\Abhify.AI-Setup-<version>.exe`.
2. Choose whether you want a desktop shortcut, then click **Install**.
   - Default location: `%LOCALAPPDATA%\Programs\Abhify.AI`.
3. Click **Finish** with "Start Abhify.AI now" ticked. Your browser opens the app, and a tray icon appears near the clock.
4. The **setup wizard**:
   - checks your PC (RAM, GPU, disk, Ollama)
   - helps you install Ollama if it's missing
   - recommends a model profile
   - downloads models **only after you confirm** (sizes are shown first)
   - runs a quick test

You can skip it and use limited mode.

### CPU or GPU?

- **CPU only:** works. Pick **Minimal** (~1.3 GB) on laptops, or **Balanced** with 16 GB RAM. Answers take several seconds to a minute.
- **NVIDIA GPU (6 GB+ VRAM):** Ollama uses it automatically, and answers take seconds. No particular CUDA version is needed; Ollama brings its own.
- Voice transcription (Faster-Whisper) uses the GPU when available and falls back to the CPU automatically.

## Using the app

| Action | How |
|---|---|
| Start | Start Menu → **Abhify.AI** (or the desktop shortcut). If it's already running, this just opens the window |
| Stop | Tray icon → **Quit Abhify.AI**, or Start Menu → **Stop Abhify.AI** |
| Open data / knowledge folder | Tray icon menu |
| Port | 8000, or the next free port up to 8010 if 8000 is busy |

The app listens on **127.0.0.1 only**. Other devices can reach it only if you turn on **Companion access** (see MOBILE_INSTALLATION.md).

## Where things are

| What | Where | Removed by uninstall? |
|---|---|---|
| Program files | `%LOCALAPPDATA%\Programs\Abhify.AI` | Yes |
| Your data (chats, memories, progress, settings, document index, backups, logs) | `%LOCALAPPDATA%\AbhifyAI` | **Only if you say Yes** when asked |
| Your knowledge folder | `%USERPROFILE%\Abhify_Knowledge` (or the folder you chose) | **Never** |
| AI models | Ollama's folder (`%USERPROFILE%\.ollama\models`) | **Never** (uninstall Ollama separately) |

## Upgrade

Run the newer installer. It:
1. stops Abhify.AI if it's running
2. **backs up your data** to `%LOCALAPPDATA%\AbhifyAI\data\backups\abhify-backup-pre-upgrade-<time>.zip`
3. replaces only the program files

On the next start, any database migration also takes its own automatic backup (`abhify-pre-schema-vN-*.db`). Models and settings are never overwritten.

**If an upgrade goes wrong:**
1. Reinstall the previous version's installer. Your data folder is untouched.
2. If the database was already migrated by the newer version, the older app refuses to open it (by design). Restore the pre-upgrade backup instead: Settings → System & data → **Restore…**, choose `abhify-backup-pre-upgrade-*.zip`, then restart.
3. Your current data is kept in `data\backups\pre-restore-<time>\`, never deleted.

## Uninstall

Settings → Apps → **Abhify.AI** → Uninstall, or Start Menu → **Uninstall Abhify.AI**.
- You'll be asked whether to also delete your personal data. The default is **No** (keep it).
- A silent uninstall (`Uninstall.exe /S`) always keeps your data.
- Reinstalling later picks your data up again automatically.

## Troubleshooting

| Problem | Fix |
|---|---|
| Nothing opens after starting | Wait 10–20 s on the first start. Check the tray icon, or open `http://127.0.0.1:8000` yourself. The log is in `%LOCALAPPDATA%\AbhifyAI\logs\launcher.log` |
| "Backend up · Ollama offline" | Start Ollama (Start Menu → Ollama) |
| Antivirus quarantines `pythonw.exe` | Restore it and allow `%LOCALAPPDATA%\Programs\Abhify.AI`. This happens because the installer isn't signed yet |
| Port conflict | Abhify.AI picks 8001–8010 automatically. The tray tooltip shows the port |

## Clean-machine test procedure (for releases)

Windows Sandbox (Windows 10/11 Pro, "Windows Sandbox" feature) gives a fresh Windows with no Python and no Ollama:

1. Run `powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1`. It writes a `.wsb` file that maps `dist\windows` (read-only) and an output folder, then starts Sandbox.
2. Inside Sandbox, `sandbox-test.ps1` runs automatically. It:
   - installs silently
   - checks the files, shortcuts and uninstall registry entry
   - starts the app and checks `/health` (expected: `ollama: false`, because Ollama isn't installed)
   - checks that the data folder is outside the install folder, and runs the backup command
   - uninstalls silently and checks that the data was **kept**
3. Results are written to `build\sandbox-results\result.json`.

Manual checks still needed on real hardware:
- install with an NVIDIA GPU and with CPU only
- download a model through the wizard
- install while a previous version is running (upgrade)
- SmartScreen and antivirus behaviour on an unsigned build
