# Abhify.AI manual phone validation (Redmi K50i) — 5.4, internal 5.4-dev.1 and internal 5.5-dev.1

> **Newest internal build:** `D:\Abhify.AI\build\internal\Abhify-5.5-dev.1.apk`, 74,392,641 bytes, SHA-256 `46b417710171d1075213d74ccab6b3efada2b61e9d5319fc1f4ac95c5404468e`. It installs over 5.4 as an update (same versionCode and signature). Do not uninstall first. Tests 53–60 cover what's new in it.
>
> **Newest internal build (5.6):** `D:\Abhify.AIuild\internal\Abhify-5.6-dev.3.apk`, 74,506,621 bytes, SHA-256 `189e49e1dfa7205b44e21d802318bb9497032618e5c9524e66bf00a7f7711921`. Tests 61–75 cover 5.6.
>
> **Newest internal build (6.0):** `D:\Abhify.AIuild\internal\Abhify-6.0-dev.1.apk`, 74,541,281 bytes, SHA-256 `a1f4567990bfd1ef5438c26d194c30fda646cd5674482bd1fb9e3eef8396adb0`. Tests 76–80 cover 6.0.

**Status: waiting for the owner's manual test on the phone. No test has been run on the phone by the developer.** Results go in the "Result" column; only tests actually done on the phone count.

## 1. The build to install

| | |
|---|---|
| File | `D:\Abhify.AI\build\Abhify-5.4.apk` (5.3, 5.1 and 5.0 are kept next to it) |
| Package / version | `ai.abhify.android` · **5.4** (versionCode 5040099) |
| Size | 73,904,909 bytes |
| SHA-256 | `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` |
| Signed with | the same debug certificate as 5.0 (`1d2d1fdb…fa22fe6246f`) |
| Permissions | the same 10 as 5.0 |

Includes the 5.4 Personal Voice Companion (tests 33–44), the 5.3 work of 2026-11-18 (five destinations, grouped More, neural core states, Home command center, Study Discover, News English/Hindi/Both, AI translation, Hindi voice; tests 23–32 below) and all Android work of 2026-11-17: Local RAG (semantic search, Knowledge Chat, Knowledge Center, corrections), 5.1 Personal Intelligence (focus, answer style, project status, Continue working, suggestions, routing explanation), memory precision, and app files in backups. Nothing in the Android source changed after this build.

**Installing:**
1. Copy the APK to the phone (USB file transfer, Drive, or chat to yourself).
2. Open it with the Files app and choose **Update**. Because it's signed like 5.0, it updates in place and keeps your chats, documents, memories and models. Don't uninstall 5.0 first: that would delete the app's data.
3. If MIUI asks, allow installs from that app for this one install.
4. Check the version in **More → About Abhify**: it should read 5.4.

`build/Abhify-5.1.apk` and `build/Abhify-5.0.apk` are kept unchanged. An older version can't be installed over 5.4 (Android refuses downgrades), so keep a backup before testing if you may want to go back.

**Internal build 5.4-dev.1** (tests 45–52): `D:\Abhify.AI\build\internal\Abhify-5.4-dev.1.apk`, 74,272,413 bytes, SHA-256 `9b8a8887a5f1e303ed29cfba83a0956b106375733c4c26a175b7152f63f61354`, versionName 5.4-dev.1 (versionCode 5040099, same certificate — installs over 5.4 keeping data). `build/Abhify-5.4.apk` is unchanged.

## 2. Why no adb session

On 2026-11-17 the developer machine (`DESKTOP-5VPDR45`) was used over Remote Desktop from a laptop, so a phone plugged into the laptop isn't visible to adb on the PC. This PC's USB log has no record of the Redmi (only a OnePlus in August 2026, file transfer only). Wireless debugging was turned on, but the phone (`192.168.1.3`) is on a different network from the PC (`192.168.15.223`), and its ports weren't reachable. The owner chose manual validation. For a later adb session: put the phone on the `192.168.15.x` Wi-Fi, or run `adb -a nodaemon server start` on the laptop. scrcpy 3.1 is installed on the PC (`D:\AndroidDev\scrcpy\scrcpy-win64-v3.1\`, checksum verified).

