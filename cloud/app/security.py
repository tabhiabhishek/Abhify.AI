"""Passwords, tokens and rate limits.

* Passwords: scrypt (memory-hard; Python standard library via OpenSSL), N=2^15, r=8, p=1, 16-byte
  salt, constant-time comparison. Stored as "scrypt$N$r$p$salt_b64$hash_b64" so parameters can be
  raised later without breaking existing hashes.
* Tokens: 256-bit random, opaque (not JWTs), only SHA-256 hashes stored, so a database leak gives
  no usable tokens and any token can be revoked instantly.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import threading
import time
from collections import deque

SCRYPT_N, SCRYPT_R, SCRYPT_P = 2 ** 15, 8, 1
_MAXMEM = 64 * 1024 * 1024


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, maxmem=_MAXMEM, dklen=32)
    b = lambda x: base64.b64encode(x).decode()
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${b(salt)}${b(dk)}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, n, r, p, salt, dk = stored.split("$")
        if algo != "scrypt":
            return False
        expected = base64.b64decode(dk)
        got = hashlib.scrypt(password.encode("utf-8"), salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p),
                             maxmem=_MAXMEM, dklen=len(expected))
        return hmac.compare_digest(got, expected)
    except (ValueError, TypeError):
        return False


# Used when the email is unknown, so a login for a missing account costs the same time as a real one.
DUMMY_HASH = hash_password(secrets.token_urlsafe(16))


def new_token(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(32)}"


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_reset_code() -> str:
    return f"{secrets.randbelow(10 ** 8):08d}"          # 8 digits, 15 min, 5 attempts, rate-limited


def password_problem(password: str, min_length: int) -> str | None:
    if len(password) < min_length:
        return f"Use at least {min_length} characters."
    if len(password) > 128:
        return "Use at most 128 characters."
    if password.lower() == password or password.upper() == password or not any(c.isdigit() for c in password):
        return "Mix upper- and lower-case letters and at least one digit."
    return None


class RateLimiter:
    """Sliding-window limiter per key (one process). Behind several instances use a shared store."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str, limit: int, window_s: float) -> bool:
        now = time.monotonic()
        with self._lock:
            q = self._hits.setdefault(key, deque())
            while q and now - q[0] > window_s:
                q.popleft()
            if len(q) >= limit:
                return False
            q.append(now)
            if len(self._hits) > 50_000:                  # bound memory under abuse
                for k in [k for k, v in self._hits.items() if not v][:10_000]:
                    self._hits.pop(k, None)
            return True

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


limiter = RateLimiter()
