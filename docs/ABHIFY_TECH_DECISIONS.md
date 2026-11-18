# Abhify.AI technical decisions (next build, 2026-11-18)

| # | Decision | Why | Alternatives considered |
|---|---|---|---|
| D1 | **No new dependencies** | everything needed exists (Compose, WorkManager, llama.cpp, httpx, FastAPI) | – |
| D2 | **No schema change**; new data in JSON side files (`task_meta.json`, `agent_runs.json`) and preferences | additive, reversible, no migration risk | new columns (needs approval and migration) |
| D3 | **Deterministic reasoning first** (planner, briefing, skill graph, action matching, quote verification); the model only for language | no invented facts, testable, fast on the phone | an LLM for every step (slower, can hallucinate) |
| D4 | The agent's model output is **data** (proposed tasks with quotes), never tool calls | removes the tool-injection path | function calling |
| D5 | Agent tools validated **in code**: unknown and sensitive tools are refused; reversible tools need approval; results read back | trust and verification | trust the model's success claims |
| D6 | Context freshness labels: recent < 7 days, this month < 30 days, older | avoids presenting old records as current | none |
| D7 | Skill level shown only with ≥ 5 answers | no fabricated scores | Bayesian estimate (overclaims with little data) |
| D8 | Briefing reminder: WorkManager once a day, off by default, notification without content | no background AI; privacy | foreground service (heavier) |
| D9 | Wikibooks as a search link, not an API | Wikimedia requires contact details; sending the user's would breach privacy | a fake contact (dishonest) |
| D10 | Internal build: `versionNameSuffix "-dev.1"`, versionCode unchanged, output only in `build/internal/` | the version may not change without approval; 5.4 protected | a new versionCode (needs approval) |
| D11 | Desktop parity scoped to the five destinations, Discover, News language, briefing and search | biggest user value; voice and agent on desktop are next | everything at once (risky) |
| D12 | Universal search on Android keeps SQL search and adds projects and AI tasks in memory | reuses the tested search; no index rebuild | an FTS index over everything (schema change) |
