"""Study 7.2 (desktop): shared rules vs study_cases.json, the tracks file, storage, the course journey and the /study routes.
AI calls are faked; nothing reaches Ollama or the network."""
import json
from datetime import date
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

BACKEND = Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
CASES = json.loads((Path(__file__).parent / "data" / "study_cases.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- shared rules (the Kotlin/JS ports must match)
def test_cases_file_is_current():
    import make_study_cases  # noqa: F401  (tests/ is on sys.path via rootdir)
    assert make_study_cases.build() == CASES, "run python backend/tests/make_study_cases.py"


def test_study_core_matches_the_shared_cases():
    import study
    import study_core as sc
    tax = study.taxonomy()
    assert len(tax) == CASES["taxonomy_size"]
    for k, c in CASES["extract"].items():
        assert sc.extract_skills(c["jd"], tax) == c["expect"], k
    g = CASES["gaps"]
    assert sc.gaps(CASES["extract"][g["jd"]]["expect"], g["resume_skills"]) == g["expect"]
    p = CASES["plan"]
    today = date.fromisoformat(p["today"])
    assert sc.make_plan(p["gaps"], date.fromisoformat(p["interview"]), today, p["minutes"]) == p["expect"]
    assert len(sc.make_plan(p["gaps"], None, today, 60)) == p["no_date_days"] == 14
    assert p["past_date_days"] == 1
    for r in CASES["readiness"]:
        assert sc.skill_score(r["answered"], r["correct"], r["lessons_done"], r["lessons_total"]) == r["score"]
        assert sc.level(r["score"]) == r["level"]
    o = CASES["readiness_overall"]
    assert sc.readiness(o["skills"], o["mcq"], o["lessons"]) == o["expect"]
    for r in CASES["review"]:
        assert sc.next_review(r["times_right"], date.fromisoformat(r["answered_on"]), r["correct"], r["lapses"]) == r["expect"]
    a = CASES["adapt"]
    assert sc.adapt_gaps(a["gaps"], a["rows"]) == a["expect"]
    assert a["expect"][-1]["why"] == "already Ready: light refresh"
    for r in CASES["streak"]:
        assert sc.streak(r["days"], date.fromisoformat(r["today"])) == r["expect"]
    for c in CASES["schedule"]:
        assert sc.course_schedule(c["modules"], c["minutes"], date.fromisoformat(c["start"]), c["optional"], c["done"]) == c["expect"]
    for c in CASES["mastery"]:
        assert sc.module_mastery(c["module"], c["done"], c["stats"]) == c["expect"]
    for c in CASES["fact_guard"]:
        assert sc.fact_guard(c["source"], c["rewritten"], tax) == c["expect"]
    for c in CASES["mock_plan"]:
        assert sc.mock_plan(c["skills"], c["competencies"], c["count"]) == c["expect"]
    for c in CASES["feedback"]:
        assert sc.check_feedback(c["o"], c["answer"]) == c["expect"]
    for c in CASES["mock_summary"]:
        assert sc.mock_summary(c["rows"]) == c["expect"]
    for c in CASES["quality"]:
        assert sc.quality_verdict(c["probes"]) == c["expect"]
    for c in CASES["next_lesson"]:
        assert sc.next_lesson(c["modules"], c["done"], c["optional"]) == c["expect"]


def test_jd_extraction_sanity():
    ex = CASES["extract"]
    assert ex["ml_engineer"]["expect"]["required"][:2] == ["Machine Learning", "Python"]
    assert "Kubernetes" in ex["ml_engineer"]["expect"]["preferred"]               # under "Nice to have:"
    assert "Azure" in ex["cloud_engineer"]["expect"]["preferred"]                  # "Bonus:" line
    assert "Python" in ex["data_analyst"]["expect"]["preferred"]                   # "Python (pandas) is a plus." sentence
    assert "SQL" in ex["data_analyst"]["expect"]["required"]


def test_tracks_file_is_valid_and_matches_android():
    t = json.loads((BACKEND / "study_tracks.json").read_text(encoding="utf-8"))
    assert t["format"] == "abhify-study-tracks-1"
    assert {x["id"] for x in t["tracks"]} == {"aiml", "swe", "ds", "cloud", "behav"}
    tax = {s["name"] for s in json.loads((BACKEND / "skills_taxonomy.json").read_text(encoding="utf-8"))["skills"]}
    ids = set()
    for tr in t["tracks"]:
        assert len(tr["modules"]) >= 3
        for m in tr["modules"]:
            assert m["id"] not in ids and len(m["lessons"]) >= 2
            ids.add(m["id"])
            assert all(s in tax or s == "Behavioural" for s in m["skills"]), m["id"]
            for l in m["lessons"]:
                assert l["id"] not in ids and l["title"].strip()
                ids.add(l["id"])
    assert len(t["competencies"]) == 8
    android = ROOT / "android" / "app" / "src" / "main" / "assets"
    assert (android / "study_tracks.json").read_bytes() == (BACKEND / "study_tracks.json").read_bytes()
    assert (android / "skills_taxonomy.json").read_bytes() == (BACKEND / "skills_taxonomy.json").read_bytes()


def test_schedule_keeps_progress_and_puts_optional_modules_last():
    import study_core as sc
    mods = [{"id": "a", "lessons": [{"id": "a1"}, {"id": "a2"}]}, {"id": "b", "lessons": [{"id": "b1"}, {"id": "b2"}, {"id": "b3"}]}]
    r = sc.course_schedule(mods, 30, date(2026, 12, 1), ["a"], ["b1"])
    assert [d["lessons"] for d in r["days"]] == [["b2"], ["b3"], ["a1"], ["a2"]]     # done skipped, optional last
    assert r["weeks"][0]["modules_completed"] == ["b", "a"]
    long = sc.course_schedule([{"id": "x", "lessons": [{"id": f"x{i}"} for i in range(12)]}], 30, date(2026, 12, 1))
    assert long["days"][6]["kind"] == "revision" and long["days"][6]["lessons"] == []   # a revision day every 7th day
    assert sc.next_lesson(mods, ["b1"], ["a"]) == "b2" and sc.next_lesson(mods, ["a1", "a2", "b1", "b2", "b3"]) is None


def test_validators_reject_made_up_modules_and_incomplete_ai_output():
    import study
    mods = [{"id": "m1", "lessons": []}, {"id": "m2", "lessons": []}, {"id": "m3", "lessons": []}]
    ov = study.validate_overview({"summary": "Good.", "optional": ["m1", "m99"], "focus": {"m2": "why", "zz": "x"}, "objectives": ["a"]}, mods)
    assert ov["optional"] == ["m1"] and ov["focus"] == {"m2": "why"} and ov["completion"]
    with pytest.raises(study.StudyError):
        study.validate_overview({"objectives": []}, mods)
    with pytest.raises(study.StudyError):
        study.validate_coach({"scores": {"situation": 9, "task": 3, "action": 3, "result": 3}})
    assert study.validate_coach({"scores": {"situation": 4, "task": 3, "action": 5, "result": 2}, "missing": ["numbers"]})["scores"]["action"] == 5
    with pytest.raises(study.StudyError):
        study.validate_cards({"cards": [{"front": "Q?", "back": "A"}]})
    with pytest.raises(study.StudyError):
        study.validate_outline({"modules": [{"title": "x", "lessons": ["a", "b"]}]}, "Rust")
    o = study.validate_outline({"title": "Rust", "modules": [{"title": f"M{i}", "lessons": ["a", "b", "c"]} for i in range(10)]}, "Rust")
    assert len(o["modules"]) == 8 and o["modules"][0]["lessons"][0]["id"] == "m1-1"


# --------------------------------------------------------------------------- routes (fake AI, isolated data)
LESSON_MD = "\n".join(f"## {h}\n" + "Text about the topic. " * 8 for h in
                      ["In simple words", "Going deeper", "Advanced: production and architecture", "Real-world example", "Code example",
                       "Common mistakes", "Practice exercise", "Interview questions", "Follow-up questions"])


class FakeAI:
    prompts: list = []

    @classmethod
    async def invoke(cls, prompt, model, json_mode):
        cls.prompts.append((prompt, model, json_mode))
        if not json_mode:
            return LESSON_MD if "Write an accurate lesson" in prompt else "A kind tutor answer."
        if "course overview" in prompt or "syllabus" in prompt:
            return json.dumps({"summary": "This course prepares you.", "objectives": ["Explain models"], "prerequisites": ["Python"],
                               "optional": ["aiml-math", "not-a-module"], "focus": {"aiml-llm": "Core for GenAI roles"}, "completion": ["Finish"]})
        if "flashcards" in prompt:
            return json.dumps({"cards": [{"front": f"Question number {i}?", "back": f"Answer {i}."} for i in range(6)]})
        if "capstone" in prompt:
            return json.dumps({"title": "RAG assistant", "brief": "Build it.", "milestones": ["Data", "Index", "Serve", "Evaluate"], "stretch": ["UI"]})
        if "STAR" in prompt:
            return json.dumps({"scores": {"situation": 4, "task": 3, "action": 4, "result": 2}, "missing": ["A number in the result"],
                               "tips": ["Say what you did"], "improved": "I led the migration."})
        if "course outline" in prompt:
            return json.dumps({"title": "Rust for interviews", "modules": [{"title": f"Module {i}", "lessons": ["One", "Two", "Three"]} for i in range(4)]})
        return "{}"


@pytest.fixture
def app(data_root, monkeypatch):
    import career_mcq
    import main
    import study
    import system_service as system
    FakeAI.prompts = []
    monkeypatch.setattr(study, "_invoke", FakeAI.invoke)

    async def resolve(name):
        if name != "qwen2.5:latest":
            raise ValueError("not installed")
        return name
    monkeypatch.setattr(system, "resolve_chat_model", resolve)
    calls = []

    async def gen(mode, topic, document, level, difficulty, count, model, avoid):
        calls.append((mode, topic, level, count, list(avoid)))
        n = len(calls)
        return {"questions": [{"key": f"k{n}-{i}", "q": f"{topic} question {n}.{i}?", "options": ["a", "b", "c", "d"], "answer": 0,
                               "explanation": "Because."} for i in range(count)], "scope": f"topic:{topic}"}
    monkeypatch.setattr(career_mcq, "generate", gen)
    with TestClient(main.app) as c:
        c.mcq_calls = calls
        yield c


def test_course_journey_end_to_end(app, data_root):
    opts = app.get("/learn/prefs/options").json()
    assert "LLM / GenAI Engineer" in opts["targets"] and set(opts["levels"]) == {"beginner", "intermediate", "advanced"}
    c = app.post("/learn/courses", json={"track": "aiml"}).json()
    assert c["id"] == "c-aiml" and app.post("/learn/courses", json={"track": "aiml"}).json()["id"] == "c-aiml"   # one per track
    assert app.post("/learn/courses", json={"track": "nope"}).status_code == 400
    cid = c["id"]
    assert app.put(f"/learn/courses/{cid}/prefs", json={"target": "LLM / GenAI Engineer", "level": "advanced", "minutes": 60}).status_code == 200
    ov = app.post(f"/learn/courses/{cid}/overview", json={}).json()
    assert ov["optional"] == ["aiml-math"]                                     # made-up module ids are dropped
    dash = app.get(f"/learn/courses/{cid}/dashboard").json()
    assert dash["progress"] == 0 and dash["assessment"]["percent"] is None and dash["seconds"] == 0     # nothing invented
    assert dash["next_lesson"]["id"].startswith("aiml-ml")                     # optional basics go last
    lid = dash["next_lesson"]["id"]

    les = app.post("/learn/lesson", json={"course_id": cid, "lesson_id": lid}).json()
    assert les["cached"] is False and "## In simple words" in les["markdown"]
    prompt = next(p for p, _, j in FakeAI.prompts if "Write an accurate lesson" in p)
    assert "LLM / GenAI Engineer" in prompt and "production trade-offs" in prompt   # follows the preferences
    assert app.post("/learn/lesson", json={"course_id": cid, "lesson_id": lid}).json()["cached"] is True
    assert app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "zzz"}).status_code == 400

    app.post("/learn/lesson/done", json={"course_id": cid, "lesson_id": lid})
    app.post(f"/learn/courses/{cid}/exercise", json={"course_id": cid, "lesson_id": lid})
    app.post(f"/learn/courses/{cid}/time", json={"seconds": 300})
    assert app.post(f"/learn/courses/{cid}/time", json={"seconds": 5000}).status_code == 422     # capped per report

    b1 = app.post("/learn/mcq", json={"course_id": cid, "round": 0, "level": "auto"}).json()
    assert len(b1["questions"]) == 5 and b1["module_id"] != "aiml-math"
    assert app.mcq_calls[0][2] == "senior"                                     # level follows the course preferences
    b2 = app.post("/learn/mcq", json={"course_id": cid, "round": 1, "avoid": [q["q"] for q in b1["questions"]]}).json()
    assert b2["module_id"] != b1["module_id"] and app.mcq_calls[1][4]           # infinite scroll: next module, no repeats
    q = b1["questions"][0]
    for ok in (False, False, False, True):
        app.post("/learn/mcq/answer", json={"skill": q["skill"], "question": q, "correct": ok, "course_id": cid, "module_id": q["module_id"]})
    dash = app.get(f"/learn/courses/{cid}/dashboard").json()
    assert dash["lessons_done"] == 1 and dash["exercises_done"] == 1 and dash["seconds"] == 300
    assert dash["assessment"] == {"answered": 4, "correct": 1, "percent": 25}
    assert dash["weak"][0]["id"] == q["module_id"]                             # weak-topic detection
    assert dash["revision"]["wrong_this_week"] == 3 and dash["streak"] == 1
    assert app.post("/learn/mcq", json={"course_id": cid, "round": 0, "weak": True}).json()["module_id"] == q["module_id"]

    cards = app.post(f"/learn/courses/{cid}/cards", json={"course_id": cid, "lesson_id": lid}).json()["cards"]
    assert len(cards) == 6
    due = app.get("/learn/cards", params={"course_id": cid}).json()["cards"]
    assert len(due) == 6
    g = app.post(f"/learn/cards/{due[0]['id']}", json={"remembered": True}).json()
    assert g["times_right"] == 1 and g["due"] > date.today().isoformat()
    assert len(app.get("/learn/cards", params={"course_id": cid}).json()["cards"]) == 5
    assert len(app.get("/learn/cards", params={"course_id": cid, "weekly": True}).json()["cards"]) == 6
    assert app.post(f"/learn/courses/{cid}/cards", json={"course_id": cid, "lesson_id": "aiml-ops-1"}).status_code == 400   # open it first

    cap = app.post(f"/learn/courses/{cid}/capstone", json={}).json()
    assert len(cap["milestones"]) == 4
    app.post(f"/learn/courses/{cid}/capstone/step", json={"index": 1})
    assert app.get(f"/learn/courses/{cid}/dashboard").json()["capstone"] == {"title": "RAG assistant", "done": 1, "total": 4}
    t = app.post(f"/learn/courses/{cid}/tutor", json={"lesson_id": lid, "question": "Why does this matter?"}).json()
    assert t["answer"]
    tutor_prompt = FakeAI.prompts[-1][0]
    assert "Why does this matter?" in tutor_prompt and "Text about the topic" in tutor_prompt   # lesson context only

    # changing preferences later keeps all progress
    app.put(f"/learn/courses/{cid}/prefs", json={"target": "My own: robotics", "level": "Somewhere in between", "minutes": 30})
    dash = app.get(f"/learn/courses/{cid}/dashboard").json()
    assert dash["lessons_done"] == 1 and dash["assessment"]["answered"] == 4 and dash["seconds"] == 300 and dash["capstone"]["done"] == 1
    assert dash["prefs"]["target"] == "My own: robotics" and dash["prefs"]["level_key"] == "custom" and dash["overview"] is None
    assert [h["kind"] for h in dash["history"]][:1] == ["prefs"]

    saved = json.loads((data_root / "data" / "study.json").read_text(encoding="utf-8"))
    assert saved["courses"][cid]["done"] == [lid]
    import local_file_service as lfs
    assert "data/study.json" in lfs.BACKUP_INCLUDE
    assert app.delete(f"/learn/courses/{cid}").status_code == 200
    assert not [k for k in json.loads((data_root / "data" / "study.json").read_text(encoding="utf-8"))["cards"]]


