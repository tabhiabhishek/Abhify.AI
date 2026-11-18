"""Abhify cloud service: auth, isolation, sync and AI gateway. Each test has its own temporary database;
Ollama is a fake httpx transport (no model, no network)."""
from __future__ import annotations

import json
import re
import sqlite3
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from app import config, main, security

PW = "Correct-Horse-9"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("ABHIFY_CLOUD_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ABHIFY_CLOUD_ENV", "dev")
    config.get_settings.cache_clear()
    security.limiter.reset()
    main.app.state.ollama_transport = fake_ollama()
    main.app.state.provider_transport = None
    with TestClient(main.app) as c:
        c.data_dir = tmp_path
        yield c
    config.get_settings.cache_clear()


def fake_ollama(tokens=("Gradient ", "descent ", "minimizes loss."), status=200, cut=False):
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/tags":
            return httpx.Response(200, json={"models": []})
        body = json.loads(req.content)
        assert body["stream"] is True and body["messages"][-1]["role"] == "user"
        lines = [json.dumps({"message": {"content": t}, "done": False}) for t in tokens]
        if not cut:
            lines.append(json.dumps({"done": True, "eval_count": len(tokens), "total_duration": 5_000_000}))
        return httpx.Response(status, content=("\n".join(lines) + "\n").encode())
    return httpx.MockTransport(handler)


def register(c, email="asha@example.com", name="Asha", device="Pixel 8"):
    r = c.post("/api/v1/auth/register", json={"email": email, "password": PW, "display_name": name, "device_name": device})
    assert r.status_code == 201, r.text
    return r.json()


def H(tokens):
    return {"Authorization": f"Bearer {tokens['access_token']}"}


def db(c):
    conn = sqlite3.connect(c.data_dir / "abhify-cloud.db"); conn.row_factory = sqlite3.Row
    return conn


# ------------------------------------------------------------------ registration and login
def test_register_login_me(client):
    t = register(client)
    assert t["user"]["email"] == "asha@example.com" and t["access_token"].startswith("at_") and t["refresh_token"].startswith("rt_")
    me = client.get("/api/v1/users/me", headers=H(t)).json()
    assert me["user"]["display_name"] == "Asha" and me["session_id"] == t["session_id"]
    t2 = client.post("/api/v1/auth/login", json={"email": "ASHA@example.com ", "password": PW, "device_name": "Laptop"}).json()
    assert t2["session_id"] != t["session_id"]
    assert len(client.get("/api/v1/users/me/sessions", headers=H(t2)).json()["sessions"]) == 2


def test_registration_validation(client):
    bad = lambda **k: client.post("/api/v1/auth/register", json={"email": "a@b.co", "password": PW, "display_name": "A", **k})
    assert bad(email="not-an-email").status_code == 422
    assert bad(password="short1A").status_code == 422
    assert bad(password="alllowercase123").status_code == 422
    assert bad().status_code == 201
    assert bad().status_code == 409                                          # duplicate


def test_wrong_and_unknown_login_look_the_same(client):
    register(client)
    a = client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": "Wrong-pass-1"})
    b = client.post("/api/v1/auth/login", json={"email": "nobody@example.com", "password": "Wrong-pass-1"})
    assert a.status_code == b.status_code == 401 and a.json() == b.json()


def test_passwords_and_tokens_are_never_stored_in_plaintext(client):
    t = register(client)
    conn = db(client)
    assert conn.execute("SELECT password_hash FROM users").fetchone()[0].startswith("scrypt$32768$8$1$")
    dump = "\n".join(conn.iterdump())
    assert PW not in dump and t["access_token"] not in dump and t["refresh_token"] not in dump


def test_login_rate_limited(client):
    register(client)
    codes = [client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": "Wrong-pass-1"}).status_code for _ in range(7)]
    assert codes[:5] == [401] * 5 and codes[5] == 429


# ------------------------------------------------------------------ tokens
PROTECTED = [("get", "/api/v1/users/me"), ("patch", "/api/v1/users/me"), ("get", "/api/v1/users/me/sessions"),
             ("post", "/api/v1/auth/logout"), ("get", "/api/v1/sync/changes"), ("post", "/api/v1/sync/changes"),
             ("delete", "/api/v1/sync/data"), ("post", "/api/v1/ai/chat"), ("post", "/api/v1/auth/change-password"),
             ("post", "/api/v1/users/me/delete")]


@pytest.mark.parametrize("method,path", PROTECTED)
def test_unauthorized_requests_are_refused(client, method, path):
    for headers in ({}, {"Authorization": "Bearer at_forged"}, {"Authorization": "Basic x"}):
        assert getattr(client, method)(path, headers=headers).status_code == 401


def test_expired_access_token_then_refresh(client):
    t = register(client)
    db(client).execute("UPDATE tokens SET expires_at = 0 WHERE kind = 'access'").connection.commit()
    assert client.get("/api/v1/users/me", headers=H(t)).status_code == 401
    n = client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).json()
    assert n["session_id"] == t["session_id"] and n["refresh_token"] != t["refresh_token"]
    assert client.get("/api/v1/users/me", headers=H(n)).status_code == 200


