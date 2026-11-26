"""7.2.1-dev.1 privacy patch (Windows): what reaches an online provider, project memory switch, Study context and the memory
switch, deleting uploaded documents. Provider HTTP is a mock transport that records every request body; no network."""
import asyncio
import json

import httpx
import pytest

import providers as P

KEY = "gsk_" + "a" * 40
CLOUD = "@groq:llama-3.3-70b-versatile"
MODELS = {"data": [{"id": "llama-3.3-70b-versatile"}]}


def run(c):
    return asyncio.run(c)


@pytest.fixture
def cloud(migrated, monkeypatch):
    """A connected Groq provider whose chat requests are recorded in .sent (bodies) and .chat_calls (count)."""
    import agent
    import local_file_service as lfs
    lfs.reset_cache()
    rec = type("Rec", (), {"sent": [], "chat_calls": 0})()

    def handler(req):
        if req.url.path.endswith("/models"):
            return httpx.Response(200, json=MODELS)
        rec.chat_calls += 1
        rec.sent.append(json.loads(req.content))
        lines = ['data: {"choices":[{"delta":{"content":"Hello"}}]}', 'data: {"choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}', "data: [DONE]"]
        return httpx.Response(200, text="\n".join(lines) + "\n", headers={"content-type": "text/event-stream"})
    monkeypatch.setattr(P, "_transport", httpx.MockTransport(handler))
    run(P.save_key("groq", KEY))
    monkeypatch.setattr(agent, "record_inference", lambda *a, **k: None)
    yield rec
    lfs.reset_cache()


def _plant(user_name=True):
    import database as db
    import memory_service as mem
    import projects
    if user_name:
        db.set_prefs(user_name="Markername Qx")
    mem.add_memory("My secret note is MEMORY-SECRET-5521", category="fact")
    p = projects.create("Zephyr Ledger", "Internal codename ZEPHYR-INSTR-4410")
    projects.update(p["id"], status={"objective": "Ship the ledger (ZEPHYR-STATUS-7731)", "current": "Building the API",
                                     "next": ["Write tests TASK-SECRET-9902"], "milestones": [], "issues": [], "decisions": [], "stack": ""})
    return p


MARKERS = ("ZEPHYR-STATUS-7731", "TASK-SECRET-9902", "ZEPHYR-INSTR-4410", "MEMORY-SECRET-5521", "Markername Qx")


# --------------------------------------------------------------------------- F1: cloud prompts
@pytest.mark.parametrize("question,cats", [
    ("What's the status of my projects?", ["projects"]),
    ("What's on my to-do list?", ["tasks"]),
    ("How much GPU memory is being used right now?", ["system"]),
])
def test_local_record_questions_never_reach_the_provider(cloud, question, cats):
    """T1 + T3: a question that needs project, task or system records is answered by the app itself; 0 provider calls."""
    import agent
    import realtime
    _plant()
    assert [c for c in realtime.resolve(question) if c in realtime.LOCAL] == cats
    r = run(agent.run_agent(question, None, model=CLOUD))
    assert cloud.chat_calls == 0
    assert "stay on this computer" in r["answer"] and "Groq" in r["answer"]
    assert r.get("private_records_kept_local") == cats


def test_cloud_prompt_has_no_user_name_memory_or_project_text(cloud):
    """T2 (+F1): a plain question: the provider receives no planted marker at all."""
    import agent
    _plant()
    r = run(agent.run_agent("Explain recursion in one sentence.", None, model=CLOUD))
    assert cloud.chat_calls == 1 and r["answer"] == "Hello there"
    body = json.dumps(cloud.sent)
    for m in MARKERS:
        assert m not in body, m


def test_external_live_information_still_reaches_cloud_prompts(cloud, monkeypatch):
    """T5: news/weather evidence is still sent (no regression); the engine is asked for external categories only."""
    import agent
    seen = {}

    async def fake_live(message, focus="auto", guest=False, refresh=False, external_only=False):
        seen["external_only"] = external_only
        return {"needed": ["news"], "state": "fresh", "sources": [], "notes": [], "problems": [], "retrieved_at": 1, "block": "\n\nNEWS-EVIDENCE-OK"}
    monkeypatch.setattr(agent, "_live", fake_live)
    run(agent.run_agent("What's the latest news about AI chips?", None, model=CLOUD))
    assert seen["external_only"] is True and "NEWS-EVIDENCE-OK" in json.dumps(cloud.sent)


