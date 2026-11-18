# Abhify.AI 3.2.0: device acceptance checklist

**Status:** every result is **NOT RUN**. Record PASS, FAIL or BLOCKED with notes.

**Install:** `adb install -r "Abhify-3.2.0-debug.apk"`. Never uninstall; never clear data.

## Upgrade and setup

| # | Test | Expected | Result |
|---|---|---|---|
| 1 | Install over 3.1.0 | No uninstall prompt. Setup is not shown. Chats, documents, resumes, keys and the model are all present. | NOT RUN |
| 2 | Bottom bar | Chat · Career · Study · Documents · Knowledge. ⚙ in the header opens Settings. | NOT RUN |
| 3 | Settings | Providers, models, OCR, memory profile, Run setup again: all open, values kept after restart | NOT RUN |

## Career

| # | Test | Expected | Result |
|---|---|---|---|
| 4 | Career → Resume | Opens the Resume Assistant. Existing resumes are listed. | NOT RUN |
| 5 | Jobs → ＋ Job description → Compare with resume | The Resume Assistant opens with the job description filled in; exact match shown | NOT RUN |
| 6 | Jobs → ＋ Application (all fields, bad date "05/11/2026") | Date error shown; fix it → saved; edit; search; status filter; delete asks first | NOT RUN |
| 7 | Interview practice: Python question → 🎙 Speak → edit → Get feedback | Transcription editable. Feedback shown and saved under Earlier practice. Leaving the screen stops the mic. | NOT RUN |
| 8 | My projects question with a resume open | The question refers to a resume line | NOT RUN |
| 9 | Share a resume result | Contact details hidden unless ticked | NOT RUN |

## Study

| # | Test | Expected | Result |
|---|---|---|---|
| 10 | Topic → Explain / Summary / Quiz / Flashcards / Revision plan | Each saved in Study history with its source | NOT RUN |
| 11 | Document source → Quiz in Online mode | Consent dialog first; "This phone" works | NOT RUN |
| 12 | Quiz: answer all, then Review wrong | Score correct; the review quiz has only the missed questions | NOT RUN |
| 13 | Leave a quiz half done, then reopen it from history | Resumes at the same question with earlier answers kept | NOT RUN |
| 14 | Share score | Text has only the topic and score | NOT RUN |

## Voice

| # | Test | Expected | Result |
|---|---|---|---|
| 15 | Chat 🎙: deny permission once, then allow | A clear message, then it works. The text goes into the box, not sent. | NOT RUN |
| 16 | 🔊 Read a long answer → Pause → Resume → Stop | Resumes near the same place; Stop ends it | NOT RUN |

## Documents and My Knowledge

| # | Test | Expected | Result |
|---|---|---|---|
| 17 | Tick 2 documents → Ask | Sources only from those 2, with pages for PDFs | NOT RUN |
| 18 | A question not in the documents | "No section … matches", nothing sent | NOT RUN |
| 19 | ⭳ Save a chat answer, a study summary and a document | All appear in Knowledge. Search, category, tags, favourite, edit and delete work. | NOT RUN |
| 20 | Tick items → Ask | Answer cites only the ticked items | NOT RUN |
| 21 | Scanned PDF → OCR, then a local chat question | OCR first; the model reloads afterwards; no crash | NOT RUN |

## Kit

| # | Test | Expected | Result |
|---|---|---|---|
| 22 | `RUN-TESTS.cmd` | DbMigrationTest (v7), PdfTextTest and OcrBenchmarkTest pass; `ocr-benchmark.json` saved | NOT RUN |
