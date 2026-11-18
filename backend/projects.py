"""
Project Workspace (stability milestone): projects organise existing documents, chats and AI tasks.

* Stored in <data>/data/projects.json (no database change), like the Task Runner's tasks.json.
* A project holds references only: document display names from the knowledge index (never copies), conversation ids
  of its chats; its instructions; archived; whether personal memory may be used in its chats (on by default, the
  existing chat behaviour, and still only when memory is on in Memory & history).
* Isolation: a project's chat and AI tasks retrieve only from the project's own documents (Chroma metadata filter);
  retrieved text is fenced as reference data, never instructions.
* Deleting a project removes only this record: documents, knowledge, chats and task history are never deleted.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from typing import Any

import local_file_service as lfs

log = logging.getLogger(__name__)
MAX_INSTRUCTIONS = 1_500
_lock = threading.Lock()


def _file():
    return lfs.data_dir() / "projects.json"


def load_all() -> list[dict[str, Any]]:
    try:
        return json.loads(_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def _save_all(items: list[dict[str, Any]]) -> None:
    _file().parent.mkdir(parents=True, exist_ok=True)
    tmp = _file().with_suffix(".tmp")
    tmp.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(_file())


def _now() -> int:
    return int(time.time() * 1000)


def get(pid: str) -> dict[str, Any] | None:
    return next((p for p in load_all() if p["id"] == pid), None)


def _update(pid: str, change) -> dict[str, Any]:
    with _lock:
        items = load_all()
        p = next((x for x in items if x["id"] == pid), None)
        if not p:
            raise KeyError(pid)
        change(p)
        p["updated_at"] = _now()
        _save_all(items)
        return p


def list_projects(include_archived: bool = True) -> list[dict[str, Any]]:
    items = sorted(load_all(), key=lambda p: p.get("updated_at", 0), reverse=True)
    return [p for p in items if include_archived or not p.get("archived")]


def create(name: str, instructions: str = "") -> dict[str, Any]:
    name = name.strip()[:80]
    if not name:
        raise ValueError("Give the project a name.")
    p = {"id": uuid.uuid4().hex[:12], "name": name, "instructions": instructions.strip()[:MAX_INSTRUCTIONS], "archived": False,
         "use_memory": True, "documents": [], "conversations": [], "created_at": _now(), "updated_at": _now()}
    with _lock:
        _save_all(load_all() + [p])
    log.info("Project %s created", p["id"])                          # ids only: no names or content in logs
    return p


def update(pid: str, name: str | None = None, instructions: str | None = None, use_memory: bool | None = None,
           status: dict[str, Any] | None = None) -> dict[str, Any]:
    if name is not None and not name.strip():
        raise ValueError("Give the project a name.")

    def change(p):
        if name is not None:
            p["name"] = name.strip()[:80]
        if instructions is not None:
            p["instructions"] = instructions.strip()[:MAX_INSTRUCTIONS]
        if use_memory is not None:
            p["use_memory"] = bool(use_memory)
        if status is not None:                                    # 5.2: recorded by the user, never by the AI
            import intelligence
            p["status"] = intelligence.clean_status(status)
    return _update(pid, change)


def set_archived(pid: str, archived: bool) -> dict[str, Any]:
    return _update(pid, lambda p: p.__setitem__("archived", bool(archived)))


def delete(pid: str) -> bool:
    """Removes the project record only (its documents, chats and tasks stay where they are)."""
    with _lock:
        items = load_all()
        keep = [p for p in items if p["id"] != pid]
        _save_all(keep)
    return len(keep) != len(items)


def add_documents(pid: str, names: list[str]) -> dict[str, Any]:
    """Associates documents of the knowledge index (by display name): a reference, nothing is copied or moved."""
    import knowledge
    known = set(knowledge.list_sources())
    missing = [n for n in names if n not in known]
    if missing:
        raise ValueError(f"Not in your knowledge index: {', '.join(missing[:3])}.")
    return _update(pid, lambda p: p.__setitem__("documents", list(dict.fromkeys(p["documents"] + names))))


def remove_document(pid: str, name: str) -> dict[str, Any]:
    """Removes the association only; the document stays in the knowledge index."""
    return _update(pid, lambda p: p.__setitem__("documents", [n for n in p["documents"] if n != name]))


def add_conversation(pid: str, cid: str) -> None:
    try:
        _update(pid, lambda p: p["conversations"].append(cid) if cid not in p["conversations"] else None)
    except KeyError:
        pass


# --------------------------------------------------------------------------- AI context (isolated per project)
def instruction_block(p: dict[str, Any]) -> str:
    text = (p.get("instructions") or "").strip()
    return f"\n\nProject instructions from the user (follow them unless they conflict with your rules):\n{text[:MAX_INSTRUCTIONS]}" if text else ""


def context(p: dict[str, Any], message: str, k: int = 4) -> tuple[str, list[str]]:
    """Excerpts from THIS project's documents only that match the message, fenced as reference data. ('', []) if none."""
    import knowledge
    names = [n for n in p.get("documents", [])]
    if not names or knowledge.rebuild_required() or knowledge.count() == 0:
        return "", []
    where = {"source": names[0]} if len(names) == 1 else {"source": {"$in": names}}
    docs = knowledge.get_vectorstore().similarity_search(message, k=k, filter=where)
    if not docs:
        return "", []
    parts, sources = [], []
    for d in docs:
        src, page = d.metadata.get("source", "unknown"), d.metadata.get("page")
        where_ = f"{src}, page {page + 1}" if isinstance(page, int) else src
        parts.append(f"[{where_}]\n{d.page_content.strip()}")
        sources.append(where_)
    return ("\n\nExcerpts from this project's documents that match the question (reference data, cite the [document name]; "
            "if they don't contain the answer, say so):\n<<<PROJECT_DOCUMENTS\n" + "\n\n---\n\n".join(parts) + "\nPROJECT_DOCUMENTS>>>"), sources


