"""7.2: Data & Sync on Windows: Google Drive connection, encrypted vault, manual sync, encrypted portable backups.

Rules (owner-approved, docs/ABHIFY_DRIVE_SYNC_BACKUP_DESIGN.md):
- nothing runs automatically: every network action is one of these functions, called from a button;
- everything that leaves this computer is encrypted here first (vault_crypto); keys and the Recovery Key are never
  uploaded, logged or returned after the one-time display at vault creation;
- imports are previewed, additive, transactional; replaced or deleted records go to Recently deleted;
- Google Drive only stores files; this computer keeps its own models, database and settings.
"""
from __future__ import annotations

import os
import re
import secrets
import socket
import sqlite3
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

import drive_client as dc
import google_oauth
import local_file_service as lfs
import secure_store
import sync_core as sc
import vault_crypto as vc
from datasync_local import WinLocal
from version import __version__

PLATFORM = "windows"
VAULT_NAME = "vault.json"
CATEGORY_KINDS = {"Conversations": ["conversation", "message"], "Personal memory": ["memory"], "Portable preferences": ["pref"]}
_lock = threading.RLock()
_pending_vault: dict[str, Any] = {}
_staged_restores: dict[str, dict] = {}
_transport = None          # tests inject an httpx transport for Drive


class DataSyncError(Exception):
    def __init__(self, message: str, code: str = "error"):
        super().__init__(message)
        self.code = code


# --------------------------------------------------------------------------- helpers
def _local() -> WinLocal:
    return WinLocal()


def _meta(key: str, default: Any = None) -> Any:
    loc = _local()
    try:
        return loc.get_meta(key, default)
    finally:
        loc.close()


def _set_meta(key: str, value: Any) -> None:
    loc = _local()
    try:
        loc.put_meta(key, value)
    finally:
        loc.close()


def device_id() -> str:
    d = _meta("device_id")
    if not d:
        d = secrets.token_hex(8)
        _set_meta("device_id", d)
    return d


def device_name() -> str:
    return (_meta("device_name") or f"Windows · {socket.gethostname()}")[:80]


def _drive() -> dc.Drive:
    acct = _meta("account") or {}
    cache = dict((_meta("drive_folders") or {}).get(acct.get("id", ""), {}))
    return dc.Drive(google_oauth.access_token, _transport, cache)


def _save_folders(d: dc.Drive) -> None:
    acct = _meta("account") or {}
    if acct.get("id"):
        allf = dict(_meta("drive_folders") or {})
        allf[acct["id"]] = d.folders
        _set_meta("drive_folders", allf)


def _wrap_errors(fn):
    def inner(*a, **kw):
        try:
            return fn(*a, **kw)
        except google_oauth.AuthError as e:
            raise DataSyncError(str(e), "auth" if e.auth else "error") from None
        except dc.DriveError as e:
            if e.auth:
                google_oauth.forget_access()
            raise DataSyncError(str(e), "auth" if e.auth else ("retry" if e.transient else "error")) from None
        except (vc.VaultError, sc.SyncError, secure_store.SecureStoreError) as e:
            raise DataSyncError(str(e), type(e).__name__) from None
    inner.__name__ = fn.__name__
    inner.__doc__ = fn.__doc__
    return inner


def _keys() -> dict[int, bytes]:
    raw = secure_store.get("vault_keys") or {}
    return {int(k): bytes.fromhex(v) for k, v in raw.items()}


def _store_keys(keys: dict[int, bytes]) -> None:
    secure_store.put("vault_keys", {str(k): v.hex() for k, v in keys.items()})


def _engine(loc: WinLocal, remote) -> sc.Engine:
    return sc.Engine(loc, remote, device_id(), PLATFORM, device_name())


