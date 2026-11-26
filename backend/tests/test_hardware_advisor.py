"""7.0.2 hardware-aware model advice (desktop). Pure decisions over realistic machines; facts labelled; the catalog
holds registry-verified metadata; nothing downloads or changes without the user's choice; a model becomes the default
only after a real inference test passes."""
import json

import pytest
from fastapi.testclient import TestClient

import hardware_advisor as H

GB = 1024 ** 3


def diag(ram=16.0, avail=8.0, gpus=(), free=100.0, installed=(), reachable=True):
    return {"os": {"system": "Windows", "release": "10", "version": "10.0.22631", "arch": "AMD64"}, "cpu": {"logical_cores": 8},
            "ram": {"total_gb": ram, "available_gb": avail}, "gpus": list(gpus),
            "disk": {"models_free_gb": free, "models_dir": r"C:\Users\x\.ollama\models"},
            "ollama": {"reachable": reachable, "version": "0.40.16" if reachable else None, "installed": True,
                       "models": [{"name": n, "size_gb": 1} for n in installed]}}


GPU12 = {"vendor": "NVIDIA", "name": "RTX A2000 12GB", "vram_gb": 12.0, "vram_used_gb": 0.8, "driver": "581.42"}
GPU6 = {"vendor": "NVIDIA", "name": "RTX 3060 Laptop", "vram_gb": 6.0, "vram_used_gb": 0.3, "driver": "560.94"}


def test_catalog_holds_registry_verified_metadata():
    c = H.catalog()
    names = [m["name"] for m in c["models"]]
    assert [m["name"] for m in c["models"] if not m.get("library_only")] == ["llama3.2:1b", "qwen2.5:1.5b", "llama3.2:3b", "qwen2.5:7b", "qwen2.5:14b"]
    # 7.1.0: Model Library entries (offered, never auto-chosen), read from the registry the same way
    lib = [m for m in c["models"] if m.get("library_only")]
    assert {"llama3.1:8b", "mistral:7b", "gemma2:9b", "deepseek-r1:7b", "qwen2.5-coder:7b"} <= {m["name"] for m in lib} and len(names) == len(set(names))
    assert next(m for m in lib if m["name"] == "qwen2.5:3b")["license"].startswith("Qwen Research")         # not Apache: said so
    for m in c["models"] + [c["embedding"]]:
        assert m["manifest_digest"].startswith("sha256:") and len(m["manifest_digest"]) == 71
        assert m["download_bytes"] > 100_000_000 and m["license"] and m["quantization"]
    q7 = next(m for m in c["models"] if m["name"] == "qwen2.5:7b")
    assert q7["manifest_digest"].endswith("845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e")   # = installed qwen2.5:latest
    assert next(m for m in c["models"] if m["name"] == "llama3.2:1b")["quantization"] == "Q8_0"          # read from its config, not assumed


def test_release_pc_reuses_the_installed_7b_on_its_gpu():
    a = H.advise(diag(15.8, 6.8, [GPU12], 21.7, ["llama3.2:latest", "qwen2.5:latest", "nomic-embed-text:latest"]))
    assert a["primary"]["name"] == "qwen2.5:7b" and a["primary"]["installed"] and a["primary"]["mode"].startswith("GPU")
    assert a["downloads"] == [] and a["download_total_gb"] == 0                                      # nothing to download
    big = next(r for r in a["alternatives"] if r["name"] == "qwen2.5:14b")
    assert big["mode"] == "CPU" and not big["comfortable"]                                            # fits, but not recommended


def test_never_the_largest_model_that_merely_fits():
    a = H.advise(diag(32.0, 20.0, [], 200.0))                                                        # 32 GB, CPU only
    assert a["primary"]["name"] == "qwen2.5:7b"                                                       # 14B fits on CPU, too slow
    assert any(r["name"] == "qwen2.5:14b" and r["compatible"] for r in a["alternatives"])


