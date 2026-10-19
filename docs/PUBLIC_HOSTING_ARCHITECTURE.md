# Public hosting: audit and architecture proposal

Date: 2026-10-19. Status: **PROPOSAL, nothing provisioned, deployed or published.** The GitHub repository stays private. No cloud account, domain, certificate or subscription exists. No external AI API is configured.

Companion docs:
- `HOSTING_COST_ESTIMATE.md` (cost categories and scenarios)
- `PUBLIC_SECURITY_CHECKLIST.md` (security requirements)

The deployment runbook and the public acceptance test are **outlined in §9** and written only after you approve an architecture, because they depend on that choice.

## 1. Audit summary (what exists today)

| Area | Finding (verified in the code) | Consequence for public hosting |
|---|---|---|
| Branch / state | `feature/m2-knowledge-packaging` @ `a8c9d6d`, plus **uncommitted** dev.6 membership work. `main` / `v1.0.0` @ `574072e`. Latest installer 2.0.0-dev.5 (`612750ed…`), unchanged | Hosting work must start from an agreed commit |
| Frontend | Vanilla JS PWA, **no build step** (`index.html`, `app.js`, `styles.css`, `sw.js`, `manifest.json`), served by FastAPI at `/` | Can be served by the same container; no bundler, so no secrets can be baked into a bundle |
| Backend | FastAPI (`main.py`). Lifespan prepares local storage, runs migrations and starts the folder watcher. One middleware, `companion_guard`; CORS from env | Startup assumes a local, writable data folder |
| Authentication | **There are no user accounts.** "Owner" = a request from `127.0.0.1` (`local_only`). dev.6 adds *device* credentials and invitations (`members`, `invitations`) with the roles owner device / guest | In the cloud, **every** request is remote, so "loopback = admin" is meaningless there. Hosting needs real accounts, sessions and an explicit admin role |
| Data ownership | **Single owner.** `conversations`, `messages`, `memories` (+ FTS), `app_settings`, `documents` have no owner column. Quiz progress uses a browser-chosen ID (not an identity) | **No multi-user isolation exists.** Pairing or login doesn't isolate anything (dev.6 guests are *excluded* from personal data, not given their own) |
| Inference | Ollama via `OLLAMA_BASE_URL` (default `http://localhost:11434`): `ChatOllama` (chat), `OllamaEmbeddings` (index), `/api/tags` (health), `/api/pull` (downloads with consent), `/api/generate` (setup test). No authentication, no timeouts per tenant, no queue | Needs an authenticated private endpoint, timeouts, a concurrency limit and a queue for shared use |
| Vector store | Embedded ChromaDB `PersistentClient` on local disk; one active collection | Not suitable for stateless containers or many tenants |
| Database | SQLite (WAL) in `%LOCALAPPDATA%\AbhifyAI\data` | Fine for desktop; hosted needs a managed database (or a single VM with a disk) |
| Local-only features | Watched knowledge folder (watchdog on a Windows folder), data relocation, backup/restore to local disk, model downloads, GPU detection (`nvidia-smi`), tray launcher, port fallback, Faster-Whisper on the local GPU | Must be **disabled** in a hosted edition or redesigned (uploads instead of folders, managed backups) |
| PWA | Manifest (standalone, icons incl. maskable), Apple tags, safe areas; the service worker caches only the app shell, never API responses; honest connection states | Reusable as is; the session model must change for accounts |
| Secrets | None in the repo. Config from env (`config.py`) and `settings.json`. Token and invitation hashes only; log redaction | Good base; a hosted edition needs a secret store |
| Deployment files | `Dockerfile` (python:3.11-slim, non-root, healthcheck) and `docker-compose.yml`, both **unverified**. **Compose sets `ALLOW_REMOTE_ADMIN: "true"`**, uses `ollama/ollama:latest` (unpinned) and **pulls models automatically** | Not safe for public use as is: remote admin open, no consent for downloads, no pinned versions |
| Tests | ~250 automated (desktop, companion privacy, membership, migrations, packaging) | **Missing:** real multi-user isolation, load and quota tests, hosted-mode configuration tests, real-device tests |

**Conclusion: the current app is safe as a single-owner desktop app with a private companion. It is NOT safe to put on the public internet as a multi-user service.** That needs accounts, per-user data isolation, a different storage layer and an authenticated inference service: a separate **hosted edition**, built from the same code base, with the desktop edition unchanged.