def test_custom_course_plan_stories_and_overview(app):
    c = app.post("/learn/courses", json={"topic": "Rust for interviews"}).json()
    assert c["id"].startswith("c-") and len(c["modules"]) == 4 and c["modules"][0]["lessons"][0]["id"] == "m1-1"
    assert app.post("/learn/courses", json={"topic": "x"}).status_code == 400
    assert app.post("/learn/courses", json={"topic": "Go", "model": "llama3:70b"}).status_code == 400   # never swapped

    jd = CASES["extract"]["ml_engineer"]["jd"]
    p = app.post("/learn/plan", json={"jd": jd, "resume_text": "Python, SQL, Docker and Git", "minutes": 60}).json()
    assert p["gaps"][0] == {"skill": "Machine Learning", "why": "required, not on your resume"} and len(p["days"]) == 14
    assert app.post("/learn/plan", json={"jd": "short"}).status_code == 400
    assert app.post("/learn/plan", json={"jd": jd, "interview_date": "31-12-2026"}).status_code == 400
    assert app.post("/learn/plan/item", json={"day": 1, "index": 0}).json()["done"] == ["1:0"]
    assert app.post("/learn/plan/item", json={"day": 99, "index": 0}).status_code == 400

    s = app.post("/learn/stories", json={"title": "Led a migration", "competencies": ["leadership", "made-up"],
                                         "situation": "Our old system was slow.", "task": "I had to move it.",
                                         "action": "I planned and led the move.", "result": "It became faster."}).json()
    assert s["competencies"] == ["leadership"]
    fb = app.post(f"/learn/stories/{s['id']}/coach", json={}).json()
    assert fb["scores"]["result"] == 2 and "Never invent facts" in FakeAI.prompts[-1][0]
    st = app.get("/learn/stories").json()
    assert st["stories"][0]["coach"]["scores"]["action"] == 4 and len(st["competencies"]) == 8
    app.post("/learn/stories", json={"id": s["id"], "title": "Led a migration (edited)"})       # edit keeps coaching
    assert app.get("/learn/stories").json()["stories"][0]["coach"]

    state = app.get("/learn/state").json()
    assert state["plan"]["gaps"] and state["today"][0]["done"] is True and state["stories"] == 1 and state["streak"] == 1
    assert state["readiness"]["overall"] == 0 and state["readiness"]["level"] == "Not ready"   # honest: nothing practised yet
    assert app.delete("/learn/plan").json() == {"plan": None}


