"""7.1.0-rc.2 (Windows desktop + Web): fixes from the owner's real-device report. Automated checks only."""
import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
APP = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")


def test_whisper_silence_phrases_are_not_treated_as_speech():
    import transcribe as tr
    assert not tr.keep_segment(" Thank you.", 0.5, -0.7)        # classic silence hallucination, low confidence
    assert not tr.keep_segment("you", 0.4, -0.5)
    assert not tr.keep_segment("Hello how are you", 0.8, -1.2)   # Whisper itself says: probably no speech
    assert tr.keep_segment("Thank you.", 0.05, -0.2)             # a real, clear "thank you" is kept
    assert tr.keep_segment("What is the capital of France?", 0.1, -0.3)
    assert not tr.keep_segment("  ", 0.0, 0.0)
    src = (ROOT / "backend" / "transcribe.py").read_text(encoding="utf-8")
    assert "MIN_SPEECH_S" in src and "duration_after_vad" in src and '"speech":' in src


def test_microphone_is_never_swallowed_by_a_greeting_command():
    body = APP[APP.index("function voiceCommand(raw)"):APP.index("function deliverTranscript(raw)")]
    assert "speak('How can I help?')" not in body                                    # the old unsolicited greeting
    assert re.search(r"if \(!/\^\(\?:open\|go to\|show", body)                     # commands must start with a command word


def test_no_sound_is_reported_with_the_device_name_and_a_test():
    # 7.2.1: the verdict comes from micVerdict (silent / very quiet / quiet / ok); the silent message names the device
    assert "micVerdict(voice.peak, voice.meterRan)" in APP and "micSilentMessage(voice.micLabel)" in APP
    assert "Test microphone" in APP and "async function testMicrophone()" in APP
    html = (ROOT / "frontend" / "index.html").read_text(encoding="utf-8")
    assert 'id="micTest"' in html and 'id="micTestBar"' in html


def test_new_app_code_runs_right_after_an_update():
    assert "navigator.serviceWorker.addEventListener('controllerchange'" in APP and "location.reload()" in APP
    assert "if (!hadController) return;" in APP                                      # not on the very first install


def test_online_providers_are_offered_in_the_model_picker():
    assert "＋ Add Gemini, Groq or OpenRouter" in APP and 'data-model-act="providers"' in APP
    assert "Download more models…" in APP


def test_model_library_lists_verified_models_to_download():
    assert "function renderModelAvail()" in APP and "data-avail-act=\"pull\"" in APP
    html = (ROOT / "frontend" / "index.html").read_text(encoding="utf-8")
    assert 'id="modelLibAvail"' in html


def _launcher():
    spec = importlib.util.spec_from_file_location("launcher_t", ROOT / "desktop" / "launcher.pyw")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def test_desktop_opens_as_an_app_window(data_root):
    L = _launcher()
    assert L.ui_command("http://127.0.0.1:8000/", "app", r"C:\Edge\msedge.exe") == [r"C:\Edge\msedge.exe", "--app=http://127.0.0.1:8000/", "--new-window"]
    assert L.ui_command("http://127.0.0.1:8000/", "browser", r"C:\Edge\msedge.exe") is None
    assert L.ui_command("http://127.0.0.1:8000/", "app", None) is None                # no Edge/Chrome: the default browser
    assert L.window_mode() == "app"                                                    # default
    L.set_window_mode("browser"); assert L.window_mode() == "browser"
    L.set_window_mode("app"); assert L.window_mode() == "app"
    import local_file_service as lfs
    lfs.update_settings(desktop={"last_port": 8123})
    L.set_window_mode("browser")
    assert lfs.load_settings()["desktop"]["last_port"] == 8123                         # other desktop settings kept


def test_installer_has_an_update_mode():
    nsi = (ROOT / "packaging" / "windows" / "installer.nsi").read_text(encoding="utf-8")
    assert "Function SkipWhenUpdating" in nsi and nsi.count("MUI_PAGE_CUSTOMFUNCTION_PRE SkipWhenUpdating") == 2
    assert 'StrCpy $WelcomeTitle "Update ${APPNAME} to ${VERSION}"' in nsi
    assert "--backup pre-upgrade" in nsi and "--stop" in nsi                          # data backed up, app stopped first
    assert "RMDir /r \"$INSTDIR\\runtime\"" in nsi and "AbhifyAI" not in nsi.split("Section \"Uninstall\"")[0].split("RMDir")[1][:80]   # program files only


def test_web_microphone_harness_still_passes():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "voice_harness.js"), str(ROOT / "frontend" / "app.js")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    assert json.loads(out.stdout)["plan711"]["web_sr"] == {"use": "browser"}
