"""Mode B companion access, admin protection behind tunnels, update checks, upgrade preservation."""
import asyncio
import sqlite3

import httpx
import pytest
from fastapi.testclient import TestClient

import database as db
import local_file_service as lfs
import memory_service as mem


@pytest.fixture
def app_client(data_root, monkeypatch):
    import main
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


def remote(app_client):
    """Same app, but requests appear to come from a phone on the LAN."""
    import main
    return TestClient(main.app, client=("192.168.1.42", 50000))


def test_remote_api_blocked_when_companion_off(app_client):
    r = remote(app_client)
    assert r.get("/memory").status_code == 403
    assert r.get("/health").status_code == 200                  # open, reveals nothing personal
    assert r.get("/").status_code == 200                         # PWA shell still loads


def test_remote_needs_valid_token(app_client):
    token = app_client.post("/companion/enable").json()["token"]
    assert "token_sha256" in lfs.load_settings()["companion"]
    assert token not in lfs.settings_path().read_text()          # only the hash is stored
    r = remote(app_client)
    assert r.get("/memory").status_code == 401
    assert r.get("/memory", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert r.get("/memory", headers={"Authorization": f"Bearer {token}"}).status_code == 200


def test_admin_stays_local_only_even_with_token(app_client):
    token = app_client.post("/companion/enable").json()["token"]
    r = remote(app_client)
    h = {"Authorization": f"Bearer {token}"}
    for method, path in [("post", "/backup"), ("post", "/knowledge/watcher/start"), ("post", "/companion/enable"),
                         ("post", "/setup/models/pull"), ("put", "/knowledge/folder")]:
        resp = getattr(r, method)(path, headers=h, json={"model": "x", "path": "C:/x"})
        assert resp.status_code == 403, path


def test_tunnel_requests_from_loopback_count_as_remote(app_client):
    app_client.post("/companion/enable")
    tunneled = {"X-Forwarded-For": "100.64.0.7"}
    assert app_client.get("/memory", headers=tunneled).status_code == 401
    assert app_client.post("/backup", headers=tunneled).status_code in (401, 403)


def test_disable_revokes_token(app_client):
    token = app_client.post("/companion/enable").json()["token"]
    app_client.post("/companion/disable")
    assert remote(app_client).get("/memory", headers={"Authorization": f"Bearer {token}"}).status_code == 403


def test_every_api_route_is_guarded():
    import companion
    import main
    paths = {r.path for r in main.app.routes if hasattr(r, "methods")}
    for p in paths:
        assert companion.is_api(p), p                           # nothing slips past the guard


# --------------------------------------------------------------------------- update check
def _run(coro):
    return asyncio.run(coro)


def test_update_check_not_configured(data_root):
    import system_service as system
    r = _run(system.check_for_update())
    assert r["configured"] is False and r["update_available"] is False


def test_update_check_rejects_http_and_offline(data_root, monkeypatch):
    import system_service as system
    lfs.update_settings(updates={"manifest_url": "http://example.com/latest.json"})
    assert "HTTPS" in _run(system.check_for_update())["error"]
    lfs.update_settings(updates={"manifest_url": "https://example.com/latest.json"})
    monkeypatch.setattr(system, "internet_available", lambda: False)
    assert "Offline" in _run(system.check_for_update())["error"]


@pytest.mark.parametrize("manifest,expect", [
    ({"version": "9.0.0", "windows": {"url": "https://x/s.exe", "sha256": "a" * 64}}, True),
    ({"version": "1.0.0", "windows": {"url": "https://x/s.exe", "sha256": "a" * 64}}, False),
    ({"version": "9.0.0", "windows": {"url": "http://x/s.exe", "sha256": "a" * 64}}, "invalid"),
    ({"version": "9.0.0", "windows": {"url": "https://x/s.exe", "sha256": "not-a-hash"}}, "invalid"),
    ({"windows": {}}, "invalid"),
])
def test_update_manifest_validation(data_root, monkeypatch, manifest, expect):
    import system_service as system
    lfs.update_settings(updates={"manifest_url": "https://example.com/latest.json"})
    monkeypatch.setattr(system, "internet_available", lambda: True)

    def handler(request):
        return httpx.Response(200, json=manifest)
    real = httpx.AsyncClient
    monkeypatch.setattr(system.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler)))
    r = _run(system.check_for_update())
    if expect == "invalid":
        assert "Invalid update manifest" in r["error"]
    else:
        assert r["update_available"] is expect and r["sha256"] == "a" * 64


# --------------------------------------------------------------------------- upgrade preservation (v3 -> v4)
def test_upgrade_from_m1_schema_preserves_history_memories_and_docs(data_root, monkeypatch):
    v3 = db.MIGRATIONS[:3]
    monkeypatch.setattr(db, "MIGRATIONS", v3)
    monkeypatch.setattr(db, "SCHEMA_VERSION", 3)
    db.migrate()                                                 # an M1 (schema v3) database
    cid = mem.ensure_conversation(None, "hello")
    mem.add_message(cid, "user", "hello")
    mem.add_memory("Goal: fintech ML interviews", "goal")
    with db.connect() as c:
        c.execute("INSERT INTO documents (path, source, sha256, size, chunks, status) "
                  "VALUES ('notes.pdf', 'upload', 'abc', 10, 3, 'indexed')")
    monkeypatch.undo()                                           # back to the current (v4) code
    info = db.migrate()
    assert info["from"] == 3 and info["to"] == db.SCHEMA_VERSION and info["backup"]
    assert mem.recent_messages(cid)[0]["content"] == "hello"
    assert mem.list_memories()[0]["content"] == "Goal: fintech ML interviews"
    d = db.get_document("upload", "notes.pdf")
    assert d["chunks"] == 3 and d["status"] == "indexed"


def test_whisper_falls_back_to_cpu(monkeypatch):
    import sys
    import types
    import transcribe
    calls = []

    class FakeModel:
        def __init__(self, name, device, compute_type):
            calls.append(device)
            if device == "cuda":
                raise RuntimeError("CUDA driver not found")
    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=FakeModel))
    monkeypatch.setattr(transcribe, "_model", None)
    assert transcribe._load() and calls == ["cuda", "cpu"]
    monkeypatch.setattr(transcribe, "_model", None)
