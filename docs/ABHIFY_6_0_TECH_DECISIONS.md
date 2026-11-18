# Abhify.AI 6.0 technical decisions

These extend [ABHIFY_TECH_DECISIONS.md](ABHIFY_TECH_DECISIONS.md).

| # | Decision | Alternatives considered | Why |
|---|---|---|---|
| D1 | A deterministic planner (`Brain.plan`, no model call) in front of every turn | An LLM "router" call per message | A router call adds latency and failure modes and costs a model run on a phone. The planner runs in microseconds and is fully testable |
| D2 | Modes change real parameters (history, context, phone-model length and creativity, web evidence, scaffold) | Prompt-only modes | The brief asked for real behaviour differences, and parameters can be measured and tested |
| D3 | Quick and Deep are never auto-detected | Auto-detect "quick" or "think deeply" | Effort is the user's choice. Auto detection would guess wrong and silently cut or expand answers |
| D4 | Research uses the existing Google News RSS search, only in Research mode with online access on | A web search API (Brave, Bing, SerpAPI, Tavily) | Free, already in the app, dated sources. A general search API needs a key, may cost money and is a new external service: **needs your approval** (R13) |
| D5 | A mode never changes which AI answers; stronger models are **advised**, not switched | Automatic escalation to an online model | Respects provider preferences and privacy settings. An automatic switch could send text online the user didn't intend |
| D6 | Mode tuning is handed to `HybridAi` via a one-shot `tuneNext()` | A new `generate` signature at every call site | The routing call stays byte-for-byte the same (guarded by an existing test), so routing provably didn't change |
| D7 | Memory was reviewed and **not** rebuilt | A new memory store or vector memory | It already ranks, dedupes, expires, keeps provenance and needs approval. A rewrite would add risk without measured benefit |
| D8 | No image or video understanding model | On-device vision LLM (for example Qwen2-VL, LLaVA) | Hundreds of MB to GB, and thermal and RAM cost on an 8 GB phone. **Needs approval** for a large model download (R14) |
| D9 | The desktop gets continuity (`companion_ai.py`) now, and modes later | Port everything at once | Backend and web changes were already large in 5.6; modes on the desktop are next (R12) |
| D10 | Versioning: `versionNameSuffix = "-internal.6.0-dev.1"`, versionCode unchanged | Bump versionName/versionCode to 6.0 | A public version change needs your approval |
