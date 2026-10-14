"""
document_indexer.py: one file in, searchable chunks out.

Pipeline:  discovery → validation → text extraction → normalisation → chunking
           → embedding → ChromaDB indexing → status in SQLite

Safety rules (applied before a file is ever opened):
  * the file must be inside the configured knowledge folder (resolved path, so
    `..`, symlinks and junctions that escape the folder are rejected)
  * symbolic links are never followed
  * excluded by name/folder: secrets (.env, keys, certificates, credentials,
    tokens), VCS/venv/node_modules/caches, temp & partial downloads, databases,
    model weights, backups, and Abhify.AI's own data folder
  * text files are scanned for secret-looking content (private keys, API
    tokens, password assignments) and skipped if found
Document contents are never logged.

Errors are classified:
  SkipFile        permanent (unsupported, excluded, empty, secret): status 'skipped'
  TransientError  worth retrying (locked file, Ollama/model unavailable): retried with backoff
  anything else   'failed' (e.g. corrupt PDF); can be retried manually
"""
from __future__ import annotations

import fnmatch
import hashlib
import logging
import os
import re
import time
from pathlib import Path
from typing import Callable

from langchain_core.documents import Document

import database as db
import knowledge
import local_file_service as lfs

log = logging.getLogger(__name__)


class SkipFile(Exception):
    """Permanently not indexable (unsupported, excluded, empty, sensitive)."""


class TransientError(Exception):
    """Temporary problem; retry later (locked file, model/Ollama unavailable)."""


# --------------------------------------------------------------------------- parsers (extensible registry)
def _read_text(path: Path) -> list[Document]:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-16", "cp1252"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        text = raw.decode("utf-8", errors="ignore")
    if "\x00" in text[:2000] and not text.startswith("﻿"):
        raise SkipFile("binary content")
    return [Document(page_content=text)]


def _read_pdf(path: Path) -> list[Document]:
    try:
        docs = knowledge.pdf_pages(path)
    except Exception as e:                                    # pypdf raises many types on corrupt files
        raise ValueError(f"unreadable PDF ({type(e).__name__})") from e
    if not docs:
        raise SkipFile("no extractable text (scanned PDFs need OCR)")
    return docs


PARSERS: dict[str, Callable[[Path], list[Document]]] = {
    ".pdf": _read_pdf,
    ".txt": _read_text,
    ".md": _read_text,
    ".markdown": _read_text,
    ".py": _read_text,
}
TEXT_TYPES = {".txt", ".md", ".markdown", ".py"}


# --------------------------------------------------------------------------- exclusions
EXCLUDED_DIRS = {".git", ".svn", ".hg", "venv", ".venv", "env", "node_modules", "__pycache__", ".cache",
                 ".pytest_cache", ".mypy_cache", ".idea", ".vscode", "site-packages", "dist", "build",
                 "backups", ".ollama", "abhifyai", "$recycle.bin", "system volume information"}
EXCLUDED_NAMES = ["*.env", ".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx", "*.crt", "*.cer", "*.kdbx",
                  "id_rsa*", "id_dsa*", "id_ecdsa*", "id_ed25519*", "*.ppk", "*secret*", "*credential*",
                  # credential files, but not ML notes like "tokenization.md" / "tokenizer.py"
                  "token", "token.*", "*.token", "*_token.*", "*-token.*", "access_token*", "refresh_token*",
                  "password*.txt", "passwords*", "*.password", ".npmrc", ".pypirc", ".netrc", ".git-credentials",
                  "*.tmp", "*.temp", "~$*", "*.part", "*.partial", "*.crdownload", "*.download", "*.swp", "*~",
                  ".ds_store", "thumbs.db", "desktop.ini",
                  "*.db", "*.sqlite", "*.sqlite3", "*.db-wal", "*.db-shm",
                  "*.gguf", "*.safetensors", "*.bin", "*.pt", "*.pth", "*.onnx", "*.ckpt", "*.h5"]
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),                                  # AWS access key id
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),                        # GitHub token
    re.compile(r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_\-]{20,}\b"),             # OpenAI/Anthropic-style keys
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),                      # Slack token
    re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"),                            # Google API key
    re.compile(r"(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\b\s*[:=]\s*['\"][^'\"\s]{8,}['\"]"),
]


