# RC.3 audit and plan: hosted providers (Gemini, Groq, OpenRouter), per-device keys, premium UI (2026-11-02)

## RC.2 audit (starting point)
| Item | State |
|---|---|
| Git | branch `feature/m2-knowledge-packaging` @ `a9325d2`, 27 uncommitted entries (dev.7 web, `android/`, `cloud/`, docs). Nothing committed or pushed. |
| App id / version | `ai.abhify.android` · 2.0.0-rc.2 (versionCode 2000002) · minSdk 29 / target 36 |
| Signing | **debug key of this PC**, SHA-256 `1D:2D:1F:DB:0B:2D:C9:34:CA:9A:9E:72:2F:A6:C0:3D:9F:03:9F:40:8B:0C:68:B0:A4:FF:3F:A2:2F:E6:24:6F`. RC.3 uses the same key, so it **installs over RC.2 in place**, with no uninstall and no data loss. |
| Database | `ChatDb` v3 (additive from v1/v2). RC.3 adds v4, **additive only** (pinned chats). |
| Model | `noBackupFilesDir/models/installed/<id>/model.gguf` with `state.json`. Never touched by upgrades. |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+DATA_SYNC), POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, RECORD_AUDIO. **No new permission in RC.3.** |
| Backups | `allowBackup=false` and data-extraction rules exclude everything, which also covers the new provider-key store. |
| Cloud API | `/api/v1/*` (auth, devices, sync, ai/chat via private Ollama, models, health). **Not deployed.** |
| Owner-reported phone results | local AI **PASS**, airplane-mode chat **PASS**; memory persistence, database upgrade, existing-data preservation **PENDING** |
| Secrets in repo | none. Root `.gitignore` only ignored `backend/.env`; RC.3 adds `.env` patterns and a placeholder-only `.env.example`. |

## Verified provider facts (official docs read 2026-11-02)
| | Gemini | Groq | OpenRouter |
|---|---|---|---|
| Chat (OpenAI-compatible) | `POST https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` | `POST https://api.groq.com/openai/v1/chat/completions` | `POST https://openrouter.ai/api/v1/chat/completions` |
| Auth | `Authorization: Bearer <key>` | same | same (+ optional `HTTP-Referer`, `X-Title`) |
| Streaming | `stream: true` (SSE; terminator not stated in its docs, handled defensively) | SSE `data: …`, ends `data: [DONE]` | SSE, `: OPENROUTER PROCESSING` comments, ends `data: [DONE]`; **mid-stream errors arrive as an `error` object with HTTP 200** |
| Models | `GET …/v1beta/openai/models` | `GET …/openai/v1/models` (`id`, `active`, `context_window`) | `GET https://openrouter.ai/api/v1/models` (`pricing.prompt/completion` as strings) |
| Free models | free tier: `gemini-3.8-flash`, `gemini-3.6-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`; **not free:** `gemini-4-argon`, `gemini-3.1-pro-preview` | free plan until the account is upgraded with a payment method | ids ending `:free` **and** zero pricing; **fallbacks to paid models are charged**, so never send `models` arrays |
| Paid risk | a key in a project **with billing linked is charged** (the API can't tell), so the user must confirm | only after upgrading to the Developer tier | non-`:free` models cost credits |
| Usage info | **none via API**, so the app shows "Usage information unavailable" | `x-ratelimit-*` response headers (remaining requests/tokens) | `GET /api/v1/key` → `usage`, `is_free_tier`, `free_model_daily_requests {used, limit, remaining}` |
| Errors | 401 invalid, 403 blocked/leaked, 429 rate/quota, 402 prepay | 401, 429 + `retry-after` | 401, 402 credits, 429 (+ Retry-After may), 403 budget |
| Privacy note | free-tier content **"used to improve our products"** (pricing page) | — | routed to third-party model providers |

## Decisions
1. **Personal keys stay on the device and the phone calls the provider directly.** They're never sent to the Abhify server, as the spec requires. Keys are sealed with a **dedicated Android Keystore AES-256-GCM key** in a separate app-private store, never synced, exported or backed up, never logged, and shown only masked (`gsk_••••••••abcd`). Documented limitation: a rooted or compromised phone could extract a key that the app itself can decrypt.
2. **One OpenAI-compatible adapter** with per-provider quirks, covered by fixture tests: SSE parsing, comment lines, mid-stream error objects, `[DONE]`, Retry-After.
3. **Paid-model protection on by default:**
   - Only models on the verified free list (Gemini) or with free pricing (OpenRouter `:free` + zero price) can be selected.
   - Groq models are shown with a note that charges only apply after upgrading the account.
   - **No fallback arrays, ever.**
   - A per-provider *"Allow models that may be charged"* switch stays off unless the user turns it on and confirms.
   - Gemini also requires the user to confirm "my project has no billing linked".
4. **Shared (administrator) access** goes through `cloud/`: keys live only in server environment variables; Android sees only provider names and availability. There are per-user daily limits, admin on/off, and usage counting. It needs a signed-in account and a deployed server, so it's pending deployment.
5. **Routing:**
   - **On-device:** the local Qwen2.5 model.
   - **Online:** *one selected* provider; errors are shown and nothing switches silently.
   - **Auto:** the user's priority list (default Gemini → Groq → OpenRouter → shared → this phone). It moves to the next only **before the first word**; falling back to the phone is a user setting.
   - Every answer is labelled `provider · model`.
6. **Privacy:** online AI still requires the existing *Allow online AI* permission. Each provider's setup screen states what is sent and that provider's data note. Memories go online only with the separate permission.
7. **No fake features:** P1 productivity features are implemented. P2/P3 items that aren't built (documents, images, OCR, RAG, web search, tools) have **no buttons** and are listed as not implemented.

## Phases
1. Credential store + adapters + model discovery + paid protection (tests with mock HTTP).
2. Routing with providers, fallback rules and labels (tests).
3. Provider management UI, second-device prompt, privacy disclosures.
4. Backend shared providers + limits + usage (tests with mocked providers).
5. P1 productivity: edit & resend, share, tables, search history, pin, rename, export (keys never included), custom system prompt, memory on/off.
6. Typography update to the RC.3 scale.
7. Build RC.3 debug APK, docs, acceptance checklist. Real provider tests only with the owner's keys (none are available here).
