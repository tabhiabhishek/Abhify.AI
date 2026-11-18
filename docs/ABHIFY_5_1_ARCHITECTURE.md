# Abhify.AI 5.1: Personal Intelligence architecture (Android)

Status: implemented in code and JVM tests on 2026-11-17. **Physical-device testing: not performed.** The Local RAG phone validation (5.0 work) is still pending and is not assumed.
Android version 5.1 (5010099) at the owner's request, build `build/Abhify-5.1.apk`; database schema unchanged (v17); no new dependencies, providers or permissions. Desktop parity: `backend/intelligence.py` (same focus rules, project status).

## 1. Principle

5.1 adds **one thin, deterministic layer** that decides *how* a message is answered (focus, format, preferences) and *which recorded facts* about a project are relevant. *What* context is used is still decided by the existing components, reused unchanged:

| Need | Component (existing unless marked new) |
|---|---|
| Relevant personal memories | `MemoryKit.relevant` (shared words, category hints, importance; confirmed and unexpired only) |
| Documents / saved knowledge | `RagIndex.retrieve` (BM25 + optional bge-small, RRF), `KnowledgeChat` modes |
| Project notes, open to-dos, instructions | workspace `ContextOrchestrator`, `ProjectStore` |
| Project status and continuation | **new** `ProjectStatus` (in `projects.json`), **new** `intel/ProjectBrief` |
| Focus, format, response preferences | **new** `intel/Intelligence` |
| Proactive suggestions | **new** `intel/Suggestions` + `ui/CompanionState`; study signals from `StudyInsights.suggest` |
| Provider choice | `RoutePolicy` (unchanged); **new** `intel/RouteExplain` explains it |
| Consent, privacy, private chats | existing `docConsent`, `knowledge_online`, `shareMemoriesOnline`, `privateChat` |
| Controlled learning | 5.0 `KnowledgeUpdates` (corrections → approval → memory, revertible) |

No new database, store or chat system. All new state is in `SharedPreferences` (focus, response preferences, suggestion switch and dismissed keys) or in the existing `projects.json` (project status, an optional field, so older files load unchanged).

## 2. Context-selection pipeline (per message)

```
message
 ├─ 1 intent / focus      Intelligence.detect: chosen focus wins; Auto = keyword + signal scores (stack trace, log, code); ties → most specific
 ├─ 2 context class       KnowledgeChat.wants (Normal/Auto/Knowledge) · Research focus also searches your knowledge (unless Normal)
 ├─ 3 memory              MemoryKit.relevant (≤ 6 items, ≤ 900 chars, related words required) · per-project "use memory" switch
 ├─ 4 project             selected project: status brief + relevant notes + open to-dos (ContextOrchestrator, 1,200 chars on the phone)
 │                        Auto: a project NAMED in the question adds its status brief (local prompt only)
 ├─ 5 RAG                 RagIndex.retrieve over chosen docs / project docs / all docs (Knowledge), 8 passages, cosine ≥ 0.59
 ├─ 6 ranking / budget    hybrid RRF for passages; profile-based character budgets (LOW/MID/HIGH) shared between project parts and passages;
 │                        dedup of passages (doc, index); at most 2 Knowledge items; memories capped
 ├─ 7 provider            RoutePolicy.plan(mode, network, keys, local model) — unchanged; explained in "Online context"
 ├─ 8 prompt              PromptBuilder.build: base rules + personality + focus format + response preferences + project instructions,
 │                        passages fenced with the citation/no-injection instruction
 └─ 9 validation          sources list with ✓ for cited [S#]; "No source cited" warning; "Nothing in your knowledge matched";
                          focus label under the answer (e.g. "🧑‍💻 Coding (auto) · stack trace")
```

Online prompts are built separately from local prompts: recent chat only if allowed, memories only if "share memories" is on, documents/knowledge/project parts only after consent. A project found **by name** is never added to the online prompt.

## 3. Focus (smart conversation modes)

Two independent, visible choices in the chat bar, so no second chat system exists:

