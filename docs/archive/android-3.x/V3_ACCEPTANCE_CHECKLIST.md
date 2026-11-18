# Abhify.AI 3.0.0: phone acceptance checklist

**APK:** `Abhify-3.0.0-debug.apk`
- versionCode 3000099
- SHA-256 `E89B6CD109C034DA1B3010FD03B967A2A2E4B6AE04949A117259B6BD632EAACF`
- Signed with the same debug key as RC.3 and RC.4 (certificate `1d2d1fdb…e6246f`)

**Install over RC.4:**

```
platform-tools\adb.exe install -r Abhify-3.0.0-debug.apk
```

Do **not** uninstall the app and do **not** clear app data.

**Status:** every item is **PENDING**: no phone was connected during the build. Mark each one PASS or FAIL with a note. Items 4–8 need at least one real provider key.

## Upgrade and data

| # | Check | Expected |
|---|---|---|
| 1 | Install over RC.4 | It installs without asking you to uninstall. All chats, memories, provider keys and the selected provider are kept. |
| 2 | AI models → Self-test | The same Qwen model passes. Nothing is downloaded again. |
| 3 | Airplane mode, **On this phone** mode, ask a long question ("Explain transformers in detail") | The answer is complete, or marked "Partial: …length limit" with a **▶ Continue** button that extends it. |

## Response reliability (P0)

| # | Check | Expected |
|---|---|---|
| 4 | Groq, Online mode: "Explain how transformers work in 800 words with headings" | The whole answer appears. The newest answer is fully expanded, and the screen follows the end while it streams. |
| 5 | Same request with Gemini and with OpenRouter | Each is complete and labelled with its provider. |
| 6 | AI Providers → Groq → answer length limit **1k**, then the long question again | The answer is marked "Partial answer: it reached the answer length limit". **▶ Continue** extends the same message without repeating text. |
| 7 | Tap ■ Stop mid-answer | "Partial answer: you stopped it". Continue works. The answer is still labelled with its provider. |
| 8 | Close the app completely and reopen the chat | Long answers are complete, and partial ones still show their status. |
| 9 | Scroll to an older long answer | It starts collapsed, with "Read more ▼ (N more characters)". Show less collapses it again. Copy and Share give the full text. |
| 10 | 🔊 Read on a long answer | The whole answer is read, past 3,000 characters. Stop ends it. |
| 11 | 👍/👎 on an answer | The choice is kept after a restart. |

## The five tabs

| # | Check | Expected |
|---|---|---|
| 12 | Switch tabs: Chat → Learn → Documents → Work → Settings → Chat | Each tab works. The chat, the typed text and each tab's state are kept. Labels are readable at the largest font size. |
| 13 | Documents → ＋ Add a **text PDF**, a DOCX and a TXT | Each is listed with its size and character count. **View text** shows the PDF with "— Page N —" headings. |
| 14 | A scanned PDF or a photo | A clear "no text layer / OCR isn't available" message. No crash. |
| 15 | A document → Summary / Key points / Flashcards / Quiz in **Online** mode | "Send … online?" appears first. **This phone** runs it locally. **Cancel** sends nothing. |
| 16 | Ask your documents a question that is in one document | The answer cites [S1]… The source list names the document and the page (PDF) or section. |
| 17 | Learn → Tutor, Plans (create a plan, tick lessons), Cards (make a deck, review) | Everything is saved after a restart. Progress counts update. |
| 18 | Work → Meeting notes → Structure with AI → "Add them as tasks…" | Tasks are added only after you confirm. The note is saved in the workspace. |
| 19 | Work → Knowledge: add a document to the Work workspace, then ask | Only that workspace's documents are searched. The personal library is not. |
| 20 | Settings tab → every row opens. Diagnostics shows no key text. | – |

## Privacy

| # | Check | Expected |
|---|---|---|
| 21 | Menu → New private chat, chat, then End | Nothing from it appears in History. |
| 22 | Privacy → "Delete all documents, notes, tasks, plans and flashcards" | Those are gone. Chats, memories, keys and the model stay. |

## Automatic phone tests (USB debugging)

`RUN-TESTS.cmd` runs only `DbMigrationTest` (v2 → v5 on a scratch database) and `PdfTextTest` (generated PDFs). Neither touches your chats, keys or model.
