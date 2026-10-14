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


class _Handler(FileSystemEventHandler):
    def __init__(self, w: "KnowledgeWatcher"):
        self.w = w

    def on_created(self, e: FileSystemEvent):
        if not e.is_directory:
            self.w.enqueue("index", Path(e.src_path))
        else:
            self.w.request_rescan()             # a folder was copied in: scan it

    def on_modified(self, e: FileSystemEvent):
        if not e.is_directory:
            self.w.enqueue("index", Path(e.src_path))

    def on_deleted(self, e: FileSystemEvent):
        self.w.enqueue("delete", Path(e.src_path))
        if e.is_directory:
            self.w.request_rescan()

    def on_moved(self, e: FileSystemEvent):
        if e.is_directory:
            self.w.request_rescan()
        else:
            self.w.enqueue("move", Path(e.dest_path), old=Path(e.src_path))


class KnowledgeWatcher:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._observer: Observer | None = None
        self._worker: threading.Thread | None = None
        self._stop = threading.Event()
        self._pending: dict[str, dict[str, Any]] = {}
        self._rescan = threading.Event()
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
        with self._lock:
            if self.running and self.root == root:
                return self.status()                # already watching this folder: no second instance
            self.stop()
            root.mkdir(parents=True, exist_ok=True)
            self.root, self.paused = root, False
            self._stop.clear()
            self.state = self._fresh_state()
            self.state["started_at"] = time.time()
            self._observer = Observer()
            self._observer.schedule(_Handler(self), str(root), recursive=True)
            self._observer.daemon = True
            self._observer.start()
            self._worker = threading.Thread(target=self._run, name="abhify-knowledge-watcher", daemon=True)
            self._worker.start()
            self.request_rescan()                   # initial scan
            log.info("Knowledge watcher started")
            return self.status()

    def stop(self, timeout: float = 10) -> None:
        # Grab references under the lock, but JOIN outside it: the worker may be waiting
        # for this lock, and joining while holding it would deadlock until the timeout.
        with self._lock:
            self._stop.set()
            observer, worker = self._observer, self._worker
            self._observer = self._worker = None
        if observer:
            try:
                observer.stop()
                observer.join(timeout)
            except Exception:
                pass
        if worker and worker is not threading.current_thread():
            worker.join(timeout)
        with self._lock:
            self._pending.clear()
            self.state["current_file"] = None

    def pause(self) -> None:
        self.paused = True

    def resume(self) -> None:
        self.paused = False

    def request_rescan(self) -> None:
        self._rescan.set()

    # ------------------------------------------------------------------ queue
    def enqueue(self, action: str, path: Path, old: Path | None = None, if_absent: bool = False) -> None:
        key = os.path.normcase(str(path))
        with self._lock:
            item = self._pending.get(key)
            if if_absent and item:
                return                      # already queued: don't reset its stability timer
            if item and item["action"] == "move" and action == "index":
                action, old = "move", item.get("old")        # keep the move semantics through modify events
            self._pending[key] = {"action": action, "path": path, "old": old, "last_event": time.time(),
                                  "stat": None}

    def retry_failed(self, doc_id: int | None = None) -> int:
        n = db.reset_failed(doc_id)
        self.request_rescan()
        return n

    # ------------------------------------------------------------------ worker
    def _run(self) -> None:
        last_retry_scan = 0.0
        while not self._stop.is_set():
            try:
                if self.paused:
                    time.sleep(POLL_S)
                    continue
                if self._rescan.is_set():
                    self._rescan.clear()
                    self._scan()
                if time.time() - last_retry_scan > RETRY_SCAN_S:
                    last_retry_scan = time.time()
                    for d in db.due_retries(time.time(), MAX_ATTEMPTS):
                        self.enqueue("index", Path(d["path_key"]), if_absent=True)
                self._process_ready()
            except Exception as e:                       # never let the worker die silently
                self.state["last_error"] = f"{type(e).__name__}"
                log.exception("Watcher loop error")
            self._stop.wait(POLL_S)

    def _scan(self) -> None:
        """Queue every candidate file; mark tracked files that disappeared as removed."""
        root = self.root
        if root is None or not root.exists():
            return
        self.state["scanning"] = True
        seen: set[str] = set()
        for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
            if self._stop.is_set():
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
                self.enqueue("index", p)
        for d in db.list_documents(source="watcher"):
            if d["path_key"] not in seen and not Path(d["path_key"]).exists():
                di.remove_file(d["path_key"])
        with self._lock:                               # scanned files don't need the debounce delay
            for item in self._pending.values():
                item["last_event"] = 0
        self.state["scanning"] = False

    def _process_ready(self) -> None:
        now = time.time()
        with self._lock:
            ready = [(k, v) for k, v in self._pending.items() if now - v["last_event"] >= DEBOUNCE_S]
        if not ready:
            if not self._pending:
                self.state.update(batch_total=0, batch_done=0)
            return
        if self.state["batch_done"] >= self.state["batch_total"]:
            self.state.update(batch_total=len(self._pending), batch_done=0)
        for key, item in ready:
            if self._stop.is_set() or self.paused:
                return
            path = item["path"]
            if item["action"] in ("index", "move") and path.exists():
                st = path.stat()
                sig = (st.st_size, st.st_mtime)
                if item["stat"] != sig:                    # still being written: wait one more round
                    with self._lock:
                        if key in self._pending:
                            self._pending[key].update(stat=sig, last_event=time.time())
                    continue
            with self._lock:
                self._pending.pop(key, None)
            self._handle(item)
            self.state["batch_done"] += 1

    def _handle(self, item: dict) -> None:
        path, root = item["path"], self.root
        self.state["current_file"] = path.name
        try:
            if item["action"] == "delete" or not path.exists():
                di.remove_file(os.path.normcase(str(path)))
                return
            if item["action"] == "move" and item.get("old"):
                result = di.move_file(item["old"], path, root)
            else:
                result = di.index_file(path, root)
            if result["status"] in ("indexed", "moved"):
                self.state["last_success_at"] = time.time()
            elif result["status"] in ("failed", "retry"):
                self.state["last_error"] = f"{result['path']}: {result.get('reason', '')}"[:200]
        finally:
            self.state["current_file"] = None

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
