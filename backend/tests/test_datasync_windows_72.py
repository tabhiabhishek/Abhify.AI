"""7.2.0-dev.1: Windows Data & Sync end to end against a fake Google (tests/fake_google.py): OAuth loopback + PKCE,
vault setup/join, manual sync with a simulated second device, conflicts, deletions, encrypted backups and restore.
Automated tests only: no real Google account, no physical device."""
import json
import logging
import pathlib
import re
import urllib.parse
import urllib.request

import pytest

import drive_client as dc
import google_oauth
import secure_store
import sync_core as sc
import vault_crypto as vc
from fake_google import FakeGoogle

DESKTOP = "desk-123.apps.googleusercontent.com"
ANDROID = "android-456.apps.googleusercontent.com"


class MemLocal:
    """A second device (stands in for the phone or the Web app) in memory."""
    def __init__(self):
        self.rows, self.meta = {}, {}

    def read_items(self, kinds):
        return {r: (k, dict(d)) for r, (k, d) in self.rows.items() if k in kinds}

    def add(self, kind, rid, data):
        self.rows[rid] = (kind, dict(data))

    def update(self, kind, rid, data):
        self.rows[rid] = (kind, dict(data))

    def remove(self, kind, rid):
        self.rows.pop(rid, None)

    def get_meta(self, key, default=None):
        return json.loads(json.dumps(self.meta.get(key, default)))

    def put_meta(self, key, value):
        self.meta[key] = json.loads(json.dumps(value))

    import contextlib

    @contextlib.contextmanager
    def transaction(self):
        snap = json.loads(json.dumps([self.rows, self.meta]))
        try:
            yield
        except BaseException:
            self.rows, self.meta = {k: tuple(v) for k, v in snap[0].items()}, snap[1]
            raise


@pytest.fixture
def win(migrated, monkeypatch):
    import datasync_service as ds
    monkeypatch.setattr(secure_store, "_protect", lambda b: b"SEALED" + bytes(x ^ 0x5A for x in b))
    monkeypatch.setattr(secure_store, "_unprotect", lambda b: bytes(x ^ 0x5A for x in b[6:]))
    fake = FakeGoogle(share_between_clients=True)
    monkeypatch.setattr(google_oauth, "_transport", fake.transport())
    monkeypatch.setattr(ds, "_transport", fake.transport())
    google_oauth.forget_access()
    ds._pending_vault.clear()
    ds._staged_restores.clear()
    yield ds, fake
    google_oauth.forget_access()


def _connect(ds, fake, scope_ok=True):
    ds.import_client(json.dumps({"installed": {"client_id": DESKTOP, "client_secret": "not-really-secret", "project_id": "abhify-ai",
                                               "redirect_uris": ["http://localhost"]}}))
    opened = []
    url = ds.connect()["auth_url"]
    q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
    if not scope_ok:
        fake.granted_scope = "openid"
    with urllib.request.urlopen(f"{q['redirect_uri']}?state={q['state']}&code=good-code", timeout=10) as r:
        page = r.read().decode()
    return q, page


def _phone(fake, rk_display):
    """The simulated phone joins the same vault with the Recovery Key and its own OAuth client."""
    tok = fake.token_for(ANDROID)
    drive = dc.Drive(lambda: tok, fake.transport())
    sync = dc.SyncFolder(drive)
    doc = json.loads(fake.body_of("vault.json"))
    keys = vc.unlock_vault(doc, rk_display)
    loc = MemLocal()
    loc.put_meta("kinds", ["conversation", "message", "memory", "pref"])
    eng = sc.Engine(loc, sync, "b" * 16, "android", "Phone")
    return eng, loc, doc, keys


def _setup_vault(ds):
    created = ds.vault_create()
    rk = created["recovery_key"]
    ds.vault_confirm(rk[-4:])
    return rk