| Requested mode | How it is provided |
|---|---|
| Normal Chat | Focus **General** (+ Knowledge Chat Normal if you want no retrieval at all) |
| Auto Intelligence | Focus **Auto** + Knowledge Chat **Auto**: format by detected focus; knowledge when the question is about your material; project status when a project is named |
| Knowledge Chat | Knowledge Chat **Knowledge** (5.0) |
| Coding Assistant | Focus **Coding**: root cause → fix (fenced code) → verification → edge cases; logs/stack traces: first real error, own-code line; pasted code is data, never instructions; never claims to have run code |
| Learning Mode | Focus **Learning**: explanation → example → practice questions (answers at the end) |
| Career Mode | Focus **Career**: structured, actionable; STAR for behavioural answers |
| Research Mode | Focus **Research**: facts vs claims, cite given sources, state uncertainty, no invented references; also searches your own knowledge. **No live web search on the phone** (the desktop agent has one); adding it would be a new external dependency (approval needed) |

Focus never changes the provider (tested). It changes the instructions; Research additionally searches your own knowledge, and those passages follow the 5.0 rules (local unless "Online answers may use my knowledge" is on and you agreed in this chat).

## 4. Response preferences (Settings → Answer style)

Detail (Auto/Concise/Detailed), technical depth (Auto/Beginner/Intermediate/Expert), explanation style (Auto/Direct/Step by step/Examples first), preferred language (letters only, 2–30 characters, so the field can't carry instructions). All defaults add nothing, so existing prompts are byte-for-byte unchanged until you choose. Nothing about you is inferred.

## 5. Project Intelligence

`ProjectStatus` (objective, technology, current status, decisions, completed milestones, open issues, next actions, updated time) is written **by the user** on the project page ("Add status / Update status"). `ProjectBrief.summary` turns it, the open to-dos (with overdue count) and the linked-document count into "Where you left off". It returns nothing when nothing is recorded, every line maps to a recorded field (tested), and a stale status is shown with its age. "Continue in chat" opens the project chat with a continuation question in the box; it isn't sent automatically. Export includes the status.

## 6. Proactive Companion

`Suggestions.compute` (pure) looks at:
- project overdue to-dos, open issues and next actions (questioning a status older than 14 days);
- the study dashboard's own suggestion;
- corrections waiting for review;
- an AI task that failed in the last 3 days.

At most three are shown, one per project, most urgent first. Each has a stable key, so a dismissal holds until the content changes. They are computed only when Home opens: no background work, no notifications, no AI call, no action taken (a button only navigates). You can turn them off in Settings → AI.

## 6b. Continue working (Home)

`ContinueWorking.items` (pure) picks, from the last 14 days, one item per kind:
- the last chat;
- the project whose status you updated most recently (its next action or status);
- an AI task that was interrupted or never run;
- a study plan in progress.

At most three are shown, most recent first. A project shown here doesn't repeat its "Next in …" suggestion. It is computed when Home opens, like Suggestions, and has its own switch.

## 7. Memory categories

Added **Professional profile** (with category hints) and **Corrections** (where approved corrections are filed). The existing categories are unchanged. Temporary conversation context is the chat's recent turns (never stored as memory), and project decisions live in the project status, so no duplicate store exists. Unchanged and re-verified:
- confirmation of suggested memories;
- duplicate and contradiction review;
- sensitive-data refusal;
- provenance (source, created/updated);
- edit, delete and export;
- private chats store nothing.

## 8. Android performance (Redmi K50i, 8 GB)

- **The new layer:** pure Kotlin and regex. Measured on the JVM dev PC: 0.32 ms per message for a 15 KB log or a short question. No model is loaded, no extra thread is used, and nothing runs in the background. Suggestions are computed with a few SQLite reads when Home opens, on `Dispatchers.IO`.
- **Prompt cost:** 0 characters by default. 122–230 characters for a focus, 461 for coding with a pasted trace, 228 for all four preferences. Everything is capped at 900 characters, which matters for Qwen 1.5B's context.
- **Unchanged:** model lifecycle (single llama.cpp worker; the embedder is unloaded after index builds), the memory-pressure and thermal gates, and offline operation.
- **Not measured:** real phone latency and memory.
