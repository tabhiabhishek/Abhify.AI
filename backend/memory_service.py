"""
memory_service.py: conversations and user-controlled personal memory.

Two different things are stored here, on purpose kept apart:

* Conversation history: what was said in each chat thread. Used to give the
  agent short-term context. Can be disabled (`save_history`) and auto-expired
  (`history_retention_days`).
* Personal memories: short facts the USER chose to keep ("I'm preparing for
  ML interviews", "I prefer short answers"). They are only created explicitly
  (memory UI, or "remember that ..." in chat), never silently from every
  sentence. They're editable, deletable, and can be switched off entirely
  (`memory_enabled`).

Relevant memories are retrieved per request with SQLite FTS5 (BM25 ranking),
which works fully offline and keeps personal memory out of the document
vector store.
"""
from __future__ import annotations

import json
import re
import uuid
from typing import Any

import database as db

STOPWORDS = set("""a an and are as at be but by can could did do does for from had has have how i if in into is it
its me my of on or our please so tell that the their them then there these they this to was what when where which
who why will with would you your about explain give know show""".split())

CATEGORIES = ("general", "preference", "goal", "weak_topic", "fact")


# --------------------------------------------------------------------------- conversations
def ensure_conversation(conversation_id: str | None, first_message: str = "") -> str:
    cid = conversation_id or uuid.uuid4().hex
    title = (first_message.strip().splitlines() or ["New conversation"])[0][:80] or "New conversation"
    with db.connect() as c:
        c.execute("INSERT OR IGNORE INTO conversations (id, title) VALUES (?, ?)", (cid, title))
    return cid


def add_message(conversation_id: str, role: str, content: str,
                tools: list[str] | None = None, sources: list[str] | None = None) -> None:
    with db.connect() as c:
        c.execute("INSERT INTO messages (conversation_id, role, content, tools, sources) VALUES (?, ?, ?, ?, ?)",
                  (conversation_id, role, content, json.dumps(tools or []), json.dumps(sources or [])))
        c.execute("UPDATE conversations SET updated_at = datetime('now') WHERE id = ?", (conversation_id,))


def recent_messages(conversation_id: str, limit: int = 12) -> list[dict[str, Any]]:
    """Last `limit` messages of a conversation, oldest first."""
    with db.connect() as c:
        rows = c.execute("SELECT role, content, tools, sources, created_at FROM messages WHERE conversation_id = ? "
                         "ORDER BY id DESC LIMIT ?", (conversation_id, limit)).fetchall()
    return [{**dict(r), "tools": json.loads(r["tools"] or "[]"), "sources": json.loads(r["sources"] or "[]")}
            for r in reversed(rows)]


def list_conversations(limit: int = 50) -> list[dict[str, Any]]:
    with db.connect() as c:
        return [dict(r) for r in c.execute(
            "SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS messages FROM conversations c "
            "LEFT JOIN messages m ON m.conversation_id = c.id GROUP BY c.id ORDER BY c.updated_at DESC LIMIT ?",
            (limit,))]


def delete_conversation(conversation_id: str) -> bool:
    with db.connect() as c:
        return c.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,)).rowcount > 0


def delete_all_conversations() -> int:
    with db.connect() as c:
        return c.execute("DELETE FROM conversations").rowcount


def purge_expired_history() -> int:
    """Delete conversations older than the retention setting (0 = keep forever)."""
    days = int(db.get_prefs().get("history_retention_days") or 0)
    if days <= 0:
        return 0
    with db.connect() as c:
        return c.execute("DELETE FROM conversations WHERE updated_at < datetime('now', ?)", (f"-{days} days",)).rowcount


# --------------------------------------------------------------------------- memories
def _clean(content: str) -> str:
    content = " ".join(content.split())
    if not content:
        raise ValueError("Memory text cannot be empty")
    if len(content) > 500:
        raise ValueError("Keep memories short (max 500 characters)")
    return content


def add_memory(content: str, category: str = "general", source: str = "user", pinned: bool = False) -> dict[str, Any]:
    if category not in CATEGORIES:
        category = "general"
    content = _clean(content)
    with db.connect() as c:
        dup = c.execute("SELECT id FROM memories WHERE lower(content) = lower(?)", (content,)).fetchone()
        if dup:
            return get_memory(dup["id"])
        mid = c.execute("INSERT INTO memories (content, category, source, pinned) VALUES (?, ?, ?, ?)",
                        (content, category, source, int(pinned))).lastrowid
    return get_memory(mid)


def get_memory(memory_id: int) -> dict[str, Any] | None:
    with db.connect() as c:
        r = c.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
    return dict(r) if r else None


def list_memories() -> list[dict[str, Any]]:
    with db.connect() as c:
        return [dict(r) for r in c.execute("SELECT * FROM memories ORDER BY pinned DESC, updated_at DESC")]


def update_memory(memory_id: int, content: str | None = None, category: str | None = None,
                  pinned: bool | None = None) -> dict[str, Any] | None:
    sets, args = [], []
    if content is not None:
        sets.append("content = ?"); args.append(_clean(content))
    if category is not None:
        sets.append("category = ?"); args.append(category if category in CATEGORIES else "general")
    if pinned is not None:
        sets.append("pinned = ?"); args.append(int(pinned))
    if not sets:
        return get_memory(memory_id)
    with db.connect() as c:
        n = c.execute(f"UPDATE memories SET {', '.join(sets)}, updated_at = datetime('now') WHERE id = ?",
                      (*args, memory_id)).rowcount
    return get_memory(memory_id) if n else None


def delete_memory(memory_id: int) -> bool:
    with db.connect() as c:
        return c.execute("DELETE FROM memories WHERE id = ?", (memory_id,)).rowcount > 0


def delete_all_memories() -> int:
    with db.connect() as c:
        return c.execute("DELETE FROM memories").rowcount


def _fts_query(text: str) -> str | None:
    words = [w.lower() for w in re.findall(r"[A-Za-z0-9][A-Za-z0-9+#.\-]{1,}", text)]
    terms = [w.strip(".-") for w in words if w not in STOPWORDS and len(w.strip(".-")) > 2]
    terms = list(dict.fromkeys(terms))[:12]
    return " OR ".join(f'"{t}"*' for t in terms) if terms else None   # quoted = no FTS syntax injection


def search_memories(text: str, limit: int = 4) -> list[dict[str, Any]]:
    """Memories relevant to `text` (BM25), plus all pinned ones."""
    out: dict[int, dict[str, Any]] = {}
    with db.connect() as c:
        for r in c.execute("SELECT * FROM memories WHERE pinned = 1 ORDER BY updated_at DESC LIMIT 5"):
            out[r["id"]] = dict(r)
        q = _fts_query(text)
        if q:
            for r in c.execute("SELECT m.* FROM memories_fts f JOIN memories m ON m.id = f.rowid "
                               "WHERE memories_fts MATCH ? ORDER BY bm25(memories_fts) LIMIT ?", (q, limit)):
                out.setdefault(r["id"], dict(r))
    return list(out.values())


def memory_context(text: str) -> str:
    """Short block appended to the system prompt; '' when memory is disabled/empty."""
    if not db.get_prefs().get("memory_enabled", True):
        return ""
    mems = search_memories(text)
    if not mems:
        return ""
    lines = "\n".join(f"- ({m['category']}) {m['content']}" for m in mems)
    return ("\n\nThings the user asked you to remember (use only when relevant; never invent new ones):\n" + lines)