## 2. Architectures compared

| | **A. Fully hosted (incl. GPU in the same cloud)** | **B. Managed web + backend, self-hosted inference** *(your preferred direction)* | **C. Owner-operated PC / private companion** (today) |
|---|---|---|---|
| What runs where | Frontend, API, DB, vector store and Ollama all in one cloud project | Frontend + API + DB on a managed platform; Ollama on **dedicated compute you control** (rented GPU server or VM), reached privately | Everything on the owner's PC; phones via Tailscale |
| Availability | Independent of your PC | Independent of your PC (as long as the GPU host runs) | Only while the PC is on, Abhify.AI runs and the connection holds |
| Multi-user | Yes, after the isolation work | Yes, after the isolation work | Single owner + guests (dev.6) |
| Privacy | All user data in one cloud provider | User data in the managed DB; prompts and documents also go to **your** inference host (no third-party AI) | Stays on the PC |
| Cost shape | GPU dominates; serverless GPU can scale to zero (cold starts) | Fixed monthly GPU host (no cold start) or serverless GPU; small managed web/DB | Electricity + Tailscale (free tier) |
| Operations | One provider, but GPU quotas and regions limit choice | Two environments (managed platform + GPU host), plus a private link to secure and monitor | Minimal; capacity = one PC |
| Main risks | GPU quota/region, cold starts, cost if left running | Private-link security, a GPU host that is a single point of failure, OS patching on the host | PC availability, home network, not a public service |
| Fits "invite-only → public later" | ✅ | ✅ | ❌ (not a website) |

Rejected or deferred:
- **A** is kept as a variant of B: running Ollama on Cloud Run GPU inside the same cloud project is a valid *inference host*. It's listed in the cost estimate as B-serverless.
- **C** stays the desktop edition. Never make the development PC publicly reachable.
- **Third-party AI APIs** are excluded by your rule.

## 3. Recommendation: **B, "managed web + self-hosted Ollama"**, in two stages

```
            Internet (HTTPS only)
                    │
        ┌───────────▼────────────┐        managed platform (no GPU)
        │  Managed container      │  ──── secrets from the platform's secret store
        │  "abhify-web" (FastAPI  │
        │   + PWA, EDITION=hosted)│
        └───┬───────────────┬────┘
            │ private        │ private / managed
            │ network        │
  ┌─────────▼─────┐   ┌──────▼────────────────┐
  │ Managed        │   │ Object storage        │   (optional: originals of uploads;
  │ PostgreSQL     │   │ (per-user prefixes)   │    by default only extracted text is kept)
  │ + pgvector     │   └───────────────────────┘
  │ (accounts,     │
  │  chats,        │        private tunnel (WireGuard/Tailscale) or VPC-internal service
  │  memories,     │   ┌──────────────────────────────────────────────┐
  │  embeddings    │   │ Inference host YOU control (not your dev PC)  │
  │  per user)     │   │  reverse proxy: bearer auth, rate/conc. limit │
  └───────────────┘   │  → Ollama on 127.0.0.1:11434 (never public)   │
                      │  models pinned by digest, pulled by the owner │
                      └──────────────────────────────────────────────┘
```

**Stage 1 (invite-only pilot):**
- one web container
- the smallest managed PostgreSQL with pgvector
- one inference host: either **B-dedicated** (rented GPU server, fixed price, no cold start) or **B-serverless** (Cloud Run GPU L4, scale to zero, about 20 s cold start)
- a handful of invited users

**Stage 2 (public):** the same shape, plus a queue and per-user quotas for inference, a second inference host or autoscaling, monitoring and alerting, abuse protection, and backups tested by restore drills.

Why B:
- It meets your constraints: no external AI API, inference under your control, not tied to your PC.
- It keeps the web tier cheap, and it can grow into public registration.
- The desktop edition keeps local SQLite, Chroma and Ollama unchanged (`EDITION=desktop`, the default).

