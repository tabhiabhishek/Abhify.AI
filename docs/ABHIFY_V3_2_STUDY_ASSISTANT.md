# Abhify.AI 3.2: Study Assistant (Study tab)

## Study (new)

**Choose a source:**
- a **topic** you type;
- a **document** from the library;
- a **note** from your workspaces.

**Generate:**

| Action | What you get |
|---|---|
| Explain | For topics: intuition, details, an example, common mistakes |
| Summary | Short summary, key points, important terms (only from the material) |
| Quiz | 6 multiple-choice questions. Strict check: 4 different options and a valid answer, else "try again". |
| Flashcards | 10 cards, with previous / next / reveal |
| Revision plan | N days × M minutes. "Track in Plans" turns it into a tick-off plan. |

**Practice mode:**
- One question at a time. An answer can't be changed after choosing.
- Shows correct and wrong counts, the score, and the explanation per question.
- **Review wrong answers** starts a new quiz with only the missed questions. These are the topics that need review.

**Study history:**
- Every result is saved with its source (document, note or topic, and the model that wrote it).
- Resume a quiz where you left it, review, or delete with confirmation.

**Saving and sharing:**
- Save any session to **My Knowledge**.
- Share a quiz **score**: the topic, score and number of questions only.

## Still in Study (from earlier versions)

Tutor (3 levels plus answer checking), Plans (lessons with progress), Cards (decks with known/learning review), Quiz bank, News.

## Notes

- Generated material can contain mistakes; the screen says so.
- Document and note text goes online only after the consent dialog, with an on-phone alternative.

## Tests

`StudyKitTest` (scoring, locked answers, navigation, wrong-answer review, save and resume encoding, prompts), plus the strict parser tests in `StudioTest`.
