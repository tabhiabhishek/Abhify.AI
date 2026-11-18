# v3.7: Career Copilot maintenance

Career Copilot 2.0 was delivered in **3.3.0**. This phase is an **audit plus small, justified improvements**, not a rebuild.

**Database:** v11 → v12.

## Audit: what already existed (unchanged)
| Area | Implementation | Status |
|---|---|---|
| Résumé profiles, parsing, versions | `resume/Resume.kt`, `chat/StudioDb.kt` (`resume_versions`), `ui/ResumeState.kt` | Kept |
| Skill normalisation (133 skills, aliases, related, ambiguous with context) | `career/Skills.kt`, `assets/skills_taxonomy.json` | Kept; related skills are shown but never counted |
| Résumé quality (100-point rubric) | `ResumeQuality` | Kept |
| Job description parsing and matching (weights 40/15/20/10/10/5, evidence) | `JobParser`, `MatchEngine`, `CareerAdvice` | Kept |
| Section improvement (accepted = new version) | `SectionImprove` | Kept |
| Application tracker (statuses, follow-ups, overdue) | `life/Life.kt` (`Career`, `Tracker`), `job_applications` | Kept |
| Interview practice with validated AI feedback (invalid feedback isn't saved) | `ui/LifeState.kt`, `AiJson.feedback` | Kept |
| DOCX and PDF export | `career/Export.kt`, `ResumePdf.kt` | Kept |
| Regression tests | `V33Test.kt` (15 tests) | All pass unchanged |

## Gaps found
1. **Job-specific practice wasn't linked to the job.** Attempts stored only a topic label, so "how have I practised for *this* job?" couldn't be answered.
2. **Practice scores weren't queryable.** The validated 1–5 scores were stored only inside the feedback markdown, so there was no progress view.
3. **Career goals had no place on the Career dashboard,** although Personal memory already has a "Career goals" category.

## Changes
| Change | Files |
|---|---|
| DB v12: `interview_attempts.jd_id`, `interview_attempts.score` (mean of the validated scores) plus an index. Old attempts keep NULL ("not scored"). | `chat/SchemaV12.kt`, `chat/LifeDb.kt` |
| A job-description question records the selected job. Deleting a job sets `jd_id` to NULL and keeps the history. | `ui/LifeState.kt`, `LifeDb.deleteJd` |
| `PracticeStats`: per topic and per job counts, averages, and a trend (last 3 scored attempts vs the 3 before; none until there are 4) | `career/Practice.kt` |
| Interview → Earlier practice: **All / This job** filter; each attempt shows its score | `ui/LifeScreens.kt` |
| Career dashboard: **Interview practice** card, and a **Career goals** card that reads memories in that category (one place, no duplicate goal store) | `ui/CareerScreens.kt` |

## Deliberately unchanged
- **Matching weights and the taxonomy.** No new evidence justified changing them.
- **No external job-search API.** It wasn't approved.
- **No new résumé storage.** Résumé versions already exist.
- **No changes to résumé data.**

## Tests
- `V37Test.kt` (2 tests): practice statistics (grouping, averages, trend, deleted job, invalid scores) and the v12 migration on a populated database (history kept, links cleaned on delete, unvalidated feedback still never saved).
- All 3.3 Career tests pass unchanged.
