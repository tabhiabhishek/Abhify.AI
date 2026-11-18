# Phase 7: reliability, performance, security

**Status:** ongoing.

## Reliability
- **Database:** additive migrations v2→v17, each tested on populated SQLite. The database is never reset.
- **Backup restore** is additive, idempotent and integrity-checked. **4.4 hardening:** export and restore skip tables that one side doesn't have, instead of failing.
- **Threading:** work runs on IO dispatchers; WorkManager runs background work with network and battery constraints.

## Performance (measured or reviewed)
- Markdown styling is cached per text.
- The document text viewer renders lazily.
- The PDF page viewer renders one page at a time (width capped at 2,200 px, one page open at a time).
- The model is unloaded during OCR (`HeavyGate`).
- **No phone profiling has been done in this pass.**

## Security
| Area | State |
|---|---|
| Secrets | Provider keys, server session and backup passphrase sealed with the Android Keystore (AES-GCM). Drive tokens in memory only. A secret scan of the APK and source runs on every build: **0 hits** (4.4.0). |
| Network | Release: HTTPS only (system CAs). Debug: plain http to 10.0.2.2, 127.0.0.1 and localhost only. |
| Data at rest | App sandbox; **the database isn't encrypted**; exported backups can be (AES-256-GCM, PBKDF2 210k) |
| Permissions | Unchanged since 3.4 (10, all justified in [V4_0_PRODUCTION_READINESS.md](V4_0_PRODUCTION_READINESS.md)) |
| Logs | No tokens, keys or content in logs (source checks) |

## Open
1. **`android/` and `cloud/` are not under version control.** `.gitignore` is prepared (4.4); the commit is the owner's decision.
2. Instrumented tests on a phone.
3. Lint: 86 warnings, mostly KTX style and newer-version notices.
