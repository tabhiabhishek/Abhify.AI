# Career, refresh and in-app News reading: test report (2026-11-10)

| Suite | Kind | Before | After |
|---|---|---|---|
| Backend `pytest backend/tests` | unit + integration (fake model and embeddings) | 368 passed, 1 skipped | **390 passed, 1 skipped** (+22: `test_career_milestone.py` 9, `test_news_reading.py` 14 including parametrised cases, minus none) |
| Android `testDebugUnitTest` | JVM unit + source contracts | 294 / 0 failed / 4 skipped | **309 / 0 failed / 4 skipped** (`V50Test.kt`: navigation 1, MCQ 8, generated questions 1, news reading 4, refresh 1) |
| Android lint | static | 0 errors | **0 errors** |
| Android build | `assembleDebug`, `assembleDebugAndroidTest` | – | **successful** (5.0 / 5000099) |
| Browser `career_e2e.mjs` | real Edge, live Ollama and live BBC feeds | – | **18/18** |
| Browser `stability_e2e.mjs` | real Edge, live | 25/25 | **25/25** |
| Browser `web_e2e.mjs` | real Edge, live | 43/43 | **43/43** (one check updated, see below) |
| Browser `launcher_e2e.mjs` / `task_e2e.mjs` | real Edge (live) | 17/17 · 11/11 | **17/17 · 11/11** |
| Live MCQ batches (`mcq_batches.py`) | live qwen2.5 | – | measured (see MCQ document) |
| Physical Android device | – | – | **not run (no device connected)** |

## Changed existing checks (intended behaviour changes, no assertion weakened)
- **`web_e2e` `news_center_live`:** it required each card's "Read more" to be a direct link to the publisher. At your request, cards now open the in-app reader. The check now requires the card's "Read in app" action, no direct external link on the card, and a valid original URL kept for the reader.
- **A test-only reword:** `V451Test` still finds the Dashboard tile "Match a job". I restored the original label instead of changing the test.

## Live failures found and fixed during testing
1. **Pull-to-refresh didn't fire on the web:** the browser sent `touchcancel` when it took over the gesture. Fixed with a non-passive `touchmove` at the top.
2. **Resume MCQs repeated themselves:** fixed with angle rotation, one technology per general batch, a 0.8 sampling temperature (as the existing MCQ generator uses) and containment deduplication. Yield went from 1, 0, 0, 0 to 4, 2, 4, 3 new questions per batch.
3. **The angle didn't advance after a low-yield batch:** fixed with a round counter per resume or topic.
4. **Technology terms included a surname and a degree:** fixed by using the Skills section and mixed-case names, with exclusions.
5. **The rotation revisited the same three technologies:** caught by the new rotation test and fixed.
6. **The README route guard:** the new reading route was documented in a form the guard didn't recognise; reworded.
7. **An action-sheet glyph had no vector icon:** caught by an existing test; an existing glyph is used.

## Not tested
- Android gestures, News reading, MCQs and Question Bank generation on a phone.
- Android generation times.
- Background playback.