def test_refresh_rotation_and_reuse_detection(client):
    t = register(client)
    n1 = client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).json()
    assert client.get("/api/v1/users/me", headers=H(t)).status_code == 401      # old access token dropped
    reuse = client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]})
    assert reuse.status_code == 401 and "safety" in reuse.json()["detail"]
    # the whole session is gone: even the legitimately rotated tokens stop working
    assert client.get("/api/v1/users/me", headers=H(n1)).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": n1["refresh_token"]}).status_code == 401


def test_logout_revokes_both_tokens(client):
    t = register(client)
    assert client.post("/api/v1/auth/logout", headers=H(t)).status_code == 204
    assert client.get("/api/v1/users/me", headers=H(t)).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).status_code == 401


def test_end_another_session_and_isolation(client):
    a1 = register(client)
    a2 = client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": PW, "device_name": "Tablet"}).json()
    b = register(client, "ravi@example.com", "Ravi")
    assert client.delete(f"/api/v1/users/me/sessions/{a2['session_id']}", headers=H(b)).status_code == 404   # not Ravi's
    assert client.delete(f"/api/v1/users/me/sessions/{a2['session_id']}", headers=H(a1)).status_code == 204
    assert client.get("/api/v1/users/me", headers=H(a2)).status_code == 401


# ------------------------------------------------------------------ password reset / change
def outbox_code(c, email):
    lines = (c.data_dir / "outbox.log").read_text().splitlines()
    return [re.search(r"code=(\d{8})", l).group(1) for l in lines if f"to={email}" in l][-1]


def test_forgot_and_reset_password(client):
    t = register(client)
    unknown = client.post("/api/v1/auth/forgot-password", json={"email": "nobody@example.com"})
    known = client.post("/api/v1/auth/forgot-password", json={"email": "asha@example.com"})
    assert unknown.status_code == known.status_code == 202 and unknown.json() == known.json()
    assert "nobody@example.com" not in (client.data_dir / "outbox.log").read_text()
    code = outbox_code(client, "asha@example.com")
    wrong = client.post("/api/v1/auth/reset-password", json={"email": "asha@example.com", "code": "00000000", "new_password": "New-Password-77"})
    assert wrong.status_code == 400
    assert db(client).execute("SELECT attempts FROM password_resets").fetchone()[0] == 1      # the failed try is counted
    ok = client.post("/api/v1/auth/reset-password", json={"email": "asha@example.com", "code": code, "new_password": "New-Password-77"})
    assert ok.status_code == 200
    assert client.get("/api/v1/users/me", headers=H(t)).status_code == 401                   # all sessions ended
    again = client.post("/api/v1/auth/reset-password", json={"email": "asha@example.com", "code": code, "new_password": "Other-Password-77"})
    assert again.status_code == 400                                                          # single use
    assert client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": "New-Password-77"}).status_code == 200


def test_reset_code_locks_after_five_wrong_attempts(client):
    register(client)
    client.post("/api/v1/auth/forgot-password", json={"email": "asha@example.com"})
    code = outbox_code(client, "asha@example.com")
    for _ in range(5):
        client.post("/api/v1/auth/reset-password", json={"email": "asha@example.com", "code": "11111111", "new_password": "New-Password-77"})
    r = client.post("/api/v1/auth/reset-password", json={"email": "asha@example.com", "code": code, "new_password": "New-Password-77"})
    assert r.status_code == 400


def test_change_password_signs_out_other_devices(client):
    a1 = register(client)
    a2 = client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": PW}).json()
    assert client.post("/api/v1/auth/change-password", headers=H(a1), json={"current_password": "x", "new_password": "New-Password-77"}).status_code == 401
    assert client.post("/api/v1/auth/change-password", headers=H(a1), json={"current_password": PW, "new_password": "New-Password-77"}).status_code == 200
    assert client.get("/api/v1/users/me", headers=H(a1)).status_code == 200
    assert client.get("/api/v1/users/me", headers=H(a2)).status_code == 401


