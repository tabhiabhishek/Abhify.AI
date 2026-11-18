# Abhify.AI next build: baseline audit (Phase 0, 2026-11-18)

## 1. Protected baseline (verified before any change)

| Artifact | Size (bytes) | SHA-256 | Version | Signer |
|---|---|---|---|---|
| `build/Abhify-5.4.apk` | 73,904,909 | `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` | 5.4 (5040099) | CN=Android Debug, `1d2d1fdb…fa22fe6246f` |
| `build/Abhify-5.3.apk` | 73,708,493 | `778e5c50…7bd5` | 5.3 | same |
| `build/Abhify-5.1.apk` | 73,552,637 | `659053fc…cd9f` | 5.1 | same |
| `build/Abhify-5.0.apk` | 73,163,006 | `746150e5…5423` | 5.0 | same |

- **Version policy for this build:** the application version is not changed (no approval). The internal build keeps 5.4 / 5040099 and adds a debug `versionNameSuffix` of `-dev.1`. It is written only to `build/internal/` and can never overwrite `build/Abhify-5.4.apk`.
- **Git:** branch `feature/m2-knowledge-packaging`, HEAD `a9325d2`, 149 changed or untracked paths (uncommitted, preserved). No commit, push, reset or stash.

## 2. Test baseline

| Suite | Result |
|---|---|
| Android JVM | 374 passed, 0 failed, 4 skipped |
| Android lint | 0 errors (119 warnings) |
| Backend pytest | 403 passed, 2 skipped at the 5.3 stage (re-run at the start of this build, see the test report) |

## 3. What exists (verified in source, not taken from reports)

| Area | Android (Kotlin) | Desktop (FastAPI + web) |
|---|---|---|
| AI routing | `ai/HybridAi.kt` RoutePolicy (Gemini, Groq, OpenRouter, server, local Qwen via llama.cpp) | Ollama (qwen2.5, llama3.2), LangGraph ReAct agent (`agent.py`) with web_search, memory and knowledge tools |
| Intelligence | `intel/Intelligence.kt` (focus, preferences), `ProjectBrief`, `Suggestions`, `ContinueWorking`, `RouteExplain` | `intelligence.py` (focus, status, suggestions) |
| Memory | `MemoryKit` (relevance, relative cut-off), `MemoryReview`, Knowledge corrections | `memory_service.py` |
| Knowledge / RAG | `docs/RagIndex` (BM25 + optional bge-small hybrid), `KnowledgeChat`, Knowledge Center | Chroma + nomic-embed-text (`knowledge.py`) |
| Search | `workspace/UnifiedSearch` (SQL per type: chats, memory, documents, notes, tasks, knowledge, study, cards, plans, career) | palette (conversations, projects, tasks) |
| Task Runner | `task/TaskRunner.kt` templates (summary, study plan, quiz, briefing, custom), steps, consent, retry | `task_runner.py` (same templates) |
| Projects / tasks | workspaces + notes + tasks tables; `projects.json` (instructions, status) | `projects.py` (+ status) |
| Voice | `voice/` STT + TTS, conversation loop, `AppActions` registry (5.4) | Whisper / browser STT, Web Speech TTS |
| Study / Career | tutor, plans, cards (SRS), quiz, Discover, MCQs, Question Bank, `StudyInsights` | MCQs, Question Bank |
| News | English/Hindi/Both, translation, reader, Listen | news center (language filtering in backend; no web selector yet) |
| Privacy | Conversation data controls, consent, Keystore keys | memory delete, retention |

## 4. Missing for this build

- One shared **context resolver** with freshness labels.
- **Universal search** additions: projects, AI task history, recent searches, semantic document hits.
- **Agent 2.0:** a typed tool registry, plan preview, approval, verification and an audit log.
- **Productivity:** priority, blocked and daily plan (no schema change), weekly review.
- **Personal briefing:** on demand, spoken, with optional scheduled reminders.
- **Skill graph** with an adaptive next step.
- **News:** related stories, source comparison, "Learn this topic".
- **Desktop parity:** five destinations, Study with Discover, News language, briefing, search.

## 5. Platform differences that limit "parity"

- The desktop has no phone-model routing, no ML Kit OCR and no Android TTS voices.
- Browser speech APIs vary.
- Android has no background microphone (no wake word), by design.

## 6. Data migration risks

Android's SQLite stays at v17 and desktop at v5. New data goes into additive JSON files:
- `task_meta.json` (priority, blocked, keyed by task id);
- `agent_runs.json` (audit log);
- preferences for the briefing.

No table is altered and nothing is deleted.
