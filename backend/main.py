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

Setup & system (all local machine only; companion.REMOTE_ROUTES lists what a paired phone may use)
  GET  /system/diagnostics        hardware, Ollama, storage, versions, recommended profile
  GET  /setup/profiles            model profiles + disk check per profile
  POST /setup/profile             choose a profile (changes settings.json)
  POST /setup/models/pull         download a model (needs confirm=true and enough disk)
  GET  /setup/models/pull         download progress
  POST /setup/test                quick local inference test
  POST /setup/complete            mark first-run setup as done
  GET  /backup                    list backups;  POST /backup  create one
  GET  /backup/export             download a fresh backup zip
  POST /backup/restore            upload a backup zip; applied on next start
  POST /system/data-location      move user data to another folder (restart required)
  GET  /system/update-check       opt-in HTTPS update manifest check (never installs)
  POST /system/shutdown           stop the app (desktop launcher only)

Knowledge folder (M2)
  GET  /knowledge/status          watcher + index status     GET /knowledge/files  per-file status
  local only: PUT /knowledge/folder, POST /knowledge/watcher/{start,stop,pause,resume},
              POST /knowledge/rescan, /knowledge/retry, /knowledge/rebuild, /knowledge/rebuild/cleanup

Companion access (Mode B) and membership (dev.6, see membership.py)
  GET  /companion                 pairing status (open)      POST /companion/enable|disable (local only)
  POST /companion/redeem          phone redeems a single-use invitation (open, rate-limited)
  POST /companion/register        self-registration as a member, only in public mode (open, rate-limited)
  local only: GET /companion/admin, GET/POST /companion/invitations, POST /companion/invitations/{id}/revoke,
              GET /companion/members, POST /companion/members/{id}/revoke,
              POST /companion/registration-mode, POST /companion/lan-access

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

import companion
import database as db
import document_indexer
import generators
import knowledge
import local_file_service as lfs
import log_redaction
import membership
import memory_service as mem
import system_service as system
from agent import run_agent
from config import get_settings
from generators import GenerationError
from folder_watcher import watcher
from version import APP_NAME, __version__

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log_redaction.install()                     # no tokens / secret query values in any log (F3)
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
    info["legacy_companion_token_migrated"] = membership.migrate_legacy_token()   # dev.5 -> dev.6
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
    log_redaction.install(h)


SHUTDOWN_HOOK: dict = {}       # the desktop launcher registers a callable here


@asynccontextmanager
async def lifespan(_: FastAPI):
    log_redaction.install()                 # again: uvicorn may have added handlers after import
    STARTUP.update(prepare_storage())
    log.info("%s %s ready: data=%s schema=v%s models=%s", APP_NAME, __version__, STARTUP["data_root"],
             STARTUP["schema"]["to"], system.active_models())
    if lfs.load_settings()["knowledge"].get("watcher_enabled"):
        try:
            await asyncio.to_thread(_start_watcher)
        except Exception as e:                    # a bad folder setting must never stop the app
            log.warning("Knowledge watcher not started: %s", e)
    yield
    watcher.stop()                 # clean shutdown of the observer + worker threads


def _start_watcher() -> dict:
    folder = lfs.validate_knowledge_folder(lfs.knowledge_folder())
    return watcher.start(folder)


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
    """Admin and system endpoints (they change data on disk, download GBs, or reveal paths, hardware and
    settings) are for the local machine only. Requests relayed by a tunnel/proxy (they arrive on
    127.0.0.1 with forwarding headers) count as remote."""
    if settings.allow_remote_admin or companion.is_local_request(request):
        return
    raise HTTPException(403, companion.LOCAL_ONLY_MESSAGE)


@app.middleware("http")
async def companion_guard(request: Request, call_next):
    """Mode B (phone -> this PC): remote API calls need companion access enabled AND a valid token.
    Static PWA files are always served so the app shell can load and explain what's missing."""
    denied = companion.check(request, settings.allow_remote_admin)
    if denied:
        from fastapi.responses import JSONResponse
        return JSONResponse({"detail": denied[1]}, status_code=denied[0])
    return await call_next(request)


def _unavailable(e: Exception) -> HTTPException:
    """503 tells the PWA to fall back to cached / offline data. Paths are redacted (they contain the
    Windows user name) because these errors also reach paired phones."""
    log.warning("Upstream unavailable: %s", type(e).__name__)
    return HTTPException(status_code=503, detail=companion.redact_text(str(e)))


