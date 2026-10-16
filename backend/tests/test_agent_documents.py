"""D3 regression tests (dev.4): document questions don't depend on a small model choosing the tool.

Found in the dev.3 clean-PC Sandbox test: qwen2.5:1.5b answered "I don't have access to your
documents" without calling knowledge_base_search, although the PDF was indexed.
"""
import asyncio

import pytest
from langchain_core.messages import AIMessage

import agent
import document_indexer as di


class CapturingAgent:
    """Stands in for the LangGraph agent: records the prompt and answers without calling tools."""

    def __init__(self):
        self.system = None

    async def ainvoke(self, inputs, config=None):
        self.system = inputs["messages"][0].content
        return {"messages": [*inputs["messages"], AIMessage("The codeword is BLUE-HERON-9 [notes.md].")]}


@pytest.fixture
def indexed(kb):
    f = kb / "notes.md"
    f.write_text("Acceptance test document. The test codeword is BLUE-HERON-9.", encoding="utf-8")
    di.index_file(f, kb)
    return f


@pytest.fixture
def fake_agent(monkeypatch):
    a = CapturingAgent()
    monkeypatch.setattr(agent, "get_agent", lambda: a)
    return a


@pytest.mark.parametrize("q", ["Search my documents: what is the test codeword?",
                               "What do my notes say about the codeword?",
                               "What does my knowledge base say about the test codeword?"])
def test_document_questions_get_excerpts(indexed, fake_agent, q):
    r = asyncio.run(agent.run_agent(q, None))
    assert "BLUE-HERON-9" in fake_agent.system                  # the model sees the excerpt
    assert "knowledge_base_search" in r["tools_used"] and "notes.md" in r["sources"]


@pytest.mark.parametrize("q", ["What is overfitting?", "How do I read files in Python?",
                               "Explain gradient descent with notes on learning rates"])
def test_general_questions_unchanged(indexed, fake_agent, q):
    r = asyncio.run(agent.run_agent(q, None))
    assert "Excerpts from the user's own documents" not in fake_agent.system
    assert r["tools_used"] == [] and r["sources"] == []


def test_empty_knowledge_base_adds_nothing(kb, fake_agent):
    r = asyncio.run(agent.run_agent("Search my documents for the codeword", None))
    assert "Excerpts from the user's own documents" not in fake_agent.system and r["sources"] == []


def test_tool_output_unchanged(indexed):
    out = agent.knowledge_base_search.invoke({"query": "test codeword"})
    assert out.startswith("[notes.md]") and "BLUE-HERON-9" in out
