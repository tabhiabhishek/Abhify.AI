"""
knowledge.py: the local knowledge base (ChromaDB + Ollama embeddings).

Users upload PDFs or plain-text notes; we split them into overlapping chunks,
embed them with `nomic-embed-text` and store them in a persistent Chroma
collection. The agent's `knowledge_base_search` tool queries this store.
"""
from __future__ import annotations

import logging
import tempfile
from functools import lru_cache
from pathlib import Path

from langchain_chroma import Chroma
from langchain_community.document_loaders import PyPDFLoader
from langchain_core.documents import Document
from langchain_ollama import OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from config import get_settings

log = logging.getLogger(__name__)

_splitter = RecursiveCharacterTextSplitter(chunk_size=1000, chunk_overlap=150)


@lru_cache
def get_vectorstore() -> Chroma:
    """Open (or create) the persistent Chroma collection. Cached per process."""
    s = get_settings()
    embeddings = OllamaEmbeddings(model=s.embed_model, base_url=s.ollama_base_url)
    return Chroma(
        collection_name=s.chroma_collection,
        embedding_function=embeddings,
        persist_directory=str(s.chroma_dir),
    )


def ingest_pdf(data: bytes, filename: str) -> int:
    """Split a PDF into chunks and add them to the knowledge base. Returns chunk count."""
    # PyPDFLoader needs a real file path, so write the upload to a temp file.
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
        tmp.write(data)
        tmp_path = Path(tmp.name)
    try:
        pages = PyPDFLoader(str(tmp_path)).load()
    finally:
        tmp_path.unlink(missing_ok=True)
    for p in pages:
        p.metadata["source"] = filename          # show the original name, not the temp path
    return _add(pages)


def ingest_text(text: str, filename: str) -> int:
    """Add plain-text / markdown notes to the knowledge base. Returns chunk count."""
    return _add([Document(page_content=text, metadata={"source": filename})])


def _add(docs: list[Document]) -> int:
    chunks = [c for c in _splitter.split_documents(docs) if c.page_content.strip()]
    if chunks:
        get_vectorstore().add_documents(chunks)
    log.info("Indexed %d chunks", len(chunks))
    return len(chunks)


def search(query: str, k: int = 4) -> list[Document]:
    """Similarity search. Returns [] when the knowledge base is empty."""
    if count() == 0:
        return []
    return get_vectorstore().similarity_search(query, k=k)


def count() -> int:
    """Number of chunks currently stored."""
    try:
        return get_vectorstore()._collection.count()
    except Exception:  # collection not created yet
        return 0


def list_sources() -> list[str]:
    """Distinct document names in the knowledge base."""
    if count() == 0:
        return []
    metas = get_vectorstore()._collection.get(include=["metadatas"])["metadatas"]
    return sorted({m.get("source", "unknown") for m in metas if m})
