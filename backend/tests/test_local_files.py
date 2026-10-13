"""Data directory resolution, v1 migration, backup/restore and relocation."""
import json
import sqlite3
import zipfile

import pytest

import database as db
import local_file_service as lfs


def test_default_root_windows(monkeypatch, tmp_path):
    monkeypatch.delenv("ABHIFY_DATA_DIR", raising=False)
    monkeypatch.delenv("DATA_DIR", raising=False)
    monkeypatch.setattr(lfs.platform, "system", lambda: "Windows")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "Local"))
    lfs.reset_cache()
    assert lfs.root() == tmp_path / "Local" / "AbhifyAI"
    lfs.reset_cache()


def test_env_override(data_root):
    assert lfs.root() == data_root and lfs.db_path() == data_root / "data" / "abhify.db"
    for d in ("config", "data/chroma", "data/documents/uploads", "data/backups", "logs", "cache"):
        assert (data_root / d).is_dir()


def test_settings_defaults_and_atomic_update(data_root):
    s = lfs.load_settings()
    assert s["model_profile"] == "balanced" and s["setup_completed"] is False
    lfs.update_settings(models={"llm": "qwen2.5:1.5b"})
    s = lfs.load_settings()
    assert s["models"] == {"llm": "qwen2.5:1.5b", "embed": "nomic-embed-text"}   # nested merge
    lfs.settings_path().write_text("{broken", encoding="utf-8")
    assert lfs.load_settings()["model_profile"] == "balanced"                    # corrupt file -> defaults


def test_legacy_v1_data_is_copied_not_moved(data_root, tmp_path):
    legacy = tmp_path / "old_backend_data"
    (legacy / "chroma").mkdir(parents=True)
    (legacy / "chroma" / "chroma.sqlite3").write_bytes(b"")
    with sqlite3.connect(legacy / "progress.db") as c:
        c.executescript(db.MIGRATIONS[0][1])
        c.execute("INSERT INTO events (user_id, kind, correct) VALUES ('u', 'mcq', 1)")
    report = lfs.migrate_legacy_data(legacy)
    assert report and lfs.db_path().exists()
    assert (legacy / "progress.db").exists()                                    # original untouched
    assert lfs.migrate_legacy_data(legacy) is None                              # only once
    db.migrate()
    assert db.get_summary("u")["mcq_total"] == 1


def test_backup_restore_roundtrip(migrated):
    import memory_service as mem
    mem.add_memory("original memory")
    zip_path = lfs.create_backup("test", schema_version=db.SCHEMA_VERSION)
    assert zipfile.ZipFile(zip_path).read("backup-manifest.json")
    mem.delete_all_memories()
    mem.add_memory("newer memory written after the backup")

    lfs.stage_restore(zip_path, db.SCHEMA_VERSION)
    result = lfs.apply_pending_restore()                                        # what startup does
    assert result and lfs.Path(result["previous_data"]).exists()               # old data kept aside
    assert [m["content"] for m in mem.list_memories()] == ["original memory"]
    assert lfs.apply_pending_restore() is None


def test_restore_rejects_unsafe_or_foreign_zips(migrated, tmp_path):
    evil = tmp_path / "evil.zip"
    with zipfile.ZipFile(evil, "w") as z:
        z.writestr("../../escape.txt", "x")
        z.writestr("backup-manifest.json", json.dumps({"app": "AbhifyAI"}))
    with pytest.raises(ValueError, match="Unsafe path"):
        lfs.validate_backup(evil, db.SCHEMA_VERSION)

    foreign = tmp_path / "foreign.zip"
    with zipfile.ZipFile(foreign, "w") as z:
        z.writestr("data/abhify.db", "x")
    with pytest.raises(ValueError, match="manifest missing"):
        lfs.validate_backup(foreign, db.SCHEMA_VERSION)

    newer = tmp_path / "newer.zip"
    with zipfile.ZipFile(newer, "w") as z:
        z.writestr("backup-manifest.json", json.dumps({"app": "AbhifyAI", "schema_version": 99}))
    with pytest.raises(ValueError, match="newer than this app"):
        lfs.validate_backup(newer, db.SCHEMA_VERSION)


def test_relocation_copies_data_and_keeps_old(monkeypatch, tmp_path):
    monkeypatch.delenv("ABHIFY_DATA_DIR", raising=False)
    monkeypatch.delenv("DATA_DIR", raising=False)
    monkeypatch.setattr(lfs.platform, "system", lambda: "Windows")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "Local"))
    lfs.reset_cache(); lfs.ensure_layout(); db.migrate()
    import memory_service as mem
    mem.add_memory("travels with me")

    target = tmp_path / "E_drive" / "AbhifyData"
    out = lfs.relocate_data_dir(target)
    assert out["restart_required"] and (target / "data" / "abhify.db").exists()
    assert (tmp_path / "Local" / "AbhifyAI" / "data" / "abhify.db").exists()   # old kept
    lfs.reset_cache()                                                           # = app restart
    assert lfs.root() == target.resolve()
    assert [m["content"] for m in mem.list_memories()] == ["travels with me"]
    lfs.reset_cache()


def test_relocation_refuses_without_space(monkeypatch, tmp_path, data_root):
    monkeypatch.delenv("ABHIFY_DATA_DIR")
    monkeypatch.setattr(lfs, "root", lambda: data_root)
    monkeypatch.setattr(lfs, "free_bytes", lambda p: 0)
    with pytest.raises(ValueError, match="Not enough free space"):
        lfs.relocate_data_dir(tmp_path / "small_drive")


def test_relocation_refuses_non_empty_target(monkeypatch, tmp_path, data_root):
    monkeypatch.delenv("ABHIFY_DATA_DIR")
    monkeypatch.setattr(lfs, "root", lambda: data_root)
    target = tmp_path / "busy"; target.mkdir(); (target / "file.txt").write_text("x")
    with pytest.raises(ValueError, match="empty"):
        lfs.relocate_data_dir(target)
