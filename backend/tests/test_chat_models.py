"""dev.8 chat model choice: the list comes from Ollama's /api/tags, the chosen model is checked and really used,
the default persists in settings.json, and nothing silently falls back to another model."""
import pytest
from fastapi.testclient import TestClient

TAGS = {"models": [
    {"name": "qwen2.5:latest", "size": 4_700_000_000, "details": {"family": "qwen2", "parameter_size": "7.6B", "quantization_level": "Q4_K_M"}},
    {"name": "qwen2.5:1.5b", "size": 986_000_000, "details": {"family": "qwen2", "parameter_size": "1.5B", "quantization_level": "Q4_K_M"}},
    {"name": "nomic-embed-text:latest", "size": 274_000_000, "details": {"family": "nomic-bert", "families": ["nomic-bert"]}},
]}


class FakeResponse:
    def __init__(self, data): self._d = data
    def json(self): return self._d


POSTS = []


class FakeClient:
    """Stands in for httpx.AsyncClient against Ollama: /api/tags, and records unload requests."""
    def __init__(self, *a, **k): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *a): return False
    async def get(self, url): return FakeResponse(TAGS)
    async def post(self, url, json=None): POSTS.append((url.rsplit("/", 1)[-1], json)); return FakeResponse({})


class DownClient(FakeClient):
    async def get(self, url): raise ConnectionError("refused")


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system
    monkeypatch.setattr(system.httpx, "AsyncClient", FakeClient)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


def test_list_shows_installed_chat_models_only(client):
    r = client.get("/models").json()
    assert r["ollama"] is True
    assert [m["name"] for m in r["models"]] == ["qwen2.5:latest", "qwen2.5:1.5b"]           # embedding model left out
    assert r["models"][1]["parameter_size"] == "1.5B"
    assert r["default"] == "qwen2.5" and r["default_installed"] is True
    assert [m["default"] for m in r["models"]] == [True, False]


def test_chat_uses_the_chosen_model(client, monkeypatch):
    import main
    seen = {}

    async def fake_agent(message, conversation_id, guest=False, model=None):
        seen["model"] = model
        return {"answer": "ok", "conversation_id": "c1", "tools_used": [], "sources": [], "model": model}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    r = client.post("/chat", json={"message": "hi", "model": "qwen2.5:1.5b"})
    assert r.status_code == 200 and seen["model"] == "qwen2.5:1.5b" and r.json()["model"] == "qwen2.5:1.5b"


