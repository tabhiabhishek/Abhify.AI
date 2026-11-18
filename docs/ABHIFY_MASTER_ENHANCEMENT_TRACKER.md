# Abhify.AI master enhancement tracker

Last update: 2026-11-10 (career milestone; Android 5.0). New private chat confirmed on a phone by the owner. Statuses: NOT_STARTED · AUDITING · IMPLEMENTING · TESTING · COMPLETED · BLOCKED · DEFERRED.

**Rules for this tracker:**
- **COMPLETED** means implemented and validated by tests run in this session.
- **Physical-device validation:** none was possible, because no Android device was connected.
- **Code inspection** is never counted as testing.

## Stage 1: audit and stabilization (both prompts)
| # | Item | Status | Root cause / scope | Files | Tests | Remaining |
|---|---|---|---|---|---|---|
| 0 | Repository audit and baseline | COMPLETED | baseline: backend 347+1 skipped, Android 275/0/4, live Task Runner reproduction | `docs/ABHIFY_COMPREHENSIVE_ENHANCEMENT_AUDIT.md`, `docs/ABHIFY_MASTER_ENHANCEMENT_AUDIT.md` | – | – |
| 1 | Document summary | COMPLETED | Android: consent asked on every step; documents in workspaces not found; cancel crash | `task_runner.py`, `TaskRunner.kt`, `TaskState.kt`, `AppViewModel.kt` | live 8/8 summaries; unit tests | phone run not possible |
| 2 | Document to study plan | COMPLETED | same as 1; plan too generic; no length guard | same | live 8/8 with all 6 sections | phone run not possible |
| 3 | Quiz from document | COMPLETED | strict JSON parser (one unquoted string rejected the whole quiz); phone models and JSON | same, plus `app.js` and `TaskScreens.kt` | live 8/8 quizzes, 39/40 quotes verbatim; parser tests on the real failing output | answer correctness depends on the model |
| 4 | News briefing regression | COMPLETED | – | – | web E2E briefing with sources; Android unit tests | – |
| 5 | Android New private chat crash | COMPLETED (confirmed on a phone by the owner, 2026-11-10) | `LlmEngine.cancel()` called the JNI `nativeCancel` before `libabhify_llm` was loaded: `UnsatisfiedLinkError` | `llm/LlmEngine.kt` | `PrivateChatCrashTest` (3), including a reproduction of the error | **device validation not possible** |
| 6 | Chat microphone latency | COMPLETED (desktop measured) | cold model load 15.4 s; answer not streamed (4–15 s); cold Whisper 7.9 s | `agent.py`, `main.py`, `system_service.py`, `app.js`, `AppViewModel.kt` | before/after measurements; backend tests; browser E2E | Android not measured (no device); the cold model load remains |
| 7 | Settings navigation consolidation | COMPLETED | AI and System spread over More, Settings and the sidebar | `V3Screens.kt`, `ManageScreens.kt`, `Aurora.kt`, `index.html`, `app.js` | Android `NavigationSettingsTest`; browser E2E | device check |
| 8 | News read aloud | COMPLETED (web live; Android unit-tested) | missing feature | `app.js`, `Voice.kt`, `NewsScreens.kt`, `NewsState.kt` | Node harness (4), Edge live, Android `NewsAudioTest` | Android background playback not claimed |

