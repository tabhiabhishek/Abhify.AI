# Abhify.AI 3.2.0: installation

| Item | Value |
|---|---|
| APK | `D:\Abhify.AI\build\Abhify-3.2.0-debug.apk` (68,976,516 bytes, **debug build**) |
| SHA-256 | `C357DE0C37E662FD05B3DA1B4BD730F29870EE60C41F07B8C2CDEE7BAD5CE9E8` |
| Application ID / version | `ai.abhify.android`, 3.2.0, versionCode 3020099 (higher than 3.1.0's 3010099) |
| Signing | Same debug certificate as 3.1.0, 3.0.0 and RC.4 (`1d2d1fdb…6246f`) → **in-place upgrade, data kept** |

## Install over the current app (keeps everything)

1. Turn on USB debugging on the phone and connect it.
2. Check the file before installing:
   ```
   Get-FileHash "D:\Abhify.AI\build\Abhify-3.2.0-debug.apk" -Algorithm SHA256
   ```
   The hash must equal the SHA-256 above.
3. Install over the existing app:
   ```
   adb install -r "D:\Abhify.AI\build\Abhify-3.2.0-debug.apk"
   ```

**Never** run `adb uninstall` and never clear the app's data. An uninstall deletes chats, documents, keys and the downloaded model.

If adb reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the APK was signed with another key. **Stop and don't uninstall**; check the hash again.

## What the upgrade does

- **Database v6 → v7, additive:** five new tables. Nothing existing changes.
- **Not touched:** the model, keys, settings and the onboarding flag. Setup is not shown again.
- **Navigation is reorganised:**

| 3.1.0 | 3.2.0 |
|---|---|
| Chat | Chat |
| Learn | Study (Tutor, Plans, Cards, Quiz, News, plus the new study assistant) |
| Documents | Documents |
| Work | Knowledge → Workspaces |
| Learn → Interview | Career → Question bank |
| Settings tab | the ⚙ button in the header |

## Device testing

Status: **PENDING.** No device was connected when this build was made.

Run `RUN-TESTS.cmd` from `D:\Abhify.AI\build\Abhify-Android-Test-v3.2.zip`. It runs the migration, PDF and OCR benchmark tests, which don't touch your data. Then work through the checklist in the kit.
