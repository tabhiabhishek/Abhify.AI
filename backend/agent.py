"""
agent.py: the LangGraph ReAct agent ("the AI brain") of Abhify.AI.

Runs on the active local Ollama model (see system_service.active_models) and
chooses between tools:

  * knowledge_base_search: the user's uploaded documents (ChromaDB)
  * search_memory:         the user's saved personal memories (SQLite FTS5)
  * save_memory:           store a memory, ONLY when the user explicitly asks
  * web_search:            live DuckDuckGo search; skipped when offline or disabled

Conversation history is persisted in SQLite (memory_service), so context
survives restarts. Relevant memories are added to the system prompt per request.
When the user explicitly asks about their own documents ("my notes", "my
documents", "the uploaded PDF", "knowledge base"), matching excerpts are added
too (document_context): small models such as qwen2.5:1.5b often don't call
knowledge_base_search on their own (found in the dev.3 clean-PC test).

    user ─► agent (LLM) ─► tool calls? ──yes──► tools ─┐
                 ▲                                     │
                 └─────────────── ToolMessage ◄────────┘
                         │ no
                         ▼
                   final answer
"""
from __future__ import annotations

import asyncio
import logging
import re
from functools import lru_cache

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent

import database as db
import knowledge
import local_file_service as lfs
import memory_service as mem
from config import get_settings
from system_service import active_models, internet_available

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are Abhify.AI, a friendly AI/ML learning companion and interview coach that runs
locally on the user's own computer.

How to choose tools:
- `knowledge_base_search`: questions about the user's own uploaded notes or documents.
- `search_memory`: questions about the user themself (their goals, preferences, what they told you to remember).
- `save_memory`: ONLY when the user explicitly asks you to remember something ("remember that...", "note that I...").
  Never save memories on your own initiative.
- `web_search`: recent or time-sensitive information (news, releases, versions, "latest"). If it reports that
  the computer is offline, say so and answer from your own knowledge, clearly marked as possibly outdated.
- Answer directly, without tools, for stable fundamentals you know well.

Answer style:
- Concise and clear, Markdown, short paragraphs or bullets.
- Cite the document name or website when you used a tool.
- Separate verified facts (from tools) from your own general knowledge when it matters.
- If tools return nothing useful, say so instead of inventing facts.
"""


# --------------------------------------------------------------------------- tools
def _search_documents(query: str) -> tuple[str, list[str]]:
    """(text for the model, source labels). Shared by the tool and by document_context."""
    if knowledge.rebuild_required():
        return (f"The document index was built with '{knowledge.index_embed_model()}' but the configured embedding "
                f"model is '{knowledge.configured_embed_model()}'. Tell the user to rebuild it in "
                "Settings > Knowledge before documents can be searched.", [])
    docs = knowledge.search(query, k=4)
    if not docs:
        return "The knowledge base is empty or has nothing relevant to this query.", []
    parts, sources = [], []
    for d in docs:
        src = d.metadata.get("source", "unknown")
        page = d.metadata.get("page")
        where = f"{src}, page {page + 1}" if isinstance(page, int) else src
        parts.append(f"[{where}]\n{d.page_content.strip()}")
        sources.append(where)
    return "\n\n---\n\n".join(parts), sources


@tool
def knowledge_base_search(query: str) -> str:
    """Search the user's uploaded documents and notes (local knowledge base)."""
    return _search_documents(query)[0]


# The user explicitly refers to their own material: "my notes", "search my documents",
# "the uploaded PDF", "in my files", "knowledge base / folder".
DOC_INTENT = re.compile(
    r"\b(?:my|our|these|those|the\s+uploaded|uploaded|attached)\s+(?:\w+\s+)?"
    r"(?:documents?|docs|notes?|files?|pdfs?|papers?|uploads?)\b"
    r"|\bknowledge\s+(?:base|folder)\b|\bsearch\s+(?:my|the)\s+(?:documents?|notes?|files?)\b", re.I)


def document_context(message: str) -> tuple[str, list[str]]:
    """Excerpts added to the system prompt when the user asks about their own documents, so the answer
    doesn't depend on a small model deciding to call the tool. ('', []) otherwise."""
    if not DOC_INTENT.search(message) or knowledge.count() == 0:
        return "", []
    text, sources = _search_documents(message)
    if not sources:
        return "", []
    return ("\n\nExcerpts from the user's own documents that match this question. Answer from them and cite the "
            "[document name]. If they don't contain the answer, say so:\n\n" + text), sources