def test_ai_unavailable_is_a_clear_message(app, monkeypatch):
    import study

    async def down(prompt, model, json_mode):
        raise study.StudyError("The AI model isn't available right now (ConnectError). Check AI models & setup.")
    monkeypatch.setattr(study, "_invoke", down)
    cid = app.post("/learn/courses", json={"track": "swe"}).json()["id"]
    r = app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "swe-dsa-1"})
    assert r.status_code == 503 and "isn't available" in r.json()["detail"]


# --------------------------------------------------------------------------- Web (no backend): the JS ports in Node
def _node(script, *args):
    import shutil
    import subprocess
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js is not installed")
    out = subprocess.run([node, str(Path(__file__).parent / "js" / script), *map(str, args)], capture_output=True, text=True, encoding="utf-8", timeout=120)
    res = json.loads(out.stdout.strip().splitlines()[-1])
    assert not res["failed"], res["failed"]
    return res["passed"]


def test_js_study_core_matches_the_python_reference():
    assert _node("study_harness.js", Path(__file__).parent / "data" / "study_cases.json", BACKEND / "skills_taxonomy.json", ROOT / "frontend") >= 40


def test_js_study_local_routes_match_the_desktop_behaviour():
    assert _node("study_local_harness.js", ROOT / "frontend") >= 29


