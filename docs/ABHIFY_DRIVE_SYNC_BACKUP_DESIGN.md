# Abhify.AI: optional Google Drive sync and encrypted backup (design for approval, 2026-11-23)

**Status:** design only. No code, schema, dependency or Google Cloud setting has been changed. Implementation waits for your approval of this document and of the decisions in §11.

**Basis:** current working tree (`release/7.0.0` at 592df89 plus the uncommitted 7.1.0-dev.2 changes). All findings below were verified in the code.

---

## 1. Audit of what exists

### 1.1 Google Drive

| Platform | What exists | Evidence |
|---|---|---|
| **Android** | Full Drive integration (since 3.4, reorganized in 4.2). <br>• **Sign-in:** official Authorization API (Play services), scope **`drive.file` only**. No client secret; the OAuth client is matched by package + signing SHA-1. <br>• **Tokens:** access tokens in memory only, renewed silently. Revoke at `oauth2.googleapis.com/revoke`. <br>• **Accounts:** per-account folder ids (`drive_folders:<permissionId>`); queued uploads are bound to their account (DB v16). <br>• **Drive REST v3:** resumable chunked uploads that continue after an app restart; MD5 dedupe; name conflicts are never overwritten unless you choose "Replace"; mapped errors (401 / 403 rate / 403 quota / 429 / 5xx); trash, not delete. <br>• **Folders:** `My Drive/Abhify.AI/{Backups, Documents, Imports, Exports}`. The 3.4–4.1 folders (*Application Backups, Memory Backups, Learning Library, Archived Conversations, Exported Notes*) are mapped to these and moved only on request (`DriveMigration`). | `drive/Drive.kt`, `DriveAndroid.kt`, `DriveAccounts.kt`, `DriveQueue.kt`, `DriveWork.kt`, `docs/GOOGLE_DRIVE_SETUP.md` |
| **Windows** | **None.** No OAuth, no Drive client. | grep over `backend/` |
| **Web** | **None.** | grep over `frontend/` |

**Google Cloud:** one project, "Abhify AI Android". It has one **Android** OAuth client (debug SHA-1 `AF:35:…:63:51`), and the consent screen is External, in **Testing**.

> The folder names in your prompt (*Documents, Learning Library, Archived Conversations, Exported Notes, Memory Backups, Application Backups*) are the **3.4–4.1 layout**. Since 4.2 they map onto `Backups / Documents / Imports / Exports`. I propose keeping that layout and adding one sub-folder, `Sync/` (§4). No duplicate folders.

### 1.2 Encryption

| Platform | What exists |
|---|---|
| **Android** | **Backups:** PBKDF2-HMAC-SHA256 (210,000 iterations) → AES-256-GCM, 16-byte salt, 12-byte IV, 128-bit tag. The wrong passphrase and tampering give the same error. <br>**Scheduled-backup passphrase:** sealed with an Android Keystore key. Provider keys use the same mechanism. |
| **Windows** | **No AES available.** The Python standard library has no AES-GCM, and `cryptography` is not in `packaging/windows/requirements.lock`. Provider API keys are protected with DPAPI. |
| **Web** | WebCrypto (`AES-GCM`, `PBKDF2`) is built into every target browser, but nothing uses it yet. |

### 1.3 Backup and restore

| Platform | What exists | Gaps against your prompt |
|---|---|---|
| **Android** | Format `abhify-backup` v1, built per category: <br>• JSON tables plus a SHA-256 per table; <br>• a settings whitelist (no keys, tokens or online-privacy switches); <br>• **restore preview** (new / already present); <br>• **additive restore** in one transaction (uid or natural key; never updates or deletes; running it twice is a no-op); <br>• backup history. <br>Destinations: share, file, Drive. | • **Encryption is optional.** An empty passphrase uploads a **plaintext** backup to Drive (`V34State.toDrive`). <br>• **Scheduled backups** can upload to Drive automatically. They're off by default, need a passphrase, and are opt-in, but they contradict your "no automatic backup uploads" rule. |
| **Windows** | A ZIP of the data folder: SQLite copied consistently, manifest with app and schema version, zip-slip and newer-schema checks. Restore is **staged** and applied on the next start; the current data is moved aside to `backups/pre-restore-*` and never deleted. | • **Not encrypted.** <br>• No preview, no selective restore, no additive restore (whole-folder replace only). <br>• No Drive. |
| **Web** | **No backup at all.** Data lives in IndexedDB `abhify-device` v1 (`messages`, `memories`, autoincrement ids). | Everything. |

