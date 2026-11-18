# Changelog: Abhify.AI for Android

The desktop/web app has its own changelog: [../CHANGELOG.md](../CHANGELOG.md). These are debug builds only; nothing has been published.

## [Internal 6.2-dev.1]: real-time information (2026-11-18; `build/internal/Abhify-6.2-dev.1.apk`, SHA-256 `4a979a49f7a63f3da708ac3d62289283f8252e345e8e2e94fa1b0674d23ce171`)
versionName `5.4-internal.6.2-dev.1`, versionCode 5040099 (unchanged). Reports: `docs/ABHIFY_REALTIME_*.md`.
- **Live information when a question needs it**, in every mode, for typed and spoken questions:
  - news (Google News, disclosed under the answer);
  - your projects and to-dos;
  - this phone's memory, storage and AI model;
  - weather (Open-Meteo) and exchange rates (ECB via Frankfurter), both **off until you turn them on** in Settings → Live information.
- **Answers cite their sources** as [W#] (retrieved now) and [L#] (your records or phone). Chips show the publisher, the published time ("date not given" when unknown) and open the original page. The live line says "Sources checked", "Using cached information" or "couldn't check: …", and "↻ Refresh live information" fetches fresh sources.
- **Honest when it can't:** no internet, a service turned off, or general web search (not available on the phone) are stated, never guessed.
- **Privacy:** an online model gets only the public sources; your records stay on the phone.
- **Safety:** retrieved text is treated as untrusted data, and only fixed provider hosts are contacted.
- **Voice:** no longer reads "[W1]" or web addresses aloud.
- No database change, no new permission or dependency. Not yet tested on a phone (checklist tests 91–98).

## [Internal 6.1-dev.1]: audit fixes and polish (2026-11-18; `build/internal/Abhify-6.1-dev.1.apk`, SHA-256 `4e8bafeb5bd3754befcbc79960235ee74641ca73d41808abe90766bd3fa69f24`)
versionName `5.4-internal.6.1-dev.1`, versionCode 5040099 (unchanged). Reports: [ABHIFY_6_1_AUDIT_AND_STATUS.md](ABHIFY_6_1_AUDIT_AND_STATUS.md), [ABHIFY_6_1_BUILD_REPORT.md](ABHIFY_6_1_BUILD_REPORT.md).
- **System Back:** closes the open project, task, resume, study session or knowledge item first. In the Voice and Agent panels it ends voice or cancels the agent, and never navigates underneath. The panels no longer let taps through.
- **Navigation:** Documents, Career and Knowledge go back to where you opened them (Home or More). The Assistant header has a history button.
- **No stale or fake data:** project pages never show another project's counts. Home says "Loading…" instead of flashing empty values.
- **Fixes:** the dead AI-task source rows are gone; deleting a to-do or an AI task asks first; "Copied ✓" on code blocks.
- **Touch targets and labels:** 48 dp composer buttons; TalkBack names for the arrow and stepper buttons.
- **Motion:** new chat messages fade in once; reduced motion now also covers the splash.
- **Cleanup:** unused composables, icons and two unused libraries removed; one friendly "time ago" helper.
- No database change, no new permission or dependency. Not yet tested on a phone (checklist tests 81–90).

## [Internal 6.0-dev.1]: Personal AI Brain (2026-11-18; `build/internal/Abhify-6.0-dev.1.apk`, SHA-256 `a1f4567990bfd1ef5438c26d194c30fda646cd5674482bd1fb9e3eef8396adb0`)
versionName `5.4-internal.6.0-dev.1`, versionCode 5040099 (unchanged). Reports: [ABHIFY_6_0_BUILD_REPORT.md](ABHIFY_6_0_BUILD_REPORT.md), [ABHIFY_6_0_TEST_REPORT.md](ABHIFY_6_0_TEST_REPORT.md).
- **One brain for every turn** (`Brain.plan`): intent, which personal context is retrieved, mode behaviour, honest notices.
- **New modes that really behave differently:** ⚡ Quick answer, 🧠 Deep think, 🗺 Planning, 🎨 Creative (plus Coding, Learning, Career, Research). They change history, context, answer length, creativity and the reasoning structure. They never change which AI answers.
- **🔬 Research:** gathers recent news sources (online on, your choice) and cites them as [W1]… with dates.
- **Current events:** questions about current events get an honest notice instead of invented facts.
- **💡 Route advice:** a stronger configured model is suggested for hard problems, never switched to automatically.
- No database change, no new permission or dependency. Not yet tested on a phone.

## [Internal 5.6-dev.1 – dev.3]: search 2.0, reminders, news image fix, friendly AI, streaming voice, Neural Futuristic look (2026-11-18)
- `5.6-dev.1` `fa7aa2fe…6416` · `5.6-dev.2` `e1df68bc…620c` · `5.6-dev.3` `189e49e1…1921`. Details: [ABHIFY_5_6_BUILD_REPORT.md](ABHIFY_5_6_BUILD_REPORT.md).
- **Search filters:** type, date, project and sort; news and briefing results included.
- **Agent:** smarter duplicate to-dos.
- **Reminders:** optional, per to-do, with quiet hours and snooze.
- **Skills:** revision plan and learning path.
- **Settings:** provider statistics.
- **News:** the image refresh bug fixed at its root cause.
- **My Friendly AI:** follow-ups, earlier-turn summary, an honest personality.
- **Talk to Me:** speaks while answering, interrupts reliably, and understands Hindi and Hinglish commands.
- **Look and reliability:** near-black, violet and electric-cyan look; rate-limit cooldown.

## [Internal 5.5-dev.1]: context finder, agent workflows, skill trends (2026-11-18; `build/internal/Abhify-5.5-dev.1.apk`, SHA-256 `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e`)
Internal development build, not a release. versionName `5.4-internal.5.5-dev.1`, versionCode 5040099 (unchanged). Earlier APKs are unchanged. Reports: [ABHIFY_5_5_BUILD_REPORT.md](ABHIFY_5_5_BUILD_REPORT.md), [ABHIFY_5_5_TEST_REPORT.md](ABHIFY_5_5_TEST_REPORT.md).
- **Chat context finder:** answers from the phone's model about your own material now draw on your projects, to-dos, notes, knowledge, news and AI tasks, and list them as 🧭 sources. Online answers never receive them.
- **Agent:** new project buttons 🤖 Status & blockers, 🤖 Suggest priorities and 🤖 Related documents.
  - Possible duplicate to-dos are shown.
  - Changes happen only after "Approve N", are checked afterwards, and can be undone.
- **Today:** to-dos from recently updated projects come first when everything else is equal.
- **Skills:**
  - choose how many answers a level needs (3, 5 or 10);
  - a trend (improving / declining / steady) after 10 answers;
  - "Add a revision to-do" with a confirmation.
- **News:** "Also reported by N other sources" on stories covered elsewhere.
- **Search:** results are grouped by type, with counts.
- **Voice:** "summarise my project" and "what's blocking my project".
- No database change, no new permission or dependency. Not yet tested on a phone.

## [Internal 5.4-dev.1]: Personal AI Brain, Agent 2.0, productivity, briefing (2026-11-18; `build/internal/Abhify-5.4-dev.1.apk`, SHA-256 `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354`)
Internal development build, not a release: the version stays 5.4 (versionCode 5040099) with a `-dev.1` name suffix. `build/Abhify-5.4.apk` is unchanged. Report: [ABHIFY_NEXT_BUILD_REPORT.md](ABHIFY_NEXT_BUILD_REPORT.md).
- **Home → Today:** a daily plan from your real to-dos and project next actions, with reasons. Priority, blocked and estimate per to-do. Overdue only from stored dates.
- **Personal briefing:** morning, evening, weekly or on demand; sources shown; Skip; Listen. An optional daily reminder, off by default, with no content in the notification.
- **Agent 2.0:** 🤖 Find unfinished work in a project.
  - It proposes to-dos found in your notes (verified word-for-word) or as labelled suggestions, and creates only what you approve.
  - It verifies the result, and offers Undo and an audit log.
  - It uses a typed tool registry; sensitive tools are never run by the agent.
- **Skills:** a skill map from your quiz and MCQ answers (no score below 5 answers) with an adaptive next step.
- **News:** related stories, Compare sources (AI, labelled), and Learn this topic.
- **Search:** also finds projects and AI tasks, keeps recent searches, and hides memories while memory is off.
- **Voice:** "plan my day", "brief me", "weekly review", "find unfinished work in my project", "what do I have on …".
- **Discover:** Wikibooks is now a search link (Wikimedia blocks automated requests without contact details).
- No database change, no new permission or dependency. Not yet tested on a phone.

## [5.4]: Personal Voice Companion (2026-11-18; `build/Abhify-5.4.apk`, versionCode 5040099, SHA-256 `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330`)
Docs: [ABHIFY_PERSONAL_VOICE_ASSISTANT.md](ABHIFY_PERSONAL_VOICE_ASSISTANT.md). Installs over 5.3 (same certificate). Includes everything in 5.3.
- **Talk to Abhify:** Assistant → 🗣 Talk opens a hands-free conversation.
  - Live transcript, streamed answer, spoken reply, then it listens again.
  - Tap to interrupt; End stops everything.
  - The neural core shows Listening (real mic level), Processing, Searching, Thinking, Responding, Speaking, Interrupted, Offline.
- **Voice commands for Abhify itself**, in English and Hindi:
  - open any destination or screen, the last conversation, a new chat, search, course discovery;
  - read-only answers (what to do today, project status, model status, study progress, "what is this screen");
  - to-dos and notes with Confirm and Undo.
  - Deleting or changing data is never done by voice.
- **Settings → Voice & Conversation:**
  - the speech engine's real voices with preview; female by default where the engine labels one;
  - tone, speed (now saved), language (English/Hindi/both/auto), response mode, interruption, keep listening, screen help;
  - spoken notifications (off by default, quiet hours).
- **Privacy → Conversation data:** where conversations are stored (this phone only), counts, and confirmed Clear for conversations, memories, or all conversational data.
- Not built (platform limits, explained in the app): wake phrase, automatic voice barge-in, microphone/speaker pickers.
- No database change, no new permission or dependency. Not yet tested on a phone.

## [5.3]: Neural interface, Study Discover, Hindi news (2026-11-18; `build/Abhify-5.3.apk`, versionCode 5030099, SHA-256 `778e5c504e410ada2f582af89492f64d92a324f2220f4344975d01ecc5b57bd5`)
Report: [ABHIFY_5_2_IMPLEMENTATION_REPORT.md](ABHIFY_5_2_IMPLEMENTATION_REPORT.md). Includes everything in 5.1. Installs over 5.1 (same certificate).
- **Five destinations:** Home · **Assistant** · Study · News · More.
- **More** is grouped Knowledge · Workspace · Career · AI & System · Settings & Privacy. AI models, providers, memory, Knowledge Center, connection and sync now open from More; Settings keeps the settings themselves.
- **Neural core:** new **Searching** (while your documents are searched) and **Offline** states, plus a light neural layer. It is still with reduced motion.
- **Home:** active projects with their next step, learning in progress, knowledge status, the last saved news with its time, and a Discover shortcut.
- **Study → Discover:**
  - real courses and books for any topic from MIT OpenCourseWare, OpenStax and Wikibooks (live), with cost only as the source states it, plus links to search NPTEL, SWAYAM, freeCodeCamp, Khan Academy, Coursera, edX, Microsoft Learn, YouTube and official docs;
  - Save to Knowledge, and Make a learning path (AI-generated, in Plans).
- **Study → MCQs and Questions:** the same screens as in Career.
- **News:**
  - English / हिन्दी / Both, with 20 verified Hindi publisher feeds and Google News in Hindi;
  - "Translate (AI)" per story, labelled as an AI translation;
  - Hindi read-aloud uses a Hindi voice, or explains how to install one.
- No database change. Not yet tested on a phone.

## [5.1]: Personal Intelligence (2026-11-17; `build/Abhify-5.1.apk`, versionCode 5010099, SHA-256 `659053fcd7ce8cb7f7ca0bae019d7e4112506c13ae31cb95b49938f1dcb0cd9f`)
Includes the Local RAG / Knowledge Center work below. Debug-signed with the same certificate as 5.0, so it installs over it.
Architecture: [ABHIFY_5_1_ARCHITECTURE.md](ABHIFY_5_1_ARCHITECTURE.md) · report and measurements: [ABHIFY_5_1_IMPLEMENTATION_REPORT.md](ABHIFY_5_1_IMPLEMENTATION_REPORT.md).
- **Chat focus** (chip in the chat bar):
  - Options: Auto, General, Coding, Learning, Career, Research.
  - Auto detects the focus per message (0.95 accuracy on 60 labelled messages; ordinary messages stay ordinary, 0/15).
  - Answers are formatted for the focus (coding: root cause, fix, verification; learning: explanation, example, practice; research: sources and uncertainty), and the focus used is shown under each answer.
- **Answer style:** detail, technical depth, explanation style and preferred language. The defaults change nothing.
- **Project status:**
  - Record a project's objective, technology, status, milestones, open issues, next actions and decisions.
  - "Where you left off" is built from those records only (never invented). "Continue in chat" puts the question in the box without sending it.
  - Project chats get the status. A question that names a project gets its status, on the phone only.
- **Suggestions on Home:** up to three, from your projects, overdue to-dos, study, corrections to review and failed AI tasks. Dismiss each one, or turn them off in Settings. No notifications, no background AI.
- **Online context** dialog explains where the next answer goes and what goes with it, plus a hint (never a switch) when a heavy task would run on the phone.
- **Memory:** new categories, Professional profile and Corrections. Fewer unrelated memories in answers: a weak match next to a strong one is left out (measured precision 0.765 → 1.0, recall unchanged at 1.0).
- **Continue working on Home:** your last chat, the project you last updated with its next action, an interrupted or not-yet-run AI task, and a study plan in progress (last 14 days). It has its own switch in Settings.
- No database change, no new permission or dependency. Not yet tested on a phone.

## [Unreleased]: Local RAG, Knowledge Center, controlled learning (2026-11-17; version unchanged at 5.0, not built as a release)
Details and measurements: [ABHIFY_LOCAL_RAG.md](ABHIFY_LOCAL_RAG.md).
- **Semantic search on the phone (optional):**
  - bge-small-en-v1.5 (36 MB, MIT), downloaded only when you tap Download; checksum-verified; runs through the same llama.cpp engine as Qwen.
  - Hybrid retrieval: keyword (BM25) + meaning, fused. On the evaluation set, Recall@5 went from 0.87 to 0.98.
  - Pauses itself on low memory, battery saver or heat. Incremental index (only new or changed passages).
- **Knowledge Chat:**
  - Modes: Normal, Auto (default: only questions about your own material), Knowledge (every question). A chip in chat cycles them.
  - Searches all documents plus saved Knowledge items, with [S1] citations, or says "Nothing in your knowledge matched".
  - "Online answers may use my knowledge" (off by default) plus per-chat consent; otherwise your knowledge stays on the phone.
- **Knowledge Center** (Settings → AI): mode, online switch, counts, semantic model (download, pause, on/off, remove), index build with pause, per-document reindex, clear vectors.
- **Controlled learning:** ✎ Correct under an answer → a candidate you approve or reject; approved changes are logged with the old value and can be reverted.
- **Backup:** tasks, projects, career MCQs and generated questions are now included ("App files"); restore never overwrites existing files.
- Citation instruction now tells the model never to follow instructions found inside sources.
- No database change. Not yet tested on a phone.

## [5.0]: Career preparation, News reading, refresh (2026-11-10; `build/Abhify-5.0.apk`, versionCode 5000099)
Includes everything from the stability, unified-workspace, Task Runner and News previews below (4.6.1 previews).
- **Career, in a new order:** Dashboard · Resumes · Question Bank · MCQs · Jobs · Match. Back returns to the Dashboard.
- **Question Bank:**
  - **Bank:** the curated questions, with refresh and pull-to-refresh.
  - **Generate:** technical, resume-based, project-based, conceptual, scenario or behavioural questions from your resume, its skills or projects, or a topic; kept apart from the curated bank.
  - **Practise:** the existing interview practice, unchanged.
- **MCQs:**
  - **Sources:** your resume, its skills or projects, a topic, a document, or a mix.
  - **Batches on demand:** Generate more, Refresh unanswered, with duplicates filtered and a new angle each batch.
  - **Answering:** a tap answers; Correct or Incorrect with the right option and explanation; Review mode.
  - **Labels:** every answer is labelled AI-generated, with the supporting quote from your resume only when it really is there.
  - **Progress:** session, per resume/topic and overall.
- **News:**
  - **In-app reader:** tapping a story opens it in the app (summary of its own text), never the website.
  - **Read more:** the first tap shows the full text in the app; the second opens the original.
  - **Refresh:** reliable refresh with status, and pull-to-refresh.
- **Career dashboard:** the active resume's skills, MCQ progress, quick actions.
- No database change (new data in JSON files next to the database). Not yet tested on a phone.

## [Unreleased]: Stability milestone (build `build/stability-preview/Abhify-4.6.1-stability-preview-debug.apk`, version unchanged at 4.6.1)
- **Fixed: New private chat crashed.**
  - Cancel and New chat called the AI engine's native library before it was loaded.
  - This also fixes Cancel in the Task Runner, Study and News before the first on-device answer.
- **Fixed: Document summary, Document to study plan, Quiz from document.**
  - Consent is asked once per task, not once per step.
  - Documents inside workspaces work.
  - The quiz reader tolerates small-model output, with a line format for the phone.
  - Richer study plan; quiz settings (3/5/10 questions, difficulty); score; Share.
- **News Listen:** reads the briefing on screen with Android's text-to-speech. Pause, resume, previous/next item, stop, replay and speed. No new AI request.
- **Settings:** now holds AI, models & performance, system and diagnostics (moved from More). More keeps Workspace, Documents, Data, and Settings/Account/About.
- **Projects:** built on the existing workspaces:
  - instructions, archive/restore, export;
  - linked documents;
  - project chat with its instructions (memory switch);
  - AI tasks and study plans on the project's documents.
  - Delete keeps documents. No database change.
- **Voice:** the phone model loads while you speak (when it will answer).
- Details: [ABHIFY_MASTER_ENHANCEMENT_REPORT.md](ABHIFY_MASTER_ENHANCEMENT_REPORT.md). Not yet tested on a phone.

## [Unreleased]: Unified Aurora workspace (build `build/unified/Abhify-4.6.1-unified-preview-debug.apk`, version unchanged at 4.6.1)
- **Brand launcher:** tap the Abhify logo in the header for every destination, grouped like the desktop:
  - Workspace: Home, Chat, AI Task Runner, Study, News, Documents, Knowledge, Memory;
  - AI System: AI Models, Performance, System;
  - Application: Settings, Account, About.
  - Existing screens only; opening it starts nothing; the bottom bar and More are unchanged.
- **Build folder consolidated:** superseded intermediate preview APKs removed after the unified build was verified; the 4.5.1 rollback is kept (`build/unified/CLEANUP_REPORT.md`).
- Details: [UNIFIED_AURORA_WORKSPACE.md](UNIFIED_AURORA_WORKSPACE.md).

## [Unreleased]: AI Task Runner (preview build `Abhify-4.6.1-tasks-preview-debug.apk`, version unchanged at 4.6.1)
Development milestone; versions not changed. Database: unchanged (v17); tasks are kept in `tasks.json`. Details: [AI_TASK_RUNNER.md](AI_TASK_RUNNER.md).
- **AI Task Runner** (Home card; More → Workspace):
  - five workflows: Document summary, Document to study plan, Quiz from document, News briefing, Custom task;
  - sources: documents, Knowledge entries, News Center stories, pasted text;
  - steps run one at a time through the app's AI path (your AI mode and phone model; consent before document text goes online);
  - progress, cancel, retry (whole task or one step), copy;
  - an interactive quiz with explanations and source quotes checked against the source;
  - **Save to Knowledge** and **Add flashcards** only after confirmation;
  - source references kept with every result;
  - never personal memory or chat history.
- If you cancel the "send online?" question, the AI task now reports "Cancelled" instead of waiting forever (found while building the Task Runner).

## [Unreleased]: News Center, Qwen 0.5B fix (preview build `Abhify-4.6.1-news-preview-debug.apk`, version unchanged at 4.6.1)
Versions were not changed (awaiting your approval). Database: unchanged (v17). No new dependencies, no new permissions.
- **Navigation:** the bottom bar is now Home · Chat · Study · **News** · More. **Documents moved to More → Workspace**, unchanged otherwise. Study's small news section is replaced by the News tab.
- **News Center** ([NEWS_CENTER.md](NEWS_CENTER.md)):
  - 15 categories plus custom topics, from publisher RSS feeds and Google News (no API keys);
  - featured story and article cards with the publisher's image (placeholder otherwise), publisher, publication time and description;
  - **Read more** opens the original link;
  - search (last 7 days, dates shown), refresh, offline copy, loading/error/empty states;
  - source manager: city, edition, language, For You categories, topics, publishers to prioritize or hide;
  - **AI briefing**, Summarize and Explain, labelled as AI with source links;
  - Bookmark to Knowledge and Share.
  - **Never added to personal memory** (tested).
- **Qwen2.5 0.5B fix** ([QWEN_05B_INVESTIGATION.md](QWEN_05B_INVESTIGATION.md)):
  - GPU use is now validated **per model**; an unchecked model no longer inherits the 1.5B's GPU result.
  - The model picker shows real errors with **Try again**, and **Get it** for models not on the phone.
- **Action Center:** adds **Checklist** and **Rewrite clearly**.

: AI Action Center and on-phone performance (2026-11-09)
- **AI Action Center:** **✨ Actions** on every answer (Summarize, Explain simply, Key points, Translate into 6 languages, Quiz me, Flashcards).
  - Each one is a follow-up sent through the normal chat: same model choice, consent and history; never while an answer is being written.
  - Same wording as the web app (`chat/AiActions.kt`).
- **Performance on this phone** (AI models screen): app memory now and at peak (from the engine), free RAM (Android), the last answer's tokens/s (measured from the stream), threads and backend. A dash is shown when there's nothing measured.

## [4.6.0]: "Aurora" colourful redesign (2026-11-09)
Design system: [AURORA_DESIGN_SYSTEM.md](AURORA_DESIGN_SYSTEM.md). Database: unchanged (v17). No new dependencies, no new permissions.
- **New identity:**
  - a deep-indigo night (warm lavender-white in light mode) lit by a static aurora background;
  - electric-violet brand, and a colour per feature (Chat violet, Study mint, Documents azure, Career coral, Knowledge amber, Memory orchid, System indigo);
  - softer 20 dp cards.
- **The AI orb:** the assistant's animated identity, showing its real state:
  - ready, listening, thinking, writing, needs attention;
  - still with reduced motion, and its state is spoken.
  - It appears on Home, in the chat's empty state, as the avatar on answers (animated only on the one being written), in the tutor, on Welcome and in the phone-AI card.
- **Navigation:**
  - a new bottom bar where the selected tab grows a pill in its feature colour (animated);
  - pages glide in;
  - More's groups and every sub-page wear their feature colour (gradient icon tiles, a coloured title icon).
- **Home:** a deep-aurora hero (orb, greeting, live state, Ask box, model chip), then "Your spaces": six colourful tiles, each with one real fact. Below them: recent chats, a quiz-score ring, the phone AI card, saved prompts, Drive, shortcuts.
- **Chat:**
  - an orb welcome;
  - colour-coded starter cards;
  - orb avatars;
  - softer bubbles: yours use the deep aurora, so white text stays at least 6:1.
- **Study:**
  - Tutor gets an orb header and a level selector;
  - Plans get progress rings, a plan hero and a lesson checklist;
  - **Flashcards flip** (3D) with colour-coded ratings and a deck progress bar;
  - the quiz has an animated progress bar;
  - stat tiles are colour-coded.
- **Everywhere:** press feedback on cards and buttons; primary buttons use the violet → purple gradient; feature-coloured section tabs; file-type colours in Documents.

: premium redesign pass 2, page layouts (2026-11-09)
Database: unchanged (v17). No new dependencies, no new permissions. **Not device-tested** (no phone was connected).
- **Page kit (new shared components):**
  - page header, search field, segmented section tabs with icons, stat tiles, settings groups;
  - busy and error banners, action sheet, type tiles, list/grid toggle, action tiles.
- **Documents:**
  - header with count and an **Add** button, then search and sort, plus a **list/grid** toggle (remembered);
  - document cards with a type icon and **status tags** ("Not indexed yet", warnings);
  - a focused **Ask your documents** card;
  - the 13 per-document actions moved from inline button rows into a grouped **action sheet** (Use / View / Organize / Delete); every action is kept;
  - Resume Assistant, PDF pages and the privacy notes are grouped under "More tools".
- **Study:**
  - segmented tabs with icons (Overview, Tutor, Plans, Cards, Quiz, News);
  - **Overview** has a "Recommended next" card, four stat tiles, then the main action;
  - "Create study material" with source tabs and **action tiles** (Explain, Summary, Quiz, Flashcards);
  - study history with type icons.
- **Quiz:** stat tiles, a progress bar, lettered option cards with correct/incorrect states, an explanation panel and a primary **Next** button.
- **Career:** dashboard with six stat tiles, an overdue alert, an active-resume card and three action tiles (Match, Practise, Track). Segmented tabs.
- **Knowledge:** segmented tabs; My Knowledge gets a header with a count, the new search field, and a cleaner item view with a back arrow and metadata. Workspaces get segmented tabs with icons.
- **Settings pages:**
  - 64 card sections across the settings, Drive, Backup, Storage, Connection, Providers, Resume, Setup and Career pages became titled **settings groups**.
  - "Settings" is renamed **Answer style & personalization** (as in More).
  - Theme and AI-mode selectors are segmented controls.
- **Setup:** segmented step indicator (read aloud as "Step x of y"), centred readable column, raised bottom action bar.
- **Welcome:** the three promises (AI on this phone, private by default, online optional) with icons.

: premium redesign (phases 1–4) and chat model choice (2026-11-06)
Design: [PREMIUM_REDESIGN_SPEC.md](PREMIUM_REDESIGN_SPEC.md). Model choice: [CHAT_MODEL_SELECTION.md](CHAT_MODEL_SELECTION.md). Database: unchanged (v17). No new dependencies.
- **New look:**
  - The palette is shared with the desktop app: violet `#635BFF`, navy `#171827`, neutral light surfaces, cyan only as a small accent; the pink accent and the glow backgrounds are retired.
  - Opaque cards with hairlines.
  - Solid violet primary buttons; the brand gradient is kept for the launch, the Home "Ask" card, the send button and your chat bubbles.
  - Updated type scale; motion tokens with reduced-motion support.
- **Icons:** every navigation and settings row, empty state and status chip uses official Material outlined icons (49 vector drawables, Apache-2.0) instead of emoji.
- **Navigation:**
  - New bottom bar: **Home · Chat · Study · Documents · More**.
  - **More** replaces the drawer and the Settings tab. It is grouped into Workspace, AI, Data and System; every page appears once, and the old hub's inline settings are kept.
  - Career and Knowledge open from More or Home.
  - Back returns to the parent tab first.
- **Home (new):** a bento dashboard built from your real data:
  - an "Ask Abhify.AI" box (starts a new chat);
  - continue your last chat;
  - study (due cards, quiz score);
  - recent chats, documents, the phone's AI status, saved prompts, Google Drive (when connected);
  - shortcuts.
- **Launch:**
  - The system splash follows the phone's light/dark theme.
  - The reveal (logo, one ring sweep, name and tagline) takes at most 900 ms, uses the app's theme and can be skipped with a tap.
  - With "Remove animations" on, a static logo shows for 300 ms.
  - It never waits for Drive, a server or the model.
- **Chat:**
  - **Model chip and model sheet** (switch between installed phone models, Auto, and providers you configured).
  - Composer with vector icons (voice, attach, send).
  - A New chat button in the header.
- **Models screen:** shows "Also installed on this phone" with **Use for chat**. An installed model is no longer offered for download again (bug fix).

## [4.4.0]: chat, documents and personalization gaps, cleanup (2026-11-06)
- **Chat:**
  - Edit an **earlier** question: it's sent in a new chat ("… (edited)"); the original stays untouched.
  - **Saved prompts** (📋 Prompts in the chat bar): save, edit, delete, insert.
  - Export a conversation as **PDF** (History → Save .pdf).
- **AI on the phone:** **Creativity** (Precise 0.2 / Balanced 0.3 = previous default / Creative 0.7).
- **Documents:**
  - Sort by newest, name, size or type.
  - "Not indexed yet" label (Re-index fixes it).
  - **PDF page viewer** with 1×, 1.5× and 2× zoom.
- **Appearance:**
  - **Text size** (Small, Default, Large, Largest), on top of the system font size.
  - **Vector icons** (official Material outlined) in the bottom bar and header.
- **Database:** v16 → v17 (`saved_prompts`, additive). Saved prompts are included in "Notes and tasks" backups. Creativity and text size are in the Settings backup.
- **Fix:** backup export and restore now skip a table the database doesn't have, instead of failing.
- **Project:** `.gitignore` Android entries; build-folder cleanup; old reports archived to `docs/archive/`; monorepo README; new architecture, setup, testing and phase docs.

## [4.3.1]: UI polish (2026-11-06)
- Dialog text scrolls when long (23 dialogs); dialog button rows wrap.
- The launch screen always uses the dark palette (matches the system splash).
- Decorative emoji are hidden from TalkBack.

## [4.3.0]: UI/UX pass (2026-11-06)
- Branded launch screen ("Abhify.AI", "Learn AI. Think Deeper. Build the Future").
- One page layout and design tokens.
- Markdown fixes: line breaks, quotes, rules, headings 1–6, nested and task lists, links, strikethrough, escapes, limited HTML.
- Document viewer rendered lazily; empty states; accent contrast in light mode.

## [4.2.0]: Google Drive for every user and Local Mode
- Per-account Drive folders (`My Drive/Abhify.AI/{Backups,Documents,Imports,Exports}`), account chooser, optional confirmed migration.
- Connection mode screen; the server is optional.
- DB v16.

## [4.1.0] to [3.5.0]: roadmap
- Background Drive uploads and scheduled backups (3.5).
- Semantic search infrastructure (3.6).
- Career maintenance (3.7).
- Advanced memory (3.8).
- Spaced-repetition study (3.9).
- Unified workspace and search (4.0).
- Fine-tuning research and safeguards (4.1).
- DB v10–v15.

Details: [V3_5_TO_V4_1_IMPLEMENTATION_PLAN.md](V3_5_TO_V4_1_IMPLEMENTATION_PLAN.md) and the per-version docs.

## [3.4.0] and earlier
- **3.4:** personal memory, Drive, RAG, unified chat.
- **3.3:** Career Copilot 2.0.
- **3.2:** career, study, knowledge.
- **3.1:** OCR, résumé assistant, setup.
- **3.0:** documents, learn and work.
- **2.0 RC.2–RC.4:** accounts, sync, providers.

See [V3.4_RELEASE_NOTES.md](V3.4_RELEASE_NOTES.md), [V3_RELEASE_NOTES.md](V3_RELEASE_NOTES.md) and [archive/](archive/README.md).
