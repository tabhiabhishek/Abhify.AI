"""Study 7.2.0-dev.2 (desktop): mock interview, resume tailoring, Personal AI Brain opt-in, model quality check.
AI calls are faked; nothing reaches Ollama or the network."""
import json

import pytest
from fastapi.testclient import TestClient

RESUME = """Jane Doe
EXPERIENCE
- Built REST APIs in Python and FastAPI serving 10k users
- Cut report runtime by 30% by adding SQL indexes
- Mentored 2 junior developers on testing practices
- Organised the team's weekly knowledge-sharing sessions
"""
JD = """Backend Engineer
Requirements:
- Python, FastAPI and SQL
- Docker and Kubernetes
Nice to have: AWS"""


class AI:
    prompts = []
    mode = {}

    @classmethod
    async def invoke(cls, prompt, model, json_mode):
        cls.prompts.append((prompt, model, json_mode))
        if not json_mode:
            if "17 multiplied by 23" in prompt:
                return cls.mode.get("fact", "391")
            if "HTTP caching" in prompt:
                return "## In simple words\nx\n## Going deeper\ny\n## Common mistakes\nz\n## Interview questions\nq"
            return "\n".join(f"## {h}\n" + "Text. " * 30 for h in ["In simple words", "Going deeper", "Advanced: production and architecture",
                                                                 "Real-world example", "Code example", "Common mistakes"])
        if "Python lists" in prompt:
            return cls.mode.get("mcq", json.dumps({"questions": [{"q": "Q?", "options": ["a", "b", "c", "d"], "answer": 1, "explanation": "e"}]}))
        if "Rewrite these resume bullet points" in prompt:
            return json.dumps({"bullets": [
                {"i": 0, "text": "Designed Python and FastAPI REST APIs used by 10k users"},
                {"i": 1, "text": "Reduced SQL report runtime 30% with Docker and Kubernetes"},     # adds skills -> rejected
                {"i": 2, "text": "Coached 5 junior developers"}]})                                   # adds a number -> rejected
        if "realistic interview question" in prompt:
            return json.dumps({"questions": [{"skill": "Python", "question": "How do Python generators save memory?"}]})
        if "fair interviewer" in prompt:
            if cls.mode.get("bad_feedback"):
                return json.dumps({"scores": {"relevance": 9}})
            return json.dumps({"scores": {"relevance": 4, "depth": 3, "structure": 4}, "evidence": ["they yield values lazily", "I built Google"], "tips": ["Add an example."]})
        return "{}"


@pytest.fixture
def app(data_root, monkeypatch):
    import main
    import study
    import system_service as system
    AI.prompts, AI.mode = [], {}
    monkeypatch.setattr(study, "_invoke", AI.invoke)

    async def note(name): pass
    monkeypatch.setattr(system, "note_chat_model", note)
    with TestClient(main.app) as c:
        yield c


def test_tailoring_keeps_only_resume_facts(app):
    r = app.post("/learn/resume/tailor", json={"jd": JD, "resume_text": RESUME}).json()
    b = r["bullets"]
    assert b[0]["original"].startswith("Built REST APIs") and b[0]["rewritten"] == "Designed Python and FastAPI REST APIs used by 10k users"
    rej = {x["original"][:12]: x["rejected"] for x in b if x["rejected"]}
    assert "Docker" in rej["Cut report r"] and "Kubernetes" in rej["Cut report r"]
    assert "5" in rej["Mentored 2 j"]
    assert {"Docker", "Kubernetes", "AWS"} <= set(r["missing"]) and "Python" in r["covered"]
    assert "nothing was saved" in r["note"]
    assert "tailor" not in json.dumps(app.get("/learn/export").json()["data"])          # nothing stored
    assert app.post("/learn/resume/tailor", json={"jd": "short", "resume_text": RESUME}).status_code == 400
    assert app.post("/learn/resume/tailor", json={"jd": JD, "resume_text": "no bullets"}).status_code == 400


def test_resume_bullets_without_markers_use_sentences():
    import study_plus
    assert study_plus.resume_bullets("- one long enough bullet\n* another one here ok") == ["one long enough bullet", "another one here ok"]
    out = study_plus.resume_bullets("I worked at a big company doing many backend things. I also led migrations to the cloud for teams.")
    assert len(out) == 2


