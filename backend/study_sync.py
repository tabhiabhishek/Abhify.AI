"""Study data for the optional encrypted Drive sync: PREPARED, NOT CONNECTED (7.2.0-dev.2).

The live sync protocol (docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md, sync_core.KINDS) is not changed. This module only
defines how Study data would become sync records, and how two copies merge, so the extension can be reviewed and
tested before anything is wired. It is connected only after the owner's drive.file cross-client check passes and the
protocol extension is approved.

Records (kind, rid, data):
- study_course: one per course. Merge: done lessons, exercises and wrong answers are unions; per-module scores take
  the larger counts; prefs come from the newer prefs_history; history entries are merged and de-duplicated by
  (at, kind, detail); seconds per day take the larger value per day.
- study_story: one per STAR story. The newer `updated` wins.
- study_plan: the current interview plan. The newer `created` wins; the other goes into plan_history.
- study_progress: per-skill MCQ counts, review schedule and activity days. Counts take the max; activity is a union;
  for a review item the later schedule wins.
Lessons (AI text), cards, mock interviews and quality checks stay on each device (large or device-specific).
"""
from __future__ import annotations

from typing import Any

KINDS = ("study_course", "study_story", "study_plan", "study_progress")


def to_records(d: dict[str, Any]) -> list[tuple[str, str, dict]]:
    recs = [("study_course", cid, c) for cid, c in sorted(d.get("courses", {}).items())]
    recs += [("study_story", s["id"], s) for s in d.get("stories", [])]
    if d.get("plan"):
        recs.append(("study_plan", "current", d["plan"]))
    recs.append(("study_progress", "progress", {"mcq": d.get("mcq", {}), "reviews": d.get("reviews", {}), "activity": d.get("activity", [])}))
    return recs


def _max_counts(a: dict, b: dict) -> dict:
    out = {}
    for k in set(a) | set(b):
        x, y = a.get(k, {}), b.get(k, {})
        out[k] = {"answered": max(x.get("answered", 0), y.get("answered", 0)), "correct": max(x.get("correct", 0), y.get("correct", 0))}
    return out


def merge_course(a: dict, b: dict) -> dict:
    out = {**a, **b}
    out["done"] = sorted(set(a.get("done", [])) | set(b.get("done", [])))
    out["exercises"] = sorted(set(a.get("exercises", [])) | set(b.get("exercises", [])))
    out["scores"] = _max_counts(a.get("scores", {}), b.get("scores", {}))
    days = dict(a.get("days", {}))
    for k, v in b.get("days", {}).items():
        days[k] = max(days.get(k, 0), v)
    out["days"] = days
    out["seconds"] = sum(days.values()) if days else max(a.get("seconds", 0), b.get("seconds", 0))
    seen, hist = set(), []
    for h in sorted(a.get("history", []) + b.get("history", []), key=lambda h: (h.get("at", 0), h.get("kind", ""), h.get("detail", ""))):
        key = (h.get("at"), h.get("kind"), h.get("detail"))
        if key not in seen:
            seen.add(key)
            hist.append(h)
    out["history"] = hist[-300:]
    wk = {(w.get("key") or w.get("q"), w.get("at")) for w in a.get("wrong", [])}
    out["wrong"] = (a.get("wrong", []) + [w for w in b.get("wrong", []) if (w.get("key") or w.get("q"), w.get("at")) not in wk])[-200:]
    newer = a if _prefs_at(a) >= _prefs_at(b) else b
    out["prefs"], out["overview"] = newer.get("prefs"), newer.get("overview")
    return out


def _prefs_at(c: dict) -> int:
    hist = [h.get("at", 0) for h in c.get("history", []) if h.get("kind") == "prefs"]
    return max(hist) if hist else 0


def merge(local: dict, remote: dict) -> dict:
    """Merges two study.json documents (no data is lost; conflicts resolve as documented above)."""
    out = {**local}
    courses = dict(local.get("courses", {}))
    for cid, c in remote.get("courses", {}).items():
        courses[cid] = merge_course(courses[cid], c) if cid in courses else c
    out["courses"] = courses
    stories = {s["id"]: s for s in local.get("stories", [])}
    for s in remote.get("stories", []):
        if s["id"] not in stories or s.get("updated", 0) > stories[s["id"]].get("updated", 0):
            stories[s["id"]] = s
    out["stories"] = sorted(stories.values(), key=lambda s: s.get("updated", 0))
    lp, rp = local.get("plan"), remote.get("plan")
    hist = {p.get("created"): p for p in local.get("plan_history", []) + remote.get("plan_history", [])}
    if lp and rp and lp.get("created") != rp.get("created"):
        newer, older = (lp, rp) if lp["created"] >= rp["created"] else (rp, lp)
        out["plan"] = newer
        hist[older["created"]] = {**older, "archived_at": newer["created"]}
    else:
        out["plan"] = lp or rp
    out["plan_history"] = sorted(hist.values(), key=lambda p: p.get("created", 0))[-10:]
    out["mcq"] = _max_counts(local.get("mcq", {}), remote.get("mcq", {}))
    out["activity"] = sorted(set(local.get("activity", [])) | set(remote.get("activity", [])))[-400:]
    rev = dict(local.get("reviews", {}))
    for k, v in remote.get("reviews", {}).items():
        mine = rev.get(k)
        if not mine or (v.get("times_right", 0), v.get("due") or "") > (mine.get("times_right", 0), mine.get("due") or ""):
            rev[k] = v
    out["reviews"] = rev
    return out
