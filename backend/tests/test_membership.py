"""Membership service (dev.6): invitations, device credentials, registration modes, rate limiting,
and the migration of the dev.5 shared token. Throw-away data folder only."""
import hashlib
import sqlite3

import pytest

import database as db
import local_file_service as lfs
import membership as ms


@pytest.fixture(autouse=True)
def fresh(migrated):
    ms.LIMITER.reset(); ms.REGISTRATIONS.reset(); ms._SEEN.clear()
    yield
    ms.LIMITER.reset(); ms.REGISTRATIONS.reset()


def test_new_installation_is_invite_only():
    assert ms.get_mode() == "invite_only"
    assert lfs.load_settings()["companion"]["registration_mode"] == "invite_only"


def test_unknown_mode_value_falls_back_to_invite_only():
    lfs.update_settings(companion={"registration_mode": "PUBLIC!!"})
    assert ms.get_mode() == "invite_only"


def test_invitation_is_hashed_single_use_and_creates_a_device():
    inv = ms.create_invitation("Ravi's phone", role="member")
    with db.connect() as c:
        stored = c.execute("SELECT code_sha256 FROM invitations WHERE id = ?", (inv["id"],)).fetchone()[0]
    assert stored == hashlib.sha256(inv["code"].encode()).hexdigest() and inv["code"] not in stored
    dev = ms.redeem(inv["code"], "Pixel 8", "c1")
    assert dev["role"] == "member" and len(dev["token"]) >= 32
    assert ms.authenticate(dev["token"])["role"] == "member"
    with pytest.raises(ms.MembershipError, match="isn't valid"):
        ms.redeem(inv["code"], "Pixel 8", "c2")                     # second use refused
    assert [i["status"] for i in ms.list_invitations()] == ["used"]


def test_expired_and_revoked_invitations_are_refused_with_the_same_message():
    inv = ms.create_invitation(role="member")
    with db.connect() as c:
        c.execute("UPDATE invitations SET expires_at = '2000-01-01 00:00:00' WHERE id = ?", (inv["id"],))
    with pytest.raises(ms.MembershipError) as e1:
        ms.redeem(inv["code"], "x", "c1")
    inv2 = ms.create_invitation(role="member")
    assert ms.revoke_invitation(inv2["id"])
    with pytest.raises(ms.MembershipError) as e2:
        ms.redeem(inv2["code"], "x", "c1")
    with pytest.raises(ms.MembershipError) as e3:
        ms.redeem("x" * 22, "x", "c1")                              # unknown code
    assert str(e1.value) == str(e2.value) == str(e3.value) == ms.GENERIC_INVALID
    assert {i["status"] for i in ms.list_invitations()} == {"expired", "revoked"}


def test_failed_redemptions_are_rate_limited_per_client_and_globally():
    for _ in range(5):
        with pytest.raises(ms.MembershipError):
            ms.redeem("wrong-code-" + "x" * 12, "x", "attacker")
    good = ms.create_invitation(role="member")
    with pytest.raises(ms.RateLimited):
        ms.redeem(good["code"], "x", "attacker")                    # even a valid code waits
    assert ms.redeem(good["code"], "x", "someone-else")["role"] == "member"
    for i in range(30):                                              # global budget across many clients
        try:
            ms.redeem("wrong-code-" + "y" * 12, "x", f"bot{i}")
        except ms.RateLimited:
            break
        except ms.MembershipError:
            pass
    with pytest.raises(ms.RateLimited):
        ms.redeem("wrong-code-" + "z" * 12, "x", "fresh-client")


def test_revoked_or_expired_device_loses_access():
    dev = ms.redeem(ms.create_invitation(role="owner_device")["code"], "iPhone", "c")
    assert ms.authenticate(dev["token"])
    assert ms.revoke_member(dev["member_id"])
    assert ms.authenticate(dev["token"]) is None
    dev2 = ms.redeem(ms.create_invitation(role="member", device_days=1)["code"], "Tab", "c")
    with db.connect() as c:
        c.execute("UPDATE members SET expires_at = '2000-01-01 00:00:00' WHERE id = ?", (dev2["member_id"],))
    assert ms.authenticate(dev2["token"]) is None
    assert {m["status"] for m in ms.list_members()} == {"revoked", "expired"}


def test_member_devices_expire_by_default_owner_devices_do_not():
    m = ms.create_invitation(role="member"); o = ms.create_invitation(role="owner_device")
    assert m["device_days"] == 30 and o["device_days"] == 0
    dm = ms.redeem(m["code"], "a", "c"); do = ms.redeem(o["code"], "b", "c")
    rows = {r["id"]: r for r in ms.list_members()}
    assert rows[dm["member_id"]]["expires_at"] and rows[do["member_id"]]["expires_at"] is None


