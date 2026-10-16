"""Task B regression tests: model download and inference checks against a fake Ollama over real HTTP.

Each scenario is something that happens on real PCs: Ollama refuses a model, the network drops or
Ollama is restarted mid-download, Ollama reports success but the model is missing, the model can't
load (e.g. not enough RAM), or Ollama isn't running at all. A download or test is reported as
successful only when it really is.
"""
import asyncio
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import system_service as system

MODEL = "qwen2.5:1.5b"


class FakeOllama(BaseHTTPRequestHandler):
    scenario = "success"
    installed: list[str] = []

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/tags":
            self._json(200, {"models": [{"name": n} for n in FakeOllama.installed]})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(length) or b"{}")
        s = FakeOllama.scenario
        if self.path == "/api/pull":
            if s == "http_500":
                return self._json(500, {"error": "pull model manifest: file does not exist"})
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            events = [{"status": "pulling manifest"},
                      {"status": "pulling abc", "total": 1000, "completed": 400}]
            if s == "error_event":
                events.append({"error": "max retries exceeded: dial tcp: lookup registry.ollama.ai: no such host"})
            elif s in ("success", "success_but_missing"):
                events += [{"status": "pulling abc", "total": 1000, "completed": 1000},
                           {"status": "verifying sha256 digest"}, {"status": "success"}]
            for ev in events:
                self.wfile.write((json.dumps(ev) + "\n").encode())
                self.wfile.flush()
            if s == "abort":                                  # connection killed mid-download (Ollama crashed)
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if s == "success":
                FakeOllama.installed.append(req["model"])
            # "clean_close": the stream simply ends without the final "success"
        elif self.path == "/api/generate":
            if s == "gen_oom":
                return self._json(500, {"error": "model requires more system memory (5.6 GiB) than is available (3.1 GiB)"})
            if s == "gen_empty":
                return self._json(200, {"response": "   "})
            return self._json(200, {"response": "OK"})


@pytest.fixture
def ollama(data_root, monkeypatch):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    from config import get_settings
    monkeypatch.setenv("OLLAMA_BASE_URL", f"http://127.0.0.1:{srv.server_address[1]}")
    get_settings.cache_clear()
    FakeOllama.installed = []
    system.PULLS.clear()
    monkeypatch.setattr(system, "check_disk_for", lambda m: {"ok": True, "required_free_gb": 1, "free_gb": 99,
                                                           "models_dir": "x", "model": m})
    yield FakeOllama
    srv.shutdown()
    get_settings.cache_clear()
    system.PULLS.clear()


def pull(scenario):
    FakeOllama.scenario = scenario

    async def go():
        await system.start_pull(MODEL, confirm=True)
        for _ in range(200):
            if system.PULLS[MODEL]["status"] != "downloading":
                break
            await asyncio.sleep(0.05)
        return dict(system.PULLS[MODEL])
    return asyncio.run(go())


def test_successful_download_is_verified(ollama):
    st = pull("success")
    assert st["status"] == "done" and st["completed"] == st["total"] == 1000


def test_stream_ending_without_success_is_a_failure(ollama):
    st = pull("clean_close")
    assert st["status"] == "failed" and "stopped before it finished" in st["message"]


def test_connection_killed_mid_download_is_a_failure(ollama):
    st = pull("abort")
    assert st["status"] == "failed"
    assert "interrupted" in st["message"] or "stopped before it finished" in st["message"]


def test_network_error_reported_by_ollama(ollama):
    st = pull("error_event")
    assert st["status"] == "failed" and "no such host" in st["message"]


def test_ollama_refuses_model(ollama):
    st = pull("http_500")
    assert st["status"] == "failed" and "HTTP 500" in st["message"] and "does not exist" in st["message"]


def test_success_but_model_not_installed_is_a_failure(ollama):
    st = pull("success_but_missing")
    assert st["status"] == "failed" and "isn't listed as installed" in st["message"]


def test_retry_after_failure_succeeds(ollama):
    assert pull("abort")["status"] == "failed"
    assert pull("success")["status"] == "done"                       # Download again works


def test_ollama_not_running(data_root, monkeypatch):
    from config import get_settings
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]                                    # closed port: nothing listens
    monkeypatch.setenv("OLLAMA_BASE_URL", f"http://127.0.0.1:{port}")
    get_settings.cache_clear()
    monkeypatch.setattr(system, "check_disk_for", lambda m: {"ok": True})
    system.PULLS.clear()
    try:
        st = pull("success")
        assert st["status"] == "failed" and "isn't reachable" in st["message"]
        r = asyncio.run(system.test_inference(MODEL))
        assert r["ok"] is False and "isn't reachable" in r["error"]
    finally:
        get_settings.cache_clear()
        system.PULLS.clear()


def test_inference_ok_only_with_real_answer(ollama):
    FakeOllama.scenario = "gen_ok"
    r = asyncio.run(system.test_inference(MODEL))
    assert r["ok"] is True and r["reply"] == "OK"
    FakeOllama.scenario = "gen_empty"
    r = asyncio.run(system.test_inference(MODEL))
    assert r["ok"] is False and "empty answer" in r["error"]


def test_model_load_failure_shows_ollamas_reason(ollama):
    FakeOllama.scenario = "gen_oom"
    r = asyncio.run(system.test_inference(MODEL))
    assert r["ok"] is False and "more system memory" in r["error"]


def test_download_needs_consent_and_disk(data_root, monkeypatch):
    with pytest.raises(ValueError, match="confirmation"):
        asyncio.run(system.start_pull(MODEL, confirm=False))
    monkeypatch.setattr(system, "check_disk_for", lambda m: {"ok": False, "required_free_gb": 2.1, "free_gb": 0.4,
                                                           "models_dir": "C:/x"})
    with pytest.raises(ValueError, match="Not enough disk space"):
        asyncio.run(system.start_pull(MODEL, confirm=True))
