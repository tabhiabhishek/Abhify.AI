# Career MCQ practice (Android 5.0, desktop/web 2.0.0-dev.8)

Personalised multiple-choice practice from your resume, its skills or projects, a topic, a document, or a mix.

## Where
- **Android:** Career → **MCQs** (`ui/McqState.kt`, `ui/CareerPracticeScreens.kt`, engine `career/McqPractice.kt`).
- **Desktop/web:** the **MCQs** tab. "Learning topics" uses the existing built-in categories (`/generate-mcq`). "My resume or document", "Any topic" and "Resume + topic" use `POST /career/mcq` (engine `backend/career_mcq.py`).

## Modes
| Mode | Source | Android | Web |
|---|---|---|---|
| Resume | the selected resume's text | ✅ | ✅ (a resume in the knowledge folder) |
| Skills | skills read from the resume by rules (`Resume.parse`) | ✅ | – (use Resume + topic) |
| Projects | lines of the resume's Projects section | ✅ | – |
| Topic | the topic you type | ✅ | ✅ |
| Document | any document in the library or knowledge folder | ✅ | ✅ |
| Mixed | topic + resume | ✅ | ✅ |

Settings: level (fresher to senior), difficulty, 3 or 5 questions per batch, and the model or provider.
- **Android:** your AI mode (this phone or your provider) through the model chip. A resume goes online only after you agree.
- **Desktop:** the chat's chosen model.
- **Model choice:** never switched automatically.

## "Unlimited" practice, done safely
**Batches:** questions come in small batches on request: Start, **Generate more**, and **Refresh unanswered**. Refresh replaces only questions you haven't answered; answered ones and all progress stay.

**Duplicate filtering** checks every question against all questions shown before (stored):
- the same normalized text;
- ≥ 80% shared content words, lightly stemmed;
- or one question being the other plus a few words.

No new index or vector database is used.

**Varied angles:** each batch takes a new angle on the source; the round counter is stored per resume or topic.
- **Two of five angles ask about your own lines.** These carry a supporting quote, which is checked against your resume.
- **Three of five ask how a technology named in your resume works.** They cover one technology per batch, in turn: technologies come from the Skills section and mixed-case names (FastAPI, PyTorch, YOLO); a name or job title is never treated as one.

**Why angles were added (measured live):**
- **Before:** without them, qwen2.5 repeated 4 of 5 questions in the second batch from the same resume, and yields fell to 1, 0, 0, 0 new questions.
- **After:** 4, 2, 4, 3, on a very short resume with ~50 questions already in its history.

**Thin batches:** when a batch is thin, the app says so and suggests "Resume + topic" or "Any topic".

## Validation (before anything is shown)
**Kept** only with:
- a question;
- exactly 4 distinct options;
- an answer that maps to one of them;
- an explanation;
- no duplicate;
- relevance: topic words, the selected skills, or the batch's technology.

**Malformed questions** are dropped one by one, never the whole batch, and every dropped question is counted with its reason.

**Parsing:** tolerant (the stability milestone's parser), including JSON with unquoted strings and the phone's line format.

**Answers:** the model's answer is never changed. The desktop shuffles options and keeps the answer with its text.

## Answers and honesty
- **Feedback:** a tap is the answer.
  - **Correct:** "Correct", plus the explanation.
  - **Wrong:** "Incorrect: you chose B. The answer is D) …", plus the explanation (it covers why the other options are wrong when the model can say so).
- **Answers stay hidden before you choose,** unless you turn on **Review mode**.
- **Every question is labelled:**
  - "AI-generated answer · not independently verified: check it if it matters", or
  - "AI-generated answer · its supporting quote is in your document (the answer itself isn't independently verified)", shown only when the quote really is in the resume or document.
- **Nothing is ever called "verified".**

## Progress (no database change)
- **Storage:**
  - Android: `filesDir/career_mcq.json`, plus round counters in preferences;
  - desktop: `<data>/data/career_mcq.json`.
- **What's tracked:** attempts and the questions already shown.
- **Shown as:** this session, this resume or topic, all practice, and by topic. Accuracy only when there are answers (never a made-up percentage).
- **Refresh never resets progress.**

## Measured (desktop, qwen2.5 7B, RTX A2000)
| | Seconds |
|---|---|
| First batch of 5 (fresh resume) | 27.2 |
| Batch in the browser (3 requested) | 19.5 |
| Refresh unanswered (3) | 10.5 |
| Batch when the first try was mostly repeats (2 model calls) | 26.8–40.0 |

Android generation time was not measured (no device here).
