"""Study 7.2 on Windows/desktop: AI courses (fixed outlines + on-demand lessons), unlimited course MCQs, the prep plan from
a job description, STAR stories with coaching, readiness and review. Deterministic rules live in study_core.py (shared
with Android and Web); this module adds storage (data/study.json, one JSON file: no database migration) and the AI calls
(a local Ollama model only: Study never sends lessons, answers, resumes or stories to an online provider)."""
from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

import local_file_service as lfs
import study_core as sc

TRACKS_PATH = Path(__file__).with_name("study_tracks.json")
_lock = threading.Lock()


class StudyError(Exception):
    pass


# --------------------------------------------------------------------------- data
def tracks() -> dict[str, Any]:
    return json.loads(TRACKS_PATH.read_text(encoding="utf-8"))


def taxonomy() -> list[dict]:
    """The same skills taxonomy as Android (android/app/src/main/assets/skills_taxonomy.json; a test keeps them equal)."""
    return json.loads(Path(__file__).with_name("skills_taxonomy.json").read_text(encoding="utf-8"))["skills"]


def _file() -> Path:
    return lfs.data_dir() / "study.json"


SCHEMA = 1
EMPTY = {"schema": SCHEMA, "courses": {}, "lessons": {}, "plan": None, "plan_history": [], "stories": [], "mcq": {}, "reviews": {},
         "activity": [], "cards": {}}
log = logging.getLogger("abhify.study")


def _empty() -> dict[str, Any]:
    return json.loads(json.dumps(EMPTY))


def migrate(d: dict[str, Any]) -> dict[str, Any]:
    """Brings an older study.json up to SCHEMA. Unknown newer schemas are refused (never downgraded or overwritten)."""
    if not isinstance(d, dict):
        raise ValueError("not an object")
    v = int(d.get("schema") or 0)
    if v > SCHEMA:
        raise StudyError("Your study data was saved by a newer Abhify.AI. Update the app to open it; nothing was changed.")
    out = {**_empty(), **d}
    if v < 1:                                                        # 7.2.0 development files had no schema field
        for c in out["courses"].values():
            c.setdefault("done", [])
        out["schema"] = 1
    return out


def _read(p: Path) -> dict[str, Any]:
    return migrate(json.loads(p.read_text(encoding="utf-8")))


def load() -> dict[str, Any]:
    """The saved study data. A file that can't be read is never overwritten: it is renamed to study.corrupt-<time>.json
    and the last good copy (study.json.bak) is used, or an empty start when there is none."""
    p = _file()
    if not p.exists():
        return _empty()
    try:
        return _read(p)
    except StudyError:
        raise
    except (OSError, ValueError, TypeError, AttributeError) as e:
        bak = p.with_name(p.name + ".bak")
        quarantine = p.with_name(f"study.corrupt-{int(time.time() * 1000)}.json")
        try:
            os.replace(p, quarantine)
        except OSError:
            pass
        log.warning("study.json could not be read (%s); kept it as %s", type(e).__name__, quarantine.name)
        if bak.exists():
            try:
                d = _read(bak)
                save(d)
                return d
            except (OSError, ValueError, TypeError, AttributeError, StudyError):
                pass
        return _empty()


