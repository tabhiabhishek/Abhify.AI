# v3.9: advanced Study Assistant

**Status:** implemented and tested on the JVM. Phone validation is pending.

**Database:** v13 → v14.

The existing Study features are reused, not duplicated: Study assistant (summaries, key points, terms, quizzes from documents), Tutor, Plans, Cards, Quiz and the study history.

## Spaced repetition (flashcards)
`learn/Srs.kt` is a **transparent SM-2-style heuristic** (after Wozniak's SM-2). It is **not** a scientifically validated or personalised algorithm.

| Button | Effect |
|---|---|
| Again | Back in 10 minutes; ease −0.20; counted as a lapse |
| Hard | Interval × 1.2 (at least 1 day); ease −0.15 |
| Good | 1 day, then 3 days, then interval × ease |
| Easy | 3 days for a new card, else interval × ease × 1.3; ease +0.15 |

- **Limits:** ease stays between 1.3 and 3.0; intervals are capped at 180 days.
- **Next interval on the button:** each button shows when the card would come back (for example "Good · 3 days") before you tap it.
- **Every rating is logged** in `card_reviews`.
- **The old flag is kept meaningful:** Good/Easy set "known", Again clears it.
- **Existing cards** keep their `known`/`reviews` counts and start **due now** with the default ease. Nothing is reset.
- **Review all due cards** across decks from the Cards screen or the dashboard. New cards are limited to 20 per session so a large new deck doesn't flood it.
- If a deck has nothing due, opening it practises the whole deck, and ratings still schedule.
- **No reminders or notifications were added,** so there is nothing to disable. Due counts are shown in the app.

## Study plans
- **Inputs:** topic, difficulty (the existing level), goal, target date, minutes per day, and **optional library documents**.
  - For documents, their names and opening ~400 characters go into the prompt.
  - With an online provider this passes through the usual consent ("the selected documents").
- **Checks (`PlanCheck`):**
  - at least 3 lessons; at most 30 (extra ones dropped);
  - duplicates removed;
  - 10–600 minutes per day;
  - the target date must be a valid date after today.
  - If there is less than about 15 minutes per lesson before the date, the plan is saved **with a warning** to extend the date or narrow the topic.
  - The prompt tells the AI how many days are available.
- **Manual editing:** edit, delete or add lessons. Plans show which documents they came from (or "deleted document").
- **Progress:** lessons done, with the date each was done (existing).

## Knowledge-gap suggestions
`learn/StudyInsights.kt` uses **only** the learner's own study records: quiz answers per category, document quizzes, flashcards and plans. It never uses chats, memories or personal data.

| Rule | Example reason shown |
|---|---|
| Quiz category below 60% with 5+ answers | "Low quiz accuracy: 4 of 10 correct (40%)." |
| Last document quiz below 60% | "Last quiz on this material: 3 of 10 correct." |
| Deck with 3+ cards forgotten twice or more | "4 cards forgotten twice or more." |
| Deck with 10+ cards due | "12 cards are due for review." |
| Deck not reviewed for 30+ days | "Not reviewed for 40 days." |
| Plan target date passed with lessons left | "Target date 2026-11-01 has passed with 8 lessons left." |
| Plan with no lesson done for 14+ days | "No lesson done for 20 days." |

Every suggestion has a reason and a next action. They are sorted by a fixed priority, and the first one is shown as "Recommended next".

## Study dashboard (top of Study)
- Recommended next session.
- Cards due, new and mature (21 days or more), and reviewed today; with **Review N due cards**.
- Active plans with progress.
- Quiz history per category and recent document quizzes.
- Other topics that may need review.
- Recent documents.

## Privacy
All of this is local. Study history is never sent to an online AI automatically.

## Tests (`V39Test.kt`, 4 tests)
- Scheduler intervals, ease floor and cap, labels.
- Plan checks (past or invalid date, too few lessons, minutes range, the tight-time warning, deduplication, the 30-lesson limit).
- Gap rules with reasons and ordering (nothing from good data or too few answers).
- The v14 migration on populated data (cards and plans kept, new columns defaulted); deck delete removes its review log; no notifications.

## Limits
- The scheduler is a heuristic; intervals are not personalised.
- The `card_reviews` log is not included in backups. The schedule itself is: the columns on `flashcards`.
- Plans are written by the AI. The checks catch unrealistic timing but cannot guarantee lesson quality.
