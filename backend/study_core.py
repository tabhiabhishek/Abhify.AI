"""Study 7.2: the deterministic rules shared by Windows, Web and Android (spec: docs/specs/ABHIFY_STUDY_V1.md).
Pure functions, no AI, no I/O: skill extraction from a job description, the day-by-day prep plan, readiness scores and
the review schedule for missed questions. The Kotlin and JS versions must pass backend/tests/data/study_cases.json."""
from __future__ import annotations

import re
from datetime import date, timedelta
from typing import Any

PREFERRED_HEAD = re.compile(r"(?i)\b(preferred|nice to have|nice-to-have|good to have|bonus|a plus|plus points?|desirable|optional)\b")
REQUIRED_HEAD = re.compile(r"(?i)\b(requirements?|required|must have|must-have|qualifications?|what you('|’)ll need|you have|skills|responsibilities)\b")
BEHAVIOURAL = "Behavioural"
MAX_GAPS = 12
DEFAULT_DAYS = 14
MAX_DAYS = 60


def _norm(s: str) -> str:
    t = re.sub(r"[^a-z0-9+#.]+", " ", s.lower())
    t = re.sub(r"\.(?=\s|$)", " ", t)                       # a sentence's full stop isn't part of a name ("Power BI.")
    return " " + re.sub(r"\s+", " ", t).strip() + " "


def _terms(skill: dict) -> list[str]:
    return [skill["name"], *skill.get("aliases", [])]


def find_skills(text: str, taxonomy: list[dict]) -> list[str]:
    """Taxonomy skills named in [text], in order of first appearance (whole words, aliases included)."""
    t = _norm(text)
    found = []
    for s in taxonomy:
        pos = min((t.find(_norm(term)) for term in _terms(s) if t.find(_norm(term)) >= 0), default=-1)
        if pos >= 0:
            found.append((pos, s["name"]))
    return [n for _, n in sorted(found)]


def extract_skills(jd: str, taxonomy: list[dict]) -> dict[str, list[str]]:
    """{'required': [...], 'preferred': [...]}: lines under a 'preferred / nice to have / bonus' heading (or containing those
    words) are preferred; everything else is required. A skill in both stays required."""
    required, preferred, mode = [], [], "required"
    for line in (jd or "").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        is_head = len(stripped) <= 60 and (stripped.endswith(":") or stripped.isupper() or stripped.startswith("#"))
        if is_head:
            mode = "preferred" if PREFERRED_HEAD.search(stripped) else "required" if REQUIRED_HEAD.search(stripped) else mode
            continue
        for sentence in re.split(r"(?<=[.;!?])\s+", stripped):     # "SQL is required. Python is a plus." -> judged per sentence
            target = preferred if (mode == "preferred" or PREFERRED_HEAD.search(sentence)) else required
            for s in find_skills(sentence, taxonomy):
                if s not in target:
                    target.append(s)
    preferred = [s for s in preferred if s not in required]
    return {"required": required, "preferred": preferred}


def gaps(jd_skills: dict[str, list[str]], resume_skills: list[str]) -> list[dict]:
    """What to study, in order: required skills missing from the resume, then preferred ones missing, then required
    skills the resume already has (to refresh). At most MAX_GAPS."""
    have = set(resume_skills)
    out = [{"skill": s, "why": "required, not on your resume"} for s in jd_skills["required"] if s not in have]
    out += [{"skill": s, "why": "preferred, not on your resume"} for s in jd_skills["preferred"] if s not in have]
    out += [{"skill": s, "why": "required: refresh"} for s in jd_skills["required"] if s in have]
    return out[:MAX_GAPS]


def plan_days(interview: date | None, today: date) -> int:
    if interview is None:
        return DEFAULT_DAYS
    return max(1, min(MAX_DAYS, (interview - today).days))


