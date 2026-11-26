"""7.0 acceptance proof against a REAL local Ollama (opt-in: ABHIFY_LIVE_OLLAMA=1, needs two installed chat models).

Typing "switch the model to <B>" must make the next real answer come from <B>: checked with the model name Ollama
itself returns in its response metadata (served_by) and with Ollama's /api/ps. Skipped by default so the normal suite
never depends on a running service."""
import os

import httpx
import pytest
from fastapi.testclient import TestClient

pytestmark = pytest.mark.skipif(os.environ.get("ABHIFY_LIVE_OLLAMA") != "1", reason="live Ollama check is opt-in (ABHIFY_LIVE_OLLAMA=1)")


def test_next_answer_comes_from_the_newly_selected_ollama_model(data_root):
    import main
    import system_service as system
    base = "http://127.0.0.1:11434"
    names = [m["name"] for m in httpx.get(f"{base}/api/tags", timeout=5).json()["models"] if system.is_chat_model(m)]
    if len(names) < 2:
        pytest.skip("needs two installed chat models")
    a, b = names[0], names[1]
    with TestClient(main.app) as c:
        cid = c.post("/chat", json={"message": f"switch the model to {a}"}).json()["conversation_id"]
        r1 = c.post("/chat", json={"message": "Reply with one word: hello", "thread_id": cid}, timeout=300).json()
        assert r1["served_by"] == a                                       # Ollama's own metadata
        sw = c.post("/chat", json={"message": f"switch the model to {b}", "thread_id": cid}).json()
        assert sw["model_command"]["ok"] and sw["model_command"]["model"] == b
        r2 = c.post("/chat", json={"message": "Reply with one word: hello", "thread_id": cid}, timeout=300).json()
        assert r2["served_by"] == b
        loaded = [m["name"] for m in httpx.get(f"{base}/api/ps", timeout=5).json()["models"]]
        assert b in loaded
