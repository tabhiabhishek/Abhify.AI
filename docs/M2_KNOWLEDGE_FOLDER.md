# M2: Watched knowledge folder

Drop files into a folder and Abhify.AI makes them searchable for the chat agent, entirely on your computer.

**Default folder:** `%USERPROFILE%\Abhify_Knowledge`. It's created when you first start watching.

**Change it:** Settings → 📚 Knowledge folder → **Change…**. These locations are refused:
- a whole drive (`C:\`)
- your entire user folder
- `AppData`, Windows, Program Files and ProgramData
- Ollama's model folder
- Abhify.AI's own data and program folders
- relative paths

Nothing outside the chosen folder is ever read.

## Supported files

| Type | Extensions | Notes |
|---|---|---|
| PDF | `.pdf` | Text-based PDFs. Scanned PDFs need OCR (skipped with a reason) |
| Text | `.txt` | UTF-8, UTF-16 or Windows-1252 |
| Markdown | `.md`, `.markdown` | |
| Python | `.py` | |

To add a format, register a parser in `document_indexer.PARSERS`. Each parser takes a `Path` and returns a list of `Document`s.

## Pipeline

```
discovery → validation → text extraction → normalisation → chunking → embedding → ChromaDB → status (SQLite)
(watchdog +   (inside folder?   (parser per    (NULs, line    (1000 chars,  (configured  (active       (documents table:
 initial scan) excluded? link?   extension)     endings)       150 overlap)  Ollama model) collection)   status, error, attempts)
               size? secrets?)
```

- **Collection:** the existing `knowledge_base` collection from v1/M1; uploads and watched files share it. A second collection is only ever created by an explicit **rebuild** (see below).
- **Chunk identity:** id `watcher-<path hash>-<content hash>-<n>`, with metadata `path_key` (which file) and `sha256` (which version).

## Watcher behaviour

| Event | What happens |
|---|---|
| New file | Indexed once it has been **unchanged for ~1.5 s** (size and modification time stable across two checks), so half-copied files are never indexed |
| Modified file | Re-indexed only if its **SHA-256 changed** |
| Renamed or moved inside the folder | Vectors are **moved without re-embedding** when the content is unchanged |
| Deleted file | Its chunks are removed, and the file is marked `removed` |
| Deleted while Abhify.AI was off | Detected by the scan at the next start or rescan |
| Repeated events | Debounced per file; the queue keeps one entry per file |

**Replacement policy:** new chunks are embedded and stored **first**, and only then are the previous chunks of that file deleted. If embedding fails (Ollama down, model missing), the old version stays searchable and the file is retried.

**Retries:**
- Locked files and an unavailable model/Ollama are retried with backoff: 10 s, 20 s, 40 s and so on, capped at 15 minutes, for at most 6 attempts.
- Corrupt files are marked `failed`, with the reason shown.
- **Retry failed** resets the counters, either for all files or per file.

**Controls:** Start / Stop, Pause / Resume, Rescan, Retry failed, Change folder. The status (state, counts, current file and progress, last indexed time, per-file status and errors) refreshes every 2 seconds while the panel is open.

**Lifecycle:**
- There is one watcher per process. Starting it on a new folder stops the old one first.
- It runs on background threads, so chat requests are not blocked. Writes are serialised with a lock.
- It shuts down cleanly when the app exits.
- Watching auto-resumes at start-up if it was on.

## Security

Never read or indexed:
- **Secrets by name:** `.env*`, `*.pem`, `*.key`, `*.pfx`, `*.p12`, `*.crt`, `id_rsa*`/`id_ed25519*`, `*secret*`, `*credential*`, `token`/`token.*`/`*_token.*`, `password*.txt`, `.npmrc`, `.pypirc`, `.netrc`, `.git-credentials`, `*.kdbx`
- **Secrets by content** (text files): private key blocks, AWS, GitHub, OpenAI/Anthropic-style, Slack and Google API keys, and `password = "…"`/`api_key: "…"` assignments. These files are marked *skipped* with a reason.
- **Folders:** `.git`, `.venv`/`venv`/`env`, `node_modules`, `__pycache__`, caches, `dist`, `build`, `backups`, `AbhifyAI`, and any hidden folder (`.something`)
- **Temporary, partial and system files:** `*.tmp`, `~$*`, `*.part`, `*.crdownload`, `*.swp`, `Thumbs.db`
- **Databases and model weights:** `*.db`, `*.sqlite*`, `*.gguf`, `*.safetensors`, `*.bin`, `*.pt`, `*.onnx`, …
- **Symbolic links and junctions**, and anything that resolves outside the folder (`..` traversal)
- Files over 20 MB (`knowledge.max_file_mb` in settings.json) and empty files

ML study notes such as `tokenization.md` or `tokenizer.py` are **not** excluded.

Logs record counts and error types only, never document text or file contents.

## Embedding-model changes

The model that built the active collection is stored as `knowledge.index_embed_model` (the M1 collection adopts the configured model once).

If the embedding model changes, for example through a different setup profile:
1. Status shows **Rebuild required**. Document search returns nothing, rather than querying vectors from a different model.
2. New files wait (`pending`) instead of being mixed in.
3. **Rebuild index**:
   - embeds every tracked document (uploads from their stored originals, watched files from disk) into a **new** collection `knowledge_base-<model>-<timestamp>`
   - validates the chunk count and a test query
   - **only then** switches `knowledge.collection` and `index_embed_model`
4. On failure the half-built collection is deleted and the old index stays active.
5. The old collection is kept, listed under `previous_collections`, until you choose **Delete old index**.

## API

| Method | Path | |
|---|---|---|
| GET | `/knowledge/status` | Watcher state, counts, progress, index model, rebuild state |
| GET | `/knowledge/files?status=` | Per-file status and errors |
| PUT | `/knowledge/folder` * | `{path}`; validates, then removes chunks from the previous folder |
| POST | `/knowledge/watcher/{start,stop,pause,resume}` * | |
| POST | `/knowledge/rescan` * | |
| POST | `/knowledge/retry?doc_id=` * | |
| POST | `/knowledge/rebuild` * | `{confirm: true}` |
| POST | `/knowledge/rebuild/cleanup` * | Delete superseded collections |

\* local machine only.

## Testing it yourself
1. Settings → 📚 Knowledge folder → **Start watching**.
2. Copy a PDF into `%USERPROFILE%\Abhify_Knowledge` and watch it go ⏳ → ✅.
3. In chat, ask about it. The answer shows the **📚 Your documents** badge.
4. Edit the file, rename it, delete it, and watch the status update.
5. Create `secret.txt` containing `password = "hunter2hunter2"`. It shows as ⏭️ skipped.
