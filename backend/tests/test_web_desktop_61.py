"""6.1 web/desktop: interaction modes (R12, ported from Android Brain.profile), regenerate, and the context indicator.

A mode chosen by the user changes the history window, the context rules, the prompt scaffold and the Ollama sampling options;
it never changes the selected model. Auto-detected focuses keep the existing behaviour. No Ollama is needed here."""
import asyncio

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

import intelligence as I


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


def test_profiles_match_android_brain():
    # android/.../intel/Brain.kt Brain.profile: history, temperature, max tokens (QUICK 384)
    assert I.PROFILES["quick"] == {"history": 4, "context": False, "temperature": 0.2, "num_predict": 384}
    assert I.PROFILES["deep"]["history"] == 12 and I.PROFILES["coding"]["history"] == 10
    assert I.PROFILES["creative"]["temperature"] == 0.9 and I.PROFILES["learning"]["temperature"] == 0.4
    assert I.PROFILES["planning"]["context"] is True and I.PROFILES["research"]["context"] is True
    for mode in ("quick", "deep", "planning", "creative"):
        assert mode in I.FOCUSES and I.FORMATS[mode].lower().startswith(I.FOCUSES[mode].split()[0].lower())


def test_modes_are_never_auto_detected_and_auto_applies_no_profile():
    assert I.detect("write a poem and a plan for my roadmap")["focus"] not in ("quick", "deep", "planning", "creative")
    assert I.profile(I.detect("explain gradient descent")) is None              # auto: nothing changes
    d = I.detect("explain gradient descent", "quick")
    assert d["focus"] == "quick" and not d["auto"] and I.profile(d)["num_predict"] == 384
    assert "Quick answer mode" in I.instructions(d)
    assert I.trace(d) == "⚡ Quick answer"


def _saved(n):
    return [m for i in range(n) for m in ({"role": "user", "content": f"q{i}"}, {"role": "assistant", "content": f"a{i}"})]


def _prepare(monkeypatch, message, focus="auto", model=None, saved=None, memory="", regenerate=False):
    import agent
    import memory_service as mem
    saved = _saved(10) if saved is None else saved
    calls = {}
    monkeypatch.setattr(mem, "ensure_conversation", lambda cid, msg: "c1")
    monkeypatch.setattr(mem, "recent_messages", lambda cid, limit=12: saved[-limit:])
    monkeypatch.setattr(mem, "memory_context", lambda q: calls.setdefault("memory", memory) or memory)
    monkeypatch.setattr(agent, "document_context", lambda q: ("", []))
    monkeypatch.setattr(agent, "forced_document_context", lambda q: calls.setdefault("forced", q) and ("\n\nDOCS [notes.md]", ["notes.md"]))
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: ("default", a))
    monkeypatch.setattr(agent, "_mode_agent", lambda *a, **k: ("mode", a))
    out = asyncio.run(agent._prepare(message, "c1", model, None, focus, regenerate))
    assert len(out) == 6                                                          # the contract tests rely on
    return out, calls


def test_quick_mode_shrinks_history_skips_memory_and_caps_tokens_without_changing_model(monkeypatch, migrated):
    (prefs, cid, inputs, agent_, docs, project), calls = _prepare(monkeypatch, "what is a tensor?", "quick", model="llama3.2:latest",
                                                                  memory="\n\nThings...\n- (goal) interviews")
    assert len(inputs["messages"]) == 1 + 4 + 1                                   # system + Quick's 4 messages + the question
    assert "memory" not in calls and prefs["_ctx"]["memories"] == 0               # minimal context
    assert agent_ == ("mode", ("llama3.2:latest", 0.2, 384))                      # same model, Quick sampling
    assert "Quick answer mode" in inputs["messages"][0].content and prefs["_ctx"]["mode"] == "quick"


def test_quick_mode_still_uses_memory_when_the_question_is_about_the_user(monkeypatch, migrated):
    (prefs, *_), calls = _prepare(monkeypatch, "what are my goals?", "quick", memory="\n\nThings...\n- (goal) interviews")
    assert calls["memory"] and prefs["_ctx"]["memories"] == 1


def test_auto_and_default_calls_are_unchanged(monkeypatch, migrated):
    (prefs, cid, inputs, agent_, docs, project), calls = _prepare(monkeypatch, "explain gradient descent")
    assert len(inputs["messages"]) == 1 + 12 + 1 and agent_ == ("default", ())
    assert "forced" not in calls and prefs["_ctx"]["mode"] is None


def test_research_and_planning_look_in_documents_and_deep_keeps_twelve(monkeypatch, migrated):
    (prefs, cid, inputs, agent_, docs, _), calls = _prepare(monkeypatch, "compare RAG chunk sizes", "research")
    assert calls["forced"] and docs == ["notes.md"] and "DOCS [notes.md]" in inputs["messages"][0].content
    assert len(inputs["messages"]) == 1 + 8 + 1 and prefs["_ctx"]["documents"] == 1
    (_, _, inputs, agent_, _, _), _ = _prepare(monkeypatch, "why does this fail?", "deep")
    assert len(inputs["messages"]) == 1 + 12 + 1 and agent_[0] == "mode" and "Deep think mode" in inputs["messages"][0].content


