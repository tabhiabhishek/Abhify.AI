"""7.2: Google sign-in for Data & Sync on Windows: OAuth 2.0 for installed apps, loopback redirect + PKCE, in the
system browser. Only the `drive.file` scope is requested (Abhify sees only files it created). No password is ever
typed into Abhify.

- The OAuth client is the owner's "Desktop app" client from the existing Google Cloud project, imported once as the
  JSON file Google provides. Google documents installed-app client secrets as not confidential; it is still kept in
  the DPAPI store, never in config, logs or backups.
- The redirect goes to a one-time listener on 127.0.0.1 (random port, this computer only, closed after one request or
  10 minutes). The `state` value is checked; the code is exchanged with the PKCE verifier.
- The refresh token is DPAPI-encrypted (secure_store); access tokens live in memory only. Nothing is logged.
"""
from __future__ import annotations

import base64
import hashlib
import html
import http.server
import json
import secrets
import threading
import time
import urllib.parse
import webbrowser
from typing import Any, Callable

import httpx

import secure_store

SCOPE = "https://www.googleapis.com/auth/drive.file"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
REVOKE_URL = "https://oauth2.googleapis.com/revoke"
FLOW_SECONDS = 600

_transport: httpx.BaseTransport | None = None      # tests inject httpx.MockTransport
_lock = threading.Lock()
_access: dict[str, Any] = {}                          # {"token", "exp"} in memory only
_flow: dict[str, Any] = {}


class AuthError(Exception):
    """User-facing. `auth=True`: the user must connect (again)."""

    def __init__(self, message: str, auth: bool = False):
        super().__init__(message)
        self.auth = auth


def _client() -> httpx.Client:
    return httpx.Client(transport=_transport, timeout=30.0)


# --------------------------------------------------------------------------- OAuth client file
def import_client(text: str) -> dict:
    """Accepts Google's downloaded client JSON for a *Desktop app* client. Returns the public client id."""
    try:
        doc = json.loads(text)
    except ValueError:
        raise AuthError("That isn't a Google OAuth client file (JSON).") from None
    inst = doc.get("installed") if isinstance(doc, dict) else None
    if not isinstance(inst, dict):
        kind = next(iter(doc), "unknown") if isinstance(doc, dict) and doc else "unknown"
        raise AuthError(f"This is a \"{kind}\" client. Windows needs a \"Desktop app\" OAuth client (see docs/GOOGLE_CLOUD_OAUTH_SETUP.md).")
    cid, secret = str(inst.get("client_id", "")), str(inst.get("client_secret", ""))
    if not cid.endswith(".apps.googleusercontent.com") or not secret:
        raise AuthError("The client file is missing its client id or secret.")
    secure_store.put("oauth_client", {"client_id": cid, "client_secret": secret, "project_id": str(inst.get("project_id", ""))})
    return {"client_id": cid, "project_id": inst.get("project_id", "")}


def client_info() -> dict | None:
    c = secure_store.get("oauth_client")
    return {"client_id": c["client_id"], "project_id": c.get("project_id", "")} if c else None


# --------------------------------------------------------------------------- sign-in flow
def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


class _Callback(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):                        # never log the request line (it carries the code)
        pass

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(url.query)
        if url.path != "/callback":
            self.send_response(404); self.end_headers(); return
        ok, msg = _finish(q.get("state", [""])[0], q.get("code", [""])[0], q.get("error", [""])[0])
        body = ("<!doctype html><meta charset=utf-8><title>Abhify.AI</title><body style='font-family:system-ui;padding:2rem'>"
                f"<h2>{'✓ Google Drive connected' if ok else 'Google Drive was not connected'}</h2><p>{html.escape(msg)}</p>"
                "<p>You can close this tab and return to Abhify.AI.</p>").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)
        threading.Thread(target=self.server.shutdown, daemon=True).start()


def start(open_browser: Callable[[str], Any] | None = None) -> dict:
    """Begins sign-in: opens Google's consent page in the browser. Returns {"auth_url"} (also shown as a link)."""
    c = secure_store.get("oauth_client")
    if not c:
        raise AuthError("Google Drive isn't set up on this computer yet: import the Desktop OAuth client file first.")
    with _lock:
        old = _flow.get("server")
        if old:
            threading.Thread(target=old.shutdown, daemon=True).start()
        srv = http.server.HTTPServer(("127.0.0.1", 0), _Callback)
        port = srv.server_address[1]
        verifier = secrets.token_urlsafe(64)
        state = secrets.token_urlsafe(24)
        redirect = f"http://127.0.0.1:{port}/callback"
        _flow.clear()
        _flow.update(server=srv, state=state, verifier=verifier, redirect=redirect, started=time.time(), status="waiting", message="")
    params = {"client_id": c["client_id"], "redirect_uri": redirect, "response_type": "code", "scope": SCOPE,
              "code_challenge": _b64url(hashlib.sha256(verifier.encode()).digest()), "code_challenge_method": "S256",
              "state": state, "access_type": "offline", "prompt": "consent", "include_granted_scopes": "false"}
    url = AUTH_URL + "?" + urllib.parse.urlencode(params)
    threading.Thread(target=_serve, args=(srv,), daemon=True).start()
    try:
        (open_browser or webbrowser.open)(url)          # looked up at call time, so tests can block it
    except Exception:
        pass
    return {"auth_url": url}