# --------------------------------------------------------------------------- OAuth
def test_oauth_loopback_pkce_drive_file_only_and_tokens_sealed(win, data_root):
    ds, fake = win
    q, page = _connect(ds, fake)
    assert q["scope"] == "https://www.googleapis.com/auth/drive.file"                       # nothing broader
    assert q["code_challenge_method"] == "S256" and len(q["code_challenge"]) >= 43 and q["redirect_uri"].startswith("http://127.0.0.1:")
    assert "Google Drive connected" in page and google_oauth.flow_status()["status"] == "connected"
    raw = (data_root / "secrets" / "datasync.json").read_text()
    rt = next(iter(fake.refresh))
    assert rt not in raw and "not-really-secret" not in raw                               # DPAPI-sealed at rest
    assert ds.finish_connect()["email"] == "tester@example.com"
    google_oauth.forget_access()
    assert google_oauth.access_token().startswith("at-" + DESKTOP)                        # silent refresh
    assert ds.disconnect()["revoked"] is True and fake.revoked == [rt] and not google_oauth.connected()


def test_oauth_rejects_wrong_state_missing_scope_and_wrong_client_type(win):
    ds, fake = win
    ds.import_client(json.dumps({"installed": {"client_id": DESKTOP, "client_secret": "s"}}))
    url = ds.connect()["auth_url"]
    q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
    with urllib.request.urlopen(f"{q['redirect_uri']}?state=forged&code=good-code", timeout=10) as r:
        assert "no longer valid" in r.read().decode()
    assert not google_oauth.connected()
    _, page = _connect(ds, fake, scope_ok=False)
    assert "access wasn&#x27;t granted" in page and not google_oauth.connected()
    with pytest.raises(ds.DataSyncError, match="Desktop app"):
        ds.import_client(json.dumps({"web": {"client_id": "x.apps.googleusercontent.com", "client_secret": "s"}}))


def test_expired_refresh_token_asks_to_connect_again(win):
    ds, fake = win
    _connect(ds, fake)
    fake.refresh.clear()                                                                   # Testing-mode expiry or revoked at Google
    google_oauth.forget_access()
    with pytest.raises(ds.DataSyncError) as e:
        ds.vault_probe()
    assert e.value.code == "auth" and not google_oauth.connected()


# --------------------------------------------------------------------------- vault
def test_vault_create_confirm_and_nothing_secret_leaves_or_stays(win, data_root):
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    assert ds.vault_probe() == {"exists": False}
    created = ds.vault_create()
    rk = created["recovery_key"]
    assert created["qr"] == vc.qr_payload(vc.normalize_recovery_key(rk))
    assert "vault.json" not in fake.names()                                                # nothing uploaded before confirmation
    with pytest.raises(ds.DataSyncError, match="doesn't match"):
        ds.vault_confirm("ZZZZ" if rk[-4:] != "ZZZZ" else "YYYY")
    ds.vault_confirm(rk[-4:].lower())
    vault_text = fake.body_of("vault.json").decode()
    canon = vc.normalize_recovery_key(rk)
    keys = vc.unlock_vault(json.loads(vault_text), rk)
    for k in keys.values():
        assert k.hex() not in vault_text and vc.b64e(k) not in vault_text
    assert canon not in vault_text and rk not in vault_text
    for f in data_root.rglob("*"):                                                         # the Recovery Key is never stored here
        if f.is_file():
            b = f.read_bytes()
            assert canon.encode() not in b and rk.encode() not in b, f
    assert ds.vault_probe()["joined"] is True
    with pytest.raises(ds.DataSyncError) as e:
        ds.vault_create()
    assert e.value.code == "exists"


def test_join_with_typo_wrong_key_and_right_key(win):
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    rk = _setup_vault(ds)
    secure_store.delete("vault_keys")
    with pytest.raises(ds.DataSyncError, match="typo|look like"):
        ds.vault_join(rk[:-1] + ("0" if rk[-1] != "0" else "1"))
    n_before = len(fake.requests)
    with pytest.raises(ds.DataSyncError):
        ds.vault_join("short")
    assert len(fake.requests) == n_before                                                  # typos never reach the network
    with pytest.raises(ds.DataSyncError, match="doesn't open"):
        ds.vault_join(vc.display_recovery_key(vc.new_recovery_key()))
    assert ds.vault_join(rk)["epoch"] == 1


