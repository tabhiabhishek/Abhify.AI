"""5.6 desktop: Universal Search 2.0 (filters, groups, memory isolation), read-only project Status overview, Desktop
Agent 2.0 (typed tools, plan -> approved apply -> verify -> undo), duplicate detection, briefing kinds, skills learning
path / revision plan, the local retrieval evaluation, and local_only on every new route. Temp data dirs only."""
import json
import time

import pytest
from fastapi.testclient import TestClient

import brain
import insights


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import knowledge
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    docs = ["fastapi_routing_notes.pdf", "local-rag-retrieval.md", "holiday_photos.docx"]
    monkeypatch.setattr(knowledge, "list_sources", lambda: list(docs))
    insights._RUNS.clear()
    with TestClient(main.app) as c:
        yield c


def _seed(client, **status):
    p = client.post("/projects", json={"name": "Abhify backend", "instructions": "FastAPI service with local RAG retrieval.\nTODO: finish the eval page"}).json()
    st = {"objective": "Local AI assistant", "current": "Routing done", "milestones": ["Add dark mode"],
          "issues": ["Waiting for Chroma upgrade"], "next": ["Write retrieval tests", "Dark mode"], "decisions": ["Use SQLite"], **status}
    assert client.patch(f"/projects/{p['id']}", json={"status": st}).status_code == 200
    return p["id"]


# --------------------------------------------------------------------------- Universal Search 2.0
NOW = 2_000_000_000
COMMON = dict(
    conversations=[{"id": "c1", "title": "Voice plan", "updated_at": "2033-05-17 12:00:00"},
                   {"id": "c2", "title": "Old voice idea", "updated_at": "2033-01-01 12:00:00"}],
    messages_hits=[], projects=[{"id": "p", "name": "Voice app", "status": {"updated_at": NOW - 60}, "documents": ["voice.pdf"], "conversations": ["c1"]}],
    tasks=[{"id": "t1", "title": "Voice summary", "status": "completed", "project_id": "p", "created_at": (NOW - 3600) * 1000},
           {"id": "t2", "title": "Voice quiz", "status": "ready", "created_at": (NOW - 100 * 86400) * 1000}],
    knowledge_files=["voice.pdf", "voice-other.pdf"], news_articles=[{"title": "Voice AI news", "url": "u", "published_at": (NOW - 7200) * 1000}],
    mcq_attempts=[{"topic": "Voice UX", "correct": True, "at": (NOW - 50) * 1000}])


def test_search_groups_counts_and_keeps_old_fields():
    r = insights.universal_search("voice", memories=[{"id": 1, "content": "likes voice answers", "created_at": "2033-05-18 00:00:00"}], now=NOW, **COMMON)
    keys = [g["key"] for g in r["groups"]]
    assert keys == ["conversation", "memory", "project", "task", "document", "news", "study"]
    assert {g["key"]: g["count"] for g in r["groups"]}["conversation"] == 2
    assert r["total"] == sum(g["count"] for g in r["groups"])
    for x in r["results"]:
        assert {"type", "title", "snippet", "ref"} <= set(x) and x["source"] and x["target"]["tab"]


def test_search_filters_types_days_project_sort():
    t = insights.universal_search("voice", memories=None, types=insights.parse_types("task,document"), now=NOW, **COMMON)
    assert {x["type_key"] for x in t["results"]} == {"task", "document"}
    d = insights.universal_search("voice", memories=None, days=7, now=NOW, **COMMON)
    refs = {x["ref"] for x in d["results"]}
    assert "c1" in refs and "c2" not in refs and "t2" not in refs and "voice.pdf" not in refs       # undated files drop out with a date filter
    p = insights.universal_search("voice", memories=[{"id": 1, "content": "voice"}], project="p", now=NOW, **COMMON)
    assert {x["ref"] for x in p["results"]} == {"c1", "p", "t1", "voice.pdf"}                       # no memories, news or study in a project
    rec = insights.universal_search("voice", memories=None, sort="recent", now=NOW, **COMMON)
    ats = [x["at"] or 0 for x in rec["results"]]
    assert ats == sorted(ats, reverse=True)
    assert insights.parse_types("Conversation, bogus") == {"conversation"} and insights.parse_types("bogus") is None


