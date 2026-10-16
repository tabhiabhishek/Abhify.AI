# Keeping README.md accurate

The README is the first thing users and contributors read. Before 2.0.0-dev.3 it had drifted badly: it said 50 tests (there were 145), it was missing about 20 API routes, it called the Windows installer "planned" when it already shipped, and it told phone users to set `ABHIFY_HOST=0.0.0.0` instead of using companion access. This page lists what has to be checked, and what is checked automatically.

## Automatic guards (run with the test suite)
| Guard | Test |
|---|---|
| Every API route appears in the README's API table | `test_packaging_dev3.py::test_readme_documents_every_api_route` |
| The README's status line names the current version (`backend/version.py`) | `test_packaging_dev3.py::test_version_is_consistent` |
| The CHANGELOG has a section for the current version, and the service-worker cache is versioned with it | same test |

If one of these fails after you add a route or bump the version, update the README and CHANGELOG in the same commit.

## Manual checklist (every version bump)
1. **Status line:** version, what is released or unreleased, and what is still untested. Never write "tested on phones" or "tested on a second PC" unless `MOBILE_ACCEPTANCE_TEST.md` / `PHYSICAL_PC_ACCEPTANCE_TEST.md` record a real-device PASS.
2. **Test count:** run `pytest backend/tests --collect-only -q` and copy the number. Describe what the tests cover, not just how many there are.
3. **API table:** the "Phone?" column must match `companion.REMOTE_ROUTES` / `REMOTE_PREFIXES` (✅) and `OPEN_API` (Open). Everything else is "PC only".
4. **Install sections:** must match `docs/WINDOWS_INSTALLATION.md` and `docs/MOBILE_INSTALLATION.md`. The README gives the short path; the guides are the full truth.
5. **Status-bar texts** quoted in the README and the mobile guide must match `connectionStatus()` in `frontend/app.js` (also covered by `test_pwa_behaviour.py`).
6. **Architecture diagram:** add new backend modules and new local services.
7. **Honesty rules:**
   - Never imply the AI runs on the phone. It runs on the PC (Mode B), or there is no AI (Mode C).
   - Don't call the PWA an "app store app" or an APK.
   - Mention that the installer is unsigned until it really is signed.
   - Mark unverified paths (for example Docker) as unverified.

## Where facts come from
| Fact | Source of truth |
|---|---|
| Version | `backend/version.py` |
| Routes and their access level | `backend/main.py` (`local_only`), `backend/companion.py` (`REMOTE_ROUTES`, `OPEN_API`) |
| Model profiles and sizes | `backend/system_service.py` (`PROFILES`) |
| Data locations | `backend/local_file_service.py` |
| Installer behaviour | `packaging/windows/installer.nsi`, `desktop/launcher.pyw` |
| Test results | `docs/RELEASE_BLOCKER_FIXES_DEV3.md`, `docs/INSTALLER_READINESS_AUDIT.md` (latest audit) |
