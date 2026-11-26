"""
7.0: model commands typed in chat, handled by the app, never by the model.

Root cause this fixes: "switch the model to llama3.2" used to go to the current model, which answered
"Sure, I'll switch the model to Llama3.2" while nothing changed (the response's own `model` field still said
qwen2.5). A language model can't change which model the app calls, so these requests are now recognised
deterministically and carried out for real:

  "switch the model to llama3.2" / "change model to qwen" / "use the llama3.2 model" / "switch to llama3.2"
      -> this conversation's model (conversations.model), checked against Ollama's installed chat models
  "set the default model to qwen2.5" / "... for new chats"
      -> the global default (settings.json, same as Settings → Default chat model)
  "which model are you?" / "what model is answering?"
      -> the real model, read from the app's state

A switch that can't happen (not installed, ambiguous, Ollama down) says so with the real reason and keeps
the previous model. Loose phrasing without the word "model" ("switch to X") only counts when X really is an
installed model, so "switch to dark mode" is still an ordinary message.
"""
from __future__ import annotations

import re
from typing import Any

import memory_service as mem
import system_service as system

_NAME = r"(?P<m>[A-Za-z0-9][\w.:\-/ ]{0,48}?)"
_END = r"\s*(?:model|llm)?\s*(?:please)?\s*[.!?]*\s*$"
# with the word "model": always a model command
STRICT = [re.compile(r"^\s*(?:please\s+)?(?:can|could|would)?\s*(?:you\s+)?(?:please\s+)?(?:switch|change|swap|set|move)\s+(?:the\s+|your\s+|my\s+|this\s+|our\s+)?"
                     r"(?:chat\s+|ai\s+|default\s+)?(?:model|llm)(?:\s+(?:for\s+(?:new|all)\s+chats|globally))?\s*(?:to|=|:|into|with)?\s+" + _NAME + _END, re.I),
          re.compile(r"^\s*(?:please\s+)?(?:use|select|pick|choose)\s+(?:the\s+)?(?:model|llm)\s+" + _NAME + _END, re.I),
          re.compile(r"^\s*(?:please\s+)?(?:use|select|pick|choose|switch\s+to|change\s+to)\s+(?:the\s+)?" + _NAME + r"\s+(?:model|llm)\b.*$", re.I)]
# without it: only when the name is an installed model
LOOSE = [re.compile(r"^\s*(?:please\s+)?(?:switch|change|swap)\s+to\s+(?:the\s+)?" + _NAME + _END, re.I),
         re.compile(r"^\s*(?:please\s+)?use\s+" + _NAME + r"\s+(?:from now on|instead|for this chat)\s*[.!?]*\s*$", re.I)]
DEFAULT = re.compile(r"\b(?:default|new chats|all chats|globally|every chat)\b", re.I)
WHICH = re.compile(r"^\s*(?:which|what)\s+(?:ai\s+)?(?:model|llm)\s+(?:are\s+you|is\s+this|is\s+(?:answering|responding|running|being\s+used)|am\s+i\s+(?:using|talking\s+to)"
                   r"|are\s+you\s+using|do\s+you\s+use)\b.*$", re.I)
_DROP = {"the", "model", "llm", "please", "a", "an"}


def parse(text: str) -> dict[str, Any] | None:
    """{"kind": "switch", "name", "strict", "default"} | {"kind": "which"} | None. Pure and offline."""
    t = (text or "").strip()
    if not t or len(t) > 160 or "\n" in t:
        return None
    if WHICH.match(t):
        return {"kind": "which"}
    for strict, rxs in ((True, STRICT), (False, LOOSE)):
        for rx in rxs:
            m = rx.match(t)
            if m:
                name = " ".join(w for w in m.group("m").split() if w.lower() not in _DROP).strip(" .")
                if name:
                    return {"kind": "switch", "name": name, "strict": strict, "default": bool(DEFAULT.search(t))}
    return None


def _norm(s: str) -> str:
    return re.sub(r"[\s_\-]+", "", s.lower())


