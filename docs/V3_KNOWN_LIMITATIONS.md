# Abhify.AI 3.0: known limitations

## Not verified yet

1. **No physical-phone or emulator testing of 3.0.** The UI, the five tabs, PDF extraction and the v5 upgrade on a real RC.4 database have **not** been run. The two instrumented tests are built but not run. "Build verified; physical phone acceptance pending."
2. **No real-provider calls.** No keys were available. The response-reliability fixes were verified with simulated streams in every provider's format, plus the live error formats captured earlier with a fake key. `RealProviderTest` is ready for the owner's keys.

## Release

3. **Debug build.** There is no release keystore, so 3.0.0 is a debug-signed APK. A production release needs a release key.
   - A release key cannot upgrade debug-signed installs: users would have to reinstall, which loses data unless they export first.
   - The owner must decide, and plan a migration (export → reinstall → import, where import is not implemented yet).

## Feature gaps

4. **Local "RAG" is keyword (BM25) retrieval.** There are no embeddings or semantic search, so different words for the same idea don't match.
5. **PDF:** text layers only. Scanned PDFs and images need OCR, which waits for an owner decision (ML Kit vs Tesseract). Password- and certificate-encrypted PDFs are refused. `pdfbox-android`'s last release was 2023-01.
6. **Work tab:** local only. Team workspaces, organisation identity, roles, permissions, audit, training assignments and dashboards need an organisation server (not built).
7. **Not implemented** (see the [feature status](archive/android-3.x/V3_FEATURE_STATUS.md)):
   - web search and web citations;
   - tool calling and automation;
   - model comparison, request history, prompt library;
   - reminders, calendar, daily planner, resume assistant;
   - vector search, image understanding;
   - secure backup, PDF export, export of the library, notes and cards.

## Behaviour to expect

8. **Small local models:** Qwen 0.5B/1.5B often produce unusable flashcard, quiz or plan formats. The strict parsers reject them with "try again" instead of saving junk. Online providers do better.
9. **Continue:** depends on the model following "continue where you stopped". Repeated text is removed only when it overlaps exactly (at least 8 characters).
10. **Abhify server answers:** still capped by the server's own `ai_max_tokens` (1024 by default), and the server doesn't report `finish_reason`.
11. **Readability:** contrast is unit-tested on the theme tokens. Layouts at 1.3×/1.5× font scale have **not** been checked on a device.