## 4. Data flows and privacy boundaries
1. **Browser/PWA → web (HTTPS):** login, session cookie (HttpOnly, Secure, SameSite=Lax/Strict) + CSRF token; no secrets in URLs or in browser storage for the hosted edition.
2. **Web → PostgreSQL:** every query is scoped by `user_id` (enforced in a data-access layer and covered by cross-user tests). The admin role is an account flag, never "loopback".
3. **Web → inference host:** prompt + retrieved excerpts of **that user's** documents, sent over the private tunnel with a service credential. The inference host stores **nothing** per user, and logs no prompts.
4. **Never public:** Ollama, PostgreSQL, the vector store, object storage buckets, admin endpoints and diagnostics.
5. **Data location:** choose a region near your users (India: GCP `asia-south1` Mumbai exists, but Cloud Run L4 there is "invitation only"; `asia-southeast1` Singapore has L4). This is a legal and privacy decision for you.

## 5. Security boundaries (summary; full list in `PUBLIC_SECURITY_CHECKLIST.md`)

| Boundary | Control |
|---|---|
| Internet ↔ web | HTTPS only (platform-managed certificate); trusted-proxy settings; request-size limits; rate limits (shared store for multiple instances); generic errors |
| User ↔ user | `user_id` scoping on every table and on the vector search; per-user upload quotas; isolation tests in CI |
| Member ↔ admin | Explicit roles; admin routes require the admin role + re-authentication; the registration-mode switch is admin-only and audited |
| Web ↔ inference | Private network + bearer credential + IP allow-list; concurrency limit, timeouts, queue; "AI unavailable" reported honestly |
| Secrets | Platform secret manager; rotate on staff change; never in the image, repo or logs |

## 6. Code changes required (NOT started; needs your approval)
1. **Edition switch:** `EDITION=desktop|hosted`. Hosted mode disables the folder watcher, data relocation, local backups, model pulls, GPU detection, the tray, `local_only` and `ALLOW_REMOTE_ADMIN`.
2. **Accounts and sessions:** user table, password (Argon2id) or passkeys, email-free invitation codes (reusing dev.6 invitations), sessions in the DB with revocation, CSRF.
3. **Per-user data:** `user_id` on conversations, messages, memories (+ FTS replacement), preferences, documents, progress. **The migration is reversible:** a new hosted DB; the desktop SQLite stays untouched; an optional export/import tool for the owner's own data.
4. **Storage layer:** PostgreSQL + pgvector behind the existing functions (`database.py`, `knowledge.py`), with SQLite + Chroma kept for desktop.
5. **Inference client:** auth header, timeouts, concurrency limit / queue, an "overloaded" response, per-user quotas.
6. **Abuse protection:** request and upload limits, per-user rate limits in a shared store, registration throttling, admin audit log.
7. **Observability:** structured logs with the existing redaction, error reporting without content, health/readiness split (`/ping`, `/health`).

## 7. What stays exactly as it is
- the dev.5 installer and release artifacts
- the desktop edition's local-first behaviour, local Ollama, SQLite + Chroma, and the companion
- `main` and `v1.0.0`

The hosted edition is additive and configuration-selected, and must not add a cloud dependency to the desktop build.

## 8. Approval gates (each needs your explicit "yes")
1. Choose the architecture (B recommended) and the inference variant (dedicated vs serverless).
2. Approve the code changes in §6 (accounts and per-user isolation are a major architectural change).
3. Choose providers and region (after `HOSTING_COST_ESTIMATE.md`), then create accounts and billing. **You** do this.
4. Domain and HTTPS.
5. A deployment workflow (it will exist **disabled**: manual trigger only, no production secrets in CI).
6. Pilot launch (invite-only), after `PUBLIC_ACCEPTANCE_TEST.md` passes on the real deployment.
7. Public registration.

## 9. Documents to be written after gate 1
- `PUBLIC_DEPLOYMENT_RUNBOOK.md`:
  - environments and config variables
  - container build (pinned base images and lock file)
  - DB migrations + rollback
  - inference-host setup (OS hardening, proxy, Ollama pinned, models pulled by the owner)
  - private-tunnel setup, backups and restore drills, monitoring, incident response, cost alarms
- `PUBLIC_ACCEPTANCE_TEST.md`:
  - invite-only and public registration
  - account and session revocation
  - **cross-user isolation**
  - admin authorization
  - chat, memory and document persistence; citations
  - inference unavailable, overloaded, timed out
  - restart recovery
  - PWA privacy and caching
  - rate and size limits, secret redaction
  - real Android/iPhone over the public URL
  - desktop regression
