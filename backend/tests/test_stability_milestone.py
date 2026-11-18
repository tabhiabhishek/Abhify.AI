"""Stability milestone: Task Runner document workflows, Projects (context isolation), streamed chat and voice warm-up.
Fake models and fake embeddings (no Ollama); live checks are in the milestone report."""
import asyncio
import json
import time

import pytest
from fastapi.testclient import TestClient

# The exact llama3.2 output that failed the live "Quiz from document" run (strings without quotes).
LLAMA_QUIZ = """[
  {
    "q": "What is the location of the Calvin cycle?",
    "options": ["chloroplast", "stroma", "mitochondria", "nucleus"],
    "answer": 1,
    "explanation": The Calvin cycle takes place in the stroma of chloroplasts.
    "quote": "The Calvin cycle takes place in the stroma."
  },
  {
    "q": "What is used to build glucose?",
    "options": ["G3P", "RuBP", "ATP", "NADPH"],
    "answer": 0,
    "explanation": G3P is used to build glucose.
    "quote": "G3P is used to build glucose, sucrose and starch."
  }
]"""
SRC = "The Calvin cycle takes place in the stroma. G3P is used to build glucose, sucrose and starch. RuBisCO fixes carbon dioxide."


# --------------------------------------------------------------------------- Task Runner: the quiz parser and prompts
def test_quiz_parser_recovers_the_real_failing_output():
    import task_runner as tr
    q = tr.parse_quiz(LLAMA_QUIZ, SRC)
    assert [x["answer"] for x in q] == [1, 0] and all(x["quote_found"] for x in q)
    assert q[0]["explanation"].startswith("The Calvin cycle takes place")


@pytest.mark.parametrize("raw,answers", [
    ('```json\n[{"q":"W?","options":["a","b","c","d"],"answer":"B","explanation":"x","quote":"stroma"}]\n```', [1]),
    ('[{"q":"W?","options":["a","b","c","d"],"answer":"2","explanation":"x",},]', [2]),
    ('{"questions":[{"q":"W?","options":["a","b","c","d"],"answer":3,"explanation":"x","quote":"q"}]}', [3]),
    ('[{"question":"W?","options":["Stroma","b","c","d"],"answer":"stroma"}]', [0]),
    ('[{"q":"One?","options":["a","b","c","d"],"answer":0},{"q":"Two?","options":["a"', [0]),
])
def test_quiz_parser_reads_common_model_variants(raw, answers):
    import task_runner as tr
    assert [x["answer"] for x in tr.parse_quiz(raw, SRC)] == answers


def test_quiz_parser_rejects_unusable_output():
    import task_runner as tr
    assert tr.parse_quiz('[{"q":"W?","options":["a","b"],"answer":7}]', SRC) is None
    assert tr.parse_quiz("Sorry, I can't.", SRC) is None
    assert tr.parse_quiz('[{"q":"","options":["a","b"],"answer":0}]', SRC) is None


def test_quiz_prompt_follows_settings_and_never_asks_more_than_the_source_supports():
    import task_runner as tr
    long = SRC * 20
    p = tr.build_prompt("quiz", "quiz", "", long, [], "notes.pdf", {"count": 10, "difficulty": "hard"})
    assert p.startswith("Write 10 multiple-choice question(s) (hard difficulty)") and "JSON array" in p
    assert tr.build_prompt("quiz", "quiz", "", SRC, [], "n", {"count": 10}).startswith("Write 1 multiple-choice")
    plan = tr.build_prompt("study", "plan", "", long, [], "photosynthesis.pdf")
    for s in ('"photosynthesis.pdf"', "Learning objectives", "Study sessions", "Revision", "Practice tasks", "Progress checklist"):
        assert s in plan


