"""F3 regression tests: tokens and secret query values never reach a log (dev.3)."""
import json
import logging
import os
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import pytest

import log_redaction as lr

BACKEND = Path(__file__).resolve().parents[1]
SECRET = "S3cretTok3nValue_9f8e7d6c5b4a"


def test_sanitize_target_drops_query_values():
    assert lr.sanitize_target(f"/memory?token={SECRET}") == "/memory?token=***"
    assert lr.sanitize_target("/news?limit=8&refresh=true") == "/news?limit=***&refresh=***"
    assert lr.sanitize_target("/health") == "/health"
    assert SECRET not in lr.sanitize_target(f"/x?a=1#pair={SECRET}")


@pytest.mark.parametrize("line", [
    f"Authorization: Bearer {SECRET}",
    f"headers={{'authorization': 'Bearer {SECRET}'}}",
    f"GET http://pc:8000/#pair={SECRET}",
    f"url=/chat?access_token={SECRET}&x=1",
    f"x-abhify-token: {SECRET}",
    f"password={SECRET}",
])
def test_redact_masks_secrets(line):
    assert SECRET not in lr.redact(line)


def test_redact_keeps_normal_text():
    assert lr.redact("Indexed notes.md (3 chunks) in 0.4 s") == "Indexed notes.md (3 chunks) in 0.4 s"


def test_filter_on_access_and_app_records(caplog):
    lr.install()
    access = logging.getLogger("uvicorn.access")
    rec = access.makeRecord("uvicorn.access", logging.INFO, __file__, 1, '%s - "%s %s HTTP/%s" %d',
                            ("1.2.3.4:5", "GET", f"/memory?token={SECRET}", "1.1", 401), None)
    lr.FILTER.filter(rec)
    assert SECRET not in rec.getMessage() and "/memory?token=***" in rec.getMessage()
    app = logging.getLogger("abhify")
    rec2 = app.makeRecord("abhify", logging.WARNING, __file__, 1, "bad header %s", (f"Bearer {SECRET}",), None)
    lr.FILTER.filter(rec2)
    assert SECRET not in rec2.getMessage()


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_dev_server_access_log_end_to_end(tmp_path):
    """The real dev path (python -m uvicorn main:app, access log ON) with an isolated data folder."""
    root = tmp_path / "AbhifyAI"
    (root / "config").mkdir(parents=True)
    (root / "config" / "migrated_from_v1.json").write_text("{}")      # never adopt the real v1 dev data
    port = _free_port()
    env = {**os.environ, "ABHIFY_DATA_DIR": str(root), "PYTHONUNBUFFERED": "1",
           "OLLAMA_BASE_URL": "http://127.0.0.1:9"}                    # no Ollama needed
    log_file = tmp_path / "server.out"
    with log_file.open("wb") as out:
        proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "main:app", "--host", "127.0.0.1",
                                 "--port", str(port)], cwd=BACKEND, env=env, stdout=out, stderr=subprocess.STDOUT)
        try:
            base = f"http://127.0.0.1:{port}"
            end = time.time() + 60
            while time.time() < end:
                try:
                    urllib.request.urlopen(base + "/ping", timeout=2).read()
                    break
                except OSError:
                    time.sleep(0.3)
            else:
                pytest.fail("dev server did not start:\n" + log_file.read_text(errors="replace")[-2000:])
            for path in (f"/memory?token={SECRET}", f"/preferences?access_token={SECRET}&a=1"):
                urllib.request.urlopen(base + path, timeout=10).read()
            req = urllib.request.Request(base + "/memory", headers={"Authorization": f"Bearer {SECRET}"})
            urllib.request.urlopen(req, timeout=10).read()
            time.sleep(0.5)
        finally:
            proc.terminate()
            proc.wait(15)
    console = log_file.read_text(encoding="utf-8", errors="replace")
    assert "/memory?token=***" in console                                   # the access log is on and sanitised
    assert SECRET not in console
    for f in (root / "logs").glob("*.log*"):
        assert SECRET not in f.read_text(encoding="utf-8", errors="replace"), f.name
