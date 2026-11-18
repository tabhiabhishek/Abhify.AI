# Task Runner document workflows: root causes and fixes (2026-11-09)

Covers Document summary, Document to study plan and Quiz from document on desktop/web (backend `task_runner.py`) and Android (`task/TaskRunner.kt`, `ui/TaskState.kt`). News briefing is unchanged and still passes.

## Why News briefing worked and the document workflows didn't
| Difference | News briefing | Document workflows |
|---|---|---|
| Source | fetched by the app | the user's document |
| AI steps | one, free text | 2–3, and the quiz step needs strict JSON |
| Android consent | never (news is not sensitive) | "Send … online?" on **every** step |

Each difference carried a defect.

## Root causes
1. **Quiz (both platforms):**
   - The parser needed perfect JSON. One malformed field rejected the whole quiz.
   - Reproduced live on llama3.2: five good questions, but `"explanation": The Calvin cycle …` was written without quotes.
   - Phone models (0.5B–3B, 1,024 output tokens) produce strict JSON even less reliably.
2. **Android consent per step:** with an online provider active, a 3-step task asked "Send the document online?" three times.
   - Cancel stopped the task.
   - "This phone" without a downloaded model failed.
3. **Android cancel crash:** cancelling called the AI engine before its native library was loaded, which crashed the app (`UnsatisfiedLinkError`). The New private chat crash has the same cause.
4. **Android document lookup:** documents inside a workspace (project) were neither listed nor found.
5. **Study plan quality:** a 5-line day list, with no objectives, sessions, revision, practice or document title.
6. **Short sources:** a few words still produced a 5-question quiz or a study plan, inviting invented content.

## Fixes
- **Tolerant quiz parser (desktop and Android):**
  - It accepts strict JSON (an array or `{"questions": […]}`).
  - Otherwise it reads each `{…}` item field by field, accepting unquoted strings, trailing commas, letter or string answers, an answer given as the option's text, and cut-off output.
  - Android additionally reads the line format `Q: / A)–D) / Answer: / Why: / Quote:`, through the existing Study `Studio.parseMcqs`.
  - One bad question loses only itself. Each supporting quote is still checked against the source and flagged when not found.
- **Desktop schema-constrained output:** the quiz step uses Ollama's `format` JSON schema on the **same** chosen model (`model_copy`). The model and its settings are unchanged.
- **Phone prompts:** the phone uses the line format and at most 5 questions. Online and desktop use JSON, with up to 10 questions.
- **Quiz settings:** 3, 5 or 10 questions; difficulty mixed, easy or hard. The question count never exceeds what the source supports (about 1 per 150 characters).
- **Study plan:** "Learning objectives, Topic order, Study sessions, Revision, Practice tasks, Progress checklist", using the document's title. The phone gets a shorter variant.
- **Minimum source length:** study plan needs 40 characters, quiz 80. Below that, the task fails clearly before any model call. Summary is unchanged (existing behaviour).
- **Android, consent once per run:** `vm.aiTask(..., decided, onDecided)` reuses the first answer for the later steps; a new run asks again.
- **Android, document lookup:** documents are found and listed wherever they live (`docById`, `allDocs`; read-only queries).
- **Busy guard:** a busy AI now reports "The AI is busy" instead of leaving the caller waiting.
- **Results:**
  - Web: Export (Markdown download), a running quiz score, and answers recorded in the existing quiz progress.
  - Android: Share (Markdown) and a running score.
- **Projects:** a project's documents can be the source. See [ABHIFY_PROJECT_WORKSPACE.md](ABHIFY_PROJECT_WORKSPACE.md).
- **Logging:** task id, step, duration and character counts only; never document text.

## Results
**Live, desktop.** Real Ollama 0.40.13, isolated data folder; a multi-page PDF and a Markdown note uploaded through the app.

| | Before | After (2 full runs) |
|---|---|---|
| Runs completed | 11 of 12 (llama3.2 quiz failed) | **24 of 24** |
| Quiz quotes found verbatim in the document | – | **39 of 40** (the other is flagged in the UI) |
| Study plans with all 6 sections | – | **8 of 8** |
| Browser Task Runner suite (`task_e2e`) | 11/11 | **11/11** |
| Project quiz in the browser, answered to a score | – | **passed** |

**Tests:**
- Backend `test_stability_milestone.py`: parser on the real failing output, 5 model variants, rejection of unusable output, prompts and settings, schema use, the end-to-end quiz, the length guard.
- Android `V49Test.kt` (`TaskRunnerFixTest`): the same parser cases, the line format, phone versus online prompts, the length guard, storage compatibility with tasks saved by 4.6.1, consent once per run.

**One existing test changed.** `V47Test`'s fake model now keys on the *online* prompt (which still asks for JSON), because the phone prompt legitimately moved to the line format. Its assertions are unchanged.

## Limitations
- Android document tasks are verified by unit tests and the build only. No phone was connected.
- Answer correctness still depends on the model. The quote check flags unsupported answers but can't prove an answer right.
