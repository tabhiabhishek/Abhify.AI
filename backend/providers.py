"""
providers.py: optional cloud AI providers for the desktop and web app (7.1), next to the local Ollama models.

Approved rules (owner decision, 2026-11-20):
  * Providers: Google Gemini, Groq, OpenRouter (the same endpoints and free-model rules as the Android app's
    providers/Providers.kt) and one custom OpenAI-compatible endpoint. Ollama stays the local default.
  * Keys: entered by the owner on this computer (Settings, or the optional, skippable setup step). Each key is encrypted with
    Windows DPAPI (bound to this Windows user; no new dependency) in <data root>/secrets/, which backups never include.
    A key is never returned to the page (only a mask), never logged, never put in a URL.
  * "Connected" only after a real call to the provider (its model list: generates nothing, costs nothing). Saved providers are
    checked again when the app starts.
  * Cloud is used only for a model the owner picked (or the opt-in Auto router). A failure is reported; it never moves the
    request to another provider or silently to the local model. Every answer names provider and model.
  * Cloud chats send the question, that chat's history and any live sources retrieved for it. Personal memories and
    document excerpts are NOT sent.
  * Custom endpoint: https:// to public addresses, or http(s)://127.0.0.1 / localhost (local servers such as LM Studio).
    Private LAN, link-local / cloud-metadata (169.254.x) and other reserved addresses are refused, checked again before
    every request; redirects are not followed.
"""
from __future__ import annotations

import asyncio
import base64
import ipaddress
import json
import logging
import re
import socket
import sys
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator
from urllib.parse import urlsplit

import httpx

import local_file_service as lfs

log = logging.getLogger(__name__)
_transport: httpx.AsyncBaseTransport | None = None            # tests install an httpx.MockTransport here
PREFIX = "@"                                                   # a cloud model id: "@groq:llama-3.3-70b-versatile"


@dataclass(frozen=True)
class Provider:
    id: str
    title: str
    chat_url: str
    models_url: str
    key_hint: str
    get_key_url: str
    default_model: str
    cost_note: str
    privacy_note: str
    preferred: tuple[str, ...]
    max_tokens: int
    key_required: bool = True
    max_tokens_field: str = "max_tokens"
    extra_headers: dict[str, str] = field(default_factory=dict)


GEMINI_FREE = {"gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"}
PROVIDERS: dict[str, Provider] = {p.id: p for p in (
    Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
             "https://generativelanguage.googleapis.com/v1beta/openai/models", "AIza…", "https://aistudio.google.com/apikey",
             "gemini-3.8-flash", "Free only while the key's Google project has NO billing linked. With billing linked, every request is charged.",
             "Google's free tier states that content you send is used to improve Google's products.",
             ("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.1-flash-lite"), 8192),
    Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "https://api.groq.com/openai/v1/models", "gsk_…",
             "https://console.groq.com/keys", "llama-3.3-70b-versatile",
             "Free on Groq's Free plan (rate-limited). Charges start only if the account is upgraded to a paid tier.",
             "Your messages are processed by Groq to answer them.",
             ("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant"), 4096,
             max_tokens_field="max_completion_tokens"),
    Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "https://openrouter.ai/api/v1/models", "sk-or-…",
             "https://openrouter.ai/keys", "openrouter/free",
             "Only free models (\":free\", zero price) are offered unless you allow charged models. Free models are limited per day.",
             "OpenRouter forwards your messages to the provider of the chosen free model, whose terms apply.",
             ("openrouter/free",), 4096, extra_headers={"HTTP-Referer": "https://abhify.ai", "X-Title": "Abhify"}),
)}
CUSTOM = "custom"
NOT_CHAT = re.compile(r"whisper|guard|orpheus|tts|embed|imagen|veo|image|audio|live|safeguard|moderation", re.I)
KEYLIKE = re.compile(r"(AIza[0-9A-Za-z_\-]{10,}|gsk_[A-Za-z0-9]{8,}|sk-[A-Za-z0-9_\-]{8,}|Bearer\s+\S+)")


class ProviderError(Exception):
    """A provider problem in one clear sentence (never contains a key)."""


# --------------------------------------------------------------------------- secrets (Windows DPAPI)
_ENTROPY = b"abhify-provider-key-v1"


