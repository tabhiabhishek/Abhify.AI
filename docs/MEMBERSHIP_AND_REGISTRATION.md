# Membership and registration (2.0.0-dev.6)

Abhify.AI runs on **your** computer and stores **one person's** data: yours. Membership decides which other **devices** may use it through companion access, and what they may do. It does **not** create user accounts, and nothing is sent to a cloud service.

## Who can do what

| | This computer (owner) | **Your device** (owner's phone/tablet) | **Guest** (someone else's device) |
|---|---|---|---|
| Chat with your documents, memories and history | ✅ | ✅ | ❌ (guest chat: general knowledge, optional web search; nothing saved) |
| News, interview questions, quizzes, voice transcription | ✅ | ✅ | ✅ |
| Memories, conversations, preferences, knowledge files, progress | ✅ | ✅ | ❌ (403) |
| Settings, backups, models, knowledge folder, membership, registration mode | ✅ | ❌ | ❌ |

All of this is enforced **on the server, for every request**; the hidden buttons on a guest's phone are only cosmetic. The rules are in `backend/companion.py` (`REMOTE_ROUTES`, `MEMBER_ROUTES`) and `backend/membership.py`.

## 1. Start: invite-only (the default)
1. On the PC: Settings → **System & data** → **Companion access** → **Turn on**.
2. **Invite a device:** enter a name, choose **Guest** or **My own device**, choose how long the invitation is valid (1, 3 or 7 days), then **Create invitation**.
3. The code is shown **once**. Send the link privately, for example:
   `https://<your-pc>.<tailnet>.ts.net/#invite=<code>`.
   Only the code's hash is stored, and each invitation works **once**.
4. On the phone, open the link. It asks for a device name, redeems the code and removes it from the address bar. Then the device appears in the **Devices** list.

Facts about invitations:
- Invitations expire. Unused ones can be cancelled.
- Guest devices expire after **30 days** by default; your own devices don't expire.
- Wrong codes are rate-limited: about 5 failures per device and 30 overall per 10 minutes, after which the server answers `429` with `Retry-After`.
- Every failure gets the same message ("This invitation isn't valid"), so nobody can find out which codes exist.

## 2. Later: public registration (deliberate, on the PC only)
In the Companion panel: **Enable public registration…**, then read and confirm the warning. Or, while Abhify.AI is stopped, set `"registration_mode": "public"` under `"companion"` in `%LOCALAPPDATA%\AbhifyAI\config\settings.json`.

**What "public" means here.** Abhify.AI is **not a hosted website**. Public mode only means: anyone who can **already reach this computer** can join **without an invitation, as a guest**. Who can reach it depends on you:
- your Tailscale network (people you shared it with)
- your local network, if you allowed local-network access

It never makes the PC reachable from the internet, and nobody can register as an owner device.

Safeguards:
- self-registration is rate-limited
- at most 25 active public guest devices
- the switch can't be made from a phone, even an owner device; it's PC-only and checked server-side

## 3. Return to invite-only
Click **Return to invite-only** (or set `"registration_mode": "invite_only"`). Nobody new can join without an invitation.

Devices that joined while public **keep working** until you revoke them: switching modes never deletes devices, chats, memories, documents or settings. To remove them, revoke them one by one, or **Turn off** companion access (which revokes every device and invitation).

Any unknown value in `settings.json` is treated as `invite_only`.

## 4. Revoking and recovery
| Situation | What to do |
|---|---|
| A device is lost or shouldn't have access any more | **Revoke** it in the Devices list. It's refused immediately (401) and shows "access was rejected". |
| Everything should stop | **Turn off** companion access: all devices and open invitations are revoked; your data is untouched. |
| Your phone shows "access was rejected" | Create a new invitation (role: My own device) and open it on the phone. |
| A guest's access expired | Create a new guest invitation. |
| Upgraded from dev.5 | Your already-paired phone is listed as **Legacy paired device** and keeps working. Revoke it and re-pair with an invitation whenever you like. |

## 5. Security boundaries
- **Stored secrets:** only SHA-256 hashes of device tokens (192-bit) and invitation codes (128-bit). Nothing is logged in plain text, and the access log masks query values (`?token=***`).
- **Network:**
  - By default Abhify.AI listens on **127.0.0.1** only, even with companion access on.
  - **Tailscale Serve** gives phones a private HTTPS address.
  - **Allow local-network access** (an opt-in with a warning, applied after a restart) makes it listen on every network connection over plain HTTP. Use it only on a trusted home network.
  - **Ollama is never exposed**; it stays on its own `127.0.0.1:11434`.
- **Responses to phones** never contain the token hashes, your Windows user name or folder paths.
- **Credential storage:** the phone keeps its device credential in the browser's local storage (owner's decision, dev.6). Any script running on the app's page could read it, so the defences are: revoke the device, guest expiry, and HTTPS through Tailscale.

## 6. Known limitations
- **No per-person data.** Guests are kept *away from* your data, rather than getting their own. Giving each member their own chats, memories and documents needs per-user isolation, a larger change that needs your approval (see `PUBLIC_HOSTING_ARCHITECTURE.md`).
- The guest chat uses your PC's model and internet (web search). Guests can use your computer's capacity; revoke them if they overuse it.
- Rate limits live in memory and reset when Abhify.AI restarts.
- Not yet tested on a real Android phone or iPhone (`MOBILE_ACCEPTANCE_TEST.md`).
