# Abhify App Action Registry (Android 5.4)

Source: `voice/AppActions.kt` (registry + matcher + validation), `ui/VoiceState.kt` (trusted executor).

## Rules

- Actions are matched only from **the user's own spoken words**, against the whole utterance (English and Hindi patterns). AI output, documents, news and knowledge text never reach the matcher; a test checks it has exactly one caller.
- Every action is a registered, typed entry: id, description, parameter, permission class and confirmation rule.
- **Parameters** are validated in the executor: required, length-limited, no control characters.
- The spoken or shown reply is the **actual result** of the execution (for example "You don't have any saved conversations yet").
- **Scope: Abhify only.** No shell, files, database queries, other apps, contacts, messages or system settings.

## Actions

| Id | Kind | Example phrases | What happens |
|---|---|---|---|
| open_home / open_assistant / open_study / open_news / open_more | Navigation | "open Study", "go to news", "go home", "न्यूज़ खोलो", "स्टडी खोलो" | opens the destination |
| open_knowledge_center, open_documents, open_projects, open_tasks, open_settings, open_voice_settings, open_history, open_memory, open_models | Navigation | "open the knowledge center", "show my projects", "open voice settings" | opens the screen |
| open_last_conversation | Navigation | "open my last conversation" | loads the latest saved chat (or says there is none) |
| new_chat | Navigation | "start a new chat", "नई चैट शुरू करो" | new chat |
| search (query) | Navigation | "find my Python notes" | Search everything with the query |
| discover (topic) | Navigation | "find free courses on machine learning" | Study → Discover search |
| model_status | Read-only | "show my model status" | which AI answers next, the phone model |
| project_status | Read-only | "what are we working on", "हम किस पर काम कर रहे हैं" | the last updated project's **recorded** status, or "no recorded status" |
| recommendations | Read-only | "what should I do today", "आज मुझे क्या करना चाहिए" | from Continue working and Suggestions (real records, with reasons), or "nothing pending" |
| study_progress | Read-only | "my study progress" | plan progress, due cards, quiz score (recorded only) |
| screen_help | Read-only | "what is this screen", "explain this page" | the open screen's description (no screenshot) |
| create_task (title) | Reversible | "create a task called …" | asks Confirm / Cancel, adds the to-do to the chat's project (or the first project, or a new "Inbox"), offers Undo |
| create_note (text) | Reversible | "take a note that …" | the same, as a note |
| sensitive | Sensitive | "delete all my conversations", "clear my memories", "export my data", "restore a backup", "turn off online AI", "change privacy settings" | **never executed by voice**: opens Privacy & data, where the existing confirmations apply |

Anything else is an ordinary question for the AI. Some phrases that look like commands are deliberately left alone, for example "find the derivative of x squared", "explain it with an example" and "tell me the news about AI". Tests cover these.
