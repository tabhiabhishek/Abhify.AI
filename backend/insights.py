"""5.6 desktop insights: Universal Search 2.0, the read-only project status page, Desktop Agent 2.0 (typed tools,
read-only planning workflows, approved reversible actions with verification and undo), smarter duplicate detection
and the morning / evening / weekly briefings.

Rules (same as the rest of the desktop brain):
* Records only. Desktop projects have no to-do list and no due dates, so nothing here invents progress, deadlines or
  priorities; every derived line names the record it came from.
* Memories are searched only when memory is on (the caller passes None otherwise).
* No model call and no network: everything is plain text processing over local records.
* The agent never deletes and never runs SQL or shell commands. It can only link a document to a project and add a
  next action, and only after the user approved that exact proposal; both can be undone.
* Logs and the audit file (<data>/data/agent_runs.json) hold ids, tool names, counts and timestamps only.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from datetime import datetime, timezone
from typing import Any

log = logging.getLogger(__name__)
DAY = 86400

# =========================================================================== text normalisation (duplicates, terms)
STOPWORDS = {
    "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "by", "from", "into", "onto", "is", "are",
    "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those", "as", "if", "then", "than", "so", "do",
    "does", "did", "done", "can", "could", "should", "would", "will", "shall", "may", "might", "must", "we", "i", "you", "our",
    "my", "your", "their", "they", "he", "she", "them", "up", "out", "about", "all", "any", "some", "more", "also", "again",
    "new", "get", "make", "use", "using", "via", "etc", "not", "no", "yes",
}


def stem(w: str) -> str:
    """Light suffix stemming (-ing / -ed / -es / -s, then a final e and a doubled consonant): enough for 'fixes',
    'fixed', 'fixing' and 'fix' (or 'issue' and 'issues') to meet; not a linguistic stemmer."""
    if len(w) <= 3:
        return w
    if w.endswith("ing") and len(w) - 3 >= 3:
        w = w[:-3]
    elif w.endswith("ed") and len(w) - 2 >= 3:
        w = w[:-2]
    elif w.endswith("es") and len(w) - 2 >= 3 and re.search(r"(s|x|z|ch|sh)$", w[:-2]):
        w = w[:-2]
    elif w.endswith("s") and not w.endswith("ss"):
        w = w[:-1]
    if len(w) >= 4 and w[-1] == w[-2] and w[-1] not in "lsz" and w[-1] not in "aeiou":
        w = w[:-1]                                                    # running -> runn -> run
    if len(w) >= 4 and w.endswith("e"):
        w = w[:-1]                                                    # update / updated -> updat
    return w


def tokens(text: str) -> list[str]:
    """Lowercase, punctuation stripped, stopwords dropped, lightly stemmed (order kept)."""
    words = re.findall(r"[a-z0-9+#]+", (text or "").lower())
    return [stem(w) for w in words if w not in STOPWORDS]


def normalise(text: str) -> str:
    return " ".join(tokens(text))


def jaccard(a: set[str], b: set[str]) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


def similar(a: str, b: str, threshold: float = 0.6) -> tuple[bool, float, str]:
    """(match, score, why): token-set Jaccard >= threshold, or one normalised text contains the other (min 2 tokens)."""
    ta, tb = tokens(a), tokens(b)
    if not ta or not tb:
        return False, 0.0, ""
    score = jaccard(set(ta), set(tb))
    if score >= threshold:
        return True, round(score, 2), f"{round(score * 100)}% of the words match"
    short, long_ = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    if len(short) >= 2 and f" {' '.join(short)} " in f" {' '.join(long_)} ":
        return True, round(max(score, 0.6), 2), "one contains the other"
    return False, round(score, 2), ""


def find_duplicates(items: list[dict[str, Any]], threshold: float = 0.6) -> list[dict[str, Any]]:
    """items: [{text, state: "open"|"done", source}] of ONE project. Two open items that match -> "duplicate";
    a done item that matches an open one -> "already_done" ("already done?"). Suggestions only: nothing is merged."""
    out = []
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            a, b = items[i], items[j]
            if a["state"] == "done" and b["state"] == "done":
                continue
            ok, score, why = similar(a["text"], b["text"], threshold)
            if not ok:
                continue
            if a["state"] != b["state"]:
                done, open_ = (a, b) if a["state"] == "done" else (b, a)
                out.append({"kind": "already_done", "label": "Already done?", "open": open_, "done": done, "score": score,
                            "reason": f"Matches a completed item ({why}); it may already be done."})
            else:
                out.append({"kind": "duplicate", "label": "Possible duplicate", "a": a, "b": b, "score": score, "reason": why})
    return out


def project_items(p: dict[str, Any], tasks: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    """The comparable items of one project: recorded next actions and issues (open), milestones (done) and the
    project's AI tasks (completed = done)."""
    st = p.get("status") or {}
    items = [{"text": t, "state": "open", "source": "next action (project status)"} for t in st.get("next") or []]
    items += [{"text": t, "state": "open", "source": "open issue (project status)"} for t in st.get("issues") or []]
    items += [{"text": t, "state": "done", "source": "completed milestone (project status)"} for t in st.get("milestones") or []]
    for t in tasks or []:
        if t.get("project_id") == p.get("id") and t.get("title"):
            items.append({"text": t["title"], "state": "done" if t.get("status") == "completed" else "open",
                          "source": f"AI task ({t.get('status', '')})"})
    return items


