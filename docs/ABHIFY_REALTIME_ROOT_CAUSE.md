# Abhify.AI: why the assistant couldn't reliably use current information (root-cause report, 2026-11-18)

**Baseline:** 6.1-dev.1, Android 437/0/4, backend 460 passed / 2 skipped.

**How this was checked:** each request was traced from the screen to the final answer in the source. Each finding has a file and function, plus the test that now proves the fix. Desktop line numbers are from before the change; [realtime_desktop_notes.md](realtime_desktop_notes.md) has the desktop engineer's confirmations.

## Summary
Neither app had a live-information path that ran **reliably**:
- **Android** recognised current-information questions but only retrieved anything in 🔬 Research mode. Even then, the only source was Google News.
- **Desktop** had a real web tool (DuckDuckGo), but whether it ran depended on a small local model choosing to call it.

There were no weather, exchange-rate or system connectors, and no shared freshness model. Results had no reliable timestamps or citations. Cached news could be shown as fresh.

## Findings (questions 1–14 from the brief)
| # | Question | Finding | Evidence | Status |
|---|---|---|---|---|
| 1 | Does the Brain recognise current-information requests? | **Android: yes**. `Brain.FRESH` sets `Intent.FRESH_INFO` (`intel/Brain.kt`, `plan()`). **Desktop: no**: `intelligence.detect` has no fresh-information concept. Research only matches words like "research/evidence/sources" | `V60Test` (FRESH_INFO); `backend/intelligence.py` (no FRESH) | Confirmed |
| 2 | Does intent routing send them anywhere? | **Android:** `FRESH_INFO` in Auto only adds a notice ("the AI models don't browse… choose 🔬 Research") and `STALE_WARNING` to the prompt. Nothing is retrieved. **Desktop:** nothing is routed | `Brain.plan` → `web = p.web && webAllowed` (only Research has `web`); `AppViewModel.answer()`: `val webItems = if (!brainPlan.web) emptyList()` | Confirmed |
| 3 | Is Research the only live path? | **Android: yes**. Research → `NewsCenter.search` (Google News RSS), cited [W#]. **Desktop:** the `web_search` tool (model-chosen) and the news reader (News tab only) | `AppViewModel.answer()`; `backend/agent.py` `web_search` | Confirmed |
| 4 | Is general web retrieval missing? | **Android: missing.** Google News is news search, not web search. **Desktop:** exists (`ddgs` DuckDuckGo, no key) but is unreliable (#5) | `agent.py:133-148` | Confirmed |
| 5 | Is tool execution connected to the planner? | **No.** On desktop the planner never calls tools. `create_react_agent` leaves it to the model, and qwen2.5/llama3.2 often skip the tool or return an empty message. The 6.1 empty-answer fallback (`_fallback_llm`) retries **without tools**, so no web at all. Android has no LLM tool loop by design | `agent.py` `get_agent`, `_fallback_llm` | Confirmed |
| 6 | Can providers browse? | **No.** Gemini, Groq and OpenRouter are called through the OpenAI-compatible chat endpoint with no browsing or grounding tools (`providers/Providers.kt`). Ollama models are offline. Live data must come from the app | `providers/Providers.kt` request body | Confirmed |
| 7 | Does the selected model receive retrieved information? | **Android Research: yes**, as `[W#]` evidence in both the local and online prompts. **Desktop:** only when the model itself called the tool | `AppViewModel` `webCtx()`; `agent.py` | Confirmed |
| 8 | Do provider errors silently trigger fallback answers? | **Not as "current" answers.** Android shows a ⚠ failure and offers explicit alternatives. But on desktop a failed or skipped tool call meant the model answered from training data with **no "this isn't current" signal**, apart from instructions in the research focus | `agent.py` `web_search` returns an error string the model may ignore | Confirmed (desktop) |
| 9 | Is cached information presented as current? | **Desktop: yes.** `news_center.feed()` returned a cached copy younger than `REFRESH_SECONDS` with `"from_cache": False`. **Android:** the News tab shows its own retrieval time; chat Research used fresh search results | `backend/news_center.py:276-278` | Confirmed, fixed (desktop notes) |
| 10 | Are source timestamps preserved? | **News: yes** (`published_at`/`publishedAt` plus `retrieved_at`). **Desktop web_search: no.** DuckDuckGo results reached the model as title/body/URL with no date, and the sources list kept only URLs | `agent.py` `web_search` output format; `_finish` keeps `Source:` lines only | Confirmed |
| 11 | Do voice and text use the same pipeline? | **Yes on both.** Android `VoiceState.handle` → `vm.send(text)`; the web Talk feature uses the same chat endpoint. **But** Android's `SpeechStream` didn't strip `[W#]` markers or URLs, so citations and links were read aloud | `ui/VoiceState.kt:123`; `voice/SpeechStream.kt` (it stripped only `[S#]`/`[C#]`) | Confirmed, fixed |
| 12 | Do desktop, web and Android have the same capabilities? | **No.** Desktop has DuckDuckGo web search (unreliable). Android has Research news only. Neither had weather, rates or system status | Above | Confirmed |
| 13 | Are network failures reported? | **Partly.** Android Research says "No recent sources found…". Desktop `web_search` returned "OFFLINE…" to the model only, and the user saw whatever the model wrote | `agent.py` `web_search` | Confirmed |
| 14 | Are keys and provider settings loaded correctly? | **Yes for AI providers** (Android Keystore vault, tested in RC.3/4). There was **no search-provider configuration**: DuckDuckGo is keyless, and there was no Brave or Tavily path | `providers/ProviderStore`; `agent.py` | Confirmed (no search-provider layer existed) |

## Traced requests (before)
- **Android, Auto: "What's happening in AI today?"** → `send()` → `answer()` → `Brain.plan` gives FRESH_INFO with `web=false` → `webItems=[]` → the notice "models don't browse" plus `STALE_WARNING` → the selected model answers from training data and says it may be out of date.
- **Android, 🔬 Research, same question** → `NewsCenter.search(rq)` → 6 Google News items → `[W#]` evidence → the model cites them. Works, but only in Research and only for news.
- **Desktop: "What is the latest Python release?"** → `/chat` → `_prepare` → `create_react_agent(tools=[…, web_search])` → qwen2.5 often answers directly from training data, or returns an empty message → the 6.1 fallback without tools gives a training-data answer with no live signal.
- **Weather, exchange rate, "GPU memory used" (all platforms)** → no connector → the model guesses or declines.

## What changed (summary; details in the architecture document)
- **One live-information channel** next to the Personal AI Brain on both platforms. The same intent cases (`backend/tests/data/realtime_intents.json`, 34 cases) are tested against both resolvers.
- **Deterministic retrieval** through approved connectors, before the model runs, for whichever model is selected.
- **Evidence** with published vs retrieved time, freshness state, validated URLs, and sanitised (untrusted) text.
- **Honest unavailable states**, a cache that labels itself, and spoken answers without citation codes or URLs.