## 3. Before you start (2 minutes)

- **Make a backup:** Settings → **Backup & restore** → create a backup with everything ticked, including **App files**. It's your safety net and is also test 12.
- **Test material:** a short document of your own (e.g. a 1–2 page PDF or notes with a few distinctive facts), and one fact you'll ask about that is **not** in it.
- Wi-Fi on for the model download (36 MB).

## 4. Checklist (non-destructive; nothing existing is deleted)

Mark each: ✅ pass · ❌ fail (what happened) · ⏭ skipped.

### Local RAG

| # | Test | What to do | Expected | Result |
|---|---|---|---|---|
| 1 | Download | More → Knowledge → **Knowledge Center** → Download (36 MB). Try **Pause**, then Download again | Progress bar; Pause stops it; Download resumes where it stopped; finishes with "Semantic search is ready" | |
| 2 | Checksum | after test 1 | No error message. The app checks size and checksum itself; a bad file is refused with an error | |
| 3 | First use | Chat → chip **📖 Knowledge** set to *Knowledge* → ask something your document answers | Answer arrives (the first one may take a few seconds longer); no crash | |
| 4 | Indexing | Knowledge Center → **Build index** | "Indexing: N of N"; each document then shows passages and vectors | |
| 5 | Incremental | Build index again (no changes); then add one new document and build again | Second build finishes almost at once; the third only processes the new document | |
| 6 | Modes | Ask "What does my *‹document name›* say about ‹topic›?" and "What is the capital of France?" in **Normal**, **Auto** and **Knowledge** (tap the chip to switch) | Normal: no sources for either. Auto: sources only for the first. Knowledge: sources for the first; for the second, sources or "Nothing in your knowledge matched" | |
| 7 | Citations | an answer from test 6 that used your document | `[S1]`-style markers in the text and "Sources sent: [S1] ✓ …" with page numbers under it | |
| 8 | Not in your knowledge | Knowledge mode, ask the fact that is **not** in your documents | "📚 Nothing in your knowledge matched this question" and/or the answer says your documents don't cover it; no made-up citation | |
| 9 | Correct → Approve → Revert | Under an answer tap **✎ Correct**, type a correction, Save → Knowledge Center → **Approve** → check More → Personal memory → back in Knowledge Center history tap **Revert** | Waits in "Corrections to review"; after Approve it appears in Personal memory (category Corrections); after Revert it's gone (or the old text is back). A private chat shows no ✎ Correct | |
| 10 | Online consent | *Only if you're happy to send passages online.* Knowledge Center → turn on "Online answers may use my knowledge"; header mode **Online AI**; ask a question about your document | A dialog asks first. "This phone" keeps it local. With the switch off, answers say "🔒 Your knowledge stayed on this phone" | |
| 11 | Battery saver | Turn on Battery saver, ask a Knowledge question, turn it off again | Still answers (keyword search) without errors | |
| 12 | Backup / restore | Use the backup from §3. Restore it onto the same phone with only **App files** ticked | Restore reports files written/skipped; existing files are skipped, nothing is overwritten or lost | |

### 5.1 Personal Intelligence

| # | Test | What to do | Expected | Result |
|---|---|---|---|---|
| 13 | Focus | Chat chip **✨ Auto**. Send "Explain overfitting", then paste an error with a stack trace, then "Tell me a joke" | Labels under the answers: "🎓 Learning (auto)", "🧑‍💻 Coding (auto) · stack trace", none for the joke. The coding answer gives root cause → fix → how to verify | |
| 14 | Focus choice | Tap the chip, choose **Career**, ask anything | Label "💼 Career"; structured, actionable answer | |
| 15 | Answer style | Settings → Answer style → Detail *Concise*, language *Hindi* (or another); ask a question; then set back to Auto/empty | Shorter answer in that language; back to normal after resetting | |
| 16 | Project status | Projects → a project → **Add status** (objective, current status, next actions) → Save | "Where you left off" shows exactly what you typed, nothing more. **Continue in chat** opens the project chat with the question typed in the box, not sent | |
| 17 | Project by name | Normal chat (no project chosen), Knowledge chip on Auto: "What's next for ‹project name›?" | The answer uses the status you recorded and doesn't invent progress | |
| 18 | Continue working | Go to Home | "Continue working" lists your last chat and the project you just updated; tapping opens them | |
| 19 | Suggestions | Home → Suggestions (if any), tap ✕ on one; leave Home and come back | The dismissed one stays gone. Settings → AI: the "Suggestions on Home" and "Continue working on Home" switches hide and show each card | |
| 20 | Where answers go | Chat → **⚙ Online context** | "Next answer" says which AI answers and what goes online | |
| 21 | Memory relevance | With a few memories saved, ask something unrelated ("Tell me a joke") and something related | Unrelated: no "🧠 memories used". Related: only the matching memories | |
| 22 | Stability | Use the app across tabs for about 10 minutes; rotate the phone once | No crash or "app isn't responding" | |

