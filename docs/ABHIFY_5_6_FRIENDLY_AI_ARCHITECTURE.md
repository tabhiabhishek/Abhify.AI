# My Friendly AI: architecture (5.6)

The companion is not a prompt bolted onto a chatbot. Every chat turn passes through the same layers, on Android, and on the desktop through `backend/companion_ai.py`, which mirrors the same rules.

```
question ─► Intelligence.detect (focus: code / study / plan …; signals: stack trace, log, code)
         ─► Companion.retrievalQuery (follow-up? → add the active topic)
         ─► retrieval with that query: memories (MemoryKit, only if memory is on) · documents/knowledge (RagIndex, Library)
             · context finder (ContextResolver: projects, to-dos, notes, saved knowledge/news, AI tasks; relevance floor)
         ─► prompt: PromptBuilder + answer style (Personality, Intelligence.instructions, response prefs)
             + Companion.turn (charter, earlier-turn summary, follow-up hint, small-talk mode)
         ─► HybridAi route (device / server / your own key), with consent rules unchanged
         ─► answer with sources (📝 workspace, [S#] documents, 🧭 your records with freshness) ─► RouteStats (counts only)
```

## Layers and what they guarantee
| Layer | File | Guarantee |
|---|---|---|
| Personality | `chat/Companion.kt` `CHARTER` | Friendly, calm, honest. Length fits the question. One clarifying question only when genuinely ambiguous. No repeated greetings or flattery, and at most one emoji. **It is an AI: it never claims feelings, experiences or memories.** |
| Continuity | `Companion.isFollowUp`, `activeTopic`, `retrievalQuery` | "why?", "and the second one?", "explain that" are answered **and searched** in the context of the active topic. A follow-up to a follow-up keeps the original topic. |
| Long conversations | `Companion.earlier` | Turns outside the 8-message window are kept as the user's own earlier questions (extractive, at most 5, never invented). This also works after an app restart, because history is stored per conversation. |
| Small talk | `Companion.smallTalk` | "thanks", "good morning" get a short, natural reply, not a feature list or a task. |
| Intent | `intel/Intelligence.kt` (existing) | Focus and format: code, debugging, study, planning and so on. |
| Personal context | `intel/ContextResolver.kt` (5.5/5.6) | Sources are filtered by permission. Questions with 3 or more meaningful words need 2 shared terms (no padding). "my notes / my project …" questions in plain chat use it too. **Local prompts only.** |
| Answer style | `PromptBuilder.Personality`, `Intelligence.Prefs` (existing) | Detail, depth and style as set by the user; unchanged and still on top. |
| Memory | `memory/MemoryKit.kt` (existing) | Only when memory is on. Sensitive items are never stored silently. Provenance is kept (`remember:<chat>`). Review, correction and deletion are in Personal memory. |
| Rate limits | `providers/Providers.kt` `coolDown` | After HTTP 429 the provider rests for its Retry-After time (default 60 s, at most 15 min; memory only). Auto mode moves on by the existing fallback rules instead of sending more failing requests. |
| Routing quality | `ai/RouteStats.kt` | Failure rate, time to first words and fallbacks per provider, shown in Settings → AI providers. Counts only. |
| Retrieval quality | `intel/RetrievalEval.kt` | precision@k, recall, and "returns nothing when nothing is relevant" on labelled cases. |

## Privacy rules (unchanged, and tested)
- **Online prompts:**
  - never contain context-finder records (`wsOnline` excludes them);
  - contain the earlier-turn summary only when "send recent chat online" is on;
  - contain memories only with "share memories online";
  - contain documents and knowledge only after consent.
- **Nothing is logged with content:** `RouteStats` holds no text, and retrieval logs only counts and timings.

## Context-aware suggestions
These already exist and are grounded in records:
- the Home suggestions (5.2) and the briefing (5.4);
- the skill revision plan (5.6);
- the agent's status, priorities and duplicates (5.5/5.6).

All can be dismissed. Nothing is suggested without a record behind it.

## Evaluation
- **Automated:**
  - `RetrievalEval` on synthetic cases (precision@k 1.0, recall 1.0, and an empty result for an off-topic question);
  - `eval_local.py` on the desktop (search, related documents, duplicates);
  - `CompanionTest` (follow-ups, the earlier-turn summary, small talk, honesty of the charter).
- **Not automated:** answer quality of the local Qwen model and the online providers on representative questions. This needs the phone, or keys and network, and is listed as a manual check (tests 62–64).
