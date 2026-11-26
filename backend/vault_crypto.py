"""Abhify.AI vault, sync-op and backup formats, version 1 (7.2.0-dev.1). Normative spec: docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md.

Only vetted primitives from the `cryptography` package are used (AES-256-GCM, PBKDF2-HMAC-SHA256); nothing here
implements a cipher. Keys, Recovery Keys and passphrases are never logged, returned in errors or written anywhere by
this module. Everything is deterministic given the random inputs, so tests can pin byte-exact vectors that the Kotlin
and JavaScript ports must reproduce.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import io
import json
import os
import re
from typing import Any, Callable

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"           # Crockford base32
ITER_PASSPHRASE = 600_000
ITER_RECOVERY = 100_000
MIN_ITER, MAX_ITER = 100_000, 5_000_000
MAX_PLAIN = 256 * 1024 * 1024
MIN_BACKUP_PASSPHRASE = 10

VAULT_FORMAT, OPS_FORMAT, BACKUP_FORMAT = "abhify-vault", "abhify-sync-ops", "abhify-vault-backup"

Random = Callable[[int], bytes]


class VaultError(Exception):
    """Base error. Messages are user-facing and never contain key material."""


class WrongKey(VaultError):
    pass


class Malformed(VaultError):
    pass


class Unsupported(VaultError):
    pass


class RecoveryKeyTypo(VaultError):
    pass


# --------------------------------------------------------------------------- encoding helpers
def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def b64d(s: Any, what: str = "value") -> bytes:
    if not isinstance(s, str):
        raise Malformed(f"The file is damaged ({what} missing).")
    try:
        return base64.b64decode(s.encode("ascii"), validate=True)
    except Exception:
        raise Malformed(f"The file is damaged ({what} unreadable).") from None


def b32c(data: bytes, chars: int | None = None) -> str:
    """Crockford base32, MSB first, final bits zero-padded; optionally truncated to `chars` characters."""
    bits = int.from_bytes(data, "big") if data else 0
    nbits = len(data) * 8
    n = (nbits + 4) // 5
    bits <<= n * 5 - nbits
    out = "".join(ALPHABET[(bits >> (5 * (n - 1 - i))) & 31] for i in range(n))
    return out[:chars] if chars else out


def _b32c_decode(s: str, nbytes: int) -> bytes:
    v = 0
    for ch in s:
        v = (v << 5) | ALPHABET.index(ch)
    extra = len(s) * 5 - nbytes * 8
    return (v >> extra).to_bytes(nbytes, "big")


# --------------------------------------------------------------------------- primitives
def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def kdf(password: str, salt: bytes, iterations: int) -> bytes:
    if not _is_int(iterations) or not (MIN_ITER <= iterations <= MAX_ITER):
        raise Unsupported("The file uses key settings this app doesn't accept.")
    if len(salt) < 16:
        raise Malformed("The file is damaged (salt).")
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=iterations).derive(password.encode("utf-8"))


def seal(key: bytes, iv: bytes, plain: bytes, aad: str) -> bytes:
    return AESGCM(key).encrypt(iv, plain, aad.encode("utf-8"))


def open_(key: bytes, iv: bytes, ct: bytes, aad: str, wrong: str) -> bytes:
    if len(iv) != 12 or len(ct) < 16:
        raise Malformed("The file is damaged (encryption header).")
    try:
        return AESGCM(key).decrypt(iv, ct, aad.encode("utf-8"))
    except InvalidTag:
        raise WrongKey(wrong) from None


def gz(data: bytes) -> bytes:
    out = gzip.compress(data, compresslevel=6, mtime=0)
    return out[:9] + bytes([255]) + out[10:]              # OS byte "unknown": the same file on every operating system


def gunz(data: bytes) -> bytes:
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as f:
            out = f.read(MAX_PLAIN + 1)
    except Exception:
        raise Malformed("The file is damaged (content unreadable).") from None
    if len(out) > MAX_PLAIN:
        raise Malformed("The file is too large to open.")
    return out


def _body(plain: bytes) -> dict:
    try:
        o = json.loads(plain.decode("utf-8"))
    except Exception:
        raise Malformed("The file's content is damaged; it was not imported.") from None
    if not isinstance(o, dict):
        raise Malformed("The file's content is damaged; it was not imported.")
    return o


def _json(o: Any) -> bytes:
    return json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


# --------------------------------------------------------------------------- Recovery Key
def new_recovery_key(rnd: Random = os.urandom) -> str:
    raw = rnd(20)
    return b32c(raw) + b32c(hashlib.sha256(raw).digest()[:3], 4)


def normalize_recovery_key(text: str) -> str:
    """Canonical 36-character form, or RecoveryKeyTypo (checked locally, before any network call)."""
    s = re.sub(r"[\s\-_]", "", (text or "").upper()).replace("O", "0").replace("I", "1").replace("L", "1")
    if len(s) != 36 or any(c not in ALPHABET for c in s):
        raise RecoveryKeyTypo("That doesn't look like a Recovery Key (36 letters and digits). Check it and try again.")
    raw = _b32c_decode(s[:32], 20)
    if b32c(hashlib.sha256(raw).digest()[:3], 4) != s[32:]:
        raise RecoveryKeyTypo("That Recovery Key has a typo (its check doesn't match). Check each group and try again.")
    return s


def display_recovery_key(canonical: str) -> str:
    return "-".join(canonical[i:i + 4] for i in range(0, 36, 4))


def qr_payload(canonical: str) -> str:
    return f"abhify-rk:1:{canonical}"


def from_qr(payload: str) -> str:
    m = re.fullmatch(r"abhify-rk:1:([0-9A-Z]{36})", (payload or "").strip())
    if not m:
        raise RecoveryKeyTypo("That QR code isn't an Abhify.AI Recovery Key.")
    return normalize_recovery_key(m.group(1))


# --------------------------------------------------------------------------- vault.json
def _wrap_aad(vault_id: str, epoch: int, iterations: int, salt_b64: str) -> str:
    return f"abhify-vault|1|{vault_id}|{epoch}|{iterations}|{salt_b64}"


def _chain_aad(vault_id: str, old_epoch: int, epoch: int) -> str:
    return f"abhify-vault-chain|1|{vault_id}|{old_epoch}|{epoch}"


def create_vault(recovery_key: str, now_ms: int, rnd: Random = os.urandom) -> tuple[dict, dict[int, bytes]]:
    """A new vault: (vault.json document, {epoch: vault key}). The keys stay on this device only."""
    rk = normalize_recovery_key(recovery_key)
    vault_id = rnd(16).hex()
    vk = rnd(32)
    doc = _wrap(vault_id, now_ms, 1, vk, rk, [], rnd)
    return doc, {1: vk}


def _wrap(vault_id: str, created: int, epoch: int, vk: bytes, rk: str, old: list[tuple[int, bytes]], rnd: Random) -> dict:
    salt = rnd(16)
    salt_b64 = b64e(salt)
    kek = kdf(rk, salt, ITER_RECOVERY)
    iv = rnd(12)
    chain = []
    for old_epoch, old_key in sorted(old):
        civ = rnd(12)
        chain.append({"epoch": old_epoch, "iv": b64e(civ), "ct": b64e(seal(vk, civ, old_key, _chain_aad(vault_id, old_epoch, epoch)))})
    return {"format": VAULT_FORMAT, "v": 1, "vault_id": vault_id, "created": int(created), "epoch": epoch,
            "kdf": {"alg": "PBKDF2-SHA256", "iter": ITER_RECOVERY, "salt": salt_b64},
            "wrap": {"iv": b64e(iv), "ct": b64e(seal(kek, iv, vk, _wrap_aad(vault_id, epoch, ITER_RECOVERY, salt_b64)))},
            "chain": chain}


def check_vault_doc(doc: Any) -> dict:
    if not isinstance(doc, dict) or doc.get("format") != VAULT_FORMAT:
        raise Malformed("This isn't an Abhify.AI vault file.")
    if doc.get("v") != 1:
        raise Unsupported("This vault was made by a newer Abhify.AI. Update the app first.")
    if not re.fullmatch(r"[0-9a-f]{32}", str(doc.get("vault_id", ""))) or not isinstance(doc.get("epoch"), int) or doc["epoch"] < 1:
        raise Malformed("The vault file is damaged.")
    k = doc.get("kdf") or {}
    if not isinstance(k, dict) or k.get("alg") != "PBKDF2-SHA256" or not _is_int(k.get("iter")) or not isinstance(k.get("salt"), str):
        raise Unsupported("The vault uses key settings this app doesn't accept.")
    if not isinstance(doc.get("created"), int) or not isinstance(doc.get("wrap"), dict) or not isinstance(doc.get("chain", []), list):
        raise Malformed("The vault file is damaged.")
    return doc


def unlock_vault(doc: dict, recovery_key: str) -> dict[int, bytes]:
    """All epoch keys {epoch: key} from vault.json and the Recovery Key. WrongKey if it doesn't open."""
    check_vault_doc(doc)
    rk = normalize_recovery_key(recovery_key)
    k = doc["kdf"]
    kek = kdf(rk, b64d(k.get("salt"), "salt"), k.get("iter", 0))
    w = doc.get("wrap") or {}
    vk = open_(kek, b64d(w.get("iv"), "iv"), b64d(w.get("ct"), "key"), _wrap_aad(doc["vault_id"], doc["epoch"], k["iter"], k["salt"]),
               "That Recovery Key doesn't open this vault.")
    keys = {doc["epoch"]: vk}
    for c in doc.get("chain") or []:
        keys[int(c["epoch"])] = open_(vk, b64d(c.get("iv"), "iv"), b64d(c.get("ct"), "key"), _chain_aad(doc["vault_id"], int(c["epoch"]), doc["epoch"]),
                                      "The vault file is damaged.")
    return keys


