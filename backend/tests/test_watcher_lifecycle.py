"""B1 regression tests: watcher lifecycle, lock ordering and stale events (dev.3).

These use a controllable fake indexer (threading.Event gates) instead of sleeps,
so a deadlock shows up as a hard failure, not as a slow or flaky run.
"""
import threading
import time
from pathlib import Path

import pytest
from watchdog.events import FileCreatedEvent

import document_indexer as di
import folder_watcher as fw

WORKER_PREFIX = "abhify-knowledge-watcher"


class GatedIndexer:
    """Replaces document_indexer.index_file. Blocks every call until `gate` is set and records
    how many calls ran at the same time (must never exceed 1)."""

    def __init__(self):
        self.gate = threading.Event()
        self.entered = threading.Event()
        self.calls: list[Path] = []
        self._active = 0
        self.max_active = 0
        self._lock = threading.Lock()

    def __call__(self, path, root):
        with self._lock:
            self._active += 1
            self.max_active = max(self.max_active, self._active)
        self.entered.set()
        try:
            assert self.gate.wait(30), "test gate never released"
            self.calls.append(Path(path))
            return {"status": "indexed", "path": str(path)}
        finally:
            with self._lock:
                self._active -= 1


@pytest.fixture
def w(kb, monkeypatch):
    monkeypatch.setattr(fw, "DEBOUNCE_S", 0.1)
    monkeypatch.setattr(fw, "POLL_S", 0.05)
    watcher = fw.KnowledgeWatcher()
    yield watcher
    watcher.stop(timeout=5)


@pytest.fixture
def gated(monkeypatch):
    g = GatedIndexer()
    monkeypatch.setattr(di, "index_file", g)
    yield g
    g.gate.set()                                   # never leave a worker blocked after the test


def folders(kb):
    a, b = kb / "A", kb.parent / "B"
    a.mkdir(exist_ok=True); b.mkdir(exist_ok=True)
    return a, b


def alive_workers():
    return [t for t in threading.enumerate() if t.name.startswith(WORKER_PREFIX) and t.is_alive()]


def test_switch_while_worker_needs_the_lock_does_not_deadlock(kb, w, gated, monkeypatch):
    """The dev.2 bug: start() held _lock while joining a worker that needed _lock.
    A huge join timeout makes a deadlock visible as a hang instead of hiding it."""
    monkeypatch.setattr(fw, "JOIN_TIMEOUT_S", 60)
    a, b = folders(kb)
    (a / "a1.txt").write_text("first", encoding="utf-8")
    (a / "a2.txt").write_text("second", encoding="utf-8")
    (b / "b1.txt").write_text("other folder", encoding="utf-8")
    w.start(a)
    assert gated.entered.wait(10)                  # worker is inside index_file for one A file
    old_worker, old_sess = w._worker, w._session

    done = threading.Event()
    t = threading.Thread(target=lambda: (w.start(b), done.set()), daemon=True)
    t.start()
    assert old_sess.stop.wait(5)                   # start(B) is now shutting the old session down
    gated.gate.set()                               # worker returns while start() waits for it
    assert done.wait(5), "start() blocked: worker and start() are waiting on each other"
    assert not old_worker.is_alive()
    assert w.root == b.resolve() and w.running

    end = time.time() + 10
    while time.time() < end and b / "b1.txt" not in gated.calls:
        time.sleep(0.05)
    assert b / "b1.txt" in gated.calls
    a_calls = [p for p in gated.calls if p.parent == a]
    assert len(a_calls) == 1                       # only the in-flight A file; the queued A file is dropped
    assert gated.max_active == 1


def test_switch_while_worker_waits_for_queue_lock(kb, w, monkeypatch):
    """Exact dev.2 shape: the worker is blocked on _lock when start() begins. start() must never
    join while holding _lock, otherwise this hangs for the whole (huge) join timeout."""
    monkeypatch.setattr(fw, "JOIN_TIMEOUT_S", 60)
    a, b = folders(kb)
    w.start(a)
    time.sleep(0.3)                                # worker is idle-polling
    w._lock.acquire()                              # the worker's next _process_ready() blocks on this
    try:
        time.sleep(0.3)
        done = threading.Event()
        t = threading.Thread(target=lambda: (w.start(b), done.set()), daemon=True)
        t.start()
        time.sleep(0.2)                            # start(B) is now also waiting for _lock
    finally:
        w._lock.release()                          # both race for the lock; neither may deadlock
    assert done.wait(5), "start() and the worker deadlocked on _lock"
    assert w.root == b.resolve() and len(alive_workers()) == 1


