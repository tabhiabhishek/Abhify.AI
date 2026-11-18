# Abhify.AI internal 5.5-dev.1 build report

| | |
|---|---|
| File | `D:\Abhify.AI\build\internal\Abhify-5.5-dev.1.apk` (new file, nothing overwritten) |
| Size | 74,392,641 bytes |
| SHA-256 | `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e` |
| Package | `ai.abhify.android` |
| versionName / versionCode | `5.4-internal.5.5-dev.1` / 5040099 (unchanged, so it installs over 5.4 or 5.4-dev.1 without losing data) |
| Signing | APK Signature Scheme v2, existing debug certificate `CN=Android Debug`, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f` (same as every earlier build; no key created, copied or moved) |
| Permissions | the same 10 as 5.4 (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, the app's own dynamic-receiver permission) |
| Database | ChatDb v17, unchanged |

## Earlier builds after this build (unchanged)
- `build/Abhify-5.4.apk`: `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330`
- `build/internal/Abhify-5.4-dev.1.apk`: `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354`
- 5.3, 5.1 and 5.0 are kept in `build/`.

## Install (manual)
1. Copy the APK to the phone (USB, Drive, or a chat with yourself).
2. Open it on the phone and tap **Update**. The version code is the same and the signature matches, so your data stays.
3. Do **not** uninstall first, because uninstalling deletes app data.
4. Run checklist tests 53–60 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md). Tests 1–52 are still worth repeating if they have not been done yet.

## Version note
A real 5.5 upgrade (versionName "5.5", versionCode 5050099) needs the owner's approval. Until then, the suffix marks this as an internal build.
