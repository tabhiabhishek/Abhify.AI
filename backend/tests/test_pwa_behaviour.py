"""B3 / F2 regression tests: the PWA's connection status and pairing-link handling (dev.3).

The real functions from frontend/app.js run in Node (tests/js/pwa_harness.js) with small stubs.
Skipped when Node isn't installed; this is logic coverage, not a real-device test.
"""
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
APP_JS = ROOT / "frontend" / "app.js"
NODE = shutil.which("node")


@pytest.fixture(scope="module")
def r():
    if not NODE:
        pytest.skip("Node.js not installed")
    out = subprocess.run([NODE, str(Path(__file__).parent / "js" / "pwa_harness.js"), str(APP_JS)],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def test_unpaired_phone_never_claims_ai_ready(r):
    for k in ("unpaired_no_token", "unpaired_wrong_token", "companion_off", "remote_unpaired_unreachable"):
        text, dot, banner = r[k]
        assert "ready" not in text.lower(), k
        assert dot == "off" and banner, k
    assert r["unpaired_no_token"][0] == "Not paired"
    assert "rejected" in r["unpaired_wrong_token"][0]
    assert "off on your computer" in r["companion_off"][0]


def test_paired_states_are_distinguished(r):
    assert r["paired_ready"] == ["AI ready · runs on your computer", "on", ""]
    assert "Ollama not running" in r["paired_no_ollama"][0] and r["paired_no_ollama"][1] == "warn"
    assert "model not installed" in r["paired_no_model"][0] and r["paired_no_model"][1] == "warn"
    assert "isn't reachable" in r["paired_unreachable_never"][0]
    assert "lost" in r["paired_connection_lost"][0]
    assert r["device_offline"][0] == "Offline mode"


def test_phone_status_never_says_ai_runs_on_the_phone(r):
    statuses = {k: v for k, v in r.items() if isinstance(v, list) and len(v) == 3}
    assert len(statuses) >= 14
    for k, (text, _dot, banner) in statuses.items():
        assert not re.search(r"on (this|your) (phone|device)", text + banner, re.I), k


def test_local_computer_states_unchanged(r):
    assert r["local_ready"][0] == "Online · AI ready"
    assert r["local_no_internet"][0] == "Local AI ready · no internet"
    assert r["local_no_ollama"][0] == "Backend up · Ollama offline"
    assert r["local_lost"][0] == "Connection lost · retrying"


def test_pairing_link_fresh_and_while_open(r):
    assert r["pair_fresh"] == {"stored": True, "token": "A" * 32, "hash_cleared": True}
    assert r["pair_no_hash"] is False
    assert r["pair_while_open"]["stored"] is True and r["pair_while_open"]["token"] == "A" * 32
    assert r["pair_while_open"]["toasts"] == ["✅ Paired with your computer"]
    assert r["pair_rejects_short"] is False


def test_rejected_pairing_is_not_reported_as_paired(r):
    assert r["pair_rejected_toasts"] and "rejected" in r["pair_rejected_toasts"][0]


def test_app_js_wires_hashchange_and_storage_events():
    js = APP_JS.read_text(encoding="utf-8")
    assert re.search(r"addEventListener\('hashchange',[^\n]*readPairingLink\(\)", js)
    assert re.search(r"addEventListener\('storage',[^\n]*LS\.token", js)
    assert "headers: authHeaders()" in js                                  # /health carries the token
    assert "AI ready · via your computer" not in js                         # the old misleading text is gone


def test_guest_and_join_states(r):
    """dev.6 membership: guests see an honest guest status; an unpaired phone is offered 'Join' only
    when the PC is in public mode."""
    assert r["member_ready"][0] == "AI ready · runs on the owner's computer (guest)" and r["member_ready"][1] == "on"
    assert "data-join" in r["public_join"][2] and r["public_join"][0] == "Not paired"
    assert "data-join" not in r["invite_only_unpaired"][2] and "invitation" in r["invite_only_unpaired"][2]


def test_invitation_links_are_redeemed_and_removed_from_the_address_bar():
    js = APP_JS.read_text(encoding="utf-8")
    fn = js[js.index("async function readInviteLink"):js.index("async function joinAsGuest")]
    assert fn.index("history.replaceState") < fn.index("/companion/redeem")    # code leaves the URL first
    assert "store.set(LS.token, r.token)" in fn and "m[1]" in fn
    assert re.search(r"addEventListener\('hashchange'.*readInviteLink", js)