# --------------------------------------------------------------------------- sync with a second device
def test_windows_and_phone_exchange_conversations_and_memory(win):
    import memory_service as mem
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    rk = _setup_vault(ds)
    with pytest.raises(ds.DataSyncError, match="category"):
        ds.preview()
    assert ds.set_kinds(["memory", "message"]) == ["conversation", "memory", "message"]   # messages always with conversations
    cid = mem.ensure_conversation(None, "Plan a trip to Goa")
    mem.add_message(cid, "user", "Plan a trip to Goa ✈")
    mem.add_message(cid, "assistant", "Sure: 3 days…", model="qwen2.5:7b")
    mem.add_memory("Prefers trains", category="preference")
    p = ds.preview()
    assert p["upload"] == {"conversation": 1, "message": 2, "memory": 1}
    r = ds.apply()
    assert r["uploaded"] == 4
    for n in fake.names():
        if n.startswith("op-"):
            assert b"Goa" not in fake.body_of(n) and b"trains" not in fake.body_of(n)      # encrypted on this computer first
    phone, ploc, doc, keys = _phone(fake, rk)
    pr = phone.apply(keys, doc["vault_id"], doc["epoch"])
    assert pr["added"] == 4
    msgs = sorted((d["role"], d["content"]) for k, d in ploc.rows.values() if k == "message")
    assert msgs == [("assistant", "Sure: 3 days…"), ("user", "Plan a trip to Goa ✈")]
    conv_rid = next(r for r, (k, _) in ploc.rows.items() if k == "conversation")
    ploc.rows["mem-phone-0001"] = ("memory", {"content": "Vegetarian", "created": 1763900000000})
    ploc.rows["msg-phone-0001"] = ("message", {"conv": conv_rid, "role": "user", "content": "Add a beach day", "created": 1763900005000})
    phone.apply(keys, doc["vault_id"], doc["epoch"])
    r = ds.apply()
    assert r["added"] == 2
    assert {m["content"] for m in mem.list_memories()} == {"Prefers trains", "Vegetarian"}
    assert [m["content"] for m in mem.recent_messages(cid, 10)][-1] == "Add a beach day"            # the phone's message, in the same chat
    assert ds.apply()["added"] == 0 and ds.preview()["upload"] == {}                       # no echo, no duplicates


def test_conflict_on_windows_resolved_by_the_user(win):
    import memory_service as mem
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    rk = _setup_vault(ds)
    ds.set_kinds(["memory"])
    m = mem.add_memory("Lives in Pune")
    ds.apply()
    phone, ploc, doc, keys = _phone(fake, rk)
    phone.apply(keys, doc["vault_id"], 1)
    rid = next(iter(ploc.rows))
    ploc.rows[rid] = ("memory", {**ploc.rows[rid][1], "content": "Lives in Mumbai"})
    phone.apply(keys, doc["vault_id"], 1)
    mem.update_memory(m["id"], content="Lives in Bengaluru")
    r = ds.apply()
    assert r["conflicts"] == 1 and mem.get_memory(m["id"])["content"] == "Lives in Bengaluru"   # never silently overwritten
    c = ds.conflicts()[0]
    assert c["local"]["content"] == "Lives in Bengaluru" and c["remote"]["data"]["content"] == "Lives in Mumbai"
    ds.resolve(c["id"], "keep_theirs")
    assert mem.get_memory(m["id"])["content"] == "Lives in Mumbai"
    assert [t["data"]["content"] for t in ds.recently_deleted()] == ["Lives in Bengaluru"]


