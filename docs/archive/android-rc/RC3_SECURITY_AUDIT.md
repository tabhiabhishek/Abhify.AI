# RC.3 security audit: API keys, providers, data (2026-11-03)

## Personal API keys (Android)
| Requirement | Implementation | Evidence |
|---|---|---|
| Never stored in plaintext | AES-256-GCM, key in the **Android Keystore** (alias `abhify_provider_keys_v1`, separate from the account-token key), random IV per write | `ProviderStoreTest.keysAreSealedMaskedAndRemovable` (stored values contain no part of the key) |
| App-private, not in Downloads/shared storage | `SharedPreferences("abhify_providers")` in the app sandbox | code (`AppGraph.providerKv`) |
| Excluded from backups | `allowBackup=false`; `dataExtractionRules` exclude every domain | manifest |
| Never synced | the sync code (`ChatDb`, `Sync.kt`, `SyncWork.kt`) doesn't reference the provider store | `NoKeysInSourceTest.syncAndExportCodeNeverTouchesProviderKeys` |
| Never exported | `exportJson()` writes chats and memories only | code; checklist item A9 |
| Never sent to the Abhify server | personal routes call the provider directly; the server route sends no key | `HybridAi.personal()`; `ProviderClientTest` (only provider hosts are contacted) |
| Never logged or shown in full | no log calls with keys; UI shows `gsk_••••••••abcd`; 401 messages don't echo provider text (which may contain the key) | `ProviderRulesTest.masksKeys`, `errorsAreReadableAndNeverEchoAnInvalidKey` |
| Not in source, APK or Git | source scan; **built APK scanned (191 files): 0 key-shaped strings**; `.env` ignored repo-wide, `.env.example` has placeholders only | `NoKeysInSourceTest.noKeysCommitted`, APK scan 2026-11-03, `git check-ignore` |
| Delete with confirmation | *Remove* dialog; *Reset all* dialog | UI |
| Per device | each install has its own store and Keystore key; nothing transfers | `ProviderStoreTest.aSecondDeviceStartsWithNoKeys` |

**Known limitation (documented):** the app must be able to decrypt a key to use it. On a rooted or compromised phone, or with a debuggable build plus `run-as`, a key could be recovered. Keystore-backed encryption protects keys at rest and in backups, not against code running as the app. Mitigations: use free-tier keys, restrict them in the provider console where possible, and revoke a key there if a phone is lost.

## Spending protection
- **Model filters:**
  - Gemini: a verified free-tier model list.
  - OpenRouter: the `:free` suffix **and** zero price.
  - Charged models are hidden and blocked unless the user enables *Allow models that may be charged* and confirms.
  - A blocked model is refused **without any network request** (`chargedModelIsRefusedWithoutAnyRequest`).
- **No fallback lists:** OpenRouter `models` arrays are never sent, since a fallback to a paid model is billed (`streamsTokensAndRecordsUsage` asserts no `models` field). The server does the same.
- **Free-tier confirmation:** Gemini and Groq can't be used until the user confirms the free tier. Gemini bills keys in projects with billing linked, and the API can't detect that.
- **Free validation:** keys are checked with model listing and key info, never a generation.
- **Shared access:** the server refuses charged shared models at startup unless the administrator opts in, and enforces a per-user daily request limit.

## Provider calls
- HTTPS only (release builds; debug may use localhost only).
- Connect timeout 10 s, read timeout 90 s.
- **Cancellation:** Stop ends the stream at the next chunk, and the connection is closed.
- **Errors:** 401/402/403/404/408/429 (with Retry-After)/5xx and mid-stream errors (OpenRouter, HTTP 200) each map to a clear message. *Last error* is kept per provider.
- **Fallback:** Auto never mixes two models in one answer.

## Privacy
- Online AI needs the *Allow online AI* permission; memories need a second permission.
- A line above the message box states where each question goes.
- Each provider's privacy note is shown before its key is saved. Gemini's free tier: content used to improve Google's products.
- Cloud sync is unchanged: off by default, and it never includes keys.

## Server (`cloud/`)
- **Shared keys:** environment variables only. `/api/v1/providers` returns names, models, availability and usage, never keys (`test_shared_access_off_by_default_and_never_leaks_keys`).
- **Errors:** provider 401/403 messages aren't echoed. Logs record error types only.
- **Earlier controls unchanged:** scrypt passwords, rotating opaque tokens with reuse revocation, per-user isolation, rate limits, production HTTPS/SMTP guards.

## Not tested
- **Real provider keys:** none were available. Every provider result in this release is **mocked**; see the acceptance checklist.
- **Behaviour on a physical phone.**
