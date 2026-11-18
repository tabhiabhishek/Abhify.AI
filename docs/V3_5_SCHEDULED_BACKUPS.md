# v3.5: scheduled backups

**Status:** implemented and tested on the JVM (schedule maths, rules, retention, migration). **Execution on a phone and to real Google Drive is pending.**

## Settings
**Settings → Backup & restore → Scheduled backups.** The schedule is **off by default** and nothing runs until you turn it on.

| Setting | Values |
|---|---|
| Frequency | Daily or weekly (choose a day) |
| Time | About HH:00. Android decides the exact moment; it can be later in deep sleep or with a low battery, so the app only says "about". |
| Include | Any of the backup categories (Personal memory, Conversations, Notes and tasks, Knowledge, Documents, Study, Career, Settings) |
| Save to | Google Drive (`Abhify.AI/Application Backups`), a folder on this phone that you pick, or both |
| Keep the newest | 1–30 per destination (default 5) |
| Wi-Fi only | For Drive (default on) |
| Passphrase | **Required when Drive is a destination**; optional for a local folder |

**Back up now** runs one backup with the same settings immediately. If Drive is a destination, it still waits for the network.

## How it works
- **Scheduling.** `ScheduledBackupWorker` runs as unique periodic work `abhify-backup-scheduled`, every 24 h or 7 days, with the `UPDATE` policy so there is only ever one schedule.
  - The first run is delayed until the next preferred hour.
  - It waits for: battery not low, storage not low, and the network when Drive is a destination.
- **Content.** The backup is the same format as a manual backup (`abhify-backup` v1, per-table SHA-256, optional AES-256-GCM).
  - Only whitelisted settings are included.
  - Never included: API keys, tokens, passwords, signing keys, the model or temporary files.
- **Encryption.** Scheduled backups to Drive run unattended, so they **must be encrypted**. The passphrase is stored sealed by an Android Keystore key (`abhify_backup_schedule_v1`), like provider keys.
  - It is never in a backup and never logged.
  - **Forget passphrase** deletes it and turns off a Drive schedule.
- **Local folder.** The file is written with the Storage Access Framework into the folder you picked, using a persisted permission and no storage permission.
  - If the permission is lost, the history says "choose it again".
- **Drive.** The backup is put into the [background upload queue](V3_5_BACKGROUND_UPLOADS.md) under the kind `backup-auto`.
  - Its history row says "waiting to upload" until Drive confirms, then "✓ saved".

## Retention (deleting old backups)
`Retention.select` only ever returns **scheduled** backups that Abhify itself recorded as written successfully. The **newest N stay**, and at least one always stays.

| Destination | When | How |
|---|---|---|
| Folder | Right after a successful scheduled backup | Deletes only document URIs that Abhify created and recorded |
| Drive | Right after a scheduled backup finished uploading | Moves only files in Abhify's `backup-auto` records to **Drive's trash**, which is restorable for 30 days |

Never deleted:
- manual backups;
- failed backups;
- files Abhify didn't create;
- anything else in the folder or on Drive;
- the only backup.

## History
The history (latest 50) shows for each backup:
- date, scheduled or manual, destination, size, encryption;
- status: ✓ saved / waiting to upload / removed by retention / ✗ failed with the reason.

The card shows:
- the next run ("about");
- the last scheduled run, OK or the failure reason.

## Tests (`ScheduledBackupTest`)
- Next run, daily and weekly, before and after the hour.
- Drive requires a passphrase and a connection; local may be unencrypted; at least one category and one destination are needed; the schedule is off by default; JSON round trip.
- Retention keeps the newest, ignores manual and failed backups, keeps at least one, and never removes the only backup.
- Source checks: unique periodic work with UPDATE, cancel when disabled, a disabled schedule never backs up.

## Limitations
- Not run on a phone yet: the actual WorkManager timing, SAF writing and Keystore use on the device are unverified.
- On some phones (aggressive battery savers, for example MIUI), background work can be delayed for long periods. Exempting Abhify from battery optimisation helps; Abhify doesn't request that itself.
- Every scheduled backup is a full backup of the chosen categories. There are no incremental backups.