def test_web_copies_of_the_study_data_are_identical():
    assert (ROOT / "frontend" / "study-tracks.json").read_bytes() == (BACKEND / "study_tracks.json").read_bytes()
    assert (ROOT / "frontend" / "skills-taxonomy.json").read_bytes() == (BACKEND / "skills_taxonomy.json").read_bytes()
    sw = (ROOT / "frontend" / "sw.js").read_text(encoding="utf-8")
    for f in ("study-ui.js", "study-core.js", "study-local.js", "study-tracks.json", "skills-taxonomy.json"):
        assert f"'./{f}'" in sw
    assert "'/learn'" in sw                                                      # API answers are never cached
    html = (ROOT / "frontend" / "index.html").read_text(encoding="utf-8")
    assert html.index('src="app.js"') < html.index('src="study-ui.js"')
    ui = (ROOT / "frontend" / "study-ui.js").read_text(encoding="utf-8")
    assert "new IntersectionObserver" in ui and "morePractice" in ui                # unlimited MCQs as you scroll
    assert "if (!d.ready && !(await setupDevice()))" in ui                         # the on-device model downloads only after the user confirms


# --------------------------------------------------------------------------- data integrity (audit 7.2.0-dev.1)
def test_corrupt_file_is_kept_and_last_good_copy_is_used(data_root):
    import study
    study.create_course(track="aiml")
    study.set_done("c-aiml", "aiml-ml-1", True)
    study.set_done("c-aiml", "aiml-ml-2", True)                        # the .bak now holds the state with ml-1 done
    p = data_root / "data" / "study.json"
    p.write_text('{"courses": {"c-aiml": ', encoding="utf-8")         # an interrupted / truncated write
    d = study.load()
    assert d["courses"]["c-aiml"]["done"] == ["aiml-ml-1"]              # recovered from study.json.bak
    corrupt = list((data_root / "data").glob("study.corrupt-*.json"))
    assert len(corrupt) == 1 and corrupt[0].read_text(encoding="utf-8").startswith('{"courses"')   # never deleted
    assert json.loads(p.read_text(encoding="utf-8"))["courses"]["c-aiml"]["done"] == ["aiml-ml-1"]


