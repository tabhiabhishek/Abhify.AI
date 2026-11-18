"""5.6 local AI-quality evaluation. A small built-in synthetic fixture set (no user data, nothing read from disk, no
network, no model) runs through the same ranking functions the app uses (insights.universal_search,
insights.related_documents, insights.find_duplicates) and reports precision@k, duplicate-detection accuracy and the
latency of each call, plus the provider failure counters kept in memory by the chat path (counts only)."""
from __future__ import annotations

import time
from typing import Any

import insights

NOW = 2_000_000_000
FIXTURE = {
    "conversations": [
        {"id": "c1", "title": "FastAPI routing questions", "updated_at": "2033-05-17 10:00:00"},
        {"id": "c2", "title": "Kotlin coroutines deep dive", "updated_at": "2033-05-10 10:00:00"},
        {"id": "c3", "title": "Weekend trip ideas", "updated_at": "2033-05-01 10:00:00"},
        {"id": "c4", "title": "Interview prep: SQL joins", "updated_at": "2033-05-16 10:00:00"},
    ],
    "messages": [
        {"conversation_id": "c5", "title": "Chat", "content": "How do I add a FastAPI dependency for auth?", "created_at": "2033-05-15 09:00:00"},
    ],
    "projects": [
        {"id": "p1", "name": "Abhify backend", "instructions": "FastAPI service with local RAG", "documents": [], "conversations": ["c1"],
         "status": {"current": "Routing done", "next": ["Write retrieval tests"], "issues": ["Waiting for Chroma upgrade"], "milestones": [],
                    "decisions": [], "updated_at": NOW - 3600}},
        {"id": "p2", "name": "Android companion", "instructions": "Kotlin app", "documents": [], "conversations": ["c2"],
         "status": {"next": ["Fix coroutine leak"], "issues": [], "milestones": [], "decisions": [], "updated_at": NOW - 9 * 86400}},
    ],
    "tasks": [
        {"id": "t1", "title": "Summary of fastapi_routing_notes.pdf", "status": "completed", "project_id": "p1", "created_at": (NOW - 7200) * 1000},
        {"id": "t2", "title": "Quiz on SQL joins", "status": "failed", "created_at": (NOW - 86400) * 1000},
    ],
    "documents": ["fastapi_routing_notes.pdf", "local-rag-retrieval.md", "kotlin_coroutines.pdf", "holiday_photos.docx", "sql-joins-cheatsheet.pdf"],
    "news": [{"title": "FastAPI 1.0 released", "url": "https://example.invalid/a", "published_at": (NOW - 3600) * 1000}],
}
SEARCH_CASES = [   # (query, filters, refs that are relevant)
    ("fastapi", {}, {"c1", "c5", "p1", "t1", "fastapi_routing_notes.pdf", "https://example.invalid/a"}),
    ("kotlin", {}, {"c2", "p2", "kotlin_coroutines.pdf"}),
    ("sql joins", {}, {"c4", "t2", "sql-joins-cheatsheet.pdf"}),
    ("fastapi", {"types": {"document"}}, {"fastapi_routing_notes.pdf"}),
    ("fastapi", {"project": "p1"}, {"c1", "p1", "t1"}),
]
DOC_CASES = [("p1", {"fastapi_routing_notes.pdf", "local-rag-retrieval.md"}), ("p2", {"kotlin_coroutines.pdf"})]
DUP_CASES = [   # (a, b, expected kind or None)
    ("Fix the login bug", "fixing login bugs", "duplicate"),
    ("Write retrieval tests", "write tests for retrieval", "duplicate"),
    ("Deploy to staging", "Update the README", None),
    ("Add dark mode", "Dark mode added", "already_done"),
]


def _timed(fn, *a, **kw):
    t = time.perf_counter()
    out = fn(*a, **kw)
    return out, round((time.perf_counter() - t) * 1000, 3)


def run(k: int = 3) -> dict[str, Any]:
    f = FIXTURE
    search_rows, lat = [], []
    for q, flt, relevant in SEARCH_CASES:
        out, ms = _timed(insights.universal_search, q, conversations=f["conversations"], memories=None,
                         messages_hits=[m for m in f["messages"] if q.lower() in m["content"].lower()],   # what the SQL LIKE returns
                         projects=f["projects"], tasks=f["tasks"], knowledge_files=f["documents"], news_articles=f["news"],
                         now=NOW, **flt)
        top = [r["ref"] for r in out["results"][:k]]
        p_at_k = sum(1 for r in top if r in relevant) / k if top else 0.0
        search_rows.append({"query": q, "filters": {kk: sorted(v) if isinstance(v, set) else v for kk, v in flt.items()},
                            "precision_at_k": round(min(1.0, p_at_k * k / min(k, len(relevant))), 3), "latency_ms": ms})
        lat.append(ms)
    doc_rows = []
    for pid, relevant in DOC_CASES:
        p = next(x for x in f["projects"] if x["id"] == pid)
        out, ms = _timed(insights.related_documents, p, f["documents"])
        top = [d["document"] for d in out[:k]]
        doc_rows.append({"project": pid, "precision_at_k": round(sum(1 for d in top if d in relevant) / len(top), 3) if top else 0.0,
                         "found": len(out), "latency_ms": ms})
        lat.append(ms)
    dup_ok, dup_rows = 0, []
    for a, b, want in DUP_CASES:
        items = [{"text": a, "state": "open", "source": "fixture"}, {"text": b, "state": "done" if want == "already_done" else "open", "source": "fixture"}]
        out, ms = _timed(insights.find_duplicates, items)
        got = out[0]["kind"] if out else None
        dup_ok += got == want
        dup_rows.append({"a": a, "b": b, "expected": want, "got": got, "latency_ms": ms})
        lat.append(ms)
    mean = lambda xs: round(sum(xs) / len(xs), 3) if xs else 0.0      # noqa: E731
    return {
        "k": k, "fixture": "built-in synthetic set (no user data)",
        "search": {"cases": search_rows, "mean_precision_at_k": mean([r["precision_at_k"] for r in search_rows])},
        "related_documents": {"cases": doc_rows, "mean_precision_at_k": mean([r["precision_at_k"] for r in doc_rows])},
        "duplicates": {"cases": dup_rows, "accuracy": round(dup_ok / len(DUP_CASES), 3)},
        "latency_ms": {"mean": mean(lat), "max": max(lat) if lat else 0.0},
        "provider_failures": insights.provider_failures(),
        "note": "precision@k = relevant results in the top k / min(k, relevant items). Search and ranking use no embeddings or model call. Provider failures are counted since the app started.",
    }
