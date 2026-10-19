"""Offline-first: after setup, the app must work with EVERY non-local network connection blocked.

Real app (FastAPI lifespan, SQLite, ChromaDB, LangGraph agent, document indexing and retrieval);
only Ollama is a fake on 127.0.0.1. Outbound attempts are recorded and refused at the socket level.
"""
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi.testclient import TestClient

import local_file_service as lfs

LOCAL = ("127.0.0.1", "::1", "localhost", "testserver", "testclient")


class FakeOllama(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/tags":
            return self._json({"models": [{"name": "qwen2.5:1.5b", "size": 1}, {"name": "nomic-embed-text:latest", "size": 1}]})
        if self.path == "/api/version":
            return self._json({"version": "0.40.5"})
        self._json({})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/embed":
            texts = req["input"] if isinstance(req["input"], list) else [req["input"]]
            vecs = [[1.0 if w in t.lower() else 0.0 for w in ("codeword", "heron", "python", "memory")] + [0.1] for t in texts]
            return self._json({"model": req["model"], "embeddings": vecs})
        if self.path == "/api/chat":
            system = next((m["content"] for m in req["messages"] if m["role"] == "system"), "")
            answer = "The codeword is BLUE-HERON-9." if "BLUE-HERON-9" in system else "Offline answer."
            lines = [{"model": req["model"], "created_at": "2026-10-19T00:00:00Z",
                      "message": {"role": "assistant", "content": answer}, "done": False},
                     {"model": req["model"], "created_at": "2026-10-19T00:00:00Z",
                      "message": {"role": "assistant", "content": ""}, "done": True, "done_reason": "stop"}]
            body = b"".join(json.dumps(x).encode() + b"\n" for x in lines)
            self.send_response(200); self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        if self.path == "/api/generate":
            return self._json({"response": "OK"})
        self._json({})


@pytest.fixture
def offline(data_root, monkeypatch):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    from config import get_settings
    monkeypatch.setenv("OLLAMA_BASE_URL", f"http://127.0.0.1:{srv.server_address[1]}")
    get_settings.cache_clear()
    lfs.update_settings(setup_completed=True, model_profile="minimal",
                        models={"llm": "qwen2.5:1.5b", "embed": "nomic-embed-text"})
    attempts = []
    real_connect, real_create, real_getaddrinfo = socket.socket.connect, socket.create_connection, socket.getaddrinfo

    def host_of(addr):
        return addr[0] if isinstance(addr, tuple) else str(addr)

    def guard_connect(self, addr):
        if host_of(addr) not in LOCAL:
            attempts.append(host_of(addr))
            raise OSError("network blocked by test_offline")
        return real_connect(self, addr)

    def guard_create(addr, *a, **k):
        if host_of(addr) not in LOCAL:
            attempts.append(host_of(addr))
            raise OSError("network blocked by test_offline")
        return real_create(addr, *a, **k)

    def guard_dns(host, *a, **k):
        if host not in LOCAL and host is not None:
            attempts.append(f"dns:{host}")
            raise socket.gaierror("DNS blocked by test_offline")
        return real_getaddrinfo(host, *a, **k)
    monkeypatch.setattr(socket.socket, "connect", guard_connect)
    monkeypatch.setattr(socket, "create_connection", guard_create)
    monkeypatch.setattr(socket, "getaddrinfo", guard_dns)
    import agent, knowledge, system_service as system
    knowledge.reset_caches()
    agent.get_llm.cache_clear(); agent.get_agent.cache_clear()       # don't reuse another test's Ollama URL
    system._NET.update(at=0.0, ok=False, known=False, refreshing=False)
    yield attempts
    srv.shutdown(); get_settings.cache_clear(); knowledge.reset_caches()
    agent.get_llm.cache_clear(); agent.get_agent.cache_clear()


def test_everything_core_works_with_the_network_blocked(offline):
    import main
    with TestClient(main.app) as c:                                  # startup with no network
        h = c.get("/health").json()
        assert h["ollama"] is True and h["llm_ready"] and h["embed_ready"] and h["internet"] is False
        assert c.post("/memory", json={"content": "I prefer Python examples"}).status_code == 200
        up = c.post("/knowledge/upload", files={"file": ("notes.md", b"The test codeword is BLUE-HERON-9.", "text/markdown")})
        assert up.status_code == 200, up.text                       # local embeddings + Chroma
        r = c.post("/chat", json={"message": "Search my documents: what is the test codeword?"}).json()
        assert "BLUE-HERON-9" in r["answer"] and "notes.md" in r["sources"]   # local retrieval + local LLM
        assert len(c.get("/conversations").json()["conversations"]) == 1
    with TestClient(main.app) as c:                                  # restart, still offline
        assert c.get("/memory").json()["memories"][0]["content"] == "I prefer Python examples"
        assert len(c.get("/conversations").json()["conversations"]) == 1
        assert c.get("/knowledge").json()["chunks"] >= 1
    # Nothing may have tried to reach a model registry, package index, model hub or telemetry service.
    forbidden = ("ollama.ai", "ollama.com", "pypi", "huggingface", "posthog", "chroma", "github")
    assert not [a for a in offline if any(f in a for f in forbidden)], offline


def test_web_search_and_news_fail_gracefully_offline(offline):
    import main
    with TestClient(main.app) as c:
        r = c.get("/news")
        assert r.status_code in (200, 503)                           # cached/none, never a crash
        assert c.post("/chat", json={"message": "hello"}).status_code == 200
