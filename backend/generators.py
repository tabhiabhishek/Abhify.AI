"""
generators.py: AI-generated content for the News, Interview and MCQ tabs.

* News: real headlines from DuckDuckGo News, rewritten into short, learner-friendly
  summaries by the local LLM (and cached, because news doesn't change every second).
* Interview questions / MCQs: generated fresh by the LLM in JSON mode and
  validated with Pydantic, so the frontend always receives a predictable shape.
"""
from __future__ import annotations

import asyncio
import json
import logging
import random
import re
import time
from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, Field, ValidationError, field_validator

from agent import get_llm
from config import get_settings

log = logging.getLogger(__name__)

Difficulty = Literal["easy", "medium", "hard"]

CATEGORIES = {
    "python": "Python programming for ML engineers",
    "ml": "classical machine learning",
    "dl": "deep learning and neural networks",
    "nlp": "natural language processing and transformers",
    "cv": "computer vision",
    "llm": "large language models, RAG and AI agents",
    "mlops": "MLOps, deployment and model serving",
    "ai": "AI fundamentals and evaluation metrics",
}


class GenerationError(RuntimeError):
    """Raised when the LLM is unreachable or returns unusable output."""


# --------------------------------------------------------------------------- schemas
class NewsItem(BaseModel):
    title: str
    summary: str
    source: str = "Web"
    url: str | None = None
    time: str = ""
    isBreaking: bool = False


class InterviewQuestion(BaseModel):
    category: str
    question: str
    answer: str
    difficulty: Difficulty = "medium"
    keyPoints: list[str] = Field(default_factory=list)


class MCQ(BaseModel):
    category: str
    question: str
    options: list[str]
    correctAnswer: int
    explanation: str

    @field_validator("options")
    @classmethod
    def four_options(cls, v: list[str]) -> list[str]:
        if len(v) != 4:
            raise ValueError("MCQ must have exactly 4 options")
        return v

    @field_validator("correctAnswer")
    @classmethod
    def valid_index(cls, v: int) -> int:
        if not 0 <= v <= 3:
            raise ValueError("correctAnswer must be 0-3")
        return v


# --------------------------------------------------------------------------- helpers
async def _llm_json(prompt: str, temperature: float = 0.7, retries: int = 2) -> dict:
    """Ask the LLM for a JSON object; retry on malformed output."""
    llm = get_llm(temperature=temperature, json_mode=True)
    last_err: Exception | None = None
    for _ in range(retries + 1):
        try:
            msg = await llm.ainvoke(prompt)
            return json.loads(msg.content)
        except json.JSONDecodeError as e:
            last_err = e
        except Exception as e:  # Ollama down / model missing
            raise GenerationError(f"LLM unavailable: {e}") from e
    raise GenerationError(f"LLM returned invalid JSON: {last_err}")


def _validate_list(raw: list, model: type[BaseModel]) -> list:
    """Keep only items that pass validation (LLMs occasionally emit a bad one)."""
    out = []
    for item in raw or []:
        try:
            out.append(model.model_validate(item))
        except ValidationError as e:
            log.debug("Dropped invalid item: %s", e)
    return out


