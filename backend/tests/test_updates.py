"""Update manager (updates.py): signed manifests, verified downloads, confirmed installs, rollback
archive, model updates with restore. Local fixtures only: no real release channel exists."""
import asyncio
import base64
import hashlib
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

import ed25519_verify as ed
import local_file_service as lfs
import updates
from version import __version__

SECRET = hashlib.sha256(b"abhify test publisher key - never a real key").digest()
PUBLIC = base64.b64encode(ed.public_key(SECRET)).decode()
OTHER = hashlib.sha256(b"someone else").digest()
PAYLOAD = b"MZ" + b"installer-bytes" * 1000
SHA = hashlib.sha256(PAYLOAD).hexdigest()


def manifest(version="9.0.0", **pkg):
    return {"app": "Abhify.AI", "version": version, "released": "2026-11-01", "min_supported": "2.0.0",
            "notes": "test", "packages": {"windows-x64": {"url": "https://example.test/Setup.exe", "size": len(PAYLOAD),
                                                          "sha256": SHA, "authenticode_subject": None, **pkg}}}


def envelope(m=None, secret=SECRET, key_id="test"):
    return json.dumps(updates.sign_envelope(m or manifest(), secret, key_id)).encode()


@pytest.fixture
def upd(data_root, monkeypatch):
    lfs.update_settings(updates={"manifest_url": "https://example.test/latest.json", "trusted_keys": {"test": PUBLIC}})
    monkeypatch.setattr(updates, "_internet", lambda: True)
    seen = {"requests": [], "manifest": envelope(), "payload": PAYLOAD}

    def handler(request: httpx.Request):
        seen["requests"].append(request)
        if request.url.path.endswith("latest.json"):
            return httpx.Response(200, content=seen["manifest"])
        rng = request.headers.get("range")
        start = int(rng.split("=")[1].split("-")[0]) if rng else 0
        return httpx.Response(206 if start else 200, content=seen["payload"][start:])
    real = httpx.AsyncClient
    monkeypatch.setattr(updates.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler)))
    launched = []
    monkeypatch.setattr(updates, "_launch", lambda path: launched.append(path))
    monkeypatch.setattr(updates.platform, "system", lambda: "Windows")
    seen["launched"] = launched
    yield seen


def run(c):
    return asyncio.run(c)


