# Abhify.AI next build: architecture

## Shared intelligence path (Android)

```
request (typed, spoken, or a button)
  → ContextResolver (intel/ContextResolver.kt): picks only relevant, permitted records from projects, tasks, notes,
      memories, documents (titles), saved knowledge/news, study progress, AI task history; scores by shared terms + recency;
      labels freshness ("recent", "this month", "older — check it's current"); reports sources with no data
  → reasoning: deterministic where possible (planner, briefing, skill graph, action matching); the AI model only where
      language is needed (project review proposals, summaries) through the existing routing/consent
  → recommendation (DailyPlanner, Briefing, SkillGraph, Suggestions) with reasons and sources
  → approval: AgentTools permission classes (READ / REVERSIBLE / SENSITIVE); reversible = user confirms; sensitive = never by agent
  → registered tool execution (AgentTools handlers; VoiceState for voice)
  → verification (read back what was written) → report (+ audit record in agent_runs.json)
```

| Component | File | Notes |
|---|---|---|
| Context resolver | `intel/ContextResolver.kt` | pure, tested; used by briefing, agent, search ranking |
| Agent tool registry | `agent/AgentTools.kt` | typed specs (id, input fields, validation, kind, confirmation, result) |
| Agent workflows | `agent/ProjectReview.kt` (pure) + `ui/AgentState.kt` | plan → steps → proposals with quote verification → approval → create → verify → report; cancel; undo; audit |
| Productivity | `intel/DailyPlanner.kt` (pure), `intel/TaskMetaStore` | priority/blocked side file; plan from real tasks and valid due dates only |
| Briefing | `intel/Briefing.kt` (pure), `ui/BriefingState.kt`, `ui/BriefingWorker.kt` | on demand; optional daily reminder (WorkManager, off by default, quiet hours, no content in the notification) |
| Skill graph | `learn/SkillGraph.kt` (pure) | evidence-based; no score below the evidence threshold |
| News intelligence | `news/NewsIntel.kt` (pure) | related stories, source clusters for comparison, topic → Discover |
| Universal search | `ui/V40State.kt` (WorkspaceHub) + `workspace/UnifiedSearch.kt` | + projects, AI tasks, recent searches |
| Voice | `voice/AppActions.kt` + `ui/VoiceState.kt` | + plan my day, brief me, weekly review, review project (agent) |

## Desktop/Web

- **Backend:** `intelligence.py` gains a briefing, a course discovery port and a universal search over the local stores. `news_center.py` already filters by language.
- **Web:** five destinations (Home · Assistant · Study · News · More). Study holds Discover, Question Bank and MCQs. News gets the language selector. Home gets the briefing, and search covers more sources.

## Data

- No schema change.
- New: additive JSON side files in app-private storage (Android) and the data folder (desktop), plus preferences.
- All local. No sync is added.