def _dpapi(data: bytes, protect: bool) -> bytes:
    if sys.platform != "win32":
        raise ProviderError("Saving API keys needs Windows (keys are encrypted with Windows DPAPI).")
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def blob(b: bytes):
        buf = ctypes.create_string_buffer(b, len(b))
        return Blob(len(b), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char))), buf
    crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
    src, _keep = blob(data)
    ent, _keep2 = blob(_ENTROPY)
    out = Blob()
    ok = (crypt32.CryptProtectData(ctypes.byref(src), "Abhify.AI provider key", ctypes.byref(ent), None, None, 0x01, ctypes.byref(out))
          if protect else crypt32.CryptUnprotectData(ctypes.byref(src), None, ctypes.byref(ent), None, None, 0x01, ctypes.byref(out)))
    if not ok:
        raise ProviderError("Windows couldn't " + ("encrypt" if protect else "decrypt") + " the API key.")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def protect(data: bytes) -> bytes:
    return _dpapi(data, True)


def unprotect(data: bytes) -> bytes:
    return _dpapi(data, False)


def _secrets_path():
    return lfs.root() / "secrets" / "provider_keys.json"


def _secrets() -> dict[str, str]:
    try:
        return json.loads(_secrets_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _write_secrets(d: dict[str, str]) -> None:
    p = _secrets_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(d), encoding="utf-8")
    tmp.replace(p)


def key(pid: str) -> str | None:
    s = _secrets().get(pid)
    if not s:
        return None
    try:
        return unprotect(base64.b64decode(s)).decode("utf-8")
    except Exception:  # noqa: BLE001 - another Windows user / damaged file: treated as no key
        log.warning("provider key for %s couldn't be decrypted", pid)
        return None


def _store_key(pid: str, k: str) -> None:
    d = _secrets()
    d[pid] = base64.b64encode(protect(k.encode("utf-8"))).decode("ascii")
    _write_secrets(d)


def _drop_key(pid: str) -> None:
    d = _secrets()
    if d.pop(pid, None) is not None:
        _write_secrets(d)


def mask(k: str) -> str:
    k = k.strip()
    if len(k) <= 8:
        return "••••"
    m = re.match(r"^([A-Za-z]{1,6}[_-])", k)
    return (m.group(1) if m else k[:4]) + "••••••••" + k[-4:]


def sanitize(text: str) -> str:
    return KEYLIKE.sub("[redacted]", str(text or ""))[:200]


# --------------------------------------------------------------------------- settings (no secrets)
def _all_cfg() -> dict[str, Any]:
    return dict(lfs.load_settings().get("providers") or {})


def _cfg(pid: str) -> dict[str, Any]:
    return dict(_all_cfg().get(pid) or {})


def _save_cfg(pid: str, change: dict[str, Any] | None = None, drop: bool = False) -> None:
    allc = _all_cfg()
    if drop:
        allc.pop(pid, None)
    else:
        c = dict(allc.get(pid) or {})
        c.update(change or {})
        allc[pid] = c
    lfs.update_settings(providers=allc)


def router_settings() -> dict[str, Any]:
    r = _all_cfg().get("_router") or {}
    return {"auto": bool(r.get("auto")), "coding": r.get("coding") or None, "complex": r.get("complex") or None}


def save_router(auto: bool | None = None, coding: str | None = None, complex_: str | None = None) -> dict[str, Any]:
    cur = router_settings()
    if auto is not None:
        cur["auto"] = bool(auto)
    for k, v in (("coding", coding), ("complex", complex_)):
        if v is not None:
            if v and not is_cloud(v):
                raise ValueError("Auto routing targets must be cloud models (the rest stays on your local model).")
            cur[k] = v or None
    if cur["auto"] and not (cur["coding"] or cur["complex"]):
        raise ValueError("Choose a cloud model for coding or for complex questions before turning Auto routing on.")
    _save_cfg("_router", cur)
    return cur


# --------------------------------------------------------------------------- custom endpoint (SSRF rules)
def _ip_ok(ip: ipaddress._BaseAddress, local_ok: bool) -> bool:
    if ip.is_loopback:
        return local_ok
    return not (ip.is_private or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified
                or (ip.version == 4 and ip in ipaddress.ip_network("100.64.0.0/10")))


