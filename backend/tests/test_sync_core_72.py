"""7.2.0-dev.1: manual encrypted sync engine (sync_core.py), simulated with three devices sharing one in-memory folder.
These are automated tests of the protocol rules; they don't involve Google Drive or real devices."""
import contextlib
import copy

import pytest

import sync_core as sc
import vault_crypto as vc

VID = "0" * 31 + "1"


class MemRemote:
    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.fail_puts = 0
        self.lose_reply = False

    def list_names(self):
        return sorted(self.files)

    def get(self, name):
        return self.files[name]

    def put_new(self, name, data):
        if self.fail_puts:
            self.fail_puts -= 1
            raise OSError("network down")
        if name in self.files:
            raise sc.Exists(name)
        self.files[name] = data
        if self.lose_reply:                 # stored on Drive, but the reply never arrived
            self.lose_reply = False
            raise OSError("connection reset")


class MemLocal:
    def __init__(self):
        self.rows: dict[str, tuple[str, dict]] = {}
        self.meta: dict = {}
        self.fail_add_after = None

    def read_items(self, kinds):
        return {r: (k, copy.deepcopy(d)) for r, (k, d) in self.rows.items() if k in kinds}

    def add(self, kind, rid, data):
        if self.fail_add_after is not None:
            if self.fail_add_after == 0:
                raise RuntimeError("disk full")
            self.fail_add_after -= 1
        assert rid not in self.rows
        self.rows[rid] = (kind, copy.deepcopy(data))

    def update(self, kind, rid, data):
        assert rid in self.rows
        self.rows[rid] = (kind, copy.deepcopy(data))

    def remove(self, kind, rid):
        del self.rows[rid]

    def get_meta(self, key, default=None):
        return copy.deepcopy(self.meta.get(key, default))

    def put_meta(self, key, value):
        self.meta[key] = copy.deepcopy(value)

    @contextlib.contextmanager
    def transaction(self):
        snap = copy.deepcopy((self.rows, self.meta))
        try:
            yield
        except BaseException:
            self.rows, self.meta = snap
            raise


KEYS = {1: bytes(range(32))}


class Dev:
    def __init__(self, remote, hexchar, platform="windows", clock=None):
        self.local = MemLocal()
        self.local.put_meta("kinds", ["conversation", "message", "memory", "pref"])
        self.t = clock or [1763900000000]
        self.e = sc.Engine(self.local, remote, hexchar * 16, platform, f"dev-{hexchar}", now=lambda: self._tick())

    def _tick(self):
        self.t[0] += 1
        return self.t[0]

    def sync(self, **kw):
        return self.e.apply(KEYS, VID, 1, **kw)

    def preview(self):
        return self.e.preview(KEYS, VID)

    def mem(self, rid, content, created=1):
        self.local.rows[rid] = ("memory", {"content": content, "created": created})

    def rows(self, kind):
        return {r: d for r, (k, d) in self.local.rows.items() if k == kind}


@pytest.fixture
def world():
    remote = MemRemote()
    clock = [1763900000000]
    return remote, Dev(remote, "a", clock=clock), Dev(remote, "b", "android", clock=clock), Dev(remote, "c", "web", clock=clock)


