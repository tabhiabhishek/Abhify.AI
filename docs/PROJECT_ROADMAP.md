# Abhify.AI Android: seven-phase roadmap (updated for 4.4.0)

**Baseline:** [PROJECT_BASELINE.md](PROJECT_BASELINE.md).

**This roadmap compares each requested item with the code as it is today.** Most of it already exists, so the work is the **gaps**, not a rebuild. Items marked **(approval)** add a feature, a dependency or a data change and wait for your decision.

The desktop app (2.0.0-dev.x, Ollama) has its own plan in [ROADMAP.md](ROADMAP.md).

**Status key:**
- ✅ Implemented (verified in code and tests).
- 🟡 Partial.
- ❌ Missing.
- 📱 Needs the phone.
- N/A: doesn't apply to Android.

## Phase 1: UI/UX validation and polish (4.3.x)
| Item | Status |
|---|---|
| Branded launch screen, exact tagline, cold start only | ✅ (4.3.0–4.3.1) 📱 visual check pending |
| Shared layouts and tokens, Markdown fixes, dialogs scroll, accessibility labels | ✅ |
| Visual validation in light and dark themes, large font, small screen | ❌ **📱 blocked: no device connected** |
| Vector icons instead of emoji | ✅ **4.4:** official Material outlined icons (vector drawables) in the bottom bar and header; drawer and settings rows keep emoji tiles |

**Acceptance:** screenshots of the main screens in both themes, checked on the phone. **Complexity:** low. **Next:** connect the phone.

## Phase 2: AI chat experience (4.4)
| Item | Status |
|---|---|
| Real token streaming (local llama.cpp and online providers) | ✅ |
| Stop generation (keeps the partial answer, marked "stopped") | ✅ |
| Regenerate the last answer | ✅ |
| Continue an answer cut off by length | ✅ |
| Edit and resend | ✅ the last question in place; **4.4:** an earlier question is sent in a new chat ("… (edited)"), the original unchanged |
| Copy, share, copy code, save to Knowledge or note, read aloud | ✅ |
| History: search (FTS), rename, pin, folders, archive and restore, date filter, paging, export .md/.txt | ✅ |
| Error messages (model missing, out of memory, provider errors with names, network, empty answer) | ✅ (gaps found on the phone get fixed here) |
| Ollama unavailable | **N/A on Android** (the phone runs llama.cpp; Ollama is desktop/server only) |

**Gap work:** edit an earlier message (as a branch). **Complexity:** medium. **Risk:** message ordering → covered by tests.

## Phase 3: Local AI and model management (4.5)
| Item | Status |
|---|---|
| Model catalog, installed models, sizes, selected model, status | ✅ (Models screen) |
| Downloads: progress, confirmation, resume, SHA-256 verification, no duplicates | ✅ (foreground download service) |
| Device-adaptive settings (context size, threads, memory profile, HeavyGate) | ✅ (automatic) |
| User-adjustable temperature | ✅ **4.4:** Creativity: Precise 0.2 / Balanced 0.3 (the previous fixed value, default) / Creative 0.7, phone model only |
| Offline local chat, no mandatory server or cloud | ✅ |
| Ollama status and model list | N/A on Android |

**Complexity:** low–medium.

## Phase 4: Knowledge and documents (4.6)
| Item | Status |
|---|---|
| Library, search, import (PDF, DOCX, TXT, MD, images with OCR), duplicate detection, metadata | ✅ |
| Indexing (chunks, FTS, BM25), re-index, Q&A with [S#] citations and page numbers | ✅ |
| Semantic search | 🟡 infrastructure only **(approval: embedding model)** |
| Sort options in the library | ✅ **4.4:** newest, name, size, type (remembered) |
| PDF viewer with page images and zoom | ✅ **4.4:** "View pages" (PdfRenderer, 1×, 1.5×, 2×; opens a PDF you pick, since the library stores text only) |
| Indexing status per document | ✅ **4.4:** "Not indexed yet" label plus Re-index |

## Phase 5: Google Drive (4.7)
| Item | Status |
|---|---|
| Connect, account, switch, reconnect, disconnect; per-account folders | ✅ (4.2) 📱 real-account check pending |
| Background upload queue, progress, retry, cancel, conflicts preserved, last check | ✅ |
| Scheduled backups and retention | ✅ (off by default) |
| Two-way sync | ❌ **by design** (not planned without a decision) |

**Work:** a phone verification pass only. Fix what it finds.

## Phase 6: Personalization and productivity (4.8)
| Item | Status |
|---|---|
| Theme (dark, light, system), personalities, custom instructions, default AI mode | ✅ |
| Chat folders, search, sort, rename, export .md/.txt | ✅ |
| Prompt library | ✅ **4.4:** 📋 Prompts in the chat bar (DB v17, included in backups) |
| In-app text size | ✅ **4.4:** Small, Default, Large, Largest (on top of the system font size) |
| PDF export of conversations | ✅ **4.4:** History → Save .pdf |

## Phase 7: Reliability, performance, security (4.9, ongoing)
| Item | Status |
|---|---|
| Additive DB migrations v2→v16 with JVM tests; never reset | ✅ |
| Keystore-sealed secrets, HTTPS-only release, secret scans, no logs of tokens | ✅ |
| Lint warnings (66 UseKtx style, 6 newer versions) | 🟡. A clean-up pass and dependency updates **(approval)** in a dedicated step |
| Instrumented tests on a device | ❌ 📱 |
| **Source in version control** | ❌ **(owner action):** `android/` and `cloud/` are untracked; `.gitignore` is prepared (4.4) so a commit won't include build output |

## Progress
- **Done in 4.4.0:** documentation foundation; the approved cleanup; every gap marked ✅ 4.4 above.
- **Next, with the phone:**
  - install 4.4.0;
  - Phase 1 visual check (both themes, the largest text size);
  - Phase 5 real-account Drive check;
  - on-device tests (`RUN-TESTS.cmd`).
- **Waiting for your decision:**
  - commit `android/` and `cloud/`;
  - semantic-search model;
  - production signing and release;
  - fine-tuning pilot.