def check_url(url: str) -> str:
    """Validates a custom endpoint base URL (see the module rules); returns it normalised (no trailing slash)."""
    u = urlsplit((url or "").strip())
    if u.scheme not in ("http", "https") or not u.hostname:
        raise ValueError("Enter a full address starting with https:// (or http://127.0.0.1 for a server on this computer).")
    if u.username or u.password or u.fragment or u.query:
        raise ValueError("The address must not contain a user name, password, query or fragment.")
    host = u.hostname.lower()
    local_name = host in ("localhost", "127.0.0.1", "::1")
    if u.scheme == "http" and not local_name:
        raise ValueError("Plain http:// is only allowed for a server on this computer (127.0.0.1 or localhost). Use https://.")
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        literal = None
    if literal is not None and not _ip_ok(literal, local_ok=True):
        raise ValueError("That address is on a private, link-local or reserved network and isn't allowed.")
    return f"{u.scheme}://{u.netloc}{u.path.rstrip('/')}"


async def _resolve_ok(url: str) -> None:
    """Before each request to a custom endpoint: every address the host resolves to must be allowed (DNS can change)."""
    u = urlsplit(url)
    host = u.hostname or ""
    local_name = host.lower() in ("localhost", "127.0.0.1", "::1")
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(host, u.port or (443 if u.scheme == "https" else 80), type=socket.SOCK_STREAM)
    except OSError as e:
        raise ProviderError(f"The custom endpoint's address couldn't be resolved ({type(e).__name__}).") from None
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not _ip_ok(ip, local_ok=local_name):
            raise ProviderError("The custom endpoint resolves to a private or reserved network address, which isn't allowed.")


def custom_provider() -> Provider | None:
    c = _cfg(CUSTOM)
    base = c.get("base_url")
    if not base:
        return None
    return Provider(CUSTOM, c.get("name") or "Custom endpoint", base + "/chat/completions", base + "/models", "optional",
                    "", c.get("model") or "", "Billing depends on the endpoint you configured.",
                    "Your messages are sent to " + urlsplit(base).netloc + ".", (), 4096, key_required=False)


def provider(pid: str) -> Provider:
    if pid == CUSTOM:
        p = custom_provider()
        if not p:
            raise ProviderError("No custom endpoint is configured.")
        return p
    if pid not in PROVIDERS:
        raise ProviderError("Unknown provider.")
    return PROVIDERS[pid]


# --------------------------------------------------------------------------- rules (ported from Providers.kt)
def parse_models(pid: str, body: dict[str, Any]) -> list[dict[str, Any]]:
    out = []
    for m in body.get("data") or []:
        mid = str(m.get("id") or "").removeprefix("models/")
        if not mid or NOT_CHAT.search(mid):
            continue
        if pid == "gemini":
            if not mid.startswith("gemini"):
                continue
            free = mid in GEMINI_FREE
            out.append({"id": mid, "free": free, "note": "free tier" if free else "not on the free tier: may be charged"})
        elif pid == "groq":
            if m.get("active") is False:
                continue
            out.append({"id": mid, "free": True, "note": "free while your Groq account is on the Free plan"})
        elif pid == "openrouter":
            pr = m.get("pricing") or {}
            zero = all(_num(pr.get(k)) == 0.0 for k in ("prompt", "completion"))
            free = mid.endswith(":free") and zero
            out.append({"id": mid, "free": free, "note": "free" if free else "paid"})
        else:
            out.append({"id": mid, "free": None, "note": ""})
    out.sort(key=lambda x: (not x["free"], x["id"]))
    if pid == "openrouter":
        out = [{"id": "openrouter/free", "free": True, "note": "Free Models Router"}] + [x for x in out if x["id"] != "openrouter/free"]
    return out


def _num(v: Any) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def allowed(pid: str, model: str, allow_charged: bool) -> bool:
    if allow_charged or pid in ("groq", CUSTOM):
        return True
    if pid == "gemini":
        return model in GEMINI_FREE
    if pid == "openrouter":
        return model == "openrouter/free" or model.endswith(":free")
    return False


def error_message(title: str, code: int, body: str, retry_after: str | None = None) -> str:
    msg = None
    try:
        t = body.strip()
        o = json.loads(t)
        if isinstance(o, list):
            o = o[0]
        msg = (o.get("error") or {}).get("message") if isinstance(o.get("error"), dict) else o.get("message")
    except Exception:  # noqa: BLE001
        pass
    msg = sanitize(msg) if msg else None
    bad_key = code == 401 or (code == 400 and msg and re.search(r"api.?key", msg, re.I))
    model_problem = code == 404 or (msg and 400 <= code <= 404 and re.search(r"model.*(not|unknown|unsupported|invalid|exist|found)|no endpoints", msg, re.I))
    if bad_key:
        return f"{title} rejected the API key. Check the key in AI providers."
    if model_problem:
        return f"The selected {title} model is unavailable. Choose another model in AI providers."
    if code == 402:
        return f"{title}: no credits left on this account (nothing was charged)."
    if code == 403:
        return f"{title} refused access (key blocked, restricted or over budget)." + (f" {msg}" if msg else "")
    if code in (408, 504):
        return f"{title} timed out. Retry in a moment."
    if code == 429:
        return f"{title} rate limit reached" + (f" (try again in {retry_after}s)" if retry_after else "") + ". Retry later or choose another model."
    if 500 <= code <= 599:
        return f"{title} is unavailable right now (HTTP {code}). Retry later."
    return f"{title} answered HTTP {code}" + (f": {msg}" if msg else ".")


