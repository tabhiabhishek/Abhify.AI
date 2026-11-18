# Android implementation plan: independent local-first hybrid app (2026-11-02)

Follows the approved architecture: **local profile and/or online account, optional cloud sync (off by default), hybrid AI (Auto / On-device / Online).** Based on [ANDROID_ARCHITECTURE_AUDIT.md](ANDROID_ARCHITECTURE_AUDIT.md).

## Decisions (with reasons)

| # | Decision | Reason |
|---|---|---|
| D1 | **Keep SQLiteOpenHelper with explicit versioned migrations; no move to Room.** | Phones (including the owner's K50i) already have a v2 database that Room didn't create. Moving it under Room's schema validation is a needless risk to existing chats, and the spec forbids replacing frameworks without a measured reason. Migrations stay explicit, additive and tested; DAO-style data access lives in `ChatDb`. |
| D2 | **No SQLCipher in this phase.** | **Encryption boundaries:** tokens are encrypted with an Android Keystore AES-256-GCM key; the database is in app-private storage (Android file-based encryption at rest, sandboxed per app) and excluded from backups. The SQLite file itself is **not** encrypted by the app. SQLCipher is a later option (adds ~4 MB per ABI and a key-management migration). |
| D3 | **Online accounts live in a separate `cloud/` FastAPI service.** | The desktop backend is single-user by design; its Ollama and localhost service are never exposed. |
| D4 | **Passwords: scrypt (N=2¹⁵, r=8, p=1).** | A memory-hard KDF recommended alongside Argon2id (OWASP), available in Python's standard library with no new native dependency. The hash format is versioned, so a later move to Argon2id (`argon2-cffi`) re-hashes on next login. |
| D5 | **Tokens are opaque** (256-bit random, SHA-256-hashed at rest), not JWTs. | Instantly revocable; a database leak gives no usable tokens. Access 15 min; refresh 30 days, rotated, with reuse detection that ends the session. |
| D6 | **The sync queue is per-record dirty flags plus a cursor in `sync_metadata`.** | Equivalent to a SyncQueue table but can't diverge from the data. Retries are idempotent. |
| D7 | **Conflicts are never silently discarded.** | Deterministic last-writer-wins by (updated_at, device_id) decides what's shown, but the losing version of an edited memory or title is kept in `sync_conflicts`. The user resolves it in Sync settings (keep mine / keep theirs). Messages are append-only, so they don't conflict. |
| D8 | **Privacy: online AI is off until the user allows it.** | Auto falls back to the phone when online processing isn't allowed. **Memories are not sent to online AI** unless separately allowed. Documents are never uploaded. |
| D9 | **Cloud sync is off by default**, with per-kind toggles (conversations, memories, preferences) and Wi-Fi-only. | As approved. Linking shows exactly what would sync, with counts, before anything is enabled. |
| D10 | **No production server URL in the app.** | The app ships with an empty server setting. Online features show "pending deployment" until the owner deploys `cloud/` (recommended: an Oracle Cloud Always Free ARM VM with the `cloud/deploy` Docker files). |

## Database v3 (additive migration from v2; no table dropped)
- **New columns on `conversations`, `messages`, `memories`:**
  - `uid` (stable id, backfilled), `profile_id`, `deleted` (tombstone), `dirty` (sync status);
  - `updated_at` on messages and memories (backfilled from `created_at`);
  - `model` on messages, which records the provider and model of every answer.
- **New tables:**
  - `local_profiles`: the default profile is created from the existing user name; all existing rows belong to it;
  - `linked_accounts`, `devices`, `user_preferences`, `sync_metadata`, `sync_conflicts`;
  - `model_registry`: mirrors `models/state.json` for listing; the file stays authoritative;
  - `document_metadata`: reserved, empty.
- **Tests:** an instrumented test builds a real v2 database (v1 → v2 data), opens it as v3, and checks that every row survived with ids, timestamps and its profile.

## Phases (each ends with a report: files, tests, limitations)
1. **Phase 2/3 core (data + local profile + account):**
   - DB v3, local profile, linking/unlinking;
   - `CloudClient` (done) plus the `/devices` aliases;
   - Keystore token vault (done).
2. **Phase 4 (cloud):**
   - `/api/v1/devices`, `/api/v1/models` and `/api/v1/ai/chat/stream` (alias), `memories_allowed` handling;
   - structured JSON logs;
   - deployment docs.
3. **Phase 5 (hybrid):**
   - `HybridAi` (done) gains the online-processing consent, the memory-sharing consent, and provider metadata;
   - tests for routing, fallback, cancellation and labels.
4. **Phase 6 (sync):** off by default; per-kind and Wi-Fi-only settings; conflict preservation and resolution UI; tests with fakes and against the real `cloud/` service.
5. **Phase 1 (UI):** web-token theme, Inter, splash, onboarding (local profile / sign in / register), home, chat (Markdown, code highlighting, copy, stop, regenerate, provider badge), history, memories, models, account, network/sync, privacy, settings, about.
6. **Phase 7 (internet features):** news (exists), web search (via the cloud service, later), clear separation of "internet" from "server reachable".
7. **Phase 8/9 (tests and docs):**
   - JVM tests;
   - cloud tests;
   - a cloud↔Android-client integration test (the JVM client against a real local `cloud/` server);
   - instrumented migration test;
   - debug APK;
   - docs: `ANDROID_ARCHITECTURE`, `AUTHENTICATION`, `SYNC`, `PRIVACY`, `DEPLOYMENT`.

## Not possible without the owner
- **Production hosting** (account, domain, card verification), **SMTP**, and the **release signing key** (to be created on the owner's laptop).
- **Physical-device tests:** the phone is attached to the owner's laptop, so the owner runs the provided test kit.
- **Online features on the phone:** stay "pending deployment" until a server exists, and are never claimed as working before that.
