# On-device AI ("Abhify Lite"): the AI on each person's own phone (2.0.0-dev.7)

Abhify Lite runs a small AI model **inside the phone's browser, on the phone's own graphics chip**. No PC is involved. Each person installs the same web app (PWA) and downloads the model once; after that, chat works with no network at all, and nothing anyone types leaves their phone.

It is a mode of the existing app, not a separate app: **Settings → Where the AI runs → This device**. The default is still **My computer**, so nothing changes for anyone who doesn't pick it.

## What you get, and what you don't

| | My computer (Modes A/B) | This device (Abhify Lite) |
|---|---|---|
| Where the AI runs | Your PC (Ollama) | This phone's GPU, in the browser (WebLLM + WebGPU) |
| Needs a PC | Yes | **No** |
| Model | qwen2.5 1.5B–7B (by PC profile) | Qwen 2.5 1.5B (6 GB+ phones) or Llama 3.2 1B (others, and iPhones) |
| One-time download | n/a on the phone | about **869 MB** (Qwen) or **695 MB** (Llama), only after you confirm |
| Works offline | Only on the PC's network | **Yes**, after the one-time download |
| Chat + "remember that …" memories | ✅ | ✅ (stored in this browser) |
| Search your documents | ✅ | ❌ (no knowledge base on the phone) |
| Fresh news, new quiz/question generation | ✅ | ❌ (saved copies and built-in samples) |
| Answer quality | Better (bigger models) | Good for explanations and Q&A; small models make more mistakes |

## Requirements
- **Android:** Chrome 121 or newer on Android 12+, with a recent Qualcomm Adreno or ARM Mali GPU. In practice: phones from about 2021 onwards with 4 GB of RAM or more (6 GB+ gets the better model).
- **iPhone / iPad:** Safari on **iOS 26 or newer** (the first version with WebGPU on by default). Older iOS: use My computer mode.
- About **1 GB of free storage** and, while chatting, about 0.9–1.6 GB of memory for the model.
- **HTTPS.** Browsers allow WebGPU, installing the app and offline caching only on HTTPS pages (or `localhost`). See *Hosting* below.

If the phone can't run it, the app says so honestly (*On-device AI not supported here*, with the reason) and the user can keep using My computer mode.

## How it works for a user
1. Open the app (from the HTTPS address you share), **Settings → Where the AI runs → This device → Save**.
2. The banner shows *On-device AI not set up* with the exact download size. Tap **Set up…**.
3. A confirmation shows the model, the size, the memory it uses and where it comes from. Nothing is downloaded before **OK**. If the browser has too little storage, it stops before downloading and says so.
4. Progress shows in the status bar. When it says **AI ready · runs on this device**, chat works.
5. Next time: the status says *AI on this device · starts when you chat*; the model loads from the phone's storage on the first message (a few seconds, no network).
6. **Settings → Remove on-device AI model** frees the space (chats are kept). **Delete on-device chats** erases chats and memories.

## Status bar (this device mode)

| Status | Meaning |
|---|---|
| **AI ready · runs on this device** | The model is loaded on this phone's GPU |
| **AI on this device · starts when you chat** | Downloaded; loads on the first message |
| **On-device AI not set up** | Not downloaded yet; tap *Set up…* |
| **Downloading the AI model · N%** / **Starting the on-device AI…** | In progress |
| **On-device AI not supported here** | No WebGPU, or too little memory; the banner says which |
| **On-device AI failed to start** | The error is shown, with *Try again* |

## Privacy and security
- **Nothing you type leaves the phone.** The chat path calls no server at all (enforced by `test_ondevice.py`).
- **Chats and memories** are stored in this browser's IndexedDB on this phone. They're removed by *Delete on-device chats*, or by clearing the site's data in the browser.
- **Code is served by this app, not fetched from third parties.** The WebLLM engine and the two model libraries (WebAssembly) are vendored in `frontend/vendor/webllm/` with pinned SHA-256 hashes (`VENDORED.md`, checked by tests).
- **The only download** is the model weights (data, not code), from `huggingface.co/mlc-ai` (the publisher of these WebLLM builds) over HTTPS, only after the user confirms. WebLLM stores them in the browser's cache, and the app asks the browser to keep them (`navigator.storage.persist()`).
- No account, no server, no telemetry.

## Hosting (needed before anyone else can use it)
Every phone needs to load the app once from an **HTTPS** address. After that it runs offline from the phone's cache. Options:
- **A free static host** (GitHub Pages, Cloudflare Pages, Netlify): publish the `frontend/` folder. The host only serves files: it never sees chats or data. The vendored engine is about 17 MB; model weights come from Hugging Face, not from your host.
- **Tailscale Serve from your PC** (already documented): works for people on your tailnet.

**Not done:** nothing has been published or deployed. Choosing and setting up a host needs the owner's decision and account.

## Testing status
- Logic tests (`backend/tests/test_ondevice.py`): vendored hashes, model choice, prompts, memory commands, status texts, consent before download, no network calls in the chat path, PC mode unchanged.
- **Desktop browser, real GPU (2026-10-26): 14/14 PASS.** Chrome (headless, temporary profile) on this development PC's NVIDIA Ampere GPU via WebGPU, driving the real app UI; app served by the backend with an isolated data folder; everything deleted afterwards. Results: `build/ondevice-desktop-result.json`.
  - Default stays PC mode; switching to *This device* shows *not set up*; **no request to Hugging Face before consent**; the dialog shows 869 MB, 1630 MB and the source.
  - Real download + load of Qwen 2.5 1.5B: **92 s**; first answer **10.2 s**; "remember that …" saved and used in the next answer; 6 messages + 1 memory in IndexedDB.
  - **Offline:** the app's server stopped *and* the network cut, page reloaded: the app opened from cache, showed the 6 earlier messages, loaded the model from storage and answered in **4.8 s**, with **zero** external requests.
  - *Remove model*: confirm dialog, cache emptied, status back to *not set up*.
  - This is a desktop GPU, not a phone: it proves the code path, not phone speed, memory limits or browser support.
- **Real phones: NOT TESTED.** Run [MOBILE_ACCEPTANCE_TEST.md](MOBILE_ACCEPTANCE_TEST.md) section *On-device AI* on a real Android phone and iPhone before relying on it.
