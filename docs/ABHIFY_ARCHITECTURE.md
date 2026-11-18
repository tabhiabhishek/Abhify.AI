# Abhify.AI architecture (current, 2026-11-18)

This document is the entry point. Details live in:
- [PROJECT_ARCHITECTURE.md](PROJECT_ARCHITECTURE.md) — the monorepo layout;
- [ABHIFY_NEXT_BUILD_ARCHITECTURE.md](ABHIFY_NEXT_BUILD_ARCHITECTURE.md) — the intelligence path;
- [ABHIFY_LOCAL_RAG.md](ABHIFY_LOCAL_RAG.md);
- [ABHIFY_PERSONAL_VOICE_ASSISTANT.md](ABHIFY_PERSONAL_VOICE_ASSISTANT.md);
- [ABHIFY_NEURAL_DESIGN_SYSTEM.md](ABHIFY_NEURAL_DESIGN_SYSTEM.md).

```
                         ┌───────────── Android (Kotlin/Compose, one APK) ─────────────┐
 request (type/voice) ─► │ Intelligence.detect (focus) · AppActions (voice commands, from the user's words only) │
                         │ ContextResolver (relevant, permitted, fresh records) ── BrainState (projects, to-dos, notes, │
                         │   memories, documents, knowledge/news, study, AI tasks) → DailyPlanner · Briefing · SkillGraph │
                         │ Retrieval: MemoryKit (relevance, cut-off) · RagIndex (BM25 + bge-small hybrid) · ProjectBrief │
                         │ Reasoning by code where possible; AI only for language (chat, summaries, project review)    │
                         │ Routing: RoutePolicy (phone Qwen via llama.cpp · Gemini/Groq/OpenRouter with your keys ·    │
                         │   Abhify server) + consent + RouteExplain                                                  │
                         │ Actions: AgentTools (typed, validated, READ/REVERSIBLE/SENSITIVE) → approval → execute →    │
                         │   verify → audit (agent_runs.json) · VoiceState executor                                   │
                         │ Storage: SQLite v17 (app-private) + JSON side files; Keystore keys; optional sync (off)     │
                         └──────────────────────────────────────────────────────────────────────────────────────────┘
                         ┌───────────── Desktop (FastAPI + PWA, Windows installer) ─────────────┐
 browser / phone access ─► │ five destinations (Home · Assistant · Study · News · More)          │
                         │ agent.py (LangGraph ReAct: web_search, memory, knowledge tools) · intelligence.py (focus,   │
                         │   status, suggestions) · brain.py (discover, briefing, search) · news_center · task_runner  │
                         │ Ollama (qwen2.5, llama3.2, nomic-embed-text) · Chroma · Whisper · SQLite v5 + JSON          │
                         └──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Invariants:**
- The model never executes anything. Actions are registered, validated code paths.
- Private context goes online only under consent.
- Everything is stored locally.
- There is no schema change without approval: new data uses additive JSON side files.
