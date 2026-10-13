"""Shared fixtures: every test gets its own throw-away Abhify.AI data root."""
import sys
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))


@pytest.fixture
def data_root(tmp_path, monkeypatch):
    """Point the app at an empty temp data root (never the real user data)."""
    import local_file_service as lfs
    root = tmp_path / "AbhifyAI"
    monkeypatch.setenv("ABHIFY_DATA_DIR", str(root))
    monkeypatch.delenv("DATA_DIR", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)
    monkeypatch.delenv("EMBED_MODEL", raising=False)
    monkeypatch.setattr(lfs, "LEGACY_DATA_DIR", tmp_path / "no-legacy-data")   # never adopt real dev data
    lfs.reset_cache()
    lfs.ensure_layout()
    yield root
    lfs.reset_cache()


@pytest.fixture
def migrated(data_root):
    """Data root with the database migrated to the latest schema."""
    import database as db
    db.migrate()
    return data_root