def test_corrupt_file_without_backup_starts_empty_but_keeps_the_file(data_root):
    import study
    (data_root / "data").mkdir(parents=True, exist_ok=True)
    (data_root / "data" / "study.json").write_bytes(b"\x00\xff not json")
    assert study.load()["courses"] == {}
    assert len(list((data_root / "data").glob("study.corrupt-*.json"))) == 1


def test_leftover_temp_file_and_schema_migration_and_newer_schema(data_root):
    import study
    (data_root / "data").mkdir(parents=True, exist_ok=True)
    p = data_root / "data" / "study.json"
    p.write_text(json.dumps({"courses": {"c-x": {"id": "c-x", "track": "swe", "title": "SWE"}}, "mcq": {}}), encoding="utf-8")   # schema 0
    (data_root / "data" / "study.json.123.456.tmp").write_text("{partial", encoding="utf-8")       # a crash mid-write
    d = study.load()
    assert d["schema"] == 1 and d["courses"]["c-x"]["done"] == [] and d["plan_history"] == []
    p.write_text(json.dumps({"schema": 99, "courses": {}}), encoding="utf-8")
    with pytest.raises(study.StudyError, match="newer Abhify"):
        study.load()
    assert json.loads(p.read_text(encoding="utf-8"))["schema"] == 99                               # untouched


