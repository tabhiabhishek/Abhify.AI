# Abhify.AI 5.5 recommendations and decisions

This file adds to [ABHIFY_RECOMMENDATIONS.md](ABHIFY_RECOMMENDATIONS.md) and [ABHIFY_TECH_DECISIONS.md](ABHIFY_TECH_DECISIONS.md) rather than repeating them.

## Decisions taken in 5.5 (safe; no approval needed)

| Decision | Why | Reversible? |
|---|---|---|
| The context finder feeds **only local** chat answers, and only when the question is about your own material (knowledge mode or a named project) | Never sends records to an online model without the existing consent paths | Yes (one condition) |
| Memories and documents are **excluded** from the context finder (`CHAT_CONTEXT_SOURCES`) | They already have their own consent and RAG paths; avoids double context | Yes |
| Agent workflows only **propose**; priority, blocked and doc-link changes happen after "Approve N", are read back afterwards, and can be undone | Same contract as Agent 2.0 | Yes |
| Blockers and priorities come only from stored dates, issues, the blocked flag and wording such as "waiting for" | No invented deadlines | – |
| The skill threshold can be changed (3/5/10, default 5); a trend needs 10 answers | A level or trend is never shown without enough evidence | Yes |
| The revision to-do goes into a project named "Study" (created if missing), after a confirm dialog | Visible and easy to delete; no new storage | Yes |
| Internal identifier via `versionNameSuffix = "-internal.5.5-dev.1"`; versionCode unchanged | The prompt requires approval before changing the version | Yes |

## Decisions awaiting the owner

| # | Decision | Recommendation |
|---|---|---|
| A1 | Commit the 5.x work on `feature/m2-knowledge-packaging` (many uncommitted paths) | **Yes, soon.** It is the largest risk to the work. |
| A2 | Back up the debug keystore (`~/.android/debug.keystore`) to a location you choose | **Yes.** Every installed build depends on it. |
| A3 | Make 5.5 official: versionName "5.5", versionCode 5050099 | After the phone checklist passes |
| A4 | A release signing key | Only before any distribution beyond your own phone |
| A5 | Optional integrations: YouTube/edX catalogs, ML Kit translation, SQLCipher, wake word | Defer; each adds a dependency or permission |

## More ideas, ranked (value ÷ risk)
1. Search type filters (chips) on Android and web: small, and the data is already grouped.
2. Read-only desktop project status from `projects.py` (no write tools).
3. Per-to-do reminders using the existing WorkManager, opt-in.
4. A merge action for duplicate to-dos: proposed, approved, and undoable.
5. Retrieval evaluation on the owner's own documents (offline script, nothing uploaded).
