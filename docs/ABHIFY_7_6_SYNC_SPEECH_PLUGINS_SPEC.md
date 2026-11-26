# Abhify.AI 7.6: encrypted portability, offline speech and local plugins specification

**Status:** proposal.

## 1. Encrypted Google Drive portability (conditional)

### Gate

**Implementation is blocked until your drive.file cross-client check passes.**

What the check needs:
- create the Desktop and Web OAuth clients;
- run `backend/tools/drive_interop_check.py`, following `docs/GOOGLE_CLOUD_OAUTH_SETUP.md`.

Until then, only this document and the permission/security assessment below may be done.

### What already exists

| Item | State |
|---|---|
| Windows backend | Complete but hidden (`DATASYNC_VISIBLE=false`): `vault_crypto.py` (AES-GCM, PBKDF2), `sync_core.py`, `drive_client.py` (drive.file), `google_oauth.py` (loopback + PKCE), 29 `/datasync/*` routes |
| Web | `vault.js`, `sync-core.js`: precached, not wired |
| Android | `vault/VaultCrypto.kt`, `vault/SyncCore.kt`: tests only. A separate older server sync (`sync/`, off by default; decision D7) |
| Study | Merge rules in `study_sync.py` (not connected) |
| Spec | `docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md` (kinds: device, pref, conversation, memory, message) |

### Permission and security assessment (to finish before any code)

1. **Scope:** `drive.file` only (per-file access to files the app created). Confirm that each platform's client can see files created by the others (this is exactly what the check tests). If not, options are a per-platform folder, manual file export/import, or `drive.appdata` (decision).
2. **Keys:**
   - The recovery key or passphrase is derived locally (PBKDF2, or Argon2 if approved).
   - The data key is wrapped and never uploaded in plaintext.
   - Passwords never leave the device; Drive tokens stay in DPAPI or Keystore, and in memory only on Web.
3. **Threats:** see the threat model §5 (stolen Drive account, tampered bundle, rollback attack, partial upload, wrong-key restore).

### Backup manifest and restore order (proposed `abhify-bundle-2`)

| Order | Kind | Depends on | Notes |
|---|---|---|---|
| 1 | device, settings (portable prefs) | — | Whitelisted keys only |
| 2 | consent grants | settings | Re-validated on restore; online grants restored as **revoked**, so you re-confirm them |
| 3 | memories (+history) | grants | Sensitive memories restored as `proposed` for re-confirmation |
| 4 | projects, tasks | — | |
| 5 | conversations, messages | projects | |
| 6 | documents metadata (+ optional originals) | projects | Index rebuilt locally, never transferred |
| 7 | Study (courses, progress, stories, plans, mocks) | — | Uses `study_sync` merge rules |
| 8 | audit log (optional) | — | |

### Protocol change

Adding Study, projects, tasks and grants needs **protocol v2**. Your rule says not to change the encrypted sync protocol without approval, so this is decision D13.

### Requirements (after the gate)

| ID | Requirement |
|---|---|
| SP-001 | Manual "Back up now" and "Restore" only; no background sync; no silent upload |
| SP-002 | Encrypt locally before upload; authenticated encryption (AES-256-GCM) with per-bundle nonce; manifest hash chain to detect tampering or rollback |
| SP-003 | Selective inclusion per kind (default: all except documents' originals) |
| SP-004 | Restore preview (counts per kind, conflicts); local data kept until validation succeeds; staged apply as the Windows restore does today |
| SP-005 | Cancellation and resume for uploads and downloads; corruption detected via AEAD and hashes |
| SP-006 | Each platform works fully when Drive is unavailable |
| SP-007 | Delete controls: remove the app's Drive files (drive.file allows it) and the local Drive link |

## 2. Offline desktop speech (Piper TTS)

**Existing:** Whisper speech-to-text through `transcribe.py`, loaded from the local cache, with a confirmed download. It stays unchanged unless a verified bug requires otherwise. Text-to-speech today is browser `speechSynthesis` (Windows SAPI voices).

| ID | Requirement |
|---|---|
| SP-020 | The Piper runtime is bundled or downloaded **only after confirmation**, with a pinned version and SHA-256. Voice models (about 20–60 MB each) are picked from a pinned catalog showing size and licence (decision D11: runtime packaging and default voice) |
| SP-021 | `backend/tts.py`: synthesize locally into WAV/PCM, stream to the page; playback controls (play, pause, stop), cancellation |
| SP-022 | Availability checks (runtime present, voice present, audio device), clear errors, fallback to the browser voice when Piper is unavailable |
| SP-023 | Storage management: list installed voices and sizes, remove a voice |
| SP-024 | Resources: one synthesis at a time; long texts chunked (reuse `speechChunks`); CPU only by default |
| SP-025 | Privacy: no network at synthesis time (static and runtime test); no microphone involvement; existing single-voice preference respected |

## 3. Local plugins and tools

### Start narrow

The first plugin type is **declarative** tools that call an existing, permissioned app capability or a sandboxed local process. There is no marketplace, no remote code and no execution of code produced by an LLM.

**Plugin manifest (`abhify-plugin-1`):** id, name, version, description, author, `permissions[]` (consent scopes plus capabilities), `capabilities_required[]`, input JSON schema, output JSON schema, `execution: declarative | process`, `limits: {timeout_ms, max_output_kb, max_memory_mb}`, `compatibility: {app: ">=7.6", platforms[]}`, and a signature (optional, shown as "unverified" if absent).

| ID | Requirement |
|---|---|
| SP-040 | Local catalogue: install from a folder or file you choose; validation (schema, permissions known, version compatible); disabled after install until you review its permissions |
| SP-041 | Enable or disable, permission review screen, safe removal (removes plugin files and its grants; never touches core data) |
| SP-042 | Execution: inputs validated against the schema; process plugins run as a separate low-privilege process with a timeout, no inherited secrets, working directory confined, network off unless granted (Windows: Job Object limits; Android: process plugins not supported in 7.6, decision D12) |
| SP-043 | Outputs treated as untrusted (schema-validated, fenced before reaching a model) |
| SP-044 | Consequential actions need per-call approval (the approval engine from 7.4); read-only tools may be pre-approved per plugin |
| SP-045 | Execution log (local): plugin, capability, duration, result status, with no content unless you turn on debug |
| SP-046 | Failure isolation: a crash or timeout returns an error and cannot corrupt core stores (plugins have no direct database access) |

## 7.6 acceptance (release gate)

- Drive work stays blocked until the check passes; plaintext never reaches Drive (SP-002).
- Each platform stays fully usable offline and without Drive (SP-006).
- Whisper behaviour is unchanged (existing voice tests pass); Piper works with the network blocked (SP-025).
- Plugins cannot exceed their declared permissions; a failing plugin cannot corrupt core data (SP-042, SP-046).