# =========================================================================== timestamps (records use several units)
def ts(v: Any) -> float | None:
    """Epoch seconds from ms / seconds numbers or SQLite 'YYYY-MM-DD HH:MM:SS' (UTC) text; None if unknown."""
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)):
        if v <= 0:
            return None
        return v / 1000 if v > 1e11 else float(v)
    try:
        s = str(v).strip().replace(" ", "T")
        d = datetime.fromisoformat(s.replace("Z", "+00:00"))
        return (d if d.tzinfo else d.replace(tzinfo=timezone.utc)).timestamp()
    except ValueError:
        return None


def age(seconds: float) -> str:
    import intelligence
    return intelligence._age(max(0.0, seconds))


def local_midnight(now: float) -> float:
    t = time.localtime(now)
    return time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 0, 0, 0, 0, 0, -1))


# =========================================================================== Universal Search 2.0
TYPES = {   # key -> (label shown, navigation tab)
    "conversation": ("Conversation", "chat"),
    "memory": ("Memory", "memory"),
    "project": ("Project", "projects"),
    "task": ("AI task", "tasks"),
    "document": ("Knowledge file", "knowledge"),
    "news": ("Saved news", "news"),
    "study": ("Study", "mcq"),
}
LABEL_TO_KEY = {v[0]: k for k, v in TYPES.items()}
SOURCE = {"conversation": "chat history", "memory": "personal memory", "project": "project record", "task": "AI task history",
          "document": "knowledge folder (file name)", "news": "last saved copy of News", "study": "MCQ answers"}


def parse_types(raw: str | None) -> set[str] | None:
    if not raw:
        return None
    out = set()
    for t in raw.split(","):
        t = t.strip()
        t = LABEL_TO_KEY.get(t, t.lower())
        if t in TYPES:
            out.add(t)
    return out or None


