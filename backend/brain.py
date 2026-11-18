"""Next build — desktop side of the Personal AI Brain (same rules as the Android app):

* ``discover(topic)``: real learning resources — live MIT OpenCourseWare (MIT Learn API, MIT's own free/certificate/
  price fields) and OpenStax textbooks, plus the providers' own search pages (incl. Wikibooks, which blocks automated
  requests without contact details). Cost only as the source states
  it, otherwise "Check the provider". Nothing invented; results open on the provider's site.
* ``briefing()``: projects in progress, open issues, AI tasks that failed, MCQ practice, the last saved news (with the time
  it was saved) — from local records only, each item with its source.
* ``search(q)``: one local search over conversations, memories (only when memory is on), projects, AI tasks, knowledge
  files and saved news. No external search provider.
"""
from __future__ import annotations

import time
from typing import Any
from urllib.parse import quote_plus

import httpx

UA = {"User-Agent": "Abhify.AI/2.0 (local study search)"}

PROVIDERS = [
    ("NPTEL", "IIT/IISc video courses", lambda t: "https://nptel.ac.in/courses"),
    ("SWAYAM", "Indian university courses", lambda t: f"https://swayam.gov.in/explorer?searchText={quote_plus(t)}"),
    ("freeCodeCamp", "Coding tutorials and curriculum", lambda t: f"https://www.freecodecamp.org/news/search/?query={quote_plus(t)}"),
    ("Khan Academy", "Maths, science and computing lessons", lambda t: f"https://www.khanacademy.org/search?page_search_query={quote_plus(t)}"),
    ("Coursera", "University courses (free and paid)", lambda t: f"https://www.coursera.org/search?query={quote_plus(t)}"),
    ("edX", "University courses (free and paid)", lambda t: f"https://www.edx.org/search?q={quote_plus(t)}"),
    ("Microsoft Learn", "Official technical training", lambda t: f"https://learn.microsoft.com/en-us/search/?terms={quote_plus(t)}"),
    ("MIT OpenCourseWare", "All OCW material", lambda t: f"https://ocw.mit.edu/search/?q={quote_plus(t)}"),
    ("Wikibooks", "Free open books (CC BY-SA)", lambda t: f"https://en.wikibooks.org/w/index.php?search={quote_plus(t)}"),
    ("YouTube", "Educational videos", lambda t: f"https://www.youtube.com/results?search_query={quote_plus(t + ' course')}"),
    ("MDN Web Docs", "Official web documentation", lambda t: f"https://developer.mozilla.org/en-US/search?q={quote_plus(t)}"),
    ("Python docs", "Official Python documentation", lambda t: f"https://docs.python.org/3/search.html?q={quote_plus(t)}"),
]

_STOP = {"and", "the", "for", "of", "to", "in", "a", "an", "with", "intro", "introduction"}


def _terms(s: str) -> set[str]:
    import re
    return {w for w in re.findall(r"[\w+#]{2,}", s.lower()) if w not in _STOP}


