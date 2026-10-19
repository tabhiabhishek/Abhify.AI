# Public hosting: security checklist (hosted edition, architecture B)

Every item must be ✅ **with evidence** before the invite-only pilot (P) or public registration (R). Status today: **nothing implemented for hosting**. The desktop edition's existing controls are noted where they can be reused.

## Accounts, sessions, roles
| # | Requirement | Gate | Reuse / notes |
|---|---|---|---|
| A1 | Real user accounts; passwords hashed with **Argon2id** (or passkeys); no plaintext anywhere | P | New |
| A2 | Server-side sessions: HttpOnly + Secure + SameSite cookie, rotation on login, idle and absolute expiry, revocation on logout, password change and admin revoke | P | dev.6 revocation model |
| A3 | CSRF protection for every state-changing request (token + Origin check) | P | New |
| A4 | Registration mode `invite_only` by default; `public` only by an admin, audited, with confirmation; an invalid value means invite-only | P | dev.6 `membership.py` logic |
| A5 | Invitations: single use, expiring, stored hashed, rate-limited, generic errors | P | dev.6 (move rate limits to a shared store) |
| A6 | Roles `admin` / `member` as account attributes. **No "loopback = admin"**; `local_only` and `ALLOW_REMOTE_ADMIN` disabled in hosted mode | P | Replace dev.6 device roles |
| A7 | Suspend, revoke and delete a member: sessions are invalidated immediately | P | dev.6 revoke |
| A8 | Admin actions need recent re-authentication and are written to an audit log (no secrets) | R | New |

## Data isolation
| # | Requirement | Gate |
|---|---|---|
| D1 | Every user-owned table has `user_id NOT NULL`; all queries go through a data-access layer that requires the user | P |
| D2 | Vector search is filtered by `user_id` (pgvector `WHERE user_id = …`); no shared collection without a filter | P |
| D3 | Uploads: per-user storage prefix, size and type limits, per-user quota; extracted text only by default | P |
| D4 | **Cross-user tests in CI:** user B can't list, read, update, delete or search user A's chats, memories, documents, settings or progress (IDs guessed or enumerated) | P |
| D5 | Features without isolation stay **off** for members (for example, the shared knowledge folder) | P |
| D6 | Account deletion removes the user's rows, vectors and files; backups expire on a schedule | R |

## Network and infrastructure
| # | Requirement | Gate |
|---|---|---|
| N1 | HTTPS only (HSTS); a platform-managed certificate | P |
| N2 | Trusted-proxy configuration: client IP only from the platform's forwarding header | P |
| N3 | Ollama, PostgreSQL, the vector store, storage buckets and the admin API are **not reachable from the internet** | P |
| N4 | Inference: private tunnel or VPC-internal, a bearer service credential, IP allow-list, concurrency limit, timeouts, queue; "AI unavailable" or "busy" shown honestly | P |
| N5 | Request size limits (chat ≤ 4,000 chars as today; uploads ≤ 20 MB); per-user and per-IP rate limits in a shared store | P |
| N6 | Containers run non-root (as in the existing Dockerfile), with pinned base images and the hash lock file | P |
| N7 | `docker-compose.yml`'s `ALLOW_REMOTE_ADMIN: "true"` and automatic model pulls are **never** used for a hosted deployment | P |
| N8 | The inference host is hardened: OS updates, firewall default-deny, SSH keys only, Ollama bound to 127.0.0.1 behind the auth proxy | P |

## Secrets and logging
| # | Requirement | Gate | Reuse |
|---|---|---|---|
| S1 | Secrets only in the platform secret manager; none in the repo, image, URLs, frontend or CI logs | P | No secrets in the repo today |
| S2 | Log redaction of tokens, cookies, auth headers and query values | P | dev.3 `log_redaction.py` |
| S3 | No prompts, document text or memories in logs or error reports | P | Existing rule |
| S4 | Error responses are generic; no paths, stack traces or user names | P | dev.3 redaction |
| S5 | Diagnostics endpoints are admin-only and contain no secrets | P | dev.3 |

## PWA and clients
| # | Requirement | Gate | Reuse |
|---|---|---|---|
| W1 | The service worker caches only the app shell; never API responses or private data | P | ✅ today |
| W2 | No long-lived secret in `localStorage` in the hosted edition (cookie session instead) | P | Differs from the desktop companion (owner's dev.6 decision) |
| W3 | Honest status: never "AI ready" while inference is down or busy | P | ✅ today (extend with "busy") |
| W4 | Expired or revoked session goes to the login screen without a data flash | P | New |

## Operations
| # | Requirement | Gate |
|---|---|---|
| O1 | Automated DB backups + a **tested restore** (drill recorded) | P |
| O2 | Migrations versioned, backed up first, with a rollback procedure | P |
| O3 | Monitoring and alerting: uptime, error rate, inference latency and queue, cost budget alerts | P |
| O4 | Dependency and image vulnerability scanning in CI; no automatic production deploy | P |
| O5 | Incident runbook: revoke sessions, rotate secrets, restore, notify users | R |
| O6 | Privacy notice and terms (what is stored, where, retention, deletion) | R |