# --------------------------------------------------------------------------- status and connection
def status() -> dict:
    vault = _meta("vault")
    loc = _local()
    try:
        hist = loc.get_meta("history") or []
        out = {
            "platform": PLATFORM, "app_version": __version__,
            "oauth_client": google_oauth.client_info(), "connected": google_oauth.connected(), "flow": google_oauth.flow_status(),
            "account": _meta("account"), "vault": vault, "has_keys": secure_store.has("vault_keys"),
            "kinds": [k for k in (loc.get_meta("kinds") or [])], "categories": CATEGORY_KINDS,
            "device": {"id": device_id(), "name": device_name()},
            "last": hist[0] if hist else None, "outbox": len(loc.get_meta("outbox") or []),
            "conflicts": len(loc.get_meta("conflicts") or []), "devices": loc.get_meta("devices") or {},
            "automatic": False,
        }
    finally:
        loc.close()
    return out


@_wrap_errors
def import_client(text: str) -> dict:
    return google_oauth.import_client(text)


@_wrap_errors
def connect() -> dict:
    return google_oauth.start()


@_wrap_errors
def finish_connect() -> dict:
    """After the browser said "connected": read which Google account this is (bookkeeping per account)."""
    d = _drive()
    acct = d.account()
    old = _meta("account") or {}
    if old.get("id") and old["id"] != acct["id"] and _meta("vault"):
        raise DataSyncError("This is a different Google account than the one this computer's sync uses. "
                            "Connect the original account, or disconnect and set up sync again.", "account")
    _set_meta("account", acct)
    return acct


@_wrap_errors
def disconnect(remove_remote: bool = False, confirm: str = "") -> dict:
    """Revokes Google access here. Optionally moves Abhify's Sync files on Drive to Drive's trash (typed confirmation)."""
    trashed = 0
    if remove_remote:
        if confirm != "DELETE SYNC DATA":
            raise DataSyncError('Type DELETE SYNC DATA to move the sync files on Google Drive to the trash.', "confirm")
        d = _drive()
        for it in d.list(d.folder(dc.SYNC)):
            d.trash(it.id)
            trashed += 1
    revoked = google_oauth.disconnect()
    return {"revoked": revoked, "trashed": trashed}


# --------------------------------------------------------------------------- requirement A: can this client see the other apps' files?
@_wrap_errors
def interop_check() -> dict:
    """Checks whether this Windows OAuth client can see files the Android app created (drive.file is per-app), then
    writes a harmless probe file into Abhify.AI/Imports so the phone's Drive screen can show whether it sees ours."""
    d = _drive()
    acct = d.account()
    root = d.find_root()
    seen = {}
    if root:
        for it in d.list(root):
            if it.mime == dc.FOLDER_MIME:
                seen[it.name] = [c.name for c in d.list(it.id)][:50]
    name = f"abhify-interop-check-windows-{datetime.now():%Y%m%d-%H%M%S}.txt"
    probe = d.create(dc.IMPORTS, name, f"Abhify.AI Windows Drive access check, {datetime.now().isoformat(timespec='seconds')}. No personal data.\n".encode(), "text/plain")
    _save_folders(d)
    return {"account": acct.get("email"), "root_visible_before": bool(root), "folders_seen": seen, "probe": probe.name,
            "next": "On the phone: More → Google Drive → Imports. If this file is listed, Android can read files Windows creates."}


# --------------------------------------------------------------------------- vault
@_wrap_errors
def vault_probe() -> dict:
    d = _drive()
    it = d.find(d.folder(dc.SYNC), VAULT_NAME)
    _save_folders(d)
    if not it:
        return {"exists": False}
    doc = vc.check_vault_doc(_json(d.download(it.id)))
    return {"exists": True, "vault_id": doc["vault_id"], "epoch": doc["epoch"], "created": doc["created"],
            "joined": (_meta("vault") or {}).get("vault_id") == doc["vault_id"] and secure_store.has("vault_keys")}


def _json(b: bytes) -> Any:
    import json
    try:
        return json.loads(b.decode("utf-8"))
    except Exception:
        raise DataSyncError("The vault file on Google Drive is damaged.") from None