# ------------------------------------------------------------------ sync
def ch(kind, rid, ts, **data):
    return {"kind": kind, "id": rid, "updated_at": ts, "deleted": data.pop("_deleted", False), "data": data}


def push(c, t, changes, device="device-aaaa"):
    r = c.post("/api/v1/sync/changes", headers=H(t), json={"device_id": device, "changes": changes})
    assert r.status_code == 200, r.text
    return r.json()


def test_first_and_incremental_sync(client):
    t = register(client)
    now = int(time.time() * 1000)
    r = push(client, t, [ch("conversation", "conv-0001", now, title="Gradient descent"),
                         ch("message", "msg-00000001", now, conversation_id="conv-0001", role="user", content="hi"),
                         ch("memory", "mem-00000001", now, content="I like PyTorch")])
    assert [a["seq"] for a in r["accepted"]] == [1, 2, 3]
    first = client.get("/api/v1/sync/changes?since=0", headers=H(t)).json()
    assert len(first["changes"]) == 3 and first["next"] == 3 and not first["more"]
    push(client, t, [ch("memory", "mem-00000002", now + 1, content="Exam on Friday")])
    inc = client.get(f"/api/v1/sync/changes?since={first['next']}", headers=H(t)).json()
    assert [c["id"] for c in inc["changes"]] == ["mem-00000002"]


def test_pagination(client):
    t = register(client)
    now = int(time.time() * 1000)
    push(client, t, [ch("memory", f"mem-{i:08d}", now + i, content=str(i)) for i in range(12)])
    page = client.get("/api/v1/sync/changes?since=0&limit=5", headers=H(t)).json()
    assert len(page["changes"]) == 5 and page["more"] and page["next"] == 5


def test_duplicate_push_is_idempotent(client):
    t = register(client)
    c = [ch("memory", "mem-00000001", 1_700_000_000_000, content="x")]
    push(client, t, c)
    again = push(client, t, c)
    assert again["accepted"] == [] and again["unchanged"] == ["mem-00000001"] and again["conflicts"] == []
    assert again["server_seq"] == 1                                          # no new version


def test_older_change_never_overwrites_newer(client):
    t = register(client)
    push(client, t, [ch("conversation", "conv-0001", 2_000, title="Newer title")], device="phone-aaaa")
    r = push(client, t, [ch("conversation", "conv-0001", 1_000, title="Older title")], device="tablet-bbbb")
    assert r["accepted"] == [] and r["conflicts"][0]["data"]["title"] == "Newer title"
    newer = push(client, t, [ch("conversation", "conv-0001", 3_000, title="Newest")], device="tablet-bbbb")
    assert len(newer["accepted"]) == 1
    # same timestamp from two devices: deterministic winner (higher device id), loser gets the winner back
    push(client, t, [ch("memory", "mem-00000009", 5_000, content="from b")], device="device-bbbb")
    tie = push(client, t, [ch("memory", "mem-00000009", 5_000, content="from a")], device="device-aaaa")
    assert tie["conflicts"][0]["data"]["content"] == "from b"


def test_tombstones_and_validation(client):
    t = register(client)
    push(client, t, [ch("memory", "mem-00000001", 1_000, content="x")])
    r = push(client, t, [ch("memory", "mem-00000001", 2_000, _deleted=True)])
    assert len(r["accepted"]) == 1
    assert client.get("/api/v1/sync/changes?since=1", headers=H(t)).json()["changes"][0]["deleted"] is True
    bad = push(client, t, [ch("secrets", "abcdefgh1", 1_000), ch("memory", "x", 1_000),
                           ch("memory", "mem-00000002", int(time.time() * 1000) + 3 * 86_400_000), ch("memory", "mem-00000003", 1, blob="x" * 70_000)])
    assert [x["reason"] for x in bad["rejected"]] == ["unknown kind", "bad id", "timestamp in the future", "record too large"]
    assert client.post("/api/v1/sync/changes", headers=H(t), json={"device_id": "dev-aaaa", "changes": [ch("memory", f"m-{i:08d}", 1) for i in range(501)]}).status_code == 413


