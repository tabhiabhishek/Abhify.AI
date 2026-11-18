# Career navigation and Question Bank

## Career order (Android)
**Dashboard · Resumes · Question Bank · MCQs · Jobs · Match** (Jobs and Match last; `CareerSection` in `ui/CareerScreens.kt`).
- **Question Bank:** has three parts.
  - **Bank:** the curated questions.
  - **Generate:** new questions from your resume, skills, projects or a topic.
  - **Practise:** the existing interview practice with AI feedback, moved here unchanged.
- **Back:** from any section returns to the Dashboard first, then leaves Career as before.
- **Dashboard quick actions:** Interview questions, MCQ practice, Practise answers, Question Bank, Jobs, Match a job.
- **Job matching:** choosing a job in Jobs still opens Match with that job.

## Desktop/web
- **Tabs:** Interview and MCQ are now **Question Bank** and **MCQs**, under "Learn & career". The launcher reads "Career: Question Bank / MCQs".
- **No Resumes, Jobs or Match pages:** these exist only on Android.

## Question Bank refresh
| | Android | Web |
|---|---|---|
| How | the ↻ button or pull down | ↻ Refresh, or pull down on a touch screen |
| What it does | the curated questions of the current category in a new order; AI-written ones stay on top | the curated questions in a new order |
| Kept | category, generated questions, nothing deleted | category, difficulty, generated questions |
| Guard | one refresh at a time | one at a time |

**Generated and curated stay separate.**
- **Android:** generated questions are stored apart from the curated bank (`filesDir/career_questions.json`), with an outline marked "a guide, not a verified answer".
- **Web, a bug fixed:** generating questions used to *replace* the curated questions; now generated ones (marked AI) come first and the curated ones stay.

## Generate (Android)
- **Types:** technical, resume-based, project-based, conceptual, scenario, behavioural.
- **Batch size:** 3 or 5 questions (the phone model writes at most 3).
- **Grounding:** resume and project questions need a resume and use only its text. Duplicates of earlier generated questions are filtered.