@pytest.mark.parametrize("machine,expected,mode", [
    (diag(8.0, 3.5, [], 50.0), "qwen2.5:1.5b", "CPU"),                 # 8 GB laptop, CPU only: light, leaves room for Windows
    (diag(16.0, 9.0, [], 50.0), "qwen2.5:7b", "CPU"),                  # 16 GB, CPU only: the Balanced model
    (diag(16.0, 9.0, [GPU6], 50.0), "llama3.2:3b", "GPU"),             # 6 GB GPU: a model that runs on the GPU beats a 7B on CPU
    (diag(16.0, 9.0, [GPU12], 50.0), "qwen2.5:7b", "GPU"),             # 12 GB GPU: 7B on the GPU, not the 14B at 85 % VRAM
])
def test_realistic_machines(machine, expected, mode):
    p = H.advise(machine)["primary"]
    assert p["name"] == expected and p["mode"].startswith(mode)


def test_installed_compatible_model_is_reused_instead_of_a_download():
    a = H.advise(diag(16.0, 9.0, [GPU6], 50.0, ["llama3.2:latest"]))
    assert a["primary"]["name"] == "llama3.2:3b" and a["primary"]["installed"]
    assert [d["model"] for d in a["downloads"]] == ["nomic-embed-text:latest"]                       # only the missing embedder


def test_too_little_memory_or_disk_gives_reasons_not_a_guess():
    a = H.advise(diag(4.0, 1.5, [], 50.0))
    assert a["primary"] is None and a["incompatible"] and all(r["reasons"] for r in a["incompatible"])
    b = H.advise(diag(16.0, 9.0, [], 2.5))                                                           # 2.5 GB free disk
    assert all(not r["compatible"] for r in b["incompatible"]) and any("free disk" in x for r in b["incompatible"] for x in r["reasons"])


def test_device_report_labels_every_fact_and_never_invents():
    facts = H.device_report(diag(15.8, 6.8, [GPU12], 21.7, ["qwen2.5:latest"]),
                            adapters=[{"name": "RTX A2000 12GB", "driver": "x", "reported_memory_gb": 4.0},
                                      {"name": "Intel(R) UHD Graphics P630", "driver": "y", "reported_memory_gb": 1.0}],
                            cpu=("Intel(R) Xeon(R) E-2288G CPU @ 3.70GHz", "verified"))
    by = {f["label"]: f for f in facts}
    assert by["Operating system"]["value"].startswith("Windows 11 (build 10.0.22631)")              # build 22000+ = Windows 11
    assert by["CPU"]["source"] == "verified" and "Xeon" in by["CPU"]["value"]
    assert any(f["label"].startswith("GPU (NVIDIA") and "12.0 GB VRAM" in f["value"] for f in facts)
    igpu = next(f for f in facts if "UHD" in str(f["value"]))
    assert igpu["source"] == "estimate"                                                              # WMI AdapterRAM is capped
    nocpu = H.device_report(diag(), adapters=[], cpu=(None, "unavailable"))
    assert next(f for f in nocpu if f["label"] == "CPU")["source"] == "unavailable"
    assert next(f for f in nocpu if f["label"] == "GPU")["value"] == "not available"


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system

    async def fake_diag():
        return diag(15.8, 6.8, [GPU12], 21.7, ["qwen2.5:latest", "llama3.2:latest", "nomic-embed-text:latest"])
    monkeypatch.setattr(system, "diagnostics", fake_diag)
    monkeypatch.setattr(H, "display_adapters", lambda: [])
    monkeypatch.setattr(H, "cpu_name", lambda: ("Test CPU", "verified"))
    with TestClient(main.app) as c:
        yield c


def test_advice_route_is_read_only_and_local(client, monkeypatch):
    import system_service as system
    calls = []
    monkeypatch.setattr(system, "start_pull", lambda *a, **k: calls.append(a))
    r = client.get("/setup/advice").json()
    assert r["primary"]["name"] == "qwen2.5:7b" and r["device"] and not calls
    assert "Nothing is downloaded" in r["note"] and r["current_default"] == "qwen2.5"


