"""Abhify cloud service: accounts, sessions, sync and the online-AI gateway for the Android app.

Separate from the desktop app (backend/), which stays single-user and is never exposed. Run:
    uvicorn app.main:app --host 127.0.0.1 --port 8100        (TLS by the reverse proxy in front)
API reference and deployment: docs/ANDROID_DEPLOYMENT.md
"""
from __future__ import annotations

import hmac
import json
import logging
import re
import sqlite3
import time
import uuid
from typing import Any, Iterator

import httpx
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from . import mailer, providers, security
from .config import Settings, get_settings
from .db import connect, transaction

VERSION = "1.0.0-dev.1"
log = logging.getLogger("abhify.cloud")
app = FastAPI(title="Abhify Cloud", version=VERSION, docs_url=None, redoc_url=None, openapi_url=None)
app.state.ollama_transport = None          # tests inject an httpx transport here
app.state.provider_transport = None        # ...and here for the shared providers

EMAIL = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,255}\.[^@\s]{2,}$")
RECORD_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
KINDS = {"conversation", "message", "memory", "preference"}
NOW_MS = lambda: int(time.time() * 1000)


# ------------------------------------------------------------------ plumbing
def settings() -> Settings:
    return get_settings()


def db(s: Settings = Depends(settings)) -> Iterator[sqlite3.Connection]:
    conn = connect(s.db_path)
    try:
        yield conn
    finally:
        conn.close()


def client_ip(request: Request, s: Settings) -> str:
    if s.trust_proxy and (fwd := request.headers.get("x-forwarded-for")):
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else "?"


def limit(key: str, n: int, window_s: float) -> None:
    if not security.limiter.allow(key, n, window_s):
        raise HTTPException(429, "Too many attempts. Wait a minute and try again.", headers={"Retry-After": str(int(window_s))})


@app.middleware("http")
async def security_headers(request: Request, call_next):
    resp = await call_next(request)
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    if get_settings().env == "prod":
        resp.headers["Strict-Transport-Security"] = "max-age=31536000"
    return resp


@app.exception_handler(Exception)
async def unexpected(request: Request, exc: Exception):               # never leak internals or tokens
    log.exception("unhandled error on %s %s", request.method, request.url.path)
    return JSONResponse({"detail": "Internal error."}, status_code=500)


# ------------------------------------------------------------------ auth core
class User(BaseModel):
    id: str
    email: str
    display_name: str
    created_at: int


class Auth(BaseModel):
    user: User
    session_id: str


def user_out(row: sqlite3.Row) -> User:
    return User(id=row["id"], email=row["email"], display_name=row["display_name"], created_at=row["created_at"])


def issue_tokens(conn: sqlite3.Connection, s: Settings, session_id: str) -> dict[str, Any]:
    now = int(time.time())
    access, refresh = security.new_token("at"), security.new_token("rt")
    conn.execute("INSERT INTO tokens (hash, session_id, kind, expires_at) VALUES (?,?,?,?)",
                 (security.token_hash(access), session_id, "access", now + s.access_token_minutes * 60))
    conn.execute("INSERT INTO tokens (hash, session_id, kind, expires_at) VALUES (?,?,?,?)",
                 (security.token_hash(refresh), session_id, "refresh", now + s.refresh_token_days * 86400))
    return {"access_token": access, "access_expires_in": s.access_token_minutes * 60,
            "refresh_token": refresh, "refresh_expires_in": s.refresh_token_days * 86400,
            "session_id": session_id, "token_type": "Bearer"}


def new_session(conn: sqlite3.Connection, s: Settings, user_id: str, device_name: str) -> dict[str, Any]:
    sid = uuid.uuid4().hex
    now = int(time.time())
    conn.execute("INSERT INTO sessions (id, user_id, device_name, created_at, last_seen_at) VALUES (?,?,?,?,?)",
                 (sid, user_id, (device_name or "Unknown device")[:80], now, now))
    return issue_tokens(conn, s, sid)


