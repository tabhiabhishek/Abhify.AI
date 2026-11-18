# Abhify.AI 5.2 product audit (Phase 0, 2026-11-18)

Audit written before Phase 1; sections 7 and 10 record what the 5.3 build then implemented. Purpose: an inventory of what exists, where each screen belongs among the five approved destinations (**HOME · ASSISTANT · STUDY · NEWS · MORE**), what's missing, and a prioritised plan. Physical-device validation of Local RAG / 5.1 is still pending (owner's manual test); nothing here assumes it passed.

## 0. Baseline (recorded 2026-11-18, before any Phase 1 change)

| Suite | Result |
|---|---|
| Android JVM unit tests (`testDebugUnitTest`) | **342 passed, 0 failed, 4 skipped** (opt-in device/provider/eval tests) |
| Android lint | 0 errors (115 warnings) — last run 2026-11-17 |
| Backend pytest | **399 passed, 2 skipped** (dev.2-installer test after the approved cleanup; opt-in live Ollama test) |
| Browser 5.2 checks (headless Edge, isolated data) | 12/12 |
| Git | branch `feature/m2-knowledge-packaging`, HEAD `a9325d2` (desktop dev.6). **138 changed/untracked paths are uncommitted** — every Android release since rc.2 and all desktop work since dev.6 (see §11, risk R1) |
| Versions | At audit time Android **5.1** (5010099); `build/Abhify-5.0.apk` kept unchanged. Desktop 2.0.0-dev.8. **The owner then asked for a 5.3 build** (`build/Abhify-5.3.apk`, 5030099); 5.0 and 5.1 APKs kept |

Version note: the prompt says "keep version 5.0 unchanged". The owner asked on 2026-11-17 for 5.1, and on 2026-11-18 for "build app 5.3 version", so 5.3 is the current Android version. All earlier APKs are kept.

## 1. Current architecture

```
                    ┌──────────────────────────── Android app (ai.abhify.android, Kotlin + Compose) ───────────────────────────┐
 Phone user ──────► │ UI: AppViewModel (+ state holders: Studio, Life, Career, Mcq, News, Task, Project, KnowledgeCenter,      │
                    │     Companion, Memory, Backup, Drive…) · custom back stack (Route) · tabs (Tab)                           │
                    │ AI: HybridAi + RoutePolicy ──► llama.cpp JNI (Qwen2.5 1.5B/0.5B GGUF; bge-small embeddings on request)  │
                    │                          └──► Gemini / Groq / OpenRouter (user's own keys, Keystore) · optional Abhify server│
                    │ Data: SQLite ChatDb v17 (chats, memories, library_docs, doc_chunks+FTS4, chunk_vectors, flashcards, plans,│
                    │       workspaces/notes/tasks, career tables, vault_items…) + JSON side files (projects, tasks, career_mcq,  │
                    │       knowledge_updates) · Backup (local/Drive, additive restore) · ML Kit OCR · Android TTS/STT             │
                    └──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                    ┌──────────────── Desktop (Windows installer 2.0.0-dev.8; FastAPI backend + PWA frontend) ──────────────────┐
 PC / browser ────► │ frontend/ (index.html, app.js ~vanilla JS, styles.css, sw.js; WebLLM "Abhify Lite" in-browser option)     │
                    │ backend/ FastAPI (108 routes, 24 modules): agent.py (LangGraph ReAct + tools: web_search via ddgs, memory, │
                    │   knowledge), knowledge.py (Chroma + nomic-embed-text), news_center.py, task_runner.py, projects.py,       │
                    │   career_mcq.py, intelligence.py (5.2 focus/status/suggestions), companion.py (phone access), updates.py   │
                    │ AI: Ollama (qwen2.5 default, llama3.2, nomic-embed-text) · Whisper (voice) · SQLite schema v5 + JSON files  │
                    └──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
 cloud/ (optional account/sync service, not deployed)
```

## 2. Android ↔ Desktop technology mapping

