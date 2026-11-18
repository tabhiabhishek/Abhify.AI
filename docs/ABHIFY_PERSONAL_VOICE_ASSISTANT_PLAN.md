# Abhify.AI Personal Voice Companion: implementation plan

Android first, because the owner tests on the phone; the result is build 5.4. Desktop/web parity follows as a separate step. Each phase is tested before the next.

| Phase | Android work | Files |
|---|---|---|
| 1 Voice settings | `VoiceSettings` (pure, persisted in preferences): enabled, voice name, tone, speed preset + custom rate, language (EN / HI / both / auto), response mode, interruption, auto-listen, spoken notifications + quiet hours, screen help. Settings → **Voice & Conversation** with the engine's real voice list, preview, female-marked default when available | `voice/VoiceSettings.kt`, `voice/Voice.kt`, `ui/VoiceScreens.kt`, `ui/ManageScreens.kt` |
| 2 Conversation | hands-free loop in the view model; live transcript; streamed answer; spoken reply in conversation mode; tap-to-interrupt; stop / pause / resume; transcripts stored as the chat (local) | `ui/VoiceState.kt`, `ui/AppViewModel.kt` |
| 3 Wake phrase | **not built** (platform limits documented); tap-to-talk kept; setting shows why | `ui/VoiceScreens.kt` |
| 4 App actions | `AppActions` registry (typed, validated, permission classes, confirmation, actual result); English + Hindi phrases; executed only from the user's words | `voice/AppActions.kt`, `ui/VoiceState.kt` |
| 5 Conversation data | Privacy → Conversation data: location, counts, clear conversations / memories / all conversational data (confirmed), links to History and Memory | `chat/V54Db.kt`, `ui/ManageScreens.kt` |
| 6 Recommendations + screen help | spoken "what should I do today" from Suggestions / Continue working; "what is this screen" from the registry's screen descriptions | `voice/AppActions.kt` |
| 7 Offline / online | existing routing; voice works with local Qwen + on-device recognition when installed; honest messages | – |
| 8 Spoken notifications | off by default; AI-task-finished notice spoken only in the foreground, outside quiet hours, without content | `ui/TaskState.kt` |
| 9 Neural interface | orb states Processing, Speaking, Interrupted; mic level waveform from the recogniser's real RMS | `ui/Aurora.kt`, `ui/VoiceScreens.kt` |
| 10 Tests, docs, build | `V54Test.kt`; docs; `build/Abhify-5.4.apk` (new file; earlier APKs kept) | – |
