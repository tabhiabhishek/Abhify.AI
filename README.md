# 🤖 AI Tech Pulse

A cross-platform AI/ML learning companion. **One codebase** runs on the **web, phones (iOS/Android) and desktops (Windows/macOS)** as an installable PWA. AI features are powered by a local LLM, so they cost nothing to run and your data stays on your machine.

| Tab | What it does | Powered by |
|---|---|---|
| 📰 **News** | Live AI/ML headlines, filtered for relevance and summarized for learners | DuckDuckGo News + qwen2.5 |
| 🎓 **Interview** | Fresh interview questions with model answers and key points, by category and difficulty | qwen2.5 (JSON mode) |
| 📝 **MCQ** | 5-question quizzes with explanations and score tracking | qwen2.5 + SQLite |
| 💬 **Chat** | An agent that decides between **your uploaded PDFs/notes** and **live web search** | LangGraph ReAct + ChromaDB + DuckDuckGo |
| 🎙️ **Voice** | Voice commands ("news", "quiz", "next", "my progress") or spoken questions, with spoken answers | Web Speech API → Faster-Whisper fallback |

When the backend is unreachable, the app switches to **Offline Mode**: it shows saved content (or built-in samples), quizzes still work, and progress is queued and synced later.

---

## 🧱 Architecture

```
 ┌──────────── PWA (frontend/) ────────────┐        ┌──────────── FastAPI (backend/) ─────────────┐
 │ index.html · styles.css · app.js        │  HTTP  │ main.py        REST API + serves the PWA     │
 │ sw.js (offline shell) · manifest.json   │ ─────► │ agent.py       LangGraph ReAct agent         │
 │ localStorage cache · offline queue      │        │   ├─ knowledge_base_search → ChromaDB        │
 │ Web Speech API / MediaRecorder          │        │   └─ web_search           → DuckDuckGo      │
 └─────────────────────────────────────────┘        │ generators.py  news / questions / MCQs       │
                                                    │ knowledge.py   PDF → chunks → embeddings     │
                                                    │ transcribe.py  Faster-Whisper (GPU/CPU)      │
                                                    │ db.py          SQLite progress + streaks     │
                                                    └──────────────┬──────────────────────────────┘
                                                                   │
                                                     Ollama: qwen2.5 · nomic-embed-text
```

## 📂 Project structure

```
ai_tech_pulse/
├── backend/
│   ├── main.py            FastAPI app: /chat /news /generate-question /generate-mcq /transcribe /voice ...
│   ├── agent.py           LangGraph agent with knowledge-base + web-search tools
│   ├── generators.py      AI-generated news summaries, interview questions, MCQs (validated)
│   ├── knowledge.py       ChromaDB vector store (PDF / .txt / .md ingestion)
│   ├── transcribe.py      Faster-Whisper transcription (CUDA float16 → CPU int8 fallback)
│   ├── db.py              SQLite progress tracking
│   ├── config.py          Settings (env vars / .env)
│   ├── requirements.txt
│   └── .env.example
├── frontend/
│   ├── index.html  styles.css  app.js  mock-data.js
│   ├── manifest.json  sw.js
│   └── icons/             SVG + 192/512 PNG + maskable + Apple touch icon
├── Dockerfile
├── docker-compose.yml
└── start.bat / start.sh   one-click local start
```

## 🚀 Quick start (local)