def parse_line(line: str) -> tuple[str, str]:
    """One SSE line → (kind, value): token / finish / error / done / skip."""
    t = line.strip()
    if not t.startswith("data:"):
        return "skip", ""
    data = t[5:].strip()
    if data == "[DONE]":
        return "done", ""
    try:
        o = json.loads(data)
    except ValueError:
        return "skip", ""
    if isinstance(o.get("error"), dict):
        return "error", sanitize(o["error"].get("message") or "The provider reported an error.")
    ch = (o.get("choices") or [{}])[0] or {}
    finish = ch.get("finish_reason")
    if finish == "error":
        return "error", "The provider stopped with an error."
    text = ((ch.get("delta") or {}).get("content")) or ""
    if text:
        return "token", text
    if finish:
        return "finish", str(finish)
    return "skip", ""


# --------------------------------------------------------------------------- HTTP
def _client(timeout: float = 20.0) -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=10.0), transport=_transport, follow_redirects=False)


def _headers(p: Provider, k: str | None) -> dict[str, str]:
    h = {"Content-Type": "application/json", **p.extra_headers}
    if k:
        h["Authorization"] = f"Bearer {k}"
    return h


async def _models(p: Provider, k: str | None) -> list[dict[str, Any]]:
    if p.id == CUSTOM:
        await _resolve_ok(p.models_url)
    try:
        async with _client() as c:
            r = await c.get(p.models_url, headers=_headers(p, k))
    except httpx.TimeoutException:
        raise ProviderError(f"{p.title} connection timed out. Check your internet connection and retry.") from None
    except httpx.HTTPError as e:
        raise ProviderError(f"{p.title} connection failed ({type(e).__name__}). Check your internet connection and retry.") from None
    if r.status_code != 200:
        raise ProviderError(error_message(p.title, r.status_code, r.text, r.headers.get("retry-after")))
    try:
        models = parse_models(p.id, r.json())
    except ValueError:
        raise ProviderError(f"{p.title} answered with something that isn't a model list.") from None
    if not models:
        raise ProviderError(f"{p.title} accepted the key but listed no usable chat models.")
    if p.id == "openrouter":
        # OpenRouter's model list is public (it answers 200 to any key), so the key itself is checked: GET /api/v1/key
        # (as Providers.kt validate() does). Found live: a fake key was "connected" without this.
        try:
            async with _client() as c:
                kr = await c.get("https://openrouter.ai/api/v1/key", headers=_headers(p, k))
        except httpx.HTTPError as e:
            raise ProviderError(f"{p.title} connection failed ({type(e).__name__}). Check your internet connection and retry.") from None
        if kr.status_code != 200:
            raise ProviderError(error_message(p.title, kr.status_code, kr.text, kr.headers.get("retry-after")))
    return models


def _adopt(pid: str, models: list[dict[str, Any]]) -> None:
    c = _cfg(pid)
    cur = c.get("model")
    ids = [m["id"] for m in models]
    if cur not in ids:
        p = provider(pid)
        free = [m["id"] for m in models if m["free"] is not False]
        cur = next((x for x in p.preferred if x in free), free[0] if free else ids[0])
    _save_cfg(pid, {"models": models[:200], "model": cur, "last_ok": int(time.time()), "last_error": None})


async def save_key(pid: str, k: str) -> dict[str, Any]:
    """Tests the key with a real (free) call first; only a working key is saved."""
    p = provider(pid)
    k = (k or "").strip()
    if (p.key_required and not (16 <= len(k) <= 512)) or len(k) > 512 or any(ch.isspace() for ch in k):
        raise ProviderError(f"That doesn't look like a {p.title} API key.")
    models = await _models(p, k or None)
    if k:
        _store_key(pid, k)
    _save_cfg(pid, {"masked": mask(k) if k else None, "enabled": True})
    _adopt(pid, models)
    return status_of(pid)