def match(name: str, installed: list[str]) -> list[str]:
    """Installed models that [name] means: exact tag, the tag without ":latest", spaces/case ignored ("Llama 3.2" ->
    llama3.2:latest), then a unique prefix ("qwen" -> qwen2.5:latest). Several hits = ambiguous."""
    n = _norm(name)
    exact = [m for m in installed if _norm(m) == n or _norm(m.split(":")[0]) == n or _norm(m) == n + ":latest"]
    if exact:
        return exact[:1] if len(exact) == 1 or len({_norm(m.split(":")[0]) for m in exact}) == 1 else exact
    return [m for m in installed if _norm(m).startswith(n) or _norm(m.split(":")[0]).startswith(n)]


def _result(answer: str, cid: str | None, model: str | None, switch: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"answer": answer, "conversation_id": cid, "tools_used": [], "sources": [], "model": model, "served_by": None,
            "stats": None, "project_id": None, "focus": None, "context": {}, "live": {"needed": [], "state": "none", "sources": [], "notes": []},
            "model_command": switch}


async def handle(message: str, conversation_id: str | None, requested: str | None = None, save: bool = True) -> dict[str, Any] | None:
    """Carries out a model command, or returns None when [message] isn't one (then the normal chat runs).
    [requested]: the model the page sent with this message (the conversation's current choice)."""
    cmd = parse(message)
    if not cmd:
        return None
    info = await system.chat_models()
    names = [m["name"] for m in info.get("models", [])]
    default = info.get("default") or system.active_models()["llm"]
    current = requested or mem.conversation_model(conversation_id) or default
    if cmd["kind"] == "which":
        cid = mem.ensure_conversation(conversation_id, message)
        scope = "chosen for this conversation" if (requested or mem.conversation_model(cid)) else "the default for new chats"
        answer = f"This conversation is using **{current}** ({scope}), running locally in Ollama. The default for new chats is **{default}**."
        out = _result(answer, cid, current, {"kind": "which", "ok": True, "model": current, "default": default})
    else:
        hits = match(cmd["name"], names) if info.get("ollama") else []
        if not cmd["strict"] and len(hits) != 1:
            return None                                            # "switch to dark mode": not a model, an ordinary message
        cid = mem.ensure_conversation(conversation_id, message)
        if not info.get("ollama"):
            answer = f"I couldn't switch models: {info.get('error') or 'Ollama is not reachable.'} Still using **{current}**."
            out = _result(answer, cid, current, {"kind": "switch", "ok": False, "error": "ollama_unavailable", "model": current})
        elif len(hits) != 1:
            listed = ", ".join(f"`{n}`" for n in (hits or names)) or "none"
            why = (f"`{cmd['name']}` matches several installed models ({listed}); say which one." if hits else
                   f"`{cmd['name']}` isn't installed in Ollama. Installed chat models: {listed}. You can download others in Models.")
            answer = f"I couldn't switch models: {why} Still using **{current}**."
            out = _result(answer, cid, current, {"kind": "switch", "ok": False, "error": "ambiguous" if hits else "not_installed",
                                                 "model": current, "candidates": hits})
        elif cmd["default"]:
            try:
                await system.set_default_chat_model(hits[0])
            except (ValueError, PermissionError, ConnectionError) as e:
                out = _result(f"I couldn't change the default model: {e} Still using **{current}**.", cid, current,
                              {"kind": "default", "ok": False, "error": str(e), "model": current})
            else:
                mem.set_conversation_model(cid, hits[0])
                out = _result(f"Default model switched to **{hits[0]}**. New chats and this conversation now use it.", cid, hits[0],
                              {"kind": "default", "ok": True, "model": hits[0], "previous": current, "default": hits[0]})
        else:
            mem.set_conversation_model(cid, hits[0])
            note = "" if hits[0] == default else f" New chats still start with the default, **{default}**."
            out = _result(f"Model switched to **{hits[0]}** for this conversation. The next answer comes from {hits[0]}.{note}", cid, hits[0],
                          {"kind": "switch", "ok": True, "model": hits[0], "previous": current, "default": default})
    if save and out["conversation_id"]:
        mem.add_message(out["conversation_id"], "user", message)
        mem.add_message(out["conversation_id"], "assistant", out["answer"], model="abhify")   # written by the app, not a model
    return out
