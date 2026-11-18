# Abhify.AI 6.0 complete audit (2026-11-18)

## Baseline (verified, not assumed)
- **Branch:** `feature/m2-knowledge-packaging`, last commit `a9325d2`.
- **Working tree:** about 170 uncommitted paths (modified and untracked), all preserved. No git write commands were run.
- **APK hashes:** all re-verified after the 6.0 build and unchanged (5.0, 5.1, 5.3, 5.4, 5.4-dev.1, 5.5-dev.1, 5.6-dev.1/2/3). The 5.5-dev.1 APK is `46b41771…468e`, 74,392,641 bytes, `5.4-internal.5.5-dev.1` / 5040099.
- **Signing:** debug certificate `1d2d1fdb…6246f` (`CN=Android Debug`). The key was not touched or exposed.
- **Tests:**

  | | 5.5-dev.1 (as reported) | 6.0, before changes | 6.0 final |
  |---|---|---|---|
  | Android | 396 passed / 4 skipped | 420 / 0 failed / 4 skipped | **426 / 0 / 4** |
  | Backend | 409 + 2 skipped | 443 + 2 skipped | **443 + 2 skipped** |

  The "before changes" column already includes the 5.6 work.
- **Data and models:** no database or model directory was modified. ChatDb stays v17 and the desktop database stays v5. Ollama models were untouched.

## Capability audit
| Area | State | Notes |
|---|---|---|
| Personal AI Brain | **Implemented (6.0)** | One planner (`Brain.plan`) in front of chat and voice turns (see the architecture doc) |
| Context resolver | Working (5.5/5.6) | Permissions, relevance floor, freshness labels; used by chat, voice, briefing and planner |
| Conversation continuity | Working (5.6) | Follow-ups, an earlier-turn summary, a history window per mode (4/8/12) |
| Memory | Working, already "2.0"-level | Relevance ranking with cutoff, recency bonus, expiry, duplicate groups, approval, provenance, review, sensitive-content block. 6.0 added nothing; it was reviewed |
| Reasoning modes | **Implemented (6.0)** | 10 modes; Quick, Deep, Planning and Creative are new and change real behaviour |
| Research | **Partial (6.0)** | Recent news search as dated `[W#]` evidence. No general web search API (deliberately not added: it would need a key, cost, and your approval) |
| Multimodal | Partial (existing) | PDF, DOCX, text and code documents; image OCR (on device); audio transcription on desktop (Whisper). No image understanding model and no video (they would need large models) |
| Model router | Working + improved | Consent-aware routing; rate-limit cooldown; per-session reliability stats; route advice (shown, not applied) |
| Agent | Working (5.5/5.6) | Typed registry, approval, read-back, undo, audit. Desktop port done in 5.6 |
| Voice | Working + improved (5.6) | Streaming sentences, reliable interruption, audio focus, background stop, Hindi and Hinglish commands. No wake word (approval required) |
| News | Working + fixed (5.6) | Image root cause fixed on both platforms |
| UI | Implemented (5.6) | Neural Futuristic tokens on both platforms; web redesign checked in a real browser; Android checked in code only (no emulator) |
| Evaluation | Partial | Retrieval precision and recall, brain planning tests, provider stats. No automated answer-quality scoring of real model output (it needs the models running) |
| Security and privacy | Holds | No new permission or telemetry. Logs hold ids and counts only. Keys in SecretBox. Approval gates on every change |

## Weaknesses found (and what was done)
| Finding | Action |
|---|---|
| Focus modes changed only one instruction ("label-only") | 6.0 `Brain.Profile`: history, context, length, creativity, web, scaffold |
| Models answered questions about current events as if current | Fresh-information detection, an honest notice, a stale-information rule in the prompt, Research evidence |
| A stronger configured model was never suggested for hard problems | Route advice notice (never a silent switch) |
| After a 429, Auto kept sending requests to the same provider | Cooldown (5.6) |
| `AppViewModel.kt` (~1,450 lines), `main.py`, `app.js` are very large | Recorded (R3), not refactored in this milestone |
| The LangGraph `create_react_agent` deprecation warning | Recorded (R4) |
| Desktop has no interaction modes | Deferred (R12) |