def rotate_vault(doc: dict, recovery_key: str, rnd: Random = os.urandom) -> tuple[dict, dict[int, bytes]]:
    """New epoch (after revoking a device): new key, rewrapped; older keys chained under the new key."""
    keys = unlock_vault(doc, recovery_key)
    epoch = doc["epoch"] + 1
    vk = rnd(32)
    new = _wrap(doc["vault_id"], doc["created"], epoch, vk, normalize_recovery_key(recovery_key), list(keys.items()), rnd)
    keys[epoch] = vk
    return new, keys


# --------------------------------------------------------------------------- op files
def op_name(device: str, seq: int) -> str:
    return f"op-{device}-{seq:08d}.abx"


def parse_op_name(name: str) -> tuple[str, int] | None:
    m = re.fullmatch(r"op-([0-9a-f]{16})-([0-9]{8})\.abx", name or "")
    return (m.group(1), int(m.group(2))) if m else None


def _ops_aad(vault_id: str, epoch: int, device: str, seq: int, created: int) -> str:
    return f"abhify-sync-ops|1|{vault_id}|{epoch}|{device}|{seq}|{created}"


def seal_ops(vault_id: str, epoch: int, key: bytes, device: str, seq: int, created: int, records: list[dict], rnd: Random = os.urandom) -> bytes:
    iv = rnd(12)
    body = gz(_json({"device": device, "seq": seq, "records": records}))
    ct = seal(key, iv, body, _ops_aad(vault_id, epoch, device, seq, int(created)))
    return _json({"format": OPS_FORMAT, "v": 1, "vault_id": vault_id, "epoch": epoch, "device": device, "seq": seq,
                  "created": int(created), "iv": b64e(iv), "ct": b64e(ct)})