| Concern | Android | Desktop/Web |
|---|---|---|
| UI | Jetpack Compose, Aurora design (Theme.kt tokens, Aurora.kt components, PageKit.kt) | Vanilla JS/HTML/CSS, same Aurora tokens (`--acc`, data-acc), sidebar + sheets/pages |
| Navigation | 5-item bottom bar Home·Chat·Study·News·More + off-bar tabs Documents, Career, Knowledge, Settings; Routes for full screens; brand launcher | Sidebar groups (Learn & Career / Workspace / AI System / Application), tab panels, sheets/pages, brand launcher, Ctrl+K palette |
| Local LLM | llama.cpp JNI, Qwen2.5 GGUF | Ollama (qwen2.5, llama3.2), WebLLM in browser |
| Online LLM | Gemini/Groq/OpenRouter direct with user keys | Ollama only by default (no hosted providers on desktop) |
| RAG | BM25 + FTS4 + optional bge-small (hybrid RRF) | Chroma + nomic-embed-text |
| Web search | none | `web_search` tool (DuckDuckGo via ddgs) |
| Storage | SQLite v17 + JSON side files | SQLite v5 + JSON files in the data dir |
| Voice | Android STT/TTS | Whisper (local) / browser STT; Web Speech TTS |
| Backup | abhify-backup v1, local/Drive, additive restore | data-folder backup/restore |

## 3. Existing feature inventory (screens)

### 3.1 Android (Kotlin/Compose): tabs `AppViewModel.Tab` (AppViewModel.kt:78-83), routes `AppViewModel.Route` (rendered in MainActivity.kt:94-121)

| Screen | Where now | What it does | Data / AI dependencies |
|---|---|---|---|
| Home (`HomeTab`, V45Screens.kt) | bar: Home | hero + Ask box + orb, Continue working, Suggestions, spaces tiles, Task Runner card, recent chats, quiz score, Phone AI, saved prompts, Drive | conversations, CompanionState (projects.json, tasks.json, study stats, corrections), deckStats |
| Chat (`ChatTab`, HomeScreens.kt) | bar: Chat | streaming chat, Continue/Regenerate/Edit, Actions, Correct, focus chip, Knowledge chip, documents/workspace/prompts chips, online-context dialog, voice | HybridAi/RoutePolicy, MemoryKit, RagIndex, KnowledgeChat, Intelligence, ProjectBrief |
| Study (`LearnTab`: Overview, Tutor, Plans, Cards, Quiz) | bar: Study | study dashboard + insights, AI tutor + evaluation, study plans from topics/documents, flashcards with SRS, built-in bank quiz | study_plans, plan_items, flashcards, card_reviews, study_sessions; learning_bank.json; Studio prompts |
| News (`NewsTab`) | bar: News | categories, search, featured + cards, briefing/summary/explain, bookmark→Knowledge, Listen (TTS), in-app reader (staged Read more), pull-to-refresh, sources/language prefs | news_sources.json (English publishers + Google News RSS, `language` pref drives hl/ceid), ArticleReader cache |
| More (`SettingsTab`) | bar: More | groups Workspace / Documents & OCR / Data / Application | – |
| Documents (`DocumentsTab`) | More → Documents | library import (PDF/DOCX/TXT/MD/CSV/images + OCR), search/sort, ask, compare, PDF viewer | library_docs, doc_chunks, ML Kit OCR |
| Career (`CareerTab`: Dashboard, Resumes, Question Bank, MCQs, Jobs, Match) | More → Career | tracker, résumé versions/analysis/export, question bank + generate + practise, MCQ practice, job descriptions, match engine | job_*, interview_attempts, resume_versions, career_mcq.json, career_questions.json, skills_taxonomy.json |
| Knowledge (`KnowledgeTab`: My Knowledge, Workspaces) | More → Knowledge | knowledge vault (saved answers/notes/news/documents), workspaces with tasks/notes/meeting notes/write/linked | vault_items, workspaces, notes, tasks, workspace_links |
| Knowledge Center (`SemanticSearchScreen`) | Settings → AI | Knowledge Chat mode, online switch, counts, bge-small model, index build, corrections review/history | chunk_vectors, knowledge_updates.json, EmbeddingManager |
| Tasks (`TasksScreen`) | More, Home | AI Task Runner (summary, study plan, quiz, briefing, custom), steps, retry, export | tasks.json, vm.aiTask |
| Projects (`ProjectsScreen`) | More | projects on workspaces: instructions, docs, status/"where you left off", chat, tasks, study, export | workspaces + projects.json |
| Search everything (`UnifiedSearchScreen`) | header 🔎, More | search across chats, documents, knowledge, notes, tasks, memories… | UnifiedSearch (SQL per type) |
| History, Memories, Models, Providers, Settings, Account, Sync, Privacy, About, Setup, Resume (classic), Drive, Storage, Backup, Connection | More / Settings | management screens | various |
| Onboarding (Welcome, Profile, Login, Register, Forgot, Link) | first run | local profile, optional account | cloud/ (optional) |