def current(request: Request, conn: sqlite3.Connection = Depends(db)) -> Auth:
    """The authenticated user for every protected route: a valid, unexpired access token of a live session."""
    h = request.headers.get("authorization", "")
    if not h.lower().startswith("bearer "):
        raise HTTPException(401, "Not signed in.", headers={"WWW-Authenticate": "Bearer"})
    row = conn.execute(
        "SELECT u.*, s.id AS sid, s.last_seen_at FROM tokens t JOIN sessions s ON s.id = t.session_id JOIN users u ON u.id = s.user_id "
        "WHERE t.hash = ? AND t.kind = 'access' AND t.expires_at > ? AND s.revoked_at IS NULL",
        (security.token_hash(h[7:].strip()), int(time.time()))).fetchone()
    if not row:
        raise HTTPException(401, "Session expired or signed out.", headers={"WWW-Authenticate": "Bearer"})
    if int(time.time()) - row["last_seen_at"] > 300:
        conn.execute("UPDATE sessions SET last_seen_at = ? WHERE id = ?", (int(time.time()), row["sid"]))
    return Auth(user=user_out(row), session_id=row["sid"])


def revoke_session(conn: sqlite3.Connection, sid: str) -> None:
    conn.execute("UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", (int(time.time()), sid))
    conn.execute("DELETE FROM tokens WHERE session_id = ?", (sid,))


# ------------------------------------------------------------------ auth routes
class RegisterIn(BaseModel):
    email: str = Field(max_length=320)
    password: str = Field(max_length=256)
    display_name: str = Field(min_length=1, max_length=60)
    device_name: str = Field(default="", max_length=80)


class LoginIn(BaseModel):
    email: str = Field(max_length=320)
    password: str = Field(max_length=256)
    device_name: str = Field(default="", max_length=80)


def norm_email(e: str) -> str:
    e = e.strip().lower()
    if not EMAIL.match(e):
        raise HTTPException(422, "Enter a valid email address.")
    return e


