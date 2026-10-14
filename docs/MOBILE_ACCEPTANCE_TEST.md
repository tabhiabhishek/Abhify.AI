# Mobile companion access: acceptance test (2.0.0-dev.2)

Date: 2026-10-14. **No physical Android or iPhone was available.** Device-only checks are **NOT TESTED**, with exact steps below.

What *was* tested here:
- **Server side, for real:** an isolated Abhify.AI instance listened on `0.0.0.0:8400` with temporary data. Requests went to the PC's **LAN IP** (`192.168.15.223`), so the server saw them as non-loopback, exactly like a phone on Wi-Fi.
- **A simulated phone:** Edge in Android mobile emulation (412 px, touch), talking to that LAN address, with the real `qwen2.5` on the PC.

The test token was never printed in full, and it was deleted afterwards. Nothing was exposed to the internet, and no port forwarding was created.

## Results

### Server-side access control (real requests over the LAN)
| Check | Result | Evidence |
|---|---|---|
| Companion **off**: remote `/memory` | **PASS** | 403. `/health`, `/ping` and `/` (PWA shell) answer 200, as designed |
| Enabling companion **from a remote client** | **PASS** | `POST /companion/enable` from the LAN IP → 403 |
| Token issued locally only; only its SHA-256 stored | **PASS** | 32-character token; settings hold `token_sha256` |
| No token / wrong token / token in `?query` | **PASS** | 401 / 401 / 401 |
| Valid token | **PASS** | `/memory` → 200 |
| **Admin actions with a valid token** (backup, restore, export, move data, model pull, knowledge folder, watcher start, companion enable, shutdown) | **PASS** | All **403** |
| Requests relayed by a tunnel (`X-Forwarded-For` from 127.0.0.1) treated as remote | **PASS** | Automated test `test_tunnel_requests_from_loopback_count_as_remote` |
| **Read-only system data visible to a token holder** | **FAIL (B2)** | `/system/diagnostics` returns `token_sha256`, the Windows **user name** in `data_root`, the Python path and settings. `/backup` lists backup names and `data_root`. `/setup/profiles` is readable |
| Token in server logs | **PASS (app)** | The app sends the token only in the `Authorization` header. The single log line containing it came from the **deliberate negative test** using `?token=`, which was rejected (401). See F3: the access log records query strings |
| `#pair=` fragment reaches the server | **PASS** | 0 occurrences of `pair=` in the server log; fragments aren't sent over HTTP |

### Simulated phone (LAN, real AI on the PC)
| Check | Result | Evidence |
|---|---|---|
| Fresh open of `http://<pc>:8400/#pair=<token>` | **PASS** | Token stored in `localStorage`; **removed from the address bar** (`history.replaceState`) |
| Pairing link opened while the app is **already open** in the tab | **FAIL (F2)** | The fragment change isn't handled; the token isn't stored |
| Status shown **before pairing** | **FAIL (B3)** | Shows "AI ready · via your computer" although every API call is 401 |
| Status after pairing | **PASS** | "AI ready · via your computer". The AI is never presented as running on the phone |
| Chat through the PC | **PASS** | Real `qwen2.5` answer received on the phone |
| Setup wizard on the phone | **PASS** | Not shown (admin is local-only) |
| Admin from the phone UI | **PASS** | "Backup now" → "This action only works on the computer running Abhify.AI." |
| Companion settings on the phone | **PASS** | "connected to Abhify.AI on another computer ✅. Settings can only be changed on that computer" |
| Knowledge search through the PC | **PASS (indirect)** | Remote `/knowledge/status` → 200; chat retrieval was verified on the PC. Document Q&A *from the phone* NOT TESTED |
| Connection loss → Offline banner → reconnection | **PASS** | Blocked `/ping`, `/health` and `/companion` → "Offline" plus banner; restored → "AI ready · via your computer" |
| File import uses an explicit picker (no folder scanning) | **PASS** | Only `<input type=file accept=".pdf,.txt,.md">`; the PWA has no file-system access API |
| Layout at 412 px | **PASS** | 0 px horizontal overflow, no JS errors |
| Token in browser history | **NOT VERIFIED** | The address bar is cleaned, but whether the browser's *history database* stored the original `#pair=` URL can't be checked headless. Safer alternative: type the token in Settings → Access token |

### Physical devices: NOT TESTED
| # | Android Chrome / iPhone Safari step | Expected | Result |
|---|---|---|---|
| 1 | Install Tailscale on PC and phone, same account; on the PC run `tailscale serve --bg 8000` | HTTPS URL `https://<pc>.<tailnet>.ts.net` | NOT TESTED |
| 2 | On the PC: Settings → System & data → Companion access → Turn on → restart Abhify.AI | Token shown once | NOT TESTED |
| 3 | On the phone, open `https://<pc>.<tailnet>.ts.net/#pair=<token>` in a **fresh** tab | Status "AI ready · via your computer" | NOT TESTED |
| 4 | Enter a **wrong** token in Settings → Access token | Requests rejected; a clear message (see B3: status currently misleading) | NOT TESTED |
| 5 | Android: ⋮ → **Install app**. iPhone (Safari, iOS 16.4+): Share → **Add to Home Screen** | Icon = Abhify.AI logo mark; name "Abhify.AI"; opens full-screen | NOT TESTED |
| 6 | Chat; ask about a document in the PC's knowledge folder | Answer with the 📚 badge | NOT TESTED |
| 7 | Chat 🎤 (microphone) | Permission prompt over HTTPS; transcription works | NOT TESTED |
| 8 | Airplane mode, then reopen the app | App shell opens, Offline banner, saved content only | NOT TESTED |
| 9 | Settings → System & data → Backup now | Refused ("only on the computer") | NOT TESTED |
| 10 | Chat → 📎 Add file | System file picker; nothing scanned automatically | NOT TESTED |
| 11 | Turn companion off on the PC | The phone loses access (403) | NOT TESTED |

**Never do:** router port forwarding, public tunnels without access control, disabling TLS validation, or sharing the token in chats, screenshots or issue reports.

## Defects for the fix list
- **B2 (Medium):** redact or restrict system endpoints for remote clients; never return `token_sha256`.
- **B3 (Medium):** detect "unpaired" (401 on an authenticated probe) and show "Not paired" instead of "AI ready".
- **F2 (Low):** handle `hashchange` for `#pair=`.
- **F3 (Low):** redact query strings from the access log, or disable it.