def test_upload_then_download_and_no_duplicates(world):
    remote, a, b, _ = world
    a.local.rows["conv-00000001"] = ("conversation", {"title": "Trip", "created": 5})
    a.local.rows["msg-00000001"] = ("message", {"conv": "conv-00000001", "role": "user", "content": "hi", "created": 6})
    a.mem("mem-00000001", "Likes tea")
    r = a.sync()
    assert r["uploaded"] == 3 and list(remote.files) == [vc.op_name("a" * 16, 1)]       # 3 records (+ its device record, not counted)
    assert all(b"Likes tea" not in d and b"Trip" not in d for d in remote.files.values())  # encrypted
    p = b.preview()
    assert p["incoming"]["add"] == {"conversation": 1, "message": 1, "memory": 1} and b.local.rows == {}   # preview changes nothing
    r = b.sync()
    assert r["added"] == 3 and b.rows("memory") == {"mem-00000001": {"content": "Likes tea", "created": 1}}
    assert b.rows("message")["msg-00000001"]["conv"] == "conv-00000001"
    assert b.sync()["added"] == 0 and len(b.rows("memory")) == 1                           # nothing twice
    b.e.redownload_all()                                                                   # even reading every file again
    r = b.sync()
    assert r["added"] == 0 and r["skipped"] >= 4 and len(b.local.rows) == 3
    assert a.sync()["added"] == 0                                                          # a learns b's device record only
    assert set(a.e.devices()) == {"b" * 16}


def test_edit_propagates_and_concurrent_edit_is_a_conflict_for_the_user(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "v1"); a.sync(); b.sync()
    a.mem("mem-00000001", "v2 from a"); a.sync()
    assert b.sync()["updated"] == 1 and b.rows("memory")["mem-00000001"]["content"] == "v2 from a"
    a.mem("mem-00000001", "A edit"); b.mem("mem-00000001", "B edit")
    a.sync()
    r = b.sync()
    assert r["conflicts"] == 1 and b.rows("memory")["mem-00000001"]["content"] == "B edit"   # nothing overwritten
    assert b.preview()["conflicts_waiting"] == 1
    c = b.e.conflicts()[0]
    assert c["local"]["content"] == "B edit" and c["remote"]["data"]["content"] == "A edit"
    b.e.resolve(c["id"], "keep_mine"); b.sync(); a.sync()
    assert a.rows("memory")["mem-00000001"]["content"] == "B edit" == b.rows("memory")["mem-00000001"]["content"]
    assert a.e.conflicts() == []                                                            # converged without a second conflict


def test_keep_theirs_and_keep_both(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "base"); a.sync(); b.sync()
    a.mem("mem-00000001", "A"); b.mem("mem-00000001", "B"); a.sync(); b.sync()
    b.e.resolve(b.e.conflicts()[0]["id"], "keep_theirs")
    assert b.rows("memory")["mem-00000001"]["content"] == "A"
    assert [t["data"]["content"] for t in b.e.recently_deleted()] == ["B"]                    # replaced version is recoverable
    b.sync(); a.sync()
    assert a.rows("memory")["mem-00000001"]["content"] == "A"
    a.mem("mem-00000001", "A2"); b.mem("mem-00000001", "B2"); a.sync(); b.sync()
    b.e.resolve(b.e.conflicts()[0]["id"], "keep_both"); b.sync(); a.sync()
    for d in (a, b):
        assert sorted(x["content"] for x in d.rows("memory").values()) == ["A2", "B2"]


def test_deletion_needs_approval_goes_to_recently_deleted_and_can_be_restored(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "keep me"); a.mem("mem-00000002", "delete me"); a.sync(); b.sync()
    del a.local.rows["mem-00000002"]
    p = a.preview()
    assert p["local_deletions"] == [{"rid": "mem-00000002", "kind": "memory"}]
    a.sync(); b.sync()
    assert "mem-00000002" in b.rows("memory")                                               # not ticked: not deleted elsewhere
    a.sync(approve_deletes=["mem-00000002"])
    r = b.sync()
    assert r["deleted"] == 1 and "mem-00000002" not in b.rows("memory")
    t = b.e.recently_deleted()
    assert t[0]["data"]["content"] == "delete me" and t[0]["reason"] == "deleted on another device"
    b.e.restore_deleted(t[0]["id"]); b.sync(); a.sync()
    assert a.rows("memory")["mem-00000002"]["content"] == "delete me"                        # undelete reached the other device
    assert a.preview()["local_deletions"] == []


