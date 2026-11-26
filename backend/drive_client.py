"""7.2: Google Drive REST v3 for Windows Data & Sync, with the narrow `drive.file` scope (same folder rules as the
Android client, android/.../drive/Drive.kt): everything under "My Drive/Abhify.AI", sub-folders created once and
re-validated (exists, not trashed, right parent). A file is never overwritten unless the caller names its id (only
vault.json, which Abhify itself created). Nothing unencrypted is ever written into Backups/ or Sync/.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from typing import Callable

import httpx

API = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
FOLDER_MIME = "application/vnd.google-apps.folder"
ROOT = "Abhify.AI"
BACKUPS, DOCUMENTS, IMPORTS, EXPORTS, SYNC = "Backups", "Documents", "Imports", "Exports", "Sync"
FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime,trashed,parents"
SIMPLE_MAX = 5 * 1024 * 1024
CHUNK = 4 * 256 * 1024


class DriveError(Exception):
    def __init__(self, message: str, auth: bool = False, transient: bool = False):
        super().__init__(message)
        self.auth, self.transient = auth, transient


@dataclass
class Item:
    id: str
    name: str
    mime: str
    size: int | None
    md5: str | None
    modified: str | None


def is_encrypted_payload(data: bytes) -> bool:
    """An Abhify encrypted container (backup v1/v2, sync op, vault document). Plaintext is refused for Backups/Sync."""
    try:
        o = json.loads(data.decode("utf-8"))
    except Exception:
        return False
    if not isinstance(o, dict):
        return False
    f = o.get("format")
    if f == "abhify-backup-encrypted":
        return bool(o.get("data") and o.get("iv") and o.get("salt"))
    if f in ("abhify-vault-backup", "abhify-sync-ops"):
        return bool(o.get("ct") and o.get("iv"))
    if f == "abhify-vault":
        return bool((o.get("wrap") or {}).get("ct"))
    return False


class Drive:
    def __init__(self, token: Callable[[], str], transport: httpx.BaseTransport | None = None, folder_cache: dict[str, str] | None = None):
        self._token, self._transport = token, transport
        self.folders = folder_cache if folder_cache is not None else {}

    # ---- HTTP
    def _call(self, method: str, url: str, **kw) -> httpx.Response:
        headers = dict(kw.pop("headers", {}) or {})
        headers["Authorization"] = "Bearer " + self._token()
        try:
            with httpx.Client(transport=self._transport, timeout=httpx.Timeout(30.0, read=120.0)) as h:
                r = h.request(method, url, headers=headers, **kw)
        except httpx.HTTPError:
            raise DriveError("No connection to Google Drive. Check the internet and retry.", transient=True) from None
        text = r.text if r.status_code >= 400 else ""
        if r.status_code == 401:
            raise DriveError("Google Drive sign-in expired or was revoked. Connect again in Data & Sync.", auth=True)
        if r.status_code == 403:
            if "storageQuota" in text:
                raise DriveError("Your Google Drive is full. Nothing was changed.")
            if "rateLimit" in text or "userRateLimit" in text:
                raise DriveError("Google Drive is limiting requests. Retry in a minute.", transient=True)
            raise DriveError("Google Drive refused the request (permission).")
        if r.status_code == 429:
            raise DriveError("Google Drive is limiting requests. Retry in a minute.", transient=True)
        if r.status_code >= 500:
            raise DriveError(f"Google Drive is temporarily unavailable ({r.status_code}). Retry later.", transient=True)
        return r

    @staticmethod
    def _item(o: dict) -> Item:
        size = o.get("size")
        return Item(o["id"], o.get("name", ""), o.get("mimeType", ""), int(size) if size is not None else None, o.get("md5Checksum"), o.get("modifiedTime"))

    @staticmethod
    def _q(s: str) -> str:
        return s.replace("\\", "\\\\").replace("'", "\\'")

    # ---- account and folders
    def account(self) -> dict:
        r = self._call("GET", f"{API}/about", params={"fields": "user(permissionId,emailAddress,displayName)"})
        if r.status_code != 200:
            raise DriveError(f"Couldn't read the Google account ({r.status_code}).")
        u = r.json()["user"]
        return {"id": u["permissionId"], "email": u.get("emailAddress"), "name": u.get("displayName")}

    def list(self, parent: str, folders_only: bool = False) -> list[Item]:
        q = f"'{self._q(parent)}' in parents and trashed = false" + (f" and mimeType = '{FOLDER_MIME}'" if folders_only else "")
        out, page = [], None
        while True:
            params = {"q": q, "fields": f"nextPageToken,files({FIELDS})", "pageSize": 1000, "orderBy": "createdTime"}
            if page:
                params["pageToken"] = page
            r = self._call("GET", f"{API}/files", params=params)
            if r.status_code != 200:
                raise DriveError(f"Couldn't list Google Drive files ({r.status_code}).")
            o = r.json()
            out += [self._item(f) for f in o.get("files", [])]
            page = o.get("nextPageToken")
            if not page:
                return out

    def _valid(self, fid: str, parent: str | None) -> bool:
        r = self._call("GET", f"{API}/files/{fid}", params={"fields": "id,trashed,parents"})
        if r.status_code != 200:
            return False
        o = r.json()
        return not o.get("trashed") and (parent is None or parent in (o.get("parents") or [parent]))

    def _find_folder(self, name: str, parent: str | None) -> str | None:
        q = f"name = '{self._q(name)}' and mimeType = '{FOLDER_MIME}' and trashed = false and '{self._q(parent or 'root')}' in parents"
        r = self._call("GET", f"{API}/files", params={"q": q, "orderBy": "createdTime", "fields": "files(id,name)"})
        if r.status_code != 200:
            raise DriveError(f"Couldn't search Google Drive ({r.status_code}).", transient=r.status_code >= 500)
        files = r.json().get("files", [])
        return files[0]["id"] if files else None

    def find_root(self) -> str | None:
        """The Abhify.AI folder id if this client can see one (never creates)."""
        cached = self.folders.get(ROOT)
        if cached and self._valid(cached, None):
            return cached
        fid = self._find_folder(ROOT, None)
        if fid:
            self.folders[ROOT] = fid
        return fid

    def folder(self, sub: str | None) -> str:
        key = sub or ROOT
        root = None if sub is None else self.folder(None)
        cached = self.folders.get(key)
        if cached and self._valid(cached, root):
            return cached
        self.folders.pop(key, None)
        fid = self._find_folder(sub or ROOT, root)
        if not fid:
            meta = {"name": sub or ROOT, "mimeType": FOLDER_MIME, "parents": [root or "root"]}
            r = self._call("POST", f"{API}/files", params={"fields": "id"}, json=meta)
            if r.status_code not in (200, 201):
                raise DriveError(f"Couldn't create the folder \"{sub or ROOT}\" on Google Drive ({r.status_code}).")
            fid = r.json()["id"]
        self.folders[key] = fid
        return fid

    # ---- files
    def find(self, parent: str, name: str) -> Item | None:
        q = f"name = '{self._q(name)}' and '{self._q(parent)}' in parents and trashed = false"
        r = self._call("GET", f"{API}/files", params={"q": q, "orderBy": "createdTime", "fields": f"files({FIELDS})"})
        if r.status_code != 200:
            raise DriveError(f"Couldn't search Google Drive ({r.status_code}).", transient=r.status_code >= 500)
        files = r.json().get("files", [])
        return self._item(files[0]) if files else None

    def _guard(self, sub: str, data: bytes) -> None:
        if sub in (BACKUPS, SYNC) and not is_encrypted_payload(data):
            raise DriveError("Refused: only encrypted files may be written to the Backups and Sync folders.")

    def create(self, sub: str, name: str, data: bytes, mime: str = "application/json") -> Item:
        """A new file in Abhify.AI/<sub>. Never replaces an existing file (callers check with find())."""
        self._guard(sub, data)
        parent = self.folder(sub)
        meta = {"name": name, "parents": [parent], "mimeType": mime}
        if len(data) <= SIMPLE_MAX:
            boundary = "abhify-" + uuid.uuid4().hex
            body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{json.dumps(meta)}\r\n"
                    f"--{boundary}\r\nContent-Type: {mime}\r\n\r\n").encode("utf-8") + data + f"\r\n--{boundary}--".encode()
            r = self._call("POST", UPLOAD, params={"uploadType": "multipart", "fields": FIELDS}, content=body,
                           headers={"Content-Type": f"multipart/related; boundary={boundary}"})
            if r.status_code not in (200, 201):
                raise DriveError(f"Google Drive didn't accept the upload ({r.status_code}).", transient=r.status_code in (404, 410))
            return self._item(r.json())
        return self._resumable("POST", UPLOAD, meta, data, mime)

    def replace(self, sub: str, file_id: str, data: bytes, mime: str = "application/json") -> Item:
        """New content for a file Abhify created (used only for Sync/vault.json on key rotation)."""
        self._guard(sub, data)
        r = self._call("PATCH", f"{UPLOAD}/{file_id}", params={"uploadType": "media", "fields": FIELDS}, content=data, headers={"Content-Type": mime})
        if r.status_code != 200:
            raise DriveError(f"Google Drive didn't accept the update ({r.status_code}).")
        return self._item(r.json())

    def _resumable(self, method: str, url: str, meta: dict, data: bytes, mime: str) -> Item:
        r = self._call(method, url, params={"uploadType": "resumable", "fields": FIELDS}, json=meta,
                       headers={"X-Upload-Content-Type": mime, "X-Upload-Content-Length": str(len(data))})
        loc = r.headers.get("Location")
        if r.status_code not in (200, 201) or not loc:
            raise DriveError(f"Google Drive didn't start the upload ({r.status_code}).")
        sent, attempts = 0, 0
        while True:
            end = min(sent + CHUNK, len(data))
            try:
                r = self._call("PUT", loc, content=data[sent:end], headers={"Content-Range": f"bytes {sent}-{end - 1}/{len(data)}"})
            except DriveError as e:
                attempts += 1
                if e.auth or attempts > 4:
                    raise
                st = self._call("PUT", loc, content=b"", headers={"Content-Range": f"bytes */{len(data)}"})
                if st.status_code in (200, 201):
                    return self._item(st.json())
                rng = st.headers.get("Range")
                sent = int(rng.split("-")[1]) + 1 if rng else 0
                continue
            if r.status_code in (200, 201):
                return self._item(r.json())
            if r.status_code == 308:
                rng = r.headers.get("Range")
                sent, attempts = (int(rng.split("-")[1]) + 1 if rng else end), 0
                continue
            raise DriveError(f"The upload failed ({r.status_code}).", transient=r.status_code in (404, 410))

    def download(self, file_id: str) -> bytes:
        r = self._call("GET", f"{API}/files/{file_id}", params={"alt": "media"})
        if r.status_code == 404:
            raise DriveError("That file is no longer on Google Drive.")
        if r.status_code != 200:
            raise DriveError(f"Download failed ({r.status_code}).")
        return r.content

    def trash(self, file_id: str) -> None:
        r = self._call("PATCH", f"{API}/files/{file_id}", json={"trashed": True})
        if r.status_code not in (200, 404):
            raise DriveError(f"Couldn't move the file to Drive's trash ({r.status_code}).")


class SyncFolder:
    """sync_core.Remote over Abhify.AI/Sync (immutable op files; names unique per device)."""

    def __init__(self, drive: Drive):
        self.drive = drive
        self._ids: dict[str, str] = {}

    def list_names(self) -> list[str]:
        items = self.drive.list(self.drive.folder(SYNC))
        self._ids = {}
        for it in items:
            self._ids.setdefault(it.name, it.id)        # oldest first: a duplicate name never shadows the original
        return sorted(self._ids)

    def get(self, name: str) -> bytes:
        if name not in self._ids:
            self.list_names()
        if name not in self._ids:
            raise DriveError("A sync file disappeared from Google Drive.")
        return self.drive.download(self._ids[name])

    def put_new(self, name: str, data: bytes) -> None:
        import sync_core
        existing = self.drive.find(self.drive.folder(SYNC), name)
        if existing:
            self._ids[name] = existing.id
            raise sync_core.Exists(name)
        it = self.drive.create(SYNC, name, data)
        self._ids[name] = it.id
