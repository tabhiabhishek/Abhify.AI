# Abhify.AI 6.1 — web & desktop quality audit and fixes

Scope: the FastAPI backend (`backend/`) and the vanilla-JS PWA (`frontend/`), audited in a real browser, then fixed.
Version unchanged (`2.0.0-dev.8`, `CACHE_VERSION` unchanged); shell revision bumped `SHELL_REV 'ui56.1' → 'ui61.1'`.
No new dependencies, no new API routes, no new external data transmission. Nothing committed.

## How it was tested

- Isolated backend (`ABHIFY_DATA_DIR` = a scratch folder, port 18061; never the real data folder, never port 8000), seeded with
  2 conversations, 2 projects with status, 1 AI task, 12 MCQ answers, 1 memory.
- Headless Chrome via the DevTools protocol, the real PWA, widths **360, 390, 768, 1024, 1366, 1920**, **dark and light** themes,
  21 screens each (Home, Assistant, Assistant with history, Study, Question Bank, MCQs, News, More, Knowledge, Memory, Projects,
  Project page, Tasks, AI Models/setup, Performance, System & data, Settings, Command palette, Launcher, Customize Home, News sources)
  = **264 screen captures per run**. Per screen: console errors/warnings, uncaught exceptions, `Log.entryAdded`, HTTP ≥ 400 and failed
  requests, horizontal overflow (any element past the viewport), clipped controls (buttons/chips/tags whose text overflows).
- Static check of `index.html` + `app.js`: every `$('#id')` reference exists; every interactive element with an id is used; every
  `data-*` hook is read; every rendered `data-action` is handled.
- Behaviour checks: direct URLs and refresh, keyboard Tab order and focus ring, Esc on every dialog/popover, live chat with
  Ollama (`qwen2.5`, local) incl. streaming, Stop, Regenerate, copy, modes; backend stopped while the page is open, then restarted.
- Ollama was running locally (qwen2.5, llama3.2), so live answers were exercised against the isolated backend.
- Caveat: the PWA's built-in backend discovery tries `http://localhost:8000/health` when its own origin fails. In the first
  "backend stopped" runs the test browser may therefore have sent that one read-only `/health` probe to the owner's backend
  (the page stayed "Connection lost", so nothing was read or written there). Later runs pinned the backend URL in the page.

Tools (scratchpad, not in the repo): `w61_audit.py` (screens/overflow/console/network), `w61_behave.py` (routes, keys, chat, offline),
`w61_eval.py` (ad-hoc checks), `w61_static.py` (dead controls), `w61_seed.py`.

## Audit result (before the fixes)

Console errors / exceptions / failed requests across all 264 captures: **none**. Static dead-control check: **none** (no missing
ids, no unused controls, no unhandled actions). The defects were behavioural, found by exercising the features:

