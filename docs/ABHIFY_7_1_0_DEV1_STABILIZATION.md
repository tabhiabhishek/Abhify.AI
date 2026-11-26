# Abhify.AI 7.1.0-dev.1: security hardening and release stabilization (2026-11-20)

**Authorized and done:**
- the CORS / cross-origin security correction;
- its regression tests.

**Prepared only (waiting for your approval):**
- everything else in this report.

**Not done:**
- No commit, tag, push, deploy or new build.
- No dependency installed and no model downloaded.
- No user data or release package changed.

**Status codes:**
- **Verified** = run in this session.
- **Packaged** = run on the built installer or APK.
- **Physical** = run on a real phone or laptop.
- **Not tested.**

---

## 0. Core requirement: independent platforms (your rule, 2026-11-20)

Android, Windows and Web are separate installations with the same identity:
- each keeps its own data, memory, settings and models;
- each selects its model from its own hardware, with a manual override;
- there is no automatic sync, no remote control and no shared backend;
- the internet is used only for requested real-time information or updates;
- shared source code and design are fine; shared runtime is not.

I saved this as a permanent rule.

### How today's code fits the rule (verified by inspection)

| Area | State | Fits the rule? |
|---|---|---|
| Windows hardware-aware selection | `hardware_advisor.py`: RAM, CPU (registry), NVIDIA GPU + VRAM + driver (nvidia-smi), other adapters (WMI), disk, Ollama; manual choice in the model picker and Model Library | ✔ Gaps: no CUDA-toolkit or **Vulkan/AMD/Intel GPU** capability check; it reads NVIDIA only |
| Android hardware-aware selection | `model/ModelSelector.kt`: RAM, SoC/ABI, storage, thermal, battery, Vulkan only after an on-phone GPU test; manual model choice | ✔ |
| Web hardware-aware selection | `ondevice.js capabilities()/chooseModel()`: secure context, WebAssembly, WebGPU adapter and limits, memory estimate, storage quota; real self-test | ✔ Gap: no speed benchmark |
| **Desktop "companion access" (Mode B)** | `companion.py`, `membership.py`, pairing: a phone uses this PC's backend (opt-in, **off by default**) | ✘ **Conflict**: phone → PC dependency and remote control |
| **Android account / server sync** | `cloud/CloudClient.kt`, `sync/Sync.kt`, `Route.Server`: optional self-hosted server, record sync, shared providers (server URL empty by default) | ✘ **Conflict**: cross-device sync / shared backend |
| **Web served by the Windows backend** | The default web experience is the PWA talking to the PC backend; the browser-only "on this device" mode (WebLLM + IndexedDB) is independent | ◐ **Partial**: Web is independent only in on-device mode |
| Discovery report item E11 (desktop↔phone sync) | proposed | ✘ **Withdrawn** under this rule |

**Decision needed (not changed):** how to retire the conflicting pieces. My recommendation:
- **7.1.x:** hide them behind "Legacy: connected devices (off)". Nobody is enabled automatically, and existing data stays local.
- **7.2/8.0:** remove them.
- **Web:** ship a standalone static Web build whose default is on-device mode. The PC backend becomes optional, not required.

---

## 1. CORS vulnerability assessment (deliverable 1)

**How the backend authenticates [verified]:** a request that arrives from 127.0.0.1 with no proxy headers is treated as the owner, with full access (`companion.is_local_request`, `main.local_only`).

The problem: a malicious web page open in your browser *also* connects from 127.0.0.1. So the browser's cross-origin rules were the only thing standing between any website and your data. Cookies aren't used (`allow_credentials=False`), and a token is needed only by remote phones.

**Reproduced in real Chrome against an isolated backend** with a seeded private memory (`scratchpad/cors/cors_attack.py`).

| # | Issue | Evidence before the fix | Severity |
|---|---|---|---|
| V1 | CORS regex `https://.*\.(netlify\|vercel)\.app` | The server answered `Access-Control-Allow-Origin: https://evil.netlify.app` to `GET /memory`. This Chrome build additionally blocked the read (its local-network protection), but the server granted it; browsers without that protection would allow it | **High** |
| V2 | Cross-site "simple" requests are acted on (CSRF), from **any** website, not only netlify/vercel | Multipart `POST /knowledge/upload` from both attacker pages returned **200, and `attacker-notes.txt` was indexed into the knowledge base**, with Chrome's protection on. `/backup/restore` (multipart) and `/system/shutdown` (no body) accept the same kinds of request. CORS cannot stop this | **High / Critical** (knowledge poisoning, data replacement, shutdown) |
| V3 | DNS rebinding: an attacker hostname resolving to 127.0.0.1 is "same-origin" with the API and was treated as the owner | It **read the private memory and wrote a new one**, in today's Chrome | **Critical** |