### 5.3 additions

| # | Test | What to do | Expected | Result |
|---|---|---|---|---|
| 23 | Navigation | Look at the bottom bar | Home · **Assistant** · Study · News · More | |
| 24 | More groups | Open More | Knowledge · Workspace · Career · AI & System · Settings & Privacy (then Documents & text recognition). AI models, providers, Knowledge Center and memory open from here. Settings still has AI mode, answer style, appearance, voice, performance, diagnostics | |
| 25 | Neural core | Knowledge mode (or with documents ticked), ask a question; watch the orb next to the answer | Orb shows **Searching** briefly (cyan arc), then Thinking/Responding. With aeroplane mode on and Online AI mode: orb grey and still ("Offline") | |
| 26 | Home | Open Home | Active projects (with their next step), Learning, Knowledge status, "News · saved X ago" (after News was opened once), Discover shortcut. All match your real data | |
| 27 | Discover | Study → Discover → tap "Machine Learning" | MIT OpenCourseWare courses (marked **Free**, CC BY-NC-SA), OpenStax if it matches, then "Search on the providers' own sites". Open ↗ opens the provider page in the browser | |
| 28 | Discover filters | Turn on **Free only** | Only items marked Free remain; provider search links disappear | |
| 29 | Learning path / Save | Tap **Make a learning path**; tap **Save to Knowledge** on a course | Plans opens with the topic filled in (the plan is AI-generated); the course appears in More → Knowledge & collections (category Study) | |
| 30 | MCQs and Questions in Study | Study → MCQs, Study → Questions | Same screens and progress as Career → MCQs / Question Bank | |
| 31 | News language | News → **हिन्दी**, then **Both**, then English | Hindi: Hindi headlines (Amar Ujala, Live Hindustan, ABP Live, NDTV India, BBC Hindi, Google News) and Hindi category names. Both: both mixed. English: as before | |
| 32 | Translate / Hindi voice | On an English story: ⋯ → Translate into Hindi (AI); then Listen on Hindi news | The translation is labelled "AI translation · not the publisher's text". Hindi is read with a Hindi voice, or a message explains how to install one | |

### 5.4 Personal Voice Companion

