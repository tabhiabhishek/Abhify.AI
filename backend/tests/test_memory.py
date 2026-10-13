"""Personal memory + conversation history behaviour."""
import pytest

import database as db
import memory_service as mem


def test_memory_crud(migrated):
    m = mem.add_memory("I am preparing for ML engineer interviews", "goal")
    assert m["id"] and m["category"] == "goal" and m["source"] == "user"
    assert mem.add_memory("i am preparing for ML engineer interviews")["id"] == m["id"]   # no duplicates
    u = mem.update_memory(m["id"], content="Preparing for senior ML interviews", pinned=True)
    assert u["content"].startswith("Preparing") and u["pinned"] == 1
    assert len(mem.list_memories()) == 1
    assert mem.delete_memory(m["id"]) and mem.list_memories() == []
    assert mem.update_memory(999, content="x") is None


def test_memory_validation(migrated):
    with pytest.raises(ValueError):
        mem.add_memory("   ")
    with pytest.raises(ValueError):
        mem.add_memory("x" * 501)
    assert mem.add_memory("likes short answers", category="bogus")["category"] == "general"


def test_relevant_memories_only(migrated):
    mem.add_memory("My weakest topic is backpropagation math", "weak_topic")
    mem.add_memory("I prefer answers with Python examples", "preference")
    mem.add_memory("My dog is called Bruno", "fact")
    hits = [m["content"] for m in mem.search_memories("Can you explain backpropagation again?")]
    assert hits == ["My weakest topic is backpropagation math"]


def test_pinned_memories_always_included(migrated):
    mem.add_memory("Always answer in English", "preference", pinned=True)
    assert [m["content"] for m in mem.search_memories("random unrelated question")] == ["Always answer in English"]


def test_fts_query_is_injection_safe(migrated):
    mem.add_memory("Interested in NEAR operators and OR logic")
    for q in ['" OR 1=1 --', "NEAR(a b)", "*", "c++ AND c#", ""]:
        mem.search_memories(q)                                     # must not raise


def test_memory_context_respects_toggle(migrated):
    mem.add_memory("Preparing for NLP interviews", "goal")
    assert "Preparing for NLP interviews" in mem.memory_context("help with NLP interviews")
    db.set_prefs(memory_enabled=False)
    assert mem.memory_context("help with NLP interviews") == ""


def test_conversations_persist_and_cascade(migrated):
    cid = mem.ensure_conversation(None, "What is dropout?")
    mem.add_message(cid, "user", "What is dropout?")
    mem.add_message(cid, "assistant", "A regularization technique.", ["web_search"], ["https://x"])
    msgs = mem.recent_messages(cid)
    assert [m["role"] for m in msgs] == ["user", "assistant"] and msgs[1]["tools"] == ["web_search"]
    assert mem.list_conversations()[0]["title"] == "What is dropout?"
    assert mem.delete_conversation(cid)
    with db.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0


def test_history_retention(migrated):
    cid = mem.ensure_conversation(None, "old chat")
    with db.connect() as c:
        c.execute("UPDATE conversations SET updated_at = datetime('now', '-40 days') WHERE id = ?", (cid,))
    assert mem.purge_expired_history() == 0                        # 0 = keep forever (default)
    db.set_prefs(history_retention_days=30)
    assert mem.purge_expired_history() == 1
