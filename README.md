<p align="center"><img src="frontend/brand/logo-320.png" width="160" alt="Abhify.AI logo"></p>

<h1 align="center">Abhify.AI</h1>
<p align="center"><b>Learn · Build · Share · Grow</b>: a local-first AI/ML learning companion that runs on your own computer.</p>

Abhify.AI combines live AI news, AI-generated interview questions and quizzes, a chat agent that knows your documents and remembers what you ask it to, and voice control. The AI runs **locally through Ollama**, so there are no paid APIs and no cloud account, and your documents and memories stay on your machine.

> **Status:** `2.0.0-dev.2`, milestones 1–2 of the v2 upgrade (see [docs/ROADMAP.md](docs/ROADMAP.md)). The v1.0.0 baseline is Git tag `v1.0.0`.
>
> **Install on Windows:** [docs/WINDOWS_INSTALLATION.md](docs/WINDOWS_INSTALLATION.md) · **Phones:** [docs/MOBILE_INSTALLATION.md](docs/MOBILE_INSTALLATION.md) · **Knowledge folder:** [docs/M2_KNOWLEDGE_FOLDER.md](docs/M2_KNOWLEDGE_FOLDER.md) · **Releases:** [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md)

| Feature | What it does | Needs internet? |
|---|---|---|
| 📰 News | Latest AI/ML headlines, filtered and summarized by your local LLM | Yes (shows saved news offline, with its age) |
| 🎓 Interview | Fresh interview questions with model answers and key points | No* |
| 📝 MCQ | 5-question quizzes, explanations, score and streak | No* |
| 💬 Chat | Agent that uses **your documents**, **your memories**, or **web search** when needed | Only for web search |
| 📚 Knowledge folder | Drop PDFs, notes and `.py` files into a folder; they're indexed automatically and safely | No |
| 🧠 Memory | Personal facts *you* choose to save; editable, deletable, can be switched off | No |
| 🎙️ Voice | Voice commands and spoken questions (Web Speech API or local Faster-Whisper) | No with Whisper |

\* after setup, when a local model is installed.

---

## 🚀 Quick start (Windows)

