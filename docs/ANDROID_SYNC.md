# Abhify Android: optional cloud sync

**Off by default.** Chat, memories and history work without it, forever. Sync needs a signed-in account and the user's explicit choice.

## Settings (Sync & network)
- **Sync with my account:** before turning on, a confirmation lists exactly what will upload (counts per kind).
- **Per kind:** Conversations (with their messages), Memories, Preferences (theme, AI mode, read aloud).
- **Only on Wi-Fi** (WorkManager `UNMETERED`).
- **Sync now.** Status shows the last result, failures, pending changes and conflicts.
- **Delete cloud data:** deletes every cloud copy and turns sync off. Phone data is untouched.
- **Never uploaded:** AI model files, documents.

## Protocol (`cloud/` `/api/v1/sync/changes`)
- **Records:** `{kind, id, updated_at, deleted, data}` with stable IDs (32-hex `uid`; preferences use `pref-<key>`).
  - Kinds are `conversation`, `message`, `memory`, `preference`.
  - Deletions are **tombstones** (`deleted: true`, content cleared) so they propagate.
- **Push** (`POST`, ≤ 500 changes): per record, the version with the higher `(updated_at, device_id)` wins.
  - **Accepted** changes get a new per-user sequence number.
  - **Unchanged** means an identical repeat, so retries are **idempotent**.
  - **Conflicts:** the server's newer copy is returned and not overwritten.
  - **Rejected:** unknown kind, bad ID, timestamp > 1 day in the future, record > 64 KB, over quota (50 MB/user).
- **Pull** (`GET ?since=<seq>&limit=`): changes after the device's cursor, in pages. The device stores the cursor in `sync_metadata`.
- **Isolation:** every row carries `user_id`; queries always filter by the authenticated user.

## Conflict policy
1. **Display:** the deterministic rule above (newest wins; ties go to the higher device ID). Phone and server use identical code (`SyncMerge` / `push`).
2. **Never silently discarded:** whenever the server's version differs from an **unsent local edit**, whichever wins, both versions go into `sync_conflicts`. *Sync & network* lists them with "Keep this phone's" or "Keep other". The choice is written as a new edit, so every device converges on it.
3. **Messages** are append-only (only deleted, never edited), so they can't conflict.
4. **Duplicates:** the same memory text created separately on two devices is merged into the synced ID.
5. A dirty flag is cleared only if the record didn't change again while its upload was in flight (`updated_at` match), so no edit is lost.

## Scheduling and retries
- **When it runs:** every hour (network constraint), "soon" (5 s) after local changes, and immediately when connectivity returns.
- **Failures:**
  - Network errors and 5xx are retried with exponential backoff (30 s base).
  - 401 or other 4xx stop and show "Sign-in expired" or the error.
- **Offline:** changes queue as dirty records and upload on reconnection. Local use is never blocked.

## Tested
- **`SyncEngineTest` (JVM, server rules in memory):**
  - first sync, then a second device;
  - incremental and idempotent repeats;
  - a concurrent edit keeping both versions;
  - a newer local edit surviving an older remote;
  - disabled kinds neither uploading nor downloading;
  - deletion propagation.
- **`CloudIntegrationTest`, the real Kotlin engine against the real server:** two devices, idempotency, a conflict preserved, another user seeing nothing.
- **Cloud tests:** first/incremental/paged sync, duplicates, older-never-overwrites-newer and tie-break, tombstones and validation, per-user isolation, quota, deleting cloud data.
- **Not yet tested:** background WorkManager scheduling on a real phone, and sync between two physical phones.