def test_search_endpoint_memory_isolation_and_filters(client):
    client.post("/memory", json={"content": "My favourite language is Kotlin"})
    on = client.get("/search", params={"q": "kotlin"}).json()
    assert any(g["key"] == "memory" for g in on["groups"]) and any(r["type"] == "Memory" for r in on["results"])
    client.put("/preferences", json={"memory_enabled": False})
    off = client.get("/search", params={"q": "kotlin"}).json()
    assert not any(r["type"] == "Memory" for r in off["results"]) and not any(g["key"] == "memory" for g in off["groups"])
    pid = _seed(client)
    r = client.get("/search", params={"q": "fastapi", "types": "project,document", "sort": "recent"}).json()
    assert {x["type_key"] for x in r["results"]} == {"project", "document"} and r["filters"]["sort"] == "recent"
    assert client.get("/search", params={"q": "fastapi", "project": pid}).json()["groups"][0]["key"] == "project"
    assert client.get("/search", params={"q": "x", "sort": "bogus"}).status_code == 422
    assert client.get("/search", params={"q": "a"}).json() == {"results": [], "groups": [], "total": 0}


# --------------------------------------------------------------------------- project Status overview
def test_project_overview_is_read_only_and_invents_nothing(client):
    pid = _seed(client)
    before = client.get(f"/projects/{pid}").json()
    o = client.get(f"/projects/{pid}/overview").json()
    assert o["read_only"] is True and o["status"]["recorded"] and o["status"]["age"] == "just now"
    assert [m["text"] for m in o["milestones"]] == ["Add dark mode"] and o["issues"][0]["text"] == "Waiting for Chroma upgrade"
    assert all(x["source"] for k in ("milestones", "issues", "decisions", "next", "next_possible_actions") for x in o[k])
    assert [a["text"] for a in o["next_possible_actions"]] == ["Resolve: Waiting for Chroma upgrade", "Write retrieval tests", "Dark mode"]
    flat = json.dumps(o).lower()
    assert not (insights.FORBIDDEN_OVERVIEW_KEYS & set(o)) and '"due' not in flat and "deadline" not in flat
    assert client.get(f"/projects/{pid}").json()["updated_at"] == before["updated_at"]            # reading changed nothing
    empty = client.post("/projects", json={"name": "Empty"}).json()
    e = client.get(f"/projects/{empty['id']}/overview").json()
    assert e["status"]["recorded"] is False and e["next_possible_actions"] == [] and e["milestones"] == []
    assert client.get("/projects/nope/overview").status_code == 404


# --------------------------------------------------------------------------- agent: tools, plan, apply, verify, undo
def test_agent_validate_rules():
    assert "not a registered tool" in insights.validate("shell.run", {}, True)
    assert "never done by the agent" in insights.validate("data.delete", {}, True)               # SENSITIVE: always refused
    assert "approval" in insights.validate("doc.link", {"project_id": "p", "document": "d"}, False)
    assert insights.validate("doc.link", {"project_id": "p"}, True) == "Missing document."
    assert "Unknown input" in insights.validate("next.add", {"project_id": "p", "text": "x", "sql": "DROP"}, True)
    assert "too long" in insights.validate("next.add", {"project_id": "p", "text": "x" * 401}, True)
    assert insights.validate("project.read", {"project_id": "p"}, False) is None                  # READ needs no approval
    assert insights.validate("next.add", {"project_id": "p", "text": "ok"}, True) is None
    assert not any(k in n for n in insights.TOOLS for k in ("sql", "shell", "exec"))
    assert all(v["kind"] == "SENSITIVE" for n, v in insights.TOOLS.items() if "delete" in n)          # no deletion tool can run
    assert {v["kind"] for v in insights.TOOLS.values()} == {"READ", "REVERSIBLE", "SENSITIVE"}


