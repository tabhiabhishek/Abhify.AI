# Baseline test report (Phase 0) — 2026-11-02

Taken before the local-first/hybrid implementation continues. All runs are on the development PC (DESKTOP-5VPDR45, Windows 11, Python 3.11.9, JDK 17.0.20.1). No phone was attached to this PC.

| Suite | Command | Result | When |
|---|---|---|---|
| Desktop/web backend (incl. dev.7 web on-device tests) | `backend\.venv\Scripts\python -m pytest backend\tests` | **319 passed, 0 failed** (2 deprecation warnings), 207.6 s | 2026-11-02 |
| Cloud service (`cloud/`, new) | `python -m pytest cloud\tests` | **35 passed, 0 failed**, 8.7 s | 2026-11-02 |
| Android JVM unit tests (baseline app 2.0.0-rc.1) | `gradlew testDebugUnitTest` | **41 passed, 0 failed** (plus 1 opt-in live-network test, skipped) | last run 2026-10-26 on the rc.1 sources |
| Android live feed test (opt-in) | `ABHIFY_LIVE=1 gradlew testDebugUnitTest --tests *LiveFeedsTest*` | **8/8 feeds parsed** | 2026-10-26 |
| Android release build + lint | `gradlew assembleRelease lintRelease` | BUILD SUCCESSFUL, **0 lint errors** (warnings only) | 2026-10-26 |
| Cloud gateway, real model | local `cloud/` server + local Ollama `qwen2.5:latest` | register → stream: 1.6 s cold / 1.1 s warm, labelled `served_by: cloud`; no token → 401 | 2026-11-02 |

## Android unit-test breakdown (baseline)
| Class | Tests | Covers |
|---|---|---|
| ModelLifecycleTest | 15 | resumable/verified download, corruption, low storage, HTTPS-only, activation after self-test, failed update keeps the old model |
| ModelSelectionTest | 11 | catalog validation/pinning, device classes 8/6/4/3/2 GB, Android 9, 32-bit, Android Go, memory pressure, prompts, memory command |
| LearnNewsMarkdownTest | 10 | Markdown, learning bank, AI-output parsing, streaks, RSS/Atom, merge/dedupe |
| UpdateSignatureTest | 5 | Ed25519 RFC 8032 vectors, envelopes signed by the Windows tooling, update policy |

## Physical device (owner's Redmi K50i, Android 14, MT6895Z)
| Version | Result | Evidence |
|---|---|---|
| 0.1.0-dev.1 | self-test FAILED (native libs not extracted), fixed in dev.2 | `adb` output, root-caused |
| 0.1.0-dev.2 | installed; Qwen2.5 1.5B downloaded and **passed the real test answer**; chat works | **owner-reported** |
| 0.1.0-dev.3 / dev.4 / 2.0.0-rc.1 | **NOT TESTED on the phone** | — |

No benchmark numbers from the phone have been received yet.

## Notes
- The Android sources are now mid-change for the new phases, so the Android baseline is the last recorded run on the rc.1 sources. It will be re-run as soon as the tree compiles, and the comparison reported.
- No test was skipped or edited to make it pass.