def make_plan(gap_list: list[dict], interview: date | None, today: date, minutes: int) -> list[dict]:
    """Day-by-day plan. Each day: a lesson and MCQs on one skill (skills in rotation), a STAR story every second day,
    review from day 2; the last day (when there are 3+ days) is a full rehearsal. Minutes split 45/25/20/10."""
    n = plan_days(interview, today)
    minutes = max(15, min(240, int(minutes or 60)))
    skills = [g["skill"] for g in gap_list] or ["General interview preparation"]
    days = []
    for d in range(n):
        day = today + timedelta(days=d)
        last = n >= 3 and d == n - 1
        if last:
            items = [{"type": "mcq", "skill": "All skills", "minutes": round(minutes * 0.5), "title": "Mixed MCQs across all your plan skills"},
                     {"type": "story", "skill": BEHAVIOURAL, "minutes": round(minutes * 0.3), "title": "Rehearse your STAR stories out loud"},
                     {"type": "review", "skill": "All skills", "minutes": minutes - round(minutes * 0.5) - round(minutes * 0.3), "title": "Review everything you missed"}]
        else:
            skill = skills[d % len(skills)]
            items = [{"type": "lesson", "skill": skill, "minutes": round(minutes * 0.45), "title": f"Learn: {skill}"},
                     {"type": "mcq", "skill": skill, "minutes": round(minutes * 0.25), "title": f"Practice MCQs: {skill}"}]
            if d % 2 == 1:
                items.append({"type": "story", "skill": BEHAVIOURAL, "minutes": round(minutes * 0.2), "title": "Write or improve one STAR story"})
            if d >= 1:
                items.append({"type": "review", "skill": "Missed questions", "minutes": round(minutes * 0.1), "title": "Review questions you missed"})
        days.append({"date": day.isoformat(), "day": d + 1, "items": items})
    return days


def skill_score(answered: int, correct: int, lessons_done: int, lessons_total: int) -> int:
    """0–100: 60% MCQ accuracy (counted fully only after 10 answers), 40% lessons completed."""
    acc = (correct / answered) if answered else 0.0
    confidence = min(1.0, answered / 10)
    lessons = (lessons_done / lessons_total) if lessons_total else 0.0
    return round(100 * (0.6 * acc * confidence + 0.4 * lessons))


def level(score: int) -> str:
    return "Not ready" if score < 40 else "Getting there" if score < 70 else "Ready"


def readiness(skills: list[str], mcq: dict[str, dict], lessons: dict[str, dict]) -> dict[str, Any]:
    """Per-skill scores for [skills] from MCQ stats {skill: {answered, correct}} and lesson progress {skill: {done, total}};
    overall = rounded mean. Weakest first in 'weak' (score < 40)."""
    rows = []
    for s in skills:
        m, l = mcq.get(s, {}), lessons.get(s, {})
        sc = skill_score(int(m.get("answered", 0)), int(m.get("correct", 0)), int(l.get("done", 0)), int(l.get("total", 0)))
        rows.append({"skill": s, "score": sc, "level": level(sc), "answered": int(m.get("answered", 0)), "lessons_done": int(l.get("done", 0)),
                     "lessons_total": int(l.get("total", 0))})
    overall = round(sum(r["score"] for r in rows) / len(rows)) if rows else 0
    weak = [r["skill"] for r in sorted(rows, key=lambda r: (r["score"], r["skill"])) if r["score"] < 40]
    return {"overall": overall, "level": level(overall), "skills": rows, "weak": weak}


REVIEW_STEPS = [1, 3, 7, 14, 30]


def next_review(times_right: int, answered_on: date, correct: bool, lapses: int = 0) -> dict[str, Any]:
    """A missed question comes back after 1 day; each later right answer moves it to 3, 7, 14, 30 days, then it's done.
    [lapses] = how many times it was missed before this answer: from the second miss on it comes back the same day."""
    if not correct:
        return {"due": (answered_on + timedelta(days=0 if lapses >= 1 else REVIEW_STEPS[0])).isoformat(), "times_right": 0,
                "done": False, "lapses": lapses + 1}
    t = times_right + 1
    if t >= len(REVIEW_STEPS):
        return {"due": None, "times_right": t, "done": True, "lapses": lapses}
    return {"due": (answered_on + timedelta(days=REVIEW_STEPS[t])).isoformat(), "times_right": t, "done": False, "lapses": lapses}