def universal_search(q: str, *, conversations: list[dict], messages_hits: list[dict], memories: list[dict] | None,
                     projects: list[dict], tasks: list[dict], knowledge_files: list[str], news_articles: list[dict],
                     mcq_attempts: list[dict] | None = None, types: set[str] | None = None, days: int | None = None,
                     project: str | None = None, sort: str = "relevance", now: float | None = None,
                     per_group: int = 10, limit: int = 60) -> dict[str, Any]:
    """One local search with filters. Every word must appear (same rule as before); relevance favours title hits.
    `project` restricts to that project's record, chats, documents and AI tasks (memories, news and study have no
    project, so they are left out). With `days`, items without a date are left out."""
    now = now or time.time()
    words = [w for w in (q or "").lower().split() if len(w) >= 2]
    filters = {"types": sorted(types) if types else None, "days": days, "project": project, "sort": sort}
    if not words:
        return {"results": [], "groups": [], "total": 0, "filters": filters}
    prj = next((p for p in projects if p.get("id") == project), None) if project else None
    if project and not prj:
        return {"results": [], "groups": [], "total": 0, "filters": filters, "note": "That project doesn't exist any more."}

    def score(title: str, body: str = "") -> int | None:
        t, b = (title or "").lower(), (body or "").lower()
        if not all(w in t or w in b for w in words):
            return None
        return sum(3 if w in t else 1 for w in words) + (2 if " ".join(words) in t else 0)

    res: list[dict[str, Any]] = []

    def add(key: str, title: str, snippet: str, ref: Any, at: Any, s: int, target_id: Any = None, **extra):
        label, tab = TYPES[key]
        res.append({"type": label, "type_key": key, "title": title, "snippet": (snippet or "")[:160], "ref": ref,
                    "at": ts(at), "score": s, "source": SOURCE[key], "target": {"tab": tab, "id": target_id if target_id is not None else ref}, **extra})

    allowed_convs = set(prj.get("conversations") or []) if prj else None
    seen_c = set()
    for c in conversations:
        if allowed_convs is not None and c["id"] not in allowed_convs:
            continue
        s = score(c.get("title") or "")
        if s is not None:
            seen_c.add(c["id"])
            add("conversation", c.get("title") or "Chat", "", c["id"], c.get("updated_at"), s)
    for m in messages_hits:
        cid = m["conversation_id"]
        if cid in seen_c or (allowed_convs is not None and cid not in allowed_convs):
            continue
        seen_c.add(cid)
        add("conversation", m.get("title") or "Chat", m.get("content") or "", cid, m.get("created_at"),
            (score(m.get("title") or "", m.get("content") or "") or 1))
    if memories is not None and not prj:                   # None = memory is off: never searched
        for m in memories:
            s = score("", m.get("content") or "")
            if s is not None:
                add("memory", (m.get("category") or "memory").title(), m.get("content") or "", m.get("id"), m.get("created_at"), s)
    for p in ([prj] if prj else projects):
        st = p.get("status") or {}
        body = " ".join([p.get("instructions") or "", st.get("objective") or "", st.get("current") or "",
                         " ".join(st.get("next") or []), " ".join(st.get("issues") or []), " ".join(st.get("milestones") or []),
                         " ".join(st.get("decisions") or [])])
        s = score(p.get("name") or "", body)
        if s is not None:
            add("project", p["name"], ((st.get("next") or [st.get("current") or ""])[0]), p["id"],
                st.get("updated_at") or p.get("updated_at"), s, archived=bool(p.get("archived")))
    for t in tasks:
        if prj and t.get("project_id") != prj["id"]:
            continue
        s = score(t.get("title") or "", t.get("request") or "")
        if s is not None:
            add("task", t.get("title", ""), t.get("status", ""), t.get("id"), t.get("completed_at") or t.get("created_at"), s)
    docs = [n for n in knowledge_files if not prj or n in (prj.get("documents") or [])]
    for n in docs:
        s = score(n)
        if s is not None:
            add("document", n, "linked to this project" if prj else "", n, None, s)
    if not prj:
        for a in news_articles:
            s = score(a.get("title") or "", a.get("summary") or "")
            if s is not None:
                add("news", a.get("title", ""), a.get("publisher", ""), a.get("url"), a.get("published_at") or a.get("retrieved_at"), s)
        by_topic: dict[str, list[dict]] = {}
        for a in mcq_attempts or []:
            if (a.get("topic") or "").strip():
                by_topic.setdefault(a["topic"].strip(), []).append(a)
        for topic, l in by_topic.items():
            s = score(topic)
            if s is not None:
                c = sum(1 for a in l if a.get("correct"))
                add("study", topic, f"{len(l)} answers · {c} correct", topic, max((a.get("at") or 0) for a in l), s, target_id=None)
    if types:
        res = [r for r in res if r["type_key"] in types]
    if days:
        since = now - days * DAY
        res = [r for r in res if r["at"] is not None and r["at"] >= since]
    if sort == "recent":
        res.sort(key=lambda r: (r["at"] or 0, r["score"]), reverse=True)
    else:
        res.sort(key=lambda r: (r["score"], r["at"] or 0), reverse=True)
    groups = []
    for key in TYPES:
        g = [r for r in res if r["type_key"] == key]
        if g:
            groups.append({"type": TYPES[key][0], "key": key, "count": len(g), "results": g[:per_group]})
    flat = [r for g in groups for r in g["results"]]
    flat.sort(key=(lambda r: (r["at"] or 0, r["score"])) if sort == "recent" else (lambda r: (r["score"], r["at"] or 0)), reverse=True)
    return {"results": flat[:limit], "groups": groups, "total": len(res), "filters": filters}


# =========================================================================== read-only project status page
FORBIDDEN_OVERVIEW_KEYS = {"due", "due_date", "deadline", "priority", "progress", "percent", "eta"}