def test_quiz_uses_schema_constrained_output_on_the_same_model():
    import task_runner as tr

    class LLM:
        def __init__(self): self.format = None
        def model_copy(self, update): c = LLM(); c.format = update["format"]; return c
        async def ainvoke(self, msgs): R = type("R", (), {}); r = R(); r.content = json.dumps({"format_used": self.format is not None}); return r
    import agent
    import system_service as system
    orig, orig_note = agent.get_llm, system.note_chat_model
    agent.get_llm = lambda *a, **k: LLM()

    async def note(m): pass
    system.note_chat_model = note
    try:
        assert json.loads(asyncio.run(tr._call_model("p", "m", tr.QUIZ_SCHEMA)))["format_used"] is True
        assert json.loads(asyncio.run(tr._call_model("p", "m")))["format_used"] is False      # other steps: free text as before
    finally:
        agent.get_llm, system.note_chat_model = orig, orig_note
    assert tr.QUIZ_SCHEMA["properties"]["questions"]["items"]["properties"]["answer"]["maximum"] == 3


# --------------------------------------------------------------------------- shared app fixture (fake model)
class FakeLLM:
    prompts = []

    def __init__(self, model=None):
        self.model = model

    def model_copy(self, update):
        return self

    async def ainvoke(self, msgs, **kw):
        FakeLLM.prompts.append(msgs[1].content)
        R = type("R", (), {}); r = R()
        r.content = LLAMA_QUIZ if "JSON array" in msgs[1].content else "ok: " + msgs[1].content.splitlines()[0][:40]
        return r


@pytest.fixture
def app(kb, monkeypatch):
    import agent
    import knowledge
    import main
    import system_service as system
    FakeLLM.prompts = []
    monkeypatch.setattr(agent, "get_llm", lambda *a, model=None, **k: FakeLLM(model))

    async def note(name): pass
    monkeypatch.setattr(system, "note_chat_model", note)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    knowledge.add_upload(b"Biology notes. The Calvin cycle takes place in the stroma. G3P is used to build glucose, sucrose and starch. "
                         b"RuBisCO fixes carbon dioxide onto RuBP in every turn of the cycle.", "biology.md")
    knowledge.add_upload(b"History notes. The Treaty of Westphalia was signed in 1648 and ended the Thirty Years War in Europe. "
                         b"It is often cited as the start of the modern state system.", "history.md")
    with TestClient(main.app) as c:
        yield c


def wait(c, tid):
    for _ in range(200):
        t = c.get(f"/tasks/{tid}").json()
        if t["status"] in ("completed", "failed", "cancelled"):
            return t
        time.sleep(0.05)
    raise AssertionError(t)


def test_quiz_from_document_end_to_end_with_settings_and_min_length(app):
    t = app.post("/tasks", json={"template": "quiz", "source": {"type": "document", "name": "biology.md"}, "options": {"count": 3, "difficulty": "easy"}}).json()
    assert t["options"] == {"count": 3, "difficulty": "easy"}
    app.post(f"/tasks/{t['id']}/run")
    t = wait(app, t["id"])
    assert t["status"] == "completed" and len(t["steps"][1]["quiz"]) == 2           # the malformed-but-real output is now a quiz
    assert app.post("/tasks", json={"template": "quiz", "source": {"type": "text", "text": "x"}, "options": {"count": 7}}).status_code == 400
    short = app.post("/tasks", json={"template": "quiz", "source": {"type": "text", "text": "Too short to quiz."}}).json()
    app.post(f"/tasks/{short['id']}/run")
    s = wait(app, short["id"])
    assert s["status"] == "failed" and "too short" in s["error"] and not any("Too short" in p for p in FakeLLM.prompts)


