<p align="center"><img src="frontend/brand/logo-320.png" width="160" alt="Abhify.AI logo"></p>

<h1 align="center">Abhify.AI</h1>
<p align="center"><b>Learn · Build · Share · Grow</b>: a local-first AI/ML learning companion that runs on your own computer.</p>

Abhify.AI combines live AI news, AI-generated interview questions and quizzes, a chat agent that knows your documents and remembers what you ask it to, and voice control. The AI runs **locally through Ollama**, so there are no paid APIs and no cloud account, and your documents and memories stay on your machine.

> **Status:** `2.0.0-dev.4`, a development build of the v2 line (milestones 1–2 plus the release-blocker and model-setup fixes; see [CHANGELOG.md](CHANGELOG.md) and [docs/ROADMAP.md](docs/ROADMAP.md)). Not released yet: the Windows installer is **unsigned**, and real-device phone tests and a second-PC test are still outstanding. The v1.0.0 baseline is Git tag `v1.0.0`.
>
> **Install on Windows:** [docs/WINDOWS_INSTALLATION.md](docs/WINDOWS_INSTALLATION.md) · **Phones:** [docs/MOBILE_INSTALLATION.md](docs/MOBILE_INSTALLATION.md) · **Knowledge folder:** [docs/M2_KNOWLEDGE_FOLDER.md](docs/M2_KNOWLEDGE_FOLDER.md) · **Your data:** [docs/DATA_AND_MIGRATION.md](docs/DATA_AND_MIGRATION.md) · **Releases:** [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md)

| Feature | What it does | Needs internet? |
|---|---|---|
| 📰 News | Latest AI/ML headlines, filtered and summarized by your local LLM | Yes (shows saved news offline, with its age) |
| 🎓 Interview | Fresh interview questions with model answers and key points | No* |
| 📝 MCQ | 5-question quizzes, explanations, score and streak | No* |
| 💬 Chat | Agent that uses **your documents**, **your memories**, or **web search** when needed | Only for web search |
| 📚 Knowledge folder | Drop PDFs, notes and `.py` files into a folder; they're indexed automatically and safely | No |
| 🧠 Memory | Personal facts *you* choose to save; editable, deletable, can be switched off | No |
| 🎙️ Voice | Voice commands and spoken questions (Web Speech API or local Faster-Whisper) | No with Whisper |
| 📱 Phone companion | Use the AI on your PC from your phone, after you pair it | No (same network or Tailscale) |

\* after setup, when a local model is installed.

---

## 🧭 How you can use it

| | **A. Windows PC** | **B. Phone connected to your PC** | **C. Phone on its own** |
|---|---|---|---|
| Where the AI runs | This PC | **Your PC** (the phone only shows the app) | Nowhere: no AI runs on the phone |
| Install | Windows installer | Pair the phone, then "Install app" / "Add to Home Screen" | Same PWA, offline |
| Chat, memory, documents | ✅ | ✅ through your PC | ❌ |
| News, interview questions, quizzes | ✅ live | ✅ live | Saved copies and samples only |
| Your data lives on | This PC | Your PC | Browser caches only |

Every computer has its **own** data (chats, memories, settings, document index and models). Nothing is synced between computers. To move to another PC, use backup and restore ([DATA_AND_MIGRATION.md](docs/DATA_AND_MIGRATION.md)).

