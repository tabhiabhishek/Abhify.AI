"""Folder watcher: real watchdog events on a temp folder (fake embeddings)."""
import os
import threading
import time

import pytest

import database as db
import document_indexer as di
import folder_watcher as fw
import knowledge
import local_file_service as lfs


def wait_for(cond, timeout=15.0):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.2)
    return False


@pytest.fixture
def w(kb, monkeypatch):
    monkeypatch.setattr(fw, "DEBOUNCE_S", 0.4)
    monkeypatch.setattr(fw, "POLL_S", 0.1)
    watcher = fw.KnowledgeWatcher()
    yield watcher
    watcher.stop()


def indexed(path):
    d = db.get_document("watcher", di.path_key(path))
    return d and d["status"] == "indexed"


def test_initial_scan_indexes_existing_files(kb, w):
    (kb / "a.md").write_text("alpha about regularization", encoding="utf-8")
    (kb / "sub").mkdir(); (kb / "sub" / "b.txt").write_text("beta about pooling", encoding="utf-8")
    (kb / ".env").write_text("SECRET=1", encoding="utf-8")
    w.start(kb)
    assert wait_for(lambda: indexed(kb / "a.md") and indexed(kb / "sub" / "b.txt"))
    assert db.get_document("watcher", di.path_key(kb / ".env")) is None        # never even recorded
    st = w.status()
    assert st["running"] and st["counts"]["indexed"] == 2


def test_new_modified_and_deleted_files(kb, w):
    w.start(kb)
    f = kb / "live.txt"
    f.write_text("first draft about LSTMs", encoding="utf-8")
    assert wait_for(lambda: indexed(f))
    f.write_text("second draft about GRUs", encoding="utf-8")
    assert wait_for(lambda: knowledge.get_vectorstore()._collection.get(
        where={"path_key": di.path_key(f)}, include=["documents"])["documents"] == ["second draft about GRUs"])
    key = di.path_key(f)
    f.unlink()
    assert wait_for(lambda: db.get_document("watcher", key)["status"] == "removed")
    assert knowledge.count() == 0


def test_rename_detected(kb, w):
    f = kb / "before.md"; f.write_text("renamed file content about ViT", encoding="utf-8")
    w.start(kb)
    assert wait_for(lambda: indexed(f))
    old_key = di.path_key(f)
    os.replace(f, kb / "after.md")
    assert wait_for(lambda: indexed(kb / "after.md"))
    assert wait_for(lambda: db.get_document("watcher", old_key)["status"] == "removed")
    assert knowledge.count() == 1


def test_partial_write_waits_until_file_is_stable(kb, w):
    w.start(kb)
    f = kb / "growing.txt"
    stop = threading.Event()

    def writer():
        with f.open("w", encoding="utf-8") as h:
            for i in range(12):                                   # ~1.8 s of slow writing
                h.write(f"line {i} about batch normalization\n"); h.flush()
                time.sleep(0.15)
        stop.set()
    t = threading.Thread(target=writer); t.start()
    assert wait_for(lambda: indexed(f), timeout=20)
    t.join()
    docs = knowledge.get_vectorstore()._collection.get(where={"path_key": di.path_key(f)},
                                                       include=["documents"])["documents"]
    assert "line 11" in docs[0]                                   # indexed the complete file


def test_single_instance_per_folder(kb, w):
    w.start(kb)
    first = w._worker
    w.start(kb)                                                   # same folder: no new threads
    assert w._worker is first
    other = kb.parent / "Other"
    w.start(other)                                                # new folder: old instance replaced
    assert w._worker is not first and not first.is_alive() and w.root == other.resolve()


def test_stop_shuts_down_threads(kb, w):
    w.start(kb)
    worker, observer = w._worker, w._observer
    w.stop()
    assert not worker.is_alive() and not observer.is_alive() and not w.running


def test_pause_and_resume(kb, w):
    w.start(kb)
    w.pause()
    f = kb / "paused.txt"; f.write_text("queued while paused", encoding="utf-8")
    time.sleep(1.5)
    assert not indexed(f)
    w.resume()
    assert wait_for(lambda: indexed(f))


def test_retry_after_model_comes_back(kb, w, monkeypatch):
    from conftest import FakeEmbeddings
    import httpx
    monkeypatch.setattr(fw, "RETRY_SCAN_S", 0.5)
    monkeypatch.setattr(di, "backoff", lambda n: 0.3)
    FakeEmbeddings.fail_with = httpx.ConnectError("Ollama down")
    f = kb / "later.txt"; f.write_text("indexed once Ollama is back", encoding="utf-8")
    w.start(kb)
    assert wait_for(lambda: (db.get_document("watcher", di.path_key(f)) or {}).get("status") == "pending")
    FakeEmbeddings.fail_with = None                              # Ollama recovers
    assert wait_for(lambda: indexed(f), timeout=20)


def test_manual_retry_resets_failed(kb, w):
    f = kb / "bad.pdf"; f.write_bytes(b"%PDF broken")
    w.start(kb)
    assert wait_for(lambda: (db.get_document("watcher", di.path_key(f)) or {}).get("status") == "failed")
    assert w.retry_failed() == 1
    assert db.get_document("watcher", di.path_key(f))["attempts"] == 0


def test_rescan_removes_files_deleted_while_stopped(kb, w):
    f = kb / "gone.txt"; f.write_text("will vanish while stopped", encoding="utf-8")
    w.start(kb)
    assert wait_for(lambda: indexed(f))
    key = di.path_key(f)
    w.stop()
    f.unlink()
    w.start(kb)
    assert wait_for(lambda: db.get_document("watcher", key)["status"] == "removed")


def test_folder_policy_rejects_broad_or_sensitive_locations(kb, tmp_path):
    from pathlib import Path
    with pytest.raises(ValueError, match="drive"):
        lfs.validate_knowledge_folder(Path(Path.home().anchor))
    with pytest.raises(ValueError, match="entire user folder"):
        lfs.validate_knowledge_folder(Path.home())
    with pytest.raises(ValueError, match="can't"):
        lfs.validate_knowledge_folder(lfs.root() / "data")
    with pytest.raises(ValueError, match="absolute"):
        lfs.validate_knowledge_folder(Path("relative/folder"))
    with pytest.raises(ValueError, match="AppData"):
        lfs.validate_knowledge_folder(Path.home() / "AppData" / "Local" / "Notes")
    ok = Path.home() / "Documents" / "Abhify_Test_Notes_Not_Created"
    assert lfs.validate_knowledge_folder(ok) == ok.resolve()        # validation never creates folders
    assert not ok.exists()
