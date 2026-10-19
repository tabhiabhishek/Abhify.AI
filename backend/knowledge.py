"""
knowledge.py: the local document knowledge base (ChromaDB + Ollama embeddings).

Documents come from two places, stored in ONE active Chroma collection
(`knowledge_base`, the collection created in v1.0.0):
  * uploads:  files added through the app (originals kept in <data>/documents/uploads)
  * watcher:  files in the watched knowledge folder (see folder_watcher.py)

Every chunk carries `path_key` (document identity) and `sha256` (content
version) metadata. Chunk ids are `<source>-<key hash>-<content hash>-<n>`.

Update policy (never lose working content):
  1. new chunks are embedded and ADDED first;
  2. only after that succeeds are the old chunks of the same document deleted.
  If embedding fails, the previous version stays searchable.

Embedding-model safety:
  The model that built the active collection is recorded in settings
  (`knowledge.index_embed_model`). If the configured embedding model differs,
  the index is "rebuild required": searches return nothing rather than mixing
  incompatible vectors, and `rebuild_index()` builds a NEW collection, validates
  it, and only then switches over (the old collection is kept).

Personal memories are deliberately NOT stored here (see memory_service.py).
"""
from __future__ import annotations

import hashlib
import logging
import re
import threading
import time
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

import chromadb
from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pypdf import PdfReader

import database as db
import local_file_service as lfs
from config import get_settings

log = logging.getLogger(__name__)

DEFAULT_COLLECTION = "knowledge_base"          # name kept from v1.0.0 so existing vectors stay usable
INDEX_LOCK = threading.RLock()                 # serialises writes from uploads, the watcher and rebuilds
splitter = RecursiveCharacterTextSplitter(chunk_size=1000, chunk_overlap=150)

# Tests swap this for deterministic fake embeddings (no Ollama needed).
embeddings_factory: Callable[[str], Any] = lambda model: OllamaEmbeddings(
    model=model, base_url=get_settings().ollama_base_url)


# --------------------------------------------------------------------------- collection / model bookkeeping
def _ksettings() -> dict:
    return lfs.load_settings()["knowledge"]


def active_collection() -> str:
    return _ksettings().get("collection") or DEFAULT_COLLECTION


def configured_embed_model() -> str:
    from system_service import active_models
    return active_models()["embed"]


def index_embed_model() -> str | None:
    return _ksettings().get("index_embed_model")


def adopt_index_model_if_unset() -> None:
    """Record which model built the collection. v1/M1 collections predate this field:
    they were built with the configured model, so adopt it once."""
    if index_embed_model() is None:
        lfs.update_settings(knowledge={"index_embed_model": configured_embed_model()})


def rebuild_required() -> bool:
    m = index_embed_model()
    return m is not None and m != configured_embed_model()


@lru_cache
def _client(path: str) -> chromadb.api.ClientAPI:
    # Telemetry off explicitly: nothing about the user's index leaves the device (offline-first, dev.6).
    return chromadb.PersistentClient(path=path, settings=chromadb.config.Settings(anonymized_telemetry=False))


def client() -> chromadb.api.ClientAPI:
    return _client(str(lfs.chroma_dir()))


@lru_cache
def _store(collection: str, model: str, chroma_path: str) -> Chroma:
    return Chroma(client=_client(chroma_path), collection_name=collection, embedding_function=embeddings_factory(model))


def get_vectorstore(collection: str | None = None, model: str | None = None) -> Chroma:
    """The active store, embedding with the model that built it (so queries match the vectors)."""
    collection = collection or active_collection()
    model = model or index_embed_model() or configured_embed_model()
    return _store(collection, model, str(lfs.chroma_dir()))


def reset_caches() -> None:
    _store.cache_clear()
    _client.cache_clear()


get_vectorstore.cache_clear = reset_caches     # keeps system_service._reset_model_caches() working


# --------------------------------------------------------------------------- parsing helpers
def pdf_pages(path: Path) -> list[Document]:
    """One Document per PDF page (0-based page numbers)."""
    docs = []
    for i, page in enumerate(PdfReader(str(path)).pages):
        text = page.extract_text() or ""
        if text.strip():
            docs.append(Document(page_content=text, metadata={"page": i}))
    return docs


def _key_hash(path_key: str) -> str:
    return hashlib.sha1(path_key.encode("utf-8")).hexdigest()[:12]


# --------------------------------------------------------------------------- write path
def replace_document(source: str, path_key: str, display: str, sha: str, docs: list[Document],
                     store: Chroma | None = None) -> int:
    """Index `docs` as the new version of a document, then drop its older chunks. Returns chunk count."""
    store = store or get_vectorstore()
    for d in docs:
        d.metadata.update(source=display, path_key=path_key, sha256=sha, origin=source)
    chunks = [c for c in splitter.split_documents(docs) if c.page_content.strip()]
    if not chunks:
        return 0
    prefix = f"{source}-{_key_hash(path_key)}"
    ids = [f"{prefix}-{sha[:16]}-{i}" for i in range(len(chunks))]
    with INDEX_LOCK:
        store.add_documents(chunks, ids=ids)                  # 1) add new version (raises on embed failure)
        old = store._collection.get(where={"path_key": path_key}, include=[])["ids"]
        stale = [i for i in old if i not in set(ids)]
        if stale:
            store._collection.delete(ids=stale)               # 2) only now remove the previous version
    return len(chunks)


def remove_document(path_key: str, store: Chroma | None = None) -> int:
    store = store or get_vectorstore()
    with INDEX_LOCK:
        ids = store._collection.get(where={"path_key": path_key}, include=[])["ids"]
        if ids:
            store._collection.delete(ids=ids)
    return len(ids)


