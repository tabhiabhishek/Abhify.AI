# Talk to Me: voice architecture (5.6)

## The pipeline
```
tap Speak ─► SpeechRecognizer (on-device when available; language from Voice settings: English / Hindi)
          ─► partial results → live transcript in the panel (vm.heard) · real mic level → waveform
          ─► final text ─► AppActions.match (the user's own words only)
                ├─ registered action: READ → run and speak · REVERSIBLE → Confirm/Cancel on screen · SENSITIVE → never by voice
                └─ question → vm.send (same chat path: Companion layer, context finder, routing, consent)
          ─► tokens stream ─► SpeechStream (5.6): each completed sentence → Voice.appendParts (TTS QUEUE_ADD, in order)
          ─► answer done ─► the remaining tail is spoken ─► speech ends ─► auto-listen next turn (if on, not in quiet pause)
```

## What 5.6 changed
| Stage | Before | 5.6 |
|---|---|---|
| Speaking | Started only after the **whole** answer was generated | **Streaming:** speech starts after the first sentence (at least 40 characters, so there are no tiny fragments). Sentences play in order through one TTS queue, so voices never overlap. Code blocks are never read halfway through. Citation marks like `[S1]` are not spoken. |
| Interruption | Stopped speech only; the answer kept generating and was spoken again at the end | `interrupt()` does all of this at once. Repeated taps are safe. 1. Cancels the turn's `SpeechStream`, so late text is never spoken. 2. Clears `awaitingAnswer`, so the cancelled answer isn't spoken again. 3. Stops TTS. 4. Stops generation; the text so far is kept in the chat. 5. Shows "Interrupted" and listens again. |
| Turns | No turn identity | One `SpeechStream` per turn (`turnSeq`). A new turn cancels the previous stream. |
| Audio focus | Speech ducked other audio | A phone call or another app taking focus **pauses** Abhify's speech (resumable), instead of talking over it. |
| Background | The loop could stay open | The conversation **ends when the app goes to the background** (`ON_STOP`), so there is no microphone or speech behind the user's back. |
| Panel | State, transcript and answer | Adds "You: …" (the last heard turn). The button reads **Interrupt** while Abhify is speaking or still answering. The core is tappable to interrupt. |
| Hindi | Language from settings; Hindi voice chosen for Devanagari; Hindi commands for navigation only | Sentence splitting also understands the Devanagari danda (।). **Hindi and Hinglish (romanised) commands** now cover plan my day, briefing, project summary and blockers, weekly review, open Study and open News: for example "aaj ka plan batao", "project mein kya atka hai", "मुझे ब्रीफिंग सुनाओ". All are read-only; unclear speech falls through to an ordinary question. The selected language is never changed silently. |
| Panel state | ERROR shown only for engine problems | A real voice failure (microphone, recogniser, TTS) shows as **Needs attention** on the core. |

## Safety (unchanged)
- No wake word and no always-on listening: tap-to-talk only, and the microphone is used only while the listening indicator shows. No `FOREGROUND_SERVICE_MICROPHONE` permission.
- Unclear speech never authorises a change: reversible actions need an on-screen Confirm, and sensitive ones are never run by voice.
- Errors are spoken or shown in plain words: microphone permission, no recogniser, network needed for recognition, no TTS engine, no Hindi voice.

## Visual states
The neural core follows the real pipeline states only: idle, listening (moving with the real mic level), processing, thinking, searching, speaking, interrupted, offline, error. No fake animation.

## Not done (and why)
- **Barge-in by voice while speaking:** needs the microphone open during playback, which means echo cancellation and constant listening. Deferred; interruption is a tap.
- **Headset unplug (`ACTION_AUDIO_BECOMING_NOISY`):** needs a broadcast receiver. Audio-focus loss already covers calls and other apps.
- **Mixed English/Hindi recognition in one utterance:** Android recognisers take one language tag per session.

## Tests
`SpeechStreamTest` covers:
- order and no repeats;
- the danda;
- code blocks;
- cancellation;
- the interruption contract;
- the queue and focus handling;
- the background stop.

Phone checks are tests 65–68 in the checklist.