def project_overview(p: dict[str, Any], conversations: list[dict], tasks: list[dict], known_docs: list[str] | set[str],
                     now: float | None = None) -> dict[str, Any]:
    """Everything shown on the Status overview page, from the project's records only. Read-only: no edit fields."""
    now = now or time.time()
    st = p.get("status") or {}
    recorded = any(st.get(k) for k in ("objective", "stack", "current", "milestones", "issues", "next", "decisions"))
    upd = st.get("updated_at")
    known = set(known_docs)
    convs = [c for c in conversations if c["id"] in set(p.get("conversations") or [])]
    last_conv = max((ts(c.get("updated_at")) or 0 for c in convs), default=0) or None
    ptasks = sorted([t for t in tasks if t.get("project_id") == p["id"]], key=lambda t: t.get("created_at") or 0, reverse=True)
    counts: dict[str, int] = {}
    for t in ptasks:
        counts[t.get("status", "?")] = counts.get(t.get("status", "?"), 0) + 1

    def lines(key: str, src: str) -> list[dict[str, str]]:
        return [{"text": x, "source": src} for x in st.get(key) or []]
    actions = [{"text": f"Resolve: {i}", "source": "open issue (recorded)"} for i in st.get("issues") or []]
    actions += [{"text": n, "source": "next action (recorded)"} for n in st.get("next") or []]
    instr = (p.get("instructions") or "").strip()
    return {
        "id": p["id"], "name": p["name"], "archived": bool(p.get("archived")), "read_only": True,
        "instructions_excerpt": (instr[:300] + ("…" if len(instr) > 300 else "")) if instr else None,
        "status": {"recorded": recorded, "updated_at": upd, "age": age(now - upd) if upd else None,
                   "objective": st.get("objective") or None, "stack": st.get("stack") or None, "current": st.get("current") or None},
        "milestones": lines("milestones", "completed milestone (recorded)"),
        "issues": lines("issues", "open issue / blocker (recorded)"),
        "decisions": lines("decisions", "decision (recorded)"),
        "next": lines("next", "next action (recorded)"),
        "documents": [{"name": n, "indexed": n in known} for n in p.get("documents") or []],
        "activity": {"conversations": len(convs), "conversations_last_updated": last_conv,
                     "conversations_age": age(now - last_conv) if last_conv else None,
                     "tasks": [{"id": t["id"], "title": t.get("title", ""), "status": t.get("status"), "created_at": t.get("created_at"),
                                "completed_at": t.get("completed_at")} for t in ptasks[:10]],
                     "task_counts": counts},
        "next_possible_actions": actions,
        "note": "Read-only. Built from what you recorded in this project (no due dates or progress are invented).",
    }


# =========================================================================== Desktop Agent 2.0: typed tool registry
READ, REVERSIBLE, SENSITIVE = "READ", "REVERSIBLE", "SENSITIVE"
TOOLS: dict[str, dict[str, Any]] = {
    "project.read": {"kind": READ, "args": {"project_id": 40}, "about": "Read a project's recorded status, documents and AI tasks"},
    "project.summary": {"kind": READ, "args": {"project_id": 40}, "about": "Summarise recorded status, work, blockers and possible duplicates"},
    "docs.related": {"kind": READ, "args": {"project_id": 40}, "about": "Find knowledge documents sharing terms with a project"},
    "duplicates.find": {"kind": READ, "args": {"project_id": 40}, "about": "Find possible duplicate or already-done items in a project"},
    "knowledge.list": {"kind": READ, "args": {}, "about": "List the document names in the knowledge index"},
    "doc.link": {"kind": REVERSIBLE, "args": {"project_id": 40, "document": 300}, "about": "Link a knowledge document to a project (a reference; nothing is copied)"},
    "next.add": {"kind": REVERSIBLE, "args": {"project_id": 40, "text": 400}, "about": "Add a next action to a project's status"},
    "data.delete": {"kind": SENSITIVE, "args": {}, "about": "Delete chats, memories, documents or projects"},
    "privacy.change": {"kind": SENSITIVE, "args": {}, "about": "Change memory, history or companion access settings"},
}


def validate(tool: str, args: dict[str, Any] | None, approved: bool) -> str | None:
    """None when the call may run, else why not. Unknown tools, SENSITIVE tools (always), REVERSIBLE tools without
    approval, missing / unknown / oversized arguments are refused here, in code."""
    spec = TOOLS.get(tool)
    if not spec:
        return f'"{tool}" is not a registered tool.'
    if spec["kind"] == SENSITIVE:
        return f"{spec['about']}: never done by the agent; use its own confirmation screen."
    if spec["kind"] == REVERSIBLE and not approved:
        return f"{spec['about']}: needs your approval first."
    args = args or {}
    for name, maxlen in spec["args"].items():
        v = args.get(name)
        if v is None or not str(v).strip():
            return f"Missing {name}."
        if len(str(v)) > maxlen:
            return f"{name} is too long."
        if any(ord(ch) < 32 and ch != "\n" for ch in str(v)):
            return f"{name} has characters that can't be used."
    extra = [k for k in args if k not in spec["args"]]
    if extra:
        return f'Unknown input "{extra[0]}" for {tool}.'
    return None


# --------------------------------------------------------------------------- workflows (read-only planning)
WORKFLOWS = {"status": "Status & blockers", "priorities": "Suggest priorities", "docs": "Related documents",
             "unfinished": "Unfinished work", "duplicates": "Possible duplicates"}
BLOCK_RX = re.compile(r"\b(waiting (?:for|on)|blocked (?:on|by)|blocker|depends on|dependent on|stuck|can't|cannot|until)\b", re.I)
URGENT_RX = re.compile(r"\b(urgent|asap|critical|first|immediately|before (?:release|launch|ship\w*))\b", re.I)
OPEN_RX = re.compile(r"\b(todo|to-do|to do|tbd|pending|unfinished|not yet|still need\w*|need(?:s)? to|wip|in progress|incomplete|"
                     r"remaining|left to|fix|finish|implement|missing|later)\b", re.I)


