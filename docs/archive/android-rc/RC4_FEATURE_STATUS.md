# Abhify Android 2.0.0-rc.4: feature status

**Columns:**

- **Built:** the code is in the RC.4 APK.
- **Automated:** there are JVM unit tests with mocked HTTP. 91 tests run and 0 fail; 3 opt-in tests are skipped.
- **Phone:** the feature was tested on a real device. For RC.4 every feature is still PENDING (see [RC4_ACCEPTANCE_CHECKLIST.md](RC4_ACCEPTANCE_CHECKLIST.md)).
- **Real API:** the feature was tested against a live provider with a real key. That is NOT TESTED, because no real keys were available. A probe with a fake key confirmed the live error formats on 2026-11-03.

## P0: online providers

| Feature | Built | Automated | Phone | Real API |
|---|---|---|---|---|
| Personal keys without an Abhify account or the server | ✅ | ✅ | PENDING | NOT TESTED |
| Explicit, persisted provider selection (RC.3 → RC.4 migration picks the first saved key once) | ✅ | ✅ routing | PENDING | n/a |
| The exact "No online provider configured…" message plus an Open AI Providers action | ✅ | ✅ | PENDING | n/a |
| The hidden free-tier checkbox gate removed (it was the RC.3 root cause) | ✅ | ✅ | PENDING | n/a |
| Provider-named errors (401, Gemini 400, 403, 404/model, 429, 5xx, timeout, DNS, TLS, empty, interrupted) and key redaction | ✅ | ✅ | PENDING | Error formats only (fake key) |
| Auto: first provider only; other providers and the phone only with your settings; switch only before the first word | ✅ | ✅ | PENDING | NOT TESTED |
| Model adopted from the key's own list | ✅ | ✅ | PENDING | NOT TESTED |
| Every answer labelled with its source | ✅ | ✅ | PENDING | NOT TESTED |
| Separate home-screen states (mode, provider, local model, account) | ✅ | – | PENDING | n/a |
| Copy diagnostics (no keys) | ✅ | – | PENDING | n/a |
| Partial answer kept with **↻ Retry** after a stream drop | ✅ | ✅ client | PENDING | NOT TESTED |

## P1: readability

| Feature | Built | Automated | Phone | Real API |
|---|---|---|---|---|
| Default text colour from the theme (fixes black text on navy) | ✅ | – | PENDING | n/a |
| WCAG AA tokens: red-400 for errors in dark mode; slate-600 muted, green-700, amber-700 and red-700 in light mode | ✅ | ✅ ContrastTest | PENDING | n/a |
| Buttons grow with font size (min-height instead of a fixed height); bottom navigation colours | ✅ | – | PENDING | n/a |

## P2: voice and documents

| Feature | Built | Automated | Phone | Real API |
|---|---|---|---|---|
| Voice fills the input box (no auto-send), Cancel, error states | ✅ | – | PENDING | n/a |
| Read-aloud with Stop and a missing-engine message | ✅ | – | PENDING | n/a |
| TXT, MD, CSV and DOCX attachments; limits; View, Remove, Cancel | ✅ | ✅ DocumentsTest | PENDING | n/a |
| Consent before document text goes online | ✅ | – | PENDING | NOT TESTED |
| PDF text | ❌ Not implemented; refused with a message | ✅ | – | – |
| Images and OCR | ❌ Not implemented; refused with a message | ✅ | – | – |

## P3: controls

| Feature | Built | Automated | Phone | Real API |
|---|---|---|---|---|
| Answer styles: Default, Tutor, Concise, Interview coach, Socratic | ✅ | ✅ | PENDING | n/a |
| Custom instructions: edit, save, clear, pause, preview | ✅ | ✅ | PENDING | n/a |
| Memories: on/off, edit, delete, delete all (with confirmation) | ✅ | – | PENDING | n/a |
| Chat summaries, image understanding, RAG library, web search, tools, model comparison, 👍/👎 feedback | ❌ Not implemented (no buttons shown) | – | – | – |

## P4: data and security

- The database stays at **v4**. RC.4 makes no schema change, so the upgrade has nothing to migrate.
- Keys are stored in Android Keystore AES-GCM. Backups stay disabled.
- Diagnostics and errors never include keys; provider error text is redacted.
- The APK (192 files) and the project sources were scanned for key patterns and **no matches** were found.
- **Signing:** the first RC.4 build was accidentally signed with a newly created debug key. It was rebuilt with the RC.3 debug key (certificate SHA-256 `1d2d1fdb…e6246f`), so the in-place upgrade keeps data.
