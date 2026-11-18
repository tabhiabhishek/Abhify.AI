# AI provider configuration (Abhify.AI Android 3.4)

## Default: the phone's own model
- **The model.** Abhify's default model is **Qwen2.5 running on the phone** (llama.cpp). It works offline, and nothing leaves the device.
- **New installs.** The default AI mode is **Device**.
- **Updates from 3.3 or earlier.** The previous mode (normally **Auto**) is kept. You can change it in Settings → AI mode.

A cloud provider is **optional**. The app is fully usable without one.

## Adding an online provider (optional)
1. Allow online AI: **Settings → Privacy & data deletion**.
2. Add a provider: **Settings → AI providers**, then choose a provider and paste **your own** API key.
   - The key is encrypted with an AES-256-GCM key held in the Android Keystore.
   - It is never displayed again, never logged, and never included in backups or in Drive uploads.
3. Choose the AI mode (for example **Auto**, where Abhify picks per request, or the phone only).

No keys are built into the app. The repository has none either, as checked by a scan of both.

## What an online provider receives
**⚙ Online context** (in the chat bar) controls this. The line above the message box always says where the next answer will come from and what is sent.

| Data | Sent online? |
|---|---|
| Your current question | Yes, when an online provider answers |
| Recent messages of this chat | Only if "Recent messages of this chat" is on (off by default) |
| Personal memories | Only if "Relevant personal memories" is on, and then only the relevant few |
| Selected document passages | Only after the "Send document passages online?" dialog, per selection |
| Attached file | Only after its own consent dialog |
| Anything in a private chat | Never online |

## No silent fallback
- **When the local model fails** (for example, out of memory or the model is not installed), the answer shows an error. Abhify does **not** switch to a cloud provider by itself.
- **Asking online instead.** If an online provider is configured, the failed answer offers **"Ask <provider> instead…"**. Tapping it shows the consent dialog first.
- **When the online provider fails,** the error is shown. There is no hidden retry on another provider unless you configured "fallback providers" yourself.

## Source labels
Every answer is labelled with where it came from: 📱 This phone (model name), or ☁ the provider name.
