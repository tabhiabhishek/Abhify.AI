# Abhify.AI complete project audit (2026-11-18)

This report is built on what is actually in the source, the configuration and the artifacts, not on earlier reports. The baseline details (protected APKs, test baseline, what exists per area) are in [ABHIFY_NEXT_BUILD_BASELINE_AUDIT.md](ABHIFY_NEXT_BUILD_BASELINE_AUDIT.md). The five-destination screen inventory is in [ABHIFY_5_2_PRODUCT_AUDIT.md](ABHIFY_5_2_PRODUCT_AUDIT.md).

## 1. Current architecture

See [ABHIFY_ARCHITECTURE.md](ABHIFY_ARCHITECTURE.md).

| Component | Size and makeup |
|---|---|
| Android | Kotlin + Compose, about 150 source files. `AppViewModel` (1,401 lines) plus about 25 state holders. SQLite v17 plus JSON side files. llama.cpp JNI. |
| Desktop | FastAPI, 111 routes, about 26 modules. Vanilla JS PWA (`app.js`, 2,931 lines). SQLite v5. Ollama, Chroma, Whisper. |

## 2. Feature inventory

| Area | Status (Android / Desktop) |
|---|---|
| Five destinations | complete / complete (this build) |
| Chat with routing (phone, Gemini, Groq, OpenRouter, server) | complete / Ollama only (by design) |
| Personal memory with approval, conflicts, relative cut-off | complete / basic |
| Local RAG (hybrid with optional bge-small) + Knowledge Center + corrections | complete in code, **phone-unvalidated** / Chroma RAG |
| Personal AI Brain: context resolver, planner, briefing, skill graph (this build) | complete in code / briefing + search only |
| Agent 2.0: tool registry, project review, approval, verification, undo, audit (this build) | complete in code (one workflow) / Task Runner templates only |
| Voice companion (5.4) | complete in code, **phone-unvalidated** / existing STT + TTS only |
| Universal search | complete (+ projects, AI tasks, recent) / complete (this build) |
| Study Discover (MIT OCW, OpenStax live; provider links) | complete / complete (this build) |
| News English/Hindi/Both, translation, related stories, compare, learn | complete / language selector (this build); no translate/compare on web yet |
| Backup, privacy, conversation data | complete / data-folder backup |

## 3. Findings (P0–P3)

| # | Priority | Finding | Evidence | Action |
|---|---|---|---|---|
| F1 | **P0** (data integrity) | Months of work are **uncommitted**: 149+ changed or untracked paths since `a9325d2`, including all of `android/` and `cloud/`. A disk failure or a mistaken checkout would lose them. | `git status` | **Needs your approval:** commit on the feature branch, or make a verified copy. Not done (RED rule). |
| F2 | **P1** | Every APK is signed with the **debug key** on this PC (`C:\Users\Administrator\.android\debug.keystore`, `1d2d1fdb…`). If that file is lost, no future build can update the installed app: you'd have to uninstall, which deletes app data. | apksigner output | **Needs your approval:** back up the keystore (it is a secret) and decide on a release key. Not done. |
| F3 | P1 | Physical-device validation is pending for Local RAG, 5.3, 5.4 and this build. | no device connected; manual checklist exists | the owner's manual test (checklist tests 1–52) |
| F4 | P1 (fixed) | Wikibooks' API now rejects automated requests without contact details. Discover reported "couldn't be reached" for it. | live 403 "robot policy" on 2026-11-18 | fixed: Wikibooks is a provider search link on both platforms |
| F5 | P1 | No instrumented Android tests run (UI, database migrations on a device). | `androidTest` exists, never executed here | needs a device or emulator (WHPX disabled on this PC) |
| F6 | P2 | `AppViewModel.kt` is 1,401 lines; initialisation order has already caused a near-crash (5.4, fixed). | source | keep extracting state holders (this build added `BrainState` and `AgentState`); add an init-order test (done in 5.4) |
| F7 | P2 | `frontend/app.js` is 2,931 lines in one file. | source | split into modules when the web is next restructured (no bundler today, so plan it) |
| F8 | P2 | LangGraph deprecation: `create_react_agent` moved to `langchain.agents`. | pytest warning | migrate in the next desktop step, with an agent regression test |
| F9 | P2 | The desktop lacks the voice companion, Agent 2.0, daily planner and skill graph. | source | desktop roadmap (`ABHIFY_DEVELOPMENT_ROADMAP.md`) |
| F10 | P2 | Lint warnings are growing (111 → 124; 0 errors). | lint report | triage in the next quality pass |
| F11 | P2 | The Android database relies on the phone's storage encryption, with no app-level encryption. | Privacy screen states it | evaluate SQLCipher, which would be a new dependency of about 4 MB (approval) |
| F12 | P3 | The web cache name is tied to the desktop version, and new web files rely on `cache:'reload'` (added 5.2). | `sw.js` | fine; bump the cache name with the next desktop version |
| F13 | P3 | Mixed CRLF/LF line endings in the working tree (normalised on commit by `.gitattributes`). | `tr` count | no action needed; patch tooling preserves endings |
| F14 | P3 | Hindi AI translation quality and TTS voice gender are unmeasured or unavailable from the API. | – | owner judgement on the phone; documented |
| F15 | P3 | Google News RSS terms allow personal, non-commercial use only. | research | fine for local use; revisit before any public distribution |

**Security review:**
- **Android:**
  - keys in the Keystore;
  - no secrets in logs (voice and RAG logs hold timings only);
  - app actions only from the user's words; agent tools validated in code; sensitive actions never automatic;
  - documents and notes fenced as untrusted input.
- **Desktop:**
  - binds `127.0.0.1` by default; phone access is opt-in;
  - personal routes are `local_only` (new `/discover`, `/briefing` and `/search` included);
  - new route prefixes are in the service worker's never-cache list;
  - SQL is parameterised, and the LIKE pattern is escaped.
- **No new permissions, dependencies or network services** in this build. Discover sends only the topic to public APIs.

## 4. Dependency review

No dependency was added in the 5.3, 5.4 or next builds; everything uses libraries already in the project. Candidates and decisions are in [ABHIFY_RECOMMENDATIONS.md](ABHIFY_RECOMMENDATIONS.md): SQLCipher, ML Kit translation, the YouTube Data API, a wake-word engine and a release signing key.

## 5. Recommended priorities

1. **F1:** commit or back up the work. Approval needed.
2. **F2:** back up the debug keystore and decide on release signing. Approval needed.
3. **F3:** run the phone checklist.
4. **Desktop parity:** voice and agent.
5. **Quality:** split `app.js`, the LangGraph migration, lint triage.
