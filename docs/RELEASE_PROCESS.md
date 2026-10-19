# Release process

## Versions
- **App version:** `backend/version.py` (`__version__`), the only source. The installer, `/health`, diagnostics and the update check all read it. SemVer: MAJOR (incompatible data or architecture), MINOR (features), PATCH (fixes), plus `-dev.N` / `-rc.N` pre-releases.
- **Database schema version:** `len(database.MIGRATIONS)`. Migrations are append-only and backed up first; a schema newer than the app is refused.
- **Diagnostics** (`/system/diagnostics`) report the app version, schema version, active LLM, embedding model, index model and settings.

## Build (Windows)
```powershell
cd D:\Abhify.AI
backend\.venv\Scripts\python.exe -m pytest backend\tests -q          # must be green
py -3.11 packaging\windows\build.py                                      # online: downloads wheels
# or, offline / slow network, reusing the dev environment (same Python 3.11.9 x64):
py -3.11 packaging\windows\build.py --site-packages-from backend\.venv\Lib\site-packages
```

**Outputs in `dist\windows\<version>\`** (2.0.0-dev.3 and later; each version gets its own folder, so a new build never overwrites an earlier version's files. The 2.0.0-dev.2 files stay in `dist\windows\`):
- `Abhify.AI-Setup-<version>.exe`
- `SHA256SUMS.txt`
- `RELEASE_NOTES.md` (taken from CHANGELOG.md)
- `INSTALL.html` (the guide the installer's finish page opens) and `INSTALL.md`
- `latest.json.template`

**What the build checks:**
- SHA-256 of the downloaded Python runtime and NSIS. These are pinned on first download in `packaging/.cache/*.sha256` and enforced afterwards. Compare them once with the values published on python.org and SourceForge.
- The bundled runtime imports every dependency in isolated mode, without writing bytecode (`-I -B`).
- Bytecode: third-party packages are copied **without** `__pycache__`, then compiled by the bundled runtime with a neutral path prefix. The build **fails** if any `.pyc` contains a build-machine path (source folder, staging folder, home folder or dev venv).
- `check_package.py` refuses `.env`, keys, databases, user data, tests and secret-like strings in the app files.

> **Use the online build for releases** (verified end to end with 2.0.0-dev.4).
> - Build from a short folder such as `D:\Abhify.AI`: NSIS fails on file paths over 260 characters (chromadb's test files are deep).
> - The build also imports the app's own modules with the bundled runtime, and fails if a dependency is missing from `requirements.txt`. That is what D2 was: an online build that "passed" but couldn't start.
> - It also fails if **any** runtime file contains a build-machine path, such as pip's console-script launchers (F7).
>
> The `--site-packages-from` build copies what is installed in that environment, **including packages the app doesn't declare** (for example `langchain_community` in the dev venv). Run `pip check` and the test suite in it first. For official releases, prefer the online build from `requirements.txt`, or add a lock file (`pip-compile --generate-hashes`) and `pip install --require-hashes`.

## Dependency lock (2.0.0-dev.5 and later)
- The online build installs `packaging\windows\requirements.lock`: every package pinned, with SHA-256 hashes, wheels only.
- **To change dependencies:**
  1. Edit `backend\requirements.txt`.
  2. Run `packaging\windows\lock.ps1`.
  3. Review the lock diff like code.
  4. Create a fresh venv from the lock (`pip install --require-hashes -r packaging\windows\requirements.lock`, plus `pytest`) and run the full suite **in that venv**.
  5. Commit both files. `test_lock_and_signing.py` fails if the lock drifts from `requirements.txt` or loses its hashes.
- The lock is for the Windows x64 / Python 3.11 runtime only. Docker, macOS and Linux use `requirements.txt`.

## Signing (when a certificate exists; see CODE_SIGNING.md)
```powershell
$env:ABHIFY_SIGN_MODE = "TrustedSigning"          # or "Store" (hardware token / cloud HSM in the cert store)
$env:ABHIFY_SIGN_SUBJECT = "<validated publisher name>"
$env:ABHIFY_SIGN_DLIB = "<path>\Azure.CodeSigning.Dlib.dll"; $env:ABHIFY_SIGN_METADATA = "<path>\metadata.json"
py -3.11 packaging\windows\build.py --sign
```
NSIS signs `Uninstall.exe` and the installer through `packaging\windows\sign.ps1`, which timestamps and verifies (chain policy `/pa`, Valid status, publisher, timestamp). `build.py` writes `SHA256SUMS.txt` and `latest.json.template` only after the signed file verifies, so the checksum is always of the signed file.

## Test before publishing
1. `pytest` (automated suite).
2. **Clean-machine test in Windows Sandbox:** `packaging\windows\sandbox\run-sandbox-test.ps1`, then check `build\sandbox-results\result.json`.
3. **Upgrade test:** `packaging\windows\sandbox\run-sandbox-test.ps1 -UpgradeFrom <previous version>` installs the previous installer, adds a memory, installs the new one over the **running** app, then checks the version, the `abhify-backup-pre-upgrade-*.zip` backup and the memory. Also do it by hand once with a chat and a quiz answer.
4. Manual: CPU-only PC, NVIDIA PC, model download through the wizard, phone over Tailscale HTTPS.

## Signing (recommended before a public release)
- **Authenticode-sign** the installer and `Uninstall.exe` with a code-signing certificate (an OV or EV certificate, or Azure Trusted Signing). Signing reduces SmartScreen and antivirus warnings.
  ```powershell
  signtool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /a dist\windows\Abhify.AI-Setup-<v>.exe
  ```
- **Keep keys out of the repo and the installer.** Use the Windows certificate store, a hardware token, or a CI secret store (for example GitHub Actions encrypted secrets with OIDC to a signing service).
- `.gitignore` excludes `*.pfx`, `*.key` and `*.pem`, and `check_package.py` blocks them from packages.
- Publish `SHA256SUMS.txt`. If you use a detached signature (`minisign`/`gpg`), publish only the public key in the README.

## Publish (GitHub Releases)
1. Merge to `main` and tag: `git tag -a v2.0.0 -m "Abhify.AI 2.0.0"`, then `git push --tags`.
2. Create a GitHub Release for the tag and attach `Abhify.AI-Setup-<v>.exe`, `SHA256SUMS.txt` and `RELEASE_NOTES.md`.
3. Fill in `latest.json.template`: set `windows.url` to the release asset URL; the SHA-256 is already filled in.
4. Publish the result as `latest.json` at a stable HTTPS URL, such as a release asset or GitHub Pages.
5. Optionally, deploy `frontend/` to an HTTPS static host for the mobile PWA (see MOBILE_INSTALLATION.md).

## Update checks (in the app)
- Users opt in by setting `updates.manifest_url` in `settings.json`. There is no default, so there is no phoning home.
- Settings → System & data → **Check for updates** fetches the manifest over **HTTPS only**. It validates the version, the download URL (HTTPS) and the SHA-256 (64 hex characters), and shows the result.
- **Nothing is downloaded or installed automatically.** The user downloads the installer, checks the SHA-256 and runs it.
- The upgrade path (stop → backup → replace program files → migrate with backup) preserves user data, configuration and models.

## Rollback
- **App:** install the previous installer. Data is untouched.
- **Data after a schema migration:** older apps refuse newer schemas, so restore `abhify-backup-pre-upgrade-*.zip` (Settings → Restore) or the automatic `abhify-pre-schema-vN-*.db`.
- **Never** downgrade a database in place.
