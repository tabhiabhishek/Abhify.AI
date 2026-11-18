# Abhify.AI 6.1: audit, changes and status (2026-11-18)

**Related documents:**
- **Baseline:** [ABHIFY_6_1_BASELINE.md](ABHIFY_6_1_BASELINE.md)
- **Web and desktop details:** [ABHIFY_6_1_WEB_DESKTOP_REPORT.md](ABHIFY_6_1_WEB_DESKTOP_REPORT.md)
- **Design specification:** [ABHIFY_6_1_DESIGN_SPEC.md](ABHIFY_6_1_DESIGN_SPEC.md)
- **Cleanup and APK manifest:** [ABHIFY_6_1_CLEANUP_MANIFEST.md](ABHIFY_6_1_CLEANUP_MANIFEST.md)
- **Build:** [ABHIFY_6_1_BUILD_REPORT.md](ABHIFY_6_1_BUILD_REPORT.md)

**Nothing in 6.1 was tested on a phone or an Android emulator.** No emulator image is installed and adb can't reach the phone.

## 1. How the audit was done
- **Android:** the code was read screen by screen. Every finding was confirmed against the surrounding code. The navigation wiring is sound: all 26 routes are rendered and reachable, there are no TODOs and no empty `onClick`s, and motion uses the `Motion` tokens.
- **Web and desktop:** the real PWA ran against an isolated backend with seeded data, in headless Chrome.
  - 21 screens × 6 widths (360/390/768/1024/1366/1920) × dark and light, checking console errors, failed requests, overflow and clipped controls.
  - Plus a static dead-control scan, direct URLs and refresh, keyboard use, a live chat with local Ollama (qwen2.5), and stopping the backend while the page was open.
- **Desktop distribution:** the packaged 2.0.0-dev.8 staging app ran from its own embedded Python, with isolated data.
- **Cleanup:** a reference scan across all source, build, packaging, test and doc files, plus a duplicate-hash scan.

## 2. Feature status, before → after
| Area | Before 6.1 | After 6.1 | Evidence |
|---|---|---|---|
| Android navigation (5 tabs, 26 routes) | WORKING | WORKING | Audit; `NavigationSettingsTest` |
| Android system Back in detail views (project, task, resume, study session, knowledge item) | **BROKEN**: Back left the whole screen, and the item stayed open | Fixed | `V61Test.systemBackClosesDetailViews…` |
| Android Voice and Agent overlays | **BROKEN**: Back navigated underneath; taps fell through | Fixed: Back ends voice or cancels the agent (never approves); the panel consumes taps | `V61Test.overlaysHandleBack…` |
| Android Documents/Career/Knowledge back target | PARTIALLY WORKING: always went to More | Returns to Home or More, wherever it was opened from | `V61Test.nonBarTabs…` |
| Android Projects page | PARTIALLY WORKING: briefly showed the previous project's counts, including in the delete dialog; a database error could crash | Fixed: cleared, a stale result is ignored, `runCatching` | `V61Test.projectPage…` |
| Android AI task sources | Dead control (a chevron row that did nothing) | Plain text | `V61Test.noDeadControls…` |
| Android to-do and AI-task delete | No confirmation | Confirmation with the item's name | Same |
| Android Home tiles | Flashed fake "empty" values on every visit | "Loading…" the first time, then the last snapshot at once | `V61Test.homeShowsLoading…` |
| Android Assistant | WORKING (stop, retry, edit, copy, code highlighting, context chips, mode and model chips) | Adds history from the Assistant header, a one-time message appear animation, and "Copied ✓" with a 48 dp target | `V61Test` |
| Android accessibility | PARTIALLY WORKING: 46 dp composer buttons; symbol-only ↑ ↓ − + ✕ | 48 dp; named actions for TalkBack | `V61Test.accessibilityAndMotion` |
| Android reduced motion | WORKING, except the splash fade | The splash also follows it | Same |
| Android dead code | 5 unused composables, a duplicate `ago()` ("123 d ago"), 2 unused libraries | Removed; one `ago()` with friendly dates | `V61Test` |
| Web Home with the backend stopped | **BROKEN**: an endless request loop (2.7–4.3 GB, the tab hung) | Fixed (126 MB, reconnects in 0.46 s) | `test_backend_loss_on_home_does_not_loop` |
| Web Stop generation | **BROKEN**: vanished at the first token | Available for the whole answer, then Retry | `test_shell_guards_for_6_1`, live |
| Desktop "write a Python function" | **BROKEN**: empty answer with tools bound (qwen2.5) | One retry with the **same model** without tools | `test_an_empty_tool_round_is_answered_once_more…` |
| Web retry, regenerate, copy, code highlighting, headings/tables, sources, follow-ups, context indicator | INCOMPLETE or PARTIAL | WORKING (regenerate replaces history only when the new answer completes) | Web report; JS harness (including an XSS check) |
| Web direct URLs `/home`, `?tab=`, `#chat`, refresh | **BROKEN** | Fixed | `test_direct_page_links_serve_the_app_shell` |
| Web dialogs: Esc and focus | **BROKEN** / PARTIAL | Esc closes the topmost dialog; focus moves in and back out | Browser check |
| Web project "Continue" | **BROKEN**: chat loaded twice | Fixed | Browser check |
| Web layout at 360 px | **BROKEN**: toolbar overflow | 0 overflow and 0 clipped controls in 264 captures | Audit sweep |
| Desktop interaction modes (R12) | INCOMPLETE | Quick/Deep/Research/Coding/Learning/Planning/Creative from Android's `Brain.profile`; **never change the model**; Auto unchanged | `test_web_desktop_61.py` |
| Motion | PARTIAL: web 200/320 ms against Android 220/340 | One specification: 120/220/340 ms and the same two easing curves on both platforms; reduced motion everywhere | [Design spec](ABHIFY_6_1_DESIGN_SPEC.md) |
| Packaged desktop dev.8 | WORKING (starts, five tabs, clean stop) with one defect: `/news/categories` 500 because `news_sources.json` wasn't packaged | Packaging fixed for the next build (`build.py` allowlist and its test). The dev.8 installer itself is unchanged | `test_packaging.py` |
| Backend test isolation | One test read the database without the throw-away fixture | Fixed | Baseline |