### 3.2 Desktop/Web (frontend/): tabs `.tab[data-tab]` (index.html:68-74), sidebar `.side-btn` (:76-83), sheets/pages

| Screen | Where now | What it does | APIs |
|---|---|---|---|
| Home (`tab-home`) | tab 1 | bento (ask, continue chat, quiz, news, documents, AI status, interview), Continue working + suggestions, customize layout | `/conversations`, `/suggestions`, `/news`, `/health` |
| News (`tab-news`) | tab 2 | categories, search, briefing, sources/language, in-app reader, Listen, pull-to-refresh | `/news*`, `/news/article/{id}`, `/news/prefs` |
| Question Bank (`tab-interview`) | tab 3 | categories, difficulty, generate, practise | `/generate-question` |
| MCQs (`tab-mcq`) | tab 4 | MCQs from topics / résumé / documents / mixed, review, progress | `/career/mcq*`, `/generate-mcq`, `/progress` |
| Chat (`tab-chat`) | tab 5 | streamed chat, model picker, focus selector, project chip, upload, mic, actions | `/chat`, `/chat/stream`, `/chat/warm`, `/models`, `/transcribe`, `/knowledge/upload` |
| Knowledge folder (`knowledgeSheet`) | sidebar | watched folder, indexing status, rescan/rebuild | `/knowledge/*` |
| Memory & history (`memorySheet`) | sidebar | memories CRUD, history settings | `/memory*`, `/preferences`, `/conversations` |
| Task Runner (`tasksSheet`) | sidebar | templates, sources, runs, history | `/tasks*` |
| Projects (`projectsSheet`) | sidebar | instructions, documents, status, chats, tasks, export | `/projects*` |
| Settings (`settingsSheet`) | sidebar / header | answer style, name, engine (PC / in-browser), default model, backend URL/token, voice | `/preferences`, `/models/default` |
| Setup / models (`setupSheet`) | launcher "AI models" | hardware check, model plan/pull/test | `/setup/*`, `/system/diagnostics` |
| System & data (`systemSheet`) | Settings / launcher | diagnostics, updates, companion (phone) access, backup/restore, data location | `/system/*`, `/backup*`, `/companion/*`, `/updates/*` |
| Performance (`perfSheet`) | Settings / launcher | live CPU/GPU/RAM and loaded models | `/system/performance` |
| Command palette (Ctrl+K), brand launcher, Home layout, News sources, News reader | overlays | navigation and commands | various |

### 3.3 Parity gaps found

- **Web lacks:**
  - Study: tutor, plans, flashcards, study dashboard.
  - Career: tracker, résumés, jobs, match.
  - A documents library; web has only the watched folder.
  - The knowledge vault and workspaces.
  - Online providers.
  - Unified content search (the palette searches conversations, projects and tasks only).
