# Phone setup: Android and iPhone (2.0.0-dev.6)

The phone app is a **Progressive Web App (PWA)**: a web page you add to the home screen. It is **not** an APK or App Store app. **The AI runs on your computer**; the phone only shows the app. Background: `MOBILE_INSTALLATION.md`. Who may connect: `MEMBERSHIP_AND_REGISTRATION.md`.

## 1. Find the actual port
Abhify.AI normally uses port **8000**. If Windows has reserved that port (Hyper-V, WSL and Docker can do this), it picks another one and keeps using it; on the development PC that's **18000**.

The port is shown in two places:
- the tray icon tooltip
- Settings → System & data → Companion access, in the line "port … " and in the Tailscale command shown there

Use **that** number below, not a guess.

## 2. Secure connection with Tailscale (recommended)
Tailscale gives the PC a private HTTPS address that only your own devices can reach. Nothing is opened to the internet.

1. **PC:** install Tailscale from https://tailscale.com/download and sign in.
2. **PC:** in the Companion panel, copy the command shown there and run it in a terminal:
   ```powershell
   tailscale serve --bg <port>        # e.g. tailscale serve --bg 18000
   tailscale serve status             # shows https://<your-pc>.<tailnet>.ts.net
   ```
   Tailscale forwards to `127.0.0.1:<port>`, so **local-network access stays off**.
3. **Phone:** install the Tailscale app and sign in with the **same** account (or one you've shared the PC with).
4. **PC:** Companion access → **Invite a device** (choose *My own device* for your phone, *Guest* for someone else's) → send the link privately.
5. **Phone:** open `https://<your-pc>.<tailnet>.ts.net/#invite=<code>` and give the device a name.

To stop sharing: `tailscale serve --https=443 off` (or `tailscale serve reset`).

**Plain Wi-Fi instead (not recommended).** Turn on **Allow local-network access** in the Companion panel and restart Abhify.AI, then open `http://<pc-ip>:<port>/#invite=<code>`. It's plain HTTP, so phones can't install the app or use the microphone, and anyone on that Wi-Fi can reach the page (devices still need an invitation).

## 3. Install on the home screen
| | Steps | Notes |
|---|---|---|
| **Android (Chrome)** | Open the HTTPS address → ⋮ → **Install app** (or **Add to Home screen**) | Microphone: Chrome asks the first time you tap 🎤 |
| **iPhone / iPad (Safari, iOS 16.4+)** | Open the HTTPS address **in Safari** → Share → **Add to Home Screen** | Only Safari can add web apps on iOS. The home-screen app has its own storage: if you paired in Safari *before* adding it, open the invitation again inside the installed app, or create a new invitation |

## 4. Status bar meanings
| Status | Meaning |
|---|---|
| AI ready · runs on your computer | Paired as your device; PC and model ready |
| AI ready · runs on the owner's computer (guest) | Paired as a guest |
| Not paired | No invitation redeemed yet (in public mode a **Join as guest** button appears) |
| Not paired · access token rejected | This device was revoked, expired or replaced: ask for a new invitation |
| Computer connected · Ollama not running / AI model not installed | PC reachable, but its AI isn't ready |
| Paired · your computer isn't reachable / Connection … lost · retrying | PC off, Abhify.AI stopped, or Tailscale disconnected |
| Offline mode | The phone has no network |

## 5. Troubleshooting
| Problem | Fix |
|---|---|
| The ts.net address doesn't load | Is Tailscale connected on both devices? Is `tailscale serve status` pointing at the **current** port? Is Abhify.AI running? |
| "This invitation isn't valid" | Used, expired or cancelled: create a new one |
| "Too many attempts" | Wait 10 minutes (wrong codes are rate-limited) |
| The installed iPhone app shows "Not paired" | Open the invitation link inside the installed app, or create a new invitation |
| Port changed after a restart | It shouldn't (the last working port is remembered); if it did, run `tailscale serve --bg <new port>` |

## 6. Privacy
- The phone stores its device credential and display caches in the browser. "Clear website data" removes them; revoking the device on the PC makes the credential useless.
- The service worker caches only the app shell (HTML, CSS, JS, icons), **never** API responses.
- Chats, memories and documents stay on the PC.
