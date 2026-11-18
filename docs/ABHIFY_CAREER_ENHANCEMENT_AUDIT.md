# Career, refresh and MCQ audit (2026-11-10)

## Baseline (before any change)
- **Git:** branch `feature/m2-knowledge-packaging`, HEAD `a9325d2`; the previous milestones are uncommitted.
- **Tests:** backend **368 passed, 1 skipped**; Android **294 run, 0 failed, 4 skipped**.
- **Phone:** the owner installed the stability preview and confirmed New private chat works on a phone.

## What exists
| Area | Android | Desktop/web |
|---|---|---|
| Career navigation | `CareerTab` (`ui/CareerScreens.kt`): Dashboard, Resumes, Jobs, Match, Interview, Question bank (local section index, no back handling) | none; Learn has Interview (question bank) and MCQ tabs |
| Resumes | library docs with role "resume"; `resume/Resume.parse` (sections, skills, OCR-unclear fields, missing fields); `career/Skills.kt` taxonomy (133 skills); quality score; details editor; improve/accept; export | none (resumes would be knowledge documents) |
| Question bank | curated `assets/learning_bank.json` (`LearningBank`) + "New question from the AI (offline)" (on-phone model, one at a time) | `/generate-question` (3 at a time) + localStorage |
| Interview practice | `LifeState.nextQuestion/submitAnswer`: topics including "My projects" (resume-based) and "From job description", validated AI feedback, attempts in `interview_attempts` | none |
| MCQs | Study → Quiz: bundled MCQs + one AI question at a time; `TaskRunner.parseQuiz` (tolerant, from the stability milestone) | MCQ tab: `/generate-mcq` (5, curated categories), select then Submit; Task Runner quiz from a document |
| Jobs / Match | full (`JobsView`, `MatchView`, `MatchEngine`) | none |
| News refresh | `NewsState.open(force=true)`: always fetches (no client cache); a progress bar while loading; no pull-to-refresh; no "updated" confirmation | `newsOpen(cat, true)` → `/news/feed?refresh=true` (backend fetches); **no single-flight guard** (repeated taps run duplicate requests), **no stale-response guard** (a slow response for the old category can overwrite the newly selected one), errors only as a toast |
| Pull-to-refresh | none | none; on a phone browser the **browser's own** pull-to-refresh reloads the whole page (no `overscroll-behavior`) |
| AI providers | `vm.aiTask` (AI mode: device, online provider, Auto, with consent), the Model chip/sheet to switch | local Ollama; per-chat model chip |

## Bugs and gaps
1. **Career order:** Jobs and Match sit in the middle. There is no MCQ section, and Interview practice is a separate section.
2. **No personalized MCQs:** MCQs come from fixed categories; none from a resume, skills, projects or a document. There is no batch continuation and no deduplication across batches.
3. **Model answers presented as facts:** both platforms show the model's "correct" answer without saying it's AI-generated.
4. **News refresh:**
   - **Web:** duplicate and racing requests, no visible result.
   - **Android:** refresh works, but there's no confirmation and no pull gesture.
   - **Not reproduced:** the exact symptom the owner saw. The defects above are the ones found in code.
5. **Whole-page reload when pulling down on a phone browser:** the browser's native gesture. The app sets no `overscroll-behavior`.

## Plan (no database change)
- **Android Career:**
  - order: Dashboard, Resumes, Question Bank (with Practise and Generate), MCQs, Jobs, Match;
  - back returns to Dashboard.
- **MCQ practice (both platforms):**
  - pure engine for prompts, validation, deduplication and progress;
  - JSON files: Android `filesDir/career_mcq.json`, desktop `<data>/data/career_mcq.json`;
  - batches of 3–5, "more" and "refresh";
  - immediate feedback;
  - an "AI-generated, not independently verified" label (a quote found in the source is shown as *supporting text*, never as proof).
- **Question generation (Android):** resume, skill, project or topic based; generated questions kept apart from the curated bank (`filesDir/career_questions.json`).
- **Refresh:**
  - single-flight requests, a stale-response guard, the previous content kept on failure, a status line;
  - pull-to-refresh with `PullToRefreshBox` (Android) and a custom gesture on the active section (web);
  - `overscroll-behavior-y: contain` stops the browser's page reload.
