# Phase 4: knowledge and documents

**Status:** done in code (4.4.0). Semantic search awaits approval. **Phone verification:** pending.

| Capability | State |
|---|---|
| Import | PDF (text layer, or ML Kit OCR for scans, asks first), DOCX, TXT, MD, CSV, JPEG/PNG/WebP; up to 10 MB; SHA-256 duplicate check ("already in your library") |
| Library | Search by name and text; **sort by newest, name, size or type (4.4, remembered)**; size, characters, OCR pages, warnings, age |
| Viewing | "View text" (extracted text, page headings, lazy rendering). **"View pages" (4.4):** PDF pages as images with 1×, 1.5× and 2× zoom, rendered one at a time on the phone. The library stores text only, so you pick the PDF file to view its pages. Password-protected PDFs show a clear message. |
| Indexing | Page-aware chunks, FTS4 + BM25. Indexed in the background at start. **"Not indexed yet" label (4.4)** with Re-index. Indexes are removed with their document, never otherwise. |
| Q&A | "Ask your documents" and documents in chat: best sections with [S1] citations and page numbers; "The documents don't cover this" when absent |
| Semantic search | Hybrid ranking infrastructure; no embedding model (see [V3_6_SEMANTIC_SEARCH_EVALUATION.md](V3_6_SEMANTIC_SEARCH_EVALUATION.md)) |

**Storage:** text, chunks and indexes live in `ChatDb`. Original files aren't kept. OCR scratch files go in the cache.

**User guide:** [RAG_DOCUMENT_LIBRARY.md](RAG_DOCUMENT_LIBRARY.md).
