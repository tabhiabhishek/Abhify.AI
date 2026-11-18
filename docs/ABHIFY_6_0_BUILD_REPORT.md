# Abhify.AI internal 6.0-dev.1 build report

| | |
|---|---|
| File | `D:\Abhify.AI\build\internal\Abhify-6.0-dev.1.apk` (new file; nothing overwritten) |
| Size | 74,541,281 bytes |
| SHA-256 | `a1f4567990bfd1ef5438c26d194c30fda646cd5674482bd1fb9e3eef8396adb0` |
| Package | `ai.abhify.android` |
| versionName / versionCode | `5.4-internal.6.0-dev.1` / 5040099 (unchanged, so it installs as an update over 5.4 and every internal build and keeps data) |
| Signing | APK Signature Scheme v2, existing debug certificate `CN=Android Debug`, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`. No key was created, copied or moved. |
| Permissions | The same 10 as 5.4 (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, the app's own dynamic-receiver permission) |
| Database | ChatDb v17, unchanged |
| Checks before the build | Android 426 run / 0 failed / 4 skipped · lint 0 errors / 125 warnings · backend 443 passed / 2 skipped |
| Contents | Everything in 5.6-dev.3, plus the 6.0 Personal AI Brain: the turn planner; Quick, Deep think, Planning and Creative modes with real behaviour; Research with dated web evidence; honest fresh-information handling; route advice |

## Earlier builds, re-verified after the build (unchanged)
| APK | SHA-256 |
|---|---|
| `build/internal/Abhify-5.6-dev.3.apk` | `189e49e1dfa7205b44e21d802318bb9497032618e5c9524e66bf00a7f7711921` |
| `build/internal/Abhify-5.6-dev.2.apk` | `e1df68bc1607590255a3dd81a5f356c9ed6ab23279d23ddc70b62a474757620c` |
| `build/internal/Abhify-5.6-dev.1.apk` | `fa7aa2fe4998df79f80c7bdd40002190268c9bffeb904a6c4ecbd72b80706416` |
| `build/internal/Abhify-5.5-dev.1.apk` | `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e` |
| `build/internal/Abhify-5.4-dev.1.apk` | `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354` |
| `build/Abhify-5.4.apk` | `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` |
| `build/Abhify-5.3.apk`, `5.1`, `5.0` | `778e5c50…7bd5`, `659053fc…cd9f`, `746150e5…5725` |

## Install (manual)
1. Copy `Abhify-6.0-dev.1.apk` to the phone.
2. Open it and tap **Update**. Do **not** uninstall first, because that deletes app data.
3. Run checklist tests 61–80 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md).
