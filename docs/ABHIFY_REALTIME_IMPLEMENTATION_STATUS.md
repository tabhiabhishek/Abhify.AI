# Abhify.AI real-time engine: implementation status (6.2, 2026-11-18)

**Build:** `build/internal/Abhify-6.2-dev.1.apk`
- 74,655,524 bytes, SHA-256 `4a979a49f7a63f3da708ac3d62289283f8252e345e8e2e94fa1b0674d23ce171`.
- versionName `5.4-internal.6.2-dev.1`, versionCode 5040099 (unchanged).
- Same debug certificate `1d2d1fdb…6246f`; the same 10 permissions.

**Why 6.2:** no 6.1.1 maintenance work exists in the repo (no files, builds or references). New features go in a new minor internal number, so 6.2 cannot collide with a later 6.1.1. All 12 earlier APKs are unchanged.

## The ten report items
1. **Why current data wasn't used reliably:**
   - Android retrieved only in 🔬 Research, and only news.
   - Desktop web search depended on a small model choosing to call a tool, and its 6.1 fallback ran without tools.
   - No weather, rate or system connectors existed, timestamps and citations were incomplete, cached news was labelled fresh, and Android voice read citations aloud.

   Details: [ABHIFY_REALTIME_ROOT_CAUSE.md](ABHIFY_REALTIME_ROOT_CAUSE.md).
2. **Confirmed issues:** all 14 questions in the brief were answered, and 10 issues were confirmed. The cached-news one was proved with a failing test first.
3. **Connectors implemented:** news, web (DuckDuckGo plus Brave/Tavily on desktop), weather, exchange rates, projects, tasks, system. Android has all except general web search.
4. **Needs configuration or approval:**
   - Brave or Tavily key (R13).
   - Weather and exchange rates are off by default. Turn them on in Settings → Live information; please also say whether they should default on.
   - Android general web search (needs an approved provider plus Keystore storage).
   - Whether news lookups in Auto should stay on by default (Privacy review).
5. **Works offline:** projects, to-dos, system/device status, and answering from knowledge. External parts say "no internet connection" instead of guessing.
6. **Needs internet:** news, web search, weather, exchange rates.
7. **Needs an API key:** only Brave and Tavily. Everything enabled now is keyless.
8. **By platform:** see the matrix in [ABHIFY_REALTIME_ARCHITECTURE.md](ABHIFY_REALTIME_ARCHITECTURE.md). Android has no general web search; desktop and web have DuckDuckGo.
9. **Tests:** backend **533 passed / 2 skipped** (was 460/2); Android **449/0/4** (was 437/0/4); lint 0 errors. See [ABHIFY_REALTIME_TEST_REPORT.md](ABHIFY_REALTIME_TEST_REPORT.md).
10. **Remaining limitations:** see below.

## Status by component
| Component | Desktop/web | Android |
|---|---|---|
| Intent resolver (shared cases) | Done | Done |
| News connector | Done | Done (any mode; disclosed) |
| Web search connector | Done: DuckDuckGo; Brave/Tavily with key (mocked only) | **Not available** (honest note) |
| Weather / exchange rates | Done, off by default | Done, off by default |
| Projects / tasks / system | Done | Done |
| Validation, freshness, cache, rate limit | Done | Done |
| Evidence + prompt rules | Done (retrieved-text turns run without tools) | Done (online gets public evidence only) |
| UI: live state, chips, details, Refresh, settings | Done (screenshots) | Done (code; **not seen on a device**) |
| Voice parity | Done (code + harness) | Done (code + test) |
| Official-docs connector | Not built (SSRF and maintenance risk) | Not built |

## Remaining limitations
- **Citation accuracy** depends on the model. Small local models sometimes put a fact under the wrong `[W#]`. The chips always show the real sources.
- **DuckDuckGo** results have no publication dates, so they are marked "date not given / may be old". `ddgs` is unofficial and not for commercial use.
- **Exchange rates** are ECB daily reference rates, not live market prices; this is labelled. There are no stock prices: "stock price" questions route to web search, which is unavailable on Android.
- **Android:** source chips aren't kept after the app restarts (the message text and note are stored; the chips are rebuilt only for new answers). Desktop weather can take the place from the question; Android uses only the place in Settings.
- **The News tab's own search** (desktop) still matches cached stories by substring. Chat filters these out.
- **Not run on a phone or with a real microphone.**
