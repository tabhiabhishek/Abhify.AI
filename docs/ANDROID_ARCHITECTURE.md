# Abhify Android: architecture (2.0.0-rc.2)

A **local-first** app. The phone's own database and its on-device AI are the source of truth. An online account, online AI and cloud sync are optional layers that the app works fine without.

```
┌──────────────────────────── Android app (ai.abhify.android) ─────────────────────────────┐
│ UI (Jetpack Compose, web-app design tokens, Inter)                                        │
│   Welcome · Local profile · Sign in/Register/Forgot · Link · Home (Chat/Learn/News)        │
│   History · Memories · Models · Account · Sync & network · Privacy · Settings · About      │
│ AppViewModel (navigation stack, state)                                                    │
├───────────────────────────────────────────────────────────────────────────────────────────┤
│ HybridAi ── RoutePolicy (Auto / Online / On-device, privacy permission)                    │
│   ├─ on-device: LlmEngine ─JNI─ llama.cpp v0.7.0 (CPU variants; Vulkan only after a check)│
│   └─ online:    CloudClient.stream → /api/v1/ai/chat (NDJSON)                              │
│ CloudClient (accounts, token refresh) ── TokenStore (Android Keystore AES-GCM)             │
│ SyncEngine ── SyncWorker (WorkManager) ── CloudSyncApi                                     │
│ ChatDb (SQLite v3): profiles, chats, memories, preferences, sync metadata, conflicts       │
│ ModelStore + Downloader + DownloadService (verified GGUF, self-test, recovery)             │
│ NetworkMonitor (validated connectivity) · NewsRepository (RSS) · Voice                     │
└───────────────────────────────────────────────────────────────────────────────────────────┘
                        │ HTTPS only (release)            │ HTTPS (model publisher)
                        ▼                                 ▼
        ┌──── cloud/ (FastAPI, your server) ────┐   huggingface.co/Qwen (GGUF, pinned SHA-256)
        │ accounts · sessions · sync · AI gateway│
        │ SQLite · scrypt · opaque tokens        │
        └──────── private network ──────────────┘
                        ▼
                  Ollama (never public)
```

## Principles
- **Offline first.** Chat, memories, history, learning and model management need no network. Online parts degrade to clear messages.
- **One source of truth per phone.** `ChatDb`. The cloud holds copies only for users who enable sync.
- **Nothing silent.** Every answer is labelled with its provider and model. Online AI and memory sharing need explicit permission. Sync is off until enabled. Conflicts are kept for the user to resolve.
- **Preserved components.** llama.cpp, the GGUF model lifecycle, model selection, CPU/Vulkan logic, the downloader and updater are all unchanged from the baseline.

## Packages (`app/src/main/java/ai/abhify/android/`)
| Package | Contents |
|---|---|
| `ui/` | `MainActivity` (splash, router), `Theme` (web tokens), `Components`, `OnboardingScreens`, `HomeScreens`, `ManageScreens`, `MarkdownText`, `AppViewModel` |
| `ai/` | `HybridAi`, `RoutePolicy`, `Source` (provider label), `CloudLine` (gateway NDJSON) |
| `cloud/` | `Http`/`UrlHttp`, `CloudClient`, `TokenStore` + `KeystoreBox` |
| `sync/` | `SyncEngine`, `SyncMerge`, `SyncStore`/`SyncApi`, `CloudSyncApi`, `SyncWorker`, `NetworkMonitor` |
| `chat/` | `ChatDb` (all local data), `PromptBuilder`, `Markdown`, `CodeHighlight` |
| `llm/`, `model/`, `device/`, `learn/`, `news/`, `voice/`, `update/` | unchanged from the baseline (see ANDROID_APP.md) |

## Local database (`ChatDb`, SQLite, version 3)
**D1 (decision):** SQLiteOpenHelper with explicit, additive migrations; no Room. Existing installs have a v1/v2 database that Room didn't create. Moving it under Room's schema validation risks the existing chats for no functional gain.

| Entity (spec) | Table | Notes |
|---|---|---|
| LocalProfile | `local_profiles` | created by the v3 migration; owns all existing rows |
| LinkedAccount | `linked_accounts` | server, user id, email, linked_at |
| Device | `devices` | this device (name, id) |
| Conversation | `conversations` | + uid, profile_id, deleted, dirty |
| Message | `messages` | + uid, profile_id, updated_at, deleted, dirty, **model** (provider:model of every answer) |
| Memory | `memories` | + uid, profile_id, updated_at, deleted, dirty |
| DocumentMetadata | `document_metadata` | reserved (no document feature on Android yet) |
| ModelRegistry | `model_registry` | mirror of `models/state.json` (the file stays authoritative) |
| UserPreference | `user_preferences` | synced preferences (theme, AI mode, read aloud) |
| SyncQueue | dirty flags on each table | equivalent queue that can't diverge from the data (D6) |
| SyncMetadata | `sync_metadata` | device id, cursor, profile id |
| (conflicts) | `sync_conflicts` | both versions of concurrent edits |
| learning | `quiz_answers`, `interview_views` | v2, unchanged |

**Migrations:**
- v1→v2 adds the learning tables.
- v2→v3 adds columns and tables only. It backfills `uid` with `lower(hex(randomblob(16)))`, sets `updated_at` from `created_at`, and assigns every row to one new local profile. Nothing is dropped or rewritten.
- Tested by `androidTest/DbMigrationTest`, which builds a real v2 database and opens it as v3.
- Android 10 ships SQLite 3.22, so no `UPSERT` syntax is used.

**Encryption boundaries (D2):**
- Tokens are sealed with an Android Keystore key.
- The database lives in app-private storage, covered by Android file-based encryption, and is excluded from backups.
- The database file is **not** app-encrypted; SQLCipher is a possible later step.

## Threads and lifecycles
- One worker thread runs all llama.cpp calls.
- Network and database work runs on `Dispatchers.IO`.
- Sync runs in WorkManager:
  - every hour with a network constraint (Wi-Fi only if chosen);
  - "soon" after local changes;
  - immediately when the connection returns.
- The model unloads under background memory pressure and reloads on demand.