def op_header(data: bytes) -> dict:
    try:
        h = json.loads(data.decode("utf-8"))
    except Exception:
        raise Malformed("A sync file is damaged.") from None
    if not isinstance(h, dict) or h.get("format") != OPS_FORMAT:
        raise Malformed("A sync file is damaged.")
    if h.get("v") != 1:
        raise Unsupported("A sync file was made by a newer Abhify.AI. Update this app first.")
    if not (_is_int(h.get("epoch")) and _is_int(h.get("seq")) and _is_int(h.get("created"))
            and re.fullmatch(r"[0-9a-f]{16}", str(h.get("device", ""))) and re.fullmatch(r"[0-9a-f]{32}", str(h.get("vault_id", "")))):
        raise Malformed("A sync file is damaged.")
    return h


def open_ops(data: bytes, keys: dict[int, bytes], vault_id: str) -> dict:
    """{device, seq, epoch, created, records} after verifying the tag (header included via AAD)."""
    h = op_header(data)
    if h["vault_id"] != vault_id:
        raise Malformed("A sync file belongs to another vault.")
    key = keys.get(h["epoch"])
    if key is None:
        raise WrongKey("A sync file uses a newer vault key. Enter your Recovery Key to continue.")
    plain = gunz(open_(key, b64d(h.get("iv"), "iv"), b64d(h.get("ct"), "content"),
                       _ops_aad(h["vault_id"], h["epoch"], h["device"], h["seq"], h["created"]), "A sync file was changed or damaged; it was not imported."))
    body = _body(plain)
    if body.get("device") != h["device"] or body.get("seq") != h["seq"] or not isinstance(body.get("records"), list):
        raise Malformed("A sync file is inconsistent; it was not imported.")
    return {"device": h["device"], "seq": h["seq"], "epoch": h["epoch"], "created": h["created"], "records": body["records"]}


