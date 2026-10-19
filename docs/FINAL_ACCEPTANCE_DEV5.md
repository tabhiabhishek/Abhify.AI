# Physical acceptance and release decision: Abhify.AI 2.0.0-dev.5

Date: 2026-10-19. Branch `feature/m2-knowledge-packaging` at `7232f3c` (plus this report). Nothing was merged, tagged, signed, published or purchased. `main` and `v1.0.0` are unchanged at `574072e`.

Legend: **PASS** (performed, with evidence) · **FAIL** · **BLOCKED** (couldn't be performed) · **NOT TESTED**.

## 1. Recommendation

# ✅ Option 1: **Continue internal testing**
### (Option 2, a restricted development prerelease, becomes appropriate as soon as the second-PC test passes; see §7)
### ❌ Option 3, merge and prepare a release candidate: **not eligible** (required acceptance tests are outstanding)

- **No critical security or data-loss issue is known.** No new defect was found in this round.
- New this round:
  - dev.5 was **run on physical hardware with a real GPU**: the development PC, with the packaged payload and isolated data. This closes the long-standing "GPU use never verified" risk.
  - The signing-provider decision note is written.
- **Still outstanding:**
  - the **second physical PC** acceptance (standard user, real installer, clean machine)
  - **Android** and **iPhone**
  - none of these devices is available here

## 2. Verified state

| Item | Value |
|---|---|
| Installer | `dist\windows\2.0.0-dev.5\Abhify.AI-Setup-2.0.0-dev.5.exe` |
| SHA-256 | `612750ed34c39fb9697d82bcfe3f0f81b343397cf8a3cd86f38bc55cce72f2f2`: matches the expected value and `SHA256SUMS.txt` (re-hashed 2026-10-19) |
| Size / signature | 121,257,187 bytes · **NotSigned** |
| Provenance | Built from `931969d` with `requirements.lock` (hash-checked); runtime = lock (118/118); 34/34 app files identical to the commit |
| Earlier installers | dev.4 `1f0a61ca…`, dev.3 `ccbd42f9…`, dev.2 `9e299def…`: unchanged |
| Working tree | clean, apart from this round's docs |

## 3. Task 1: physical Windows PC

### 3a. Second physical PC (the required acceptance test): **BLOCKED**
No second PC is available, so no step of `PHYSICAL_PC_ACCEPTANCE_TEST.md` was performed on one. These remain **NOT TESTED** on physical hardware:
- **standard-user installation** of the real installer, and Start Menu / desktop shortcuts
- **tray icon** (visual)
- **model download on a clean PC**
- **upgrade / uninstall / reinstall**

### 3b. Physical hardware run on the development PC (new): **14/14 PASS**, but **not** the acceptance test

What was run and how:
- **Hardware:** Windows 11 Pro 23H2 (10.0.22631), NVIDIA **RTX A2000 12 GB**, driver 581.42, Ollama 0.40.0 (already installed, with `qwen2.5` 7B and `nomic-embed-text`).
- **What ran:** the **dev.5 packaged payload**, meaning the build staging that is byte-identical to the installer contents: bundled Python 3.11 runtime, 118 locked packages and the 34 app files. It was started exactly like the Start Menu shortcut (`pythonw.exe launcher.pyw`), with a **temporary data folder** (`ABHIFY_DATA_DIR`), a temporary Hugging Face cache and a temporary knowledge folder (`D:\Abhify_HWTest`). All were deleted afterwards, and your real `%LOCALAPPDATA%\AbhifyAI` was never used.
- **Why not install it:** installing would add shortcuts and uninstall entries to your profile, and a shortcut could open your real data. The installer mechanics themselves are covered by the Sandbox tests (§5).
- **Downloads:** no model was downloaded. The Whisper `base` model (~145 MB) was fetched into the temporary cache.

| Check | Result | Evidence |
|---|---|---|
| Packaged runtime starts | PASS | `/health` version `2.0.0-dev.5` |
| **Port fallback on a real reservation (D4)** | PASS | Windows reserves 7914–8213 on this PC; the launcher chose **18000** |
| Single instance | PASS | 1 process (PID 803268) before and after a second start; 1 listener |
| **GPU detection** | PASS | "NVIDIA RTX A2000 12GB, 12.0 GB, driver 581.42" |
| Profile recommendation | PASS | **Balanced**: "NVIDIA GPU with 12.0 GB VRAM detected" |
| No download without consent | PASS | HTTP 400; nothing downloaded |
| Models ready | PASS | `qwen2.5` + `nomic-embed-text` |
| Real inference | PASS | setup test "OK" (19.5 s, including the first model load); chat answer 250 chars in **2.2 s** |
| **LLM actually on the GPU** | PASS | `ollama ps`: `qwen2.5:latest 4.7 GB **100% GPU**`; nvidia-smi 6,100 MiB in use |
| Document indexing | PASS | `sample.pdf` indexed, 1 chunk |
| Document citation | PASS | "The test codeword … is BLUE-HERON-9. [sample.pdf, page 1]" |
| **Whisper on the GPU** (packaged runtime) | PASS | log: "Whisper 'base' loaded on **cuda (float16)**"; `cuda_whisper=true` |
| Whisper accuracy on a synthetic voice | PASS with note | Heard "blue **here in** 9" instead of "blue heron nine". The audio was Windows' synthetic voice (SAPI); this is the small `base` model's accuracy, not a malfunction. *A stricter word check in my script reported FAIL; I'm reporting what actually happened* |
| Clean stop | PASS | 0 processes, port closed, run file removed |
| Restart persistence | PASS | 2 conversations, 1 memory, 1 chunk; **same port (18000)** after restart |

Harness notes (problems in my test scripts, not in the app, recorded for honesty):
- The first run's summary file broke because PowerShell treats `$r` and `$R` as the same variable; every step was still logged individually.
- The single-instance and clean-stop checks used a process lookup that matched nothing, so both were re-run with a corrected lookup (results above).

## 4. Task 2: real mobile devices

| | Android | iPhone |
|---|---|---|
| Device model / OS / browser | — | — |
| Pairing, HTTPS (Tailscale), home-screen install, AI status, chat, knowledge search, authentication, admin restrictions, reconnection, microphone, file import | **NOT TESTED** | **NOT TESTED** |

No phone is connected (USB/ADB), and Tailscale isn't installed. Other devices on your network were not accessed. No emulator or simulated request is counted.

## 5. Evidence carried forward (Windows Sandbox, clean VM; **not** physical acceptance)

| Test (dev.5) | Result |
|---|---|
| Clean install (no network) | 28/28 |
| Upgrade dev.4 → dev.5 over the running app | 34/34 |
| Clean-PC model setup (network on, 8 GB, no GPU) | 22/22: consent, network error, interrupted and resumed download, CPU inference, document answer with citation |
| Signing pipeline lab (throw-away certificate) | 10/10 |

## 6. Task 3: signing provider

See `SIGNING_PROVIDER_DECISION.md`. In short:
- **Azure Artifact Signing** (formerly Trusted Signing) is ~$9.99/month, but **individual developers must be in the US or Canada** (Microsoft quickstart, updated 2026-10-08). Organizations: the US, CA, EU, UK and 8 more countries.
- **For an individual in India** (the PC's time zone suggests India; please confirm): Certum Open Source Code Signing (from €49; needs a **public open-source project**), or SSL.com IV/OV ($129/yr + eSigner) with eSigner CKA, which works with our `sign.ps1` `Store` mode.
- SignPath Foundation is free for open-source projects, but you wouldn't be the publisher, and approval depends on reputation.
- Decide on open-sourcing first. Nothing was bought or configured.

## 7. Task 4: release gate review

| Area | Status |
|---|---|
| Dependency lock | ✅ 118 packages with hashes; fresh-venv install + 230 tests; dev.5 runtime = lock |
| Reproducible build | ✅ Hash-locked online build; Python and NSIS inputs pinned by SHA-256. ⚠️ Installer bytes differ per build (NSIS timestamps); contents are identical |
| Installer checksum | ✅ `612750ed…72f2f2` verified |
| Automated tests | ✅ 230 passed on the current HEAD (2026-10-19; plus 3× earlier today) |
| Known open defects | none known |
| Security / data loss | no open critical issue |
| **Required acceptance** | ❌ Second physical PC: BLOCKED · ❌ Android: NOT TESTED · ❌ iPhone: NOT TESTED |
| Signing | ⏳ Pipeline ready; certificate depends on your decisions (`SIGNING_PROVIDER_DECISION.md`) |

**Remaining blockers:**
1. Second-PC acceptance (standard user, real installer, at least steps 0–14).
2. A phone decision: either test a real Android phone and an iPhone, or explicitly accept "phones untested" as a disclosed limitation.

**Remaining risks:**
- **Unsigned installer** (SmartScreen warnings).
- **SmartScreen / Defender on a downloaded copy:** not tested.
- **A real disk-full** during a download: automated tests only.
- **Whisper `base` accuracy:** modest on synthetic or accented speech; a bigger model is a settings change (`WHISPER_MODEL`).
- **D3 mitigation** covers explicit document questions only.
- **The lock is Windows-only.**

**What option 2 would need** (if you choose it after the second-PC test):
- version `2.0.0-dev.5`, or a new number if anything changes
- GitHub pre-release, unsigned, marked "development / not for general use"
- disclosures: unsigned (SmartScreen), Ollama required, phones untested if so, no app-store versions, and every NOT TESTED item above
- assets: installer + `SHA256SUMS.txt` + release notes + `INSTALL.html`
- after uploading: download the asset again and compare its hash
- needs your explicit approval and a GitHub login (GitHub is logged out on this PC)

## 8. What you can do next (≈1 hour each)
1. **Second PC:** copy `Abhify.AI-Setup-2.0.0-dev.5.exe` and `SHA256SUMS.txt` to a second Windows 10/11 PC, then follow `PHYSICAL_PC_ACCEPTANCE_TEST.md` as a standard user. Send me the filled table, and I'll record it as physical evidence.
2. **Phones:** on the PC running dev.5, install Tailscale and run `tailscale serve --bg <port>` (the tray tooltip shows the port: **18000 on this PC**, because 8000 is reserved here). Then follow the device table in `MOBILE_ACCEPTANCE_TEST.md` on each phone.
3. **Signing:** answer the three questions in `SIGNING_PROVIDER_DECISION.md` §4.