## 3. Personal AI Brain and safety
No Brain behaviour changed on Android in 6.1. The 6.0 invariants are still enforced by the existing tests, and 6.1 respects them:
- **The mode never changes the model:**
  - Android: `BrainTest`, `RoutingTransparencyTest`.
  - Desktop: new 6.1 tests.
- **Personal context only when relevant:** `Brain.plan` decides when the context finder runs.
- **Private documents go online only after consent:** `UnifiedWorkspaceTest`.
- **App actions are matched only in voice:** `AppActionsTest`.
- **Agent changes need explicit approval:** registry, approval, verification and undo. In 6.1, Back cancels an agent run and never approves.
- **Tools are only registered functions:** the LLM cannot run SQL, shell commands or unregistered functions.
- **Desktop fallback:** the 6.1 empty-answer retry uses the same model, **without** tools, so it has less capability, not more.

## 4. Security and privacy review (6.1)
- No new permission, dependency, telemetry, paid service or external data flow. Two Android libraries were *removed*.
- **The new web page routes** (`/home`, `/assistant`, `/study`, `/more`) serve only the static app shell; they are not API routes and add no data access.
- **Backend binding:** unchanged (127.0.0.1 by default).
- **Keys:**
  - Android provider keys stay in the Keystore.
  - Desktop has no new secrets.
  - The web token stays in localStorage (your earlier decision).
- **Risk still open:** the debug keystore is the only signing key and has no backup (R2, needs your approval).
- **Possible probe to your backend:** when its own backend is unreachable, the web app tries `http://localhost:8000/health`. During two of the helper's offline tests, that single read-only probe may have reached your own backend on port 8000. Nothing was read or written; later runs pinned the URL.

## 5. Performance
- **APK:** 74,497,356 bytes (44 KB smaller than 6.0).
- **Web:** fixed a runaway request loop that reached 2.7–4.3 GB (126 MB after).
- **Android:** removed a database read during composition (AI Task Runner). Home no longer recomputes placeholder tiles on every visit.
- **Not measured:** startup time, memory and battery on the phone (needs the device).

## 6. Known issues and limitations
- Phone, emulator, Safari/Firefox, voice, OS file pickers and installer/tray behaviour are untested.
- Android source citations are text, not tappable passages. Follow-ups are an "Actions" sheet, not suggestions generated from the answer.
- Low-priority design drift on Android: 27 literal corner radii in 13 files, and some `Color.White` on gradient surfaces (intentional on the hero).
- Two launcher entries ("Performance (in AI models)", "System (in Settings)") duplicate other entries. They were kept because `V49Test` requires the System entry.
- The web Home bottom microphone button sits over the centre of the status bar on wide screens (cosmetic).
- The desktop installer is still dev.8 (older than the source). Building a new installer needs a desktop version decision.