@app.post("/api/v1/auth/register", status_code=201)
def register(body: RegisterIn, request: Request, s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    limit(f"register:{client_ip(request, s)}", 5, 3600)
    email = norm_email(body.email)
    if problem := security.password_problem(body.password, s.password_min_length):
        raise HTTPException(422, problem)
    pw = security.hash_password(body.password)
    now = int(time.time())
    with transaction(conn):
        if conn.execute("SELECT 1 FROM users WHERE email = ?", (email,)).fetchone():
            raise HTTPException(409, "An account with this email already exists. Sign in instead.")
        uid = uuid.uuid4().hex
        conn.execute("INSERT INTO users (id, email, display_name, password_hash, created_at, password_changed_at) VALUES (?,?,?,?,?,?)",
                     (uid, email, body.display_name.strip(), pw, now, now))
        tokens = new_session(conn, s, uid, body.device_name)
        user = user_out(conn.execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone())
    return {"user": user, **tokens}


@app.post("/api/v1/auth/login")
def login(body: LoginIn, request: Request, s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    email = body.email.strip().lower()
    limit(f"login-ip:{client_ip(request, s)}", 20, 300)
    limit(f"login-email:{email}", 5, 300)
    row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    ok = security.verify_password(body.password, row["password_hash"] if row else security.DUMMY_HASH)
    if not row or not ok:
        raise HTTPException(401, "Wrong email or password.")
    with transaction(conn):
        tokens = new_session(conn, s, row["id"], body.device_name)
    return {"user": user_out(row), **tokens}


class RefreshIn(BaseModel):
    refresh_token: str = Field(max_length=200)


@app.post("/api/v1/auth/refresh")
def refresh(body: RefreshIn, request: Request, s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    limit(f"refresh:{client_ip(request, s)}", 60, 300)
    h = security.token_hash(body.refresh_token)
    reused = False
    with transaction(conn):
        row = conn.execute("SELECT t.*, s.revoked_at FROM tokens t JOIN sessions s ON s.id = t.session_id "
                           "WHERE t.hash = ? AND t.kind = 'refresh'", (h,)).fetchone()
        if not row or row["revoked_at"] is not None or row["expires_at"] <= int(time.time()):
            raise HTTPException(401, "Session expired. Sign in again.")
        if row["used_at"] is not None:
            # A rotated-out refresh token came back: it was copied. End the whole session (both copies).
            # The revocation must commit, so the refusal is raised only after the transaction ends.
            revoke_session(conn, row["session_id"])
            log.warning("refresh token reuse detected; session %s revoked", row["session_id"])
            reused = True
        else:
            conn.execute("UPDATE tokens SET used_at = ? WHERE hash = ?", (int(time.time()), h))
            conn.execute("DELETE FROM tokens WHERE session_id = ? AND kind = 'access'", (row["session_id"],))
            tokens = issue_tokens(conn, s, row["session_id"])
            conn.execute("UPDATE sessions SET last_seen_at = ? WHERE id = ?", (int(time.time()), row["session_id"]))
    if reused:
        raise HTTPException(401, "Session ended for safety (sign-in used from two places). Sign in again.")
    return tokens


@app.post("/api/v1/auth/logout", status_code=204)
def logout(auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    with transaction(conn):
        revoke_session(conn, auth.session_id)
    return Response(status_code=204)


class ForgotIn(BaseModel):
    email: str = Field(max_length=320)


@app.post("/api/v1/auth/forgot-password", status_code=202)
def forgot(body: ForgotIn, request: Request, s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    email = body.email.strip().lower()
    limit(f"forgot-ip:{client_ip(request, s)}", 10, 3600)
    if security.limiter.allow(f"forgot-email:{email}", 3, 3600):
        row = conn.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()
        if row:
            code = security.new_reset_code()
            with transaction(conn):
                conn.execute("INSERT OR REPLACE INTO password_resets (user_id, code_hash, expires_at, attempts) VALUES (?,?,?,0)",
                             (row["id"], security.token_hash(f"{row['id']}:{code}"), int(time.time()) + s.reset_code_minutes * 60))
            mailer.send_reset_code(s, email, code)
    # Same answer whether or not the account exists (no account discovery).
    return {"detail": "If an account exists for this email, a reset code has been sent."}


class ResetIn(BaseModel):
    email: str = Field(max_length=320)
    code: str = Field(max_length=20)
    new_password: str = Field(max_length=256)


@app.post("/api/v1/auth/reset-password")
def reset(body: ResetIn, request: Request, s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    email = body.email.strip().lower()
    limit(f"reset-ip:{client_ip(request, s)}", 20, 3600)
    bad = HTTPException(400, "The code is wrong or has expired. Ask for a new one.")
    if problem := security.password_problem(body.new_password, s.password_min_length):
        raise HTTPException(422, problem)
    wrong = False
    with transaction(conn):
        row = conn.execute("SELECT u.id, r.code_hash, r.expires_at, r.attempts FROM users u JOIN password_resets r ON r.user_id = u.id "
                           "WHERE u.email = ?", (email,)).fetchone()
        if not row or row["expires_at"] <= int(time.time()) or row["attempts"] >= 5:
            raise bad
        if not hmac.compare_digest(row["code_hash"], security.token_hash(f"{row['id']}:{body.code.strip()}")):
            conn.execute("UPDATE password_resets SET attempts = attempts + 1 WHERE user_id = ?", (row["id"],))
            wrong = True                                                  # committed below, then refused
        else:
            conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                     (security.hash_password(body.new_password), int(time.time()), row["id"]))
            conn.execute("DELETE FROM password_resets WHERE user_id = ?", (row["id"],))
            for sid in [r["id"] for r in conn.execute("SELECT id FROM sessions WHERE user_id = ? AND revoked_at IS NULL", (row["id"],))]:
                revoke_session(conn, sid)                                 # every device must sign in again
    if wrong:
        raise bad
    return {"detail": "Password changed. Sign in with the new password."}


class ChangePasswordIn(BaseModel):
    current_password: str = Field(max_length=256)
    new_password: str = Field(max_length=256)


@app.post("/api/v1/auth/change-password")
def change_password(body: ChangePasswordIn, auth: Auth = Depends(current), s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    limit(f"change-pw:{auth.user.id}", 5, 300)
    row = conn.execute("SELECT password_hash FROM users WHERE id = ?", (auth.user.id,)).fetchone()
    if not security.verify_password(body.current_password, row["password_hash"]):
        raise HTTPException(401, "The current password is wrong.")
    if problem := security.password_problem(body.new_password, s.password_min_length):
        raise HTTPException(422, problem)
    with transaction(conn):
        conn.execute("UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?",
                     (security.hash_password(body.new_password), int(time.time()), auth.user.id))
        for sid in [r["id"] for r in conn.execute("SELECT id FROM sessions WHERE user_id = ? AND id != ? AND revoked_at IS NULL",
                                                   (auth.user.id, auth.session_id))]:
            revoke_session(conn, sid)                                     # other devices sign in again
    return {"detail": "Password changed. Other devices were signed out."}


# ------------------------------------------------------------------ users
@app.get("/api/v1/users/me")
def me(auth: Auth = Depends(current)):
    return {"user": auth.user, "session_id": auth.session_id}


class ProfileIn(BaseModel):
    display_name: str = Field(min_length=1, max_length=60)


@app.patch("/api/v1/users/me")
def update_me(body: ProfileIn, auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    conn.execute("UPDATE users SET display_name = ? WHERE id = ?", (body.display_name.strip(), auth.user.id))
    return {"user": user_out(conn.execute("SELECT * FROM users WHERE id = ?", (auth.user.id,)).fetchone())}


class DeleteAccountIn(BaseModel):
    password: str = Field(max_length=256)


@app.post("/api/v1/users/me/delete")
def delete_me(body: DeleteAccountIn, auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    limit(f"delete:{auth.user.id}", 5, 300)
    row = conn.execute("SELECT password_hash FROM users WHERE id = ?", (auth.user.id,)).fetchone()
    if not security.verify_password(body.password, row["password_hash"]):
        raise HTTPException(401, "The password is wrong.")
    with transaction(conn):
        conn.execute("DELETE FROM users WHERE id = ?", (auth.user.id,))   # cascades: sessions, tokens, records, resets
    return {"detail": "Account and all cloud data deleted. Data on your devices is not touched."}


@app.get("/api/v1/users/me/sessions")
def sessions(auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    rows = conn.execute("SELECT id, device_name, created_at, last_seen_at FROM sessions WHERE user_id = ? AND revoked_at IS NULL "
                        "ORDER BY last_seen_at DESC", (auth.user.id,)).fetchall()
    return {"sessions": [{**dict(r), "current": r["id"] == auth.session_id} for r in rows]}


@app.get("/api/v1/devices")
def devices(auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    """Signed-in devices (one session per device)."""
    return sessions(auth, conn)


@app.delete("/api/v1/devices/{sid}", status_code=204)
@app.delete("/api/v1/users/me/sessions/{sid}", status_code=204)
def end_session(sid: str, auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    with transaction(conn):
        if not conn.execute("SELECT 1 FROM sessions WHERE id = ? AND user_id = ?", (sid, auth.user.id)).fetchone():
            raise HTTPException(404, "No such session.")                  # someone else's session looks the same as none
        revoke_session(conn, sid)
    return Response(status_code=204)


# ------------------------------------------------------------------ sync
class Change(BaseModel):
    kind: str
    id: str
    updated_at: int
    deleted: bool = False
    data: dict[str, Any] = Field(default_factory=dict)


class PushIn(BaseModel):
    device_id: str = Field(min_length=8, max_length=64)
    changes: list[Change]


def record_out(r: sqlite3.Row) -> dict[str, Any]:
    return {"kind": r["kind"], "id": r["id"], "updated_at": r["updated_at"], "deleted": bool(r["deleted"]),
            "data": json.loads(r["data"]), "seq": r["seq"], "device_id": r["device_id"]}


@app.get("/api/v1/sync/changes")
def pull(since: int = 0, limit_: int = Query(500, alias="limit"), auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    n = max(1, min(limit_, 1000))
    rows = conn.execute("SELECT * FROM records WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", (auth.user.id, since, n + 1)).fetchall()
    more = len(rows) > n
    rows = rows[:n]
    latest = conn.execute("SELECT sync_seq FROM users WHERE id = ?", (auth.user.id,)).fetchone()["sync_seq"]
    return {"changes": [record_out(r) for r in rows], "next": rows[-1]["seq"] if rows else since, "more": more, "server_seq": latest}


@app.post("/api/v1/sync/changes")
def push(body: PushIn, auth: Auth = Depends(current), s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    """Last-writer-wins per record, by (updated_at, device_id); repeats of the same change are no-ops.
    A change older than the server's copy is not applied: the newer server copy comes back in
    `conflicts`, so the device keeps the newer version instead of overwriting it."""
    limit(f"sync:{auth.user.id}", 120, 60)
    if len(body.changes) > s.sync_max_changes:
        raise HTTPException(413, f"At most {s.sync_max_changes} changes per request.")
    accepted, unchanged, conflicts, rejected = [], [], [], []
    future = NOW_MS() + 86_400_000
    with transaction(conn):
        used = conn.execute("SELECT COALESCE(SUM(LENGTH(data)), 0) AS n FROM records WHERE user_id = ?", (auth.user.id,)).fetchone()["n"]
        seq = conn.execute("SELECT sync_seq FROM users WHERE id = ?", (auth.user.id,)).fetchone()["sync_seq"]
        for c in body.changes:
            data = json.dumps(c.data, separators=(",", ":"), sort_keys=True)
            why = ("unknown kind" if c.kind not in KINDS else "bad id" if not RECORD_ID.match(c.id)
                   else "record too large" if len(data) > s.sync_max_record_bytes
                   else "timestamp in the future" if c.updated_at > future or c.updated_at <= 0 else None)
            if why:
                rejected.append({"kind": c.kind, "id": c.id, "reason": why}); continue
            old = conn.execute("SELECT * FROM records WHERE user_id = ? AND kind = ? AND id = ?", (auth.user.id, c.kind, c.id)).fetchone()
            if old is not None:
                if (old["updated_at"], old["device_id"]) == (c.updated_at, body.device_id) and old["data"] == data and bool(old["deleted"]) == c.deleted:
                    unchanged.append(c.id); continue                              # duplicate request
                if (old["updated_at"], old["device_id"]) >= (c.updated_at, body.device_id):
                    conflicts.append(record_out(old)); continue                   # server copy is newer: keep it
            if used + len(data) - (len(old["data"]) if old else 0) > s.user_quota_bytes:
                rejected.append({"kind": c.kind, "id": c.id, "reason": "cloud storage quota reached"}); continue
            seq += 1
            used += len(data) - (len(old["data"]) if old else 0)
            conn.execute("INSERT OR REPLACE INTO records (user_id, kind, id, data, updated_at, deleted, device_id, seq) VALUES (?,?,?,?,?,?,?,?)",
                         (auth.user.id, c.kind, c.id, data, c.updated_at, int(c.deleted), body.device_id, seq))
            accepted.append({"kind": c.kind, "id": c.id, "seq": seq})
        conn.execute("UPDATE users SET sync_seq = ? WHERE id = ?", (seq, auth.user.id))
    return {"accepted": accepted, "unchanged": unchanged, "conflicts": conflicts, "rejected": rejected, "server_seq": seq}


@app.delete("/api/v1/sync/data")
def delete_cloud_data(auth: Auth = Depends(current), conn: sqlite3.Connection = Depends(db)):
    with transaction(conn):
        n = conn.execute("DELETE FROM records WHERE user_id = ?", (auth.user.id,)).rowcount
        conn.execute("UPDATE users SET sync_seq = sync_seq + 1 WHERE id = ?", (auth.user.id,))
    return {"deleted": n, "detail": "Cloud copies deleted. Data on your devices is not touched."}


# ------------------------------------------------------------------ online AI gateway
class Msg(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(max_length=32_000)


class AiIn(BaseModel):
    messages: list[Msg] = Field(min_length=1, max_length=40)
    max_tokens: int = Field(default=512, ge=1, le=4096)
    temperature: float = Field(default=0.3, ge=0.0, le=1.5)
    # "auto" (shared providers if enabled, else the server's own model), "ollama", or a shared provider id
    provider: str = Field(default="auto", pattern="^(auto|ollama|gemini|groq|openrouter)$")


def _today() -> str:
    return time.strftime("%Y-%m-%d", time.gmtime())


def _used_today(conn: sqlite3.Connection, user_id: str) -> int:
    return conn.execute("SELECT COALESCE(SUM(requests), 0) AS n FROM ai_usage WHERE user_id = ? AND day = ?", (user_id, _today())).fetchone()["n"]


@app.get("/api/v1/providers")
def shared_providers(auth: Auth = Depends(current), s: Settings = Depends(settings), conn: sqlite3.Connection = Depends(db)):
    """What shared access this server offers. Never includes keys."""
    configured = s.shared()
    return {"shared_enabled": bool(configured), "daily_limit": s.shared_daily_limit,
            "providers": [{"id": pid, "title": providers.SPECS[pid].title, "model": model, "available": True} for pid, _k, model in configured],
            "usage": {"used": _used_today(conn, auth.user.id), "limit": s.shared_daily_limit} if configured else None,
            "server_model": s.ai_model}


def ollama_client(s: Settings) -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=s.ollama_url, timeout=httpx.Timeout(10.0, read=120.0), transport=app.state.ollama_transport)


@app.post("/api/v1/ai/chat")
@app.post("/api/v1/ai/chat/stream")
async def ai_chat(body: AiIn, auth: Auth = Depends(current), s: Settings = Depends(settings)):
    """Streams NDJSON: {"type":"token","text":..}* then {"type":"done","model":..,"served_by":"cloud"} or {"type":"error",..}."""
    limit(f"ai:{auth.user.id}", 30, 60)
    if sum(len(m.content) for m in body.messages) > s.ai_max_prompt_chars:
        raise HTTPException(413, "The conversation is too long for one request.")
    shared = [p for p in s.shared() if body.provider in ("auto", p[0])]
    if body.provider not in ("auto", "ollama") and not shared:
        raise HTTPException(404, "That shared provider isn't enabled on this server.")
    if shared and body.provider != "ollama":
        return StreamingResponse(_shared_stream(auth, s, body, shared), media_type="application/x-ndjson")
    payload = {"model": s.ai_model, "stream": True, "messages": [m.model_dump() for m in body.messages],
               "options": {"num_predict": min(body.max_tokens, s.ai_max_tokens), "temperature": body.temperature}}

    async def stream():
        line = lambda o: (json.dumps(o, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            async with ollama_client(s) as client, client.stream("POST", "/api/chat", json=payload) as r:
                if r.status_code != 200:
                    yield line({"type": "error", "message": f"The online AI is unavailable (HTTP {r.status_code})."}); return
                async for raw in r.aiter_lines():
                    if not raw.strip():
                        continue
                    ev = json.loads(raw)
                    if text := ev.get("message", {}).get("content"):
                        yield line({"type": "token", "text": text})
                    if ev.get("done"):
                        yield line({"type": "done", "model": s.ai_model, "served_by": "cloud",
                                    "tokens": ev.get("eval_count"), "duration_ms": int((ev.get("total_duration") or 0) / 1e6)})
                        return
                yield line({"type": "error", "message": "The online AI stopped early."})
        except (httpx.HTTPError, json.JSONDecodeError) as e:
            log.warning("ai gateway: %s", type(e).__name__)
            yield line({"type": "error", "message": "The online AI is unavailable right now."})

    return StreamingResponse(stream(), media_type="application/x-ndjson")


@app.get("/api/v1/models")
async def models(auth: Auth = Depends(current), s: Settings = Depends(settings)):
    """The online model(s) this server offers (what the app shows as the online provider)."""
    ok = False
    try:
        async with ollama_client(s) as c:
            ok = (await c.get("/api/tags", timeout=3)).status_code == 200
    except httpx.HTTPError:
        pass
    return {"models": [{"id": s.ai_model, "provider": "ollama", "served_by": "cloud", "available": ok}]}


async def _shared_stream(auth: Auth, s: Settings, body: AiIn, shared: list[tuple[str, str, str]]):
    """Tries the administrator's providers in order; moves on only before the first token. Counts usage per user."""
    line = lambda o: (json.dumps(o, ensure_ascii=False) + "\n").encode("utf-8")
    conn = connect(s.db_path)
    try:
        if _used_today(conn, auth.user.id) >= s.shared_daily_limit:
            yield line({"type": "error", "message": f"Daily shared limit reached ({s.shared_daily_limit} requests). Try tomorrow, or use your own key or this phone's AI."})
            return
        msgs = [m.model_dump(include={"role", "content"}) for m in body.messages]
        last_error = "no shared provider answered"
        for pid, key, model in shared:
            with transaction(conn):
                conn.execute("INSERT INTO ai_usage (user_id, day, provider, requests) VALUES (?, ?, ?, 1) "
                             "ON CONFLICT(user_id, day, provider) DO UPDATE SET requests = requests + 1", (auth.user.id, _today(), pid))
            tokens = 0
            async for kind, text in providers.stream(providers.SPECS[pid], key, model, msgs, min(body.max_tokens, s.ai_max_tokens),
                                                     body.temperature, app.state.provider_transport):
                if kind == "token":
                    tokens += 1
                    yield line({"type": "token", "text": text})
                elif kind == "done":
                    yield line({"type": "done", "model": model, "provider": pid, "served_by": "shared"})
                    return
                else:
                    last_error = f"{providers.SPECS[pid].title}: {text}"
                    if tokens:                                   # never stitch two providers into one answer
                        yield line({"type": "error", "message": last_error})
                        return
                    break
        yield line({"type": "error", "message": last_error})
    finally:
        conn.close()


# ------------------------------------------------------------------ health
@app.get("/api/v1/health")
async def health(s: Settings = Depends(settings)):
    ai = False
    try:
        async with ollama_client(s) as c:
            ai = (await c.get("/api/tags", timeout=3)).status_code == 200
    except httpx.HTTPError:
        pass
    return {"status": "ok", "service": "abhify-cloud", "version": VERSION, "ai_available": ai, "ai_model": s.ai_model if ai else None}