# --------------------------------------------------------------------------- Ed25519
@pytest.mark.parametrize("sk,pk,msg,sig", [
    ("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
     "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
     "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
    ("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
     "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
     "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
])
def test_ed25519_rfc8032_vectors(sk, pk, msg, sig):
    sk, pk, msg, sig = map(bytes.fromhex, (sk, pk, msg, sig))
    assert ed.public_key(sk) == pk and ed.sign(sk, msg) == sig and ed.verify(pk, msg, sig)
    assert not ed.verify(pk, msg + b"x", sig) and not ed.verify(pk, msg, sig[:-1] + bytes([sig[-1] ^ 1]))


# --------------------------------------------------------------------------- check
def test_not_configured_without_url_or_trusted_key(data_root):
    assert run(updates.check())["update_available"] is False
    lfs.update_settings(updates={"manifest_url": "http://example.test/x.json"})
    assert "HTTPS" in run(updates.check())["error"]
    lfs.update_settings(updates={"manifest_url": "https://example.test/x.json"})
    assert "trusted publisher key" in run(updates.check())["error"]


def test_offline_check_fails_softly(upd, monkeypatch):
    monkeypatch.setattr(updates, "_internet", lambda: False)
    r = run(updates.check(force=True))
    assert r["update_available"] is False and "Offline" in r["error"] and not upd["requests"]


def test_signed_manifest_is_accepted(upd):
    r = run(updates.check(force=True))
    assert r["update_available"] is True and r["latest"] == "9.0.0" and r["verified"] is True
    req = upd["requests"][0]
    assert str(req.url) == "https://example.test/latest.json" and not req.url.query          # nothing about the user
    assert not {"authorization", "cookie", "x-abhify-token"} & {h.lower() for h in req.headers}


@pytest.mark.parametrize("bad,reason", [
    (lambda: envelope(secret=OTHER), "signature does not match"),                         # wrong signer
    (lambda: envelope(key_id="unknown"), "unknown key"),
    (lambda: json.dumps(manifest()).encode(), "not a signed"),                            # unsigned (old format)
    (lambda: _tampered(), "signature does not match"),
    (lambda: envelope(manifest(url="http://example.test/S.exe")), "HTTPS"),
    (lambda: envelope(manifest(sha256="nothex")), "checksum"),
    (lambda: envelope({**manifest(), "app": "Other"}), "different application"),
    (lambda: b"{" * 70000, "too large"),
])
def test_untrusted_or_invalid_manifests_are_rejected(upd, bad, reason):
    upd["manifest"] = bad()
    r = run(updates.check(force=True))
    assert r["update_available"] is False and reason in r["error"], r


def _tampered():
    env = updates.sign_envelope(manifest(), SECRET, "test")
    m = json.loads(base64.b64decode(env["manifest"])); m["packages"]["windows-x64"]["url"] = "https://evil.test/x.exe"
    env["manifest"] = base64.b64encode(json.dumps(m, sort_keys=True, separators=(",", ":")).encode()).decode()
    return json.dumps(env).encode()


def test_checks_are_not_repeated_within_the_interval(upd):
    run(updates.check(force=True)); n = len(upd["requests"])
    r = run(updates.check())
    assert r.get("cached") and len(upd["requests"]) == n


def test_no_update_when_same_or_older(upd):
    upd["manifest"] = envelope(manifest(version=__version__))
    assert run(updates.check(force=True))["update_available"] is False


# --------------------------------------------------------------------------- download and install
def test_download_verifies_and_install_needs_confirmation(upd):
    run(updates.check(force=True))
    r = run(updates.download())
    assert r == {"prepared": True, "version": "9.0.0", "size": len(PAYLOAD)}
    staged = updates.staging_dir() / "Abhify.AI-Setup-9.0.0.exe"
    assert staged.read_bytes() == PAYLOAD
    with pytest.raises(updates.UpdateError, match="confirmation"):
        updates.install(False)
    assert not upd["launched"]
    out = updates.install(True)
    assert out["launched"] and upd["launched"] == [staged]
    assert (updates.archive_dir() / staged.name).exists()                              # kept for rollback


def test_download_resumes_a_partial_file(upd):
    run(updates.check(force=True))
    updates.staging_dir().mkdir(parents=True, exist_ok=True)
    (updates.staging_dir() / "Abhify.AI-Setup-9.0.0.exe.part").write_bytes(PAYLOAD[:5000])
    run(updates.download())
    ranges = [r.headers.get("range") for r in upd["requests"] if not r.url.path.endswith(".json")]
    assert ranges == ["bytes=5000-"]


def test_corrupt_or_oversized_downloads_are_deleted(upd):
    run(updates.check(force=True))
    upd["payload"] = PAYLOAD[:-1] + b"X"                                                 # same size, wrong bytes
    with pytest.raises(updates.UpdateError, match="doesn't match"):
        run(updates.download())
    assert not list(updates.staging_dir().glob("*"))
    upd["payload"] = PAYLOAD + b"extra"
    with pytest.raises(updates.UpdateError, match="larger than the signed"):
        run(updates.download())


def test_wrong_windows_publisher_is_rejected(upd, monkeypatch):
    upd["manifest"] = envelope(manifest(authenticode_subject="O=Abhify Publisher"))
    monkeypatch.setattr(updates, "_verify_publisher", lambda p, e: (False, "NotSigned"))
    run(updates.check(force=True))
    with pytest.raises(updates.UpdateError, match="Windows signature"):
        run(updates.download())
    assert not list(updates.staging_dir().glob("*.exe"))


def test_install_re_verifies_the_staged_file(upd):
    run(updates.check(force=True)); run(updates.download())
    (updates.staging_dir() / "Abhify.AI-Setup-9.0.0.exe").write_bytes(b"MZ swapped after download")
    with pytest.raises(updates.UpdateError, match="changed or is missing"):
        updates.install(True)
    assert not upd["launched"]


def test_disk_space_is_checked_before_downloading(upd, monkeypatch):
    run(updates.check(force=True))
    monkeypatch.setattr(updates.lfs, "free_bytes", lambda p: 1000)
    with pytest.raises(updates.UpdateError, match="disk space"):
        run(updates.download())


def test_startup_records_whether_the_update_finished(upd, monkeypatch):
    lfs.update_settings(updates={"installing": {"from": "2.0.0-dev.5", "to": __version__, "started_at": 1}})
    assert updates.record_startup()["result"] == "installed"
    lfs.update_settings(updates={"installing": {"from": __version__, "to": "99.0.0", "started_at": 1}})
    assert "not installed" in updates.record_startup()["result"]


def test_update_never_touches_user_data(upd):
    import database as db, memory_service as mem
    db.migrate(); mem.add_memory("survives updates")
    run(updates.check(force=True)); run(updates.download()); updates.install(True)
    assert [m["content"] for m in mem.list_memories()] == ["survives updates"]


# --------------------------------------------------------------------------- model updates
class FakeOllama(BaseHTTPRequestHandler):
    calls: list = []
    validate_ok = True

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        self._json(200, {"models": [{"name": "qwen2.5:1.5b", "digest": "aa" * 32}, {"name": "nomic-embed-text:latest", "digest": "bb" * 32}]})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0); req = json.loads(self.rfile.read(n) or b"{}")
        FakeOllama.calls.append((self.path, req))
        if self.path == "/api/pull":
            self.send_response(200); self.end_headers(); self.wfile.write(b'{"status":"success"}\n'); return
        if self.path == "/api/generate":
            return self._json(200, {"response": "OK" if FakeOllama.validate_ok else ""})
        self._json(200, {})


@pytest.fixture
def ollama(data_root, monkeypatch):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    from config import get_settings
    monkeypatch.setenv("OLLAMA_BASE_URL", f"http://127.0.0.1:{srv.server_address[1]}")
    get_settings.cache_clear()
    lfs.update_settings(models={"llm": "qwen2.5:1.5b", "embed": "nomic-embed-text"})
    import system_service as system
    system.PULLS.clear(); FakeOllama.calls, FakeOllama.validate_ok = [], True
    yield FakeOllama
    srv.shutdown(); get_settings.cache_clear(); system.PULLS.clear()


def test_model_update_keeps_a_backup_and_needs_confirmation(ollama):
    with pytest.raises(updates.UpdateError, match="confirm"):
        run(updates.apply_model_update("qwen2.5:1.5b", False))
    r = run(updates.apply_model_update("qwen2.5:1.5b", True))
    assert r["backup"] == "qwen2.5-1.5b-abhify-backup"
    paths = [c[0] for c in ollama.calls]
    assert paths.index("/api/copy") < paths.index("/api/pull") < paths.index("/api/generate")


def test_failed_model_update_restores_the_previous_version(ollama):
    ollama.validate_ok = False
    with pytest.raises(updates.UpdateError, match="previous version was restored"):
        run(updates.apply_model_update("qwen2.5:1.5b", True))
    copies = [c[1] for c in ollama.calls if c[0] == "/api/copy"]
    assert copies[-1] == {"source": "qwen2.5-1.5b-abhify-backup", "destination": "qwen2.5:1.5b"}


def test_only_active_models_can_be_updated(ollama):
    with pytest.raises(updates.UpdateError, match="in use"):
        run(updates.apply_model_update("llama3.2", True))


# --------------------------------------------------------------------------- API boundary
def test_update_endpoints_are_pc_only(data_root):
    from fastapi.testclient import TestClient
    import main
    lfs.update_settings(companion={"enabled": True})
    phone = TestClient(main.app, client=("192.168.1.50", 5000))
    for method, path in (("get", "/updates"), ("post", "/updates/check"), ("post", "/updates/download"),
                         ("post", "/updates/install"), ("post", "/updates/models/apply")):
        assert getattr(phone, method)(path).status_code in (401, 403), path


# --------------------------------------------------------------------------- publisher tool
def test_release_tool_keeps_private_keys_out_of_the_repo_and_signs_verifiably(data_root, tmp_path):
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "packaging" / "windows"))
    import release_manifest as rm
    with pytest.raises(SystemExit, match="inside the repository"):
        rm.keygen(rm.ROOT / "build" / "should-not-exist.key")
    assert not (rm.ROOT / "build" / "should-not-exist.key").exists()
    key = rm.keygen(tmp_path / "keys" / "release.key")
    dist = tmp_path / "dist"; dist.mkdir()
    (dist / "Abhify.AI-Setup-9.9.9.exe").write_bytes(b"MZ test")
    out = rm.sign(tmp_path / "keys" / "release.key", "k1", "9.9.9", "https://example.test/S.exe", None, dist=dist)
    m = updates.verify_envelope(out.read_bytes(), {"k1": key["public_key"]})
    assert m["packages"]["windows-x64"]["sha256"] == hashlib.sha256(b"MZ test").hexdigest()
    assert "private" not in out.read_text().lower()


