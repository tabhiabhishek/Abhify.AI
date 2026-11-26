# Abhify.AI 7.4: productivity intelligence specification

**Status:** proposal.
**Depends on:** 7.3 M1 (consent), because calendar and focus data are personal context.

## 1. AI App Control, Level 1: documentation only

### Owner rule

At Level 1 the AI may understand a request, find the matching capability, prepare a proposal, explain the result and risks, show exact parameters and ask for confirmation. **It never executes**, and there is **no execution path** in code.

### Reconciliation

`docs/ABHIFY_AI_APP_CONTROL_PROPOSAL.md` (2026-11-23) uses "level 1" to mean low-risk actions that run directly.

This spec renumbers the levels:

| Level | Meaning |
|---|---|
| L1 | Proposal only |
| L2 | Reversible actions with approval (future, needs approval) |
| L3 | Consequential actions with explicit per-action approval (future) |

The older document will be annotated, not rewritten, after approval (decision D1).

### Deliverable

A new document, `docs/ABHIFY_AI_APP_CONTROL_L1_DESIGN.md`, written in 7.4.

**It defines:**

| Area | Content |
|---|---|
| Capability catalogue | Id, description, platforms, parameter schema, risk class (read / reversible / consequential / forbidden), preconditions, expected result. Seeded from Android AppActions (20), Windows `model_switch`, agent tool typing |
| Proposal object | `{capability, params, explanation, risks, reversible, requires: [permissions]}` |
| Permission scopes | Reuse the consent scopes of 7.3, plus a new `app_control.<capability>` scope (never granted at L1) |
| Approval UI | Mock-ups and copy only |
| Audit | Proposal events in the audit log (`kind=proposal`) |
| Revocation and failure handling | For L2+ |
| Threat model section | Injection-driven proposals, parameter tampering, confused deputy |

**Code allowed in 7.4:** a proposal *renderer* only, if you want the proposal shown in chat. It must not be wired to any handler, and a test must assert that no handler is reachable.

| ID | Requirement | Platforms | Test | Acceptance |
|---|---|---|---|---|
| PR-001 | L1 design document complete, with the sections above | — | review | Owner approves the document |
| PR-002 | No execution pathway: a static test asserts the proposal renderer has no reference to action handlers or OS/file/browser APIs | W, A, B | U | Test green |
| PR-003 | Proposals are audited (kind=proposal) with parameters but no personal content | W, A, B | U | Audit entry present |

## 2. Calendar-aware Study plan (local `.ics` import)

| ID | Requirement | Platforms | Deps | Pri | Test | Acceptance |
|---|---|---|---|---|---|---|
| PR-010 | The user picks an `.ics` file through the platform file picker; it is parsed locally (RFC 5545: VEVENT, DTSTART/DTEND/DURATION, TZID, VTIMEZONE, all-day events) | W, A, B | — | P0 | SC, U | `ics_cases.json` parse fixtures pass on 3 platforms |
| PR-011 | Recurring events (RRULE: DAILY/WEEKLY/MONTHLY with COUNT, UNTIL, BYDAY, INTERVAL; EXDATE) are expanded for the plan window only (≤ 60 days) | W, A, B | PR-010 | P0 | SC | Recurrence fixtures pass |
| PR-012 | Time zones and DST: times converted to the device's zone; DST transitions handled (fixtures cover the spring-forward and fall-back days) | W, A, B | PR-010 | P0 | SC | DST fixtures pass |
| PR-013 | Invalid entries are reported (line, reason) and skipped; import never fails as a whole on one bad event | W, A, B | PR-010 | P0 | U | Bad file → summary with skipped count |
| PR-014 | Imported events shown (list or week view) with source file name and import time; replace and remove actions | W, A, B | PR-010 | P0 | E2E | Remove → plan recalculated |
| PR-015 | Study window finder: free slots between events within user-set study hours, minimum slot length, and the daily minutes target | W, A, B | PR-011 | P0 | SC | `slots_cases.json` pass |
| PR-016 | The plan (course schedule and interview plan) places items into free slots; progress is never changed by a calendar change | W, A, B | PR-015 | P0 | I | Change calendar → slots move, lessons_done unchanged |
| PR-017 | No calendar accounts, no background access, no upload, no editing of calendar events | W, A, B | — | P0 | static + I | No network calls during import; no OAuth code |
| PR-018 | The calendar is personal context, so using it in the Assistant needs a grant (`calendar.events`) | W, A, B | 7.3 M1 | P1 | SC | Denied by default |

**Storage:**
- Parsed events go into Study's store as `calendar: {file, imported_at, events[] (expanded window), tz}`.
- The raw file is not kept unless you choose to.

## 3. Focus Mode (Pomodoro linked to Study)

| ID | Requirement | Platforms | Pri | Test | Acceptance |
|---|---|---|---|---|---|
| PR-020 | Configurable focus and break durations (defaults 25/5, long break 15 every 4) | W, A, B | P0 | U | Settings persist |
| PR-021 | Start, pause, resume and stop; session states `running`, `paused`, `break`, `completed`, `abandoned` | W, A, B | P0 | SC | `focus_cases.json` state-machine fixtures pass |
| PR-022 | A session links to a course or lesson; only **completed** focus intervals add Study time | W, A, B | P0 | SC, I | Abandoned or interrupted → 0 seconds added |
| PR-023 | No double counting: while a focus session is running, the existing visible-time Study counter is suspended for that course, and each session adds its seconds once (idempotent session id) | W, A, B | P0 | SC, I | Running focus + open lesson for 25 minutes → 25 minutes added, not 50 |
| PR-024 | Elapsed time from a monotonic clock (`performance.now`, `SystemClock.elapsedRealtime`, `time.monotonic`); a wall-clock jump never adds or removes time | W, A, B | P0 | U | Fixture with a clock change |
| PR-025 | Suspension and restart: state persisted on every transition; on restart a running session becomes `paused` (never silently completed) | W, A, B | P0 | I | Kill app mid-session → resume offers continue or abandon |
| PR-026 | Optional end-of-interval notification (Android uses the already-declared POST_NOTIFICATIONS, asked only when turned on; desktop uses an in-app sound and toast) | W, A, B | P1 | D | Off by default |
| PR-027 | Session history (date, course, planned vs actual, outcome), offline | W, A, B | P0 | E2E | History matches sessions |

## 7.4 acceptance (release gate)

- App Control is documentation only, and a test proves no execution path exists (PR-002).
- ICS parsing is offline and correct on the fixtures, including recurrence and DST (PR-010 to PR-013).
- No calendar cloud code exists (PR-017).
- The plan respects availability without changing progress (PR-016).
- Focus sessions persist and never double-count (PR-022 to PR-025).
- All existing Study functions and suites still pass.

## Complexity

| Item | Effort |
|---|---|
| L1 design document | Small |
| ICS parser with recurrence and DST, × 3 platforms | **Large**: the hardest part. A shared fixture set is mandatory |
| Slot finder | Medium |
| Focus Mode | Medium |
