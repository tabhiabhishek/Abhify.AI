"""Automatic first-run setup (setup_manager.py) against a fake Ollama over real HTTP.
No real downloads, installers or signatures: those three hooks are replaced; everything else is real."""
import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

import local_file_service as lfs
import setup_manager as sm
import system_service as system


class Ollama(BaseHTTPRequestHandler):
    up = False
    models: dict = {}
    pull_mode = "ok"            # ok | abort | slow
    embed_ok = True
    pulls = 0

    def log_message(self, *a):
        pass

    def _send(self, code, obj=None, raw=None):
        body = raw if raw is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not Ollama.up:
            return self._send(503, raw=b"not running")       # not JSON: the app sees "unreachable"
        if self.path == "/api/tags":
            return self._send(200, {"models": [{"name": n, "digest": d, "size": 1000} for n, d in Ollama.models.items()]})
        if self.path == "/api/version":
            return self._send(200, {"version": "0.40.5"})
        self._send(404, {"error": "nf"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/pull":
            Ollama.pulls += 1
            self.send_response(200); self.end_headers()
            self.wfile.write(b'{"status":"pulling","total":100,"completed":10}\n'); self.wfile.flush()
            if Ollama.pull_mode == "slow":
                time.sleep(3)
            if Ollama.pull_mode == "abort":
                self.connection.close(); return
            self.wfile.write(b'{"status":"success"}\n')
            Ollama.models[req["model"] if ":" in req["model"] else req["model"] + ":latest"] = "sha256:" + "ab" * 32
            return
        if self.path == "/api/generate":
            return self._send(200, {"response": "OK"})
        if self.path == "/api/embed":
            return self._send(200, {"embeddings": [[0.1, 0.2, 0.3]] if Ollama.embed_ok else [[]]})
        self._send(404, {"error": "nf"})


@pytest.fixture
def env(data_root, monkeypatch):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Ollama)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    from config import get_settings
    monkeypatch.setenv("OLLAMA_BASE_URL", f"http://127.0.0.1:{srv.server_address[1]}")
    get_settings.cache_clear()
    Ollama.up, Ollama.models, Ollama.pull_mode, Ollama.embed_ok, Ollama.pulls = False, {}, "ok", True, 0
    monkeypatch.setattr(system, "detect_gpus", lambda: [])
    monkeypatch.setattr(system, "_ram", lambda: {"total_gb": 8.0, "available_gb": 6.0})
    monkeypatch.setattr(system, "internet_available", lambda: True)
    monkeypatch.setattr(sm.platform, "system", lambda: "Windows")
    monkeypatch.setattr(sm, "ollama_exe", lambda: None)
    calls = {"download": 0, "verify": 0, "install": 0}

    async def fake_download(url, dest, progress):
        calls["download"] += 1
        dest.write_bytes(b"MZ fake installer"); progress(17, 17)

    def fake_verify(path, expected):
        calls["verify"] += 1
        return calls.get("signature_ok", True), "Valid: CN=Ollama Inc., O=Ollama Inc."

    def fake_install(path):
        calls["install"] += 1
        Ollama.up = True                                   # "installed and started"
        return 0
    monkeypatch.setattr(sm, "_download", fake_download)
    monkeypatch.setattr(sm, "_verify_publisher", fake_verify)
    monkeypatch.setattr(sm, "_run_installer", fake_install)
    sm.JOB.update(status="idle", step=None, error=None); sm._stop["reason"] = None; system.PULLS.clear()
    yield calls
    srv.shutdown(); get_settings.cache_clear(); system.PULLS.clear()


def run(coro):
    return asyncio.run(coro)


async def until_finished(timeout=20):
    end = time.time() + timeout
    while sm.JOB["status"] == "running" and time.time() < end:
        await asyncio.sleep(0.05)
    return dict(sm.JOB)


def full_setup():
    async def go():
        await sm.start(confirm=True)
        return await until_finished()
    return run(go())


def test_plan_lists_components_sizes_and_downloads_nothing(env):
    p = run(sm.plan())
    assert p["profile"] == "minimal" and "8.0 GB RAM" in p["reason"]                 # CPU-only, 8 GB → Minimal
    actions = {c["id"]: c["action"] for c in p["components"]}
    assert actions == {"ollama": "install", "model:qwen2.5:1.5b": "download", "model:nomic-embed-text": "download"}
    assert p["download_gb"] > 2 and p["needs_internet"] and p["ready_to_start"]
    assert env == {"download": 0, "verify": 0, "install": 0} and Ollama.pulls == 0


def test_confirmation_is_required(env):
    with pytest.raises(ValueError, match="confirm"):
        run(sm.start(confirm=False))


def test_full_automatic_setup_installs_verifies_and_records(env):
    job = full_setup()
    assert job["status"] == "done", job
    assert env == {"download": 1, "verify": 1, "install": 1}
    assert set(Ollama.models) == {"qwen2.5:1.5b", "nomic-embed-text:latest"}
    comp = sm.load_components()
    assert comp["profile"] == "minimal" and comp["ollama"]["version"] == "0.40.5"
    assert {m["role"] for m in comp["models"].values()} == {"llm", "embed"}
    assert all(m["digest"].startswith("sha256:") for m in comp["models"].values())
    assert comp["validation"]["embedding_dims"] == 3 and comp["validation"]["llm_reply"] == "OK"
    assert lfs.load_settings()["setup_completed"] is True
    assert not (sm.downloads_dir() / "OllamaSetup.exe").exists()                     # installer removed after use


def test_second_run_downloads_nothing(env):
    full_setup()
    pulls_before = Ollama.pulls
    p = run(sm.plan())
    assert p["nothing_to_do"] and p["download_gb"] == 0 and not p["needs_internet"]
    assert full_setup()["status"] == "done"
    assert Ollama.pulls == pulls_before and env["download"] == 1 and env["install"] == 1


def test_unsigned_installer_is_deleted_and_never_run(env):
    env["signature_ok"] = False
    job = full_setup()
    assert job["status"] == "failed" and "not validly signed" in job["error"]
    assert env["install"] == 0 and not (sm.downloads_dir() / "OllamaSetup.exe").exists()
    assert lfs.load_settings()["setup_completed"] is False


def test_interrupted_model_download_fails_then_retry_resumes(env):
    Ollama.pull_mode = "abort"
    job = full_setup()
    assert job["status"] == "failed" and "qwen2.5:1.5b" in job["error"]
    assert lfs.load_settings()["setup_completed"] is False
    Ollama.pull_mode = "ok"
    assert full_setup()["status"] == "done"
    assert env["install"] == 1                                                        # Ollama step not repeated


def test_pause_and_resume(env):
    Ollama.up = True
    Ollama.pull_mode = "slow"

    async def go():
        await sm.start(confirm=True)
        await asyncio.sleep(1.0)
        sm.stop("pause")
        return await until_finished()
    job = run(go())
    assert job["status"] == "paused"
    Ollama.pull_mode = "ok"
    assert full_setup()["status"] == "done"


def test_no_internet_on_first_launch_explains_and_refuses(env, monkeypatch):
    monkeypatch.setattr(system, "internet_available", lambda: False)
    p = run(sm.plan())
    assert not p["ready_to_start"] and any("No internet" in x for x in p["problems"])
    with pytest.raises(ValueError, match="No internet"):
        run(sm.start(confirm=True))


def test_insufficient_disk_is_refused_before_downloading(env, monkeypatch):
    monkeypatch.setattr(system, "check_disk_for", lambda m: {"model": m, "ok": False, "required_free_gb": 9.9,
                                                           "free_gb": 0.5, "estimated_download_gb": 1,
                                                           "models_dir": "x"})
    p = run(sm.plan())
    assert any("Not enough disk space for the models" in x for x in p["problems"])
    with pytest.raises(ValueError, match="disk space"):
        run(sm.start(confirm=True))
    assert env["download"] == 0


def test_setup_not_complete_until_validation_passes(env):
    Ollama.embed_ok = False
    job = full_setup()
    assert job["status"] == "failed" and "embedding" in job["error"]
    assert lfs.load_settings()["setup_completed"] is False and sm.load_components() == {}


def test_resumable_download_continues_a_partial_file(data_root, tmp_path):
    """The real _download against a local HTTP server that honours Range requests."""
    payload = bytes(range(256)) * 400

    class Files(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            start = int((self.headers.get("Range") or "bytes=0-").split("=")[1].split("-")[0])
            body = payload[start:]
            self.send_response(206 if start else 200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Files)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    dest = tmp_path / "file.bin"
    Path(str(dest) + ".part").write_bytes(payload[:30000])                          # an interrupted download
    seen = []
    sm._stop["reason"] = None
    try:
        asyncio.run(sm._download(f"http://127.0.0.1:{srv.server_address[1]}/f", dest, lambda d, t: seen.append((d, t))))
    finally:
        srv.shutdown()
    assert dest.read_bytes() == payload and seen[-1] == (len(payload), len(payload)) and seen[0][0] > 30000


def test_setup_endpoints_are_pc_only(env):
    from fastapi.testclient import TestClient
    import main
    phone = TestClient(main.app, client=("192.168.1.50", 5000))
    lfs.update_settings(companion={"enabled": True})
    for method, path in (("get", "/setup/plan"), ("post", "/setup/auto"), ("get", "/setup/auto"),
                         ("post", "/setup/auto/pause")):
        assert getattr(phone, method)(path).status_code in (401, 403), path


@pytest.mark.skipif(__import__("sys").platform != "win32", reason="Authenticode is Windows-only")
def test_publisher_check_is_real_and_injection_safe(tmp_path):
    """No stand-in: the real Authenticode check (the dev.6 review found it rejected every file because
    `powershell -Command` doesn't pass extra arguments as $args)."""
    import os
    ps = Path(os.environ["SystemRoot"]) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    ok, detail = sm._verify_publisher(ps, "O=Microsoft Corporation")
    assert ok and detail.startswith("Valid")
    assert sm._verify_publisher(ps, "O=Ollama Inc.")[0] is False                      # wrong publisher
    hostile = tmp_path / "x'; New-Item pwned.txt; '.exe"
    hostile.write_bytes(b"MZ not signed")
    assert sm._verify_publisher(hostile, "O=Ollama Inc.")[0] is False
    assert not (Path.cwd() / "pwned.txt").exists() and not (tmp_path / "pwned.txt").exists()
