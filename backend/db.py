"""
db.py: SQLite progress tracking.

A single `events` table records every answered MCQ and every viewed interview
answer per user. Summaries (score, streak, per-category accuracy) are computed
with SQL, so there's nothing else to keep in sync.

Uses the standard-library sqlite3 module; FastAPI runs these sync functions in
its thread pool, and each call opens its own short-lived connection.
"""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from datetime import date, timedelta

from config import get_settings

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT    NOT NULL,
    kind        TEXT    NOT NULL CHECK (kind IN ('mcq', 'interview_view', 'chat')),
    category    TEXT,
    correct     INTEGER,             -- 1/0 for MCQs, NULL otherwise
    item        TEXT,                -- question text (for de-duplication / history)
    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_events_user ON events(user_id, kind);
"""


@contextmanager
def connect():
    conn = sqlite3.connect(get_settings().sqlite_path)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with connect() as c:
        c.executescript(SCHEMA)


def record_event(user_id: str, kind: str, category: str | None, correct: bool | None, item: str | None) -> None:
    with connect() as c:
        c.execute(
            "INSERT INTO events (user_id, kind, category, correct, item) VALUES (?, ?, ?, ?, ?)",
            (user_id, kind, category, None if correct is None else int(correct), (item or "")[:500]),
        )


def get_summary(user_id: str) -> dict:
    with connect() as c:
        row = c.execute(
            "SELECT COUNT(*) AS total, COALESCE(SUM(correct), 0) AS correct "
            "FROM events WHERE user_id = ? AND kind = 'mcq'", (user_id,)).fetchone()
        viewed = c.execute(
            "SELECT COUNT(*) FROM events WHERE user_id = ? AND kind = 'interview_view'", (user_id,)).fetchone()[0]
        by_cat = c.execute(
            "SELECT category, COUNT(*) AS total, COALESCE(SUM(correct), 0) AS correct FROM events "
            "WHERE user_id = ? AND kind = 'mcq' GROUP BY category ORDER BY total DESC", (user_id,)).fetchall()
        days = [r[0] for r in c.execute(
            "SELECT DISTINCT date(created_at) FROM events WHERE user_id = ? ORDER BY 1 DESC", (user_id,))]

    # Streak = consecutive days with any activity, ending today or yesterday.
    streak, expected = 0, date.today()
    for d in days:
        d = date.fromisoformat(d)
        if d == expected or (streak == 0 and d == expected - timedelta(days=1)):
            streak += 1
            expected = d - timedelta(days=1)
        else:
            break

    total, correct = row["total"], row["correct"]
    return {
        "mcq_total": total,
        "mcq_correct": correct,
        "accuracy": round(100 * correct / total) if total else 0,
        "interview_viewed": viewed,
        "streak_days": streak,
        "by_category": [dict(r) for r in by_cat],
    }


def seen_items(user_id: str, kind: str, limit: int = 30) -> list[str]:
    """Recently seen questions, so the generator can avoid repeats."""
    with connect() as c:
        return [r[0] for r in c.execute(
            "SELECT item FROM events WHERE user_id = ? AND kind = ? AND item != '' "
            "ORDER BY id DESC LIMIT ?", (user_id, kind, limit))]
