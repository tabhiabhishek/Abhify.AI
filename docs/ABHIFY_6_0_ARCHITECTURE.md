# Abhify.AI 6.0 architecture: one Personal AI Brain

This builds on [ABHIFY_ARCHITECTURE.md](ABHIFY_ARCHITECTURE.md) and the 5.5/5.6 documents. 6.0 does not rewrite the app. It puts **one planner** (`intel/Brain.kt`) in front of the pieces that already worked, so chat, voice, Study, News and the agent all use the same intelligence.

```
user text (typed, or spoken → VoiceState; app actions are matched only from spoken words, in VoiceState)
   │
   ▼
Brain.plan ── Intelligence.detect (mode: Auto/General/Coding/Learning/Career/Research/Quick/Deep/Planning/Creative; code/log/trace signals)
   │          Companion.retrievalQuery (follow-ups → active topic) · intent (small talk / own records / needs fresh info / task)
   │          Profile: history window · personal-context on/off · phone-model length & creativity · web evidence · reasoning scaffold
   ▼
Context (only what the plan asks for, with permissions):
   memories (MemoryKit.relevant: only if memory is on; ranked; cutoff) · documents/knowledge (RagIndex; Knowledge Center rules)
   records (ContextResolver: projects, to-dos, notes, saved news/knowledge, AI tasks; freshness labels; relevance floor)
   project status (ProjectBrief: recorded, with its age) · web evidence (Research only: news search, dated, [W#])
   ▼
Prompt: PromptBuilder (+ answer style, Companion charter / earlier turns / follow-up hint, mode scaffold, stale-information warning)
   ▼
HybridAi route (RoutePolicy: device / your server / your keys; consent rules; rate-limit cooldown) + mode tuning for the phone model
   ▼
Answer + sources (📝 project · [S#] documents · 🧭 records with freshness · 🌐 [W#] web with dates) + notices (mode, route hint, honesty)
   ▼
Voice: SpeechStream speaks finished sentences in order; interrupt cancels stream + generation · RouteStats (counts only)
   ▼
Actions: AgentTools registry (READ / REVERSIBLE needs approval / SENSITIVE never) → execute → read back → undo → audit log
```

## Modules and responsibilities
| Concern | Android | Desktop |
|---|---|---|
| Turn planner | `intel/Brain.kt` (new in 6.0) | Partly: `companion_ai.py` (continuity) and `brain.py`/`insights.py`. No modes yet (deferred) |
| Intent and mode | `intel/Intelligence.kt` (6.0: Quick, Deep, Planning, Creative) | `agent.py` focus handling |
| Continuity | `chat/Companion.kt` | `companion_ai.py` (same rules) |
| Personal records | `intel/ContextResolver.kt`, `ui/BrainState.kt` | `brain.search`, `insights.search_grouped` |
| Memory | `memory/MemoryKit.kt` | `memory_service.py` |
| Documents | `docs/RagIndex.kt`, `KnowledgeChat` | `knowledge.py` (Chroma) |
| Routing | `ai/HybridAi.kt`, `RoutePolicy`, `providers/Providers.kt` (cooldown), `ai/RouteStats.kt` | Ollama (local) |
| Agent | `agent/AgentTools.kt`, `ProjectInsights.kt`, `ui/AgentState.kt` | `insights.py` (`/agent/plan`, `/agent/apply`, `/agent/undo`) |
| Voice | `ui/VoiceState.kt`, `voice/SpeechStream.kt`, `voice/Voice.kt`, `voice/AppActions.kt` | Web speech: existing STT/TTS |
| Evaluation | `intel/RetrievalEval.kt`, `V60Test` | `eval_local.py` (`/eval/retrieval`) |

## Invariants (all enforced by tests)
- Personal records (context finder) go into **local** prompts only. Online prompts get documents only after consent, and memories only with "share memories online".
- Research sends the question to Google News **only** when the user picks Research **and** online access is on. The evidence is public, dated and cited.
- A mode never changes **which** AI answers (`RoutePolicy` is untouched; `tuneNext` affects only the phone model's length and creativity). Route advice is shown, never applied.
- App actions are matched only from the user's spoken words (`AppActions.match` has one caller). Changes need on-screen approval. Sensitive actions never run by voice or by the agent.
- No new permission, no new dependency, and no database schema change (ChatDb v17).