def test_remote_deletion_goes_to_recently_deleted_and_conversation_messages_come_back(win):
    import memory_service as mem
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    rk = _setup_vault(ds)
    ds.set_kinds(["conversation", "message", "memory"])
    cid = mem.ensure_conversation(None, "Keep this chat")
    mem.add_message(cid, "user", "first"); mem.add_message(cid, "assistant", "second")
    ds.apply()
    phone, ploc, doc, keys = _phone(fake, rk)
    phone.apply(keys, doc["vault_id"], 1)
    conv_rid = next(r for r, (k, _) in ploc.rows.items() if k == "conversation")
    del ploc.rows[conv_rid]
    phone.apply(keys, doc["vault_id"], 1, approve_deletes=[conv_rid])
    r = ds.apply()
    assert r["deleted"] == 1 and mem.list_conversations() == []
    t = ds.recently_deleted()
    assert t[0]["data"]["title"] == "Keep this chat"
    ds.restore_deleted(t[0]["id"])
    convs = mem.list_conversations()
    assert len(convs) == 1 and convs[0]["messages"] == 2                                   # messages restored with it


def test_interrupted_upload_keeps_the_outbox_and_resumes(win, monkeypatch):
    import memory_service as mem
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    _setup_vault(ds)
    ds.set_kinds(["memory"])
    mem.add_memory("Survives a network cut")
    real = fake.handle
    calls = {"n": 0}

    def flaky(req):
        if req.url.path.startswith("/upload/") and b"abhify-sync-ops" in req.content and calls["n"] == 0:
            calls["n"] += 1
            return __import__("httpx").Response(503, text="{}")
        return real(req)
    import httpx
    monkeypatch.setattr(ds, "_transport", httpx.MockTransport(flaky))
    with pytest.raises(ds.DataSyncError) as e:
        ds.apply()
    assert e.value.code == "retry" and ds.status()["outbox"] == 1
    assert ds.apply()["uploaded"] == 1 and ds.status()["outbox"] == 0
    assert len([n for n in fake.names() if n.startswith("op-")]) == 1


def test_requirement_a_isolated_clients_are_detected_not_assumed(migrated, monkeypatch):
    """If Google isolates drive.file per OAuth client, the Windows client can't see the phone's files: the check says so."""
    import datasync_service as ds
    monkeypatch.setattr(secure_store, "_protect", lambda b: b"SEALED" + b)
    monkeypatch.setattr(secure_store, "_unprotect", lambda b: b[6:])
    fake = FakeGoogle(share_between_clients=False)
    monkeypatch.setattr(google_oauth, "_transport", fake.transport())
    monkeypatch.setattr(ds, "_transport", fake.transport())
    google_oauth.forget_access()
    phone_drive = dc.Drive(lambda: fake.token_for(ANDROID), fake.transport())
    phone_drive.create(dc.BACKUPS, "abhify-backup-phone.json", json.dumps({"format": "abhify-backup-encrypted", "data": "x", "iv": "y", "salt": "z"}).encode())
    _connect(ds, fake); ds.finish_connect()
    r = ds.interop_check()
    assert r["root_visible_before"] is False and r["folders_seen"] == {}
    assert phone_drive.find(phone_drive.folder(dc.IMPORTS), r["probe"]) is None            # and the phone can't see ours
    google_oauth.forget_access()


