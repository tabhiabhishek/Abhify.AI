# Abhify.AI on Android and iPhone

On phones, Abhify.AI is a **Progressive Web App (PWA)**; there is **no APK and no iOS app**. The full app (Ollama models, ChromaDB, Whisper, the Python backend) runs on a **computer**, and the phone connects to it (Mode B). Since 2.0.0-dev.7 the phone can also run a **small AI model on its own** with no computer at all (Mode C, *Abhify Lite*: [ON_DEVICE_AI.md](ON_DEVICE_AI.md)).

## The three modes

| | Mode A: Windows computer | Mode B: phone connected to your computer | Mode C: phone on its own (Abhify Lite) |
|---|---|---|---|
| Where the AI runs | This PC | Your PC | **This phone's GPU** (small model, in the browser) |
| Chat, memory | ✅ full | ✅ via your PC | ✅ on the phone (one-time ~0.7–0.9 GB download, then offline) |
| Search your documents | ✅ | ✅ via your PC | ❌ |
| News, interview questions, quizzes | ✅ live | ✅ live | Saved copies and built-in samples only |
| Your data lives on | This PC | Your PC (the phone sends questions and receives answers) | The phone's browser storage |
| Setup | Windows installer | Companion access + HTTPS (below) | Open the PWA over HTTPS → Settings → *This device* |

The app never claims the AI runs on your phone unless you chose *This device* and the model is actually loaded. In Mode B the status bar shows exactly one of these (2.0.0-dev.3):

| Status bar | Meaning | What to do |
|---|---|---|
| **AI ready · runs on your computer** | Paired, your PC is reachable, Ollama and the model are ready | Nothing |
| **Not paired** | This phone has no access token | Open the pairing link from your PC, or enter the token in Settings |
| **Not paired · access token rejected** | The token is wrong, or was replaced or turned off on the PC | Pair again from the PC |
| **Companion access is off on your computer** | Your PC answers, but companion access is turned off | Turn it on on the PC |
| **Computer connected · Ollama not running** | Paired and reachable, but Ollama isn't running on the PC | Start Ollama on the PC |
| **Computer connected · AI model not installed** | Paired and reachable, but setup isn't finished on the PC | Finish the setup wizard on the PC |
| **Paired · your computer isn't reachable** | A token is saved, but the PC can't be reached | Check the PC is on, Abhify.AI is running, same Wi-Fi or Tailscale |
| **Connection to your computer lost · retrying** | It was connected, and the connection dropped | Wait; it re-checks every 30 s and when the app comes back to the foreground |
| **Offline mode** | The phone itself has no network | Saved content only |

Whenever the AI isn't usable, a banner under the header explains why.

## Mode B: use Abhify.AI from your phone

### Step 1: turn on companion access (on the computer)
1. In Abhify.AI on the PC, open Settings → **System & data** → **Companion access** → **Turn on**.
2. A random **access token** is shown **once**. Only a hash of it is stored on the PC.
3. Restart Abhify.AI. The launcher then listens on your network instead of only `127.0.0.1`.
   - Windows Firewall may ask whether to allow Python on **private networks**. Allow private, not public.

What changes, and what doesn't:
- Every API call from another device must carry the token. Wrong or missing tokens get `401`.
- **A token only opens your own content:** chat, news, interview questions, quizzes, voice, your documents, memories, preferences, chat history and progress.
- **Everything else stays limited to the PC (`403`), even with a valid token:** system diagnostics, model setup, backups and restore, moving data, the knowledge-folder and watcher controls, update checks, the API docs, and any route added in the future.
- Responses to a phone never contain the token hash, your Windows user name or folder paths. The knowledge folder is shown by name only.
- **Ollama is never exposed.** It keeps listening on its own `127.0.0.1:11434`.
- **Turn it off** at any time with **Turn off**, which revokes the token.

### Step 2: connect the phone

**Option 1: home Wi-Fi, quick test (HTTP).** Open `http://<PC-address>:8000/#pair=<token>` in the phone's browser. The address is shown in Companion access. The `#pair=` part saves the token on the phone and is never sent to the server.
- The link also works when Abhify.AI is **already open** in that tab, and other open windows of the app on the phone pick up the new token automatically.
- "✅ Paired" is only shown after your PC accepts the token. A rejected token says so.
- Prefer typing the token in Settings → Access token if you don't want the link in your browser history.
- Chat, news and quizzes work.
- Browsers only allow **installing** the app and **using the microphone** over **HTTPS**, so over plain HTTP the phone can't install it or use voice.

