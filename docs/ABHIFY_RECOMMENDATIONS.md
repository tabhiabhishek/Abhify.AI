# Abhify.AI recommendations register (2026-11-18)

Decisions: **Implemented** · **Deferred** · **Rejected** · **Ask approval**.

| Area | Current state | Problem | Recommendation | Benefit | Alternatives | Dependencies | Cost | Privacy | Device impact | Risk | Decision |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Version control | 149+ uncommitted paths | total loss on disk failure | commit on `feature/m2-knowledge-packaging` (or a verified copy) | protects months of work | zip backup to another drive | none | free | none | none | low | **Ask approval** |
| Signing | debug key on this PC | losing it blocks updates without data loss | back up the keystore offline; create a release key before any wider install | update continuity | Play App Signing | none | free | the key is a secret | none | low | **Ask approval** |
| Context | separate retrieval per feature | duplicated logic, no freshness | `ContextResolver` with freshness and permissions | consistent, honest context | an embedding-only resolver | none | free | local | negligible CPU | low | **Implemented** |
| Agent | fixed Task Runner templates | no approval or verification for data changes | typed `AgentTools` + project review with quote verification, approval, read-back, undo, audit | trustworthy changes | free-form LLM tool calling | none | free | AI sees notes only under consent | one AI call per review | medium | **Implemented** (one workflow) |
| Agent | the LLM could propose any tool | – | keep the model proposing **data**, not tool calls | no tool-injection path | function calling | – | – | – | – | – | **Rejected** (LLM-chosen tools) |
| Productivity | to-dos without priority | no plan | `DailyPlanner` + `task_meta.json` (priority, blocked, estimate), weekly review | daily plan from real records | schema columns | none | free | local | negligible | low | **Implemented** |
| Briefing | none | – | records-only briefing, optional daily reminder (WorkManager, off, quiet hours) | daily overview | AI-written briefing | none | free | local | one job/day if on | low | **Implemented** |
| Skill graph | `StudyInsights` suggestions | no per-skill view | evidence-based `SkillGraph` (no score below 5 answers) + adaptive next step | honest progress | ML knowledge tracing | none | free | local | negligible | low | **Implemented** |
| News | single-list view | no related stories or comparison | `NewsIntel` (related, clusters, learn this topic, labelled AI comparison) | depth without new requests | external clustering API | none | free | local | negligible | low | **Implemented** |
| Discover | Wikibooks API live | blocked (robot policy) | provider search link | no broken source | send contact info in the UA | none | free | **would expose contact** | – | low | **Implemented** (link) |
| Course catalogs | MIT OCW + OpenStax live | limited breadth | YouTube Data API / edX / Coursera catalog | more courses | provider links (current) | API keys / OAuth | free quota | queries to Google/edX | network | medium | **Ask approval** |
| Translation | AI translation via routing | quality on Qwen 1.5B unknown | ML Kit on-device translation | offline, consistent | Argos (no Android lib), keep AI | ML Kit (~30 MB/lang, sends usage metrics) | free | metrics to Google | storage | medium | **Ask approval** |
| Wake word | tap-to-talk | hands-free missing | Porcupine / openWakeWord with a foreground service | hands-free | none reliable | new library + permission + notification | Porcupine: licence; openWakeWord: free | always-on mic | battery | high | **Deferred** (separate approval) |
| Android DB encryption | phone encryption only | a rooted or stolen device can read the DB | SQLCipher | defence in depth | Jetpack Security file encryption | SQLCipher (~4 MB) | free (BSD) | better | small CPU | medium (migration) | **Ask approval** |
| Desktop | no voice companion or agent | parity | port VoiceState/AppActions and AgentTools to web/backend | parity | – | none | free | local | – | medium | **Deferred** (next build) |
| Code health | 1.4k-line ViewModel, 2.9k-line app.js | maintainability | continue extracting state holders; split app.js into modules | fewer regressions | rewrite | none | free | – | – | low | **Implemented** partly / **Deferred** |
| Desktop agent | `create_react_agent` deprecated | future break | migrate to `langchain.agents.create_agent` | stays supported | pin versions | none | free | – | – | medium | **Deferred** (with regression test) |
