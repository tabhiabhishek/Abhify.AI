# Abhify.AI 3.3.0: Career Copilot 2.0 implementation

**Audit note:** the database is a plain `SQLiteOpenHelper` (`ChatDb`), **not Room** as the request assumed. Migrations remain additive SQL objects (`SchemaV5`…`SchemaV8`).

## Features implemented (Career tab: Dashboard · Resumes · Jobs · Match · Interview · Question bank)

| Area | Implementation |
|---|---|
| Resume profiles | Several resumes (library documents with `role='resume'`). Active resume (per-phone preference), rename, delete with confirmation (removes the document and its versions), open the original and extracted text (Resume Assistant). Upload stays in the Resume Assistant: PDF, DOCX, TXT, MD, photo with OCR. |
| Extracted details | Name, summary, skills, experience (titles, companies and dates are inside it), projects, education, certifications, achievements, employment dates. Empty = "unavailable". **Manual editing** is saved as a `profile` version (JSON); the extracted original stays. |
| Resume quality | `ResumeQuality`: 100-point rubric (contact 15, summary 10, skills 15, experience 20, achievements 10, projects 10, education 10, certifications 5, readability 5). Each point comes from a listed rule. Strengths (≥ 80%), weaknesses (< 50%), suggestions. Rounded to 5. Labelled "does not predict hiring". |
| Skill taxonomy | `assets/skills_taxonomy.json` (133 skills, aliases, explicit related concepts, ambiguous words with context) → `career/SkillTaxonomy`: normalisation (case, whitespace, punctuation, compact forms), boundary-safe search with evidence lines. Ambiguous words count only with a context word or inside a skills list. |
| Skill matching | `SkillMatch`: direct, alias ("under another name"), related (**shown, never counted**), missing, coverage (rounded to 5). |
| Job descriptions | Paste, or **import from PDF, DOCX, TXT or MD** (existing extraction, OCR asked first; nothing uploaded). Save, edit, delete, search. `JobParser` extracts title, company, location, type, minimum years, experience lines, mandatory and preferred skills, responsibilities, qualifications, education, certifications. "Details" dialog for manual correction (skills normalised), stored in `job_descriptions.fields` (v8). The original text stays viewable. |
| Matching engine | `MatchEngine` (deterministic). Weights required 40 / preferred 15 / experience 20 / projects 10 / education and certifications 10 / responsibilities 5 (`Weights`, internal). Dimensions without job-description information are "not assessed" and the rest re-weighted. Experience = dated years versus the required years (70%) plus job-title words (30%). Per-requirement evidence: "Direct evidence found" / "under another name" / "Related experience only" / "No supporting evidence … doesn't mean you lack it". Strong matches and potential gaps. Overall "about N%" rounded to 5. |
| Improvement assistant | `CareerAdvice`: each item has recommendation, reason, job requirement, resume evidence and next action (missing required, missing preferred, weak resume sections, interview topics), plus learning priorities. Optional **AI project ideas**, labelled "Suggested project – not done yet" and never added to the resume. |
| Section improvement | Summary, skills, experience, projects or achievements → AI rewrite (`SectionImprove`): keeps facts, `[ask: …]` instead of inventing, changes explained. Original and suggestion are shown side by side, the suggestion is editable, the grounding check flags new numbers and skills, and Accept stays disabled while `[ask:]` remains. **Accept → new `accepted` version**; Reject discards. Nothing is overwritten. |
| Export | **PDF** (`ResumePdf`: Android `PdfDocument` + `StaticLayout`, A4, selectable text, paginated) and **DOCX** (`DocxWriter`: minimal OOXML zip, no library). Templates Simple and Compact; preview; save through the system picker; share through a new `FileProvider` (cache `exports/` only, not exported, no permission). Content = the resume text only. Success is reported only after the bytes are written. |
| Tracker 2.0 | Search, company and title filters, application-date and follow-up-date ranges, sorts (last changed, latest application, follow-up date), overdue and 7-day upcoming lists, **dashboard** (total, in progress, interviews, offers, rejections, upcoming follow-ups). All local. |
| Interview 2.0 | 12 topics (Python, AI/ML, Machine Learning, Deep Learning, Computer Vision, NLP, Backend, FastAPI, SQL, Technical, My projects from the **active resume**, **From job description** labelled with the requirement it tests, Behavioural). Structured feedback: JSON with five 1–5 scores (correctness, completeness, clarity, practical, communication), strengths, missing points, answer structure, recommendations. Validated (`AiJson`); **invalid output is shown with a warning and not saved**. The answer is kept for edit and retry. |

## Not implemented

- Updating a resume's file in place: a new file is a new resume. Use rename and delete to manage them. This keeps the original-document rule simple.
- Paginated lists: data sizes are small, and lists are loaded once per screen.
- UI tests: there is no Compose UI-test infrastructure in the project. The logic is unit-tested and the screens need device checks.

## AI routing and memory

- Every AI call (section improvement, project ideas, interview question and feedback) goes through `AppViewModel.aiTask`. That means the user's routing (local Qwen2.5 or the selected provider), the consent dialog naming the provider (with a "This phone" alternative), Cancel, and `HeavyGate`, so OCR and Qwen are never simultaneous.
- No new model and no new provider.
- Deterministic parts (taxonomy, matching, quality, job-description parsing, export) run without any model, on `Dispatchers.Default`/`IO` where heavy.

## Database

**v8:** `ALTER TABLE job_descriptions ADD COLUMN fields TEXT`.

Everything else reuses existing tables:

| Data | Stored as |
|---|---|
| Profile edits | `resume_versions` kind `profile` |
| Accepted improvements | `resume_versions` kind `accepted` |
| Active resume | preference |

## Files

| | Files |
|---|---|
| **New** | `assets/skills_taxonomy.json`, `career/Skills.kt`, `career/CareerEngine.kt`, `career/Export.kt`, `career/ResumePdf.kt`, `chat/SchemaV8.kt`, `ui/CareerState.kt`, `ui/CareerScreens.kt`, `res/xml/shared_files.xml`, `test/V33Test.kt`, `androidTest/ResumeExportTest.kt` |
| **Modified** | `AndroidManifest.xml` (FileProvider), `AppGraph.kt` (taxonomy load), `ChatDb.kt` (v8), `LifeDb.kt` (job-description fields), `life/Life.kt` (topics, prompts, Tracker), `ui/LifeState.kt` (interview 2.0, tracker query), `ui/LifeScreens.kt` (Jobs filters, job-description import and details, interview labels), `ui/AppViewModel.kt` (`career` state), `resume/Resume.kt` (taxonomy-based quick match), `androidTest/DbMigrationTest.kt` (v8), `app/build.gradle.kts` (version) |

Results: [V3_3_TEST_REPORT.md](archive/android-3.x/V3_3_TEST_REPORT.md).