def test_sync_data_is_isolated_per_user(client):
    a = register(client)
    b = register(client, "ravi@example.com", "Ravi")
    push(client, a, [ch("memory", "mem-00000001", 1_000, content="Asha's secret")])
    assert client.get("/api/v1/sync/changes?since=0", headers=H(b)).json()["changes"] == []
    push(client, b, [ch("memory", "mem-00000001", 9_000, content="Ravi's note")])          # same id, other user
    assert client.get("/api/v1/sync/changes?since=0", headers=H(a)).json()["changes"][0]["data"]["content"] == "Asha's secret"
    client.delete("/api/v1/sync/data", headers=H(b))
    assert len(client.get("/api/v1/sync/changes?since=0", headers=H(a)).json()["changes"]) == 1


def test_quota(client, monkeypatch):
    monkeypatch.setenv("ABHIFY_CLOUD_USER_QUOTA_BYTES", "200")
    config.get_settings.cache_clear()
    t = register(client)
    r = push(client, t, [ch("memory", "mem-00000001", 1, content="x" * 100), ch("memory", "mem-00000002", 1, content="y" * 150)])
    assert len(r["accepted"]) == 1 and r["rejected"][0]["reason"] == "cloud storage quota reached"


def test_delete_cloud_data_and_account(client):
    t = register(client)
    push(client, t, [ch("memory", "mem-00000001", 1, content="x")])
    assert client.delete("/api/v1/sync/data", headers=H(t)).json()["deleted"] == 1
    assert client.post("/api/v1/users/me/delete", headers=H(t), json={"password": "nope"}).status_code == 401
    assert client.post("/api/v1/users/me/delete", headers=H(t), json={"password": PW}).status_code == 200
    assert client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": PW}).status_code == 401
    assert db(client).execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0


# ------------------------------------------------------------------ AI gateway
def events(resp):
    return [json.loads(l) for l in resp.text.splitlines() if l.strip()]


def test_ai_streams_and_labels_the_model(client):
    t = register(client)
    r = client.post("/api/v1/ai/chat", headers=H(t), json={"messages": [{"role": "user", "content": "What is gradient descent?"}]})
    ev = events(r)
    assert r.headers["content-type"].startswith("application/x-ndjson")
    assert "".join(e["text"] for e in ev if e["type"] == "token") == "Gradient descent minimizes loss."
    assert ev[-1] == {"type": "done", "model": "qwen2.5:3b", "served_by": "cloud", "tokens": 3, "duration_ms": 5}


def test_ai_errors_are_reported_not_hidden(client):
    t = register(client)
    msg = {"messages": [{"role": "user", "content": "hi"}]}
    main.app.state.ollama_transport = fake_ollama(status=500)
    assert events(client.post("/api/v1/ai/chat", headers=H(t), json=msg))[-1]["type"] == "error"
    main.app.state.ollama_transport = fake_ollama(cut=True)                          # stream interrupted
    assert events(client.post("/api/v1/ai/chat", headers=H(t), json=msg))[-1] == {"type": "error", "message": "The online AI stopped early."}
    def down(req): raise httpx.ConnectError("refused")
    main.app.state.ollama_transport = httpx.MockTransport(down)
    assert events(client.post("/api/v1/ai/chat", headers=H(t), json=msg))[-1]["type"] == "error"
    assert client.get("/api/v1/health").json()["ai_available"] is False
    assert client.post("/api/v1/ai/chat", headers=H(t), json={"messages": [{"role": "hacker", "content": "x"}]}).status_code == 422


def test_health_and_headers(client):
    r = client.get("/api/v1/health")
    assert r.json()["status"] == "ok" and r.json()["ai_available"] is True
    assert r.headers["cache-control"] == "no-store" and "strict-transport-security" not in r.headers     # dev: no HSTS
    assert client.get("/docs").status_code == 404 and client.get("/openapi.json").status_code == 404


def test_production_refuses_unsafe_config(monkeypatch):
    for env in ({"ABHIFY_CLOUD_ENV": "prod"}, {"ABHIFY_CLOUD_ENV": "prod", "ABHIFY_CLOUD_PUBLIC_URL": "http://x.example"},
                {"ABHIFY_CLOUD_ENV": "prod", "ABHIFY_CLOUD_PUBLIC_URL": "https://x.example"}, {"ABHIFY_CLOUD_ENV": "staging"}):
        for k in ("ABHIFY_CLOUD_PUBLIC_URL", "ABHIFY_CLOUD_SMTP_HOST", "ABHIFY_CLOUD_SMTP_FROM"):
            monkeypatch.delenv(k, raising=False)
        for k, v in env.items():
            monkeypatch.setenv(k, v)
        with pytest.raises(RuntimeError):
            config.Settings().check()
    monkeypatch.setenv("ABHIFY_CLOUD_ENV", "prod"); monkeypatch.setenv("ABHIFY_CLOUD_PUBLIC_URL", "https://x.example")
    monkeypatch.setenv("ABHIFY_CLOUD_SMTP_HOST", "smtp.example.com"); monkeypatch.setenv("ABHIFY_CLOUD_SMTP_FROM", "no-reply@example.com")
    config.Settings().check()                                                        # https + SMTP: fine


