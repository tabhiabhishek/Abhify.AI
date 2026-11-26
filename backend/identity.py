"""
identity.py: the assistant's personal name (7.1.0-dev.2), separate from the product name Abhify.AI.

* Stored locally on this computer (app_settings key "assistant_name"; no migration). Android and the standalone Web app keep
  their own name: nothing is shared or synced.
* One source for every prompt (intro()), the UI (GET/PUT /preferences) and the wake phrases (wake_phrases()).
* Identity questions ("what is your name?", "who are you?", "can I change your name?") get the app's own answer, so a small
  local model can't answer "I am Qwen". Same rules and cases as Android (tests/data/identity_cases.json).
"""
from __future__ import annotations

import re
import unicodedata

DEFAULT_NAME = "Abhify"
SUGGESTIONS = ["Abhi", "Aira", "Nova", "Astra", "Ava", "Alex", "Echo", "Aria", "Orion", "Athena", "Jarvis", "Neo", "Friday"]
MAX_LEN = 24


def validate(name: str | None) -> str:
    """A clean assistant name, or ValueError with a message for the user."""
    raw = name if isinstance(name, str) else ""
    if any(unicodedata.category(ch) in ("Cc", "Cf", "Cs", "Co", "Cn") for ch in raw):
        raise ValueError("The name can't contain control or invisible characters.")
    n = re.sub(r"\s+", " ", raw).strip()
    if not n:
        raise ValueError("Enter a name for your assistant.")
    if len(n) > MAX_LEN:
        raise ValueError(f"Use at most {MAX_LEN} characters.")
    def kind(ch: str) -> str:
        c = unicodedata.category(ch)
        return "L" if c[0] in "LN" else "M" if c[0] == "M" else "P" if ch in " .'-" else "x"
    kinds = [kind(ch) for ch in n]
    # letters/digits (any script, with combining marks such as Devanagari vowel signs) and inner " . ' -" only
    if "x" in kinds or kinds[0] != "L" or kinds[-1] == "P" or not any(ch.isalpha() for ch in n):
        raise ValueError("Use letters, and optionally digits, spaces, apostrophes, dots or hyphens.")
    return n


def current(prefs: dict | None = None) -> str:
    if prefs is None:
        import database as db
        prefs = db.get_prefs()
    try:
        return validate(prefs.get("assistant_name") or DEFAULT_NAME)
    except ValueError:
        return DEFAULT_NAME


def intro(name: str) -> str:
    """Replaces "You are Abhify.AI," at the start of the system prompts."""
    if name == DEFAULT_NAME:
        return "You are Abhify, the AI assistant of the Abhify.AI app,"
    return f"You are {name}, the user's AI assistant in the Abhify.AI app (the user chose the name {name}),"


def named(prompt: str, name: str) -> str:
    return prompt.replace("You are Abhify.AI,", intro(name), 1)


def wake_phrases(name: str) -> list[str]:
    return [f"Hey {name}", f"Hello {name}", name]


# the whole message must be the question (optionally greeting first, or the assistant's name last): "What is your name for
# the variable…" and "Who are you voting for…" are not identity questions
_ASK_NAME = re.compile(r"^\W*(?:(?:hi|hey|hello)[\s,!]+)?(?:what(?:'s|\s+is)\s+your\s+name|your\s+name|who\s+are\s+you|"
                       r"what\s+(?:should|do|can)\s+i\s+call\s+you|tell\s+me\s+your\s+name|do\s+you\s+have\s+a\s+name)"
                       r"(?:\s*,?\s*[^\W\d_]+)?\W*$", re.I)
_RENAME = re.compile(r"\b(?:can|could|may|how\s+(?:do|can))\s+i\s+(?:change|rename|choose|pick|set)\s+(?:your\s+name|you)\b|"
                     r"\b(?:change|rename)\s+(?:your\s+name|you)\b", re.I)


def answer(message: str, name: str) -> str | None:
    """The app's own reply to an identity question, or None for anything else."""
    t = (message or "").strip()
    if len(t) > 120:
        return None
    if _RENAME.search(t):
        return (f"Yes. I'm {name} now, and you can rename me any time: **More → Settings → AI personalization → Assistant identity**. "
                "The new name is used in chat, in my answers and for the wake phrase on this device.")
    if _ASK_NAME.search(t):
        return (f"I'm **{name}**, your AI assistant in Abhify.AI. I run on this device, and you can rename me in "
                "**Settings → AI personalization**.")
    return None


def handle(message: str, conversation_id: str | None, save: bool = True) -> dict | None:
    """An identity question answered by the app (same shape as model_switch replies), or None."""
    import memory_service as mem
    name = current()
    text = answer(message, name)
    if text is None:
        return None
    cid = mem.ensure_conversation(conversation_id, message)
    if save:
        mem.add_message(cid, "user", message)
        mem.add_message(cid, "assistant", text, model="abhify")
    return {"answer": text, "conversation_id": cid, "tools_used": [], "sources": [], "model": None, "served_by": None, "stats": None,
            "project_id": None, "focus": None, "context": {}, "live": {"needed": [], "state": "none", "sources": [], "notes": []},
            "model_command": {"kind": "identity", "ok": True, "name": name}}