@tool
def search_memory(query: str) -> str:
    """Search the personal memories the user asked you to keep (goals, preferences, facts about them)."""
    if not db.get_prefs().get("memory_enabled", True):
        return "Long-term memory is turned off by the user."
    found = mem.search_memories(query, limit=6, fallback_recent=True)
    return "\n".join(f"- ({m['category']}) {m['content']}" for m in found) or "No saved memories match."


@tool
def save_memory(content: str, category: str = "general") -> str:
    """Save a short personal memory. Use ONLY when the user explicitly asks you to remember something.
    category: general | preference | goal | weak_topic | fact"""
    if not db.get_prefs().get("memory_enabled", True):
        return "Not saved: long-term memory is turned off. The user can enable it in Settings > Memory."
    m = mem.add_memory(content, category=category, source="assistant")
    return f"Saved memory #{m['id']}: {m['content']}"


@tool
def web_search(query: str) -> str:
    """Search the live web with DuckDuckGo for recent news, releases and time-sensitive facts."""
    if not lfs.load_settings()["features"].get("web_search", True):
        return "Web search is disabled in settings. Answer from local knowledge and say so."
    if not internet_available():
        return "OFFLINE: this computer has no internet connection, so live web search is unavailable."
    try:
        from ddgs import DDGS
        results = DDGS().text(query, max_results=5)
    except Exception as e:
        log.warning("web_search failed: %s", type(e).__name__)
        return f"Web search is unavailable right now ({type(e).__name__})."
    if not results:
        return "No web results found."
    return "\n\n".join(f"{r.get('title', '')}\n{r.get('body', '')}\nSource: {r.get('href', '')}" for r in results)


TOOLS = [knowledge_base_search, search_memory, save_memory, web_search]


# --------------------------------------------------------------------------- graph
@lru_cache
def get_llm(temperature: float | None = None, json_mode: bool = False) -> ChatOllama:
    s = get_settings()
    return ChatOllama(model=active_models()["llm"], base_url=s.ollama_base_url,
                      temperature=s.llm_temperature if temperature is None else temperature,
                      format="json" if json_mode else None)


@lru_cache
def get_agent():
    """Build the ReAct agent once (history comes from SQLite, so no checkpointer)."""
    return create_react_agent(model=get_llm(), tools=TOOLS)


async def run_agent(message: str, conversation_id: str | None) -> dict:
    """Run one user turn. Returns {answer, conversation_id, tools_used, sources}."""
    prefs = db.get_prefs()
    cid = mem.ensure_conversation(conversation_id, message)
    history = []
    for m in mem.recent_messages(cid, limit=12):
        history.append(HumanMessage(m["content"]) if m["role"] == "user" else AIMessage(m["content"]))

    docs_text, doc_sources = await asyncio.to_thread(document_context, message)
    system = SYSTEM_PROMPT + mem.memory_context(message) + docs_text
    if prefs.get("user_name"):
        system += f"\n\nThe user's name is {prefs['user_name']}."
    inputs = {"messages": [SystemMessage(system), *history, HumanMessage(message)]}

    result = await get_agent().ainvoke(inputs, config={"recursion_limit": 12})
    new_msgs = result["messages"][len(inputs["messages"]):]

    tools_used, sources = (["knowledge_base_search"], list(doc_sources)) if doc_sources else ([], [])
    for m in new_msgs:
        if isinstance(m, AIMessage):
            tools_used += [tc["name"] for tc in (m.tool_calls or [])]
        if isinstance(m, ToolMessage):
            for line in str(m.content).splitlines():
                if line.startswith("Source: "):
                    sources.append(line[8:].strip())
                elif line.startswith("[") and line.endswith("]"):
                    sources.append(line[1:-1])
    answer = next((m.content for m in reversed(new_msgs) if isinstance(m, AIMessage) and m.content), "") \
        or "Sorry, I couldn't produce an answer."
    tools_used, sources = list(dict.fromkeys(tools_used)), list(dict.fromkeys(sources))[:6]

    if prefs.get("save_history", True):
        mem.add_message(cid, "user", message)
        mem.add_message(cid, "assistant", answer, tools_used, sources)
    return {"answer": answer, "conversation_id": cid, "tools_used": tools_used, "sources": sources}