def test_agent_plan_apply_verify_undo_round_trip(client, data_root):
    pid = _seed(client)
    plan = client.post("/agent/plan", json={"project_id": pid, "workflow": "docs"}).json()
    assert plan["changed"] is False and [p["args"]["document"] for p in plan["proposals"]] == ["local-rag-retrieval.md", "fastapi_routing_notes.pdf"]   # most shared terms first
    assert client.get(f"/projects/{pid}").json()["documents"] == []                                 # planning changed nothing
    ids = [p["id"] for p in plan["proposals"]]
    refused = client.post("/agent/apply", json={"project_id": pid, "run_id": plan["run_id"], "item_ids": ids})
    assert refused.status_code == 403 and client.get(f"/projects/{pid}").json()["documents"] == []
    ok = client.post("/agent/apply", json={"project_id": pid, "run_id": plan["run_id"], "item_ids": ids, "approved": True}).json()
    assert ok["applied"] == 2 and all(r["verified"] for r in ok["results"]) and ok["can_undo"]
    assert sorted(client.get(f"/projects/{pid}").json()["documents"]) == ["fastapi_routing_notes.pdf", "local-rag-retrieval.md"]
    un = client.post("/agent/undo", json={"run_id": plan["run_id"]}).json()
    assert un["undone"] and all(r["verified"] for r in un["reverted"])
    assert client.get(f"/projects/{pid}").json()["documents"] == []
    assert client.post("/agent/undo", json={"run_id": plan["run_id"]}).status_code == 400            # once only

    # next.add from "unfinished": the instruction line is quoted verbatim, added, verified, then removed again
    client.post(f"/projects/{pid}/documents", json={"names": ["holiday_photos.docx"]})                # a link the agent didn't make
    u = client.post("/agent/plan", json={"project_id": pid, "workflow": "unfinished"}).json()
    assert any(l["text"] == '"TODO: finish the eval page"' and l["source"] == "project instructions" for s in u["sections"] for l in s["lines"])
    prop = next(p for p in u["proposals"] if p["tool"] == "next.add")
    assert prop["args"]["text"] == "TODO: finish the eval page"
    r = client.post("/agent/apply", json={"project_id": pid, "run_id": u["run_id"], "item_ids": [prop["id"], "zzz"], "approved": True}).json()
    assert r["applied"] == 1 and r["results"][0]["verified"] and r["results"][1]["applied"] is False
    assert client.get(f"/projects/{pid}").json()["status"]["next"][-1] == "TODO: finish the eval page"
    client.post("/agent/undo", json={"run_id": u["run_id"]})
    after = client.get(f"/projects/{pid}").json()
    assert after["status"]["next"] == ["Write retrieval tests", "Dark mode"] and after["documents"] == ["holiday_photos.docx"]   # only its own change undone

    audit = (data_root / "data" / "agent_runs.json").read_text(encoding="utf-8")
    assert "eval page" not in audit and "fastapi_routing" not in audit and "Abhify backend" not in audit    # ids and counts only
    assert [e["event"] for e in json.loads(audit)] == ["plan", "apply", "undo", "plan", "apply", "undo"]     # the refused apply changed nothing
    assert client.post("/agent/apply", json={"project_id": pid, "run_id": "missing", "item_ids": ["i1"], "approved": True}).status_code == 404
    assert client.post("/agent/plan", json={"project_id": pid, "workflow": "delete_all"}).status_code == 422
    assert client.post("/agent/plan", json={"project_id": "nope", "workflow": "status"}).status_code == 404


