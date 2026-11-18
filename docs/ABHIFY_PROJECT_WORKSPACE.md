# Project Workspace (2026-11-09)

A project groups your work: its own instructions, documents, chats, AI tasks and study material.
- **References, not copies:** documents are never copied, moved or deleted by a project.
- **No database change on either platform.**

## Where it lives
| | Desktop/web | Android |
|---|---|---|
| Data | `<data>/data/projects.json` (`backend/projects.py`), like `tasks.json` | the existing **workspaces** (`workspaces`, `notes`, `tasks`, `workspace_links`, `library_docs.workspace_id`) plus `filesDir/projects.json` for instructions, archived and the memory switch (`workspace/Projects.kt`, `ui/ProjectState.kt`) |
| Open it | sidebar **Projects**, Home chip, brand launcher, command palette | More → **Projects**, brand launcher |
| Page | list (with archived) and project page | list (with archived) and project page (`ui/ProjectScreens.kt`) |

## Project management
- **Create, open, rename, archive and restore.** Archived projects are hidden from pickers and not used as chat context.
- **Export:** Markdown with instructions, documents, chats or notes, to-dos and AI tasks. Titles only; content stays in the app.
- **Settings:**
  - instructions (up to 1,500 characters);
  - "Use my personal memory in this project": on by default, the existing chat behaviour, and still only when memory is on.
- **Delete** needs explicit confirmation:
  - **Desktop:** removes only the project record. Documents, knowledge, chats and task history stay.
  - **Android:** removes the project with its own notes and to-dos. Documents imported into it move back to the library, never deleted. Linked documents, chats and memories stay. The dialog says exactly this.

## Documents and knowledge
- **Add existing documents:**
  - Desktop: by name, from the knowledge index; unknown names are refused.
  - Android: from the library.
- **Removing** removes the association only.
- **Overview:** shows each document's state, including documents no longer in the index.

## Project chat
- Opens the existing chat with a visible project chip (web) or the workspace chip (Android).
- **What it uses:**
  - the project's instructions, marked as user preferences that never override the app's rules;
  - excerpts retrieved **only from the project's documents** (a Chroma metadata filter on desktop; the workspace's documents on Android), fenced as reference data whose instructions are ignored;
  - memory only if the project allows it.
- **Desktop isolation:** the general "search all my documents" tool is not offered in a project chat, so other documents can't leak in.
- **History:** chats are recorded in the project (desktop) and listed on its page, with Continue.
- **Model and microphone:** model choice, streaming and the microphone work as in normal chat.

## AI tasks and study
- **AI Task Runner:**
  - a **Project** source (all the project's documents, each with a fair share of the source budget);
  - tasks are tagged with the project and listed on its page;
  - opened from the project with the source preset.
- **Study:**
  - **Desktop:** Study plan and Quiz buttons open the Task Runner on the project's documents.
  - **Android:** "Study plan from the project's documents" opens the existing study-plan builder with them preselected.

## Context isolation: tests
- **Backend:**
  - `test_project_tasks_use_only_the_projects_documents`: a Biology project never sees the History document;
  - `test_project_chat_context_is_isolated_and_instructions_are_marked`: retrieval only from the project; no all-documents tool; memory excluded when off; unknown project refused;
  - `test_project_management_and_document_associations`: removing an association or deleting a project keeps every document.
- **Android:** `ProjectStoreTest` (4): meta round trip and defaults, bounded instructions, export, delete keeps documents (`UPDATE library_docs SET workspace_id = NULL` before `deleteWorkspace`), project chat and tasks scoped to the workspace's documents.
- **Browser (live Ollama):** create, add a document, instructions, archive and restore, export; streamed project chat grounded in the project PDF and recorded on the project; project quiz preset, completed, answered with a score.

## Limitations
- **Web:** a conversation belongs to at most one project; the history lists all chats (project chats too).
- **Android:** projects are the existing workspaces, so "Personal" and "Work" (created by earlier versions) appear as projects.
- **Device testing:** Android projects are verified by unit tests and the build; no phone was connected.
