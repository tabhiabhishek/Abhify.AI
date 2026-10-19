# Final acceptance and release gate: Abhify.AI 2.0.0-dev.3 (and the dev.4 follow-up)

Date: 2026-10-16. Branch `feature/m2-knowledge-packaging`. Nothing was merged, tagged, signed or published. `main` and `v1.0.0` are unchanged at `574072e`.

Legend: **PASS** (performed, with evidence) · **FAIL** · **BLOCKED** (couldn't be performed here) · **NOT TESTED**.

> **Environment, stated plainly:**
> - Only the development PC was available: Windows 11 Pro, RTX A2000. **No second physical PC and no Android or iPhone devices were available.** Other devices on the local network were not used: they aren't part of this test and nothing authorizes using them.
> - All installation tests ran in **Windows Sandbox** (a clean Windows 11 VM). That is a clean machine, but **not** physical hardware, and none of it is reported as a physical-PC result.

## 1. Decision

# 🔴 NO-GO for merge to `main` or a public release
## 🟢 GO for internal testing, using **2.0.0-dev.4** (not dev.3)

- The dev.3 target installer passed most of its checks, but this run found **5 product defects, all fixed in dev.4**, plus a build-robustness note (P1) and a bug in my own test script (T1). See §3.
  - **D4** (high): the app **can't start at all** on PCs where Windows reserves ports 8000–8010.
  - **D2** (high): every build from `requirements.txt` produced an app that crashes on start.
  - **D1, D3** (medium) and **F7** (low).
- **dev.3 should not be distributed further.** The fixes are in **2.0.0-dev.4**, which passed everything that could be run here.
- **No critical security or data-loss issue remains.** D1–D4 are reliability and correctness defects. None loses user data, and none exposes data.
- It **stays NO-GO** because the **mandatory physical-device acceptance tests (Tasks A and D) were not performed:** no second PC and no phones.

## 2. Installers

| | dev.3 (target of this gate) | dev.4 (recommended for the next tests) |
|---|---|---|
| Path | `dist\windows\2.0.0-dev.3\Abhify.AI-Setup-2.0.0-dev.3.exe` | `dist\windows\2.0.0-dev.4\Abhify.AI-Setup-2.0.0-dev.4.exe` |
| SHA-256 | `ccbd42f90d73addd5a3464b879a7a94a4974881d4441b8a94842771065e057e3` (**verified**, matches the expected value and `SHA256SUMS.txt`) | `1f0a61ca60c2d30ea37ab4286d6aba7bdfe5347ef8466e4e45614a348d36b9ee` (matches its `SHA256SUMS.txt`) |
| Size | 128,987,754 bytes | 121,272,688 bytes |
| Signature | **NotSigned** | **NotSigned** |
| Built from | `cbba504`, offline build (`--site-packages-from`) | `292c848`, **online build** from `requirements.txt` (pip cache off); 34/34 app files identical to the commit |
| Payload scan | 0 build paths, 0 personal strings | 15,077 files: 0 build paths, 0 personal strings, 8 known false-positive secret patterns, `check_package` 0 problems |

The dev.2 installer and its checksum files are **unchanged**: `9e299def…52f8b2`, re-hashed after every build.

A first dev.4 build (`24d75f81…`) contained F7. It was found by the payload scan before any test or distribution, was moved to `build\discarded-dev4-build1-24d75f81\` (never shipped) and was replaced by `1f0a61ca…`.

## 3. Defects found in this run

| ID | Severity | Found by | Defect | Fix (commit) | Verified |
|---|---|---|---|---|---|
| **D4** | **High** (app can't start) | Full test suite, after the networked Sandbox run | Windows (Hyper-V/WinNAT, also WSL2/Docker) reserved TCP **7914–8213**. The launcher only tried 8000–8010 and stopped with "No free port" | Last working port, then 8000–8010, then 18000/28000/38000, then any free port; the working port is remembered so the browser keeps the same address (`01bc0fb`) | 2 new tests pass **with the real reservation still active** on this PC; full suite 3× green |
| **D2** | **High** (release builds) | Online build | `langchain-text-splitters` (imported by `knowledge.py`) wasn't in `requirements.txt`. An online build produced an installer whose app **crashes on start**, and the build's checks passed anyway | Pinned `==1.1.3`; `build.py` now imports the app's own modules with the bundled runtime; a test checks that every third-party import is declared (`01bc0fb`) | The new build check **caught** the broken experimental build; the dev.4 online build passes it ("app imports ok: 56 routes") |
| **D1** | Medium | Code review for Task B, then the Sandbox | A download whose connection was cut, or whose stream ended early, could be reported as **done**. On a real kill, dev.3 reported failed but with an **empty** message, and a model that can't start showed a raw `HTTPStatusError 404` | "done" needs Ollama's `success` event **and** the model in `/api/tags`; plain-language failure messages; the setup test needs a real answer and shows Ollama's reason (`01bc0fb`) | 11 new tests (8 fail on the old code); dev.4 Sandbox: "connection to Ollama was interrupted … Press Download again to resume", "Ollama couldn't run qwen2.5:14b: model 'qwen2.5:14b' not found" |
| **D3** | Medium | dev.3 Sandbox model test | On the CPU **Minimal** profile, `qwen2.5:1.5b` didn't call the documents tool: "I don't have access to your local documents", although the PDF was indexed | Questions that explicitly refer to the user's documents get matching excerpts in the prompt, as memories already did; general questions are unchanged (`01bc0fb`) | 8 new tests; dev.4 Sandbox, real model on CPU: "The test codeword is BLUE-HERON-9.", sources `sample.pdf, page 1` |
| **F7** | Low (privacy/hygiene) | dev.4 payload scan | Online `pip --target` wrote 37 `bin\*.exe` launchers embedding the builder's `C:\Users\<name>\…\python.exe` | Removed; the path check now scans **every** runtime file (`292c848`) | Re-scan: 0 personal strings, 0 build paths |
| P1 | Low (build robustness) | Online build, first attempt | NSIS fails when the checkout sits in a very deep folder (a file path over 260 characters inside chromadb's test files) | Not changed: documented. Build from a short path such as `D:\Abhify.AI` | Rebuilt from a short path: OK |
| T1 | Test harness | dev.3 Sandbox model test | My Sandbox script waited for the Ollama tray app (`Start-Process -Wait` waits for child processes) | Waits for the installer process only | Re-run OK |

Also found:
- **The offline build ships unused packages.** It copies the whole dev venv, including `langchain_community`, `langchain_classic`, `sqlalchemy` and `httpx_sse`; this affects dev.3. Releases should use the online build, which only contains the declared dependency set.
- **The online build pulls newer transitive packages** than the dev venv (for example `langsmith 0.14.11` vs `0.14.8`), because only the top-level packages are pinned. A hashed lock file is still recommended (§7).

## 4. Task A: second physical Windows PC: **BLOCKED / NOT TESTED**

No second physical PC was available. **No row of `PHYSICAL_PC_ACCEPTANCE_TEST.md` was executed on physical hardware.** The closest evidence (Windows Sandbox, not a substitute) for each item in the task:

| Item | Physical PC | Clean VM evidence (Windows Sandbox) |
|---|---|---|
| Checksum before install | NOT TESTED | PASS: re-hashed inside the VM (dev.3 and dev.4) |
| Standard-user install, shortcuts | NOT TESTED | PASS: per-user, no admin prompt, Start Menu + desktop (dev.3 28/28, dev.4 34/34) |
| Tray icon | NOT TESTED | **Not observable** headless (the process runs; the icon wasn't visually checked) |
| Single instance, clean shutdown | NOT TESTED | PASS (pythonw 1 → 1; `--stop`) |
| Missing Ollama | NOT TESTED | PASS (`degraded`, `ollama=false`, install guidance data) |
| Model download after consent | NOT TESTED | PASS (§5) |
| Successful inference | NOT TESTED | PASS on CPU (§5) |
| Knowledge-folder indexing (harmless PDF) | NOT TESTED | PASS: indexed, 1 chunk; document question answered (dev.4) |
| Restart persistence | NOT TESTED | PASS: conversations, memory, index, watcher |
| Upgrade | NOT TESTED | PASS: dev.2 → dev.3 (34/34), **dev.3 → dev.4 (34/34)**, both over the running app, with backup and data kept |
| Uninstall / reinstall recovery | NOT TESTED | PASS: data kept, memories found again |
| SmartScreen on a downloaded copy, Defender, GPU use after install | NOT TESTED | n/a |

## 5. Task B: clean-PC model installation: **PASS in a clean VM** (dev.4 22/22; dev.3 21/22)

Clean Windows 11 Enterprise 22621 in Windows Sandbox: **networking on**, 8 GB RAM, **no GPU** (CPU only), no Python, no Ollama.
- Ollama 0.40.5, official installer (Authenticode **Valid**, Ollama Inc.; SHA-256 `ea0b5068…a519`), installed silently in 70–72 s.
- Command: `packaging\windows\sandbox\run-sandbox-modeltest.ps1 -OllamaSetup build\taskb-input\OllamaSetup.exe -Pdf build\taskb-input\sample.pdf -Version <v>`

| Check | dev.3 | dev.4 | Evidence (dev.4) |
|---|---|---|---|
| Detects missing Ollama | PASS | PASS | `ollama=false`, `status=degraded`, `installed=false` |
| CPU-only option | PASS | PASS | 0 GPUs, 8 GB → **Minimal** recommended ("8.0 GB RAM and no suitable GPU detected") |
| Detects Ollama once installed | PASS | PASS | `ollama=true`, `llm_ready=false` |
| Explains size and disk before download | PASS | PASS | `qwen2.5:1.5b ~1.0 GB, needs 1.65 GB free, 34.1 GB free` · `nomic-embed-text ~0.27 GB, needs 0.81 GB` |
| Consent required | PASS | PASS | no `confirm` → HTTP 400; 0 models in Ollama |
| **Network error** (firewall blocked Ollama's outbound traffic) | PASS | PASS | failed: "Ollama: pull model manifest: … dial tcp …: An attempt was made to access a socket in a way forbidden …" |
| Embedding model download | PASS | PASS | done in 35 s, listed by Ollama |
| **Interrupted download** (Ollama killed at 25%) | PASS, *empty message* | PASS | failed: "The connection to Ollama was interrupted during the download. Press Download again to resume (ReadError)" |
| Interrupted model not shown as installed | PASS | PASS | `missing=qwen2.5:1.5b` |
| Resume and complete | PASS | PASS | done in 97 s; Ollama lists `qwen2.5:1.5b` |
| **Inference verified** after install | PASS | PASS | `/setup/test`: ok, reply "OK", 3.8 s on CPU; a real chat answer (135 characters) |
| **Model startup failure** | PASS, *raw 404 text* | PASS | "Ollama couldn't run qwen2.5:14b: model 'qwen2.5:14b' not found" |
| Knowledge PDF indexed | PASS | PASS | `sample.pdf` indexed, 1 chunk |
| Document question answered | **FAIL (D3)** | **PASS** | "The test codeword is BLUE-HERON-9." · source `sample.pdf, page 1` |
| Restart persistence | PASS | PASS | 2 conversations, 1 memory, 1 chunk, watcher running, model ready |
| **Insufficient disk space** | — | — | NOT TESTED for real (the VM had 34 GB free). Covered by automated tests (`test_disk_check_blocks_unsafe_download`, `test_download_needs_consent_and_disk`) |

Model installation is reported as successful **only** where inference afterwards answered (`inference_verified`, `chat_answers_on_cpu`).

## 6. Task C: online packaging build: **PASS (dev.4)**, after finding D2 and F7

| Step | Result |
|---|---|
| Isolation | First run in a separate git worktree (`D:\Abhify_OnlineBuild_Test`, removed afterwards), so dev.2 and dev.3 couldn't be touched; dev.4 then built in the repo into its own `dist\windows\2.0.0-dev.4\` |
| Python 3.11.9 embeddable + NSIS 3.10 downloaded fresh | PASS: both matched their **pinned SHA-256** |
| Dependency resolution (`PIP_NO_CACHE_DIR=1`) | PASS: all packages from PyPI. The pip resolver warning about `transformers`/`whisperx` refers to the **build machine's own** Python, not the bundled runtime |
| Package completeness | **FAIL on dev.3 code (D2)**: `langchain_text_splitters` missing, so the app crashed on import. **PASS on dev.4** |
| Runtime imports | PASS (library list) and, new in dev.4, **app imports ok: 56 routes** |
| Build paths | **FAIL on the first dev.4 build (F7)**: 37 launchers. **PASS** after the fix (every runtime file scanned) |
| Clean install of the online build | PASS: dev.4 upgrade test 34/34, model test 22/22, clean test (§8) |
| Checksums | PASS: `SHA256SUMS.txt` matches; verified again inside the VM |
| Versioned output | PASS: `dist\windows\<version>\`; dev.2 and dev.3 re-hashed unchanged after every build |
| Duration | ~6.3 min for the online build (network 2.6–4.5 MB/s at the time) |
| Long build paths (P1) | First attempt failed in NSIS (path > 260 characters); building from a short path works |

## 7. Task D: mobile acceptance: **NOT TESTED**

No Android or iPhone device was available. **Every device check in `MOBILE_ACCEPTANCE_TEST.md` remains NOT TESTED:** pairing, HTTPS (Tailscale), home-screen install, authentication, reconnection, status indicators, microphone permission, file import, admin restrictions as seen on a phone.

No simulated or logic test is counted as device acceptance. For reference only, the earlier real-LAN server test and the Node logic tests from dev.3 passed (see `MOBILE_ACCEPTANCE_TEST.md`).

## 8. Automated tests and Sandbox runs

| Run | Result |
|---|---|
| Full suite on `01bc0fb` (dev.4 code), 3 consecutive runs | **219 passed** each time; 0 failed, 0 skipped, 0 errors (79.8 s each). Ports 7914–8213 were reserved by Windows during these runs |
| Full suite on `292c848` (final dev.4 code incl. F7), 3 consecutive runs, 2026-10-19 | **220 passed** each time; 0 failed, 0 skipped, 0 errors (124–218 s; machine under other load). Ports 7914–8213 still reserved |
| Earlier, before the D4 fix | 216 passed, 1 failed (`test_launcher_picks_next_free_port`, every run), which exposed D4 |
| dev.3 Sandbox, model setup (networking on) | 21/22 (D3) |
| dev.4 Sandbox, upgrade from dev.3 | **34/34** |
| dev.4 Sandbox, model setup (networking on) | **22/22** |
| dev.4 Sandbox, clean install (no network) | **28/28** (including no build paths in 6,440 `.pyc`, `INSTALL.html`, remote token can't read system routes, uninstall keeps data, reinstall recovers memory) |

Command: `backend\.venv\Scripts\python.exe -m pytest backend\tests -q -p no:cacheprovider -rs`

## 9. Remaining blockers and risks

**Blockers (keep NO-GO):**
1. **Second physical PC acceptance:** not performed (Task A).
2. **Real Android and iPhone acceptance:** not performed (Task D). Alternatively, you could explicitly accept phones as a disclosed limitation of a *dev* prerelease.

**Risks (not blockers for internal testing):**
- **Unsigned installer:** SmartScreen "unknown publisher" warning; possible antivirus quarantine. See `CODE_SIGNING.md`.
- **Transitive dependencies aren't locked:** online builds can change as PyPI changes. Add a hashed lock file (`pip-compile --generate-hashes`, then `pip install --require-hashes`) before a public release.
- **Never verified:** GPU use after installation on a real NVIDIA PC, SmartScreen on a downloaded (Mark-of-the-Web) copy, and a real "disk full" during a download.
- **D3 is a targeted mitigation.** Small models may still not search documents for indirect questions ("what was that codeword?"); explicit questions work.
- **The PWA remembers browser data per address (port).** If the launcher ever had to use a new port, the browser would start with fresh local settings. Server-side data (chats, memories, documents) isn't affected. Mitigated by remembering the port.
- **The Sandbox scripts assume port 8000** inside the VM. That is fine on a clean VM; a physical-PC tester should read the port from the tray tooltip.

## 10. What to run next (on real devices)
1. On a second Windows PC (standard user): `PHYSICAL_PC_ACCEPTANCE_TEST.md` with **dev.4** (`1f0a61ca…36b9ee`), steps 0–16.
2. On an Android phone and an iPhone: the device table in `MOBILE_ACCEPTANCE_TEST.md`, with dev.4.
3. Then repeat this gate.