def test_agent_status_and_priorities_name_their_sources(client):
    pid = _seed(client)
    s = client.post("/agent/plan", json={"project_id": pid, "workflow": "status"}).json()
    titles = [x["title"] for x in s["sections"]]
    assert titles[:3] == ["Recorded", "Work", "Blockers"] and "Possible duplicates" in titles
    assert all(l["source"] for x in s["sections"] for l in x["lines"]) and s["proposals"] == []
    pr = client.post("/agent/plan", json={"project_id": pid, "workflow": "priorities"}).json()
    lines = [l for x in pr["sections"] for l in x["lines"]]
    assert any(l["text"] == "Unblock first: Waiting for Chroma upgrade" and 'wording "Waiting for"' in l["source"] for l in lines)
    assert not any(k in json.dumps(pr).lower() for k in ("deadline", "overdue", "due:"))
    assert pr["proposals"][0]["args"]["text"] == "Follow up: Waiting for Chroma upgrade"
    d = client.post("/agent/plan", json={"project_id": pid, "workflow": "duplicates"}).json()
    assert d["duplicates"][0]["kind"] == "already_done" and d["proposals"] == []                    # never merged
    assert client.get("/agent/tools").json()["tools"]


# --------------------------------------------------------------------------- duplicate detection
@pytest.mark.parametrize("a,b,match", [
    ("Fix the login bug", "fixing login bugs", True),
    ("Write retrieval tests", "write tests for retrieval", True),
    ("Update docs for setup", "Update the setup docs page", True),          # Jaccard 0.75
    ("Phone test on Pixel", "Run the phone test on Pixel 8 tomorrow", True),  # containment, >= 2 tokens
    ("Deploy to staging", "Update the README", False),
    ("Test", "Test the release build twice", False),                       # containment needs 2 tokens
])
def test_similarity_cases(a, b, match):
    assert insights.similar(a, b)[0] is match


def test_duplicates_done_vs_open_and_same_project_only():
    items = [{"text": "Add dark mode", "state": "done", "source": "milestone"}, {"text": "dark mode", "state": "open", "source": "next"},
             {"text": "Write retrieval tests", "state": "open", "source": "next"}, {"text": "write the retrieval tests", "state": "open", "source": "issue"},
             {"text": "Ship v1", "state": "done", "source": "milestone"}, {"text": "Ship v1", "state": "done", "source": "milestone"}]
    d = insights.find_duplicates(items)
    kinds = sorted(x["kind"] for x in d)
    assert kinds == ["already_done", "duplicate"]
    done = next(x for x in d if x["kind"] == "already_done")
    assert done["label"] == "Already done?" and done["open"]["text"] == "dark mode"
    assert insights.stem("fixes") == insights.stem("fixed") == insights.stem("fixing") == insights.stem("fix")
    assert insights.stem("issues") == insights.stem("issue")
    other = {"id": "q", "status": {"next": ["Write retrieval tests"]}}
    mine = {"id": "p", "status": {"next": ["Write tests"]}}
    assert insights.project_items(mine, [{"project_id": "q", "title": "Write retrieval tests"}]) == \
        [{"text": "Write tests", "state": "open", "source": "next action (project status)"}]     # another project's task never mixes in
    assert other["id"] != mine["id"]


