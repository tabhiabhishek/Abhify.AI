"""Manual, end-to-end encrypted sync engine (spec: docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md §4–§6). Pure logic over two
small interfaces, so the same rules run in tests (in-memory) and in the app (SQLite + Google Drive):

- `Local`: this installation's records in canonical form plus sync bookkeeping (one transaction per run);
- `Remote`: a folder of immutable files (Drive `Abhify.AI/Sync`, or a test double).

Nothing runs on a timer. `preview()` downloads and verifies but changes nothing; `apply()` imports (additive, deletions
only into "Recently deleted"), then writes this device's changes as one new op file through a durable outbox.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Any, Iterable, Protocol

import vault_crypto as vc

KINDS = ("device", "pref", "conversation", "memory", "message")
SELECTABLE = ("conversation", "message", "memory", "pref")
PREF_KEYS = ("user_name", "theme", "answer_detail", "answer_depth", "answer_style", "answer_language")
ORDER = {k: i for i, k in enumerate(KINDS)}
HIST = 32
RID = re.compile(r"[A-Za-z0-9_.:\-]{8,80}")


class SyncError(Exception):
    """User-facing; never contains keys or personal content."""


class Exists(Exception):
    """Remote.put_new: a file with that name is already there."""


class Remote(Protocol):
    def list_names(self) -> list[str]: ...
    def get(self, name: str) -> bytes: ...
    def put_new(self, name: str, data: bytes) -> None: ...


class Local(Protocol):
    def read_items(self, kinds: Iterable[str]) -> dict[str, tuple[str, dict]]: ...
    def add(self, kind: str, rid: str, data: dict) -> None: ...
    def update(self, kind: str, rid: str, data: dict) -> None: ...
    def remove(self, kind: str, rid: str) -> None: ...
    def get_meta(self, key: str, default: Any = None) -> Any: ...
    def put_meta(self, key: str, value: Any) -> None: ...
    def transaction(self): ...


# --------------------------------------------------------------------------- records
def vid(v: dict | None) -> str | None:
    return f"{v['dev']}:{v['n']}" if v else None


def data_hash(data: dict) -> str:
    return hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def _s(v: Any, n: int) -> bool:
    return isinstance(v, str) and len(v) <= n


def _ms(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool) and 0 <= v < 10 ** 14


def validate_record(r: Any) -> dict | None:
    """The record in normalized form, or None when it must not be imported (counted as invalid)."""
    if not isinstance(r, dict) or r.get("kind") not in KINDS or not isinstance(r.get("rid"), str) or not RID.fullmatch(r["rid"]):
        return None
    ver = r.get("ver")
    if not (isinstance(ver, dict) and re.fullmatch(r"[0-9a-f]{16}", str(ver.get("dev", ""))) and isinstance(ver.get("n"), int) and not isinstance(ver["n"], bool) and ver["n"] >= 1):
        return None
    base = r.get("base")
    if base is not None and not (isinstance(base, dict) and isinstance(base.get("n"), int) and not isinstance(base["n"], bool) and isinstance(base.get("dev"), str)):
        return None
    out = {"rid": r["rid"], "kind": r["kind"], "ver": {"dev": ver["dev"], "n": ver["n"]},
           "base": {"dev": base["dev"], "n": base["n"]} if base else None, "at": r.get("at") if _ms(r.get("at")) else 0, "del": r.get("del") is True}
    if out["del"]:
        out["data"] = {}
        return out
    d = r.get("data")
    if not isinstance(d, dict):
        return None
    k = r["kind"]
    if k == "conversation":
        ok = _s(d.get("title"), 500) and _ms(d.get("created"))
        keep = ("title", "created", "pinned", "archived")
    elif k == "message":
        ok = isinstance(d.get("conv"), str) and RID.fullmatch(d["conv"]) is not None and d.get("role") in ("user", "assistant") \
            and _s(d.get("content"), 1_000_000) and _ms(d.get("created")) and (d.get("model") is None or _s(d.get("model"), 200))
        keep = ("conv", "role", "content", "created", "model")
    elif k == "memory":
        ok = _s(d.get("content"), 20_000) and _ms(d.get("created")) and (d.get("category") is None or _s(d.get("category"), 40))
        keep = ("content", "created", "category", "pinned")
    elif k == "pref":
        ok = d.get("key") in PREF_KEYS and _s(d.get("value"), 200) and r["rid"] == f"pref:{d.get('key')}"
        keep = ("key", "value")
    else:  # device
        ok = _s(d.get("name"), 80) and d.get("platform") in ("windows", "android", "web") and _ms(d.get("added")) and r["rid"] == ver["dev"] \
            or (d.get("revoked") is True and _s(d.get("name"), 80))
        keep = ("name", "platform", "added", "revoked", "revoked_after_seq")
    if not ok:
        return None
    for b in ("pinned", "archived", "revoked"):
        if b in d and not isinstance(d[b], bool):
            return None
    out["data"] = {kk: d[kk] for kk in keep if kk in d and d[kk] is not None}
    return out


def natural_key(kind: str, data: dict) -> str | None:
    if kind == "memory":
        return "m|" + " ".join(str(data.get("content", "")).split()).casefold()
    if kind == "message":
        return f"g|{data.get('conv')}|{data.get('role')}|{data.get('created')}|{data.get('content')}"
    return None


# --------------------------------------------------------------------------- state
@dataclass
class State:
    kind: str
    ver: str | None = None          # "dev:n" last applied/created here
    hist: list[str] = field(default_factory=list)
    maxn: int = 0
    hash: str | None = None         # local canonical data hash at last sync (None: nothing local to compare)
    deleted: bool = False
    alias_of: str | None = None
    detached: bool = False          # deleted here, user chose not to delete elsewhere

    def push(self, new_ver: dict) -> None:
        old = self.ver
        if old and old != vid(new_ver):
            self.hist = ([old] + [h for h in self.hist if h != old])[:HIST]
        self.ver = vid(new_ver)
        self.maxn = max(self.maxn, new_ver["n"])

    def knows(self, v: dict) -> bool:
        return vid(v) == self.ver or vid(v) in self.hist


def _ver(s: str | None) -> dict | None:
    if not s:
        return None
    dev, n = s.rsplit(":", 1)
    return {"dev": dev, "n": int(n)}


@dataclass
class Action:
    op: str                          # add | update | delete | tombstone | skip | alias | adopt | conflict | hold | invalid | revoked
    record: dict | None
    detail: str = ""


# --------------------------------------------------------------------------- engine
class Engine:
    def __init__(self, local: Local, remote: Remote, device: str, platform: str, device_name: str, now=lambda: int(time.time() * 1000)):
        if not re.fullmatch(r"[0-9a-f]{16}", device):
            raise ValueError("device id")
        self.local, self.remote, self.me, self.platform, self.name, self.now = local, remote, device, platform, device_name, now

    # ---- bookkeeping in Local meta
    def _states(self) -> dict[str, State]:
        return {k: State(**v) for k, v in (self.local.get_meta("states") or {}).items()}

    def _save_states(self, st: dict[str, State]) -> None:
        self.local.put_meta("states", {k: asdict(v) for k, v in st.items()})

    def _seen(self) -> dict[str, int]:
        return dict(self.local.get_meta("seen") or {})

    def selected(self) -> list[str]:
        return [k for k in (self.local.get_meta("kinds") or []) if k in SELECTABLE]

    def devices(self) -> dict[str, dict]:
        return dict(self.local.get_meta("devices") or {})

    # ---- remote reading
    def _incoming(self, keys: dict[int, bytes], vault_id: str) -> tuple[list[dict], list[str]]:
        """Verified op bodies for every other device, contiguous after what was applied. Staged locally (resumable)."""
        seen, names = self._seen(), self.remote.list_names()
        per: dict[str, list[int]] = {}
        for n in names:
            p = vc.parse_op_name(n)
            if p and p[0] != self.me:
                per.setdefault(p[0], []).append(p[1])
        staged = dict(self.local.get_meta("staged") or {})
        ops, notes = [], []
        for dev, seqs in sorted(per.items()):
            want = seen.get(dev, 0) + 1
            for s in sorted(set(x for x in seqs if x >= want)):          # a name listed twice on Drive counts once
                if s != want:
                    notes.append(f"waiting for an earlier sync file from device {dev[:6]}")
                    break
                name = vc.op_name(dev, s)
                if name in staged:
                    body = staged[name]
                else:
                    try:
                        body = vc.open_ops(self.remote.get(name), keys, vault_id)
                    except vc.VaultError as e:
                        notes.append(str(e))
                        break
                    if body["device"] != dev or body["seq"] != s:
                        notes.append("a sync file has the wrong name; it was not imported")
                        break
                    staged[name] = body
                ops.append(body)
                want += 1
        self.local.put_meta("staged", staged)
        return ops, notes

    # ---- planning (pure simulation over copies)
    def _plan(self, ops: list[dict], states: dict[str, State], items: dict[str, tuple[str, dict]], kinds: set[str]) -> list[Action]:
        devices = self.devices()
        nat = {natural_key(k, d): rid for rid, (k, d) in items.items() if natural_key(k, d)}
        known_conv = {rid for rid, (k, _) in items.items() if k == "conversation"} | {r for r, s in states.items() if s.kind == "conversation" and not s.deleted}
        acts: list[Action] = []
        held = list(self.local.get_meta("held") or [])
        queue: list[dict] = ops + [{"device": "held", "seq": 0, "records": held}]    # held messages after the files that may bring their conversation
        for op in ops:                                                                # revocations first, whatever device order
            for r in op["records"]:
                v = validate_record(r)
                if v and v["kind"] == "device" and v["data"].get("revoked") is True and v["rid"] != op["device"]:
                    devices[v["rid"]] = {**devices.get(v["rid"], {}), **v["data"]}
        for op in queue:
            dev = op["device"]
            cut = devices.get(dev, {}).get("revoked_after_seq")
            if dev != "held" and cut is not None and op["seq"] > cut:
                acts.extend(Action("revoked", None, f"from removed device {dev[:6]}") for _ in op["records"])
                continue
            recs = [validate_record(r) for r in op["records"]]
            for r in [x for x in recs if x is None]:
                acts.append(Action("invalid", None, "a record failed validation"))
            for r in sorted((x for x in recs if x), key=lambda x: (ORDER[x["kind"]], 0 if x["base"] else 1)):   # changes before new records (natural keys)
                acts.append(self._decide(r, states, items, nat, known_conv, kinds))
        return acts

    def _decide(self, r: dict, states, items, nat, known_conv, kinds) -> Action:
        rid, kind = r["rid"], r["kind"]
        if kind == "device":                                        # bookkeeping, never a user conflict; "revoked" is sticky
            s = states.get(rid)
            if s is None:
                s = states[rid] = State("device")
            elif s.knows(r["ver"]):
                return Action("skip", r, "already here")
            if r["ver"]["n"] > s.maxn or s.ver is None:
                s.push(r["ver"])
            else:
                s.hist = ([vid(r["ver"])] + s.hist)[:HIST]
                s.maxn = max(s.maxn, r["ver"]["n"])
            return Action("update", r)
        if kind not in kinds:
            return Action("skip", r, "category not selected")
        s = states.get(rid)
        local = items.get(rid)
        if s is None:
            if r["del"]:
                states[rid] = State(kind, deleted=True)
                states[rid].push(r["ver"])
                return Action("tombstone", r)
            if kind == "message" and r["data"]["conv"] not in known_conv:
                return Action("hold", r, "its conversation hasn't arrived yet")
            nk = natural_key(kind, r["data"])
            if nk and nk in nat and nat[nk] != rid:
                states[rid] = State(kind, alias_of=nat[nk])
                states[rid].push(r["ver"])
                return Action("alias", r, "same as an item already here")
            st = State(kind)
            st.push(r["ver"])
            st.hash = data_hash(r["data"])
            states[rid] = st
            items[rid] = (kind, r["data"])
            if kind == "conversation":
                known_conv.add(rid)
            if nk:
                nat[nk] = rid
            return Action("add", r)
        if s.knows(r["ver"]):
            return Action("skip", r, "already here")
        s.maxn = max(s.maxn, r["ver"]["n"])
        if s.alias_of:
            s.push(r["ver"])
            return Action("skip", r, "duplicate of an item already here")
        local_changed = local is not None and s.hash is not None and data_hash(local[1]) != s.hash
        locally_gone = local is None and not s.deleted and not s.detached and s.hash is not None and kind != "device"
        if vid(r["base"]) == s.ver:
            if local_changed or (locally_gone and not r["del"]):
                return Action("conflict", r, "changed here and on another device" if local_changed else "deleted here, changed on another device")
            s.push(r["ver"])
            if r["del"]:
                s.deleted, s.hash = True, None
                if local is None and kind != "device":
                    return Action("tombstone", r)
                self._renat(nat, kind, rid, local[1] if local else None, None)
                items.pop(rid, None)
                return Action("delete", r)
            s.deleted, s.detached = False, False
            s.hash = data_hash(r["data"])
            if local is None and kind != "device":
                items[rid] = (kind, r["data"])
                return Action("add", r, "restored from another device")
            self._renat(nat, kind, rid, local[1] if local else None, r["data"])
            items[rid] = (kind, r["data"])
            return Action("update", r)
        # concurrent
        if r["del"] and (local is None or s.deleted):
            if r["ver"]["n"] >= (_ver(s.ver) or {"n": 0})["n"]:
                s.push(r["ver"])
            else:
                s.hist = ([vid(r["ver"])] + s.hist)[:HIST]
            s.deleted = True
            return Action("adopt", r, "already deleted here")
        if not r["del"] and local is not None and data_hash(local[1]) == data_hash(r["data"]):
            if r["ver"]["n"] > (_ver(s.ver) or {"n": 0})["n"]:
                s.push(r["ver"])
            else:
                s.hist = ([vid(r["ver"])] + s.hist)[:HIST]
            return Action("adopt", r, "same content")
        return Action("conflict", r, "changed on two devices")

    @staticmethod
    def _renat(nat: dict, kind: str, rid: str, old: dict | None, new: dict | None) -> None:
        if old is not None:
            k = natural_key(kind, old)
            if k and nat.get(k) == rid:
                del nat[k]
        if new is not None:
            k = natural_key(kind, new)
            if k:
                nat[k] = rid

    # ---- local changes
    def _collect(self, states: dict[str, State], items: dict[str, tuple[str, dict]], kinds: set[str], approve_deletes: set[str],
                 blocked: set[str] = frozenset()) -> tuple[list[dict], list[dict]]:
        """This device's changes as new versions. Records with an open conflict wait until the user resolves it."""
        out, pending_del = [], []
        now = self.now()
        for rid, (kind, data) in sorted(items.items(), key=lambda kv: ORDER[kv[1][0]]):
            if (kind != "device" and kind not in kinds) or rid in blocked or (kind == "device" and rid != self.me):
                continue
            s = states.get(rid)
            h = data_hash(data)
            if s is not None and (s.alias_of or s.hash == h):
                continue
            if s is None:
                s = states[rid] = State(kind)
            base = _ver(s.ver)
            v = {"dev": self.me, "n": s.maxn + 1}
            s.push(v)
            s.hash, s.deleted, s.detached = h, False, False
            out.append({"rid": rid, "kind": kind, "ver": v, "base": base, "at": now, "del": False, "data": data})
        for rid, s in sorted(states.items()):
            if s.kind in kinds and rid not in items and rid not in blocked and not s.deleted and not s.detached and not s.alias_of and s.hash is not None:
                if rid in approve_deletes:
                    base = _ver(s.ver)
                    v = {"dev": self.me, "n": s.maxn + 1}
                    s.push(v)
                    s.deleted, s.hash = True, None
                    out.append({"rid": rid, "kind": s.kind, "ver": v, "base": base, "at": now, "del": True, "data": {}})
                else:
                    pending_del.append({"rid": rid, "kind": s.kind})
        return out, pending_del

    def _check_not_revoked(self) -> None:
        if (self.devices().get(self.me) or {}).get("revoked"):
            raise SyncError("This device was removed from sync on another device. Your data here is unchanged; to sync again, reset this device's sync identity.")

    def revoke(self, device: str) -> dict:
        """Marks another device removed (its files after the last one applied here are ignored everywhere). The caller
        rotates the vault key with the Recovery Key; the record uploads on the next Sync Now."""
        if device == self.me:
            raise SyncError("To stop syncing this device, use Disconnect instead.")
        with self.local.transaction():
            devices, states = self.devices(), self._states()
            d = dict(devices.get(device) or {"name": "device", "platform": "windows", "added": 0})
            d.update({"revoked": True, "revoked_after_seq": self._seen().get(device, 0)})
            devices[device] = d
            s = states.setdefault(device, State("device"))
            base = _ver(s.ver)
            v = {"dev": self.me, "n": s.maxn + 1}
            s.push(v)
            extra = list(self.local.get_meta("extra") or [])
            extra.append({"rid": device, "kind": "device", "ver": v, "base": base, "at": self.now(), "del": False, "data": d})
            self.local.put_meta("extra", extra)
            self.local.put_meta("devices", devices)
            self._save_states(states)
        return d

    def _items(self, kinds: set[str]) -> dict[str, tuple[str, dict]]:
        items = dict(self.local.read_items(sorted(kinds)))
        info = self.local.get_meta("device_info") or {"name": self.name, "platform": self.platform, "added": self.now()}
        self.local.put_meta("device_info", info)
        items[self.me] = ("device", info)
        return items

    # ---- public API
    def preview(self, keys: dict[int, bytes], vault_id: str) -> dict:
        """What Sync Now would do. Downloads and verifies; changes no records."""
        kinds = set(self.selected())
        if not kinds:
            raise SyncError("Choose at least one category to sync.")
        self._check_not_revoked()
        ops, notes = self._incoming(keys, vault_id)
        states, items = self._states(), self._items(kinds)
        acts = self._plan(ops, states, items, kinds)
        blocked = {c["rid"] for c in self.conflicts()} | {a.record["rid"] for a in acts if a.op == "conflict"}
        local, pending_del = self._collect(states, items, kinds, set(), blocked)
        summary: dict[str, dict[str, int]] = {}
        for a in acts:
            k = a.record["kind"] if a.record else "other"
            if k == "device":
                continue
            summary.setdefault(a.op, {}).setdefault(k, 0)
            summary[a.op][k] += 1
        up: dict[str, int] = {}
        for r in local:
            if r["kind"] != "device":
                up[r["kind"]] = up.get(r["kind"], 0) + 1
        return {"incoming": summary, "files": len(ops), "upload": up, "local_deletions": pending_del,
                "conflicts_waiting": len(self.local.get_meta("conflicts") or []), "notes": notes, "outbox": len(self.local.get_meta("outbox") or [])}

    def apply(self, keys: dict[int, bytes], vault_id: str, epoch: int, direction: str = "both", approve_deletes: Iterable[str] = (),
              keep_elsewhere: Iterable[str] = ()) -> dict:
        if direction not in ("both", "upload", "download"):
            raise ValueError(direction)
        kinds = set(self.selected())
        if not kinds:
            raise SyncError("Choose at least one category to sync.")
        self._check_not_revoked()
        result = {"added": 0, "updated": 0, "deleted": 0, "conflicts": 0, "held": 0, "skipped": 0, "invalid": 0, "uploaded": 0, "notes": []}
        ops: list[dict] = []
        if direction in ("both", "download"):                       # network first, outside the database transaction
            ops, notes = self._incoming(keys, vault_id)
            result["notes"] += notes
        with self.local.transaction():
            states = self._states()
            if direction in ("both", "download"):
                items = self._items(kinds)
                plan_states = {k: State(**asdict(v)) for k, v in states.items()}
                acts = self._plan(ops, plan_states, dict(items), kinds)
                self._execute(acts, states, items, result)
                seen = self._seen()
                for op in ops:
                    seen[op["device"]] = op["seq"]
                self.local.put_meta("seen", seen)
                staged = dict(self.local.get_meta("staged") or {})
                for op in ops:
                    staged.pop(vc.op_name(op["device"], op["seq"]), None)
                self.local.put_meta("staged", staged)
            for rid in keep_elsewhere:
                if rid in states:
                    states[rid].detached = True
            revoked_here = bool((self.devices().get(self.me) or {}).get("revoked"))
            if direction in ("both", "upload") and not revoked_here:
                items = self._items(kinds)
                records, _ = self._collect(states, items, kinds, set(approve_deletes), {c["rid"] for c in self.conflicts()})
                records += list(self.local.get_meta("extra") or [])
                self.local.put_meta("extra", [])
                if records:
                    seq = int(self.local.get_meta("seq") or 0) + 1
                    data = vc.seal_ops(vault_id, epoch, keys[epoch], self.me, seq, self.now(), records)
                    outbox = list(self.local.get_meta("outbox") or [])
                    outbox.append({"name": vc.op_name(self.me, seq), "data": vc.b64e(data), "count": sum(1 for x in records if x["kind"] != "device")})
                    self.local.put_meta("outbox", outbox)
                    self.local.put_meta("seq", seq)
            self._save_states(states)
        if revoked_here:
            self._history(direction, result)
            raise SyncError("This device was removed from sync on another device. Nothing more was uploaded; your data here is unchanged.")
        result["uploaded"] = self.flush_outbox()
        self._history(direction, result)
        return result

    def flush_outbox(self) -> int:
        """Uploads waiting op files in order. Same bytes are retried after an interruption; never overwrites."""
        sent = 0
        outbox = list(self.local.get_meta("outbox") or [])
        while outbox:
            item = outbox[0]
            data = vc.b64d(item["data"])
            try:
                self.remote.put_new(item["name"], data)
            except Exists:
                if self.remote.get(item["name"]) != data:
                    raise SyncError("Another installation is using this device's sync identity (for example a copied data folder). "
                                    "Nothing was overwritten. Reset this device's sync identity in Data & Sync.")
            sent += item.get("count", 0)
            outbox.pop(0)
            self.local.put_meta("outbox", outbox)
        return sent

    def _execute(self, acts: list[Action], states: dict[str, State], items: dict, result: dict) -> None:
        held: list[dict] = []
        conflicts = list(self.local.get_meta("conflicts") or [])
        trash = list(self.local.get_meta("trash") or [])
        devices = self.devices()
        nat = {natural_key(k, d): rid for rid, (k, d) in items.items() if natural_key(k, d)}
        known_conv = {rid for rid, (k, _) in items.items() if k == "conversation"} | {r for r, s in states.items() if s.kind == "conversation" and not s.deleted}
        for a in acts:
            r = a.record
            if a.op in ("invalid", "revoked"):
                result["invalid" if a.op == "invalid" else "skipped"] += 1
                continue
            # re-decide against the live state (the plan ran on copies; this keeps one code path for both)
            act = self._decide(r, states, dict(items), nat, known_conv, set(self.selected()))
            rid, kind = r["rid"], r["kind"]
            if kind == "device" and act.op in ("add", "update"):
                old = devices.get(rid, {})
                merged = {**old, **r["data"]}
                if old.get("revoked"):
                    merged["revoked"], merged["revoked_after_seq"] = True, old.get("revoked_after_seq", r["data"].get("revoked_after_seq", 0))
                if rid != self.me or merged.get("revoked"):
                    devices[rid] = merged
                continue                                            # bookkeeping: not counted as the user's data
            if act.op == "add":
                if kind == "device":
                    devices[rid] = r["data"]
                else:
                    self.local.add(kind, rid, r["data"])
                    items[rid] = (kind, r["data"])
                    states[rid].hash = self._rehash(kind, rid)
                result["added"] += 1
            elif act.op == "update":
                if kind == "device":
                    devices[rid] = r["data"]
                else:
                    self.local.update(kind, rid, r["data"])
                    items[rid] = (kind, r["data"])
                    states[rid].hash = self._rehash(kind, rid)
                result["updated"] += 1
            elif act.op == "delete":
                if kind == "device":
                    devices.pop(rid, None)
                else:
                    old = items.pop(rid, None)
                    if old:
                        trash.append({"id": uuid.uuid4().hex, "rid": rid, "kind": kind, "data": old[1], "reason": "deleted on another device", "at": self.now()})
                        self.local.remove(kind, rid)
                result["deleted"] += 1
            elif act.op == "conflict":
                conflicts.append({"id": uuid.uuid4().hex, "rid": rid, "kind": kind, "local": items.get(rid, (kind, None))[1], "remote": r,
                                  "why": act.detail, "at": self.now()})
                result["conflicts"] += 1
            elif act.op == "hold":
                held.append(r)
                result["held"] += 1
            else:
                result["skipped"] += 1
        self.local.put_meta("held", held)
        self.local.put_meta("conflicts", conflicts)
        self.local.put_meta("trash", trash)
        self.local.put_meta("devices", devices)

    def _rehash(self, kind: str, rid: str) -> str | None:
        got = self.local.read_items([kind]).get(rid)
        return data_hash(got[1]) if got else None

    # ---- conflicts, recently deleted, history
    def conflicts(self) -> list[dict]:
        return list(self.local.get_meta("conflicts") or [])

    def resolve(self, conflict_id: str, choice: str) -> None:
        """keep_mine | keep_theirs | keep_both. The result uploads on the next Sync Now."""
        if choice not in ("keep_mine", "keep_theirs", "keep_both"):
            raise ValueError(choice)
        with self.local.transaction():
            cs = self.conflicts()
            c = next((x for x in cs if x["id"] == conflict_id), None)
            if c is None:
                raise SyncError("That conflict was already resolved.")
            states, r, rid, kind = self._states(), c["remote"], c["rid"], c["kind"]
            s = states.setdefault(rid, State(kind))
            items = self.local.read_items([kind])
            local = items.get(rid)
            s.push(r["ver"])                                        # the other version is now known here
            if choice == "keep_theirs":
                if local:
                    self._trash(rid, kind, local[1], "replaced by the other device's version")
                if r["del"]:
                    if local:
                        self.local.remove(kind, rid)
                    s.deleted, s.hash = True, None
                else:
                    (self.local.update if local else self.local.add)(kind, rid, r["data"])
                    s.deleted, s.detached, s.hash = False, False, self._rehash(kind, rid)
            else:
                if choice == "keep_both" and not r["del"]:
                    new_rid = uuid.uuid4().hex
                    data = dict(r["data"])
                    self.local.add(kind, new_rid, data)             # new record: uploads as new next time
                if local is None and c.get("local"):
                    self.local.add(kind, rid, c["local"])
                    local = (kind, c["local"])
                if local is None and not r["del"]:
                    s.hash, s.deleted, s.detached = data_hash(r["data"]), False, False   # kept deleted here: offered as a deletion in the next preview
                else:
                    s.hash, s.deleted, s.detached = None, False, False  # forces a new local version based on the other one
            self._save_states(states)
            self.local.put_meta("conflicts", [x for x in cs if x["id"] != conflict_id])

    def _trash(self, rid: str, kind: str, data: dict, reason: str) -> None:
        trash = list(self.local.get_meta("trash") or [])
        trash.append({"id": uuid.uuid4().hex, "rid": rid, "kind": kind, "data": data, "reason": reason, "at": self.now()})
        self.local.put_meta("trash", trash)

    def recently_deleted(self, days: int = 30) -> list[dict]:
        cutoff = self.now() - days * 86_400_000
        return [t for t in (self.local.get_meta("trash") or []) if t["at"] >= cutoff]

    def restore_deleted(self, trash_id: str) -> None:
        with self.local.transaction():
            trash = list(self.local.get_meta("trash") or [])
            t = next((x for x in trash if x["id"] == trash_id), None)
            if t is None:
                raise SyncError("That item is no longer in Recently deleted.")
            if t["rid"] in self.local.read_items([t["kind"]]):
                raise SyncError("That item is already here.")
            self.local.add(t["kind"], t["rid"], t["data"])
            states = self._states()
            s = states.setdefault(t["rid"], State(t["kind"]))
            s.deleted, s.detached, s.hash = False, False, None   # uploads as a new version (undelete) next time
            self._save_states(states)
            self.local.put_meta("trash", [x for x in trash if x["id"] != trash_id])

    def redownload_all(self) -> None:
        """After choosing more categories: read every device's files again (already-known records are skipped)."""
        self.local.put_meta("seen", {})

    def _history(self, direction: str, result: dict) -> None:
        h = list(self.local.get_meta("history") or [])
        h.insert(0, {"at": self.now(), "direction": direction, **{k: v for k, v in result.items() if k != "notes"}, "notes": result["notes"][:5]})
        self.local.put_meta("history", h[:100])