def test_scrypt_hash_roundtrip():
    h = security.hash_password("Abc-12345678")
    assert security.verify_password("Abc-12345678", h) and not security.verify_password("abc-12345678", h)
    assert not security.verify_password("x", "md5$abc") and not security.verify_password("x", "garbage")


def test_device_and_model_routes(client):
    a1 = register(client)
    a2 = client.post("/api/v1/auth/login", json={"email": "asha@example.com", "password": PW, "device_name": "Tablet"}).json()
    devs = client.get("/api/v1/devices", headers=H(a1)).json()["sessions"]
    assert {d["device_name"] for d in devs} == {"Pixel 8", "Tablet"}
    assert client.delete(f"/api/v1/devices/{a2['session_id']}", headers=H(a1)).status_code == 204
    assert client.get("/api/v1/users/me", headers=H(a2)).status_code == 401
    m = client.get("/api/v1/models", headers=H(a1)).json()["models"][0]
    assert m == {"id": "qwen2.5:3b", "provider": "ollama", "served_by": "cloud", "available": True}
    assert client.get("/api/v1/models").status_code == 401
    r = client.post("/api/v1/ai/chat/stream", headers=H(a1), json={"messages": [{"role": "user", "content": "hi"}]})
    assert events(r)[-1]["type"] == "done"


# ------------------------------------------------------------------ shared provider access (RC.3), mocked providers only
FAKE_GEMINI = "AIzaFAKE-shared-0000000000000000000000"
FAKE_GROQ = "gsk_FAKEsharedkey000000000000000000000000"


def shared_env(monkeypatch, **extra):
    env = {"ABHIFY_CLOUD_SHARED_ENABLED": "true", "ABHIFY_CLOUD_SHARED_GEMINI_KEY": FAKE_GEMINI, "ABHIFY_CLOUD_SHARED_GROQ_KEY": FAKE_GROQ,
           "ABHIFY_CLOUD_SHARED_DAILY_LIMIT": "3", **extra}
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    config.get_settings.cache_clear()


def fake_providers(gemini_status=200, groq_tokens=("Fine ", "answer"), cut_gemini_after=None):
    seen = []
    def handler(req: httpx.Request) -> httpx.Response:
        seen.append((req.url.host, req.headers.get("authorization"), json.loads(req.content)))
        if "googleapis" in req.url.host:
            if gemini_status != 200:
                return httpx.Response(gemini_status, json=[{"error": {"code": gemini_status, "message": "quota exceeded"}}], headers={"retry-after": "20"})
            chunks = ['data: {"choices":[{"delta":{"content":"Gem"}}]}']
            if cut_gemini_after is None:
                chunks.append("data: [DONE]")
            else:
                chunks.append('data: {"error":{"code":502,"message":"upstream failed"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}')
            return httpx.Response(200, content=("\n".join(chunks) + "\n").encode())
        lines = [": OPENROUTER PROCESSING"] + [json.dumps({"choices": [{"delta": {"content": t}}]}).join(["data: ", ""]) for t in groq_tokens] + ["data: [DONE]"]
        return httpx.Response(200, content=("\n".join(lines) + "\n").encode())
    return httpx.MockTransport(handler), seen


def test_shared_access_off_by_default_and_never_leaks_keys(client, monkeypatch):
    t = register(client)
    assert client.get("/api/v1/providers", headers=H(t)).json()["shared_enabled"] is False
    shared_env(monkeypatch)
    r = client.get("/api/v1/providers", headers=H(t))
    assert r.json()["shared_enabled"] is True and [p["id"] for p in r.json()["providers"]] == ["gemini", "groq"]
    assert FAKE_GEMINI not in r.text and FAKE_GROQ not in r.text and "key" not in json.dumps(r.json()["providers"])
    assert client.get("/api/v1/providers").status_code == 401


