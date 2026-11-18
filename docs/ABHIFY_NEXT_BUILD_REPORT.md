# Abhify.AI next internal build report: 5.4-dev.1 (2026-11-18)

## Artifact

| | |
|---|---|
| Path | `D:\Abhify.AI\build\internal\Abhify-5.4-dev.1.apk` |
| Size | 74,272,413 bytes |
| SHA-256 | `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354` |
| Package / version | `ai.abhify.android` · versionName **5.4-dev.1** · versionCode 5040099 (unchanged; internal suffix only) |
| Signing | debug certificate `CN=Android Debug`, SHA-256 `1d2d1fdb…fa22fe6246f` (same as 5.0–5.4; it updates over 5.4 and keeps data) |
| Permissions | the same 10 as 5.4 |
| Build | `gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL |
| **5.4 protected** | `build/Abhify-5.4.apk` re-checked after the build: SHA-256 `c176c90b…2330`, 73,904,909 bytes, unchanged. 5.0, 5.1 and 5.3 untouched. |

**Device-ready?** It builds, signs and passes every automated test. It is **not phone-validated**.

## What's new (Android)

| Feature | Before | Now | Files | Tests |
|---|---|---|---|---|
| Personal AI Brain | separate retrieval per feature | `ContextResolver` (relevant, permitted, freshness-labelled, cited) over projects, to-dos, notes, memories (only when on), documents, knowledge/news, AI tasks; `BrainState` gathers records on demand | `intel/ContextResolver.kt`, `ui/BrainState.kt` | `ContextResolverTest` |
| Today (daily plan) | – | Home → **Today**: plan from real to-dos and project next actions with reasons; overdue only from stored dates; blocked kept out; tap a to-do for priority, blocked or estimate; time budget 1–8 h | `intel/DailyPlanner.kt`, `ui/BrainScreens.kt` | `DailyPlannerTest` |
| Personal briefing | – | Home → Today → **Briefing**, or Settings → Personal briefing: morning / evening / weekly / on-demand. Every item shows its source; Skip; 🔊 Listen. Optional daily reminder, off by default, with no content in the notification and quiet hours respected | `intel/Briefing.kt`, `ui/BriefingWorker.kt`, `BrainScreens.kt` | `BriefingTest` |
| Agent 2.0 | Task Runner templates | Project page → **🤖 Find unfinished work** (or say "find unfinished work in my project"):<br>1. reads the status, notes and to-dos;<br>2. finds work written as TODO, `[ ]`, next or pending, plus AI proposals checked word-for-word against your notes (✔ verified / ≈ suggestion), with duplicates marked;<br>3. you tick and approve;<br>4. it creates the to-dos and reads them back to verify;<br>5. Undo and Cancel are available, and an audit log is kept.<br>The typed tool registry refuses unknown or sensitive tools. | `agent/AgentTools.kt`, `agent/ProjectReview.kt`, `ui/AgentState.kt` | `AgentToolsTest` |
| Skill graph | study suggestions | Study → Overview → **Skills**: per-topic level from quiz + MCQ answers (no level below 5 answers), due cards, plans as goals, general prerequisite order, adaptive next step with its reason | `learn/SkillGraph.kt` | `SkillGraphTest` |
| News intelligence | – | story ⋯ → **Related stories**, **Compare N sources (AI)** (only when publishers cover the same story; labelled; differences never called contradictions), **Learn this topic** → Study → Discover | `news/NewsIntel.kt`, `NewsScreens.kt`, `NewsState.kt` | `NewsIntelTest` |
| Universal search | SQL search | + projects (recorded status), AI task history, recent searches; memories hidden while personal memory is off | `ui/V40State.kt`, `ui/V40Screens.kt` | `NextBuildIntegrationTest` |
| Voice | 5.4 commands | + "plan my day", "brief me", "weekly review", "find unfinished work in my project", "what do I have on …" | `voice/AppActions.kt`, `ui/VoiceState.kt` | `NextBuildIntegrationTest` |
| Discover | Wikibooks API live | Wikibooks is a provider link (Wikimedia blocks automated requests without contact details) | `learn/CourseDiscovery.kt`, `ui/DiscoverState.kt` | `CourseDiscoveryTest` |

## What's new (Desktop/Web; backend + PWA)

| Feature | Files | Tests |
|---|---|---|
| Five destinations: Home · Assistant · Study · News · More (Question Bank and MCQs open from Study; More groups every existing page) | `frontend/index.html`, `app.js` | browser 11/11 |
| Study → Discover (`GET /discover`: live MIT OCW + OpenStax, provider links; the same cost rules as Android) | `backend/brain.py`, `main.py`, web | `test_brain.py`, browser |
| Home briefing (`GET /briefing`: projects, issues, failed AI tasks, weak MCQ topics, saved news with age) | `brain.py`, web | `test_brain.py`, browser |
| Universal search (`GET /search`: conversations, memories only when memory is on, projects, AI tasks, knowledge files, saved news) in the Ctrl+K palette | `brain.py`, `main.py`, web | `test_brain.py`, browser |
| News English / हिन्दी / Both selector (backend filtering existed since 5.3) | web | browser |

All new routes are `local_only`, listed in the service worker's never-cache list and in the README API table.

## Security and privacy

- No new permissions, dependencies, external accounts or paid services.
- Data stays local; the agent audit log, to-do priorities and briefing settings are app-private files.
- **Agent:** the model proposes data only. Tools are validated in code, nothing is written before approval, writes are read back, and the agent can never run sensitive tools.
- **Briefing reminder:** no content in the notification; off by default.
- **Discover:** sends only the topic; there is no contact information in requests (which is why Wikibooks is a link).
- **Prompt injection:** project notes are fenced as untrusted data in the review prompt; documents and news remain fenced as before.

## Performance

- New computations are pure Kotlin over local SQLite, run on IO threads and on demand: Home, Study, voice, the agent.
- No background work, except the optional once-a-day reminder (no AI, no network).
- The agent makes one AI call per project review.
- Phone latency and memory: **not measured** (no device).

## Known limitations

- The context resolver feeds the briefing, voice and planner. It isn't yet added to every chat prompt; chat keeps its tested memory, RAG and project context.
- Agent 2.0 has one workflow.
- The desktop lacks the voice companion, agent, planner and skill graph.
- Skill topics merge by name only ("Statistics" from quizzes and from MCQs merge; different names don't).
- See [ABHIFY_COMPLETE_PROJECT_AUDIT.md](ABHIFY_COMPLETE_PROJECT_AUDIT.md) for P0–P3 findings and [ABHIFY_RECOMMENDATIONS.md](ABHIFY_RECOMMENDATIONS.md) for what needs approval.

## Install and test

1. Copy `build\internal\Abhify-5.4-dev.1.apk` to the phone and open it.
2. Tap **Update**. Same signature, so data is kept; don't uninstall first.
3. **More → About** should read 5.4-dev.1.
4. Then run checklist tests 45–52 (plus the earlier ones still open) in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md).
