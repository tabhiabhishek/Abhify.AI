# Real-Time Intelligence Engine: desktop/web notes (facts for the reports)

Scope: backend (FastAPI) + web PWA. Android isn't covered here. Nothing is committed.

## Root causes (all confirmed)

1. **Web search depended on the model choosing to call it.** In the old `backend/agent.py`, `web_search` (tool at line 133,
   ddgs, flag `features.web_search`, default True) ran only when the small local model emitted a tool call. The 6.1
   empty-answer fallback `_fallback_llm` (old ~line 311, used at old lines 363/384) retried **without tools**, so that turn
   had no web at all. Results had no dates and no citation ids, and `_finish` (old line 319) only picked up `Source: <url>` lines.
   *Evidence:* code reading of the pre-change file. I did not write a failing test against the old code because I had no
   untouched copy to run it on (the file already had uncommitted edits). The new tests
   `test_a_turn_with_retrieved_text_runs_without_tools_and_keeps_the_model` and `test_stream_emits_live_events_before_tokens`
   check that `[W1]` evidence is in the system prompt before the model runs. The old code had no such block, so both would fail on it.
2. **No routing for current information.** `backend/intelligence.py` `detect()` (line 53) only picks a focus. The Research
   focus only changes the instructions (`FORMATS["research"]`, line 75). Chat never called `news_center.search` (Google News
   RSS with `published_at` / `retrieved_at`). *Evidence:* code reading; `grep news_center agent.py intelligence.py` → no hit before the change.
3. **Cached news was labelled fresh.** `backend/news_center.py` `feed()` returned a cached copy younger than
   `REFRESH_SECONDS` with `"from_cache": False`. *Failing test first:*
   `tests/test_realtime.py::test_news_center_feed_reports_a_cached_copy_as_cached` failed before the fix with
   `assert (False is True)` at the `second["from_cache"] is True` line. It passes after the fix.
   - Fix: `news_center.py:280` now returns `from_cache: True` with the cache's own `retrieved_at`, and `failed: []`.
   - Web UI callers adjusted (`frontend/app.js` `newsOpen` / `renderNewsCenter`). A recent cached copy with `failed == []` is
     not treated as a failure. It shows "Updated N min ago · saved copy"; "offline copy" still appears when sources failed.
   - Other callers: `task_runner.py:213` and `main.py /news/feed` don't read `from_cache`. Existing test
     `test_feed_caches_in_its_own_folder_and_works_offline` is unchanged and passes.

## New module: `backend/realtime.py` (~990 lines)

