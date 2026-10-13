# Your data: where it lives, backups, and moving to another PC

## Where Abhify.AI stores data

Program files (this folder) and **your data** are kept apart. Your data lives in:

| OS | Default location |
|---|---|
| Windows | `%LOCALAPPDATA%\AbhifyAI\` (e.g. `C:\Users\<you>\AppData\Local\AbhifyAI`) |
| macOS | `~/Library/Application Support/AbhifyAI/` |
| Linux | `~/.local/share/AbhifyAI/` |

```
AbhifyAI/
├── config/settings.json       model profile, feature switches, setup status
├── data/abhify.db             SQLite: progress, conversations, memories, preferences, document index
├── data/chroma/               ChromaDB vectors of your documents
├── data/documents/uploads/    originals of files you added
├── data/backups/              automatic pre-migration backups + your backups
├── logs/abhify.log            rotating log (no document contents or chat text)
└── cache/                     temporary files, restore staging
```

**AI models are not in this folder.** Ollama keeps them in `%USERPROFILE%\.ollama\models` (or `OLLAMA_MODELS`).

Ways to change the location:
- **In the app:** Settings → System & data → **Move data folder…**. Data is copied, verified, and the app switches after a restart. The old folder is kept until you delete it.
- **Environment variable:** `ABHIFY_DATA_DIR=E:\AbhifyData` (or `DATA_DIR`, used by Docker).

## Upgrading from AI Tech Pulse v1.0.0

On first start, v2 **copies** `backend/data/progress.db` and `backend/data/chroma/` into the new folder. It never moves them. Then it migrates the database, taking an automatic backup first. The record of this is in `config/migrated_from_v1.json`. You can delete the old `backend/data` once you're happy.

## Backups

- **Automatic:** before every database schema migration, as `data/backups/abhify-pre-schema-vN-<time>.db`.
- **Manual:** Settings → System & data → **Backup now** (zip in `data/backups/`) or **Export** (downloads a zip).
- **Restore:** **Restore…** → choose a zip → restart Abhify.AI.
  - Your current data is moved to `data/backups/pre-restore-<time>/`, never deleted.
  - Backups from a *newer* app version are refused, so there are no silent downgrades.
  - Zips that aren't Abhify.AI backups, or that contain unsafe paths, are rejected.

## Moving to another PC

1. On the old PC, open Settings → System & data and click **Export**. Copy the zip to the new PC (USB drive, etc.).
2. On the new PC, install Abhify.AI and Ollama, and run the setup wizard. It picks models that suit *that* computer.
3. Open Settings → System & data → **Restore…**, select the zip, then restart.

Your progress, conversations, memories, uploaded documents and their search index come with you.

Two caveats:
- **Models are not copied.** The new PC downloads its own, chosen for its hardware.
- **If the new PC uses a different embedding model** than the old one, re-index your documents (available in milestone M2). Chat, memory and progress are unaffected.

Manual alternative: copy the whole `AbhifyAI` folder while Abhify.AI is **closed** on both machines.
