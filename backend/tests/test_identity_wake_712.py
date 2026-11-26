"""7.1.0-dev.2: assistant identity, voice profile, tool routing (performance) and the Windows wake phrase.
Shared identity cases with the web app and Android: tests/data/identity_cases.json."""
import asyncio
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

import identity

ROOT = Path(__file__).resolve().parents[2]
CASES = json.loads((Path(__file__).parent / "data" / "identity_cases.json").read_text(encoding="utf-8"))


@pytest.fixture
def client(migrated):
    import main
    return TestClient(main.app)


# --------------------------------------------------------------------------- identity
@pytest.mark.parametrize("case", CASES["names"], ids=[repr(c["in"])[:30] for c in CASES["names"]])
def test_shared_name_rules(case):
    if case["out"] is None:
        with pytest.raises(ValueError):
            identity.validate(case["in"])
    else:
        assert identity.validate(case["in"]) == case["out"]


@pytest.mark.parametrize("case", CASES["questions"], ids=[c["q"][:40] for c in CASES["questions"]])
def test_shared_identity_questions(case):
    a = identity.answer(case["q"], "Nova")
    got = None if a is None else ("rename" if a.startswith("Yes.") else "name")
    assert got == case["a"]
    if a:
        assert "Nova" in a and ("Abhify.AI" in a or got == "rename")


def test_name_is_saved_locally_and_used_everywhere(client):
    assert client.get("/identity").json()["name"] == "Abhify"
    r = client.put("/preferences", json={"assistant_name": "  Nova "})
    assert r.status_code == 200 and r.json()["assistant_name"] == "Nova"
    d = client.get("/identity").json()
    assert d["name"] == "Nova" and d["wake_phrases"] == ["Hey Nova", "Hello Nova", "Nova"] and "Jarvis" in d["suggestions"]
    assert client.put("/preferences", json={"assistant_name": "<script>"}).status_code == 422
    assert client.put("/preferences", json={"assistant_name": ""}).status_code == 422
    assert client.get("/identity").json()["name"] == "Nova"                          # a refused name changes nothing


def test_prompts_use_the_current_name_not_a_hardcoded_one():
    import agent
    p = identity.named(agent.SYSTEM_PROMPT, "Nova")
    assert p.startswith("You are Nova, the user's AI assistant in the Abhify.AI app") and "You are Abhify.AI" not in p
    assert identity.named(agent.CLOUD_PROMPT, "Abhify").startswith("You are Abhify, the AI assistant of the Abhify.AI app")
    assert identity.named(agent.GUEST_PROMPT, "Echo").startswith("You are Echo")


def test_identity_question_is_answered_by_the_app(client, monkeypatch):
    import agent
    client.put("/preferences", json={"assistant_name": "Nova"})
    for name in ("get_agent", "_live_agent", "_mode_agent"):
        monkeypatch.setattr(agent, name, lambda *a, **k: (_ for _ in ()).throw(AssertionError("no model for identity questions")))
    r = client.post("/chat", json={"message": "What is your name?"}).json()
    assert r["answer"].startswith("I'm **Nova**") and r["model_command"]["kind"] == "identity"
    evs = [json.loads(x) for x in client.post("/chat/stream", json={"message": "who are you?"}).text.splitlines() if x.strip()]
    assert [e["type"] for e in evs] == ["token", "done"] and "Nova" in evs[0]["text"]
    import memory_service as mem
    assert mem.recent_messages(r["thread_id"])[-1]["content"] == r["answer"]       # saved like any answer


def test_voice_profile_is_validated_and_stored(client):
    ok = {"style": "female", "voiceURI": "Microsoft Zira - English (United States)", "lang": "en-US", "rate": 1.1, "pitch": 1.0, "volume": 0.9}
    assert client.put("/preferences", json={"voice_profile": ok}).json()["voice_profile"] == ok
    assert client.put("/preferences", json={"voice_profile": {"evil": "x"}}).status_code == 422
    assert client.put("/preferences", json={"voice_profile": {"rate": 99}}).json()["voice_profile"]["rate"] == 2.0      # clamped


# --------------------------------------------------------------------------- tool routing (performance)
@pytest.mark.tool_routing
@pytest.mark.parametrize("q,tools", [("Explain gradient descent in two sentences.", False), ("What is overfitting?", False), ("hello", False),
                                     ("Write a Python function to reverse a list", False), ("Remember that I prefer short answers", True),
                                     ("What do you know about me?", True), ("Search the web for Python 3.15 features", True),
                                     ("Summarise my notes on Kotlin", True), ("Who won the 2026 world cup?", True)])
def test_tools_only_when_asked_for(q, tools):
    import agent
    import intelligence
    assert agent.needs_tools(q, intelligence.detect(q)) is tools


@pytest.mark.tool_routing
def test_general_question_runs_without_the_tool_round(monkeypatch, migrated):
    """Measured live: the tool round made llama3.2 web-search "Explain gradient descent" (first token 4.8 s instead of 0.03 s)."""
    import agent
    used = []

    class Plain:
        async def ainvoke(self, inputs, config=None):
            used.append("plain")
            return {"messages": [*inputs["messages"], AIMessage("Gradient descent…")]}

    class Tools:
        async def ainvoke(self, *a, **k):
            raise AssertionError("tool agent used")
    monkeypatch.setattr(agent, "_live_agent", lambda *a, **k: Plain())
    monkeypatch.setattr(agent, "get_agent", lambda *a, **k: Tools())
    monkeypatch.setattr(agent, "record_inference", lambda *a, **k: None)
    monkeypatch.setattr(agent, "active_models", lambda: {"llm": "qwen2.5", "embed": "x"})
    r = asyncio.run(agent.run_agent("Explain gradient descent in two sentences.", None))
    assert used == ["plain"] and r["tools_used"] == []