def adapt_gaps(gap_list: list[dict], rows: list[dict]) -> list[dict]:
    """A new plan version keeps what the learner already proved: skills scored Ready (70+) move to the end as a light
    refresh, so the days go to what is genuinely still needed. Order is otherwise kept."""
    ready = {r["skill"] for r in rows if r.get("score", 0) >= 70}
    keep = [g for g in gap_list if g["skill"] not in ready]
    return keep + [{"skill": g["skill"], "why": "already Ready: light refresh"} for g in gap_list if g["skill"] in ready]


def streak(activity_days: list[str], today: date) -> int:
    """Consecutive days with study activity ending today (or yesterday)."""
    days = {date.fromisoformat(d) for d in activity_days}
    start = today if today in days else today - timedelta(days=1)
    n = 0
    while start - timedelta(days=n) in days:
        n += 1
    return n


def lesson_skills(tracks: dict) -> dict[str, list[str]]:
    """lesson id → the skills of its module (for lesson progress per skill)."""
    return {l["id"]: m["skills"] for t in tracks["tracks"] for m in t["modules"] for l in m["lessons"]}


def lessons_per_skill(tracks: dict, done_ids: set[str]) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    for lid, skills in lesson_skills(tracks).items():
        for s in skills:
            r = out.setdefault(s, {"done": 0, "total": 0})
            r["total"] += 1
            r["done"] += lid in done_ids
    return out


# --------------------------------------------------------------------------- 7.2 course journey (per-course preferences)
LESSON_MINUTES = 30          # one lesson + its quick practice
WEAK_MIN_ANSWERS = 3
WEAK_ACCURACY = 0.6