def test_mode_agent_keeps_the_model_and_sets_sampling(monkeypatch):
    import agent
    agent.get_llm.cache_clear(); agent._mode_agent.cache_clear()
    llm = agent.get_llm(temperature=0.2, model="qwen2.5:latest", num_predict=384)
    assert llm.model == "qwen2.5:latest" and llm.temperature == 0.2 and llm.num_predict == 384
    assert agent.get_llm(model="qwen2.5:latest").num_predict is None               # the default call is unchanged
    agent.get_llm.cache_clear()


def test_regenerate_replaces_the_last_pair_only_after_a_new_answer(monkeypatch, migrated):
    import agent
    import memory_service as mem
    cid = mem.ensure_conversation(None, "What is RAG?")
    mem.add_message(cid, "user", "What is RAG?"); mem.add_message(cid, "assistant", "old answer")

    class Fake:
        async def ainvoke(self, inputs, config=None):
            Fake.seen = [m.content for m in inputs["messages"][1:]]
            return {"messages": [*inputs["messages"], AIMessage("new answer")]}
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: Fake())
    monkeypatch.setattr(agent, "record_inference", lambda *a, **k: None)
    monkeypatch.setattr(agent, "active_models", lambda: {"llm": "qwen2.5", "embed": "x"})
    r = asyncio.run(agent.run_agent("What is RAG?", cid, regenerate=True))
    assert Fake.seen == ["What is RAG?"]                                          # the old pair is not in the prompt
    assert [m["content"] for m in mem.recent_messages(cid)] == ["What is RAG?", "new answer"]
    assert r["context"]["history"] == 0 and "memories" in r["context"]
    # a different last question: nothing is dropped
    assert mem.drop_last_exchange(cid, "something else") is False and len(mem.recent_messages(cid)) == 2


def test_chat_api_accepts_modes_and_passes_regenerate_only_when_set(client, monkeypatch):
    import main
    seen = []

    async def fake(message, conversation_id, **kw):
        seen.append(kw)
        return {"answer": "ok", "conversation_id": "c1", "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake)
    assert client.post("/chat", json={"message": "hi", "focus": "creative"}).status_code == 200
    assert seen[-1] == {"focus": "creative"}
    client.post("/chat", json={"message": "hi", "thread_id": "c1", "regenerate": True})
    assert seen[-1] == {"regenerate": True}
    client.post("/chat", json={"message": "hi", "regenerate": True})                # no thread: nothing to regenerate
    assert seen[-1] == {}
    assert client.post("/chat", json={"message": "hi", "focus": "turbo"}).status_code == 422


def test_direct_page_links_serve_the_app_shell(client):
    for path in ("/home", "/assistant", "/study", "/more"):
        r = client.get(path)
        assert r.status_code == 200 and 'id="app"' in r.text, path
    r = client.get("/home/", follow_redirects=False)                              # relative asset URLs would break under a slash
    assert r.status_code == 308 and r.headers["location"] == "/home"
    assert client.get("/nothing-here").status_code == 404
    assert client.get("/ping").json()["app"] == "Abhify.AI"                       # API routes are unaffected
    import companion
    assert not {"/home", "/assistant", "/study", "/more"} & companion.API_PREFIXES  # pages, not API (phones may load them)


class _EmptyAgent:
    """A tool-bound model that returns an empty message (seen with qwen2.5 + tools on 'write a Python function')."""
    async def ainvoke(self, inputs, config=None):
        return {"messages": [*inputs["messages"], AIMessage("")]}

    async def astream(self, inputs, config=None, stream_mode=None):
        yield "values", {"messages": [*inputs["messages"], AIMessage("")]}


class _PlainLLM:
    def __init__(self, model=None, **kw):
        self.model, self.kw = model, kw

    async def ainvoke(self, messages):
        _PlainLLM.seen = (self.model, self.kw, len(messages))
        return AIMessage("def add(a, b):\n    return a + b")

    async def astream(self, messages):
        _PlainLLM.seen = (self.model, self.kw, len(messages))
        for part in ("def add(a, b):", "\n    return a + b"):
            yield AIMessage(part)


def _fallback_setup(monkeypatch):
    import agent
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: _EmptyAgent())
    monkeypatch.setattr(agent, "_mode_agent", lambda *a, **k: _EmptyAgent())
    monkeypatch.setattr(agent, "_live_agent", lambda *a, **k: _EmptyAgent())            # 7.1: code questions get the no-tools agent
    monkeypatch.setattr(agent, "get_llm", lambda temperature=None, json_mode=False, model=None, num_predict=None: _PlainLLM(model, temperature=temperature, num_predict=num_predict))
    monkeypatch.setattr(agent, "record_inference", lambda *a, **k: None)
    monkeypatch.setattr(agent, "active_models", lambda: {"llm": "qwen2.5", "embed": "x"})
    return agent


