"""
folder_watcher.py: keeps the knowledge index in sync with the watched folder.

One `KnowledgeWatcher` per process (module-level `watcher`); starting it on a
new folder stops the old one first, so there is never more than one observer.

    watchdog events ─► pending queue (debounced per file) ─► worker thread ─► document_indexer
                                         ▲                                     │
                    periodic retry scan ─┘   (bounded exponential backoff)     ▼
                                                                         SQLite status + Chroma

* Debounce: a file is processed only after ~1.5 s without new events, and only
  when its size and modification time are unchanged between two checks, so
  files that are still being copied are not indexed half-written.
* Locked files and an unavailable embedding model are retried (10 s → 15 min,
  max 6 attempts); "Retry failed" resets the counters.
* Everything runs on a background thread; chat requests are never blocked
  (indexing writes are serialised with knowledge.INDEX_LOCK).

Lifecycle and locking (see docs/RELEASE_BLOCKER_FIXES_DEV3.md, B1):
* Every start() creates a new `_Session` (generation number, folder, its own
  stop/rescan events and status dict). Events, queue items and status writes
  from an older session are ignored, so a stale event from the previous folder
  is never indexed into the new one.
* `_life` serialises start()/stop(); `_lock` only guards the queue and is held
  for a few statements. Nothing waits for a thread while holding `_lock`, and
  the worker never takes `_life`, so start()/stop() can't deadlock with it.
* Joins are bounded (JOIN_TIMEOUT_S). If the old worker is still finishing a
  slow file, the new worker waits for it before doing anything, so at most one
  worker ever indexes at a time.
"""
from __future__ import annotations

import logging
import os
import threading
import time
from pathlib import Path
from typing import Any

from watchdog.events import FileSystemEvent, FileSystemEventHandler
from watchdog.observers import Observer

import database as db
import document_indexer as di

log = logging.getLogger(__name__)

DEBOUNCE_S = 1.5
POLL_S = 0.5
MAX_ATTEMPTS = 6
RETRY_SCAN_S = 15
JOIN_TIMEOUT_S = 5


class _Session:
    """One start() → stop() lifecycle of the watcher."""

    def __init__(self, gen: int, root: Path, state: dict[str, Any]):
        self.gen, self.root, self.state = gen, root, state
        self.stop = threading.Event()
        self.rescan = threading.Event()


class _Handler(FileSystemEventHandler):
    """Forwards watchdog events, tagged with the session they belong to."""

    def __init__(self, w: "KnowledgeWatcher", gen: int):
        self.w, self.gen = w, gen

    def on_created(self, e: FileSystemEvent):
        if not e.is_directory:
            self.w.enqueue("index", Path(e.src_path), gen=self.gen)
        else:
            self.w.request_rescan(gen=self.gen)          # a folder was copied in: scan it

    def on_modified(self, e: FileSystemEvent):
        if not e.is_directory:
            self.w.enqueue("index", Path(e.src_path), gen=self.gen)

    def on_deleted(self, e: FileSystemEvent):
        self.w.enqueue("delete", Path(e.src_path), gen=self.gen)
        if e.is_directory:
            self.w.request_rescan(gen=self.gen)

    def on_moved(self, e: FileSystemEvent):
        if e.is_directory:
            self.w.request_rescan(gen=self.gen)
        else:
            self.w.enqueue("move", Path(e.dest_path), old=Path(e.src_path), gen=self.gen)


