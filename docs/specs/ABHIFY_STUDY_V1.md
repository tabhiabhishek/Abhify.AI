# Abhify Study v1 (7.2)

Study helps one person learn courses and prepare for interviews. It runs on each platform by itself (Windows, Web,
Android), with no sync and no shared backend, like the rest of Abhify.

## What it does

| Part | What the user gets |
|---|---|
| Today | Readiness (overall and per skill), study streak, flashcards and missed questions due, today's plan items, weak areas, courses to continue |
| Courses | 5 ready-made tracks (AI/ML, Software Engineering, Data Science, Cloud, Behavioural) and courses on any topic (AI-written outline) |
| Learning journey (per course) | Goal (a preset or custom target role) and level (beginner / intermediate / advanced or custom) chosen on first open. Changing them later keeps all progress |
| Overview | Summary, objectives, prerequisites, optional modules for this learner, why each module matters, completion criteria. Built from the course's real syllabus |
| Lessons | Written on demand and saved, in layers: In simple words → Going deeper → Advanced → Real-world example → Code example → Architecture sketch → Common mistakes → Practice exercise → Interview questions → Follow-up questions. The depth follows the level |
| AI tutor | Questions about the open lesson. The context is the lesson and the course goal only |
| Practice | Unlimited MCQs: the next batch of 5 loads as the list scrolls to the end. Rotates core modules, one module, or weak topics. No repeats |
| Revision | Flashcards made from lessons, plus missed questions, on a spaced schedule (1, 3, 7, 14, 30 days). Daily revision covers what is due; weekly revision covers this week's lessons |
| Course dashboard | Progress, lessons and modules done, time studied, score, exercises done, weak topics, items to revise, streak, next lesson, capstone progress, weekly milestones, learning history |
| Capstone | One project sized for the goal and level, with checkable milestones |
| Interview plan | Paste a job description (plus optional resume text and interview date). Abhify finds the required and preferred skills, compares them with the resume, and makes a day-by-day plan. Each item links to the matching course |
| STAR stories | A story bank covering 8 behavioural themes, with AI coaching (S/T/A/R scores 1–5, what is missing, tips, a tighter version that keeps only the user's facts) |

## Rules (deterministic, shared)

`backend/study_core.py` is the reference. `frontend/study-core.js` and the Android `StudyCore` must reproduce
`backend/tests/data/study_cases.json` exactly. Regenerate the cases with `python backend/tests/make_study_cases.py`.

- **Skills from a job description.** Taxonomy names and aliases are matched as whole words (`skills_taxonomy.json`).
  - A short line ending in `:`, written in capitals, or starting with `#` is a heading.
  - "Preferred / nice to have / bonus / a plus…" headings or sentences mark a skill as preferred. Everything else is required.
  - A skill that appears in both lists stays required.
- **Gaps.** Up to 12, in this order:
  1. required skills missing from the resume;
  2. preferred skills missing from the resume;
  3. required skills already on the resume (to refresh).
- **Plan.** It runs to the interview date (1–60 days; 14 when there is no date).
  - Each day has a lesson (45% of the time) and MCQs (25%), with skills taken in rotation.
  - Every second day adds a STAR story (20%). From day 2, each day adds review (10%).
  - On plans of 3 days or more, the last day is a rehearsal.
- **Skill score.**
  - Formula: `round(100 × (0.6 × accuracy × min(1, answered/10) + 0.4 × lessons done/total))`.
  - Rounding: Python rounding, where halves go to the even number.
  - Levels: below 40 is "Not ready", below 70 is "Getting there", and 70 or more is "Ready".
- **Review.** A missed question or "not yet" flashcard is due tomorrow. Each later right answer pushes it out to 3, 7, 14, then 30 days. After that it is done.
- **Course schedule.**
  - Lessons follow the syllabus order, with optional modules last and done lessons left out.
  - The number of lessons a day is `max(1, minutes ÷ 30)`.
  - Every 7th day is a revision day.
  - The weekly milestones list the modules completed that week.
- **Module mastery and weak topics.** Mastery uses the skill score formula on the module's answers and lessons. A module is weak after 3 or more answers below 60% right.

## Where data lives

| Platform | Storage | AI |
|---|---|---|
| Windows | `<data>/data/study.json`, a single JSON file with no database migration. It is included in backups (`BACKUP_INCLUDE`) | The chat's chosen model (local Ollama by default) through `/learn/*` routes (`local_only`) |
| Web / on-device / paired phone | This browser's `localStorage` (`abhify.study.v1`) through `frontend/study-local.js`, which answers the same routes in the browser | The on-device model. It downloads only after the user confirms the size |
| Android | `filesDir/study.json` | The on-device model |

Study data is not part of Drive sync (v1).

## Honesty and privacy

- Every number shown is recorded data. Before the first answer the score shows "–".
- Lessons, questions and feedback are labelled as AI-written and not independently verified.
- Overviews may only refer to modules from the real syllabus; invented ids are dropped.
- STAR coaching never adds facts.
- The tutor sees only the lesson and the course goal. It never sees chats, memories or documents.
- Study time is counted only while a lesson or practice screen is visible and focused, at most 600 seconds per report.

## API (Windows)

Routes go under `/learn`, because `/study` is the page URL.

| Area | Routes |
|---|---|
| Overview | `GET /learn/state`, `GET /learn/tracks`, `GET /learn/prefs/options` |
| Courses | `POST /learn/courses` (`{track}` or `{topic}`), `GET` and `DELETE /learn/courses/{cid}`, `PUT /learn/courses/{cid}/prefs`, `POST /learn/courses/{cid}/overview`, `GET /learn/courses/{cid}/dashboard`, `POST /learn/courses/{cid}/time`, `POST /learn/courses/{cid}/exercise`, `POST /learn/courses/{cid}/cards`, `POST /learn/courses/{cid}/capstone`, `POST /learn/courses/{cid}/capstone/step`, `POST /learn/courses/{cid}/tutor` |
| Lessons | `POST /learn/lesson`, `POST /learn/lesson/done` |
| Practice | `POST /learn/mcq` (`round`, `module_id`, `weak`, `avoid`; reuses the career MCQ generator), `POST /learn/mcq/answer`, `GET /learn/review` |
| Revision | `GET /learn/cards` (`course_id`, `weekly`), `POST /learn/cards/{id}` (`remembered`) |
| Interview plan | `POST` and `DELETE /learn/plan`, `POST /learn/plan/item` |
| STAR stories | `GET` and `POST /learn/stories`, `DELETE /learn/stories/{sid}`, `POST /learn/stories/{sid}/coach` |

## Not in v1

- Personal AI Brain context (memories, projects and career goals) in lessons. It is planned as an opt-in toggle for each course.
- Rendered architecture diagrams. Lessons include text diagrams only.
- Running code inside lessons.
- Sync of Study data between devices.
