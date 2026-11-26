"""Study 7.2.0-dev.2 on Windows: mock interviews, resume tailoring, the Personal AI Brain opt-in per course and the model
quality check. Storage is the same data/study.json (study.load/save); AI is the local model only (study._invoke).
Deterministic rules (fact guard, interview order, feedback checks, summaries, verdicts) are in study_core.py and shared
with Web and Android through backend/tests/data/study_cases.json."""
from __future__ import annotations

import re
import time
import uuid
from datetime import date
from typing import Any

import study
import study_core as sc
from study import StudyError

MAX_MOCKS = 20


# --------------------------------------------------------------------------- Personal AI Brain opt-in (per course, off by default)
def brain_sources() -> dict[str, Any]:
    """What the learner may choose to share with a course: goal memories, other memories and projects (titles only here)."""
    import memory_service as mem
    import projects
    import database as db
    mems = mem.list_memories()
    return {"memory_enabled": bool(db.get_prefs().get("memory_enabled", True)),          # 7.2.1: shown on the course card
            "goals": [{"id": m["id"], "text": m["content"][:200]} for m in mems if m.get("category") == "goal"],
            "memories": [{"id": m["id"], "text": m["content"][:200], "category": m.get("category")} for m in mems if m.get("category") != "goal"][:60],
            "projects": [{"id": p["id"], "name": p["name"]} for p in projects.list_projects(include_archived=False)]}


def set_brain(cid: str, enabled: bool, memory_ids: list[int], project_ids: list[str]) -> dict:
    with study._lock:
        d = study.load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        c["brain"] = {"enabled": bool(enabled), "memories": [int(x) for x in memory_ids][:20], "projects": [str(x) for x in project_ids][:10]}
        study._history(d, cid, "brain", "Personal context " + ("on" if enabled else "off"))
        study.save(d)
        return c["brain"]


def brain_context(c: dict) -> tuple[str, list[str]]:
    """The learner-approved context for this course's prompts, and labels of what was used ("" when off). Only the
    items ticked for this course; never chat history. Items that were deleted since are skipped."""
    b = c.get("brain") or {}
    if not b.get("enabled"):
        return "", []
    import database as db
    if not db.get_prefs().get("memory_enabled", True):          # 7.2.1: the global memory switch wins (choices are kept)
        return "", []
    import memory_service as mem
    import projects
    lines, used = [], []
    for mid in b.get("memories", []):
        m = mem.get_memory(mid)
        if m:
            lines.append(f"- {'Goal' if m.get('category') == 'goal' else 'About me'}: {m['content'][:200]}")
            used.append(("Goal: " if m.get("category") == "goal" else "Note: ") + m["content"][:60])
    for pid in b.get("projects", []):
        p = projects.get(pid)
        if p:
            lines.append(f"- Project {p['name']}: {(p.get('instructions') or '')[:200]}")
            used.append("Project: " + p["name"])
    if not lines:
        return "", []
    text = "\n".join(lines)[:1200]
    return ("Learner context, shared by the learner for this course only (use it to choose examples; do not repeat it back):\n" + text + "\n\n"), used


# --------------------------------------------------------------------------- model quality check
QUALITY_PROBES = {
    "json_mcq": ('Write 1 multiple-choice question about Python lists. Answer ONLY with JSON: {"questions": [{"q": question, "options": [4 strings], '
                 '"answer": index 0-3, "explanation": why}]}.'),
    "lesson_sections": "Write a very short lesson on HTTP caching with exactly these Markdown headings: ## In simple words, ## Going deeper, ## Common mistakes, ## Interview questions.",
    "fact": "Answer with only the number: what is 17 multiplied by 23?",
}


def _probe_ok(name: str, text: str) -> bool:
    if name == "json_mcq":
        import json
        try:
            o = json.loads(text)
        except ValueError:
            m = re.search(r"\{.*\}", text or "", re.S)
            try:
                o = json.loads(m.group(0)) if m else None
            except ValueError:
                o = None
        q = (o or {}).get("questions", [None])[0] if isinstance(o, dict) and isinstance(o.get("questions"), list) and o["questions"] else None
        return bool(isinstance(q, dict) and len(q.get("options") or []) == 4 and isinstance(q.get("answer"), int) and 0 <= q["answer"] <= 3 and str(q.get("q", "")).strip())
    if name == "lesson_sections":
        heads = " ".join(h.lower() for h in re.findall(r"^#{1,4}\s+(.+)$", study.normalize_lesson(text), re.M))
        return sum(s in heads for s in ("in simple words", "going deeper", "common mistakes", "interview questions")) >= 3
    return "391" in re.findall(r"\d+", text or "")


