"""
database.py: SQLite access + schema migrations for Abhify.AI.

One database file (data/abhify.db) holds progress, conversations, memories,
user preferences and document-index metadata. ChromaDB keeps the embeddings.

Migrations
----------
`MIGRATIONS` is an append-only list. Each entry upgrades the schema by one
version inside a transaction; `PRAGMA user_version` records the current
version and `schema_migrations` keeps a history. Before any pending migration
runs, the database is backed up to data/backups/. A failed migration rolls back
and the app refuses to start rather than running on a half-migrated schema.

Never edit a migration that has shipped. Add a new one instead.
"""
from __future__ import annotations

import json
import logging
import sqlite3
from contextlib import contextmanager
from datetime import date, datetime, timedelta
from typing import Any, Iterator

import local_file_service as lfs
from version import __version__

log = logging.getLogger(__name__)

# --------------------------------------------------------------------------- migrations
MIGRATIONS: list[tuple[str, str]] = [
    # v1: the schema shipped with AI Tech Pulse v1.0.0 (progress events).
    ("v1 progress events", """
        CREATE TABLE IF NOT EXISTS events (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     TEXT    NOT NULL,
            kind        TEXT    NOT NULL CHECK (kind IN ('mcq', 'interview_view', 'chat')),
            category    TEXT,
            correct     INTEGER,
            item        TEXT,
            created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
        );
        CREATE INDEX IF NOT EXISTS idx_events_user ON events(user_id, kind);
    """),
    # v2: conversations, personal memory, preferences, document index metadata.
    ("v2 conversations, memory, settings, documents", """
        CREATE TABLE conversations (
            id          TEXT PRIMARY KEY,
            title       TEXT NOT NULL DEFAULT 'New conversation',
            created_at  TEXT NOT NULL DEFAULT (datetime('now')),
            updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
        );
        CREATE TABLE messages (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
            content         TEXT NOT NULL,
            tools           TEXT,                    -- JSON list of tool names used
            sources         TEXT,                    -- JSON list of cited sources
            created_at      TEXT NOT NULL DEFAULT (datetime('now'))
        );
        CREATE INDEX idx_messages_conv ON messages(conversation_id, id);

        CREATE TABLE memories (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            content     TEXT NOT NULL,
            category    TEXT NOT NULL DEFAULT 'general',   -- general | preference | goal | weak_topic | fact
            source      TEXT NOT NULL DEFAULT 'user' CHECK (source IN ('user', 'assistant')),
            pinned      INTEGER NOT NULL DEFAULT 0,        -- pinned memories are always included
            created_at  TEXT NOT NULL DEFAULT (datetime('now')),
            updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
        );
        CREATE VIRTUAL TABLE memories_fts USING fts5(content, category, content='memories', content_rowid='id');
        CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
            INSERT INTO memories_fts(rowid, content, category) VALUES (new.id, new.content, new.category);
        END;
        CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
            INSERT INTO memories_fts(memories_fts, rowid, content, category) VALUES ('delete', old.id, old.content, old.category);
        END;
        CREATE TRIGGER memories_au AFTER UPDATE ON memories BEGIN
            INSERT INTO memories_fts(memories_fts, rowid, content, category) VALUES ('delete', old.id, old.content, old.category);
            INSERT INTO memories_fts(rowid, content, category) VALUES (new.id, new.content, new.category);
        END;

        CREATE TABLE app_settings (
            key     TEXT PRIMARY KEY,
            value   TEXT NOT NULL                      -- JSON
        );

        CREATE TABLE documents (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            path        TEXT NOT NULL,                 -- original name (uploads) or absolute path (watcher)
            source      TEXT NOT NULL DEFAULT 'upload' CHECK (source IN ('upload', 'watcher')),
            sha256      TEXT NOT NULL,
            size        INTEGER NOT NULL DEFAULT 0,
            chunks      INTEGER NOT NULL DEFAULT 0,
            status      TEXT NOT NULL DEFAULT 'indexed' CHECK (status IN ('pending', 'indexed', 'failed', 'removed')),
            error       TEXT,
            modified_at TEXT,
            indexed_at  TEXT NOT NULL DEFAULT (datetime('now')),
            UNIQUE (source, path)
        );
        CREATE INDEX idx_documents_hash ON documents(sha256);
    """),
]

