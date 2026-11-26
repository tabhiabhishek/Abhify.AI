"""7.1 voice pipeline: the local speech engine never downloads on its own, reports its state, needs a confirmed download from
this computer, and gives a clear 409 when the model is missing; the web helpers (plan, silence detection, voice choice, error
messages, read-aloud rule) via a Node harness; the page markup for the privacy-visible states."""
import json
import shutil
import subprocess
import sys
import types
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def client(data_root):
    from fastapi.testclient import TestClient
    import main
    return TestClient(main.app)


def test_model_is_never_downloaded_implicitly(monkeypatch):
    import transcribe
    seen = []

    def fake_download(name, local_files_only=False, **k):
        seen.append(local_files_only)
        raise FileNotFoundError("no snapshot")
    monkeypatch.setitem(sys.modules, "faster_whisper.utils", types.SimpleNamespace(download_model=fake_download))
    monkeypatch.setattr(transcribe, "_model", None)
    with pytest.raises(transcribe.NotInstalled) as e:
        transcribe._load()
    assert "about 145 MB" in str(e.value) and "Settings → Voice" in str(e.value)
    assert seen and all(seen)                                    # only local look-ups: no network


def test_status_reports_missing_model_with_size(client, monkeypatch):
    import transcribe
    monkeypatch.setattr(transcribe, "library_available", lambda: True)
    monkeypatch.setattr(transcribe, "model_installed", lambda name=None: False)
    st = client.get("/voice/status").json()
    assert st["available"] and not st["installed"] and not st["ready"] and st["download_mb"] == 145
    assert st["source"] == "huggingface.co/Systran/faster-whisper-base" and st["download"]["state"] == "idle"


def test_transcribe_without_model_is_a_clear_409(client, monkeypatch):
    import transcribe
    monkeypatch.setattr(transcribe, "_model", None)
    monkeypatch.setattr(transcribe, "model_installed", lambda name=None: False)
    r = client.post("/transcribe", files={"file": ("a.webm", b"\x1a\x45\xdf\xa3 audio", "audio/webm")})
    assert r.status_code == 409 and "isn't installed yet" in r.json()["detail"]
    r = client.post("/voice", files={"file": ("a.webm", b"\x1a\x45\xdf\xa3 audio", "audio/webm")})
    assert r.status_code == 409


def test_download_needs_confirmation_and_runs_once(client, monkeypatch):
    import transcribe
    started = []
    monkeypatch.setattr(transcribe, "library_available", lambda: True)
    monkeypatch.setattr(transcribe, "model_installed", lambda name=None: False)
    monkeypatch.setattr(transcribe, "_download", {"state": "idle", "error": None, "started": None, "finished": None})

    monkeypatch.setattr(transcribe, "_run_download", lambda name: started.append((name,)))   # the thread runs this fake only
    r = client.post("/voice/model/download", json={})
    assert r.status_code == 400 and "145 MB" in r.json()["detail"] and started == []
    assert client.post("/voice/model/download", json={"confirm": True}).json()["download"]["state"] == "downloading"
    client.post("/voice/model/download", json={"confirm": True})
    assert started == [("base",)]                                # a second press doesn't start a second download


def test_download_is_local_only(client, monkeypatch):
    import companion
    assert "/voice/model/download" not in companion.REMOTE_ROUTES and "/voice/status" not in companion.REMOTE_ROUTES
    assert not any("/voice/model/download".startswith(p) for p in companion.REMOTE_PREFIXES)


def test_download_failure_is_reported_without_raw_error(monkeypatch):
    import transcribe

    def boom(name, **k):
        raise OSError("[Errno 11001] getaddrinfo failed for huggingface.co")
    monkeypatch.setitem(sys.modules, "faster_whisper.utils", types.SimpleNamespace(download_model=boom))
    monkeypatch.setattr(transcribe, "_download", {"state": "downloading", "error": None, "started": 1, "finished": None})
    transcribe._run_download("base")
    assert transcribe._download["state"] == "error" and "Check the internet connection" in transcribe._download["error"]
    assert "getaddrinfo" not in transcribe._download["error"]


def test_web_voice_helpers():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "voice_harness.js"), str(ROOT / "frontend" / "app.js")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    p = r["plan"]
    assert p["local_ready"] == {"use": "local"} and p["local_unknown"] == {"use": "local"}
    assert p["local_missing"] == {"setup": "download", "mb": 145, "downloading": False, "error": None}
    assert p["local_downloading"]["downloading"] is True and p["local_failed"]["error"] == "offline"
    assert p["local_no_lib"] == {"setup": "unavailable"} and p["local_no_backend_sr"] == {"setup": "nobackend"}
    assert "error" in p["local_no_backend_no_sr"]
    assert p["browser_sr"] == {"use": "browser"} and p["browser_no_sr_local_ready"]["use"] == "local" and "error" in p["browser_no_sr_nothing"]
    v = r["vad"]
    assert v["speech_then_pause"]["result"] == "stop:silence" and 4000 <= v["speech_then_pause"]["at"] <= 4300   # 1.6 s after speech
    assert v["silence_only"]["result"] == "stop:nospeech" and v["noisy_room_no_speech"]["result"] == "stop:nospeech"
    assert v["noisy_room_speech"]["result"] == "stop:silence" and v["long_speech"]["result"] == "stop:max"
    assert v["speaks_at_once"]["result"] == "stop:silence"                   # 7.1 regression: talking straight away
    vc = r["voice"]
    assert vc["default_en"] == "Microsoft Zira" and vc["saved"] == "Google US English" and vc["saved_gone"] == "Microsoft Zira"
    assert vc["hindi"] == "Microsoft Hemant" and vc["only_online"] == "Google UK" and vc["none"] is None
    assert "blocked" in r["mic"][0] and "Windows Settings" in r["mic"][0] and "No microphone" in r["mic"][1] and "No microphone" in r["mic"][2]
    assert "another app" in r["mic"][3] and "secure page" in r["mic"][4] and "Weird" in r["mic"][5]
    assert "blocked" in r["sr"][0] and "On this computer" in r["sr"][1] and "No speech" in r["sr"][2] and "xyz" in r["sr"][4]
    assert r["speak"] == [[True, False], [True, True], [False, False]]
    assert r["reader"]["spoken"] == [{"text": "नमस्ते", "lang": "hi-IN", "voice": "Microsoft Hemant"}] and r["reader"]["state"] == "ended"


def test_page_shows_listening_state_and_privacy_choices():
    html = (ROOT / "frontend" / "index.html").read_text(encoding="utf-8")
    app = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
    for el in ("voiceStop", "voiceCancelRec", "voiceSetup", "voiceDl", "voiceUseBrowser", "voiceReview", "voiceReviewDiscard", "speakChip",
               "speakStop", "setVoiceEngine", "setMic", "setSpeak", "setTtsVoice", "setHandsFree", "voiceHandsFree"):
        assert f'id="{el}"' in html, el
    assert "The microphone is on only while this is open." in html
    assert 'value="browser">The browser\'s built-in recognition (audio is sent to Google or Microsoft)' in html
    assert "state.voiceEngine = store.get(VOICE_LS.engine) === 'browser' ? 'browser' : 'local'" in app     # local unless chosen
    assert "state.handsFree = store.get(VOICE_LS.handsFree) === '1'" in app                                 # off unless chosen
    assert "voice.maxTimer = setTimeout(stopListening, 60000)" in app and "visibilitychange" in app
    assert "/voice/status" in (ROOT / "docs" / "ABHIFY_AI_COMPLETE_DOCUMENTATION.md").read_text(encoding="utf-8")
