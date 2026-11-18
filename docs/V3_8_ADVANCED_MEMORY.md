# v3.8: advanced personal memory

**Status:** implemented and tested on the JVM. Phone validation is pending.

**Database:** v12 → v13.

The 3.4 memory system is extended, not replaced. These stay unchanged:
- categories, importance, expiry, search, edit and delete;
- duplicate merge, sensitive-data checks, approval of suggestions;
- relevant-only retrieval with at most 6 memories and 900 characters;
- export and Drive backup.

## What's new
| Feature | How it works | Files |
|---|---|---|
| **Possible contradictions** | Memories are mapped to simple "slots": `preferred/favourite/main X: Y`, `lives in`, `works at`, `works as`, `name is`, `uses X for Y`, `prefers A (over B)`. Same slot with a different value means a possible conflict (for example "Preferred framework: Flask" and "Preferred framework: FastAPI"). Bare preferences conflict only when they are opposite (A over B vs B over A) or are alternatives in the skill taxonomy (Flask/FastAPI). "Prefers dark mode" never conflicts with "prefers PyTorch". | `memory/MemoryReview.kt` |
| **Conflict at save time** | Adding a memory that may contradict one shows the existing memory (with its source) and the new one. You choose **Keep existing / Replace / Keep both / Merge…**. Nothing is overwritten before you choose. AI suggestions that may conflict are flagged and not ticked. | `ui/V34State.kt`, `ui/V34Screens.kt` |
| **Review queue** | **Needs review (N)** lists possible contradictions, duplicates, "could be combined" (same category, 40–70% shared words), expired, expiring within 14 days, and low-importance memories not used for 180+ days. Each item has explicit actions (keep one / keep both / merge / keep / delete). "Keep both" is remembered (`memory_dismissed`), so the same pair isn't suggested again. | `MemoryReview.review`, `ui/V38Screens.kt` |
| **Provenance** | Each memory shows its recorded origin: added by you; "Remember that…" in the chat "X" (new: the chat id is now recorded); suggested from the chat "X" and approved by you; from a document; restored from a backup. It also shows how often and when it was last used. **Open chat** jumps to the chat when it still exists. Memories from before v3.4 show "Source not recorded", never a guess. | `MemoryReview.provenance`, `MemoryProvenance` |
| **Context-aware retrieval** | Shared words with the question (×2) and category hints still decide relevance. New: words from the last turns of this chat count at ×0.5, but only when at least 2 match, so one stray word never pulls a memory in. Small bonuses: recency (updated or used in the last 30 days, +0.3) and use count (up to +0.25). The bounds are unchanged (6 memories, 900 characters). An unrelated question still gets **no** memories. | `MemoryKit.relevant` |
| **Usage tracking** | `last_used_at` and `use_count` are updated when memories go into a prompt. This is not done in private chats, which store nothing. It is local bookkeeping and doesn't mark the memory for sync. | `chat/V38Db.kt`, `AppViewModel.relevantMemories` |
| **Dashboard** | Count, expiring soon, expired, duplicates, possible conflicts, never used, per-category counts, recently updated, size, last backup. | `MemoryOverview` |

## Privacy (unchanged)
- Memories stay on the phone. Online providers get relevant memories only when **⚙ Online context → Relevant personal memories** is on.
- Nothing is extracted from chats automatically: suggestions need approval, and sensitive data is flagged and needs confirmation.
- "Remember that…" with sensitive-looking content is still refused.

## Limits
- **Contradiction detection is pattern-based (English).** It misses conflicts phrased differently ("I moved to Bengaluru") and can produce false positives. That's why it is only a suggestion.
- **Nothing is ever applied automatically.**

## Tests (`V38Test.kt`, 5 tests)
- Slots and contradictions, including the spec's Flask/FastAPI example and non-conflicts.
- The review queue finds each kind; "keep both" is remembered; dashboard counts.
- Provenance never invents a source.
- Context, recency and bounds in retrieval; unrelated questions get nothing; a single context word isn't enough.
- The v13 migration on a populated database; private chats don't record use; "remember that" records its chat.

The 3.4 memory tests (`PersonalMemoryTest`) pass unchanged.