def test_start_is_bounded_and_workers_never_overlap(kb, w, gated, monkeypatch):
    """If the old worker is stuck on a slow file, start() still returns within the join timeout,
    and the new worker waits instead of indexing alongside it."""
    monkeypatch.setattr(fw, "JOIN_TIMEOUT_S", 0.3)
    a, b = folders(kb)
    (a / "slow.txt").write_text("slow", encoding="utf-8")
    (a / "queued.txt").write_text("queued", encoding="utf-8")
    (b / "new.txt").write_text("new", encoding="utf-8")
    w.start(a)
    assert gated.entered.wait(10)
    old_worker = w._worker

    t0 = time.monotonic()
    w.start(b)
    assert time.monotonic() - t0 < 3               # bounded: the API request isn't held up
    assert old_worker.is_alive() and w.running     # old one still finishing, new one waiting for it

    gated.entered.clear()
    assert not gated.entered.wait(0.5)             # new worker hasn't started indexing in parallel
    gated.gate.set()
    old_worker.join(5)
    assert not old_worker.is_alive()
    end = time.time() + 10
    while time.time() < end and b / "new.txt" not in gated.calls:
        time.sleep(0.05)
    assert b / "new.txt" in gated.calls
    a_calls = [p for p in gated.calls if p.parent == a]
    assert len(a_calls) == 1                       # only the in-flight file; the other queued A file is dropped
    assert gated.max_active == 1


def test_drain_waits_for_leftover_worker(kb, w, gated, monkeypatch):
    """Changing folders removes the old folder's documents only after a still-busy worker is done."""
    monkeypatch.setattr(fw, "JOIN_TIMEOUT_S", 0.2)
    a, _ = folders(kb)
    (a / "big.txt").write_text("big", encoding="utf-8")
    w.start(a)
    assert gated.entered.wait(10)
    w.stop(timeout=0.2)                                         # worker still inside index_file
    assert w.drain(timeout=0.3) is False                        # bounded: reports "still busy"
    gated.gate.set()
    assert w.drain(timeout=10) is True and w._draining is None
    assert w.drain(timeout=0) is True                           # nothing left to wait for


def test_stale_events_from_old_session_are_ignored(kb, w):
    a, b = folders(kb)
    w.start(a)
    old_gen = w._session.gen
    old_handler = fw._Handler(w, old_gen)
    w.start(b)
    w.enqueue("index", a / "late.txt", gen=old_gen)
    old_handler.on_created(FileCreatedEvent(str(a / "late2.txt")))
    assert not any("late" in k for k in w._pending)
    w.request_rescan(gen=old_gen)
    assert not w._session.rescan.is_set() or w._session.gen != old_gen


def test_events_after_stop_are_ignored(kb, w):
    a, _ = folders(kb)
    w.start(a)
    gen = w._session.gen
    w.stop()
    w.enqueue("index", a / "after-stop.txt", gen=gen)
    w.enqueue("index", a / "after-stop2.txt")
    assert w._pending == {} and not w.running


def test_repeated_folder_switching_keeps_one_worker(kb, w, monkeypatch):
    monkeypatch.setattr(fw, "JOIN_TIMEOUT_S", 10)
    a, b = folders(kb)
    (a / "a.txt").write_text("alpha about attention", encoding="utf-8")
    (b / "b.txt").write_text("beta about convolution", encoding="utf-8")
    for i in range(30):
        target = a if i % 2 == 0 else b
        t0 = time.monotonic()
        w.start(target)
        assert time.monotonic() - t0 < fw.JOIN_TIMEOUT_S, f"switch {i} hit the join timeout"
        assert len(alive_workers()) == 1, f"switch {i}: {[t.name for t in alive_workers()]}"
        assert w.root == target.resolve()
        if i % 5 == 0:
            w.pause(); w.resume(); w.request_rescan()
    w.stop()
    assert alive_workers() == [] and not w.running


def test_stop_start_same_folder_loop(kb, w):
    a, _ = folders(kb)
    for _ in range(20):
        w.start(a)
        assert w.running
        w.stop()
        assert not w.running and alive_workers() == []
