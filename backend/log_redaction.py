"""
log_redaction.py: keeps secrets and personal paths out of Abhify.AI's logs (F3, dev.3).

One logging.Filter, installed on uvicorn's loggers and on every root handler:
  * access-log lines keep the path but drop query-string VALUES
    ("GET /memory?token=abc" -> "GET /memory?token=***"), so a token put in a URL by
    mistake is never written to disk
  * any message: `Authorization: Bearer …`, bearer tokens, `token=` / `pair=` /
    `key=` / `password=` style parameters and `#pair=` fragments are masked
The app itself sends the companion token only in the Authorization header, which
is never logged; this is defence in depth. The desktop launcher also turns the
uvicorn access log off entirely.
"""
from __future__ import annotations

import logging
import re
from urllib.parse import parse_qsl, urlsplit

MASK = "***"   # ASCII: Windows consoles may not encode an ellipsis
_SECRET_PARAM = re.compile(r"(?i)([?&#;\s]|^)((?:access_)?token|pair|api[_-]?key|key|password|passwd|secret|auth)=([^&#\s\"']+)")
_BEARER = re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]+")
_AUTH_HEADER = re.compile(r"(?i)(authorization|x-abhify-token)(['\"]?\s*[:=]\s*['\"]?)[^'\",}\s]+(?:\s+[^'\",}\s]+)?")


def redact(text: str) -> str:
    text = _AUTH_HEADER.sub(lambda m: f"{m.group(1)}{m.group(2)}{MASK}", text)
    text = _BEARER.sub(lambda m: f"{m.group(1)} {MASK}", text)
    return _SECRET_PARAM.sub(lambda m: f"{m.group(1)}{m.group(2)}={MASK}", text)


def sanitize_target(target: str) -> str:
    """Request target for the access log: path kept, every query value and any fragment removed."""
    parts = urlsplit(target)
    if not parts.query and not parts.fragment:
        return target
    keys = [k for k, _ in parse_qsl(parts.query, keep_blank_values=True)]
    query = "&".join(f"{k}={MASK}" for k in keys)
    return parts.path + (f"?{query}" if query else "")


class RedactingFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            if record.name == "uvicorn.access" and isinstance(record.args, tuple) and len(record.args) >= 3:
                args = list(record.args)
                args[2] = sanitize_target(str(args[2]))          # (client, method, target, http, status)
                record.args = tuple(args)
                return True
            msg = record.getMessage()
            clean = redact(msg)
            if clean != msg:
                record.msg, record.args = clean, ()
        except Exception:                                        # a log filter must never break logging
            pass
        return True


FILTER = RedactingFilter()
LOGGERS = ("uvicorn", "uvicorn.access", "uvicorn.error", "abhify")


def install(*handlers: logging.Handler) -> None:
    """Idempotent. Logger filters cover records logged to those loggers; handler filters cover
    records propagated from any module to the root handlers (console and abhify.log)."""
    for name in LOGGERS:
        lg = logging.getLogger(name)
        if FILTER not in lg.filters:
            lg.addFilter(FILTER)
        for h in lg.handlers:
            if FILTER not in h.filters:
                h.addFilter(FILTER)
    for h in (*logging.getLogger().handlers, *handlers):
        if FILTER not in h.filters:
            h.addFilter(FILTER)