There is **no APK and no iOS app**. On phones, Abhify.AI is a Progressive Web App connected to your PC ([why](docs/MOBILE_INSTALLATION.md#why-no-apk-or-ios-app-yet)).

## 🚀 Install

### Windows (recommended): the installer
1. Run `Abhify.AI-Setup-<version>.exe`, after checking its SHA-256 against `SHA256SUMS.txt`.
   - Installs for your user only: no administrator rights, no Python needed. It bundles its own Python runtime.
   - The installer is not signed yet, so Windows SmartScreen may warn you: **More info → Run anyway**.
2. Install **[Ollama](https://ollama.com/download)** if you don't have it. The app tells you if it's missing.
3. Start **Abhify.AI** from the Start Menu. It runs in the tray and opens in your browser. The **setup wizard**:
   - **checks this computer:** OS, CPU, RAM, NVIDIA GPU/VRAM, free disk, Ollama
   - **recommends a model profile:**

     | Profile | Models | Download | Good for |
     |---|---|---|---|
     | Minimal | `qwen2.5:1.5b` + `nomic-embed-text` | ~1.3 GB | Most laptops, CPU-only |
     | Balanced | `qwen2.5` (7B) + `nomic-embed-text` | ~5 GB | 6 GB+ GPU, or 16 GB RAM on CPU |
     | Quality | `qwen2.5:14b` + `nomic-embed-text` | ~9.3 GB | ~11 GB+ VRAM |

   - **downloads only after you confirm,** and only if the disk has room
   - **runs a test** to confirm the AI works

A GPU is **not** required. You can skip setup ("limited mode"); news and saved quizzes still work. Details, upgrade, uninstall and troubleshooting: [docs/WINDOWS_INSTALLATION.md](docs/WINDOWS_INSTALLATION.md).

### From source (developers)
1. Install **Python 3.11** and **Ollama**, and start Ollama.
2. Double-click **`start.bat`**. The first run creates `backend\.venv` and installs the dependencies, then opens **http://localhost:8000**.

macOS / Linux: `./start.sh`. Docker: `docker compose up -d --build` (unverified, since Docker wasn't available on the development machine).

### Phone (Android / iPhone)
1. On the PC: Settings → **System & data** → **Companion access** → **Turn on**. The access token is shown **once**. Restart Abhify.AI.
2. Recommended: give the PC a private HTTPS address with **[Tailscale](https://tailscale.com/download)** (`tailscale serve --bg 8000`, using the port shown in the tray tooltip if it isn't 8000). Browsers only allow installing the app and using the microphone over HTTPS.
3. On the phone, open `https://<your-pc>.<tailnet>.ts.net/#pair=<token>`. The app saves the token, removes it from the address bar, and says "Paired" only once the PC accepts it.
4. Install it: **Android (Chrome):** ⋮ → **Install app**. **iPhone (Safari, iOS 16.4+):** Share → **Add to Home Screen**.

The status bar always says where the AI is: for example **"AI ready · runs on your computer"**, **"Not paired"**, **"Computer connected · Ollama not running"** or **"Connection to your computer lost · retrying"**. Never forward port 8000 on your router. Full guide: [docs/MOBILE_INSTALLATION.md](docs/MOBILE_INSTALLATION.md).

## 🗂️ Where things live

| What | Where (Windows) | Removed by uninstall? |
|---|---|---|
| Program (app + private Python runtime) | `%LOCALAPPDATA%\Programs\Abhify.AI` | Yes |
| Your data: chats, memories, progress, settings, document index, backups | `%LOCALAPPDATA%\AbhifyAI` | Only if you say yes |
| Logs | `%LOCALAPPDATA%\AbhifyAI\logs` | Only if you say yes |
| Your knowledge folder | `%USERPROFILE%\Abhify_Knowledge` (changeable) | Never |
| AI models | Ollama's folder (`%USERPROFILE%\.ollama\models`) | Never |

On macOS the data folder is `~/Library/Application Support/AbhifyAI/`, and on Linux `~/.local/share/AbhifyAI/`.
- Data from AI Tech Pulse v1.0.0 is **copied** in automatically on first start; the original stays where it was.
- Every database migration takes a backup first. A database newer than the app is refused, never damaged.

## 🔒 Privacy & safety

- The LLM, embeddings, documents, memories, chat history, progress and Whisper all run and stay **locally**.
- Internet is used only for news and web search (queries go to DuckDuckGo; web search can be turned off with `features.web_search` in `settings.json`), model downloads you confirm, and an opt-in update check. Nothing is downloaded or installed automatically.
- Memory is only saved when you add it, or explicitly say *"remember that…"*. You can view, edit and delete every memory.
- The server listens on **127.0.0.1** by default. It listens on your network only after you turn on companion access.
- **Phones get an allow-list, not admin rights.** A valid token opens chat, learning content, voice, your documents, memories, preferences, history and progress. Everything else answers `403` to a phone, including any future route: system diagnostics, model setup, backups, moving data, knowledge-folder and watcher controls, update checks and the API docs.
- Responses to phones never include the token hash, your Windows user name or folder paths. The token itself is stored only as a SHA-256 hash.
- **Logs contain no chat text or document contents, and no tokens.** Query-string values and `Authorization` headers are masked.
- Requests that arrive through a tunnel or proxy count as remote, never as "this computer".

## 🧱 Architecture

```
 PWA (frontend/)                        FastAPI backend (backend/)                        Local services
 ─────────────────                      ──────────────────────────                        ──────────────
 index.html · app.js · styles.css   ──► main.py            REST API, serves the PWA
 sw.js offline shell (versioned)        companion.py       phone access: token, allow-list, redaction
 setup · memory · system ·              log_redaction.py   masks tokens / query values in all logs
 knowledge · companion UI               agent.py           LangGraph ReAct agent ───────────► Ollama (LLM + embeddings)
 localStorage: prefs, caches,           ├ knowledge_base_search ► knowledge.py ────────────► ChromaDB  ┐
 offline queue, access token            ├ search/save_memory ───► memory_service.py ───────► SQLite    │ %LOCALAPPDATA%
                                        └ web_search (online only) ► DuckDuckGo                        │ \AbhifyAI
                                        folder_watcher.py  watchdog → debounced queue → worker         │
                                        document_indexer.py parse · chunk · embed · replace-then-delete │
                                        generators.py      news / questions / MCQs                     │
                                        system_service.py  hardware, profiles, model pulls, updates    │
                                        database.py        SQLite + migrations + backups ──────────────┤
                                        local_file_service.py data dir, settings, backup/restore ──────┘
                                        transcribe.py      Faster-Whisper (GPU → CPU fallback)

 desktop/launcher.pyw   tray icon, single instance, port 8000 (+ fallbacks), --stop, --backup (used by upgrades)
 packaging/windows/     build.py (bundled Python 3.11 + app) → installer.nsi (NSIS, per-user) → Sandbox test
```

**Chat request flow**
1. The PWA sends `POST /chat {message, thread_id}`.
2. The agent loads the last 12 messages of that conversation from SQLite and adds your relevant memories to its instructions.
3. The local model decides which tools to call: your documents, memory, or the web (web only when online).
4. The answer and the tools it used are saved to SQLite and returned with sources.

**Knowledge folder flow:** a file event (or a rescan) is queued, debounced until the file stops changing, then indexed by a single worker thread. A changed file replaces its old chunks only after the new version is indexed. A rename reuses the vectors, a delete removes them, locked files and an unavailable Ollama are retried with backoff, and secrets and unsupported files are skipped. Changing the embedding model triggers a safe rebuild into a new collection, which is validated before it's switched in; the old index is kept as a fallback. See [docs/M2_KNOWLEDGE_FOLDER.md](docs/M2_KNOWLEDGE_FOLDER.md).

## 🔌 API

"Phone" means a paired companion device with a valid token. "PC only" endpoints answer `403` to any other device, token or not.

| Area | Endpoints | Phone? |
|---|---|---|
| Status | `GET /ping` · `GET /health` (reduced for unpaired callers) · `GET /companion` | Open |
| Learning | `POST /chat` · `GET /news` · `POST /generate-question` · `POST /generate-mcq` | ✅ |
| Voice | `POST /transcribe` · `POST /voice` | ✅ |
| Documents | `POST /knowledge/upload` · `GET /knowledge` · `GET /knowledge/status` · `GET /knowledge/files` (no paths for phones) | ✅ |
| Knowledge folder | `PUT /knowledge/folder` · `POST /knowledge/watcher/{start,stop,pause,resume}` · `POST /knowledge/rescan` · `POST /knowledge/retry` · `POST /knowledge/rebuild` · `POST /knowledge/rebuild/cleanup` | PC only |
| Memory | `GET/POST/DELETE /memory` · `PATCH/DELETE /memory/{id}` · `GET/PUT /preferences` | ✅ |
| Conversations & progress | `GET/DELETE /conversations` · `GET/DELETE /conversations/{id}` · `GET /progress/{user_id}` · `POST /progress` | ✅ |
| Setup / system | `GET /system/diagnostics` · `GET /setup/profiles` · `POST /setup/profile` · `GET/POST /setup/models/pull` · `POST /setup/test` · `POST /setup/complete` · `GET /system/update-check` · `POST /system/shutdown` | PC only |
| Backup / data | `GET/POST /backup` · `GET /backup/export` · `POST /backup/restore` · `POST /system/data-location` | PC only |
| Companion | `POST /companion/enable` · `POST /companion/disable` | PC only |

Interactive docs are at http://localhost:8000/docs (on the PC only).

## ⚙️ Configuration

| Where | What |
|---|---|
| In the app (`settings.json`) | Model profile, feature switches, setup status, knowledge folder, companion access, update manifest URL |
| In the app (SQLite) | Your name, memory on/off, save history, history retention |
| Environment / `backend/.env` | `OLLAMA_BASE_URL`, `LLM_MODEL` / `EMBED_MODEL` (override the profile), `ABHIFY_DATA_DIR`, `WHISPER_MODEL`, `WHISPER_DEVICE`, `CORS_ORIGINS`, `ALLOW_REMOTE_ADMIN` (testing only; lets a phone use PC-only routes), `ABHIFY_HOST` (start scripts) |

## 🧪 Tests

```powershell
cd backend
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest tests -q -p no:cacheprovider
```

**Automated suite:** 219 tests. Ollama, the network and downloads are mocked, and every test uses a throw-away data folder, never your real data. They cover:
- migrations, backup/restore safety (including zip-slip and newer-version backups), data relocation, disk checks
- memory and history; missing Ollama; local-only admin endpoints
- the knowledge folder: indexing, edits, renames, deletes, partial writes, retries, rebuilds, exclusions
- watcher lifecycle: deterministic deadlock, overlap and stale-event tests, plus repeated folder switching
- companion access: every route with no, wrong and valid tokens; no private data in any response a phone can get
- log redaction, including a real `uvicorn` dev server with its access log on
- the PWA's connection-status and pairing logic, run in Node (skipped if Node isn't installed)
- packaging: allow-lists, no build paths in bytecode, the HTML install guide, version consistency

**Clean-machine test:** `powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1 [-UpgradeFrom <older version>]` installs the real installer in Windows Sandbox with no Python, no Ollama and no network. It then tests the app, privacy, upgrade, uninstall and reinstall.

**Latest results:** see [docs/RELEASE_BLOCKER_FIXES_DEV3.md](docs/RELEASE_BLOCKER_FIXES_DEV3.md) and [docs/INSTALLER_READINESS_AUDIT.md](docs/INSTALLER_READINESS_AUDIT.md). Physical-PC and real-phone acceptance tests are tracked in [PHYSICAL_PC_ACCEPTANCE_TEST.md](docs/PHYSICAL_PC_ACCEPTANCE_TEST.md) and [MOBILE_ACCEPTANCE_TEST.md](docs/MOBILE_ACCEPTANCE_TEST.md).

## 🛠️ Troubleshooting

| Symptom | Fix |
|---|---|
| *Backend up · Ollama offline* | Start the Ollama app (`ollama serve`) |
| *Backend up · model not installed* | Settings → System & data → **Models & setup** → download your profile |
| *Local AI ready · no internet* | Everything works except news and web search |
| Phone says *Not paired* | Open the pairing link from the PC again, or enter the token in Settings |
| Phone says *Companion access is off on your computer* | Turn it on, on the PC (Settings → System & data) |
| Phone says *Paired · your computer isn't reachable* | Check the PC is on, Abhify.AI is running, and both are on the same Wi-Fi or Tailscale |
| Chat is slow on a laptop | Choose the **Minimal** profile in Models & setup |
| App won't start: "schema … is newer than this app" | You opened data from a newer version. Update Abhify.AI, or restore an older backup |
| A migration failed | Nothing is changed (it rolls back). The pre-migration backup is in `data\backups\` |
| Voice doesn't work in Firefox or an app window | Turn on Settings → Private voice mode (uses Whisper locally) |
| Need to start over | Close the app and rename `%LOCALAPPDATA%\AbhifyAI`. A fresh folder is created and your old one is kept |

More: [docs/WINDOWS_INSTALLATION.md#troubleshooting](docs/WINDOWS_INSTALLATION.md#troubleshooting).

## 📦 Building the installer

```powershell
py -3.11 packaging\windows\build.py                     # online: downloads pinned Python + NSIS, installs wheels
py -3.11 packaging\windows\build.py --site-packages-from backend\.venv\Lib\site-packages   # offline
```
The output goes to `dist\windows\<version>\`: the installer, `SHA256SUMS.txt`, release notes, `INSTALL.html` and a `latest.json` template. The build refuses to package user data, secrets or bytecode with build-machine paths. Signing is designed but not set up ([docs/CODE_SIGNING.md](docs/CODE_SIGNING.md)). Release steps: [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md).

## 📚 Documentation

| Document | For |
|---|---|
| [WINDOWS_INSTALLATION.md](docs/WINDOWS_INSTALLATION.md) | Installing, upgrading, uninstalling on Windows |
| [MOBILE_INSTALLATION.md](docs/MOBILE_INSTALLATION.md) | Android / iPhone, pairing, HTTPS, the three modes |
| [M2_KNOWLEDGE_FOLDER.md](docs/M2_KNOWLEDGE_FOLDER.md) | The watched knowledge folder |
| [DATA_AND_MIGRATION.md](docs/DATA_AND_MIGRATION.md) | Data folder, backups, restore, moving PCs |
| [RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md) · [CODE_SIGNING.md](docs/CODE_SIGNING.md) | Building and releasing |
| [ROADMAP.md](docs/ROADMAP.md) · [CHANGELOG.md](CHANGELOG.md) | Plan and history |
| [README_MAINTENANCE.md](docs/README_MAINTENANCE.md) | How to keep this README accurate |
