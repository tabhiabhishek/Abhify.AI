# RC.4: voice input and read-aloud

## Speaking a question

1. Tap 🎙. The first time, Android asks for microphone permission. If you refuse, the app says "Microphone permission was not given." and nothing else happens.
2. While you speak, the line "🎙 Listening… *words*" shows the partial text. Tap **Cancel** to throw the recording away, or ■ to stop.
3. The recognised words **go into the message box**. You can edit them, then tap ➤ to send. RC.4 never sends what you said automatically. RC.3 did.

Where recognition happens:

- **Android 12 and later, with an on-device recogniser:** speech is recognised on the phone.
- **Otherwise:** Android's default recogniser is used, set to "prefer offline". It may use Google's online service unless an offline language pack is installed.

**Settings → Voice** shows which recogniser your phone uses.

| Message | Meaning |
|---|---|
| Didn't catch that. Try again. | No speech was recognised, or listening timed out. |
| Speech recognition needs the internet on this phone… | There is no offline language pack. Install one in Android settings, under speech recognition / offline languages. |
| This phone has no speech recognizer. | Install Google's speech services (or another recogniser). |

## Read-aloud (text to speech)

- Tap **🔊 Read** on any answer, or turn on **Settings → Voice → Read answers aloud**.
- A "🔊 Reading the answer aloud · **Stop**" bar appears while it plays. Sending a new question also stops it.
- Markdown symbols and code are left out of the spoken text, and up to 3,000 characters are read.
- If the phone has no text-to-speech engine, the app shows: "This phone has no text-to-speech engine. Install one (for example Speech Services by Google) in Android settings."
- If your language isn't available, US English is used.