def _public_docs(request: Request, docs: list[dict]) -> list[dict]:
    """Document rows for a remote (paired phone) caller: no absolute paths, no path-bearing errors."""
    if not companion.is_remote(request):
        return docs
    return [{**{k: v for k, v in d.items() if k != "path_key"}, "error": companion.redact_text(d.get("error"))}
            for d in docs]


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


class FolderIn(BaseModel):
    path: str = Field(..., min_length=3, max_length=400)


class ConfirmIn(BaseModel):
    confirm: bool = False


# --------------------------------------------------------------------------- health
@app.get("/ping")
def ping():
    """Instant liveness + identity check (no Ollama or network probes). Used by the desktop launcher."""
    return {"app": APP_NAME, "version": __version__}


@app.get("/health")
async def health(request: Request):
    st = await system.ollama_status()
    names = [m["name"] for m in st["models"]]
    models = system.active_models()
    return companion.health_view({
        "app": APP_NAME, "version": __version__, "schema_version": db.SCHEMA_VERSION,
        "status": "ok" if st["reachable"] else "degraded",
        "ollama": st["reachable"],
        "llm_model": models["llm"], "llm_ready": system.model_installed(models["llm"], names),
        "embed_model": models["embed"], "embed_ready": system.model_installed(models["embed"], names),
        "internet": await asyncio.to_thread(system.internet_available),
        "setup_completed": lfs.load_settings().get("setup_completed", False),
        "knowledge_chunks": await asyncio.to_thread(knowledge.count) if st["reachable"] else None,
    }, request)


# --------------------------------------------------------------------------- chat
@app.post("/chat")
async def chat(req: ChatRequest, request: Request):
    guest = companion.role_of(request) == "member"           # members never touch the owner's data
    try:
        result = await (run_agent(req.message.strip(), None, guest=True) if guest
                        else run_agent(req.message.strip(), req.thread_id))
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
async def voice(request: Request, file: UploadFile = File(...), thread_id: str | None = Form(None),
                language: str | None = Form(None)):
    guest = companion.role_of(request) == "member"
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        tr = await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _unavailable(e)
    if not tr["text"]:
        return {"transcript": "", "answer": "I didn't catch that. Please try again.", "thread_id": thread_id}
    try:
        result = await (run_agent(tr["text"], None, guest=True) if guest else run_agent(tr["text"], thread_id))
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
async def list_knowledge(request: Request):
    return {"documents": _public_docs(request, await asyncio.to_thread(db.list_documents)),
            "chunks": await asyncio.to_thread(knowledge.count)}


# ---- watched knowledge folder (M2)
def _index_info() -> dict:
    k = lfs.load_settings()["knowledge"]
    return {"collection": knowledge.active_collection(), "index_embed_model": knowledge.index_embed_model(),
            "configured_embed_model": knowledge.configured_embed_model(),
            "rebuild_required": knowledge.rebuild_required(), "rebuild": dict(knowledge.REBUILD),
            "previous_collections": k.get("previous_collections", []), "watcher_enabled": k.get("watcher_enabled"),
            "default_folder": str(lfs.default_knowledge_dir())}


@app.get("/knowledge/status")
async def knowledge_status(request: Request):
    st = await asyncio.to_thread(watcher.status)
    st["folder"] = st["folder"] or str(lfs.knowledge_folder())
    out = {**st, **await asyncio.to_thread(_index_info), "chunks": await asyncio.to_thread(knowledge.count)}
    if companion.is_remote(request):                 # folder name only; no user-profile paths
        out.update(folder=companion.public_name(out["folder"]), default_folder=None,
                   last_error=companion.redact_text(out.get("last_error")))
    return out


@app.get("/knowledge/files")
async def knowledge_files(request: Request, status: str | None = None, source: str | None = "watcher"):
    return {"files": _public_docs(request, await asyncio.to_thread(db.list_documents, source, status))}


@app.put("/knowledge/folder", dependencies=[Depends(local_only)])
async def set_knowledge_folder(f: FolderIn):
    try:
        folder = lfs.validate_knowledge_folder(Path(f.path))
    except ValueError as e:
        raise HTTPException(400, str(e))
    old = lfs.knowledge_folder()
    was_running = watcher.running
    await asyncio.to_thread(watcher.stop)
    if not await asyncio.to_thread(watcher.drain):           # never remove docs while one is still being written
        if was_running:
            await asyncio.to_thread(_start_watcher)          # nothing changed: keep watching the old folder
        raise HTTPException(409, "The watcher is still finishing a large file. Try again in a moment.")
    lfs.update_settings(knowledge={"folder": str(folder)})
    # Files from the previous folder are no longer inside the knowledge folder: remove their chunks.
    removed = 0
    if folder != old.resolve():
        for d in await asyncio.to_thread(db.list_documents, "watcher"):
            await asyncio.to_thread(document_indexer.remove_file, d["path_key"])
            removed += 1
    if was_running:
        await asyncio.to_thread(_start_watcher)
    return {"folder": str(folder), "removed_from_old_folder": removed, "watching": watcher.running}


