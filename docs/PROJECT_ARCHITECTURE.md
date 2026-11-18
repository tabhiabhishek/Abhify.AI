# Abhify.AI Android: architecture (4.4.0)

**Overview:** [../README.md](../README.md). **History:** [archive/](archive/README.md).

This replaces the RC-era architecture notes for the current app. [ANDROID_ARCHITECTURE.md](ANDROID_ARCHITECTURE.md) and [ANDROID_APP.md](ANDROID_APP.md) remain for the account, sync and native-runtime background.

## Layers
| Layer | Main types | Notes |
|---|---|---|
| **App start** | `AbhifyApp` → `AppGraph` (object graph: database, engine, AI router, stores, WorkManager hooks) → `MainActivity` | The system splash, then `BrandSplash` (about 1.1 s, cold start only). Nothing waits for Drive, a server or the model. |
| **UI** | `ui/` (Compose, Material 3) | One activity. `AppViewModel.Route` back stack plus `Tab`: Chat, Career, Study, Documents, Knowledge, and Settings from the header. Tokens in `Theme.kt` (`Dimens`, colours, typography), shared components in `Components.kt` (`PageBody`, `ScreenTitle`, `Glass`, `NavRow`, `EmptyState`, `DialogText`, `IconAction`). |
| **State** | `AppViewModel` plus feature states: `StudioState` (documents, study, workspaces), `LifeState`, `CareerState`, `ResumeState`, `MemoryState`, `DriveState`, `BackupState`, `StorageState`, `WorkspaceHub` | All database and network work runs on `Dispatchers.IO`. |
| **AI routing** | `ai/HybridAi`, `RoutePolicy`, `aiTask` | Auto, Online or Phone. Consent dialogs before personal content goes online. No silent cloud fallback. "Creativity" sets the phone model's temperature (default 0.3). |
| **On-device LLM** | `llm/LlmEngine` (JNI `cpp/abhify_llm.cpp`), `model/` (catalog, downloader, `ModelStore` in `noBackupFilesDir/models`) | One model and context at a time. `device/Memory` profiles and `HeavyGate` protect RAM. |
| **Online providers** | `providers/Providers.kt` (an OpenAI-compatible adapter for Gemini, Groq and OpenRouter) | The user's own keys, sealed with the Keystore (`ProviderStore`). |
| **Data** | `chat/ChatDb` (SQLiteOpenHelper, **v17**), `SchemaV5`…`SchemaV17`, `*Db.kt` extension files | Additive migrations only; a JVM test per version; FTS4 created in `onOpen` with a LIKE fallback. |
| **Documents / RAG** | `docs/DocumentReader`, `ocr/` (ML Kit), `docs/Library` (chunks, BM25, citations), `docs/RagIndex`, `docs/Retrieval` (optional vectors) | Text kept, original files aren't stored. The PDF page viewer opens a file you pick (`PdfRenderer`). |
| **Memory** | `memory/MemoryKit`, `memory/MemoryReview` | Relevant-only (6 memories / 900 characters), contradiction review, provenance. |
| **Study / Career** | `learn/` (`Srs`, `StudyInsights`, `PlanCheck`), `career/`, `resume/`, `life/` | Local only. |
| **Workspace** | `workspace/UnifiedSearch`, `ContextOrchestrator` | Local search; bounded workspace context. |
| **Drive / backup** | `drive/` (`DriveApi`, `DriveQueue`, `DriveUploadWorker`, `DriveAccounts`), `backup/` (`Backup`, `BackupIo`, `ScheduledBackupWorker`) | `drive.file`, per-account folders, durable queue, additive restore. |
| **Optional server** | `cloud/` client, `sync/`, `ServerConnection` | Local Mode by default. |
| **Preferences** | `settings/Personalization` (creativity, text size), SharedPreferences `abhify` | Safe settings are included in backups; secrets never. |

## Database versions
| Version | Change |
|---|---|
| v2–v4 | Profiles and sync metadata, pinned chats |
| v5 | Library, study, workspaces |
| v6 | OCR fields, résumé versions |
| v7 | Career, study sessions, vault |
| v8 | Job fields |
| v9 | Memory metadata, archive, chunks, Drive, backups |
| v10 | Drive queue, backup history |
| v11 | Chunk vectors |
| v12 | Interview job/score |
| v13 | Memory usage and review |
| v14 | Spaced repetition, plan docs |
| v15 | Workspace links |
| v16 | Drive account per upload |
| **v17** | Saved prompts |

## Data that never leaves the phone unless the user acts
Chats, documents' text, memories, study and career data, prompts, settings.

What leaves the phone, and only when the user acts:
- **Online AI:** the question, plus approved context.
- **Drive:** an upload or a scheduled backup the user enabled.
- **Opt-in sync:** to an Abhify server.
