"""
main.py: Abhify.AI FastAPI backend.

Learning features
  GET  /health                    status for the PWA's online badge (app version, Ollama, models, internet)
  POST /chat                      agent chat (documents, memory, web); history stored in SQLite
  GET  /news                      live AI/ML news summarized by the local LLM (needs internet)
  POST /generate-question         AI-generated interview questions
  POST /generate-mcq              AI-generated multiple-choice questions
  POST /transcribe                Faster-Whisper transcription
  GET  /providers                 7.1: cloud AI providers (Gemini, Groq, OpenRouter, custom): state, masked key, models
  PUT  /providers/{id}/key        7.1: test and save a key (DPAPI-encrypted); POST /providers/{id}/test; PUT/DELETE /providers/{id}
  GET  /voice/status              7.1: local speech engine status (model installed, download size/progress)
  POST /voice/model/download      7.1: download the speech model, only with confirm (this computer only)
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
  GET  /setup/plan                automatic setup plan (components, sizes, disk); downloads nothing
  POST /setup/auto  GET /setup/auto  POST /setup/auto/pause|cancel   automatic setup job (setup_manager.py)
  GET  /backup                    list backups;  POST /backup  create one
  GET  /backup/export             download a fresh backup zip
  POST /backup/restore            upload a backup zip; applied on next start
  POST /system/data-location      move user data to another folder (restart required)
  GET  /system/update-check       update check (signed manifest; same as POST /updates/check)
  GET  /updates  POST /updates/check|download|install  POST /updates/models/check|apply|remove-backup
                                  update manager (updates.py); install and model updates need confirm=true
  POST /system/shutdown           stop the app (desktop launcher only)

Live information (Real-Time Intelligence Engine, realtime.py; all local machine only)
  GET  /realtime/status           connector states (news, web, weather, fx, projects, tasks, system) and settings; never keys
  PUT  /realtime/settings         enabled, news, web_provider (ddgs|brave|tavily|none), weather, fx, place
  POST /realtime/lookup           {q, refresh}: the same `live` structure /chat returns, without calling the model

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

from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

import companion
import database as db
import document_indexer
import generators
import knowledge
import local_file_service as lfs
import log_redaction
import membership
import memory_service as mem
import model_switch
import career_mcq
import news_center
import projects
import task_runner
import setup_manager
import updates
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
    try:
        knowledge.purge_trash()                     # 7.2.1: deleted uploads are kept 7 days, then removed
    except Exception as e:                          # noqa: BLE001 - never blocks startup
        log.warning("trash purge skipped: %s", type(e).__name__)
    log.info("%s %s ready: data=%s schema=v%s models=%s", APP_NAME, __version__, STARTUP["data_root"],
             STARTUP["schema"]["to"], system.active_models())
    if lfs.load_settings()["knowledge"].get("watcher_enabled"):
        try:
            await asyncio.to_thread(_start_watcher)
        except Exception as e:                    # a bad folder setting must never stop the app
            log.warning("Knowledge watcher not started: %s", e)
    STARTUP["update_result"] = updates.record_startup()       # did an update we started finish?
    stop_updates = asyncio.Event()
    update_task = asyncio.create_task(updates.periodic(stop_updates))   # only checks if the owner enabled it
    import providers
    provider_check = asyncio.create_task(providers.check_all_on_start())  # 7.1: saved cloud providers only (model lists)
    yield
    provider_check.cancel()
    import wakeword
    wakeword.ENGINE.shutdown()                                      # 7.1.0-dev.2: the microphone is released when the app exits
    stop_updates.set(); update_task.cancel()
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
    cross = companion.cross_site_reason(request, settings.cors_origin_list)   # 7.1: CSRF / cross-origin guard
    if cross:
        from fastapi.responses import JSONResponse
        return JSONResponse({"detail": cross}, status_code=403)
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
    model: str | None = Field(None, max_length=200, description="Installed Ollama chat model for this turn; omit for the default")
    project_id: str | None = Field(None, max_length=40, description="Project whose context (instructions, documents) this chat uses")
    focus: str = Field("auto", pattern="^(auto|general|coding|learning|career|research|quick|deep|planning|creative)$",
                       description="Answer focus / interaction mode; auto detects coding, learning, career or research per message (same rules "
                                   "as Android). A chosen mode (quick, deep, planning, creative, ...) also sets the history window, context "
                                   "rules and sampling options; it never changes the model")
    regenerate: bool = Field(False, description="Answer the thread's last question again; the new answer replaces the previous one in history")
    refresh_live: bool = Field(False, description="Fetch live information again instead of using the live cache (Refresh on an answer)")


class DefaultModelRequest(BaseModel):
    model: str = Field(..., min_length=1, max_length=200)


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
    assistant_name: str | None = Field(None, max_length=64)        # 7.1.0-dev.2: validated by identity.validate

    voice_profile: dict | None = None                              # 7.1.0-dev.2: read-aloud voice profile (validated below)

    @field_validator("voice_profile")
    @classmethod
    def _voice_profile(cls, v):
        if v is None:
            return v
        allowed = {"style": str, "voiceURI": str, "lang": str, "rate": (int, float), "pitch": (int, float), "volume": (int, float)}
        out = {}
        for k, val in v.items():
            if k not in allowed or not isinstance(val, allowed[k]) or (isinstance(val, str) and len(val) > 200):
                raise ValueError(f"Invalid voice setting: {k}")
            out[k] = val if isinstance(val, str) else max(0.0, min(2.0, float(val)))
        return out

    @field_validator("assistant_name")
    @classmethod
    def _assistant_name(cls, v):
        if v is None:
            return v
        import identity
        return identity.validate(v)
    memory_enabled: bool | None = None
    save_history: bool | None = None
    history_retention_days: int | None = Field(None, ge=0, le=3650)
    answer_detail: Literal["auto", "concise", "detailed"] | None = None
    answer_depth: Literal["auto", "beginner", "intermediate", "expert"] | None = None
    answer_style: Literal["auto", "direct", "steps", "examples"] | None = None
    # letters, spaces and hyphens only, so the field can't carry instructions into the prompt
    answer_language: str | None = Field(None, max_length=30, pattern=r"^(?:|[^\W\d_][^\W\d_ -]*(?:[ -][^\W\d_]+)*)$")


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
async def _chat_model(req: "ChatRequest") -> tuple[str | None, str | None]:
    """7.0: the model for this turn, one source of truth: the model sent with the message (the page's picker), else the
    model stored for the conversation, else None (= the global default). (model, notice). Never substitutes another model:
    an explicit choice that isn't installed is an error; a stored one that was removed falls back to the default, said so."""
    import providers
    if req.model and providers.is_cloud(req.model.strip()):                 # 7.1: a cloud model the owner picked
        try:
            return providers.resolve(req.model.strip()), None
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
    if req.model:
        try:
            return await system.resolve_chat_model(req.model.strip()), None
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    stored = mem.conversation_model(req.thread_id)
    if stored and providers.is_cloud(stored):
        try:
            return providers.resolve(stored), None
        except ValueError as e:
            mem.set_conversation_model(req.thread_id, None)
            return None, f"{e} The default local model answered instead (nothing was sent to the cloud)."
    if not stored:
        import intelligence                                                 # 7.1: opt-in Auto router (off by default)
        routed, why = providers.route(req.message, intelligence.detect(req.message, req.focus))
        if routed:
            return routed, f"Auto routing: {providers.label(routed)} answered this {why}."
        return None, None
    try:
        return await system.resolve_chat_model(stored), None
    except ValueError:
        mem.set_conversation_model(req.thread_id, None)
        return None, f"This conversation's model {stored} is no longer installed, so the default model answered."
    except ConnectionError as e:
        raise _unavailable(e)


def _remember_model(cid: str | None, req: "ChatRequest", model: str | None) -> None:
    """The model picked in the chat is remembered for that conversation (switching conversations restores it)."""
    if cid and req.model and model:
        mem.set_conversation_model(cid, model)


@app.post("/chat")
async def chat(req: ChatRequest, request: Request):
    guest = companion.role_of(request) == "member"           # members never touch the owner's data
    if not guest:                                             # 7.0: "switch the model to …" is done by the app, not the model
        done = await model_switch.handle(req.message.strip(), req.thread_id, req.model, save=db.get_prefs().get("save_history", True))
        if done:
            return {**done, "thread_id": done["conversation_id"]}
        import identity
        who = identity.handle(req.message.strip(), req.thread_id, save=db.get_prefs().get("save_history", True))
        if who:                                               # 7.1.0-dev.2: identity questions answered by the app
            return {**who, "thread_id": who["conversation_id"]}
    model, notice = (None, None) if guest else await _chat_model(req)     # dev.8 / 7.0: checked, never swapped
    if not (model or "").startswith("@"):                              # 7.1: cloud models don't occupy Ollama
        await system.note_chat_model(model or system.active_models()["llm"])   # release the previous chat model
    extra = {**({"project_id": req.project_id} if req.project_id and not guest else {}),
             **({"focus": req.focus} if req.focus != "auto" and not guest else {}),         # default: the call is unchanged
             **({"regenerate": True} if req.regenerate and req.thread_id and not guest else {}),
             **({"refresh_live": True} if req.refresh_live else {})}                                # default: the call is unchanged
    try:
        result = await (run_agent(req.message.strip(), None, guest=True, **({"refresh_live": True} if req.refresh_live else {})) if guest
                        else run_agent(req.message.strip(), req.thread_id, model=model, **extra) if model
                        else run_agent(req.message.strip(), req.thread_id, **extra))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        import insights
        insights.note_provider_failure("chat", e)                 # 5.6: a count only (no content)
        raise _unavailable(e)
    if not guest:
        _remember_model(result["conversation_id"], req, model)
    return {**result, "thread_id": result["conversation_id"], **({"model_notice": notice} if notice else {})}


@app.post("/chat/stream")
async def chat_stream(req: ChatRequest, request: Request):
    """The same chat as /chat, streamed as newline-delimited JSON: {"type":"token","text"} … then {"type":"done", …}.
    The answer appears while it is written instead of after it. Errors arrive as {"type":"error","message"}."""
    if companion.role_of(request) == "member":
        raise HTTPException(status_code=403, detail="Guests use /chat.")
    import json as _json
    done = await model_switch.handle(req.message.strip(), req.thread_id, req.model, save=db.get_prefs().get("save_history", True))
    if done:                                                  # 7.0: a model command, carried out by the app (no model call)
        async def command_events():
            yield _json.dumps({"type": "model_command", **(done["model_command"] or {})}, ensure_ascii=False) + "\n"
            yield _json.dumps({"type": "token", "text": done["answer"]}, ensure_ascii=False) + "\n"
            yield _json.dumps({"type": "done", **done, "thread_id": done["conversation_id"]}, ensure_ascii=False) + "\n"
        return StreamingResponse(command_events(), media_type="application/x-ndjson", headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
    import identity
    who = identity.handle(req.message.strip(), req.thread_id, save=db.get_prefs().get("save_history", True))
    if who:                                                   # 7.1.0-dev.2: "what is your name?" answered by the app
        async def identity_events():
            yield _json.dumps({"type": "token", "text": who["answer"]}, ensure_ascii=False) + "\n"
            yield _json.dumps({"type": "done", **who, "thread_id": who["conversation_id"]}, ensure_ascii=False) + "\n"
        return StreamingResponse(identity_events(), media_type="application/x-ndjson", headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
    model, notice = await _chat_model(req)
    if req.project_id and not projects.get(req.project_id):
        raise HTTPException(status_code=400, detail="That project doesn't exist any more.")
    if not (model or "").startswith("@"):                              # 7.1: cloud models don't occupy Ollama
        await system.note_chat_model(model or system.active_models()["llm"])
    from agent import stream_agent

    async def events():
        try:
            async for ev in stream_agent(req.message.strip(), req.thread_id, model=model, project_id=req.project_id,
                                         **({"focus": req.focus} if req.focus != "auto" else {}),    # default: the call is unchanged
                                         **({"regenerate": True} if req.regenerate and req.thread_id else {}),
                                         **({"refresh_live": True} if req.refresh_live else {})):
                if ev["type"] == "done":
                    ev["thread_id"] = ev["conversation_id"]
                    _remember_model(ev["conversation_id"], req, model)
                    if notice:
                        ev["model_notice"] = notice
                yield _json.dumps(ev, ensure_ascii=False) + "\n"
        except Exception as e:                                              # noqa: BLE001 - reported to the page, not hidden
            log.warning("Streamed chat failed: %s", type(e).__name__)
            import insights
            insights.note_provider_failure("chat_stream", e)            # 5.6: a count only (no content)
            yield _json.dumps({"type": "error", "message": str(_unavailable(e).detail)}) + "\n"
    return StreamingResponse(events(), media_type="application/x-ndjson", headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})


class WarmIn(BaseModel):
    model: str | None = Field(None, max_length=200)
    whisper: bool = False


@app.post("/chat/warm")
async def chat_warm(req: WarmIn, request: Request):
    """Called when the microphone starts: loads the chat model that will answer (the chosen one, never another) and, for
    Private voice mode, Whisper, while the user is still speaking. Generates nothing and changes no setting."""
    if companion.role_of(request) == "member":
        return {"warmed": False}
    model = req.model
    if model:
        try:
            model = await system.resolve_chat_model(model.strip())
        except (ValueError, ConnectionError):
            return {"warmed": False}
    model = model or system.active_models()["llm"]
    await system.note_chat_model(model)                                    # the one-chat-model rule still holds
    tasks = [system.preload_model(model)]
    if req.whisper:
        from transcribe import _load
        tasks.append(asyncio.to_thread(_load))
    results = await asyncio.gather(*tasks, return_exceptions=True)
    return {"warmed": not isinstance(results[0], Exception), "model": model}


# --------------------------------------------------------------------------- live information (Real-Time Intelligence Engine)
class RealtimeSettingsIn(BaseModel):
    enabled: bool | None = None
    news: bool | None = None
    web_provider: Literal["ddgs", "brave", "tavily", "none"] | None = None
    weather: bool | None = None
    fx: bool | None = None
    place: str | None = Field(None, max_length=80)


class RealtimeLookupIn(BaseModel):
    q: str = Field(..., min_length=1, max_length=1000)
    refresh: bool = False
    focus: str = Field("auto", pattern="^(auto|general|coding|learning|career|research|quick|deep|planning|creative)$")


@app.get("/realtime/status", dependencies=[Depends(local_only)])
async def realtime_status():
    """Each live connector's state (ready / off / needs_key / offline / error) and why, and the settings. Never a key."""
    import realtime
    return await realtime.status()


@app.put("/realtime/settings", dependencies=[Depends(local_only)])
async def realtime_settings(req: RealtimeSettingsIn):
    import realtime
    try:
        realtime.save_settings(req.model_dump(exclude_none=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return await realtime.status()


@app.post("/realtime/lookup", dependencies=[Depends(local_only)])
async def realtime_lookup(req: RealtimeLookupIn):
    """What a chat question would retrieve (resolve + connectors), without calling the model: {needed, state, sources, notes}."""
    import realtime
    return realtime.public(await realtime.lookup(req.q.strip(), req.focus, refresh=req.refresh))


# --------------------------------------------------------------------------- performance (dev.8)
@app.get("/system/performance", dependencies=[Depends(local_only)])
async def system_performance():
    """One real sample: CPU, RAM, NVIDIA GPU/VRAM, models loaded in Ollama, speed of the last answer."""
    return await system.performance()


# --------------------------------------------------------------------------- chat models (dev.8)
@app.get("/models", dependencies=[Depends(local_only)])
async def list_chat_models():
    """Chat models installed in Ollama and the default for new chats (for the chat's model picker); 7.1: plus the selected
    model of each connected cloud provider ("cloud": [...], ids "@provider:model")."""
    import providers
    return {**(await system.chat_models()), "cloud": providers.cloud_models()}


# --------------------------------------------------------------------------- 7.1: cloud AI providers (this computer only)
class ProviderKeyIn(BaseModel):
    key: str = Field(..., max_length=512)


class ProviderUpdateIn(BaseModel):
    enabled: bool | None = None
    model: str | None = Field(None, max_length=200)
    allow_charged: bool | None = None
    consent: bool | None = None


class CustomProviderIn(BaseModel):
    base_url: str = Field(..., max_length=300)
    name: str | None = Field(None, max_length=40)
    key: str | None = Field(None, max_length=512)


class RouterIn(BaseModel):
    auto: bool | None = None
    coding: str | None = Field(None, max_length=250)
    complex: str | None = Field(None, max_length=250)


def _provider_call(fn):
    import providers
    try:
        return fn()
    except (providers.ProviderError, ValueError) as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/providers", dependencies=[Depends(local_only)])
async def providers_status():
    """Cloud providers: state, masked key, models, router and the privacy rules. Keys are never returned."""
    import providers
    return providers.all_status()


@app.put("/providers/custom/endpoint", dependencies=[Depends(local_only)])
async def providers_custom(req: CustomProviderIn):
    """A custom OpenAI-compatible endpoint (https, or a server on this computer); tested before it is saved."""
    import providers
    try:
        return await providers.set_custom(req.base_url, req.name, req.key)
    except (providers.ProviderError, ValueError) as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.put("/providers/router", dependencies=[Depends(local_only)])
async def providers_router(req: RouterIn):
    import providers
    return _provider_call(lambda: providers.save_router(req.auto, req.coding, req.complex))


@app.put("/providers/{pid}/key", dependencies=[Depends(local_only)])
async def providers_key(pid: str, req: ProviderKeyIn):
    """Tests the key with the provider (its model list: free) and saves it, encrypted, only if it works."""
    import providers
    try:
        return await providers.save_key(pid, req.key)
    except providers.ProviderError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.post("/providers/{pid}/test", dependencies=[Depends(local_only)])
async def providers_test(pid: str):
    import providers
    try:
        return await providers.test(pid)
    except providers.ProviderError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.put("/providers/{pid}", dependencies=[Depends(local_only)])
async def providers_update(pid: str, req: ProviderUpdateIn):
    import providers
    return _provider_call(lambda: providers.update(pid, req.enabled, req.model, req.allow_charged, req.consent))


@app.delete("/providers/{pid}", dependencies=[Depends(local_only)])
async def providers_remove(pid: str):
    """Deletes the provider's key and settings."""
    import providers
    _provider_call(lambda: providers.remove(pid))
    return providers.all_status()


@app.put("/models/default", dependencies=[Depends(local_only)])
async def set_default_model(req: DefaultModelRequest):
    try:
        return await system.set_default_chat_model(req.model.strip())
    except PermissionError as e:
        raise HTTPException(status_code=409, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except ConnectionError as e:
        raise _unavailable(e)


# --------------------------------------------------------------------------- content
@app.get("/news")
async def news(limit: int = 8, refresh: bool = False):
    try:
        items = await generators.get_news(limit=min(max(limit, 1), 15), refresh=refresh)
    except GenerationError as e:
        raise _unavailable(e)
    return {"items": items, "generated": True, "updated_at": generators.news_cache_time()}


# --------------------------------------------------------------------------- AI Task Runner (dev.8)
class TaskCreate(BaseModel):
    template: Literal["summary", "study", "quiz", "news", "custom"]
    request: str = Field("", max_length=1000)
    source: dict
    model: str | None = Field(None, max_length=200)
    options: dict | None = None                 # quiz: {"count": 3|5|10, "difficulty": "mixed"|"easy"|"hard"}
    project_id: str | None = Field(None, max_length=40)


class TaskSave(BaseModel):
    step: str = Field(..., max_length=30)
    confirm: bool = False


@app.get("/tasks/templates", dependencies=[Depends(local_only)])
def task_templates():
    return {"templates": [{"id": k, "title": v["title"], "sources": v["sources"], "steps": [t for _, t, _ in v["steps"]]}
                          for k, v in task_runner.TEMPLATES.items()]}


@app.get("/tasks/sources", dependencies=[Depends(local_only)])
async def task_sources():
    docs = await asyncio.to_thread(knowledge.list_sources)
    cats = [{"id": c["id"], "label": c["label"]} for c in news_center.catalog()["categories"]]
    prj = [{"id": p["id"], "name": p["name"], "documents": len(p["documents"])} for p in projects.list_projects(include_archived=False)]
    return {"documents": docs, "news_categories": cats, "projects": prj}


# --------------------------------------------------------------------------- career MCQ practice (career milestone)
class CareerMcqIn(BaseModel):
    mode: Literal["document", "topic", "mixed"] = "topic"
    topic: str = Field("", max_length=120)
    document: str | None = Field(None, max_length=300)
    level: str = Field("junior", max_length=20)
    difficulty: str = Field("medium", max_length=10)
    count: int = 3
    model: str | None = Field(None, max_length=200)
    avoid: list[str] = Field(default_factory=list, max_length=40)


class CareerAnswerIn(BaseModel):
    key: str = Field(..., max_length=40)
    topic: str = Field("", max_length=80)
    difficulty: str = Field("medium", max_length=10)
    scope: str = Field("", max_length=120)
    correct: bool


@app.post("/career/mcq", dependencies=[Depends(local_only)])
async def career_mcq_batch(req: CareerMcqIn):
    """One validated batch of personalised MCQs (resume/document, topic or both). Ask again for more: nothing repeats."""
    model = None
    if req.model:
        try:
            model = await system.resolve_chat_model(req.model)
        except ValueError as e:
            raise HTTPException(400, str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    try:
        return await career_mcq.generate(req.mode, req.topic, req.document, req.level, req.difficulty, req.count, model, [a[:200] for a in req.avoid])
    except ValueError as e:
        raise HTTPException(400, str(e))
    except Exception as e:
        raise _unavailable(e)


@app.post("/career/mcq/answer", dependencies=[Depends(local_only)])
def career_mcq_answer(req: CareerAnswerIn):
    return career_mcq.record_answer(req.key, req.topic, req.difficulty, req.scope, req.correct)


@app.get("/career/mcq/stats", dependencies=[Depends(local_only)])
def career_mcq_stats(scope: str | None = None):
    return career_mcq.stats(scope)


# --------------------------------------------------------------------------- Study 7.2: courses, unlimited MCQs, prep plan, STAR, readiness
import study  # noqa: E402


async def _study_model(name: str | None) -> str | None:
    """Study uses local Ollama models only: a cloud chat model choice falls back to the local default (nothing is sent online)."""
    import providers
    if not name or providers.is_cloud(name):
        return None
    try:
        return await system.resolve_chat_model(name)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except ConnectionError as e:
        raise _unavailable(e)


def _study_err(e: Exception) -> HTTPException:
    if isinstance(e, (study.StudyError, ValueError)):
        msg = str(e)
        return HTTPException(503 if "isn't available" in msg else 400, msg)
    return _unavailable(e)


class StudyCourseIn(BaseModel):
    track: str | None = Field(None, max_length=20)
    topic: str | None = Field(None, max_length=120)
    model: str | None = Field(None, max_length=200)


class StudyLessonIn(BaseModel):
    course_id: str = Field(..., max_length=40)
    lesson_id: str = Field(..., max_length=40)
    refresh: bool = False
    model: str | None = Field(None, max_length=200)


class StudyDoneIn(BaseModel):
    course_id: str = Field(..., max_length=40)
    lesson_id: str = Field(..., max_length=40)
    done: bool = True


class StudyMcqIn(BaseModel):
    course_id: str = Field(..., max_length=40)
    round: int = Field(0, ge=0, le=100000)
    level: str = Field("junior", max_length=20)
    difficulty: str = Field("medium", max_length=10)
    count: int = 5
    model: str | None = Field(None, max_length=200)
    avoid: list[str] = Field(default_factory=list, max_length=60)
    module_id: str | None = Field(None, max_length=40)
    weak: bool = False


class StudyAnswerIn(BaseModel):
    skill: str = Field(..., max_length=80)
    question: dict = Field(default_factory=dict)
    correct: bool
    course_id: str | None = Field(None, max_length=40)
    module_id: str | None = Field(None, max_length=40)


class StudyPrefsIn(BaseModel):
    target: str = Field(..., max_length=80)
    level: str = Field(..., max_length=80)
    minutes: int = Field(60, ge=15, le=240)


class StudyTimeIn(BaseModel):
    seconds: int = Field(..., ge=0, le=600)


class StudyCardGradeIn(BaseModel):
    remembered: bool


class StudyStepIn(BaseModel):
    index: int
    done: bool = True


class StudyTutorIn(BaseModel):
    lesson_id: str = Field(..., max_length=40)
    question: str = Field(..., max_length=1500)
    model: str | None = Field(None, max_length=200)


class StudyPlanIn(BaseModel):
    jd: str = Field(..., max_length=20000)
    resume_text: str = Field("", max_length=20000)
    resume_doc: str | None = Field(None, max_length=300)
    interview_date: str | None = Field(None, max_length=10)
    minutes: int = Field(60, ge=15, le=240)


class StudyPlanItemIn(BaseModel):
    day: int
    index: int
    done: bool = True


class StudyStoryIn(BaseModel):
    id: str | None = Field(None, max_length=40)
    title: str = Field(..., max_length=120)
    competencies: list[str] = Field(default_factory=list, max_length=8)
    situation: str = Field("", max_length=2000)
    task: str = Field("", max_length=2000)
    action: str = Field("", max_length=2000)
    result: str = Field("", max_length=2000)


class StudyCoachIn(BaseModel):
    model: str | None = Field(None, max_length=200)


@app.get("/learn/tracks", dependencies=[Depends(local_only)])
def study_tracks():
    return study.tracks()


@app.get("/learn/state", dependencies=[Depends(local_only)])
def study_state():
    """Everything the Study tab shows: courses with progress, the plan, readiness, today's items, streak, due reviews."""
    return study.overview()


@app.get("/learn/courses/{cid}", dependencies=[Depends(local_only)])
def study_course(cid: str):
    d = study.load()
    c = d["courses"].get(cid)
    if not c:
        raise HTTPException(404, "Unknown course.")
    o = study.course_outline(c)
    cached = {k.split("/", 1)[1] for k in d["lessons"] if k.startswith(cid + "/")}
    return {**c, "modules": [{**m, "lessons": [{**l, "done": l["id"] in c["done"], "ready": l["id"] in cached} for l in m["lessons"]]}
                             for m in o["modules"]]}


@app.post("/learn/courses", dependencies=[Depends(local_only)])
async def study_add_course(req: StudyCourseIn):
    try:
        if req.track:
            return study.create_course(track=req.track)
        return await study.custom_course(req.topic or "", await _study_model(req.model))
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise _study_err(e)


@app.delete("/learn/courses/{cid}", dependencies=[Depends(local_only)])
def study_delete_course(cid: str):
    if not study.delete_course(cid):
        raise HTTPException(404, "Unknown course.")
    return {"deleted": cid}


@app.post("/learn/lesson", dependencies=[Depends(local_only)])
async def study_lesson(req: StudyLessonIn):
    try:
        return await study.lesson(req.course_id, req.lesson_id, req.refresh, await _study_model(req.model))
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise _study_err(e)


@app.post("/learn/lesson/done", dependencies=[Depends(local_only)])
def study_lesson_done(req: StudyDoneIn):
    try:
        return study.set_done(req.course_id, req.lesson_id, req.done)
    except study.StudyError as e:
        raise HTTPException(400, str(e))


@app.post("/learn/mcq", dependencies=[Depends(local_only)])
async def study_mcq(req: StudyMcqIn):
    """One more batch for a course (the Study tab asks again as the list scrolls to the end)."""
    try:
        return await study.course_mcq(req.course_id, req.round, req.level, req.difficulty, req.count, await _study_model(req.model),
                                      [a[:200] for a in req.avoid], req.module_id, req.weak)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise _study_err(e)


@app.post("/learn/mcq/answer", dependencies=[Depends(local_only)])
def study_mcq_answer(req: StudyAnswerIn):
    q = {k: req.question.get(k) for k in ("key", "q", "options", "answer", "explanation", "module") if k in req.question}
    return study.answer(req.skill, q, req.correct, course_id=req.course_id, module_id=req.module_id)


def _study_call(fn, *a):
    try:
        return fn(*a)
    except study.StudyError as e:
        raise HTTPException(400, str(e))


async def _study_ai(coro):
    try:
        return await coro
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise _study_err(e)


@app.put("/learn/courses/{cid}/prefs", dependencies=[Depends(local_only)])
def study_prefs(cid: str, req: StudyPrefsIn):
    """Goal and level for this course. Changing them later keeps all progress; the overview is rebuilt."""
    return _study_call(study.set_prefs, cid, req.target, req.level, req.minutes)


@app.get("/learn/prefs/options", dependencies=[Depends(local_only)])
def study_prefs_options():
    return {"targets": study.TARGETS, "levels": study.LEVELS}


@app.post("/learn/courses/{cid}/overview", dependencies=[Depends(local_only)])
async def study_overview(cid: str, req: StudyCoachIn):
    return await _study_ai(study.build_overview(cid, await _study_model(req.model)))


@app.get("/learn/courses/{cid}/dashboard", dependencies=[Depends(local_only)])
def study_dashboard(cid: str):
    return _study_call(study.dashboard, cid)


@app.post("/learn/courses/{cid}/time", dependencies=[Depends(local_only)])
def study_time(cid: str, req: StudyTimeIn):
    return {"seconds": _study_call(study.add_time, cid, req.seconds)}


@app.post("/learn/courses/{cid}/exercise", dependencies=[Depends(local_only)])
def study_exercise(cid: str, req: StudyDoneIn):
    return _study_call(study.set_exercise, cid, req.lesson_id, req.done)


@app.post("/learn/courses/{cid}/cards", dependencies=[Depends(local_only)])
async def study_make_cards(cid: str, req: StudyLessonIn):
    return {"cards": await _study_ai(study.make_cards(cid, req.lesson_id, await _study_model(req.model)))}


@app.get("/learn/cards", dependencies=[Depends(local_only)])
def study_cards(course_id: str | None = None, weekly: bool = False):
    return {"cards": study.due_cards(course_id, weekly=weekly)}


@app.post("/learn/cards/{card_id}", dependencies=[Depends(local_only)])
def study_grade_card(card_id: str, req: StudyCardGradeIn):
    return _study_call(study.grade_card, card_id, req.remembered)


@app.post("/learn/courses/{cid}/capstone", dependencies=[Depends(local_only)])
async def study_capstone(cid: str, req: StudyCoachIn):
    return await _study_ai(study.make_capstone(cid, await _study_model(req.model)))


@app.post("/learn/courses/{cid}/capstone/step", dependencies=[Depends(local_only)])
def study_capstone_step(cid: str, req: StudyStepIn):
    return _study_call(study.capstone_step, cid, req.index, req.done)


@app.post("/learn/courses/{cid}/tutor", dependencies=[Depends(local_only)])
async def study_tutor(cid: str, req: StudyTutorIn):
    return await _study_ai(study.tutor(cid, req.lesson_id, req.question, await _study_model(req.model)))


@app.get("/learn/export", dependencies=[Depends(local_only)])
def study_export():
    """Your Study data as one JSON file (courses, progress, lessons, cards, plan, stories). Saved where you choose."""
    return study.export_data()


@app.post("/learn/import", dependencies=[Depends(local_only)])
def study_import(body: dict):
    return _study_call(study.import_data, body)


# ---- 7.2.0-dev.2: mock interview, resume tailoring, Personal AI Brain opt-in, model quality check (study_plus.py)
import study_plus  # noqa: E402


class StudyBrainIn(BaseModel):
    enabled: bool = False
    memories: list[int] = Field(default_factory=list, max_length=20)
    projects: list[str] = Field(default_factory=list, max_length=10)


class StudyTailorIn(BaseModel):
    jd: str = Field(..., max_length=20000)
    resume_text: str = Field("", max_length=30000)
    resume_doc: str | None = Field(None, max_length=300)
    model: str | None = Field(None, max_length=200)


class StudyMockIn(BaseModel):
    count: int = Field(6, ge=3, le=12)
    skills: list[str] = Field(default_factory=list, max_length=8)
    model: str | None = Field(None, max_length=200)


class StudyMockAnswerIn(BaseModel):
    i: int = Field(..., ge=0, le=20)
    answer: str = Field(..., max_length=4000)
    seconds: int = Field(0, ge=0, le=3600)
    model: str | None = Field(None, max_length=200)


@app.get("/learn/brain/sources", dependencies=[Depends(local_only)])
def study_brain_sources():
    """What can be shared with a course (goal memories, other memories, projects). Nothing is shared until chosen."""
    return study_plus.brain_sources()


@app.put("/learn/courses/{cid}/brain", dependencies=[Depends(local_only)])
def study_brain(cid: str, req: StudyBrainIn):
    return _study_call(study_plus.set_brain, cid, req.enabled, req.memories, req.projects)


@app.get("/learn/quality", dependencies=[Depends(local_only)])
def study_quality(model: str | None = None):
    return {"result": study_plus.quality_status(model)}


@app.post("/learn/quality", dependencies=[Depends(local_only)])
async def study_quality_run(req: StudyCoachIn):
    return await _study_ai(study_plus.quality_check(await _study_model(req.model)))


@app.post("/learn/resume/tailor", dependencies=[Depends(local_only)])
async def study_tailor(req: StudyTailorIn):
    """Rewrites the most relevant resume bullets for a job, keeping only facts already in the resume. Nothing is stored."""
    resume = req.resume_text
    if req.resume_doc and not resume.strip():
        resume = await asyncio.to_thread(knowledge.document_text, req.resume_doc, 30000) or ""
    return await _study_ai(study_plus.tailor(resume, req.jd, await _study_model(req.model)))


@app.post("/learn/mock", dependencies=[Depends(local_only)])
async def study_mock_start(req: StudyMockIn):
    return await _study_ai(study_plus.mock_start(req.count, req.skills, await _study_model(req.model)))


@app.get("/learn/mock", dependencies=[Depends(local_only)])
def study_mock_list():
    return {"sessions": study_plus.mock_list()}


@app.get("/learn/mock/{sid}", dependencies=[Depends(local_only)])
def study_mock_get(sid: str):
    return _study_call(study_plus.mock_get, sid)


@app.post("/learn/mock/{sid}/answer", dependencies=[Depends(local_only)])
async def study_mock_answer(sid: str, req: StudyMockAnswerIn):
    return await _study_ai(study_plus.mock_answer(sid, req.i, req.answer, req.seconds, await _study_model(req.model)))


@app.post("/learn/mock/{sid}/finish", dependencies=[Depends(local_only)])
def study_mock_finish(sid: str):
    return _study_call(study_plus.mock_finish, sid)


@app.delete("/learn/mock/{sid}", dependencies=[Depends(local_only)])
def study_mock_delete(sid: str):
    if not study_plus.mock_delete(sid):
        raise HTTPException(404, "Unknown interview session.")
    return {"deleted": sid}


@app.get("/learn/review", dependencies=[Depends(local_only)])
def study_review():
    return {"due": study.due_reviews()}


@app.post("/learn/plan", dependencies=[Depends(local_only)])
async def study_plan(req: StudyPlanIn):
    resume = req.resume_text
    if req.resume_doc and not resume.strip():
        resume = await asyncio.to_thread(knowledge.document_text, req.resume_doc, 20000) or ""
    try:
        return study.make_plan(req.jd, resume, req.interview_date, req.minutes)
    except study.StudyError as e:
        raise HTTPException(400, str(e))


@app.delete("/learn/plan", dependencies=[Depends(local_only)])
def study_plan_delete():
    return study.clear_plan()


@app.post("/learn/plan/item", dependencies=[Depends(local_only)])
def study_plan_item(req: StudyPlanItemIn):
    try:
        return study.plan_item(req.day, req.index, req.done)
    except study.StudyError as e:
        raise HTTPException(400, str(e))


@app.get("/learn/stories", dependencies=[Depends(local_only)])
def study_stories():
    return {"stories": study.load()["stories"], "competencies": study.tracks()["competencies"]}


@app.post("/learn/stories", dependencies=[Depends(local_only)])
def study_save_story(req: StudyStoryIn):
    try:
        old = next((x for x in study.load()["stories"] if x["id"] == req.id), None) if req.id else None
        return study.save_story({**req.model_dump(), "coach": (old or {}).get("coach")})
    except study.StudyError as e:
        raise HTTPException(400, str(e))


@app.delete("/learn/stories/{sid}", dependencies=[Depends(local_only)])
def study_delete_story(sid: str):
    if not study.delete_story(sid):
        raise HTTPException(404, "Unknown story.")
    return {"deleted": sid}


@app.post("/learn/stories/{sid}/coach", dependencies=[Depends(local_only)])
async def study_coach(sid: str, req: StudyCoachIn):
    try:
        return await study.coach(sid, await _study_model(req.model))
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise _study_err(e)


# --------------------------------------------------------------------------- projects (stability milestone)
class ProjectIn(BaseModel):
    name: str = Field(..., min_length=1, max_length=80)
    instructions: str = Field("", max_length=1500)


class ProjectStatusIn(BaseModel):
    objective: str = Field("", max_length=400)
    stack: str = Field("", max_length=400)
    current: str = Field("", max_length=400)
    milestones: list[str] = Field(default_factory=list, max_length=12)
    issues: list[str] = Field(default_factory=list, max_length=12)
    next: list[str] = Field(default_factory=list, max_length=12)
    decisions: list[str] = Field(default_factory=list, max_length=12)


class ProjectPatch(BaseModel):
    name: str | None = Field(None, min_length=1, max_length=80)
    instructions: str | None = Field(None, max_length=1500)
    use_memory: bool | None = None
    status: ProjectStatusIn | None = None


class ProjectDocs(BaseModel):
    names: list[str] = Field(..., min_length=1, max_length=50)


class ArchiveIn(BaseModel):
    archived: bool = True


def _project(pid: str) -> dict:
    p = projects.get(pid)
    if not p:
        raise HTTPException(404, "Project not found.")
    return p


@app.get("/projects", dependencies=[Depends(local_only)])
def project_list(archived: bool = True):
    return {"projects": projects.list_projects(include_archived=archived)}


@app.post("/projects", dependencies=[Depends(local_only)])
def project_create(req: ProjectIn):
    try:
        return projects.create(req.name, req.instructions)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.get("/discover", dependencies=[Depends(local_only)])
async def discover_courses(q: str = Query("", max_length=80), free_only: bool = False):
    """Next build: Study → Discover (live MIT OCW, OpenStax, Wikibooks + provider search links; cost only as the source states)."""
    import brain
    return await brain.discover(q, free_only)


@app.get("/briefing", dependencies=[Depends(local_only)])
def personal_briefing(kind: Literal["now", "morning", "evening", "weekly"] = "now"):
    """Next build: the personal briefing from local records only (projects, failed AI tasks, MCQ practice, last saved news).
    5.6: kind=morning (today's plan + briefing), evening (what changed today), weekly (last 7 days); default unchanged."""
    import brain
    import career_mcq
    import insights
    p = news_center.prefs()
    args = (projects.list_projects(), task_runner.load_all(), career_mcq.load()["attempts"], news_center.cached(news_center.cache_key("foryou", p)))
    if kind == "now":
        return brain.briefing(*args)
    return insights.briefing(kind, *args, conversations=mem.list_conversations(limit=500) if kind == "weekly" else None)


@app.get("/plan", dependencies=[Depends(local_only)])
def daily_plan():
    """5.5: today's plan from projects' recorded issues and next actions (no invented deadlines)."""
    import brain
    return brain.plan(projects.list_projects())


@app.get("/skills", dependencies=[Depends(local_only)])
def skill_map(min_answers: int = Query(5, ge=3, le=20)):
    """5.5: skill levels from MCQ answers (none below min_answers), trend from the last 10 answers."""
    import brain
    import career_mcq
    return brain.skills(career_mcq.load()["attempts"], min_answers)


@app.get("/search", dependencies=[Depends(local_only)])
def universal_search(q: str = Query("", max_length=80), types: str | None = Query(None, max_length=120),
                     days: int | None = Query(None, ge=1, le=3650), project: str | None = Query(None, max_length=40),
                     sort: Literal["relevance", "recent"] = "relevance"):
    """Next build: one local search (conversations, memories only when memory is on, projects, AI tasks, knowledge files, saved news).
    5.6 (Universal Search 2.0): optional filters types (comma list), days, project, sort; results also grouped by type with counts."""
    import knowledge
    import insights
    import career_mcq
    q = q.strip()
    if len(q) < 2:
        return {"results": [], "groups": [], "total": 0}
    with db.connect() as c:
        hits = [dict(r) for r in c.execute(
            "SELECT m.conversation_id, m.content, m.created_at, c.title FROM messages m JOIN conversations c ON c.id = m.conversation_id "
            "WHERE m.content LIKE ? ESCAPE '\\' ORDER BY m.created_at DESC LIMIT 20",
            ("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",))]
    mems = mem.list_memories() if db.get_prefs().get("memory_enabled", True) else None
    news_files = sorted((news_center.lfs.cache_dir() / "news").glob("cat-*.json"))
    articles = [a for f in news_files for a in (news_center.cached(f.stem) or {}).get("articles", [])]
    return insights.universal_search(q, conversations=mem.list_conversations(limit=500), messages_hits=hits, memories=mems,
                                     projects=projects.list_projects(), tasks=task_runner.load_all(), knowledge_files=knowledge.list_sources(),
                                     news_articles=articles, mcq_attempts=career_mcq.load()["attempts"], types=insights.parse_types(types),
                                     days=days, project=project, sort=sort)


@app.get("/suggestions", dependencies=[Depends(local_only)])
def home_suggestions():
    """5.2 Home: "continue working" and suggestions from your projects and AI tasks (records only, computed on request)."""
    import intelligence
    return intelligence.companion(projects.list_projects(), task_runner.load_all())


@app.get("/projects/{pid}", dependencies=[Depends(local_only)])
async def project_get(pid: str):
    return await asyncio.to_thread(projects.overview, _project(pid))


@app.patch("/projects/{pid}", dependencies=[Depends(local_only)])
def project_update(pid: str, req: ProjectPatch):
    _project(pid)
    try:
        return projects.update(pid, req.name, req.instructions, req.use_memory, req.status.model_dump() if req.status else None)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.post("/projects/{pid}/archive", dependencies=[Depends(local_only)])
def project_archive(pid: str, req: ArchiveIn):
    _project(pid)
    return projects.set_archived(pid, req.archived)


@app.delete("/projects/{pid}", dependencies=[Depends(local_only)])
def project_delete(pid: str, confirm: bool = False):
    """Removes the project record only; documents, knowledge, chats and task history are kept. Needs confirm=true."""
    _project(pid)
    if not confirm:
        raise HTTPException(400, "Deleting a project needs your confirmation (confirm=true). Its documents and chats are kept.")
    return {"deleted": projects.delete(pid)}


@app.post("/projects/{pid}/documents", dependencies=[Depends(local_only)])
async def project_add_documents(pid: str, req: ProjectDocs):
    _project(pid)
    try:
        return await asyncio.to_thread(projects.add_documents, pid, req.names)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.delete("/projects/{pid}/documents", dependencies=[Depends(local_only)])
def project_remove_document(pid: str, name: str):
    """Removes the association only; the document stays in your knowledge index."""
    _project(pid)
    return projects.remove_document(pid, name)


@app.get("/projects/{pid}/export", dependencies=[Depends(local_only)])
async def project_export(pid: str):
    from fastapi.responses import PlainTextResponse
    p = _project(pid)
    md = await asyncio.to_thread(projects.export_markdown, p)
    safe = "".join(ch for ch in p["name"] if ch.isalnum() or ch in " -_").strip()[:50] or "project"
    return PlainTextResponse(md, media_type="text/markdown", headers={"Content-Disposition": f'attachment; filename="{safe}.md"'})


@app.get("/projects/{pid}/overview", dependencies=[Depends(local_only)])
async def project_status_overview(pid: str):
    """5.6: read-only Status overview (recorded status and its age, milestones, blockers, decisions, next actions,
    documents, recent activity, next possible actions from recorded next/issues). Nothing is edited or invented."""
    import insights
    p = _project(pid)
    known = await asyncio.to_thread(knowledge.list_sources)
    return insights.project_overview(p, mem.list_conversations(limit=1000), task_runner.load_all(), known)


# --------------------------------------------------------------------------- Desktop Agent 2.0 (5.6)
class AgentPlanIn(BaseModel):
    project_id: str = Field(..., min_length=1, max_length=40)
    workflow: Literal["status", "priorities", "docs", "unfinished", "duplicates"]


class AgentApplyIn(BaseModel):
    project_id: str = Field(..., min_length=1, max_length=40)
    run_id: str = Field(..., min_length=1, max_length=40)
    item_ids: list[str] = Field(..., min_length=1, max_length=20)
    approved: bool = False


class AgentUndoIn(BaseModel):
    run_id: str = Field(..., min_length=1, max_length=40)


@app.get("/agent/tools", dependencies=[Depends(local_only)])
def agent_tools():
    """The agent's typed tool registry (READ / REVERSIBLE / SENSITIVE) and its workflows."""
    import insights
    return {"tools": [{"name": k, "kind": v["kind"], "args": list(v["args"]), "about": v["about"]} for k, v in insights.TOOLS.items()],
            "workflows": insights.WORKFLOWS}


@app.post("/agent/plan", dependencies=[Depends(local_only)])
async def agent_plan(req: AgentPlanIn):
    """Read-only planning: sections with sources and proposals with ids. Nothing is changed."""
    import insights
    docs = await asyncio.to_thread(knowledge.list_sources)
    try:
        return insights.plan(req.project_id, req.workflow, task_runner.load_all(), docs)
    except KeyError:
        raise HTTPException(404, "Project not found.")
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.post("/agent/apply", dependencies=[Depends(local_only)])
async def agent_apply(req: AgentApplyIn):
    """Applies only the approved REVERSIBLE proposals of a plan (link a document, add a next action) and verifies each one."""
    import insights
    try:
        return await asyncio.to_thread(insights.apply, req.project_id, req.run_id, req.item_ids, req.approved)
    except PermissionError as e:
        raise HTTPException(403, str(e))
    except LookupError as e:
        if isinstance(e, KeyError):
            raise HTTPException(404, "Project not found.")
        raise HTTPException(404, str(e))
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.post("/agent/undo", dependencies=[Depends(local_only)])
async def agent_undo(req: AgentUndoIn):
    """Reverts exactly what that run applied."""
    import insights
    try:
        return await asyncio.to_thread(insights.undo, req.run_id)
    except LookupError as e:
        if isinstance(e, KeyError):
            raise HTTPException(404, "Project not found.")
        raise HTTPException(404, str(e))
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.get("/eval/retrieval", dependencies=[Depends(local_only)])
def eval_retrieval(k: int = Query(3, ge=1, le=10)):
    """5.6: local AI-quality check on a built-in synthetic set (no user data): precision@k of search and related documents,
    duplicate detection accuracy, latency per call, and provider failure counts (counts only)."""
    import eval_local
    return eval_local.run(k)


@app.get("/tasks", dependencies=[Depends(local_only)])
def task_list():
    return {"tasks": list(reversed(task_runner.load_all()))}


@app.post("/tasks", dependencies=[Depends(local_only)])
async def task_create(req: TaskCreate):
    model = None
    if req.model:
        try:
            model = await system.resolve_chat_model(req.model)
        except ValueError as e:
            raise HTTPException(400, str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    try:
        return task_runner.create(req.template, req.request, req.source, model, req.options, req.project_id)
    except (ValueError, TypeError) as e:
        raise HTTPException(400, str(e))


@app.get("/tasks/{task_id}", dependencies=[Depends(local_only)])
def task_get(task_id: str):
    t = task_runner.get(task_id)
    if not t:
        raise HTTPException(404, "Task not found.")
    return t


@app.post("/tasks/{task_id}/run", dependencies=[Depends(local_only)])
async def task_run(task_id: str):
    try:
        return task_runner.start(task_id)
    except KeyError:
        raise HTTPException(404, "Task not found.")
    except RuntimeError as e:
        raise HTTPException(409, str(e))


@app.post("/tasks/{task_id}/retry/{step_id}", dependencies=[Depends(local_only)])
async def task_retry(task_id: str, step_id: str):
    try:
        return task_runner.retry_step(task_id, step_id)
    except KeyError:
        raise HTTPException(404, "Task not found.")
    except RuntimeError as e:
        raise HTTPException(409, str(e))
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.post("/tasks/{task_id}/cancel", dependencies=[Depends(local_only)])
async def task_cancel(task_id: str):
    t = await task_runner.cancel(task_id)
    if not t:
        raise HTTPException(404, "Task not found.")
    return t


@app.delete("/tasks/{task_id}", dependencies=[Depends(local_only)])
def task_delete(task_id: str):
    return {"deleted": task_runner.delete(task_id)}


@app.post("/tasks/{task_id}/save", dependencies=[Depends(local_only)])
async def task_save(task_id: str, req: TaskSave):
    """Saves one finished result to Knowledge. Requires confirm=true (an explicit choice); never personal memory."""
    if not req.confirm:
        raise HTTPException(400, "Saving to Knowledge needs your confirmation (confirm=true).")
    try:
        return await asyncio.to_thread(task_runner.save_to_knowledge, task_id, req.step)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except RuntimeError as e:
        raise HTTPException(409, str(e))


# --------------------------------------------------------------------------- News Center (dev.8)
class NewsAiRequest(BaseModel):
    mode: Literal["briefing", "summarize", "explain"] = "briefing"
    category: str | None = Field(None, max_length=30)
    query: str | None = Field(None, max_length=120)
    article_id: str | None = Field(None, max_length=64)
    model: str | None = Field(None, max_length=200)


@app.get("/news/categories")
def news_categories():
    c = news_center.catalog()
    return {"categories": [{"id": x["id"], "label": x["label"], "glyph": x.get("glyph", ""), "local": bool(x.get("local")),
                            "publishers": [f["publisher"] for f in x.get("feeds", [])]} for x in c["categories"]],
            "prefs": news_center.prefs()}


@app.get("/news/article/{article_id}")
async def news_article(article_id: str, model: str | None = None, summarize: bool = True):
    """In-app reading of one story: its own text (when the publisher's page is readable) and a summary of exactly that
    text on the local model. Opening a story never opens the publisher's site; that is the reader's second Read more."""
    m = None
    if model:
        try:
            m = await system.resolve_chat_model(model)
        except ValueError as e:
            raise HTTPException(400, str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    if summarize:
        await system.note_chat_model(m or system.active_models()["llm"])
    try:
        return await news_center.read_article(article_id[:80], m, summarize)
    except KeyError:
        raise HTTPException(404, "That story isn't in the current list any more. Refresh News and open it again.")


@app.get("/news/feed")
async def news_feed(category: str = "foryou", refresh: bool = False):
    return await news_center.feed(category[:30], refresh)


@app.get("/news/search")
async def news_search(q: str = ""):
    return await news_center.search(q)


@app.put("/news/prefs", dependencies=[Depends(local_only)])
def news_prefs(p: dict):
    return news_center.save_prefs(p)


@app.post("/news/ai")
async def news_ai(req: NewsAiRequest):
    """Briefing / summary / explanation on the local model, from the stories already retrieved (no tools, history or memory)."""
    src = (await news_center.search(req.query)) if req.query else (news_center.cached(f"cat-{req.category or 'foryou'}") or {})
    articles = src.get("articles", [])
    if req.article_id:
        articles = [a for a in articles if a["id"] == req.article_id]
        if not articles:
            raise HTTPException(404, "That story isn't in the current list any more. Refresh News and try again.")
    model = None
    if req.model:
        try:
            model = await system.resolve_chat_model(req.model)
        except ValueError as e:
            raise HTTPException(400, str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    await system.note_chat_model(model or system.active_models()["llm"])   # News AI also keeps one chat model in memory
    try:
        return await news_center.ai(req.mode, articles, model)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except Exception as e:
        raise _unavailable(e)


@app.post("/generate-question")
async def generate_question(req: QuestionRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "interview_view")
    await system.note_chat_model(system.active_models()["llm"])   # one chat model in memory, whichever feature uses it
    try:
        return {"questions": await generators.generate_questions(req.category, req.difficulty, req.count, exclude)}
    except GenerationError as e:
        raise _unavailable(e)


@app.post("/generate-mcq")
async def generate_mcq(req: MCQRequest):
    exclude = list(req.exclude)
    if req.user_id:
        exclude += await asyncio.to_thread(db.seen_items, req.user_id, "mcq")
    await system.note_chat_model(system.active_models()["llm"])
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


def _voice_error(e: Exception) -> HTTPException:
    """7.1: a missing speech model is a clear, actionable 409 (never a raw library error or a hidden download)."""
    import transcribe as tr
    if isinstance(e, tr.NotInstalled):
        return HTTPException(409, str(e))
    return _unavailable(e)


@app.get("/voice/status")
async def voice_status():
    """7.1: the local speech engine: library present, model installed (checked offline), download size and progress."""
    import transcribe as tr
    return await asyncio.to_thread(tr.status)


class VoiceDownloadIn(BaseModel):
    confirm: bool = False


@app.post("/voice/model/download", dependencies=[Depends(local_only)])
async def voice_model_download(req: VoiceDownloadIn):
    """7.1: downloads the local speech model ONLY when the user confirmed it (the size is shown first). Runs in the background;
    poll GET /voice/status. From this computer only."""
    import transcribe as tr
    if not req.confirm:
        raise HTTPException(400, f"Confirm the download first: about {tr.SIZES_MB.get(tr.get_settings().whisper_model, '?')} MB from Hugging Face.")
    try:
        return await asyncio.to_thread(tr.download)
    except RuntimeError as e:
        raise HTTPException(503, str(e))


# --------------------------------------------------------------------------- 7.1.0-dev.2: wake phrase (Windows, this computer only)
class WakeIn(BaseModel):
    enabled: bool | None = None
    confirm: bool = False
    use_name: bool | None = None
    phrase: str | None = Field(None, max_length=40)
    name_only: bool | None = None
    sensitivity: Literal["low", "medium", "high"] | None = None


@app.get("/voice/wake", dependencies=[Depends(local_only)])
def wake_status(visible: bool = False):
    """Wake-phrase settings and engine state. The open page calls this about once a second with visible=1: the engine runs
    only while an Abhify.AI page on this computer is visible."""
    import wakeword
    wakeword.ENGINE.heartbeat(visible)
    return wakeword.ENGINE.status()


@app.put("/voice/wake", dependencies=[Depends(local_only)])
def wake_update(req: WakeIn):
    """Turning the wake phrase on needs confirm=true (the page explains microphone use first). Off by default; after an update
    it waits for a new confirmation."""
    import wakeword
    from version import __version__
    ch = req.model_dump(exclude_none=True, exclude={"confirm"})
    if req.phrase is not None:
        ch["phrase"] = " ".join(req.phrase.split())
    if req.enabled:
        if not req.confirm:
            raise HTTPException(400, "Confirm first: the microphone listens for the wake phrase while an Abhify.AI page is open.")
        av = wakeword.availability()
        if not av["supported"]:
            raise HTTPException(409, av["why"])
        bad = wakeword.compatibility(wakeword.phrases({**wakeword.settings(), **ch}))
        if bad:
            raise HTTPException(400, bad)
        ch["confirmed_version"] = __version__
    try:
        wakeword.save(ch)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if req.enabled is False:
        wakeword.ENGINE.shutdown()
    return wakeword.ENGINE.status()


@app.post("/voice/wake/pause", dependencies=[Depends(local_only)])
def wake_pause():
    """Stop listening now (until Resume or the next app start); the setting stays on."""
    import wakeword
    wakeword.ENGINE.paused_until = float("inf")
    wakeword.ENGINE._stop("paused")
    return wakeword.ENGINE.status()


@app.post("/voice/wake/resume", dependencies=[Depends(local_only)])
def wake_resume():
    """The page finished the interaction that a detection started: listen for the wake phrase again."""
    import wakeword
    wakeword.ENGINE.resume()
    return wakeword.ENGINE.status()


class WakeTestIn(BaseModel):
    phrase: str | None = Field(None, max_length=40)
    seconds: float = Field(8.0, ge=3, le=15)


@app.post("/voice/wake/test", dependencies=[Depends(local_only)])
async def wake_test(req: WakeTestIn):
    """"Test my wake phrase": listens for a few seconds and says whether the phrase was recognised. Nothing is kept."""
    import wakeword
    av = wakeword.availability()
    if not av["supported"]:
        raise HTTPException(409, av["why"])
    ph = [" ".join(req.phrase.split())] if req.phrase else wakeword.phrases()
    bad = wakeword.compatibility(ph)
    if bad:
        raise HTTPException(400, bad)
    return await asyncio.to_thread(wakeword.test_live, ph, req.seconds, wakeword.settings()["sensitivity"])


@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...), language: str | None = Form(None)):
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        return await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _voice_error(e)


@app.post("/voice")
async def voice(request: Request, file: UploadFile = File(...), thread_id: str | None = Form(None),
                language: str | None = Form(None)):
    guest = companion.role_of(request) == "member"
    from transcribe import transcribe
    audio = await _read_audio(file)
    try:
        tr = await asyncio.to_thread(transcribe, audio, language)
    except Exception as e:
        raise _voice_error(e)
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


@app.delete("/knowledge/documents/{name}", dependencies=[Depends(local_only)])
async def delete_knowledge_upload(name: str, confirm: bool = False):
    """7.2.1: delete a document added through the app: removed from search and answers now, the file kept in Trash for
    7 days. Files from the watched folder are removed by deleting them from that folder."""
    if not confirm:
        raise HTTPException(400, "Add confirm=true to delete this document.")
    try:
        return await asyncio.to_thread(knowledge.delete_upload, name)
    except KeyError:
        raise HTTPException(404, "No uploaded document with that name (files from the watched folder are removed by deleting them there).")


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


@app.get("/identity", dependencies=[Depends(local_only)])
def get_identity():
    """7.1.0-dev.2: the assistant's name on this computer, the default, suggestions and the wake phrases it implies."""
    import identity
    name = identity.current()
    return {"name": name, "default": identity.DEFAULT_NAME, "suggestions": identity.SUGGESTIONS, "max_length": identity.MAX_LEN,
            "wake_phrases": identity.wake_phrases(name), "product": "Abhify.AI"}


# --------------------------------------------------------------------------- conversations
@app.get("/conversations")
def conversations():
    return {"conversations": mem.list_conversations()}


@app.get("/conversations/{conversation_id}")
def conversation(conversation_id: str, limit: int = 100):
    return {"id": conversation_id, "model": mem.conversation_model(conversation_id),
            "messages": mem.recent_messages(conversation_id, limit=min(limit, 500))}


class ConversationModelIn(BaseModel):
    model: str | None = Field(None, max_length=200, description="An installed chat model, or null to follow the default")


@app.put("/models/conversation/{conversation_id}", dependencies=[Depends(local_only)])
async def set_conversation_model(conversation_id: str, req: ConversationModelIn):
    """7.0: this conversation's model (the chat's model picker). Checked against Ollama; a failure keeps the previous one."""
    if not conversation_id or len(conversation_id) > 64:
        raise HTTPException(400, "Unknown conversation.")
    previous = mem.conversation_model(conversation_id)
    import providers
    if req.model and providers.is_cloud(req.model.strip()):                # 7.1: a connected cloud model
        try:
            real = providers.resolve(req.model.strip())
        except ValueError as e:
            raise HTTPException(400, str(e))
    elif req.model:
        try:
            real = await system.resolve_chat_model(req.model.strip())
        except ValueError as e:
            raise HTTPException(400, str(e))
        except ConnectionError as e:
            raise _unavailable(e)
    else:
        real = None
    mem.set_conversation_model(conversation_id, real)
    return {"id": conversation_id, "model": real, "previous": previous, "default": system.active_models()["llm"]}


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


@app.post("/setup/models/pull/cancel", dependencies=[Depends(local_only)])
def cancel_pull(p: PullIn):
    """7.0 Model Library: stops a running download (Ollama keeps the partial download, so Download resumes it)."""
    return {"model": p.model, "cancelled": system.cancel_pull(p.model)}


@app.delete("/models/{name:path}", dependencies=[Depends(local_only)])
async def delete_model(name: str, confirm: bool = False):
    """7.0 Model Library: removes an installed Ollama model. Needs confirm=true; the default chat model and the
    embedding model can't be removed here (choose another default first)."""
    if not confirm:
        raise HTTPException(400, "Removing a model needs confirmation (confirm=true).")
    try:
        return await system.delete_model(name)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except ConnectionError as e:
        raise _unavailable(e)


@app.post("/setup/test", dependencies=[Depends(local_only)])
async def test_model():
    return await system.test_inference()


@app.get("/setup/advice", dependencies=[Depends(local_only)])
async def setup_advice():
    """7.0.2: this computer's hardware (each fact labelled verified / estimate / unavailable) and the recommended local
    model with alternatives. Read-only: downloads nothing and changes nothing."""
    import hardware_advisor
    diag = await system.diagnostics()
    adapters, cpu = await asyncio.gather(asyncio.to_thread(hardware_advisor.display_adapters), asyncio.to_thread(hardware_advisor.cpu_name))
    return {"device": hardware_advisor.device_report(diag, adapters, cpu, await asyncio.to_thread(hardware_advisor.acceleration)),
            **hardware_advisor.advise(diag, default_model=system.active_models()["llm"])}


class UseModelIn(BaseModel):
    model: str = Field(..., min_length=1, max_length=100, pattern=r"^[A-Za-z0-9._:\-/]+$")


@app.post("/setup/use-model", dependencies=[Depends(local_only)])
async def setup_use_model(req: UseModelIn):
    """7.0.2: the user chose this (installed) model: run a REAL local inference first; only if it answers does it become the
    default chat model. A failed test changes nothing and says why."""
    import hardware_advisor
    have = [m["name"] for m in (await system.ollama_status()).get("models", [])]
    try:                                                         # "qwen2.5:7b" (catalog) may be installed as "qwen2.5:latest"
        real = await system.resolve_chat_model(hardware_advisor.installed_name(req.model.strip(), have))
    except ValueError as e:
        raise HTTPException(400, str(e))
    except ConnectionError as e:
        raise _unavailable(e)
    test = await system.test_inference(real)
    if not test.get("ok"):
        return {"ok": False, "model": real, "test": test, "default": system.active_models()["llm"]}
    try:
        await system.set_default_chat_model(real)
    except PermissionError as e:
        return {"ok": False, "model": real, "test": test, "error": str(e), "default": system.active_models()["llm"]}
    return {"ok": True, "model": real, "test": test, "default": real}


class AutoSetupIn(BaseModel):
    confirm: bool = False


@app.get("/setup/plan", dependencies=[Depends(local_only)])
async def setup_plan():
    """What automatic setup would install on this device (sizes, disk, internet). Downloads nothing."""
    return await setup_manager.plan()


@app.post("/setup/auto", dependencies=[Depends(local_only)])
async def setup_auto_start(c: AutoSetupIn):
    """Start (or resume / retry) automatic setup after the user confirmed the plan."""
    try:
        return await setup_manager.start(c.confirm)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.get("/setup/auto", dependencies=[Depends(local_only)])
def setup_auto_status():
    return setup_manager.status()


@app.post("/setup/auto/pause", dependencies=[Depends(local_only)])
def setup_auto_pause():
    return setup_manager.stop("pause")


@app.post("/setup/auto/cancel", dependencies=[Depends(local_only)])
def setup_auto_cancel():
    return setup_manager.stop("cancel")


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


# --------------------------------------------------------------------------- 7.2.0-dev.1: Data & Sync (manual, end-to-end encrypted; this computer only)
import datasync_service as dsync  # noqa: E402
from fastapi.responses import JSONResponse, Response  # noqa: E402


@app.exception_handler(dsync.DataSyncError)
async def _datasync_error(_request: Request, e: dsync.DataSyncError):
    status = {"auth": 428, "rekey": 409, "exists": 409, "missing": 409, "no_vault": 409, "account": 409, "retry": 503}.get(e.code, 400)
    return JSONResponse({"detail": str(e), "code": e.code}, status_code=status)


class DsClientIn(BaseModel):
    text: str = Field(..., max_length=20000)


class DsRecoveryIn(BaseModel):
    recovery_key: str = Field(..., max_length=200)


class DsConfirmIn(BaseModel):
    last_group: str = Field(..., max_length=10)


class DsKindsIn(BaseModel):
    kinds: list[Literal["conversation", "message", "memory", "pref"]] = Field(default_factory=list, max_length=4)


class DsApplyIn(BaseModel):
    direction: Literal["both", "upload", "download"] = "both"
    approve_deletes: list[str] = Field(default_factory=list, max_length=100000)
    keep_elsewhere: list[str] = Field(default_factory=list, max_length=100000)


class DsResolveIn(BaseModel):
    choice: Literal["keep_mine", "keep_theirs", "keep_both"]


class DsDisconnectIn(BaseModel):
    remove_remote: bool = False
    confirm: str = Field("", max_length=40)


class DsBackupIn(BaseModel):
    passphrase: str = Field(..., max_length=1024)
    categories: list[Literal["Conversations", "Personal memory", "Portable preferences"]] = Field(..., min_length=1, max_length=3)
    to_drive: bool = False


class DsRestoreIn(BaseModel):
    token: str = Field(..., max_length=64)
    kinds: list[Literal["conversation", "message", "memory", "pref"]] = Field(..., min_length=1, max_length=4)
    replace_different: bool = False
    confirm_replace: bool = False


@app.get("/datasync/status", dependencies=[Depends(local_only)])
def datasync_status():
    return dsync.status()


@app.put("/datasync/oauth-client", dependencies=[Depends(local_only)])
def datasync_oauth_client(b: DsClientIn):
    return dsync.import_client(b.text)


@app.post("/datasync/connect", dependencies=[Depends(local_only)])
def datasync_connect():
    return dsync.connect()


@app.post("/datasync/connect/finish", dependencies=[Depends(local_only)])
def datasync_connect_finish():
    return dsync.finish_connect()


@app.post("/datasync/disconnect", dependencies=[Depends(local_only)])
def datasync_disconnect(b: DsDisconnectIn):
    return dsync.disconnect(b.remove_remote, b.confirm)


@app.post("/datasync/interop-check", dependencies=[Depends(local_only)])
def datasync_interop():
    return dsync.interop_check()


@app.get("/datasync/vault", dependencies=[Depends(local_only)])
def datasync_vault():
    return dsync.vault_probe()


@app.post("/datasync/vault/create", dependencies=[Depends(local_only)])
def datasync_vault_create():
    return dsync.vault_create()


@app.post("/datasync/vault/confirm", dependencies=[Depends(local_only)])
def datasync_vault_confirm(b: DsConfirmIn):
    return dsync.vault_confirm(b.last_group)


@app.post("/datasync/vault/join", dependencies=[Depends(local_only)])
def datasync_vault_join(b: DsRecoveryIn):
    return dsync.vault_join(b.recovery_key)


@app.put("/datasync/kinds", dependencies=[Depends(local_only)])
def datasync_kinds(b: DsKindsIn):
    return {"kinds": dsync.set_kinds(list(b.kinds))}


@app.post("/datasync/preview", dependencies=[Depends(local_only)])
def datasync_preview():
    return dsync.preview()


@app.post("/datasync/apply", dependencies=[Depends(local_only)])
def datasync_apply(b: DsApplyIn):
    return dsync.apply(b.direction, b.approve_deletes, b.keep_elsewhere)


@app.get("/datasync/conflicts", dependencies=[Depends(local_only)])
def datasync_conflicts():
    return dsync.conflicts()


@app.post("/datasync/conflicts/{cid}", dependencies=[Depends(local_only)])
def datasync_resolve(cid: str, b: DsResolveIn):
    return dsync.resolve(cid, b.choice)


@app.get("/datasync/deleted", dependencies=[Depends(local_only)])
def datasync_deleted():
    return dsync.recently_deleted()


@app.post("/datasync/deleted/{tid}/restore", dependencies=[Depends(local_only)])
def datasync_restore_deleted(tid: str):
    return dsync.restore_deleted(tid)


@app.get("/datasync/history", dependencies=[Depends(local_only)])
def datasync_history():
    return dsync.history()


@app.get("/datasync/devices", dependencies=[Depends(local_only)])
def datasync_devices():
    return dsync.devices()


@app.post("/datasync/devices/{dev}/revoke", dependencies=[Depends(local_only)])
def datasync_revoke(dev: str, b: DsRecoveryIn):
    return dsync.revoke(dev, b.recovery_key)


@app.post("/datasync/identity/reset", dependencies=[Depends(local_only)])
def datasync_reset_identity():
    return dsync.reset_identity()


@app.get("/datasync/backups", dependencies=[Depends(local_only)])
def datasync_backups():
    return {"local": dsync.list_backups()}


@app.get("/datasync/backups/drive", dependencies=[Depends(local_only)])
def datasync_drive_backups():
    return {"drive": dsync.drive_backups()}


@app.post("/datasync/backups", dependencies=[Depends(local_only)])
def datasync_backup_create(b: DsBackupIn):
    return dsync.create_backup(b.passphrase, list(b.categories), b.to_drive)


@app.get("/datasync/backups/{name}", dependencies=[Depends(local_only)])
def datasync_backup_download(name: str):
    p = dsync.backup_path(name)
    return FileResponse(p, filename=p.name, media_type="application/octet-stream")


@app.post("/datasync/backups/drive/{file_id}/fetch", dependencies=[Depends(local_only)])
def datasync_backup_fetch(file_id: str):
    return dsync.fetch_drive_backup(file_id)


@app.post("/datasync/restore/preview", dependencies=[Depends(local_only)])
async def datasync_restore_preview(passphrase: str = Form(..., max_length=1024), name: str | None = Form(None),
                                   file: UploadFile | None = File(None)):
    if file is not None:
        data = await file.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024:
            raise HTTPException(413, "That backup file is too large (over 64 MB).")
    elif name:
        data = dsync.backup_path(name).read_bytes()
    else:
        raise HTTPException(400, "Choose a backup file.")
    return await asyncio.to_thread(dsync.preview_restore, data, passphrase)


@app.post("/datasync/restore/apply", dependencies=[Depends(local_only)])
def datasync_restore_apply(b: DsRestoreIn):
    return dsync.apply_restore(b.token, list(b.kinds), b.replace_different, b.confirm_replace)


# --------------------------------------------------------------------------- updates & lifecycle
class ConfirmModelIn(BaseModel):
    model: str = Field(..., min_length=1, max_length=100, pattern=r"^[A-Za-z0-9._:\-/]+$")
    confirm: bool = False


def _update_error(e: Exception) -> HTTPException:
    return HTTPException(400, str(e))


@app.get("/updates", dependencies=[Depends(local_only)])
def updates_status():
    return updates.status()


@app.post("/updates/check", dependencies=[Depends(local_only)])
async def updates_check():
    return await updates.check(force=True)


@app.post("/updates/download", dependencies=[Depends(local_only)])
async def updates_download():
    try:
        return await updates.download()
    except updates.UpdateError as e:
        raise _update_error(e)


@app.post("/updates/cancel", dependencies=[Depends(local_only)])
def updates_cancel():
    return updates.cancel_download()


@app.post("/updates/install", dependencies=[Depends(local_only)])
def updates_install(c: ConfirmIn):
    try:
        return updates.install(c.confirm)
    except updates.UpdateError as e:
        raise _update_error(e)


@app.post("/updates/models/check", dependencies=[Depends(local_only)])
async def updates_models_check():
    return await updates.check_models()


@app.post("/updates/models/apply", dependencies=[Depends(local_only)])
async def updates_models_apply(m: ConfirmModelIn):
    try:
        return await updates.apply_model_update(m.model, m.confirm)
    except updates.UpdateError as e:
        raise _update_error(e)


@app.post("/updates/models/remove-backup", dependencies=[Depends(local_only)])
async def updates_models_remove_backup(m: ConfirmModelIn):
    return await updates.remove_model_backup(m.model)


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
# 6.1: direct links / refreshes of the app's own pages (/home, /assistant, /study, /more) get the app shell, which opens that
# page. Not API routes (no JSON, not in the route table): only these exact names, everything else is served as before.
PAGE_PATHS = {"home", "assistant", "study", "more"}


class AppStatic(StaticFiles):
    async def get_response(self, path: str, scope):
        if path.strip("/\\") in PAGE_PATHS:
            if scope.get("path", "").endswith("/"):           # /home/ -> /home (relative asset URLs would break under a slash)
                from fastapi.responses import RedirectResponse
                return RedirectResponse(scope["path"].rstrip("/"), status_code=308)
            path = "index.html"
        return await super().get_response(path, scope)


if FRONTEND_DIR.is_dir():
    app.mount("/", AppStatic(directory=FRONTEND_DIR, html=True), name="frontend")