- **Android lacks:**
  - Live performance monitor.
  - Customisable Home.
  - Command palette.
  - Desktop updates and companion administration. These are not needed on a phone.

## 4. Five-destination mapping (every existing screen → exactly one destination)

**Android** already has exactly these five bar items: Home · Chat · Study · News · More. The only change needed is the label **Chat → Assistant**.

**Web** has a different primary bar today: Home · News · Question Bank · MCQs · Chat, plus a sidebar. To reach the approved five, **Question Bank and MCQs move under Study (Assessments)** and **Chat becomes Assistant**. Their pages and APIs are kept; only where they sit changes. The sidebar's Workspace items move under **More**.

| Destination | Android screens | Web screens |
|---|---|---|
| **HOME** | Home (hero/orb/ask, Continue working, Suggestions, tiles, recent chats, progress, Phone AI, saved prompts, Drive status) · Search everything (entry from Home header) | Home bento + Continue working/suggestions · Customize Home · Command palette (global entry) |
| **ASSISTANT** | Chat (all modes, focus, knowledge, documents, workspace context, voice, actions, corrections) · History · Saved prompts · New private chat · AI Task Runner | Chat (focus, model, project chip, upload, voice) · Task Runner |
| **STUDY** | Overview (dashboard), Tutor, Plans, Cards (SRS), Quiz · **from Career:** Question Bank and MCQs (also stay reachable from Career) | Question Bank · MCQs (move here) · *new:* dashboard, topic/course discovery, learning paths, tutor, flashcards |
| **NEWS** | News Center (categories, search, briefing, reader, Listen, sources/language) | News (same) · News sources · News reader |
| **MORE** | **Knowledge:** Documents, Knowledge (vault + workspaces), Knowledge Center, Personal memory. **Workspace:** Projects. **Career:** Dashboard, Resumes, Jobs, Match (+ links to Question Bank/MCQs in Study). **AI & system:** Models, Providers, Connection, Setup, Diagnostics. **Settings & privacy:** Settings, Privacy, Backup, Drive, Storage, Sync, Account, About | **Knowledge:** Knowledge folder, Memory & history. **Workspace:** Projects. **AI & system:** Models/Setup, Performance, System & data. **Settings & privacy:** Settings, backup |

Question Bank and MCQs have one home, **Study → Assessments**. Career keeps a shortcut that opens the same screen, so there's no duplicate engine.

## 5. Current capabilities by destination

- **Home**
  - Android has most of the requested items already:
    - greeting;
    - an orb tied to real state;
    - the Ask box;
    - Continue working (chat, project, task, study plan);
    - suggestions;
    - recent chats, quiz score, model status, saved prompts.
  - **Missing:**
    - daily briefing (configurable);
    - knowledge-status tile;
    - active-projects list;
    - recent-activity feed;
    - the orb's SEARCHING and OFFLINE states;
    - on web, Study progress.
- **Assistant**
  - Normal / Auto / Knowledge retrieval modes.
  - Focus: Coding / Learning / Career / Research.
  - Citations, "nothing found", corrections with approval, project-aware answers.
  - Memory with relevance cut-off.
  - Routing explanation.
  - Voice: Android STT/TTS; desktop Whisper.
  - Files and images: Android documents + ML Kit OCR; desktop upload.
  - Task Runner with consent, history and cancel.
  - **Missing:**
    - task-type routing is a hint only, by design (it never switches);
    - web search on the phone;
    - the assistant can't be opened from other destinations with their context. Partial today: News → summarise/explain, Projects → chat.
- **Study**
  - Android: tutor (levels, evaluate answers), study plans from topics/documents with progress, flashcards with SRS, study dashboard and weak-topic suggestions (`StudyInsights`), bank quiz, plus Career MCQs and Question Bank.
  - Web: MCQs and Question Bank only.
  - **Missing:**
    - topic and course discovery from real providers;
    - verified course metadata with free/paid status;
    - learning paths combining AI stages and verified resources;
    - a lesson reader with progress;
    - learning streak and daily goals (a quiz streak exists);
    - on web, tutor, plans and cards.