## Career milestone (2026-11-10; Android 5.0)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| Career order: Dashboard, Resumes, Question Bank, MCQs, Jobs, Match (Android); Question Bank before MCQs (web) | COMPLETED | Android `CareerNavigationTest`; browser `web_tab_labels` | phone check |
| Resume-based interview questions (Question Bank → Generate; Android) | COMPLETED (unit-tested) | `CareerQuestionsTest` | phone check; not on web |
| Personalised MCQs: resume, skills, projects, topic, document, mixed; batches; deduplication; honest labels | COMPLETED (web live; Android unit-tested) | `McqPracticeTest` (8), backend 9, browser 6 | phone check |
| Immediate feedback, explanation, review mode | COMPLETED | browser + unit | – |
| Question Bank refresh / MCQ refresh / News refresh | COMPLETED | browser + unit | – |
| Pull-to-refresh (News, Question Bank, MCQs) | COMPLETED (web live; Android unit/contract) | browser 2, Android 1 | Android gesture on a phone |
| News in-app reading (summary, staged Read more, per-story state) | COMPLETED (web live; Android unit-tested) | backend 14, browser 8, Android 4 | phone check |
| Career dashboard: active resume skills, MCQ progress, quick actions | COMPLETED | contract | – |
| Resumes / Jobs / Match on the web | NOT_STARTED (Android only) | – | needs a desktop resume manager |

## Next build: Personal AI ecosystem (2026-11-18; internal `build/internal/Abhify-5.4-dev.1.apk`)
Status per area: [ABHIFY_IMPLEMENTATION_STATUS.md](ABHIFY_IMPLEMENTATION_STATUS.md) · audit (P0–P3): [ABHIFY_COMPLETE_PROJECT_AUDIT.md](ABHIFY_COMPLETE_PROJECT_AUDIT.md)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| Context resolver (Personal AI Brain) | COMPLETED (Android); desktop search/briefing | `ContextResolverTest`, `test_brain.py` | inject into every chat prompt |
| Agent 2.0 (tool registry, project review, approval, verify, undo, audit) | COMPLETED (Android, 1 workflow) | `AgentToolsTest` | more workflows; desktop |
| Productivity: daily plan, priority/blocked, weekly review | COMPLETED (Android) | `DailyPlannerTest` | per-to-do reminders |
| Personal briefing (+ optional reminder) | COMPLETED (Android); desktop on-demand | `BriefingTest`, `test_brain.py` | – |
| Skill graph + adaptive next step | COMPLETED (Android) | `SkillGraphTest` | desktop |
| News intelligence (related, compare, learn) | COMPLETED (Android) | `NewsIntelTest` | desktop |
| Universal search (+ projects, AI tasks, recent; permissions) | COMPLETED (both) | `NextBuildIntegrationTest`, `test_brain.py`, browser | semantic hits |
| Desktop five destinations, Discover, News language | COMPLETED | browser 11/11 | voice, agent |
| Commit / keystore backup / release key | NEEDS OWNER APPROVAL | – | – |

## Personal Voice Companion (2026-11-18; Android 5.4 build `build/Abhify-5.4.apk`)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| Phase 0 audit + plan | COMPLETED | – | – |
| Voice & Conversation settings (real voices, preview, tone, speed, language, response mode, interruption) | COMPLETED (Android) | `VoiceSettingsTest` | phone check |
| Hands-free conversation loop, tap-to-interrupt, live transcript, waveform | COMPLETED (Android) | `VoiceCompanionTest` | phone check; latency measurements |
| Wake phrase | NOT BUILT (platform limits documented; tap-to-talk kept) | `noWakeWordIsFaked` | – |
| App Action Registry (navigation, read-only, reversible with confirm/undo, sensitive → confirmation screen) | COMPLETED (Android) | `AppActionsTest` | phone check |
| Conversation data controls (Privacy) | COMPLETED (Android) | `VoiceCompanionTest` | phone check |
| Spoken recommendations, screen help | COMPLETED (Android) | `AppActionsTest` | phone check |
| Spoken notifications (opt-in, quiet hours) | COMPLETED (Android) | `VoiceCompanionTest` | phone check |
| Desktop/web voice companion parity | NOT_STARTED | – | next |

