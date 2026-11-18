"""Administrator-managed shared provider access (Gemini, Groq, OpenRouter) for signed-in users.

* The keys live only in the server's environment (ABHIFY_CLOUD_SHARED_*_KEY). No API response ever contains them;
  devices only see provider names, models and availability.
* Off unless ABHIFY_CLOUD_SHARED_ENABLED=true. Per-user daily request limit; usage counted per user/day/provider.
* Only free models unless the administrator sets ABHIFY_CLOUD_SHARED_ALLOW_CHARGED=true. Never sends OpenRouter
  "models" fallback lists (fallbacks to paid models are billed).
Endpoints and rules follow each provider's docs (read 2026-11-02, see docs/RC3_AUDIT_AND_PLAN.md).
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import AsyncIterator

import httpx

log = logging.getLogger("abhify.cloud.providers")


@dataclass(frozen=True)
class Spec:
    id: str
    title: str
    chat_url: str


SPECS = {
    "gemini": Spec("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
    "groq": Spec("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions"),
    "openrouter": Spec("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions"),
}
GEMINI_FREE = {"gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"}


def model_is_free(provider: str, model: str) -> bool:
    if provider == "gemini":
        return model in GEMINI_FREE
    if provider == "openrouter":
        return model == "openrouter/free" or model.endswith(":free")
    return True                                         # Groq: free plan; charges only after upgrading the account


def error_message(code: int, body: str, retry_after: str | None) -> str:
    """Readable provider error. The provider's own message is included except for auth errors (it may echo the key)."""
    msg = None
    try:
        o = json.loads(body)
        if isinstance(o, list) and o:
            o = o[0]
        if isinstance(o, dict):
            e = o.get("error")
            msg = (e.get("message") if isinstance(e, dict) else None) or o.get("message")
    except (ValueError, AttributeError):
        pass
    base = {401: "the shared key was rejected", 402: "no credits left (nothing was charged)", 403: "access refused",
            404: "model not available", 429: "rate limit or daily quota reached"}.get(code, f"HTTP {code}")
    if code == 429 and retry_after:
        base += f" (retry in {retry_after}s)"
    return f"{base}: {str(msg)[:160]}" if msg and code not in (401, 403) else base


def parse_line(line: str) -> tuple[str, str] | None:
    """SSE line → ("token", text) | ("error", message) | ("done", "") | None to skip."""
    t = line.strip()
    if not t or t.startswith(":") or t.startswith("event:") or not t.startswith("data:"):
        return None
    data = t[5:].strip()
    if data == "[DONE]":
        return ("done", "")
    try:
        o = json.loads(data)
    except ValueError:
        return None
    if isinstance(o.get("error"), dict):                    # OpenRouter mid-stream error (HTTP 200)
        return ("error", str(o["error"].get("message", "provider error"))[:160])
    choices = o.get("choices") or []
    if not choices:
        return None
    if choices[0].get("finish_reason") == "error":
        return ("error", "the provider stopped with an error")
    text = (choices[0].get("delta") or {}).get("content")
    return ("token", text) if text else None


async def stream(spec: Spec, key: str, model: str, messages: list[dict], max_tokens: int, temperature: float,
                 transport: httpx.AsyncBaseTransport | None = None) -> AsyncIterator[tuple[str, str]]:
    """Yields ("token", text)… then ("done", "") or one ("error", message). Never raises for provider/network errors."""
    body = {"model": model, "stream": True, "temperature": temperature, "messages": messages,
            ("max_completion_tokens" if spec.id == "groq" else "max_tokens"): max_tokens}
    headers = {"Authorization": f"Bearer {key}"}
    if spec.id == "openrouter":
        headers.update({"HTTP-Referer": "https://abhify.ai", "X-Title": "Abhify"})
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, read=90.0), transport=transport) as c, \
                c.stream("POST", spec.chat_url, json=body, headers=headers) as r:
            if r.status_code != 200:
                yield ("error", error_message(r.status_code, (await r.aread()).decode("utf-8", "replace"), r.headers.get("retry-after")))
                return
            async for line in r.aiter_lines():
                ev = parse_line(line)
                if ev is None:
                    continue
                yield ev
                if ev[0] in ("done", "error"):
                    return
            yield ("done", "")                              # stream closed without [DONE]: treat as complete
    except httpx.HTTPError as e:
        log.warning("shared provider %s: %s", spec.id, type(e).__name__)   # never the key
        yield ("error", "the provider could not be reached")
