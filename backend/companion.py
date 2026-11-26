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
  * /health, /ping and /companion answer without a token; for an unpaired
    caller /health is reduced to "is the AI reachable" (see health_view)
  * every other API route needs companion access enabled AND a valid token
  * a valid token only opens the routes in REMOTE_ROUTES / REMOTE_PREFIXES
    (chat, learning content, voice, your documents, memory, history). Every
    other route, including system diagnostics, setup, backups, folder and
    watcher control, the API docs, and any route added later, stays
    local-only. main.py's local_only dependency is a second, per-route check.
  * responses to remote callers never contain token hashes, the Windows user
    name or absolute paths (see redact_text / main.py's _public_* helpers)

A tunnel or reverse proxy connects from 127.0.0.1, so requests carrying
forwarding headers are treated as remote, never as "this computer".
Ollama itself is never exposed: it keeps listening on its own 127.0.0.1 port.
"""
from __future__ import annotations

import os
import re
import socket
import time
from pathlib import Path
from typing import Any, Iterable

from starlette.requests import Request

import local_file_service as lfs
import membership

LOOPBACK = {"127.0.0.1", "::1", "localhost", "testclient"}
FORWARD_HEADERS = ("x-forwarded-for", "forwarded", "x-real-ip", "cf-connecting-ip", "true-client-ip",
                   "x-forwarded-host", "tailscale-user-login", "tailscale-user-name")
OPEN_API = {"/health", "/ping", "/companion"}
API_PREFIXES: set[str] = set()          # filled by main.py from the real route table

# What a paired phone may use (allow-list; everything else is local-only).
REMOTE_ROUTES = {
    "/chat", "/news", "/news/categories", "/news/feed", "/news/search", "/news/ai", "/generate-question", "/generate-mcq", "/transcribe", "/voice",
    "/knowledge", "/knowledge/upload", "/knowledge/status", "/knowledge/files",
    "/progress", "/memory", "/preferences", "/conversations",
    "/chat/stream", "/chat/warm",          # stability milestone: the same chat, streamed / its model loaded ahead (owner devices only)
}
REMOTE_PREFIXES = ("/progress/", "/memory/", "/conversations/", "/news/article/")
# What a *member* device (someone else's phone) may use: nothing that reads or writes the owner's
# data. /chat and /voice run as a "guest" chat for members (agent.run_agent(guest=True)).
MEMBER_ROUTES = {"/chat", "/news", "/generate-question", "/generate-mcq", "/transcribe", "/voice"}
# Reachable without a device credential (rate-limited), but only while companion access is on.
PAIRING_ROUTES = {"/companion/redeem", "/companion/register"}
LOCAL_ONLY_MESSAGE = "This action is only allowed from the computer running Abhify.AI"
MEMBER_DENIED_MESSAGE = "This feature is only available to the owner's devices."


def register_api_routes(paths: Iterable[str]) -> None:
    API_PREFIXES.clear()
    for p in paths:
        seg = "/" + p.strip("/").split("/")[0]
        if seg != "/":
            API_PREFIXES.add(seg)


LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1"}


def host_name(value: str | None) -> str:
    """The name part of a Host header or URL authority, lower-case, without port or IPv6 brackets."""
    v = (value or "").strip().lower()
    if v.startswith("["):                                       # [::1]:8000
        return v[1:v.find("]")] if "]" in v else v
    return v.rsplit(":", 1)[0] if v.count(":") == 1 else v


def is_local_request(request: Request) -> bool:
    """This computer's own owner: a loopback connection, no proxy/tunnel headers, AND a loopback Host header.
    7.1 security: the Host check stops DNS rebinding (a web page whose own domain resolves to 127.0.0.1 reached the API
    as the owner and could read and write memories). Such a request is now treated as remote, so it needs a paired token."""
    peer = request.client.host if request.client else ""
    if peer not in LOOPBACK:
        return False
    if any(h in request.headers for h in FORWARD_HEADERS):
        return False
    host = host_name(request.headers.get("host"))
    # no Host header at all: not a browser (browsers always send one), so not a rebinding vector
    return peer == "testclient" or host == "" or host in LOOPBACK_HOSTS


def _norm_origin(o: str) -> str:
    o = (o or "").strip().lower().rstrip("/")
    for scheme, port in (("http://", ":80"), ("https://", ":443")):
        if o.startswith(scheme) and o.endswith(port):
            o = o[: -len(port)]
    return o


def cross_site_reason(request: Request, trusted_origins: Iterable[str]) -> str | None:
    """7.1 security (CSRF / cross-origin protection, independent of CORS and never used as authentication): a browser
    request to the API made by ANOTHER website is refused. Browsers mark them with an Origin header and Sec-Fetch-Site;
    "simple" requests (form posts, multipart uploads, no-cors fetches) reach the server without any CORS preflight, so
    CORS alone can't stop them (a cross-site page could upload a document into the knowledge base).
    Allowed: the page's own origin (same host as the request), origins listed explicitly in CORS_ORIGINS, and clients that
    send neither header (the Android app, the desktop launcher, scripts), which then pass the normal authentication.
    Returns the reason to refuse, or None."""
    if request.method == "OPTIONS" or not is_api(request.url.path):
        return None
    trusted = {_norm_origin(o) for o in trusted_origins}
    if "*" in trusted:                                           # explicit testing opt-in (documented in config.py)
        return None
    origin = request.headers.get("origin")
    if origin is not None:
        o = _norm_origin(origin)
        own = o.split("://", 1)[-1] == _norm_origin("http://" + (request.headers.get("host") or "")).split("://", 1)[-1]
        if o and o != "null" and (own or o in trusted):
            return None
        return "Cross-site request refused: this page's origin isn't trusted by Abhify.AI."
    if (request.headers.get("sec-fetch-site") or "").lower() == "cross-site":
        return "Cross-site request refused."
    return None


def is_api(path: str) -> bool:
    return any(path == p or path.startswith(p + "/") for p in API_PREFIXES)


def _token_from(request: Request) -> str | None:
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip() or None
    return request.headers.get("x-abhify-token") or None


def device(request: Request) -> dict | None:
    """The paired device making this remote request (id, name, role), or None. Companion access must be on."""
    if not lfs.load_settings()["companion"].get("enabled"):
        return None
    return membership.authenticate(_token_from(request))


def token_valid(request: Request) -> bool:
    return device(request) is not None


def remote_allowed(path: str) -> bool:
    return path in REMOTE_ROUTES or path.startswith(REMOTE_PREFIXES)


def member_allowed(path: str) -> bool:
    return path in MEMBER_ROUTES


def role_of(request: Request) -> str:
    """'owner' (this PC), 'owner_device', 'member', or 'none'. Set by check() for every API request."""
    return getattr(request.state, "role", "owner" if is_local_request(request) else "none")


def check(request: Request, allow_remote_admin: bool = False) -> tuple[int, str] | None:
    """None = allowed; otherwise (status code, message). Runs for EVERY request (middleware), and
    records the caller's role on request.state for the route handlers."""
    if request.method == "OPTIONS" or is_local_request(request):
        request.state.role = "owner"
        return None
    path = request.url.path
    if not is_api(path) or path in OPEN_API:
        return None
    if not lfs.load_settings()["companion"].get("enabled"):
        return 403, "Companion access is turned off on the computer running Abhify.AI."
    if path in PAIRING_ROUTES:
        return None                                   # rate-limited inside; no credential yet
    dev = membership.authenticate(_token_from(request))
    if dev is None:
        return 401, "This device isn't paired (or its access was revoked or has expired). Pair it again from the computer."
    request.state.role, request.state.device = dev["role"], dev
    if allow_remote_admin:
        return None
    if dev["role"] == "member":
        return None if member_allowed(path) else (403, MEMBER_DENIED_MESSAGE if remote_allowed(path) else LOCAL_ONLY_MESSAGE)
    return None if remote_allowed(path) else (403, LOCAL_ONLY_MESSAGE)


def is_remote(request: Request) -> bool:
    return not is_local_request(request)


# --------------------------------------------------------------------------- redaction for remote callers
_WIN_PATH = re.compile(r"(?i)(?<![a-z])[a-z]:[\x5c/][^\s'\"<>|]*")
_POSIX_HOME = re.compile(r"/(?:home|Users)/[^\s'\"<>|]*")


def redact_text(text: Any) -> Any:
    """Replace absolute paths (which contain the OS user name) with a placeholder."""
    if not isinstance(text, str):
        return text
    text = _WIN_PATH.sub("<path>", text)
    return _POSIX_HOME.sub("<path>", text)


def public_name(path: Any) -> str | None:
    """Only the last path component, e.g. the knowledge folder's name without the user's profile path."""
    return Path(str(path)).name if path else None


def health_view(health: dict[str, Any], request: Request) -> dict[str, Any]:
    """/health is open so a phone can tell "PC reachable" from "offline". Unpaired remote callers
    only learn whether the AI is reachable, never model names, document counts or setup state."""
    if is_local_request(request):
        return {**health, "remote": False, "authorized": True, "role": "owner"}
    cfg = lfs.load_settings()["companion"]
    dev = device(request)
    if dev and dev["role"] == "owner_device":
        return {**health, "remote": True, "authorized": True, "companion_enabled": True, "role": "owner_device"}
    keep = ("app", "version", "status", "ollama", "llm_ready")
    out = {**{k: health.get(k) for k in keep}, "remote": True, "authorized": bool(dev),
           "companion_enabled": bool(cfg.get("enabled"))}
    if dev:                                    # member: AI status only, nothing about the owner's data
        out["role"] = "member"
    elif cfg.get("enabled"):
        out["registration"] = membership.get_mode()           # lets an unpaired phone offer "Join"
    return out


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


def actual_port() -> int:
    """The port this server really uses (the launcher sets ABHIFY_PORT, including fallback ports)."""
    try:
        return int(os.environ.get("ABHIFY_PORT", "8000"))
    except ValueError:
        return 8000


def listening_on_lan() -> bool:
    return os.environ.get("ABHIFY_BOUND_HOST", "127.0.0.1") not in ("127.0.0.1", "localhost", "::1")


def connection_info() -> dict:
    """For the owner on the PC: how phones reach this PC, with the ACTUAL port."""
    port = actual_port()
    cfg = lfs.load_settings()["companion"]
    return {"port": port, "tailscale_command": f"tailscale serve --bg {port}",
            "lan_access": bool(cfg.get("lan_access")), "listening_on_lan": listening_on_lan(),
            "lan_urls": [f"http://{ip}:{port}" for ip in lan_addresses()] if cfg.get("lan_access") else []}


def enable() -> dict:
    """Owner action on the PC: turn companion access on and pair one of the owner's own devices."""
    lfs.update_settings(companion={"enabled": True, "created_at": time.time()})
    dev = membership.issue_owner_device("Paired device")
    return {
        "enabled": True,
        "token": dev["token"],                             # shown once; only its hash is stored
        **connection_info(),
        "next_steps": "Recommended: install Tailscale on this PC and the phone, run the Tailscale command shown "
                      "here, then open the https://…ts.net address on the phone with the pairing link. Plain Wi-Fi "
                      "(HTTP) needs 'Allow access from my local network' and can't install the app or use the "
                      "microphone. See docs/MOBILE_SETUP.md.",
    }


def disable() -> dict:
    """Turn companion access off and revoke every device credential and open invitation (rows are kept)."""
    lfs.update_settings(companion={"enabled": False, "token_sha256": None, "created_at": None})
    return {"enabled": False, "revoked_devices": membership.revoke_all()}


def status(request: Request) -> dict:
    cfg = lfs.load_settings()["companion"]
    local = is_local_request(request)
    dev = None if local else device(request)
    out = {"enabled": bool(cfg.get("enabled")), "this_device_is_host": local,
           "authorized": local or dev is not None, "role": "owner" if local else (dev["role"] if dev else "none")}
    if local:                                              # never reveal network details to remote callers
        out.update({"lan_addresses": lan_addresses(), "registration_mode": membership.get_mode(),
                    **connection_info()})          # connection_info() includes listening_on_lan
    elif dev:
        out["device_name"] = dev["name"]
    elif cfg.get("enabled"):
        out["registration"] = membership.get_mode()
    return out
