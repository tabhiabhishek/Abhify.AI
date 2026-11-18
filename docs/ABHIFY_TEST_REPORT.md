# Abhify.AI test report (next build, 2026-11-18)

## Automated tests executed on the dev PC

| Suite | Baseline (start of build) | Result |
|---|---|---|
| Android JVM unit tests | 374 / 0 failed / 4 skipped | **388 passed, 0 failed, 4 skipped** (14 new in `V55Test.kt`) |
| Android lint | 0 errors / 119 warnings | **0 errors** / 124 warnings |
| Android build incl. native (`assembleDebug`) | OK | **OK**: `build/internal/Abhify-5.4-dev.1.apk` |
| Backend pytest | 403 passed / 2 skipped | **407 passed, 2 skipped** (4 new in `test_brain.py`; skips: dev.2-installer test after the approved cleanup, opt-in live Ollama test) |
| Web, real browser (headless Edge, isolated data folder, live internet) | – | **11/11**: five destinations in order; Home briefing from records; Study tab; Discover with live MIT OCW results marked Free; provider links; Question Bank under Study; More groups; News language saved and marked; palette universal search; no page errors |

No existing test was deleted or weakened.

## What the new tests cover

| Area | Covered |
|---|---|
| Context resolver | relevance, freshness labels, memory permission, empty sources, no invention, cited block |
| Planner | overdue and due-today only from stored dates; blocked and priority; time budget; recorded next actions; meta persistence; weekly-review query |
| Briefing | records only, sources on every item, skip, empty state, spoken text, weekly kind; reminder off by default, no content, quiet hours, scheduling maths |
| Skill graph | no score below 5 answers; quiz + MCQ evidence combined; weak prerequisite of a goal first; none without records |
| News intelligence | related, one-per-publisher clusters, learn topic, non-contradiction wording of the comparison |
| Agent | unknown, sensitive and unapproved tools refused; input validation (types, unknown fields); audit persistence; rule-based extraction; quote verification (verified vs suggestion); duplicates; nothing written before approval; read-back verification; undo and cancel present |
| Voice | new commands (plan, briefing, weekly review, review project, ask knowledge) |
| Search | memory permission; projects and AI tasks; recent searches |
| Desktop | discovery parsers on the same real responses as Android; paid / unknown cost never shown as free; briefing records-only; search memory permission; endpoints |

## Manual and physical-device checks

- **Physical device: not performed.** The phone isn't reachable from this PC (Remote Desktop; different network).
- The owner's checklist covers it: `ABHIFY_RAG_DEVICE_VALIDATION.md` tests 1–52 (45–52 are new for this build).
- **Live checks done on the PC:**
  - MIT Learn and OpenStax APIs answered.
  - Wikibooks returned 403 ("robot policy"). It is now a provider link (finding F4).
