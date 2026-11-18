# Abhify.AI 3.0.0 (debug build, phone acceptance pending)

**Upgrade from 2.0.0-rc.4:** install with `adb install -r`. It uses the same signing key, and the database upgrades from v4 to v5 additively.

## Complete answers (RC.5 fixes)

- **Long answers no longer stop early.**
  - RC.4 limited online answers to 768 tokens, and reasoning models used part of that for hidden thinking. Defaults are now 8192 tokens for Gemini and 4096 for Groq and OpenRouter, adjustable per provider. The phone's model gets 1024.
  - A streaming bug could end an answer early when text arrived faster than the screen updated, which was likely with Groq. It is fixed and tested with 5,000 chunks.
- **Honest status for partial answers:** cut off by the length limit, stopped by you, or interrupted. The status is stored and shown after a restart.
- **▶ Continue** extends a partial answer with the same provider or model, without repeating text.
- **Read more / Show less** for long answers. Copy, Share, Read aloud and Export always use the complete text.
- **Read aloud** now reads the whole answer, not just the first 3,000 characters.
- 👍/👎 feedback on answers.
- The chat follows the end of a streaming answer, unless you scroll up to read.

## Five areas

| Area | Contents |
|---|---|
| **Chat** | As before. Adds private chats, chat folders, summaries, and saving a chat as .md or .txt. |
| **Learn** | AI tutor (3 levels plus answer checking), study plans with lessons and progress, flashcard decks with review, interview questions, quizzes, AI news. |
| **Documents** | A library on the phone with PDF (text), DOCX, TXT, MD and CSV. Summary, key points, action items, study notes, flashcards, quiz, comparison, and questions with cited sources (page or section). |
| **Work** | Local workspaces with tasks, notes, meeting notes → action items (with confirmation), knowledge Q&A over workspace documents, email drafts, reports. |
| **Settings** | Providers, models, style, privacy, memory, account, sync, diagnostics. |

## Privacy

- Document and note text goes to an online AI only after a dialog that names the provider. You can choose to run it on the phone instead.
- New "delete all documents, notes, tasks, plans and flashcards".
- Private chats are never stored.

## Limits

See [V3_KNOWN_LIMITATIONS.md](V3_KNOWN_LIMITATIONS.md):
- no OCR, web search or organisation server;
- keyword retrieval, not semantic;
- debug-signed;
- not yet tested on a phone or with real provider keys.