| # | Test | What to do | Expected | Result |
|---|---|---|---|---|
| 33 | Voice settings | More → Settings → **Voice & Conversation** | All options shown; the voice list shows your engine's real voices ("Automatic (female)" only if the engine labels one); ▶ Preview speaks; choices survive closing and reopening the app | |
| 34 | Start a conversation | Assistant → **🗣 Talk** (allow the microphone if asked) | Panel opens; core shows Listening with a moving waveform while you speak; your words appear live | |
| 35 | Question | Say "Can you explain RAG in simple terms?" | Core: Processing → Thinking → Responding; answer appears in the chat and is spoken in short sentences; then it listens again | |
| 36 | Interrupt | While it speaks, tap the core (or Interrupt) and say "Explain it with an example" | Speech stops at once (Interrupted), it listens, and answers the new request with the earlier context | |
| 37 | Navigation by voice | Say "Open Study", "Go to news", "Open the knowledge center", "Show my projects", "Go home" | Each screen opens underneath the panel and Abhify says what it did | |
| 38 | Read-only | Say "What should I do today?", "What are we working on?", "Show my model status" | Answers only from your real records (or says nothing is recorded) | |
| 39 | Create with confirmation | Say "Create a task called test voice" → Confirm → then Undo | Asks first; adds the to-do to a project; Undo removes it | |
| 40 | Sensitive | Say "Delete all my conversations" | Nothing is deleted; it opens Privacy & data and explains you must confirm there | |
| 41 | Hindi | Settings → Language **हिन्दी**; say "न्यूज़ खोलो" and ask a question in Hindi | News opens; the answer is in Hindi and spoken with a Hindi voice (or a message says how to install one) | |
| 42 | Response modes | Try Voice only and Text only | Voice only: panel shows status, answer still saved in chat; Text only: nothing is spoken | |
| 43 | Conversation data | More → Privacy & data deletion → **Conversation data** | Storage explanation and counts; each Clear asks first; after "Clear all conversations" the chats are gone but documents, projects and models remain | |
| 44 | End / offline | Tap **■ End**; then aeroplane mode + phone model, start Talk again | Microphone stops immediately; offline: answers with the phone model if on-device speech recognition is available, otherwise a clear message | |

### Internal build 5.4-dev.1

