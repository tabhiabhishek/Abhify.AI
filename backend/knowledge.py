"""
knowledge.py: the local document knowledge base (ChromaDB + Ollama embeddings).

Uploaded PDFs / notes are copied into <data>/documents/uploads, split into
overlapping chunks, embedded with the active embedding model and stored in a
persistent Chroma collection. Index metadata (hash, chunk count, status) lives
in SQLite (`documents` table), so the same file is never indexed twice.

Personal memories are deliberately NOT stored here (see memory_service.py).
"""
from __future__ import annotations

import hashlib
import logging
import re
from functools import lru_cache
from pathlib import Path

from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pypdf import PdfReader

import database as db
import local_file_service as lfs
from config import get_settings

log = logging.getLogger(__name__)

COLLECTION = "knowledge_base"          # name kept from v1.0.0 so existing vectors stay usable
_splitter = RecursiveCharacterTextSplitter(chunk_size=1000, chunk_overlap=150)


@lru_cache
def get_vectorstore() -> Chroma:
    """Open (or create) the persistent Chroma collection. Cached per process."""
    from system_service import active_models
    embeddings = OllamaEmbeddings(model=active_models()["embed"], base_url=get_settings().ollama_base_url)
    return Chroma(collection_name=COLLECTION, embedding_function=embeddings,
                  persist_directory=str(lfs.chroma_dir()))


def _safe_name(name: str) -> str:
    name = Path(name).name                                  # drop any directory parts
    return re.sub(r"[^A-Za-z0-9._ -]", "_", name)[:120] or "upload"


def add_upload(data: bytes, filename: str) -> dict:
    """Index an uploaded file once. Returns {filename, chunks, duplicate}."""
    name = _safe_name(filename)
    sha = hashlib.sha256(data).hexdigest()
    existing = db.find_document_by_hash(sha)
    if existing:
        return {"filename": name, "chunks": existing["chunks"], "duplicate": True, "already_as": existing["path"]}

    lfs.uploads_dir().mkdir(parents=True, exist_ok=True)
    stored = lfs.uploads_dir() / f"{sha[:12]}-{name}"
    stored.write_bytes(data)                                # keep the original for re-indexing later

    if Path(name).suffix.lower() == ".pdf":
        docs = _pdf_pages(stored)
    else:
        docs = [Document(page_content=data.decode("utf-8", errors="ignore"))]
    for d in docs:
        d.metadata.update(source=name, sha256=sha)
    chunks = [c for c in _splitter.split_documents(docs) if c.page_content.strip()]
    if chunks:
        get_vectorstore().add_documents(chunks, ids=[f"{sha[:16]}-{i}" for i in range(len(chunks))])
    db.upsert_document(name, "upload", sha, len(data), len(chunks),
                       status="indexed" if chunks else "failed",
                       error=None if chunks else "No extractable text")
    log.info("Indexed upload (%d chunks)", len(chunks))       # no file content or names in logs
    return {"filename": name, "chunks": len(chunks), "duplicate": False}


def _pdf_pages(path: Path) -> list[Document]:
    """One Document per PDF page (page numbers are 0-based, like PyPDFLoader)."""
    docs = []
    for i, page in enumerate(PdfReader(str(path)).pages):
        text = page.extract_text() or ""
        if text.strip():
            docs.append(Document(page_content=text, metadata={"page": i}))
    return docs


def search(query: str, k: int = 4) -> list[Document]:
    """Similarity search. Returns [] when the knowledge base is empty."""
    if count() == 0:
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
