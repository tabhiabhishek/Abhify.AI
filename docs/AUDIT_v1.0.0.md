# Phase 0: Audit of AI Tech Pulse v1.0.0

Audited on 2026-10-13 before any v2 change. The baseline is preserved as Git tag **`v1.0.0`** (commit `574072e`), and a copy of its data is in `D:\Abhify_Backups\ai_tech_pulse_v1.0.0_data\`.

## Environment

| Item | Found |
|---|---|
| OS | Windows 11 Pro 10.0.22631, x64 |
| Python | 3.11.9 (project venv `backend\.venv`, created from `D:\model_ai\venv`) |
| GPU | NVIDIA RTX A2000 12 GB, driver 581.42; CUDA works for Faster-Whisper (CTranslate2) |
| Ollama | 0.40.0, running; models: `qwen2.5:latest` (7B), `nomic-embed-text:latest`, `llama3.2:latest` |
| Disk | C: 33 GB free (Ollama models + %LOCALAPPDATA%), D: 304 GB free |
| Docker | **not installed**, so the Docker build is unverified |
| Git | the project was **not** under version control (now initialised) |

## Features (all verified working in v1.0.0 by the earlier end-to-end test)

News (DuckDuckGo + LLM summaries), Interview, MCQ, Chat (LangGraph agent with knowledge-base and web tools), PDF/text upload, Web Speech voice, Faster-Whisper `/transcribe` and `/voice`, offline fallback, PWA install, SQLite progress.

## Endpoints (v1.0.0)

`GET /health`, `POST /chat`, `GET /news`, `POST /generate-question`, `POST /generate-mcq`, `POST /transcribe`, `POST /voice`, `POST /knowledge/upload`, `GET /knowledge`, `GET /progress/{user_id}`, `POST /progress`, plus the static PWA at `/`.

## Data

| Store | Actual location (verified) | Content at audit |
|---|---|---|
| SQLite | `backend/data/progress.db`: one table `events`, **no schema version** | 0 rows |
| ChromaDB | `backend/data/chroma/`: collection **`knowledge_base`** (not `./tech_news_db`) | 0 embeddings |
| Browser | `localStorage` keys `aitp_*` (cached news, questions, quiz, score mirror, offline queue, thread id) | per device |

## Problems found

| # | Finding | Risk | Fixed in |
|---|---|---|---|
| 1 | User data stored **inside the program folder** (`backend/data`) | Lost or blocked in read-only installs and upgrades | M1 (Phase 3) |
| 2 | No schema versioning or migrations | Upgrades can't change the DB safely | M1 (Phase 4) |
| 3 | Chat memory was `InMemorySaver`, lost on restart | No persistent history | M1 (Phase 4) |
| 4 | Model names hard-coded via env defaults (`qwen2.5`, `nomic-embed-text`) | Breaks on weaker PCs | M1 (Phase 2) |
| 5 | No hardware detection; GPU assumed for Whisper ("auto" fallback existed) | Poor first run on other PCs | M1 (Phase 2) |
| 6 | `start.bat` bound to `0.0.0.0` (whole LAN) with no auth | Exposure on shared networks | M1 (127.0.0.1 default + local-only admin endpoints) |
| 7 | Uploading the same file twice indexed it twice | Duplicate search results | M1 (hash de-dup) |
| 8 | **No automated tests** | Regressions undetected | M1 (50 pytest tests) |
| 9 | No app version or changelog | Can't do safe updates | M1 (`version.py`, CHANGELOG) |
| 10 | Web search had no offline check (waited for timeouts) | Slow, misleading offline answers | M1 (Phase 6 slice) |
| 11 | `langchain-community` is sunset upstream (deprecation warning) | Future breakage | M1 (PDFs read with `pypdf` directly) |
| 12 | `requirements.txt` used `pip freeze`-free pins but no lock file | Non-reproducible installs | M5 (lockfile) |

## Hard-coded values found

- `localhost:11434` in config (kept as an overridable default).
- `localhost:8000` as the frontend fallback (kept).
- `qwen2.5` / `nomic-embed-text` (now profile-driven).
- `backend/data` (replaced by the OS data directory).
- `aitp_*` storage keys (migrated to `abhify_*`).
- CORS origins (extended for Tauri).
- No absolute machine paths in code. The only `D:\…` path is the venv's own `pyvenv.cfg`.
