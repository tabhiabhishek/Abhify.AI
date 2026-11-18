# Abhify.AI: new phone setup (3.1)

## Fresh installation

On first start, Abhify shows Welcome, then Profile (or sign-in), then **Setup**. Every setup step can be skipped, and nothing is downloaded or enabled unless you choose it.

| Step | What you see or do |
|---|---|
| 1. This phone | Facts measured on the phone: Android version, RAM total and free, storage, processor, installed model, the recommended model from the compatibility check, OCR availability, internet, processing profile. |
| 2. Where answers come from | **On this phone only**: no key or account. **Online with my own key**: no Abhify account. **Auto.** |
| 3. AI on this phone (not shown for online-only) | The installed model, or the recommended one with its size. Opens the normal **AI models** screen: download (asks first, checks storage, progress, cancel, retry, SHA-256 verification), self-test, select. |
| 4. Online AI (not shown for local-only) | Opens the normal **AI providers** screen: Gemini, Groq, OpenRouter. Your own key; validation lists models from the provider; masked display; select, test, remove. |
| 5. Reading scans and photos | ML Kit (included, no download): Ask each time / Allow / Off, and an OCR self-test. |
| 6. Privacy and memory | Allow online AI, share memories, processing profile (Auto / Low / Balanced / Quality). |
| 7. Ready | A summary, and an honest readiness line ("Not ready yet: download a model…" when nothing can answer). |

- **Finish** or **Skip setup** opens the app. Setup can always be finished without an API key.
- **Settings → Run setup again** repeats the steps over the current configuration. It never resets anything.

## Existing installation and upgrades

- The setup-completed flag is the same `onboarded` preference RC-era builds already set. An upgrade (`adb install -r`, same signing key) keeps it, so **no onboarding is forced**.
- Model files, the selected model, provider keys (Keystore), privacy choices, chats and documents are untouched.
- The database migrates additively from v5 to v6.

## Each phone is independent

- Keys are typed on that phone, sealed with that phone's Android Keystore, never synced, exported or sent to the Abhify server.
- A new install starts with no keys (tested: `SetupTest`, `ProviderStoreTest.aSecondDeviceStartsWithNoKeys`).
- The model is chosen per phone from the catalog by that phone's own compatibility check. Nothing about the owner's phone is hard-coded.

## Troubleshooting

| Problem | What to do |
|---|---|
| "Not ready yet" | Download a model (Settings → Models on this phone) or add a provider key (Settings → AI providers). |
| Model download refused | Not enough free storage, or no compatible model for this phone's memory. The screen says which. |
| Key rejected | AI providers shows the provider's reason (for example "Groq rejected the API key"). Other settings stay. |
| Scanned PDF or photo not read | Text recognition is Off or was skipped. Settings → Documents and text recognition → Allow, then add the file again. |
| "Please wait: … is running" | One heavy job at a time (OCR or the phone's AI). Wait or cancel. |
| Memory warning | Close other apps, or pick the Low profile. |

## What is tested

| Kind | Status |
|---|---|
| Unit tested (JVM) | setup steps per mode; first-install vs upgrade decision; readiness text; device report uses only measured facts; no key code in setup, resume or OCR files; a new store has no keys |
| Built, not run | the full flow on a device |
| **Not tested** | fresh install and upgrade on a physical phone (none connected) |
