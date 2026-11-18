# Backup and restore (Abhify.AI Android 3.4)

Go to **Settings → Backup & restore**.

## Creating a backup
1. **Choose categories:**
   - Personal memory
   - Conversations
   - Notes and tasks
   - Knowledge
   - Documents
   - Study
   - Career
   - Settings (a safe subset of settings)
2. **Passphrase (optional, recommended).** Use at least 8 characters. The backup is then encrypted with AES-256-GCM, with the key derived from the passphrase by PBKDF2-HMAC-SHA256 (210,000 iterations, random salt).
   - **Without the passphrase, an encrypted backup cannot be restored.** Abhify does not store the passphrase.
3. **Save it:**
   - **Save to file:** choose a location (Downloads, SD card, a cloud folder in the picker).
   - **Save to Drive:** goes to `Abhify.AI/Application Backups` (see [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md)).
4. **Record.** Each backup is recorded on the phone (date, size, location), and the last one is shown.

### Never in a backup
- API keys, OAuth/Drive tokens, passwords, PINs, signing keys.
- Sync accounts and device records.
- The AI model file.
- The online-AI permission switches. After a restore, online AI must be allowed again by you.

## Format
- **The file.** `abhify-backup` version 1 is JSON with the app version, creation time, the chosen categories, and one array of rows per table. Each table has a SHA-256 checksum.
- **Encrypted backups** are wrapped as `abhify-backup-encrypted` (salt, IV, iterations, ciphertext).
- **Newer backups.** Backups from a newer format version are refused with a clear message.

## Restoring
1. **Open the backup:** pick a file, or **Download** one from the Drive screen.
   - If it is encrypted, enter the passphrase. A wrong passphrase shows "Wrong passphrase or damaged file". Nothing changes.
2. **Integrity check.** If any table's checksum doesn't match, the backup is reported as **damaged** and restore is refused.
3. **Preview.** For each table you see **new** rows and rows **already on this phone**. Choose which categories to restore, and whether to apply the saved settings.
4. **Restore.** Everything runs in one database transaction.

### Restore only adds
- **Nothing on the phone is changed or deleted.**
- **Rows already present are skipped.** A row counts as present when it has the same sync ID (`uid`) or, for tables without one, the same natural key (for example, same title + creation time).
- **Links are kept.** References between tables are remapped to the IDs on this phone: messages → conversation, notes → workspace, résumé versions → document, applications → job description / résumé.
- **Rows whose parent is missing** are skipped, never attached wrongly.
- **Restoring the same backup twice adds nothing.**

## Tests
`BackupTest` (JVM, real SQLite) covers:
- format, checksums and tamper detection
- encryption, wrong passphrase and short passphrase
- newer-version refusal
- export → restore onto a phone that already has part of the data (remapping, skipping, settings whitelist)
- idempotence
- refusal of a damaged backup without any change

## Limitations
- **Document files.** The original files of documents are not in the backup, only their extracted text and metadata. Re-import the original if you need it.
- **Memory use on large backups.** Very large backups (many thousands of messages) are built in memory, so a phone with little RAM may be slow.
