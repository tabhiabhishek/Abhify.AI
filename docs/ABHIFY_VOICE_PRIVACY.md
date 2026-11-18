# Abhify voice privacy (Android 5.4)

| Data | Where | Leaves the phone? |
|---|---|---|
| Microphone audio | handled by Android's speech recogniser only while the listening indicator is on; **never stored by Abhify** | on-device recognition when the phone has it; otherwise the system recogniser may use Google's service (that's Android's speech service, shown in Settings → Voice & Conversation) |
| Transcripts (what you said) | an ordinary chat message in app-private SQLite | only as part of a question to an online AI, under your AI mode and consent |
| Spoken answers | generated on the phone by the TTS engine; online voices (marked "online") use the engine's network service | the answer text, for online voices only |
| Voice settings, dismissed suggestions | app preferences | no |
| Memories | app-private SQLite; suggestions need approval; review, edit, delete | only if "Also send my memories to the online AI" is on |
| Logs | `AbhifyVoice`: **timings only**, no words, no audio | no |

- **No new cloud sync.** Optional sync (existing, off by default) syncs only the kinds you choose.
- **Deletion:** Privacy → Conversation data. Each action is user-initiated and confirmed, and scoped (see [ABHIFY_PERSONAL_VOICE_ASSISTANT.md](ABHIFY_PERSONAL_VOICE_ASSISTANT.md) §3).
- **App actions:** only from your words. Sensitive actions only on confirmation screens. Instructions inside documents or news can't trigger anything ([ABHIFY_APP_ACTION_REGISTRY.md](ABHIFY_APP_ACTION_REGISTRY.md)).
- **Screen help:** uses the open screen's name and purpose; no screenshots, nothing outside Abhify.
- **Spoken notifications:** off by default, foreground only, quiet hours, no content read.
