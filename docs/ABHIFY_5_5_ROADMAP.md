# Abhify.AI 5.5 roadmap (internal milestone)

**Status (2026-11-18):** internal build `build/internal/Abhify-5.5-dev.1.apk` exists. Nothing is phone-validated or committed yet. This file only covers 5.5. The long-term plan stays in [ABHIFY_DEVELOPMENT_ROADMAP.md](ABHIFY_DEVELOPMENT_ROADMAP.md), and status per area is in [ABHIFY_IMPLEMENTATION_STATUS.md](ABHIFY_IMPLEMENTATION_STATUS.md).

| Phase | Scope | State |
|---|---|---|
| 0 | Baseline check: 5.4 and 5.4-dev.1 APK hashes, signing certificate, Android tests 388/0/4, backend tests 407 + 2 skipped | **Done** |
| 1 | Context finder in chat: your records go into local answers only, with the sources listed | **Done** (Android) |
| 2 | Agent expansion: status and blockers, priorities, related documents, duplicates; new tools registered | **Done** (Android) |
| 3 | Daily plan: recent project work breaks ties; desktop Today | **Done** |
| 4 | Study skills: answer threshold you can change (3/5/10), trend, revision to-do after you confirm | **Done** (Android); desktop skill map **Done** (read-only) |
| 5 | News: "Also reported by N sources" badge on each story; compare and learn as in 5.4-dev.1 | **Done** (Android) |
| 6 | Desktop/web parity: Today plan and Skills; briefing and search already present | **Partial**: no agent or voice loop on desktop |
| 7 | Search: results grouped by type with counts | **Done** (Android); filters per type are deferred |
| 8 | Voice: "summarise my project" / "what's blocking my project" | **Done** (Android) |
| 9 | Performance and security review of the new paths | **Done** (see the architecture doc, section 5) |
| 10 | Docs and phone checklist (tests 53–60) | **Done** |
| 11 | Full regression and internal build | **Done**: Android 396/0/4, backend 409 + 2 skipped, lint 0 errors |

## Next (needs owner input or a phone)
1. Manual phone run of checklist tests 1–60 ([ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md)).
2. Approval to commit the branch, and to make 5.5 the official version (versionCode 5050099).
3. Desktop agent workflows (read-only status first), search type filters, and evening and weekly briefing on desktop.
