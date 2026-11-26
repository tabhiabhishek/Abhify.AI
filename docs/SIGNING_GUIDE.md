# Abhify.AI 7.2.1: production signing guide (for the owner)

**State of 7.2.1:**

| Package | Signing |
|---|---|
| Android APK | **Development-signed** with the debug certificate, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f` |
| Windows installer and portable | **Not signed** |

This guide only prepares production signing. No keys were created, requested or stored for this release. Every step below is yours to run, with your own certificate and passwords.

**Rules:**
- **Never put keys, keystores, passwords or certificate files in Git or in a release folder.** `.gitignore` already excludes `*.jks`, `*.keystore` and `*.key`.
- Keep an offline backup of every production key in two separate places. A lost Android key means existing installs can never be upgraded.
- **Do not move, replace or delete `C:\Users\Administrator\.android\debug.keystore`.** Every installed copy of Abhify.AI on your phones is signed with it, and you need it for key rotation (A3).

---

## A. Android

### A1. Create the production key (once, offline)

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore D:\Keys\abhify-release.jks `
  -alias abhify -keyalg RSA -keysize 4096 -validity 10000
```

- Store `D:\Keys\` **outside** the repository, and back it up.
- keytool asks for the passwords interactively. Do not write them in scripts.

### A2. Sign a release build

The project deliberately has no release signing config, so sign the built APK with `apksigner` from the Android build tools:

```powershell
cd D:\Abhify.AI\android
.\gradlew.bat --offline assembleRelease          # unsigned: app\build\outputs\apk\release\app-release-unsigned.apk
$bt = Get-ChildItem "$env:ANDROID_HOME\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
& "$($bt.FullName)\zipalign.exe" -p -f 4 app\build\outputs\apk\release\app-release-unsigned.apk abhify-aligned.apk
& "$($bt.FullName)\apksigner.bat" sign --ks D:\Keys\abhify-release.jks --ks-key-alias abhify --out Abhify.AI-7.2.1-release.apk abhify-aligned.apk
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs Abhify.AI-7.2.1-release.apk
```

### A3. Keep existing installs upgradable (important)

Android only installs an update signed with the same certificate as the installed app. A production-signed APK will **not** install over today's development-signed app. Uninstalling to get around this would delete all data on the phone.

Use **APK Signature Scheme v3 key rotation**, which Android 9+ supports (Abhify.AI needs Android 10+). The new key is given a signed proof that the old key trusts it:

```powershell
& "$($bt.FullName)\apksigner.bat" rotate --out D:\Keys\abhify-lineage.bin `
  --old-signer --ks C:\Users\Administrator\.android\debug.keystore --ks-key-alias androiddebugkey `
  --new-signer --ks D:\Keys\abhify-release.jks --ks-key-alias abhify
& "$($bt.FullName)\apksigner.bat" sign --ks C:\Users\Administrator\.android\debug.keystore --ks-key-alias androiddebugkey `
  --next-signer --ks D:\Keys\abhify-release.jks --ks-key-alias abhify `
  --lineage D:\Keys\abhify-lineage.bin --out Abhify.AI-7.2.1-release.apk abhify-aligned.apk
```

**Before rolling out a rotated APK:**
1. Test it on a spare device or emulator that has the development build installed. Install over it, then check that data is kept.
2. Export a Backup (More → Backup) on each phone first.

### A4. Verify the APK before publishing

- `apksigner verify --print-certs` shows the production certificate (and the lineage, if rotated).
- Record the APK's SHA-256 in `SHA256SUMS.txt`.

---

## B. Windows (Authenticode)

### B1. Get a certificate

You can use either:
- an **OV/EV code-signing certificate** on a hardware token or cloud HSM; or
- **Microsoft Trusted Signing**.

Since 2023, certificate authorities issue code-signing keys only on hardware, so there is no exportable `.pfx` to manage. Background: `docs/CODE_SIGNING.md`.

### B2. Install signtool

The Windows SDK appears to have been removed from the build PC on 2026-11-24. Install the "Windows SDK Signing Tools for Desktop Apps" component, or set `ABHIFY_SIGNTOOL` to an existing `signtool.exe`.

### B3. Configure (environment variables only; nothing goes in the repo)

| Mode | Variables |
|---|---|
| Certificate in the Windows store / token | `ABHIFY_SIGN_MODE=Store`, `ABHIFY_SIGN_THUMBPRINT=<40-hex SHA-1>`, `ABHIFY_SIGN_SUBJECT=<publisher name>` |
| Microsoft Trusted Signing | `ABHIFY_SIGN_MODE=TrustedSigning`, `ABHIFY_SIGN_DLIB=<path to Azure.CodeSigning.Dlib.dll>`, `ABHIFY_SIGN_METADATA=<path to metadata.json>`, `ABHIFY_SIGN_SUBJECT=<publisher name>` |

### B4. Build and sign

```powershell
cd D:\Abhify.AI
backend\.venv\Scripts\python packaging\windows\build.py --sign
```

- NSIS signs `Uninstall.exe` and the installer through `packaging/windows/sign.ps1`.
- Every file is timestamped and verified with `signtool verify /pa`. The build fails if any signature does not verify.
- Use a **new version number** for a signed build (for example 7.2.2). Never re-sign or replace the published 7.2.1 files.

### B5. Verify

```powershell
Get-AuthenticodeSignature .\dist\windows\<version>\Abhify.AI-Setup-<version>.exe | Format-List Status, SignerCertificate, TimeStamperCertificate
```

`Status` must be `Valid`. Then re-run the Windows Sandbox install and upgrade test on the signed installer before publishing.

---

## C. In-app updates (optional, later)

In-app updates are off: there is no manifest URL and no trusted keys. To enable them later, generate an Ed25519 update key outside the repository, put only the **public** key in the app's trusted keys, and sign `latest.json` per release. Design: `docs/AUTOMATIC_UPDATES.md` and `backend/updates.py`. This is a 7.5 item and needs your approval.