@_wrap_errors
def vault_create() -> dict:
    """Step 1: makes a Recovery Key and a vault in memory. Nothing is stored or uploaded until vault_confirm()."""
    d = _drive()
    if d.find(d.folder(dc.SYNC), VAULT_NAME):
        raise DataSyncError("A sync vault already exists in this Google Drive. Join it with your Recovery Key instead.", "exists")
    rk = vc.new_recovery_key()
    doc, keys = vc.create_vault(rk, int(time.time() * 1000))
    with _lock:
        _pending_vault.clear()
        _pending_vault.update(doc=doc, keys=keys, rk_last=rk[-4:], at=time.time())
    return {"recovery_key": vc.display_recovery_key(rk), "qr": vc.qr_payload(rk), "confirm_hint": "Type the last group of 4 characters to confirm you saved it."}


@_wrap_errors
def vault_confirm(last_group: str) -> dict:
    with _lock:
        p = dict(_pending_vault)
    if not p or time.time() - p["at"] > 900:
        raise DataSyncError("Vault setup expired. Start again.", "expired")
    if (last_group or "").strip().upper().replace("O", "0").replace("I", "1").replace("L", "1") != p["rk_last"]:
        raise DataSyncError("That doesn't match the last group of your Recovery Key.", "mismatch")
    d = _drive()
    if d.find(d.folder(dc.SYNC), VAULT_NAME):
        raise DataSyncError("A sync vault was created meanwhile on another device. Join it with its Recovery Key.", "exists")
    d.create(dc.SYNC, VAULT_NAME, _dump(p["doc"]))
    _save_folders(d)
    _store_keys(p["keys"])
    _set_meta("vault", {"vault_id": p["doc"]["vault_id"], "epoch": p["doc"]["epoch"], "created": p["doc"]["created"]})
    with _lock:
        _pending_vault.clear()
    return {"vault_id": p["doc"]["vault_id"]}


def _dump(o: Any) -> bytes:
    import json
    return json.dumps(o, separators=(",", ":")).encode("utf-8")


@_wrap_errors
def vault_join(recovery_key: str) -> dict:
    """Join (or re-enter after a key rotation): unlocks Drive's vault.json with the Recovery Key; keys stay here only."""
    rk = vc.normalize_recovery_key(recovery_key)            # typo check before any network call
    d = _drive()
    it = d.find(d.folder(dc.SYNC), VAULT_NAME)
    if not it:
        raise DataSyncError("There's no sync vault in this Google Drive yet. Create one first.", "missing")
    doc = vc.check_vault_doc(_json(d.download(it.id)))
    keys = vc.unlock_vault(doc, rk)
    old = _meta("vault") or {}
    if old.get("vault_id") and old["vault_id"] != doc["vault_id"]:
        _reset_sync_state()                                  # a different vault: start its bookkeeping fresh (records untouched)
    _store_keys(keys)
    _set_meta("vault", {"vault_id": doc["vault_id"], "epoch": doc["epoch"], "created": doc["created"]})
    _save_folders(d)
    return {"vault_id": doc["vault_id"], "epoch": doc["epoch"]}


def _current_vault(d: dc.Drive) -> tuple[dict, dict[int, bytes]]:
    v = _meta("vault")
    if not v or not secure_store.has("vault_keys"):
        raise DataSyncError("Set up or join a sync vault first.", "no_vault")
    it = d.find(d.folder(dc.SYNC), VAULT_NAME)
    if not it:
        raise DataSyncError("The sync vault is missing from Google Drive (deleted or moved?). Nothing was changed.", "missing")
    doc = vc.check_vault_doc(_json(d.download(it.id)))
    if doc["vault_id"] != v["vault_id"]:
        raise DataSyncError("The sync vault in Google Drive is a different one. Join it with its Recovery Key.", "rekey")
    keys = _keys()
    if doc["epoch"] not in keys:
        raise DataSyncError("A device was removed from sync, so the vault key changed. Enter your Recovery Key to continue.", "rekey")
    return doc, keys


def _reset_sync_state() -> None:
    loc = _local()
    try:
        with loc.transaction():
            for k in ("states", "seen", "staged", "outbox", "seq", "held", "extra", "devices", "device_info"):
                loc.conn.execute("DELETE FROM sync_meta WHERE key = ?", (k,))
    finally:
        loc.close()


