# Abhify.AI 5.6 recommendations (maintained list)

This list extends [ABHIFY_5_5_RECOMMENDATIONS.md](ABHIFY_5_5_RECOMMENDATIONS.md). Cost: S = under half a day, M = 1–2 days, L = more than that. Privacy impact means what new data could be exposed, not what stays local.

## Implemented in 5.6 (safe, high value)

| # | Change | Benefit | Cost | Privacy | Dependencies | Risk |
|---|---|---|---|---|---|---|
| 1 | Search 2.0 filters on Android and web: type, date, project, sort; news and briefing results; empty states that say what to try next | Finds the right item faster | M | None. Memories are still excluded before filtering when memory is off; nothing is fetched online | none | Low |
| 2 | Smarter duplicate to-dos: normalised text, light stemming, containment check, a "may already be done" flag | Fewer missed duplicates, fewer false ones | S | None. Suggestions only | none | Low |
| 3 | Per-to-do reminders (Android): opt-in, quiet hours, cancel, snooze, completed to-dos never notify | Productivity | M | The lock screen shows only "To-do reminder" | none (existing POST_NOTIFICATIONS and WorkManager) | Low. Times are inexact (WorkManager, no exact-alarm permission) |
| 4 | Context finder also answers "my notes / my project …" questions in plain chat, with a relevance floor (2 shared terms for longer questions) | More useful local answers, less padding with loosely matching records | S | Local prompts only; online prompts unchanged | none | Low |
| 5 | Skill revision plan and learning path, with answer counts | A clear next step that rests on evidence | S | None | none | Low |
| 6 | Provider reliability and speed per session (`RouteStats`), shown in Settings → AI providers | Real numbers for choosing a model or provider | S | Counts and milliseconds only; never text; memory only | none | Low |
| 7 | Retrieval evaluation (`RetrievalEval`): precision@k, recall, and "nothing returned when nothing is relevant" | Catches regressions in context quality | S | Synthetic fixtures only | none | None |
| 8 | Desktop: read-only project status, Agent 2.0 (plan, then approve, then verify, then undo), search filters, briefing kinds, skills path, local evaluation | Desktop/web parity | L | All routes are PC-only (`local_only`) | none | Low to medium (new write tools: approval and undo required) |

## Recommended next (needs a decision or a phone)

| # | Recommendation | Benefit | Cost | Privacy | Dependencies | Risk |
|---|---|---|---|---|---|---|
| R1 | **Commit the work.** About 170 uncommitted paths are on `feature/m2-knowledge-packaging` | Protects months of work | S | None (local git) | none | Very low. **Needs approval** |
| R2 | Back up the debug keystore to a location you choose | Every installed build depends on it | S | Key material: use an encrypted location | none | **Needs approval** |
| R3 | Split the largest files: `AppViewModel.kt` (~1,400 lines), `backend/main.py` (~1,550), `frontend/app.js` (~3,100) | Faster reviews and safer changes | M each | None | none | Medium (refactor). Do it in small steps with the guard tests running |
| R4 | Move from `langgraph.prebuilt.create_react_agent` to `langchain.agents.create_agent` (deprecation warning in every test run) | Stays supported after LangGraph v2 | S–M | None | an existing library | Medium (agent behaviour), so test with Ollama first |
| R5 | Reminder action straight from the notification without opening the app (a non-exported broadcast receiver) | Smoother snooze | S | None | none (manifest component, no permission) | Low |
| R6 | Semantic duplicate check using the phone's existing bge-small embeddings (opt-in, on device) | Catches duplicates worded differently | M | On device only | none (model already shipped) | Low; battery cost on large lists |
| R7 | Keep provider stats across app restarts (a local file, counts only) | Trends over weeks | S | Counts only | none | Low |
| R8 | Accessibility pass: about 14 icons with no description need a check that a label is read next to them; 125 lint warnings are mostly `UseKtx` style hints | TalkBack quality; less lint noise | S–M | None | none | Low |
| R9 | Replace the 9 bare `except Exception:` blocks in the backend with logged, specific handling | Faster diagnosis of failures | S | Logs stay ids-only | none | Low |
| R10 | Official 5.5/5.6 version name and versionCode | Clear versions on the phone | S | None | none | **Needs approval**; do it after the phone checklist |
| R11 | SQLCipher, ML Kit translation, YouTube/edX catalogs, wake word | Features or at-rest encryption | M–L | Varies | **new dependencies or permissions** | **Needs approval** |

## Reviewed and not changed
- **Task Runner and the context finder:** a task works only on the sources the user picked, and its text may go online after consent. Adding personal records there would widen what is sent, so it stays as is.
- **Exact alarms for reminders:** would need `SCHEDULE_EXACT_ALARM`, which is a new permission. Inexact WorkManager timing is enough for to-dos.
