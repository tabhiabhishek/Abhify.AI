# Abhify.AI 6.0 recommendation register

This continues [ABHIFY_5_6_RECOMMENDATIONS.md](ABHIFY_5_6_RECOMMENDATIONS.md); R1–R11 there still apply. Cost: S = under half a day, M = 1–2 days, L = more than that.

## Needs your approval
| # | Recommendation | Benefit | Cost | Privacy | Dependency | Risk |
|---|---|---|---|---|---|---|
| R1 | **Commit the 5.x/6.0 work** (about 170 paths uncommitted) | Protects the work | S | none | none | very low |
| R2 | Back up the debug keystore to a location you choose | Every installed build depends on it | S | key material; use an encrypted location | none | low |
| R10 | Official version name/code (for example 6.0 / 6000099) after the phone checklist | Clear versions | S | none | none | low |
| R13 | A general web search API for Research (Brave Search free tier, or Tavily) | Research beyond news: docs, papers, reference pages | M | the query goes to that provider | an API key; possible cost | low–medium |
| R14 | On-device image understanding (a small vision model, downloaded on demand) | Screenshot and diagram Q&A offline | L | stays on the phone | a large model download | medium (RAM and heat on 8 GB) |
| R11 | SQLCipher, ML Kit translation, wake word | Encryption at rest, translation, hands-free | M–L | varies | new dependency or permission | medium |

## Safe next steps (no approval needed)
| # | Recommendation | Benefit | Cost | Risk |
|---|---|---|---|---|
| R12 | Desktop interaction modes (port `Brain.profile` to `companion_ai.py`; mode chips on the web) | Parity | M | low |
| R15 | Answer-quality evaluation set: about 40 representative questions with reference points, run locally against Qwen/Ollama and each configured provider; results in a local JSON file; compared across versions | Measured model choice | M | low (no private data in the set) |
| R16 | Typed chat actions: when a typed message looks like an app action, show a tappable suggestion (the same registry and the same approval) without matching the text automatically | Cross-module flow | M | low; the voice-only matching invariant must be kept or carefully extended |
| R3 | Split `AppViewModel.kt` (chat send pipeline → `ChatPipeline`), `main.py` (routers), `app.js` (modules) | Maintainability | M each | medium; do it step by step with the guard tests |
| R4 | Move from `create_react_agent` to `langchain.agents.create_agent` | Stays supported after LangGraph v2 | S–M | medium |
| R17 | Voice barge-in (speak to interrupt) with echo cancellation | Natural interruption | L | the microphone is open during speech; needs care and probably your OK |
| R18 | Keep `RouteStats` across restarts (counts only), with a weekly comparison | Provider trends | S | low |
