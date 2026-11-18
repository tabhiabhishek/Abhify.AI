# News audio: Listen to the briefing (2026-11-09)

**Listen** reads the briefing (or a story's summary or explanation) that is already on screen.
- No new AI request is made (measured: 0).
- Nothing is downloaded, and there is no paid or online speech service.

## Architecture
| | Desktop/web | Android |
|---|---|---|
| Engine | the browser's own speech synthesis (`speechSynthesis`) | Android's own text-to-speech, through the existing `Voice` class (the one that reads chat answers) |
| Code | `speechChunks()` (text into parts) and `makeReader()` (the player, engine injected for tests) in `frontend/app.js`; the same reader also reads voice answers (no longer cut at 600 characters) | `Voice.newsParts()`, `Voice.speakParts(parts, owner)`, `skip()`, `setRate()`; view model: `listenNews`, `speechOwner`, `speechProgress` |
| Parts | one per bullet/paragraph (an "item"), long items split at sentence ends (≤ 220 characters) | one per bullet/paragraph, ≤ 400 characters |
| Not read aloud | citation numbers like [1], links, Markdown, code | the same |

## Controls
- **Buttons:**
  - Listen / Pause / Resume / Replay (one button that changes with the state);
  - Previous item, Next item, Stop;
  - speed 0.8× / 1× / 1.25× / 1.5×.
- **Status line** (screen-reader live region): "Reading item 2 of 6", "Paused at…", "Finished.", or the error.
- **Pause and Resume:** Pause remembers the part; Resume restarts that part. Browsers and Android can't pause mid-sentence reliably.
- **Accessibility:** controls have accessible names.
- **Reduced motion:** respected; the only visual effect is the bar's glow.
- **Android:** the speed is also in Settings → Voice & audio.

## Reliability
- **One voice at a time:** every start cancels what was playing and bumps a generation number, so a late event from older speech can't advance or duplicate the new one. Pressing Listen twice still gives one voice.
- **Android TTS start-up:** a press while the engine is still starting is queued, never a second engine.
- **Owner tag:** News controls only show and control News playback, not chat read-aloud.
- **Playback stops when:**
  - a new briefing or summary is created;
  - the result is closed;
  - you leave News.
- **Errors in plain words:** blocked by the browser ("Click Listen again"), no voice installed, or no TTS engine on the phone (with how to install one).

## Platform support and limitations
- **Desktop/web:** works in Chromium browsers (Edge/Chrome) and Firefox with `speechSynthesis`.
  - Speech must start from a click. A scripted start is blocked by the browser, and is reported.
  - If the browser has no speech synthesis, the bar says so and stays disabled.
- **Android:**
  - Uses the phone's TTS engine, for example Speech Services by Google. Offline voices depend on the phone.
  - **Background playback is not claimed:** there is no foreground media service. Android's TTS service often keeps speaking queued parts when the screen locks, but this was **not tested on a device**.
  - Leaving News stops playback by design.

## Tests
| Test | Kind | Result |
|---|---|---|
| `test_pwa_behaviour.py` news audio tests (4) | the real `speechChunks` and `makeReader` from `app.js` in Node, with a fake speech engine | start, order, pause, a stale event ignored, resume, no duplicate voice, next/previous item, speed, end, replay, stop, synthesis error, unavailable, empty, no AI request, stop on leaving News |
| `stability_e2e.mjs` | real Edge, real browser speech, live briefing on Ollama | Listen plays (22 items), **0 new AI requests**, Pause → Resume → Next, leaving News stops it |
| `V49Test.kt` `NewsAudioTest` | Android JVM | parts per item, no citations or links, bounded parts, owner scoping, one engine, stop on leave or new content |
| Physical Android device | – | **not run (no device connected)** |
