"""Live AI Task Runner test against the real local Ollama. Off by default; run with ABHIFY_LIVE_OLLAMA=1
(optionally ABHIFY_LIVE_MODEL=<installed chat model>). Uses a throw-away data folder (the data_root fixture)."""
import os
import time

import pytest
from fastapi.testclient import TestClient

pytestmark = pytest.mark.skipif(os.environ.get("ABHIFY_LIVE_OLLAMA") != "1", reason="live Ollama test: set ABHIFY_LIVE_OLLAMA=1")


def test_live_summary_task_on_ollama(data_root):
    import main
    model = os.environ.get("ABHIFY_LIVE_MODEL", "llama3.2:latest")
    with TestClient(main.app) as c:
        t = c.post("/tasks", json={"template": "summary", "model": model, "source": {"type": "text", "label": "Notes",
                   "text": "Gradient descent minimises loss by moving weights against the gradient. The learning rate sets the step size."}})
        assert t.status_code == 200, t.text
        tid = t.json()["id"]
        c.post(f"/tasks/{tid}/run")
        for _ in range(600):
            task = c.get(f"/tasks/{tid}").json()
            if task["status"] in ("completed", "failed", "cancelled"):
                break
            time.sleep(0.5)
        assert task["status"] == "completed", task.get("error")
        assert task["model"] == model and all(s["result"] for s in task["steps"])
        assert "gradient" in task["steps"][1]["result"].lower()
