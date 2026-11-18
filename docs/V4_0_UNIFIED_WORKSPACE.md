# v4.0: unified personal AI workspace

**Status:** implemented and tested on the JVM. Phone validation is pending.

**Database:** v14 → v15.

This connects the existing Chat, Career, Study, Documents, Knowledge, Memory and Settings features. There was no rewrite and no new feature module that duplicates an existing one.

## Unified search (🔍 in the header)
- **Searches:** conversations (titles and messages), memories, documents (name and full text), workspace notes and tasks, the knowledge vault, study sessions, flashcards, study plans (topic, goal, lessons), and career data (job descriptions, applications, interview practice).
  - The implementation is `workspace/UnifiedSearch.kt` (one plain SQL query per source) and `chat/V40Db.kt`.
- **Local only.** It runs on the phone's database. Nothing is sent online; a test checks that the search code has no network or AI calls.
- **Results** show their source type, title, a short snippet (180 characters around the match, cut in SQL so whole documents are never loaded), and age.
- **Ranking:** title matches first, then newest; at most 8 per type, so one busy type can't hide the others. Type filters are available.
- **Literal matching:** the user's text is never a wildcard (`%`, `_` and `\` are escaped).
- **Related to the current chat:** a one-tap search with the chat's title.

## Project workspaces
The existing workspaces (notes, tasks, documents imported into them) gain **links** in `workspace_links`.
- **Links are references, not copies.** A workspace can reference conversations, library documents and memories. Nothing is copied or moved, and removing a link never deletes the item.
- **Workspaces → Linked** lists them. Deleted items show as "(deleted since it was linked)".
- **Deleting a workspace** removes its notes, tasks and the documents imported *into* it, plus its links. Linked chats, documents and memories stay.
- **Fix:** before this, deleting a workspace left those documents' retrieval chunks behind. Now the chunks and vectors are deleted too.

## Bounded, explicit context in chat
The chat context bar has **🗂 Workspace**. Choosing one adds:
- the workspace's documents (imported and linked) to retrieval, with the usual [S#] citations;
- the **most relevant notes** (up to 3, by shared words with the question) and **open tasks** (up to 10). The budget is 1,200 characters for the phone's model and 4,000 for an online model. The RAG budget on the phone shrinks by the same amount.

Under each answer, "Sources sent" lists the passages **and** the workspace parts (📝).

Rules:
- Nothing is included unless a workspace is chosen.
- Career data and memories are **not** added automatically by a workspace. Memories keep their own relevance filter and switch.
- **Online:** workspace context goes to an online model only after the consent dialog ("Send workspace context online?"). One approval covers that combination of documents and workspace; changing either asks again.
- "Ask <provider> instead…" after a local failure lists the workspace context only if it was approved.

## Cross-feature actions (each is a user tap)
| Action | Where | Notes |
|---|---|---|
| Save a chat answer as a note | 📒 Note on an answer | Into the chat's workspace, or the first workspace |
| Add a document to a study plan | Documents → **Study plan**; Search; Linked | Opens Study → Plans with the document pre-selected |
| Use a résumé in interview practice | Career → Interview (Projects topic) | Existed since 3.3; unchanged |
| Search related conversations | Search → "Related to the current chat" | Local |
| Save a document passage as a memory suggestion | Search → a document → **Memory suggestion…** | You edit the text; it then goes through the normal sensitive, duplicate and contradiction checks. Source recorded as `doc:<id>` |
| Create a workspace from an existing project | Search → a chat, or an "Active projects" memory → **New workspace from this** | Creates the workspace and links the item |
| Add an item to a workspace | Search → **Add to workspace…** | A link only |

## AI routing (unchanged)
- The phone's Qwen2.5 is the default.
- The optional online provider needs explicit selection and the existing consent.
- There is no automatic cloud fallback. No new model was added.

## Tests (`V40Test.kt`, 5 tests)
- **Every** search query runs against a database migrated to v15, with one matching row per source. Deleted chats are excluded and snippets are bounded.
- Wildcard escaping.
- Ranking and the per-type cap.
- Workspace context: relevance, open tasks only, budget, reporting, truncation.
- Link uniqueness.
- Source checks: workspace context goes online only after consent; workspace deletion cleans links and chunks; no network or AI calls in the search and workspace code.

## Limits
- **Search uses LIKE scans.** It is fine for personal-size data; very large histories may take longer. The time is shown.
- **Note relevance is keyword-based.**
- **Links aren't removed when the item is deleted.** They show as deleted until you remove them.
