"""On-device mode (Abhify Lite): the AI runs in the phone's browser with WebLLM, no computer needed.

Covers the vendored engine (pinned hashes, no remote code), the model choice and prompt logic (in Node),
the status texts, consent before the download, and that the PC/companion mode is untouched.
This is logic coverage only: a real phone (WebGPU) run is a separate, manual test.
"""
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
FRONT = ROOT / "frontend"
VENDOR = FRONT / "vendor" / "webllm"
APP_JS = (FRONT / "app.js").read_text(encoding="utf-8")
OD_JS = (FRONT / "ondevice.js").read_text(encoding="utf-8")
NODE = shutil.which("node")

PINNED = {
    "web-llm-0.2.85.js": "341bae95822bfee1d0fd6a0e6cd2db8613bb8edf809390ac142fba36ec17792c",
    "Llama-3.2-1B-Instruct-q4f16_1_cs1k-webgpu.wasm": "2aa9b5f0c8de532f6cbf6bb7b863aaa02645bf6fff856083b202f968712d3f92",
    "Qwen2-1.5B-Instruct-q4f16_1_cs1k-webgpu.wasm": "0fceb50bbaf47efdc31fce96b72c115dcb7f5221c85abe6a9fc02dda9d1d6fc3",
    "LICENSE": "d412ab9d5ac17e6931705aac01e5a0d323da5acd2e89a2c19aa8fc05becc59ad",
}


