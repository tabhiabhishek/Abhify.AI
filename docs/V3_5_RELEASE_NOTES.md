# Abhify.AI Android 3.5.0: release notes

**Build:** debug build, versionCode 3050099. App ID `ai.abhify.android`, same debug signing key.

**Install:** install over 3.4.0 with `adb install -r`. Do not uninstall and do not clear data.

**Database:** v9 → v10 (additive: new columns on `drive_files` and `backups`).

## New
- **Background Google Drive uploads.**
  - Every upload is queued and continues after you close the app.
  - Interrupted uploads resume where they stopped.
  - Retries with backoff, Wi-Fi-only option, Cancel / Retry / Remove.
  - Conflicts are kept until you choose.
  - Details: [V3_5_BACKGROUND_UPLOADS.md](V3_5_BACKGROUND_UPLOADS.md).
- **Scheduled backups.**
  - Daily or weekly at about a chosen hour, chosen categories.
  - Saved to Drive (encrypted, required) and/or a folder you pick.
  - Keeps the newest N; history shows each backup's status; Back up now.
  - Details: [V3_5_SCHEDULED_BACKUPS.md](V3_5_SCHEDULED_BACKUPS.md).

## Unchanged
- Qwen2.5 and its configuration, the chat and AI routing, ML Kit OCR, Career, Study, Documents.
- Permissions are identical to 3.4.0.
  - WorkManager's foreground service is now declared with the data-sync type. That only reuses permissions the app already had.

## Tests
- 179 JVM tests: 0 failed, 3 skipped (live-network tests). There are 10 new tests.
- Lint: 0 errors.

## Pending
- Real Google Drive (needs the OAuth client).
- Testing on a physical phone.