def doc_terms(name: str) -> set[str]:
    base = re.sub(r"\.[A-Za-z0-9]{1,5}$", "", name or "")
    base = re.sub(r"([a-z])([A-Z])", r"\1 \2", base)
    return {t for t in tokens(base.replace("_", " ").replace("-", " ").replace(".", " ")) if len(t) >= 3 and not t.isdigit()}


def project_terms(p: dict[str, Any]) -> set[str]:
    st = p.get("status") or {}
    text = " ".join([p.get("name") or "", p.get("instructions") or "", st.get("objective") or "", st.get("stack") or "",
                     st.get("current") or ""] + [x for k in ("milestones", "issues", "next", "decisions") for x in st.get(k) or []])
    return {t for t in tokens(text.replace("_", " ").replace("-", " ")) if len(t) >= 3 and not t.isdigit()}


def related_documents(p: dict[str, Any], doc_names: list[str], min_shared: int = 2) -> list[dict[str, Any]]:
    """Knowledge documents whose file name shares >= min_shared normalised terms with the project's name, instructions
    and status; documents already linked are skipped. Sorted by shared terms."""
    terms, linked = project_terms(p), set(p.get("documents") or [])
    out = []
    for n in doc_names:
        if n in linked:
            continue
        shared = sorted(terms & doc_terms(n))
        if len(shared) >= min_shared:
            out.append({"document": n, "shared": shared, "score": len(shared)})
    out.sort(key=lambda d: (-d["score"], d["document"]))
    return out


def _line(text: str, source: str) -> dict[str, str]:
    return {"text": text, "source": source}


def run_workflow(workflow: str, p: dict[str, Any], tasks: list[dict], doc_names: list[str], now: float | None = None) -> dict[str, Any]:
    """Read-only: sections of lines (each with its source) and proposals (REVERSIBLE tool calls the user may approve)."""
    now = now or time.time()
    st = p.get("status") or {}
    sections: list[dict[str, Any]] = []
    proposals: list[dict[str, Any]] = []
    nexts = st.get("next") or []

    def propose(tool: str, args: dict[str, str], label: str, source: str):
        if validate(tool, args, approved=True) is None:
            proposals.append({"id": f"i{len(proposals) + 1}", "tool": tool, "kind": TOOLS[tool]["kind"], "args": args,
                              "label": label, "source": source})

    def already_next(text: str) -> bool:
        return any(similar(text, n)[0] for n in nexts)
    dups = find_duplicates(project_items(p, tasks))
    if workflow == "status":
        rec = []
        if st.get("objective"):
            rec.append(_line(f"Objective: {st['objective']}", "project status · objective"))
        if st.get("current"):
            rec.append(_line(f"Current: {st['current']}" + (f" (recorded {age(now - st['updated_at'])})" if st.get("updated_at") else ""),
                             "project status · current"))
        rec += [_line(f"Done: {m}", "project status · completed milestones") for m in st.get("milestones") or []]
        rec += [_line(f"Decision: {d}", "project status · decisions") for d in st.get("decisions") or []]
        work = [_line(f"Next: {n}", "project status · next actions") for n in nexts]
        ptasks = [t for t in tasks if t.get("project_id") == p["id"]]
        work += [_line(f"AI task: {t.get('title', '')} ({t.get('status')})", "AI task history") for t in ptasks[:8]]
        blockers = [_line(i, "project status · open issues") for i in st.get("issues") or []]
        dup_lines = [_line(f"{d['label']} \"{(d.get('open') or d.get('a'))['text']}\" ~ \"{(d.get('done') or d.get('b'))['text']}\"",
                           f"{(d.get('open') or d.get('a'))['source']} + {(d.get('done') or d.get('b'))['source']}") for d in dups]
        known = set(doc_names)
        docs = [_line(n + ("" if n in known else " (not in the knowledge index any more)"), "project documents") for n in p.get("documents") or []]
        for title, ls in (("Recorded", rec), ("Work", work), ("Blockers", blockers), ("Possible duplicates", dup_lines), ("Documents", docs)):
            if ls:
                sections.append({"title": title, "lines": ls})
    elif workflow == "priorities":
        flagged = []
        for key, src in (("issues", "open issue"), ("next", "next action")):
            for x in st.get(key) or []:
                m = BLOCK_RX.search(x)
                u = URGENT_RX.search(x)
                if m:
                    flagged.append((0, _line(f"Unblock first: {x}", f"{src} (recorded) · wording \"{m.group(0)}\"")))
                    if key == "issues" and not already_next(x):
                        propose("next.add", {"project_id": p["id"], "text": f"Follow up: {x}"[:400]}, f"Add next action \"Follow up: {x}\"",
                                f"open issue with the wording \"{m.group(0)}\"")
                elif u:
                    flagged.append((1, _line(f"Marked important: {x}", f"{src} (recorded) · wording \"{u.group(0)}\"")))
        if flagged:
            sections.append({"title": "Suggested from wording", "lines": [l for _, l in sorted(flagged, key=lambda f: f[0])]})
        rest = [x for x in (st.get("issues") or []) + nexts if not BLOCK_RX.search(x) and not URGENT_RX.search(x)]
        if rest:
            sections.append({"title": "No priority wording (kept in recorded order)", "lines": [_line(x, "project status") for x in rest]})
        sections.append({"title": "Note", "lines": [_line("Desktop projects have no due dates, so priorities are suggested only from the words "
                                                          "you used (for example \"waiting for\", \"blocked on\", \"urgent\").", "Abhify rules")]})
    elif workflow == "docs":
        rel = related_documents(p, doc_names)
        if rel:
            sections.append({"title": "Related documents", "lines": [_line(d["document"], "shares terms: " + ", ".join(d["shared"][:6])) for d in rel[:10]]})
        for d in rel[:10]:
            propose("doc.link", {"project_id": p["id"], "document": d["document"]}, f"Link \"{d['document']}\" to this project",
                    "shares terms: " + ", ".join(d["shared"][:6]))
    elif workflow == "unfinished":
        found = []
        instr_lines = [l.strip().lstrip("-•*").strip() for l in (p.get("instructions") or "").splitlines()]
        for l in instr_lines:
            if l and OPEN_RX.search(l):
                found.append((l, "project instructions"))
        if st.get("current") and OPEN_RX.search(st["current"]):
            found.append((st["current"], "project status · current"))
        for key, src in (("issues", "project status · open issues"), ("next", "project status · next actions")):
            found += [(x, src) for x in st.get(key) or [] if OPEN_RX.search(x)]
        if found:
            sections.append({"title": "Looks like open work (quoted as written)", "lines": [_line(f"\"{t}\"", s) for t, s in found]})
        for t, s in found:
            if s in ("project instructions", "project status · current") and not already_next(t):
                propose("next.add", {"project_id": p["id"], "text": t[:400]}, f"Add next action \"{t[:120]}\"", f"quoted from {s}")
    elif workflow == "duplicates":
        if dups:
            sections.append({"title": "Possible duplicates and already-done items", "lines": [
                _line(f"{d['label']} \"{(d.get('open') or d.get('a'))['text']}\" ~ \"{(d.get('done') or d.get('b'))['text']}\" ({d['reason']})",
                      f"{(d.get('open') or d.get('a'))['source']} + {(d.get('done') or d.get('b'))['source']}") for d in dups]})
        sections.append({"title": "Note", "lines": [_line("Suggestions only: nothing is merged or removed.", "Abhify rules")]})
    else:
        raise ValueError("Unknown workflow.")
    return {"workflow": workflow, "title": WORKFLOWS[workflow], "sections": sections, "proposals": proposals,
            "duplicates": dups if workflow in ("status", "duplicates") else [], "changed": False}


