"""SQLite storage for the cloud service. Every user-data row carries user_id, and every query that
reads or writes user data filters by it (per-user isolation is enforced here, not in callers)."""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    password_changed_at INTEGER NOT NULL,
    sync_seq INTEGER NOT NULL DEFAULT 0
);
-- one row per signed-in device (a "session"); refresh tokens rotate inside it
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
-- only SHA-256 hashes of tokens are stored
CREATE TABLE IF NOT EXISTS tokens (
    hash TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('access', 'refresh')),
    expires_at INTEGER NOT NULL,
    used_at INTEGER
);
CREATE INDEX IF NOT EXISTS tokens_session ON tokens(session_id);
CREATE TABLE IF NOT EXISTS password_resets (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);
-- synced data: conversations, messages, memories, preferences
CREATE TABLE IF NOT EXISTS records (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    data TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0,
    device_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    PRIMARY KEY (user_id, kind, id)
);
CREATE INDEX IF NOT EXISTS records_seq ON records(user_id, seq);
-- shared provider usage per user per UTC day (administrator limits)
CREATE TABLE IF NOT EXISTS ai_usage (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    provider TEXT NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, provider)
);
"""

_init_lock = threading.Lock()
_initialized: set[str] = set()


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 10000")
    with _init_lock:
        if str(path) not in _initialized:
            conn.execute("PRAGMA journal_mode = WAL")
            conn.executescript(SCHEMA)
            _initialized.add(str(path))
    return conn


@contextmanager
def transaction(conn: sqlite3.Connection):
    """BEGIN IMMEDIATE: one writer at a time, so per-user sequence numbers never collide."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