def test_use_model_becomes_default_only_after_a_real_test(client, monkeypatch):
    import system_service as system
    async def resolve(n): return "llama3.2:latest"
    seen = {}
    async def failing(model=None): return {"ok": False, "model": model, "error": "out of memory"}
    async def passing(model=None): return {"ok": True, "model": model, "reply": "OK", "seconds": 1.2}
    async def set_default(n): seen["default"] = n
    monkeypatch.setattr(system, "resolve_chat_model", resolve)
    monkeypatch.setattr(system, "set_default_chat_model", set_default)
    monkeypatch.setattr(system, "test_inference", failing)
    r = client.post("/setup/use-model", json={"model": "llama3.2"}).json()
    assert r["ok"] is False and "out of memory" in r["test"]["error"] and "default" not in seen       # unchanged on failure
    monkeypatch.setattr(system, "test_inference", passing)
    r = client.post("/setup/use-model", json={"model": "llama3.2"}).json()
    assert r["ok"] and seen["default"] == "llama3.2:latest" and r["test"]["reply"] == "OK"


def test_browser_capabilities_are_labelled_and_storage_aware():
    """Web: only what the browser reports, each labelled; WebGPU without an adapter is 'not usable'; a model is offered
    only if browser storage can hold it."""
    import shutil
    import subprocess
    from pathlib import Path
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    root = Path(__file__).resolve().parents[2]
    out = subprocess.run([node, str(Path(__file__).parent / "js" / "capabilities_harness.mjs"), str(root / "frontend")],
                         capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    by = {f["label"]: f for f in r["phone"]["facts"]}
    assert r["phone"]["webgpu"] is True and by["WebGPU"]["value"] == "available"
    assert by["GPU adapter (as reported by the browser)"]["source"] == "estimate" and "adreno" in by["GPU adapter (as reported by the browser)"]["value"]
    assert by["Device memory"]["source"] == "estimate" and "possibly capped" in by["Device memory"]["value"]
    assert by["Storage this site may use"]["value"] == "about 3900 MB" and r["phone"]["storageFreeMB"] == 3900
    nog = {f["label"]: f for f in r["noGpu"]["facts"]}
    assert nog["WebGPU"]["value"] == "not supported by this browser" and nog["Device memory"]["source"] == "unavailable"
    assert nog["Secure context (HTTPS or localhost)"]["value"].startswith("no")
    assert {f["label"]: f for f in r["noAdapter"]["facts"]}["WebGPU"]["value"].startswith("API present, but no usable GPU adapter")
    c = r["choose"]
    assert c["roomy"]["modelId"].startswith("Qwen2.5-1.5B") and c["tight"]["modelId"].startswith("Llama-3.2-1B")
    assert c["full"]["ok"] is False and "store about 500 MB" in c["full"]["reason"]
    assert c["unknown"]["modelId"].startswith("Qwen2.5-1.5B")


def test_catalog_names_map_to_the_names_ollama_has_installed():
    """Seen live: the advice said "qwen2.5:7b" while Ollama lists the same model (same digest) as "qwen2.5:latest"."""
    have = ["qwen2.5:latest", "llama3.2:latest", "nomic-embed-text:latest"]
    assert H.installed_name("qwen2.5:7b", have) == "qwen2.5:latest"
    assert H.installed_name("llama3.2:3b", have) == "llama3.2:latest"
    assert H.installed_name("nomic-embed-text:latest", have) == "nomic-embed-text:latest"
    assert H.installed_name("qwen2.5:14b", have) == "qwen2.5:14b"                     # not installed: unchanged (no false match)
    assert H.installed_name("phi4:14b", have) == "phi4:14b"


def test_library_models_are_offered_but_never_chosen_automatically():
    a = H.advise(diag(15.8, 6.8, [GPU12], 200.0, ["llama3.2:latest", "qwen2.5:latest", "nomic-embed-text:latest"]))
    assert a["primary"]["name"] == "qwen2.5:7b"                                            # unchanged recommendation
    names = {r["name"] for r in a["alternatives"]}
    assert {"llama3.1:8b", "mistral:7b", "deepseek-r1:7b", "qwen2.5-coder:7b"} <= names     # downloadable from the Model Library
    fresh = H.advise(diag(15.8, 6.8, [GPU12], 200.0, []))
    assert not fresh["primary"]["library_only"]
