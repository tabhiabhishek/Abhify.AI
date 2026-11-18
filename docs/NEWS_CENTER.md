# News Center (Android preview build 4.6.1-news-preview, desktop/web 2.0.0-dev.8)

## Navigation
- **Android:** the bottom bar is now **Home · Chat · Study · News · More**.
  - News takes the slot Documents had.
  - **Documents** moved to **More → Workspace** (first row). Its screen, storage and every action are unchanged; Home's Documents tile still opens it.
  - Study no longer has its own small News section; News is its own tab.
- **Web:** the existing News tab became the News Center (sidebar and phone tab bar unchanged). The web app has no Documents tab to move.

## Sources (no API keys needed)
- **Catalog:** one data-driven catalog, `news_sources.json`, an identical copy in `android/app/src/main/assets/` and `backend/` (a test enforces this).
- **15 categories:** For You, Local, India, World, Technology, AI & ML, Business, Sports, Science, Entertainment, Health, Education, Cybersecurity, Startups, Environment. Custom topics go in For You.
- **Feeds:** publisher RSS/Atom feeds, all checked 2026-11-09:
  - BBC, The Guardian, Al Jazeera;
  - The Hindu, Indian Express, Times of India, NDTV;
  - The Verge, Ars Technica, TechCrunch;
  - The Economic Times, Mint;
  - ESPNcricinfo, ScienceDaily, NASA, Variety, WHO;
  - The Hacker News, BleepingComputer, Krebs on Security;
  - Inc42, YourStory;
  - the 8 AI feeds the app had before.
- **Google News RSS:** topics, **Local** (a search for your city) and **search**. Its items name the original publisher; Read more opens the link Google News supplies.
- **Not integrated:** NewsAPI, GNews and NewsData.io all need API keys and have paid or limited tiers. The provider layer (fetch → normalize → validate → dedupe → sort → cache) can take an adapter later, but none was added without your decision on keys and terms.
- **Live check (desktop, 2026-11-09):**
  - every category returned stories, with images wherever the publisher provides them (e.g. India 54/60, Environment 45/45);
  - Local (Pune) returned 25 stories;
  - only MIT News was unreachable, and the app reports that.

## Articles
Each article has:
- id: SHA-1 of the normalized URL;
- headline, publisher, the original URL as supplied;
- publication time (or "date not given") and retrieval time;
- category, the source's own description, the image URL only if the source gave one, and the provider.

Rules:
- Links must be https.
- Tracking parameters are ignored for dedupe.
- Duplicates are merged only by identical URL, or identical title from the same publisher. Similar headlines are never merged.
- Missing images show a category-coloured placeholder, never another picture.

## Refresh and offline
- News is fetched when you open it, refresh it or search. There is no background polling.
- A category is re-fetched at most every 10 minutes unless you refresh.
- Each category's last result is kept in News's own cache: Android `cacheDir/news`, desktop `<data>/cache/news`. Offline, that copy is shown with its retrieval time.
- Search asks Google News for the last 7 days, also matches cached stories, and always shows each story's date.

## Source manager
You can set:
- your city (sent only as a search word; no location permission);
- the edition (IN, US, GB, AU, CA, SG, AE) and language (English, Hindi);
- which categories For You mixes, and custom topics;
- publishers to show first (★) or hide (✕).

## AI on news (optional)
- **What it does:** Briefing (from the stories shown), Summarize and Explain on one story.
- **Android:** through the app's task runner, so it uses your AI mode: the phone model, or your own online provider after the usual consent question.
- **Desktop:** the local Ollama model (the chat's chosen model, if any).
- **What's sent:** only headlines, publishers, dates and the source's short description. Never your conversations, memories or profile.
- **Untrusted input:** articles are fenced as untrusted data, and the model is told to ignore any instructions inside them (tested with an injection sample).
- **Labelling:** output says it is AI-generated from headlines and descriptions only (not the full articles), can be wrong, and lists the numbered source links.

## Strict separation from personal memory
- News code has no reference to the memory, knowledge or chat stores. The tests check the code itself, not the comments.
- News AI runs with no tools, no history and no memory.
- **Desktop test:** feed, search, briefing and summary leave the `memories`, `conversations` and `messages` tables unchanged.
- **Browser run:** memory had 0 entries before and 0 after.
- **Saving is always explicit:**
  - Android **Bookmark to Knowledge** creates a Knowledge note with the link, never a memory;
  - web **Save** keeps a reading list in this browser only.
