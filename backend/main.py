"""
main.py: AI Tech Pulse FastAPI backend.

Endpoints
---------
GET  /health                 backend + Ollama status (used by the PWA's online/offline badge)
POST /chat                   chat with the LangGraph agent (knowledge base + web search)
GET  /news                   latest AI/ML news, summarized by the local LLM
POST /generate-question      AI-generated interview questions with model answers
POST /generate-mcq           AI-generated multiple-choice questions
POST /transcribe             Faster-Whisper transcription of an audio clip
POST /voice                  audio clip -> transcription -> agent answer, in one call
POST /knowledge/upload       add a PDF / .txt / .md file to the ChromaDB knowledge base
GET  /knowledge              list documents in the knowledge base
GET  /progress/{user_id}     progress summary (SQLite)
POST /progress               record an answered MCQ / viewed interview answer

If ../frontend exists it is also served at "/", so a single process (or
container) can host both the API and the PWA.

Run locally:
    uvicorn main:app --reload --port 8000
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import db
import generators
import knowledge
from agent import run_agent
from config import get_settings
from generators import GenerationError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("aitp")
settings = get_settings()


# --------------------------------------------------------------------------- app
@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    log.info("AI Tech Pulse backend ready (model=%s, ollama=%s)", settings.llm_model, settings.ollama_base_url)
    yield


app = FastAPI(title="AI Tech Pulse API", version="1.0.0", lifespan=lifespan)

# CORS: the PWA may be served from another origin (Netlify, Vercel, localhost:5500,
# an Electron/Tauri shell...). Allowed origins come from settings / env vars.
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_origin_regex=settings.cors_origin_regex,
    allow_credentials=False,          # we don't use cookies, so "*" origins stay legal
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)


def _unavailable(e: Exception) -> HTTPException:
    """503 tells the PWA to fall back to cached / offline data."""
    log.warning("Upstream unavailable: %s", e)
    return HTTPException(status_code=503, detail=str(e))


# --------------------------------------------------------------------------- schemas
class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=4000)
    thread_id: str | None = Field(None, description="Conversation id; omit to start a new one")


class ChatResponse(BaseModel):
    answer: str
    thread_id: str
    tools_used: list[str]
    sources: list[str]


class QuestionRequest(BaseModel):
    category: str = "all"
    difficulty: Literal["easy", "medium", "hard"] | None = None
    count: int = Field(3, ge=1, le=10)
    user_id: str | None = None          # used to avoid repeating seen questions
    exclude: list[str] = Field(default_factory=list)


class MCQRequest(BaseModel):
    category: str = "all"
    count: int = Field(5, ge=1, le=10)
    user_id: str | None = None
    exclude: list[str] = Field(default_factory=list)


class ProgressEvent(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=64)
    kind: Literal["mcq", "interview_view", "chat"]
    category: str | None = None
    correct: bool | None = None
    item: str | None = None


# --------------------------------------------------------------------------- health
@app.get("/health")
async def health():
    """Reports whether Ollama is reachable and which models are installed."""
    ollama_ok, models = False, []
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            r = await client.get(f"{settings.ollama_base_url}/api/tags")
            r.raise_for_status()
            models = [m["name"] for m in r.json().get("models", [])]
            ollama_ok = True
    except Exception as e:
        log.debug("Ollama health check failed: %s", e)

    def has(name: str) -> bool:
        return any(m.split(":")[0] == name.split(":")[0] for m in models)

    return {
        "status": "ok" if ollama_ok else "degraded",
        "ollama": ollama_ok,
        "llm_model": settings.llm_model,
        "llm_ready": has(settings.llm_model),
        "embed_model": settings.embed_model,
        "embed_ready": has(settings.embed_model),
        "knowledge_chunks": await asyncio.to_thread(knowledge.count),
    }


# --------------------------------------------------------------------------- chat
@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest):
    thread_id = req.thread_id or uuid.uuid4().hex
    try:
        result = await run_agent(req.message.strip(), thread_id)
    except Exception as e:
        raise _unavailable(e)
    return ChatResponse(thread_id=thread_id, **result)


# --------------------------------------------------------------------------- content
@app.get("/news")
async def news(limit: int = 8, refresh: bool = False):
    try:
        items = await generators.get_news(limit=min(max(limit, 1), 15), refresh=refresh)
    except GenerationError as e:
        raise _unavailable(e)
    return {"items": items, "generated": True}


@app.post("/generate-question")
async def generate_question(req: QuestionRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "interview_view")
    try:
        qs = await generators.generate_questions(req.category, req.difficulty, req.count, exclude)
    except GenerationError as e:
        raise _unavailable(e)
    return {"questions": qs}


@app.post("/generate-mcq")
async def generate_mcq(req: MCQRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "mcq")
    try:
        mcqs = await generators.generate_mcqs(req.category, req.count, exclude)
    except GenerationError as e:
        raise _unavailable(e)
    return {"mcqs": mcqs}


# --------------------------------------------------------------------------- voice
async def _read_audio(file: UploadFile) -> bytes:
    data = await file.read()
    if not data:
        raise HTTPException(400, "Empty audio file")
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "Audio clip too large (max 25 MB)")
    return data


@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...), language: str | None = Form(None)):
    from transcribe import transcribe          # lazy: keeps startup fast
    audio = await _read_audio(file)
    try:
        return await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _unavailable(e)


@app.post("/voice")
async def voice(file: UploadFile = File(...), thread_id: str | None = Form(None), language: str | None = Form(None)):
    """Audio in, agent answer out: transcribe with Whisper, then run the agent."""
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        tr = await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _unavailable(e)
    if not tr["text"]:
        return {"transcript": "", "answer": "I didn't catch that. Please try again.", "thread_id": thread_id}
    tid = thread_id or uuid.uuid4().hex
    try:
        result = await run_agent(tr["text"], tid)
    except Exception as e:
        raise _unavailable(e)
    return {"transcript": tr["text"], "thread_id": tid, **result}


# --------------------------------------------------------------------------- knowledge base
@app.post("/knowledge/upload")
async def upload_knowledge(file: UploadFile = File(...)):
    name = Path(file.filename or "upload").name
    data = await file.read()
    if len(data) > settings.max_upload_mb * 1024 * 1024:
        raise HTTPException(413, f"File too large (max {settings.max_upload_mb} MB)")
    suffix = Path(name).suffix.lower()
    try:
        if suffix == ".pdf":
            chunks = await asyncio.to_thread(knowledge.ingest_pdf, data, name)
        elif suffix in {".txt", ".md", ".markdown"}:
            chunks = await asyncio.to_thread(knowledge.ingest_text, data.decode("utf-8", errors="ignore"), name)
        else:
            raise HTTPException(415, "Only .pdf, .txt and .md files are supported")
    except HTTPException:
        raise
    except Exception as e:
        raise _unavailable(e)
    if chunks == 0:
        raise HTTPException(422, "No text could be extracted (scanned PDFs need OCR first)")
    return {"filename": name, "chunks": chunks, "total_chunks": await asyncio.to_thread(knowledge.count)}


@app.get("/knowledge")
async def list_knowledge():
    return {
        "documents": await asyncio.to_thread(knowledge.list_sources),
        "chunks": await asyncio.to_thread(knowledge.count),
    }


# --------------------------------------------------------------------------- progress
@app.get("/progress/{user_id}")
def progress(user_id: str):
    return db.get_summary(user_id)


@app.post("/progress")
def record_progress(ev: ProgressEvent):
    db.record_event(ev.user_id, ev.kind, ev.category, ev.correct, ev.item)
    return db.get_summary(ev.user_id)


# --------------------------------------------------------------------------- static PWA
# Mounted last so API routes take precedence.
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.is_dir():
    app.mount("/", StaticFiles(directory=FRONTEND_DIR, html=True), name="frontend")
