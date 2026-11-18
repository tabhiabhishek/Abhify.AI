# Abhify.AI real-time engine: privacy and security review (6.2)

## What leaves the device, and when
| Data | Goes to | When | Default |
|---|---|---|---|
| The question's topic (news query, at most 120 characters; "AI" spelled out) | Google News RSS | A question that needs news, with Live information on and a connection | **On.** This is an existing integration (the News tab and Research already use it). Android shows "topic searched on Google News" under the answer |
| The question as a web query (desktop) | DuckDuckGo (`ddgs`), or Brave/Tavily if you configure them | A question that needs web information | **On** for DuckDuckGo (the existing `features.web_search` setting). Brave/Tavily need your key and approval |
| A place name | Open-Meteo (geocoding + forecast) | Weather questions | **Off** until you enable weather and set a place. No device location is used, and no location permission was added |
| A currency pair | Frankfurter (ECB rates) | Exchange-rate questions | **Off** until enabled |
| Projects, to-dos, memories, documents, system details | **Nowhere** | – | They are read locally and never sent to these services |

**Your decision:** Android now sends news-type questions to Google News in every mode, not only in 🔬 Research. It is disclosed under each such answer and can be turned off in Settings → Live information. If you'd rather keep news lookups to Research only, turn that setting off; the code change is one default.

## Online AI providers
- **Android:** an online model receives only the public evidence (`[W#]`). Own records (`[L#]`) and phone status stay in the on-phone prompt, and the online prompt says they were left out.
- **Unchanged:** private documents go online only after consent; memories only if allowed; chat history only if allowed.
- **Desktop:** Ollama is local, so evidence stays on the PC.

## Security controls
- **Keys:**
  - Brave and Tavily keys come only from server-side environment variables (`BRAVE_API_KEY`, `TAVILY_API_KEY`).
  - They are never stored in settings, never returned by `/realtime/status` or any other API, never sent to the browser, and never logged (errors log only the exception type). This is tested.
  - Android has no search keys.
- **SSRF:**
  - Both engines call only fixed provider hosts. Android allows only `geocoding-api.open-meteo.com`, `api.open-meteo.com` and `api.frankfurter.dev`, over https, with no redirects and responses capped at 256 KB.
  - Desktop uses a `HOSTS` table with `follow_redirects=False`.
  - Result URLs are never fetched; they are only shown as links, and only if they are http(s) with no credentials.
- **Retrieved text is untrusted data:**
  - HTML, control and bidi characters are removed, and snippets are capped.
  - Fake citation markers are neutralised.
  - Instruction-like sentences ("ignore previous instructions", "system prompt", "you are now", "call the tool", `save_memory`…) are replaced with "[instruction-like text removed]".
  - The prompt says sources are data, not instructions.
  - On desktop, any turn that contains retrieved outside text runs with **no tools**. Android has no LLM tool loop.
  - Tested with injection samples on both platforms.
- **Abuse limits:** 6 s timeouts, at most one retry, 1 s or more between calls per connector (desktop also caps at 20 a minute), shared in-flight requests, a cache per category, and no retry loops (tested).
- **Validation:**
  - `PUT /realtime/settings` accepts only known keys; the provider must be one of `ddgs|brave|tavily|none`; the place is at most 80 characters.
  - All `/realtime/*` routes are `local_only`.
- **Guests (desktop membership):** external categories only. No personal data and no system status (tested).
- **Telemetry and analytics:** none added.
- **Permissions:** none added on Android (it still has the same 10).
- **Dependencies:** none added (httpx and ddgs were already used).

## Licensing notes
- Open-Meteo's free API is for non-commercial use (CC BY 4.0, attribution shown).
- `ddgs` is unofficial and says it is not for commercial use. Both need replacing or a paid plan before any commercial or public release (see the provider review).