def test_download_can_be_cancelled_and_resumed(upd, monkeypatch):
    run(updates.check(force=True))
    real_fetch = updates._fetch

    async def fetch_then_cancel(url, part, total):
        updates.cancel_download()                               # the user presses Cancel during the download
        await real_fetch(url, part, total)
    monkeypatch.setattr(updates, "_fetch", fetch_then_cancel)
    with pytest.raises(updates.UpdateError, match="cancelled"):
        run(updates.download())
    assert not (updates.staging_dir() / "Abhify.AI-Setup-9.0.0.exe").exists()
    monkeypatch.setattr(updates, "_fetch", real_fetch)
    assert run(updates.download())["prepared"] is True           # a new Download works (and resumes)


def test_connection_lost_during_download_is_a_clear_resumable_error(upd, monkeypatch):
    """Found while writing the dev.6 E2E script: a dropped connection used to escape as a 500."""
    run(updates.check(force=True))

    async def drop(url, part, total):
        part.write_bytes(PAYLOAD[:3000])
        raise httpx.RemoteProtocolError("peer closed connection")
    monkeypatch.setattr(updates, "_fetch", drop)
    with pytest.raises(updates.UpdateError, match="interrupted .*resume"):
        run(updates.download())
    assert (updates.staging_dir() / "Abhify.AI-Setup-9.0.0.exe.part").stat().st_size == 3000   # kept for resuming