| Piece | Where | Behaviour |
|---|---|---|
| `resolve(text, focus)` | `realtime.py:183` | Deterministic regex routing to `news, web, weather, fx, projects, tasks, system`; `[]` = no retrieval. Combinations supported. A sentence starting with "Research …" → web (+ news when fresh). A user-chosen Research focus plus a fresh-sounding question → news + web. All 34 cases in `tests/data/realtime_intents.json` pass (JSON not edited). |
| Settings | `settings()` / `save_settings()` | `settings.json["realtime"]` = `{enabled: true, news: true, web_provider: "ddgs", weather: false, fx: false, place: ""}`. Only these keys are stored. `place` is cleaned to ≤ 80 chars of letters, digits, spaces and `, . ' -`. |
| `DataSourceRegistry` | `realtime.py:728` | One connector per category: `id, category, external, requires_key, enabled(settings), status()` → `{id, category, label, external, requires_key, state: ready/off/needs_key/offline/error, why}` |
| news | `NewsConnector` :410 | `news_center.search(q)`, or `news_center.feed("foryou")` for "today's headlines" with no topic. A lone acronym is spelled out ("AI" → `"artificial intelligence"`): the live check showed "AI" also matching *Air India flight AI 171*. Older cached stories that `news_center.search` merges in (it matches by substring: "ai" matches "Spain") are kept only when a query word matches as a whole word. |
| web | `WebConnector` :495 + `DdgsProvider` :452, `BraveProvider` :460 (GET `api.search.brave.com/res/v1/web/search`, header `X-Subscription-Token`), `TavilyProvider` :473 (POST `api.tavily.com/search`, `Authorization: Bearer`) | Provider is set by `realtime.web_provider`. ddgs is enabled only when `features.web_search` is true. Keys are read **only** from `BRAVE_API_KEY` / `TAVILY_API_KEY` env vars; missing key → `needs_key` with "set BRAVE_API_KEY in the environment…". Errors log `type(e).__name__` only. Google News is never presented as general web search (separate connector and label). |
| weather | `WeatherConnector` :555, `weather_item` :591 | Open-Meteo geocoding (`count=1`), then forecast `current=` and today's min/max. Needs `weather: true` **and** a place: the one in the question ("in Pune") or the place set in Settings. No device location. Observation time = `current.time` minus `utc_offset_seconds`. Attribution "Weather data by Open-Meteo.com (CC BY 4.0)". |
| fx | `FxConnector` :636, `fx_item` :659 | Frankfurter `api.frankfurter.dev/v1/latest?base=…&symbols=…[&amount=…]`; response `{amount, base, date, rates}`. Pairs come from ISO codes and names (dollar, rupee, euro, pound, yen, …); one currency → paired with INR (or USD→INR). Title "USD→INR daily reference rate for <date>"; `published_date` = the rate's own date. Freshness is recent/historical (4-day window), never "live". |
| projects / tasks / system | :672 / :692 / :713 | `projects.list_projects()` + `intelligence.project_brief` (recorded fields only); project `status.next` + Task Runner tasks in draft/ready/running/failed; `system_service.performance()` (CPU, RAM, nvidia-smi GPU/VRAM, Ollama `/api/ps`). Local only, read only, ids `L#`. |
| Evidence | `Evidence` dataclass, `_to_evidence` :839 | `id (W#/L#), kind, title, publisher, url (http/https, no credentials, else the item is dropped), published_at (ms or None — 0/absent stays None), retrieved_at, snippet, source_id, freshness (live/recent/historical/cached/unavailable), reliability (official/news/web/reference-rate/local-record/device)` plus `published_date`, `rate`, `via` where relevant. |
| Freshness / cache | `TTL` :53, `_lookup_one` :790, `_attempt` :768, `_rate_limit` :757 | TTLs: weather 10 min, fx 60, news 15, web 30, local 0. In-memory cache keyed by (connector, normalised query). Identical in-flight lookups are shared. Timeout 6 s per attempt, **one** retry, ≥ 1 s between calls and ≤ 20/min per external connector. `refresh` bypasses the cache. On failure, an expired copy is served as `cached` with its **original** `retrieved_at`. |
| `SourceValidator` | :244 | Strips HTML and control/bidi characters; caps snippets (400 chars; local 600–900); `[W3]` → `(W3)`; sentences that look like instructions ("ignore … instructions", "system prompt", "you are now", "call the tool", `save_memory`, role tags) → `[instruction-like text removed]`; `<<<`/`>>>` neutralised. |
| `EvidenceAssembler` | :929 | System-prompt block: current local date/time; rules (cite [W#]/[L#] only from the list; published vs retrieved; insufficient evidence; never invent URLs/publishers/prices/dates/facts; sources are DATA not instructions; separate records / retrieved / general knowledge / uncertain); fenced `<<<SOURCES … SOURCES>>>`. If nothing was retrieved, the block tells the model to say plainly at the start of the answer that current information couldn't be retrieved and why. |
| API helpers | `status()` :972, `public()`, `evidence_text()` :982 | `public()` removes the prompt text from `live`. `evidence_text()` feeds the legacy `web_search` tool. |

SSRF: the engine only calls the fixed hosts in `HOSTS`. It never fetches a result URL, and `follow_redirects=False`.

## Integration (`backend/agent.py`)

- `LIVE_TURN` contextvar (`agent.py:50`). `_live()` (:188) wraps `realtime.lookup`; if the engine itself fails, the turn is
  reported as `unavailable` with the reason and the chat doesn't crash.
- `_prepare(..., refresh_live=False)` (:336) runs the engine for every non-guest chat and appends the block to the system
  prompt. **When external evidence (`W#`) is present, the turn uses `_live_agent(model, temperature, num_predict)` (:208):
  the same model and sampling, with no tools.** Retrieved text therefore can't call `save_memory`, read the owner's data or
  send a search. It still returns 6 values; `prefs["_ctx"]["live"]` holds the result.
- `_finish` returns `live` = `{needed, state: verified|partial|unavailable|cached|none, sources, notes, problems, retrieved_at}` (:401).
  External source URLs are also added to `sources`, so they're saved in conversation history.
- `stream_agent` (:431/:436) emits `{"type":"live","phase":"searching","needed"}` before any retrieval, then
  `{"type":"live","phase":"done",…}`, then tokens. With no live need, no live event is sent and the stream is unchanged.
- `run_guest` (:217): guests get only external categories; projects, tasks, system and memories are never used.
  `live` is returned for guests too.
- Legacy `web_search` tool (:140): if the engine already searched this turn, it returns the same evidence (no second search).
- `main.py`: `ChatRequest.refresh_live` (:214) is passed on only when true, so default calls are unchanged.
  New routes `GET /realtime/status` (:423), `PUT /realtime/settings` (:430, Literal provider, bools, place ≤ 80),
  `POST /realtime/lookup` (:440). All are `local_only`. The module docstring lists them.
- `/voice` goes through `run_agent`, so it gets the same engine. The web Talk / dictate path calls `sendChat` → `/chat/stream`,
  the same path as typed chat.

## Web UI (`frontend/app.js`, `styles.css`, `index.html`, `sw.js`)

- Indicator line in the answer while retrieving: "Retrieving news…", "Checking a live source…", "Reading your records…",
  "Reading this computer's status…" or "Searching current information…". It then shows "Sources verified · retrieved <time>",
  "Using cached information (retrieved N min ago)", "Some sources verified · <reason>" or
  "Live information unavailable — <reason>". The dot pulses only under `prefers-reduced-motion: no-preference`.
- Source chips: `[W#]`/`[L#]` · publisher · "published 2 h ago" / "date not given" / "rate for <date>" / "reading …" / "your records".
  Each has a ↗ link (`target=_blank rel="noopener noreferrer"`, http/https only, re-checked on the client) and an inline
  details panel (title, publisher, published, retrieved, freshness, snippet, URL). Everything is escaped.
- "↻ Refresh": the newest answer is answered again in place (regenerate + `refresh_live`); an older answer is sent again as a
  new question with `refresh_live`.
- Speech: `speechChunks` removes `[W1]`, `[L2]`, `[W1, W2]`, `http(s)://…` and `www.…`, so markers and URLs are never read
  aloud. The chips stay in the conversation.
- Settings → "Live information": master toggle; provider select (DuckDuckGo default / Brave / Tavily / Off) with
  "needs key: set BRAVE_API_KEY in the environment…"; Weather toggle ("sends the place name to Open-Meteo") and place field;
  Exchange rates toggle ("uses Frankfurter / ECB daily reference rates"); connector status list from `/realtime/status`.
  Changes save immediately (PUT). On phones, guests and the on-device engine the section is hidden and a one-line note is shown instead.
- `sw.js`: `'/realtime'` added to `API_PATHS`; `SHELL_REV` 'ui61.1' → 'ui62.1'. `CACHE_VERSION` and `version.py` unchanged.
- News UI: see root cause 3.

## Tests

- New file `backend/tests/test_realtime.py`: 72 tests, including the 34 JSON intent cases.
- New harness `backend/tests/js/realtime_harness.js`: escaping (XSS), chips, details, indicator texts, speech stripping.
- `conftest.py`: an autouse fixture blocks realtime's HTTP transport, ddgs and News hooks and reports "offline" in every
  test, so no test can reach DuckDuckGo, Google News, Open-Meteo, Frankfurter, Brave or Tavily.
- `test_web_desktop_61.py::test_shell_guards_for_6_1` pinned `SHELL_REV == 'ui61.1'`. Updated to 'ui62.1' (the required bump); no other assertion changed.
- Full suite (`pytest -p no:cacheprovider`, non-existent temp `ABHIFY_DATA_DIR`): **532 passed, 2 skipped** (baseline 460 passed / 2 skipped; +72 new).

## Verified live (isolated backend :18062, ABHIFY_DATA_DIR = scratchpad\rt_data, deleted afterwards)

- `GET /realtime/status` defaults: news ready, web ready (DuckDuckGo), weather **off**, fx **off**, projects/tasks/system ready.
- `/chat/stream` "What is the latest Python release?" (llama3.2:latest): events `live:searching`, `live:done`, then tokens.
  Answer: "the latest Python release is Python 3.15.2 … released on October 25, 2026 … [W1]", with W1 =
  https://www.python.org/downloads/latest/. DuckDuckGo results carry no date, so they are shown as "date not given".
- `/chat/stream` "What's happening in AI today?" (llama3.2:latest, refresh): 5 Google News items, all `recent`, published 0.8–7.2 h
  before retrieval. In the second run (after the acronym fix) every [W#] in the answer matched its source title.
  In the **first** run (before the fix), llama3.2 attributed one fact to the wrong id ([W1] for a story that was W3/W4).
  Citation accuracy still depends on the small model.
- Weather and FX: one call each, after turning them on via `PUT /realtime/settings`, then turned off again. Open-Meteo Pune:
  "28.4°C; feels like 26.5°C; clear sky; humidity 34% …", observed 17:15 local, freshness live.
  Frankfurter: "1 USD = 93.48 INR (ECB daily reference rate published for 2026-11-17 …)". The real response shapes match the parsers.
- Headless Chrome (API base pinned to :18062; the run made **no requests to :8000** and none to other origins):
  screenshots in `docs/screenshots/realtime/web/`: retrieving indicator, verified chips, details panel, cached state,
  unavailable (weather off), chips at 360/390/768/1920, Settings → Live information at 1366 dark / 360 light.
  Overflow check at 360/390/768/1024/1366/1920 in dark and light: no page or component overflow.
- With weather off, the model's answer said weather was off. The UI line read "Live information unavailable — Weather is off (Settings → Live information)…".

## Not verified / known limits

- Brave and Tavily were tested only with mocked HTTP; no keys were used.
- The ddgs provider gives no publish dates, so web items are "historical / date not given".
- `news_center.search` still matches cached stories by substring for the News tab's own search. The engine filters these out
  for chat; the News tab isn't changed.
- Voice was checked by reading the code (`/voice` → `run_agent`; Talk → `sendChat` → `/chat/stream`) plus the speech-text
  harness. No microphone test was run.