def exclusion_reason(path: Path, root: Path) -> str | None:
    """Why `path` must not be indexed, or None if it may be. Pure path check; never opens the file."""
    try:
        rel = path.relative_to(root)
    except ValueError:
        return "outside the knowledge folder"
    for part in rel.parts[:-1]:
        if part.lower() in EXCLUDED_DIRS or part.startswith("."):
            return f"excluded folder '{part}'"
    name = path.name.lower()
    for pat in EXCLUDED_NAMES:
        if fnmatch.fnmatch(name, pat):
            return "excluded file type (sensitive, temporary or binary)"
    if path.suffix.lower() not in PARSERS:
        return f"unsupported file type '{path.suffix or 'none'}'"
    return None


def contains_secret(text: str) -> bool:
    return any(p.search(text) for p in SECRET_PATTERNS)


def validate(path: Path, root: Path, max_bytes: int) -> Path:
    """Return the safe, resolved path or raise SkipFile."""
    root = root.resolve()
    if path.is_symlink():
        raise SkipFile("symbolic link (not followed)")
    resolved = path.resolve()
    try:
        resolved.relative_to(root)                         # rejects ../ and links/junctions leaving the folder
    except ValueError:
        raise SkipFile("outside the knowledge folder")
    cur = path.parent
    while cur != root and root in cur.parents:              # a linked folder inside the knowledge folder
        if cur.is_symlink():
            raise SkipFile("inside a linked folder (not followed)")
        cur = cur.parent
    reason = exclusion_reason(resolved, root)
    if reason:
        raise SkipFile(reason)
    if not resolved.is_file():
        raise SkipFile("not a regular file")
    size = resolved.stat().st_size
    if size == 0:
        raise SkipFile("empty file")
    if size > max_bytes:
        raise SkipFile(f"larger than {max_bytes // (1024 * 1024)} MB")
    return resolved


def normalize(text: str) -> str:
    text = text.replace("\x00", "").replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+\n", "\n", text)
    return re.sub(r"\n{4,}", "\n\n\n", text).strip()


def path_key(path: Path) -> str:
    """Stable identity for a watched file (Windows paths are case-insensitive)."""
    return os.path.normcase(str(path.resolve()))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def is_locked(path: Path) -> bool:
    """True while another program holds the file open for writing (Windows sharing rules)."""
    try:
        with path.open("r+b"):
            return False
    except PermissionError:
        return True
    except OSError:
        return False


def extract(path: Path) -> list[Document]:
    docs = PARSERS[path.suffix.lower()](path)
    for d in docs:
        d.page_content = normalize(d.page_content)
    docs = [d for d in docs if d.page_content]
    if not docs:
        raise SkipFile("no text content")
    if path.suffix.lower() in TEXT_TYPES and contains_secret("\n".join(d.page_content for d in docs)):
        raise SkipFile("looks like it contains a password, key or token (skipped for your safety)")
    return docs


def _is_transient(e: Exception) -> bool:
    text = f"{type(e).__name__} {e}".lower()
    return any(s in text for s in ("connect", "timeout", "refused", "unavailable", "not found", "pull", "502", "503",
                                   "remoteprotocol", "readerror", "no such model"))