def test_keep_elsewhere_stops_offering_the_deletion(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "x"); a.sync(); b.sync()
    del a.local.rows["mem-00000001"]
    a.sync(keep_elsewhere=["mem-00000001"])
    assert a.preview()["local_deletions"] == []
    b.sync()
    assert "mem-00000001" in b.rows("memory")


def test_deleted_here_but_edited_elsewhere_is_a_conflict(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "x"); a.sync(); b.sync()
    del b.local.rows["mem-00000001"]
    a.mem("mem-00000001", "edited"); a.sync()
    assert b.sync()["conflicts"] == 1 and "mem-00000001" not in b.rows("memory")
    b.e.resolve(b.e.conflicts()[0]["id"], "keep_theirs")
    assert b.rows("memory")["mem-00000001"]["content"] == "edited"


def test_interrupted_upload_resumes_with_identical_bytes(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "x")
    remote.fail_puts = 1
    with pytest.raises(OSError):
        a.sync()
    assert remote.files == {} and len(a.local.meta["outbox"]) == 1                         # durable outbox
    pending = a.local.meta["outbox"][0]["data"]
    a.mem("mem-00000002", "later")
    a.sync()
    assert sorted(remote.files) == [vc.op_name("a" * 16, 1), vc.op_name("a" * 16, 2)]
    assert vc.b64e(remote.files[vc.op_name("a" * 16, 1)]) == pending                        # same file, not rebuilt
    remote.lose_reply = True
    a.mem("mem-00000003", "y")
    with pytest.raises(OSError):
        a.sync()
    assert a.e.flush_outbox() == 1                                                          # already on Drive: confirmed, not duplicated
    assert len(remote.files) == 3
    assert b.sync()["added"] == 3


def test_cloned_identity_is_detected_never_overwritten(world):
    remote, a, b, _ = world
    clone = Dev(remote, "a")                                                                # e.g. a copied data folder
    a.mem("mem-00000001", "x"); a.sync()
    clone.mem("mem-00000009", "other"); before = dict(remote.files)
    with pytest.raises(sc.SyncError, match="sync identity"):
        clone.sync()
    assert remote.files == before


def test_tampered_or_missing_files_are_not_imported(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "one"); a.sync()
    a.mem("mem-00000002", "two"); a.sync()
    a.mem("mem-00000003", "three"); a.sync()
    n1, n2, n3 = (vc.op_name("a" * 16, i) for i in (1, 2, 3))
    good2 = remote.files[n2]
    ct = bytearray(remote.files[n2]); ct[-10] ^= 1; remote.files[n2] = bytes(ct)
    r = b.sync()
    assert set(b.rows("memory")) == {"mem-00000001"} and r["notes"]                        # stops at the damaged file
    assert b.e._seen()["a" * 16] == 1
    del remote.files[n2]
    b.sync()
    assert set(b.rows("memory")) == {"mem-00000001"}                                        # a gap is never skipped
    remote.files[n2] = good2
    b.sync()
    assert set(b.rows("memory")) == {"mem-00000001", "mem-00000002", "mem-00000003"}


def test_message_waits_for_its_conversation(world):
    remote, a, b, _ = world
    a.local.rows["conv-00000001"] = ("conversation", {"title": "T", "created": 1})
    a.sync()
    a.local.rows["msg-00000001"] = ("message", {"conv": "conv-00000009", "role": "user", "content": "orphan", "created": 2})
    a.sync()
    r = b.sync()
    assert r["held"] == 1 and "msg-00000001" not in b.rows("message")
    a.local.rows["conv-00000009"] = ("conversation", {"title": "late", "created": 3}); a.sync()
    b.sync()
    assert b.rows("message")["msg-00000001"]["conv"] == "conv-00000009"


def test_same_memory_typed_on_two_devices_is_not_duplicated(world):
    remote, a, b, _ = world
    a.mem("mem-aaaaaaaa", "Lives in Pune"); b.mem("mem-bbbbbbbb", "  lives in   PUNE ")
    a.sync(); r = b.sync()
    assert r["skipped"] >= 1 and len(b.rows("memory")) == 1
    a.sync()
    assert len(a.rows("memory")) == 1