# --------------------------------------------------------------------------- projects
def test_project_management_and_document_associations(app):
    p = app.post("/projects", json={"name": "Thesis", "instructions": "Be brief."}).json()
    assert p["use_memory"] is True and p["documents"] == [] and not p["archived"]
    assert app.post(f"/projects/{p['id']}/documents", json={"names": ["nope.pdf"]}).status_code == 400
    app.post(f"/projects/{p['id']}/documents", json={"names": ["biology.md"]})
    p = app.patch(f"/projects/{p['id']}", json={"name": "Bio thesis", "use_memory": False}).json()
    assert p["name"] == "Bio thesis" and p["documents"] == ["biology.md"] and p["use_memory"] is False
    app.post(f"/projects/{p['id']}/archive", json={"archived": True})
    assert app.get("/projects?archived=false").json()["projects"] == []
    assert app.get("/tasks/sources").json()["projects"] == []                          # archived: not offered
    app.post(f"/projects/{p['id']}/archive", json={"archived": False})
    md = app.get(f"/projects/{p['id']}/export").text
    assert md.startswith("# Bio thesis") and "- biology.md" in md and "Be brief." in md
    app.delete(f"/projects/{p['id']}/documents", params={"name": "biology.md"})
    import knowledge
    assert "biology.md" in knowledge.list_sources()                                    # removing the association keeps the document
    assert app.delete(f"/projects/{p['id']}").status_code == 400                        # needs confirm
    assert app.delete(f"/projects/{p['id']}", params={"confirm": True}).json() == {"deleted": True}
    assert set(knowledge.list_sources()) >= {"biology.md", "history.md"}               # deleting a project deletes no document


def test_project_tasks_use_only_the_projects_documents(app):
    bio = app.post("/projects", json={"name": "Bio"}).json()
    app.post(f"/projects/{bio['id']}/documents", json={"names": ["biology.md"]})
    t = app.post("/tasks", json={"template": "summary", "source": {"type": "project", "project_id": bio["id"]}, "project_id": bio["id"]}).json()
    app.post(f"/tasks/{t['id']}/run")
    t = wait(app, t["id"])
    assert t["status"] == "completed" and t["project_id"] == bio["id"] and t["source_refs"] == [{"type": "document", "label": "biology.md"}]
    assert all("Calvin" in p and "Westphalia" not in p for p in FakeLLM.prompts)
    assert app.get(f"/projects/{bio['id']}").json()["tasks"][0]["id"] == t["id"]
    empty = app.post("/projects", json={"name": "Empty"}).json()
    e = app.post("/tasks", json={"template": "summary", "source": {"type": "project", "project_id": empty["id"]}}).json()
    app.post(f"/tasks/{e['id']}/run")
    assert "has no documents" in wait(app, e["id"])["error"]
    assert app.post("/tasks", json={"template": "news", "source": {"type": "project", "project_id": bio["id"]}}).status_code == 400


def test_project_chat_context_is_isolated_and_instructions_are_marked(app, monkeypatch):
    import agent
    import projects
    assert {t.name for t in agent._project_tools(True)} == {"save_memory", "web_search", "search_memory"}   # no all-documents search
    assert "search_memory" not in {t.name for t in agent._project_tools(False)}
    monkeypatch.setattr(agent, "_project_agent", lambda model, use_memory: ("agent", use_memory))
    bio = projects.create("Bio", "Answer like a tutor.")
    projects.add_documents(bio["id"], ["biology.md"])
    text, sources = projects.context(projects.get(bio["id"]), "Westphalia treaty 1648 Calvin")
    assert sources and all(s.startswith("biology.md") for s in sources) and "Westphalia" not in text
    assert "<<<PROJECT_DOCUMENTS" in text and "reference data" in text
    prefs, cid, inputs, agent_, docs, project = asyncio.run(agent._prepare("What is in the stroma?", None, None, bio["id"]))
    system = inputs["messages"][0].content
    assert "Answer like a tutor." in system and "never mention other projects" in system and "Westphalia" not in system
    assert agent_ == ("agent", True)
    off = projects.update(bio["id"], use_memory=False)
    import memory_service as mem
    mem.add_memory("I live in Pune")
    _, _, inputs, *_ = asyncio.run(agent._prepare("Where do I live?", None, None, off["id"]))
    assert "Pune" not in inputs["messages"][0].content                                  # memory off for this project
    with pytest.raises(ValueError):
        asyncio.run(agent._prepare("x", None, None, "missing"))


