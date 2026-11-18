# Abhify.AI 6.1 baseline (Phase 0, 2026-11-18, before any 6.1 change)

## Git
- **Branch:** `feature/m2-knowledge-packaging`
- **HEAD:** `a9325d2431c5a7c06db1b94c9dc8ec54711a5e09` ("dev.6 review: clear resumable error…")
- **Working tree:** 191 changed paths (22 modified tracked files, 169 untracked, including all of `android/` and `cloud/`). Nothing has been committed since a9325d2.
- **Rule:** nothing was reset, stashed, cleaned or committed during 6.1.

## Android APKs (all package `ai.abhify.android`; all signed with the same debug certificate)
**Signing certificate:** `CN=Android Debug`, SHA-256 `1d2d1fdb0b2dc934ca9a9e722fa6c03d9f039f408b0c68b0a4ff3fa22fe6246f`.
**Key file:** `C:\Users\Administrator\.android\debug.keystore` (2,618 bytes, 2026-10-26).
- It is the only copy and has no backup. Backing it up needs your approval (R2).
- No release keystore exists.

| File | versionName | versionCode | Bytes | SHA-256 |
|---|---|---|---|---|
| `build/Abhify-5.0.apk` | 5.0 | 5000099 | 73,163,006 | `746150e5df2fe229f99b3635cb17d6a37cf58edb2480ecb7579db7ab24725423` |
| `build/Abhify-5.0-androidTest.apk` | (test APK) | – | 873,935 | `e2473239689728b3874f05e256ebbca5f382119a4b726ad18fef9af84a4c7399` |
| `build/Abhify-5.1.apk` | 5.1 | 5010099 | 73,552,637 | `659053fcd7ce8cb7f7ca0bae019d7e4112506c13ae31cb95b49938f1dcb0cd9f` |
| `build/Abhify-5.3.apk` | 5.3 | 5030099 | 73,708,493 | `778e5c504e410ada2f582af89492f64d92a324f2220f4344975d01ecc5b57bd5` |
| `build/Abhify-5.4.apk` | 5.4 | 5040099 | 73,904,909 | `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` |
| `build/internal/Abhify-5.4-dev.1.apk` | 5.4-dev.1 | 5040099 | 74,272,413 | `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354` |
| `build/internal/Abhify-5.5-dev.1.apk` | 5.4-internal.5.5-dev.1 | 5040099 | 74,392,641 | `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e` |
| `build/internal/Abhify-5.6-dev.1.apk` | 5.4-internal.5.6-dev.1 | 5040099 | 74,483,329 | `fa7aa2fe4998df79f80c7bdd40002190268c9bffeb904a6c4ecbd72b80706416` |
| `build/internal/Abhify-5.6-dev.2.apk` | 5.4-internal.5.6-dev.2 | 5040099 | 74,510,741 | `e1df68bc1607590255a3dd81a5f356c9ed6ab23279d23ddc70b62a474757620c` |
| `build/internal/Abhify-5.6-dev.3.apk` | 5.4-internal.5.6-dev.3 | 5040099 | 74,506,621 | `189e49e1dfa7205b44e21d802318bb9497032618e5c9524e66bf00a7f7711921` |
| `build/internal/Abhify-6.0-dev.1.apk` | 5.4-internal.6.0-dev.1 | 5040099 | 74,541,281 | `a1f4567990bfd1ef5438c26d194c30fda646cd5674482bd1fb9e3eef8396adb0` |

**Archive:** `D:\Abhify-archive\apks` holds 2.0.0-rc.4 to 4.3.0 debug APKs (865 MB, with `SHA256SUMS.txt`). Untouched.

## Desktop
- **Source version:** `backend/version.py` 2.0.0-dev.8, PWA shell `SHELL_REV ui56.1`.
- **Packaged:** `dist/windows/2.0.0-dev.8/Abhify.AI-Setup-2.0.0-dev.8.exe` (131,040,775 bytes, NotSigned) and its staging folder `build/windows/staging` (704 MB). Both were built from an older source than the current tree.
- **Build:** `packaging/windows/build.py`; development start with `start.bat` (127.0.0.1, port 8000).

## Data and models (inventory only; nothing opened, changed or deleted)
- **`%LOCALAPPDATA%\AbhifyAI`:**
  - `data/` holds 10 files (903 KB): `abhify.db` plus four `abhify-pre-schema-v2…v5` migration backups.
  - `config/` (2 files), `cache/` (1), `logs/` (595 KB).
- **`D:\Abhify_Backups`:** 1 entry.
- **Ollama:** `llama3.2:latest` (2.0 GB), `qwen2.5:latest` (4.7 GB), `nomic-embed-text:latest` (274 MB).
- **Android data:** the app keeps its data on the phone (ChatDb v17 plus JSON side files); none of it is on this PC.

## Tests at baseline
- **Android** `testDebugUnitTest`: **426 run / 0 failed / 4 skipped** (same as the 6.0 build).
- **Backend** `pytest`: **442 passed / 1 failed / 2 skipped**.
  - **Failing test:** `test_companion_ai.py::test_desktop_chat_uses_the_companion_for_retrieval_and_the_prompt`.
  - **Cause:** the test calls `agent._prepare` without the isolated-data fixture. `_prepare` reads `db.get_prefs()`, so the test only passed when `ABHIFY_DATA_DIR` pointed at an existing folder. Without the variable it would read the real user database. The read is read-only, but it is still wrong.
  - **Fixed first in 6.1:** the test now uses the `migrated` fixture, a throw-away database. It passes with a non-existent `ABHIFY_DATA_DIR`.
  - This test was written in 5.6, so the "443 passed" figure in the 6.0 report depended on the environment.
- **Lint at 6.0:** 0 errors / 125 warnings.

## Build processes
- **Android:** `android/gradlew.bat` with JAVA_HOME `D:\AndroidDev\jdk17` and GRADLE_USER_HOME `D:\AndroidDev\gradle-home`. ANDROID_USER_HOME must stay unset, otherwise a new debug key is created.
- **Desktop installer:** `packaging/windows/build.py`. It refuses to overwrite an existing version folder.
- **Web/PWA:** served by the backend from `frontend/`.
- **Device access:** no emulator system image, and no adb access to the phone (Remote Desktop, different network).
