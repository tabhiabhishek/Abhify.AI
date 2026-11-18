"""Next build — desktop Personal AI Brain: discovery parsing (same real responses as the Android tests), records-only
briefing, permission-aware universal search, and the endpoints."""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import brain

RES = Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "test" / "resources"


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


def test_discovery_parsers_match_android_on_real_responses():
    mit = brain.parse_mit(json.loads((RES / "discover_mit.json").read_text(encoding="utf-8")))
    assert len(mit) == 3 and all(r["cost"] == "Free" and r["license"] == "CC BY-NC-SA 4.0" and r["url"].startswith("https://ocw.mit.edu/") for r in mit)
    paid = brain.parse_mit({"results": [{"title": "T", "url": "https://x", "free": False, "certification": True, "prices": ["11550"], "offered_by": {"name": "MIT xPRO"}}]})
    assert paid[0]["cost"] == "Paid"
    unknown = brain.parse_mit({"results": [{"title": "T", "url": "https://x", "free": False, "prices": []}]})
    assert unknown[0]["cost"] == "Check the provider"                       # never assumed free
    books = brain.parse_openstax(json.loads((RES / "discover_openstax.json").read_text(encoding="utf-8")), "Statistics")
    assert books and all("Statistics" in b["title"] for b in books)
    wb = brain.parse_wikibooks(json.loads((RES / "discover_wikibooks.json").read_text(encoding="utf-8")))
    assert [b["title"] for b in wb] == ["Python Programming"]


def test_briefing_uses_records_only_with_sources():
    now = 2_000_000_000
    projects = [{"id": "p1", "name": "Abhify", "archived": False, "status": {"next": ["Phone test"], "issues": ["No adb"], "current": "RAG done"}},
                {"id": "p2", "name": "Old", "archived": True, "status": {"next": ["x"]}}]
    tasks = [{"title": "Quiz", "status": "failed", "created_at": (now - 3600) * 1000}]
    attempts = [{"topic": "SQL", "correct": i < 2} for i in range(6)]
    b = brain.briefing(projects, tasks, attempts, {"articles": [{"title": "Rain"}], "retrieved_at": (now - 7200) * 1000}, now)
    titles = [s["title"] for s in b["sections"]]
    assert "Projects in progress" in titles and "Open issues" in titles and "Needs attention" in titles and "Study" in titles
    assert any(t.startswith("Saved news (") for t in titles)
    assert all(i["source"] for s in b["sections"] for i in s["items"])
    assert not any("Old" in i["text"] for s in b["sections"] for i in s["items"])       # archived projects stay quiet
    assert brain.briefing([], [], [], None, now)["empty"] is True


def test_search_respects_memory_permission():
    common = dict(conversations=[{"id": "c1", "title": "Voice plan"}], messages_hits=[], projects=[{"id": "p", "name": "Voice app", "status": {}}],
                  tasks=[], knowledge_files=["voice.pdf"], news_articles=[{"title": "Voice AI news", "url": "u"}])
    on = brain.search("voice", memories=[{"id": 1, "content": "likes voice answers"}], **common)
    off = brain.search("voice", memories=None, **common)
    assert {r["type"] for r in on} == {"Conversation", "Memory", "Project", "Knowledge file", "Saved news"}
    assert "Memory" not in {r["type"] for r in off}
    assert brain.search("x", memories=None, **common) == []


def test_endpoints(client, monkeypatch):
    async def fake(q, free_only=False):
        return {"topic": q, "results": [], "providers": [], "notes": []}
    monkeypatch.setattr(brain, "discover", fake)
    assert client.get("/discover", params={"q": "python"}).json()["topic"] == "python"
    b = client.get("/briefing").json()
    assert "sections" in b
    client.post("/memory", json={"content": "My favourite language is Kotlin"})
    assert any(r["type"] == "Memory" for r in client.get("/search", params={"q": "kotlin"}).json()["results"])
    client.put("/preferences", json={"memory_enabled": False})
    assert not any(r["type"] == "Memory" for r in client.get("/search", params={"q": "kotlin"}).json()["results"])


def test_plan_has_no_invented_deadlines_and_skills_need_evidence():
    projects = [{"id": "a", "name": "A", "archived": False, "status": {"issues": ["Crash"], "next": ["Ship"], "updated_at": 5}},
                {"id": "b", "name": "B", "archived": False, "status": {"next": ["Docs"], "updated_at": 9}},
                {"id": "c", "name": "C", "archived": True, "status": {"next": ["Hidden"]}}]
    p = brain.plan(projects)
    assert [e["title"] for e in p["entries"]] == ["Resolve: Crash", "Docs", "Ship"]          # issues first, recent projects first
    assert not any("overdue" in e["reason"].lower() for e in p["entries"]) and "no due dates" in p["note"]
    att = [{"topic": "SQL", "correct": i % 3 != 0, "at": i} for i in range(10)] + [{"topic": "Python", "correct": True, "at": 1}] * 3
    s = brain.skills(att, 5)
    sql = next(k for k in s["skills"] if k["skill"] == "sql"); py = next(k for k in s["skills"] if k["skill"] == "python")
    assert sql["accuracy"] == 60 and sql["trend"] in {"improving", "declining", "steady"}
    assert py["accuracy"] is None and py["level"] == "Not enough data yet"                     # 3 answers: no level
    assert next(k for k in brain.skills(att, 3)["skills"] if k["skill"] == "python")["accuracy"] == 100   # lower threshold → measured


def test_plan_and_skills_endpoints(client):
    assert "entries" in client.get("/plan").json()
    assert client.get("/skills", params={"min_answers": 2}).status_code == 422                 # threshold bounded
    assert client.get("/skills").json()["min_answers"] == 5
