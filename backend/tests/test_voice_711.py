"""7.1.1 voice stabilization (Windows desktop + standalone Web): automated checks of the microphone plan, the central orb
and privacy wording. Real-browser checks with a simulated microphone are reported separately (not physical devices)."""
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
APP = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")


def _harness():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "voice_harness.js"), str(ROOT / "frontend" / "app.js")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def test_web_uses_the_browser_recognizer_and_never_asks_for_a_computer():
    r = _harness()["plan711"]
    assert r["web_sr"] == {"use": "browser"} and r["web_sr_engine_browser"] == {"use": "browser"}
    assert "no speech recognition" in r["web_no_sr"]["error"] and "computer" not in r["web_no_sr"]["error"].lower()
    assert r["desktop_default_platform"] == {"use": "local"}                       # the desktop default is unchanged


def test_web_microphone_errors_have_no_windows_advice():
    msgs = _harness()["mic_web"]
    assert all("Windows" not in m for m in msgs) and "Allow the microphone for this site" in msgs[0]


def test_transcription_does_not_depend_on_the_ai_engine():
    body = APP[APP.index("async function startListening("):APP.index("function startWebSpeech(")]
    assert "state.engine !== 'device'" not in body and "transcriberReachable()" in body
    assert "const transcriberReachable = () => PLATFORM !== 'web' && state.apiBase !== null && state.online === true;" in APP


def test_browser_recognition_needs_one_time_consent_that_names_where_audio_goes():
    body = APP[APP.index("async function startListening("):APP.index("function startWebSpeech(")]
    assert "plan.use === 'browser' && !srConsented()" in body and "showVoiceSetup({ setup: 'consent' })" in body
    consent = next(l for l in APP.splitlines() if l.strip().startswith("consent: 'Speech recognition"))
    assert "sent to their speech service" in consent and "record or upload your voice" in consent
    use = APP[APP.index("function useBrowserVoice()"):APP.index("function useBrowserVoice()") + 400]
    assert "store.set(VOICE_LS.srOk, '1')" in use


def test_central_orb_is_a_real_tap_to_talk_button():
    assert "[['#chatOrb', 'dictate'], ['#homeOrb', 'command']]" in APP
    block = APP[APP.index("[['#chatOrb', 'dictate']"):APP.index("[['#chatOrb', 'dictate']") + 500]
    assert "setAttribute('role', 'button')" in block and "tabIndex = 0" in block and "startListening(mode)" in block and "'Enter'" in block


def test_one_microphone_session_at_a_time_and_released():
    body = APP[APP.index("async function startListening("):APP.index("function startWebSpeech(")]
    assert "if (voice.starting) return;" in body and "finally { voice.starting = false; }" in body
    sr = APP[APP.index("function startWebSpeech("):APP.index("async function openMic(")]
    assert "voice.maxTimer = setTimeout(stopListening, 60000)" in sr                  # browser recognition also has the limit
    stop = APP[APP.index("function stopListening("):APP.index("function showVoiceUI(")]
    assert "getTracks().forEach((t) => t.stop())" in stop                             # tracks are stopped
    assert "document.hidden && voice.listening" in APP                                # hidden window stops listening