### 1.4 Sync and record identity (what a sync protocol can build on)

| Platform | Stable ids, versions, tombstones | Existing sync |
|---|---|---|
| **Android** | `conversations`, `messages`, `memories`: `uid` (unique), `updated_at`, `deleted` (tombstone), `dirty`. `user_preferences` has `dirty`. Tables `devices`, `sync_metadata`, `sync_conflicts`. **Other tables** (notes, tasks, workspaces, study, career, library docs) **have no uid.** | `sync/Sync.kt`: the self-hosted-server sync (newest-wins merge, keep-both on conflict). This is the conflicting "connected" feature you haven't decided on yet. |
| **Windows** | `conversations.id` is TEXT. `messages` and `memories` have **integer ids, no uid, and deletes are hard deletes** (no tombstones). `updated_at` exists only on conversations, memories and documents. | none |
| **Web** | Autoincrement ids only. | none |

**Conclusion:** the reusable parts are the Android Drive client, the Android backup format and its additive-restore engine, and Android's uid columns. The new parts are:
- Drive on Windows and on the Web;
- AES on Windows;
- a cross-platform record model;
- the vault key and pairing;
- the sync protocol;
- encrypted, previewable restore on Windows and the Web.

---

## 2. Architecture

```
 ┌──────────────── Windows PC ────────────────┐   ┌──────────────── Android phone ──────────────┐   ┌──────── Browser (standalone Web) ────────┐
 │ own SQLite · own Ollama/models · own prefs │   │ own SQLite · own llama.cpp · own prefs      │   │ own IndexedDB · own WebLLM · own prefs   │
 │                                            │   │                                             │   │                                          │
 │  [Sync Now] ──► Sync engine (Python)       │   │  [Sync Now] ──► Sync engine (Kotlin)        │   │  [Sync Now] ──► Sync engine (JS)         │
 │     │  1 export changed records (local DB) │   │     │                                       │   │     │                                    │
 │     │  2 encrypt AES-256-GCM, vault key    │   │     │   same protocol, same test vectors    │   │     │  WebCrypto                         │
 │     │  3 upload immutable op file          │   │     │                                       │   │     │                                    │
 │     │  4 download others' op files         │   │     │                                       │   │     │                                    │
 │     │  5 verify tag → preview → apply      │   │     │                                       │   │     │                                    │
 │  vault key: DPAPI (this Windows user)      │   │  vault key: Android Keystore                │   │  vault key: non-extractable CryptoKey    │
 │  OAuth refresh token: DPAPI                │   │  OAuth: Play services, memory only          │   │  OAuth: GIS access token, memory only    │
 └──────────────────────┬─────────────────────┘   └──────────────────────┬──────────────────────┘   └──────────────────┬───────────────────────┘
                        │ HTTPS, only when the user presses a button     │                                             │
                        ▼                                                ▼                                             ▼
        ┌─────────────────────────────── the user's own Google Drive (scope drive.file) ─────────────────────────────────────┐
        │ My Drive/Abhify.AI/                                                                                                 │
        │   Backups/      *.abx  passphrase-encrypted backups (manual only)                                                   │
        │   Sync/vault.json      vault header: id, key epoch, vault key WRAPPED by the Recovery Key (ciphertext only)         │
        │   Sync/devices.abx     device registry (encrypted)                                                                  │
        │   Sync/ops/<device>/<seq>.abx   immutable encrypted change sets, one per Sync Now per device                        │
        │   Documents/ Imports/ Exports/  unchanged                                                                           │
        │ Google sees: file names, sizes, times. Never content, keys or passphrases.                                          │
        └─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Properties:**
- No device talks to another device. Drive is a passive encrypted mailbox.
- A device that never presses Sync Now behaves exactly as it does today.
- Offline: nothing changes. Sync Now simply reports "No connection" and nothing is changed locally.
- No model, inference or runtime state is ever exported.

---

## 3. Encryption, keys, pairing and recovery

**Algorithms** (all available in Kotlin/JCA, WebCrypto, and Python `cryptography`):

| Use | Algorithm |
|---|---|
| Content | AES-256-GCM, a random 96-bit IV per file, 128-bit tag. **AAD** = the file's header (format, vault id, key epoch, device id, sequence), so a file can't be renamed, replayed under another device or moved between vaults without detection. |
| Passphrase → key | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt. Argon2 would be stronger, but WebCrypto doesn't provide it, and one KDF on all three platforms keeps the formats interoperable. Android's existing 210,000-iteration v1 backups still restore. |
| Integrity | GCM tag, plus a SHA-256 per category inside the plaintext manifest, checked **before** anything is imported. |

### Vault key
- A random 256-bit key is generated on the **first** device when you turn on sync. It is stored on each device only in OS-protected storage:

| Platform | Where the vault key is kept |
|---|---|
| Android | Keystore-sealed |
| Windows | DPAPI (current user) |
| Web | Non-extractable `CryptoKey` in IndexedDB. Honest limit: clearing site data deletes it, and the Recovery Key is then needed. |

- **On Drive** there is only `vault.json`. It holds the vault key **wrapped** (AES-GCM) by a key derived from the Recovery Key. The plaintext key and the Recovery Key are never uploaded.

### Recovery Key (recommended over a user-chosen passphrase)
- **What it is:** a generated 26-character code (about 130 bits), shown once, with "I've written it down" and print/save prompts.
- **Why not a passphrase:** the wrapped key sits on Drive. If the Google account were ever compromised, a human passphrase could be brute-forced offline; a 130-bit code can't.

### Adding a device (pairing without connected devices)
1. Connect Google Drive on the new device.
2. It finds `vault.json` and asks for the Recovery Key. The key is entered by typing it, or by scanning a QR code that an existing device shows **on its own screen** (camera only, no network between devices).
3. It unwraps the vault key, checks it against the vault's key-check value, and registers itself in `devices.abx` (name, platform, created, last sync).

**Wrong Recovery Key:** "That key doesn't open this vault". Nothing is stored. Attempts are rate-limited per session.

### Device identity and revocation
- **Identity:** each device has a random device id, plus a per-device **sequence counter** for its op files.
- **What authentication means here:** GCM authenticates "written by a holder of the vault key". It does not prove which device wrote a file.
  - Per-device signatures (Ed25519) would add that, but Ed25519 in WebCrypto isn't available in all target browsers yet.
  - Proposed as a later option.
- **Revoke device:**
  1. Marks the device revoked in the registry.
  2. **Rotates the vault key** (new key epoch): new files are encrypted with the new key, and the new key is wrapped with the Recovery Key.
  3. Other devices pick up the new epoch on their next sync. This requires entering the Recovery Key once, because they can't receive keys from each other directly.
- **Honest limits of revocation:**
  - A revoked device keeps whatever it already downloaded.
  - It can still reach Drive if it remains signed in to your Google account. You remove that in your Google account's security page; the UI links there.

### Lost every device
Install the app, connect Drive, enter the Recovery Key: all synced data comes back. Without the Recovery Key, synced data **cannot** be recovered. This is stated plainly before you turn sync on.

### Passphrase-protected backups
Separate from sync, by design: a backup is usable without the vault, without sync and without Google. A backup has its own passphrase (minimum 10 characters, with a strength meter), or can use "Use my Recovery Key".

---

## 4. Synchronization protocol (format `abhify-sync` v1)

**Canonical record** (identical JSON on all platforms):
```json
{"rid": "uuid", "kind": "memory", "ver": {"dev": "<device id>", "n": 42}, "base": {"dev": "...", "n": 41},
 "updated_at": 1763900000000, "deleted": false, "data": { ...kind-specific, platform-neutral fields... }}