| # | Test | What to do | Expected | Result |
|---|---|---|---|---|
| 45 | Today | Home → Today card (have a project with some to-dos, one with a past due date) | Plan lists overdue first ("Overdue since …"), then others with reasons; to-dos without a date are never called overdue; tap one → set High priority / Blocked → plan updates | |
| 46 | Briefing | Today → Briefing (or Settings → Personal briefing → Open) | Morning/Evening/Weekly/Your tabs; every item shows "Source: …"; Skip hides an item; 🔊 Listen reads it | |
| 47 | Briefing reminder | Settings → Personal briefing → Daily reminder on, hour 08:00 (allow notifications if asked) | Next day at 08:00 a notification "Your briefing is ready" without details; nothing during quiet hours | |
| 48 | Agent | Project page → 🤖 Find unfinished work (project notes containing "TODO: …" or "- [ ] …") | Steps tick through; proposals marked ✔ (found in your notes) or ≈ (suggestion); duplicates noted; nothing is created until Approve; after Approve the report says created and verified; Undo removes them | |
| 49 | Skills | Study → Overview → Skills (after some quiz/MCQ answers) | Levels only for topics with ≥ 5 answers; "Next:" with a reason | |
| 50 | News intelligence | News → ⋯ on a story → Related stories / Compare N sources (when shown) / Learn this topic | Related list from loaded stories; comparison labelled as AI; Learn opens Study → Discover with the topic | |
| 51 | Search | 🔎 Search everything → type a project name | The project and matching AI tasks appear; recent searches are offered next time | |
| 52 | Voice | Talk → "plan my day", "brief me", "weekly review", "what do I have on ‹topic›", "find unfinished work in my project" | Spoken answers from your records; the last one opens the agent panel | |
| 53 | Agent: status (5.5) | A project → 🤖 Status & blockers | Sections Recorded / Work / Blockers / Possible duplicates / Documents, each line with its source; nothing is changed | |
| 54 | Agent: priorities (5.5) | A project with a past-due to-do → 🤖 Suggest priorities → tick one → Approve → Undo | Suggestion names the stored due date; after Approve the to-do shows High in Today; Undo restores it | |
| 55 | Agent: documents (5.5) | A project → 🤖 Related documents → Approve one | The document appears under the project's linked documents; Undo unlinks it (the document itself stays) | |
| 56 | Chat context (5.5) | Phone model, knowledge mode → ask about one of your projects or notes | The answer uses your records; sources show 🧭 lines with freshness. With an online model these 🧭 lines do not appear | |
| 57 | Skills (5.5) | Study → Overview → Skills → tap 3 / 5 / 10 → "Add a revision to-do" → Add | Levels change with the threshold; a trend appears only after 10 answers; "Revise …" appears in the Study project | |
| 58 | News + search (5.5) | News list; then 🔎 Search everything | "Also reported by N other sources" on shared stories; search results grouped under type headers with counts | |
| 59 | Voice (5.5) | Talk → "what's blocking my project" | The agent panel opens with the status of your most recently updated project | |
| 60 | Desktop (5.5) | PC browser → Home briefing and Study | "Today" lists issues and next actions from your projects; Study shows Skills when you have MCQ answers | |
| 61 | News image (5.6) | News → pull to refresh 3–4 times (and switch category and back) | The featured (first) story's image always belongs to that story; no story shows another story's picture; missing images show the same placeholder | |
| 62 | Friendly AI follow-up (5.6) | Ask "How does RAG work?", then "why?", then "give an example" | Answers stay on RAG without repeating the question; no "I feel…" claims | |
| 63 | Small talk (5.6) | Say "thanks" or "good morning" | A short natural reply, not a feature list | |
| 64 | Personal context (5.6) | Phone model: "what are my notes on ‹a topic you have notes on›" | Uses your notes; 🧭 sources listed; an unrelated question shows no 🧭 lines | |
| 65 | Voice streaming (5.6) | Talk → ask a question with a long answer | Speech starts after the first sentence, sentences in order, no overlapping voices | |
| 66 | Interrupt (5.6) | While Abhify speaks (or is still answering) tap Interrupt; repeat 3 times | Speech stops at once, nothing from the old answer is spoken later, it listens again | |
| 67 | Voice lifecycle (5.6) | During a conversation press Home; also receive a call while it speaks | The conversation ends when the app leaves the screen; speech pauses for the call | |
| 68 | Hindi voice (5.6) | Settings → Voice → Hindi; ask a question in Hindi | Hindi transcript; Hindi voice (or a clear "no Hindi voice" message); language setting unchanged afterwards | |
| 69 | Search filters (5.6) | 🔎 Search everything → Last 7 days, a project chip, Recent activity | Results narrow correctly; "Nothing matches with these filters" when empty; news/briefing results appear | |
| 70 | To-do reminder (5.6) | Today → tap a to-do → Reminder → In 1 hour (or set quiet hours to test the move) → later Snooze | One notification at about that time (inexact); lock screen shows only "To-do reminder"; Snooze moves it 1 hour; a completed to-do never notifies | |
| 71 | Duplicates (5.6) | A project with "Fix login bug" and "fix the login bug on Android" → 🤖 Status & blockers | Listed under Possible duplicates; nothing is changed | |
| 72 | Skills path (5.6) | Study → Overview → Skills | Revision plan with answer counts; learning path for a goal | |
| 73 | Look (5.6) | Browse Home, Assistant, Study, News, More, Settings in dark mode | Near-black charcoal surfaces, violet and electric-cyan accents, readable text everywhere | |
| 74 | Provider stats (5.6) | After a few answers: Settings → AI providers | "Measured this session" lines with answered/failed and first-words time | |
| 75 | Hinglish voice (5.6) | Talk → "aaj ka plan batao", "project mein kya atka hai" | Today's plan and the project status are spoken; nothing is changed | |
| 76 | Modes (6.0) | Assistant → Mode → ⚡ Quick answer, then 🧠 Deep think, same question | Quick: a short direct answer; Deep: breaks the problem down, checks an alternative, states confidence. The same AI answers both (shown under the answer) | |
| 77 | Research (6.0) | Mode → 🔬 Research (online on) → "latest news on ‹a current topic›" | 🌐 [W1]… sources with publisher and age under the answer; the answer cites them; with online off it says it can't search | |
| 78 | Honest current info (6.0) | Auto mode: "what's the latest on ‹a current topic›" | A notice that the models don't browse, plus a suggestion to use Research; no invented recent facts | |
| 79 | Planning / Creative (6.0) | "make a roadmap for my ‹project›"; "write a poem about rain" | Planning: steps, dependencies, first action, no invented deadlines; Creative: several distinct options | |
| 80 | Route advice (6.0) | Deep think on the phone model while an online key is set up | A 💡 hint suggesting the stronger model; nothing switches by itself | |
| 81 | Back in detail views (6.1) | Open a project (More → Projects), then press the phone's Back. Repeat with an AI task, a resume, a study session and a Knowledge item | Back closes the open item first and you stay on the same screen; a second Back leaves | |
| 82 | Back over overlays (6.1) | Start Talk to Me, press Back. Start an agent run (for example, a project review or plan), press Back while it waits for approval | Voice: the conversation ends and the panel closes. Agent: the run is cancelled, nothing changes, and the app does not navigate underneath | |
| 83 | Taps don't fall through (6.1) | With the voice panel open, tap its empty background area | Nothing underneath reacts (composer, bottom bar) | |
| 84 | Back to where you came from (6.1) | Home → "Your spaces" → Documents, then Back (or the arrow). Repeat from More → Documents | From Home you return to Home ("Back to Home"); from More to More | |
| 85 | History from Assistant (6.1) | Assistant tab header | A history icon opens Conversation history | |
| 86 | Project switch (6.1) | Open project A, go back, quickly open project B | B never shows A's documents or counts, even briefly | |
| 87 | Delete confirmations (6.1) | Delete a workspace to-do; delete an AI task | Each asks "Delete this…?" with the item's name; Cancel keeps it | |
| 88 | Home loading (6.1) | Cold start, open Home | Tiles say "Loading…" briefly, then real numbers; returning to Home shows numbers at once (no "0 documents" flash) | |
| 89 | Message appear and copy (6.1) | Send a message; ask for a code sample and tap Copy on the code block | New messages fade/rise in once (not when scrolling back); "Copied ✓" shows for about 1.5 s. With Developer options → Remove animations, nothing animates | |
| 90 | Touch targets and TalkBack (6.1) | With TalkBack on: AI Providers order arrows, Backup schedule −/+, Home suggestion ✕ | Each is announced by name ("Move Gemini up", "One hour later", "Dismiss suggestion: …") | |
| 91 | Live news in Auto (6.2) | Online, Auto mode: "What's happening in AI today?" | "🌐 Checking current information…" then an answer citing [W#]; under it "✓ Sources checked · topic searched on Google News" and chips with publishers; tapping a chip shows the published time and "Open original" opens the article in the browser | |
| 92 | No retrieval for ordinary questions (6.2) | "Explain how RAG works." | No live line, no chips, normal answer | |
| 93 | Web question on the phone (6.2) | "What is the latest Python release?" | Honest: "◐ Some live information found · couldn't check: web search" (or ⚠ unavailable); the answer says general web search isn't available on the phone; any news chips are labelled as news | |
| 94 | Weather and rates off by default (6.2) | "What is the weather now?", "USD to INR rate?" | "⚠ Live information unavailable"; the answer says weather and exchange rates are off and no values are invented | |
| 95 | Weather and rates on (6.2) | Settings → Live information: turn on Weather (place: your city) and Exchange rates; ask again | Current weather with Open-Meteo attribution; the rate labelled "daily reference rate for ‹date›", not live | |
| 96 | Own records and phone status offline (6.2) | Aeroplane mode: "status of my ‹project› project", "my pending tasks", "how much RAM is free" | Answers from your records/phone ([L#] chips, no link); external parts say no internet connection | |
| 97 | Voice parity (6.2) | Talk to Me: "what's happening in AI today?" | Same retrieval as typed; the spoken answer doesn't read "[W1]" or web addresses; chips appear in the conversation | |
| 98 | Refresh and cache (6.2) | Ask a news question twice within a few minutes, then tap "↻ Refresh live information" | Second answer says "🕘 Using cached information"; Refresh fetches fresh sources | |

### Optional measurements (by eye)

| What | How | Value |
|---|---|---|
| Knowledge answer time | from Send to the first words, with and without the semantic model | |
| Index build time | Knowledge Center message after Build index ("… s") | |
| Phone warmth | does it get noticeably warm during Build index? | |
| App memory | Settings → Apps → Abhify.AI → Storage & memory (if MIUI shows it) | |

## 5. Reporting problems

For any ❌, note the test number, what you did, and what appeared (a screenshot helps). If the app closes unexpectedly, write the time; a crash log can be collected later with adb (`adb logcat -b crash`) when a connection is possible.

## 6. Results

*(to be filled from the owner's manual test)*
