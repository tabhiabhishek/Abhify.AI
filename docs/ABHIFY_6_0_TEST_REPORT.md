# Abhify.AI 6.0 test report (2026-11-18)

This report also covers the 5.6 rounds; [ABHIFY_5_6_TEST_REPORT.md](ABHIFY_5_6_TEST_REPORT.md) points here.

| Kind | Result | What |
|---|---|---|
| **Automated, Android JVM** (`testDebugUnitTest`) | **426 run / 0 failed / 4 skipped** (5.5-dev.1: 396) | New: `V56Test` (search filters, duplicates, reminders, context finder, eval, skills, route stats), `NewsImageTest` (reproduces the old image bug, then the fix), `V56bTest` (companion, speech stream and interruption, tokens, provider cooldown, Hindi/Hinglish commands), `V60Test` (brain modes, intents, fresh information, route advice, privacy wiring) |
| **Lint** (`lintDebug`) | **0 errors** / 125 warnings (same count as 5.5; mostly `UseKtx` style hints) | – |
| **Automated, backend** (`pytest`) | **443 passed / 2 skipped** (5.5: 409 + 2) | New: `test_desktop_56.py` (22: grouped search, overview, agent plan/apply/undo, duplicates, briefings, skills, eval, `local_only`), `test_companion_ai.py` (6), `test_news_images_web.py` (5, JS harness), a news merge image test |
| **Browser** (headless Chrome, isolated backend and temporary data, deleted afterwards) | 15 screens × 3 widths before and after; no overflow after; the agent approve → verify → undo round trip seen on screen | See [ABHIFY_5_6_UI_AUDIT.md](ABHIFY_5_6_UI_AUDIT.md); 8 pairs kept in `docs/screenshots/ui56/` |
| **Emulator** | **Not run.** No Android system image is installed on this PC | – |
| **Physical device** | **Not run** (no adb over Remote Desktop) | Manual checklist tests 1–80 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md) |

## Existing tests changed (none weakened)
- **5.5 and 5.6 source-contract tests** (written in this session) now point at the 6.0 wiring: `val personal = if (!brainPlan.contextFinder) …`, and `Companion.turn(…, win)`. The behaviour they protected is now unit-tested directly in `BrainTest`.
- **`V50Test` / `V511Test` string updates** (5.5): the same checks, adjusted to the new call shapes.
- **Safety tests the 6.0 code had to respect, unchanged:**
  - `AppActionsTest` (one caller for action matching): the chat no longer matches actions.
  - `RoutingTransparencyTest` (routing call unchanged): mode tuning moved to `tuneNext`.
  - `V44FeaturesTest` (the Creativity setting stays the default temperature).
  - `UnifiedWorkspaceTest` (private documents online only after consent): the consent expression is kept exactly; web evidence is added after it.
  - `ContrastTest`: it caught a too-faint hairline token, so the **token** was fixed.

## Not tested (manual or pending)
- **Answer quality** of Qwen, Ollama or online providers on real questions (needs the models running).
- **Voice on a phone:** streaming, interruption, Hindi, audio focus.
- **Reminders firing,** and the Android visual look.
- **Research** against live Google News from the phone.
- **Desktop live chat** with the new continuity (unit and wiring tests only).