@pytest.fixture(scope="module")
def r():
    if not NODE:
        pytest.skip("Node.js not installed")
    out = subprocess.run([NODE, str(Path(__file__).parent / "js" / "ondevice_harness.mjs"), str(FRONT)],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


# ------------------------------------------------------------------ vendored engine
@pytest.mark.parametrize("name,sha", PINNED.items())
def test_vendored_files_match_pinned_hashes(name, sha):
    assert hashlib.sha256((VENDOR / name).read_bytes()).hexdigest() == sha


def test_vendored_md_lists_the_same_hashes():
    md = (VENDOR / "VENDORED.md").read_text(encoding="utf-8")
    for name, sha in PINNED.items():
        assert name in md and sha in md, name
    assert sorted(p.name for p in VENDOR.iterdir()) == sorted([*PINNED, "VENDORED.md"])   # nothing unlisted


def test_vendored_files_are_binary_in_git():
    attrs = (ROOT / ".gitattributes").read_text(encoding="utf-8")
    assert "*.wasm binary" in attrs and "frontend/vendor/** -text" in attrs   # byte-exact, hashes stay valid


def test_model_libraries_come_from_this_app_not_a_code_host():
    assert "raw.githubusercontent" not in OD_JS and "cdn." not in OD_JS
    assert "model_lib: new URL(MODELS[modelId].lib, VENDOR).href" in OD_JS
    for lib in re.findall(r"lib: '([^']+\.wasm)'", OD_JS):
        assert (VENDOR / lib).is_file(), lib
    assert re.search(r"import\(new URL\(LIB, VENDOR\)\.href\)", OD_JS)        # the engine itself is local too


def test_engine_is_loaded_lazily_only_in_device_mode():
    assert "import('./ondevice.js')" in APP_JS
    assert not re.search(r"^import ", APP_JS, re.M)                       # app.js stays a classic script
    index = (FRONT / "index.html").read_text(encoding="utf-8")
    assert "ondevice.js" not in index and "web-llm" not in index


def test_service_worker_precaches_only_the_small_module():
    sw = (FRONT / "sw.js").read_text(encoding="utf-8")
    shell = sw[sw.index("const SHELL"):sw.index("];", sw.index("const SHELL"))]
    assert "'./ondevice.js'" in shell
    assert "vendor/webllm" not in shell.split("//")[0] and ".wasm" not in shell   # 17 MB never forced on PC users


# ------------------------------------------------------------------ model choice and prompts
def test_model_choice(r):
    c = r["choose"]
    assert c["no_webgpu"]["ok"] is False and "WebGPU" in c["no_webgpu"]["reason"]
    assert c["low_memory"]["ok"] is False and "2 GB" in c["low_memory"]["reason"]
    assert c["mem_4"]["modelId"] == "Llama-3.2-1B-Instruct-q4f16_1-MLC"
    assert c["unknown_memory"]["modelId"] == "Llama-3.2-1B-Instruct-q4f16_1-MLC"    # iPhone: the light model
    assert c["mem_6"]["modelId"] == c["mem_8"]["modelId"] == "Qwen2.5-1.5B-Instruct-q4f16_1-MLC"


def test_model_sizes_shown_to_the_user(r):
    m = r["models"]
    assert m["Llama-3.2-1B-Instruct-q4f16_1-MLC"]["downloadMB"] == 695      # 695.2 MB measured
    assert m["Qwen2.5-1.5B-Instruct-q4f16_1-MLC"]["downloadMB"] == 869      # 868.5 MB measured


def test_prompt_has_memories_name_and_bounded_history(r):
    msgs = r["messages"]
    assert msgs[0]["role"] == "system" and "Abhi" in msgs[0]["content"] and "I am learning PyTorch" in msgs[0]["content"]
    assert "no internet access" in msgs[0]["content"]
    assert len(msgs) == 1 + 8 + 1 and msgs[-1] == {"role": "user", "content": "What is overfitting?"}
    assert [m["content"] for m in msgs[1:-1]] == [f"m{i}" for i in range(4, 12)]
    assert r["messages_empty"] == [r["messages_empty"][0], {"role": "user", "content": "hi"}]


def test_memory_command(r):
    m = r["memory"]
    assert m["remember that I prefer short answers."] == "I prefer short answers"
    assert m["Remember: my exam is on Friday"] == "my exam is on Friday"
    assert m["please remember I like PyTorch"] == "I like PyTorch"
    assert m["do you remember what I said?"] is None and m["remember"] is None and m["what is a tensor?"] is None


# ------------------------------------------------------------------ status, consent, isolation from PC mode
def test_device_status_texts(r):
    s = r["status"]
    assert s["checking"][1] == "warn"
    assert s["unsupported"][0] == "On-device AI not supported here" and s["unsupported"][1] == "off"
    assert s["not_set_up"][0] == "On-device AI not set up" and "695 MB" in s["not_set_up"][2] and "data-device-setup" in s["not_set_up"][2]
    assert "42%" in s["downloading"][0] and "param cache" in s["downloading"][2]
    assert s["starting"][0] == "Starting the on-device AI…"
    assert s["downloaded_idle"] == ["AI on this device · starts when you chat", "on", ""]
    assert s["ready"] == ["AI ready · runs on this device", "on", ""]
    assert s["failed"][1] == "off" and "<b>" not in s["failed"][2]                 # error text escaped


def test_only_ready_says_ready(r):
    for k, (text, _dot, _b) in r["status"].items():
        assert ("ready" in text.lower()) == (k == "ready"), k


def _fn(name):
    start = APP_JS.index(f"function {name}(")
    i, depth = APP_JS.index(") {", start) + 2, 0                       # the body, not a { destructured } parameter
    while True:
        depth += {"{": 1, "}": -1}.get(APP_JS[i], 0)
        if depth == 0:
            return APP_JS[start:i + 1]
        i += 1


def test_download_needs_confirmation_and_storage_check():
    f = _fn("setupDevice")
    assert f.index("navigator.storage?.estimate") < f.index("confirm(") < f.index("od.load(")
    assert "if (!d.downloaded)" in f and "MB" in f and "huggingface.co/mlc-ai" in f
    assert "persist" in f                                                       # ask the browser not to evict


def test_device_chat_never_calls_the_computer():
    for name in ("deviceChat", "checkDevice", "setupDevice", "loadDeviceHistory", "renderDevice"):
        f = _fn(name)
        assert "api(" not in f and "fetch(" not in f, name
    assert "fetch(" not in OD_JS.replace("prefetch", "")


def test_pc_mode_paths_unchanged():
    assert "if (state.engine === 'device') return checkDevice();" in _fn("checkConnection")
    assert "if (state.engine === 'device') return deviceChat(" in _fn("sendChat")
    assert "engine: PLATFORM === 'web' || store.get(LS.engine) === 'device' ? 'device' : 'pc'" in APP_JS               # default stays the computer
    assert "AI ready · runs on your computer" in _fn("connectionStatus")         # PC statuses not touched
    assert "runs on this device" not in _fn("connectionStatus")


def test_settings_offer_the_choice_and_removal():
    index = (FRONT / "index.html").read_text(encoding="utf-8")
    for id_ in ("setEngine", "deviceInfo", "deviceRemove", "deviceClear"):
        assert f'id="{id_}"' in index, id_
    assert '<option value="pc">' in index and '<option value="device">' in index
    assert "confirm(" in _fn("removeDeviceModel") and "confirm(" in _fn("clearDeviceChats")