# --------------------------------------------------------------------------- backup v2
def _backup_aad(platform: str, app_version: str, created: int, iterations: int, salt_b64: str) -> str:
    return f"abhify-vault-backup|2|{platform}|{app_version}|{created}|{iterations}|{salt_b64}"


def backup_passphrase_problem(passphrase: str | None) -> str | None:
    if not passphrase or len(passphrase) < MIN_BACKUP_PASSPHRASE:
        return f"Use a passphrase of at least {MIN_BACKUP_PASSPHRASE} characters (or your Recovery Key)."
    return None


def seal_backup(passphrase: str, platform: str, app_version: str, created: int, records: dict[str, list[dict]], categories: list[str],
                rnd: Random = os.urandom, iterations: int = ITER_PASSPHRASE) -> bytes:
    problem = backup_passphrase_problem(passphrase)
    if problem:
        raise VaultError(problem)
    if platform not in ("windows", "android", "web") or "|" in app_version:
        raise VaultError("Invalid backup header.")
    salt = rnd(16)
    salt_b64 = b64e(salt)
    iv = rnd(12)
    manifest = {"categories": list(categories), "counts": {k: len(v) for k, v in records.items()},
                "platform": platform, "app_version": app_version, "created": int(created)}
    ct = seal(kdf(passphrase, salt, iterations), iv, gz(_json({"manifest": manifest, "records": records})),
              _backup_aad(platform, app_version, int(created), iterations, salt_b64))
    return _json({"format": BACKUP_FORMAT, "v": 2, "platform": platform, "app_version": app_version, "created": int(created),
                  "kdf": {"alg": "PBKDF2-SHA256", "iter": iterations, "salt": salt_b64}, "iv": b64e(iv), "ct": b64e(ct)})


def backup_header(data: bytes) -> dict:
    try:
        h = json.loads(data.decode("utf-8"))
    except Exception:
        raise Malformed("This isn't an Abhify.AI backup file.") from None
    if not isinstance(h, dict) or h.get("format") != BACKUP_FORMAT:
        raise Malformed("This isn't an Abhify.AI encrypted backup (v2).")
    if h.get("v") != 2:
        raise Unsupported("This backup was made by a newer Abhify.AI. Update the app first.")
    k = h.get("kdf") or {}
    if not isinstance(k, dict) or k.get("alg") != "PBKDF2-SHA256" or not _is_int(k.get("iter")) or not _is_int(h.get("created")) or not isinstance(k.get("salt"), str):
        raise Unsupported("This backup uses settings this app doesn't accept.")
    return h


def open_backup(data: bytes, passphrase: str) -> dict:
    """{header, manifest, records} after the tag check; counts must match. Nothing is imported here."""
    h = backup_header(data)
    k = h["kdf"]
    plain = gunz(open_(kdf(passphrase or "", b64d(k.get("salt"), "salt"), k["iter"]), b64d(h.get("iv"), "iv"), b64d(h.get("ct"), "content"),
                       _backup_aad(str(h.get("platform")), str(h.get("app_version")), h["created"], k["iter"], k["salt"]),
                       "Wrong passphrase, or the file was changed."))
    body = _body(plain)
    manifest, records = body.get("manifest") or {}, body.get("records") or {}
    if not isinstance(manifest, dict) or not isinstance(records, dict) or any(not isinstance(v, list) for v in records.values()):
        raise Malformed("The backup content is damaged.")
    counts = manifest.get("counts") or {}
    if not isinstance(counts, dict):
        raise Malformed("The backup content is damaged.")
    for kind, n in counts.items():
        if len(records.get(kind, [])) != n:
            raise Malformed("The backup content is incomplete (counts don't match).")
    return {"header": {kk: h[kk] for kk in ("platform", "app_version", "created")}, "manifest": manifest, "records": records}