def test_an_empty_tool_round_is_answered_once_more_without_tools_by_the_same_model(monkeypatch, migrated):
    agent = _fallback_setup(monkeypatch)
    r = asyncio.run(agent.run_agent("Write a tiny Python function that adds two numbers", None, model="qwen2.5:latest", focus="quick"))
    assert r["answer"].startswith("def add") and "couldn't produce" not in r["answer"]
    assert _PlainLLM.seen[0] == "qwen2.5:latest" and _PlainLLM.seen[1] == {"temperature": 0.2, "num_predict": 384}   # same model, mode sampling


def test_the_streamed_chat_streams_the_fallback_answer(monkeypatch, migrated):
    agent = _fallback_setup(monkeypatch)

    async def collect():
        return [ev async for ev in agent.stream_agent("Write a tiny Python function", None)]
    evs = asyncio.run(collect())
    assert [e["text"] for e in evs if e["type"] == "token"] == ["def add(a, b):", "\n    return a + b"]
    assert evs[-1]["type"] == "done" and evs[-1]["answer"] == "def add(a, b):\n    return a + b" and _PlainLLM.seen[0] is None


# --------------------------------------------------------------------------- frontend (real app.js functions in Node)
import json as _json
import shutil as _shutil
import subprocess as _subprocess
from pathlib import Path as _Path

_ROOT = _Path(__file__).resolve().parents[2]
_NODE = _shutil.which("node")


@pytest.fixture(scope="module")
def web():
    if not _NODE:
        pytest.skip("Node.js not installed")
    out = _subprocess.run([_NODE, str(_Path(__file__).parent / "js" / "web61_harness.js"), str(_ROOT / "frontend" / "app.js")],
                          capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return _json.loads(out.stdout)


def test_code_blocks_have_language_copy_and_safe_highlighting(web):
    h = web["md_code"]
    assert '<h4 class="md-h">Setup</h4>' in h and 'data-copy="code"' in h and "<span>python</span>" in h
    assert '<span class="tk-k">import</span>' in h and '<span class="tk-c"># load</span>' in h and '<span class="tk-s">&quot;hi&quot;</span>' in h
    assert "<p><div" not in h                                                    # a code block is never wrapped in <p>
    assert "<script>" not in web["md_xss"] and "<img" not in web["md_xss"] and "&lt;script&gt;" in web["md_xss"]
    assert "<th>a</th>" in web["md_table"] and "<td>&lt;b&gt;</td>" in web["md_table"]
    assert web["hl_sql"].count('class="tk-k"') == 3 and "tk-c" in web["hl_sql"]
    assert "&quot;a&lt;b&quot;" in web["hl_js"] and web["hl_unknown"] == "&lt;b&gt;x&lt;/b&gt;"


def test_message_tools_follow_ups_context_and_retry(web):
    assert 'data-copy="msg"' in web["tools_regen"] and 'data-regen-q="q &quot;x&quot;"' in web["tools_regen"]
    assert "data-regen-q" not in web["tools_plain"]
    assert "Tell me more about Gradient descent" in web["followups"] and web["followups_short"] == ""
    assert "2 saved memories" in web["ctx_used"] and "1 document excerpt" in web["ctx_used"] and "Thesis" in web["ctx_used"]
    assert "none of your data" in web["ctx_none"] and web["ctx_absent"] == ""
    assert 'data-retry="why &lt;b&gt;?"' in web["retry"] and 'data-regen="1"' in web["retry"]


def test_pages_open_from_the_address(web):
    assert web["routes"] == ["study", "chat", "more", "news", None, None, None]


def test_backend_loss_on_home_does_not_loop(web):
    # before 6.1 every failing Home request re-rendered Home, which sent the requests again (endless, growing loop)
    assert web["offline_home_renders"] <= 1


def test_shell_guards_for_6_1():
    app = (_ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
    sw = (_ROOT / "frontend" / "sw.js").read_text(encoding="utf-8")
    html = (_ROOT / "frontend" / "index.html").read_text(encoding="utf-8")
    css = (_ROOT / "frontend" / "styles.css").read_text(encoding="utf-8")
    assert "const SHELL_REV = 'ui72.5';" in sw and "abhify-v7.2.1'" in sw   # 7.2.1 final
    for mode in ("quick", "deep", "planning", "creative"):
        assert f'<option value="{mode}"' in html                                  # the modes reuse the chat focus selector
    assert "bubble.onclick = (e) => { if (e.target.closest('.chat-stop')) ctrl.abort(); }" in app   # Stop works while streaming
    assert "+ stopBtn()" in app
    assert "if (top.id === 'settingsSheet') closeSettings(); else top.classList.add('hidden');" in app   # Esc closes dialogs
    assert "--dur-base: 220ms; --dur-slow: 340ms" in css and "cubic-bezier(.05, .7, .1, 1)" in css   # Android motion tokens
    assert ":focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }" in css          # ring keeps each control's shape
    assert "@media (prefers-reduced-motion: reduce)" in css