# --------------------------------------------------------------------------- runs: plan -> apply (approved) -> verify -> undo
_RUNS: dict[str, dict[str, Any]] = {}       # in memory: proposals and what was applied (content stays in this process)
_runs_lock = threading.Lock()
MAX_RUNS = 50


def _audit_file():
    import local_file_service as lfs
    return lfs.data_dir() / "agent_runs.json"


def audit_load() -> list[dict[str, Any]]:
    try:
        return json.loads(_audit_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def _audit(entry: dict[str, Any]) -> None:
    """ids, tool names, counts and timestamps only (no names, document titles or text)."""
    try:
        f = _audit_file()
        f.parent.mkdir(parents=True, exist_ok=True)
        items = (audit_load() + [{**entry, "at": int(time.time() * 1000)}])[-200:]
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(items), encoding="utf-8")
        tmp.replace(f)
    except OSError:
        log.warning("Agent audit could not be written")


def plan(project_id: str, workflow: str, tasks: list[dict], doc_names: list[str]) -> dict[str, Any]:
    import projects
    if workflow not in WORKFLOWS:
        raise ValueError("Unknown workflow.")
    p = projects.get(project_id)
    if not p:
        raise KeyError(project_id)
    err = validate("project.summary", {"project_id": project_id}, approved=False)
    if err:
        raise ValueError(err)
    out = run_workflow(workflow, p, tasks, doc_names)
    rid = uuid.uuid4().hex[:12]
    with _runs_lock:
        _RUNS[rid] = {"project_id": project_id, "workflow": workflow, "proposals": {x["id"]: x for x in out["proposals"]},
                      "applied": [], "undone": False, "created": time.time()}
        while len(_RUNS) > MAX_RUNS:
            _RUNS.pop(next(iter(_RUNS)))
    _audit({"run": rid, "event": "plan", "workflow": workflow, "project": project_id,
            "tools": sorted({x["tool"] for x in out["proposals"]}), "proposals": len(out["proposals"])})
    log.info("Agent run %s planned (%s, %d proposals)", rid, workflow, len(out["proposals"]))
    return {"run_id": rid, "project_id": project_id, **out}