def test_shared_falls_over_only_before_the_first_word(client, monkeypatch):
    shared_env(monkeypatch)
    t = register(client)
    transport, seen = fake_providers(gemini_status=429)
    main.app.state.provider_transport = transport
    ev = events(client.post("/api/v1/ai/chat", headers=H(t), json={"messages": [{"role": "user", "content": "hi"}]}))
    assert "".join(e["text"] for e in ev if e["type"] == "token") == "Fine answer"
    assert ev[-1] == {"type": "done", "model": "llama-3.3-70b-versatile", "provider": "groq", "served_by": "shared"}
    assert seen[0][1] == f"Bearer {FAKE_GEMINI}" and seen[1][1] == f"Bearer {FAKE_GROQ}"      # each provider gets its own key
    assert all("models" not in body for _h, _a, body in seen)                              # never a paid fallback list
    transport, seen = fake_providers(cut_gemini_after=1)                                   # Gemini fails mid-answer
    main.app.state.provider_transport = transport
    ev = events(client.post("/api/v1/ai/chat", headers=H(t), json={"messages": [{"role": "user", "content": "hi"}]}))
    assert ev[-1]["type"] == "error" and "upstream failed" in ev[-1]["message"]
    assert [h for h, _a, _b in seen] == ["generativelanguage.googleapis.com"]               # Groq not asked: no stitching


def test_shared_daily_limit_and_usage(client, monkeypatch):
    shared_env(monkeypatch)
    t = register(client)
    main.app.state.provider_transport = fake_providers()[0]
    msg = {"messages": [{"role": "user", "content": "hi"}]}
    for _ in range(3):
        assert events(client.post("/api/v1/ai/chat", headers=H(t), json=msg))[-1]["type"] == "done"
    last = events(client.post("/api/v1/ai/chat", headers=H(t), json=msg))[-1]
    assert last["type"] == "error" and "Daily shared limit" in last["message"]
    assert client.get("/api/v1/providers", headers=H(t)).json()["usage"] == {"used": 3, "limit": 3}
    other = register(client, "ravi@example.com", "Ravi")                                    # limits are per user
    assert events(client.post("/api/v1/ai/chat", headers=H(other), json=msg))[-1]["type"] == "done"


def test_shared_explicit_provider_and_ollama_choice(client, monkeypatch):
    shared_env(monkeypatch)
    t = register(client)
    transport, seen = fake_providers()
    main.app.state.provider_transport = transport
    ev = events(client.post("/api/v1/ai/chat", headers=H(t), json={"provider": "groq", "messages": [{"role": "user", "content": "hi"}]}))
    assert ev[-1]["provider"] == "groq" and [h for h, _a, _b in seen] == ["api.groq.com"]
    ev = events(client.post("/api/v1/ai/chat", headers=H(t), json={"provider": "ollama", "messages": [{"role": "user", "content": "hi"}]}))
    assert ev[-1]["served_by"] == "cloud"                                                  # the server's own model
    assert client.post("/api/v1/ai/chat", headers=H(t), json={"provider": "openrouter", "messages": [{"role": "user", "content": "hi"}]}).status_code == 404


def test_shared_paid_models_refused_at_startup(monkeypatch):
    for k in ("ABHIFY_CLOUD_PUBLIC_URL", "ABHIFY_CLOUD_SHARED_ALLOW_CHARGED"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("ABHIFY_CLOUD_ENV", "dev")
    monkeypatch.setenv("ABHIFY_CLOUD_SHARED_ENABLED", "true")
    monkeypatch.setenv("ABHIFY_CLOUD_SHARED_OPENROUTER_KEY", "sk-or-v1-FAKE")
    monkeypatch.setenv("ABHIFY_CLOUD_SHARED_OPENROUTER_MODEL", "anthropic/claude-sonnet")
    with pytest.raises(RuntimeError, match="may be charged"):
        config.Settings().check()
    monkeypatch.setenv("ABHIFY_CLOUD_SHARED_ALLOW_CHARGED", "true")
    config.Settings().check()


def test_provider_error_messages_never_echo_auth_details():
    from app import providers
    assert providers.error_message(401, '{"error":{"message":"Invalid key sk-or-v1-abc"}}', None) == "the shared key was rejected"
    assert providers.error_message(429, '[{"error":{"message":"quota"}}]', "7") == "rate limit or daily quota reached (retry in 7s): quota"
    assert providers.parse_line(": OPENROUTER PROCESSING") is None and providers.parse_line("data: [DONE]") == ("done", "")
