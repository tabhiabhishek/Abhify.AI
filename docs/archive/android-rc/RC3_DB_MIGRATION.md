# Database migration report (RC.3)

| Version | Introduced in | Change | Kind |
|---|---|---|---|
| 1 | 0.1.0-dev.1 | conversations, messages, memories | initial |
| 2 | 0.1.0-dev.4 | quiz_answers, interview_views | additive |
| 3 | 2.0.0-rc.2 | uid/profile_id/deleted/dirty/updated_at/model columns; local_profiles, linked_accounts, devices, user_preferences, sync_metadata, sync_conflicts, model_registry, document_metadata | additive + backfill |
| **4** | **2.0.0-rc.3** | `conversations.pinned INTEGER NOT NULL DEFAULT 0` | **additive** |

- **No table is dropped or recreated, and no row is deleted.** Every step is `ALTER TABLE … ADD COLUMN` or `CREATE TABLE`.
- **Upgrade paths:** `onUpgrade` runs each step once in order, so a phone on v1 (dev.1–dev.3), v2 (dev.4/rc.1) or v3 (rc.2) reaches v4 in one launch.
- **Provider keys are not in the database.** They're in a separate Keystore-sealed store.
- **The model files are not touched** (`noBackupFilesDir/models`).
- **Compatibility:** no SQLite syntax newer than Android 10's SQLite 3.22 (no UPSERT).

**Test:** `androidTest/DbMigrationTest` builds a real v2 database (2 conversations, 3 messages, 2 memories, 1 quiz answer) and opens it with today's code. It checks that every row is kept, the uids are unique, there's a single profile, everything is queued for sync once, the new tables exist, and the version is current.
- **Status:** written; it runs on a device (`RUN-TESTS.cmd` → `db_migration_v2_to_v3`). **NOT RUN yet**, because no device is attached to this PC.
- The version assertion was updated for v4.