class KnowledgeWatcher:
    def __init__(self) -> None:
        self._lock = threading.RLock()          # queue + session pointer; held briefly, never while waiting
        self._life = threading.Lock()           # serialises start()/stop(); the worker never takes it
        self._observer: Observer | None = None
        self._worker: threading.Thread | None = None
        self._session: _Session | None = None
        self._draining: threading.Thread | None = None     # old worker still finishing a file after stop()
        self._gen = 0
        self._pending: dict[str, dict[str, Any]] = {}
        self.root: Path | None = None
        self.paused = False
        self.state: dict[str, Any] = self._fresh_state()

    @staticmethod
    def _fresh_state() -> dict[str, Any]:
        return {"current_file": None, "batch_total": 0, "batch_done": 0, "last_success_at": None,
                "last_error": None, "scanning": False, "started_at": None}

    # ------------------------------------------------------------------ lifecycle
    @property
    def running(self) -> bool:
        return bool(self._worker and self._worker.is_alive())

    def start(self, root: Path) -> dict:
        root = Path(root).resolve()
        with self._life:
            sess = self._session
            if self.running and sess and sess.root == root and not sess.stop.is_set():
                return self.status()                # already watching this folder: no second instance
            previous = self._shutdown(JOIN_TIMEOUT_S)
            root.mkdir(parents=True, exist_ok=True)
            with self._lock:
                self._gen += 1
                sess = _Session(self._gen, root, self._fresh_state())
                sess.state["started_at"] = time.time()
                self._session, self.state = sess, sess.state
                self.root, self.paused = root, False
                self._pending.clear()
            sess.rescan.set()                       # initial scan
            observer = Observer()
            observer.schedule(_Handler(self, sess.gen), str(root), recursive=True)
            observer.daemon = True
            worker = threading.Thread(target=self._run, args=(sess, previous),
                                      name=f"abhify-knowledge-watcher-{sess.gen}", daemon=True)
            with self._lock:
                self._observer, self._worker = observer, worker
            observer.start()
            worker.start()
            log.info("Knowledge watcher started")
            return self.status()

    def stop(self, timeout: float = JOIN_TIMEOUT_S) -> None:
        with self._life:
            self._shutdown(timeout)

    def _shutdown(self, timeout: float) -> threading.Thread | None:
        """End the current session. Joins happen WITHOUT `_lock`, so a worker waiting for `_lock`
        can always finish. Returns the old worker if it is still busy after `timeout`."""
        with self._lock:
            sess, observer, worker = self._session, self._observer, self._worker
            if sess:
                sess.stop.set()
                sess.state["current_file"] = None
            self._session = self._observer = self._worker = None
            self._gen += 1                          # events still in flight from the old observer are now stale
            self._pending.clear()
        if observer:
            try:
                observer.stop()
                observer.join(timeout)
            except Exception:
                pass
        if worker and worker is not threading.current_thread():
            worker.join(timeout)
            if worker.is_alive():
                log.warning("Previous watcher worker is still finishing a file; the next one waits for it")
                self._draining = worker
                return worker
        return None

    def drain(self, timeout: float = 120) -> bool:
        """Wait (without any lock) until a worker left over from stop() has finished its last file.
        Used before removing a previous folder's documents, so nothing is re-added afterwards."""
        worker = self._draining
        if worker is not None:
            worker.join(timeout)
            if worker.is_alive():
                return False
            self._draining = None
        return True

    def pause(self) -> None:
        self.paused = True

    def resume(self) -> None:
        self.paused = False

    def request_rescan(self, gen: int | None = None) -> None:
        sess = self._session
        if sess and (gen is None or gen == sess.gen):
            sess.rescan.set()

    # ------------------------------------------------------------------ queue
    def enqueue(self, action: str, path: Path, old: Path | None = None, if_absent: bool = False,
                gen: int | None = None) -> None:
        key = os.path.normcase(str(path))
        with self._lock:
            sess = self._session
            if sess is None or (gen is not None and gen != sess.gen):
                return                              # stale event from a stopped or replaced session
            item = self._pending.get(key)
            if if_absent and item:
                return                      # already queued: don't reset its stability timer
            if item and item["action"] == "move" and action == "index":
                action, old = "move", item.get("old")        # keep the move semantics through modify events
            self._pending[key] = {"action": action, "path": path, "old": old, "last_event": time.time(),
                                  "stat": None, "gen": sess.gen}

    def retry_failed(self, doc_id: int | None = None) -> int:
        n = db.reset_failed(doc_id)
        self.request_rescan()
        return n

    # ------------------------------------------------------------------ worker
    def _run(self, sess: _Session, previous: threading.Thread | None = None) -> None:
        while previous is not None and previous.is_alive() and not sess.stop.is_set():
            previous.join(POLL_S)                   # never two workers indexing at the same time
        last_retry_scan = 0.0
        while not sess.stop.is_set():
            try:
                if self.paused:
                    sess.stop.wait(POLL_S)
                    continue
                if sess.rescan.is_set():
                    sess.rescan.clear()
                    self._scan(sess)
                if time.time() - last_retry_scan > RETRY_SCAN_S:
                    last_retry_scan = time.time()
                    for d in db.due_retries(time.time(), MAX_ATTEMPTS):
                        self.enqueue("index", Path(d["path_key"]), if_absent=True, gen=sess.gen)
                self._process_ready(sess)
            except Exception as e:                       # never let the worker die silently
                sess.state["last_error"] = f"{type(e).__name__}"
                log.exception("Watcher loop error")
            sess.stop.wait(POLL_S)

    def _scan(self, sess: _Session) -> None:
        """Queue every candidate file; mark tracked files that disappeared as removed."""
        root = sess.root
        if not root.exists():
            return
        sess.state["scanning"] = True
        seen: set[str] = set()
        try:
            for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
                if sess.stop.is_set():
                    return
                base = Path(dirpath)
                # prune excluded / hidden / linked folders before descending
                dirnames[:] = [d for d in dirnames if d.lower() not in di.EXCLUDED_DIRS and not d.startswith(".")
                               and not (base / d).is_symlink()]
                for f in filenames:
                    p = base / f
                    if di.exclusion_reason(p, root) and not p.suffix.lower() in di.PARSERS:
                        continue                             # silently ignore unsupported files (no noise in the UI)
                    seen.add(os.path.normcase(str(p.resolve())) if not p.is_symlink() else os.path.normcase(str(p)))
                    self.enqueue("index", p, gen=sess.gen)
            for d in db.list_documents(source="watcher"):
                if sess.stop.is_set():
                    return
                if d["path_key"] not in seen and not Path(d["path_key"]).exists():
                    di.remove_file(d["path_key"])
            with self._lock:                               # scanned files don't need the debounce delay
                for item in self._pending.values():
                    if item["gen"] == sess.gen:
                        item["last_event"] = 0
        finally:
            sess.state["scanning"] = False

    def _process_ready(self, sess: _Session) -> None:
        now = time.time()
        with self._lock:
            mine = {k: v for k, v in self._pending.items() if v["gen"] == sess.gen}
        ready = [(k, v) for k, v in mine.items() if now - v["last_event"] >= DEBOUNCE_S]
        state = sess.state
        if not ready:
            if not mine:
                state.update(batch_total=0, batch_done=0)
            return
        if state["batch_done"] >= state["batch_total"]:
            state.update(batch_total=len(mine), batch_done=0)
        for key, item in ready:
            if sess.stop.is_set() or self.paused:
                return
            path = item["path"]
            if item["action"] in ("index", "move") and path.exists():
                st = path.stat()
                sig = (st.st_size, st.st_mtime)
                if item["stat"] != sig:                    # still being written: wait one more round
                    with self._lock:
                        if self._pending.get(key) is item:
                            item.update(stat=sig, last_event=time.time())
                    continue
            with self._lock:
                if self._pending.get(key) is not item or sess.stop.is_set():
                    continue                               # replaced by a newer event, or the session ended
                self._pending.pop(key)
            self._handle(sess, item)
            state["batch_done"] += 1

    def _handle(self, sess: _Session, item: dict) -> None:
        path, root = item["path"], sess.root
        sess.state["current_file"] = path.name
        try:
            if item["action"] == "delete" or not path.exists():
                di.remove_file(os.path.normcase(str(path)))
                return
            if item["action"] == "move" and item.get("old"):
                result = di.move_file(item["old"], path, root)
            else:
                result = di.index_file(path, root)
            if result["status"] in ("indexed", "moved"):
                sess.state["last_success_at"] = time.time()
            elif result["status"] in ("failed", "retry"):
                sess.state["last_error"] = f"{result['path']}: {result.get('reason', '')}"[:200]
        finally:
            sess.state["current_file"] = None

    # ------------------------------------------------------------------ status
    def status(self) -> dict[str, Any]:
        counts = db.document_counts("watcher")
        return {
            "running": self.running, "paused": self.paused, "folder": str(self.root) if self.root else None,
            "queued": len(self._pending), **self.state,
            "counts": {"discovered": counts.get("discovered", 0), "indexed": counts.get("indexed", 0),
                       "skipped": counts.get("skipped", 0), "failed": counts.get("failed", 0),
                       "pending": counts.get("pending", 0)},
        }


watcher = KnowledgeWatcher()