SCHEMA_VERSION = len(MIGRATIONS)

DEFAULT_PREFS: dict[str, Any] = {
    "user_name": "Abhi",
    "memory_enabled": True,          # long-term personal memory (injection + save_memory tool)
    "save_history": True,            # store chat conversations
    "history_retention_days": 0,     # 0 = keep forever
}


# --------------------------------------------------------------------------- connections
@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    """Short-lived connection; commits on success, rolls back on error."""
    conn = sqlite3.connect(lfs.db_path(), timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def current_version(conn: sqlite3.Connection) -> int:
    v = conn.execute("PRAGMA user_version").fetchone()[0]
    if v == 0:
        # Databases from v1.0.0 have the events table but no user_version.
        has_events = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='events'").fetchone()
        return 1 if has_events else 0
    return v


def migrate() -> dict[str, Any]:
    """Bring the database up to SCHEMA_VERSION. Safe to call on every start."""
    lfs.db_path().parent.mkdir(parents=True, exist_ok=True)
    existed = lfs.db_path().exists()
    conn = sqlite3.connect(lfs.db_path(), timeout=15, isolation_level=None)   # manual transactions
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        start = current_version(conn)
        if start > SCHEMA_VERSION:
            raise RuntimeError(f"Database schema v{start} is newer than this app (v{SCHEMA_VERSION}). "
                               "Install the newer Abhify.AI version or restore a backup.")
        backup = None
        if start < SCHEMA_VERSION and existed and start > 0:
            backup = lfs.backups_dir() / f"abhify-pre-schema-v{SCHEMA_VERSION}-{datetime.now():%Y%m%d-%H%M%S}.db"
            lfs.backups_dir().mkdir(parents=True, exist_ok=True)
            b = sqlite3.connect(backup)
            try:
                conn.backup(b)
            finally:
                b.close()
            log.info("Backed up database before migration: %s", backup)

        conn.execute("""CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY, name TEXT NOT NULL,
                            app_version TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT (datetime('now')))""")
        for version in range(start + 1, SCHEMA_VERSION + 1):
            name, sql = MIGRATIONS[version - 1]
            try:
                conn.execute("BEGIN")
                for stmt in _split_sql(sql):
                    conn.execute(stmt)
                conn.execute(f"PRAGMA user_version = {version}")
                conn.execute("INSERT OR REPLACE INTO schema_migrations (version, name, app_version) VALUES (?, ?, ?)",
                             (version, name, __version__))
                conn.execute("COMMIT")
                log.info("Applied migration %d: %s", version, name)
            except Exception as e:
                conn.execute("ROLLBACK")
                raise RuntimeError(f"Migration {version} ({name}) failed and was rolled back: {e}. "
                                   f"Backup: {backup}") from e
        if start == 1 and current_version(conn) == SCHEMA_VERSION:
            # Record v1 for databases adopted from v1.0.0
            conn.execute("INSERT OR IGNORE INTO schema_migrations (version, name, app_version, applied_at) "
                         "VALUES (1, ?, '1.0.0', datetime('now'))", (MIGRATIONS[0][0],))
        return {"from": start, "to": SCHEMA_VERSION, "backup": str(backup) if backup else None}
    finally:
        conn.close()


def _split_sql(script: str) -> list[str]:
    """Split a migration script into statements, keeping CREATE TRIGGER ... END; blocks intact."""
    stmts, buf, in_trigger = [], [], False
    for line in script.strip().splitlines():
        s = line.strip()
        if not s:
            continue
        buf.append(line)
        if s.upper().startswith("CREATE TRIGGER"):
            in_trigger = True
        if in_trigger:
            if s.upper() == "END;":
                stmts.append("\n".join(buf)); buf, in_trigger = [], False
        elif s.endswith(";"):
            stmts.append("\n".join(buf)); buf = []
    if buf:
        stmts.append("\n".join(buf))
    return stmts


# --------------------------------------------------------------------------- preferences
def get_prefs() -> dict[str, Any]:
    with connect() as c:
        rows = {r["key"]: json.loads(r["value"]) for r in c.execute("SELECT key, value FROM app_settings")}
    return {**DEFAULT_PREFS, **rows}


def set_prefs(**changes: Any) -> dict[str, Any]:
    unknown = set(changes) - set(DEFAULT_PREFS)
    if unknown:
        raise ValueError(f"Unknown setting(s): {', '.join(sorted(unknown))}")
    with connect() as c:
        for k, v in changes.items():
            c.execute("INSERT INTO app_settings(key, value) VALUES (?, ?) "
                      "ON CONFLICT(key) DO UPDATE SET value = excluded.value", (k, json.dumps(v)))
    return get_prefs()


# --------------------------------------------------------------------------- progress (v1 API, unchanged behaviour)
def record_event(user_id: str, kind: str, category: str | None, correct: bool | None, item: str | None) -> None:
    with connect() as c:
        c.execute("INSERT INTO events (user_id, kind, category, correct, item) VALUES (?, ?, ?, ?, ?)",
                  (user_id, kind, category, None if correct is None else int(correct), (item or "")[:500]))


def get_summary(user_id: str) -> dict[str, Any]:
    with connect() as c:
        row = c.execute("SELECT COUNT(*) AS total, COALESCE(SUM(correct), 0) AS correct "
                        "FROM events WHERE user_id = ? AND kind = 'mcq'", (user_id,)).fetchone()
        viewed = c.execute("SELECT COUNT(*) FROM events WHERE user_id = ? AND kind = 'interview_view'",
                           (user_id,)).fetchone()[0]
        by_cat = c.execute("SELECT category, COUNT(*) AS total, COALESCE(SUM(correct), 0) AS correct FROM events "
                           "WHERE user_id = ? AND kind = 'mcq' GROUP BY category ORDER BY total DESC",
                           (user_id,)).fetchall()
        days = [r[0] for r in c.execute(
            "SELECT DISTINCT date(created_at) FROM events WHERE user_id = ? ORDER BY 1 DESC", (user_id,))]

    streak, expected = 0, date.today()
    for d in days:
        d = date.fromisoformat(d)
        if d == expected or (streak == 0 and d == expected - timedelta(days=1)):
            streak += 1
            expected = d - timedelta(days=1)
        else:
            break
    total, correct = row["total"], row["correct"]
    weak = [dict(r) for r in by_cat if r["total"] >= 3 and r["correct"] / r["total"] < 0.6]
    return {"mcq_total": total, "mcq_correct": correct, "accuracy": round(100 * correct / total) if total else 0,
            "interview_viewed": viewed, "streak_days": streak, "by_category": [dict(r) for r in by_cat],
            "weak_topics": [w["category"] for w in weak]}


def seen_items(user_id: str, kind: str, limit: int = 30) -> list[str]:
    with connect() as c:
        return [r[0] for r in c.execute(
            "SELECT item FROM events WHERE user_id = ? AND kind = ? AND item != '' ORDER BY id DESC LIMIT ?",
            (user_id, kind, limit))]


# --------------------------------------------------------------------------- documents (index metadata)
def find_document_by_hash(sha256: str) -> dict[str, Any] | None:
    with connect() as c:
        r = c.execute("SELECT * FROM documents WHERE sha256 = ? AND status = 'indexed' LIMIT 1", (sha256,)).fetchone()
    return dict(r) if r else None


def upsert_document(path: str, source: str, sha256: str, size: int, chunks: int,
                    status: str = "indexed", error: str | None = None, modified_at: str | None = None) -> None:
    with connect() as c:
        c.execute("""INSERT INTO documents (path, source, sha256, size, chunks, status, error, modified_at, indexed_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                     ON CONFLICT(source, path) DO UPDATE SET sha256 = excluded.sha256, size = excluded.size,
                         chunks = excluded.chunks, status = excluded.status, error = excluded.error,
                         modified_at = excluded.modified_at, indexed_at = excluded.indexed_at""",
                  (path, source, sha256, size, chunks, status, error, modified_at))


def list_documents() -> list[dict[str, Any]]:
    with connect() as c:
        return [dict(r) for r in c.execute("SELECT id, path, source, size, chunks, status, error, indexed_at "
                                           "FROM documents WHERE status != 'removed' ORDER BY indexed_at DESC")]
