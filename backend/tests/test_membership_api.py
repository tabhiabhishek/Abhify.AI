"""Membership over HTTP (dev.6): invitations, roles, guest chat, public mode, revocation, leaks.
Requests from 192.168.1.42 behave like a phone; the default TestClient is the PC itself."""
import re

import pytest
from fastapi.testclient import TestClient

import companion
import database as db
import local_file_service as lfs
import membership as ms
import memory_service as mem


@pytest.fixture
def pc(data_root, monkeypatch):
    import main
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    ms.LIMITER.reset(); ms.REGISTRATIONS.reset(); ms._SEEN.clear()
    with TestClient(main.app) as c:
        yield c
    ms.LIMITER.reset(); ms.REGISTRATIONS.reset()


def phone(ip="192.168.1.42"):
    import main
    return TestClient(main.app, client=(ip, 50000))


def bearer(t):
    return {"Authorization": f"Bearer {t}"}


def invite(pc, role="member", **kw):
    r = pc.post("/companion/invitations", json={"role": role, **kw})
    assert r.status_code == 200, r.text
    return r.json()


def join(code, ip="192.168.1.42", name="Pixel"):
    return phone(ip).post("/companion/redeem", json={"code": code, "device_name": name})


@pytest.fixture
def on(pc):
    pc.post("/companion/enable")                       # owner turns companion access on (+ one owner device)
    return pc


def test_default_is_invite_only_and_uninvited_registration_is_rejected(on):
    assert on.get("/companion/admin").json()["registration_mode"] == "invite_only"
    r = phone().post("/companion/register", json={"device_name": "Intruder"})
    assert r.status_code == 400 and "invite-only" in r.json()["detail"]
    r = join("made-up-code-0123456789")
    assert r.status_code == 400 and r.json()["detail"] == ms.GENERIC_INVALID


def test_invitation_flow_works_once(on):
    inv = invite(on, label="Asha")
    assert inv["port"] and inv["tailscale_command"].startswith("tailscale serve --bg ")
    r = join(inv["code"])
    assert r.status_code == 200 and r.json()["role"] == "member"
    assert join(inv["code"], ip="192.168.1.43").status_code == 400          # can't be reused
    statuses = {i["status"] for i in on.get("/companion/invitations").json()["invitations"]}
    assert statuses == {"used"}


def test_expired_invitation_is_refused(on):
    inv = invite(on)
    with db.connect() as c:
        c.execute("UPDATE invitations SET expires_at = '2000-01-01 00:00:00' WHERE id = ?", (inv["id"],))
    assert join(inv["code"]).status_code == 400


def test_brute_force_is_rate_limited(on):
    codes = [join(f"guess-{i:04d}-abcdefghijklmn").status_code for i in range(7)]
    assert codes[:5] == [400] * 5 and codes[5:] == [429, 429]
    r = join("guess-9999-abcdefghijklmn")
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0


