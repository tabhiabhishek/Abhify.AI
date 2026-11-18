# Abhify.AI Personal Voice Companion: Phase 0 audit (2026-11-18)

Inspected before any voice change (Android 5.3 source, desktop 2.0.0-dev.8 + 5.2 parity). No device was connected; nothing here was tested on a phone.

## 1. What already exists (reusable)

| Capability | Android | Desktop/Web |
|---|---|---|
| Speech-to-text | `voice/Voice.kt` → in-app `SpeechRecognizer` (on-device recogniser when the phone has one, else the system recogniser preferring offline), **live partial results**, error messages; `vm.startVoice` with model warm-up | Whisper on the PC (`/transcribe`) or browser STT; `/chat/warm` |
| Text-to-speech | `Voice.speakParts`: split into parts, **stop / pause / resume / skip**, rate (`setRate`), owner (chat/news), progress, Hindi voice selection (5.3) | Web Speech `speechSynthesis` (news Listen, answers) |
| Read answers aloud | `g.readAloud` (Settings → Voice & audio), reading speed (not persisted across restarts) | per-answer 🔊 |
| Conversation storage | SQLite `ChatDb` in app-private storage (`conversations`, `messages`; soft delete blanks content); History screen (search, export .md/.txt/.pdf, delete) | SQLite in the PC data folder (`conversations`, `messages`) |
| Personal memory | `memories` + `MemoryKit` (relevant only, suggestions need approval, duplicates, contradictions, sensitive refusal), Personal memory screen (edit, delete, export) | `memory_service.py`, Memory & history page |
| Deletion | `ChatDb.deleteAll()` (chats + memories), per-conversation delete, delete-all studio data (Privacy screen) | memory delete, retention days |
| Cloud sync | optional account + sync, **off by default**, user-chosen kinds | none (phone access is LAN, owner-approved) |
| Recommendations | 5.1/5.2 `Suggestions` + `ContinueWorking` (projects, to-dos, study, corrections, failed tasks; dismissals kept) | `/suggestions` |
| Neural core | `AiOrb`: Idle, Listening, Searching, Thinking, Responding, Offline, Error (5.3) | `.orb` idle/listening/thinking/generating/error |
| AI routing | `HybridAi`/`RoutePolicy`: Gemini, Groq, OpenRouter (user keys), Abhify server, local Qwen; consent; explanation | Ollama (qwen2.5, llama3.2) |
| Navigation targets | `AppViewModel.Tab` (five destinations) + `Route` | tabs/sheets, launcher, Ctrl+K palette |
| Notifications | `POST_NOTIFICATIONS` (model download, backup) | – |
| Permissions | `RECORD_AUDIO` already declared and requested at first mic use | browser permission |

## 2. Missing (to build)

1. Voice & Conversation settings: voice choice from the engine's real voices + preview, tone, speed presets (persisted), language for recognition / reply / speech, response mode, interruption, auto-listen, spoken notifications, privacy notes.
2. Hands-free **conversation mode**: listen → answer (streamed text) → speak → listen again; stop; interrupt by tapping while speaking.
3. Typed **App Action Registry** for voice commands (navigation, read-only answers, reversible creations with confirmation + undo, sensitive actions only through confirmation screens).
4. **Conversation Data** controls in Privacy: storage explanation, counts, clear conversations / memories / all conversational data (with confirmation), links to History and Memory.
5. Spoken recommendations ("What should I do today?") and screen help ("What is this screen?") from real records only.
6. Optional spoken notifications (off by default, quiet hours).
7. Neural core states: Processing, Speaking, Interrupted.

## 3. Platform limitations (honest)

- **Voice gender:** Android's TTS API reports voice name, locale, quality and features. It does **not** report gender. Abhify picks a voice the engine marks as female (a name or feature containing "female") when one exists; otherwise it uses the engine's default voice for the language. You can preview and choose from the real list. Voices are never invented.
- **Tone:** the speech engine has no "warm/professional" control. Tone changes **how answers are written** (spoken style); it does not change the voice's sound.
- **Voice interruption while speaking:** Android's `SpeechRecognizer` can't reliably listen while the phone itself is speaking (echo and audio focus). Interruption is therefore **tap-to-interrupt**: tapping the mic or core stops speech at once and listens. Automatic barge-in is not implemented.
- **Wake phrase:** reliable hands-free wake words need continuous microphone access, either through a foreground service or an always-running recogniser. On Android that means battery use, a permanent notification, and recognisers that beep on each restart. **Not implemented**; tap-to-talk is kept. This follows the prompt: no fake wake word.
- **Microphone / speaker selection:** neither the speech recogniser nor TTS lets an app pick devices. Android routes to Bluetooth or wired headsets automatically.
- **Hindi recognition:** depends on the phone's speech services. If Hindi isn't installed or available, the recogniser's error is shown.
- **Desktop:** browser STT and Web Speech TTS vary by browser. Desktop voice-companion parity is planned, not built in this milestone.

## 4. Dependencies

None new. Everything uses existing Android APIs (`SpeechRecognizer`, `TextToSpeech`, `AudioManager`), the existing AI routing and existing storage.

## 5. Security and privacy

- **Audio:** recorded only while the listening indicator is on, and only by the system recogniser. No audio is stored by Abhify.
- **Transcripts:** become ordinary chat messages in app-private SQLite, excluded from Android cloud backup. Content is never logged; the voice logs hold timings only.
- **Online AI:** gets voice questions only under the existing routing and consent rules (shown in "Online context").
- **App actions** come only from **the user's own spoken words**, matched by a deterministic parser in the trusted layer. AI output and document text can never trigger an action. Parameters are validated. Sensitive actions are never executed by voice: they open the confirmation screen.
- **Deletion** is always user-initiated and confirmed, and scoped: conversation data never touches documents, models, projects or keys.

## 6. Regression risks

| Risk | Mitigation |
|---|---|
| Changing the answer path (speaking after answers) | conversation mode is opt-in; typed chat keeps the "Read answers aloud" setting |
| Voice settings changing existing speech | defaults reproduce today's behaviour except the persisted rate |
| New orb states | added states; existing mapping unchanged |
| Deletion bugs | new functions are tested on a real SQLite schema; confirmation dialogs; separate from studio data deletion |

## 7. Implementation sequence

See [ABHIFY_PERSONAL_VOICE_ASSISTANT_PLAN.md](ABHIFY_PERSONAL_VOICE_ASSISTANT_PLAN.md).
