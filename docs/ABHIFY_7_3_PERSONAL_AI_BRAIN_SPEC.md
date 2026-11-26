# Abhify.AI 7.3: Personal AI Brain specification

**Status:** proposal for owner review. Nothing is implemented.

**Goal:** unified personal intelligence. This release delivers unified consent, a weekly memory review and local semantic search. It first fixes the privacy findings F1 to F5, F8 and the F6 default; F6 also needs your decision.

**Prerequisites:** owner approval of this spec, of decisions D2–D5 and D9, and of the migration plan.

## Milestones (each one releasable on its own)

| Milestone | Scope | Exit |
|---|---|---|
| **M0 Privacy fixes** | F1–F5, F8, and the F6 default (decision) | Regression tests for each finding; full suites green |
| **M1 Consent core** | ConsentPolicy and grants on all three platforms; Assistant, Study and Tasks routed through it | Shared consent cases pass on W/A/Web; no behaviour change for existing users (migration mirrors today) |
| **M2 Memory record and review** | Memory schema v8 (Windows) and V18 (Android), IndexedDB fields (Web); review queue and screen | Review E2E on 3 platforms; deletion propagation tests |
| **M3 Unified local search** | Index of chats, documents, lessons, notes and stories; filters; citations; deletion propagation | Search E2E; "delete then no hit" tests |
| **M4 Hardening** | Consent-bypass, prompt-injection and malicious-document tests; accessibility of new screens | Security test pack green |

## Requirements

**Column codes:**
- **Pri:** P0 = must have, P1 = should have.
- **Platforms:** W = Windows, A = Android, B = Web.
- **Test methods:** U = unit, SC = shared cases, I = integration, E2E = browser/real app, D = physical device.
- **Status:** "Planned" for all.

### M0: Privacy fixes

| ID | Requirement | Module | Platforms | Deps | Pri | Test | Acceptance |
|---|---|---|---|---|---|---|---|
| BR-001 | The cloud chat path excludes local evidence (projects, tasks, system), memories, documents and the user name unless an online grant exists | Assistant | W | — | P0 | U, I | A test chat with a project question on a cloud model has no project or task text in the provider request (captured by a fake provider) |
| BR-002 | In a project with `use_memory=false`, memory creation (`save_memory`) and retrieval are both unavailable | Assistant | W | — | P0 | U | The tool list for such a project has no memory tools; "remember…" there gets a clear refusal |
| BR-003 | The Study personal-context opt-in respects the global memory switch | Study | W, A | — | P0 | U | Memory off → `brain_context` returns nothing even with ticked items; the UI says why |
| BR-004 | General chat document retrieval is limited to documents not restricted to a project, unless the chat is in that project | Knowledge | W | — | P0 | U, I | A project-only document never appears in a general chat answer or its sources |
| BR-005 | Uploaded documents can be deleted; deletion removes chunks, vectors and project references | Knowledge | W | — | P0 | I, E2E | Delete → `knowledge.search` and `/search` return no hit; the file is moved to recoverable trash for 7 days, or deleted if the user asks |
| BR-006 | Search excludes expired memories | Search | A | — | P1 | U | Expired item not returned |
| BR-007 | Android `online_history` default for **new installs** becomes off (existing installs unchanged and shown in Privacy Center) | Settings | A | D5 | P1 | U | Fresh install: the online request carries no history |

### M1: Unified consent

| ID | Requirement | Module | Platforms | Deps | Pri | Test | Acceptance |
|---|---|---|---|---|---|---|---|
| BR-010 | One consent screen in Privacy Center lists modules (Assistant, Study, Tasks) × sources (goals, preferences, facts, study progress, projects, tasks, documents, older chats) × destination (on this device / online) with plain explanations | Settings | W, A, B | BR-011 | P0 | E2E, D | Every switch shows what it allows, whether it's stored, and how to revoke it |
| BR-011 | `ConsentPolicy.allows(module, scope, destination, item)` is evaluated in the service layer before any retrieval; deny by default | Brain | W, A, B | — | P0 | SC, U | Shared `consent_cases.json` passes on 3 platforms |
| BR-012 | Grants are read per request; revocation takes effect on the next request | Brain | W, A, B | BR-011 | P0 | I | Grant → answer uses item; revoke → next answer does not (no restart) |
| BR-013 | Migration mirrors existing behaviour (Chat uses memories if the memory switch is on; Study ticked items become course grants) | Brain | W, A, B | D4 | P0 | I | Upgrade test: same context used before and after upgrade |
| BR-014 | Grant changes are written to the audit log | Brain | W, A, B | — | P1 | U | Audit has a grant/revoke event with no content |
| BR-015 | Answers that used personal context show "Used: …" labels (Assistant and Tasks, like Study) | Assistant | W, A, B | BR-011 | P0 | E2E | Label lists exactly the items used |
| BR-016 | Assistant keeps working if consent storage or index is unavailable (treat as all denied, say so) | Assistant | W, A, B | — | P0 | I | Corrupt grants file → chat still answers; banner explains |
| BR-017 | Study context does not leak into Assistant unless `assistant` has `study.progress` | Brain | W, A, B | BR-011 | P0 | I | Weak topics never appear in an Assistant prompt by default |
| BR-018 | Tasks never receive documents unrelated to the task's project or explicit selection | Tasks | W, A | BR-011 | P0 | I | Task prompt capture contains only selected sources |
| BR-019 | Consent survives restart and backup/restore | Brain | W, A, B | — | P0 | I | Restart and restore tests |

