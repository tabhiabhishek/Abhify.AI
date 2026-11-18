# Abhify: deploying the online service and configuring the app

> **Status: not deployed.** No server, domain, SMTP account or release signing key exists yet. The app ships with **no server address**; online features say "pending deployment". Everything below needs the owner's accounts and approval.

## Recommended hosting (independent of the development PC)
One small Linux VM you own runs everything: **Caddy** (automatic HTTPS) → **cloud API** → **Ollama**, with Ollama on a private Docker network.
- **$0 option:** Oracle Cloud "Always Free" ARM (Ampere A1, up to 4 OCPU / 24 GB RAM). It runs `qwen2.5:3b` on CPU at usable speed. It needs an Oracle account (card verification) and a domain or subdomain that points to the VM.
- **Any VPS** with ≥ 8 GB RAM works the same way (more RAM allows a larger model).

## Steps (on the server)
```bash
git clone <your repo> && cd Abhify.AI/cloud/deploy
cp .env.example .env            # set ABHIFY_DOMAIN, ABHIFY_CLOUD_PUBLIC_URL (https://…), SMTP_*, AI model
docker compose up -d            # Caddy obtains the TLS certificate automatically
docker compose exec ollama ollama pull qwen2.5:3b
curl https://<your-domain>/api/v1/health      # {"status":"ok", "ai_available": true, ...}
```
- **Ports:** open only 80/443. The API (8100) and Ollama (11434) are never published (`expose`, not `ports`).
- **Production guards:** the service refuses to start without an `https://` public URL and SMTP (`ABHIFY_CLOUD_ENV=prod` is set by the compose file).
- **Backups:** the `api-data` volume holds `abhify-cloud.db` (SQLite, WAL). Back it up with `sqlite3 .backup` or a volume snapshot.
- **Scaling note:** the rate limiter is per-process. Run one API instance, or move limits to Redis before scaling out.

## Configuration reference (`ABHIFY_CLOUD_*`)
| Variable | Default | Meaning |
|---|---|---|
| `ENV` | `dev` | `prod` enables the guards and HSTS |
| `PUBLIC_URL` | — | `https://…` (required in prod) |
| `DATA_DIR` | `./data` | database and dev mail outbox |
| `TRUST_PROXY` | `false` | `true` behind Caddy (client IP from X-Forwarded-For) |
| `OLLAMA_URL` / `AI_MODEL` | `http://127.0.0.1:11434` / `qwen2.5:3b` | the private AI runtime |
| `ACCESS_TOKEN_MINUTES` / `REFRESH_TOKEN_DAYS` | 15 / 30 | token lifetimes |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM` | — | password-reset email (required in prod) |
| `USER_QUOTA_BYTES`, `SYNC_MAX_CHANGES`, `SYNC_MAX_RECORD_BYTES` | 50 MB, 500, 64 KB | limits |

## API (v1)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/health` | — | status, version, AI availability |
| POST | `/api/v1/auth/register` | — | create an account → user + tokens |
| POST | `/api/v1/auth/login` | — | → user + tokens (new device session) |
| POST | `/api/v1/auth/refresh` | refresh token | rotate → new tokens (reuse revokes the session) |
| POST | `/api/v1/auth/logout` | ✔ | revoke this session |
| POST | `/api/v1/auth/forgot-password` | — | send a reset code (same answer for any email) |
| POST | `/api/v1/auth/reset-password` | — | code + new password → ends all sessions |
| POST | `/api/v1/auth/change-password` | ✔ | ends other sessions |
| GET / PATCH | `/api/v1/users/me` | ✔ | profile / display name |
| POST | `/api/v1/users/me/delete` | ✔ + password | delete the account and cloud data |
| GET | `/api/v1/devices` (= `/users/me/sessions`) | ✔ | signed-in devices |
| DELETE | `/api/v1/devices/{id}` (= `/users/me/sessions/{id}`) | ✔ | sign a device out |
| GET / POST | `/api/v1/sync/changes` | ✔ | pull since cursor / push changes |
| DELETE | `/api/v1/sync/data` | ✔ | delete all cloud copies |
| GET | `/api/v1/models` | ✔ | the online model offered |
| POST | `/api/v1/ai/chat` (= `/ai/chat/stream`) | ✔ | streamed NDJSON: `token`… then `done` (model, served_by) or `error` |

Errors are `{"detail": "<user-readable message>"}` with 401 / 404 / 409 / 413 / 422 / 429 (with `Retry-After`).

## Pointing the app at the server
- **Per user:** on *Sign in* / *Create account*, or in *Settings → Abhify server*, enter `https://<your-domain>`.
- **Preconfigured build:** `gradlew assembleRelease -Pabhify.cloudUrl=https://<your-domain>`. Only after the server exists; never a guessed URL.

## Android release (needs the owner)
1. **Create the release key on the owner's laptop** (never on a shared machine):
   `keytool -genkeypair -keystore abhify-release.jks -alias abhify -keyalg RSA -keysize 4096 -validity 36500`.
   Back up the `.jks` file and its password; losing them means updates can't be installed.
2. **Build unsigned:** `gradlew assembleRelease` gives `app-release-unsigned.apk`.
3. **Sign on the laptop:** `zipalign -P 16 -f 4 in.apk aligned.apk` then `apksigner sign --ks abhify-release.jks --out Abhify-<ver>.apk aligned.apk`, then `apksigner verify --print-certs`.
4. **Publish** the APK and its SHA-256. Optionally publish a signed update manifest (`abhify-android-update-1`) and build the app with `-Pabhify.updateUrl` / `-Pabhify.updateKeys`.

**Note:** debug builds from this PC are signed with this PC's debug key. A release-signed APK can't install over them; uninstall first, and local data is lost unless it was synced.
