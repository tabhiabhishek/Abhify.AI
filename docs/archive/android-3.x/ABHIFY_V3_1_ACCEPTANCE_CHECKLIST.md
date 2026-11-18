# Abhify.AI 3.1.0: device acceptance checklist

**APK:** `Abhify-3.1.0-debug.apk`
- versionCode 3010099
- SHA-256 `B2C67A3C16B6BABDBE312F5DB2850AB9B31464117A7654B79930F7D20262D6FE`
- Same debug signing key as 3.0.0 and RC.4

**Install:** `adb install -r Abhify-3.1.0-debug.apk`. Never uninstall and never clear data.

**Status:** every result is **NOT RUN**: no device was connected. Record PASS, FAIL or BLOCKED with notes. For the RAM line, use the free memory shown in Settings → Processing profile.

**Device:**
- Model:
- Android:
- RAM total / free:
- Storage free:

## Upgrade and existing phone

| # | Test | Expected | Result |
|---|---|---|---|
| 1 | Install over 3.0.0 | No uninstall prompt. Opens the app directly; setup is **not** shown. | NOT RUN |
| 2 | Chats, memories, documents, notes, plans, cards | All present | NOT RUN |
| 3 | AI models | Same Qwen2.5 installed and selected; self-test passes; no download | NOT RUN |
| 4 | AI providers | Same keys (masked), same selected provider | NOT RUN |

## New phone (second device, or a test phone)

| # | Test | Expected | Result |
|---|---|---|---|
| 5 | Fresh install | Welcome → Profile → Setup (7 steps). The device step shows real values. | NOT RUN |
| 6 | Local-only path | Choose "On this phone only". There is no key step. Download the model (confirm, progress, cancel, retry), self-test, finish. Chat works offline. | NOT RUN |
| 7 | Online path | Choose "Online with my own key", add a key (masked), model list from the provider, finish without a model. | NOT RUN |
| 8 | Skip setup | The app opens. The home screen says what isn't ready. | NOT RUN |

## Documents and OCR

| # | Test | Expected | Result |
|---|---|---|---|
| 9 | Resume PDF with text | Extracted with **no** OCR prompt | NOT RUN |
| 10 | DOCX resume | Extracted | NOT RUN |
| 11 | Resume photo (camera, JPEG) | OCR prompt with the ML Kit disclosure → text. Unclear lines listed. | NOT RUN |
| 12 | Screenshot (PNG) | OCR, text correct | NOT RUN |
| 13 | Scanned multi-page PDF | "Recognizing page N of M…" progress; all pages read | NOT RUN |
| 14 | Cancel during OCR | Stops; "Cancelled, nothing saved"; retry works | NOT RUN |
| 15 | "Skip scanned pages" on a mixed PDF | Text pages kept; skipped pages listed | NOT RUN |
| 16 | OCR self-test (Settings) | Reports time and CER | NOT RUN |
| 17 | `RUN-TESTS.cmd` | DbMigrationTest, PdfTextTest and **OcrBenchmarkTest** pass; `results/ocr-benchmark.json` filled | NOT RUN |

## Resume Assistant

| # | Test | Expected | Result |
|---|---|---|---|
| 18 | Details | Name, email, phone, sections, skills correct; ⚠ on unclear OCR values; missing items listed | NOT RUN |
| 19 | Correct the text, then save | Analysis uses the correction; the original is still shown | NOT RUN |
| 20 | Suggestions / questions / draft with the **phone's AI** | Complete. Any new number or skill is flagged "Not found in your resume". | NOT RUN |
| 21 | Same with an online provider | Consent dialog first; "This phone" and Cancel work | NOT RUN |
| 22 | Job description | Local matched and missing lists; AI comparison doesn't add missing skills | NOT RUN |
| 23 | Export .md / .txt, Share | Files open correctly | NOT RUN |

## 8 GB memory behaviour

| # | Test | Expected | Result |
|---|---|---|---|
| 24 | OCR while the phone's AI is answering | "Please wait: the phone's AI is answering" | NOT RUN |
| 25 | Chat (local) while OCR runs | "Text recognition is running…" | NOT RUN |
| 26 | OCR, then a local question | The model reloads and answers (first answer slower) | NOT RUN |
| 27 | Open many apps, then OCR a 20-page scan | Low profile used, or a memory message. No crash; partial results reported | NOT RUN |
| 28 | Background the app during OCR, then return | No crash; result or a clear message | NOT RUN |
| 29 | Rotate during OCR and resume analysis | No crash; state kept | NOT RUN |
| 30 | Airplane mode | Local chat, documents, OCR and resume details all work | NOT RUN |
