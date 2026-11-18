# Abhify Android: privacy

## Defaults
| Setting | Default |
|---|---|
| Online AI | **off** until the user allows it (Privacy) |
| Send memories to the online AI | **off**, separate permission, only possible when online AI is allowed |
| Cloud sync | **off**; per-kind toggles; confirmation lists what uploads |
| Account | not required |

## What leaves the phone, and when
| Feature | Data sent | When |
|---|---|---|
| On-device AI | nothing | never |
| Online AI | the question, recent messages of that chat, the profile name; memories **only** with the second permission | only when online AI is allowed **and** the chosen mode routes online (Online, or Auto when online and signed in) |
| Cloud sync | the enabled kinds (conversations + messages, memories, preferences) | only when sync is on |
| Account | email, display name, device name | sign-in, registration |
| News | nothing about the user (downloads public RSS feeds) | when the News tab is opened or refreshed |
| Model download | a file request to the model publisher | when the user confirms a download |
| Voice | speech audio, **only** if the phone has no on-device recognizer (Settings shows which) | when the mic button is used |
| Never | AI model files, documents, the password (only Keystore-sealed tokens are kept) | — |

**Every answer shows its source:** `☁ Online · <model>` or `📱 On this phone · <model>`. The source is stored with the message, so history stays accurate. In Online mode the app never silently switches to the phone. In Auto, a failure before the first word falls back to the phone with a visible notice.

## Storage on the phone
- **App-private storage**, covered by Android's file-based encryption at rest. **Excluded from Android cloud backup and device transfer** (`allowBackup=false`, `dataExtractionRules`).
- **Tokens:** sealed with an Android Keystore AES-256-GCM key.
- **SQLite file:** not app-encrypted (SQLCipher is not integrated in this version). Anyone with root or a debuggable build of this app could read it.
- **Deleting data:**
  - *Privacy → Delete all chats and memories* (on this phone, and on synced devices if sync is on);
  - *Sync → Delete cloud data* (cloud only);
  - *Account → Delete account* (account and cloud);
  - uninstalling removes everything on the phone.

## Server side (`cloud/`)
- Per-user isolation on every query.
- Passwords are hashed with scrypt; tokens are stored as hashes.
- No analytics or tracking.
- Logs never contain tokens, passwords or message text; the AI gateway logs only error types.
- Ollama runs on the server's private network and is never published.