def test_streamed_and_regenerated_cloud_turns_follow_the_same_rules(cloud):
    """T7: /chat/stream path (stream_agent) and regenerate behave like /chat."""
    import agent
    _plant()

    async def collect(**kw):
        return [ev async for ev in agent.stream_agent(**kw)]
    evs = run(collect(message="What's the status of my projects?", conversation_id=None, model=CLOUD))
    assert cloud.chat_calls == 0 and any(e["type"] == "done" and e.get("private_records_kept_local") for e in evs)
    evs = run(collect(message="Explain recursion.", conversation_id=None, model=CLOUD))
    cid = next(e for e in evs if e["type"] == "done")["conversation_id"]
    run(collect(message="Explain recursion.", conversation_id=cid, model=CLOUD, regenerate=True))
    body = json.dumps(cloud.sent)
    assert cloud.chat_calls == 2 and not any(m in body for m in MARKERS)


# --------------------------------------------------------------------------- F1b: history after switching from a local model
def test_cloud_history_keeps_only_exchanges_a_cloud_model_answered():
    import agent
    saved = [
        {"role": "user", "content": "status of my projects?", "model": None},
        {"role": "assistant", "content": "Zephyr: ZEPHYR-STATUS-7731", "model": "llama3.2"},
        {"role": "user", "content": "hello", "model": None},
        {"role": "assistant", "content": "Hi!", "model": CLOUD},
        {"role": "user", "content": "old question", "model": None},
        {"role": "assistant", "content": "old answer", "model": None},     # pre-6.x rows have no model: treated as local
        {"role": "user", "content": "unanswered", "model": None},
    ]
    hist, dropped = agent.cloud_history(saved)
    assert hist == [{"role": "user", "content": "hello"}, {"role": "assistant", "content": "Hi!"}]
    assert dropped == 5


def test_switching_a_chat_from_a_local_model_to_a_cloud_model_sends_no_local_answers(cloud):
    """T6 (integration): a local answer that quoted private records stays out of the cloud request; a notice says so."""
    import agent
    import memory_service as mem
    _plant(user_name=False)
    cid = mem.ensure_conversation(None, "status?")
    mem.add_message(cid, "user", "What's the status of my projects?")
    mem.add_message(cid, "assistant", "Zephyr Ledger: Ship the ledger (ZEPHYR-STATUS-7731); next: TASK-SECRET-9902", model="llama3.2")
    mem.add_message(cid, "user", "What do you remember about my secret note?")
    mem.add_message(cid, "assistant", "Your note is MEMORY-SECRET-5521.", model="llama3.2")
    r = run(agent.run_agent("Thanks. Now explain recursion.", cid, model=CLOUD))
    body = json.dumps(cloud.sent)
    assert cloud.chat_calls == 1 and not any(m in body for m in MARKERS)
    assert "4 earlier messages" in r["history_notice"] and "Groq" in r["history_notice"]
    r2 = run(agent.run_agent("And one more example?", cid, model=CLOUD))   # the cloud exchange itself is kept as history
    assert "Now explain recursion" in json.dumps(cloud.sent[-1]) and "ZEPHYR" not in json.dumps(cloud.sent[-1])
    assert "4 earlier messages" in r2["history_notice"]


# --------------------------------------------------------------------------- F2: project memory switch
def test_project_without_memory_has_no_memory_tools():
    """T8 + T10."""
    import agent
    names = lambda tools: sorted(t.name for t in tools)   # noqa: E731
    assert names(agent._project_tools(False)) == ["web_search"]
    assert names(agent._project_tools(True)) == ["save_memory", "search_memory", "web_search"]


