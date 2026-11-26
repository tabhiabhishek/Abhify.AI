# Abhify.AI vault, sync and backup formats: version 1 (7.2.0-dev.1)

**What this is:** the normative specification. The Python reference is `backend/vault_crypto.py` and `backend/sync_core.py`. Ports (Kotlin `android/.../vault/VaultCrypto.kt` + `SyncCore.kt`, JS `frontend/vault.js` + `frontend/sync-core.js`) must pass the shared vectors in `backend/tests/data/vault_vectors.json` and `backend/tests/data/sync_cases.json`.

**Conventions:**
- **b64** = standard Base64 with padding (RFC 4648 §4).
- **ms** = Unix epoch milliseconds (integer).
- JSON is UTF-8.
- **AAD** strings are built exactly as written, joined with `|`, integers in decimal, encoded as UTF-8.
- There are no custom primitives; everything uses the platform's vetted implementations:
  - **Python:** `cryptography` (AESGCM, PBKDF2HMAC);
  - **Kotlin:** JCA `AES/GCM/NoPadding` and `PBKDF2WithHmacSHA256`;
  - **Browser:** WebCrypto `AES-GCM` and `PBKDF2`.

## 1. Primitives

| Name | Definition |
|---|---|
| `AEAD(key, iv, pt, aad)` | AES-256-GCM, a 12-byte random IV, 128-bit tag **appended** to the ciphertext (`ct = c ‖ tag`). Readers refuse an IV that isn't 12 bytes, or a `ct` shorter than 16 bytes, as `Malformed`. |
| `KDF(password, salt, iter)` | PBKDF2-HMAC-SHA256 → 32 bytes. `password` is the UTF-8 string. `salt` is 16 random bytes. |
| `iter` bounds | `iter` must be a JSON integer (not a string or boolean); salt at least 16 bytes. A reader refuses `iter < 100000` or `iter > 5000000`. Writers use **600000** for passphrases and **100000** for Recovery Keys (160-bit random input, so a low count is safe). |
| gzip | Plaintext bodies are gzip (RFC 1952) of the JSON. Writers set the header OS byte to 255 (unknown). Readers must accept any valid gzip. Decompressed size is capped at **256 MiB**. A decrypted body that isn't a JSON object is `Malformed`. |

## 2. Recovery Key

1. `raw` = 20 random bytes (160 bits).
2. `body` = Crockford base32 of `raw`, MSB first: alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, exactly 32 characters.
3. `check` = the first 4 characters of the Crockford base32 of the first 3 bytes of `SHA-256(raw)` (20 bits).
4. **Canonical form:** `body ‖ check` (36 characters).
5. **Display form:** 9 groups of 4 characters, joined by `-`.
6. **Normalization on input:**
   - upper-case;
   - remove spaces, `-` and `_`;
   - map `O → 0`, and `I`, `L` → `1`.

   The result must be 36 alphabet characters with a matching check, otherwise the error is "typo" (shown without any network call).
7. **Key encryption key:** `KEK = KDF(canonical, vault.kdf.salt, vault.kdf.iter)`.
8. The Recovery Key is **never** stored or uploaded. It is displayed once, and can be typed in or transferred as a QR code (§7).

## 3. `vault.json` (Drive: `Abhify.AI/Sync/vault.json`; the only mutable file)

```json
{"format":"abhify-vault","v":1,"vault_id":"<32 hex>","created":<ms>,"epoch":<int ≥1>,
 "kdf":{"alg":"PBKDF2-SHA256","iter":100000,"salt":"<b64>"},
 "wrap":{"iv":"<b64>","ct":"<b64>"},
 "chain":[{"epoch":<int>,"iv":"<b64>","ct":"<b64>"}]}
```

- **`wrap`:** `AEAD(KEK, iv, VK_epoch, aad = "abhify-vault|1|<vault_id>|<epoch>|<iter>|<salt b64>")`. `VK` is 32 random bytes.
- **`chain`:** every older epoch's key, each one `AEAD(VK_current, iv, VK_old, aad = "abhify-vault-chain|1|<vault_id>|<old epoch>|<current epoch>")`. Empty at epoch 1.
- **Unwrap failure** means the wrong Recovery Key or a modified file. The two can't be told apart, and the user sees "That Recovery Key doesn't open this vault".
- **Rotation** (revoking a device) needs the Recovery Key:
  1. new `epoch = old + 1`;
  2. new `VK`;
  3. new salt and wrap;
  4. `chain` = all previous keys rewrapped under the new `VK`.

## 4. Op file (Drive: `Abhify.AI/Sync/op-<device>-<seq, 8 digits>.abx`; immutable)

```json
{"format":"abhify-sync-ops","v":1,"vault_id":"…","epoch":<int>,"device":"<16 hex>","seq":<int ≥1>,"created":<ms>,"iv":"<b64>","ct":"<b64>"}
```

**Encryption:**
- `ct = AEAD(VK_epoch, iv, gzip(body), aad = "abhify-sync-ops|1|<vault_id>|<epoch>|<device>|<seq>|<created>")`.
- `body = {"device":…,"seq":…,"records":[record…]}`. `device` and `seq` must equal the header, otherwise the file is refused.

**Writing:**
- A device writes only its own names.
- `seq` increases by exactly 1 per Sync Now that has changes.
- A file is never replaced.
- If a file with this device's next name already exists and differs from the local outbox copy, the installation's sync identity is also used elsewhere (for example a copied data folder). The user is told to reset this device's sync identity; nothing is overwritten.