def test_member_gets_only_non_personal_features(on, monkeypatch):
    import main
    mem.add_memory("owner's secret goal: get promoted")
    cid = mem.ensure_conversation(None, "owner question"); mem.add_message(cid, "user", "owner private chat")
    token = join(invite(on)["code"]).json()["token"]
    p, h = phone(), bearer(token)
    for path in ["/memory", "/conversations", f"/conversations/{cid}", "/preferences", "/knowledge",
                 "/knowledge/status", "/knowledge/files", "/progress/u1"]:
        r = p.get(path, headers=h)
        assert r.status_code == 403, path
        assert "secret goal" not in r.text and "private chat" not in r.text, path
    for path in ["/backup", "/system/diagnostics", "/companion/admin", "/companion/members", "/setup/profiles"]:
        assert p.get(path, headers=h).status_code == 403, path
    assert p.post("/memory", headers=h, json={"content": "x"}).status_code == 403
    assert p.post("/knowledge/upload", headers=h, files={"file": ("a.txt", b"x")}).status_code == 403
    seen = {}

    async def fake_agent(message, conversation_id, guest=False):
        seen.update(guest=guest, cid=conversation_id)
        return {"answer": "guest answer", "conversation_id": None, "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    r = p.post("/chat", headers=h, json={"message": "hi", "thread_id": cid})
    assert r.status_code == 200 and seen == {"guest": True, "cid": None}     # never the owner's thread


def test_owner_device_keeps_full_personal_access(on, monkeypatch):
    import main
    mem.add_memory("owner memory")
    token = join(invite(on, role="owner_device")["code"]).json()["token"]
    p, h = phone(), bearer(token)
    assert "owner memory" in p.get("/memory", headers=h).text
    seen = {}

    async def fake_agent(message, conversation_id, guest=False):
        seen["guest"] = guest
        return {"answer": "a", "conversation_id": "c1", "tools_used": [], "sources": []}
    monkeypatch.setattr(main, "run_agent", fake_agent)
    p.post("/chat", headers=h, json={"message": "hi"})
    assert seen["guest"] is False
    for path in ["/backup", "/companion/admin", "/companion/members"]:
        assert p.get(path, headers=h).status_code == 403, path         # still no admin


def test_nobody_remote_can_change_membership(on):
    token = join(invite(on, role="owner_device")["code"]).json()["token"]
    for client, h in ((phone(), {}), (phone(), bearer(token))):
        assert client.post("/companion/registration-mode", headers=h, json={"mode": "public", "confirm": True}).status_code in (401, 403)
        assert client.post("/companion/invitations", headers=h, json={"role": "owner_device"}).status_code in (401, 403)
        assert client.post("/companion/members/1/revoke", headers=h).status_code in (401, 403)
        assert client.post("/companion/lan-access", headers=h, json={"enabled": True, "confirm": True}).status_code in (401, 403)
    tunneled = {"X-Forwarded-For": "100.64.0.7", **bearer(token)}       # via Tailscale: still remote
    assert on.post("/companion/registration-mode", headers=tunneled, json={"mode": "public", "confirm": True}).status_code == 403
    assert ms.get_mode() == "invite_only"


def test_public_mode_needs_confirmation_and_only_makes_members(on):
    assert on.post("/companion/registration-mode", json={"mode": "public"}).status_code == 400
    assert on.post("/companion/registration-mode", json={"mode": "public", "confirm": True}).json()["registration_mode"] == "public"
    unpaired = phone().get("/health").json()
    assert unpaired["registration"] == "public" and unpaired["authorized"] is False
    r = phone().post("/companion/register", json={"device_name": "Visitor"})
    assert r.status_code == 200 and r.json()["role"] == "member"
    assert phone().get("/memory", headers=bearer(r.json()["token"])).status_code == 403
    on.post("/companion/registration-mode", json={"mode": "invite_only"})
    assert phone().get("/news", headers=bearer(r.json()["token"])).status_code != 401   # existing device kept
    assert phone().post("/companion/register", json={"device_name": "Late"}).status_code == 400


def test_revoking_a_device_cuts_it_off_immediately(on):
    dev = join(invite(on, role="owner_device")["code"]).json()
    h = bearer(dev["token"])
    assert phone().get("/memory", headers=h).status_code == 200
    assert on.post(f"/companion/members/{dev['member_id']}/revoke").status_code == 200
    r = phone().get("/memory", headers=h)
    assert r.status_code == 401 and "revoked" in r.json()["detail"]
    assert phone().get("/health", headers=h).json()["authorized"] is False


def test_turning_companion_off_revokes_all_devices_and_invitations(on):
    dev = join(invite(on, role="owner_device")["code"]).json()
    pending = invite(on)
    on.post("/companion/disable")
    on.post("/companion/enable")
    assert phone().get("/memory", headers=bearer(dev["token"])).status_code == 401
    assert join(pending["code"]).status_code == 400


def test_admin_screen_and_responses_leak_no_secrets(on, caplog):
    inv = invite(on, role="owner_device")
    dev = join(inv["code"]).json()
    admin = on.get("/companion/admin").text
    with db.connect() as c:
        hashes = [r[0] for r in c.execute("SELECT token_sha256 FROM members UNION ALL SELECT code_sha256 FROM invitations")]
    for secret in (inv["code"], dev["token"], *hashes):
        assert secret not in admin
        assert secret not in caplog.text
    assert not re.search(r"[A-Za-z]:\\\\Users", phone().get("/companion", headers=bearer(dev["token"])).text)
    st = phone().get("/companion", headers=bearer(dev["token"])).json()
    assert st["role"] == "owner_device" and "lan_addresses" not in st and "port" not in st


def test_unpaired_phone_sees_join_option_only_when_public(on):
    assert phone().get("/health").json()["registration"] == "invite_only"
    assert phone().get("/companion").json()["registration"] == "invite_only"


def test_lan_access_needs_confirmation_and_is_off_by_default(on):
    assert lfs.load_settings()["companion"]["lan_access"] is False
    assert on.post("/companion/lan-access", json={"enabled": True}).status_code == 400
    r = on.post("/companion/lan-access", json={"enabled": True, "confirm": True}).json()
    assert r["lan_access"] is True and lfs.load_settings()["companion"]["lan_access"] is True


def test_admin_reports_actual_port(on, monkeypatch):
    monkeypatch.setenv("ABHIFY_PORT", "18000")
    a = on.get("/companion/admin").json()
    assert a["port"] == 18000 and a["tailscale_command"] == "tailscale serve --bg 18000"
