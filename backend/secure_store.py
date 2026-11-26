"""7.2: this computer's sync secrets (Google refresh token, vault keys, OAuth client file), encrypted with Windows DPAPI
for the current Windows user. Stored in <data root>/secrets/datasync.json, which backups never include. Values are
never logged or returned by any API. Tests replace `_protect`/`_unprotect` (DPAPI needs a real Windows session)."""
from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

import local_file_service as lfs

_ENTROPY = b"abhify-datasync-v1"


class SecureStoreError(Exception):
    pass


def _dpapi(data: bytes, protect: bool) -> bytes:
    if sys.platform != "win32":
        raise SecureStoreError("Data & Sync secrets need Windows (they are encrypted with Windows DPAPI).")
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def blob(b: bytes):
        buf = ctypes.create_string_buffer(b, len(b))
        return Blob(len(b), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char))), buf
    crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
    src, _k1 = blob(data)
    ent, _k2 = blob(_ENTROPY)
    out = Blob()
    ok = (crypt32.CryptProtectData(ctypes.byref(src), "Abhify.AI sync secret", ctypes.byref(ent), None, None, 0x01, ctypes.byref(out))
          if protect else crypt32.CryptUnprotectData(ctypes.byref(src), None, ctypes.byref(ent), None, None, 0x01, ctypes.byref(out)))
    if not ok:
        raise SecureStoreError("Windows couldn't " + ("protect" if protect else "read") + " a Data & Sync secret.")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def _protect(b: bytes) -> bytes:
    return _dpapi(b, True)


def _unprotect(b: bytes) -> bytes:
    return _dpapi(b, False)


def _path() -> Path:
    return lfs.root() / "secrets" / "datasync.json"


def _read() -> dict[str, str]:
    try:
        return json.loads(_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _write(d: dict[str, str]) -> None:
    p = _path()
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=".datasync-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(d, f)
    os.replace(tmp, p)


def put(name: str, value: Any) -> None:
    d = _read()
    d[name] = base64.b64encode(_protect(json.dumps(value).encode("utf-8"))).decode("ascii")
    _write(d)


def get(name: str, default: Any = None) -> Any:
    raw = _read().get(name)
    if raw is None:
        return default
    try:
        return json.loads(_unprotect(base64.b64decode(raw)).decode("utf-8"))
    except Exception:
        raise SecureStoreError("A saved Data & Sync secret can't be read on this Windows account. Connect again.") from None


def delete(*names: str) -> None:
    d = _read()
    for n in names:
        d.pop(n, None)
    _write(d)


def has(name: str) -> bool:
    return name in _read()
