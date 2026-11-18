"""Career milestone (desktop/web): personalised MCQ batches, validation, dedup, honest labels, progress, refresh contracts."""
import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
APP_JS = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
CSS = (ROOT / "frontend" / "styles.css").read_text(encoding="utf-8")
RESUME = (b"Jane Doe\nSKILLS\nPython, FastAPI, PyTorch, Docker\nPROJECTS\n- Real-time object detection with YOLO and OpenCV\n"
          b"- Speech transcription service using Whisper and FastAPI\nEXPERIENCE\nBuilt REST APIs with FastAPI serving 10k users.")


def item(q, opts, ans, why="Because.", quote=""):
    return {"q": q, "options": opts, "answer": ans, "explanation": why, "quote": quote}


def test_validation_keeps_good_questions_and_drops_bad_ones_individually():
    import career_mcq as c
    src = RESUME.decode()
    text = json.dumps({"questions": [
        item("Which framework did the candidate use to build REST APIs?", ["Django", "FastAPI", "Flask", "Spring"], 1,
             "The resume says FastAPI.", "Built REST APIs with FastAPI serving 10k users."),
        item("Which library did the candidate use for transcription?", ["Whisper", "whisper", "YOLO", "Pandas"], 0),   # repeated options
        item("What does YOLO detect in the candidate's project?", ["Objects", "Faces", "Text", "Audio"], 0, ""),       # no explanation
        item("Which framework did the candidate use for building REST APIs?", ["Django", "FastAPI", "Flask", "Spring"], 1),   # near-duplicate
        item("Which tool containerises the services?", ["Docker", "Excel", "Figma", "Jira"], 0, "Docker is a container tool.", "not in the resume"),
    ]})
    qs, dropped = c.validate(text, "document", "", "medium", src, [], "Jane.pdf")
    assert [q["q"] for q in qs] == ["Which framework did the candidate use to build REST APIs?", "Which tool containerises the services?"]
    assert dropped == {"options missing or not 4 different ones": 1, "no explanation": 1, "duplicate of an earlier question": 1}
    assert qs[0]["verification"] == c.QUOTED and qs[0]["quote"]                    # the quote really is in the resume
    assert qs[1]["verification"] == c.AI_ONLY and qs[1]["quote"] == ""            # quote not found: no claim
    assert "isn't independently verified" in c.QUOTED and "not independently verified" in c.AI_ONLY
    assert qs[0]["options"][qs[0]["answer"]] == "FastAPI"                         # options shuffled, the model's answer kept


def test_malformed_output_and_off_topic_questions():
    import career_mcq as c
    bad = '[{"q": "Which SQL join returns only matching rows?", "options": ["INNER JOIN","LEFT JOIN","FULL JOIN","CROSS JOIN"], "answer": 0, "explanation": The inner join.},' \
          '{"q": "What is the capital of France?", "options": ["Paris","Rome","Berlin","Madrid"], "answer": 0, "explanation": "Geography."}]'
    qs, dropped = c.validate(bad, "topic", "SQL joins", "easy", "", [], "SQL joins")
    assert [q["q"] for q in qs] == ["Which SQL join returns only matching rows?"]     # unquoted explanation still read (tolerant parser)
    assert dropped == {"not about the selected topic": 1}
    assert c.validate("Sorry, I can't.", "topic", "SQL", "easy", "", [], "SQL")[0] == []


def test_duplicate_detection_handles_rewording_but_not_different_questions():
    import career_mcq as c
    seen = [c.normalize("Which framework did the candidate use to build REST APIs?")]
    assert c.is_duplicate("Which framework did the candidate use for building REST APIs", seen)
    assert not c.is_duplicate("Which framework did the candidate use to build the frontend?", seen)


def test_batches_rotate_angles_and_resume_technologies():
    import career_mcq as c
    src = RESUME.decode()
    assert c.tech_terms(src) == {"python", "fastapi", "pytorch", "docker", "yolo", "opencv"}        # not "Jane", not "Doe"
    rounds = [c.facet("document", r, src) for r in range(10)]
    assert rounds[0][1] is True and rounds[0][2] is None                                       # own lines, quote checked
    general = [t for _, own, t in rounds if not own]
    assert len(set(general)) >= 4 and all(t in c.tech_terms(src) for t in general)            # moves through the technologies
    p = c.build_prompt("document", "", "junior", "medium", 3, src, [], 1)
    assert "about " + rounds[1][2] + " only" in p and "Ask general technical questions" in p and "<<<SOURCE" in p
    qs, dropped = c.validate(json.dumps([item("What does a Kubernetes pod contain?", ["a", "b", "c", "d"], 0)]),
                             "document", "", "easy", src, [], "x", {"docker"})
    assert qs == [] and dropped == {"doesn't name a technology from your document": 1}
    assert c.is_duplicate("Why might Asha choose PostgreSQL for the speech transcription service?",
                          [c.normalize("Why might Asha choose PostgreSQL for the speech transcription service job queue?")])  # measured live


def test_prompt_is_grounded_and_settings_are_checked():
    import asyncio
    import career_mcq as c
    p = c.build_prompt("document", "", "junior", "hard", 5, RESUME.decode(), ["What is Docker?"])
    assert p.startswith("Write 5 hard") and "<<<SOURCE" in p and "never invent experience" in p and "Do not repeat" in p
    t = c.build_prompt("topic", "SQL joins", "senior", "easy", 3, RESUME.decode(), [])
    assert "SOURCE" not in t and "Python" not in t                                 # a topic never carries the resume
    with pytest.raises(ValueError):
        asyncio.run(c.generate("topic", "", None, "junior", "easy", 3, None, []))
    with pytest.raises(ValueError):
        asyncio.run(c.generate("topic", "SQL", None, "junior", "easy", 7, None, []))