| Feature | Status before | Evidence |
|---|---|---|
| Home dashboard | WORKING | all cards render real data; skeletons while loading; error/empty states offline |
| Home with the backend stopped | **BROKEN** | endless request loop: renderer grew to 2.7–4.3 GB and pegged a CPU core; page stopped answering (reproduced twice) |
| Assistant: send / streamed answer | WORKING | qwen2.5 streamed with caret, model + tok/s tag |
| Assistant: Stop | **BROKEN** | Stop button disappeared with the first streamed token; clicking was impossible (`busy` stayed true) |
| Assistant: "write a Python function" | **BROKEN** (backend) | answer was "Sorry, I couldn't produce an answer." in auto and coding focus: qwen2.5 returns an empty message when tools are bound; the same model without tools answers |
| Assistant: retry / regenerate | INCOMPLETE | not present; an error left a dead message |
| Assistant: copy message / copy code | INCOMPLETE | not present |
| Assistant: code highlighting, headings, tables | INCOMPLETE | code shown plain; `## heading` and Markdown tables shown as raw text |
| Assistant: sources | PARTIALLY WORKING | plain "Sources: a · b" text |
| Assistant: follow-up suggestions | INCOMPLETE | not present (only the 8 AI action buttons) |
| Assistant: context indicator | PARTIALLY WORKING | only a "Your documents" tag when documents were searched; memories and project context invisible |
| Assistant: offline / error state | PARTIALLY WORKING | offline text shown, no retry |
| Assistant toolbar at 360 px | **BROKEN** (layout) | "Add file" chip pushed past the right edge (right = 370 px of 360) |
| Interaction modes (R12) | INCOMPLETE | desktop had only the focus selector (auto/general/coding/learning/career/research), no Quick/Deep/Planning/Creative |
| Study (Discover, skills) | WORKING | live MIT OCW / OpenStax search; offline error state |
| Question Bank, MCQs | WORKING | generate, saved/offline questions, review mode |
| News Center | WORKING | categories, language, search, reader, AI briefing button |
| More + pages from More | WORKING | Knowledge, Memory, Projects, Tasks, AI Models, Performance, System, Settings open and load |
| Projects: "Continue" a project chat | **BROKEN** | the conversation was loaded twice (4 bubbles for a 2-message chat) when the chat tab had not been opened yet |
| Task Runner / System & data offline | PARTIALLY WORKING | error squeezed into one grid cell (Task Runner); bare "Needs the backend (offline)." line (System) |
| Dialogs: Esc | **BROKEN** | Esc did not close Settings, Customize Home, News sources or the History popover |
| Dialogs: focus | PARTIALLY WORKING | focus stayed behind Settings / Customize / News sources / Memory / Tasks / News reader when they opened |
| Focus ring | PARTIALLY WORKING | visible on every control, but `:focus-visible` forced `border-radius: 6px`, squaring off pills and round buttons |
| Direct URL `/home` | **BROKEN** | 404 |
| `?tab=study`, `?tab=more`, `#chat` | **BROKEN** | ignored (opened Home); only home/news/interview/mcq/chat were honoured |
| Refresh | PARTIALLY WORKING | always reopened Home (the page wasn't in the address) |
| Motion tokens | PARTIALLY WORKING | 200/320 ms (Android: 220/340), a few literal durations and token-less transitions |
| Reduced motion | WORKING | global rule disabled animations/transitions |

## Defects found → fixed

| # | Defect | Fix (file:line, current tree) | Regression test |
|---|---|---|---|
| 1 | Backend stop with Home open → endless, exponentially growing request loop (GBs of memory) | `setOnline` re-renders Home only when the status changes or fresh health arrives — `frontend/app.js:329` | `test_backend_loss_on_home_does_not_loop` (Node harness runs the real `setOnline`); browser re-check: 11 requests in 8 s, renderer 126 MB, recovers in 0.46 s |
| 2 | Stop vanished once streaming started | Stop button re-rendered with every streamed paint; one click handler per bubble; a pending paint can't overwrite the final message — `frontend/app.js:930-992` (`stopBtn` :949, `bubble.onclick` :950) | `test_shell_guards_for_6_1`; live: Stop clicked mid-stream → "■ Stopped…" + Retry |
| 3 | Empty answer from a tool-bound model ("Sorry, I couldn't produce an answer.") | one retry of the same turn **with the same model** (and the mode's sampling) without tools; streamed too — `backend/agent.py:307-320` (`_has_answer`, `_fallback_llm`), used at `:363` and `:384` | `test_an_empty_tool_round_is_answered_once_more_without_tools_by_the_same_model`, `test_the_streamed_chat_streams_the_fallback_answer`; live: the Python-function prompt now answers with a highlighted code block |
| 4 | Project "Continue" loaded the chat twice | uses `openConversation` (one load) — `frontend/app.js:1901` | browser check (4 → 2 bubbles) |
| 5 | Esc didn't close Settings / Customize / News sources / History | one Esc handler for the topmost dialog; pages and the palette keep theirs — `frontend/app.js:1657-1690` (`topSheet`, `bindDialogs`) | `test_shell_guards_for_6_1`; browser: all 10 dialogs close on Esc |
| 6 | Focus stayed behind opened dialogs; not returned | focus moves into a dialog when it opens (the dialog itself when its content is a live region) and back to the opener when it closes — `frontend/app.js:1661` | browser: focus "inside" for 9/10 dialogs (History keeps focus on the message box by design) |
| 7 | `/home` 404; `?tab=study/more`, `#chat` ignored; refresh lost the page | backend serves the app shell for `/home`, `/assistant`, `/study`, `/more` (not API routes; `/home/` redirects to `/home`) — `backend/main.py:1555-1572` (`PAGE_PATHS`, `AppStatic`); the page reads `?tab=`, those paths and `#tab` hashes, and keeps `?tab=` in the address — `frontend/app.js:1427-1450`, boot `:1583` | `test_direct_page_links_serve_the_app_shell`, `test_pages_open_from_the_address`; browser: all routes and refresh open the right page |
| 8 | "Add file" overflowed at 360 px | icon-only below 400 px (accessible name kept) — `frontend/index.html` (kb-bar label), `frontend/styles.css:913` | audit overflow check (0 after) |
| 9 | Mode selector would wrap to a second row at phone width | select sizes to the chosen option, model + mode share one row — `frontend/styles.css:584-586` | screenshot check at 360 px |
| 10 | Focus ring squared off rounded controls | removed the forced radius; inset ring for nav items — `frontend/styles.css:117-118` | `test_shell_guards_for_6_1` |
| 11 | Task Runner / System offline states | shared error state + "Try again" (Task Runner); clear error, cleared data path (System) — `frontend/app.js:2026, 3032`, `styles.css` `.task-templates > .state` | browser offline run |
| 12 | Projects list: no loading state, plain empty/error text | skeleton while loading, shared empty/error states — `frontend/app.js:1828-1840` | browser |
| 13 | A chat stopped/failed before its first answer left a "0 messages" entry in Continue working and History | empty conversations are not listed (nothing deleted) — `frontend/app.js` `renderHome`, `openChatHistory` | after screenshot `home_390.png` |
| 14 | Visual: paragraph glued to a code block; message tools indented off the text | spacing after code blocks/tables; tools row aligned with the text — `frontend/styles.css` (`.msg-tools`, `.code-block + p`) | after screenshots |

After the fixes the full 264-capture run (6 widths × 2 themes × 21 screens) again shows **0 console errors/warnings, 0 exceptions,
0 failed requests, 0 horizontal overflow, 0 clipped controls**; the behaviour checks (routes, Esc/focus, live chat, offline and
recovery) pass as described above.

Not fixed (outside the files I may change): **the packaged build omits `backend/news_sources.json`** (see Desktop distribution).

## Assistant UX on the web (after)

| Item | Status | Notes |
|---|---|---|
| Streaming appearance | WORKING | caret while writing; an unfinished ``` block renders as a code block while streaming |
| Stop generation | WORKING (fixed) | available during the whole answer; "Nothing from this answer was saved" + Retry |
| Retry after error/stop/offline | WORKING (new) | Retry re-sends the question in the same place; offline Retry re-checks the connection first |
| Regenerate last answer | WORKING (new) | `POST /chat` and `/chat/stream` accept `regenerate: true`; the previous pair is left out of the prompt and replaced in history only when the new answer completes (cancel = nothing changes). Live check: history still 2 messages after regenerate |
| Copy message / copy code | WORKING (new) | Clipboard API with an `execCommand` fallback (plain-http phone connections). Live check with a real click: clipboard contained the code |
| Syntax highlighting | WORKING (new) | small built-in tokenizer (Python, JS/TS/JSON, Java/Kotlin/C-family/Go/Rust/Swift, SQL, shell/PowerShell/YAML): comments, strings, numbers, keywords, calls. Works on raw text and escapes every piece (XSS test in the harness) |
| Headings, tables | WORKING (new) | `#`–`####` headings, `|a|b|` tables (scrollable) |
| Source citations | WORKING (improved) | chips: 📄 document (page) / 🌐 publisher host linking to the source |
| Follow-up suggestions | WORKING (new) | up to 3 chips built from the answer's own headings and bold terms plus one example/test prompt; no extra model call; hidden for short answers |
| Context indicator | WORKING (new) | "Context: 🧠 2 saved memories · 📚 3 document excerpts · 🗂 project … · ↺ 8 earlier messages" or "none of your data" — counts from the backend (`context` in the chat result), never content |
| Offline/error state | WORKING | message + Retry |

## Interaction modes (R12)

Ported from Android `Brain.profile` (read-only reference) to `backend/intelligence.py:94-109` (`PROFILES`, `profile()`), applied in
`backend/agent.py` `_prepare` (still returns 6 values; `run_agent` / `stream_agent` keep their signatures, new optional
`regenerate` keyword only).

| Mode | History | Personal context | Temperature | Max tokens | Scaffold |
|---|---|---|---|---|---|
| ⚡ Quick answer | 4 | only when the question is about the user ("my …", "remember") | 0.2 | 384 | Android text |
| 🧠 Deep think | 12 | normal rules | 0.2 | default | Android text |
| 🔬 Research | 8 | always: memories + matching document excerpts | 0.2 | default | research format |
| 🧑‍💻 Coding | 10 | normal | 0.2 | default | coding format |
| 🎓 Learning | 8 | normal | 0.4 | default | learning format |
| 🗺 Planning | 8 | always | 0.3 | default | Android text |
| 🎨 Creative | 8 | only when about the user | 0.9 | default | Android text |
| General / Career (chosen) | 12 (desktop default) | normal | default | default | as before |

- A mode **never changes the model**: the mode agent is built with the chat's model (or the default) — `_mode_agent`, tested.
- Only an explicitly chosen mode applies a profile. **Auto** keeps today's behaviour exactly (same agent, 12-message window;
  existing tests unchanged). Quick/Deep/Planning/Creative are never auto-detected on the desktop (Android auto-detects Planning and
  Creative; kept out so the shared intent-eval parity test stays exact).
- Web UI: the existing focus selector next to the model chip now has a "Modes" group; the choice is remembered in this browser
  and explained in a toast; a non-Auto mode is tinted. `POST /chat` `focus` accepts the new values (422 for unknown ones).
- Live check: Quick answer → tag "⚡ Quick answer", 2 earlier messages in context; Coding → tag "🧑‍💻 Coding".

## Motion and design consistency

- Tokens aligned with Android: `--dur-fast 120ms`, `--dur-base 220ms`, `--dur-slow`/`--dur-emphasis 340ms`, standard
  `cubic-bezier(.2,0,0,1)`, emphasized `cubic-bezier(.05,.7,.1,1)` (aliases `--ease-standard`, `--ease-emphasized`). Measured in the
  browser: page enter 0.34 s emphasized, chip transitions 0.22 s standard, message appear 0.22 s.
- Every transition now uses the tokens and the standard easing (the remaining literal values are looping indicators: spinners,
  shimmer, typing dots, the orb).
- Pressed states added for nav tabs (icon scale), sidebar buttons, mini buttons, AI action buttons, chips, launcher items, project
  and template cards; focus ring follows each control's shape; selected states unchanged (violet soft fill + violet border).
- Reduced motion: still disables all animation/transition; additionally no press transforms and still (non-shimmering) skeletons.
  Verified with `prefers-reduced-motion: reduce` emulation (animations `none`, transitions `0s`).
- Message appear animation and skeletons already existed; skeleton added to the Projects list.

## Desktop distribution (packaged snapshot)

Launched `build\windows\staging` (not modified) with its embedded `runtime\python.exe app\desktop\launcher.pyw`,
`ABHIFY_DATA_DIR` = scratch folder, `ABHIFY_PORT=18071`, `ABHIFY_NO_BROWSER=1`, `ABHIFY_NO_TRAY=1`. The installer was not run and
nothing was built.

- Startup: `/ping` answered after ~4 s (`{"app":"Abhify.AI","version":"2.0.0-dev.8"}`); log shows the isolated data folder, schema v5.
  (A first attempt failed with "OpenBLAS error: Memory allocation still failed" while the machine was short of memory because of
  the runaway browser tab in defect 1; the second attempt with memory freed started normally.)
- PWA: loads, status "Online · AI ready", no setup wizard (pre-seeded), all five tabs (Home, News, Interview, MCQs, Chat) open,
  Settings opens. No console errors.
- Clean shutdown: `launcher.pyw --stop` → "Abhify.AI stopped.", exit 0 after 17.8 s (it waited for two in-flight Ollama requests that
  the tab visits had started), port closed, process gone, run file removed.
- **It is the older dev.8 snapshot.** 80 API paths vs 103 in the current source. Absent: Projects (`/projects…`), Agent
  (`/agent/plan|apply|undo|tools`), streamed chat (`/chat/stream`, `/chat/warm`), Study/Discover (`/discover`, `/skills`), briefing and
  plan (`/briefing`, `/plan`), suggestions, universal search (`/search`), career MCQs (`/career/mcq…`), news reader
  (`/news/article/{id}`), retrieval eval (`/eval/retrieval`); the UI has no Study or More tab, no 5.6 Neural design, a hard-coded
  greeting name ("Good afternoon, Abhi"), and none of the 6.1 fixes above.
- **Packaging defect found:** `GET /news/categories` returns **500** in the packaged app: `FileNotFoundError …\app\backend\news_sources.json`.
  `packaging/windows/build.py` copies only `*.py` and `requirements.txt` from `backend/` (`APP_ALLOW`, line 53), so the News Center's
  source catalogue is missing and News falls back to the classic AI-summarised view. Fix for the next build: add `news_sources.json`
  to `APP_ALLOW["backend"]` (not changed here: `packaging/` is outside this task's files).

## Tests

- Backend suite (`.venv\Scripts\python.exe -m pytest -q -p no:cacheprovider`, `ABHIFY_DATA_DIR` = scratch folder):
  **460 passed, 2 skipped** (baseline 443 passed / 2 skipped; +17 new tests), 2 existing LangGraph deprecation warnings.
- New: `backend/tests/test_web_desktop_61.py` (modes, regenerate, context, fallback, page links, and the real `app.js` functions in
  Node via the new `backend/tests/js/web61_harness.js`: Markdown/code/XSS, tools, follow-ups, context line, routes, the offline loop).
- No existing test was changed or weakened.

## Screenshots

`docs/screenshots/v61/web/before/` and `docs/screenshots/v61/web/after/`: Home, Assistant, Study, News, More, Settings, Knowledge,
Projects at 1920, 768 and 390 px (dark), plus Home 1920 and Assistant 390 in light.

## NOT TESTED

- Real phones, real touch input, Safari/iOS, Firefox (only headless Chrome on Windows).
- Voice (microphone, Whisper, read-aloud), file upload through the OS file picker, backup restore, "Move data folder".
- Companion/paired-phone mode and guest devices (remote `/health` states only through the existing harness).
- The Windows installer (not run by instruction); the tray icon of the packaged app (disabled with `ABHIFY_NO_TRAY`).
- Clipboard with the `execCommand` fallback on a real plain-http phone connection (the Clipboard API path was verified).
- Model pulls / setup wizard actions and the AI Task Runner executing a full task (pages and offline states only).