# --------------------------------------------------------------------------- encrypted portable backups
def test_backup_create_encrypted_and_restore_additively_elsewhere(win, data_root, tmp_path, monkeypatch):
    import local_file_service as lfs
    import memory_service as mem
    import database as db
    ds, fake = win
    mem.add_memory("Backup me ✓", category="goal")
    cid = mem.ensure_conversation(None, "Old chat")
    mem.add_message(cid, "user", "hello there")
    with pytest.raises(ds.DataSyncError, match="at least 10"):
        ds.create_backup("short", ["Personal memory"])
    out = ds.create_backup("a long passphrase", ["Personal memory", "Conversations"])
    blob = (lfs.backups_dir() / out["name"]).read_bytes()
    assert b"Backup me" not in blob and b"hello there" not in blob and out["counts"] == {"memory": 1, "conversation": 1, "message": 1}
    assert ds.list_backups()[0]["name"] == out["name"]
    # "another computer": a fresh data root
    other = tmp_path / "OtherPC"
    monkeypatch.setenv("ABHIFY_DATA_DIR", str(other))
    lfs.reset_cache(); lfs.ensure_layout(); db.migrate()
    mem.add_memory("Already here")
    with pytest.raises(ds.DataSyncError, match="Wrong passphrase"):
        ds.preview_restore(blob, "the wrong passphrase")
    assert {m["content"] for m in mem.list_memories()} == {"Already here"}                 # nothing written on failure
    p = ds.preview_restore(blob, "a long passphrase")
    assert p["plan"]["memory"]["new"] == 1 and p["plan"]["message"]["new"] == 1 and p["header"]["platform"] == "windows"
    r = ds.apply_restore(p["token"], ["memory", "message"])
    assert r["added"] == 3 and (lfs.backups_dir() / r["safety_copy"]).exists()
    assert {m["content"] for m in mem.list_memories()} == {"Already here", "Backup me ✓"}
    assert mem.list_conversations()[0]["messages"] == 1
    p2 = ds.preview_restore(blob, "a long passphrase")
    assert ds.apply_restore(p2["token"], ["memory", "message"])["added"] == 0              # restoring twice changes nothing
    target = next(m for m in mem.list_memories() if m["content"] == "Backup me ✓")
    mem.update_memory(target["id"], content="Edited here")
    p3 = ds.preview_restore(blob, "a long passphrase")
    assert p3["plan"]["memory"]["different"] == 1
    with pytest.raises(ds.DataSyncError, match="Confirm"):
        ds.apply_restore(p3["token"], ["memory"], replace_different=True)
    p3 = ds.preview_restore(blob, "a long passphrase")
    assert ds.apply_restore(p3["token"], ["memory"])["replaced"] == 0                      # default: additive, keeps the edit
    assert mem.get_memory(target["id"])["content"] == "Edited here"
    p3 = ds.preview_restore(blob, "a long passphrase")
    assert ds.apply_restore(p3["token"], ["memory"], replace_different=True, confirm_replace=True)["replaced"] == 1
    assert mem.get_memory(target["id"])["content"] == "Backup me ✓"
    assert any(t["data"]["content"] == "Edited here" for t in ds.recently_deleted())


def test_backup_from_android_or_web_restores_on_windows(win):
    import memory_service as mem
    ds, fake = win
    recs = {"memory": [{"rid": "mem-from-phone-1", "kind": "memory", "ver": {"dev": "b" * 16, "n": 3}, "base": None, "at": 1, "del": False,
                        "data": {"content": "From the phone", "created": 1763800000000}}],
            "conversation": [{"rid": "conv-from-phone", "kind": "conversation", "ver": {"dev": "b" * 16, "n": 1}, "base": None, "at": 1, "del": False,
                              "data": {"title": "Phone chat", "created": 1763800000000}}],
            "message": [{"rid": "msg-from-phone-1", "kind": "message", "ver": {"dev": "b" * 16, "n": 1}, "base": None, "at": 1, "del": False,
                         "data": {"conv": "conv-from-phone", "role": "assistant", "content": "नमस्ते", "created": 1763800001000}}]}
    for platform in ("android", "web"):
        blob = vc.seal_backup("phone passphrase!", platform, "7.2.0-dev.1", 1763800002000, recs, ["Conversations", "Personal memory"], iterations=100_000)
        p = ds.preview_restore(blob, "phone passphrase!")
        assert p["header"]["platform"] == platform
        ds.apply_restore(p["token"], ["memory", "message"])
    assert [m["content"] for m in mem.list_memories()] == ["From the phone"]               # second restore added nothing
    assert mem.list_conversations()[0]["messages"] == 1


def test_drive_backups_are_always_encrypted(win):
    ds, fake = win
    _connect(ds, fake); ds.finish_connect()
    import memory_service as mem
    mem.add_memory("secret thing")
    out = ds.create_backup("a long passphrase", ["Personal memory"], to_drive=True)
    assert out["drive"]["name"] == out["name"] and b"secret thing" not in fake.body_of(out["name"])
    d = dc.Drive(lambda: fake.token_for(DESKTOP), fake.transport())
    for sub in (dc.BACKUPS, dc.SYNC):
        with pytest.raises(dc.DriveError, match="only encrypted"):
            d.create(sub, "plain.json", b'{"memories": ["secret thing"]}')
    assert ds.drive_backups()[0]["name"] == out["name"]
    assert ds.fetch_drive_backup(out["drive"]["id"])["platform"] == "windows"


