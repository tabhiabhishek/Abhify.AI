# Phase 5: Google Drive reliability (background behaviour)

**Setup and user steps:** [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md). This page covers how uploads and recovery behave. 4.4.0 made **no** Drive changes.

| Topic | Behaviour |
|---|---|
| Optional | Abhify works fully without Drive; local AI never depends on it |
| Connection | Account shown; Check, Reconnect, Switch account, Disconnect (revokes access, files untouched) |
| Uploads | Durable queue (`drive_files`) drained by WorkManager (`abhify-drive-upload`). Resumable 1 MB chunks continue after the app is closed. Retries with backoff (6 attempts). Wi-Fi-only option. Cancel / Retry / Remove. |
| Accounts | Each upload belongs to the account it was made for and is never sent to another |
| Duplicates and conflicts | Identical files are skipped (name and MD5). A different file with the same name waits for **Replace / Keep both**. Nothing is overwritten silently. |
| Scheduled backups | Off by default. Daily or weekly, encrypted for Drive, retention of Abhify's own scheduled backups only. |
| Offline | Items wait; local data is unaffected |
| Recovery | Expired sign-in → "Connect again" (queue kept); a deleted folder → Recreate; a failed item → Retry |
| Two-way sync | **Not implemented** (by design) |

**Tests:** `DriveQueueTest`, `DriveCentralFolderTest`, `DriveAccountTest` (simulated Drive).

**Real account:** confirmed by the owner on 3.4.0. 4.2+ is **pending**.