**Not affected:**
- **Remote phones:** they need a bearer token in a header, which a browser never attaches automatically.
- **JSON writes from other origins:** these are preflighted, and CORS rejected them.

---

## 2. Security fixes and regression results (deliverable 2)

**Smallest correction, in three layers.** None of them trusts `Origin` as authentication.

1. **CORS** (`backend/config.py`): `cors_origin_regex` now defaults to `None`. Only the explicit `CORS_ORIGINS` list is trusted: localhost dev ports and Tauri, as before. A frontend hosted elsewhere is added by its exact origin, as already documented in `docs/MOBILE_INSTALLATION.md`.
2. **Cross-site guard**, independent of CORS (`companion.cross_site_reason`, applied in `main.companion_guard`). Any API request is refused with 403 when:
   - it carries an untrusted `Origin` (not the page's own host, not in `CORS_ORIGINS`, or `null`), or
   - the browser marks it `Sec-Fetch-Site: cross-site`.

   Still allowed:
   - the PWA served by this backend (same origin), including a phone opening `http://<pc-ip>:port`;
   - explicitly listed origins;
   - clients that send no `Origin` (the Android app, the desktop launcher, scripts). These still pass the normal authentication.
   - The static app shell still loads from any link.
3. **DNS-rebinding defence** (`companion.is_local_request`): "this computer" now also requires a loopback `Host` (`127.0.0.1`, `localhost` or `[::1]`), or no Host at all (not a browser). Any other host name from a loopback connection is treated as a **remote device**:
   - 403 while companion access is off;
   - otherwise 401 without a paired token.

   Tunnels keep working, because they already count as remote.

**After the fix, same attack script, same Chrome (verified):**
- every cross-site read, JSON write and hidden upload returned **403**;
- no `Access-Control-Allow-Origin` for evil origins;
- rebinding got "Companion access is turned off" and a 403 write;
- the memory list and knowledge base were unchanged;
- the result was identical with Chrome's local-network protection turned off.

**Automated tests:**
- `backend/tests/test_security_cors_71.py`: **21 tests**. They cover:
  - evil netlify/vercel/plain/`null` origins;
  - the trusted dev origin;
  - same-origin PWA, native clients and the address bar;
  - the static shell;
  - a LAN phone page reaching the token check;
  - Sec-Fetch-only cross-site restore and upload;
  - loopback hosts `127.0.0.1`, `localhost`, `[::1]`;
  - rebound hosts, including `127.0.0.1.nip.io`;
  - the companion-on token requirement;
  - `is_local_request` rules;
  - Host parsing.
- **Full backend suite:** 712 passed, 3 skipped. One existing test first failed; it builds a request with *no* Host header. That case is now explicitly allowed, because a missing Host can't come from a browser.

**Legitimate-use regression in real Chrome (verified):**
- **Providers UI:** 14/14.
- **Voice:** 29/31 in the bulk run. The two exceptions are the known test-harness limits (a fake mic file plays once; speech needs a trusted click). Both passed when re-run properly: hands-free sends and re-listens; Listen plays with the local voice; Stop works.
- No legitimate request was refused (no 403s in those server logs).

---

## 3. 7.1.0-dev.1 stabilization status (deliverable 3)

**Important:** the published dev build (`release/Abhify.AI-7.1.0-dev.1`, installer `63d1f5db…`, APK `647a61bd…`) was built **before** this security fix. The fix is in source only. A build containing it must get a new version string, `7.1.0-dev.2`, under the never-reuse-a-version rule. **Not built; waiting for approval.**

| Check | Result | Level |
|---|---|---|
| Installer integrity | `sha256sum -c` OK; build-time DLL / path / secret checks passed | Verified (file) |
| APK integrity | `sha256sum -c` OK; `ai.abhify.android` 7.1.0-dev.1 / 7010001, same development certificate `1d2d1fdb…` as 7.0.2 (installs as an update) | Verified (file) |
| 7.0.2 release packages untouched | per-folder `SHA256SUMS.txt` OK | Verified |
| Windows upgrade 7.0.2 → 7.1.0-dev.1 | Windows Sandbox **34/34**: upgrade 33 s, backup created, memory preserved, uninstall keeps data, reinstall restores | **Packaged** (disposable VM, not your laptop) |
| Local Ollama | chat through the packaged runtime on this PC | Packaged (this PC) |
| Voice input / output | local Whisper transcription in the packaged runtime; browser flows in Chrome with a simulated microphone | Packaged + browser; **real microphone not tested** |
| Provider configuration | connection test, real Groq/Gemini/OpenRouter rejection of fake keys, DPAPI storage, custom endpoint chat | Packaged + source; **real cloud keys not tested** |
| Branding | official mark and "Abhify.AI" served by the packaged app | Packaged |
| Real-time | "weather in my location" asks for the city (packaged); live Pune weather / Python version (source) | Packaged + source |
| Conversation persistence | streamed follow-up in the same thread (source); memories across upgrade and reinstall (Sandbox) | Source + Packaged |
| **Physical laptop** | — | **Not tested** |
| **Physical Android phone** | — | **Not tested** |

---

## 4. Git history and backup audit (deliverable 4)

**Repository state [verified, read-only commands only]:**
- **Branches:** `release/7.0.0` (current), `main`, `feature/7.0-synapse`, `feature/m2-knowledge-packaging`, `feature/abhify-v2`.
- **HEAD:** `592df89` (2.0.0-dev.8 / Android 6.2-dev.1). `release/7.0.0`, `feature/7.0-synapse` and `feature/m2-knowledge-packaging` all point at it.
- **Tags:** only `v1.0.0` (on `main`, `574072e`). 20 commits in total.
- **Uncommitted:** **42 modified + 45 untracked** entries (87 paths). The diff is 3,016 lines added and 396 removed; new files total about 23 MB, mostly screenshots: `docs/screenshots/7.0` 16 MB, `7.0.1` 3.5 MB, `7.0.2` 1.3 MB, fonts 1.2 MB.
- **Line endings:** CRLF/LF warnings for 3 files. Git will normalise them on commit.

**Ignored correctly [verified with `git check-ignore`]:**
- `release/`, `dist/`, `build/`;
- `backend/data/` (a local data folder) and `backend/server.log`;
- `backend/.venv/`, `android/local.properties`, `packaging/.cache/`;
- **`abhify-debug-signing-key.keystore`**, via `*.keystore`.

**Secrets [verified]:**
- No key files (`.keystore/.jks/.p12/.pfx/.pem/.key/.env`) are tracked or untracked-but-unignored.
- No `storePassword` / `keyPassword` or keystore path references in the code.
- No API-key or private-key patterns in modified or untracked files. Test keys are built at run time, for example `"gsk_" + "a"*40`.

**Backups and snapshots (exist):** `D:\Abhify_Backups\`:
- `source-7.0.1-as-built`, `source-7.0.2-as-built`;
- `full-project-2026-11-19-pre-hardening` (= 7.0.0), `full-project-2026-11-18-pre-github`;
- `broken-7.0.2-windows` (archived, never distributed);
- `ai_tech_pulse_v1.0.0_data`.

**Safe commit and tag proposal (not executed).** All commits go on the current branch `release/7.0.0`, with no branch switch. Each commit's tree is built from the as-built snapshot through a temporary index, so your real index and working tree are never touched:

| Commit | Tree source | Tag |
|---|---|---|
| C1 "Abhify 7.0.0" | `full-project-2026-11-19-pre-hardening` (tree `b54c7811…`) | `v7.0.0` |
| C2 "Abhify 7.0.1" | `source-7.0.1-as-built` (tree `f9833579…`) | `v7.0.1` |
| C3 "Abhify 7.0.2" | `source-7.0.2-as-built` (tree `83453687…`) | `v7.0.2` |
| C4 "Abhify 7.1.0-dev.2: voice, providers, branding, real-time, cross-origin security" | the current working tree after the version bump to 7.1.0-dev.2 (if you approve a dev.2 build) | `v7.1.0-dev.2`, or no tag for a dev line |

**Notes on the proposal:**
- **Re-verification:** before writing, recompute each tree and compare it with the recorded hash.
- **Attribution:** no AI attribution lines, per your rule.
- **Large files:** commit the screenshots, or move them to a separate docs-assets commit, or keep them out with a `.gitignore` entry. **Your choice.**
- **Pushing:** stays stopped, as you asked earlier.
- **Merging to `main`:** not proposed.

---

## 5. Desktop UI implementation proposal (deliverable 5; not applied)

**Source:** `docs/concepts/desktop-home-7.1-concept.html`.

**Files:** `frontend/styles.css` (desktop media queries ≥1024 px), small markup moves in `frontend/index.html`, minor `frontend/app.js` changes.

| Item | Change | Test |
|---|---|---|
| Sidebar overflow | nav items 52 → 38 px; section labels tightened; the sidebar becomes a flex column whose nav list scrolls on its own | screenshots at 1366×768, 1600×900, 1920×1080 with browser chrome; no sidebar scrollbar at ≥ 800 px height |
| Pinned active-model card | `margin-top:auto` at the bottom of the sidebar flex column; always visible; collapses to a dot in rail mode (600–1023 px) | visible at all three sizes; rail mode |
| Microphone | Home composer gets the mic button inside it; the floating footer mic is hidden on desktop ≥1024 px (kept on phones) | mic in the composer starts the same `startListening('command')`; voice suite re-run |
| Model indicator | a model pill inside the Home composer (`modelDisplay`, cloud-aware); clicking it opens the existing model picker | picker / consent tests re-run |
| Orb | static by default; the `breathe` animation only in `listening` / `thinking` states (`orbState()`); smaller on Home (56 px) | state transitions; reduced motion |
| Card clipping | grid `align-items:stretch`, a min-height per card, internal list overflow with fade; no fixed heights | long titles, empty states, 0–10 items |
| Quiz label | `0/0 · 🔥0` → "Quiz 0/0 · 0-day streak", with a tooltip and an `aria-label` | text / ARIA assertion |
| Light / dark | reuse the Synapse tokens only; no new colours | screenshots in both themes |
| Reduced motion | a global `prefers-reduced-motion` rule disabling entrance, hover-lift and breathe animations (some exist today; make it global) | emulated media in Chrome |
| CSS consolidation | merge duplicated selectors (`.voice-overlay` and others defined in several places) | the visual diff must be unchanged |

**Effort and gate:**
- **Effort:** 2–3 days.
- **Ships as:** part of 7.1.0-dev.2 or 7.1.0, with a new `SHELL_REV` so cached pages update.
- **Gate:** the existing web test suites plus new screenshot checks.

---

## 6. 7.2 Knowledge: technical proposal (deliverable 6; not implemented)

**Under the independent-platform rule:** each platform implements retrieval in its own runtime and over its own data. They share only a **specification and test fixtures** (as `realtime_intents.json` and `citation_cases.json` do today), never an index or a service.

### 6.1 Hybrid retrieval and exact match
- **Windows/Web backend** (`knowledge.py`):
  - Add a keyword channel: an FTS5 table in a **separate file** `<data>/data/knowledge_fts.sqlite3`, so there is **no migration** of `abhify.db`.
  - It is filled at index time with the same chunk ids, and updated with the existing add-before-delete policy.
  - Fuse vector top-20 and BM25 top-20 with **RRF (k = 60)**, the same constant as Android.
  - Expand to neighbouring chunks.
  - Set k from the question: 4–8.
- **Exact match:** tokens that look like identifiers (error codes such as `0x80070005` or `ERR_CONNECTION_RESET`, `CamelCase`, `snake_case`, versions, file names, quoted phrases) are searched as FTS phrases. Exact hits are ranked first, with the matched span highlighted in the citation.
- **Android** (`docs/RagIndex.kt`): already hybrid. Add the same identifier rule, and fix the outdated "no embedder" doc comments.
- **Web on-device mode:** keyword search over the browser's own chats and memories (IndexedDB). Documents in the browser are a later, separate item.

### 6.2 File types
- **Windows:**
  - PDF and TXT/MD (existing);
  - CSV/TSV with the standard library (rows to text with headers);
  - JSON;
  - code files (an allow-list of extensions, kept under the existing secret scan);
  - HTML with the standard library `html.parser`;
  - **DOCX without a new dependency.** A DOCX is a zip of XML, so it can be parsed from `word/document.xml` with `zipfile` + `ElementTree`.
- **Android:** already handles PDF, DOCX, text and code; add any type missing from the shared list.

### 6.3 Opt-in OCR (Windows), choose one
- **(a) Windows' built-in OCR** (`Windows.Media.Ocr`). Verified available on this PC (en-GB, en-US). No model download. It needs either a WinRT Python binding (a **new dependency**) or a PowerShell bridge (no dependency, slower).
- **(b) RapidOCR on the bundled `onnxruntime`.** New package plus about 15 MB of models, so a download needs approval.

**Recommendation:** (a). Either way, OCR runs only after "Read this scanned file with OCR?" for that file.

### 6.4 Search-quality evaluation
- `backend/tests/data/retrieval_cases.json`, **shared with Android**: about 30 small synthetic documents and about 60 queries (paraphrase, exact identifiers, numbers, multi-hop), each with the expected chunk.
- Each platform runs its own engine against it.
- **Gates:** recall@5 ≥ 0.9 and MRR ≥ 0.75, and 100 % on the exact-identifier subset.
- Results are reported in `eval_local.py` (existing) and the Android unit tests.

### 6.5 Encrypted backups, choose one
- **(a) Passphrase AES-256-GCM (PBKDF2/scrypt)**, the same scheme as Android's backups. Needs `cryptography`, a **new dependency (not bundled today)**. Portable to another PC.
- **(b) Windows DPAPI.** No dependency, but it can only be restored by the same Windows user on the same machine.

**Recommendation:** (a). The UI must say clearly that a forgotten passphrase cannot be recovered.

### 6.6 Test-restore workflow
- "Test restore" extracts the newest backup into a temporary folder and validates the manifest and schema.
- It opens the SQLite copy and the Chroma copy, compares counts (conversations, messages, memories, documents, chunks) with the live data, then deletes the temporary folder.
- It **never touches the live data**.
- Android: the same steps over its own backup format.

### 6.7 Effort, migrations, dependencies, approvals
- **Effort (desktop):** about 2.5–3.5 weeks.
- **Migrations:** none (side database file).
- **Dependencies:** `cryptography`; optionally a WinRT OCR binding.
- **Downloads:** none, unless OCR option (b) is chosen.
- **Approvals needed:** the two choices above (OCR, backup encryption) and the dependency.

---

## 7. Exact list of files changed in this task (deliverable 7)

**Application (security fix only):**
- `backend/config.py`: `cors_origin_regex` default `None`, with a comment.
- `backend/companion.py`: `LOOPBACK_HOSTS`, `host_name()`, a loopback-Host requirement in `is_local_request()`, `_norm_origin()`, `cross_site_reason()`.
- `backend/main.py`: `companion_guard` calls the cross-site guard first.

**Tests (added):** `backend/tests/test_security_cors_71.py`.

**Documents (added):**
- this report;
- earlier in this session (discovery task): `docs/ABHIFY_NEXT_GEN_ARCHITECTURE_DISCOVERY.md` and `docs/concepts/desktop-home-7.1-concept.html`.

**Outside the repository:**
- Claude's project memory (the independent-platform rule, status notes).
- Test data and Chrome profiles in the session scratchpad.

**Not changed:**
- databases and migrations;
- dependencies;
- Android code;
- release packages;
- signing keys;
- Git state.

---

## 8. Remaining risks and manual testing (deliverable 8)

**Remaining risks:**
1. **Dev build artifacts lack the security fix** until a 7.1.0-dev.2 build is approved.
2. **Independent-platform conflicts** (companion Mode B, Android server sync, web depending on the PC backend) remain in the code, disabled by default, until you decide how to retire them.
3. **Custom-endpoint DNS window** (7.1-dev): pin the resolved IP per request.
4. **Git:** months of work uncommitted; a disk failure would lose it. Only the `D:\Abhify_Backups` snapshots exist, on the same disk.
5. **Data at rest is unencrypted on both platforms; desktop backups are plain zip files** (7.2 addresses backups).
6. If you ever host the PWA on another domain, you must add that **exact** origin to `CORS_ORIGINS`. The old wildcard no longer applies.

**Manual tests still required:**
- **Laptop (physical):**
  - install over 7.0.2;
  - talk with a real microphone, review, send and hear the answer;
  - minimise while listening;
  - unplug a USB mic;
  - add a real Groq or Gemini key and chat with it;
  - check the logo in light and dark;
  - after the security fix: open the app normally (it must work).
- **Android (physical):**
  - update over 7.0.2;
  - "weather in my location" asks for your city;
  - existing chats and memories are still there;
  - on-device model chat;
  - voice.
- **Web:** the browser-only mode on a second browser with no PC backend (for the independence rule).
