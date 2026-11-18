# Future enhancements roadmap (after Abhify.AI Android 3.4)

This is a proposal and not a commitment. Each step keeps the same rules:
- local-first, with the phone's model as the default
- no silent cloud use
- additive database migrations
- the same app ID
- no automatic model training

| Version | Theme | Main items | Depends on |
|---|---|---|---|
| **3.5** | Drive and background reliability | WorkManager uploads that survive app restarts (with a notification); scheduled backups (weekly, user-chosen, encrypted); a Drive folder browser with search; real-account Drive test pass and OAuth verification for public use | Google OAuth client, verified consent screen |
| **3.6** | Better retrieval | Optional small on-device embedding model (requires approval: an extra download of about 25–100 MB) for semantic search, combined with BM25; per-document summaries; "chat with a folder"; table-aware PDF chunking | Model approval, device RAM benchmarks |
| **3.7** | Career Copilot 2.0 | **Already delivered in 3.3.0** (ATS scoring, job tracker, interview practice, résumé export). Follow-ups: cover-letter templates, application reminders | none |
| **3.8** | Memory 2.0 | Memory timeline and "why was this used" explanations; per-chat memory scopes (work/personal); expiry reminders; optional encrypted memory sync between the user's own devices | Sync service decision |
| **3.9** | Study and knowledge | Spaced-repetition improvements from RAG sources; quizzes generated from selected documents with citations; Learning Library collections synced to Drive on request | 3.6 retrieval |
| **4.0** | Platform and release | Production signing and Play Store track (requires an explicit decision); multi-model manager (keep Qwen2.5 as default, optionally add one more after approval); accessibility and localization pass; full physical-device matrix | Signing decision, store account |
| **4.1** | Personal fine-tuning research (opt-in only) | Export of user-approved examples to `Model Training Data`; a desktop/cloud LoRA pipeline that the user runs explicitly; no on-phone training and nothing automatic | Explicit user approval each time, legal review |

## Explicitly out of scope (unless requested)
- AI Content Creator Studio.
- Xiaomi OCR model (ML Kit stays).
- Automatic upload of all documents.
- Automatic fine-tuning.
- Replacing Qwen2.5 without approval.

## Open questions for the owner
1. Should Drive be offered to all users? That requires Google OAuth verification for a public app.
2. What download size is acceptable for an optional embedding model?
3. When should production signing be set up? It must be decided before any store release.
