# Abhify.AI internal 6.1-dev.1 build report (2026-11-18)

| | |
|---|---|
| File | `D:\Abhify.AI\build\internal\Abhify-6.1-dev.1.apk` (new file; nothing overwritten) |
| Size | 74,497,356 bytes (44 KB smaller than 6.0-dev.1: two unused libraries removed) |
| SHA-256 | `4e8bafeb5bd3754befcbc79960235ee74641ca73d41808abe90766bd3fa69f24` |
| Package | `ai.abhify.android` |
| versionName / versionCode | `5.4-internal.6.1-dev.1` / **5040099**. The code is unchanged, so it installs as an update over 5.4 and every internal build and keeps all data. An official 6.x name/code needs your approval (R10) |
| Signing | APK Signature Scheme v2 verified. Existing debug certificate `CN=Android Debug`, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`. No key was created, copied or moved |
| Permissions | The same 10 as 5.4 and 6.0 (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, the app's own dynamic-receiver permission) |
| Database | ChatDb v17, no schema change |
| Checks before the build | Android `testDebugUnitTest` **437 run / 0 failed / 4 skipped** (6.0: 426) · `lintDebug` **0 errors / 120 warnings** (6.0: 125) |
| Contents | 6.0-dev.1 plus the 6.1 audit fixes (see [ABHIFY_6_1_AUDIT_AND_STATUS.md](ABHIFY_6_1_AUDIT_AND_STATUS.md)) |

## Earlier builds, re-verified after the build (unchanged)
All 11 earlier APKs match the hashes recorded in [ABHIFY_6_1_BASELINE.md](ABHIFY_6_1_BASELINE.md): 5.0, 5.0-androidTest, 5.1, 5.3, 5.4, 5.4-dev.1, 5.5-dev.1, 5.6-dev.1/2/3, 6.0-dev.1.

## Install (manual)
1. Copy `Abhify-6.1-dev.1.apk` to the phone.
2. Open it and tap **Update**. Do **not** uninstall first, because that deletes the app's data.
3. Run checklist tests 81–90 (6.1) and the still-open 61–80 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md).
