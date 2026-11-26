"""Read-only data integrity report for an Abhify.AI data folder (Windows desktop).

Usage:
    python integrity_report.py <data-root> <out.json>                # one snapshot
    python integrity_report.py --compare <before.json> <after.json>  # what changed

Never writes to the data folder: SQLite is read through a snapshot copy made with the SQLite backup API (opened read-only),
JSON stores and folders are only read. Per SQLite table: row count and a SHA-256 over every row (ordered by rowid or all
columns). Per JSON store (data/*.json, config/settings.json): record counts and a SHA-256 of the canonical JSON.
Per data folder (chroma index, documents): file count, total bytes and a SHA-256 over (relative path, size, content).
Only counts and hashes are written: no personal content leaves the data folder."""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import sys
import tempfile
from pathlib import Path


def _sqlite(db: Path) -> dict:
    if not db.exists():
        return {"present": False}
    tmp = Path(tempfile.mkdtemp(prefix="abhify-integrity-")) / "snapshot.db"
    src = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    dst = sqlite3.connect(tmp)
    src.backup(dst)
    src.close()
    out = {"present": True, "user_version": dst.execute("PRAGMA user_version").fetchone()[0], "tables": {}}
    names = [r[0] for r in dst.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for t in names:
        if t.endswith(("_data", "_idx", "_docsize", "_config", "_content")) and any(t.startswith(n + "_") for n in names):
            continue                                            # FTS shadow tables: derived from their base table
        cols = [r[1] for r in dst.execute(f'PRAGMA table_info("{t}")')]
        try:
            rows = dst.execute(f'SELECT * FROM "{t}" ORDER BY rowid').fetchall()
        except sqlite3.OperationalError:
            rows = sorted(dst.execute(f'SELECT * FROM "{t}"').fetchall(), key=repr)
        h = hashlib.sha256()
        for r in rows:
            h.update(repr(tuple(r)).encode("utf-8"))
        out["tables"][t] = {"rows": len(rows), "columns": cols, "sha256": h.hexdigest()}
    meta = dst.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'").fetchone()
    if meta:
        out["schema_version_table"] = [list(r) for r in dst.execute("SELECT * FROM schema_version")]
    dst.close()
    tmp.unlink(missing_ok=True)
    return out


def _count(obj) -> dict:
    if isinstance(obj, list):
        return {"type": "list", "items": len(obj)}
    if isinstance(obj, dict):
        return {"type": "object", "keys": len(obj), "sizes": {k: (len(v) if isinstance(v, (list, dict)) else None) for k, v in sorted(obj.items())}}
    return {"type": type(obj).__name__}


def _json(path: Path) -> dict:
    try:
        raw = path.read_bytes()
        obj = json.loads(raw)
    except (OSError, ValueError) as e:
        return {"present": path.exists(), "error": type(e).__name__}
    return {"present": True, **_count(obj), "sha256": hashlib.sha256(json.dumps(obj, sort_keys=True, ensure_ascii=False).encode()).hexdigest()}


def _folder(path: Path) -> dict:
    if not path.exists():
        return {"present": False}
    files = sorted(p for p in path.rglob("*") if p.is_file())
    h = hashlib.sha256()
    total = 0
    for f in files:
        data = f.read_bytes()
        total += len(data)
        h.update(f"{f.relative_to(path).as_posix()}|{len(data)}|".encode())
        h.update(hashlib.sha256(data).digest())
    return {"present": True, "files": len(files), "bytes": total, "sha256": h.hexdigest()}


def _chroma_logical(path: Path) -> dict:
    """Logical content of the vector index (per collection: chunk count and a SHA-256 over ids, texts and metadata), read
    from a temporary COPY so the original files are never opened (Chroma rewrites pages when a database is opened)."""
    if not path.exists():
        return {"present": False}
    import shutil
    try:
        import chromadb
    except ImportError:
        return {"present": True, "error": "chromadb not installed"}
    tmp = Path(tempfile.mkdtemp(prefix="abhify-integrity-chroma-")) / "chroma"
    shutil.copytree(path, tmp)
    out = {"present": True, "collections": {}}
    client = chromadb.PersistentClient(path=str(tmp), settings=chromadb.config.Settings(anonymized_telemetry=False))
    for col in sorted(client.list_collections(), key=lambda c: c.name):
        g = col.get(include=["documents", "metadatas"])
        rows = sorted(zip(g["ids"], g["documents"] or [], [json.dumps(m, sort_keys=True) for m in (g["metadatas"] or [])]))
        out["collections"][col.name] = {"chunks": len(rows), "sha256": hashlib.sha256(repr(rows).encode("utf-8")).hexdigest()}
    del client
    shutil.rmtree(tmp.parent, ignore_errors=True)
    return out


def snapshot(root: Path) -> dict:
    data = root / "data"
    return {
        "root": "<data-root>",                                  # no user path in the report
        "sqlite": _sqlite(data / "abhify.db"),
        "json": {p.name: _json(p) for p in sorted(data.glob("*.json"))} | {"config/settings.json": _json(root / "config" / "settings.json")},
        "folders": {"data/chroma": _folder(data / "chroma"), "data/documents": _folder(data / "documents"), "data/memory": _folder(data / "memory")},
        "chroma_logical": _chroma_logical(data / "chroma"),
    }


def compare(before: dict, after: dict) -> dict:
    diff = {"sqlite_tables": {}, "json": {}, "folders": {}}
    bt, at = before["sqlite"].get("tables", {}), after["sqlite"].get("tables", {})
    for t in sorted(set(bt) | set(at)):
        b, a = bt.get(t), at.get(t)
        if b != a:
            diff["sqlite_tables"][t] = {"before": b and {k: b[k] for k in ("rows", "sha256")}, "after": a and {k: a[k] for k in ("rows", "sha256")},
                                        "columns_added": sorted(set((a or {}).get("columns", [])) - set((b or {}).get("columns", [])))}
    for k in sorted(set(before["json"]) | set(after["json"])):
        if before["json"].get(k) != after["json"].get(k):
            diff["json"][k] = {"before": before["json"].get(k), "after": after["json"].get(k)}
    for k in sorted(set(before["folders"]) | set(after["folders"])):
        if before["folders"].get(k) != after["folders"].get(k):
            diff["folders"][k] = {"before": before["folders"].get(k), "after": after["folders"].get(k)}
    diff["schema"] = {"before": before["sqlite"].get("user_version"), "after": after["sqlite"].get("user_version")}
    diff["chroma_logical"] = None if before.get("chroma_logical") == after.get("chroma_logical") else {"before": before.get("chroma_logical"), "after": after.get("chroma_logical")}
    # raw Chroma files can differ while their content is the same (internal page rewrites on open): judged by logical content
    raw_folders = {k: v for k, v in diff["folders"].items() if k != "data/chroma"}
    diff["identical_content"] = not (diff["sqlite_tables"] or diff["json"] or raw_folders or diff["chroma_logical"])
    diff["identical"] = diff["identical_content"] and not diff["folders"]
    return diff


if __name__ == "__main__":
    if sys.argv[1] == "--compare":
        b, a = (json.loads(Path(p).read_text(encoding="utf-8")) for p in sys.argv[2:4])
        print(json.dumps(compare(b, a), indent=1, ensure_ascii=False))
    else:
        Path(sys.argv[2]).write_text(json.dumps(snapshot(Path(sys.argv[1])), indent=1, ensure_ascii=False), encoding="utf-8")
        print("wrote", sys.argv[2])
