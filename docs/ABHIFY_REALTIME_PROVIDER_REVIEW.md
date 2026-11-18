# Abhify.AI real-time provider review (2026-11-18)

Checked on 2026-11-18 from the providers' pages and recent secondary sources. Prices change, so confirm on the provider's own page before enabling a paid plan.

**Nothing paid has been enabled.**

**The abstraction:**
- **Desktop:** `realtime.web_provider` = `ddgs` | `brave` | `tavily` | `none`. Keys come only from environment variables, read on the server.
- **Android:** a `RealtimeEngine` connector set. On the phone, general web search shows as **unavailable** until you approve a provider.

| Provider | Kind | Cost and limits | Freshness and quality | Privacy | Terms and licence | Status in Abhify |
|---|---|---|---|---|---|---|
| **Google News RSS** (existing) | News search | Free, no key; informal limits | Good for news; published dates per item; news only (not general web) | The question's topic goes to Google | Public RSS feeds; personal use | **On** (both platforms). Android Auto now uses it for news questions; disclosed under the answer ("topic searched on Google News"), and can be turned off in Settings → Live information |
| **DuckDuckGo via `ddgs`** (existing desktop dependency) | General web search (unofficial) | Free, no key; unofficial scraping library with rate limiting | Decent; **no publication dates** (results are marked "may be old" unless a date is known) | Query goes to DuckDuckGo; no account | The library says it is "not affiliated with DuckDuckGo", "for educational purposes", **not for commercial use** | **On** in the desktop app (existing setting `features.web_search`). **Needs replacing before any commercial or public release** |
| **Brave Search API** | General web search | **No free tier since Feb 2026.** $5 of credit a month (about 1,000 requests), then $5 per 1,000; card required; attribution required for the credit | Independent index, good freshness | Query goes to Brave; key required | Commercial API terms | Implemented on desktop, **needs key + your approval** (`BRAVE_API_KEY`) |
| **Tavily** | Search API made for AI agents (returns cleaned snippets) | **1,000 free credits a month, no card**; then $0.008 per credit; paid plans from $30 a month | Good for AI answers; aggregated sources | Query goes to Tavily; key required | Commercial API terms | Implemented on desktop, **needs key + your approval** (`TAVILY_API_KEY`) |
| **Open-Meteo** | Weather | Free for **non-commercial** use, no key; about 10,000 calls a day; commercial use needs a paid plan | Current conditions with the reading's own time | Only the place name (geocoding) and coordinates go out; no device location | Data CC BY 4.0, attribution required | Implemented on both, **off by default** (new external service); on with Settings → Live information plus a place |
| **Frankfurter (ECB reference rates)** | Exchange rates | Free, no key | **Daily** ECB reference rates (one a working day), not live market prices | Only the currency pair goes out | Free; data from the ECB | Implemented on both, **off by default**; labelled "daily reference rate" |
| Official documentation sites (python.org, kotlinlang.org…) | Direct sources | Free | Authoritative | Visiting the site | Per site | Not built: it would need per-site parsers or a general fetch, which is an SSRF and maintenance risk. Such pages appear when web search returns them |

## Recommendation (your decision)
1. **For a personal, non-commercial app now:** keep Google News (news) and DuckDuckGo (desktop web). Turn on Open-Meteo and Frankfurter in Settings if you want weather and rates.
2. **Before any public or commercial release:** replace `ddgs` with **Tavily** (the free 1,000 credits a month covers personal use; no card) or **Brave** (paid after the $5 credit). Open-Meteo needs its commercial plan for commercial use.
3. **General web search on Android** needs one of the keyed providers, with the key stored in the Android Keystore like the AI provider keys. It is not built until you approve a provider (R13).

## Sources
- [Brave Search API: free tier removed, $5 credit (implicator.ai)](https://www.implicator.ai/brave-drops-free-search-api-tier-puts-all-developers-on-metered-billing/), [Brave Search API pricing (websearchapi.space)](https://websearchapi.space/blog/brave-search-api-pricing)
- [Tavily credits and pricing (docs.tavily.com)](https://docs.tavily.com/documentation/api-credits), [Tavily pricing 2026 (coldiq.com)](https://coldiq.com/blog/tavily-pricing)
- [Open-Meteo](https://open-meteo.com/), [Open-Meteo terms](https://open-meteo.com/en/terms)
- [Frankfurter](https://frankfurter.dev/)
- [ddgs on PyPI](https://pypi.org/project/ddgs/9.14.1/), [DuckDuckGo API overview (cloro.dev)](https://cloro.dev/blog/duckduckgo-api/)
