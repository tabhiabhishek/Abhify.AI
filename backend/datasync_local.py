"""7.2: sync_core.Local for this computer's database (conversations, messages, personal memory, portable preferences).

Records are mapped to the platform-neutral shapes of docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md §5 through `sync_map`
(local id ↔ shared rid; existing rows are never changed to get an id). One SQLite connection per run; `transaction()`
is BEGIN IMMEDIATE … COMMIT, ROLLBACK on any error, so an import is all or nothing.

Conversation removal by sync: SQLite would cascade-delete its messages. Their records are first copied to `sync_stash`
and put back if the conversation is restored from Recently deleted, so nothing is lost silently.
"""
from __future__ import annotations

import contextlib
import json
import sqlite3
import uuid
from datetime import datetime, timezone
from typing import Any, Iterable

import local_file_service as lfs

CATEGORIES = ("general", "preference", "goal", "weak_topic", "fact")
PREF_KEYS = ("user_name", "answer_detail", "answer_depth", "answer_style", "answer_language")   # "theme" lives in the browser on Windows


def to_ms(text: str | None) -> int:
    if not text:
        return 0
    try:
        return int(datetime.strptime(text[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp() * 1000)
    except ValueError:
        try:
            return int(datetime.fromisoformat(text).astimezone(timezone.utc).timestamp() * 1000)
        except ValueError:
            return 0


def from_ms(ms: int) -> str:
    return datetime.fromtimestamp(max(0, int(ms)) / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


class WinLocal:
    def __init__(self, path=None):
        self.conn = sqlite3.connect(path or lfs.db_path(), timeout=15, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self._in_tx = False

    def close(self) -> None:
        self.conn.close()

    @contextlib.contextmanager
    def transaction(self):
        if self._in_tx:
            yield
            return
        self.conn.execute("BEGIN IMMEDIATE")
        self._in_tx = True
        try:
            yield
            self.conn.execute("COMMIT")
        except BaseException:
            self.conn.execute("ROLLBACK")
            raise
        finally:
            self._in_tx = False

    # ---- meta
    def get_meta(self, key: str, default: Any = None) -> Any:
        r = self.conn.execute("SELECT value FROM sync_meta WHERE key = ?", (key,)).fetchone()
        return json.loads(r["value"]) if r else default

    def put_meta(self, key: str, value: Any) -> None:
        self.conn.execute("INSERT INTO sync_meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                          (key, json.dumps(value, ensure_ascii=False)))

    # ---- id mapping
    def _rid(self, kind: str, local_id: Any, create: bool = True) -> str | None:
        r = self.conn.execute("SELECT rid FROM sync_map WHERE kind = ? AND local_id = ?", (kind, str(local_id))).fetchone()
        if r:
            return r["rid"]
        if not create:
            return None
        rid = uuid.uuid4().hex
        self.conn.execute("INSERT INTO sync_map(kind, local_id, rid) VALUES (?, ?, ?)", (kind, str(local_id), rid))
        return rid

    def _local(self, kind: str, rid: str) -> str | None:
        r = self.conn.execute("SELECT local_id FROM sync_map WHERE kind = ? AND rid = ?", (kind, rid)).fetchone()
        return r["local_id"] if r else None

    # ---- reading
    def read_items(self, kinds: Iterable[str]) -> dict[str, tuple[str, dict]]:
        kinds = set(kinds)
        out: dict[str, tuple[str, dict]] = {}
        conv_rid: dict[str, str] = {}
        if kinds & {"conversation", "message"}:
            for r in self.conn.execute("SELECT id, title, created_at FROM conversations ORDER BY created_at, id"):
                rid = self._rid("conversation", r["id"])
                conv_rid[r["id"]] = rid
                if "conversation" in kinds:
                    out[rid] = ("conversation", {"title": r["title"] or "", "created": to_ms(r["created_at"])})
        if "message" in kinds:
            for r in self.conn.execute("SELECT id, conversation_id, role, content, created_at, model FROM messages ORDER BY id"):
                d = {"conv": conv_rid.get(r["conversation_id"]) or self._rid("conversation", r["conversation_id"]),
                     "role": r["role"], "content": r["content"], "created": to_ms(r["created_at"])}
                if r["model"]:
                    d["model"] = r["model"]
                out[self._rid("message", r["id"])] = ("message", d)
        if "memory" in kinds:
            for r in self.conn.execute("SELECT id, content, category, pinned, created_at FROM memories ORDER BY id"):
                d = {"content": r["content"], "created": to_ms(r["created_at"]), "category": r["category"] or "general"}
                if r["pinned"]:
                    d["pinned"] = True
                out[self._rid("memory", r["id"])] = ("memory", d)
        if "pref" in kinds:
            marks = ",".join("?" * len(PREF_KEYS))
            for r in self.conn.execute(f"SELECT key, value FROM app_settings WHERE key IN ({marks})", PREF_KEYS):
                try:
                    v = json.loads(r["value"])
                except ValueError:
                    continue
                if isinstance(v, str) and v and len(v) <= 200:
                    out[f"pref:{r['key']}"] = ("pref", {"key": r["key"], "value": v})
        return out

    # ---- writing
    def add(self, kind: str, rid: str, data: dict) -> None:
        if kind == "conversation":
            cid = uuid.uuid4().hex
            self.conn.execute("INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                              (cid, (data.get("title") or "Synced conversation")[:500], from_ms(data["created"]), from_ms(data["created"])))
            self.conn.execute("INSERT INTO sync_map(kind, local_id, rid) VALUES ('conversation', ?, ?)", (cid, rid))
            self._unstash(rid, cid)
        elif kind == "message":
            cid = self._local("conversation", data["conv"]) or self._placeholder(data["conv"], data["created"])
            mid = self.conn.execute("INSERT INTO messages (conversation_id, role, content, tools, sources, created_at, model) VALUES (?, ?, ?, '[]', '[]', ?, ?)",
                                    (cid, data["role"], data["content"], from_ms(data["created"]), data.get("model"))).lastrowid
            self.conn.execute("INSERT INTO sync_map(kind, local_id, rid) VALUES ('message', ?, ?)", (str(mid), rid))
        elif kind == "memory":
            cat = data.get("category") if data.get("category") in CATEGORIES else "general"
            mid = self.conn.execute("INSERT INTO memories (content, category, source, pinned, created_at, updated_at) VALUES (?, ?, 'user', ?, ?, datetime('now'))",
                                    (data["content"], cat, int(bool(data.get("pinned"))), from_ms(data["created"]))).lastrowid
            self.conn.execute("INSERT INTO sync_map(kind, local_id, rid) VALUES ('memory', ?, ?)", (str(mid), rid))
        elif kind == "pref":
            self._pref(data)

    def _placeholder(self, conv_rid: str, created: int) -> str:
        """A message whose conversation was removed here but not deleted elsewhere: re-create an empty container."""
        cid = uuid.uuid4().hex
        self.conn.execute("INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, 'Synced conversation', ?, ?)",
                          (cid, from_ms(created), from_ms(created)))
        self.conn.execute("INSERT OR REPLACE INTO sync_map(kind, local_id, rid) VALUES ('conversation', ?, ?)", (cid, conv_rid))
        return cid

    def _pref(self, data: dict) -> None:
        if data.get("key") in PREF_KEYS and isinstance(data.get("value"), str):
            self.conn.execute("INSERT INTO app_settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                              (data["key"], json.dumps(data["value"])))

    def update(self, kind: str, rid: str, data: dict) -> None:
        lid = self._local(kind, rid) if kind != "pref" else None
        if kind == "pref":
            self._pref(data)
        elif lid is None:
            self.add(kind, rid, data)
        elif kind == "conversation":
            self.conn.execute("UPDATE conversations SET title = ? WHERE id = ?", ((data.get("title") or "")[:500], lid))
        elif kind == "message":
            self.conn.execute("UPDATE messages SET role = ?, content = ?, model = ? WHERE id = ?", (data["role"], data["content"], data.get("model"), int(lid)))
        elif kind == "memory":
            cat = data.get("category") if data.get("category") in CATEGORIES else "general"
            self.conn.execute("UPDATE memories SET content = ?, category = ?, pinned = ?, updated_at = datetime('now') WHERE id = ?",
                              (data["content"], cat, int(bool(data.get("pinned"))), int(lid)))

    def remove(self, kind: str, rid: str) -> None:
        if kind == "pref":
            return                                       # a preference is never deleted by sync
        lid = self._local(kind, rid)
        if lid is None:
            return
        if kind == "conversation":
            msgs = []
            for r in self.conn.execute("SELECT m.id, m.role, m.content, m.created_at, m.model, s.rid FROM messages m "
                                       "LEFT JOIN sync_map s ON s.kind = 'message' AND s.local_id = CAST(m.id AS TEXT) WHERE m.conversation_id = ?", (lid,)):
                msgs.append({"rid": r["rid"], "role": r["role"], "content": r["content"], "created": to_ms(r["created_at"]), "model": r["model"]})
            if msgs:
                self.conn.execute("INSERT INTO sync_stash(conv_rid, data) VALUES (?, ?)", (rid, json.dumps(msgs, ensure_ascii=False)))
            self.conn.execute("DELETE FROM sync_map WHERE kind = 'message' AND local_id IN (SELECT CAST(id AS TEXT) FROM messages WHERE conversation_id = ?)", (lid,))
            self.conn.execute("DELETE FROM conversations WHERE id = ?", (lid,))
        elif kind == "message":
            self.conn.execute("DELETE FROM messages WHERE id = ?", (int(lid),))
        elif kind == "memory":
            self.conn.execute("DELETE FROM memories WHERE id = ?", (int(lid),))
        self.conn.execute("DELETE FROM sync_map WHERE kind = ? AND rid = ?", (kind, rid))

    def _unstash(self, conv_rid: str, cid: str) -> None:
        rows = self.conn.execute("SELECT rowid, data FROM sync_stash WHERE conv_rid = ? ORDER BY rowid", (conv_rid,)).fetchall()
        for row in rows:
            for m in json.loads(row["data"]):
                mid = self.conn.execute("INSERT INTO messages (conversation_id, role, content, tools, sources, created_at, model) VALUES (?, ?, ?, '[]', '[]', ?, ?)",
                                        (cid, m["role"], m["content"], from_ms(m["created"]), m.get("model"))).lastrowid
                rid = m.get("rid") or uuid.uuid4().hex
                self.conn.execute("INSERT OR IGNORE INTO sync_map(kind, local_id, rid) VALUES ('message', ?, ?)", (str(mid), rid))
            self.conn.execute("DELETE FROM sync_stash WHERE rowid = ?", (row["rowid"],))
