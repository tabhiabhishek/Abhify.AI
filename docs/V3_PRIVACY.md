# Abhify.AI 3.0: privacy

## What stays on the phone

| Data | Stored | Synced (if you turn sync on) | Exported | Sent to an AI |
|---|---|---|---|---|
| Chats | SQLite, app-private | yes | JSON, MD, TXT | the recent chat goes to the AI answering |
| Private chats | **nowhere** | no | no | to the AI answering, during the chat only |
| Memories | SQLite | yes | JSON | always to the phone's model; online only if you allow "Share memories" |
| Feedback 👍/👎, chat folders, summaries | SQLite | no | folder only | a summary sends the chat's text after consent |
| Document library (extracted text) | SQLite (`library_docs`) | **no** | no | see below |
| Notes, tasks, workspaces, study plans, flashcards | SQLite | **no** | not yet | only the text you choose to process, after consent |
| Provider keys | Android Keystore AES-256-GCM | **never** | **never** | only to that provider, as its API key |

## Documents and notes going online

Every AI action that includes document or note text asks first when the answer would go to an online provider or the server. This covers summaries, key points, action items, notes, quizzes, flashcards, comparison, library Q&A, meeting notes, reports, email drafts and chat summaries.

The dialog shows:
- what will be sent, by document name and size;
- where it will go (the provider and model).

You can answer:
- **Send**;
- **This phone**, which runs it on the local model so nothing leaves the phone;
- **Cancel**, which sends nothing.

There is no "always allow".

**Library Q&A:** the sections are matched on the phone. Only the best-matching sections are sent, never the whole library, and the answer lists exactly which ones.

**Memory:** documents, notes and AI results are **never** added to memory automatically.

## Deleting

| Privacy screen action | What it removes |
|---|---|
| Delete all chats and memories | Chats and memories. If sync is on, they are removed from your other devices too. |
| Delete all documents, notes, tasks, plans and flashcards | The v5 local data. |

- Single items can be deleted where they are shown: document, note, task, plan, deck, chat or memory.
- Provider keys: AI Providers → Remove, or Reset.
- The model: AI models → Remove.

## Organisation data

The Work tab is **local to this phone**:
- No organisation server, accounts, roles or sharing exist in 3.0.
- Nothing in Work is visible to an employer or administrator.
- Work content is sent to an AI only through the same consent dialog, using your own routing.
