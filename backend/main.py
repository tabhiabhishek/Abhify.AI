"""
main.py: Abhify.AI FastAPI backend.

Learning features
  GET  /health                    status for the PWA's online badge (app version, Ollama, models, internet)
  POST /chat                      agent chat (documents, memory, web); history stored in SQLite
  GET  /news                      live AI/ML news summarized by the local LLM (needs internet)
  POST /generate-question         AI-generated interview questions
  POST /generate-mcq              AI-generated multiple-choice questions
  POST /transcribe                Faster-Whisper transcription
  POST /voice                     audio -> transcription -> agent answer
  POST /knowledge/upload          add a PDF / .txt / .md to the knowledge base (de-duplicated)
  GET  /knowledge                 documents in the knowledge base
  GET  /progress/{user_id}        progress summary;   POST /progress  record an event

Personal memory & history
  GET/POST /memory                list / add memories      PATCH/DELETE /memory/{id}
  GET/PUT  /preferences           user name, memory on/off, history retention
  GET  /conversations             list   GET/DELETE /conversations/{id}

Setup & system (admin endpoints marked *: local machine only)
  GET  /system/diagnostics        hardware, Ollama, storage, versions, recommended profile
  GET  /setup/profiles            model profiles + disk check per profile
  POST /setup/profile *           choose a profile (changes settings.json)
  POST /setup/models/pull *       download a model (needs confirm=true and enough disk)
  GET  /setup/models/pull         download progress
  POST /setup/test                quick local inference test
  POST /setup/complete *          mark first-run setup as done
  GET  /backup                    list backups;  POST /backup * create one
  GET  /backup/export *           download a fresh backup zip
  POST /backup/restore *          upload a backup zip; applied on next start
  POST /system/data-location *    move user data to another folder (restart required)

The PWA in ../frontend is served at "/".
Run:  uvicorn main:app --host 127.0.0.1 --port 8000
"""
from __future__ import annotations

import asyncio
import logging
import tempfile
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import database as db
import generators
import knowledge
import local_file_service as lfs
import memory_service as mem
import system_service as system
from agent import run_agent
from config import get_settings
from generators import GenerationError
from version import APP_NAME, __version__

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("abhify")
settings = get_settings()
STARTUP: dict = {}


# --------------------------------------------------------------------------- startup
def prepare_storage() -> dict:
    """Order matters: layout -> pending restore -> v1 data adoption -> schema migrations."""
    info: dict = {"data_root": str(lfs.ensure_layout())}
    info["restore"] = lfs.apply_pending_restore()
    info["legacy_migration"] = lfs.migrate_legacy_data()
    info["schema"] = db.migrate()
    info["purged_conversations"] = mem.purge_expired_history()
    _add_file_log()
    return info