def _parse_date(date_str: str | None) -> datetime | None:
    """DuckDuckGo usually returns ISO-8601, but not always; unknown formats -> None."""
    if not date_str:
        return None
    try:
        dt = datetime.fromisoformat(str(date_str).replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _relative_time(date_str: str | None) -> str:
    dt = _parse_date(date_str)
    if not dt:
        return ""
    mins = int((datetime.now(timezone.utc) - dt).total_seconds() // 60)
    if mins < 60:
        return f"{max(mins, 1)} min ago"
    if mins < 24 * 60:
        return f"{mins // 60} h ago"
    days = mins // (24 * 60)
    return "Yesterday" if days == 1 else f"{days} days ago"


# --------------------------------------------------------------------------- news
_news_cache: dict = {"at": 0.0, "items": []}


def _fetch_headlines(limit: int) -> list[dict]:
    """Blocking DuckDuckGo News call (run in a thread)."""
    from ddgs import DDGS
    seen, out = set(), []
    queries = ("new AI model release", "large language model research", "machine learning breakthrough",
               "OpenAI OR Anthropic OR Google DeepMind OR Meta AI", "open source AI model")
    for q in queries:
        for r in DDGS().news(q, max_results=limit, timelimit="w"):
            key = r.get("title", "").lower()[:60]
            if key and key not in seen:
                seen.add(key)
                out.append(r)
    oldest = datetime.min.replace(tzinfo=timezone.utc)
    out.sort(key=lambda r: _parse_date(r.get("date")) or oldest, reverse=True)
    return out[: limit * 3]          # extra candidates; the LLM filters for relevance


async def get_news(limit: int = 8, refresh: bool = False) -> list[dict]:
    """Latest AI/ML news, summarized by the local LLM. Cached for `news_cache_ttl`."""
    ttl = get_settings().news_cache_ttl
    if not refresh and _news_cache["items"] and time.time() - _news_cache["at"] < ttl:
        return _news_cache["items"]

    try:
        headlines = await asyncio.to_thread(_fetch_headlines, limit)
    except Exception as e:
        raise GenerationError(f"News search unavailable: {e}") from e
    if not headlines:
        raise GenerationError("No news found")

    numbered = "\n".join(f"{i}. {h['title']} | {h.get('body', '')[:400]}" for i, h in enumerate(headlines))
    data = await _llm_json(
        "You curate a news feed for engineers learning AI/ML.\n"
        "Classify EVERY numbered article with exactly one topic:\n"
        "  model_release | research | tools | hardware | open_source | industry | finance | politics | opinion | other\n"
        "Use finance for stock prices, investing and earnings; politics for government, policy and national strategy.\n"
        "Then write a clear 2-3 sentence summary explaining why it matters to an AI/ML learner, "
        "and set isBreaking=true only for truly major announcements (at most 2).\n"
        'Return JSON: {"items": [{"index": <number>, "topic": "...", "summary": "...", "isBreaking": false}]}\n\n'
        f"Articles:\n{numbered}",
        temperature=0.2,
    )
    by_index = {}
    for it in data.get("items", []):
        try:
            by_index[int(it.get("index", -1))] = it
        except (TypeError, ValueError, AttributeError):
            continue

    # Keep only learner-relevant topics (in recency order). If the model labelled
    # nothing as relevant, fall back to everything rather than showing an empty feed.
    relevant = {"model_release", "research", "tools", "hardware", "open_source", "industry"}
    keep = [i for i in range(len(headlines)) if by_index.get(i, {}).get("topic") in relevant] or list(range(len(headlines)))
    log.info("News: %d candidates, %d relevant", len(headlines), len(keep))
    items = []
    for i in keep[:limit]:
        h, gen = headlines[i], by_index.get(i, {})
        items.append(NewsItem(
            title=h.get("title", "").strip(),
            summary=(gen.get("summary") or h.get("body", "")).strip(),
            source=h.get("source") or "Web",
            url=h.get("url"),
            time=_relative_time(h.get("date")),
            isBreaking=bool(gen.get("isBreaking", False)),
        ).model_dump())

    _news_cache.update(at=time.time(), items=items)
    return items


# --------------------------------------------------------------------------- interview
async def generate_questions(category: str, difficulty: str | None, count: int, exclude: list[str]) -> list[dict]:
    cat = category if category in CATEGORIES else random.choice(list(CATEGORIES))
    diff = difficulty or random.choice(["easy", "medium", "hard"])
    avoid = "\n".join(f"- {q}" for q in exclude[-15:]) or "(none)"
    data = await _llm_json(
        f"You are a senior AI/ML interviewer. Write {count + 1} distinct {diff} interview questions about "
        f"{CATEGORIES[cat]}. Each answer should be a strong, interview-ready answer of 3-6 sentences, "
        "plus 2-4 short key points the candidate must mention.\n"
        f"Do NOT repeat or paraphrase these already-asked questions:\n{avoid}\n\n"
        'Return JSON: {"questions": [{"category": "' + cat + '", "question": "...", "answer": "...", '
        '"difficulty": "' + diff + '", "keyPoints": ["...", "..."]}]}',
        temperature=0.8,
    )
    qs = _validate_list(data.get("questions", []), InterviewQuestion)
    if not qs:
        raise GenerationError("LLM produced no valid questions")
    return [{**q.model_dump(), "category": cat} for q in qs[:count]]


# --------------------------------------------------------------------------- mcq
async def generate_mcqs(category: str, count: int, exclude: list[str]) -> list[dict]:
    cat = category if category in CATEGORIES else random.choice(list(CATEGORIES))
    avoid = "\n".join(f"- {q}" for q in exclude[-15:]) or "(none)"
    data = await _llm_json(
        f"Create {count + 2} multiple-choice questions about {CATEGORIES[cat]} for an AI/ML learner. "
        "Each question has exactly 4 options, exactly one correct, plausible distractors, "
        "and a 1-2 sentence explanation. Vary which option index is correct.\n"
        f"Do NOT repeat these questions:\n{avoid}\n\n"
        'Return JSON: {"mcqs": [{"category": "' + cat + '", "question": "...", '
        '"options": ["A", "B", "C", "D"], "correctAnswer": 0, "explanation": "..."}]}',
        temperature=0.8,
    )
    mcqs = _validate_list(data.get("mcqs", []), MCQ)
    if not mcqs:
        raise GenerationError("LLM produced no valid MCQs")
    out = []
    for m in mcqs[:count]:
        # LLMs tend to put the right answer in the same slot, so shuffle server-side.
        options = [_strip_letter(o) for o in m.options]
        correct_text = options[m.correctAnswer]
        random.shuffle(options)
        out.append({**m.model_dump(), "category": cat, "options": options,
                    "correctAnswer": options.index(correct_text)})
    return out


_LETTER = re.compile(r"^\s*(?:\(?[A-Da-d1-4][).:\]]|[A-Da-d]\s*-)\s+")


def _strip_letter(option: str) -> str:
    """'B) Sigmoid' -> 'Sigmoid' (the UI adds its own A-D badges)."""
    return _LETTER.sub("", option).strip()
