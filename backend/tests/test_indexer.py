"""Document indexing: pipeline, replacement policy, exclusions, safety, failures."""
import os
import sqlite3

import httpx
import pytest

import database as db
import document_indexer as di
import knowledge
from conftest import FakeEmbeddings


def chunks_for(key):
    return knowledge.get_vectorstore()._collection.get(where={"path_key": key}, include=["documents"])


def test_initial_index_and_search(kb):
    f = kb / "notes.md"
    f.write_text("# Dropout\nDropout randomly zeroes activations to reduce overfitting.", encoding="utf-8")
    r = di.index_file(f, kb)
    assert r["status"] == "indexed" and r["chunks"] == 1
    hits = knowledge.search("what does dropout do")
    assert hits and hits[0].metadata["source"] == "notes.md" and hits[0].metadata["origin"] == "watcher"
    doc = db.get_document("watcher", di.path_key(f))
    assert doc["status"] == "indexed" and doc["embed_model"] == "nomic-embed-text"


def test_unchanged_file_is_not_reindexed(kb, monkeypatch):
    f = kb / "a.txt"; f.write_text("gradient descent basics", encoding="utf-8")
    di.index_file(f, kb)
    called = []
    monkeypatch.setattr(knowledge, "replace_document", lambda *a, **k: called.append(1))
    assert di.index_file(f, kb)["status"] == "unchanged" and not called


def test_modified_file_replaces_old_chunks(kb):
    f = kb / "a.txt"; f.write_text("version one about bagging", encoding="utf-8")
    di.index_file(f, kb)
    f.write_text("version two about boosting", encoding="utf-8")
    assert di.index_file(f, kb)["status"] == "indexed"
    docs = chunks_for(di.path_key(f))["documents"]
    assert docs == ["version two about boosting"]                       # old version gone, no duplicates


def test_failed_reindex_keeps_previous_version(kb):
    f = kb / "a.txt"; f.write_text("stable content about CNNs", encoding="utf-8")
    di.index_file(f, kb)
    f.write_text("new content that fails to embed", encoding="utf-8")
    FakeEmbeddings.fail_with = httpx.ConnectError("Ollama refused connection")
    r = di.index_file(f, kb)
    assert r["status"] == "retry"
    FakeEmbeddings.fail_with = None
    assert chunks_for(di.path_key(f))["documents"] == ["stable content about CNNs"]   # still searchable
    assert db.get_document("watcher", di.path_key(f))["attempts"] == 1


def test_deleted_file_removed_from_index(kb):
    f = kb / "a.txt"; f.write_text("transformers use attention", encoding="utf-8")
    di.index_file(f, kb)
    key = di.path_key(f)
    f.unlink()
    di.remove_file(key)
    assert chunks_for(key)["documents"] == [] and db.get_document("watcher", key)["status"] == "removed"


def test_rename_moves_vectors_without_reembedding(kb, monkeypatch):
    old = kb / "old.md"; old.write_text("RAG retrieves chunks before generation", encoding="utf-8")
    di.index_file(old, kb)
    old_key = di.path_key(old)
    new = kb / "sub" / "new.md"; new.parent.mkdir(); os.replace(old, new)
    monkeypatch.setattr(knowledge, "replace_document", lambda *a, **k: pytest.fail("should not re-embed"))
    r = di.move_file(old, new, kb)
    assert r["status"] == "moved"
    assert chunks_for(old_key)["documents"] == []
    got = chunks_for(di.path_key(new))
    assert got["documents"] == ["RAG retrieves chunks before generation"]
    assert db.get_document("watcher", di.path_key(new))["path"] == os.path.join("sub", "new.md")


def test_duplicate_uploads_and_watch_files_dont_duplicate_chunks(kb):
    f = kb / "a.txt"; f.write_text("unique sentence about SVMs", encoding="utf-8")
    di.index_file(f, kb); di.index_file(f, kb)
    assert knowledge.count() == 1


@pytest.mark.parametrize("name,reason", [
    (".env", "excluded"), ("prod.env", "excluded"), ("id_rsa", "excluded"), ("server.pem", "excluded"),
    ("my_credentials.txt", "excluded"), ("token.json", "excluded"), ("data.db", "excluded"),
    ("model.gguf", "excluded"), ("download.crdownload", "excluded"), ("~$draft.txt", "excluded"),
    ("photo.jpg", "unsupported"), ("archive.zip", "unsupported"),
])
def test_exclusion_rules(kb, name, reason):
    assert reason in di.exclusion_reason(kb / name, kb)


