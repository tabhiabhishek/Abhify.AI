# v4.0: production-readiness evaluation (evaluation only)

**Nothing here was done:**
- no production signing key was created;
- no production APK was signed;
- nothing was published, deployed or committed;
- repository visibility was not changed.

These are **decisions for the owner**.

## Current state (checked in the repository)
| Area | Current state | Ready for production? |
|---|---|---|
| **Application ID** | `ai.abhify.android`, fixed; updates need the same id and key | Yes. Never change it after the first public release. |
| **Signing** | Debug key only: `~/.android/debug.keystore`, SHA-256 `1d2d1fdb…6246f`. `release {}` has **no signing config** on purpose. | **No.** A debug-signed app can't be published on Play, and anyone with the debug key could sign "updates". |
| **Versioning** | versionCode = major·1e6 + minor·1e4 + patch·100 + stage (99 = final). 4.0.0 → 4000099. It increases monotonically and is never reused. | Yes |
| **Build reproducibility** | Gradle 8.14.3, AGP 8.13.2, Kotlin 2.3.0, JDK 17. Dependencies pinned in `libs.versions.toml`. llama.cpp pinned by commit and SHA-256 (FetchContent); NDK 28.2. **Not** verified bit-for-bit reproducible; builds use the developer's local caches. | Partly. Needs a clean CI build and a record of checksums. |
| **Minification** | `isMinifyEnabled = false` for release | Optional. R8 would shrink the APK but needs keep rules (JNI, ML Kit, pdfbox) and a full regression run. |
| **Permissions** | INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO, WAKE_LOCK and RECEIVE_BOOT_COMPLETED (both from WorkManager), plus the app's own dynamic-receiver permission | Mostly. **REQUEST_INSTALL_PACKAGES is restricted on Google Play.** Its use for the signed self-updater would need a justification, or the updater disabled in a Play build. |
| **Network security** | HTTPS only, system CAs, no cleartext (`network_security_config.xml`); the Drive transport refuses non-HTTPS | Yes |
| **Secret handling** | No keys in source or APK; secret scans run on every build. Provider keys, the cloud session and the scheduled-backup passphrase are sealed with Android Keystore AES-GCM. Drive tokens are in memory only. | Yes. Keep the scans in CI. |
| **Data at rest** | The SQLite database is **not encrypted** (it relies on Android app sandboxing). Backups can be encrypted (AES-256-GCM, PBKDF2 210k). | Acceptable for v1, but this must be disclosed. Optional: SQLCipher (new dependency, migration risk). |
| **Android backup** | `allowBackup=false`; data-extraction rules exclude everything | Yes (an intentional privacy choice) |
| **Dependencies** | AndroidX (Compose BOM 2025.12.01, Lifecycle 2.9.4, Work 2.11.2), coroutines 1.10.2, org.json, pdfbox-android 2.0.27.0, ML Kit text-recognition 16.0.1 (bundled; sends usage metrics to Google, disclosed), Play services auth 21.3.0, llama.cpp | Needs a licence inventory (Apache-2.0 / MIT / BSD) and a NOTICE screen. Lint reports newer versions; update after the release candidate is frozen. |
| **Privacy** | Local-first; online AI optional, with consent; no analytics SDK. ML Kit metrics are disclosed. `docs/ANDROID_PRIVACY.md` and the in-app Privacy screen exist. | Needs a published privacy policy URL and the Play Data-safety form. |
| **Crash handling** | No crash reporter and no global exception handler (by design: no telemetry). Errors are shown in the UI. | Optional: an opt-in local crash log the user can export. |
| **Testing** | 201 JVM tests (4 skipped, opt-in); 5 instrumented test classes **never run on a device**; no UI tests | **No.** Physical-device acceptance is still pending for 3.3–4.0. |
| **Google Drive** | Code complete; needs an OAuth client per signing key (debug SHA-1 now, release SHA-1 later); consent screen in "Testing" | **No** for the public: the `drive.file` scope still needs OAuth app verification for >100 users. |
| **Model download** | Qwen GGUF from pinned Hugging Face revisions with SHA-256 checks | Yes; check the Qwen licence terms for redistribution (Apache-2.0 for 1.5B/0.5B) |

## Release checklist (proposal)
1. Physical-device acceptance of the latest debug build on the owner's phone, plus at least one low-RAM phone.
2. Decide the signing strategy (below), create the key **offline**, and back it up in two places.
3. Add a `signingConfig` that reads from environment or a keystore file outside the repository (never committed).
4. Release build: R8 on or off, decided by a full regression run of the release APK.
5. Licence inventory, NOTICE screen, privacy-policy URL, Data-safety form.
6. Decide on REQUEST_INSTALL_PACKAGES and the self-updater for each distribution channel.
7. Register a Drive OAuth client for the release SHA-1, and start verification if Drive will be offered.
8. Record APK and AAB checksums and tag the commit (owner action).

## Distribution options
| Option | Pros | Cons |
|---|---|---|
| **Google Play (internal → closed → production tracks)** | Trusted installs, automatic updates, Play App Signing protects against key loss | Review, policy for REQUEST_INSTALL_PACKAGES, Data-safety form, a developer account |
| **Direct APK (website or GitHub Releases) with the built-in signed updater** | Full control; the app's Ed25519-signed manifest updater already exists | Users must allow "install unknown apps"; you host updates; less trust |
| **Both** | Reach plus flexibility | Must be the same key, or separate application IDs per channel; two update paths |

## Signing strategy (recommendation)
- **Recommended:** **Play App Signing**. Google holds the app signing key; you keep an **upload key**, created offline with `keytool`, 4096-bit RSA, valid 25+ years, stored in a password manager plus an offline copy.
- **If you also distribute APKs directly:** export the app-signing key option at enrolment, or use a separate channel ID.
- **Never** reuse the debug key. Debug installs (3.x) can't be updated in place by a release-signed build: switching to release signing requires uninstalling the debug build. **That would delete local data unless the user first makes a backup** (the 3.4+ backup and restore flow covers this).

## Rollback strategy
- Keep every released APK/AAB with its checksum.
- On Play, use a staged rollout (5% → 20% → 100%) and halt it if crash reports or user feedback rise.
- **Database migrations are additive only** (v2…v15 never drop or rename), so an older app version can't open a newer database. SQLiteOpenHelper refuses a downgrade.
  - Therefore roll **forward** with a fix rather than back.
  - A user-side rollback = backup → install the older version → additive restore.

## Decisions needed from the owner
1. Distribution channel(s): Play, direct, or both.
2. Signing: create an upload key and enrol in Play App Signing? Who holds the key, and where is it backed up?
3. Keep the self-updater (REQUEST_INSTALL_PACKAGES) for the direct channel only?
4. Enable R8 for release?
5. Drive for all users (needs OAuth verification), or keep Drive as testing-only?
6. Privacy-policy hosting URL.
7. When to run physical-device acceptance, which **blocks** any release.

**Recommendation:** not ready for public release. Ready for continued **internal testing** with debug builds. The blockers are device acceptance, the signing decision, the Drive verification decision and the store compliance items.
