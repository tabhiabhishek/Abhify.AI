# Abhify Android 2.0.0-rc.4: phone acceptance checklist

**Build:** `Abhify-2.0.0-rc.4-debug.apk` (versionCode 2000004). It is a debug build, not a release.

**Install over RC.3:**

```
adb install -r Abhify-2.0.0-rc.4-debug.apk
```

It is signed with the same debug key as RC.3 (certificate SHA-256 `1d2d1fdb…e6246f`), so the upgrade keeps chats, memories, keys and the model. Do **not** uninstall the app and do **not** clear its data.

**Status:** every item below is **PENDING**. None of them has been run on a phone yet. Mark each one PASS or FAIL, and add a note for every FAIL.

## Upgrade and data

| # | Check | Expected | Result |
|---|---|---|---|
| 1 | Install over RC.3 with `install -r` | It installs without asking you to uninstall. The old chats, memories and the downloaded model are all still there. | |
| 2 | Open AI models | The same local Qwen model is active, and **Self-test** passes. | |
| 3 | Airplane mode, **On this phone** mode, ask a question | The answer is labelled 📱 On this phone, and nothing tries to go online. | |

## Online providers without an Abhify account (P0)

| # | Check | Expected | Result |
|---|---|---|---|
| 4 | Signed out of Abhify. Open AI Providers, add a Groq key, then **Validate & save**. | You see "Key works · N free models". The provider is marked **✓ selected for Online**. | |
| 5 | Switch the chat header to **Online** and ask a question | The answer is labelled ☁ Groq · *model*. No sign-in screen appears and there is no mention of the Abhify server. | |
| 6 | Repeat 4–5 with a Gemini key, then with an OpenRouter key | Each answer is labelled with its own provider. **Use for Online** switches the selected provider. | |
| 7 | Remove every key, or select none. Use **Online**. | The chat shows exactly "No online provider configured. Open AI Providers to add or select a provider.", with an **Open AI Providers** button. | |
| 8 | Save a wrong key (change one character) | You see "Groq rejected the API key. Check the key in AI Providers." The key is never shown in full. | |
| 9 | **Online** mode with Wi-Fi and data off | You see "You're offline. *Provider* needs internet…". The app does not silently switch to another provider. | |
| 10 | **Auto** mode with the selected provider failing (for example a bad key), and **Try other providers** off | The provider's error is shown. No other provider receives the question. The phone model answers only if "Fall back to this phone's AI" is on, and a notice says so. | |
| 11 | Home screen chips | Mode, Provider, Local model and Abhify account are shown separately. "No Abhify account (optional)" is not shown as an error. | |
| 12 | AI Providers → **Copy diagnostics (no keys)**, then paste it into a note | It shows the version, mode, selection and per-provider status, and no key text. | |

## Readability (P1)

| # | Check | Expected | Result |
|---|---|---|---|
| 13 | Dark theme: home, chat, drawer, bottom navigation, AI Providers and Settings | No black or dark text on navy anywhere. The selected tab is obvious. | |
| 14 | Android Settings → Display → Font size at maximum (and display size large) | The text wraps and buttons grow to fit. Nothing is cut off or overlapping. | |

## Voice and documents (P2)

| # | Check | Expected | Result |
|---|---|---|---|
| 15 | Tap 🎙 and speak | The words fill the message box. Nothing is sent until you tap ➤. **Cancel** stops listening. | |
| 16 | **🔊 Read** on an answer | It reads aloud, and a "Reading the answer aloud · **Stop**" bar appears. Stop ends it at once. | |
| 17 | 📎 → pick a `.txt` or `.docx` file, then ask about it in **On this phone** mode | The document chip shows its name and size. **View** shows its text. The answer uses the document, and nothing leaves the phone. | |
| 18 | The same document in **Online** mode | A "Send this document online?" dialog names the provider. **No** sends nothing. **Send** answers. | |
| 19 | Pick a PDF or a photo | A clear "isn't available yet" message appears. It does not crash and gives no fake result. | |

## Controls (P3)

| # | Check | Expected | Result |
|---|---|---|---|
| 20 | Settings → Answer style "Concise", plus custom instructions → **Preview**. Turn custom instructions off. Memories → **Delete all**, then confirm. | The preview shows the exact instructions. Off stops them being added, but the text is kept. Delete all empties the list and leaves chats untouched. | |

## Optional: automatic database-upgrade test

`RUN-TESTS.cmd` in the kit runs only `DbMigrationTest`. That test uses its own scratch database, so it does not touch your chats, keys or model. The full RC.3 suite is not included, because its `removeModel` step deletes the downloaded model.
