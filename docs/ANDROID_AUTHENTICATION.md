# Abhify Android: profiles, accounts and authentication

## Two independent ways to use the app
| | Local profile | Online account (optional) |
|---|---|---|
| Needs | nothing (works in airplane mode) | an Abhify server (`cloud/`) and internet |
| Identity | a name on this phone (`local_profiles`) | email + password on the server |
| Data | this phone only | this phone; plus cloud copies **only if sync is enabled** |
| Adds | — | online AI, multi-device sync, device management |

**First run:**
- **Start on this phone:** creates a local profile (name optional) and goes straight into the app.
- **Sign in / Create account:** needs a server address. The app ships with **none**; online features are "pending deployment" until one is configured.

## Linking (local profile ↔ account)
After a successful sign-in or registration, the **Link this phone** screen shows the counts of conversations, messages, memories and preferences on the phone.
- **Link this profile** records the link (`linked_accounts`). **Nothing is uploaded**: sync stays off until the user enables it in *Sync & network*, where the same counts are shown again before anything leaves the phone.
- **Don't link** signs out of the account. Local data is untouched.
- **Signing in again to the same account** continues without asking.
- **Signing in to a different account** starts a fresh sync position, so nothing from the previous account is merged.
- **Duplicates:** records keep their stable IDs, and the same memory text created on two devices before linking is merged into one, keeping the synced ID.
- **Unlink** (Account screen) signs out, removes the link and turns sync off. Every local record stays.

## Server-side security (`cloud/app`)
- **Passwords:** scrypt (N=2¹⁵, r=8, p=1, 16-byte salt), compared in constant time, versioned format. An unknown email costs the same time as a wrong password, and both give the same message.
  - **D4:** the spec prefers Argon2id. scrypt is the other OWASP-recommended memory-hard KDF and needs no new native dependency. Moving to Argon2id later only needs re-hashing on next login.
- **Password rules:** ≥ 10 characters, upper and lower case, at least one digit.
- **Tokens:** opaque, 256-bit random, and **only SHA-256 hashes are stored**.
  - **Access tokens** last 15 minutes.
  - **Refresh tokens** last 30 days and **rotate** on every use. Presenting an already-used refresh token is treated as theft: the whole session is revoked.
  - Logout revokes the session and all its tokens.
- **Password reset:**
  - An 8-digit code, hashed, valid 15 minutes, single use, locked after 5 wrong tries, rate-limited.
  - The same answer is returned whether or not the email exists.
  - A successful reset ends every session.
  - In development the code goes to `data/outbox.log`; production refuses to start without SMTP.
- **Change password** ends every *other* session. **Delete account** needs the password and deletes all cloud data (cascade).
- **Rate limits** (per IP and per account):
  - register 5/h;
  - login 20/5 min per IP, 5/5 min per email;
  - refresh 60/5 min;
  - forgot 10/h per IP, 3/h per email;
  - reset 20/h;
  - AI 30/min;
  - sync 120/min.
- **Authorization:** every protected route goes through one dependency (valid, unexpired access token of a live session). Every data query filters by that user's ID. Another user's session or record behaves exactly like a missing one (404 or empty).
- **Production guards:** the service refuses to start without an `https://` public URL and SMTP. It sends HSTS and `Cache-Control: no-store`. API docs are disabled.

## On the phone
- **Tokens** are sealed with an **Android Keystore AES-256-GCM** key (`KeystoreBox`) that never leaves the Keystore. A tampered or unreadable record is treated as signed out.
- **The password is never stored**, not even in memory beyond the request.
- **Refresh:** access tokens refresh automatically shortly before expiry. On a 401 the client refreshes once and retries. If the refresh token is refused, the account state becomes **"Sign-in expired"**: local AI and data keep working, and the user signs in again.
- **Network rules:** release builds talk to **HTTPS only**. Debug builds may also use `http://` to `10.0.2.2`, `127.0.0.1` or `localhost` for local testing (`src/debug/res/xml/network_security_config.xml`).

## Tested
- **Cloud service (36 tests):**
  - registration, validation, login, and identical answers for a wrong password vs an unknown account;
  - no plaintext passwords or tokens in the database;
  - login rate limits;
  - every protected route refusing no/forged/wrong-scheme tokens;
  - expiry, refresh rotation and reuse detection, logout revoking both tokens;
  - ending another session, and cross-user isolation;
  - reset flow (single use, attempt lockout), change password, account deletion;
  - production config refusal.
- **Android JVM:** `CloudClientTest` (sealed storage, tamper, refresh, retry-once, expiry, offline logout).
- **Real client ↔ real server:** `CloudIntegrationTest`, with the Kotlin client against the running service.
