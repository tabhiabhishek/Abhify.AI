# Abhify.AI Personal Voice Companion (Android 5.4, 2026-11-18)

| | |
|---|---|
| Build | `build/Abhify-5.4.apk`, versionCode 5040099, 73,904,909 bytes, SHA-256 `c176c90b9ee925fbd429c0af43f1cd4108464a0495d63a99f4e02446e9a82330` |
| Signing | same debug certificate as 5.0–5.3, so it installs over 5.3 and keeps data |
| Permissions | the same 10 as before (`RECORD_AUDIO` was already declared) |
| Kept | 5.0, 5.1, 5.3 APKs; database schema; providers; Local RAG; all features |

Audit: [ABHIFY_PERSONAL_VOICE_ASSISTANT_AUDIT.md](ABHIFY_PERSONAL_VOICE_ASSISTANT_AUDIT.md). Plan: [ABHIFY_PERSONAL_VOICE_ASSISTANT_PLAN.md](ABHIFY_PERSONAL_VOICE_ASSISTANT_PLAN.md). Actions: [ABHIFY_APP_ACTION_REGISTRY.md](ABHIFY_APP_ACTION_REGISTRY.md). Privacy: [ABHIFY_VOICE_PRIVACY.md](ABHIFY_VOICE_PRIVACY.md). Tests: [ABHIFY_VOICE_TESTING.md](ABHIFY_VOICE_TESTING.md).

**Physical-device testing: not performed.** No phone could be connected (Remote Desktop and a different network). The owner tests manually.

## 1. How it works

```
Assistant → 🗣 Talk ─► conversation panel over the app (neural core, live transcript, waveform, End / Pause / Interrupt)
   listen (SpeechRecognizer: on-device when available; language from settings; live partial text; mic level → waveform)
     ├─ the words match a registered App Action? → trusted executor (VoiceState) → real result shown + spoken
     │      navigation/read-only: run · reversible: Confirm/Cancel + Undo · sensitive: open Privacy (confirm there)
     └─ otherwise: an ordinary chat message (same routing, consent, memory and RAG rules; streamed; stored locally)
            answer written on screen, spoken in spoken style/tone/language (TextToSpeech, chosen voice, audio focus ducking)
   speech ends → listens again (if "Keep listening") · tap the core while speaking → stops at once and listens (INTERRUPTED)
```

**Neural core states:**
- Idle, Listening (with a real mic-level waveform), Processing (words received).
- Searching (documents being searched), Thinking, Responding, Speaking.
- Interrupted, Offline, Error.

Each state is set only by the matching real activity.

## 2. Settings → Voice & Conversation (saved; applied immediately)

| Setting | Default | Real effect |
|---|---|---|
| Voice assistant | on | shows 🗣 Talk |
| Voice | Automatic: a voice the engine **marks** female if it has one, else the engine's default | `TextToSpeech.setVoice`; list = the engine's installed voices for the language, with Preview |
| Tone | Warm and friendly | wording of spoken answers (prompt instruction); the voice's sound is not changed (no engine control) |
| Speed | Natural (1.0×); Slow 0.8, Fast 1.25, Custom 0.5–2.0 | `setSpeechRate`; also Read aloud and News Listen; now saved |
| Language | English + हिन्दी | recognition language (English → en-IN, Hindi → hi-IN, both/auto → phone language), reply language, Hindi voice for Hindi text |
| Response | Spoken + text | Voice only (panel shows status only; chat keeps the text) · Text only (no speech) |
| Allow interrupting | on | tap core / Interrupt while speaking; End always works |
| Keep listening after each answer | on | natural turn-taking |
| Read typed answers aloud | off (existing setting) | typed chat unchanged |
| Screen help | on | "What is this screen?" uses the open screen's name/purpose only |
| Spoken notifications | **off** | "Your AI task has finished." while the app is open, outside 22:00–07:00, no content |
| Wake phrase | **not available** | explained in Settings (see limitations) |

## 3. Local conversation data

- Chats and voice transcripts live in the existing SQLite database in app-private storage. They are excluded from Android cloud backup and survive updates.
- Voice settings live in the app's preferences.
- **Privacy → Conversation data** offers:
  - a storage explanation;
  - counts;
  - Manage conversations (History) and Review memories;
  - **Clear all conversations**, **Clear all memories** and **Clear all conversational data** (chats, memories, corrections history, dismissed suggestions, voice settings).

  Each clear is confirmed. Content is blanked, not just hidden. Documents, projects, study and career data, models and keys are never touched.
- No new cloud sync. The existing optional sync stays off unless you turn it on.

## 4. Limitations (honest)

- **Gender:** Android's TTS API has no gender field. "Female" is shown only where the engine labels a voice.
- **Automatic barge-in:** listening while the phone itself speaks isn't reliable with Android's recogniser. Interruption is by tap.
- **Wake phrase:** not built, because it would need an always-on microphone with a permanent notification and battery cost. Tap-to-talk is kept.
- **Microphone and speaker:** chosen by Android (headsets are used automatically).
- **Hindi:** recognition and voice need Hindi installed in the phone's speech services; otherwise an error or message appears. Android recognises one language per turn.
- **Online answers:** voice questions go to the configured provider only under the existing AI mode and consent. This is processing, not storage.
- **Desktop/web:** voice companion parity is not built in this milestone. The desktop keeps its existing voice: Whisper/browser STT and per-answer speech.
- **Not measured:** latency (speech recognition, first token, TTS start, interruption), RAM and battery. Timing logs (`adb logcat -s AbhifyVoice`, timings only) are ready for a phone session.
