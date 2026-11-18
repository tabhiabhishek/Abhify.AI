# Personal memory (Abhify.AI Android 3.4)

The memory screen says:

> "Personal memory stores selected information that helps Abhify.AI personalize responses. It is separate from your complete chat history and learning documents."

## What a memory is
A memory is one short fact about you, for example "Prefers PyTorch examples" or "Preparing for ML engineer interviews". Each memory has:
- **Category.** One of: Preferences, Technical skills, Learning goals, Career goals, Active projects, Important decisions, Frequent context, Personal facts, Learning progress, General.
- **Importance.** Low, normal or high. It affects ranking.
- **Expiry (optional).** After this date the memory is no longer used.
- **Source.** Where it came from: manual, chat ("remember that …"), suggestion, or restore.

Memories are stored in the app's local database (`memories` table), together with chats, documents and other app data. They leave the phone only in three cases:
1. You export them.
2. You back them up (to a local file or to Drive).
3. You turn on **⚙ Online context → Relevant personal memories** and send a question to an online provider. Even then, only the memories relevant to that question are sent.

## How memories are used
- **Only relevant memories.** For each question, Abhify scores your confirmed, unexpired memories by shared words, category hints (for example, an "interview" question gives Career goals a higher score) and importance.
- **A small number.** At most 6 memories and 900 characters go into the prompt. If nothing is relevant, none are used. Abhify never adds "all memories".
- **Visible per answer.** The answer shows "🧠 N memories used", and tapping it lists them.
- **Master switch.** The **Memory on/off** switch on the memory screen and in the chat bar turns all of this off.
- **Private chat.** Private chats can use memories but never create them.

## Adding memories
| Way | Behaviour |
|---|---|
| **＋ Add** | Type the memory and choose its category, importance and expiry. |
| **"Remember that …"** in chat | Saved directly, except when it looks sensitive: then Abhify refuses and suggests adding it manually. |
| **Suggest from current chat** | The local model proposes candidates. You tick the ones to keep. Nothing is saved until you approve. Candidates that duplicate an existing memory or contain sensitive data are labelled. |

## Sensitive data
Sensitive means text that looks like an email address, phone number, card number (checked with the Luhn test), 12-digit ID, PAN, a password or PIN, or health or financial details.
- **Manual add or approval:** an extra confirmation dialog appears.
- **"Remember that …" in chat:** refused.

## Duplicates
- **Detection.** Two memories are treated as duplicates when one contains the other, or when they share at least 70% of their words.
- **When adding.** Adding a duplicate shows the existing memory and asks before saving.
- **Merging.** **Merge duplicates (N)** keeps the newest memory of each group and deletes the others, after confirmation.

## Export and backup
- **Export:** JSON (`abhify-memory.json`) or Markdown (`abhify-memory.md`), saved where you choose.
- **Back up to Drive:** uploads to `Abhify.AI/Memory Backups`, as a backup file containing only the "Personal memory" category.
- **Last backup:** the date of the last backup is shown at the top of the screen.

## Not included
- No automatic background extraction.
- No embeddings model: relevance uses keyword matching, which runs well on a phone.
- No sync of memories across devices beyond the existing opt-in sync.