```
- **`rid`:** a stable id per record. Android reuses `uid`. Windows and Web get a mapping table (below), so existing rows don't change.
- **`ver` / `base`:** the version this change creates and the version it was based on. A change whose `base` isn't the receiver's current version, while the receiver has its own unsynced change, is a **conflict**.

**Op file** `Sync/ops/<device>/<seq>.abx`:
- **Created by:** one Sync Now on one device.
- **Contents:** the records changed since that device's last upload, encrypted as one blob, with the AAD header from §3.
- **Never edited or overwritten.** Each device writes only its own folder, so two devices can never overwrite each other on Drive.

**Sync Now** (always user-initiated; the engine never runs on a timer):
1. **Collect.**
   - Compare each eligible local row's content hash with the hash stored at its last sync (`sync_state`). This needs no triggers and no changes to existing write paths.
   - Changed rows become records.
   - Rows that disappeared become **tombstone candidates**.
2. **Download.** List the other devices' op folders; fetch files with `seq` > the last seen for that device (`sync_seen`). Files are fetched one by one, verified (GCM tag plus manifest hashes), and kept in a local staging folder. A half-downloaded set is simply resumed next time.
3. **Preview.** Show:
   - **N to add / N to update / N deleted on another device / N conflicts**, per category;
   - **N local changes to upload**;
   - **N local deletions to send**. Deletions are listed explicitly and need a tick.

   "Upload only" and "Download only" are the same flow restricted to one direction.
4. **Apply** (one database transaction per platform):
   - add and update non-conflicting records;
   - a remote tombstone **moves the local record into "Recently deleted (sync)"** for 30 days (restorable), instead of deleting it;
   - conflicts go to the **Resolve conflicts** list:
     - **Keep this device's version / Keep the other version / Keep both** (keep both = a copy with a new `rid`);
     - nothing is resolved automatically.
5. **Upload.** One op file with the local changes, using the existing resumable upload (Android) or its ports. `seq` and `sync_state` are committed **only after** Drive confirms the upload, so an interrupted upload is retried with the same `seq` and contents.

**Robustness:**
- **Duplicates:** an op file is applied once (`sync_seen`), and applying a record is idempotent (same `rid` + `ver` = no-op).
- **Retries:** transient Drive errors back off. An expired token stops cleanly and asks you to reconnect, keeping everything staged.
- **Quota:** "Your Google Drive is full", with nothing changed.

**Compaction (later, manual):** "Tidy sync data" writes one encrypted snapshot and moves op files older than every device's last sync to Drive's trash. It is never automatic.

**Local history:** a `sync_history` row per Sync Now (time, direction, counts, result, error), shown in the UI.

### What is synced (eligible categories; each one is chosen by you, all off by default)

| Category | Android tables | Windows tables | Web stores | Phase |
|---|---|---|---|---|
| Conversations and messages | `conversations`, `messages` | `conversations`, `messages` | `messages` (grouped by `cid`) | 1 |
| Personal memory | `memories` | `memories` (category, pinned) | `memories` | 1 |
| Portable preferences | whitelist | whitelist | whitelist | 1 |
| Notes, tasks, projects | `workspaces`, `notes`, `tasks` | `data/projects.json`, `data/tasks.json` | n/a | 2 |
| Study progress and notes | `study_plans`, `plan_items`, `flashcards`, `study_sessions` | learning tables | n/a | 2 |
| Document and knowledge **metadata** | `library_docs` (name, size, sha256, tags), `vault_items` | `documents_v4` metadata | n/a | 2 |

**Portable preferences** default to theme, text size, personality and creativity. They exclude the assistant name, the voice and the online-AI switches, because those stay per device under your independence rule unless you tick them.

**Never synced:**
- model files, model choice, hardware or runtime settings, Ollama configuration;
- API keys, OAuth tokens, passwords, vault and recovery material;
- caches, logs, embeddings and indexes (each device rebuilds its own);
- document **file contents** (metadata only). Contents could be a later opt-in.

---

## 5. Backup and restore (format `abhify-backup` v2, the same on all three platforms)

- **File:** `*.abx` = header `{format, version: 2, platform, app_version, schema, created, kdf, salt, iv}` + AES-256-GCM body (AAD = header). The body is a manifest (categories, counts, SHA-256 per category) plus canonical records (§4). **Always encrypted.**
- **Portable:** a backup made on Windows restores on Android or the Web, and vice versa (manual file transfer or Drive). It works with no sync, no vault and no network.
- **Create backup:** choose categories → passphrase → save to a file and/or upload to `Drive/Backups` (manual only).
- **Download backup:** from Drive history to a local file.
- **Backup history:** local backups and the Drive `Backups/` folder.
- **Preview restore:**
  - decrypts, then verifies the tag and every category hash, and checks compatibility (format ≤ 2, schema, newer-app warning);
  - shows per category: new / already present / differs from this device;
  - the live database is not touched until you confirm.
- **Restore Selected Data / Restore Complete Backup:**
  - **additive by default**: the existing Android engine, generalized;
  - "Replace records that differ" is a separate, explicitly confirmed option. Replaced versions go to "Recently deleted (restore)".
  - **Windows** restore runs in a transaction after a pre-restore copy of `abhify.db`. The existing full-folder ZIP restore (staged, current data moved aside) stays for whole-device recovery and gains optional encryption.
- **Compatibility:**
  - Android v1 backups keep restoring. Plaintext v1 files are allowed **from a local file only**; they are never accepted from or sent to Drive.
  - From this build on, **every Drive upload of a backup is encrypted**.

---

## 6. User interface: More → Settings → Data & Sync

**A. Google Drive.**
- Connect, Disconnect (revokes the grant; files kept), Reauthorize.
- Status (account email, folder, last error).

**B. Manual synchronization** (visible only after the vault is set up).
- **Sync Now**, Upload local changes, Download remote changes, Preview, Resolve conflicts (count badge).
- Last sync (time, result, counts), sync history.
- Category checklist with "what's included / never included".

**C. Backup & Restore.**
- Create encrypted backup, Download backup, Preview restore, Restore (selected or complete), Backup history.

**D. Privacy & Security.**
- Encryption status (vault id, key epoch, algorithm).
- Authorized devices (name, platform, last sync), Revoke device.
- Recovery Key: show-once at setup, "Verify my Recovery Key", print/save, plain-language guidance.
- **Disconnect**: Keep data on Drive / Move Abhify sync data to Drive trash. Typed confirmation; Drive trash is recoverable for 30 days.

**Platform notes:**
- **Android:** these screens replace the existing Drive and Backup cards under one "Data & Sync" entry. Existing Drive features (documents, imports, exports, folder migration) move under section A.
- **Windows:** new More tile → Data & Sync.
- **Web:** the same screen, with a browser-limits note (§7).

No timers, no background jobs, no automatic upload. Android's WorkManager is used only to **finish** an upload you started if the app is backgrounded (existing behaviour), never to start one.

---

## 7. Platform specifics

**Android**
- Reuses `DriveApi`/`DriveAuth` as they are. Vault key in Keystore. DB **v18** (additive).
- Sync and restore run in a foreground coroutine while the screen is open. Leaving mid-sync leaves a consistent state: staging is resumable and `seq` is committed only after upload.
- The `uid` columns are reused. Tables without a uid get one in v18: an additive column, backfilled with random uuids exactly like the v3 migration did.

**Windows**
- **OAuth:** "Desktop app" OAuth client, system browser, **loopback redirect + PKCE** on a one-time random port handled by the backend.
  - The redirect checks `state`, and the route is local-only and protected like other admin routes.
  - Google documents the desktop client secret as non-confidential; it's stored in the app config, not treated as a secret.
- **Credential storage:** refresh token and vault key encrypted with **DPAPI** (`CryptProtectData` via `ctypes`, no dependency). Never logged; redacted in diagnostics.
- **Drive client:** a Python port of `DriveApi`: same folder rules, resumable upload, error mapping.
- **AES-GCM:** needs a library (§11 decision 1).
- **Database:** schema **v7**, additive tables only (§8).

**Web (standalone)**
- **No backend, ever.**
- **Sign-in:** Google Identity Services **token model** in the browser. It gives a ~1-hour access token held in memory, and **no refresh token**.
  - You approve a Google pop-up once per session when you press Sync Now.
  - Requires a **Web** OAuth client whose *authorized JavaScript origins* list every place you host the Web app (plus `http://localhost` for testing). The Web app syncs only from those origins.
  - Loads Google's script `https://accounts.google.com/gsi/client` **only when you press Connect**. That is the first external script in the standalone build; the CSP gets exactly that origin plus `www.googleapis.com`.