def _serve(srv: http.server.HTTPServer) -> None:
    timer = threading.Timer(FLOW_SECONDS, srv.shutdown)
    timer.daemon = True
    timer.start()
    try:
        srv.serve_forever(poll_interval=0.5)
    finally:
        timer.cancel()
        srv.server_close()
        with _lock:
            if _flow.get("server") is srv and _flow.get("status") == "waiting":
                _flow.update(status="expired", message="Sign-in timed out. Press Connect to try again.")


def _finish(state: str, code: str, error: str) -> tuple[bool, str]:
    with _lock:
        f = dict(_flow)
    if not f or not secrets.compare_digest(state or "", f.get("state", "")) or time.time() - f.get("started", 0) > FLOW_SECONDS:
        return False, "This sign-in link is no longer valid. Press Connect in Abhify.AI again."
    if error or not code:
        msg = "Access wasn't granted. Nothing changed." if error == "access_denied" else f"Google returned an error ({error or 'no code'})."
        _set_flow("error", msg)
        return False, msg
    try:
        exchange(code, f["verifier"], f["redirect"])
    except AuthError as e:
        _set_flow("error", str(e))
        return False, str(e)
    _set_flow("connected", "Connected.")
    return True, "Abhify.AI can now use its own folder in your Google Drive."


def _set_flow(status: str, message: str) -> None:
    with _lock:
        _flow.update(status=status, message=message, state="")   # one use only


def flow_status() -> dict:
    with _lock:
        return {"status": _flow.get("status", "idle"), "message": _flow.get("message", "")}


def exchange(code: str, verifier: str, redirect: str) -> None:
    c = secure_store.get("oauth_client")
    if not c:
        raise AuthError("The OAuth client is missing. Import it again.")
    try:
        with _client() as h:
            r = h.post(TOKEN_URL, data={"code": code, "client_id": c["client_id"], "client_secret": c["client_secret"],
                                        "redirect_uri": redirect, "grant_type": "authorization_code", "code_verifier": verifier})
    except httpx.HTTPError:
        raise AuthError("No connection to Google. Check the internet and try again.") from None
    if r.status_code != 200:
        raise AuthError("Google didn't accept the sign-in (" + _err(r) + ").")
    tok = r.json()
    granted = set(str(tok.get("scope", "")).split())
    if SCOPE not in granted:
        raise AuthError("Google Drive access wasn't granted (the Drive checkbox was left off). Press Connect and allow it.")
    if not tok.get("refresh_token"):
        raise AuthError("Google didn't return a long-term sign-in. Remove Abhify.AI at myaccount.google.com/permissions, then connect again.")
    secure_store.put("refresh_token", tok["refresh_token"])
    with _lock:
        _access.update(token=tok["access_token"], exp=time.time() + int(tok.get("expires_in", 3600)))


def _err(r: httpx.Response) -> str:
    try:
        return str(r.json().get("error", r.status_code))
    except ValueError:
        return str(r.status_code)


def connected() -> bool:
    return secure_store.has("refresh_token")


def access_token() -> str:
    """A valid access token (refreshed silently). AuthError(auth=True) when the user must connect again."""
    with _lock:
        if _access.get("token") and _access.get("exp", 0) - 60 > time.time():
            return _access["token"]
    rt, c = secure_store.get("refresh_token"), secure_store.get("oauth_client")
    if not rt or not c:
        raise AuthError("Google Drive isn't connected on this computer.", auth=True)
    try:
        with _client() as h:
            r = h.post(TOKEN_URL, data={"client_id": c["client_id"], "client_secret": c["client_secret"], "refresh_token": rt, "grant_type": "refresh_token"})
    except httpx.HTTPError:
        raise AuthError("No connection to Google. Check the internet and try again.") from None
    if r.status_code in (400, 401) and _err(r) in ("invalid_grant", "unauthorized_client", "invalid_client"):
        secure_store.delete("refresh_token")
        with _lock:
            _access.clear()
        raise AuthError("Google Drive sign-in expired or was revoked (in Testing mode Google ends it after 7 days). Connect again.", auth=True)
    if r.status_code != 200:
        raise AuthError("Google sign-in is temporarily unavailable (" + _err(r) + "). Try again later.")
    tok = r.json()
    with _lock:
        _access.update(token=tok["access_token"], exp=time.time() + int(tok.get("expires_in", 3600)))
        return _access["token"]


def forget_access() -> None:
    with _lock:
        _access.clear()


def disconnect() -> bool:
    """Revokes the grant at Google (best effort) and forgets the tokens here. Files are not touched."""
    rt = secure_store.get("refresh_token") if secure_store.has("refresh_token") else None
    revoked = False
    if rt:
        try:
            with _client() as h:
                revoked = h.post(REVOKE_URL, data={"token": rt}).status_code == 200
        except httpx.HTTPError:
            revoked = False
    secure_store.delete("refresh_token")
    forget_access()
    return revoked