- **News**
  - Both platforms: 15 categories from public RSS + Google News.
  - Search, briefing, in-app reader, Listen, bookmarks to Knowledge, sources and preferences.
  - A `language` preference reaches Google News (`hl`/`ceid`), but the publisher feeds are English-only.
  - **Missing:**
    - Hindi publisher feeds and an English / Hindi / Both selector;
    - AI translation clearly labelled as such;
    - Hindi TTS voice selection, speed, queue;
    - source comparison;
    - offline cache management;
    - briefing schedule and notifications.
- **More**
  - All management, privacy, backup and knowledge screens exist, but on Android they're split between More and Settings.
  - **Missing:**
    - the grouped structure Knowledge / Workspace / Career / AI & System / Settings & Privacy;
    - a storage-insights view in Knowledge;
    - a Privacy Center that brings existing controls together (not new controls).

## 6. Study and News source research (checked 2026-11-18)

Labels: **V** = confirmed today on the provider's own service; **U** = unverified.

### Course discovery

| Source | Integration | Cost data | Status |
|---|---|---|---|
| MIT Learn (MIT OpenCourseWare) | keyless JSON API `api.learn.mit.edu/api/v1/learning_resources_search/` | `free`, `certification`, `prices` per course. The index also contains paid MIT xPRO courses, so the app filters to `offered_by=ocw` | **V, used** |
| OpenStax | keyless CMS API (129 books) | free textbooks | **V, used** |
| Wikibooks | MediaWiki API | CC BY-SA (page footer) | **V, used** |
| NPTEL, SWAYAM, freeCodeCamp, Khan Academy, Coursera, edX, Microsoft Learn, MIT OCW search, YouTube, MDN, Python docs | the provider's own search page for the topic (each loaded, HTTP 200) | shown on the provider's site | **V, used as links** |
| Khan Academy API | removed (HTTP 410) | – | not usable |
| Coursera `courses.v1` | undocumented, no search, no price/audit data | – | not used |
| Microsoft Learn catalog API | past its stated June 2026 sunset | – | not used |
| edX Discovery, YouTube Data API, OER Commons, MERLOT, Coursera affiliate | need OAuth, an API key or an account | – | **owner approval needed** |
| Class Central | robots.txt disallows `/api/*` | – | not used (no scraping) |

### Hindi news

Feeds verified as valid RSS with Hindi items:
- Amar Ujala, Live Hindustan and ABP Live, in six categories;
- NDTV India (latest);
- BBC Hindi, a single feed (its "section" feeds return the same items);
- Dainik Bhaskar and Prabhat Khabar (verified, not used yet);
- PIB Hindi;
- Google News in Hindi (`hl=hi&ceid=IN:hi`).

Failed: Dainik Jagran (404), Zee News Hindi (403 bot block), Jansatta (HTML, not RSS), DD News (empty or timeout).

Publishers' terms say "all rights reserved", so the app shows headline, snippet, source, date and a link, and never full republication. Google News terms allow personal, non-commercial display.

### Translation and Hindi voices

- **ML Kit on-device translation:** about 30 MB per language model. It sends usage metrics to Google and would be a new dependency, so it needs approval. Not used.
- **Argos / LibreTranslate:** offline and open source, but Hindi quality is unproven.
- **Local Qwen:** no published Hindi benchmark found.
- **Chosen:** translation goes through the existing AI routing, always labelled "AI translation · not the publisher's text", with the original one tap away.
- **Hindi voices:** Android needs Hindi voice data installed; on Windows, Hemant and Kalpana come with the speech pack. If no Hindi voice exists, the app says so instead of reading Hindi with an English voice.

## 7. Proposed new components (built in 5.3 unless marked)