# --------------------------------------------------------------------------- briefing kinds
def test_briefing_kinds_records_only():
    now = insights.local_midnight(time.time()) + 12 * 3600                                    # midday: "today" is unambiguous
    projects = [{"id": "a", "name": "A", "archived": False, "status": {"current": "RAG works", "issues": ["Crash on start"], "next": ["Ship"], "updated_at": now - 60}},
                {"id": "b", "name": "B", "archived": False, "status": {"next": ["Docs"], "updated_at": now - 3 * 86400}},
                {"id": "z", "name": "Gone", "archived": True, "status": {"issues": ["x"], "updated_at": now - 60}}]
    tasks = [{"title": "Quiz done", "status": "completed", "completed_at": (now - 30) * 1000, "created_at": (now - 90) * 1000},
             {"title": "Old one", "status": "completed", "completed_at": (now - 10 * 86400) * 1000, "created_at": 1}]
    attempts = [{"topic": "SQL", "correct": i % 2 == 0, "at": (now - 20) * 1000} for i in range(4)]
    ev = insights.briefing("evening", projects, tasks, attempts, None, now=now)
    t = {s["title"]: s for s in ev["sections"]}
    assert set(t) == {"Projects updated today", "AI tasks completed today", "Practice today"}
    assert [i["text"] for i in t["AI tasks completed today"]["items"]] == ["Quiz done"]
    assert t["Practice today"]["items"][0]["text"].startswith("4 MCQ answers, 2 correct")
    wk = insights.briefing("weekly", projects, tasks, attempts, None, conversations=[{"id": "c", "updated_at": "2000-01-01 00:00:00"}], now=now)
    wt = {s["title"]: s for s in wk["sections"]}
    assert "Last 7 days" in wt and "Still open issues" in wt and not any("Gone" in i["text"] for s in wk["sections"] for i in s["items"])
    assert not any("chats active" in i["text"] for i in wt["Last 7 days"]["items"])                    # zero counts are omitted
    mo = insights.briefing("morning", projects, tasks, attempts, None, now=now)
    assert mo["sections"][0]["title"] == "Today's plan" and all(i["source"] for s in mo["sections"] for i in s["items"])
    assert insights.briefing("evening", [], [], [], None, now=now) == {"kind": "evening", "sections": [], "empty": True, "built_at": int(now)}
    with pytest.raises(ValueError):
        insights.briefing("yearly", [], [], [], None)


def test_briefing_endpoint_kinds(client):
    assert "sections" in client.get("/briefing").json() and "kind" not in client.get("/briefing").json()      # default unchanged
    for k in ("now", "morning", "evening", "weekly"):
        assert client.get("/briefing", params={"kind": k}).status_code == 200
    assert client.get("/briefing", params={"kind": "evening"}).json()["kind"] == "evening"
    assert client.get("/briefing", params={"kind": "yearly"}).status_code == 422


# --------------------------------------------------------------------------- skills: learning path + revision
def test_skills_learning_path_and_revision_plan():
    a = [{"topic": "Machine Learning", "correct": i < 2, "at": i} for i in range(6)]                     # 2/6 weak
    a += [{"topic": "python", "correct": i < 9, "at": i} for i in range(10)]                             # strong, steady
    a += [{"topic": "SQL", "correct": i < 5, "at": i} for i in range(10)]                                # 5/10 weak
    a += [{"topic": "react", "correct": i < 8, "at": i} for i in range(10)]                              # 8/10 = Strong, but declining (5 then 3 correct)
    s = brain.skills(a)
    assert all(x["answers_label"] == f"{x['answered']} answers" for x in s["skills"])
    path = [x["skill"] for x in s["learning_path"]]
    assert path.index("python") < path.index("machine learning") and path.index("probability") < path.index("statistics") < path.index("machine learning")
    ml = next(x for x in s["learning_path"] if x["skill"] == "machine learning")
    assert ml["prerequisites"] == ["python", "statistics", "linear algebra"] and ml["yours"] and ml["answers_label"] == "6 answers"
    assert next(x for x in s["learning_path"] if x["skill"] == "statistics")["level"] == "Not practised yet"
    rev = [x["skill"] for x in s["revision"]]
    assert rev[:2] == ["machine learning", "sql"] and "react" in rev and rev.index("react") > 1
    assert brain.skills([])["learning_path"] == [] and brain.skills([])["revision"] == []
    assert brain.norm_skill("Intro to  Machine   Learning") == "machine learning"


