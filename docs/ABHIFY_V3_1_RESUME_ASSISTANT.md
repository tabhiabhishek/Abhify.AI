# Abhify.AI v3.1: Resume Assistant, OCR and 8 GB memory safety

**Where to find it:**
- Documents tab → **📄 Resume Assistant**
- Work → Write
- Settings → Documents and text recognition

## How it works (implemented)

| Step | What happens | Where |
|---|---|---|
| Upload | PDF, DOCX, TXT/MD, JPEG, PNG or WebP (10 MB max) through the system picker; or choose a library document | `ResumeScreen`, `DocumentReader` |
| Extraction | DOCX and text as before. PDF: each page's text layer first; only pages with fewer than 25 letters or digits are rendered (Android `PdfRenderer`, one page at a time, bitmap recycled) and OCR'd. Images are decoded bounded by `ImageDecoder` (EXIF rotation applied). | `ocr/Extraction.kt`, `ocr/AndroidOcr.kt` |
| OCR permission | Asked the first time a scan or image needs it. Choices: read / skip scanned pages / cancel, with "remember". Also set in Settings or setup. The same file is retried after the answer, so there is no re-upload. | `ocrAsk` dialog |
| Review | Warnings per page: OCR used, unclear lines (ML Kit confidence < 0.6), skipped or failed pages. The text can be corrected; the correction is saved as a version, and the original extraction is kept and shown. | Text tab |
| Details (no AI) | Name, email, phone, links, dates, sections (Summary, Experience, Education, Skills, Projects, Certifications, Languages, Achievements), skills from a fixed list, lines with numbers. Values from unclear OCR lines get ⚠. Missing items are listed as missing. | `resume/Resume.kt` |
| AI analysis | Summary, improvement suggestions, interview questions, improved draft, full job-description comparison. Goes through the normal routing; online use needs the consent dialog (with a "This phone" alternative). | `ResumeState.run` |
| No invention | Rules are in the system message and in every prompt. The **grounding check** lists every number, skill or email in the output that isn't in the resume ("⚠ Not found in your resume"). Drafts use `[add number]`. | `Resume.unsupported` |
| Job description | Local exact skill match (found / not in resume) and requirement lines; missing skills are never added. | `Resume.match` |
| Versions | Corrections and complete AI results are stored in `resume_versions` (database v6). Partial results are not saved. | Saved tab |
| Export | Copy, Share, Save .md / .txt for details, results and versions. | `ExportButtons` |

## Memory safety on 8 GB phones (implemented)

1. **Profiles** decide real limits. Auto picks Balanced, or Low below 1.8 GB free or under memory pressure. Quality needs 3 GB free, otherwise it falls back with a message.

| Profile | OCR image (longest side) | Scanned pages per document | PDF render | Document characters for the phone's AI |
|---|---|---|---|---|
| Low | 1600 px | 8 | 150 dpi | 2,500 |
| Balanced | 2200 px | 25 | 200 dpi | 3,500 |
| Quality | 3000 px | 40 | 300 dpi | 3,500 (not raised untested) |

2. **One heavy job at a time:**
   - OCR is refused while the phone's AI is answering.
   - OCR unloads Qwen2.5 first. The file stays; the model reloads on the next local answer.
   - Model loading is refused while OCR runs.
3. **Low memory:**
   - Foreground `onTrimMemory(RUNNING_LOW / CRITICAL)` and `onLowMemory` refuse new OCR for 60 seconds.
   - A running job stops OCR at the next page and keeps what it read, marked as skipped.
   - OCR doesn't start with less than 500 MB free.
4. **Cleanup and cancellation:**
   - Every rendered bitmap is recycled right after OCR.
   - The recognizer is closed on success, failure or cancel.
   - The temporary PDF copy is deleted.
   - Cancel leaves nothing behind.

## Tests

| Kind | Coverage |
|---|---|
| JVM | `V31Test.kt`: 21 tests covering memory profiles, gate, bounded sizes, the pipeline (mixed, text-only, limits, failures, cancellation, image), CER/WER, the resume parser, unclear and missing fields, grounding, job match, prompts, setup and the v6 migration |
| On device (built, **not run**) | `OcrBenchmarkTest`, `DbMigrationTest` (v2→v6), `PdfTextTest` |

## Not implemented or limits

- No confidence values for PDF text layers.
- No table structure from DOCX or PDF (text only).
- Skill matching uses a fixed list of about 150 skills (exact words, no synonyms).
- Handwriting and non-Latin OCR are not supported.
- No PDF or DOCX export: `.md` / `.txt` only.