def test_mock_interview_flow(app):
    jd = JD.replace("Nice to have: AWS", "")
    app.post("/learn/plan", json={"jd": jd, "resume_text": "Python"})
    s = app.post("/learn/mock", json={"count": 4}).json()
    kinds = [q["kind"] for q in s["questions"]]
    assert kinds == ["technical", "behavioural", "technical", "behavioural"]
    assert s["questions"][1]["question"] == "Tell me about a time you led a team or a piece of work." or s["questions"][1]["kind"] == "behavioural"
    tech = [q for q in s["questions"] if q["kind"] == "technical"]
    assert any(q["question"] == "How do Python generators save memory?" for q in tech) or all("Explain" in q["question"] for q in tech)
    r = app.post(f"/learn/mock/{s['id']}/answer", json={"i": 0, "answer": "Because they yield values lazily, one at a time.", "seconds": 75}).json()
    assert r["feedback"]["scores"] == {"relevance": 4, "depth": 3, "structure": 4}
    assert r["feedback"]["evidence"] == ["they yield values lazily"]                  # a quote not in the answer is dropped
    AI.mode["bad_feedback"] = True
    r2 = app.post(f"/learn/mock/{s['id']}/answer", json={"i": 1, "answer": "I led a small migration once.", "seconds": 30}).json()
    assert r2["feedback"] is None and "answer is saved" in r2["note"]
    fin = app.post(f"/learn/mock/{s['id']}/finish").json()
    assert fin["summary"] == {"answered": 1, "percent": 73, "means": {"relevance": 4.0, "depth": 3.0, "structure": 4.0}, "weakest": "depth"}
    lst = app.get("/learn/mock").json()["sessions"]
    assert lst[0]["answered"] == 2 and lst[0]["summary"]["percent"] == 73
    assert app.get(f"/learn/mock/{s['id']}").json()["questions"][1]["answer"] == "I led a small migration once."
    assert app.post(f"/learn/mock/{s['id']}/answer", json={"i": 0, "answer": " "}).status_code == 400
    assert app.delete(f"/learn/mock/{s['id']}").status_code == 200 and app.get("/learn/mock").json()["sessions"] == []


def test_brain_is_off_by_default_and_shares_only_ticked_items(app):
    import memory_service as mem
    import projects
    g = mem.add_memory("Become an LLM engineer at a health-tech company", category="goal")
    other = mem.add_memory("I prefer short examples", category="preference")
    secret = mem.add_memory("My bank PIN hint is the cat", category="general")
    p = projects.create("Clinic chatbot", "RAG over clinic FAQs")
    src = app.get("/learn/brain/sources").json()
    assert [x["id"] for x in src["goals"]] == [g["id"]] and p["id"] in [x["id"] for x in src["projects"]]
    cid = app.post("/learn/courses", json={"track": "aiml"}).json()["id"]
    app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "aiml-llm-1"})
    assert "Learner context" not in AI.prompts[-1][0]                                   # off by default
    app.put(f"/learn/courses/{cid}/brain", json={"enabled": True, "memories": [g["id"], other["id"]], "projects": [p["id"]]})
    les = app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "aiml-llm-2"}).json()
    prompt = AI.prompts[-1][0]
    assert "health-tech" in prompt and "short examples" in prompt and "Clinic chatbot" in prompt and "bank PIN" not in prompt
    assert les["used"] == ["Goal: Become an LLM engineer at a health-tech company", "Note: I prefer short examples", "Project: Clinic chatbot"]
    app.put(f"/learn/courses/{cid}/brain", json={"enabled": False, "memories": [g["id"]], "projects": []})
    app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "aiml-llm-3"})
    assert "Learner context" not in AI.prompts[-1][0]
    assert secret["id"] not in app.get(f"/learn/courses/{cid}").json()["brain"]["memories"]


def test_quality_check_verdicts_are_cached(app):
    r = app.post("/learn/quality", json={}).json()
    assert r["verdict"] == "good" and all(r["probes"].values())
    AI.mode.update(fact="It is 400", mcq="not json")
    r2 = app.post("/learn/quality", json={}).json()
    assert r2["verdict"] == "poor" and "larger model" in r2["advice"]
    assert app.get("/learn/quality").json()["result"]["verdict"] == "poor"


# --------------------------------------------------------------------------- vendored Mermaid + UI contracts
import hashlib  # noqa: E402
from pathlib import Path  # noqa: E402

FRONT = Path(__file__).resolve().parents[2] / "frontend"
MERMAID = {"mermaid-11.4.1.min.js": "a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e",
           "LICENSE": "ec9fb67dcb25eccc416ed56e1aab819222c805a2a4bfe4cb19e7556bf2ffde80"}


@pytest.mark.parametrize("name,sha", MERMAID.items())
def test_vendored_mermaid_matches_pinned_hash(name, sha):
    p = FRONT / "vendor" / "mermaid" / name
    assert hashlib.sha256(p.read_bytes()).hexdigest() == sha
    assert sha in (FRONT / "vendor" / "mermaid" / "VENDORED.md").read_text(encoding="utf-8")


