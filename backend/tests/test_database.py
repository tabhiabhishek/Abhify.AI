"""Schema migrations must preserve data, refuse newer schemas and roll back failures."""
import sqlite3

import pytest

import database as db
import local_file_service as lfs


def _make_v1_db(path, rows=3):
    """A database exactly as AI Tech Pulse v1.0.0 created it (no user_version)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(path) as c:
        c.executescript(db.MIGRATIONS[0][1])
        for i in range(rows):
            c.execute("INSERT INTO events (user_id, kind, category, correct, item) VALUES (?,?,?,?,?)",
                      ("u1", "mcq", "ml", i % 2, f"q{i}"))


def test_fresh_database_reaches_latest_schema(data_root):
    info = db.migrate()
    assert info == {"from": 0, "to": db.SCHEMA_VERSION, "backup": None}
    with db.connect() as c:
        assert db.current_version(c) == db.SCHEMA_VERSION
        tables = {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"events", "conversations", "messages", "memories", "app_settings", "documents"} <= tables


def test_migrate_is_idempotent(data_root):
    db.migrate()
    again = db.migrate()
    assert again["from"] == again["to"] == db.SCHEMA_VERSION


def test_v1_data_survives_upgrade_and_is_backed_up(data_root):
    _make_v1_db(lfs.db_path(), rows=5)
    info = db.migrate()
    assert info["from"] == 1 and info["to"] == db.SCHEMA_VERSION
    assert info["backup"] and lfs.Path(info["backup"]).exists()
    s = db.get_summary("u1")
    assert s["mcq_total"] == 5 and s["mcq_correct"] == 2
    with db.connect() as c:
        hist = [r[0] for r in c.execute("SELECT version FROM schema_migrations ORDER BY version")]
    assert hist == list(range(1, db.SCHEMA_VERSION + 1))


def test_newer_schema_is_refused(data_root):
    with sqlite3.connect(lfs.db_path()) as c:
        c.execute("PRAGMA user_version = 99")
    with pytest.raises(RuntimeError, match="newer than this app"):
        db.migrate()


def test_failed_migration_rolls_back(data_root, monkeypatch):
    db.migrate()
    bad = db.MIGRATIONS + [("v-next broken", "CREATE TABLE ok_part (x INTEGER);\nTHIS IS NOT SQL;")]
    monkeypatch.setattr(db, "MIGRATIONS", bad)
    monkeypatch.setattr(db, "SCHEMA_VERSION", len(bad))
    with pytest.raises(RuntimeError, match="rolled back"):
        db.migrate()
    with sqlite3.connect(lfs.db_path()) as c:
        assert c.execute("PRAGMA user_version").fetchone()[0] == len(bad) - 1
        assert not c.execute("SELECT 1 FROM sqlite_master WHERE name='ok_part'").fetchone()


def test_preferences_roundtrip_and_validation(migrated):
    assert db.get_prefs()["memory_enabled"] is True
    p = db.set_prefs(memory_enabled=False, user_name="Abhishek")
    assert p["memory_enabled"] is False and p["user_name"] == "Abhishek"
    with pytest.raises(ValueError):
        db.set_prefs(not_a_setting=1)


def test_document_dedupe_by_hash(migrated):
    db.upsert_document("a.pdf", "upload", "abc123", 10, 4)
    assert db.find_document_by_hash("abc123")["chunks"] == 4
    db.upsert_document("a.pdf", "upload", "def456", 12, 6)        # same path, new content -> replaced
    assert db.find_document_by_hash("abc123") is None
    assert len(db.list_documents()) == 1