def test_public_registration_only_when_enabled_and_only_as_member():
    with pytest.raises(ms.MembershipError, match="invite-only"):
        ms.register_public("Phone", "c")
    with pytest.raises(ms.MembershipError, match="confirmation"):
        ms.set_mode("public")                                        # needs explicit confirmation
    ms.set_mode("public", confirm=True)
    dev = ms.register_public("Phone", "c")
    assert dev["role"] == "member" and ms.authenticate(dev["token"])["role"] == "member"
    ms.set_mode("invite_only")
    assert ms.authenticate(dev["token"])                             # switching back deletes nothing
    with pytest.raises(ms.MembershipError, match="invite-only"):
        ms.register_public("Phone 2", "c2")


def test_switching_modes_keeps_all_existing_data():
    import memory_service as mem
    mem.add_memory("survives mode switches")
    cid = mem.ensure_conversation(None, "hello"); mem.add_message(cid, "user", "hello")
    dev = ms.issue_owner_device()
    for mode, ok in (("public", True), ("invite_only", False), ("public", True), ("invite_only", False)):
        ms.set_mode(mode, confirm=ok)
    assert [m["content"] for m in mem.list_memories()] == ["survives mode switches"]
    assert len(mem.list_conversations()) == 1 and ms.authenticate(dev["token"])


def test_public_registrations_are_capped(monkeypatch):
    monkeypatch.setattr(ms, "MAX_ACTIVE_PUBLIC_MEMBERS", 2)
    ms.set_mode("public", confirm=True)
    ms.register_public("a", "c1"); ms.register_public("b", "c2")
    with pytest.raises(ms.MembershipError, match="isn't accepting"):
        ms.register_public("c", "c3")


def test_legacy_dev5_token_keeps_working_after_migration():
    token = "legacy-dev5-token-abcdefghijklmnop"
    lfs.update_settings(companion={"enabled": True, "token_sha256": hashlib.sha256(token.encode()).hexdigest()})
    assert ms.migrate_legacy_token() is True
    assert lfs.load_settings()["companion"]["token_sha256"] is None  # one source of truth
    dev = ms.authenticate(token)
    assert dev and dev["role"] == "owner_device" and dev["name"] == "Legacy paired device"
    assert ms.migrate_legacy_token() is False                        # idempotent


def test_names_are_sanitised():
    cleaned = ms.clean_name("<script>alert(1)</script>")
    assert "<" not in cleaned and ">" not in cleaned and "/" not in cleaned   # no markup survives
    assert ms.clean_name("") == "Phone" and len(ms.clean_name("x" * 100)) == 40


def test_secrets_never_stored_in_plain_text(data_root):
    inv = ms.create_invitation(role="member"); dev = ms.redeem(inv["code"], "p", "c")
    raw = lfs.db_path().read_bytes()
    with sqlite3.connect(lfs.db_path()) as c:                        # include the WAL contents
        c.execute("PRAGMA wal_checkpoint(FULL)")
    raw += lfs.db_path().read_bytes()
    assert inv["code"].encode() not in raw and dev["token"].encode() not in raw


def test_populated_v4_database_upgrades_to_v5_without_changing_data(data_root, monkeypatch):
    """A dev.5 (schema v4) database full of data gets the v5 tables, a backup first, and keeps every row."""
    import memory_service as mem
    for suffix in ("", "-wal", "-shm"):                   # start from scratch (the autouse fixture made v5)
        lfs.db_path().with_name(lfs.db_path().name + suffix).unlink(missing_ok=True)
    full = list(db.MIGRATIONS)
    monkeypatch.setattr(db, "MIGRATIONS", full[:4]); monkeypatch.setattr(db, "SCHEMA_VERSION", 4)
    db.migrate()
    mem.add_memory("kept through v5")
    cid = mem.ensure_conversation(None, "q"); mem.add_message(cid, "user", "kept message")
    db.record_event("u1", "mcq", "ml", True, "item")
    tables = ["events", "conversations", "messages", "memories", "documents"]
    with db.connect() as c:
        before = {t: c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
    # the v4 -> v5 step itself (7.0 added v6; test_model_switching checks v5 -> v6)
    monkeypatch.setattr(db, "MIGRATIONS", full[:5]); monkeypatch.setattr(db, "SCHEMA_VERSION", 5)
    r = db.migrate()
    assert (r["from"], r["to"]) == (4, 5) and r["backup"] and "pre-schema-v5" in r["backup"]
    with db.connect() as c:
        after = {t: c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
        assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert before == after and before["memories"] == 1 and before["messages"] == 1
    assert [m["content"] for m in mem.list_memories()] == ["kept through v5"]