# --------------------------------------------------------------------------- routes, privacy, no automation
def test_routes_are_local_only_and_errors_carry_a_code(win):
    from fastapi.testclient import TestClient
    import main
    ds, fake = win
    c = TestClient(main.app)
    assert c.get("/datasync/status").json()["automatic"] is False
    r = c.get("/datasync/status", headers={"Origin": "https://evil.example", "Sec-Fetch-Site": "cross-site"})
    assert r.status_code == 403
    r = c.post("/datasync/preview")
    assert r.status_code == 409 and r.json()["code"] == "no_vault" or r.status_code == 428
    r = c.post("/datasync/backups", json={"passphrase": "x", "categories": ["Personal memory"]})
    assert r.status_code == 400 and r.json()["code"] == "passphrase"


def test_nothing_runs_automatically_and_secrets_never_logged(win, caplog):
    ds, fake = win
    caplog.set_level(logging.DEBUG)
    _connect(ds, fake); ds.finish_connect()
    rk = _setup_vault(ds)
    ds.set_kinds(["memory"])
    import memory_service as mem
    mem.add_memory("x")
    ds.apply()
    logs = "\n".join(r.getMessage() for r in caplog.records)
    for secret in [*fake.refresh, *fake.tokens, rk, vc.normalize_recovery_key(rk), "not-really-secret", "good-code"]:
        assert secret not in logs
    root = pathlib.Path(ds.__file__).parent
    main_src = (root / "main.py").read_text(encoding="utf-8")
    lifespan = main_src[main_src.index("async def lifespan"):main_src.index("app = FastAPI(")]
    assert "dsync" not in lifespan and "datasync" not in lifespan                          # no sync at startup
    for mod in ("datasync_service.py", "sync_core.py", "drive_client.py", "datasync_local.py"):
        src = (root / mod).read_text(encoding="utf-8")
        assert "threading.Timer" not in src and "schedule" not in src.lower().replace("scheduled backups", "")


def test_populated_v6_database_upgrades_to_v7_with_a_backup_and_every_row_kept(data_root, monkeypatch):
    """7.1.0-dev.2 (schema v6) data: v7 only adds the sync tables; a backup is made first; every row stays."""
    import database as db
    import local_file_service as lfs
    import memory_service as mem
    full = list(db.MIGRATIONS)
    monkeypatch.setattr(db, "MIGRATIONS", full[:6]); monkeypatch.setattr(db, "SCHEMA_VERSION", 6)
    db.migrate()
    cid = mem.ensure_conversation(None, "q"); mem.add_message(cid, "user", "kept", model=None); mem.add_memory("still here")
    monkeypatch.setattr(db, "MIGRATIONS", full); monkeypatch.setattr(db, "SCHEMA_VERSION", len(full))
    r = db.migrate()
    assert (r["from"], r["to"]) == (6, 7) and "pre-schema-v7" in r["backup"] and pathlib.Path(r["backup"]).exists()
    assert [m["content"] for m in mem.recent_messages(cid)] == ["kept"] and [m["content"] for m in mem.list_memories()] == ["still here"]
    with db.connect() as c:
        assert {t[0] for t in c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'sync_%'")} == {"sync_map", "sync_meta", "sync_stash"}
        assert c.execute("SELECT COUNT(*) FROM sync_map").fetchone()[0] == 0                # nothing is synced or mapped by upgrading


def test_tests_never_open_the_users_browser(win, _never_open_a_real_browser):
    ds, fake = win
    _connect(ds, fake)
    assert len(_never_open_a_real_browser) == 1 and _never_open_a_real_browser[0].startswith("https://accounts.google.com/")   # captured, not opened