def test_categories_respected_and_redownload_after_enabling(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "m"); a.local.rows["pref:theme"] = ("pref", {"key": "theme", "value": "dark"}); a.sync()
    b.local.put_meta("kinds", ["pref"])
    b.sync()
    assert b.rows("memory") == {} and b.rows("pref") == {"pref:theme": {"key": "theme", "value": "dark"}}
    b.local.put_meta("kinds", ["pref", "memory"])
    b.sync()
    assert b.rows("memory") == {}                                                           # already read
    b.e.redownload_all(); b.sync()
    assert "mem-00000001" in b.rows("memory")
    b.local.put_meta("kinds", [])
    with pytest.raises(sc.SyncError):
        b.sync()


def test_invalid_records_are_refused(world):
    remote, a, b, _ = world
    bad = [{"rid": "x", "kind": "memory", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "data": {"content": "short rid", "created": 1}},
           {"rid": "mem-00000001", "kind": "secrets", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "data": {}},
           {"rid": "mem-00000002", "kind": "memory", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "data": {"content": 5, "created": 1}},
           {"rid": "pref:api_key", "kind": "pref", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "data": {"key": "api_key", "value": "sk-1"}},
           {"rid": "msg-00000001", "kind": "message", "ver": {"dev": "a" * 16, "n": 1}, "base": None,
            "data": {"conv": "c" * 10, "role": "system", "content": "x", "created": 1}}]
    remote.files[vc.op_name("a" * 16, 1)] = vc.seal_ops(VID, 1, KEYS[1], "a" * 16, 1, 1, bad)
    r = b.sync()
    assert r["invalid"] == 5 and b.local.rows == {}


def test_revoked_device_is_ignored_and_told(world):
    remote, a, b, c = world
    c.mem("mem-0000000c", "from c"); c.sync(); a.sync(); b.sync()
    a.e.revoke("c" * 16); a.sync()
    c.mem("mem-000000c2", "after revoke"); c_seq_before = len(remote.files)
    with pytest.raises(sc.SyncError, match="removed"):
        c.sync()                                                                            # c learns it was removed; uploads nothing
    assert len(remote.files) == c_seq_before
    remote.files[vc.op_name("c" * 16, 2)] = vc.seal_ops(VID, 1, KEYS[1], "c" * 16, 2, 9, [   # a forged later file from c
        {"rid": "mem-000000c3", "kind": "memory", "ver": {"dev": "c" * 16, "n": 1}, "base": None, "at": 9, "del": False, "data": {"content": "evil", "created": 1}}])
    b.sync()
    assert "mem-0000000c" in b.rows("memory") and "mem-000000c3" not in b.rows("memory")
    assert b.e.devices()["c" * 16]["revoked"] is True


def test_failed_import_changes_nothing(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "1"); a.mem("mem-00000002", "2"); a.sync()
    b.local.fail_add_after = 1
    with pytest.raises(RuntimeError):
        b.sync()
    assert b.local.rows == {} and b.local.meta.get("seen") in (None, {})                    # one transaction: all or nothing
    b.local.fail_add_after = None
    assert b.sync()["added"] == 2


def test_preview_does_not_change_records_or_state(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "x"); a.sync()
    b.mem("mem-0000000b", "mine")
    rows_before, states_before = copy.deepcopy(b.local.rows), copy.deepcopy(b.local.meta.get("states"))
    p = b.preview()
    assert p["upload"] == {"memory": 1} and p["incoming"]["add"]["memory"] == 1
    assert b.local.rows == rows_before and b.local.meta.get("states") == states_before and b.local.meta.get("seen") is None