### M2: Memory record and weekly review

| ID | Requirement | Module | Platforms | Deps | Pri | Test | Acceptance |
|---|---|---|---|---|---|---|---|
| BR-020 | Memory record contract `abhify-memory-1` (status, confidence, sensitivity, consent_scopes, last_verified_at, expires_at, history) | Memory | W, A, B | D2 | P0 | SC, I | Migration keeps every existing memory active and unchanged in content |
| BR-021 | Inferred memories are created as `proposed` and never used until approved | Memory | W, A, B | BR-020 | P0 | U | Proposed item is never in context |
| BR-022 | Weekly review screen: new proposals, conflicts, duplicates, expiring, stale, sensitive checks; each with reason and source | Memory | W, A, B | BR-020 | P0 | E2E, D | All actions work: approve, edit, reject, delete, temporary, recategorise, resolve conflict |
| BR-023 | A review card appears at most once a week, without notifications by default | Home | W, A, B | BR-022 | P1 | U | No more than one prompt in 7 days |
| BR-024 | Deleting a memory removes it from retrieval and indexes; the source stays (with clear copy) | Memory | W, A, B | BR-030 | P0 | I | Delete → no hit in memory context or search |
| BR-025 | Sensitive classifier (shared patterns); sensitive items need confirmation, are never sent online, and are excluded from suggestions | Memory | W, A, B | — | P0 | SC | `sensitivity_cases.json` passes on 3 platforms |
| BR-026 | Correction history kept per memory (user-visible) | Memory | W, A, B | BR-020 | P1 | U | Edit adds a history entry |
| BR-027 | Secrets (keys, passwords) are refused as memories | Memory | W, A, B | BR-025 | P0 | U | Refusal message; nothing stored |
| BR-028 | Web gets a memory viewer (today missing) | Memory | B | BR-020 | P0 | E2E | List, edit, delete on-device memories |

### M3: Unified local semantic search

| ID | Requirement | Module | Platforms | Deps | Pri | Test | Acceptance |
|---|---|---|---|---|---|---|---|
| BR-030 | Index sources: chats, documents (W, A), lessons, Study notes, STAR stories, approved memories; incremental on save | Search | W, A, B | — | P0 | I | New lesson appears in search within one save |
| BR-031 | Hybrid ranking: keyword plus semantic, fused with RRF where embeddings exist; keyword-only fallback | Search | W, A (B keyword) | D3 | P0 | SC, U | `search_cases.json` ranking fixtures pass |
| BR-032 | Filters: source type, module, date range, project; snippets with source references (document page where known) | Search | W, A, B | BR-030 | P0 | E2E | Filters change results; each hit opens its source |
| BR-033 | Permission filtering happens **before** results are returned or passed to a model | Search | W, A, B | BR-011 | P0 | SC, I | Denied sources never appear, even by id |
| BR-034 | Deletion propagation for every source type | Search | W, A, B | — | P0 | I | Delete → reindex check → no hit |
| BR-035 | Duplicate handling: identical chunks are collapsed with all sources listed | Search | W, A, B | — | P1 | U | One hit, multiple sources |
| BR-036 | Index recovery: detect a corrupt or missing index, rebuild from owners, show progress; never block chat | Search | W, A, B | — | P0 | I | Delete the index files → rebuild → same results |
| BR-037 | Storage usage visible (index size per source) | Settings | W, A, B | — | P1 | E2E | Numbers match the files on disk |
| BR-038 | Works offline; embeddings local only (Ollama `nomic-embed-text` on Windows, bge-small on Android if downloaded) | Search | W, A | D3 | P0 | I | Network blocked → search works |

### M4: Hardening

| ID | Requirement | Platforms | Test |
|---|---|---|---|
| BR-040 | Consent-bypass attempts (crafted ids, project switching, regenerate, remote companion device) fail | W, A, B | security tests |
| BR-041 | Prompt injection in documents, news or chats cannot change scopes or create memories | W, A, B | malicious fixtures |
| BR-042 | New screens meet the accessibility checklist (labels, focus, keyboard, contrast) | W, A, B | a11y checks |
| BR-043 | No regression: the complete backend, JS and Android suites plus the 7.2 E2E all pass | W, A, B | full suites |

## 7.3 acceptance (release gate)

- Existing Assistant, Study and Tasks work exactly as before for an upgraded user (BR-013, BR-043).
- Consent is enforced in services, and revocation is immediate (BR-011, BR-012).
- Memory review is fully functional on three platforms (BR-022).
- Search returns grounded results with sources and respects deletion (BR-032, BR-034).
- No unauthorized data enters prompts, including cloud prompts (BR-001, BR-033).
- Offline works (BR-038).
- User data is intact after migration, with a rollback path tested (migration plan).

## Complexity estimate

| Milestone | Effort | Main risk |
|---|---|---|
| M0 | Small (1–2 days) | Changing the cloud prompt path without breaking provider tests |
| M1 | Medium (4–6 days) | Mirroring existing behaviour exactly |
| M2 | Medium–large (5–8 days) | Android V18 and Windows v8 migrations |
| M3 | Large (8–12 days) | Index size and recovery on low-end phones |
| M4 | Small–medium (3 days) | — |