# --------------------------------------------------------------------------- indexing one file
def index_file(path: Path, root: Path) -> dict:
    """Index or re-index one watched file. Updates the documents table. Returns a status dict."""
    key = path_key(path)
    display = str(path.resolve().relative_to(root.resolve())) if path.exists() else path.name
    max_bytes = lfs.load_settings()["knowledge"].get("max_file_mb", 20) * 1024 * 1024
    prev = db.get_document("watcher", key)
    try:
        safe = validate(path, root, max_bytes)
        if is_locked(safe):
            raise TransientError("file is locked by another program")
        st = safe.stat()
        sha = sha256_file(safe)
        if prev and prev["status"] == "indexed" and prev["sha256"] == sha:
            if prev.get("mtime") != st.st_mtime:
                db.save_document("watcher", key, mtime=st.st_mtime)
            return {"status": "unchanged", "path": display}
        if knowledge.rebuild_required():
            raise TransientError("knowledge index needs a rebuild for the new embedding model")
        knowledge.adopt_index_model_if_unset()
        docs = extract(safe)
        try:
            n = knowledge.replace_document("watcher", key, display, sha, docs)
        except Exception as e:
            if _is_transient(e):
                raise TransientError(f"embedding model unavailable ({type(e).__name__})") from e
            raise
        db.save_document("watcher", key, path=display, sha256=sha, size=st.st_size, mtime=st.st_mtime, chunks=n,
                         status="indexed", error=None, attempts=0, next_retry_at=None,
                         embed_model=knowledge.index_embed_model(), indexed_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
        return {"status": "indexed", "path": display, "chunks": n}
    except SkipFile as e:
        if prev and prev["status"] == "indexed":            # became excluded/empty: drop its old chunks
            knowledge.remove_document(key)
        db.save_document("watcher", key, path=display, status="skipped", error=str(e), chunks=0)
        return {"status": "skipped", "path": display, "reason": str(e)}
    except TransientError as e:
        attempts = (prev or {}).get("attempts", 0) + 1
        db.save_document("watcher", key, path=display, status="pending" if prev is None or prev["status"] != "indexed"
                         else "indexed", error=str(e), attempts=attempts, next_retry_at=time.time() + backoff(attempts))
        return {"status": "retry", "path": display, "reason": str(e), "attempts": attempts}
    except Exception as e:                                   # corrupt file, parser bug, ...
        attempts = (prev or {}).get("attempts", 0) + 1
        db.save_document("watcher", key, path=display, status="failed", error=str(e)[:300], attempts=attempts,
                         next_retry_at=time.time() + backoff(attempts))
        log.warning("Indexing failed (%s)", type(e).__name__)
        return {"status": "failed", "path": display, "reason": str(e)[:300], "attempts": attempts}


def backoff(attempts: int) -> float:
    """10 s, 20 s, 40 s ... capped at 15 minutes."""
    return min(10 * 2 ** max(attempts - 1, 0), 900)


def remove_file(key: str) -> int:
    """A watched file was deleted: drop its chunks and mark it removed."""
    n = knowledge.remove_document(key)
    if db.get_document("watcher", key):
        db.save_document("watcher", key, status="removed", chunks=0, error=None)
    return n


def move_file(old: Path, new: Path, root: Path) -> dict:
    """Rename inside the folder: reuse the vectors if the content is unchanged, else re-index."""
    old_key, new_key = os.path.normcase(str(old)), path_key(new)
    prev = db.get_document("watcher", old_key)
    try:
        safe = validate(new, root, lfs.load_settings()["knowledge"].get("max_file_mb", 20) * 1024 * 1024)
    except SkipFile:
        remove_file(old_key)
        return index_file(new, root)
    if prev and prev["status"] == "indexed" and prev["sha256"] == sha256_file(safe):
        display = str(safe.relative_to(root.resolve()))
        n = knowledge.move_document(old_key, new_key, display)
        db.save_document("watcher", old_key, status="removed", chunks=0)
        db.save_document("watcher", new_key, path=display, sha256=prev["sha256"], size=prev["size"],
                         mtime=safe.stat().st_mtime, chunks=n, status="indexed", error=None, attempts=0,
                         embed_model=prev.get("embed_model"), indexed_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
        return {"status": "moved", "path": display, "chunks": n}
    remove_file(old_key)
    return index_file(new, root)


def extract_for_rebuild(doc: dict) -> list[Document]:
    """Re-read a tracked document's original for an embedding-model rebuild."""
    if doc["source"] == "upload":
        matches = list(lfs.uploads_dir().glob(f"{doc['sha256'][:12]}-*"))
        if not matches:
            raise FileNotFoundError(f"original upload missing: {doc['path']}")
        p = matches[0]
        return knowledge.pdf_pages(p) if p.suffix.lower() == ".pdf" else _read_text(p)
    return extract(Path(doc["path_key"]))