def _get_run(run_id: str) -> dict[str, Any]:
    r = _RUNS.get(run_id)
    if not r:
        raise LookupError("This plan is no longer available (plans are kept until the app restarts). Make a new plan.")
    return r


def apply(project_id: str, run_id: str, item_ids: list[str], approved: bool) -> dict[str, Any]:
    """Runs only the approved REVERSIBLE proposals of this run, then re-reads the project and verifies each change."""
    import projects
    if not approved:
        raise PermissionError("Nothing was changed: approve the selected actions first.")
    with _runs_lock:
        r = _get_run(run_id)
        if r["project_id"] != project_id:
            raise ValueError("This plan belongs to another project.")
        if r["undone"]:
            raise ValueError("This plan was undone. Make a new plan.")
        results = []
        for iid in dict.fromkeys(item_ids):
            prop = r["proposals"].get(iid)
            if not prop:
                results.append({"id": iid, "applied": False, "verified": False, "note": "Not a proposal of this plan."})
                continue
            if any(a["id"] == iid for a in r["applied"]):
                results.append({"id": iid, "tool": prop["tool"], "applied": False, "verified": True, "note": "Already applied in this run."})
                continue
            err = validate(prop["tool"], prop["args"], approved=True)
            if err:
                results.append({"id": iid, "tool": prop["tool"], "applied": False, "verified": False, "note": err})
                continue
            p = projects.get(project_id)
            if not p:
                raise KeyError(project_id)
            try:
                if prop["tool"] == "doc.link":
                    doc = prop["args"]["document"]
                    if doc in p.get("documents") or []:
                        results.append({"id": iid, "tool": "doc.link", "applied": False, "verified": True, "note": "Already linked."})
                        continue
                    projects.add_documents(project_id, [doc])
                    r["applied"].append({"id": iid, "tool": "doc.link", "document": doc})
                elif prop["tool"] == "next.add":
                    text = prop["args"]["text"]
                    st = dict(p.get("status") or {})
                    nexts = list(st.get("next") or [])
                    if text in nexts:
                        results.append({"id": iid, "tool": "next.add", "applied": False, "verified": True, "note": "Already a next action."})
                        continue
                    import intelligence
                    if len(nexts) >= intelligence.MAX_ITEMS:
                        results.append({"id": iid, "tool": "next.add", "applied": False, "verified": False,
                                        "note": f"The next-actions list is full ({intelligence.MAX_ITEMS})."})
                        continue
                    st["next"] = nexts + [text]
                    projects.update(project_id, status=st)
                    r["applied"].append({"id": iid, "tool": "next.add", "text": text})
            except ValueError as e:
                results.append({"id": iid, "tool": prop["tool"], "applied": False, "verified": False, "note": str(e)})
                continue
            results.append({"id": iid, "tool": prop["tool"], "applied": True, "verified": False})
        after = projects.get(project_id) or {}
        for res in results:                                          # verification: read the stored record again
            if not res.get("applied"):
                continue
            a = next(x for x in r["applied"] if x["id"] == res["id"])
            res["verified"] = (a["document"] in (after.get("documents") or [])) if a["tool"] == "doc.link" \
                else (a["text"] in ((after.get("status") or {}).get("next") or []))
            res["note"] = "Verified in the saved project." if res["verified"] else "Could not be verified in the saved project."
    n_ok = sum(1 for x in results if x.get("applied"))
    _audit({"run": run_id, "event": "apply", "project": project_id, "tools": sorted({x.get("tool") for x in results if x.get("applied")}),
            "applied": n_ok, "verified": sum(1 for x in results if x.get("applied") and x["verified"]), "refused": len(results) - n_ok})
    log.info("Agent run %s applied %d action(s)", run_id, n_ok)
    return {"run_id": run_id, "results": results, "applied": n_ok, "can_undo": bool(_RUNS[run_id]["applied"])}


