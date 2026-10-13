"""
agent.py: the LangGraph ReAct agent ("the AI brain").

The agent runs on a local Ollama model (qwen2.5 by default, which is strong at
tool calling) and can choose between two tools:

  * knowledge_base_search: searches the user's uploaded PDFs/notes (ChromaDB)
  * web_search:            searches the live web via DuckDuckGo (no API key)

LangGraph's prebuilt ReAct graph handles the loop:

    user ─► agent (LLM) ─► tool calls? ──yes──► tools ─┐
                 ▲                                     │
                 └─────────────── ToolMessage ◄────────┘
                         │ no
                         ▼
                   final answer

Conversation memory is kept per `thread_id` with an in-memory checkpointer, so
each browser session gets its own context.
"""
from __future__ import annotations

import logging
from functools import lru_cache

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import tool
from langchain_ollama import ChatOllama
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.prebuilt import create_react_agent

import knowledge
from config import get_settings

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are AI Tech Pulse, a friendly AI/ML learning companion and interview coach.

How to choose tools:
- Use `knowledge_base_search` FIRST when the question could be answered from the user's own
  uploaded notes or documents (e.g. "my notes", "the PDF", course material, definitions they studied).
- Use `web_search` for anything recent or time-sensitive: news, releases, versions, prices,
  "latest", "today", "this week", or facts you are unsure about.
- Answer directly without tools for stable fundamentals you know well (e.g. "what is overfitting").

Answer style:
- Be concise and clear; use short paragraphs or bullet points. Use Markdown.
- When you used a tool, mention the source (document name or website) briefly.
- If tools return nothing useful, say so honestly instead of inventing facts.
"""


# --------------------------------------------------------------------------- tools
@tool
def knowledge_base_search(query: str) -> str:
    """Search the user's personal knowledge base (uploaded PDFs and notes).
    Use for questions about the user's own documents or study material."""
    docs = knowledge.search(query, k=4)
    if not docs:
        return "The knowledge base is empty or has nothing relevant to this query."
    parts = []
    for d in docs:
        src = d.metadata.get("source", "unknown")
        page = d.metadata.get("page")
        where = f"{src}, page {page + 1}" if isinstance(page, int) else src
        parts.append(f"[{where}]\n{d.page_content.strip()}")
    return "\n\n---\n\n".join(parts)


@tool
def web_search(query: str) -> str:
    """Search the live web with DuckDuckGo. Use for recent news, releases,
    and any time-sensitive or uncertain facts."""
    try:
        from ddgs import DDGS
        results = DDGS().text(query, max_results=5)
    except Exception as e:  # network down, rate-limited, etc.
        log.warning("web_search failed: %s", e)
        return f"Web search is unavailable right now ({type(e).__name__})."
    if not results:
        return "No web results found."
    return "\n\n".join(f"{r.get('title', '')}\n{r.get('body', '')}\nSource: {r.get('href', '')}" for r in results)


TOOLS = [knowledge_base_search, web_search]


# --------------------------------------------------------------------------- graph
@lru_cache
def get_llm(temperature: float | None = None, json_mode: bool = False) -> ChatOllama:
    """Shared ChatOllama client. `json_mode=True` forces valid JSON output."""
    s = get_settings()
    return ChatOllama(
        model=s.llm_model,
        base_url=s.ollama_base_url,
        temperature=s.llm_temperature if temperature is None else temperature,
        format="json" if json_mode else None,
    )


@lru_cache
def get_agent():
    """Build the ReAct agent once per process."""
    return create_react_agent(
        model=get_llm(),
        tools=TOOLS,
        prompt=SYSTEM_PROMPT,
        checkpointer=InMemorySaver(),   # per-thread conversation memory
    )


async def run_agent(message: str, thread_id: str) -> dict:
    """Run one user turn through the agent.

    Returns {"answer": str, "tools_used": [names], "sources": [str]}.
    """
    agent = get_agent()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 12}
    before = len((await agent.aget_state(config)).values.get("messages", []))

    result = await agent.ainvoke({"messages": [HumanMessage(content=message)]}, config=config)
    new_msgs = result["messages"][before:]          # only this turn's messages

    tools_used, sources = [], []
    for m in new_msgs:
        if isinstance(m, AIMessage):
            tools_used += [tc["name"] for tc in (m.tool_calls or [])]
        if isinstance(m, ToolMessage):
            for line in str(m.content).splitlines():
                if line.startswith("Source: "):
                    sources.append(line[8:].strip())
                elif line.startswith("[") and line.endswith("]"):
                    sources.append(line[1:-1])

    answer = next((m.content for m in reversed(new_msgs) if isinstance(m, AIMessage) and m.content), "")
    return {
        "answer": answer or "Sorry, I couldn't produce an answer.",
        "tools_used": list(dict.fromkeys(tools_used)),
        "sources": list(dict.fromkeys(sources))[:6],
    }
