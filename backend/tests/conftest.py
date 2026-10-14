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


# --------------------------------------------------------------------------- knowledge-base fixtures (no Ollama)
import hashlib
import math

from langchain_core.embeddings import Embeddings


class FakeEmbeddings(Embeddings):
    """Deterministic bag-of-words vectors. Different model names -> different dimensions,
    so mixing models is detectable exactly like with real embeddings."""
    fail_with: Exception | None = None          # set to simulate Ollama being down

    def __init__(self, model: str):
        self.model = model
        self.dim = 16 + (int(hashlib.md5(model.encode()).hexdigest(), 16) % 5) * 8

    def _vec(self, text: str) -> list[float]:
        if FakeEmbeddings.fail_with:
            raise FakeEmbeddings.fail_with
        v = [0.0] * self.dim
        for w in text.lower().split():
            v[int(hashlib.md5(w.encode()).hexdigest(), 16) % self.dim] += 1.0
        n = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / n for x in v]

    def embed_documents(self, texts):
        return [self._vec(t) for t in texts]

    def embed_query(self, text):
        return self._vec(text)


@pytest.fixture
def kb(migrated, tmp_path, monkeypatch):
    """Migrated data root + fake embeddings + an empty knowledge folder."""
    import knowledge
    FakeEmbeddings.fail_with = None
    monkeypatch.setattr(knowledge, "embeddings_factory", FakeEmbeddings)
    knowledge.reset_caches()
    folder = tmp_path / "Abhify_Knowledge"
    folder.mkdir()
    import local_file_service as lfs
    lfs.update_settings(knowledge={"folder": str(folder)})
    yield folder
    FakeEmbeddings.fail_with = None
    knowledge.reset_caches()