- **Crypto:** WebCrypto. IndexedDB `abhify-device` **v2** adds a `rid`/`ver` to messages and memories (additive upgrade) and stores for `sync_state`, `sync_seen`, `sync_history`, `conflicts` and `recently_deleted`.
- **Limits (stated in the UI):**
  - clearing site data or private browsing removes the local vault key (the Recovery Key restores it);
  - Safari may evict storage after 7 days without use;
  - the Web app holds only conversations, messages, memories and preferences, so phase-2 categories are skipped there and labelled.

**Same Google Cloud project for all three clients.** `drive.file` access is granted per Cloud project. Files created by the Android client are visible to the Windows and Web clients only if all three OAuth clients belong to the **same** project ("Abhify AI Android").

---

## 8. Data migration plan (all additive; nothing existing is altered or deleted)

| Platform | Change | Safety |
|---|---|---|
| Windows | Schema v6 → **v7**, new tables only: <br>• `sync_state(kind, local_id, rid, ver_dev, ver_n, hash, synced_at)` <br>• `sync_seen(device, seq)` <br>• `sync_tombstones(rid, kind, ver, at)` <br>• `sync_conflicts` <br>• `sync_trash(rid, kind, data, reason, at)` <br>• `sync_history`, `sync_devices`, `backup_history` <br>Existing tables are untouched. A hard delete is detected as "rid in `sync_state`, row gone" and shown for confirmation. | The existing pre-migration DB copy (`abhify-pre-schema-v7-*.db`) is made automatically. Downgrade: older builds refuse a newer schema (existing rule). The v7 tables are only additions. |
| Android | DB v17 → **v18**: <br>• `uid` added to workspaces, notes, tasks, study, career and library tables (backfilled); <br>• new tables `vault_state`, `sync_seen`, `sync_trash`, `sync_history`. <br>The existing `sync_conflicts` and `devices` tables are reused only if their columns fit; otherwise new `vault_*` tables, so the legacy server sync isn't disturbed until you decide on it. | Same pattern as v3/v16 (tested migrations); a Robolectric migration test from v17 with real data. |
| Web | IndexedDB v1 → **v2**: new stores, plus `rid` added to existing rows in the upgrade transaction. | No data is removed; the upgrade is tested with a v1 fixture. |
| Drive | Adds `Abhify.AI/Sync/` only. Existing folders and files are untouched; legacy folder handling is unchanged. | — |
| Settings | Turning sync on asks for categories; all off by default. Upgrading never enables anything. | — |