# --------------------------------------------------------------------------- wake phrase (Windows)
def test_wake_is_off_by_default_and_needs_confirmation(client, monkeypatch):
    import wakeword
    monkeypatch.setattr(wakeword, "availability", lambda: {"supported": True, "why": None, "languages": ["en-US"]})
    st = client.get("/voice/wake").json()
    assert st["settings"]["enabled"] is False and st["state"] == "off"
    assert client.put("/voice/wake", json={"enabled": True}).status_code == 400            # no confirm
    st = client.put("/voice/wake", json={"enabled": True, "confirm": True}).json()
    from version import __version__
    assert st["settings"]["enabled"] and st["settings"]["confirmed_version"] == __version__
    client.put("/voice/wake", json={"enabled": False})


def test_wake_phrases_follow_the_name_and_compatibility_is_checked(client, monkeypatch):
    import wakeword
    client.put("/preferences", json={"assistant_name": "Nova"})
    assert wakeword.phrases() == ["Hey Nova", "Hello Nova"]
    assert wakeword.phrases({"use_name": True, "name_only": True}) == ["Hey Nova", "Hello Nova", "Nova"]
    assert wakeword.phrases({"use_name": False, "phrase": "Okay computer"}) == ["Okay computer"]
    assert "Latin letters" in wakeword.compatibility(["Hey अभि"]) and "too short" in wakeword.compatibility(["Neo"])
    assert wakeword.compatibility(["Hey Nova"]) is None
    monkeypatch.setattr(wakeword, "availability", lambda: {"supported": False, "why": "not installed"})
    assert client.put("/voice/wake", json={"enabled": True, "confirm": True}).status_code == 409


def test_wake_listens_only_while_a_visible_page_and_never_after_an_update(migrated, monkeypatch):
    import wakeword
    monkeypatch.setattr(wakeword, "availability", lambda: {"supported": True, "why": None})
    e = wakeword.Engine()
    wakeword.save({"enabled": True, "confirmed_version": "0.0.0-old"})
    e.last_visible = __import__("time").time()
    assert e.wanted() == (False, "paused_after_update")                                   # an update never re-enables it silently
    from version import __version__
    wakeword.save({"confirmed_version": __version__})
    assert e.wanted() == (True, "listening")
    e.last_visible -= 60
    assert e.wanted() == (False, "page_hidden")                                           # no visible page: no listening
    e.last_visible += 60
    e.paused_until = float("inf")
    assert e.wanted() == (False, "paused")


def test_wake_routes_are_this_computer_only():
    import companion
    for r in ("/voice/wake", "/voice/wake/test", "/voice/wake/pause", "/voice/wake/resume", "/identity"):
        assert r not in companion.REMOTE_ROUTES and not r.startswith(companion.REMOTE_PREFIXES)


@pytest.mark.skipif(sys.platform != "win32" or not shutil.which("powershell"), reason="Windows speech recognizer")
def test_real_windows_recognizer_detects_the_phrase_and_ignores_other_speech(tmp_path):
    """Integration with the real offline recognizer on generated speech (clean audio; a room microphone is harder)."""
    import wakeword
    if not wakeword.availability()["supported"]:
        pytest.skip("no en-US recognizer installed")
    files = {"pos": tmp_path / "pos.wav", "neg": tmp_path / "neg.wav"}
    texts = {"pos": "Hello Nova", "neg": "Hey Nora, please open the news tab."}
    script = "; ".join(f"$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.SelectVoice('Microsoft David Desktop'); $s.SetOutputToWaveFile('{files[k]}'); "
                       f"$pb=New-Object System.Speech.Synthesis.PromptBuilder; $pb.AppendBreak([TimeSpan]::FromMilliseconds(500)); $pb.AppendText('{texts[k]}'); "
                       f"$pb.AppendBreak([TimeSpan]::FromMilliseconds(800)); $s.Speak($pb); $s.Dispose()" for k in files)
    subprocess.run(["powershell", "-NoProfile", "-Command", "Add-Type -AssemblyName System.Speech; " + script], check=True, timeout=60)
    ph = ["Hey Nova", "Hello Nova"]
    best = lambda f: max((r["confidence"] for r in wakeword.recognize_file(str(f), ph) if r["grammar"] == "wake"), default=0.0)
    assert best(files["pos"]) >= wakeword.SENSITIVITY["medium"]
    assert best(files["neg"]) < wakeword.SENSITIVITY["high"]


# --------------------------------------------------------------------------- web app helpers (Node harness)
def test_web_identity_and_voice_helpers():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "identity_harness.js"), str(ROOT / "frontend" / "app.js"),
                          str(Path(__file__).parent / "data" / "identity_cases.json")], capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    assert r["name_mismatches"] == [] and r["question_mismatches"] == []
    assert r["gender"] == ["female", "male", "female", "male", None]
    assert r["female_voice"] == "Microsoft Zira - English (United States)" and r["male_voice"] == "Microsoft David - English (United States)"
    assert r["hindi_voice"] == "Microsoft Hemant" and r["kept_choice"] == "Google UK English Male"
    assert r["robot"] == {"rate": 0.9, "pitch": 0.4, "volume": 1} and r["custom"] == {"rate": 1.3, "pitch": 1.4, "volume": 0.5}
    assert r["clamped"] == {"rate": 2, "pitch": 0, "volume": 1}