def test_ml_notes_with_token_in_name_are_allowed(kb):
    assert di.exclusion_reason(kb / "tokenization_notes.md", kb) is None
    assert di.exclusion_reason(kb / "tokenizer.py", kb) is None


@pytest.mark.parametrize("folder", [".git", "node_modules", "venv", ".venv", "__pycache__", "AbhifyAI", ".hidden"])
def test_excluded_folders(kb, folder):
    assert "excluded folder" in di.exclusion_reason(kb / folder / "x.txt", kb)


def test_secret_content_is_skipped_never_indexed(kb):
    f = kb / "config.py"
    f.write_text('API_KEY = "sk-proj-abcdefghijklmnopqrstuvwxyz123456"\nprint("hi")\n', encoding="utf-8")
    r = di.index_file(f, kb)
    assert r["status"] == "skipped" and "password, key or token" in r["reason"]
    assert knowledge.count() == 0


def test_private_key_block_is_skipped(kb):
    f = kb / "notes.txt"
    f.write_text("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----", encoding="utf-8")
    assert di.index_file(f, kb)["status"] == "skipped"


def test_path_traversal_and_outside_files_rejected(kb, tmp_path):
    outside = tmp_path / "outside.txt"; outside.write_text("secret plans", encoding="utf-8")
    with pytest.raises(di.SkipFile, match="outside"):
        di.validate(kb / ".." / "outside.txt", kb, 10**7)
    with pytest.raises(di.SkipFile, match="outside"):
        di.validate(outside, kb, 10**7)


def test_symlinks_not_followed(kb, tmp_path):
    target = tmp_path / "private.txt"; target.write_text("private", encoding="utf-8")
    link = kb / "link.txt"
    try:
        os.symlink(target, link)
    except OSError:
        pytest.skip("creating symlinks needs Developer Mode or admin on Windows")
    with pytest.raises(di.SkipFile, match="symbolic link"):
        di.validate(link, kb, 10**7)


def test_corrupt_pdf_fails_cleanly(kb):
    f = kb / "broken.pdf"; f.write_bytes(b"%PDF-1.4 this is not really a pdf")
    r = di.index_file(f, kb)
    assert r["status"] == "failed" and "unreadable PDF" in r["reason"]
    assert db.get_document("watcher", di.path_key(f))["next_retry_at"] is not None


def test_empty_and_unsupported_files_are_skipped(kb):
    (kb / "empty.txt").write_text("", encoding="utf-8")
    assert di.index_file(kb / "empty.txt", kb)["status"] == "skipped"
    (kb / "pic.png").write_bytes(b"\x89PNG")
    assert di.index_file(kb / "pic.png", kb)["status"] == "skipped"


def test_oversized_file_is_skipped(kb):
    import local_file_service as lfs
    lfs.update_settings(knowledge={"max_file_mb": 1})
    f = kb / "big.txt"; f.write_text("x " * 600_000, encoding="utf-8")
    assert "larger than" in di.index_file(f, kb)["reason"]


def test_locked_file_is_retried(kb, monkeypatch):
    f = kb / "a.txt"; f.write_text("being written", encoding="utf-8")
    monkeypatch.setattr(di, "is_locked", lambda p: True)
    r = di.index_file(f, kb)
    assert r["status"] == "retry" and "locked" in r["reason"]


def test_model_unavailable_is_transient_with_backoff(kb):
    f = kb / "a.txt"; f.write_text("offline test", encoding="utf-8")
    FakeEmbeddings.fail_with = httpx.ConnectError("connection refused")
    for attempt in (1, 2, 3):
        r = di.index_file(f, kb)
        assert r["status"] == "retry" and r["attempts"] == attempt
    assert [di.backoff(n) for n in (1, 2, 3, 10)] == [10, 20, 40, 900]


def test_python_and_encodings_supported(kb):
    (kb / "train.py").write_text("def train():\n    # gradient clipping prevents exploding gradients\n    pass\n",
                                 encoding="utf-8")
    (kb / "latin.txt").write_bytes("caf\xe9 au lait and k-means".encode("cp1252"))
    assert di.index_file(kb / "train.py", kb)["status"] == "indexed"
    assert di.index_file(kb / "latin.txt", kb)["status"] == "indexed"


def test_logs_never_contain_document_text(kb, caplog):
    f = kb / "diary.txt"; f.write_text("my very private diary entry about salaries", encoding="utf-8")
    with caplog.at_level("DEBUG"):
        di.index_file(f, kb)
        (kb / "broken.pdf").write_bytes(b"%PDF garbage")
        di.index_file(kb / "broken.pdf", kb)
    assert "private diary" not in caplog.text and "salaries" not in caplog.text