---

## 9. Google API permission requirements

- **Scope:** **`https://www.googleapis.com/auth/drive.file` only**, the same as Android today. It covers creating, reading and listing the app's own files and folders, and `about` for the account. **No broader scope is needed.** `drive.appdata` (a hidden app folder) is an alternative, but it would hide backups from you; not recommended.
- **One-time owner setup** in the existing Cloud project (I can't do this; it needs your Google login):
  1. Create a **Desktop app** OAuth client (Windows).
  2. Create a **Web application** OAuth client with your Web-hosting origins (and `http://localhost:8080` for testing).
  3. Optionally add the release SHA-1 to the Android client when a release key exists.
- **Consent-screen status (important):**
  - **Testing:** only listed test users (max 100) can connect, and Google **expires refresh tokens after 7 days**, so Windows would ask you to reconnect weekly.
  - **In production:** any Google user can connect their own account. `drive.file` is a **non-sensitive** scope, so no security assessment is needed, though Google may ask for brand verification (app name, logo, privacy-policy URL, domain).
  - Your decision (§11).
- **Cost:** none. The Drive API is free within quotas; storage counts against each user's own Drive.

---

## 10. Files expected to change

**Windows backend (new):**
- `backend/vault_crypto.py`: AES-GCM, PBKDF2, the formats, test vectors.
- `backend/drive_client.py`: Drive REST v3, resumable upload.
- `backend/google_oauth.py`: loopback + PKCE, refresh, revoke.
- `backend/secure_store.py`: DPAPI.
- `backend/sync_records.py`: canonical record mapping per table.
- `backend/sync_engine.py`: collect, download, preview, apply, upload, conflicts.
- `backend/backup_v2.py`: encrypted portable backup, preview, selective and additive restore.

**Windows backend (modified):**
- `backend/main.py`: `/datasync/*` routes, all `local_only`.
- `backend/database.py`: v7.
- `backend/local_file_service.py`: optional encryption of the full ZIP.
- `backend/config.py`: OAuth client id.
- `packaging/windows/build.py` and `requirements.lock`: `cryptography`, if approved.

**Web:**
- New: `frontend/datasync.js` (crypto, Drive, GIS, engine).
- Modified: `frontend/ondevice.js` (IndexedDB v2), `frontend/app.js` and `frontend/index.html` (Data & Sync screens), `frontend/styles.css`, `frontend/sw.js` (cache revision; never caches Drive or Google requests).
- CSP meta in `index.html`.

**Android:**
- New: `vault/VaultCrypto.kt`, `vault/VaultKeyStore.kt`, `vault/SyncRecords.kt`, `vault/SyncEngine.kt`, `vault/BackupV2.kt`, `ui/DataSyncScreens.kt`, `chat/SchemaV18.kt`.
- Modified: `backup/Backup.kt` (read v1, write v2), `ui/V34State.kt` (Drive uploads always encrypted), `ui/ManageScreens.kt` (navigation), `AppGraph.kt`, `chat/ChatDb.kt` (v18 hook).

**Tests (new):**
- Shared fixtures `backend/tests/data/sync_vectors.json` (key derivation, encryption, AAD, record JSON) and `backup_v2_vectors.json`, checked on all three platforms.
- `test_vault_crypto.py`, `test_sync_engine.py`, `test_backup_v2.py`, `test_drive_client.py` (fake Drive server), `test_google_oauth.py`.
- `tests/js/datasync_harness.js`.
- Android: `VaultCryptoTest`, `SyncEngineTest`, `BackupV2Test`, `SchemaV18Test`.

**Docs:**
- New: `docs/DATA_SYNC_AND_BACKUP.md` (user-facing), `docs/GOOGLE_CLOUD_OAUTH_SETUP.md` (owner).
- Updated: `docs/GOOGLE_DRIVE_SETUP.md`, CHANGELOG, README.

**Not touched:**
- models, inference and runtime code;
- signing keys, `debug.keystore`, release folders;
- `companion.py` behaviour, `sync/Sync.kt` (until you decide).

---

## 11. Decisions I need from you before implementation

1. **AES on Windows.**
   - (a) **Add `cryptography`** (recommended): the de facto Python crypto library, about 3 MB wheel, also gives Ed25519 later. It's a new dependency, so it needs your approval.
   - (b) No dependency: call Windows CNG (`bcrypt.dll`) AES-GCM through `ctypes`. More custom code to get right.
2. **Google Cloud.**
   - You create the Desktop and Web OAuth clients in the existing project (I'll write the step-by-step guide).
   - Choose the consent status: **Production** (any user, no 7-day expiry; brand verification may be requested) or stay in **Testing** (only listed test users, weekly reconnect on Windows).
3. **Recovery.** A **generated Recovery Key** (recommended) or a passphrase you choose.
4. **Android scheduled backups to Drive** contradict "no automatic backup uploads".
   - (a) Remove the Drive destination from schedules and keep scheduled backups to a local folder (recommended).
   - (b) Remove scheduled backups entirely.
   - (c) Keep them as is.
5. **Legacy connected features.** The new sync replaces Android's self-hosted server sync. Retire it (and the desktop companion phone access) now, or leave both as they are (off by default)?
6. **Web external script.** Loading Google's sign-in script (only after Connect) in the standalone Web build: OK?
7. **Scope of the first build.** Phase 1 categories (conversations, messages, memory, portable preferences) plus backup v2 on all three, then phase 2 (notes, tasks, projects, study, document metadata)? Recommended, because three schemas have to be mapped and tested per category.
8. **Version.** The next development build is **7.2.0-dev.1** (new feature line) in `release/Abhify.AI-7.2.0-dev.1/`. Or do you want 7.1.0-dev.3?

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Lost Recovery Key = synced data unrecoverable | Show-once with confirmation; "Verify my Recovery Key"; local data and backups are unaffected. |
| Schema differences between platforms lose fields | Canonical records carry platform-specific extras under `data.ext.<platform>`, preserved on round trip; shared fixtures per kind. |
| Conflicts are confusing | Only true concurrent edits conflict (messages are append-only); clear side-by-side choice. |
| Accidental mass deletion propagates | Deletions need an explicit tick in the preview, land in a 30-day "Recently deleted", and are reported as counts. |
| Testing-mode refresh-token expiry and the 100-user limit | Decision 2. |
| Web storage eviction | Recovery Key; UI warning. |
| Revocation can't erase data already on a device | Stated plainly; key rotation protects future data. |
| Drive rate limits on large first syncs | Batched op files (≤ 5 MB each), backoff, resumable. |
| A Python AES implementation differs from Kotlin or JS | Byte-exact shared test vectors, plus cross-platform decryption tests in CI. |
| Weakening security | New routes are local-only behind the same CSRF/Host guard; tokens are never logged (a test greps logs); no telemetry. |

---

## 13. Testing plan (each result labelled Automated / Emulator / Sandbox / Physical; nothing is claimed beyond what is run)

| Test | How |
|---|---|
| Crypto interop: Python ↔ Kotlin ↔ JS decrypt each other's files; tampered byte / wrong key / wrong AAD → refused | **Automated:** shared vectors in pytest, JUnit and Node. |
| First / second device authorization, wrong Recovery Key, rotation after revoke | **Automated** with a fake Drive (an in-memory HTTP double of the REST v3 subset); **Physical** on your phone and PC. |
| Windows → Android and Android → Windows, Web interop | **Automated:** three engines against one fake Drive in one test. **Emulator:** Android emulator against real Drive with a test account. **Physical:** your devices. |
| Duplicate sync, replay of an op file, same op twice | **Automated.** |
| Simultaneous edits → conflict → each resolution choice | **Automated.** |
| Interrupted upload/download (cut mid-chunk), resume, expired token (401 mid-sync), quota (403 storageQuota), no network | **Automated** (fake Drive fault injection). |
| Backup v2: create, preview, selective, complete, additive twice = no-op, replace with confirmation, cross-platform restore, v1 Android backups still restore, plaintext refused on Drive | **Automated.** |
| Existing data preserved across the v7 / v18 / IndexedDB v2 upgrades | **Automated** migration tests; **Sandbox:** upgrade from 7.1.0-dev.2 with data. |
| Offline operation unchanged; no network call unless a Data & Sync button is pressed | **Automated:** network-deny test plus a request log in headless Chrome. |
| Full regression: backend, Android unit and lint, browser checks | **Automated.** |
| Real Google OAuth on Windows and Web | Needs the OAuth clients from decision 2, then **Physical**/manual. Until then, reported as **Not verified**. |

**Protection rules for the implementation:**
- no existing data deleted;
- old release packages untouched;
- signing keys untouched;
- no external AI API;
- no telemetry;
- no automatic sync or backup;
- no commit, tag, push, merge or publish without approval;
- tests use an isolated `ABHIFY_DATA_DIR` and a Google test account you provide, never your real data.
