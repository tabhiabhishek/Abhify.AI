# Abhify.AI Android 4.2.0: Google Drive for every user and local-first mode

**Build:** debug build, versionCode 4020099. It installs over 3.4.0–4.1.0; data is kept.

**Database:** v15 → v16 (one column, `drive_files.account`).

## Part 1: Google Drive
### What already worked (kept)
- The Android Authorization API (`DriveAuth`), the `drive.file` scope, in-memory tokens and revoke on disconnect.
- Resumable uploads, conflict handling and the background queue (3.5).
- Restore preview with additive restore.

The working sign-in was **not** replaced. It now also accepts a chosen account (`AuthorizationRequest.setAccount`).

### Changes
| Area | Change | Files |
|---|---|---|
| Account selection | A first connection, and **Switch account**, open Android's account chooser (`AccountManager.newChooseAccountIntent`, which needs no permission and allows adding an account). An existing connection renews the same account silently. | `drive/DriveAndroid.kt`, `ui/V42Screens.kt` (`DriveAuthHost`) |
| Account identity | Drive `about` gives the permission id, email and name. These are shown in Settings and used to keep each account's data apart. | `DriveApi.account`, `DriveIdentity` (in `drive/DriveWork.kt`) |
| Per-account folders | Folder ids are stored per account. The single cache from 3.4–4.1 is adopted once by the first account that connects. | `DriveFolders` |
| Central folder | The root must be directly in **My Drive** (`'root' in parents`); the oldest folder of that name is reused; it's created only if missing. Cached ids are re-validated (exists, not trashed, right parent). On connect it initializes `Backups/ Documents/ Imports/ Exports/`. | `drive/Drive.kt` |
| Routing | backups → Backups; document text → Documents; picked files → Imports; conversations and notes → Exports. Legacy folder names in older queue rows map automatically. | `DriveApi.folderFor`, `DriveApi.canonical`, `ui/V34State.kt`, `backup/BackupWork.kt` |
| Accounts and the queue | Each queued upload records its account (DB v16). The worker uploads only rows for the connected account; rows made before 4.2 are adopted by it. Rows for another account wait and are shown as such. The worker asks Google for the stored account and stops if a different account comes back. | `DriveQueue.runAll(account)`, `DriveUploadWorker`, `DriveAccounts` |
| Migration | Optional and confirmed: lists Abhify files in the old sub-folders, you pick, then they **move** with `addParents`/`removeParents` (ids kept). Results are reported; nothing is deleted. | `DriveMigration`, `DriveState.scanLegacy/migrate`, `DriveMigrationCard` |
| Settings → Google Drive | Shows: Connected / Not connected, account, folder status, last successful check. Actions: **Open Abhify.AI folder**, Check connection, List files, Switch account, Reconnect, Disconnect, Setup guide & troubleshooting. Also a "folder unavailable → Recreate" card and a count of uploads waiting for another account. | `DriveStatusCard`, `DriveGuideDialog` |
| First-time setup | When not connected: an intro (optional, own account, limited access, no Cloud project, disconnect any time), a prerequisites list (internet, Play services, account), and **Connect / Not now / Learn more**. There is also an optional "Google Drive" step in the setup wizard. | `ui/V42Screens.kt`, `ui/SetupScreens.kt`, `device/Setup.kt` |
| Errors | Cancelled, denied, network, Play services and developer-configuration errors each have a clear message. Cancelling or denying never shows "connected". | `DriveErrors` |

### Answers to the inspection questions
- **Uploads after the app closes:** they continue. Since 3.5, WorkManager drains the queue; no new background service was added in 4.2.
- **Automatic backup:** only if you enable **Scheduled backups** (off by default).
- **Two-way sync:** **no**.
- **Import:** uses Android's system picker (Documents → Import). Copying a picked file to Drive is a separate, explicit action ("A file → Imports").