# --------------------------------------------------------------------------- streamed chat and voice warm-up
def test_stream_agent_yields_tokens_then_saves_like_chat(app, monkeypatch):
    import agent
    from langchain_core.messages import AIMessage, AIMessageChunk

    class FakeAgent:
        async def astream(self, inputs, config=None, stream_mode=None):
            yield "messages", (AIMessageChunk(content="", tool_call_chunks=[{"name": "web_search", "args": "", "id": "1", "index": 0}]), {"langgraph_node": "agent"})
            yield "messages", (AIMessageChunk(content="tool output"), {"langgraph_node": "tools"})
            for part in ("Gradient ", "descent ", "minimises loss."):
                yield "messages", (AIMessageChunk(content=part), {"langgraph_node": "agent"})
            yield "values", {"messages": inputs["messages"] + [AIMessage("Gradient descent minimises loss.")]}
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: FakeAgent())

    async def collect():
        return [e async for e in agent.stream_agent("What is gradient descent?", None)]
    events = asyncio.run(collect())
    assert [e["text"] for e in events if e["type"] == "token"] == ["Gradient ", "descent ", "minimises loss."]   # no tool text
    done = events[-1]
    assert done["type"] == "done" and done["answer"] == "Gradient descent minimises loss."
    import memory_service as mem
    assert [m["role"] for m in mem.recent_messages(done["conversation_id"])] == ["user", "assistant"]


def test_chat_stream_endpoint_sends_ndjson_and_guests_are_refused(app, monkeypatch):
    import agent
    import companion

    async def fake_stream(message, cid, model=None, project_id=None):
        yield {"type": "token", "text": "Hi"}
        yield {"type": "done", "answer": "Hi", "conversation_id": "c1", "tools_used": [], "sources": []}
    monkeypatch.setattr(agent, "stream_agent", fake_stream)
    r = app.post("/chat/stream", json={"message": "hello"})
    lines = [json.loads(x) for x in r.text.splitlines() if x]
    assert r.headers["content-type"].startswith("application/x-ndjson")
    assert lines[0] == {"type": "token", "text": "Hi"} and lines[-1]["thread_id"] == "c1"
    assert app.post("/chat/stream", json={"message": "x", "project_id": "missing"}).status_code == 400
    monkeypatch.setattr(companion, "role_of", lambda req: "member")
    assert app.post("/chat/stream", json={"message": "x"}).status_code == 403
    assert app.post("/chat/warm", json={}).json() == {"warmed": False}
    assert "/chat/stream" in companion.REMOTE_ROUTES and "/chat/stream" not in companion.MEMBER_ROUTES


def test_voice_warm_up_loads_the_chosen_model_only(app, monkeypatch):
    import system_service as system
    import transcribe
    loaded, whisper = [], []

    async def preload(name): loaded.append(name)

    async def resolve(name): return name
    monkeypatch.setattr(system, "preload_model", preload)
    monkeypatch.setattr(system, "resolve_chat_model", resolve)
    monkeypatch.setattr(transcribe, "_load", lambda: whisper.append(1))
    r = app.post("/chat/warm", json={"model": "llama3.2:latest", "whisper": True}).json()
    assert r == {"warmed": True, "model": "llama3.2:latest"} and loaded == ["llama3.2:latest"] and whisper == [1]
    app.post("/chat/warm", json={})
    assert loaded[-1] == system.active_models()["llm"]                                   # no choice: the default, never another


def test_inference_stats_report_prompt_time_for_first_token_latency():
    import system_service as system
    s = system.record_inference({"eval_count": 10, "eval_duration": 1e9, "load_duration": 2e9, "prompt_eval_duration": 5e8, "total_duration": 4e9}, "m")
    assert s["load_seconds"] == 2.0 and s["prompt_seconds"] == 0.5
