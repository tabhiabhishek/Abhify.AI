# Abhify.AI Android v3.0: feature status (100-item catalog)

**Build:** `Abhify-3.0.0-debug.apk`, 2026-11-04.

**Status values:**

| Status | Meaning |
|---|---|
| **Existing** | In RC.4 already; kept, and changed in v3 only where noted. |
| **Implemented** | New in v3.0 and covered by unit tests where the logic is testable. **None of it has been tested on a phone yet.** |
| **Needs improvement** | Works in part; the gap is stated. |
| **Not implemented** | No code, and no button in the app. |
| **Requires backend / external integration / user approval** | Blocked on something outside this release. |

**Test totals:** 107 JVM unit tests run, 0 failed, 3 opt-in skipped. The new phone tests `PdfTextTest` and `DbMigrationTest` v5 are built but **not run**: no phone or emulator was available.

## Core AI and chat

| # | Item | Status | Notes |
|---|---|---|---|
| 1 | Full response rendering | Implemented | Root causes R1–R7 fixed (see V3_IMPLEMENTATION_PLAN §2), including a **token-loss bug**: fast streams were cut when the 64-event buffer filled. A test now streams 5,000 chunks with none lost. |
| 2 | Read more / Show less | Implemented | View only. Older answers over 2,400 characters start collapsed; the newest is always expanded. Copy, Share, Read aloud and Export always use the full text. |
| 3 | Continue generation | Implemented | For answers cut off by the length limit, stopped, or interrupted. Uses the same provider or model only, at most 3 times, and removes repeated text. |
| 4 | Streaming | Existing → fixed | Backpressure (`trySendBlocking`) instead of dropping events. |
| 5 | Stop | Existing | The answer is now marked "Partial: you stopped it" and can be continued. |
| 6 | Retry | Existing | Labelled "Retry" for partial or failed answers. |
| 7 | Edit and resend | Existing | |
| 8 | Copy full response | Existing | Verified to use the complete text. |
| 9 | Markdown, code, tables | Existing | |
| 10 | Chat search | Existing | |
| 11 | Chat folders | Implemented | Personal, Learning, Work and Documents, in History. |
| 12 | Export chats | Existing + Implemented | JSON (all) existed. New: save a chat as `.md` or `.txt`. **PDF export is not implemented.** |
| 13 | Rename and pin | Existing | |
| 14 | Response feedback | Implemented | 👍/👎 per answer, stored on this phone and not synced. |
| 15 | Conversation summaries | Implemented | History → Summarize. Shown under the title. Content is sent online only after consent. |

## AI provider and model management

| # | Item | Status | Notes |
|---|---|---|---|
| 16 | Multi-provider | Existing | Gemini, Groq, OpenRouter and local Qwen. |
| 17 | Model selector | Needs improvement | Chosen per provider in AI Providers. There is no quick switch in the chat. |
| 18 | Provider health check | Existing | Test & refresh. |
| 19 | Usage dashboard | Needs improvement | Each provider card shows its reported usage. There is no combined dashboard. |
| 20 | Free-model filter | Existing | |
| 21 | Request history | Not implemented | |
| 22 | Automatic fallback | Existing | Opt-in only, and only before the first word. |
| 23 | Model comparison | Not implemented | |
| 24 | Provider-specific settings | Implemented | Answer length limit per provider (1k–16k). Each provider gets its own request field. |
| 25 | Local model manager | Existing | |

## Documents and knowledge

| # | Item | Status | Notes |
|---|---|---|---|
| 26 | PDF | Implemented | Text layer only, via `pdfbox-android` 2.0.27.0 (Apache-2.0), page by page. Phone test written, not run. |
| 27 | Image upload | Requires user approval | Needs a vision or OCR decision. |
| 28 | OCR | Requires user approval | ML Kit (proprietary, sends usage metrics) vs Tesseract (open source, about 15 MB data download). |
| 29 | Summaries | Implemented | |
| 30 | Q&A | Implemented | Ask a single document in chat, or the whole library with sources. |
| 31 | Library | Implemented | Add, view, rename, delete and search, with cancellable import. |
| 32 | Search | Implemented | Name and text search, plus BM25 ranking for Q&A. |
| 33 | Citations | Implemented | `[S#]` with document plus page (PDF) or section. Uncited answers are flagged. Pages are never guessed. |
| 34 | Comparison | Implemented | Two documents. |
| 35 | Action items | Implemented | Turned into tasks only after confirmation. |
| 36 | Notes | Implemented | Study notes; results can be saved as notes. |
| 37 | Local processing | Implemented | Extraction and retrieval run on the phone. |
| 38 | Deletion and index cleanup | Implemented | The index is built from the table at query time, so deleting a document leaves nothing behind. |

## Learning

