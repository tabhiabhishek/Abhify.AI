# Abhify.AI 5.5 architecture changes

This file describes what 5.5 adds on top of [ABHIFY_ARCHITECTURE.md](ABHIFY_ARCHITECTURE.md). There is **no database change** (ChatDb stays v17, desktop stays v5), **no new permission** and **no new dependency**.

## 1. Context finder in chat (Android)
`AppViewModel.send`:
- **When it runs:** only for knowledge mode or when a project is named. It runs `brain.resolve(question)`, keeps sources in `CHAT_CONTEXT_SOURCES` (project, task, note, knowledge, news, AI task) and takes the top 5.
- **The prompt part:** the results become a "your records" part (`ContextResolver.block`, 900 characters) that goes **only** into `wsLocal`, the local-model prompt. The local budget rises to 1800 characters when the part is present.
- **Online prompts:** `wsOnline` is unchanged, so online prompts never receive these records.
- **Sources:** local answers list each source as `🧭 <source>: <title> (<freshness>)`.

## 2. Agent workflows (Android)
- **`agent/ProjectInsights.kt`:** pure functions.
  - `summary` (Recorded / Work / Blockers / Possible duplicates / Documents, each line with its source);
  - `blockers`;
  - `duplicates` (word overlap ≥ 70 %);
  - `prioritize` (overdue or due within 2 days → High; "waiting for" wording → Blocked);
  - `relatedDocs` (at least 2 shared terms, skips linked docs).
- **`AgentTools` registry:** adds `project.summary` (READ), `docs.related` (READ) and `doc.link` (REVERSIBLE, needs approval).
- **`AgentState.run(id, Workflow)`:**
  - The workflows are REVIEW, STATUS, PRIORITIZE and DOCS.
  - `run` only reads.
  - `approveItems()` applies the checked items through `call(tool, approved = true)` and then reads each one back.
  - `undo()` reverts these actions.
  - Each run is written to `agent_runs.json`.
- **Entry points:** the project page buttons, and voice ("summarise my project", "what's blocking my project").

## 3. Planner, skills, news, search
- **Planner:** `DailyPlanner.Todo.projectActiveAt` breaks ties after rank and due date, so to-dos from recently updated projects come first.
- **Skills:** `SkillGraph.Evidence.recent`, then `Node.trend`; `build(minAnswers)` uses the threshold you chose (`skill_min_answers` pref). `BrainState.addRevisionTodo` validates with `AgentTools` before it adds the to-do, then checks the to-do exists.
- **News:** `NewsIntel.clusters` counts how many other sources cover each story; the card shows "Also reported by N other source(s)".
- **Search:** results are sorted by type, with a header and count per group.

## 4. Desktop/web
- `brain.plan(projects)` and `GET /plan`: open issues, then next actions, with the most recently updated projects first. The UI says "desktop projects have no due dates".
- `brain.skills(attempts, min_answers)` and `GET /skills` (3–20): level, accuracy, trend and the next topic.
- Both routes are `local_only` and are on the service worker's never-cache list. The web shows Today in the Home briefing and Skills in Study.

## 5. Performance and security review
- **Performance:**
  - The context finder runs on `Dispatchers.IO` inside `runCatching`, so a failure falls back to the old behaviour.
  - The workflows work on one project's data. News clustering runs once per list (`remember`).
- **Security and privacy:**
  - Nothing new leaves the device.
  - Agent writes need approval and are read back afterwards.
  - Sensitive tools stay blocked for the agent.
  - The new desktop routes are PC-only (`local_only`).
  - Voice matching uses only the user's own words, and the summary command only reads.