def parse_mit(data: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for r in data.get("results") or []:
        url = r.get("url") or ""
        if not url.startswith("https://") or not (r.get("title") or "").strip():
            continue
        prices, free, cert = r.get("prices") or [], bool(r.get("free")), bool(r.get("certification"))
        cost = ("Free" if free and not prices and not cert else "Free to audit · certificate paid" if free and cert
                else "Paid" if not free and prices else "Check the provider")
        by = (r.get("offered_by") or {}).get("name") or "MIT"
        level = None
        runs = r.get("runs") or []
        if runs and (runs[0] or {}).get("level"):
            level = (runs[0]["level"][0] or {}).get("name")
        out.append({"title": r["title"].strip(), "provider": by, "url": url, "kind": "Course", "cost": cost, "level": level,
                    "license": "CC BY-NC-SA 4.0" if "OpenCourseWare" in by else None, "source": "MIT Learn",
                    "topics": [t.get("name") for t in (r.get("topics") or [])][:3]})
    return out


def parse_openstax(data: dict[str, Any], topic: str) -> list[dict[str, Any]]:
    words = _terms(topic)
    out = []
    for b in data.get("items") or []:
        slug = ((b.get("meta") or {}).get("slug")) or ""
        if b.get("book_state") != "live" or not slug or not (words & _terms(b.get("title") or "")):
            continue
        out.append({"title": b["title"], "provider": "OpenStax", "url": f"https://openstax.org/details/books/{slug}", "kind": "Textbook",
                    "cost": "Free", "level": None, "license": None, "source": "OpenStax", "topics": []})
    return out[:5]


def parse_wikibooks(data: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for x in ((data.get("query") or {}).get("search") or []):
        t = x.get("title") or ""
        if t and "/" not in t:
            out.append({"title": t, "provider": "Wikibooks", "url": "https://en.wikibooks.org/wiki/" + t.replace(" ", "_"), "kind": "Open book",
                        "cost": "Free", "level": None, "license": "CC BY-SA", "source": "Wikibooks", "topics": []})
    return out


async def discover(topic: str, free_only: bool = False) -> dict[str, Any]:
    topic = (topic or "").strip()[:80]
    if len(topic) < 2:
        return {"topic": topic, "results": [], "providers": [], "notes": ["Type a topic of at least 2 characters."]}
    results: list[dict[str, Any]] = []
    notes: list[str] = []
    async with httpx.AsyncClient(timeout=12, headers=UA) as c:
        async def get(name, url, parse):
            try:
                r = await c.get(url)
                r.raise_for_status()
                results.extend(parse(r.json()))
            except Exception as e:                              # noqa: BLE001 - reported per source
                notes.append(f"{name} couldn't be reached ({type(e).__name__}).")
        import asyncio
        await asyncio.gather(
            get("MIT OpenCourseWare", f"https://api.learn.mit.edu/api/v1/learning_resources_search/?q={quote_plus(topic)}&resource_type=course&offered_by=ocw&limit=10", parse_mit),
            get("OpenStax", "https://openstax.org/apps/cms/api/v2/pages/?type=books.Book&fields=title,book_state&limit=250", lambda d: parse_openstax(d, topic)))
    # Wikibooks: Wikimedia rejects automated requests without contact details (2026-11-18) → offered as a provider search link
    seen, uniq = set(), []
    for r in results:
        if r["url"] not in seen and (not free_only or r["cost"] == "Free"):
            seen.add(r["url"]); uniq.append(r)
    providers = [] if free_only else [{"provider": n, "about": a, "url": f(topic),
                                        "note": f"{a} · availability and prices are shown on {n}"} for n, a, f in PROVIDERS]
    return {"topic": topic, "results": uniq, "providers": providers, "notes": notes, "checked_at": int(time.time())}


def briefing(projects: list[dict], tasks: list[dict], mcq_attempts: list[dict], news: dict | None, now: float | None = None) -> dict[str, Any]:
    now = now or time.time()
    import intelligence
    live = [p for p in projects if not p.get("archived")]
    sections = []

    def sec(title, items):
        sections.append({"title": title, "items": items})
    sec("Projects in progress", [{"text": f"{p['name']} — " + ((p.get('status') or {}).get('next') or [(p.get('status') or {}).get('current') or ''])[0],
                                  "source": "project status"} for p in live if (p.get("status") or {}).get("next") or (p.get("status") or {}).get("current")][:3])
    sec("Open issues", [{"text": f"{p['name']}: {(p.get('status') or {}).get('issues')[0]}", "source": "project status"}
                        for p in live if (p.get("status") or {}).get("issues")][:3])
    failed = [t for t in tasks if t.get("status") == "failed" and now - (t.get("completed_at") or t.get("created_at", 0)) / 1000 < 3 * 86400]
    sec("Needs attention", [{"text": f"AI task didn't finish: {t.get('title', '')}", "source": "AI task history"} for t in failed[:2]])
    by_topic: dict[str, list[bool]] = {}
    for a in mcq_attempts:
        by_topic.setdefault(a.get("topic") or "?", []).append(bool(a.get("correct")))
    weak = sorted(((t, sum(v), len(v)) for t, v in by_topic.items() if len(v) >= 5 and sum(v) * 100 // len(v) < 60), key=lambda x: x[1] / x[2])
    sec("Study", [{"text": f"Practise {t}: {c} of {n} correct", "source": "MCQ answers"} for t, c, n in weak[:2]])
    if news and news.get("articles"):
        age = intelligence._age(now - news.get("retrieved_at", 0) / 1000) if news.get("retrieved_at") else "earlier"
        sec(f"Saved news ({age})", [{"text": a.get("title", ""), "source": "last saved copy of News, not live"} for a in news["articles"][:3]])
    return {"sections": [s for s in sections if s["items"]], "empty": all(not s["items"] for s in sections), "built_at": int(now)}


def plan(projects: list[dict], now: float | None = None) -> dict[str, Any]:
    """5.5 desktop daily plan: open issues first, then recorded next actions, most recently updated projects first.
    Desktop projects have no to-do list or due dates, so nothing is ever called overdue here."""
    live = sorted([p for p in projects if not p.get("archived")], key=lambda p: -((p.get("status") or {}).get("updated_at") or 0))
    entries = []
    for p in live:
        st = p.get("status") or {}
        entries += [{"title": f"Resolve: {i}", "project": p["name"], "reason": "open issue recorded in the project"} for i in (st.get("issues") or [])[:2]]
    for p in live:
        st = p.get("status") or {}
        entries += [{"title": n, "project": p["name"], "reason": "next action recorded in the project"} for n in (st.get("next") or [])[:2]]
    return {"entries": entries[:8], "note": "From your projects' recorded issues and next actions (desktop projects have no due dates)."}


def skills(attempts: list[dict], min_answers: int = 5) -> dict[str, Any]:
    """5.5 desktop skill map from MCQ answers, same rules as Android: no level below min_answers, trend needs 10 answers."""
    by: dict[str, list[dict]] = {}
    for a in attempts:
        by.setdefault((a.get("topic") or "").strip().lower(), []).append(a)
    out = []
    for t, l in by.items():
        if not t:
            continue
        n, c = len(l), sum(1 for a in l if a.get("correct"))
        level = "Not enough data yet" if n < min_answers else "Needs work" if c * 100 // n < 60 else "Developing" if c * 100 // n < 80 else "Strong"
        recent = [bool(a.get("correct")) for a in sorted(l, key=lambda a: a.get("at", 0))][-10:]
        trend = None
        if len(recent) == 10:
            last, before = sum(recent[5:]), sum(recent[:5])
            trend = "improving" if last - before >= 2 else "declining" if before - last >= 2 else "steady"
        out.append({"skill": t, "answered": n, "correct": c, "level": level, "accuracy": c * 100 // n if n >= min_answers else None, "trend": trend,
                    "answers_label": f"{n} answer{'' if n == 1 else 's'}"})
    order = {"Needs work": 0, "Developing": 1, "Strong": 2, "Not enough data yet": 3}
    out.sort(key=lambda s: (order[s["level"]], s["skill"]))
    weak = [s for s in out if s["level"] == "Needs work"]
    nxt = ({"skill": weak[0]["skill"], "reason": f"Weakest measured topic: {weak[0]['correct']} of {weak[0]['answered']} correct."} if weak else None)
    return {"skills": out, "next": nxt, "min_answers": min_answers, "learning_path": learning_path(out),
            "revision": revision_plan(out),
            "path_note": "Learning order is general guidance (the same list as the Android app), not personal data."}


# 5.6: general learning order, copied from Android's SkillGraph.PREREQUISITES (skill -> what usually comes first)
PREREQUISITES = {
    "machine learning": ["python", "statistics", "linear algebra"],
    "deep learning": ["machine learning", "linear algebra", "calculus"],
    "generative ai": ["deep learning", "python"],
    "nlp": ["machine learning", "python"],
    "data science": ["python", "statistics", "sql"],
    "statistics": ["probability"],
    "react": ["javascript"],
    "django": ["python"],
    "fastapi": ["python"],
    "mlops": ["machine learning", "docker"],
}


def norm_skill(s: str) -> str:
    """Same as Android's SkillGraph.norm: trimmed, lowercase, single spaces, without 'intro to' / 'basics of'."""
    import re
    s = re.sub(r"\s+", " ", (s or "").strip().lower())
    return re.sub(r"^(intro(duction)? to|basics of) ", "", s)


def learning_path(skills_out: list[dict]) -> list[dict[str, Any]]:
    """Suggested order for the skills you practise that have prerequisites: prerequisites first (topological order),
    each step with your own level and answer count when you have answers for it."""
    mine = {norm_skill(s["skill"]): s for s in skills_out}
    roots = [k for k in mine if PREREQUISITES.get(k)]
    if not roots:
        return []
    order: list[str] = []

    def visit(k: str, seen: tuple = ()):
        if k in order or k in seen:
            return
        for pre in PREREQUISITES.get(k, []):
            visit(pre, seen + (k,))
        order.append(k)
    for r in sorted(roots):
        visit(r)
    steps = []
    for k in order:
        s = mine.get(k)
        before = sorted(x for x in order if k in PREREQUISITES.get(x, []))
        steps.append({"skill": k, "level": s["level"] if s else "Not practised yet", "answered": s["answered"] if s else 0,
                      "answers_label": s["answers_label"] if s else "0 answers", "prerequisites": PREREQUISITES.get(k, []),
                      "comes_before": before, "yours": bool(s)})
    return steps


def revision_plan(skills_out: list[dict]) -> list[dict[str, Any]]:
    """Weak topics first (lowest accuracy), then declining trends; each with the evidence it is based on."""
    weak = sorted([s for s in skills_out if s["level"] == "Needs work"], key=lambda s: (s["correct"] / s["answered"], s["skill"]))
    out = [{"skill": s["skill"], "answered": s["answered"], "answers_label": s["answers_label"],
            "reason": f"Needs work: {s['correct']} of {s['answered']} correct."} for s in weak]
    out += [{"skill": s["skill"], "answered": s["answered"], "answers_label": s["answers_label"],
             "reason": "Declining: fewer correct in your last 5 answers than in the 5 before."}
            for s in skills_out if s["trend"] == "declining" and s["level"] != "Needs work"]
    return out


def search(q: str, conversations: list[dict], messages_hits: list[dict], memories: list[dict] | None, projects: list[dict],
           tasks: list[dict], knowledge_files: list[str], news_articles: list[dict]) -> list[dict[str, Any]]:
    words = [w for w in (q or "").lower().split() if len(w) >= 2]
    if not words:
        return []

    def hit(*parts):
        text = " ".join(p for p in parts if p).lower()
        return all(w in text for w in words)
    out = []
    out += [{"type": "Conversation", "title": c.get("title") or "Chat", "snippet": "", "ref": c["id"], "at": c.get("updated_at")}
            for c in conversations if hit(c.get("title"))]
    out += [{"type": "Conversation", "title": m.get("title") or "Chat", "snippet": (m.get("content") or "")[:160], "ref": m["conversation_id"], "at": m.get("created_at")}
            for m in messages_hits if m["conversation_id"] not in {o["ref"] for o in out if o["type"] == "Conversation"}]
    if memories is not None:                                    # None = memory is off: never searched
        out += [{"type": "Memory", "title": (m.get("category") or "memory").title(), "snippet": m.get("content", "")[:160], "ref": m.get("id")}
                for m in memories if hit(m.get("content"))]
    for p in projects:
        st = p.get("status") or {}
        if hit(p.get("name"), p.get("instructions"), st.get("objective"), st.get("current"), " ".join(st.get("next") or []), " ".join(st.get("issues") or [])):
            out.append({"type": "Project", "title": p["name"], "snippet": ((st.get("next") or [st.get("current") or ""])[0])[:160], "ref": p["id"]})
    out += [{"type": "AI task", "title": t.get("title", ""), "snippet": t.get("status", ""), "ref": t.get("id")} for t in tasks if hit(t.get("title"), t.get("request"))][:10]
    out += [{"type": "Knowledge file", "title": n, "snippet": "", "ref": n} for n in knowledge_files if hit(n)][:10]
    out += [{"type": "Saved news", "title": a.get("title", ""), "snippet": a.get("publisher", ""), "ref": a.get("url")} for a in news_articles if hit(a.get("title"), a.get("summary"))][:10]
    return out[:60]