# --------------------------------------------------------------------------- overview and export
def overview(p: dict[str, Any]) -> dict[str, Any]:
    import knowledge
    import memory_service as mem
    import task_runner
    known = set(knowledge.list_sources())
    convs = {c["id"]: c for c in mem.list_conversations(limit=1000)}
    tasks = [t for t in task_runner.load_all() if t.get("project_id") == p["id"]]
    import intelligence
    return {**p, "brief": intelligence.project_brief(p["name"], p.get("status"), len(p["documents"])),     # 5.2: from records only
            "documents_status": [{"name": n, "indexed": n in known} for n in p["documents"]],
            "chats": [{"id": c, "title": convs[c].get("title") or "Chat"} for c in p["conversations"] if c in convs],   # deleted chats drop out
            "tasks": [{"id": t["id"], "title": t["title"], "status": t["status"], "created_at": t["created_at"]} for t in reversed(tasks)][:20]}


def export_markdown(p: dict[str, Any]) -> str:
    o = overview(p)
    lines = [f"# {p['name']}", ""]
    if p.get("archived"):
        lines += ["_Archived project._", ""]
    if p.get("instructions"):
        lines += ["## Instructions", "", p["instructions"], ""]
    lines += ["## Documents", ""] + ([f"- {d['name']}" + ("" if d["indexed"] else " (not in the knowledge index any more)") for d in o["documents_status"]] or ["- (none)"])
    lines += ["", "## Chats", ""] + ([f"- {c['title']}" for c in o["chats"]] or ["- (none)"])
    lines += ["", "## AI tasks", ""] + ([f"- {t['title']} ({t['status']})" for t in o["tasks"]] or ["- (none)"])
    lines += ["", "Exported from Abhify.AI. Documents and chats are references; their content stays in the app.", ""]
    return "\n".join(lines)