def test_ui_safeguards():
    ui = (FRONT / "study-ui.js").read_text(encoding="utf-8")
    assert "securityLevel: 'strict'" in ui and "startOnLoad: false" in ui                     # diagrams: no scripts/click handlers
    assert "vendor/mermaid/mermaid-11.4.1.min.js" in ui and "cdn" not in ui.lower()            # local only, lazy
    assert "Off by default" in ui and "Chats are never used" in ui                              # personal context consent text
    assert "is done by the browser's maker (online)" in ui                                      # web speech notice before use
    assert "if (view !== 'mock') stopMockTimer();" in ui                                        # leaving the interview stops the mic
    sw = (FRONT / "sw.js").read_text(encoding="utf-8")
    assert "mermaid" not in sw.split("const SHELL = [")[1].split("];")[0]                      # not precached: loaded only when used


def test_lesson_prompt_asks_for_a_mermaid_diagram():
    import study
    assert "```mermaid" in study.LESSON_PROMPT


# --------------------------------------------------------------------------- Drive sync preparation (not connected)
def test_study_sync_is_prepared_but_not_connected():
    import study_sync
    import sync_core
    assert not set(study_sync.KINDS) & set(sync_core.KINDS)                     # the live protocol is unchanged
    main_src = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
    assert "study_sync" not in main_src and "study_sync" not in (Path(__file__).resolve().parents[1] / "datasync_service.py").read_text(encoding="utf-8")


def test_study_sync_merge_keeps_both_devices_progress():
    import study_sync
    a = {"courses": {"c-swe": {"id": "c-swe", "done": ["l1"], "scores": {"m": {"answered": 5, "correct": 3}}, "days": {"2026-12-01": 300},
                               "history": [{"at": 1, "kind": "prefs", "detail": "SWE"}], "prefs": {"target": "SWE"}, "exercises": ["l1"]}},
         "stories": [{"id": "s1", "title": "Old", "updated": 1}], "plan": {"created": 10, "jd_title": "A", "gaps": []}, "plan_history": [],
         "mcq": {"SQL": {"answered": 4, "correct": 2}}, "activity": ["2026-12-01"], "reviews": {"k": {"due": "2026-12-02", "times_right": 0}}}
    b = {"courses": {"c-swe": {"id": "c-swe", "done": ["l2"], "scores": {"m": {"answered": 2, "correct": 2}}, "days": {"2026-12-01": 120, "2026-12-02": 60},
                               "history": [{"at": 5, "kind": "prefs", "detail": "Cloud"}], "prefs": {"target": "Cloud"}},
                     "c-ds": {"id": "c-ds", "done": []}},
         "stories": [{"id": "s1", "title": "New", "updated": 2}, {"id": "s2", "title": "Other", "updated": 1}], "plan": {"created": 20, "jd_title": "B", "gaps": []},
         "mcq": {"SQL": {"answered": 3, "correct": 3}}, "activity": ["2026-12-02"], "reviews": {"k": {"due": "2026-12-05", "times_right": 1}}}
    m = study_sync.merge(a, b)
    c = m["courses"]["c-swe"]
    assert c["done"] == ["l1", "l2"] and c["scores"]["m"] == {"answered": 5, "correct": 3} and c["days"] == {"2026-12-01": 300, "2026-12-02": 60}
    assert c["seconds"] == 360 and c["prefs"]["target"] == "Cloud" and len(c["history"]) == 2 and c["exercises"] == ["l1"]
    assert "c-ds" in m["courses"] and [s["title"] for s in m["stories"]] == ["Other", "New"] or [s["title"] for s in m["stories"]] == ["New", "Other"]
    assert m["plan"]["jd_title"] == "B" and m["plan_history"][0]["jd_title"] == "A"
    assert m["mcq"]["SQL"] == {"answered": 4, "correct": 3} and m["activity"] == ["2026-12-01", "2026-12-02"] and m["reviews"]["k"]["times_right"] == 1
    assert study_sync.merge(m, m)["courses"]["c-swe"]["done"] == ["l1", "l2"]                       # idempotent
    kinds = {k for k, _, _ in study_sync.to_records(m)}
    assert kinds == set(study_sync.KINDS)


def test_rewrites_align_with_any_numbering():
    import study_plus as sp
    assert sp.align_rewrites([{"i": 1, "text": "a"}, {"i": 2, "text": "b"}], 2) == {0: "a", 1: "b"}      # 1-based
    assert sp.align_rewrites([{"i": 0, "text": "a"}, {"i": 1, "text": "b"}], 2) == {0: "a", 1: "b"}      # 0-based as asked
    assert sp.align_rewrites([{"text": "a"}, {"text": "b"}], 3) == {0: "a", 1: "b"}                      # no numbers: by order
    assert sp.align_rewrites([{"i": 7, "text": "a"}, {"i": 0, "text": "b"}], 2) == {0: "b"}              # out of range dropped
