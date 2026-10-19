"""
membership.py: who may use this PC's Abhify.AI from another device (companion access, Mode B).

Abhify.AI stores ONE person's data (chats, memories, documents). There are no user accounts, and
nothing here creates any. What this module manages is DEVICES:

  * owner_device: the owner's own phones/tablets. Same access to the owner's data as before
                  (companion.REMOTE_ROUTES), never admin actions.
  * member:       someone else's device. Only non-personal features (companion.MEMBER_ROUTES):
                  news, interview questions, quizzes, voice transcription and a "guest" chat that
                  never sees the owner's memories, documents or history and saves nothing.

Registration modes (settings.json -> companion.registration_mode):
  * invite_only (default, also used for any unknown value): a device needs a single-use invitation
    created by the owner ON THE PC.
  * public: anyone who can reach this PC may register a device as a *member* (never owner_device).
    Only switchable on the PC, with an explicit confirmation. Switching back never deletes anything.

Secrets: invitation codes (128-bit) and device tokens (192-bit) are random; only SHA-256 hashes are
stored. Failed redemptions are rate-limited per client and globally. See docs/MEMBERSHIP_AND_REGISTRATION.md.
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from typing import Any

import database as db
import local_file_service as lfs

log = logging.getLogger(__name__)

MODES = ("invite_only", "public")
ROLES = ("owner_device", "member")
DEFAULT_MODE = "invite_only"
INVITE_HOURS_DEFAULT, INVITE_HOURS_MAX = 72, 720
DEVICE_DAYS_DEFAULT = {"owner_device": 0, "member": 30}       # 0 = no expiry
MAX_ACTIVE_PUBLIC_MEMBERS = 25
GENERIC_INVALID = "This invitation isn't valid. Ask the owner for a new one."


class MembershipError(ValueError):
    """Shown to the caller; never contains secrets."""


class RateLimited(MembershipError):
    def __init__(self, retry_after: int):
        super().__init__("Too many attempts. Wait a few minutes and try again.")
        self.retry_after = retry_after


# --------------------------------------------------------------------------- helpers
def _hash(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def _fmt(dt: datetime | None) -> str | None:
    return dt.strftime("%Y-%m-%d %H:%M:%S") if dt else None     # same format as SQLite datetime('now') (UTC)


def _expired(value: str | None) -> bool:
    return bool(value) and value <= _fmt(_now())


def clean_name(name: str | None, default: str = "Phone") -> str:
    name = re.sub(r"[^\w .\-'()]", "", (name or "").strip(), flags=re.UNICODE)[:40].strip()
    return name or default


# --------------------------------------------------------------------------- registration mode
def get_mode() -> str:
    mode = lfs.load_settings().get("companion", {}).get("registration_mode") or DEFAULT_MODE
    if mode not in MODES:
        log.warning("Unknown registration_mode %r in settings.json; using invite_only", mode)
        return DEFAULT_MODE
    return mode


def set_mode(mode: str, confirm: bool = False) -> dict[str, Any]:
    """Owner action on the PC (the endpoint is local-only). Never deletes devices or data."""
    if mode not in MODES:
        raise MembershipError(f"Unknown registration mode: {mode}")
    if mode == "public" and not confirm:
        raise MembershipError("Public registration needs explicit confirmation (confirm=true).")
    lfs.update_settings(companion={"registration_mode": mode})
    log.info("Registration mode set to %s", mode)
    return {"registration_mode": mode}


# --------------------------------------------------------------------------- rate limiting (failed attempts)
class _Limiter:
    """Sliding-window counters for FAILED redemptions/registrations: per client and global."""

    def __init__(self, per_client: int = 5, global_max: int = 30, window_s: int = 600):
        self.per_client, self.global_max, self.window = per_client, global_max, window_s
        self._lock = threading.Lock()
        self._client: dict[str, deque] = {}
        self._global: deque = deque()

    def _trim(self, q: deque, now: float) -> None:
        while q and now - q[0] > self.window:
            q.popleft()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            q = self._client.setdefault(key, deque())
            self._trim(q, now); self._trim(self._global, now)
            if len(q) >= self.per_client:
                raise RateLimited(max(1, int(self.window - (now - q[0]))))
            if len(self._global) >= self.global_max:
                raise RateLimited(max(1, int(self.window - (now - self._global[0]))))

    def fail(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            self._client.setdefault(key, deque()).append(now)
            self._global.append(now)

    def reset(self) -> None:
        with self._lock:
            self._client.clear(); self._global.clear()


LIMITER = _Limiter()
REGISTRATIONS = _Limiter(per_client=3, global_max=10, window_s=3600)   # public self-registrations


# --------------------------------------------------------------------------- invitations (owner, on the PC)
def create_invitation(label: str = "", role: str = "member", hours: int = INVITE_HOURS_DEFAULT,
                      device_days: int | None = None) -> dict[str, Any]:
    if role not in ROLES:
        raise MembershipError(f"Unknown role: {role}")
    if not 1 <= int(hours) <= INVITE_HOURS_MAX:
        raise MembershipError(f"An invitation can be valid for 1 to {INVITE_HOURS_MAX} hours.")
    days = DEVICE_DAYS_DEFAULT[role] if device_days is None else int(device_days)
    if not 0 <= days <= 3650:
        raise MembershipError("Device access can last 0 (no expiry) to 3650 days.")
    code = secrets.token_urlsafe(16)                               # 128 bits; shown ONCE
    expires = _fmt(_now() + timedelta(hours=int(hours)))
    with db.connect() as c:
        cur = c.execute("INSERT INTO invitations (code_sha256, label, role, device_days, expires_at) VALUES (?, ?, ?, ?, ?)",
                        (_hash(code), clean_name(label, ""), role, days, expires))
        inv_id = cur.lastrowid
    log.info("Invitation #%s created (role=%s, valid %sh)", inv_id, role, hours)
    return {"id": inv_id, "code": code, "role": role, "label": clean_name(label, ""), "expires_at": expires,
            "device_days": days}


def _inv_status(r) -> str:
    if r["revoked_at"]:
        return "revoked"
    if r["used_at"]:
        return "used"
    return "expired" if _expired(r["expires_at"]) else "active"


def list_invitations() -> list[dict[str, Any]]:
    with db.connect() as c:
        rows = c.execute("SELECT id, label, role, device_days, created_at, expires_at, used_at, member_id, revoked_at "
                         "FROM invitations ORDER BY id DESC LIMIT 200").fetchall()
    return [{**dict(r), "status": _inv_status(r)} for r in rows]


def revoke_invitation(inv_id: int) -> bool:
    with db.connect() as c:
        n = c.execute("UPDATE invitations SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL "
                      "AND used_at IS NULL", (inv_id,)).rowcount
    return n > 0


# --------------------------------------------------------------------------- devices (members)
def _create_member(c, name: str, role: str, origin: str, days: int, invitation_id: int | None = None) -> tuple[int, str]:
    token = secrets.token_urlsafe(24)                              # 192 bits; returned ONCE
    expires = _fmt(_now() + timedelta(days=days)) if days else None
    cur = c.execute("INSERT INTO members (name, role, token_sha256, expires_at, origin, invitation_id) "
                    "VALUES (?, ?, ?, ?, ?, ?)", (clean_name(name), role, _hash(token), expires, origin, invitation_id))
    return cur.lastrowid, token


def redeem(code: str, device_name: str, client_key: str) -> dict[str, Any]:
    """Phone side of an invitation. Single use, atomic. The same vague error for every failure."""
    LIMITER.check(client_key)
    code = (code or "").strip()
    if not 16 <= len(code) <= 64:
        LIMITER.fail(client_key)
        raise MembershipError(GENERIC_INVALID)
    with db.connect() as c:
        inv = c.execute("SELECT * FROM invitations WHERE code_sha256 = ?", (_hash(code),)).fetchone()
        claimed = 0
        if inv and not inv["revoked_at"] and not inv["used_at"] and not _expired(inv["expires_at"]):
            claimed = c.execute("UPDATE invitations SET used_at = datetime('now') WHERE id = ? AND used_at IS NULL "
                                "AND revoked_at IS NULL", (inv["id"],)).rowcount     # single use, even under races
        if not claimed:
            LIMITER.fail(client_key)
            log.info("Invitation redemption refused")
            raise MembershipError(GENERIC_INVALID)
        member_id, token = _create_member(c, device_name, inv["role"], "invitation", inv["device_days"], inv["id"])
        c.execute("UPDATE invitations SET member_id = ? WHERE id = ?", (member_id, inv["id"]))
    log.info("Invitation #%s redeemed by device #%s (%s)", inv["id"], member_id, inv["role"])
    return {"token": token, "member_id": member_id, "role": inv["role"], "name": clean_name(device_name)}


def register_public(device_name: str, client_key: str) -> dict[str, Any]:
    """Only in public mode, and only as a restricted member."""
    if get_mode() != "public":
        raise MembershipError("Registration is invite-only on this computer. Ask the owner for an invitation.")
    REGISTRATIONS.check(client_key)
    with db.connect() as c:
        active = c.execute("SELECT COUNT(*) FROM members WHERE origin = 'public' AND revoked_at IS NULL").fetchone()[0]
        if active >= MAX_ACTIVE_PUBLIC_MEMBERS:
            raise MembershipError("This computer isn't accepting new devices right now.")
        member_id, token = _create_member(c, device_name, "member", "public", DEVICE_DAYS_DEFAULT["member"])
    REGISTRATIONS.fail(client_key)                                  # counts every registration (flood control)
    log.info("Public registration: device #%s", member_id)
    return {"token": token, "member_id": member_id, "role": "member", "name": clean_name(device_name)}


def issue_owner_device(name: str = "Paired device") -> dict[str, Any]:
    """Owner action on the PC ('Turn on companion access'): a credential for one of the owner's devices."""
    with db.connect() as c:
        member_id, token = _create_member(c, name, "owner_device", "owner", DEVICE_DAYS_DEFAULT["owner_device"])
    return {"token": token, "member_id": member_id, "role": "owner_device"}