async def set_custom(base_url: str, name: str | None, k: str | None) -> dict[str, Any]:
    base = check_url(base_url)
    _save_cfg(CUSTOM, {"base_url": base, "name": (name or "").strip()[:40] or None})
    try:
        return await save_key(CUSTOM, k or "")
    except ProviderError:
        _save_cfg(CUSTOM, drop=True)
        _drop_key(CUSTOM)
        raise


async def test(pid: str) -> dict[str, Any]:
    p = provider(pid)
    k = key(pid)
    if p.key_required and not k:
        raise ProviderError(f"No API key saved for {p.title}.")
    try:
        _adopt(pid, await _models(p, k))
    except ProviderError as e:
        _save_cfg(pid, {"last_error": str(e), "last_check": int(time.time())})
        raise
    _save_cfg(pid, {"last_check": int(time.time())})
    return status_of(pid)


def remove(pid: str) -> None:
    provider(pid) if pid != CUSTOM else None
    _drop_key(pid)
    _save_cfg(pid, drop=True)
    r = router_settings()
    if any((r.get(k) or "").startswith(f"{PREFIX}{pid}:") for k in ("coding", "complex")):
        _save_cfg("_router", {"auto": False, **{k: (None if (r.get(k) or "").startswith(f"{PREFIX}{pid}:") else r.get(k)) for k in ("coding", "complex")}})


def update(pid: str, enabled: bool | None = None, model: str | None = None, allow_charged: bool | None = None, consent: bool | None = None) -> dict[str, Any]:
    provider(pid)
    c = _cfg(pid)
    if not c.get("last_ok"):
        raise ProviderError("Connect this provider first (save a working key).")
    ch: dict[str, Any] = {}
    if enabled is not None:
        ch["enabled"] = bool(enabled)
    if allow_charged is not None:
        ch["allow_charged"] = bool(allow_charged)
    if consent is not None:
        ch["consent"] = bool(consent)
    if model is not None:
        if model not in [m["id"] for m in c.get("models") or []]:
            raise ProviderError("That model isn't in this provider's list. Test the connection to refresh it.")
        ch["model"] = model
    _save_cfg(pid, ch)
    return status_of(pid)


def problem(pid: str, model: str | None = None) -> str | None:
    """Why this provider/model can't answer now, or None when it can."""
    try:
        p = provider(pid)
    except ProviderError as e:
        return str(e)
    c = _cfg(pid)
    model = model or c.get("model")
    if p.key_required and not _secrets().get(pid):
        return f"{p.title} has no API key saved"
    if not c.get("last_ok"):
        return f"{p.title} isn't connected yet (test the connection)"
    if c.get("enabled") is False:
        return f"{p.title} is turned off in AI providers"
    if not model:
        return f"no {p.title} model is selected"
    if not allowed(pid, model, bool(c.get("allow_charged"))):
        return f"{model} may be charged (allow charged models for {p.title}, or pick a free model)"
    return None


def status_of(pid: str) -> dict[str, Any]:
    p = provider(pid) if pid != CUSTOM or custom_provider() else None
    c = _cfg(pid)
    has_key = bool(_secrets().get(pid))
    state = ("not_configured" if (p is None or (p.key_required and not has_key)) else
             "off" if c.get("enabled") is False else "error" if c.get("last_error") else "connected" if c.get("last_ok") else "unchecked")
    return {"id": pid, "title": p.title if p else "Custom endpoint", "kind": "cloud", "state": state, "key_set": has_key,
            "masked": c.get("masked"), "enabled": c.get("enabled", True), "model": c.get("model"), "models": c.get("models") or [],
            "allow_charged": bool(c.get("allow_charged")), "consent": bool(c.get("consent")), "last_ok": c.get("last_ok"),
            "last_check": c.get("last_check"), "last_error": c.get("last_error"),
            "base_url": c.get("base_url") if pid == CUSTOM else None,
            **({"key_hint": p.key_hint, "get_key_url": p.get_key_url, "cost_note": p.cost_note, "privacy_note": p.privacy_note,
                "key_required": p.key_required} if p else {"key_required": False})}


def all_status() -> dict[str, Any]:
    return {"providers": [status_of(pid) for pid in (*PROVIDERS, CUSTOM)], "router": router_settings(),
            "rules": {"memories_sent": False, "documents_sent": False, "fallback_between_providers": False}}