@app.post("/knowledge/watcher/start", dependencies=[Depends(local_only)])
async def start_watcher():
    try:
        st = await asyncio.to_thread(_start_watcher)
    except ValueError as e:
        raise HTTPException(400, str(e))
    lfs.update_settings(knowledge={"watcher_enabled": True})
    return st


@app.post("/knowledge/watcher/stop", dependencies=[Depends(local_only)])
async def stop_watcher():
    await asyncio.to_thread(watcher.stop)
    lfs.update_settings(knowledge={"watcher_enabled": False})
    return watcher.status()


@app.post("/knowledge/watcher/pause", dependencies=[Depends(local_only)])
def pause_watcher():
    watcher.pause()
    return watcher.status()


@app.post("/knowledge/watcher/resume", dependencies=[Depends(local_only)])
def resume_watcher():
    watcher.resume()
    return watcher.status()


@app.post("/knowledge/rescan", dependencies=[Depends(local_only)])
def rescan_knowledge():
    if not watcher.running:
        raise HTTPException(409, "Start the watcher first")
    watcher.request_rescan()
    return watcher.status()


@app.post("/knowledge/retry", dependencies=[Depends(local_only)])
def retry_knowledge(doc_id: int | None = None):
    n = watcher.retry_failed(doc_id)
    return {"reset": n, **watcher.status()}


@app.post("/knowledge/rebuild", dependencies=[Depends(local_only)])
async def rebuild_knowledge(c: ConfirmIn):
    if not c.confirm:
        raise HTTPException(400, "Rebuilding re-embeds every document; send confirm=true")
    if knowledge.REBUILD.get("status") == "running":
        raise HTTPException(409, "A rebuild is already running")
    was_running = watcher.running
    watcher.pause()

    def job():
        try:
            knowledge.rebuild_index()
        finally:
            if was_running:
                watcher.resume()
                watcher.request_rescan()
    asyncio.get_running_loop().run_in_executor(None, job)
    return {"started": True, "model": knowledge.configured_embed_model()}


@app.post("/knowledge/rebuild/cleanup", dependencies=[Depends(local_only)])
def cleanup_old_indexes():
    return {"removed": knowledge.delete_previous_collections()}


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
@app.get("/system/diagnostics", dependencies=[Depends(local_only)])
async def diagnostics():
    d = await system.diagnostics()
    d["startup"] = STARTUP
    return d


@app.get("/setup/profiles", dependencies=[Depends(local_only)])
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


@app.get("/setup/models/pull", dependencies=[Depends(local_only)])
def pull_status():
    return {"pulls": system.PULLS}


@app.post("/setup/test", dependencies=[Depends(local_only)])
async def test_model():
    return await system.test_inference()


@app.post("/setup/complete", dependencies=[Depends(local_only)])
def complete_setup():
    return lfs.update_settings(setup_completed=True)


# --------------------------------------------------------------------------- backup / data location
@app.get("/backup", dependencies=[Depends(local_only)])
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


# --------------------------------------------------------------------------- companion access (Mode B)
@app.get("/companion")
def companion_status(request: Request):
    return companion.status(request)


class RedeemIn(BaseModel):
    code: str = Field(..., min_length=1, max_length=128)
    device_name: str = Field("Phone", max_length=60)


class RegisterIn(BaseModel):
    device_name: str = Field("Phone", max_length=60)


class InvitationIn(BaseModel):
    label: str = Field("", max_length=60)
    role: Literal["owner_device", "member"] = "member"
    hours: int = Field(72, ge=1, le=720)
    device_days: int | None = Field(None, ge=0, le=3650)


class ModeIn(BaseModel):
    mode: Literal["invite_only", "public"]
    confirm: bool = False


class LanIn(BaseModel):
    enabled: bool
    confirm: bool = False


