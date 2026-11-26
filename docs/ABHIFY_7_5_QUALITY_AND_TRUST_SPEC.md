# Abhify.AI 7.5: quality and trust specification

**Status:** proposal. Signing work is preparation only until you provide your own keys and certificates and approve.

## 1. Android production signing (preparation only)

**Hard rule:** I never create, receive, store, request, copy or hold the production keystore or its passwords. You create and keep them. The debug certificate (`1d2d1fdb…`) and every existing APK stay untouched.

| ID | Requirement | Test | Acceptance |
|---|---|---|---|
| QT-001 | `build.gradle.kts` gets a `release` signing config that reads only environment variables (`ABHIFY_RELEASE_KEYSTORE`, `ABHIFY_RELEASE_KEY_ALIAS`, and password variables). If any is missing, the release build fails with a clear message and never falls back to debug | static test + build without variables | Fails closed |
| QT-002 | `docs/ANDROID_RELEASE_SIGNING.md`: `keytool` command for you to run, backup instructions (two offline copies, password manager), what to never commit | review | Owner approves |
| QT-003 | Verification script: `apksigner verify --print-certs`, compared against the fingerprint you record in `docs/release-fingerprints.txt` (certificate fingerprint only, never the key) | script test on a debug APK (labelled debug) | Script prints PASS or FAIL and the signer |
| QT-004 | `.gitignore` and the package checks refuse `*.jks`, `*.keystore` and `*.p12` in source and packages (`*.keystore` is already ignored) | test | Packaging check fails if one is present |
| QT-005 | A build is labelled "production-signed" only after QT-003 passes on that exact artifact | release script | Manifest shows signer and verification result |

## 2. Windows Authenticode signing (preparation)

| ID | Requirement | Acceptance |
|---|---|---|
| QT-010 | `packaging/windows/sign.ps1` (exists from 2.0 dev.5) is reviewed; it uses a certificate from your store or HSM by thumbprint from an environment variable; no PFX in the repository | Dry run without a certificate fails closed |
| QT-011 | Timestamping with an RFC 3161 server (configurable) | `Get-AuthenticodeSignature` shows a timestamp in the test with your certificate |
| QT-012 | Post-build verification: `signtool verify /pa /v` plus Sandbox install check; the release manifest records the signature state | Unsigned builds stay labelled "not Authenticode-signed" |
| QT-013 | `docs/WINDOWS_CODE_SIGNING.md` updates `CODE_SIGNING.md` and `SIGNING_PROVIDER_DECISION.md` (Certum OSS / SSL.com IV for an individual in India) | Owner decision recorded |

## 3. Opt-in local crash reports (desktop)

| ID | Requirement | Test |
|---|---|---|
| QT-020 | Off by default; a Settings switch with a plain explanation of what is collected | E2E |
| QT-021 | When on: uncaught Python exceptions (backend), unhandled JS errors and rejections (frontend), and launcher failures are written to `crash/` as JSON (time, version, component, exception type, stack with paths redacted, last 25 pipeline events with no content), like Android `CrashNote` | U |
| QT-022 | Redaction: no message, document or memory text; no tokens or keys (reuse `log_redaction`); user paths replaced | SC: `crash_redaction_cases.json` |
| QT-023 | Review screen: list, view, copy, export (on request), delete one or all | E2E |
| QT-024 | A corrupted report file is skipped and flagged, and never crashes the viewer | U |
| QT-025 | No network: a static test asserts that the crash module imports no network clients | U |

## 4. Accessibility audit (three platforms)

**Deliverable:** `docs/ABHIFY_ACCESSIBILITY_REGISTER.md`. Each issue gets an id, platform, screen, WCAG 2.2 criterion, severity, fix, and test method.

**Known issues from the audit, to seed the register:**

| Issue | Platform |
|---|---|
| No skip link | Web/Desktop |
| No focus trap in sheets (focus can leave a dialog) | Web/Desktop |
| `#voiceOverlay` not managed by `bindDialogs` | Web/Desktop |
| Colour-only Home status dots | Web/Desktop |
| Tablists without arrow-key roving (main tabs, Study nav, News categories) | Web/Desktop |
| Native `confirm()` and `prompt()` used for most consent (screen-reader support is OK, but styling and focus are limited) | Web/Desktop |
| No Compose UI tests; contentDescription coverage unknown | Android |
| Touch targets in dense rows not verified | Android |

| ID | Requirement | Test |
|---|---|---|
| QT-030 | Automated checks: axe-core run in headless Chrome over every main view in both themes, as a vendored dev tool (dev only, not shipped); Android lint accessibility checks plus Compose semantics tests for the main screens | CI-style scripts |
| QT-031 | Manual checks: NVDA + keyboard (Windows), TalkBack (Android, physical device), 200% zoom, reduced motion, high contrast | You, or me with screen-reader tooling, logged with date and device |
| QT-032 | Remediation of all P0/P1 register items | Re-run checks |
| QT-033 | No claim of compliance from automated tests alone; the register states what was manually verified | review |

## 5. Local-model evaluations

**Deliverable:** `benchmarks/` (dev tool) and `docs/benchmarks/RESULTS_<date>.md`.

| ID | Requirement |
|---|---|
| QT-040 | Versioned dataset `benchmarks/abhify-eval-1/`. About 200 fixed items across reasoning, summarization, coding, document QA (fixed document set with answer spans), Study content (lesson sections, MCQ validity), instruction following (JSON and format), safety (refusals), privacy (does not repeat planted secrets), and hallucination (unanswerable questions → must say unknown) |
| QT-041 | Fixed settings: temperature 0, seed where supported, fixed context size, fixed prompt templates; the result file records the model digest |
| QT-042 | Scoring: deterministic checks where possible (exact, regex, JSON schema, citation span overlap); LLM-judge **not** used for pass/fail |
| QT-043 | Measurements: latency (first token, total), tokens/s, RAM/VRAM peak, CPU/GPU utilisation where available, timeouts and failures |
| QT-044 | Per model × device class (Windows GPU / Windows CPU-only / Android 8 GB / Android 6 GB / WebGPU laptop). No cross-model inference of results |
| QT-045 | Model recommendations in the hardware advisor cite benchmark evidence. The user's selected model is never auto-replaced |

## 7.5 acceptance (release gate)

- No production key held by me or stored in the repository (QT-001, QT-004).
- Signed labels appear only after verification (QT-005, QT-012).
- Crash reports are off until consent, and contain no content or network code (QT-020 to QT-025).
- The accessibility register exists, with manual results recorded (QT-031, QT-033).
- Benchmarks are reproducible: the same model and settings give the same deterministic scores (QT-041).
- No external telemetry introduced.