**Option 2: recommended, private HTTPS with Tailscale (free).**
1. Install [Tailscale](https://tailscale.com/download) on the PC and on the phone, and sign in to the same account on both.
2. On the PC, run `tailscale serve --bg 8000` (use the port from the tray tooltip if Abhify.AI had to choose another one). Tailscale shows an address like `https://your-pc.tailnet-name.ts.net`.
3. On the phone, open `https://your-pc.tailnet-name.ts.net/#pair=<token>`.

What this gets you:
- valid HTTPS, so the app can be installed and the microphone works
- it works away from home
- it's reachable **only by your own devices**: nothing is opened to the internet
- requests arriving through Tailscale are treated as remote, so they need the token, and admin actions stay blocked

> **Do not** forward port 8000 on your router, and don't publish the backend with a public tunnel. The token protects the API, but the app isn't designed to face the open internet.

### Step 3: install the app on the phone
- **Android (Chrome):** open the HTTPS address, tap **⋮** → **Install app** (or **Add to Home screen**).
- **iPhone/iPad (Safari, iOS 16.4+):** open the HTTPS address **in Safari**, tap **Share** → **Add to Home Screen**. Other iOS browsers can't install web apps.

The installed app opens full-screen with the Abhify.AI icon. The access token stays in that browser's storage. If the phone clears website data, pair again with the `#pair=` link.

### Documents from the phone
A web app **cannot scan the phone's folders**. Use **Chat → 📎 Add file** to pick a PDF, `.txt` or `.md` file. It's uploaded to your PC's knowledge base. The watched knowledge folder (M2) is a feature of the PC.

## Mode C: phone without your computer
**With Abhify Lite (2.0.0-dev.7):** Settings → Where the AI runs → **This device**. After a one-time, confirmed model download, chat and memories work on the phone with no computer and no network. Requirements, sizes, privacy and limits: [ON_DEVICE_AI.md](ON_DEVICE_AI.md).

In either mode, the installed PWA opens offline (the app shell is cached) and shows:
- the last news it loaded, labelled with its age
- saved interview questions and quizzes, or built-in samples

Document search and live content need Mode B. Quiz answers given offline are queued and synced the next time the phone reaches your PC.

## Deploying the PWA over HTTPS
Two supported ways:
- **Same origin (simplest):** the backend serves the PWA, and HTTPS comes from Tailscale Serve, as above.
- **Static hosting:** publish `frontend/` on any HTTPS static host (GitHub Pages, Netlify, Cloudflare Pages). Users enter their PC's HTTPS address and token in Settings.
  - Add the host's origin to `CORS_ORIGINS` on the PC.
  - The static host never sees user data: the app talks directly to the user's own PC.

HTTPS is mandatory for install and microphone access everywhere except `http://localhost` on the PC itself.

## Why no APK or iOS app (yet)
- A real Android app would need either an on-device model runtime (for example llama.cpp/MLC with a 1–3B model), embeddings, a vector store and a rewrite of the Python backend for mobile, or simply a wrapper around this same PWA.
- A wrapper adds nothing over **Install app** and would be misleading as "an APK".
- iOS distribution additionally needs an Apple developer account and App Store review.

A native mobile build is a separate project, not part of this release.

## Privacy summary
- Nothing is sent to any central server: there is none.
- In Mode B the phone sends your questions to **your** PC and receives answers. Your memories, documents and chat history stay on the PC.
- The phone's browser stores only preferences, cached content, the access token and the offline queue. Remove them with your browser's "clear site data".

## Offline on the phone: what is and isn't possible (2.0.0-dev.6)
The phone app is a **PWA**: a web page with an offline cache. It **does not** contain Python, Ollama or any AI model, and Abhify.AI does not install them on the phone.

| On the phone, without the PC | Works? |
|---|---|
| Open the app from the home screen (cached app shell) | ✅ |
| Saved news (with its age), saved interview questions and quizzes, built-in samples | ✅ |
| Quiz answers (queued, sent when the PC is reachable again) | ✅ |
| Chat, document search, memories, voice | ❌ needs the PC, **unless** Abhify Lite is set up: then chat and memories work on the phone (2.0.0-dev.7); document search and private voice still need the PC |
| Access to phone folders or files without you picking them | ❌ never (browsers don't allow it, and Abhify.AI doesn't try) |

Further limits:
- **Storage:** browsers may clear PWA storage when space is low (especially on iPhone after weeks without use). If that happens, pair again.
- **Fully offline AI on a phone:** available as Abhify Lite in the PWA since 2.0.0-dev.7 (small model; chat and memories only; [ON_DEVICE_AI.md](ON_DEVICE_AI.md)). Not yet tested on a real phone.
- **Nothing moves between devices automatically.** Any future sync would be a separate, optional feature.