def test_unknown_or_embedding_model_is_refused_not_swapped(client, monkeypatch):
    import main
    called = []

    async def fake_agent(*a, **k):
        called.append(1)
        return {"answer": "x", "conversation_id": "c", "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    for bad in ("llama3:70b", "nomic-embed-text"):
        r = client.post("/chat", json={"message": "hi", "model": bad})
        assert r.status_code == 400 and "not installed" in r.json()["detail"]
    assert not called                                                                      # no answer from another model


def test_chat_without_a_model_keeps_the_default_path(client, monkeypatch):
    import main

    async def fake_agent(message, conversation_id):                                         # the pre-dev.8 signature
        return {"answer": "echo", "conversation_id": "c1", "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    assert client.post("/chat", json={"message": "hi"}).status_code == 200


def test_default_model_persists_and_rebuilds_the_llm(client, monkeypatch):
    import agent
    import local_file_service as lfs
    agent.get_llm.cache_clear()
    before = agent.get_llm()
    r = client.put("/models/default", json={"model": "qwen2.5:1.5b"})
    assert r.status_code == 200 and r.json()["default"] == "qwen2.5:1.5b"
    assert lfs.load_settings()["models"]["llm"] == "qwen2.5:1.5b"
    assert lfs.load_settings()["models"]["embed"] == "nomic-embed-text"                     # embedding model unchanged
    after = agent.get_llm()
    assert after is not before and after.model == "qwen2.5:1.5b"
    assert client.put("/models/default", json={"model": "missing:1b"}).status_code == 400


def test_env_override_is_reported_not_overwritten(client, monkeypatch):
    monkeypatch.setenv("LLM_MODEL", "qwen2.5:latest")
    assert client.get("/models").json()["locked"] is True
    assert client.put("/models/default", json={"model": "qwen2.5:1.5b"}).status_code == 409


def test_ollama_down_gives_an_empty_honest_list_and_503_for_a_chosen_model(client, monkeypatch):
    import system_service as system
    monkeypatch.setattr(system.httpx, "AsyncClient", DownClient)
    r = client.get("/models").json()
    assert r["ollama"] is False and r["models"] == [] and "Ollama" in r["error"]
    assert client.post("/chat", json={"message": "hi", "model": "qwen2.5:1.5b"}).status_code == 503


def test_agent_builds_one_client_per_model():
    import agent
    agent.get_llm.cache_clear()
    assert agent.get_llm(model="a:1b").model == "a:1b"
    assert agent.get_llm(model="a:1b") is agent.get_llm(model="a:1b")
    assert agent.get_llm(model="b:1b") is not agent.get_llm(model="a:1b")


def test_switching_releases_the_previous_chat_model_only(client, monkeypatch):
    import main
    import system_service as system

    async def fake_agent(message, conversation_id, guest=False, model=None):
        return {"answer": "ok", "conversation_id": "c1", "tools_used": [], "sources": [], "model": model}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    monkeypatch.setattr(system, "_last_chat_model", None)
    POSTS.clear()
    client.post("/chat", json={"message": "a", "model": "qwen2.5:1.5b"})           # first chat after a restart: away from the default
    assert POSTS == [("generate", {"model": "qwen2.5", "keep_alive": 0})]           # ...so the default (loaded by earlier chats) is released
    POSTS.clear()
    client.post("/chat", json={"message": "b", "model": "qwen2.5:1.5b"})           # same model: nothing released
    assert POSTS == []
    client.post("/chat", json={"message": "c", "model": "qwen2.5:latest"})         # switch: the previous one is released
    assert POSTS == [("generate", {"model": "qwen2.5:1.5b", "keep_alive": 0})]
    assert all("nomic" not in str(p) for p in POSTS)                                # never the embedding model


def test_inference_stats_come_from_ollama_metadata_only():
    import system_service as system
    meta = {"model": "llama3.2:latest", "eval_count": 80, "eval_duration": 2_000_000_000, "prompt_eval_count": 28,
            "load_duration": 500_000_000, "total_duration": 3_000_000_000}
    s = system.record_inference(meta, "x")
    assert s["tokens_per_second"] == 40.0 and s["output_tokens"] == 80 and s["load_seconds"] == 0.5 and s["total_seconds"] == 3.0
    assert system.record_inference({}, "x") is None                       # nothing reported -> nothing shown (no estimate)
    assert system.record_inference(None, "x") is None


def test_performance_is_one_real_sample(client, monkeypatch):
    import system_service as system
    monkeypatch.setattr(system, "_gpu_usage", lambda: [{"name": "GPU", "util_percent": 12, "vram_used_gb": 2.0, "vram_total_gb": 12.0}])

    class PsClient(FakeClient):
        async def get(self, url):
            return FakeResponse({"models": [{"name": "qwen2.5:1.5b", "size": 2 * 1024 ** 3, "size_vram": 1024 ** 3, "expires_at": "2026-11-09T12:00:00Z"}]}
                                if url.endswith("/api/ps") else TAGS)
    monkeypatch.setattr(system.httpx, "AsyncClient", PsClient)
    r = client.get("/system/performance").json()
    assert 0 <= r["cpu_percent"] <= 100 and r["ram_total_gb"] > 0 and r["ram_used_gb"] > 0
    assert r["gpus"][0]["util_percent"] == 12 and r["ollama"] is True
    assert r["loaded"] == [{"name": "qwen2.5:1.5b", "size_gb": 2.0, "vram_gb": 1.0, "unloads_at": "2026-11-09T12:00:00Z", "chat": True}]
