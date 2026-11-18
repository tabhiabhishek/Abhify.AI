# Abhify.AI 5.2 product evolution: implementation report (Android build 5.3, 2026-11-18)

| | |
|---|---|
| Owner's request | "continue the previous task and the above prompt and then make build app 5.3 version so I can install and check on my mobile" |
| Build | `build/Abhify-5.3.apk`, versionCode 5030099, 73,708,493 bytes, SHA-256 `778e5c504e410ada2f582af89492f64d92a324f2220f4344975d01ecc5b57bd5` |
| Signing and permissions | debug certificate `1d2d1fdb…6246f`, the same as 5.0/5.1, so it updates in place. The same 10 permissions. |
| Kept unchanged | `build/Abhify-5.0.apk`, `build/Abhify-5.1.apk`, desktop installer, database schema (v17 / v5) |
| Not done | nothing committed, pushed, deployed or installed. **Physical-device testing: not performed** (no phone connected); the owner tests manually. |

Audit and plan: [ABHIFY_5_2_PRODUCT_AUDIT.md](ABHIFY_5_2_PRODUCT_AUDIT.md). Design: [ABHIFY_NEURAL_DESIGN_SYSTEM.md](ABHIFY_NEURAL_DESIGN_SYSTEM.md).

## 1. Features

| Feature | Before | Now (Android 5.3) | Files | Reused | Tests |
|---|---|---|---|---|---|
| Five destinations | Home · Chat · Study · News · More | Home · **Assistant** · Study · News · More (label only; same screens) | `AppViewModel.kt` | Tab enum | `FiveDestinationsTest`; 3 older label tests updated to the approved names |
| More, grouped | Workspace / Documents & OCR / Data / Application; AI screens inside Settings | **Knowledge · Workspace · Career · AI & System · Settings & Privacy** (+ OCR settings). AI/system *screens* open from More; Settings keeps the *settings* (AI mode, answer style, appearance, voice, performance, diagnostics). Every destination still once. | `V3Screens.kt` `SettingsTab`, `ManageScreens.kt` | all existing routes | `NavigationSettingsTest` rewritten to the new rule (owner-approved), incl. "each route once" |
| Neural core | orb states Idle/Listening/Thinking/Generating/Error | + **Searching** (retrieval running), **Offline** (no AI can answer), neural node layer; "Generating" now reads "Responding"; still when reduced motion/offline | `Aurora.kt`, `AppViewModel.kt` (`searching`), chat bubble orb | `AiOrb`, `orbStateOf` | `NeuralCoreTest` |
| Home command center | hero/ask, Continue working, suggestions, tiles, Task Runner, recent chats, quiz, Phone AI | + **Active projects** (status/next), **Learning** (plan in progress), **Knowledge** status (documents, passages, semantic on/off, corrections to review), **News · saved X ago** (last saved copy, never fetched on Home, never shown as breaking), **Discover** shortcut | `V45Screens.kt` | projects.json, planStats, chunk/vector counts, News cache | `MultilingualNewsTest.homeShows…` |
| Study → Discover | – | topic search over **live MIT OpenCourseWare** (MIT's own free/certificate/price data), **OpenStax** textbooks (Wikibooks was live in 5.3; since 2026-11-18 it is a search link because Wikimedia blocks automated requests without contact details), plus **provider search pages** (NPTEL, SWAYAM, freeCodeCamp, Khan Academy, Coursera, edX, Microsoft Learn, OCW, YouTube, MDN, Python docs). "Free only" filter; cost shown only from the source's data, else "Check the provider"; licence shown; Open on the provider's site; **Save to Knowledge** (category Study); **Make a learning path** → Study Plans (AI-generated, labelled) | `learn/CourseDiscovery.kt`, `ui/DiscoverState.kt`, `ui/DiscoverScreens.kt`, `StudioState.planTopicPrefill` | vault, Plans | `CourseDiscoveryTest` (parsers on real responses saved 2026-11-18) |
| Study → MCQs, Questions | in Career only | also in Study, using **the same screens** (`McqView`, shared `QuestionBankView`) | `HomeScreens.kt` `LearnTab`, `CareerScreens.kt` | Career engines | `FiveDestinationsTest`, updated `CareerNavigationTest` |
| News: English / हिन्दी / Both | Google News language only (publishers English) | selector on the News page; **20 verified Hindi publisher feeds** (Amar Ujala, Live Hindustan, ABP Live, NDTV India, BBC Hindi) + Google News Hindi; Hindi category names; Both = both feeds merged; separate caches (English key unchanged) | `news_sources.json` (shared), `NewsCenter.kt`, `NewsState.kt`, `NewsScreens.kt`; **desktop** `news_center.py` filters by language too | catalog, NewsQuery, cache | `MultilingualNewsTest`, backend `test_news_multilingual.py` |
| News: AI translation | – | "Translate into Hindi/English (AI)" per story: headline + description only, labelled **"AI translation · not the publisher's text"**; original one tap away | `NewsCenter.kt` (`NewsAi.translate`), `NewsState.kt` | aiTask, consent, routing | `MultilingualNewsTest` |
| Hindi read-aloud | device language voice | Hindi text uses a **Hindi voice**; if none is installed: a message with install steps, never an English voice | `voice/Voice.kt` | TTS | `MultilingualNewsTest` |

In-app paths updated to the new places: "More → Knowledge Center", "More → AI models on this phone" and "More → Google Drive" (several older messages said Settings).

## 2. Tests executed

| Suite | Before (baseline 2026-11-18) | After |
|---|---|---|
| Android JVM | 342 / 0 failed / 4 skipped | **357 / 0 / 4** (15 new in `V53Test.kt`; 5 older tests updated for the approved navigation/refactor, see table) |
| Android lint | 0 errors (115 warnings) | **0 errors** (117 warnings) |
| Debug build incl. native | OK | **OK**, `Abhify-5.3.apk` |
| Backend pytest | 399 passed / 2 skipped | **403 passed / 2 skipped** (4 new) |
| Live source checks (PC) | – | MIT Learn, OpenStax, Wikibooks APIs and 11 provider pages answered; all 22 Hindi feeds valid RSS with Hindi items |

## 3. Performance notes

- **Neural layer:** three dots, three lines and one arc per frame, drawn only while the orb is visible; static when reduced motion or offline.
- **Discover:** three requests in parallel with 8 s connect and 12 s read timeouts, off the main thread, plus a lazy list.
- **Home:** extra counts come from a few local SQLite reads on the IO thread. No network on Home.
- **News "Both":** fetches roughly twice as many feeds, in parallel, as before.
- Phone timings: **not measured** (no device).

## 4. Known limitations and remaining work

- **Desktop/web parity for 5.3 is not built yet:**
  - the Assistant label;
  - a Study tab with MCQs/QB and Discover;
  - the News language selector (the desktop backend already supports `language: en|hi|both`);
  - orb Searching/Offline.
- **Not built:**
  - news source comparison;
  - briefing schedule and notifications;
  - offline news cache screen;
  - lesson reader with progress;
  - daily learning goal and streak;
  - Privacy Center page;
  - storage insights;
  - opening the Assistant from other destinations with their context.
- **Hindi translation** quality depends on the model (Qwen 1.5B on the phone may be weak); it is labelled as AI output.
- **Approval needed:** YouTube/edX/Coursera catalog APIs (keys/OAuth), on-device ML Kit translation (new dependency, sends usage metrics). Earlier pending items are unchanged: auto-index on import, per-project online, web search on Android.
- **Risk R1:** 138+ uncommitted paths. A commit or backup is recommended (owner's decision).