def _client_key(request: Request) -> str:
    """Rate-limit key. Forwarded-for is only a hint (spoofable); the global limit backs it up."""
    fwd = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    return f"{request.client.host if request.client else '?'}|{fwd}"


def _pairing_error(e: membership.MembershipError) -> HTTPException:
    if isinstance(e, membership.RateLimited):
        return HTTPException(429, str(e), headers={"Retry-After": str(e.retry_after)})
    return HTTPException(400, str(e))


@app.post("/companion/redeem")
def companion_redeem(r: RedeemIn, request: Request):
    """A phone turns a single-use invitation into its own device credential (returned once)."""
    try:
        return membership.redeem(r.code, r.device_name, _client_key(request))
    except membership.MembershipError as e:
        raise _pairing_error(e)


@app.post("/companion/register")
def companion_register(r: RegisterIn, request: Request):
    """Public mode only: register this device as a restricted member."""
    try:
        return membership.register_public(r.device_name, _client_key(request))
    except membership.MembershipError as e:
        raise _pairing_error(e)


@app.get("/companion/admin", dependencies=[Depends(local_only)])
def companion_admin():
    """Everything the owner's membership screen shows. No secrets, no hashes."""
    cfg = lfs.load_settings()["companion"]
    return {"enabled": bool(cfg.get("enabled")), "registration_mode": membership.get_mode(),
            **companion.connection_info(), "members": membership.list_members(),
            "invitations": membership.list_invitations()}


@app.get("/companion/invitations", dependencies=[Depends(local_only)])
def list_invitations():
    return {"invitations": membership.list_invitations()}


@app.post("/companion/invitations", dependencies=[Depends(local_only)])
def create_invitation(inv: InvitationIn):
    if not lfs.load_settings()["companion"].get("enabled"):
        raise HTTPException(409, "Turn on companion access first")
    try:
        out = membership.create_invitation(inv.label, inv.role, inv.hours, inv.device_days)
    except membership.MembershipError as e:
        raise HTTPException(400, str(e))
    return {**out, **companion.connection_info()}


@app.post("/companion/invitations/{inv_id}/revoke", dependencies=[Depends(local_only)])
def revoke_invitation(inv_id: int):
    if not membership.revoke_invitation(inv_id):
        raise HTTPException(404, "No open invitation with that id")
    return {"revoked": inv_id}


@app.get("/companion/members", dependencies=[Depends(local_only)])
def list_members():
    return {"members": membership.list_members()}


@app.post("/companion/members/{member_id}/revoke", dependencies=[Depends(local_only)])
def revoke_member(member_id: int):
    if not membership.revoke_member(member_id):
        raise HTTPException(404, "No active device with that id")
    return {"revoked": member_id}


@app.post("/companion/registration-mode", dependencies=[Depends(local_only)])
def set_registration_mode(m: ModeIn):
    try:
        return membership.set_mode(m.mode, m.confirm)
    except membership.MembershipError as e:
        raise HTTPException(400, str(e))


@app.post("/companion/lan-access", dependencies=[Depends(local_only)])
def set_lan_access(l: LanIn):
    """Listen on all network interfaces (plain Wi-Fi HTTP) after the next restart. Off by default:
    Tailscale Serve only needs 127.0.0.1."""
    if l.enabled and not l.confirm:
        raise HTTPException(400, "Allowing local-network access needs explicit confirmation (confirm=true).")
    lfs.update_settings(companion={"lan_access": l.enabled})
    return {"lan_access": l.enabled, "restart_required": l.enabled != companion.listening_on_lan()}


@app.post("/companion/enable", dependencies=[Depends(local_only)])
def companion_enable():
    return companion.enable()


@app.post("/companion/disable", dependencies=[Depends(local_only)])
def companion_disable():
    return companion.disable()


# --------------------------------------------------------------------------- updates & lifecycle
@app.get("/system/update-check", dependencies=[Depends(local_only)])
async def update_check():
    return await system.check_for_update()


@app.post("/system/shutdown", dependencies=[Depends(local_only)])
def shutdown():
    hook = SHUTDOWN_HOOK.get("fn")
    if not hook:
        raise HTTPException(501, "Not started by the Abhify.AI launcher; stop the server from its window")
    hook()
    return {"stopping": True}


# --------------------------------------------------------------------------- static PWA
companion.register_api_routes(r.path for r in app.routes if hasattr(r, "methods"))

FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"
if FRONTEND_DIR.is_dir():
    app.mount("/", StaticFiles(directory=FRONTEND_DIR, html=True), name="frontend")