def test_concurrent_answers_are_all_counted(data_root):
    import threading
    import study
    study.create_course(track="swe")
    ts = [threading.Thread(target=study.answer, args=("Python", {"key": f"k{i}", "q": f"Q{i}?"}, i % 2 == 0),
                           kwargs={"course_id": "c-swe", "module_id": "swe-dsa"}) for i in range(40)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    d = study.load()
    assert d["mcq"]["Python"] == {"answered": 40, "correct": 20}
    assert d["courses"]["c-swe"]["scores"]["swe-dsa"] == {"answered": 40, "correct": 20}


def _set_overview(study, cid, optional):
    with study._lock:
        d = study.load()
        d["courses"][cid]["overview"] = {"summary": "s", "objectives": [], "prerequisites": [], "completion": [], "optional": optional, "focus": {}}
        study.save(d)


def test_changing_goal_keeps_progress_and_reports_what_changed(data_root):
    """Concrete scenario: finish modules, answer, do exercises, change the target, rebuild the plan."""
    import study
    cid = study.create_course(track="aiml")["id"]
    study.set_prefs(cid, "Senior AI/ML Engineer", "beginner", 60)
    _set_overview(study, cid, [])
    for lid in ["aiml-math-1", "aiml-math-2", "aiml-math-3", "aiml-ml-1", "aiml-ml-2"]:
        study.set_done(cid, lid, True)
    study.set_exercise(cid, "aiml-ml-1", True)
    for i, ok in enumerate((True, True, False)):
        study.answer("Machine Learning", {"key": f"x{i}", "q": f"q{i}"}, ok, course_id=cid, module_id="aiml-ml")
    study.set_prefs(cid, "LLM / GenAI Engineer", "advanced", 90)
    _set_overview(study, cid, ["aiml-math"])                            # the new overview marks maths optional for this goal
    dash = study.dashboard(cid)
    assert dash["lessons_done"] == 5 and dash["exercises_done"] == 1 and dash["assessment"]["answered"] == 3
    assert dash["plan_change"]["previous"]["target"] == "Senior AI/ML Engineer" and dash["plan_change"]["kept"] == 5
    assert dash["plan_change"]["now_optional"] == ["Maths for ML"]
    scheduled = [l for day in dash["schedule"]["days"] for l in day["lessons"]]
    assert not {"aiml-math-1", "aiml-math-2", "aiml-math-3", "aiml-ml-1", "aiml-ml-2"} & set(scheduled)   # never assigned again
    math_left = [l for l in scheduled if l.startswith("aiml-math")]
    assert math_left and scheduled[-len(math_left):] == math_left                                 # optional work goes last
    c = study.load()["courses"][cid]
    assert c["prefs_history"][0]["prefs"]["target"] == "Senior AI/ML Engineer"                    # the old version is kept
    assert [h["kind"] for h in c["history"]].count("prefs") == 2


def test_plan_versions_are_kept_and_ready_skills_go_last(data_root):
    import study
    jd = CASES["extract"]["ml_engineer"]["jd"]
    p1 = study.make_plan(jd, "Python, SQL")
    assert p1["gaps"][0]["skill"] == "Machine Learning"
    for i in range(10):
        study.answer("Machine Learning", {"key": f"m{i}", "q": f"q{i}"}, True)
    study.create_course(track="aiml")
    for lid in ["aiml-ml-1", "aiml-ml-2", "aiml-ml-3", "aiml-ml-4"]:
        try:
            study.set_done("c-aiml", lid, True)
        except study.StudyError:
            pass
    ml = next(r for r in study.overview()["readiness"]["skills"] if r["skill"] == "Machine Learning")
    p2 = study.make_plan(jd, "Python, SQL")
    if ml["score"] >= 70:
        assert p2["gaps"][-1] == {"skill": "Machine Learning", "why": "already Ready: light refresh"}
    else:
        assert p2["gaps"][0]["skill"] == "Machine Learning"
    st = study.overview()
    assert len(st["plan_history"]) == 1 and st["plan_history"][0]["archived_at"] and st["plan"]["created"] >= p1["created"]


def test_repeatedly_missed_questions_come_back_sooner(data_root):
    import study
    q = {"key": "rep1", "q": "Which?"}
    study.answer("SQL", q, False, today=date(2026, 12, 1))
    assert study.load()["reviews"]["rep1"]["due"] == "2026-12-02"
    study.answer("SQL", q, False, today=date(2026, 12, 2))
    r = study.load()["reviews"]["rep1"]
    assert r["due"] == "2026-12-02" and r["lapses"] == 2


def test_star_coaching_never_adds_numbers():
    import study
    fb = {"scores": {}, "missing": [], "tips": [], "improved": "I cut the runtime by 90% for 3 teams."}
    out = study.guard_facts(fb, "The report took 3 hours. I fixed it.")
    assert out["improved"] == "" and "added numbers" in out["tips"][-1]
    ok = study.guard_facts({**fb, "improved": "I fixed a report that took 3 hours."}, "The report took 3 hours. I fixed it.")
    assert ok["improved"]


def test_export_import_round_trip_keeps_a_copy(app, data_root):
    app.post("/learn/courses", json={"track": "cloud"})
    exp = app.get("/learn/export").json()
    assert exp["format"] == "abhify-study-export-1" and "c-cloud" in exp["data"]["courses"]
    app.delete("/learn/courses/c-cloud")
    assert app.post("/learn/import", json={"format": "nope"}).status_code == 400
    r = app.post("/learn/import", json=exp).json()
    assert r["courses"] == 1 and app.get("/learn/state").json()["courses"][0]["id"] == "c-cloud"
    assert list((data_root / "data").glob("study.before-import-*.json"))


def test_study_never_uses_an_online_model(app):
    import providers
    cid = app.post("/learn/courses", json={"track": "swe"}).json()["id"]
    cloud = providers.PREFIX + "gemini:gemini-2.0-flash"
    assert providers.is_cloud(cloud)
    r = app.post("/learn/lesson", json={"course_id": cid, "lesson_id": "swe-dsa-1", "model": cloud})
    assert r.status_code == 200 and FakeAI.prompts[-1][1] is None       # the local default, not the cloud model


def test_lesson_headings_from_small_models_are_normalised_and_checked():
    import study
    raw = ("**In simple words**\n-----------------\n" + "Plain words. " * 40 + "\n\n**Going deeper**\nMore.\n\n"
           "1. Advanced: production and architecture\nScale.\n\nCommon mistakes\n===\nOops.")
    n = study.normalize_lesson(raw)
    assert n.startswith("## In simple words") and "## Going deeper" in n and "## Advanced: production" in n and "## Common mistakes" in n
    assert study.lesson_ok(n)
    assert not study.lesson_ok("## In simple words\nshort")                                   # too short / too few sections
    assert not study.lesson_ok("Sure! Here is a lesson. " * 40)                               # no sections at all


def test_overview_lists_written_as_one_string_are_split():
    import study
    mods = [{"id": "m1", "lessons": []}, {"id": "m2", "lessons": []}, {"id": "m3", "lessons": []}]
    ov = study.validate_overview({"summary": "s", "objectives": ["Learn A; Learn B; Learn C", "prerequisites"], "prerequisites": "Python"}, mods)
    assert ov["objectives"] == ["Learn A", "Learn B", "Learn C"] and ov["prerequisites"] == ["Python"]


def test_backup_and_restore_include_study_data(migrated):
    import database as db
    import local_file_service as lfs
    import study
    study.create_course(track="ds")
    study.set_done("c-ds", "ds-stats-1", True)
    zip_path = lfs.create_backup("test")
    import zipfile
    assert "data/study.json" in zipfile.ZipFile(zip_path).namelist()
    study.delete_course("c-ds")
    assert study.load()["courses"] == {}
    lfs.stage_restore(zip_path, db.SCHEMA_VERSION)
    assert lfs.apply_pending_restore()
    assert study.load()["courses"]["c-ds"]["done"] == ["ds-stats-1"]


def test_web_build_without_sample_data_never_reads_window_mock_unguarded():
    """The standalone Web package leaves out mock-data.js: News, Question Bank and MCQs must not crash without it."""
    import re
    js = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
    assert not re.findall(r"window\.MOCK\.", js), "use window.MOCK?. (the Web build has no sample data)"
    assert "No news saved on this device yet" in js


def test_course_streak_counts_any_activity_not_only_focused_time(data_root):
    import study
    study.create_course(track="swe")
    assert study.dashboard("c-swe")["streak"] == 0
    study.answer("Python", {"key": "s1", "q": "q"}, True, course_id="c-swe", module_id="swe-dsa")   # a right answer only
    d = study.dashboard("c-swe")
    assert d["streak"] == 1 and d["seconds"] == 0                                               # no time invented
