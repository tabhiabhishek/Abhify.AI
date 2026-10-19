# Physical-device acceptance and final release gate: Abhify.AI 2.0.0-dev.4 → 2.0.0-dev.5

Date: 2026-10-19. Branch `feature/m2-knowledge-packaging`. Nothing was merged, tagged, signed or published. `main` and `v1.0.0` are unchanged at `574072e`. The only tag is `v1.0.0`.

Legend: **PASS** (performed, with evidence) · **FAIL** · **BLOCKED** (couldn't be performed here) · **NOT TESTED**.

> **Environment, stated plainly.**
> - Only the development PC was available: Windows 11 Pro, RTX A2000 12 GB.
> - **No second physical PC. No Android phone. No iPhone.** No phone was connected over USB, there's no ADB, and Tailscale isn't installed.
> - Other devices on the local network were **not** accessed (no authorization).
> - Every installation test below ran in **Windows Sandbox** (a clean Windows 11 VM) and is labelled as such. **None of it is reported as physical-device acceptance.**

## 1. Decision

# 🔴 NO-GO for merge to `main` or a public release
## 🟢 GO for internal testing and for the physical acceptance tests, using **2.0.0-dev.5**

- **No critical security or data-loss defect is known.** No new product defect was found in this run.
- Tasks 3 and 4 are done:
  - dependencies are now **hash-locked**, and the installer is built from the lock
  - the build is **signing-ready**, verified with a lab certificate in the Sandbox
- **Required physical acceptance is still outstanding** (Tasks 1 and 2: no devices), so the result must stay NO-GO.

## 2. Final installer

| Item | Value |
|---|---|
| Path | `dist\windows\2.0.0-dev.5\Abhify.AI-Setup-2.0.0-dev.5.exe` |
| Version | `2.0.0-dev.5` (version resource, `/health`, uninstall entry) |
| Size | 121,257,187 bytes |
| SHA-256 | `612750ed34c39fb9697d82bcfe3f0f81b343397cf8a3cd86f38bc55cce72f2f2` (matches `SHA256SUMS.txt`; re-hashed inside the Sandbox) |
| Signature | **NotSigned** (no certificate exists; signing needs your approval) |
| Built from | commit `931969d`, online build from `packaging\windows\requirements.lock` (`--require-hashes --only-binary :all:`, pip cache off), 6.3 min |
| Contents | 34/34 app files identical to `931969d`. Runtime packages = the lock **exactly** (118/118, same versions) |
| Payload scan | 15,176 files: 0 build-machine paths, 0 personal strings, 8 known false-positive secret patterns, `check_package` 0 problems |
| Build checks | DLL check ✓ · bytecode ✓ · runtime imports ✓ · **app imports (56 routes)** ✓ · path check of every runtime file ✓ · package check ✓ |

**dev.5 vs dev.4:** no application code changes. Same 118 dependency versions; dev.5 installs them from the hash lock. The new pieces are packaging only (lock, signing hooks, `--out-dir`, no overwriting).

**Earlier artifacts, all unchanged** (re-hashed after every build in this run):

| Version | SHA-256 |
|---|---|
| dev.4 | `1f0a61ca…36b9ee` (NotSigned) |
| dev.3 | `ccbd42f9…e057e3` (not for further distribution) |
| dev.2 | `9e299def…52f8b2` |

## 3. Task 1: physical Windows PC: **BLOCKED** (no second PC)

No step of `PHYSICAL_PC_ACCEPTANCE_TEST.md` was performed on physical hardware.

| Item | Physical PC | Evidence available instead (Windows Sandbox, **not** acceptance) |
|---|---|---|
| Checksum before execution | NOT TESTED | PASS: re-hashed inside the VM |
| Installation, shortcuts | NOT TESTED | PASS: dev.5 clean 28/28 |
| Tray behaviour | NOT TESTED | Not observable headless |
| Single instance, clean shutdown | NOT TESTED | PASS (dev.5) |
| Model setup, consent, inference | NOT TESTED | PASS on CPU: dev.4 22/22, **dev.5 22/22** (reply "OK" in 4.7 s) |
| Knowledge indexing, document citations | NOT TESTED | PASS: `sample.pdf, page 1` cited, codeword answered (dev.4 and dev.5) |
| Restart persistence | NOT TESTED | PASS (dev.4 and dev.5) |
| Upgrade | NOT TESTED | PASS: **dev.4 → dev.5 34/34** over the running app |
| Uninstall / reinstall | NOT TESTED | PASS: data kept, memory recovered (dev.5) |
| **CPU/GPU detection on real hardware** | NOT TESTED on a second PC | Dev PC only (not acceptance): RTX A2000 12 GB → Balanced; Sandbox: no GPU → Minimal |
| **GPU actually used after installation** | NOT TESTED | Never verified on any installed build |

## 4. Task 2: Android and iPhone: **NOT TESTED** (no devices)

| Check | Android | iPhone |
|---|---|---|
| Device / OS / browser | — | — |
| Pairing, secure connection (HTTPS via Tailscale) | NOT TESTED | NOT TESTED |
| Home-screen installation | NOT TESTED | NOT TESTED |
| Connected / disconnected / AI-unavailable status | NOT TESTED | NOT TESTED |
| Chat, knowledge search | NOT TESTED | NOT TESTED |
| Authentication, remote admin restrictions | NOT TESTED | NOT TESTED |
| Connection loss and recovery | NOT TESTED | NOT TESTED |
| Microphone permission, explicit file import | NOT TESTED | NOT TESTED |
| Offline behaviour, browser limitations | NOT TESTED | NOT TESTED |

No emulator, Node test or simulated request is counted here.

## 5. Task 3: dependency reproducibility: **PASS**

| Step | Result |
|---|---|
| Versions used in the successful dev.4 build | 118 distributions read from its build staging, recorded in `packaging\windows\build-versions-2.0.0-dev.4.txt` |
| Locking approach | `pip-compile` (pip-tools 7.6.2) in a **throw-away venv** on Python 3.11 / Windows x64 (the runtime's platform), with `--generate-hashes`, constrained to the dev.4 versions. Output: `packaging\windows\requirements.lock` |
| Lock content | **118 packages, 3,235 SHA-256 hashes**, identical names and versions to dev.4 (0 extra, 0 missing). No machine paths. Every top-level pin in `requirements.txt` matches |
| Compatibility | Not blindly pinned: the set is the one that was already installed, built and tested in dev.4 (Sandbox 34/34 and 22/22). `pip check` in the lock venv: no broken requirements |
| Clean install from the lock | Fresh Python 3.11 venv, `pip install --require-hashes --only-binary :all: -r requirements.lock`, pip cache off: **exit 0** |
| Regression tests **from the lock venv** | **230 passed**, 0 failed, 0 skipped (an earlier run there had 1 failure, caused by an outdated literal check in a packaging test, which was fixed; not by the lock) |
| Packaging from the lock | dev.5 online build: runtime equals the lock exactly (118/118); every build check passed |
| Clean installation of that build | Sandbox: dev.5 clean **28/28**, dev.4 → dev.5 upgrade **34/34** |
| Workflow | The build uses the lock by default; `--no-lock` resolves fresh; `lock.ps1` regenerates it; `test_lock_and_signing.py` guards the hashes, the agreement with `requirements.txt`, the dev.4 provenance and the absence of machine paths. See `RELEASE_PROCESS.md` → Dependency lock |
| Scope / limits | The lock covers **Windows x64 + Python 3.11 only**; Docker, macOS and Linux still use `requirements.txt`. pip itself and the embeddable Python and NSIS downloads are pinned separately (by SHA-256 in `build.py`). Not bit-for-bit reproducible: NSIS embeds timestamps, so two builds from the same lock give different installer hashes but identical contents |
| Build-machine warning | pip prints a resolver "ERROR" about `transformers` / `whisperx`. Those packages are in the **build machine's** Python, not in the runtime (the runtime equals the lock); it has no effect on the installer |

## 6. Task 4: code-signing readiness: **READY, not signed**

Details: `CODE_SIGNING.md` → *Readiness status*.

| Item | Result |
|---|---|
| Workflow | `sign.ps1` (Store/HSM or Azure Trusted Signing: sign, timestamp, verify `/pa`, Valid, publisher, timestamp) and `build.py --sign` (NSIS `!uninstfinalize` + `!finalize`; checksums only after a Valid signature) |
| Secrets | None in the repo. Configuration comes from `ABHIFY_SIGN_*` environment variables only |
| NSIS wiring | PASS: a dummy command was called for `Uninstall.exe` and then for the installer |
| Lab test (throw-away certificate inside Windows Sandbox) | **10/10 PASS**: valid signature + DigiCert RFC 3161 timestamp; checksum recomputed on the signed file; wrong publisher refused; misconfiguration refused; tampering detected (`HashMismatch`); the signed installer installs and runs |
| Bug found by the lab test | `sign.ps1` "lab mode" verified **without** `/pa`, so it used the driver policy and failed for every program. **Fixed**: always `/pa`, and no lab shortcut |
| Host safety | 0 lab certificates in any host store; no signed copy left the VM; dev.4 still `NotSigned` |
| Certificate requirements | Checklist in `CODE_SIGNING.md`: code-signing certificate, key in a FIPS 140-2 L2+ HSM or token (or Trusted Signing), validated publisher name, timestamp URL, MFA, renewal date |
| Not done (needs your approval) | Choosing and buying a certificate or Trusted Signing account, identity validation, a real signed build, CI signing |

## 7. Automated tests and Sandbox runs (this gate)

| Run | Result |
|---|---|
| Full suite, dev venv, on the final dev.5 code, 3 runs | **230 passed** each time (89–209 s); 0 failed, 0 skipped. Run 1 overlapped a Sandbox VM |
| Full suite in the **fresh lock venv** | **230 passed** |
| Sandbox dev.5 clean install (no network) | **28/28** |
| Sandbox upgrade dev.4 → dev.5 over the running app | **34/34** |
| Sandbox signing lab | **10/10** |
| Sandbox dev.5 model setup (network on, 8 GB, no GPU) | **22/22**: missing Ollama, consent, network error, interrupted and resumed download, CPU inference, model-start failure, PDF indexed, document answer with citation, restart |

## 8. Remaining defects and risks

**Blockers (NO-GO):**
1. Second physical PC acceptance: BLOCKED.
2. Real Android and iPhone acceptance: NOT TESTED. Alternatively, you could accept phones as a disclosed limitation of a dev prerelease.

**Open risks:**
- **Unsigned installer:** SmartScreen warning, possible antivirus quarantine. Signing is ready but needs a certificate and your approval.
- **GPU use** after installation, and **SmartScreen / Defender** on a downloaded copy: never verified on real hardware.
- A **real disk-full** during a model download: automated tests only.
- **D3 mitigation** covers explicit document questions; indirect questions on very small models may still not search the documents.
- **The lock is Windows-only.** Refreshing it changes the dependency set; follow `RELEASE_PROCESS.md` (fresh lock venv, full suite, Sandbox).
- **Sandbox scripts assume port 8000** inside the VM; real PCs may use another port (the launcher remembers it; see the tray tooltip).

**Known defects:** none open in the code that is known from tests.

## 9. Approval requested (not done)
- Merge, tag, sign or publish: **not performed**, and not recommended until §8 items 1–2 are done.
- To move forward:
  1. Run the physical PC test and the phone tests with **dev.5** (`612750ed…72f2f2`).
  2. Decide on a signing provider (`CODE_SIGNING.md` step 1).
  3. Then repeat this gate.
