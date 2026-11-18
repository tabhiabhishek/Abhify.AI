# Abhify.AI master enhancement audit (2026-11-09)

The detailed baseline is in [ABHIFY_COMPREHENSIVE_ENHANCEMENT_AUDIT.md](ABHIFY_COMPREHENSIVE_ENHANCEMENT_AUDIT.md):
- the test counts before any change;
- the live Task Runner reproduction;
- every root cause.

This document is the feature inventory for the 20-area master program. Live status is in [ABHIFY_MASTER_ENHANCEMENT_TRACKER.md](ABHIFY_MASTER_ENHANCEMENT_TRACKER.md).

## Baseline
- **Desktop/web:** backend 2.0.0-dev.8, database schema v5. Tests: 347 passed, 1 skipped (the opt-in live test).
- **Android:** 4.6.1 (versionCode 4060199), database v17. Tests: 275 run, 0 failed, 4 skipped.
- **Physical Android device:** none connected.

## Feature inventory (before this program)
| # | Area | Desktop/web | Android | Main gaps found |
|---|---|---|---|---|
| 1 | Home | bento cards, quick actions, customization | dashboard, header launcher | no project or "continue" cards |
| 2 | Chat | works; **not streamed**; no stop | streamed; stop; private chat **crashes** | latency; private chat |
| 3 | Task Runner | 5 templates; **quiz fragile** | 5 templates; **document tasks unreliable** | parser, consent, lookup |
| 4 | Study | interview, MCQ, document quiz in the Task Runner | tutor, plans, cards, quiz | adaptive practice |
| 5 | News | feeds, search, briefing | the same | **no audio** |
| 6 | Documents and knowledge | upload (PDF/TXT/MD), folder watcher, Chroma | library, OCR, chunks, retrieval | diagnostics UI |
| 7 | Memory | categories, list, delete, FTS | items, review, profile | duplicate and conflict suggestions |
| 8 | AI models | setup wizard, model chip, default model | catalogue, download, GPU policy | – |
| 9 | Performance | live samples page | inside AI models | resource warnings |
| 10 | System | System & data page | spread over More | read-only diagnostics run |
| 11 | Settings | partial dialog | personalization only | **consolidation** |
| 12 | More | n/a | 7 groups, mixed | consolidation, crash |
| 13 | Projects | **none** | workspaces without instructions, archive or export | **missing** |
| 14 | Palette and search | command palette | unified search | projects, tasks |
| 15–16 | Platforms | – | – | no phone for validation |
| 17 | Privacy | local-only routes, redaction | consent before online, private chat | isolation per project |
| 18 | Backup | zip backup and restore | local and Drive backup | new JSON files not yet in the backup set |
| 19 | AI evaluation | none in the repo | none | – |
| 20 | Automation | none | backup schedule only | – |

## Duplicates and risks found
- **Two delete behaviours (Android):** workspace delete removed the documents imported into it. Project delete now keeps them; see [ABHIFY_PROJECT_WORKSPACE.md](ABHIFY_PROJECT_WORKSPACE.md).
- **`aiTask` busy guard (Android):** it returned silently, leaving callers waiting. It now reports "busy".
- **Web pages:** opening System, Memory, Knowledge or Performance from Settings could stack two pages. Fixed.
- **Service worker:** `CACHE_VERSION` is tied to the app version by a test. Installed PWAs keep the old `app.js` cached until the next version bump, which wasn't allowed in this program.
- **Stale running backend:** a backend left running on port 8000 runs the code it started with. Restart it to get these changes (`start.bat stop`, then `start.bat`).