# --------------------------------------------------------------------------- sync
@_wrap_errors
def set_kinds(kinds: list[str]) -> list[str]:
    allowed = [k for k in kinds if k in sc.SELECTABLE]
    if "message" in allowed and "conversation" not in allowed:
        allowed.append("conversation")
    if "conversation" in allowed and "message" not in allowed:
        allowed.append("message")
    _set_meta("kinds", sorted(set(allowed)))
    return sorted(set(allowed))


def _run(fn):
    with _lock:
        d = _drive()
        doc, keys = _current_vault(d)
        loc = _local()
        try:
            return fn(_engine(loc, dc.SyncFolder(d)), doc, keys)
        finally:
            loc.close()
            _save_folders(d)


@_wrap_errors
def preview() -> dict:
    return _run(lambda e, doc, keys: e.preview(keys, doc["vault_id"]))


@_wrap_errors
def apply(direction: str = "both", approve_deletes: list[str] | None = None, keep_elsewhere: list[str] | None = None) -> dict:
    return _run(lambda e, doc, keys: e.apply(keys, doc["vault_id"], doc["epoch"], direction, approve_deletes or [], keep_elsewhere or []))


def _offline_engine() -> tuple[sc.Engine, WinLocal]:
    loc = _local()
    return _engine(loc, None), loc


def _with_offline(fn):
    with _lock:
        e, loc = _offline_engine()
        try:
            return fn(e)
        finally:
            loc.close()


@_wrap_errors
def conflicts() -> list[dict]:
    return _with_offline(lambda e: e.conflicts())


@_wrap_errors
def resolve(conflict_id: str, choice: str) -> dict:
    _with_offline(lambda e: e.resolve(conflict_id, choice))
    return {"ok": True, "note": "Press Sync Now to send your choice to your other devices."}


@_wrap_errors
def recently_deleted() -> list[dict]:
    return _with_offline(lambda e: e.recently_deleted())


@_wrap_errors
def restore_deleted(trash_id: str) -> dict:
    _with_offline(lambda e: e.restore_deleted(trash_id))
    return {"ok": True}


def history() -> list[dict]:
    return _meta("history") or []


def devices() -> dict:
    return {"this": {"id": device_id(), "name": device_name(), "platform": PLATFORM}, "others": _meta("devices") or {}}


@_wrap_errors
def revoke(device: str, recovery_key: str) -> dict:
    """Removes another device: its later files are ignored everywhere, and the vault key is rotated (needs the Recovery Key)."""
    if not re.fullmatch(r"[0-9a-f]{16}", device or ""):
        raise DataSyncError("Unknown device.")
    rk = vc.normalize_recovery_key(recovery_key)
    with _lock:
        d = _drive()
        doc, _ = _current_vault(d)
        new_doc, keys = vc.rotate_vault(doc, rk)            # WrongKey before anything changes
        it = d.find(d.folder(dc.SYNC), VAULT_NAME)
        loc = _local()
        try:
            _engine(loc, None).revoke(device)
        finally:
            loc.close()
        d.replace(dc.SYNC, it.id, _dump(new_doc))
        _store_keys(keys)
        _set_meta("vault", {"vault_id": new_doc["vault_id"], "epoch": new_doc["epoch"], "created": new_doc["created"]})
        _save_folders(d)
    return {"epoch": new_doc["epoch"], "note": "Press Sync Now to tell your other devices. Each will ask for the Recovery Key once."}


@_wrap_errors
def reset_identity() -> dict:
    """After 'sync identity used elsewhere' (copied data folder): a new device id; this computer's records upload again."""
    with _lock:
        old = device_id()
        new = secrets.token_hex(8)
        loc = _local()
        try:
            with loc.transaction():
                states = loc.get_meta("states") or {}
                for s in states.values():
                    if (s.get("ver") or "").startswith(old + ":"):
                        s["hash"] = None
                loc.put_meta("states", states)
                for k in ("outbox", "seq", "staged", "device_info", "extra"):
                    loc.conn.execute("DELETE FROM sync_meta WHERE key = ?", (k,))
                loc.put_meta("device_id", new)
        finally:
            loc.close()
    return {"device": new}


