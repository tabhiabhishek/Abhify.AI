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


def test_news_audio_reads_the_briefing_in_items_without_citations_or_links(r):
    texts = [c["text"] for c in r["audio_chunks"]]
    assert texts[0] == "Briefing from 3 stories." and [c["item"] for c in r["audio_chunks"]] == [0, 1, 2, 3]
    assert texts[1] == "Markets rose after the rate decision."                  # no [1], no Markdown
    assert not any("http" in t or "[" in t for t in texts)
    assert max(r["audio_long"]) <= 220 and len(r["audio_long"]) > 1             # long text: sentence-sized parts, nothing dropped
    assert r["audio_empty"] == 0


def test_news_audio_controls_pause_resume_skip_speed_replay(r):
    assert r["audio_start"] == {"state": "playing", "saying": "Briefing from 3 stories.", "item": 0, "items": 4}
    assert r["audio_second"]["i"] == 1                                         # parts play in order
    assert r["audio_pause"] == {"state": "paused", "i": 1, "spoken": 2}          # a late event of the stopped part doesn't advance
    assert r["audio_resume"]["state"] == "playing" and r["audio_resume"]["i"] == 1 and r["audio_resume"]["saying"].startswith("Markets")
    assert r["audio_no_duplicates"]["last_cancel"] == r["audio_no_duplicates"]["spoken_after_two_presses"] - 1   # cancelled before each start
    assert r["audio_next_item"]["item"] == 2 and r["audio_prev_item"]["item"] == 1
    assert r["audio_rate"] == {"rate": 1.5, "state": "playing"}
    assert r["audio_ended"] == {"state": "ended", "i": 0}
    assert r["audio_replay"]["state"] == "playing" and r["audio_replay"]["saying"] == "Briefing from 3 stories."
    assert r["audio_stop"] == {"state": "idle", "i": 0}


def test_news_audio_failures_are_reported(r):
    assert r["audio_error"] == {"state": "error", "error": "synthesis-failed"}
    assert r["audio_unavailable"] == {"ok": False, "state": "error", "error": "Read aloud is not available in this browser."}
    assert r["audio_nothing"]["error"] == "Nothing to read."


def test_news_audio_reuses_the_briefing_and_stops_when_leaving_news():
    src = APP_JS.read_text(encoding="utf-8")
    listen = src[src.index("function newsAudio("):src.index("function newsAudio(") + 900]
    assert "api(" not in listen and "speechChunks(news.audioText)" in listen          # no new AI request to listen
    assert "news.audioText = `${title}.\\n\\n${r.text}`" in src
    assert "if (name !== 'news' && reader.owner === 'news') reader.stop();" in src
    assert "if (reader.owner === 'news') reader.stop();                          // new content" in src


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


def test_model_chip_is_honest_about_the_model_list(r):
    """dev.8: the chip names the model that answers next, and says so when it can't."""
    assert r["model_default"] == {"label": "qwen2.5 · default", "kind": "ok", "problem": None}
    assert r["model_chosen"]["label"] == "qwen2.5:1.5b" and r["model_chosen"]["kind"] == "ok"
    assert r["model_removed"]["kind"] == "error" and "not installed" in r["model_removed"]["label"]   # never shows a model that can't run
    assert r["model_empty"]["kind"] == "error" and r["model_empty"]["label"] == "No chat model installed"
    assert r["model_ollama_down"]["kind"] == "error" and "Ollama" in r["model_ollama_down"]["label"]
    assert r["model_loading"]["kind"] == "loading"
    assert r["model_phone"]["kind"] == "remote"                                                       # phones use the PC default
    assert r["model_device"]["label"] == "On this device · Qwen 2.5 · 1.5B"
