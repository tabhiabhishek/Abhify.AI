# Abhify.AI real-time engine: test report (2026-11-18)

All automated tests mock every external service: Google News, DuckDuckGo, Brave, Tavily, Open-Meteo and Frankfurter. A backend `conftest.py` autouse fixture blocks the engine's external calls in every test. No test uses your personal database; each test gets a throw-away data folder.

| Suite | Before | After | New |
|---|---|---|---|
| **Backend** `pytest` (with `ABHIFY_DATA_DIR` pointing at a folder that doesn't exist; no test created it) | 460 passed / 2 skipped | **533 passed / 2 skipped** | 73 (`test_realtime.py`: 40 test functions, some parametrised, plus the JS harness `realtime_harness.js`) |
| **Android** `testDebugUnitTest` | 437 / 0 / 4 | **449 run / 0 failed / 4 skipped** | 12 (`RealtimeTest`) |
| **Android lint** | 0 errors / 120 warnings | **0 errors / 121 warnings** | – |

**One existing assertion changed:** `test_web_desktop_61.py` checked `SHELL_REV == 'ui61.1'`. It now checks `'ui62.1'`, because the shell changed and the guard rule requires the bump. No other existing test was changed.

## Coverage against the brief (§14)
| Area | Backend | Android |
|---|---|---|
| Current-information intent detection, plus combined requests | 34 shared cases (`realtime_intents.json`) | the same 34 cases (`sharedIntentCasesMatchTheDesktop`) |
| Normal conversation → no retrieval | ✔ | ✔ (`ordinaryQuestionsNeverRetrieve…`) |
| Research routing | ✔ | ✔ (research adds news + web only for fresh questions) |
| News retrieval | ✔ (mocked `news_center`) | ✔ (fake news source) |
| General web search | ✔ ddgs / Brave / Tavily (mocked); missing key → `needs_key`; the key never appears in responses or logs | ✔ honest unavailable note (`webSearchIsHonestlyUnavailableOnThePhone`) |
| Weather / exchange rates | ✔ parsing of the real response shapes (mocked); off by default | ✔ (`weatherAndFx…`: the reading's UTC-offset time, "not a live market price", fixed hosts only) |
| Personal database / system status | ✔ temp stores; read-only `performance()` | ✔ (`personalRecords…`: works offline, `[L#]`, no URL) |
| Citations and timestamps | ✔ `[W#]`/`[L#]` numbering; published vs retrieved; `None` stays `None` | ✔ numbering after Research's items; undated → `historical`, never invented |
| Stale cache | ✔ `cached` with the original `retrieved_at`; the `news_center.feed` fix (it failed before the fix) | ✔ (`cacheMarksCached…`, refresh bypasses, TTL expiry) |
| Network failure / offline | ✔ `unavailable` with a reason; the model is told to say so | ✔ (`offlineMeansUnavailable…`: no requests made) |
| Rate limits / retries | ✔ one retry, call counts, spacing | ✔ (`failuresRetryOnlyOnce`) |
| Model independence | ✔ the same model id with and without evidence; the retrieved-text turn runs without tools | ✔ routing call unchanged; the engine can't reference model or provider code |
| Voice and text parity | ✔ the speech text skips `[W#]`, URLs and `www.` (JS harness); Talk → `/chat/stream` | ✔ `VoiceState` → `vm.send`; `SpeechStream` strips `[W#]`/`[L#]` and URLs |
| Privacy and consent | ✔ guests get external categories only; keys env-only | ✔ online prompt gets public evidence only; private documents still need consent; new services off by default |
| Prompt injection from retrieved content | ✔ instruction-like text removed; it can't trigger tools | ✔ (`retrievedTextIsUntrustedData`) |
| Web UI | ✔ chips escape everything (XSS), http(s) links only, `noopener noreferrer`; a model's `[W4](#)` renders as `[W4]` (found in the live check, then fixed) | – |
| `/realtime/*` routes | ✔ `local_only`, in `sw.js` API_PATHS and the README | – |

## Live checks (developer, not automated)
These used an isolated backend on port 18062 with a temporary data folder, deleted afterwards, and local Ollama.
- "What is the latest Python release?" (llama3.2): searching → done → tokens. The answer cited `[W1]` = python.org/downloads/latest. DuckDuckGo gives no dates, so the source showed "date not given".
- "What's happening in AI today?": 5 Google News items, published 0.8–7.2 h earlier, every `[W#]` matching its source.
  - In an earlier run the small model put one fact under the wrong number, so citation accuracy depends on the model.
  - That run also showed "AI" matching *Air India*. Fixed on both platforms ("AI" → "artificial intelligence").
- **Weather and exchange rates:** called once each to check the real response shapes (Pune; USD→INR 93.48 for 2026-11-17), then turned off again.
- **Browser (headless Chrome, API pinned, nothing sent to port 8000):** indicator, chips, details, cached, unavailable and settings, at 360–1920 px in dark and light, with no overflow. Screenshots are in `docs/screenshots/realtime/web/`.

## Not tested
- **Android on a phone or emulator** (none available): checklist tests 91–98 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md).
- **Real Brave or Tavily keys:** mocked only; you would need to approve and provide keys. There is no automated live-provider test yet: live checks were manual, as listed above.
- **Microphone voice runs** on either platform: code and harness checks only.
