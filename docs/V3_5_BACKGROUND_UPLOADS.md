# v3.5: background Google Drive uploads

**Status:** implemented and tested on the JVM against a simulated Drive. **Real-account Drive testing is pending.** It needs the OAuth client from [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md).

**Physical-device validation is pending.**

## What changed since 3.4
In 3.4, uploads ran inside the app. If Android closed the app, an upload waited until you tapped Retry.

In 3.5, every upload goes into a **durable queue** that a WorkManager job drains in the background. The queue is the same `drive_files` table and `files/drive_queue/<id>.bin` bytes as before, with new columns in DB v10.

The same queue is used by every upload:
- uploads you start (document, chat, notes, memory backup, app backup);
- scheduled backups you enabled.

There is a single Drive implementation (`DriveApi`), shared by all of them.

## How it works
| Piece | File | Behaviour |
|---|---|---|
| Queue logic | `drive/DriveQueue.kt` | Pure Kotlin, tested on the JVM. It processes items oldest-first and records every outcome on the row. |
| Worker | `drive/DriveWork.kt` (`DriveUploadWorker`) | Unique work `abhify-drive-upload` with the `APPEND_OR_REPLACE` policy, so two drains never run at once and a new item is picked up after the current run. |
| Constraints | same file | Needs a network; uses unmetered (Wi-Fi) only if **Upload on Wi-Fi only** is on. Exponential backoff starts at 30 s. |
| Sign-in | `DriveAuth.authorize` | Silent only. If Google needs the user (access revoked or expired consent), items keep waiting with "connect again". The worker stops without retrying in a loop. Connecting again restarts the queue. |
| Resume | `DriveApi.upload(session, onSession)` | The resumable session URI is saved on the row before the first byte. After the app is killed, the next run asks Drive how much it has and continues from there. An expired session (404/410) starts a new one. |
| Large uploads | `setForeground` (data-sync type) | Used when the queue holds more than 5 MB, with a "Uploading to Google Drive…" notification. If Android refuses, the job continues as normal background work and resumes later if it is stopped. |
| Restart | `AppGraph` start-up | Waiting items are handed to WorkManager again. WorkManager also persists its jobs across reboots. |

## Outcomes per item
| Situation | Result |
|---|---|
| Uploaded | **Synced**; the queued bytes are deleted. |
| Identical file already on Drive (same name and MD5) | **Synced**; not uploaded again. |
| Different file with the same name | **Conflict** that waits for you: **Replace** or **Keep both**. The decision is stored, so it survives restarts. Nothing is overwritten before you choose. |
| No connection, rate limit, 429, 5xx | Retried later, up to 6 attempts; then **Failed (gave up after 6 attempts, tap Retry)**. |
| Permission refused or Drive full | **Failed** immediately, with the reason. |
| Sign-in revoked or expired | Waits ("connect again"); nothing is lost. |
| Account disconnected | Waits; the worker does nothing until you connect. |
| Queued bytes missing | **Failed**: "upload it again from its screen". |
| **Cancel uploads** | Waiting items become **Cancelled** (bytes kept, Retry possible). A running upload stops at its next 1 MB chunk with its session saved. |

A renamed local item is uploaded under its new name the next time you upload it. It reuses its status row (keyed by kind and local reference), but it becomes a new file on Drive. The copy with the old name stays there; Abhify never renames or deletes it for you.

## What is never uploaded
- Anything you didn't start.
- Anything outside the categories of a schedule you enabled.

There is no "upload all documents" action.

## Tests (`V35Test.kt`)
`DriveQueueTest` (5 tests) uses the in-memory `FakeDrive` from v3.4 and covers:
- skipping unchanged files;
- **resume after a simulated process kill**: only the 3 remaining chunks are sent;
- conflict persistence, keep-both and replace;
- retry limit, auth stop, permanent failure;
- cancel, missing bytes.

`V35DatabaseAndWiringTest` covers:
- the v10 migration on a populated v9 database;
- unique-work policy, network constraints, backoff and silent-auth handling (source checks);
- manual uploads going through the same queue;
- the foreground service declaration.

**Not tested:**
- WorkManager execution on a device (no device);
- real Google Drive;
- the foreground notification on Android 14 or 15.