def save(d: dict[str, Any]) -> None:
    """Atomic: write a temp file, flush it to disk, keep the previous good file as study.json.bak, then replace."""
    p = _file()
    p.parent.mkdir(parents=True, exist_ok=True)
    d["schema"] = SCHEMA
    tmp = p.with_name(p.name + f".{os.getpid()}.{threading.get_ident()}.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps(d, ensure_ascii=False))
        f.flush()
        os.fsync(f.fileno())
    if p.exists():
        try:
            json.loads(p.read_text(encoding="utf-8"))                  # only a readable file becomes the backup
            os.replace(p, p.with_name(p.name + ".bak"))
        except (OSError, ValueError):
            pass
    os.replace(tmp, p)


def _touch(d: dict[str, Any]) -> None:
    today = date.today().isoformat()
    if today not in d["activity"]:
        d["activity"] = (d["activity"] + [today])[-400:]


# --------------------------------------------------------------------------- courses
def _track(tid: str) -> dict:
    t = next((t for t in tracks()["tracks"] if t["id"] == tid), None)
    if not t:
        raise StudyError("Unknown course.")
    return t


def course_outline(c: dict) -> dict:
    """A course's modules: the fixed track outline, or the custom outline stored with it."""
    if c.get("track"):
        t = _track(c["track"])
        return {"title": t["title"], "modules": t["modules"]}
    return {"title": c["title"], "modules": c["modules"]}


def create_course(track: str | None = None, outline: dict | None = None) -> dict:
    with _lock:
        d = load()
        if track:
            _track(track)
            existing = next((c for c in d["courses"].values() if c.get("track") == track), None)
            if existing:
                return existing
            c = {"id": f"c-{track}", "track": track, "title": _track(track)["title"], "created": int(time.time() * 1000), "done": []}
        else:
            c = {"id": "c-" + uuid.uuid4().hex[:10], "track": None, "title": outline["title"], "modules": outline["modules"],
                 "created": int(time.time() * 1000), "done": []}
        d["courses"][c["id"]] = c
        save(d)
        return c


def delete_course(cid: str) -> bool:
    with _lock:
        d = load()
        gone = d["courses"].pop(cid, None) is not None
        d["lessons"] = {k: v for k, v in d["lessons"].items() if not k.startswith(cid + "/")}
        d["cards"] = {k: v for k, v in d["cards"].items() if v.get("course") != cid}
        save(d)
        return gone


def validate_outline(o: Any, topic: str) -> dict:
    """A custom course outline from the model: 3–8 modules, 2–6 lessons each, short titles. Ids are added here."""
    if not isinstance(o, dict) or not isinstance(o.get("modules"), list):
        raise StudyError("The AI didn't return a course outline. Try again.")
    mods = []
    for i, m in enumerate(o["modules"][:8]):
        if not isinstance(m, dict) or not str(m.get("title", "")).strip():
            continue
        lessons = [str(x).strip()[:120] for x in (m.get("lessons") or []) if str(x).strip()][:6]
        if len(lessons) < 2:
            continue
        mid = f"m{i + 1}"
        mods.append({"id": mid, "title": str(m["title"]).strip()[:120], "skills": sc.find_skills(topic + " " + str(m["title"]), taxonomy())[:4],
                     "lessons": [{"id": f"{mid}-{j + 1}", "title": t} for j, t in enumerate(lessons)]})
    if len(mods) < 3:
        raise StudyError("The AI's course outline was too short. Try again, or choose one of the ready-made courses.")
    return {"title": (str(o.get("title") or topic).strip() or topic)[:120], "modules": mods}


async def custom_course(topic: str, model: str | None = None) -> dict:
    topic = (topic or "").strip()
    if len(topic) < 2:
        raise StudyError("Type a topic for the course, for example \"Kubernetes for beginners\".")
    prompt = (f"Create a short course outline for learning \"{topic}\" for a job interview. Reply with JSON only: "
              '{"title": "...", "modules": [{"title": "...", "lessons": ["lesson title", "..."]}]}. '
              "Use 4 to 6 modules with 3 to 5 lessons each, ordered from basics to advanced. Lesson titles only, no content.")
    o = await _json(prompt, model)
    return create_course(outline=validate_outline(o, topic))


def _find_lesson(c: dict, lesson_id: str) -> tuple[dict, dict]:
    for m in course_outline(c)["modules"]:
        for l in m["lessons"]:
            if l["id"] == lesson_id:
                return m, l
    raise StudyError("Unknown lesson.")


LESSON_PROMPT = ("Write an accurate lesson on \"{lesson}\" (module \"{module}\" of the course \"{course}\") for a learner whose goal is "
                 "{target} at {level} level. Use Markdown with exactly these headings, in this order:\n"
                 "## In simple words\n## Going deeper\n## Advanced: production and architecture\n## Real-world example\n"
                 "## Code example (one short, runnable {lang} example in a fenced block; skip only if the topic has no code)\n"
                 "## Architecture sketch (a small Mermaid flowchart in a ```mermaid fenced block, only if it helps; otherwise write 'Not needed for this lesson.')\n"
                 "## Common mistakes\n## Practice exercise (one hands-on task with what a good solution includes)\n"
                 "## Interview questions (3, each with a 2-3 sentence model answer)\n## Follow-up questions (3 questions an interviewer may ask next)\n"
                 "{depth} Plain, friendly language. Stay on this lesson's topic. If something depends on a tool version, say so instead of guessing.")
DEPTH = {"beginner": "Keep 'Advanced' short; spend most words on the simple explanation and the example. About 700 words.",
         "intermediate": "Balance all sections. About 800 words.",
         "advanced": "Keep 'In simple words' to 2-3 sentences; spend most words on production trade-offs, scale and architecture. About 900 words."}


SECTIONS = ("in simple words", "going deeper", "advanced", "real-world example", "code example", "common mistakes",
            "practice exercise", "interview questions", "follow-up questions")
_SETEXT = re.compile(r"^(?:\*\*)?([^\n#*][^\n]{1,80}?)(?:\*\*)?\s*\n[-=]{3,}\s*$", re.M)
_BOLD_HEAD = re.compile(r"^\s*\*\*([^*\n]{3,80})\*\*:?\s*$", re.M)
_NUM_HEAD = re.compile(r"^\s*#{0,4}\s*\d+[.)]\s+(?=(?:" + "|".join(SECTIONS) + r"))", re.M | re.I)


def normalize_lesson(text: str) -> str:
    """Small models write headings in other styles ("Title" underlined, **Title**, "1. Title"): make them "## Title" so the
    lesson renders and can be checked."""
    t = (text or "").strip()
    t = _SETEXT.sub(lambda m: "## " + m.group(1).strip(), t)
    t = _BOLD_HEAD.sub(lambda m: "## " + m.group(1).strip(), t)
    t = _NUM_HEAD.sub("## ", t)
    return t


def lesson_ok(text: str) -> bool:
    """A real lesson: long enough and at least 4 of the asked-for sections as headings."""
    heads = " ".join(h.lower() for h in re.findall(r"^#{1,4}\s+(.+)$", text or "", re.M))
    return len((text or "").strip()) >= 400 and sum(s in heads for s in SECTIONS) >= 4


def _lang(c: dict) -> str:
    return {"cloud": "Terraform, Bash or Python", "ds": "Python (pandas) or SQL", "behav": "no"}.get(c.get("track") or "", "Python")


async def lesson(cid: str, lesson_id: str, refresh: bool = False, model: str | None = None) -> dict:
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    m, l = _find_lesson(c, lesson_id)
    key = f"{cid}/{lesson_id}"
    if not refresh and key in d["lessons"]:
        return {**d["lessons"][key], "cached": True}
    pr = prefs_of(c)
    import study_plus
    ctx, used = study_plus.brain_context(c)                            # only what the learner ticked for this course (off by default)
    prompt = ctx + LESSON_PROMPT.format(lesson=l["title"], module=m["title"], course=c["title"], target=pr["target"], level=pr["level"],
                                        lang=_lang(c), depth=DEPTH.get(pr["level_key"], DEPTH["intermediate"]))
    text = normalize_lesson(await _text(prompt, model))
    if not lesson_ok(text):                                            # one automatic retry before asking the user
        text = normalize_lesson(await _text(prompt, model))
    if not lesson_ok(text):
        raise StudyError("The AI's lesson was incomplete. Try again, or choose a larger model in AI models & setup.")
    entry = {"course": cid, "lesson": lesson_id, "title": l["title"], "markdown": text.strip(), "at": int(time.time() * 1000), "model": model,
             "prefs": pr["key"], "used": used}
    with _lock:
        d = load()
        d["lessons"][key] = entry
        _history(d, cid, "lesson", l["title"])
        _touch(d)
        save(d)
    return {**entry, "cached": False}


def set_done(cid: str, lesson_id: str, done: bool) -> dict:
    with _lock:
        d = load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        _find_lesson(c, lesson_id)
        s = set(c["done"])
        (s.add if done else s.discard)(lesson_id)
        c["done"] = sorted(s)
        if done:
            _history(d, cid, "done", _find_lesson(c, lesson_id)[1]["title"])
        _touch(d)
        save(d)
        return c


# --------------------------------------------------------------------------- unlimited MCQs per course
async def course_mcq(cid: str, round_: int, level: str = "junior", difficulty: str = "medium", count: int = 5,
                     model: str | None = None, avoid: list[str] | None = None, module_id: str | None = None, weak: bool = False) -> dict:
    """One batch for a course, rotating through its modules (round % modules): the existing career MCQ generator
    (validation, de-duplication, facet rotation) with the module as the topic. Called again as the user scrolls."""
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    mods = course_outline(c)["modules"]
    pr = prefs_of(c)
    core = [x for x in mods if x["id"] not in pr["optional"]] or mods
    m = core[round_ % len(core)]
    if module_id:
        m = next((x for x in mods if x["id"] == module_id), m)
    elif weak:
        w = weak_modules(c)
        m = next((x for x in mods if x["id"] == w[round_ % len(w)]), m) if w else m
    if level == "auto":
        level = {"beginner": "fresher", "intermediate": "junior", "advanced": "senior"}.get(pr["level_key"], "mid-level")
    import career_mcq
    topic = f"{m['title']} ({c['title']})"[:120]
    out = await career_mcq.generate("topic", topic, None, level, difficulty, count, model, (avoid or [])[-40:])
    skill = (m.get("skills") or [c["title"]])[0]
    for q in out.get("questions", []):
        q["skill"] = skill
        q["module"] = m["title"]
        q["module_id"] = m["id"]
        q["course_id"] = cid
    out["module"] = m["title"]
    out["module_id"] = m["id"]
    return out


def answer(skill: str, question: dict, correct: bool, today: date | None = None, course_id: str | None = None,
           module_id: str | None = None) -> dict:
    """Counts the answer for readiness (per skill) and course mastery (per module), and schedules a missed question for
    review (study_core.next_review). Wrong answers are kept for the course's 'incorrect answers' list."""
    today = today or date.today()
    with _lock:
        d = load()
        s = d["mcq"].setdefault(skill, {"answered": 0, "correct": 0})
        s["answered"] += 1
        s["correct"] += bool(correct)
        c = d["courses"].get(course_id or "")
        if c and module_id:
            c.setdefault("days", {}).setdefault(today.isoformat(), 0)        # an active day for the course streak (no time added)
            ms = c.setdefault("scores", {}).setdefault(module_id, {"answered": 0, "correct": 0})
            ms["answered"] += 1
            ms["correct"] += bool(correct)
            if not correct:
                c["wrong"] = (c.get("wrong", []) + [{**question, "at": today.isoformat()}])[-200:]
        key = str(question.get("key") or sc_hash(question.get("q") or question.get("question") or ""))
        r = d["reviews"].get(key)
        if r or not correct:
            nxt = sc.next_review((r or {}).get("times_right", 0), today, correct, int((r or {}).get("lapses", 0)))
            d["reviews"][key] = {**(r or {"question": question, "skill": skill, "course": course_id}), **nxt}
        _touch(d)
        save(d)
        return {"skill": skill, **s}


def sc_hash(text: str) -> str:
    import hashlib
    return hashlib.sha1(re.sub(r"\s+", " ", text.lower()).strip().encode()).hexdigest()[:16]


def due_reviews(today: date | None = None, course_id: str | None = None) -> list[dict]:
    today = (today or date.today()).isoformat()
    return [{"key": k, **v} for k, v in load()["reviews"].items()
            if not v.get("done") and v.get("due") and v["due"] <= today and (course_id is None or v.get("course") == course_id)]


# --------------------------------------------------------------------------- prep plan from a job description
def clear_plan() -> dict:
    with _lock:
        d = load()
        d["plan"] = None
        save(d)
    return {"plan": None}


def make_plan(jd: str, resume_text: str = "", interview: str | None = None, minutes: int = 60, today: date | None = None) -> dict:
    if len((jd or "").strip()) < 30:
        raise StudyError("Paste the job description (at least a few lines).")
    today = today or date.today()
    when = None
    if interview:
        try:
            when = date.fromisoformat(interview)
        except ValueError:
            raise StudyError("The interview date isn't valid.") from None
    tax = taxonomy()
    skills = sc.extract_skills(jd, tax)
    if not skills["required"] and not skills["preferred"]:
        raise StudyError("No known skills were found in this job description. Add the skills section, or pick a ready-made course instead.")
    resume_skills = sc.find_skills(resume_text or "", tax)
    gaps = sc.gaps(skills, resume_skills)
    d0 = load()                                                       # a new plan version keeps what's already proven
    gaps = sc.adapt_gaps(gaps, sc.readiness([g["skill"] for g in gaps], d0["mcq"], _lesson_progress(d0))["skills"])
    plan = {"created": int(time.time() * 1000), "interview": when.isoformat() if when else None, "minutes": minutes,
            "skills": skills, "resume_skills": resume_skills, "gaps": gaps,
            "days": sc.make_plan(gaps, when, today, minutes), "done": [], "jd_title": (jd.strip().splitlines() or [""])[0][:100]}
    with _lock:
        d = load()
        if d.get("plan"):
            d["plan_history"] = (d.get("plan_history", []) + [{**d["plan"], "archived_at": int(time.time() * 1000)}])[-10:]
        d["plan"] = plan
        _touch(d)
        save(d)
    return plan


def plan_item(day: int, index: int, done: bool) -> dict:
    with _lock:
        d = load()
        p = d.get("plan")
        if not p or not (1 <= day <= len(p["days"])) or not (0 <= index < len(p["days"][day - 1]["items"])):
            raise StudyError("That plan item doesn't exist.")
        k = f"{day}:{index}"
        s = set(p["done"])
        (s.add if done else s.discard)(k)
        p["done"] = sorted(s)
        _touch(d)
        save(d)
        return p


# --------------------------------------------------------------------------- STAR stories
STAR_FIELDS = ("situation", "task", "action", "result")


def save_story(story: dict) -> dict:
    title = str(story.get("title", "")).strip()[:120]
    if not title:
        raise StudyError("Give the story a short title.")
    s = {"id": story.get("id") or "s-" + uuid.uuid4().hex[:10], "title": title,
         "competencies": [c for c in story.get("competencies", []) if c in {x["id"] for x in tracks()["competencies"]}],
         **{f: str(story.get(f, "")).strip()[:2000] for f in STAR_FIELDS}, "updated": int(time.time() * 1000),
         "coach": story.get("coach")}
    with _lock:
        d = load()
        d["stories"] = [x for x in d["stories"] if x["id"] != s["id"]] + [s]
        _touch(d)
        save(d)
    return s


def delete_story(sid: str) -> bool:
    with _lock:
        d = load()
        before = len(d["stories"])
        d["stories"] = [x for x in d["stories"] if x["id"] != sid]
        save(d)
        return len(d["stories"]) < before


def validate_coach(o: Any) -> dict:
    if not isinstance(o, dict) or not isinstance(o.get("scores"), dict):
        raise StudyError("The AI's feedback was incomplete. Try again.")
    scores = {}
    for f in STAR_FIELDS:
        v = o["scores"].get(f)
        if not isinstance(v, (int, float)) or not 1 <= v <= 5:
            raise StudyError("The AI's feedback was incomplete. Try again.")
        scores[f] = int(round(v))
    lst = lambda k: [str(x).strip()[:300] for x in (o.get(k) or []) if str(x).strip()][:5]   # noqa: E731
    return {"scores": scores, "missing": lst("missing"), "tips": lst("tips"), "improved": str(o.get("improved", "")).strip()[:3000],
            "at": int(time.time() * 1000)}


NUM = re.compile(r"\d+(?:[.,]\d+)?")


def guard_facts(fb: dict, story: str) -> dict:
    """The tighter version may only reuse the story's facts: if it has a number the story doesn't, it is dropped."""
    have = set(NUM.findall(story))
    if fb.get("improved") and any(n not in have for n in NUM.findall(fb["improved"])):
        fb = {**fb, "improved": "", "tips": fb["tips"] + ["The tighter version was left out because it added numbers that aren't in your story."]}
    return fb


async def coach(sid: str, model: str | None = None) -> dict:
    d = load()
    s = next((x for x in d["stories"] if x["id"] == sid), None)
    if not s:
        raise StudyError("Unknown story.")
    if sum(len(s[f]) for f in STAR_FIELDS) < 40:
        raise StudyError("Write a few sentences in the story first.")
    prompt = ("You are an interview coach. Rate this STAR interview story. Reply with JSON only: "
              '{"scores": {"situation": 1-5, "task": 1-5, "action": 1-5, "result": 1-5}, "missing": ["..."], "tips": ["..."], '
              '"improved": "a tighter first-person version of the story, under 180 words, keeping only facts given below"}. '
              "Action should show what *I* did; Result should be concrete (numbers if given). Never invent facts.\n\n"
              + "\n".join(f"{f.title()}: {s[f]}" for f in STAR_FIELDS))
    fb = guard_facts(validate_coach(await _json(prompt, model)), " ".join(s[f] for f in STAR_FIELDS))
    s["coach"] = fb
    save_story(s)
    return fb


# --------------------------------------------------------------------------- readiness, today, overview
def _lesson_progress(d: dict) -> dict[str, dict[str, int]]:
    done = {lid for c in d["courses"].values() for lid in c.get("done", [])}
    per_skill = sc.lessons_per_skill(tracks(), done)
    for c in d["courses"].values():                                   # custom courses count for their module skills too
        if not c.get("track"):
            for m in c["modules"]:
                for s in m.get("skills", []):
                    r = per_skill.setdefault(s, {"done": 0, "total": 0})
                    r["total"] += len(m["lessons"])
                    r["done"] += sum(l["id"] in c["done"] for l in m["lessons"])
    return per_skill


def overview(today: date | None = None) -> dict:
    today = today or date.today()
    d = load()
    t = tracks()
    per_skill = _lesson_progress(d)
    plan = d.get("plan")
    target = [g["skill"] for g in plan["gaps"]] if plan else sorted(d["mcq"])[:12]
    ready = sc.readiness(target, d["mcq"], per_skill)
    today_items = []
    if plan:
        for i, day in enumerate(plan["days"]):
            if day["date"] == today.isoformat():
                today_items = [{**it, "key": f"{i + 1}:{j}", "done": f"{i + 1}:{j}" in plan["done"]} for j, it in enumerate(day["items"])]
    courses = []
    for c in d["courses"].values():
        o = course_outline(c)
        total = sum(len(m["lessons"]) for m in o["modules"])
        courses.append({"id": c["id"], "title": c["title"], "track": c.get("track"), "done": len(c["done"]), "total": total})
    comp = {x["id"]: 0 for x in t["competencies"]}
    for s in d["stories"]:
        for k in s.get("competencies", []):
            comp[k] = comp.get(k, 0) + 1
    return {"courses": courses, "plan": plan, "plan_history": d.get("plan_history", []), "readiness": ready, "today": today_items, "streak": sc.streak(d["activity"], today),
            "reviews_due": len(due_reviews(today)), "stories": len(d["stories"]), "competencies": comp}


# --------------------------------------------------------------------------- 7.2 course journey: preferences, overview, dashboard
TARGETS = ["Senior AI/ML Engineer", "LLM / GenAI Engineer", "AI Platform / MLOps Engineer", "AI Technical Lead / Architect",
           "Software Engineer", "Data Scientist / Analyst", "Cloud Engineer", "General knowledge / personal learning"]
LEVELS = {"beginner": "Beginner: build strong fundamentals", "intermediate": "Intermediate: strengthen and advance",
          "advanced": "Advanced: production and architecture"}


def _history(d: dict, cid: str, kind: str, detail: str) -> None:
    c = d["courses"].get(cid)
    if c is not None:
        c["history"] = (c.get("history", []) + [{"at": int(time.time() * 1000), "kind": kind, "detail": detail[:120]}])[-300:]


def prefs_of(c: dict) -> dict:
    """The course's learning preferences (defaults until the user chooses). key changes when they change."""
    p = c.get("prefs") or {}
    target = (p.get("target") or "General knowledge / personal learning").strip()[:80]
    level_key = p.get("level") if p.get("level") in LEVELS else "custom" if p.get("level") else "intermediate"
    level_txt = LEVELS.get(level_key) or str(p.get("level"))[:80]
    return {"target": target, "level_key": level_key, "level": level_txt, "minutes": int(p.get("minutes") or 60),
            "optional": [x for x in (c.get("overview") or {}).get("optional", [])], "chosen": bool(c.get("prefs")),
            "key": f"{target}|{level_key}|{level_txt}"}


def set_prefs(cid: str, target: str, level: str, minutes: int = 60) -> dict:
    """Saves the preferences. Progress (done lessons, scores, cards, history) is kept; only the overview is rebuilt."""
    target, level = (target or "").strip()[:80], (level or "").strip()[:80]
    if not target or not level:
        raise StudyError("Choose a goal and a level.")
    with _lock:
        d = load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        if c.get("prefs"):                                             # the earlier plan version is kept, not replaced
            c["prefs_history"] = (c.get("prefs_history", []) + [{"prefs": c["prefs"], "overview": c.get("overview"), "at": int(time.time() * 1000)}])[-20:]
        c["prefs"] = {"target": target, "level": level, "minutes": max(15, min(240, int(minutes or 60)))}
        c["overview"] = None
        _history(d, cid, "prefs", f"{target} · {LEVELS.get(level, level)}")
        save(d)
        return c


def validate_overview(o: Any, modules: list[dict]) -> dict:
    """The AI's course overview: text fields only; 'optional' and 'focus' may only name modules of the real syllabus."""
    if not isinstance(o, dict) or not str(o.get("summary", "")).strip():
        raise StudyError("The AI's course overview was incomplete. Try again.")
    ids = {m["id"] for m in modules}
    keys = {"summary", "objectives", "prerequisites", "optional", "focus", "completion"}

    def lst(k, n):
        raw = o.get(k) or []
        raw = raw if isinstance(raw, list) else [raw]
        items = [p.strip(" .") for x in raw for p in re.split(r";\s+|\n+", str(x))]     # "a; b; c" written as one item
        return [x[:200] for x in items if x and x.lower() not in keys][:n]
    optional = [x for x in (o.get("optional") or []) if x in ids][: max(0, len(ids) - 2)]
    focus = {k: str(v).strip()[:200] for k, v in (o.get("focus") or {}).items() if k in ids and str(v).strip()} if isinstance(o.get("focus"), dict) else {}
    return {"summary": str(o["summary"]).strip()[:800], "objectives": lst("objectives", 8), "prerequisites": lst("prerequisites", 8),
            "completion": lst("completion", 6) or ["Finish every core lesson", "Score 70% or more in each module's MCQs", "Complete the capstone project"],
            "optional": optional, "focus": focus, "at": int(time.time() * 1000)}


async def build_overview(cid: str, model: str | None = None) -> dict:
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    pr, mods = prefs_of(c), course_outline(c)["modules"]
    syllabus = "\n".join(f"- {m['id']}: {m['title']} ({', '.join(l['title'] for l in m['lessons'])})" for m in mods)
    prompt = (f"A learner wants to take the course \"{c['title']}\". Goal: {pr['target']}. Level: {pr['level']}.\n"
              f"The course syllabus (module id: title (lessons)):\n{syllabus}\n\n"
              "Reply with JSON only: {\"summary\": \"2-3 sentences on what this course gives this learner\", "
              "\"objectives\": [\"4-6 learning objectives\"], \"prerequisites\": [\"3-5 prerequisite skills\"], "
              "\"optional\": [\"module ids this learner can treat as optional review, e.g. basics for an advanced learner; may be empty\"], "
              "\"focus\": {\"module id\": \"one sentence on why it matters for this goal\"}, "
              "\"completion\": [\"3-4 course completion criteria\"]}. Use only modules from the syllabus; never add new ones.")
    ov = validate_overview(await _json(prompt, model), mods)
    with _lock:
        d = load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        c["overview"] = ov
        save(d)
    return ov


def add_time(cid: str, seconds: int) -> int:
    """Real study time: the Study tab reports the seconds a lesson or practice was open and visible (max 10 min a report)."""
    with _lock:
        d = load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        c["seconds"] = int(c.get("seconds", 0)) + max(0, min(600, int(seconds)))
        days = c.setdefault("days", {})
        today = date.today().isoformat()
        days[today] = days.get(today, 0) + max(0, min(600, int(seconds)))
        if len(days) > 400:
            for k in sorted(days)[:-400]:
                days.pop(k)
        _touch(d)
        save(d)
        return c["seconds"]


def set_exercise(cid: str, lesson_id: str, done: bool) -> dict:
    with _lock:
        d = load()
        c = d["courses"].get(cid)
        if not c:
            raise StudyError("Unknown course.")
        _, l = _find_lesson(c, lesson_id)
        s = set(c.get("exercises", []))
        (s.add if done else s.discard)(lesson_id)
        c["exercises"] = sorted(s)
        if done:
            _history(d, cid, "exercise", l["title"])
        _touch(d)
        save(d)
        return c


def weak_modules(c: dict) -> list[str]:
    mods = course_outline(c)["modules"]
    rows = [sc.module_mastery(m, c.get("done", []), c.get("scores", {})) for m in mods]
    return [r["module"] for r in sorted(rows, key=lambda r: r["correct"] / max(1, r["answered"])) if r["weak"]]


def dashboard(cid: str, today: date | None = None) -> dict:
    """One course's dashboard, from recorded progress only (nothing is estimated or invented)."""
    today = today or date.today()
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    o, pr = course_outline(c), prefs_of(c)
    mods = o["modules"]
    rows = [sc.module_mastery(m, c.get("done", []), c.get("scores", {})) for m in mods]
    total = sum(len(m["lessons"]) for m in mods)
    answered = sum(r["answered"] for r in rows)
    correct = sum(r["correct"] for r in rows)
    titles = {m["id"]: m["title"] for m in mods}
    cards_due = [k for k, v in d["cards"].items() if v.get("course") == cid and not v.get("done") and (v.get("due") or "") <= today.isoformat()]
    nxt = sc.next_lesson(mods, c.get("done", []), pr["optional"])
    # any study activity on this course counts (lessons, answers, cards, exercises, tutor, time), on the local date
    active_days = sorted(set(c.get("days", {})) | {date.fromtimestamp(h["at"] / 1000).isoformat() for h in c.get("history", []) if h.get("at")}
                         | {w["at"] for w in c.get("wrong", []) if w.get("at")})
    cap = c.get("capstone") or {}
    week_ago = (today - timedelta(days=6)).isoformat()
    return {
        "id": cid, "title": c["title"], "prefs": pr, "overview": c.get("overview"),
        "progress": round(100 * len(c.get("done", [])) / total) if total else 0,
        "lessons_done": len(c.get("done", [])), "lessons_total": total,
        "modules_done": sum(r["lessons_done"] == r["lessons_total"] for r in rows), "modules_total": len(mods),
        "seconds": int(c.get("seconds", 0)), "seconds_week": sum(v for k, v in c.get("days", {}).items() if k >= week_ago),
        "assessment": {"answered": answered, "correct": correct, "percent": round(100 * correct / answered) if answered else None},
        "exercises_done": len(c.get("exercises", [])),
        "mastery": [{**r, "title": titles[r["module"]], "optional": r["module"] in pr["optional"]} for r in rows],
        "weak": [{"id": m, "title": titles[m]} for m in weak_modules(c)],
        "revision": {"cards_due": len(cards_due), "questions_due": len(due_reviews(today, cid)),
                     "wrong_this_week": sum(1 for w in c.get("wrong", []) if w.get("at", "") >= week_ago)},
        "streak": sc.streak(active_days, today) if active_days else 0,
        "next_lesson": None if not nxt else {"id": nxt, "title": _find_lesson(c, nxt)[1]["title"], "module": _find_lesson(c, nxt)[0]["title"]},
        "schedule": sc.course_schedule(mods, pr["minutes"], today, pr["optional"], c.get("done", [])),
        "capstone": {"title": cap.get("title"), "done": sum(bool(x.get("done")) for x in cap.get("milestones", [])),
                     "total": len(cap.get("milestones", []))} if cap else None,
        "history": list(reversed(c.get("history", [])))[:30],
        "plan_change": plan_change(c, mods),
    }


def plan_change(c: dict, mods: list[dict]) -> dict | None:
    """After a goal/level change and a new overview: which modules became part of the path or optional, and how much
    finished work carries over (it is never assigned again: the schedule skips done lessons)."""
    hist = c.get("prefs_history") or []
    if not hist or not c.get("overview"):
        return None
    titles = {m["id"]: m["title"] for m in mods}
    before = set((hist[-1].get("overview") or {}).get("optional", []))
    now = set(c["overview"].get("optional", []))
    return {"previous": hist[-1]["prefs"], "now_core": [titles[m] for m in sorted(before - now) if m in titles],
            "now_optional": [titles[m] for m in sorted(now - before) if m in titles], "kept": len(c.get("done", []))}


# --------------------------------------------------------------------------- flashcards (spaced repetition), revision
def validate_cards(o: Any) -> list[dict]:
    items = o.get("cards") if isinstance(o, dict) else o
    if not isinstance(items, list):
        raise StudyError("The AI didn't return flashcards. Try again.")
    out = []
    for x in items[:10]:
        if isinstance(x, dict) and len(str(x.get("front", "")).strip()) >= 5 and str(x.get("back", "")).strip():
            out.append({"front": str(x["front"]).strip()[:300], "back": str(x["back"]).strip()[:600]})
    if len(out) < 3:
        raise StudyError("The AI made too few usable flashcards. Try again.")
    return out


async def make_cards(cid: str, lesson_id: str, model: str | None = None, today: date | None = None) -> list[dict]:
    """6-8 flashcards from the lesson text (the lesson must be opened first). New cards are due today."""
    today = today or date.today()
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    m, l = _find_lesson(c, lesson_id)
    les = d["lessons"].get(f"{cid}/{lesson_id}")
    if not les:
        raise StudyError("Open the lesson first, then make flashcards from it.")
    prompt = ("Make 6 to 8 flashcards for revising this lesson. Reply with JSON only: {\"cards\": [{\"front\": \"a short question\", "
              "\"back\": \"a 1-2 sentence answer\"}]}. Use only facts from the lesson.\n\n" + les["markdown"][:6000])
    cards = validate_cards(await _json(prompt, model))
    with _lock:
        d = load()
        d["cards"] = {k: v for k, v in d["cards"].items() if v.get("lesson") != f"{cid}/{lesson_id}"}
        for x in cards:
            k = "f-" + uuid.uuid4().hex[:10]
            d["cards"][k] = {**x, "course": cid, "lesson": f"{cid}/{lesson_id}", "module": m["id"], "title": l["title"],
                             "due": today.isoformat(), "times_right": 0, "done": False}
        _history(d, cid, "cards", l["title"])
        save(d)
    return cards


def due_cards(cid: str | None = None, today: date | None = None, weekly: bool = False) -> list[dict]:
    """Daily revision: cards due today or earlier. Weekly revision: every card of lessons studied in the last 7 days."""
    today = today or date.today()
    d = load()
    if weekly:
        since = int((time.time() - 7 * 86400) * 1000)
        recent = {k for k, v in d["lessons"].items() if v.get("at", 0) >= since}
        return [{"id": k, **v} for k, v in d["cards"].items() if v["lesson"] in recent and (cid is None or v["course"] == cid)]
    return [{"id": k, **v} for k, v in d["cards"].items()
            if not v.get("done") and (v.get("due") or "") <= today.isoformat() and (cid is None or v["course"] == cid)]


def grade_card(card_id: str, remembered: bool, today: date | None = None) -> dict:
    today = today or date.today()
    with _lock:
        d = load()
        v = d["cards"].get(card_id)
        if not v:
            raise StudyError("Unknown flashcard.")
        v.update(sc.next_review(v.get("times_right", 0) if remembered else 0, today, remembered, int(v.get("lapses", 0))))
        _touch(d)
        save(d)
        return {"id": card_id, **v}


# --------------------------------------------------------------------------- capstone project, AI tutor
async def make_capstone(cid: str, model: str | None = None) -> dict:
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    pr, mods = prefs_of(c), course_outline(c)["modules"]
    prompt = (f"Design one capstone project for the course \"{c['title']}\" (modules: {', '.join(m['title'] for m in mods)}) for a learner "
              f"aiming at {pr['target']}, level {pr['level']}. It must use what the course teaches and be doable on a laptop for free. "
              "Reply with JSON only: {\"title\": \"...\", \"brief\": \"3-4 sentences\", \"milestones\": [\"4-6 concrete steps\"], "
              "\"stretch\": [\"1-3 optional extras\"]}.")
    o = await _json(prompt, model)
    ms = [str(x).strip()[:200] for x in (o.get("milestones") or []) if str(x).strip()][:6] if isinstance(o, dict) else []
    if not isinstance(o, dict) or not str(o.get("title", "")).strip() or len(ms) < 3:
        raise StudyError("The AI's capstone project was incomplete. Try again.")
    cap = {"title": str(o["title"]).strip()[:120], "brief": str(o.get("brief", "")).strip()[:800],
           "milestones": [{"title": t, "done": False} for t in ms],
           "stretch": [str(x).strip()[:200] for x in (o.get("stretch") or []) if str(x).strip()][:3]}
    with _lock:
        d = load()
        d["courses"][cid]["capstone"] = cap
        _history(d, cid, "capstone", cap["title"])
        save(d)
    return cap


def capstone_step(cid: str, index: int, done: bool) -> dict:
    with _lock:
        d = load()
        cap = (d["courses"].get(cid) or {}).get("capstone")
        if not cap or not 0 <= index < len(cap["milestones"]):
            raise StudyError("That capstone step doesn't exist.")
        cap["milestones"][index]["done"] = bool(done)
        if done:
            _history(d, cid, "capstone step", cap["milestones"][index]["title"])
        _touch(d)
        save(d)
        return cap


async def tutor(cid: str, lesson_id: str, question: str, model: str | None = None) -> dict:
    """Ask the AI tutor about one lesson. The context is the lesson text and the course preferences only: no chat history,
    memories or documents are added."""
    question = (question or "").strip()
    if len(question) < 3:
        raise StudyError("Type your question.")
    d = load()
    c = d["courses"].get(cid)
    if not c:
        raise StudyError("Unknown course.")
    _, l = _find_lesson(c, lesson_id)
    les = (d["lessons"].get(f"{cid}/{lesson_id}") or {}).get("markdown", "")
    pr = prefs_of(c)
    import study_plus
    ctx, _ = study_plus.brain_context(c)
    prompt = ctx + (f"The learner is studying the lesson \"{l['title']}\" in the course \"{c['title']}\" (goal: {pr['target']}, level: {pr['level']}).\n"
              + (f"The lesson:\n{les[:6000]}\n\n" if les else "")
              + f"Their question: {question[:1500]}\n\nAnswer clearly and kindly in Markdown, with a small example if it helps. "
              "If the question is outside this lesson, answer briefly and say which topic covers it.")
    text = (await _text(prompt, model)).strip()
    if not text:
        raise StudyError("The AI tutor didn't answer. Try again.")
    with _lock:
        d = load()
        _history(d, cid, "tutor", l["title"])
        save(d)
    return {"answer": text}


# --------------------------------------------------------------------------- AI calls (the chat's chosen model; local by default)
MAX_TOKENS_JSON = 900
MAX_TOKENS_TEXT = 2200


async def _invoke(prompt: str, model: str | None, json_mode: bool) -> str:
    from langchain_core.messages import HumanMessage, SystemMessage
    import agent
    import system_service as system
    try:
        await system.note_chat_model(model or system.active_models()["llm"])
        cap = MAX_TOKENS_JSON if json_mode else MAX_TOKENS_TEXT              # bounded: a stalled model can't run for minutes
        llm = agent.get_llm(temperature=0.4, json_mode=json_mode, model=model, num_predict=cap) if model else \
            agent.get_llm(temperature=0.4, json_mode=json_mode, num_predict=cap)
        sys_msg = "You are a patient, accurate tutor helping someone prepare for job interviews."
        return str((await llm.ainvoke([SystemMessage(sys_msg), HumanMessage(prompt)])).content or "")
    except Exception as e:  # noqa: BLE001 - Ollama down, model missing
        raise StudyError(f"The AI model isn't available right now ({type(e).__name__}). Check AI models & setup.") from e


async def _json(prompt: str, model: str | None) -> Any:
    for _ in range(2):
        text = await _invoke(prompt, model, True)
        try:
            return json.loads(text)
        except ValueError:
            m = re.search(r"\{.*\}", text, re.S)
            if m:
                try:
                    return json.loads(m.group(0))
                except ValueError:
                    pass
    raise StudyError("The AI returned something unreadable. Try again.")


async def _text(prompt: str, model: str | None) -> str:
    return await _invoke(prompt, model, False)


# --------------------------------------------------------------------------- user-controlled export / import (no sync)
EXPORT_FORMAT = "abhify-study-export-1"


def export_data() -> dict:
    return {"format": EXPORT_FORMAT, "exported_at": datetime.now().isoformat(timespec="seconds"), "data": load()}


def import_data(body: dict) -> dict:
    """Restores an export (from this or another Abhify, chosen by the user). The current file is kept first as
    study.before-import-<time>.json; nothing is merged silently."""
    d = (body or {}).get("data")
    if (body or {}).get("format") != EXPORT_FORMAT or not isinstance(d, dict) or not isinstance(d.get("courses"), dict):
        raise StudyError("This file isn't an Abhify Study export.")
    d = migrate(d)
    with _lock:
        p = _file()
        if p.exists():
            import shutil
            shutil.copy2(p, p.with_name(f"study.before-import-{int(time.time() * 1000)}.json"))
        save(d)
    n = load()
    return {"courses": len(n["courses"]), "stories": len(n["stories"]), "cards": len(n["cards"])}