def test_remember_request_in_a_project_with_memory_off_stores_nothing(migrated, monkeypatch):
    """T9: even if a model calls save_memory, a project with memory off can't store it (service-side check)."""
    import agent
    import memory_service as mem
    import projects
    p = projects.create("No memory project", "")
    projects.update(p["id"], use_memory=False)
    before = len(mem.list_memories())
    token = agent.PROJECT_SCOPE.set(p["id"])
    try:
        out = agent.save_memory.invoke({"content": "Remember that my favourite colour is teal"})
    finally:
        agent.PROJECT_SCOPE.reset(token)
    assert "Memory is off for this project" in out and len(mem.list_memories()) == before


# --------------------------------------------------------------------------- F3: Study personal context and the memory switch
def test_study_personal_context_respects_the_global_memory_switch(migrated):
    """T11 + T12 (Windows)."""
    import database as db
    import memory_service as mem
    import study
    import study_plus
    g = mem.add_memory("Become an LLM engineer", category="goal")
    study.create_course(track="aiml")
    study_plus.set_brain("c-aiml", True, [g["id"]], [])
    c = study.load()["courses"]["c-aiml"]
    ctx, used = study_plus.brain_context(c)
    assert "LLM engineer" in ctx and used
    db.set_prefs(memory_enabled=False)
    ctx, used = study_plus.brain_context(c)
    assert ctx == "" and used == []
    assert study_plus.brain_sources()["memory_enabled"] is False
    assert study.load()["courses"]["c-aiml"]["brain"]["memories"] == [g["id"]]          # choices kept, not deleted
    db.set_prefs(memory_enabled=True)
    assert "LLM engineer" in study_plus.brain_context(c)[0]


# --------------------------------------------------------------------------- F4: documents are shared knowledge (intended)
def test_documents_are_shared_knowledge_by_design():
    """F4 closed as intended behaviour (owner decision P1, 2026-11-25): general chat may use every indexed document; project
    chats are additionally limited to their own documents. This test documents the rule so a change is deliberate."""
    import agent
    import inspect
    src = inspect.getsource(agent.document_context)
    assert "knowledge.search" in src or "knowledge.retrieve" in src or "search(" in src


# --------------------------------------------------------------------------- A1: microphone levels (Desktop / Web)
def _voice_harness():
    import shutil
    import subprocess
    from pathlib import Path
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js is not installed on this computer (needed for the JS voice harness)")
    root = Path(__file__).resolve().parents[2]
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "voice_harness.js"), str(root / "frontend" / "app.js")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def test_faint_speech_is_heard_and_only_true_silence_is_called_silent():
    """T22: adaptive threshold; verdicts."""
    r = _voice_harness()
    v = r["vad"]
    assert v["faint_speech"]["result"] == "stop:silence"                   # heard as speech, then the pause ends it
    assert v["very_quiet_room_no_speech"]["result"] == "stop:nospeech"
    assert v["noisy_room_no_speech"]["result"] == "stop:nospeech" and v["noisy_room_speech"]["result"] == "stop:silence"   # noise still learnt
    assert r["verdict"] == ["silent", "silent", "very-quiet", "quiet", "quiet", "ok", "unknown"]
    assert "Let desktop apps access your microphone" in r["silent_message"] and "Intel Smart Sound" in r["silent_message"]


def test_recorder_never_discards_real_sound_and_never_judges_a_stopped_meter():
    """T22/T23 (static contract on app.js): discard only on 'silent'; meter readings only while the AudioContext runs."""
    from pathlib import Path
    app = (Path(__file__).resolve().parents[2] / "frontend" / "app.js").read_text(encoding="utf-8")
    rec = app[app.index("async function startRecorder()"):app.index("async function testMicrophone()")]
    assert "voice.ctx.resume?.()" in rec and "if (voice.ctx?.state !== 'running') return;" in rec
    assert "if (v === 'stop:nospeech' && micVerdict(voice.peak, voice.meterRan) === 'silent')" in rec
    assert "voice.peak < 0.01" not in app                                   # the old fixed threshold is gone