# --------------------------------------------------------------------------- encrypted portable backups (v2)
def _backup_dir() -> Path:
    p = lfs.backups_dir()
    p.mkdir(parents=True, exist_ok=True)
    return p


@_wrap_errors
def create_backup(passphrase: str, categories: list[str], to_drive: bool = False) -> dict:
    problem = vc.backup_passphrase_problem(passphrase)
    if problem:
        raise DataSyncError(problem, "passphrase")
    kinds = sorted({k for c in categories for k in CATEGORY_KINDS.get(c, [])})
    if not kinds:
        raise DataSyncError("Choose what to back up.", "categories")
    loc = _local()
    try:
        items = loc.read_items(kinds)
    finally:
        loc.close()
    me, now = device_id(), int(time.time() * 1000)
    records: dict[str, list[dict]] = {}
    for rid, (kind, data) in items.items():
        records.setdefault(kind, []).append({"rid": rid, "kind": kind, "ver": {"dev": me, "n": 1}, "base": None, "at": now, "del": False, "data": data})
    blob = vc.seal_backup(passphrase, PLATFORM, __version__, now, records, categories)
    name = f"abhify-backup-v2-{datetime.now():%Y%m%d-%H%M%S}.abx"
    (_backup_dir() / name).write_bytes(blob)
    out = {"name": name, "bytes": len(blob), "counts": {k: len(v) for k, v in records.items()}, "drive": None}
    if to_drive:
        d = _drive()
        it = d.create(dc.BACKUPS, name, blob)
        _save_folders(d)
        out["drive"] = {"id": it.id, "name": it.name}
    return out


def list_backups() -> list[dict]:
    out = []
    for f in sorted(_backup_dir().glob("abhify-backup-v2-*.abx"), key=lambda p: p.stat().st_mtime, reverse=True):
        try:
            h = vc.backup_header(f.read_bytes())
            out.append({"name": f.name, "bytes": f.stat().st_size, "created": h["created"], "platform": h.get("platform"), "app_version": h.get("app_version")})
        except vc.VaultError:
            continue
    return out


def backup_path(name: str) -> Path:
    if not re.fullmatch(r"abhify-backup-v2-[0-9]{8}-[0-9]{6}\.abx", name or ""):
        raise DataSyncError("Unknown backup.", "missing")
    p = _backup_dir() / name
    if not p.is_file():
        raise DataSyncError("That backup file is gone.", "missing")
    return p


@_wrap_errors
def drive_backups() -> list[dict]:
    d = _drive()
    items = [{"id": it.id, "name": it.name, "bytes": it.size, "modified": it.modified} for it in d.list(d.folder(dc.BACKUPS))
             if it.name.endswith((".abx", ".json"))]
    _save_folders(d)
    return items


@_wrap_errors
def fetch_drive_backup(file_id: str) -> dict:
    """Downloads a backup from Drive into this computer's backups folder (verified header, nothing imported)."""
    d = _drive()
    data = d.download(file_id)
    h = vc.backup_header(data)
    name = f"abhify-backup-v2-{datetime.fromtimestamp(h['created'] / 1000):%Y%m%d-%H%M%S}.abx"
    p = _backup_dir() / name
    if p.exists() and p.read_bytes() != data:
        name = f"abhify-backup-v2-{datetime.now():%Y%m%d-%H%M%S}.abx"
        p = _backup_dir() / name
    p.write_bytes(data)
    return {"name": name, "platform": h.get("platform"), "created": h["created"]}


@_wrap_errors
def preview_restore(data: bytes, passphrase: str) -> dict:
    """Decrypts and verifies the whole file first (nothing is written), then compares with this computer's records."""
    got = vc.open_backup(data, passphrase)
    loc = _local()
    try:
        kinds = [k for k in ("conversation", "message", "memory", "pref") if k in got["records"]]
        plan = _restore_plan(loc, got["records"], kinds)
    finally:
        loc.close()
    token = uuid.uuid4().hex
    with _lock:
        for t in [t for t, v in _staged_restores.items() if time.time() - v["at"] > 900]:
            _staged_restores.pop(t, None)
        _staged_restores[token] = {"records": got["records"], "at": time.time()}
    return {"token": token, "header": got["header"], "manifest": got["manifest"],
            "plan": {k: {kk: len(vv) for kk, vv in v.items()} for k, v in plan.items()}}