**1. Install [Ollama](https://ollama.com/download) and pull the models**
```bash
ollama pull qwen2.5
ollama pull nomic-embed-text
```

**2. Start the backend.** It also serves the app.

- Windows: double-click **`start.bat`**
- macOS / Linux: `./start.sh`
- Manually:
  ```bash
  cd backend
  python -m venv .venv
  .venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
  pip install -r requirements.txt
  uvicorn main:app --reload --port 8000
  ```

**3. Open http://localhost:8000**. The footer should show 🟢 **Online · AI ready**. API docs are at http://localhost:8000/docs.

### With Docker (Ollama included)
```bash
docker compose up -d --build      # first run downloads the models (~5 GB)
```
Then open http://localhost:8000. Uncomment the `deploy:` blocks in `docker-compose.yml` to use an NVIDIA GPU.

## 🔌 API

| Method | Endpoint | Body / params | Returns |
|---|---|---|---|
| GET | `/health` | | Ollama status, models ready, knowledge-base size |
| POST | `/chat` | `{message, thread_id?}` | `{answer, thread_id, tools_used, sources}` |
| GET | `/news` | `?limit=8&refresh=false` | `{items:[{title, summary, source, url, time, isBreaking}]}` |
| POST | `/generate-question` | `{category, difficulty?, count, user_id?}` | `{questions:[{question, answer, keyPoints, ...}]}` |
| POST | `/generate-mcq` | `{category, count, user_id?}` | `{mcqs:[{question, options[4], correctAnswer, explanation}]}` |
| POST | `/transcribe` | multipart `file` | `{text, language, duration}` |
| POST | `/voice` | multipart `file`, `thread_id?` | transcript + agent answer |
| POST | `/knowledge/upload` | multipart `file` (.pdf/.txt/.md) | chunks indexed |
| GET | `/knowledge` | | documents in the knowledge base |
| GET / POST | `/progress/{user_id}` · `/progress` | event | score, accuracy, streak, per-category stats |

Categories: `python, ml, dl, nlp, cv, llm, mlops, ai` (or `all`).

## 🌍 Deployment: make it available everywhere

> **HTTPS matters.** Browsers only allow **PWA install, service workers and microphone access** on `https://` or `localhost`. Opening `http://192.168.x.x:8000` from your phone will load the page, but install and voice won't work. Use one of the options below.

### Web & mobile

**Option A: everything on one server (simplest).** The backend already serves the PWA, so deploy the Docker image anywhere that gives you HTTPS:
- **Your own PC/GPU box + a tunnel:** run `docker compose up` (or `start.bat`), then expose it with `cloudflared tunnel --url http://localhost:8000`, `ngrok http 8000` or **Tailscale Funnel**. You get an `https://` URL that works on any phone, with the LLM running on your own GPU.
- **Cloud VM** (any provider with a GPU, or CPU for small models): `docker compose up -d` behind Caddy/Nginx with a Let's Encrypt certificate.

**Option B: static frontend + API elsewhere.**
1. Host `frontend/` on **Netlify / Vercel / GitHub Pages**. There's no build step: just publish the folder.
2. Run the backend on **Render / Railway / Fly.io** (Docker deploy). These platforms have no GPU and are memory-limited, so point `OLLAMA_BASE_URL` at an Ollama server you control (a GPU VM or your PC through a tunnel). Running a 7B model on a small PaaS instance isn't practical.
3. Set `CORS_ORIGINS=https://your-app.netlify.app` on the backend. `*.netlify.app` and `*.vercel.app` preview URLs are already allowed by `CORS_ORIGIN_REGEX`.
4. In the app, open ⚙ **Settings → Backend URL** and enter your API URL. It's saved on the device.

**Option C: fully local.** Run Ollama and the backend on your laptop and open `http://localhost:8000`.

**Install on a phone:** open the HTTPS URL, then
- **Android (Chrome):** menu ⋮ → **Install app**, or tap the ⬇ button in the header.
- **iPhone/iPad (Safari):** Share → **Add to Home Screen**.

### Desktop (Windows / macOS)

1. **Easiest: Install as App.** In Chrome or Edge, open the app and click the install icon in the address bar (or the ⬇ button). You get a standalone window, Start-menu / Dock icon and offline support, with no extra code.
2. **Native wrapper with Tauri** (small installers, about 5–10 MB):
   ```bash
   npm create tauri-app@latest       # choose "vanilla"
   # copy frontend/* into the generated project's web folder (e.g. src/)
   # in tauri.conf.json set build.frontendDist to that folder, and allow
   # http://localhost:8000 (or your API URL) in the CSP "connect-src"
   npm run tauri build               # → .msi / .exe (Windows), .dmg / .app (macOS)
   ```
   Set the Backend URL in ⚙ Settings, since the app is no longer served by FastAPI.
3. **Electron** (bigger installers but simple): create a `BrowserWindow` that loads `http://localhost:8000` (or `file://…/frontend/index.html` plus a Backend URL), and package it with `electron-builder`. Webviews inside Electron and Tauri may not provide the Web Speech API, so the app automatically falls back to **Faster-Whisper** on the backend.

## 🔒 Privacy notes

- The LLM, embeddings, knowledge base, progress database and Whisper all run on **your** machine or server.
- Web search and news use DuckDuckGo, so search queries leave the machine.
- Chrome's built-in speech recognition sends audio to Google. Turn on **⚙ Settings → Private voice mode** to always transcribe on your own server with Whisper.

## ⚙️ Configuration

All settings live in `backend/config.py` and can be overridden with environment variables or `backend/.env` (see `.env.example`): `OLLAMA_BASE_URL`, `LLM_MODEL`, `EMBED_MODEL`, `DATA_DIR`, `WHISPER_MODEL`, `WHISPER_DEVICE`, `CORS_ORIGINS`, `CORS_ORIGIN_REGEX`, `NEWS_CACHE_TTL`, `MAX_UPLOAD_MB`.

## 🧪 Tested

Verified locally on Windows 11 with Ollama (qwen2.5 7B, nomic-embed-text) and an RTX A2000:
- **Agent tool routing:** a question about uploaded notes uses `knowledge_base_search` and cites the file; "latest PyTorch version" uses `web_search` and returns live sources; fundamentals are answered without tools.
- **Endpoints:** news, interview and MCQ generation return validated JSON (MCQ answer positions are shuffled server-side).
- **Whisper:** loads on CUDA float16 and transcribes speech accurately; `/voice` chains it into the agent.
- **Browser test** (Edge via Playwright, emulated 390×844 phone and 1280×800 desktop):
  - all four tabs work end-to-end with no horizontal overflow and no JS errors
  - the service worker is active
  - with the backend blocked, Offline Mode shows sample news and the quiz still works

Not tested here: the Docker build (Docker isn't installed on the dev machine) and installing on a real phone over HTTPS.