def _add_file_log() -> None:
    from logging.handlers import RotatingFileHandler
    root_logger = logging.getLogger()
    if any(isinstance(h, RotatingFileHandler) for h in root_logger.handlers):
        return
    h = RotatingFileHandler(lfs.logs_dir() / "abhify.log", maxBytes=2_000_000, backupCount=3, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root_logger.addHandler(h)


@asynccontextmanager
async def lifespan(_: FastAPI):
    STARTUP.update(prepare_storage())
    log.info("%s %s ready: data=%s schema=v%s models=%s", APP_NAME, __version__, STARTUP["data_root"],
             STARTUP["schema"]["to"], system.active_models())
    yield


app = FastAPI(title=f"{APP_NAME} API", version=__version__, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_origin_regex=settings.cors_origin_regex,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


def local_only(request: Request) -> None:
    """Admin endpoints change data on disk or download GBs: only the local machine may call them."""
    host = request.client.host if request.client else ""
    if settings.allow_remote_admin or host in ("127.0.0.1", "::1", "localhost", "testclient"):
        return
    raise HTTPException(403, "This action is only allowed from the computer running Abhify.AI")


def _unavailable(e: Exception) -> HTTPException:
    """503 tells the PWA to fall back to cached / offline data."""
    log.warning("Upstream unavailable: %s", type(e).__name__)
    return HTTPException(status_code=503, detail=str(e))


# --------------------------------------------------------------------------- schemas
class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=4000)
    thread_id: str | None = Field(None, max_length=64, description="Conversation id; omit to start a new one")


class QuestionRequest(BaseModel):
    category: str = "all"
    difficulty: Literal["easy", "medium", "hard"] | None = None
    count: int = Field(3, ge=1, le=10)
    user_id: str | None = None
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


class MemoryIn(BaseModel):
    content: str = Field(..., min_length=1, max_length=500)
    category: str = "general"
    pinned: bool = False


class MemoryPatch(BaseModel):
    content: str | None = Field(None, min_length=1, max_length=500)
    category: str | None = None
    pinned: bool | None = None


class PrefsIn(BaseModel):
    user_name: str | None = Field(None, max_length=40)
    memory_enabled: bool | None = None
    save_history: bool | None = None
    history_retention_days: int | None = Field(None, ge=0, le=3650)


class ProfileIn(BaseModel):
    profile: Literal["minimal", "balanced", "quality"]


class PullIn(BaseModel):
    model: str = Field(..., min_length=1, max_length=100, pattern=r"^[A-Za-z0-9._:\-/]+$")
    confirm: bool = False


class LocationIn(BaseModel):
    path: str = Field(..., min_length=3, max_length=400)


# --------------------------------------------------------------------------- health
@app.get("/health")
async def health():
    st = await system.ollama_status()
    names = [m["name"] for m in st["models"]]
    models = system.active_models()
    return {
        "app": APP_NAME, "version": __version__, "schema_version": db.SCHEMA_VERSION,
        "status": "ok" if st["reachable"] else "degraded",
        "ollama": st["reachable"],
        "llm_model": models["llm"], "llm_ready": system.model_installed(models["llm"], names),
        "embed_model": models["embed"], "embed_ready": system.model_installed(models["embed"], names),
        "internet": await asyncio.to_thread(system.internet_available),
        "setup_completed": lfs.load_settings().get("setup_completed", False),
        "knowledge_chunks": await asyncio.to_thread(knowledge.count) if st["reachable"] else None,
    }


# --------------------------------------------------------------------------- chat
@app.post("/chat")
async def chat(req: ChatRequest):
    try:
        result = await run_agent(req.message.strip(), req.thread_id)
    except Exception as e:
        raise _unavailable(e)
    return {**result, "thread_id": result["conversation_id"]}


# --------------------------------------------------------------------------- content
@app.get("/news")
async def news(limit: int = 8, refresh: bool = False):
    try:
        items = await generators.get_news(limit=min(max(limit, 1), 15), refresh=refresh)
    except GenerationError as e:
        raise _unavailable(e)
    return {"items": items, "generated": True, "updated_at": generators.news_cache_time()}


@app.post("/generate-question")
async def generate_question(req: QuestionRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "interview_view")
    try:
        return {"questions": await generators.generate_questions(req.category, req.difficulty, req.count, exclude)}
    except GenerationError as e:
        raise _unavailable(e)


@app.post("/generate-mcq")
async def generate_mcq(req: MCQRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "mcq")
    try:
        return {"mcqs": await generators.generate_mcqs(req.category, req.count, exclude)}
    except GenerationError as e:
        raise _unavailable(e)


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
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        return await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _unavailable(e)


@app.post("/voice")
async def voice(file: UploadFile = File(...), thread_id: str | None = Form(None), language: str | None = Form(None)):
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        tr = await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _unavailable(e)
    if not tr["text"]:
        return {"transcript": "", "answer": "I didn't catch that. Please try again.", "thread_id": thread_id}
    try:
        result = await run_agent(tr["text"], thread_id)
    except Exception as e:
        raise _unavailable(e)
    return {"transcript": tr["text"], "thread_id": result["conversation_id"], **result}


# --------------------------------------------------------------------------- knowledge base
@app.post("/knowledge/upload")
async def upload_knowledge(file: UploadFile = File(...)):
    name = Path(file.filename or "upload").name
    if Path(name).suffix.lower() not in {".pdf", ".txt", ".md", ".markdown"}:
        raise HTTPException(415, "Only .pdf, .txt and .md files are supported")
    data = await file.read()
    if len(data) > settings.max_upload_mb * 1024 * 1024:
        raise HTTPException(413, f"File too large (max {settings.max_upload_mb} MB)")
    try:
        r = await asyncio.to_thread(knowledge.add_upload, data, name)
    except Exception as e:
        raise _unavailable(e)
    if r["chunks"] == 0:
        raise HTTPException(422, "No text could be extracted (scanned PDFs need OCR first)")
    return {**r, "total_chunks": await asyncio.to_thread(knowledge.count)}


@app.get("/knowledge")
async def list_knowledge():
    return {"documents": await asyncio.to_thread(db.list_documents),
            "chunks": await asyncio.to_thread(knowledge.count)}


# --------------------------------------------------------------------------- progress
@app.get("/progress/{user_id}")
def progress(user_id: str):
    return db.get_summary(user_id)


@app.post("/progress")
def record_progress(ev: ProgressEvent):
    db.record_event(ev.user_id, ev.kind, ev.category, ev.correct, ev.item)
    return db.get_summary(ev.user_id)


# --------------------------------------------------------------------------- memory & preferences
@app.get("/memory")
def memories():
    return {"memories": mem.list_memories(), "enabled": db.get_prefs()["memory_enabled"]}


@app.post("/memory")
def add_memory(m: MemoryIn):
    try:
        return mem.add_memory(m.content, m.category, source="user", pinned=m.pinned)
    except ValueError as e:
        raise HTTPException(422, str(e))


@app.patch("/memory/{memory_id}")
def edit_memory(memory_id: int, m: MemoryPatch):
    try:
        out = mem.update_memory(memory_id, m.content, m.category, m.pinned)
    except ValueError as e:
        raise HTTPException(422, str(e))
    if not out:
        raise HTTPException(404, "Memory not found")
    return out


@app.delete("/memory/{memory_id}")
def remove_memory(memory_id: int):
    if not mem.delete_memory(memory_id):
        raise HTTPException(404, "Memory not found")
    return {"deleted": memory_id}


@app.delete("/memory")
def remove_all_memories(confirm: bool = False):
    if not confirm:
        raise HTTPException(400, "Add ?confirm=true to delete all memories")
    return {"deleted": mem.delete_all_memories()}


@app.get("/preferences")
def get_preferences():
    return db.get_prefs()


@app.put("/preferences")
def put_preferences(p: PrefsIn):
    return db.set_prefs(**p.model_dump(exclude_none=True))


# --------------------------------------------------------------------------- conversations
@app.get("/conversations")
def conversations():
    return {"conversations": mem.list_conversations()}


@app.get("/conversations/{conversation_id}")
def conversation(conversation_id: str, limit: int = 100):
    return {"id": conversation_id, "messages": mem.recent_messages(conversation_id, limit=min(limit, 500))}


@app.delete("/conversations/{conversation_id}")
def delete_conversation(conversation_id: str):
    if not mem.delete_conversation(conversation_id):
        raise HTTPException(404, "Conversation not found")
    return {"deleted": conversation_id}


@app.delete("/conversations")
def delete_all_conversations(confirm: bool = False):
    if not confirm:
        raise HTTPException(400, "Add ?confirm=true to delete all conversations")
    return {"deleted": mem.delete_all_conversations()}


# --------------------------------------------------------------------------- setup & system
@app.get("/system/diagnostics")
async def diagnostics():
    d = await system.diagnostics()
    d["startup"] = STARTUP
    return d


@app.get("/setup/profiles")
async def profiles():
    st = await system.ollama_status()
    names = [m["name"] for m in st["models"]]
    out = {}
    for key, p in system.PROFILES.items():
        missing = [m for m in (p["llm"], p["embed"]) if not system.model_installed(m, names)]
        out[key] = {**p, "installed": not missing, "missing": missing,
                    "disk": [system.check_disk_for(m) for m in missing]}
    return {"profiles": out, "active": lfs.load_settings().get("model_profile"),
            "active_models": system.active_models()}


@app.post("/setup/profile", dependencies=[Depends(local_only)])
def choose_profile(p: ProfileIn):
    return system.apply_profile(p.profile)


@app.post("/setup/models/pull", dependencies=[Depends(local_only)])
async def pull_model(p: PullIn):
    try:
        return await system.start_pull(p.model, p.confirm)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.get("/setup/models/pull")
def pull_status():
    return {"pulls": system.PULLS}


@app.post("/setup/test")
async def test_model():
    return await system.test_inference()


@app.post("/setup/complete", dependencies=[Depends(local_only)])
def complete_setup():
    return lfs.update_settings(setup_completed=True)


# --------------------------------------------------------------------------- backup / data location
@app.get("/backup")
def backups():
    return {"backups": lfs.list_backups(), "data_root": str(lfs.root())}


@app.post("/backup", dependencies=[Depends(local_only)])
def make_backup():
    out = lfs.create_backup("manual", schema_version=db.SCHEMA_VERSION)
    return {"file": out.name, "size": out.stat().st_size}


@app.get("/backup/export", dependencies=[Depends(local_only)])
def export_backup():
    out = lfs.create_backup("export", schema_version=db.SCHEMA_VERSION)
    return FileResponse(out, filename=out.name, media_type="application/zip")


@app.post("/backup/restore", dependencies=[Depends(local_only)])
async def restore_backup(file: UploadFile = File(...)):
    with tempfile.NamedTemporaryFile(suffix=".zip", delete=False, dir=lfs.cache_dir()) as tmp:
        while chunk := await file.read(1024 * 1024):
            tmp.write(chunk)
        tmp_path = Path(tmp.name)
    try:
        manifest = await asyncio.to_thread(lfs.stage_restore, tmp_path, db.SCHEMA_VERSION)
    except (ValueError, OSError) as e:
        raise HTTPException(400, f"Backup rejected: {e}")
    finally:
        tmp_path.unlink(missing_ok=True)
    return {"staged": manifest, "restart_required": True,
            "note": "Restart Abhify.AI to apply. Your current data will be kept in backups/pre-restore-*."}


@app.post("/system/data-location", dependencies=[Depends(local_only)])
async def move_data(loc: LocationIn):
    try:
        return await asyncio.to_thread(lfs.relocate_data_dir, Path(loc.path))
    except (ValueError, OSError) as e:
        raise HTTPException(400, str(e))


# --------------------------------------------------------------------------- static PWA
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.is_dir():
    app.mount("/", StaticFiles(directory=FRONTEND_DIR, html=True), name="frontend")