# --------------------------------------------------------------------------- local evaluation + provider counters
def test_eval_endpoint_reports_precision_and_latency(client):
    r = client.get("/eval/retrieval").json()
    assert r["k"] == 3 and 0 <= r["search"]["mean_precision_at_k"] <= 1 and r["search"]["mean_precision_at_k"] >= 0.8
    assert r["related_documents"]["mean_precision_at_k"] >= 0.8 and r["duplicates"]["accuracy"] == 1.0
    assert all(c["latency_ms"] >= 0 for c in r["search"]["cases"]) and r["latency_ms"]["max"] < 1000
    assert isinstance(r["provider_failures"], dict) and "synthetic" in r["fixture"]
    assert client.get("/eval/retrieval", params={"k": 0}).status_code == 422


def test_provider_failure_counter_counts_only(client, monkeypatch):
    import main
    import system_service as system

    async def boom(*a, **k):
        raise ConnectionError("secret prompt text should never be stored")

    async def nothing(*a, **k):
        return None
    monkeypatch.setattr(main, "run_agent", boom)
    monkeypatch.setattr(system, "note_chat_model", nothing)                                   # no Ollama call
    before = insights.provider_failures().get("chat:ConnectionError", 0)
    assert client.post("/chat", json={"message": "hello"}).status_code == 503
    after = insights.provider_failures()
    assert after["chat:ConnectionError"] == before + 1 and "secret" not in json.dumps(after)


# --------------------------------------------------------------------------- every new route is PC-only
NEW_ROUTES = {("GET", "/projects/{pid}/overview"), ("GET", "/agent/tools"), ("POST", "/agent/plan"), ("POST", "/agent/apply"),
              ("POST", "/agent/undo"), ("GET", "/eval/retrieval"), ("GET", "/search"), ("GET", "/briefing"), ("GET", "/skills")}


def test_every_new_route_is_local_only():
    import main
    found = set()
    for r in main.app.routes:
        for m in getattr(r, "methods", set()) or set():
            if (m, r.path) in NEW_ROUTES:
                found.add((m, r.path))
                assert any(d.call is main.local_only for d in r.dependant.dependencies), r.path
    assert found == NEW_ROUTES


def test_new_paths_are_never_cached_and_documented():
    from pathlib import Path
    root = Path(__file__).resolve().parents[2]
    sw = (root / "frontend" / "sw.js").read_text(encoding="utf-8")
    assert "'/agent'" in sw and "'/eval'" in sw
    readme = (root / "docs" / "ABHIFY_AI_COMPLETE_DOCUMENTATION.md").read_text(encoding="utf-8")   # API table (7.2.1)
    for p in ("/projects/{pid}/overview", "/agent/plan", "/agent/apply", "/agent/undo", "/agent/tools", "/eval/retrieval"):
        assert p in readme, p


# --------------------------------------------------------------------------- web: the new render helpers (Node harness)
def test_web_render_helpers():
    import shutil
    import subprocess
    from pathlib import Path
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    here = Path(__file__).parent
    out = subprocess.run([node, str(here / "js" / "desktop56_harness.js"), str(here.parents[1] / "frontend" / "app.js")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    assert r["list"].count('class="pal-group"') == 2 and "Conversation · 2" in r["list"] and r["list"].count('role="option"') == 4
    assert r["query_default"] == "/search?q=voice" and r["query_filtered"] == "/search?q=voice+%3Cx%3E&types=project&days=7&sort=recent"
    ov = r["overview"]
    assert "Abhify &lt;b&gt;" in ov and "Read-only" in ov and "<textarea" not in ov and "<input" not in ov and 'data-pact="save"' not in ov
    assert "Resolve: Waiting for Chroma" in ov and "not in the knowledge index" in ov
    assert 'data-agent-item="i1"' in r["plan"] and "Approve 1" in r["plan"] and "can be undone" in r["plan"]
    assert "Approve" not in r["plan_empty"] and "Nothing in this project" in r["plan_empty"]
    assert "Needs work · 6 answers" in r["skills"] and "Not enough data yet · 2 answers" in r["skills"]
    assert "Revision plan" in r["skills"] and "Learning path" in r["skills"] and "before react" in r["skills"]