| # | Item | Status | Notes |
|---|---|---|---|
| 39 | AI tutor | Implemented | Beginner, intermediate and advanced levels, ending with a practice question. |
| 40 | Learning plans | Implemented | AI lesson list saved locally, with checkable lessons and progress. |
| 41 | Quiz generator | Existing + Implemented | Bank and AI questions existed. New: a quiz generated from a document. |
| 42 | Flashcards | Implemented | Decks from a topic or document; review, known/still-learning, counts. |
| 43 | Practice mode | Implemented | "Check my answer" evaluation. It is not a validated grade. |
| 44 | Exam preparation | Needs improvement | Quizzes and cards only; no timed exam mode. |
| 45 | Interview preparation | Needs improvement | Question bank and AI questions. No HR or mock-interview flow yet. |
| 46 | Coding tutor | Not implemented | The tutor can explain code; there is no dedicated coding mode or execution. |
| 47 | Progress tracking | Existing + Implemented | Quiz stats plus plan progress. |
| 48 | Revision reminders | Not implemented | |
| 49 | Weak-topic analysis | Not implemented | |
| 50 | Learning history | Needs improvement | Counts only. |

## Corporate productivity (the Work tab is local only, with no organisation server)

| # | Item | Status | Notes |
|---|---|---|---|
| 51 | Workspaces | Implemented | Local Personal and Work spaces; create, select, separate data. |
| 52 | Meeting notes | Implemented | Raw notes become summary, decisions, actions and questions, saved as a note. |
| 53 | Action-item extraction | Implemented | Confirmation before tasks are created. |
| 54 | Email drafting | Implemented | Never sends anything. |
| 55 | Report generation | Implemented | From selected notes. |
| 56 | SOP assistant | Implemented | Workspace documents, Q&A with sources. |
| 57 | Corporate knowledge search | Implemented | Per workspace, on the phone. |
| 58 | Training modules | Requires backend | |
| 59 | Corporate quizzes | Needs improvement | A quiz from a document exists in the Documents tab, not in the workspace view. |
| 60 | Learning assignments | Requires backend | |
| 61 | Progress dashboard (team) | Requires backend | |
| 62 | Team workspaces | Requires backend | |
| 63 | Role-based access | Requires backend | |
| 64 | Document permissions | Requires backend | |
| 65 | Audit history | Requires backend | |

## Personal productivity

| # | Item | Status | Notes |
|---|---|---|---|
| 66 | AI notes | Implemented | |
| 67 | Task manager | Implemented | Add, done, delete, per workspace. |
| 68 | Daily planner | Not implemented | |
| 69 | Goal tracking | Needs improvement | Through study-plan goals only. |
| 70 | Reminders | Not implemented | |
| 71 | Calendar integration | Requires user approval | Calendar permission. |
| 72 | Writing assistant | Needs improvement | Email drafts only. |
| 73 | Resume assistant | Not implemented | |
| 74 | Personal knowledge base | Implemented | Library plus notes. |
| 75 | Smart chat organization | Needs improvement | Manual folders. |

## Advanced AI

| # | Item | Status | Notes |
|---|---|---|---|
| 76 | Local RAG | Implemented | **Keyword (BM25)**, not semantic. |
| 77 | Vector search | Not implemented | Needs an embedding model download. |
| 78 | Web search | Requires external integration | A search API key. |
| 79 | Web citations | Requires external integration | |
| 80 | Image understanding | Requires user approval | |
| 81 | Voice conversation | Needs improvement | Voice input and read-aloud; no hands-free loop. |
| 82 | Text-to-speech | Existing → fixed | Now reads the whole answer in parts (RC.4 stopped at 3,000 characters). |
| 83 | Tool calling | Not implemented | |
| 84 | Workflow automation | Not implemented | |
| 85 | Response evaluation | Needs improvement | Feedback plus tutor evaluation. |
| 86 | Prompt library | Not implemented | |
| 87 | Custom assistant | Needs improvement | Custom instructions plus styles. |
| 88 | Personalities | Existing | |
| 89 | Context management | Needs improvement | Last 8 messages, plus Continue context. |
| 90 | Memory management | Existing | |

## Security and privacy

| # | Item | Status | Notes |
|---|---|---|---|
| 91 | Private mode | Implemented | Private chat: nothing written to the database, no sync, no new memories. |
| 92 | Local-only mode | Existing | Online AI off, or On this phone mode. |
| 93 | Data export | Needs improvement | Chats and memories (JSON, MD, TXT). Library, notes and cards are not in the export yet. |
| 94 | Data deletion | Implemented | New "Delete all documents, notes, tasks, plans and flashcards". |
| 95 | Secure backup | Not implemented | Backups stay disabled for privacy. |
| 96 | Provider privacy notice | Existing | |
| 97 | Permission management | Needs improvement | The system prompts for the microphone and notifications. There is no in-app overview. |
| 98 | Corporate access control | Requires backend | |
| 99 | Session management | Existing (account) / Requires backend (organisation) | |
| 100 | Security diagnostics | Implemented | Settings → Diagnostics, with no secrets. |