**Reading:**
- For each other device, fetch `seq` = last applied + 1, +2, and so on. Names are matched with ASCII digits only; a name listed twice counts once.
- A gap (a missing file) stops reading that device for this run. It is retried next time, never skipped.

## 5. Records

```json
{"rid":"<id>","kind":"conversation|message|memory|pref|device","ver":{"dev":"<device>","n":<int ≥1>},
 "base":{"dev":"…","n":…}|null,"at":<ms>,"del":false,"data":{…}}
```

**`rid`:** 8–80 characters of `[A-Za-z0-9_.:-]`. `pref` records use `pref:<key>`; `device` records use the device id.

**`data` per kind:** fields not listed below are dropped on import (v1 keeps no unknown fields). Integers are JSON integers; booleans are never accepted where a number is required.

| kind | data |
|---|---|
| `conversation` | `title` (string ≤ 500), `created` (ms), optional `pinned` (bool), `archived` (bool) |
| `message` | `conv` (rid of its conversation), `role` (`user`/`assistant`), `content` (string ≤ 1,000,000), `created` (ms), optional `model` (string ≤ 200) |
| `memory` | `content` (string ≤ 20,000), `created` (ms), optional `category` (string ≤ 40), `pinned` (bool) |
| `pref` | `key` ∈ {`user_name`, `theme`, `answer_detail`, `answer_depth`, `answer_style`, `answer_language`}, `value` (string ≤ 200). Each platform applies only the keys it has. |
| `device` | `name` (≤ 80), `platform` (`windows`/`android`/`web`), `added` (ms), optional `revoked` (bool), `revoked_after_seq` (int) |

**Tombstones:** a record with `del: true` keeps `rid`, `kind`, `ver` and `base`. Its `data` may be `{}`.

## 6. Versions, conflicts and deletions

Each device keeps per `rid`:
- `ver`: the last version applied or created here;
- `hist`: up to 32 earlier version ids (`"<dev>:<n>"`);
- `hash`: SHA-256 of the local canonical data when last synced (local only).

**Local change:** `ver = {dev: me, n: max(ver.n, highest n seen for this rid) + 1}`, `base = previous ver` (null for new records).

**Incoming record R, local state S:**

| Situation | Action |
|---|---|
| no S, not deleted | **add**, unless a local record of the same kind has the same natural key (memory: trimmed, case-folded content; message: conv + role + created + content). Then it's **alias**: state recorded, no new local row. |
| no S, deleted | record the tombstone state only |
| `R.ver` = `S.ver`, or `R.ver` ∈ `S.hist` | **skip** (duplicate or stale) |
| `R.base` = `S.ver` and local is unchanged | **update**, or **delete** if `R.del` |
| `R.base` = `S.ver` and local changed since last sync | **conflict** |
| otherwise (concurrent), same data | **adopt**: no change; the higher version becomes `S.ver`, the other goes into `hist` |
| otherwise (concurrent), different data | **conflict** |

**Deletion is never silent:**
- An applied remote delete moves the local record into **Recently deleted (sync)**, which keeps the full data and can be restored for 30 days.
- A **local** deletion becomes a tombstone only after the user ticks it in the preview.

**Conflict choices:**

| Choice | Result |
|---|---|
| **Keep this device's** | A new local version `{me, max(n) + 1}` with `base = R.ver`. |
| **Keep the other** | `R` applied; the local version goes to Recently deleted. |
| **Keep both** | `R`'s data becomes a new record (new rid). The local record gets a new version with `base = R.ver`. |

**Ordering in one run:** `device`, `pref`, `conversation`, `memory`, `message`; within a device by `seq`. A message whose conversation isn't known yet is held and retried next run; it is never attached elsewhere.

**Revocation:** a `device` record with `revoked: true` and `revoked_after_seq: k`. Ops from that device with `seq > k` are ignored.

## 7. QR transfer of the Recovery Key

- **Content:** `abhify-rk:1:<canonical 36 chars>`.
- **Where it appears:** only on the screen of a device that has just shown or verified the Recovery Key, after the user taps **Show QR**. It clears after 60 s or when the screen is left.
- **Where it travels:** nowhere over a network.

## 8. Backup file v2 (`*.abx`; portable between Windows, Android and Web)

```json
{"format":"abhify-vault-backup","v":2,"platform":"windows|android|web","app_version":"…","created":<ms>,
 "kdf":{"alg":"PBKDF2-SHA256","iter":600000,"salt":"<b64>"},"iv":"<b64>","ct":"<b64>"}
```

- **Encryption:** `ct = AEAD(KDF(passphrase, salt, iter), iv, gzip(body), aad = "abhify-vault-backup|2|<platform>|<app_version>|<created>|<iter>|<salt b64>")`.
- **Body:** `body = {"manifest":{"categories":[…],"counts":{"<kind>":n},"platform":…,"app_version":…,"created":…},"records":{"<kind>":[record…]}}`.
- **Passphrase:** at least 10 characters (a Recovery Key may be used as the passphrase).
- **Integrity:** the GCM tag authenticates the header and the whole body. Counts must match the records.
- **Restore:**
  - additive by default, using the same natural keys and rids as §6;
  - replacing a record that differs needs an explicit choice, and the replaced version goes to Recently deleted;
  - always previewed first;
  - applied in one transaction after the file is fully verified.

## 9. Not in any file

Model files, model or hardware settings, runtime configuration, API keys, OAuth tokens, passwords, the vault key (except wrapped as in §3), the Recovery Key, caches, logs and embeddings.
