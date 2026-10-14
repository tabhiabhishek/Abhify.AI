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

**Outputs in `dist\windows\`:**
- `Abhify.AI-Setup-<version>.exe`
- `SHA256SUMS.txt`
- `RELEASE_NOTES.md` (taken from CHANGELOG.md)
- `INSTALL.md`
- `latest.json.template`

**What the build checks:**
- SHA-256 of the downloaded Python runtime and NSIS. These are pinned on first download in `packaging/.cache/*.sha256` and enforced afterwards. Compare them once with the values published on python.org and SourceForge.
- The bundled runtime imports every dependency in isolated mode.
- `check_package.py` refuses `.env`, keys, databases, user data, tests and secret-like strings in the app files.

> The `--site-packages-from` build copies what is installed in that environment. Run `pip check` and the test suite in it first. For official releases, prefer the online build from `requirements.txt`, or add a lock file (`pip-compile --generate-hashes`) and `pip install --require-hashes`.

## Test before publishing
1. `pytest` (automated suite).
2. **Clean-machine test in Windows Sandbox:** `packaging\windows\sandbox\run-sandbox-test.ps1`, then check `build\sandbox-results\result.json`.
3. **Upgrade test:**
   - install the previous release, add a memory, a chat and a quiz answer
   - install the new release over it
   - check that the data is still there and that `abhify-backup-pre-upgrade-*.zip` exists
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
