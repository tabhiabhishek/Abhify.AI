"""
companion.py: Mode B, using Abhify.AI from your phone through your own computer.

By default the backend listens on 127.0.0.1 only, so nothing else can reach it.
Companion access is an explicit opt-in on the computer:

  1. Settings → System & data → Companion access → Enable. A random access
     token is shown ONCE; only its SHA-256 hash is stored in settings.json.
  2. The launcher (or start.bat with ABHIFY_HOST=0.0.0.0) listens on the LAN,
     or you put a private HTTPS tunnel in front (Tailscale Serve recommended).
  3. The phone sends `Authorization: Bearer <token>` with every API call.

Rules enforced for every request that does NOT come from this computer:
  * the PWA's static files are always served (so the app can explain what's missing)
  * /health and /companion answer without a token (they reveal nothing personal)
  * every other API route needs companion access enabled AND a valid token
  * admin actions (backups, restore, moving data, model downloads, folder
    changes) stay local-only even with a valid token

A tunnel or reverse proxy connects from 127.0.0.1, so requests carrying
forwarding headers are treated as remote, never as "this computer".
Ollama itself is never exposed: it keeps listening on its own 127.0.0.1 port.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import socket
import time
from typing import Iterable

from starlette.requests import Request

import local_file_service as lfs

LOOPBACK = {"127.0.0.1", "::1", "localhost", "testclient"}
FORWARD_HEADERS = ("x-forwarded-for", "forwarded", "x-real-ip", "cf-connecting-ip", "true-client-ip",
                   "x-forwarded-host", "tailscale-user-login", "tailscale-user-name")
OPEN_API = {"/health", "/ping", "/companion"}
API_PREFIXES: set[str] = set()          # filled by main.py from the real route table


def register_api_routes(paths: Iterable[str]) -> None:
    API_PREFIXES.clear()
    for p in paths:
        seg = "/" + p.strip("/").split("/")[0]
        if seg != "/":
            API_PREFIXES.add(seg)


def is_local_request(request: Request) -> bool:
    host = request.client.host if request.client else ""
    if host not in LOOPBACK:
        return False
    return not any(h in request.headers for h in FORWARD_HEADERS)


def is_api(path: str) -> bool:
    return any(path == p or path.startswith(p + "/") for p in API_PREFIXES)


def _token_from(request: Request) -> str | None:
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip() or None
    return request.headers.get("x-abhify-token") or None


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def token_valid(request: Request) -> bool:
    cfg = lfs.load_settings()["companion"]
    token, stored = _token_from(request), cfg.get("token_sha256")
    return bool(cfg.get("enabled") and token and stored and hmac.compare_digest(_hash(token), stored))


def check(request: Request) -> tuple[int, str] | None:
    """None = allowed; otherwise (status code, message)."""
    if request.method == "OPTIONS" or is_local_request(request):
        return None
    path = request.url.path
    if not is_api(path) or path in OPEN_API:
        return None
    if not lfs.load_settings()["companion"].get("enabled"):
        return 403, "Companion access is turned off on the computer running Abhify.AI."
    if not token_valid(request):
        return 401, "Missing or invalid access token. Enter it in Settings → Backend on this device."
    return None


def lan_addresses() -> list[str]:
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


def enable() -> dict:
    token = secrets.token_urlsafe(24)
    lfs.update_settings(companion={"enabled": True, "token_sha256": _hash(token), "created_at": time.time()})
    port = os.environ.get("ABHIFY_PORT", "8000")
    return {
        "enabled": True,
        "token": token,                                    # shown once; not stored in plain text
        "lan_urls": [f"http://{ip}:{port}" for ip in lan_addresses()],
        "listening_on_lan": os.environ.get("ABHIFY_BOUND_HOST", "127.0.0.1") not in ("127.0.0.1", "localhost"),
        "next_steps": "Restart Abhify.AI so it listens on your network (the launcher does this automatically when "
                      "companion access is on). For installing the app and using the microphone on a phone, "
                      "use HTTPS via Tailscale Serve. See docs/MOBILE_INSTALLATION.md.",
    }


def disable() -> dict:
    lfs.update_settings(companion={"enabled": False, "token_sha256": None, "created_at": None})
    return {"enabled": False}


def status(request: Request) -> dict:
    cfg = lfs.load_settings()["companion"]
    local = is_local_request(request)
    out = {"enabled": bool(cfg.get("enabled")), "this_device_is_host": local,
           "authorized": local or token_valid(request)}
    if local:                                              # never reveal network details to remote callers
        out["lan_addresses"] = lan_addresses()
        out["listening_on_lan"] = os.environ.get("ABHIFY_BOUND_HOST", "127.0.0.1") not in ("127.0.0.1", "localhost")
    return out
