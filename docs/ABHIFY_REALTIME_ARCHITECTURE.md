# Abhify.AI Real-Time Intelligence Engine: architecture (6.2)

**Purpose.** The app works out when a question needs current information, retrieves it through approved connectors before the model runs, validates and dates it, and gives it to **whichever model is selected**. Live data never depends on a model being able to browse, and the engine never changes the model.

## Where it sits
```
question (typed or spoken; both go through the same chat path)
  └─ Personal AI Brain (unchanged): mode, intent, personal context, honest notices
  └─ Real-time intent resolver ─── needs: news / web / weather / fx / projects / tasks / system / none
        └─ connectors (approved, fixed hosts) ─ cache (TTL per category, in-flight dedup, 6 s timeout, 1 retry, rate limit)
              └─ source validator (URLs, HTML, instruction-like text, fake [W#])
                    └─ evidence assembler: [W#] retrieved from outside, [L#] own records / this device, plus rules
  └─ selected model (unchanged routing) ─ answer with citations
  └─ UI: live state, source chips (open the original), details, Refresh; voice speaks without codes or URLs
```

| Logical component (brief) | Desktop/web (`backend/realtime.py`) | Android (`intel/Realtime.kt`) |
|---|---|---|
| RealtimeIntentResolver | `resolve(text, focus)` | `Realtime.resolve(q, research)` |
| Shared intent cases | `backend/tests/data/realtime_intents.json` (34 cases), tested by `test_realtime.py` **and** `RealtimeTest` | same file |
| DataSourceRegistry | `DataSourceRegistry` plus `Connector.status()` (ready/off/needs_key/offline/error) | `RealtimeEngine.gather` (one branch per category) plus the Settings notes |
| WebSearchConnector | `WebConnector` with `DdgsProvider` (DuckDuckGo, existing), `BraveProvider`, `TavilyProvider` (env keys) | Not available. The honest note says so, and recent news is checked instead, labelled as news |
| NewsConnector | `news_center.search` / `feed("foryou")`; "AI" is spelled out; whole-word topic filter | `NewsCenter.search` via `Realtime.newsQuery` |
| WeatherConnector | Open-Meteo (place from the question or Settings) | Open-Meteo (place from Settings) |
| FinancialDataConnector | Frankfurter (ECB daily reference rates) | Frankfurter |
| PersonalDataConnector | `projects.list_projects` + Task Runner + project next actions | the workspace database, project status (`projects.json`) and open to-dos |
| SystemStatusConnector | `system_service.performance()`: CPU, RAM, nvidia-smi GPU/VRAM, Ollama `/api/ps` (read-only) | RAM, storage, on-phone model state; says GPU isn't measured and that Ollama is on the PC |
| SourceValidator | `SourceValidator` | `Realtime.validUrl`, `Realtime.sanitize` |
| FreshnessManager | `freshness_for`; TTL: weather 10 min, fx 60, news 15, web 30, local 0 | `freshnessOf`; TTL: weather 10, fx 60, news 15 |
| CacheManager | in-memory; on failure an expired copy is served as `cached` with its original time | in-memory; `cached` with its original time |
| EvidenceAssembler / RealtimeResponseBuilder | system-prompt block plus the `live` field on `/chat` and `/chat/stream` (`{"type":"live"}` events before tokens) | prompt block plus `UiMsg.links` / `UiMsg.live` |

## Freshness vocabulary (both platforms)
| State | Meaning |
|---|---|
| `live` | A reading taken now (weather, the device, records from the database) |
| `recent` | Retrieved now, published within the category window (news: 7 days). Exchange rates: the ECB reference rate for its own date |
| `historical` | Retrieved now, but published long ago, **or with no publication date**. Dates are never invented |
| `cached` | Served from the cache, with its **original** retrieval time |
| `unavailable` | Needed but not retrieved. The answer says why (offline, turned off, needs a key, service error) |

Each answer has a state: `verified` (everything needed was found), `partial`, `cached`, `unavailable`, or `none` (not needed).

## Model independence and privacy rules
- The routing call is unchanged on Android (`g.hybrid.generate(local, onlinePrompt, routeInputs())`) and the model id is unchanged on desktop (tested).
- **What may go to an online model:**
  - Android: only public evidence (`[W#]`); own records (`[L#]`) stay in the on-phone prompt.
  - Private documents still go online only after consent (unchanged).
- **Desktop:** a turn that contains retrieved outside text runs **without tools**, so retrieved text can't make the model save a memory, search, or read personal data.
- **Guests (desktop):** only the external categories; never projects, tasks, system or memories.

## Platform matrix
| Capability | Android | Desktop | Web (PWA served by the desktop) |
|---|---|---|---|
| News (Google News) | ✔ any mode, when online and Live information is on | ✔ | ✔ |
| General web search | ✖ (needs an approved keyed provider) | ✔ DuckDuckGo (existing); Brave/Tavily with a key | ✔ (via desktop) |
| Weather / exchange rates | ✔ when enabled (off by default) | ✔ when enabled (off by default) | ✔ (via desktop) |
| Projects / tasks | ✔ phone records | ✔ desktop records | ✔ (desktop records) |
| System status | ✔ phone (RAM, storage, model) | ✔ PC (CPU, RAM, GPU, Ollama) | ✔ (the PC's) |
| Voice | ✔ same pipeline; no codes or URLs spoken | ✔ same pipeline | ✔ Talk uses `/chat/stream` |
| Offline | Records and system work; external parts are "unavailable" with the reason | same | same |
