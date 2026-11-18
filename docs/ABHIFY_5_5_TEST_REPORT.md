# Abhify.AI 5.5 test report (2026-11-18)

**Phone: not tested.** Every result below is automated, on the PC. Phone checks are listed in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md) (tests 53–60).

| Suite | Before (5.4-dev.1) | 5.5 |
|---|---|---|
| Android JVM unit tests (`testDebugUnitTest`) | 388 / 0 failed / 4 skipped | **396 / 0 failed / 4 skipped** |
| Android lint (`lintDebug`) | 0 errors / 124 warnings | **0 errors** / 125 warnings (+1 `UseKtx` style hint in `BrainState.setMinAnswers`) |
| Backend pytest | 407 passed, 2 skipped | **409 passed, 2 skipped** |
| `assembleDebug` | ok | ok |

## New tests
- **`V55bTest.kt` / `ProjectInsightsTest`:**
  - the summary names a source for every line, and says nothing when nothing is recorded;
  - priorities come only from stored dates and wording;
  - related documents need 2 shared terms and skip linked documents;
  - `doc.link` needs approval, `project.summary` is read-only, and nothing is applied before approval.
- **`V55bTest.kt` / `FiftyFiveIntegrationTest`:**
  - the planner tie-breaker;
  - the skill threshold and trend (needs 10 answers) and the revision confirm dialog;
  - the chat context finder: local only, 🧭 sources, memories excluded;
  - the news coverage badge, search grouping, and the voice `project_summary` command.
- **`backend/tests/test_brain.py`:**
  - no invented deadlines, and skills need evidence;
  - the `/plan` and `/skills` endpoints, including `min_answers` validation.

## Existing tests updated (not weakened)
- **`V511Test.projectStatusFoundByNameStaysOnThePhone`:** now matches the new `wsLocal` call, which adds the context finder part. The check that project status stays on the phone and the `wsOnline` check are unchanged.
- **`V50Test.readerStateIsPerStoryAndReadMoreIsStaged`:** now matches the new `ArticleCard(a, coverage[a.id] ?: 0, onClick = …)` call. The behaviour checks are unchanged.
- **`UiPolishTest`:** caught a revision dialog that used `Text`. The code was fixed to use `DialogText`; the test was not changed.

## Not tested
- **Phone:** anything on the phone (no adb over Remote Desktop).
- **Web UI:** the Today and Skills blocks were not opened in a browser. Only their API is covered by tests.
- **Local model:** the quality of local-model answers with the context finder part.
