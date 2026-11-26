# Installing Abhify.AI 7.2.1

**Packages in this folder:**

| Platform | File | Signing |
|---|---|---|
| Windows (installer) | `windows/installer/Abhify.AI-7.2.1-Setup.exe` | Not signed. Windows SmartScreen may warn: choose **More info → Run anyway** |
| Windows (portable) | `windows/portable/Abhify.AI-7.2.1-Portable.zip` | Not signed |
| Android | `android/Abhify.AI-7.2.1-debug.apk` | Development certificate `1d2d1fdb…` |
| Web | `web/standalone/` (static site) | — |

Check a download before installing: in PowerShell, run `Get-FileHash <file>` and compare the result with `SHA256SUMS.txt`.

The three platforms are **independent installations**. Installing one does not change the others.

---

## Windows desktop

**Requirements:**
- Windows 10/11, 64-bit.
- 8 GB RAM (16 GB recommended).
- About 1 GB for the app, plus model space: 1–9 GB per model.

### Fresh install

1. Run `Abhify.AI-7.2.1-Setup.exe`. It installs for your user only, so no admin rights are needed. The default folder is `%LOCALAPPDATA%\Programs\Abhify.AI`.
2. Abhify.AI starts, and the **setup wizard** opens:
   - it checks your hardware;
   - it offers to install **Ollama** (the local AI engine) if it is missing;
   - it recommends a model.

   Every download shows its size and asks you first.
3. Finished: chat, Study and the rest work offline once a model is installed.

### Upgrade from an earlier version (7.0.x – 7.2.1-dev.x)

1. Run the new installer. You don't need to uninstall first.
2. The installer then does the following:
   - closes Abhify.AI;
   - backs up your data to `%LOCALAPPDATA%\AbhifyAI\data\backups\abhify-backup-pre-upgrade-*.zip`;
   - stops any leftover Abhify.AI process running from the install folder;
   - replaces only the program files.
3. Your chats, memories, documents, study progress, settings and Ollama models are kept.

If old program files still can't be replaced, the installer asks you to close Abhify.AI and press **Retry**. **Cancel** stops the update without changing anything.

> **Upgrading from 7.2.1-dev.1 failed with "Error opening file for writing … runtime\_asyncio.pyd"?** The 7.2.1 installer fixes this. Just run it.

### Portable (no installation)

1. Unzip `Abhify.AI-7.2.1-Portable.zip` anywhere.
2. Run `Start Abhify.AI.vbs` to start the app, and `Stop Abhify.AI.vbs` to stop it.

It uses the same data folder (`%LOCALAPPDATA%\AbhifyAI`) as the installed app. Don't run both at the same time.

### Uninstall

Use **Settings → Apps → Abhify.AI → Uninstall**. It asks whether to also delete your personal data. **No** keeps it for a later reinstall, and a silent uninstall always keeps it. Ollama models and your knowledge-folder files are never deleted.

---

## Android

**Requirements:**
- Android 10 or newer, 64-bit (arm64).
- 6–8 GB RAM recommended.
- About 1.5 GB free for a model.

### Fresh install

1. Copy `Abhify.AI-7.2.1-debug.apk` to the phone and open it. Allow "install unknown apps" for your file manager when asked.
2. The setup wizard checks the device and downloads a model. The download is confirmed, checked against its SHA-256 and tested with a real answer before use.

### Upgrade

Install the new APK over the existing app. It is signed with the same certificate, so data and settings are kept.

> **Do not uninstall first, and do not clear app storage:** both delete everything on the phone.

### Permissions

| Permission | When it's used |
|---|---|
| Internet and network state | Model download, news and optional online AI |
| Notifications | Asked only when you turn on a reminder |
| Microphone | Asked when you first tap the mic |
| Foreground service | Long model downloads |

7.2.1 removed the unused "install packages" permission.

---

## Standalone Web app

Serve the `web/standalone/` folder as a static site, over HTTPS or on `localhost`. For example:

```
cd web/standalone
python -m http.server 8080
```

Then open `http://localhost:8080`.

- **Browser:** needs **WebGPU** (recent Chrome or Edge).
- **AI:** the first chat asks before downloading a model (about 1 GB). The model runs in the browser.
- **Data:** stays in this browser only. Clearing site data deletes it, so use **Study → Today → Export** to keep a copy.
- **Not available on Web:** Knowledge, Projects, Task Runner and live News are Windows-only (§8 of the complete documentation).

---

## Backup and restore

| Platform | Backup | Restore |
|---|---|---|
| Windows | System & data → Backup creates a zip (no secrets) | Restore is applied at the next start, and the previous data is kept aside |
| Android | More → Backup (optional passphrase encryption) | Import from the same screen. It is additive: nothing is deleted |
| Web | Study → Today → Export / Import | Import from the same screen |

## Troubleshooting

| Problem | What to do |
|---|---|
| No AI answer on Windows | Open Models & setup. Check that Ollama is running and a model is installed |
| "No sound reached Abhify.AI" | Check the mic mute key, then Windows **Settings → Privacy → Microphone**. **Settings → Voice → Test microphone** shows the live level |
| SmartScreen warning | Expected for unsigned builds. Check the SHA-256 first |
| Phone says "App not installed" during an upgrade | The APK was signed with a different certificate. Do **not** uninstall; ask for a correctly signed build |

**More information:** `docs/ABHIFY_AI_COMPLETE_DOCUMENTATION.md`.
