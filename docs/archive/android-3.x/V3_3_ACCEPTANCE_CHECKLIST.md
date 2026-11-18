# Abhify.AI 3.3.0: manual acceptance checklist

**Install:** `adb install -r "D:\Abhify.AI\build\Abhify-3.3.0-debug.apk"`. Never uninstall; never clear data.

**Status:** every result is **NOT RUN**. Record PASS, FAIL or BLOCKED with notes.

| # | Area | Steps | Expected | Result |
|---|---|---|---|---|
| 1 | Resume import | Resumes → ＋ → upload a PDF, a DOCX and a photo | Each appears in Resumes; OCR is asked first for the photo | NOT RUN |
| 2 | Resume selection | Tap the radio on another resume; rename it; delete a test resume | Active marker moves; the new name shows; delete asks first and removes it | NOT RUN |
| 3 | Resume analysis | Tools → Quality; Edit details → change the summary → Save | Score with every category explained; edits reflected in Quality and Match; the original text is still shown in the Resume Assistant | NOT RUN |
| 4 | Skill normalisation | Resume says "ML, NLP"; a job asks for "Machine Learning" | Listed under "Matches under another name" | NOT RUN |
| 5 | Skill matching | A job requiring Python, FastAPI, PyTorch, SQL, Docker; resume with Python, FastAPI, PyTorch, OpenCV, MongoDB | Direct: Python, FastAPI, PyTorch · No evidence: SQL, Docker · coverage ~60% · OpenCV never counted for SQL | NOT RUN |
| 6 | Job description import | Jobs → ＋ Job description → Import PDF/DOCX → Save → Details | Fields extracted; corrections saved; original text viewable; search finds it | NOT RUN |
| 7 | Job matching | Match → choose resume and job → Compare | Overall "about N%", dimensions with weights, evidence per requirement, gaps | NOT RUN |
| 8 | Match explanation | Read each dimension and requirement line | Plain-language reason. Missing evidence says "doesn't mean you lack it". Not-assessed dimensions are marked. | NOT RUN |
| 9 | Resume improvement | Improve a section → Summary → suggestion → edit → Accept | Original and suggestion side by side; new facts flagged; `[ask:]` blocks Accept; a new "Improved resume" version; the original unchanged | NOT RUN |
| 10 | Resume export | Export → PDF and DOCX → Save, then Share | Files open in a PDF viewer or Word; text matches the preview; share sheet works | NOT RUN |
| 11 | Application tracker | Add 3 applications with dates; try the filters and sorts; check the Dashboard | Overdue and upcoming lists correct; counts correct; delete asks first | NOT RUN |
| 12 | Interview practice | Topic "From job description" → question shows its requirement → answer by voice → feedback | Scores 1–5 plus sections; saved under Earlier practice; answer kept for retry | NOT RUN |
| 13 | AI output validation | Use the phone's small model for feedback until it returns non-JSON | Warning "couldn't be validated … wasn't saved"; no crash | NOT RUN |
| 14 | Local Qwen | On this phone mode: improve a section and get interview feedback offline | Works offline; only one heavy job at a time | NOT RUN |
| 15 | Cloud provider consent | Online mode: improve a section | Consent dialog names the provider; "This phone" and Cancel work | NOT RUN |
| 16 | Existing 3.2 features | Chat, Study, Documents, Knowledge, Voice, OCR, Settings | Unchanged behaviour | NOT RUN |
| 17 | Data preservation | After upgrading: resumes, versions, job descriptions, applications, attempts, chats, documents | All present | NOT RUN |
| 18 | Upgrade installation | `adb install -r` over 3.2.0 | "Success"; no uninstall prompt; setup not shown | NOT RUN |
| 19 | Memory | Scan a resume photo (OCR), then immediately improve a section with the local model | OCR finishes first; no crash; the model reloads afterwards | NOT RUN |
| 20 | UI stability | Rotate, switch tabs, go back during Match, Export and Interview | No crash; state kept; the microphone stops when leaving Interview | NOT RUN |
| 21 | Phone tests | `RUN-TESTS.cmd` from the v3.3 kit | DbMigrationTest (v8), PdfTextTest, OcrBenchmarkTest, ResumeExportTest pass | NOT RUN |