class FakeLLM:
    calls = []

    def __init__(self, model=None):
        self.model, self.format = model, None

    def model_copy(self, update):
        c = FakeLLM(self.model); c.format = update["format"]; return c

    async def ainvoke(self, msgs, **kw):
        FakeLLM.calls.append((self.model, self.format is not None, msgs[1].content))
        R = type("R", (), {}); r = R()
        n = len(FakeLLM.calls)
        r.content = json.dumps({"questions": [
            item(f"Which framework serves the candidate's REST APIs number {n}?", ["Django", "FastAPI", "Flask", "Spring"], 1, "FastAPI.", "Built REST APIs with FastAPI"),
            item(f"Which detector is used in the candidate's vision project {n}?", ["YOLO", "BERT", "GPT", "SVM"], 0, "YOLO."),
        ]})
        return r


@pytest.fixture
def app(kb, monkeypatch):
    import agent
    import knowledge
    import main
    import system_service as system
    FakeLLM.calls = []
    monkeypatch.setattr(agent, "get_llm", lambda *a, model=None, **k: FakeLLM(model))

    async def note(name): pass
    monkeypatch.setattr(system, "note_chat_model", note)

    async def resolve(name):
        if name != "qwen2.5:latest":
            raise ValueError("not installed")
        return name
    monkeypatch.setattr(system, "resolve_chat_model", resolve)
    knowledge.add_upload(RESUME, "jane-resume.md")
    with TestClient(main.app) as c:
        yield c


def test_batches_from_a_resume_continue_without_repeats_and_keep_progress(app):
    r = app.post("/career/mcq", json={"mode": "document", "document": "jane-resume.md", "count": 3, "model": "qwen2.5:latest"}).json()
    assert len(r["questions"]) == 2 and r["scope"] == "document:jane-resume.md"
    assert FakeLLM.calls[0][0] == "qwen2.5:latest" and FakeLLM.calls[0][1] is True           # the chosen model, schema output
    assert "Python, FastAPI" in FakeLLM.calls[0][2]                                           # the resume text is the source
    q = r["questions"][0]
    s = app.post("/career/mcq/answer", json={"key": q["key"], "topic": q["topic"], "scope": r["scope"], "correct": True}).json()
    assert s["overall"] == {"attempted": 1, "correct": 1, "wrong": 0, "accuracy": 100}
    r2 = app.post("/career/mcq", json={"mode": "document", "document": "jane-resume.md", "count": 3, "avoid": [x["q"] for x in r["questions"]]}).json()
    assert {x["q"] for x in r2["questions"]}.isdisjoint({x["q"] for x in r["questions"]})   # the next batch, no repeats
    assert "Do not repeat" in FakeLLM.calls[1][2]
    assert app.get("/career/mcq/stats", params={"scope": r["scope"]}).json()["scope"]["attempted"] == 1   # progress kept
    assert app.post("/career/mcq", json={"mode": "document", "document": "missing.pdf"}).status_code == 400
    assert app.post("/career/mcq", json={"mode": "topic", "topic": "SQL", "model": "llama3:70b"}).status_code == 400   # never swapped


def test_web_mcq_refresh_and_feedback_contracts():
    js = APP_JS
    assert "if (state.busy.mcq) return;" in js                                               # one request at a time
    assert "const keep = q.items.filter((_, i) => i in q.answers)" in js                     # refresh keeps answered questions
    assert "Your questions and progress are unchanged." in js                                # a failed refresh keeps everything
    assert "else if (a === 'pick') answerMCQ(+el.dataset.i);" in js                          # a tap answers: feedback immediately
    assert "❌ Incorrect: you chose" in js and "The answer is" in js and "verification || MCQ_AI" in js
    assert "if (!m || q.index in q.answers) return;" in js                                   # counted once
    assert "$('#mcqReview').checked" in js                                                   # optional review mode
    body = js[js.index("function visibleQuestions"):js.index("function renderQuestions")]
    assert "...state.questions.map((q) => ({ ...q, ai: true })), ...curated" in body         # generated added, curated kept


def test_web_news_refresh_and_pull_to_refresh_contracts():
    js = APP_JS
    news = js[js.index("let newsSeq = 0;"):js.index("async function newsSearch")]
    assert "if (refresh && news.refreshing && cat === news.cat) return news.refreshing;" in news   # no duplicate refresh
    assert "if (seq !== newsSeq) return;" in news                                                   # stale answers dropped
    assert "the previous stories stay" in news and "Updated just now" in news
    assert "newsAi(" not in news and "reader." not in news                                         # no briefing, Listen untouched
    ptr = js[js.index("const PTR_TARGETS"):js.index("/* ---------- 6. MCQ PRACTICE")]
    assert "location.reload" not in js and "window.location.reload" not in js                      # never a page reload
    assert "input, textarea, select, [contenteditable], .scroll-x, .chips" in ptr                  # not from fields or chip rows
    assert "ptrAtTop(e.target)" in ptr and "window.getSelection" in ptr and "busy" in ptr
    assert set(re.findall(r"^\s+(\w+): \(\) =>", ptr, re.M)) == {"news", "interview", "mcq"}
    assert "html, body, #content { overscroll-behavior-y: contain; }" in CSS                       # browser pull-to-reload off