1. Install **[Python 3.11](https://www.python.org/downloads/)** and **[Ollama](https://ollama.com/download)**, and start Ollama.
2. Double-click **`start.bat`**. The first run creates `backend\.venv` and installs dependencies.
3. Your browser opens **http://localhost:8000**, and the **setup wizard** guides you:
   - **Checks this computer:** OS, CPU, RAM, NVIDIA GPU/VRAM, free disk, Ollama.
   - **Recommends a model profile:**

     | Profile | Models | Download | Good for |
     |---|---|---|---|
     | Minimal | `qwen2.5:1.5b` + `nomic-embed-text` | ~1.3 GB | Most laptops, CPU-only |
     | Balanced | `qwen2.5` (7B) + `nomic-embed-text` | ~5 GB | 6 GB+ GPU, or 16 GB RAM on CPU |
     | Quality | `qwen2.5:14b` + `nomic-embed-text` | ~9.3 GB | ~11 GB+ VRAM |

   - **Downloads only after you confirm,** and only if the disk has room.
   - **Runs a test** to confirm the AI works.

You can skip setup ("limited mode"). News and saved quizzes still work.

macOS / Linux: `./start.sh`. Docker: `docker compose up -d --build` (unverified, since Docker wasn't available on the development machine).

## 🗂️ Where your data lives

User data is kept **outside** the program folder:

| OS | Location |
|---|---|
| Windows | `%LOCALAPPDATA%\AbhifyAI\` |
| macOS | `~/Library/Application Support/AbhifyAI/` |
| Linux | `~/.local/share/AbhifyAI/` |

- Data from AI Tech Pulse v1.0.0 is **copied** in automatically on first start; the original stays where it was.
- AI models stay in Ollama's own folder.

See **[docs/DATA_AND_MIGRATION.md](docs/DATA_AND_MIGRATION.md)** for the folder layout, backups, restore, moving the data folder, and moving to another PC.

## 🔒 Privacy & safety

- The LLM, embeddings, documents, memories, chat history, progress and Whisper all run and stay **locally**.
- Web search and news send search queries to DuckDuckGo. You can turn web search off in `settings.json` (`features.web_search`).
- Memory is only saved when you add it, or explicitly say *"remember that…"*. You can view, edit and delete every memory.
- Logs contain no chat text or document contents.
- The server listens on **127.0.0.1** by default. To use it from your phone, set `ABHIFY_HOST=0.0.0.0` and use an HTTPS tunnel.
- **Admin actions** (backup, restore, moving data, downloading models) only work from the computer running Abhify.AI.

## 🧱 Architecture

```
 PWA (frontend/)                        FastAPI backend (backend/)                     Local services
 ─────────────────                      ──────────────────────────                     ──────────────
 index.html · app.js · styles.css   ──► main.py          REST API, serves the PWA
 sw.js offline shell                    agent.py         LangGraph ReAct agent ──────► Ollama (LLM)
 setup wizard · memory · system UI      ├ knowledge_base_search ─► knowledge.py ─────► ChromaDB  ┐
 localStorage: prefs, caches, queue     ├ search/save_memory ────► memory_service.py ► SQLite    │ %LOCALAPPDATA%
                                        └ web_search (online only) ► DuckDuckGo                  │ \AbhifyAI
                                        generators.py    news / questions / MCQs                 │
                                        system_service.py hardware, profiles, model pulls        │
                                        database.py      SQLite + migrations + backups ──────────┤
                                        local_file_service.py data dir, settings, backup/restore ┘
                                        transcribe.py    Faster-Whisper (GPU → CPU fallback)
```

**Chat request flow**
1. The PWA sends `POST /chat {message, thread_id}`.
2. The agent loads the last 12 messages of that conversation from SQLite and adds your relevant memories to its instructions.
3. The local model decides which tools to call: your documents, memory, or the web (web only when online).
4. The answer and the tools it used are saved to SQLite and returned with sources.

## 🔌 API (summary)

| Area | Endpoints |
|---|---|
| Learning | `GET /health` · `POST /chat` · `GET /news` · `POST /generate-question` · `POST /generate-mcq` |
| Voice | `POST /transcribe` · `POST /voice` |
| Knowledge | `POST /knowledge/upload` · `GET /knowledge` |
| Progress | `GET /progress/{user_id}` · `POST /progress` |
| Memory | `GET/POST /memory` · `PATCH/DELETE /memory/{id}` · `GET/PUT /preferences` |
| Conversations | `GET /conversations` · `GET/DELETE /conversations/{id}` |
| Setup / system | `GET /system/diagnostics` · `GET /setup/profiles` · `POST /setup/profile`* · `POST/GET /setup/models/pull`* · `POST /setup/test` · `POST /setup/complete`* |
| Backup / data | `GET /backup` · `POST /backup`* · `GET /backup/export`* · `POST /backup/restore`* · `POST /system/data-location`* |

\* local machine only. Interactive docs are at http://localhost:8000/docs.

## ⚙️ Configuration

| Where | What |
|---|---|
| In the app (`settings.json`) | Model profile, feature switches, setup status |
| In the app (SQLite) | Your name, memory on/off, save history, history retention |
| Environment / `backend/.env` | `OLLAMA_BASE_URL`, `LLM_MODEL` / `EMBED_MODEL` (override profile), `ABHIFY_DATA_DIR`, `WHISPER_MODEL`, `WHISPER_DEVICE`, `CORS_ORIGINS`, `ALLOW_REMOTE_ADMIN`, `ABHIFY_HOST` (start scripts) |

## 🧪 Tests

```bash
cd backend
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest tests -q
```

**Unit and API suite:** 50 tests. Ollama, the network and downloads are mocked, and every test uses a throw-away data folder. They cover:
- migrations: data preserved, newer schema refused, failed migration rolled back
- memory and history
- backup and restore safety, including zip-slip and newer-version backups
- data relocation, and disk-space checks before downloads
- missing Ollama, and local-only admin endpoints

**Live checks run on the development PC** (Windows 11, RTX A2000, Ollama 0.40, qwen2.5 7B), not part of the pytest suite:
- v1 → v2 data adoption, and upgrade migrations v2 → v3 on real data with automatic backups.
- Memory saved via chat and recalled in a new conversation after a restart.
- An identical upload indexed once.
- Browser end-to-end test (Edge, emulated 390 px phone and 1280 px desktop):
  - setup wizard, memory and system panels
  - chat history restored after a page reload
  - offline mode
  - no horizontal overflow, no JavaScript errors

## 🛠️ Troubleshooting

| Symptom | Fix |
|---|---|
| Footer says *Backend up · Ollama offline* | Start the Ollama app (`ollama serve`) |
| *Backend up · model not installed* | Settings → System & data → **Models & setup** → download your profile |
| *Local AI ready · no internet* | Everything works except news and web search |
| Chat is slow on a laptop | Choose the **Minimal** profile in Models & setup |
| App won't start: "schema … is newer than this app" | You opened data from a newer version. Update Abhify.AI, or restore an older backup |
| A migration failed | Nothing is changed (it rolls back). The pre-migration backup is in `data\backups\` |
| Voice doesn't work in Firefox or an app window | Turn on Settings → Private voice mode (uses Whisper locally) |
| Need to start over | Close the app and rename `%LOCALAPPDATA%\AbhifyAI`. A fresh folder is created and your old one is kept |

## 📦 Deployment options

- **Local (recommended):** `start.bat` on the computer with Ollama.
- **Install as an app:** in Chrome or Edge, click the ⬇ install button for a standalone window and Start-menu icon.
- **Phone:** run on your PC and expose it with an HTTPS tunnel (`cloudflared tunnel --url http://localhost:8000`). Browsers only allow install and microphone access over HTTPS.
- **Native desktop installer (Tauri) and auto-updates:** planned for milestones M4–M5. See [docs/ROADMAP.md](docs/ROADMAP.md).