def _restore_plan(loc: WinLocal, records: dict[str, list[dict]], kinds: list[str]) -> dict[str, dict[str, list[dict]]]:
    items = loc.read_items(kinds)
    nat = {sc.natural_key(k, dd): rid for rid, (k, dd) in items.items() if sc.natural_key(k, dd)}
    plan: dict[str, dict[str, list[dict]]] = {}
    for kind in kinds:
        p = plan.setdefault(kind, {"new": [], "same": [], "different": [], "invalid": []})
        for r0 in records.get(kind, []):
            r = sc.validate_record(r0)
            if not r or r["del"] or r["kind"] != kind:
                p["invalid"].append(r0)
                continue
            have = items.get(r["rid"])
            if have:
                (p["same"] if sc.data_hash(have[1]) == sc.data_hash(r["data"]) or _same_enough(kind, have[1], r["data"]) else p["different"]).append(r)
            elif sc.natural_key(kind, r["data"]) in nat:
                p["same"].append(r)
            else:
                p["new"].append(r)
    return plan


def _same_enough(kind: str, a: dict, b: dict) -> bool:
    """Treats representation differences (second-precision times on Windows, missing optional fields) as the same."""
    keys = {"conversation": ("title",), "message": ("conv", "role", "content"), "memory": ("content",), "pref": ("key", "value")}[kind]
    return all(a.get(k) == b.get(k) for k in keys)


@_wrap_errors
def apply_restore(token: str, kinds: list[str], replace_different: bool = False, confirm_replace: bool = False) -> dict:
    """Additive restore of the chosen kinds in one transaction, after a safety copy of the database. Records that differ are
    replaced only with explicit confirmation; the replaced versions go to Recently deleted."""
    with _lock:
        st = _staged_restores.pop(token, None)
    if not st:
        raise DataSyncError("The restore preview expired. Open the backup again.", "expired")
    if replace_different and not confirm_replace:
        raise DataSyncError("Confirm that records which differ should be replaced.", "confirm")
    kinds = [k for k in ("conversation", "message", "memory", "pref") if k in kinds or (k == "conversation" and "message" in kinds)]
    safety = _safety_copy()
    loc = _local()
    counts = {"added": 0, "replaced": 0, "skipped": 0, "invalid": 0}
    try:
        with loc.transaction():
            plan = _restore_plan(loc, st["records"], kinds)
            trash = list(loc.get_meta("trash") or [])
            items = loc.read_items(kinds)
            for kind in kinds:
                p = plan[kind]
                counts["invalid"] += len(p["invalid"])
                counts["skipped"] += len(p["same"])
                for r in p["new"]:
                    if kind == "message" and loc._local("conversation", r["data"]["conv"]) is None:
                        counts["skipped"] += 1
                        continue
                    loc.add(kind, r["rid"], r["data"])
                    counts["added"] += 1
                for r in p["different"]:
                    if not replace_different:
                        counts["skipped"] += 1
                        continue
                    trash.append({"id": uuid.uuid4().hex, "rid": r["rid"], "kind": kind, "data": items[r["rid"]][1],
                                  "reason": "replaced by a backup restore", "at": int(time.time() * 1000)})
                    loc.update(kind, r["rid"], r["data"])
                    counts["replaced"] += 1
            loc.put_meta("trash", trash)
    finally:
        loc.close()
    return {**counts, "safety_copy": safety.name}


def _safety_copy() -> Path:
    dest = _backup_dir() / f"abhify-pre-restore-{datetime.now():%Y%m%d-%H%M%S}.db"
    src = sqlite3.connect(lfs.db_path())
    try:
        b = sqlite3.connect(dest)
        try:
            src.backup(b)
        finally:
            b.close()
    finally:
        src.close()
    return dest