async def quality_check(model: str | None) -> dict:
    """Three fixed probes on the model Study will use. The result is cached per model; a weak model is never blocked."""
    import system_service as system
    name = model or system.active_models()["llm"]
    probes, seconds = {}, {}
    for k, prompt in QUALITY_PROBES.items():
        t = time.monotonic()
        try:
            out = await study._invoke(prompt, model, k == "json_mcq")
        except StudyError:
            raise
        probes[k] = _probe_ok(k, out)
        seconds[k] = round(time.monotonic() - t, 1)
    verdict = sc.quality_verdict(probes)
    r = {"model": name, "verdict": verdict, "probes": probes, "seconds": seconds, "at": int(time.time() * 1000),
         "advice": {"good": "This model handles Study well.",
                    "weak": "This model sometimes misses the format Study needs. Lessons and questions may need a retry; a larger model gives better results.",
                    "poor": "This model struggles with Study's lessons and questions. Choose a larger model in AI models & setup for better results."}[verdict]}
    with study._lock:
        d = study.load()
        d.setdefault("quality", {})[name] = r
        study.save(d)
    return r


def quality_status(model: str | None) -> dict | None:
    import system_service as system
    return study.load().get("quality", {}).get(model or system.active_models()["llm"])


# --------------------------------------------------------------------------- resume tailoring (nothing stored, nothing online)
BULLET = re.compile(r"^\s*(?:[-*•●▪◦]|\d+[.)])\s+(.+)$")


def resume_bullets(text: str) -> list[str]:
    """Bullet lines of a resume; when there are none, sentences of the longer lines."""
    lines = [l.rstrip() for l in (text or "").splitlines()]
    bullets = [m.group(1).strip() for l in lines if (m := BULLET.match(l))]
    if not bullets:
        bullets = [s.strip() for l in lines if len(l.strip()) > 40 for s in re.split(r"(?<=[.;])\s+", l.strip()) if len(s.strip()) > 25]
    return [b[:400] for b in bullets if len(b) >= 15][:60]


def rank_bullets(bullets: list[str], jd_skills: list[str], taxonomy: list[dict], n: int = 8) -> list[int]:
    """The bullets that matter most for the job: most job skills named, then original order."""
    scored = [(-len(set(sc.find_skills(b, taxonomy)) & set(jd_skills)), i) for i, b in enumerate(bullets)]
    return [i for _, i in sorted(scored)[:n]]


def align_rewrites(items: list, n: int) -> dict[int, str]:
    """Rewrites by bullet position. Models number lists their own way: 0-based as asked, 1-based, or not at all."""
    rows = [x for x in items if isinstance(x, dict) and str(x.get("text", "")).strip()]
    idx = [x.get("i") for x in rows]
    if rows and all(isinstance(i, int) and not isinstance(i, bool) for i in idx):
        if min(idx) >= 1 and max(idx) <= n and 0 not in idx:
            idx = [i - 1 for i in idx]                                 # 1-based
    else:
        idx = list(range(len(rows)))                                   # no usable numbers: by order
    got: dict[int, str] = {}
    for i, x in zip(idx, rows):
        if isinstance(i, int) and 0 <= i < n and i not in got:
            got[i] = str(x["text"]).strip()[:400]
    return got


async def tailor(resume: str, jd: str, model: str | None = None) -> dict:
    if len((jd or "").strip()) < 30:
        raise StudyError("Paste the job description (at least a few lines).")
    bullets = resume_bullets(resume)
    if not bullets:
        raise StudyError("No resume bullet points were found. Paste your resume text (each achievement on its own line).")
    tax = study.taxonomy()
    jd_sk = sc.extract_skills(jd, tax)
    jd_all = jd_sk["required"] + jd_sk["preferred"]
    pick = rank_bullets(bullets, jd_all, tax)
    listing = "\n".join(f"{k}. {bullets[i]}" for k, i in enumerate(pick))
    prompt = ("Rewrite these resume bullet points so they speak to the job below. Rules: keep every fact exactly as given; never add a number, "
              "employer, tool or technology that is not in the bullet or the resume; start with a strong verb; one line each. "
              'Reply with JSON only: {"bullets": [{"i": number, "text": "rewritten bullet"}]}.\n\n'
              f"Job skills: {', '.join(jd_all) or 'see the job text'}\nJob description:\n{jd[:3000]}\n\nBullets:\n{listing}")
    o = await study._json(prompt, model)
    got = align_rewrites((o.get("bullets") if isinstance(o, dict) else []) or [], len(pick))
    out = []
    for k, i in enumerate(pick):
        new = got.get(k)
        g = sc.fact_guard(resume, new or "", tax) if new else {"ok": False, "new_numbers": [], "new_skills": []}
        out.append({"original": bullets[i], "rewritten": new if new and g["ok"] else None,
                    "rejected": None if (new and g["ok"]) else ("The AI didn't rewrite this one." if not new else
                                                                "Left out: it added " + ", ".join(g["new_numbers"] + g["new_skills"]) + ", which isn't in your resume."),
                    "skills": [s for s in sc.find_skills(new or bullets[i], tax) if s in jd_all]})
    covered = sorted({s for b in bullets for s in sc.find_skills(b, tax) if s in jd_all})
    return {"bullets": out, "job_skills": jd_all, "covered": covered, "missing": [s for s in jd_all if s not in covered],
            "note": "Only facts already in your resume are allowed; nothing was saved or sent online."}