## 5.2 product evolution (2026-11-18; Android 5.3 build `build/Abhify-5.3.apk`)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| Phase 0 audit (inventory, five-destination map, sources research, plan) | COMPLETED | – | – |
| Neural design system + core states Searching/Offline | COMPLETED (Android) | `NeuralCoreTest` | web orb states |
| Five destinations (Assistant) + More grouped | COMPLETED (Android) | `FiveDestinationsTest`, `NavigationSettingsTest` | web navigation |
| Home command center | COMPLETED (Android) | contract | phone check |
| Study Discover (live MIT OCW, OpenStax, Wikibooks + provider search) + learning path + assessments in Study | COMPLETED (Android) | `CourseDiscoveryTest` | web; lesson reader, goals/streak |
| News English/Hindi/Both + AI translation + Hindi voice | COMPLETED (Android; backend filtering) | `MultilingualNewsTest`, `test_news_multilingual.py` | web selector, source comparison, briefing schedule |
| Privacy Center, storage insights, cross-module "Learn this topic" | NOT_STARTED | – | next |

## 5.1 Personal Intelligence (2026-11-17; Android 5.1 build `build/Abhify-5.1.apk`)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| Chat focus (Auto/General/Coding/Learning/Career/Research) + adaptive format | COMPLETED | `FocusDetectionEvalTest` (accuracy 0.95, 0/15 false positives) | phone check |
| Response preferences (detail, depth, style, language) | COMPLETED | `FocusDetectionEvalTest` | phone check |
| Project status + "Where you left off" + Continue in chat | COMPLETED | `ProjectIntelligenceTest` | phone check |
| Project status from a project named in the question (local only) | COMPLETED | `RoutingTransparencyTest` | – |
| Home suggestions (real records, dismiss, off switch) | COMPLETED | `ProactiveSuggestionsTest` | phone check |
| Routing explanation in Online context | COMPLETED | `RoutingTransparencyTest` | – |
| Memory categories (Professional profile, Corrections); relevance measured and tuned | COMPLETED | `MemoryRelevanceEvalTest` (recall 1.0, precision 0.765 → 1.0) | confirm on real memories |
| Home "Continue working" | COMPLETED | `ContinueWorkingTest` | phone check |
| Desktop parity (focus, project status, answer style, Home continue working + suggestions) | COMPLETED (backend + web; live Ollama + browser 12/12) | `test_intelligence.py` (10) | – |
| Auto-index on import · per-project online · Android web search | DEFERRED (plans in the 5.1 report) | – | owner approval |

## Local RAG milestone (2026-11-17; Android, version unchanged)
| Item | Status | Tests | Remaining |
|---|---|---|---|
| On-phone embeddings (bge-small, download on request, run conditions) | COMPLETED (unit/contract; native build OK) | `SemanticModelTest`, eval harness | phone: download, speed, memory |
| Hybrid retrieval in chat and document Q&A; incremental index | COMPLETED | `HybridRetrievalTest`, eval R@5 0.98 | phone timing |
| Knowledge Chat modes, knowledge items, "nothing found" | COMPLETED | `KnowledgeChatTest`, `KnowledgePrivacyTest` | phone check |
| Online use of knowledge: switch + consent | COMPLETED | `KnowledgePrivacyTest` | – |
| Knowledge Center screen | COMPLETED | compile + lint | phone check |
| Controlled learning (candidates, approve, reject, revert) | COMPLETED | `ControlledLearningTest` | phone check |
| Backup of app JSON files (Android + desktop) | COMPLETED | `AppFilesBackupTest` | restore between two phones |
| Command palette finds projects and tasks (web) | COMPLETED | – (browser check) | – |
| Home "continue working" | DEFERRED to 5.2 (plan in the 5.1 report) | – | – |
| Per-project "allow online" collections; auto-index on import | NOT_STARTED | – | – |