def test_download_only_and_upload_only(world):
    remote, a, b, _ = world
    a.mem("mem-00000001", "x"); a.sync()
    b.mem("mem-0000000b", "mine")
    r = b.sync(direction="download")
    assert r["added"] == 1 and not any(n.startswith("op-" + "b" * 16) for n in remote.files)
    b.sync(direction="upload")
    assert any(n.startswith("op-" + "b" * 16) for n in remote.files)


# --------------------------------------------------------------------------- shared conformance cases (ports run the same file)
import json as _json
import pathlib as _pl

CASES = _json.loads((_pl.Path(__file__).parent / "data" / "sync_cases.json").read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_shared_sync_case(case):
    remote, clock = MemRemote(), [1763900000000]
    devs = {h: Dev(remote, h, clock=clock) for h in "abc"}
    for i, st in enumerate(case["steps"]):
        d, op, where = devs[st["dev"]], st["op"], f"step {i}: {st}"
        if op == "set":
            d.local.rows[st["rid"]] = (st["kind"], st["data"])
        elif op == "del":
            del d.local.rows[st["rid"]]
        elif op == "kinds":
            d.local.put_meta("kinds", st["kinds"])
        elif op == "sync":
            kw = {"direction": st.get("direction", "both"), "approve_deletes": st.get("approve", []), "keep_elsewhere": st.get("keep", [])}
            if "error" in st:
                with pytest.raises(sc.SyncError, match=st["error"]):
                    d.sync(**kw)
                continue
            r = d.sync(**kw)
            for k, v in st.get("expect", {}).items():
                assert r[k] == v, where
        elif op == "rows":
            assert d.rows(st["kind"]) == st["rows"], where
        elif op == "contents":
            assert sorted(x["content"] for x in d.rows(st["kind"]).values()) == st["values"], where
        elif op == "conflicts":
            assert len(d.e.conflicts()) == st["n"], where
        elif op == "resolve":
            d.e.resolve(d.e.conflicts()[st["index"]]["id"], st["choice"])
        elif op == "restore":
            d.e.restore_deleted(d.e.recently_deleted()[-1]["id"])
        elif op == "deleted":
            assert [t["data"] for t in d.e.recently_deleted()] == st["data"], where
        elif op == "redownload":
            d.e.redownload_all()
        elif op == "revoke":
            d.e.revoke(st["target"] * 16)
        elif op == "pending_deletions":
            assert [x["rid"] for x in d.preview()["local_deletions"]] == st["rids"], where
        else:
            raise AssertionError(f"unknown op {op}")


def test_duplicate_file_name_on_drive_does_not_block_the_device(world):
    remote, a, b, _ = world
    for i in (1, 2, 3):
        a.mem(f"mem-0000000{i}", str(i)); a.sync()
    orig = remote.list_names
    remote.list_names = lambda: sorted(orig() + [vc.op_name("a" * 16, 1)])          # Drive allows two files with one name
    b.sync()
    assert set(b.rows("memory")) == {"mem-00000001", "mem-00000002", "mem-00000003"}


def test_damaged_body_stops_only_that_device(world):
    remote, a, b, c = world
    remote.files[vc.op_name("a" * 16, 1)] = vc.seal(KEYS[1], b"\x00" * 12, b"", "x")    # placeholder, replaced below
    body = vc.gz(b"[1, 2, 3]")                                                           # valid encryption, not an object
    iv = bytes(12)
    hdr = {"format": "abhify-sync-ops", "v": 1, "vault_id": VID, "epoch": 1, "device": "a" * 16, "seq": 1, "created": 5}
    ct = vc.seal(KEYS[1], iv, body, f"abhify-sync-ops|1|{VID}|1|{'a' * 16}|1|5")
    remote.files[vc.op_name("a" * 16, 1)] = vc._json({**hdr, "iv": vc.b64e(iv), "ct": vc.b64e(ct)})
    c.mem("mem-0000000c", "from c"); c.sync()
    r = b.sync()
    assert r["notes"] and "mem-0000000c" in b.rows("memory")                             # c's data still arrives