def move_document(old_key: str, new_key: str, new_display: str, source: str = "watcher") -> int:
    """Rename/move: copy existing vectors under the new identity (no re-embedding), then delete the old ids."""
    store = get_vectorstore()
    with INDEX_LOCK:
        got = store._collection.get(where={"path_key": old_key}, include=["embeddings", "documents", "metadatas"])
        if not got["ids"]:
            return 0
        prefix = f"{source}-{_key_hash(new_key)}"
        new_ids, metas = [], []
        for i, (oid, meta) in enumerate(zip(got["ids"], got["metadatas"])):
            new_ids.append(f"{prefix}-{meta.get('sha256', '')[:16]}-{i}")
            metas.append({**meta, "path_key": new_key, "source": new_display})
        store._collection.add(ids=new_ids, embeddings=got["embeddings"], documents=got["documents"], metadatas=metas)
        store._collection.delete(ids=got["ids"])
    return len(new_ids)


# --------------------------------------------------------------------------- uploads
def _safe_name(name: str) -> str:
    name = Path(name).name
    return re.sub(r"[^A-Za-z0-9._ -]", "_", name)[:120] or "upload"


def add_upload(data: bytes, filename: str) -> dict:
    """Index an uploaded file once. Returns {filename, chunks, duplicate}."""
    if rebuild_required():
        raise RuntimeError("The knowledge index must be rebuilt for the new embedding model before adding files.")
    adopt_index_model_if_unset()
    name = _safe_name(filename)
    sha = hashlib.sha256(data).hexdigest()
    existing = db.find_document_by_hash(sha)
    if existing:
        return {"filename": name, "chunks": existing["chunks"], "duplicate": True, "already_as": existing["path"]}
    lfs.uploads_dir().mkdir(parents=True, exist_ok=True)
    stored = lfs.uploads_dir() / f"{sha[:12]}-{name}"
    stored.write_bytes(data)                                  # keep the original for rebuilds
    docs = pdf_pages(stored) if Path(name).suffix.lower() == ".pdf" else \
        [Document(page_content=data.decode("utf-8", errors="ignore"))]
    n = replace_document("upload", name, name, sha, docs)
    db.save_document("upload", name, path=name, sha256=sha, size=len(data), chunks=n,
                     status="indexed" if n else "failed", error=None if n else "No extractable text",
                     embed_model=index_embed_model(), indexed_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
    log.info("Indexed upload (%d chunks)", n)                 # no file names or contents in logs
    return {"filename": name, "chunks": n, "duplicate": False}


# --------------------------------------------------------------------------- read path
def search(query: str, k: int = 4) -> list[Document]:
    """Similarity search. [] when empty, or when the index needs a rebuild (never mix models)."""
    if rebuild_required() or count() == 0:
        return []
    return get_vectorstore().similarity_search(query, k=k)


def count() -> int:
    try:
        return get_vectorstore()._collection.count()
    except Exception:
        return 0


def list_sources() -> list[str]:
    if count() == 0:
        return []
    metas = get_vectorstore()._collection.get(include=["metadatas"])["metadatas"]
    return sorted({m.get("source", "unknown") for m in metas if m})


# --------------------------------------------------------------------------- rebuild for a new embedding model
REBUILD: dict[str, Any] = {"status": "idle"}


def rebuild_index(extract: Callable[[dict], list[Document]] | None = None) -> dict[str, Any]:
    """Re-embed every tracked document into a NEW collection with the configured model.

    The active collection keeps serving until the new one is complete and validated;
    then settings switch atomically. On any failure the old index stays active.
    """
    from document_indexer import extract_for_rebuild
    extract = extract or extract_for_rebuild
    new_model = configured_embed_model()
    slug = re.sub(r"[^a-z0-9]+", "-", new_model.lower()).strip("-")[:30]
    new_name = f"{DEFAULT_COLLECTION}-{slug}-{int(time.time())}"
    docs = [d for d in db.list_documents() if d["status"] == "indexed"]
    REBUILD.update(status="running", model=new_model, collection=new_name, total=len(docs), done=0, error=None)
    store = _store(new_name, new_model, str(lfs.chroma_dir()))
    expected = 0
    try:
        with INDEX_LOCK:
            for d in docs:
                parts = extract(d)
                expected += replace_document(d["source"], d["path_key"], d["path"], d["sha256"], parts, store=store)
                REBUILD["done"] += 1
            got = store._collection.count()
            if got != expected:
                raise RuntimeError(f"validation failed: expected {expected} chunks, found {got}")
            if expected:
                store.similarity_search("test", k=1)          # the new model really answers queries
            k = _ksettings()
            prev = [c for c in k.get("previous_collections", []) if c != new_name] + [k.get("collection") or DEFAULT_COLLECTION]
            lfs.update_settings(knowledge={"collection": new_name, "index_embed_model": new_model,
                                           "previous_collections": prev})
        with db.connect() as c:
            c.execute("UPDATE documents SET embed_model = ? WHERE status = 'indexed'", (new_model,))
        REBUILD.update(status="done", chunks=expected)
        log.info("Rebuilt knowledge index with %s (%d chunks)", new_model, expected)
    except Exception as e:
        REBUILD.update(status="failed", error=str(e)[:300])
        try:
            client().delete_collection(new_name)               # discard the half-built index
        except Exception:
            pass
        log.warning("Knowledge rebuild failed; previous index still active: %s", type(e).__name__)
    return dict(REBUILD)


def delete_previous_collections() -> list[str]:
    """Free disk space by removing collections superseded by a rebuild (explicit user action)."""
    k = _ksettings()
    removed = []
    for name in k.get("previous_collections", []):
        if name == active_collection():
            continue
        try:
            client().delete_collection(name)
            removed.append(name)
        except Exception:
            pass
    lfs.update_settings(knowledge={"previous_collections": []})
    return removed