def course_schedule(modules: list[dict], minutes: int, start: date, optional: list[str] | None = None,
                    done: list[str] | None = None) -> dict[str, Any]:
    """Daily schedule and weekly milestones from the course's real syllabus: lessons in order (modules marked optional for
    the chosen level go last), max(1, minutes // 30) lessons a day, a revision day every 7th day. Done lessons are skipped,
    so changing preferences later never loses progress."""
    optional, done_set = set(optional or []), set(done or [])
    ordered = [m for m in modules if m["id"] not in optional] + [m for m in modules if m["id"] in optional]
    queue = [(m["id"], l["id"]) for m in ordered for l in m["lessons"] if l["id"] not in done_set]
    per_day = max(1, max(15, min(240, int(minutes or 60))) // LESSON_MINUTES)
    days, d, i = [], 0, 0
    while i < len(queue):
        day = start + timedelta(days=d)
        if d % 7 == 6:
            days.append({"date": day.isoformat(), "kind": "revision", "lessons": []})
        else:
            days.append({"date": day.isoformat(), "kind": "learn", "lessons": [lid for _, lid in queue[i:i + per_day]]})
            i += per_day
        d += 1
    weeks, seen, covered = [], set(), set(done_set)
    for w in range(0, len(days), 7):
        chunk = days[w:w + 7]
        lessons = [lid for x in chunk for lid in x["lessons"]]
        covered |= set(lessons)
        finished = [m["id"] for m in ordered if m["id"] not in seen and all(l["id"] in covered for l in m["lessons"])]
        seen |= set(finished)
        weeks.append({"week": w // 7 + 1, "from": chunk[0]["date"], "to": chunk[-1]["date"], "lessons": len(lessons), "modules_completed": finished})
    return {"per_day": per_day, "days": days, "weeks": weeks}


def module_mastery(module: dict, done: list[str], stats: dict[str, dict]) -> dict[str, Any]:
    """Mastery of one module from its lessons done and its recorded MCQ answers {module_id: {answered, correct}}."""
    s = stats.get(module["id"], {})
    a, c = int(s.get("answered", 0)), int(s.get("correct", 0))
    ld = sum(l["id"] in done for l in module["lessons"])
    score = skill_score(a, c, ld, len(module["lessons"]))
    weak = a >= WEAK_MIN_ANSWERS and c / a < WEAK_ACCURACY
    return {"module": module["id"], "score": score, "level": level(score), "answered": a, "correct": c,
            "lessons_done": ld, "lessons_total": len(module["lessons"]), "weak": weak}


def next_lesson(modules: list[dict], done: list[str], optional: list[str] | None = None) -> str | None:
    """The first lesson not done, core modules before optional ones."""
    optional = set(optional or [])
    for m in [m for m in modules if m["id"] not in optional] + [m for m in modules if m["id"] in optional]:
        for l in m["lessons"]:
            if l["id"] not in done:
                return l["id"]
    return None


# --------------------------------------------------------------------------- 7.2.0-dev.2: shared rules for the enhancements
_NUM = re.compile(r"\d+(?:[.,]\d+)?")


def fact_guard(source: str, rewritten: str, taxonomy: list[dict]) -> dict[str, Any]:
    """A rewrite (resume bullet, STAR version) may only reuse facts from [source]: every number and every taxonomy skill
    it names must already be in the source. Returns what was added; ok only when nothing was."""
    have_n = set(_NUM.findall(source or ""))
    new_n = sorted({n for n in _NUM.findall(rewritten or "") if n not in have_n})
    have_s = set(find_skills(source or "", taxonomy))
    new_s = [s for s in find_skills(rewritten or "", taxonomy) if s not in have_s]
    return {"ok": not new_n and not new_s, "new_numbers": new_n, "new_skills": new_s}


def mock_plan(skills: list[str], competencies: list[dict], count: int) -> list[dict]:
    """The questions of a mock interview, in order: technical skills and behavioural themes alternate (technical first),
    each used once while they last; count is clamped to 3-12."""
    count = max(3, min(12, int(count or 6)))
    tech = [{"kind": "technical", "skill": s} for s in skills]
    beh = [{"kind": "behavioural", "competency": c["id"], "question": c["question"]} for c in competencies]
    out, i, j = [], 0, 0
    while len(out) < count and (i < len(tech) or j < len(beh)):
        if (len(out) % 2 == 0 and i < len(tech)) or j >= len(beh):
            out.append(tech[i]); i += 1
        else:
            out.append(beh[j]); j += 1
    return out


def _squash(s: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]+", " ", (s or "").lower())).strip()


def check_feedback(o: Any, answer: str) -> dict[str, Any] | None:
    """Mock-interview feedback from the model: relevance, depth and structure must be 1-5; 'evidence' may only quote the
    candidate's own answer (quotes not found in it are dropped). None when the scores are unusable."""
    if not isinstance(o, dict) or not isinstance(o.get("scores"), dict):
        return None
    scores = {}
    for k in ("relevance", "depth", "structure"):
        v = o["scores"].get(k)
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 1 <= v <= 5:
            return None
        scores[k] = int(round(v))
    ans = _squash(answer)
    evidence = [str(q).strip()[:200] for q in (o.get("evidence") or []) if str(q).strip() and len(_squash(str(q))) >= 4 and _squash(str(q)) in ans][:3]
    tips = [str(t).strip()[:300] for t in (o.get("tips") or []) if str(t).strip()][:3]
    return {"scores": scores, "total": sum(scores.values()), "evidence": evidence, "tips": tips}


def mock_summary(rows: list[dict]) -> dict[str, Any]:
    """Session result from scored answers ({'skill' or 'competency', 'feedback'}): mean per dimension (1 decimal),
    overall percent of the maximum, weakest dimension. Unanswered or unscored questions don't count."""
    scored = [r["feedback"] for r in rows if r.get("feedback")]
    if not scored:
        return {"answered": 0, "percent": None, "means": {}, "weakest": None}
    means = {k: round(sum(f["scores"][k] for f in scored) / len(scored), 1) for k in ("relevance", "depth", "structure")}
    percent = round(100 * sum(f["total"] for f in scored) / (15 * len(scored)))
    weakest = sorted(means, key=lambda k: (means[k], k))[0]
    return {"answered": len(scored), "percent": percent, "means": means, "weakest": weakest}


def quality_verdict(probes: dict[str, bool]) -> str:
    """Model quality check from 3 probes (json_mcq, lesson_sections, fact): all pass = good, 2 = weak, else poor."""
    n = sum(bool(probes.get(k)) for k in ("json_mcq", "lesson_sections", "fact"))
    return "good" if n == 3 else "weak" if n == 2 else "poor"
