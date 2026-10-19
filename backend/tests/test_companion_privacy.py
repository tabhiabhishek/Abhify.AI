"""B2 regression tests: what a phone (remote caller) can see and do, with no / wrong / valid tokens (dev.3)."""
import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import companion
import database as db
import local_file_service as lfs

SENSITIVE_GETS = ["/system/diagnostics", "/setup/profiles", "/setup/models/pull", "/backup",
                  "/system/update-check", "/docs", "/openapi.json"]


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


def phone():
    import main
    return TestClient(main.app, client=("192.168.1.42", 50000))


@pytest.fixture
def token(app_client):
    return app_client.post("/companion/enable").json()["token"]


def bearer(t):
    return {"Authorization": f"Bearer {t}"}


def private_strings():
    """Things that must never reach a phone: the token hash, the user's home folder / user name,
    and the data folder path."""
    cfg = lfs.load_settings()["companion"]
    home = str(Path.home())
    out = {cfg["token_sha256"], home, home.replace("\\", "/"), str(lfs.root()), str(lfs.root()).replace("\\", "\\\\")}
    return {s for s in out if s}


def assert_no_private_data(body: str, where: str):
    for s in private_strings():
        assert s.lower() not in body.lower(), f"{where} leaked {s[:12]}…"
    assert "token_sha256" not in body, where


@pytest.mark.parametrize("path", SENSITIVE_GETS)
def test_sensitive_reads_are_local_only_for_every_credential(app_client, token, path):
    p = phone()
    assert p.get(path).status_code == 401                                   # no token
    assert p.get(path, headers=bearer("wrong-token-xxxxxxxx")).status_code == 401
    r = p.get(path, headers=bearer(token))                                  # valid token: still refused
    assert r.status_code == 403, path
    assert_no_private_data(r.text, path)


def test_setup_test_is_local_only(app_client, token):
    assert phone().post("/setup/test", headers=bearer(token)).status_code == 403


def test_local_admin_still_works(app_client, token):
    for path in ["/system/diagnostics", "/setup/profiles", "/setup/models/pull", "/backup", "/system/update-check"]:
        assert app_client.get(path).status_code == 200, path


def test_every_route_is_allowlisted_or_local_only(app_client, token):
    """A valid token opens only the documented user-content routes; every other route (including any
    added later) answers 403 to a phone."""
    import main
    p = phone()
    for r in main.app.routes:
        if not hasattr(r, "methods") or r.path in companion.OPEN_API or r.path in companion.PAIRING_ROUTES:
            continue                       # open by design (pairing routes are rate-limited; see test_membership_api)
        path = re.sub(r"\{[^}]+\}", "1", r.path)
        for method in r.methods - {"HEAD", "OPTIONS"}:
            resp = p.request(method, path, headers=bearer(token))
            if companion.remote_allowed(r.path.split("{")[0].rstrip("/") or r.path) or companion.remote_allowed(path):
                assert resp.status_code != 403 or "only allowed" not in resp.text, (method, r.path)
            else:
                assert resp.status_code == 403, (method, r.path, resp.status_code)


def test_allowed_remote_responses_contain_no_private_data(app_client, token, kb):
    import document_indexer as di
    f = kb / "notes.md"; f.write_text("transformers use attention", encoding="utf-8")
    di.index_file(f, kb)
    db.save_document("watcher", di.path_key(kb / "locked.pdf"), path="locked.pdf", status="failed",
                     error=f"[Errno 13] Permission denied: '{kb / 'locked.pdf'}'")
    p, h = phone(), bearer(token)
    for path in ["/health", "/companion", "/knowledge", "/knowledge/status", "/knowledge/files",
                 "/memory", "/preferences", "/conversations", "/progress/u1"]:
        r = p.get(path, headers=h)
        assert r.status_code == 200, path
        assert_no_private_data(r.text, path)
    st = p.get("/knowledge/status", headers=h).json()
    assert st["folder"] == kb.name and st["default_folder"] is None
    files = p.get("/knowledge/files", headers=h).json()["files"]
    assert files and all("path_key" not in f for f in files)
    assert any(f["error"] and "<path>" in f["error"] for f in files)
    # the computer itself still gets full details
    assert app_client.get("/knowledge/status").json()["folder"] == str(kb.resolve())


def test_unpaired_health_reveals_only_reachability(app_client, token):
    p = phone()
    h = p.get("/health").json()
    assert h["authorized"] is False and h["remote"] is True and h["companion_enabled"] is True
    for k in ("llm_model", "embed_model", "knowledge_chunks", "setup_completed", "internet", "schema_version"):
        assert k not in h, k
    assert p.get("/health", headers=bearer("wrong-token-xxxxxxxx")).json()["authorized"] is False
    paired = p.get("/health", headers=bearer(token)).json()
    assert paired["authorized"] is True and "llm_model" in paired
    local = app_client.get("/health").json()
    assert local["remote"] is False and local["authorized"] is True


def test_health_when_companion_off(app_client):
    h = phone().get("/health").json()
    assert h["authorized"] is False and h["companion_enabled"] is False


def test_error_details_are_redacted():
    import main
    e = main._unavailable(PermissionError(13, "Permission denied", r"C:\Users\someone\Abhify_Knowledge\a.pdf"))
    assert "someone" not in e.detail and "<path>" in e.detail
    assert companion.redact_text("see /home/someone/notes.txt") == "see <path>"
    assert companion.redact_text("ratio 3:4 at http://pc:8000/x") == "ratio 3:4 at http://pc:8000/x"


def test_token_hash_never_returned_locally_either(app_client, token):
    import database as db
    with db.connect() as c:                       # dev.6: device hashes live in the members table
        hashes = [r[0] for r in c.execute("SELECT token_sha256 FROM members")]
    assert hashes
    for path in ["/system/diagnostics", "/companion", "/health", "/companion/admin", "/companion/members"]:
        body = app_client.get(path).text
        assert not any(h in body for h in hashes), path
        assert "token_sha256" not in body and "code_sha256" not in body, path