## Part 2: Local-first mode with an optional Abhify server
### What I found
- **First run** already allowed "Start on this phone". `CLOUD_URL` is empty by default.
- **No server calls without a session:** sync and refresh run only after a sign-in.
- **Gaps:**
  - Welcome always offered sign-in and registration;
  - the server address was buried in the "Answer style" settings;
  - there was no Local Mode status, Verify or Disconnect.
- **Correction to my earlier note:** debug builds already had a debug-only network config allowing plain http to 10.0.2.2, 127.0.0.1 and localhost. I rewrote that file with the same three hosts; I didn't read it before overwriting, and recovered the old version from the 4.1.0 APK to confirm it was identical.

### Changes
| Area | Change | Files |
|---|---|---|
| Connection model | `LOCAL_MODE`, `SERVER_CONFIGURED`, `SERVER_CONNECTED`, `SERVER_DISCONNECTED`, `SERVER_AUTHENTICATED`. No address (or switched off) means Local Mode, **never an error**. | `cloud/ServerConnection.kt` |
| Address checks | Format, protocol and host. https only for real servers. Debug-only http to 10.0.2.2 (emulator → PC) and 127.0.0.1/localhost (the phone itself, e.g. `adb reverse tcp:8000 tcp:8000`). LAN http (192.168.x) gets a clear explanation instead of a silent failure. No downgrade, no TLS bypass. | same |
| Health check | Uses the existing `GET /api/v1/health` of `cloud/` (`service: abhify-cloud`, `version`). Other services, 404, 5xx, timeouts and versions below 1.x are reported clearly. A check happens only when you tap Verify or Connect; nothing runs at startup. | same, `AppViewModel.verifyServer` |
| Settings → **Connection mode** | Three independent sections. **Local application:** "Connection mode: Local Mode — Abhify.AI is running locally. No Abhify server is connected." **Google Drive:** its own status. **Abhify server:** Connect to Abhify Server, URL, Connect, Verify server, Retry, Disconnect server (keeps the address), Remove address, Continue in Local Mode, and Sign in / Create account only once a server is set. | `ConnectionScreen` (`ui/V42Screens.kt`), `ui/V3Screens.kt`, `ui/ManageScreens.kt` |
| Welcome | Sign-in and registration appear only when a server address is set and enabled. Otherwise: "Have an Abhify server? Connect (optional)". | `ui/OnboardingScreens.kt` |
| Independence | The Drive code never touches the server session; Google sign-in never creates or signs in to an Abhify account. Disconnecting the server keeps Drive, local data and AI settings. | tests |

**Database:** no change for the server part. Settings live in the existing preferences: `server_url`, plus the new `server_enabled`. Server session tokens stay in the existing Keystore-sealed store.

## Tests
- **JVM:** 216 run, 0 failed, 4 skipped (opt-in). The 11 new tests are in `V42Test.kt`. They cover:
  - the root strictly in My Drive and never duplicated (a same-name decoy is ignored);
  - the 4 sub-folders; moved or deleted folders detected; legacy names mapped;
  - routing, account identity, moves that keep ids, the migration plan;
  - per-account queue isolation and adoption of older rows; error kinds;
  - Local Mode states, address rules (debug/release, emulator, LAN), health interpretation;
  - setup without a server, with the Drive step;
  - no hard-coded Gmail address or client secret; Drive code independent of the server; no health call at startup; sign-in gated on Welcome; network configs;
  - the v16 migration.
- **Changed existing test:** one v3.4 test now expects the "Learning Library" folder to be cached as "Documents" (an intended mapping). The shared fake Drive learned `about`, parents and moves.
- **On-phone `DbMigrationTest`** now checks v16. It was built but not run.

## Verification status
| Item | Status |
|---|---|
| Automated tests, lint, debug build | See the final report |
| Your phone's existing Drive connection after the update | **Not tested.** Expected: still connected; the first Check identifies the account and creates the 4 new sub-folders beside the old ones. |
| A new user with a second Google account | **Not tested.** The account must be a test user of the Google project. |
| Account switching, disconnect/reconnect, Open folder, migration on real Drive | **Not tested** |
| Server: verify, connect and sign-in against a real server | **Not tested** (no server running) |
