"""7.0 model switching: typed model commands are carried out by the app (never claimed by a model), each conversation
keeps its own model on the backend (one source of truth), the next answer really uses it (normal and streamed chat),
failures keep the previous model, and the Model Library removes/cancels through Ollama's API. Ollama is faked here;
test_model_switch_live.py checks a real Ollama when ABHIFY_LIVE_OLLAMA=1."""
import json

import pytest
from fastapi.testclient import TestClient

TAGS = {"models": [
    {"name": "qwen2.5:latest", "size": 4_700_000_000, "digest": "q1", "details": {"family": "qwen2", "parameter_size": "7.6B", "quantization_level": "Q4_K_M"}},
    {"name": "llama3.2:latest", "size": 2_000_000_000, "digest": "l1", "details": {"family": "llama", "parameter_size": "3.2B", "quantization_level": "Q4_K_M"}},
    {"name": "nomic-embed-text:latest", "size": 274_000_000, "digest": "n1", "details": {"family": "nomic-bert", "families": ["nomic-bert"]}},
]}
PS = {"models": [{"name": "qwen2.5:latest"}]}
CALLS = []


class R:
    def __init__(self, data, status=200): self._d, self.status_code = data, status
    def json(self): return self._d


class FakeOllama:
    up = True

    def __init__(self, *a, **k): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *a): return False

    async def get(self, url, **k):
        if not FakeOllama.up:
            raise ConnectionError("refused")
        return R(PS if url.endswith("/api/ps") else TAGS)

    async def post(self, url, json=None, **k):
        CALLS.append(("POST", url.rsplit("/", 1)[-1], json))
        if url.endswith("/api/show"):
            return R({"capabilities": ["completion", "tools"] if "qwen" in json["model"] else ["completion"]})
        return R({})

    async def request(self, method, url, json=None, **k):
        CALLS.append((method, url.rsplit("/", 1)[-1], json))
        return R({})


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system
    FakeOllama.up = True
    CALLS.clear()
    system._CAPS.clear()
    monkeypatch.setattr(system.httpx, "AsyncClient", FakeOllama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


@pytest.fixture
def agent_calls(monkeypatch):
    """Records the model each real chat turn is run with (instead of calling a model)."""
    import main
    seen = []

    async def fake_run(message, conversation_id, guest=False, model=None, **kw):
        import memory_service as mem
        cid = mem.ensure_conversation(conversation_id, message)
        seen.append(model)
        return {"answer": f"answered by {model}", "conversation_id": cid, "tools_used": [], "sources": [], "model": model, "served_by": model}
    monkeypatch.setattr(main, "run_agent", fake_run)
    return seen


# --------------------------------------------------------------------------- parsing (pure)
@pytest.mark.parametrize("text,name,strict,default", [
    ("switch the model to llama3.2", "llama3.2", True, False),
    ("Change model to qwen", "qwen", True, False),
    ("please switch your model to Llama 3.2", "Llama 3.2", True, False),
    ("can you change the chat model to llama3.2?", "llama3.2", True, False),
    ("use the llama3.2 model", "llama3.2", True, False),
    ("use model qwen2.5", "qwen2.5", True, False),
    ("set the default model to qwen2.5", "qwen2.5", True, True),
    ("switch to llama3.2", "llama3.2", False, False),
])
def test_model_commands_are_recognised(text, name, strict, default):
    import model_switch as ms
    c = ms.parse(text)
    assert c == {"kind": "switch", "name": name, "strict": strict, "default": default}


@pytest.mark.parametrize("text", ["Explain how model training works", "What is a language model?", "switch off the lights",
                                  "Compare llama3.2 and qwen2.5 for coding", "I want to change my model of thinking about RAG\nplease"])
def test_ordinary_messages_are_not_model_commands(text):
    import model_switch as ms
    assert ms.parse(text) is None


def test_which_model_question_and_name_matching():
    import model_switch as ms
    assert ms.parse("which model are you using?") == {"kind": "which"}
    names = ["qwen2.5:latest", "llama3.2:latest", "qwen2.5:1.5b"]
    assert ms.match("llama3.2", names) == ["llama3.2:latest"]
    assert ms.match("Llama 3.2", names) == ["llama3.2:latest"]
    assert ms.match("qwen2.5:1.5b", names) == ["qwen2.5:1.5b"]
    assert sorted(ms.match("qwen", names)) == ["qwen2.5:1.5b", "qwen2.5:latest"]          # ambiguous: the user is asked
    assert ms.match("phi4", names) == []


# --------------------------------------------------------------------------- the acceptance requirement
def test_typed_switch_changes_the_model_of_the_next_real_answer(client, agent_calls):
    r = client.post("/chat", json={"message": "switch the model to llama3.2"}).json()
    assert r["model_command"] == {"kind": "switch", "ok": True, "model": "llama3.2:latest", "previous": "qwen2.5", "default": "qwen2.5"}
    assert agent_calls == []                                          # no model was asked to "pretend" to switch
    assert "Model switched to **llama3.2:latest**" in r["answer"]
    cid = r["conversation_id"]
    nxt = client.post("/chat", json={"message": "hello", "thread_id": cid}).json()      # the page sends no model
    assert agent_calls == ["llama3.2:latest"] and nxt["model"] == "llama3.2:latest"
    assert client.get(f"/conversations/{cid}").json()["model"] == "llama3.2:latest"


def test_streamed_chat_uses_the_conversation_model(client, monkeypatch):
    import agent
    import memory_service as mem
    seen = []

    async def fake_stream(message, conversation_id, model=None, **kw):
        seen.append(model)
        cid = mem.ensure_conversation(conversation_id, message)
        yield {"type": "token", "text": "hi"}
        yield {"type": "done", "answer": "hi", "conversation_id": cid, "model": model, "served_by": model}
    monkeypatch.setattr(agent, "stream_agent", fake_stream)
    lines = client.post("/chat/stream", json={"message": "use the llama3.2 model"}).text.strip().splitlines()
    evs = [json.loads(x) for x in lines]
    assert evs[0]["type"] == "model_command" and evs[0]["ok"] and evs[-1]["type"] == "done" and seen == []
    cid = evs[-1]["conversation_id"]
    done = json.loads(client.post("/chat/stream", json={"message": "hi", "thread_id": cid}).text.strip().splitlines()[-1])
    assert seen == ["llama3.2:latest"] and done["model"] == "llama3.2:latest"


def test_picker_choice_is_stored_per_conversation_and_restored(client, agent_calls):
    a = client.post("/chat", json={"message": "first", "model": "llama3.2"}).json()["conversation_id"]
    b = client.post("/chat", json={"message": "second"}).json()["conversation_id"]          # new chat: inherits the default
    assert agent_calls == ["llama3.2:latest", None]
    assert client.get(f"/conversations/{a}").json()["model"] == "llama3.2:latest"
    assert client.get(f"/conversations/{b}").json()["model"] is None
    client.post("/chat", json={"message": "again", "thread_id": a})                       # back in A: its model, restored
    client.post("/chat", json={"message": "again", "thread_id": b})
    assert agent_calls[2:] == ["llama3.2:latest", None]
    # the picker in an existing conversation: PUT, checked against Ollama; null = follow the default again
    r = client.put(f"/models/conversation/{b}", json={"model": "llama3.2"}).json()
    assert r == {"id": b, "model": "llama3.2:latest", "previous": None, "default": "qwen2.5"}
    assert client.put(f"/models/conversation/{b}", json={"model": None}).json()["model"] is None
    listed = {c["id"]: c["model"] for c in client.get("/conversations").json()["conversations"]}
    assert listed[a] == "llama3.2:latest" and listed[b] is None


def test_invalid_model_is_an_error_and_the_previous_model_is_kept(client, agent_calls):
    cid = client.post("/chat", json={"message": "switch the model to llama3.2"}).json()["conversation_id"]
    r = client.post("/chat", json={"message": "switch the model to phi4", "thread_id": cid}).json()
    assert r["model_command"]["ok"] is False and r["model_command"]["error"] == "not_installed"
    assert "isn't installed" in r["answer"] and "Still using **llama3.2:latest**" in r["answer"]
    assert client.put(f"/models/conversation/{cid}", json={"model": "phi4"}).status_code == 400
    assert client.post("/chat", json={"message": "hi", "model": "phi4"}).status_code == 400   # never swapped for another
    assert client.get(f"/conversations/{cid}").json()["model"] == "llama3.2:latest"
    assert agent_calls == []


def test_loose_phrasing_only_counts_for_installed_models(client, agent_calls):
    r = client.post("/chat", json={"message": "switch to dark mode"}).json()
    assert "model_command" not in r and agent_calls == [None]                              # an ordinary message
    r = client.post("/chat", json={"message": "switch to llama3.2"}).json()
    assert r["model_command"]["ok"] and r["model"] == "llama3.2:latest"


def test_which_model_answers_from_real_state(client, agent_calls):
    cid = client.post("/chat", json={"message": "switch the model to llama3.2"}).json()["conversation_id"]
    r = client.post("/chat", json={"message": "what model are you using?", "thread_id": cid}).json()
    assert "**llama3.2:latest**" in r["answer"] and "chosen for this conversation" in r["answer"] and agent_calls == []


def test_default_model_command_persists_for_new_chats(client, agent_calls):
    import system_service as system
    r = client.post("/chat", json={"message": "set the default model to llama3.2"}).json()
    assert r["model_command"]["kind"] == "default" and r["model_command"]["ok"]
    assert system.active_models()["llm"] == "llama3.2:latest"                              # settings.json
    assert client.get("/models").json()["default"] == "llama3.2:latest"


def test_ollama_unavailable_keeps_the_model_and_says_why(client, agent_calls):
    FakeOllama.up = False
    r = client.post("/chat", json={"message": "switch the model to llama3.2"}).json()
    assert r["model_command"]["error"] == "ollama_unavailable" and "Ollama" in r["answer"] and agent_calls == []
    assert client.get("/models").json()["ollama"] is False


def test_a_removed_conversation_model_falls_back_to_the_default_and_says_so(client, agent_calls):
    import memory_service as mem
    cid = client.post("/chat", json={"message": "hi"}).json()["conversation_id"]
    mem.set_conversation_model(cid, "mistral:7b")                                         # was installed, then removed
    r = client.post("/chat", json={"message": "hi again", "thread_id": cid}).json()
    assert agent_calls[-1] is None and "no longer installed" in r["model_notice"]
    assert mem.conversation_model(cid) is None


def test_model_list_reports_ollama_details_only(client):
    m = {x["name"]: x for x in client.get("/models").json()["models"]}
    assert set(m) == {"qwen2.5:latest", "llama3.2:latest"}                               # the embedding model is not a chat model
    assert m["qwen2.5:latest"]["loaded"] is True and m["llama3.2:latest"]["loaded"] is False     # from /api/ps
    assert m["qwen2.5:latest"]["capabilities"] == ["completion", "tools"]                 # from /api/show, never assumed
    assert "vision" not in (m["llama3.2:latest"]["capabilities"] or [])
    assert m["llama3.2:latest"]["use"].startswith("Fast and light") and m["llama3.2:latest"]["parameter_size"] == "3.2B"


def test_model_library_remove_and_cancel(client):
    assert client.delete("/models/llama3.2:latest").status_code == 400                     # needs confirm
    assert "default chat model" in client.delete("/models/qwen2.5:latest?confirm=true").json()["detail"]
    assert "embedding model" in client.delete("/models/nomic-embed-text:latest?confirm=true").json()["detail"]
    assert client.delete("/models/phi4?confirm=true").status_code == 400
    r = client.delete("/models/llama3.2:latest?confirm=true")
    assert r.status_code == 200 and r.json()["deleted"] == "llama3.2:latest"
    assert ("DELETE", "delete", {"model": "llama3.2:latest"}) in CALLS
    assert client.post("/setup/models/pull/cancel", json={"model": "mistral:7b"}).json() == {"model": "mistral:7b", "cancelled": False}


def test_answers_record_the_model_ollama_reports(data_root):
    """served_by comes from Ollama's own response metadata and is stored with the answer."""
    import agent
    import memory_service as mem
    import database as db
    from langchain_core.messages import AIMessage
    db.migrate()
    cid = mem.ensure_conversation(None, "q")
    final = AIMessage("4", response_metadata={"model": "llama3.2:latest", "eval_count": 3, "eval_duration": 10_000_000})
    out = agent._finish("q", [final], {"save_history": True}, cid, [], "llama3.2:latest", None)
    assert out["served_by"] == "llama3.2:latest"
    assert mem.recent_messages(cid)[-1]["model"] == "llama3.2:latest"


def test_migration_v6_adds_the_model_columns(data_root):
    import database as db
    info = db.migrate()
    assert info["to"] == db.SCHEMA_VERSION >= 6                       # 7.2 adds v7 (sync tables) on top
    with db.connect() as c:
        conv = [r[1] for r in c.execute("PRAGMA table_info(conversations)")]
        msgs = [r[1] for r in c.execute("PRAGMA table_info(messages)")]
    assert "model" in conv and "model" in msgs


def test_populated_v5_database_upgrades_to_v6_with_a_backup_and_every_row_kept(data_root, monkeypatch):
    """A 7.0.0-dev.1 (schema v5) database full of chats gets the two model columns, a backup first, and keeps every row."""
    import database as db
    import local_file_service as lfs
    import memory_service as mem
    for suffix in ("", "-wal", "-shm"):
        lfs.db_path().with_name(lfs.db_path().name + suffix).unlink(missing_ok=True)
    full = list(db.MIGRATIONS)
    monkeypatch.setattr(db, "MIGRATIONS", full[:5]); monkeypatch.setattr(db, "SCHEMA_VERSION", 5)
    db.migrate()
    cid = mem.ensure_conversation(None, "q"); mem.add_message(cid, "user", "kept"); mem.add_message(cid, "assistant", "kept too")
    monkeypatch.setattr(db, "MIGRATIONS", full[:6]); monkeypatch.setattr(db, "SCHEMA_VERSION", 6)
    r = db.migrate()
    assert (r["from"], r["to"]) == (5, 6) and r["backup"] and "pre-schema-v6" in r["backup"]
    assert [m["content"] for m in mem.recent_messages(cid)] == ["kept", "kept too"]
    assert mem.conversation_model(cid) is None and all(m["model"] is None for m in mem.recent_messages(cid))
