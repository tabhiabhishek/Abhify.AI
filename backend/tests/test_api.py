"""API-level tests with the LLM / network mocked out."""
import io
import zipfile

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


def test_health_degraded_without_ollama(client):
    h = client.get("/health").json()
    assert h["app"] == "Abhify.AI" and h["status"] == "degraded" and h["ollama"] is False
    assert h["internet"] is False and h["knowledge_chunks"] is None


def test_frontend_is_rebranded(client):
    html = client.get("/").text
    assert "<title>Abhify.AI</title>" in html


def test_news_offline_returns_503(client):
    r = client.get("/news?refresh=true")
    assert r.status_code == 503 and "Offline" in r.json()["detail"]


def test_chat_uses_agent_and_returns_thread(client, monkeypatch):
    import main

    async def fake_agent(message, conversation_id):
        return {"answer": f"echo: {message}", "conversation_id": conversation_id or "c1",
                "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    r = client.post("/chat", json={"message": "hi"}).json()
    assert r["answer"] == "echo: hi" and r["thread_id"] == "c1"


def test_chat_failure_is_503_not_crash(client, monkeypatch):
    import main

    async def broken(*_):
        raise ConnectionError("Ollama down")
    monkeypatch.setattr(main, "run_agent", broken)
    assert client.post("/chat", json={"message": "hi"}).status_code == 503


def test_memory_api(client):
    m = client.post("/memory", json={"content": "Goal: crack ML interviews", "category": "goal"}).json()
    assert client.get("/memory").json()["memories"][0]["id"] == m["id"]
    assert client.patch(f"/memory/{m['id']}", json={"pinned": True}).json()["pinned"] == 1
    assert client.delete(f"/memory/{m['id']}").status_code == 200
    assert client.delete(f"/memory/{m['id']}").status_code == 404
    assert client.delete("/memory").status_code == 400                    # needs confirm


def test_preferences_api(client):
    assert client.put("/preferences", json={"memory_enabled": False}).json()["memory_enabled"] is False
    assert client.put("/preferences", json={"history_retention_days": -1}).status_code == 422


def test_progress_api_unchanged(client):
    client.post("/progress", json={"user_id": "u", "kind": "mcq", "category": "ml", "correct": True})
    s = client.get("/progress/u").json()
    assert s["mcq_total"] == 1 and s["accuracy"] == 100


def test_model_pull_requires_confirm(client):
    r = client.post("/setup/models/pull", json={"model": "qwen2.5:1.5b"})
    assert r.status_code == 400 and "confirmation" in r.json()["detail"]
    assert client.post("/setup/models/pull", json={"model": "bad name; rm -rf"}).status_code == 422


def test_backup_and_bad_restore(client):
    assert client.post("/backup").status_code == 200
    assert len(client.get("/backup").json()["backups"]) == 1
    junk = io.BytesIO()
    with zipfile.ZipFile(junk, "w") as z:
        z.writestr("hello.txt", "not a backup")
    r = client.post("/backup/restore", files={"file": ("x.zip", junk.getvalue(), "application/zip")})
    assert r.status_code == 400


def test_export_backup_downloads_zip(client):
    r = client.get("/backup/export")
    assert r.status_code == 200 and r.headers["content-type"] == "application/zip"
    assert "backup-manifest.json" in zipfile.ZipFile(io.BytesIO(r.content)).namelist()


def test_admin_endpoints_reject_remote_hosts():
    import main
    from starlette.requests import Request
    remote = Request({"type": "http", "client": ("192.168.1.50", 5000), "headers": []})
    with pytest.raises(HTTPException) as e:
        main.local_only(remote)
    assert e.value.status_code == 403
    main.local_only(Request({"type": "http", "client": ("127.0.0.1", 5000), "headers": []}))