| Component | Where | Built on (reused) |
|---|---|---|
| Neural core states SEARCHING, OFFLINE + neural layer | `ui/Aurora.kt` | existing `AiOrb`, `orbStateOf`, `reducedMotion` |
| Five destinations (Assistant label), grouped More | `AppViewModel.Tab`, `SettingsTab` | existing screens and routes, no new route |
| Home command center: active projects, learning, knowledge status, saved news, Discover shortcut | `V45Screens.kt` | `projects.json` status, `planStats`, `chunkCounts`/`vectorCounts`, News cache |
| Study → Discover (live sources + provider search) | `learn/CourseDiscovery.kt`, `ui/DiscoverState.kt`, `ui/DiscoverScreens.kt` | Knowledge vault (save), Study Plans (learning path) |
| Study → MCQs, Questions | `LearnTab` | **the same** `McqView`, `QuestionBankView` as Career |
| News language English / Hindi / Both, AI translation, Hindi voice | `news/NewsCenter.kt`, `NewsState`, `NewsScreens`, `Voice` | shared catalog, `NewsQuery`, aiTask, TTS |
| Desktop parity for 5.3 (Assistant tab, Study tab with MCQs/QB + Discover, News language selector) | web | **not built yet** (next step) |
| Source comparison, briefing schedule/notifications, lesson reader with progress, daily goals/streak | both | **not built yet** |

## 8. API and data integration plan

- **No new database or tables.**
  - Saved courses go to the Knowledge vault (category Study).
  - Learning paths are Study Plans.
  - Language is the existing `news_prefs.language`, now with "both".
  - Hindi caches use their own keys; the English key is unchanged.
- **Network:** only the search topic goes to MIT Learn, OpenStax and Wikibooks, which are keyless and public, with an identifying User-Agent. Nothing personal is sent. Each source has its own timeout and an honest failure note.
- **AI:** translation, summaries and learning paths go through the existing `vm.aiTask`, with consent and routing unchanged.

## 9. Dependencies and risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | 138 uncommitted paths since `a9325d2` (a disk failure loses months of work) | **Recommend** a commit on the feature branch, or at least a backup copy, now. This needs the owner's approval. |
| R2 | External APIs change (MIT Learn, OpenStax, Wikibooks, feeds) | Parsers tolerate missing fields. Per-source failure note. Provider search links still work. |
| R3 | Feeds' terms ("all rights reserved"); Google News personal-use terms | Show snippet + link only. Personal, local-first use. Revisit before any public release. |
| R4 | Hindi AI translation quality unproven | Labelled as AI translation, original kept. Owner to judge on the phone. |
| R5 | No Hindi TTS voice installed | Clear message with install steps, never an English voice. |
| R6 | Moving AI/System entries from Settings to More (owner-approved navigation) | Every destination still once (test). Settings keeps the settings. |
| R7 | Physical-device validation still pending | All 5.3 phone checks are listed in `ABHIFY_RAG_DEVICE_VALIDATION.md`. |

## 10. Prioritised implementation plan

| Phase | Content | Status |
|---|---|---|
| 0 | Audit (this document) | done |
| 1 | Neural design foundation (tokens doc, neural core states) | done on Android: `ABHIFY_NEURAL_DESIGN_SYSTEM.md`, orb states |
| 2 | Navigation (five destinations, grouped More) | done on Android; web next |
| 3 | Home command center | done on Android (projects, learning, knowledge, saved news, shortcuts) |
| 4 | Assistant | 5.1 work kept, plus Searching state. Next: invoke from other destinations with their context |
| 5 | Study | Discover + assessments in Study done. Next: lesson reader with progress, daily goal/streak, tutor on the current lesson |
| 6 | News | English/Hindi/Both + translation + Hindi voice done. Next: source comparison, briefing schedule, offline cache screen |
| 7 | More | grouped (done). Next: Privacy Center page, storage insights |
| 8 | Cross-module | News → "Learn this topic" (Discover), Study → Assistant |
| 9 | Desktop parity for 1–8 | next major step |
| 10 | Regression and optimisation | each phase has tests; phone validation by the owner |
