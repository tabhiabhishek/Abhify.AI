"""5.6 My Friendly AI on the desktop chat path (companion_ai.py): same behaviour as Android's chat/Companion.kt
(the cases below mirror android/app/src/test/.../V56bTest.kt CompanionTest), and wired into agent._prepare."""
import asyncio
from pathlib import Path

import companion_ai as ca

H = [{"role": "user", "content": "How does RAG chunking work in my Abhify knowledge base?"}, {"role": "assistant", "content": "It splits documents…"}]


def test_follow_ups_use_the_active_topic_for_retrieval():
    assert ca.is_follow_up("why?", H) and ca.is_follow_up("and what about overlap?", H) and ca.is_follow_up("explain that again", H)
    assert not ca.is_follow_up("What is the capital of France and why is it famous for art?", H)
    assert not ca.is_follow_up("why?", [])                                     # nothing to follow
    assert ca.retrieval_query("why?", H) == "why? How does RAG chunking work in my Abhify knowledge base?"
    assert ca.retrieval_query("Explain transformers", H) == "Explain transformers"
    chain = H + [{"role": "user", "content": "why?"}, {"role": "assistant", "content": "Because…"}]
    assert ca.active_topic(chain) == H[0]["content"]                           # a follow-up of a follow-up keeps the real topic
    assert ca.is_follow_up("Is it fast?", H) and not ca.is_follow_up("Is gradient descent fast for very large models today?", H)
    assert ca.active_topic([{"role": "user", "content": "thanks!"}]) is None   # small talk is never a topic


def test_earlier_turns_are_the_users_own_words_and_never_invented():
    long = [m for i in range(1, 13) for m in ({"role": "user", "content": f"question {i} about kotlin"}, {"role": "assistant", "content": f"answer {i}"})]
    e = ca.earlier(long, 8)
    assert '"question 8 about kotlin"' in e and "question 9 about" not in e and "answer" not in e   # only outside the window, only user text
    assert e.count('"') == 10                                                   # at most 5, newest last
    assert e.index("question 4 about") < e.index("question 8 about") and "question 3 about" not in e
    assert ca.earlier(H, 8) is None
    assert ca.earlier(long, ca.HISTORY_WINDOW) is not None and ca.HISTORY_WINDOW == 12
    multi = [{"role": "user", "content": "line one\nline two " + "x" * 200}] + [{"role": "assistant", "content": "a"}] * 12
    assert '"line one line two ' in ca.earlier(multi, 12) and len(ca.earlier(multi, 12)) < 200   # one line, clipped to 90


def test_small_talk_stays_light():
    assert ca.small_talk("Thanks!") and ca.small_talk("good morning") and ca.small_talk("  hi ") and not ca.small_talk("thanks, now explain LoRA")
    assert "just chatting" in ca.turn("hi", [])
    t = ca.turn("why?", H)
    assert t.startswith(ca.CHARTER) and 'follow-up to: "How does RAG chunking' in t and "just chatting" not in t


def test_charter_is_honest():
    assert "You are an AI: never claim feelings, experiences or memories" in ca.CHARTER
    assert "at most one emoji" in ca.CHARTER and "one short clarifying question" in ca.CHARTER and "No repeated greetings, no flattery" in ca.CHARTER
    assert "I feel" not in ca.CHARTER


def test_desktop_chat_uses_the_companion_for_retrieval_and_the_prompt(monkeypatch, migrated):
    # `migrated`: _prepare reads prefs from the database, so it must be a throw-away one (never the real user data).
    """agent._prepare: memory and document retrieval get retrieval_query; the system prompt gets the charter, earlier and the hint."""
    import agent
    import memory_service as mem
    saved = [m for i in range(1, 10) for m in ({"role": "user", "content": f"question {i} about vectors"}, {"role": "assistant", "content": f"answer {i}"})]
    saved[-2] = {"role": "user", "content": "How does RAG chunking work in my notes?"}
    seen = {}
    monkeypatch.setattr(mem, "ensure_conversation", lambda cid, msg: "c1")
    monkeypatch.setattr(mem, "recent_messages", lambda cid, limit=12: saved[-limit:])
    monkeypatch.setattr(mem, "memory_context", lambda q: seen.setdefault("memory", q) and "")
    monkeypatch.setattr(agent, "document_context", lambda q: (seen.setdefault("docs", q), ("", []))[1])
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: "agent")
    prefs, cid, inputs, agent_, doc_sources, project = asyncio.run(agent._prepare("why?", "c1", None, None))
    assert seen["memory"] == seen["docs"] == "why? How does RAG chunking work in my notes?"
    system = inputs["messages"][0].content
    assert ca.CHARTER in system and 'follow-up to: "How does RAG chunking work in my notes?"' in system
    assert 'the user asked: "question 1 about vectors"' in system and '"question 3 about vectors"' in system   # outside the 12-message window
    assert len(inputs["messages"]) == 1 + ca.HISTORY_WINDOW + 1                 # system + window + the question


def test_wiring_keeps_private_context_local():
    src = (Path(__file__).resolve().parents[1] / "agent.py").read_text(encoding="utf-8")
    assert "rq = companion_ai.retrieval_query(message, saved)" in src
    assert "document_context, rq)" in src and "mem.memory_context(rq)" in src and "projects.context, project, rq)" in src
    assert "companion_ai.turn(message, saved, companion_ai.HISTORY_WINDOW)" in src
    assert "companion_ai" not in src.split("async def run_guest")[1].split("PROJECT_RULES")[0]   # guests: no history, no companion context