_SEEN: dict[int, float] = {}


def authenticate(token: str | None) -> dict[str, Any] | None:
    """The active device for this token, or None (unknown, revoked or expired)."""
    if not token or len(token) > 128:
        return None
    h = _hash(token)
    with db.connect() as c:
        row = c.execute("SELECT id, name, role, token_sha256, expires_at, revoked_at FROM members WHERE token_sha256 = ?",
                        (h,)).fetchone()
        if not row or not hmac.compare_digest(row["token_sha256"], h) or row["revoked_at"] or _expired(row["expires_at"]):
            return None
        if time.monotonic() - _SEEN.get(row["id"], 0) > 60:          # don't write on every request
            c.execute("UPDATE members SET last_seen_at = datetime('now') WHERE id = ?", (row["id"],))
            _SEEN[row["id"]] = time.monotonic()
    return {"id": row["id"], "name": row["name"], "role": row["role"]}


def _member_status(r) -> str:
    if r["revoked_at"]:
        return "revoked"
    return "expired" if _expired(r["expires_at"]) else "active"


def list_members() -> list[dict[str, Any]]:
    with db.connect() as c:
        rows = c.execute("SELECT id, name, role, origin, created_at, last_seen_at, expires_at, revoked_at "
                         "FROM members ORDER BY id DESC LIMIT 500").fetchall()
    return [{**dict(r), "status": _member_status(r)} for r in rows]