def undo(run_id: str) -> dict[str, Any]:
    """Reverts exactly what this run applied (unlinks the documents it linked, removes the next actions it added)."""
    import projects
    with _runs_lock:
        r = _get_run(run_id)
        if r["undone"]:
            raise ValueError("This run was already undone.")
        pid, results = r["project_id"], []
        for a in reversed(r["applied"]):
            p = projects.get(pid)
            if not p:
                raise KeyError(pid)
            if a["tool"] == "doc.link":
                if a["document"] in p.get("documents") or []:
                    projects.remove_document(pid, a["document"])
            elif a["tool"] == "next.add":
                st = dict(p.get("status") or {})
                nexts = list(st.get("next") or [])
                if a["text"] in nexts:
                    nexts.remove(a["text"])
                    st["next"] = nexts
                    projects.update(pid, status=st)
            results.append({"id": a["id"], "tool": a["tool"]})
        after = projects.get(pid) or {}
        for res, a in zip(results, reversed(r["applied"])):
            res["verified"] = (a["document"] not in (after.get("documents") or [])) if a["tool"] == "doc.link" \
                else (a["text"] not in ((after.get("status") or {}).get("next") or []))
        r["undone"] = True
    _audit({"run": run_id, "event": "undo", "project": pid, "reverted": len(results), "verified": sum(1 for x in results if x["verified"])})
    log.info("Agent run %s undone (%d change(s))", run_id, len(results))
    return {"run_id": run_id, "reverted": results, "undone": True}


# =========================================================================== briefing kinds
def briefing(kind: str, projects: list[dict], tasks: list[dict], attempts: list[dict], news: dict | None,
             conversations: list[dict] | None = None, now: float | None = None) -> dict[str, Any]:
    """now (default, unchanged), morning (today's plan + the briefing), evening (what changed today) and weekly (last 7
    days). Verified records only; empty sections are left out."""
    import brain
    now = now or time.time()
    if kind == "now":
        return {**brain.briefing(projects, tasks, attempts, news, now), "kind": "now"}
    live = [p for p in projects if not p.get("archived")]
    sections: list[dict[str, Any]] = []

    def sec(title, items):
        if items:
            sections.append({"title": title, "items": items})
    if kind == "morning":
        pl = brain.plan(projects, now)
        sec("Today's plan", [{"text": f"{e['title']} ({e['project']})", "source": e["reason"]} for e in pl["entries"][:5]])
        sections.extend(s for s in brain.briefing(projects, tasks, attempts, news, now)["sections"] if s["title"] != "Projects in progress")
    elif kind in ("evening", "weekly"):
        since = local_midnight(now) if kind == "evening" else now - 7 * DAY
        span = "today" if kind == "evening" else "in the last 7 days"
        upd = [p for p in live if ((p.get("status") or {}).get("updated_at") or 0) >= since]
        done = [t for t in tasks if t.get("status") == "completed" and (ts(t.get("completed_at")) or 0) >= since]
        failed = [t for t in tasks if t.get("status") == "failed" and (ts(t.get("completed_at") or t.get("created_at")) or 0) >= since]
        ans = [a for a in attempts if (ts(a.get("at")) or 0) >= since]
        if kind == "weekly":
            convs = [c for c in conversations or [] if (ts(c.get("updated_at")) or 0) >= since]
            counts = [("project statuses updated", len(upd), "project status"), ("AI tasks completed", len(done), "AI task history"),
                      ("AI tasks that didn't finish", len(failed), "AI task history"), ("MCQ answers", len(ans), "MCQ answers"),
                      ("chats active", len(convs), "chat history")]
            sec("Last 7 days", [{"text": f"{n} {label}" + (f" ({sum(1 for a in ans if a.get('correct'))} correct)" if label == "MCQ answers" else ""),
                                 "source": src} for label, n, src in counts if n])
        sec(f"Projects updated {span}", [{"text": p["name"] + (f" — {(p['status'].get('current') or '')[:90]}" if p["status"].get("current") else ""),
                                          "source": f"project status (recorded {age(now - p['status']['updated_at'])})"} for p in upd[:6]])
        sec(f"AI tasks completed {span}", [{"text": t.get("title", ""), "source": "AI task history"} for t in done[:6]])
        if kind == "evening":
            sec("AI tasks that didn't finish today", [{"text": t.get("title", ""), "source": "AI task history"} for t in failed[:3]])
            if ans:
                c = sum(1 for a in ans if a.get("correct"))
                topics = sorted({(a.get("topic") or "").strip() for a in ans if (a.get("topic") or "").strip()})
                sec("Practice today", [{"text": f"{len(ans)} MCQ answers, {c} correct" + (f" ({', '.join(topics[:4])})" if topics else ""),
                                        "source": "MCQ answers"}])
        if kind == "weekly":
            sec("Still open issues", [{"text": f"{p['name']}: {i}", "source": "project status · open issues"}
                                      for p in live for i in ((p.get("status") or {}).get("issues") or [])][:8])
    else:
        raise ValueError("Unknown briefing kind.")
    return {"kind": kind, "sections": sections, "empty": not sections, "built_at": int(now)}


# =========================================================================== provider failure counters (counts only)
_failures: dict[str, int] = {}


def note_provider_failure(where: str, exc: BaseException) -> None:
    key = f"{where}:{type(exc).__name__}"
    _failures[key] = _failures.get(key, 0) + 1


def provider_failures() -> dict[str, int]:
    return dict(_failures)
