"""A small in-memory stand-in for Google's OAuth token endpoint and the Drive v3 subset Abhify uses, as an httpx
transport. It models the drive.file rule per OAuth client: a client sees only files created by its own client id
(or, with `share_between_clients=True`, by any client of the same project), so tests can express both outcomes of
requirement A. It is a test double, not a claim about Google's real behaviour."""
from __future__ import annotations

import itertools
import json
import re
import urllib.parse

import httpx

FOLDER = "application/vnd.google-apps.folder"


class FakeGoogle:
    def __init__(self, share_between_clients: bool = True):
        self.files: dict[str, dict] = {"root": {"id": "root", "name": "My Drive", "mimeType": FOLDER, "parents": [], "trashed": False, "owner_client": None}}
        self.tokens: dict[str, str] = {}               # access token -> client id
        self.refresh: dict[str, str] = {}              # refresh token -> client id
        self.share = share_between_clients
        self.ids = itertools.count(1)
        self.fail_next: list[int] = []                 # status codes to return for the next Drive calls
        self.revoked: list[str] = []
        self.requests: list[tuple[str, str]] = []
        self.quota_full = False
        self.granted_scope = "https://www.googleapis.com/auth/drive.file"

    # ---- helpers for tests
    def token_for(self, client: str) -> str:
        t = f"at-{client}-{next(self.ids)}"
        self.tokens[t] = client
        return t

    def names(self, parent_name: str | None = None) -> list[str]:
        return sorted(f["name"] for f in self.files.values() if not f["trashed"] and f["id"] != "root")

    def body_of(self, name: str) -> bytes:
        return next(f["body"] for f in self.files.values() if f["name"] == name and not f["trashed"])

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    # ---- request handling
    def _visible(self, f: dict, client: str) -> bool:
        if f["id"] == "root":
            return True
        return f["owner_client"] == client or (self.share and f["owner_client"] is not None)

    def handle(self, req: httpx.Request) -> httpx.Response:
        url = req.url
        self.requests.append((req.method, str(url)))
        if url.host == "oauth2.googleapis.com":
            return self._oauth(req)
        auth = req.headers.get("Authorization", "")
        client = self.tokens.get(auth.removeprefix("Bearer "))
        if not client:
            return httpx.Response(401, json={"error": {"code": 401}})
        if self.fail_next:
            code = self.fail_next.pop(0)
            return httpx.Response(code, text='{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}' if code == 403 else "{}")
        path = url.path
        q = dict(urllib.parse.parse_qsl(url.query.decode()))
        if path == "/drive/v3/about":
            return httpx.Response(200, json={"user": {"permissionId": "perm-1", "emailAddress": "tester@example.com", "displayName": "Tester"}})
        if path == "/drive/v3/files" and req.method == "GET":
            return self._list(q, client)
        if path == "/drive/v3/files" and req.method == "POST":
            meta = json.loads(req.content)
            return httpx.Response(200, json=self._new(meta, b"", client))
        m = re.fullmatch(r"/drive/v3/files/([^/]+)", path)
        if m:
            f = self.files.get(m.group(1))
            if not f or not self._visible(f, client):
                return httpx.Response(404, json={})
            if req.method == "GET" and q.get("alt") == "media":
                return httpx.Response(200, content=f["body"])
            if req.method == "GET":
                return httpx.Response(200, json=self._out(f))
            if req.method == "PATCH":
                f.update(json.loads(req.content or b"{}"))
                return httpx.Response(200, json=self._out(f))
        if path == "/upload/drive/v3/files" and q.get("uploadType") == "multipart":
            if self.quota_full:
                return httpx.Response(403, text='{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}')
            ctype = req.headers["Content-Type"]
            boundary = ctype.split("boundary=")[1]
            parts = req.content.split(b"--" + boundary.encode())
            meta = json.loads(parts[1].split(b"\r\n\r\n", 1)[1].rstrip(b"\r\n"))
            body = parts[2].split(b"\r\n\r\n", 1)[1][:-2]
            return httpx.Response(200, json=self._new(meta, body, client))
        m = re.fullmatch(r"/upload/drive/v3/files/([^/]+)", path)
        if m and req.method == "PATCH" and q.get("uploadType") == "media":
            f = self.files.get(m.group(1))
            if not f or not self._visible(f, client):
                return httpx.Response(404, json={})
            f["body"] = req.content
            return httpx.Response(200, json=self._out(f))
        return httpx.Response(400, json={"error": f"unhandled {req.method} {path}"})

    def _oauth(self, req: httpx.Request) -> httpx.Response:
        form = dict(urllib.parse.parse_qsl(req.content.decode()))
        if req.url.path == "/revoke":
            self.revoked.append(form.get("token", ""))
            return httpx.Response(200, json={})
        if form.get("grant_type") == "authorization_code":
            if form.get("code") != "good-code" or not form.get("code_verifier"):
                return httpx.Response(400, json={"error": "invalid_grant"})
            rt = f"rt-{next(self.ids)}"
            self.refresh[rt] = form["client_id"]
            return httpx.Response(200, json={"access_token": self.token_for(form["client_id"]), "expires_in": 3600,
                                             "refresh_token": rt, "scope": self.granted_scope, "token_type": "Bearer"})
        if form.get("grant_type") == "refresh_token":
            client = self.refresh.get(form.get("refresh_token", ""))
            if not client:
                return httpx.Response(400, json={"error": "invalid_grant"})
            return httpx.Response(200, json={"access_token": self.token_for(client), "expires_in": 3600, "scope": self.granted_scope})
        return httpx.Response(400, json={"error": "unsupported_grant_type"})

    def _new(self, meta: dict, body: bytes, client: str) -> dict:
        fid = f"f{next(self.ids)}"
        self.files[fid] = {"id": fid, "name": meta["name"], "mimeType": meta.get("mimeType", "application/octet-stream"),
                           "parents": meta.get("parents", ["root"]), "trashed": False, "body": body, "owner_client": client}
        return self._out(self.files[fid])

    def _out(self, f: dict) -> dict:
        o = {k: f[k] for k in ("id", "name", "mimeType", "parents", "trashed")}
        if f["mimeType"] != FOLDER:
            o["size"] = str(len(f.get("body", b"")))
        o["modifiedTime"] = "2026-11-23T00:00:00Z"
        return o

    def _list(self, q: dict, client: str) -> httpx.Response:
        query = q.get("q", "")
        files = [f for f in self.files.values() if f["id"] != "root" and self._visible(f, client)]
        m = re.search(r"'([^']+)' in parents", query)
        if m:
            files = [f for f in files if m.group(1) in f["parents"]]
        m = re.search(r"name = '((?:[^'\\]|\\.)*)'", query)
        if m:
            files = [f for f in files if f["name"] == m.group(1).replace("\\'", "'")]
        if "trashed = false" in query:
            files = [f for f in files if not f["trashed"]]
        if f"mimeType = '{FOLDER}'" in query:
            files = [f for f in files if f["mimeType"] == FOLDER]
        files.sort(key=lambda f: int(f["id"][1:]))
        return httpx.Response(200, json={"files": [self._out(f) for f in files]})