# --------------------------------------------------------------------------- F5: deleting an uploaded document
def test_deleting_an_upload_removes_it_from_search_projects_and_moves_the_file_to_trash(kb):
    """T15 + T17."""
    import time as _t
    from fastapi.testclient import TestClient
    import knowledge
    import local_file_service as lfs
    import main
    import projects
    r = knowledge.add_upload(b"The zephyrine protocol uses quantum ledgers for settlement. " * 20, "zephyr notes.md")
    assert r["chunks"] > 0
    p = projects.create("Ledger", "")
    projects.add_documents(p["id"], ["zephyr notes.md"])
    assert any("zephyrine" in d.page_content for d in knowledge.search("zephyrine protocol settlement"))
    with TestClient(main.app) as c:
        assert c.delete("/knowledge/documents/zephyr notes.md").status_code == 400                 # confirm required
        assert c.delete("/knowledge/documents/missing.md?confirm=true").status_code == 404
        out = c.delete("/knowledge/documents/zephyr notes.md?confirm=true").json()
        assert out["chunks_removed"] == r["chunks"] and out["file_in_trash"] and out["projects_updated"] == 1
        assert not c.get("/knowledge/files?source=upload").json()["files"]
    assert not any("zephyrine" in d.page_content for d in knowledge.search("zephyrine protocol settlement"))
    assert projects.get(p["id"])["documents"] == []
    trashed = list((lfs.documents_dir() / "trash").rglob("*zephyr notes.md"))
    assert len(trashed) == 1 and not list(lfs.uploads_dir().glob("*zephyr notes.md"))
    assert knowledge.purge_trash(now=_t.time() + 3 * 86400) == 0                                  # kept for 7 days
    assert knowledge.purge_trash(now=_t.time() + 8 * 86400) == 1 and not list((lfs.documents_dir() / "trash").rglob("*.md"))


def test_paired_devices_cannot_delete_documents(kb):
    """T16: the route is local_only (a request that isn't from this computer is refused)."""
    from fastapi.testclient import TestClient
    import knowledge
    import main
    knowledge.add_upload(b"Some text worth indexing for the remote check. " * 10, "remote.md")
    with TestClient(main.app, client=("192.168.1.42", 50000)) as c:                         # a paired phone, as in test_companion_privacy
        r = c.delete("/knowledge/documents/remote.md?confirm=true")
    assert r.status_code in (401, 403)
    assert knowledge.search("worth indexing remote check")


# --------------------------------------------------------------------------- 7.2.1-dev.2: installer update with a stray process
def test_installer_stops_every_abhify_process_and_never_installs_over_locked_files():
    """Owner's laptop: 'Error opening file for writing ...\runtime\_asyncio.pyd'. The installer must stop every python from
    the install folder (not only the recorded one), verify the old files are gone, and stop instead of a mixed install."""
    from pathlib import Path
    nsi = (Path(__file__).resolve().parents[2] / "packaging" / "windows" / "installer.nsi").read_text(encoding="utf-8")
    macro = nsi[nsi.index("!macro StopAbhifyProcesses"):nsi.index("!macroend")]
    assert "ExecutablePath.StartsWith($$d" in macro and "'python.exe'" in macro and "'pythonw.exe'" in macro and "$$d = '$INSTDIR" in macro
    install = nsi[nsi.index('Section "${APPNAME} (required)"'):nsi.index('Section "Desktop shortcut"')]
    assert install.count("!insertmacro StopAbhifyProcesses") >= 2 and "remove_old:" in install
    assert r'${If} ${FileExists} "$INSTDIR\runtime\*.*"' in install and "MB_RETRYCANCEL" in install and "Abort " in install
    assert install.index("--backup pre-upgrade") < install.index("remove_old:")                       # backup before removal
    uninstall = nsi[nsi.index('Section "Uninstall"'):]
    assert "!insertmacro StopAbhifyProcesses" in uninstall
    init = nsi[nsi.index("Function .onInit"):nsi.index("FunctionEnd", nsi.index("Function .onInit"))]
    assert r'${OrIf} ${FileExists} "$INSTDIR\app\*.*"' in init                                      # a half-removed install is an update
    sbx = (Path(__file__).resolve().parents[2] / "packaging" / "windows" / "sandbox" / "sandbox-test.ps1").read_text(encoding="utf-8")
    assert "upgrade_clears_stray_process" in sbx