def revoke_member(member_id: int) -> bool:
    with db.connect() as c:
        n = c.execute("UPDATE members SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL",
                      (member_id,)).rowcount
    if n:
        log.info("Device #%s revoked", member_id)
    return n > 0


def revoke_all() -> int:
    with db.connect() as c:
        n = c.execute("UPDATE members SET revoked_at = datetime('now') WHERE revoked_at IS NULL").rowcount
        c.execute("UPDATE invitations SET revoked_at = datetime('now') WHERE revoked_at IS NULL AND used_at IS NULL")
    return n


def summary() -> dict[str, Any]:
    with db.connect() as c:
        devices = c.execute("SELECT role, COUNT(*) FROM members WHERE revoked_at IS NULL GROUP BY role").fetchall()
    return {"registration_mode": get_mode(), "active_devices": {r[0]: r[1] for r in devices}}


# --------------------------------------------------------------------------- migration from 2.0.0-dev.5
def migrate_legacy_token() -> bool:
    """dev.5 and earlier had ONE shared token (hash in settings.json). Keep phones that use it working:
    move the hash into `members` as an owner device, then remove it from settings (single source)."""
    comp = lfs.load_settings().get("companion", {})
    legacy = comp.get("token_sha256")
    if not legacy:
        return False
    with db.connect() as c:
        if not c.execute("SELECT 1 FROM members WHERE token_sha256 = ?", (legacy,)).fetchone():
            c.execute("INSERT INTO members (name, role, token_sha256, origin) VALUES (?, 'owner_device', ?, 'legacy')",
                      ("Legacy paired device", legacy))
    lfs.update_settings(companion={"token_sha256": None})
    log.info("Moved the dev.5 companion token into the device list as 'Legacy paired device'")
    return True