def cloud_models() -> list[dict[str, Any]]:
    """The model picker's cloud entries: each connected, enabled provider's selected model (others are chosen in AI providers)."""
    out = []
    for pid in (*PROVIDERS, CUSTOM):
        s = status_of(pid)
        if s["state"] in ("connected", "error") and s["enabled"] and s["model"] and not problem(pid):
            out.append({"id": f"{PREFIX}{pid}:{s['model']}", "provider": pid, "provider_title": s["title"], "model": s["model"],
                        "consent": s["consent"], "privacy_note": s.get("privacy_note")})
    return out


async def check_all_on_start() -> None:
    """At app start: the saved providers are tested once (a model list each: nothing is generated). Never raises."""
    for pid in (*PROVIDERS, CUSTOM):
        if (_cfg(pid).get("last_ok") or _secrets().get(pid)) and _cfg(pid).get("enabled", True):
            try:
                await test(pid)
            except Exception as e:  # noqa: BLE001 - recorded as the provider's last error
                log.info("provider %s start check: %s", pid, type(e).__name__)


# --------------------------------------------------------------------------- chat
def is_cloud(model: str | None) -> bool:
    return bool(model) and str(model).startswith(PREFIX) and ":" in str(model)


def split(model: str) -> tuple[str, str]:
    pid, _, name = model[len(PREFIX):].partition(":")
    return pid, name


def resolve(model: str) -> str:
    """Checks a cloud model id can answer now (raises ValueError with the reason); returns it unchanged."""
    pid, name = split(model)
    why = problem(pid, name)
    if why:
        raise ValueError(f"Can't use {model[1:]}: {why}.")
    return model


def label(model: str) -> str:
    pid, name = split(model)
    try:
        return f"{provider(pid).title} · {name}"
    except ProviderError:
        return name


async def stream_chat(model: str, messages: list[dict[str, str]], temperature: float = 0.4) -> AsyncIterator[str]:
    """Streams answer text from a cloud model. Raises ProviderError (one sentence, no key) on any failure; never retries
    on another provider."""
    pid, name = split(model)
    why = problem(pid, name)
    if why:
        raise ProviderError(f"Can't use {name}: {why}.")
    p = provider(pid)
    if p.id == CUSTOM:
        await _resolve_ok(p.chat_url)
    body = {"model": name, "stream": True, "temperature": temperature, p.max_tokens_field: p.max_tokens, "messages": messages}
    tokens = 0
    try:
        async with _client(timeout=90.0) as c:
            async with c.stream("POST", p.chat_url, headers=_headers(p, key(pid)), json=body) as r:
                if r.status_code != 200:
                    text = (await r.aread()).decode("utf-8", "replace")
                    msg = error_message(p.title, r.status_code, text, r.headers.get("retry-after"))
                    _save_cfg(pid, {"last_error": msg})
                    raise ProviderError(msg)
                finish = None
                async for line in r.aiter_lines():
                    kind, val = parse_line(line)
                    if kind == "token":
                        tokens += 1
                        yield val
                    elif kind == "finish":
                        finish = val
                    elif kind == "error":
                        raise ProviderError(f"{p.title}: {val}")
                    elif kind == "done":
                        break
    except httpx.TimeoutException:
        raise ProviderError(f"{p.title} timed out" + (" during the answer." if tokens else ". Retry in a moment.")) from None
    except httpx.HTTPError as e:
        raise ProviderError((f"The connection to {p.title} was interrupted during the answer." if tokens else
                             f"{p.title} connection failed ({type(e).__name__}). Check your internet connection and retry.")) from None
    if not tokens:
        raise ProviderError(f"{p.title} returned an empty answer" + (" (it used the whole length limit)" if finish == "length" else "") + ". Retry, or choose another model.")
    _save_cfg(pid, {"last_ok": int(time.time()), "last_error": None})


# --------------------------------------------------------------------------- Auto router (opt-in)
def route(message: str, detection: dict[str, Any] | None) -> tuple[str | None, str | None]:
    """With Auto routing on: coding questions → the chosen coding cloud model, long/complex ones → the complex model, the rest
    stays local (None). Returns (model, why). Nothing is routed while Auto is off."""
    r = router_settings()
    if not r["auto"]:
        return None, None
    focus = (detection or {}).get("focus")
    if focus == "coding" and r["coding"]:
        target, why = r["coding"], "coding question"
    elif r["complex"] and (focus in ("research", "planning") or len(message) > 600):
        target, why = r["complex"], "complex question"
    else:
        return None, None
    pid, name = split(target)
    if problem(pid, name):
        return None, None                      # an unusable target is never used; the local model answers as usual
    return target, why