## The 20 enhancement areas
| No. | Area | Status | Implemented in this session | Tests | Remaining |
|---|---|---|---|---|---|
| 1 | Home AI dashboard | DEFERRED | Projects chip on Home (web); existing bento and quick actions unchanged | browser E2E | "continue working", task overview and project cards |
| 2 | Chat | IMPLEMENTING (partly COMPLETED) | streamed answers with Stop (web), project chat, private-chat fix, voice warm-up | backend + browser | rename, archive and search in web chat; long-context summarization |
| 3 | AI Task Runner | COMPLETED for the reported bugs; templates DEFERRED | fixes 1–3, quiz settings, export/share, project source, score | backend, Android, browser | new templates (Q&A, comparison, meeting notes); multi-document selection beyond projects |
| 4 | Study | IMPLEMENTING | project study plan (Android: preselected documents); document quiz with score and progress (web); **career MCQs and Question Bank (career milestone)** | browser, Android | adaptive weak-topic practice; interview follow-ups |
| 5 | News Center and audio | COMPLETED (audio, refresh, in-app reading) | Listen with all controls; reliable refresh and pull-to-refresh; in-app reader with staged Read more, both platforms | Node, Edge, Android, backend | news-to-project association; phone check |
| 6 | Documents and knowledge | DEFERRED | documents inside workspaces now usable by tasks (Android) | Android | diagnostics page, retrieval filters, large-document handling |
| 7 | Memory | DEFERRED | per-project memory switch (both) | backend, Android | duplicate and conflict suggestions |
| 8 | AI models | DEFERRED | – | – | model control centre additions; routing (needs your preferences) |
| 9 | Performance | IMPLEMENTING | prompt time added to inference stats; voice warm-up; measured pipeline | backend | resource warnings |
| 10 | System diagnostics | DEFERRED | diagnostics moved into Settings (Android) | Android | read-only "run diagnostics" checklist |
| 11 | Settings | COMPLETED | sections on both platforms (item 7) | Android, browser | – |
| 12 | More tab | COMPLETED | crash fixed; AI/System moved out; Projects; Application group | Android | device check |
| 13 | Project Workspace | COMPLETED (web live; Android unit-tested) | both platforms, no schema change | backend, browser, Android | see `docs/ABHIFY_PROJECT_WORKSPACE.md` |
| 14 | Command palette and search | IMPLEMENTING | Projects in the palette and launcher | browser | search over projects, chats and tasks |
| 15 | Android | IMPLEMENTING | items 1–8 and 13 | 294 unit tests, lint, build | **physical device not available** |
| 16 | Desktop/web | IMPLEMENTING | streaming, projects, audio, settings, one page at a time, `start.bat` | 43 + 17 + 11 + 25 browser checks | split-panel workspace |
| 17 | Privacy and security | AUDITING | project isolation; fenced document text; logs without content; new routes local-only or owner-only | backend tests | full security review |
| 18 | Backup and restore | NOT_STARTED | – | – | `projects.json` should join the backup set (needs a decision; see the report) |
| 19 | AI evaluation and prompt lab | NOT_STARTED | the live reproduction scripts (scratchpad) are a starting point | – | an in-repo evaluation harness |
| 20 | Automation | NOT_STARTED | – | – | after task reliability is proven on a device |

## Cross-feature integrations
| # | Integration | Status |
|---|---|---|
| 1 | Projects + documents | COMPLETED |
| 2 | Projects + chat | COMPLETED |
| 3 | Projects + AI tasks | COMPLETED |
| 4 | Projects + Study | COMPLETED (Android: plan builder; web: Task Runner study plan and quiz) |
| 5 | Knowledge + chat | existing (unchanged) |
| 6 | Knowledge + Task Runner | existing; Android now includes workspace documents |
| 7 | Knowledge + Study | existing |
| 8 | News + audio | COMPLETED |
| 9 | AI models + Task Runner | existing (the chosen model; schema output on the same model) |
| 10 | Performance + chat | IMPLEMENTING (prompt and load seconds per answer) |
| 11 | Settings + System | COMPLETED |
| 12 | Command palette + workspace | IMPLEMENTING (Projects only) |
