"""
companion_ai.py: "My Friendly AI" for the desktop chat (/chat and /chat/stream), the same companion layer as the
Android app (android/.../chat/Companion.kt; keep the two behaving identically).

Not to be confused with companion.py (Mode B: phone access through this computer).

  * CHARTER: one consistent, honest personality: friendly and calm, an AI (never claims feelings, experiences or
    memories it doesn't have), length matched to the question, one clarifying question only when genuinely ambiguous,
    no repeated greetings or flattery, at most one emoji.
  * is_follow_up / active_topic / retrieval_query: a short follow-up ("why?", "and in Hindi?", "what about the second
    one") is resolved against the active topic, so memory and document retrieval search for the topic, not just "why".
  * earlier: user questions older than the prompt window, quoted extractively (never invented, at most 5), so long
    conversations keep their thread.
  * small_talk: greetings and thanks get a short, natural reply instead of being forced into a workflow.

Pure functions over the saved history ([{"role": "user"|"assistant", "content": str}, ...], oldest first, WITHOUT the
current message). Nothing here reads or sends data anywhere; the desktop chat runs on the local Ollama model.
"""
from __future__ import annotations

import re
from typing import Iterable, Mapping

HISTORY_WINDOW = 12            # the desktop prompt keeps the last 12 saved messages (agent._prepare)

CHARTER = ("How you talk: friendly, calm, clear and honest — like a knowledgeable friend, not a generic chatbot. "
           "Match the length to the question: brief for simple things, detailed only when asked or needed. If a request is genuinely ambiguous, "
           "ask one short clarifying question instead of guessing. Say when you are unsure. Continue the current topic naturally and don't ask for "
           "information the user already gave. No repeated greetings, no flattery, at most one emoji and only when it fits. You are an AI: never "
           "claim feelings, experiences or memories you don't have; only what is in this conversation and the context below is known to you.")

_FOLLOW_START = re.compile(r"^(and|but|so|also|then|why|how come|what about|how about|and what|ok(ay)?|really|more|explain (it|that|this)|in (hindi|english)|"
                           r"give (me )?(an )?example|which one|the (first|second|third|last) one)\b", re.I)
_ANAPHORA = re.compile(r"\b(it|its|that|this|those|these|they|them|he|she|the same|above|previous|earlier|last one)\b", re.I)
_SMALL_TALK = re.compile(r"(hi|hello|hey|namaste|good (morning|afternoon|evening|night)|thanks?( you)?|thank you( so much)?|ok(ay)?|cool|great|nice|bye|see you)[.!? ]*",
                         re.I)

Msg = Mapping[str, str]


def _users(history: Iterable[Msg]) -> list[str]:
    return [str(m.get("content") or "") for m in history if m.get("role") == "user"]


def small_talk(q: str) -> bool:
    """A greeting, thanks or acknowledgement with nothing else in it."""
    return _SMALL_TALK.fullmatch((q or "").strip()) is not None


def is_follow_up(q: str, history: list[Msg]) -> bool:
    """A short question that leans on the previous turn: it starts like a follow-up, or it is at most 6 words and points
    back with "it / that / ..."; never when the user hasn't asked anything yet."""
    if not _users(history):
        return False
    t = (q or "").strip()
    words = len(re.split(r"\s+", t))
    return words <= 12 and (_FOLLOW_START.search(t) is not None or (words <= 6 and _ANAPHORA.search(t) is not None))


def active_topic(history: list[Msg]) -> str | None:
    """The topic a follow-up refers to: the latest user message that was not itself a follow-up or small talk."""
    users = [u.strip() for u in _users(history)]
    for i in range(len(users) - 1, -1, -1):
        u = users[i]
        if not u or small_talk(u):
            continue
        if is_follow_up(u, [{"role": "user", "content": x} for x in users[:i]]):
            continue
        return u[:200]
    return None


def retrieval_query(q: str, history: list[Msg]) -> str:
    """What memory / document retrieval should search for: the question, plus the active topic for a follow-up."""
    if not is_follow_up(q, history):
        return q
    topic = active_topic(history)
    return f"{q} {topic}" if topic else q


def earlier(history: list[Msg], window: int = HISTORY_WINDOW) -> str | None:
    """The user's own questions older than the prompt window (newest last, at most 5), quoted; None when nothing is older."""
    older_msgs = list(history)[:-window] if window > 0 else list(history)
    older = [str(m.get("content") or "") for m in older_msgs
             if m.get("role") == "user" and str(m.get("content") or "").strip() and not small_talk(str(m.get("content") or ""))]
    if not older:
        return None
    quoted = "; ".join('"' + o.replace("\n", " ").strip()[:90] + '"' for o in older[-5:])
    return f"Earlier in this conversation (outside the recent messages) the user asked: {quoted}."


def turn(q: str, history: list[Msg], window: int = HISTORY_WINDOW) -> str:
    """The per-turn guidance appended to the system prompt: charter, earlier questions, follow-up and small-talk hints."""
    parts: list[str | None] = [CHARTER, earlier(history, window)]
    if is_follow_up(q, history):
        topic = active_topic(history)
        parts.append(f'The user\'s message is a follow-up to: "{topic[:160]}". Answer it in that context.' if topic else None)
    if small_talk(q):
        parts.append("The user is just chatting: reply in one or two friendly sentences; don't list features or start a task.")
    return "\n".join(p for p in parts if p)