# --------------------------------------------------------------------------- mock interview
def _mock_skills(d: dict) -> list[str]:
    if d.get("plan"):
        return [g["skill"] for g in d["plan"]["gaps"]][:6]
    weak = sorted(d["mcq"].items(), key=lambda kv: kv[1]["correct"] / max(1, kv[1]["answered"]))
    return [k for k, _ in weak][:6]


async def mock_start(count: int = 6, skills: list[str] | None = None, model: str | None = None) -> dict:
    d = study.load()
    skills = [s for s in (skills or _mock_skills(d)) if s][:8]
    comps = study.tracks()["competencies"]
    plan = sc.mock_plan(skills, comps, count)
    tech = [q["skill"] for q in plan if q["kind"] == "technical"]
    written = {}
    if tech:
        level = "a mid-level"
        prompt = (f"Write one realistic interview question for {level} candidate for each of these skills: {', '.join(tech)}. "
                  "Each question should need a 1-2 minute spoken answer. Reply with JSON only: "
                  '{"questions": [{"skill": "skill name", "question": "..."}]}.')
        o = await study._json(prompt, model)
        for x in (o.get("questions") if isinstance(o, dict) else []) or []:
            if isinstance(x, dict) and str(x.get("question", "")).strip() and x.get("skill") in tech:
                written.setdefault(x["skill"], str(x["question"]).strip()[:300])
    qs = []
    for i, q in enumerate(plan):
        text = q.get("question") if q["kind"] == "behavioural" else written.get(q["skill"]) or f"Explain {q['skill']} and describe how you have used it in a real project."
        qs.append({"i": i, **q, "question": text, "answer": None, "seconds": None, "feedback": None})
    s = {"id": "m-" + uuid.uuid4().hex[:10], "created": int(time.time() * 1000), "source": "plan" if d.get("plan") else "practice",
         "job": (d.get("plan") or {}).get("jd_title"), "questions": qs, "finished": False, "summary": None}
    with study._lock:
        d = study.load()
        d["mocks"] = (d.get("mocks", []) + [s])[-MAX_MOCKS:]
        study._touch(d)
        study.save(d)
    return s


def _mock(d: dict, sid: str) -> dict:
    s = next((m for m in d.get("mocks", []) if m["id"] == sid), None)
    if not s:
        raise StudyError("Unknown interview session.")
    return s


async def mock_answer(sid: str, i: int, answer: str, seconds: int, model: str | None = None) -> dict:
    answer = (answer or "").strip()[:4000]
    if len(answer) < 3:
        raise StudyError("Say or type your answer first.")
    s = _mock(study.load(), sid)
    if not 0 <= i < len(s["questions"]):
        raise StudyError("Unknown question.")
    q = s["questions"][i]
    about = f"the skill {q['skill']}" if q["kind"] == "technical" else "behavioural (STAR) answers"
    prompt = (f"You are a fair interviewer. Score the candidate's answer to: \"{q['question']}\" (about {about}). "
              "Reply with JSON only: {\"scores\": {\"relevance\": 1-5, \"depth\": 1-5, \"structure\": 1-5}, "
              "\"evidence\": [\"short exact quotes from the answer that support the scores\"], \"tips\": [\"1-3 concrete tips\"]}. "
              "Judge only what the candidate said; never assume experience they did not mention.\n\nAnswer:\n" + answer)
    fb = None
    for _ in range(2):
        fb = sc.check_feedback(await study._json(prompt, model), answer)
        if fb:
            break
    with study._lock:
        d = study.load()
        s = _mock(d, sid)
        s["questions"][i].update({"answer": answer, "seconds": max(0, min(3600, int(seconds or 0))), "feedback": fb})
        study._touch(d)
        study.save(d)
    return {"feedback": fb, "note": None if fb else "The AI's scores weren't usable this time; your answer is saved. Try scoring again."}


def mock_finish(sid: str) -> dict:
    with study._lock:
        d = study.load()
        s = _mock(d, sid)
        s["summary"] = sc.mock_summary([{"feedback": q["feedback"]} for q in s["questions"]])
        s["finished"] = True
        study.save(d)
        return s


def mock_list() -> list[dict]:
    return [{"id": m["id"], "created": m["created"], "job": m.get("job"), "questions": len(m["questions"]),
             "answered": sum(1 for q in m["questions"] if q["answer"]), "summary": m.get("summary")} for m in reversed(study.load().get("mocks", []))]


def mock_get(sid: str) -> dict:
    return _mock(study.load(), sid)


def mock_delete(sid: str) -> bool:
    with study._lock:
        d = study.load()
        n = len(d.get("mocks", []))
        d["mocks"] = [m for m in d.get("mocks", []) if m["id"] != sid]
        study.save(d)
        return len(d["mocks"]) < n
