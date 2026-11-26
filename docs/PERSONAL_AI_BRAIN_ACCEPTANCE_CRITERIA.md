# Personal AI Brain: acceptance criteria (all releases)

**Each criterion is pass/fail.** It lists its test method and links to the requirement ids in the release specs.

**Evidence wording:**
- "Automated" means the suites, harnesses, E2E or Sandbox that I run.
- "Physical" means you on your phone or laptop. I never mark a physical item as passed myself.

## Global (every release)

| # | Criterion | Method |
|---|---|---|
| G1 | Existing Assistant, Study, News, Knowledge, Projects and Tasks work as before (no regression) | Full suites + E2E J1 |
| G2 | All three platforms work independently and offline; none needs another platform | E2E with network blocked; standalone Web smoke |
| G3 | No user data lost; migrations backed up, additive, rollback tested | Migration tests + Sandbox upgrade |
| G4 | No unauthorized data in any prompt (local or online) | Prompt-capture tests (fake model and provider) |
| G5 | No new external telemetry; network calls only for approved features | Static checks + network-blocked runs |
| G6 | No new Android permission without approval; no signing changes | Manifest diff; apksigner |
| G7 | Earlier releases untouched; new release in a new folder with verified checksums | Checksum verification |
| G8 | Honest reporting: every result labelled by method | Report review |

## 7.3 Personal AI Brain

| # | Criterion | Req | Method |
|---|---|---|---|
| A1 | Cloud prompts never contain project, task or system evidence, memories, documents or the user name without an online grant | BR-001 | I (fake provider) + J4 |
| A2 | Project `use_memory=false` blocks memory creation and retrieval | BR-002 | U |
| A3 | Study personal context is off when the global memory switch is off | BR-003 | U (W, A) |
| A4 | General chat never cites project-only documents | BR-004 | I |
| A5 | Deleted uploads never appear in search or answers | BR-005, BR-034 | I + J2 |
| A6 | With consent disabled, no context is retrieved | BR-011 | SC (3 platforms) |
| A7 | Enabled sources are used only within their scope and destination | BR-011, BR-017, BR-018 | SC + I |
| A8 | Revoking consent blocks the next request without a restart | BR-012 | I + J3 |
| A9 | Consent survives restart and restore | BR-019 | I |
| A10 | The upgrade keeps today's effective context | BR-013 | Upgrade test |
| A11 | Inferred memories are never used before approval | BR-021 | U |
| A12 | The weekly review supports approve, edit, reject, delete, temporary, recategorise and conflict resolution, with reasons and sources | BR-022 | J5 (3 platforms) + D |
| A13 | Deleting a memory removes it from retrieval and indexes; the source remains, with clear copy | BR-024 | I |
| A14 | Sensitive items are never sent online and need confirmation | BR-025 | SC |
| A15 | Search covers chats, documents (W, A), lessons, notes and stories; filters; snippets with sources | BR-030, BR-032 | J2 |
| A16 | Permission filtering happens before results leave the search service | BR-033 | SC |
| A17 | Index recovery rebuilds without blocking chat | BR-036 | I |
| A18 | Search works with the network blocked | BR-038 | I |
| A19 | Injection fixtures cause no leak, no memory and no tool call | BR-041 | Security pack |

## 7.4 Productivity

| # | Criterion | Req | Method |
|---|---|---|---|
| B1 | App Control L1 design document approved; no execution path (static test) | PR-001, PR-002 | Review + U |
| B2 | ICS parsing (recurrence, EXDATE, TZ, DST, invalid lines) correct on the fixtures, offline | PR-010–013 | SC |
| B3 | No calendar account code or network use | PR-017 | Static + network-blocked run |
| B4 | The plan uses free slots and never changes progress | PR-015, PR-016 | SC + J7 |
| B5 | Focus: only completed intervals count; no double counting; clock changes ignored; a restart resumes as paused | PR-022–025 | SC + J8 |

## 7.5 Quality and trust

| # | Criterion | Req | Method |
|---|---|---|---|
| C1 | No production key or password in the repository, logs or packages; the release build fails closed without environment variables | QT-001, QT-004 | Static + build test |
| C2 | "Signed" appears only after apksigner or signtool verification of that artifact | QT-005, QT-012 | Release script |
| C3 | Crash reports are off by default; no content; no network code | QT-020–025 | U + SC |
| C4 | Accessibility register complete; P0/P1 fixed; manual results recorded with device and date | QT-030–033 | axe + manual (physical) |
| C5 | Benchmarks reproducible; results per model × device; no automatic model replacement | QT-040–045 | Repeat run |

## 7.6 Sync, speech, plugins

| # | Criterion | Req | Method |
|---|---|---|---|
| D1 | Drive work started only after the drive.file check passed (your record) | gate | Your record |
| D2 | Bundles encrypted locally; no plaintext markers in uploaded bytes | SP-002 | Byte scan test |
| D3 | Restore preview; local data kept until validation; tampered or rolled-back bundle refused | SP-004, SP-005 | I |
| D4 | Every platform fully usable without Drive | SP-006 | E2E offline |
| D5 | Whisper behaviour unchanged; Piper works with the network blocked | SP-025 | Voice suites + network-blocked run |
| D6 | Plugins cannot exceed permissions; failures can't corrupt core data | SP-042, SP-046 | Security pack + J10 |
