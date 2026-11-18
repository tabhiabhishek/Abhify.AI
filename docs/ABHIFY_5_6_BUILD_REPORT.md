# Abhify.AI internal 5.6 build report

Three internal 5.6 APKs were built on 2026-11-18. **Install the newest, 5.6-dev.3.** None is a release, and none is published. Older ones are kept and were not overwritten.

| | **5.6-dev.3 (install this)** | 5.6-dev.2 | 5.6-dev.1 |
|---|---|---|---|
| File | `D:\Abhify.AI\build\internal\Abhify-5.6-dev.3.apk` | `…\Abhify-5.6-dev.2.apk` | `…\Abhify-5.6-dev.1.apk` |
| Size | 74,506,621 bytes | 74,510,741 bytes | 74,483,329 bytes |
| SHA-256 | `189e49e1dfa7205b44e21d802318bb9497032618e5c9524e66bf00a7f7711921` | `e1df68bc1607590255a3dd81a5f356c9ed6ab23279d23ddc70b62a474757620c` | `fa7aa2fe4998df79f80c7bdd40002190268c9bffeb904a6c4ecbd72b80706416` |
| versionName | `5.4-internal.5.6-dev.3` | `5.4-internal.5.6-dev.2` | `5.4-internal.5.6-dev.1` |
| Contents | dev.2, plus Hindi/Hinglish voice commands, the voice ERROR state, rate-limit cooldown and one shared loading banner | dev.1, plus the news image root-cause fix, Friendly AI companion layer, streaming and interruptible Talk to Me, and Neural Futuristic tokens | Search 2.0, smarter duplicates, to-do reminders, context finder completion, skill path, provider stats |

| Common | |
|---|---|
| Package | `ai.abhify.android` |
| versionCode | 5040099 (unchanged; installs as an update over 5.4 and every internal build since) |
| Signing | APK Signature Scheme v2, existing debug certificate `CN=Android Debug`, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`. No key was created, copied or moved. |
| Permissions | The same 10 as 5.4/5.5: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, and the app's own dynamic-receiver permission |
| Database | ChatDb v17 unchanged. New side file: `task_reminders.json`, created only when you set a reminder |
| Checks before the dev.3 build | Android JVM tests 420 run / 0 failed / 4 skipped; lint 0 errors / 125 warnings (same count as 5.5) |

## Earlier builds, re-verified after the build (unchanged)
| APK | SHA-256 |
|---|---|
| `build/internal/Abhify-5.5-dev.1.apk` | `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e` |
| `build/internal/Abhify-5.4-dev.1.apk` | `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354` |
| `build/Abhify-5.4.apk` | `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` |
| `build/Abhify-5.3.apk` | `778e5c504e410ada2f582af89492f64d92a324f2220f4344975d01ecc5b57bd5` |
| `build/Abhify-5.1.apk` | `659053fcd7ce8cb7f7ca0bae019d7e4112506c13ae31cb95b49938f1dcb0cd9f` |
| `build/Abhify-5.0.apk` | `746150e5df2fe229f99b3635cb17d6a37cf58edb2480ecb7579db7ab24725423` |

## Why "dev.3"
dev.1 and dev.2 already existed from earlier rounds the same day. The rule is never to overwrite an existing APK, so each new build gets the next number.

## Install (manual)
1. Copy `Abhify-5.6-dev.3.apk` to the phone.
2. Open it and tap **Update**. Your data stays, because the version code and signature are the same.
3. Do **not** uninstall first, because that deletes app data.
4. Run checklist tests 61–75 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md). Tests 1–60 are still worth doing if they haven't been done yet.

## Version note
An official 5.6 (versionName "5.6", versionCode 5060099) needs your approval. It is recommended after the phone checklist passes.
