"""Hardware-adaptive setup: recommendations, disk checks, consent, missing Ollama."""
import asyncio

import pytest

import local_file_service as lfs
import system_service as system

A2000 = [{"vendor": "NVIDIA", "name": "RTX A2000 12GB", "vram_gb": 12.0, "vram_used_gb": 1, "driver": "x"}]


@pytest.mark.parametrize("ram,gpus,expected", [
    (8, [], "minimal"),                 # small laptop, no GPU
    (16, [], "balanced"),               # CPU-only but enough RAM
    (32, A2000, "balanced"),            # the development machine
    (8, [{"vram_gb": 4.0}], "minimal"),  # small GPU
])
def test_profile_recommendation(ram, gpus, expected):
    assert system.recommend_profile(ram, gpus)["profile"] == expected


def test_quality_flagged_only_with_enough_vram():
    assert system.recommend_profile(32, A2000)["quality_possible"] is True
    assert system.recommend_profile(64, [])["quality_possible"] is False


def test_disk_check_blocks_unsafe_download(data_root, monkeypatch):
    monkeypatch.setattr(lfs, "free_bytes", lambda p: 2 * system.GB)
    chk = system.check_disk_for("qwen2.5")                     # ~4.7 GB model
    assert chk["ok"] is False and chk["required_free_gb"] > 4.7
    with pytest.raises(ValueError, match="Not enough disk space"):
        asyncio.run(system.start_pull("qwen2.5", confirm=True))


def test_download_requires_consent(data_root):
    with pytest.raises(ValueError, match="confirmation"):
        asyncio.run(system.start_pull("qwen2.5:1.5b", confirm=False))


def test_missing_ollama_is_reported_not_raised(monkeypatch):
    from config import Settings
    monkeypatch.setattr(system, "get_settings", lambda: Settings(ollama_base_url="http://127.0.0.1:9"))
    st = asyncio.run(system.ollama_status())
    assert st["reachable"] is False and st["models"] == []


def test_diagnostics_without_ollama(migrated, monkeypatch):
    from config import Settings
    monkeypatch.setattr(system, "get_settings", lambda: Settings(ollama_base_url="http://127.0.0.1:9"))
    d = asyncio.run(system.diagnostics())
    assert d["ollama"]["reachable"] is False
    assert d["storage"]["sqlite_ok"] is True and d["app"]["schema_version"] == d["app"]["schema_target"]
    assert d["recommended_profile"]["profile"] in system.PROFILES


def test_model_name_matching():
    installed = ["qwen2.5:latest", "nomic-embed-text:latest"]
    assert system.model_installed("qwen2.5", installed)
    assert system.model_installed("nomic-embed-text", installed)
    assert not system.model_installed("qwen2.5:14b", installed)


def test_profile_and_env_override(data_root, monkeypatch):
    monkeypatch.setattr(system, "_reset_model_caches", lambda: None)
    system.apply_profile("minimal")
    assert system.active_models() == {"llm": "qwen2.5:1.5b", "embed": "nomic-embed-text"}
    monkeypatch.setenv("LLM_MODEL", "llama3.2")
    assert system.active_models()["llm"] == "llama3.2"
    with pytest.raises(ValueError):
        system.apply_profile("ultra")


def test_news_same_story_detection():
    import generators as g
    seen = [g._title_words("Meta unveils Muse Wonder and Muse Wonder Full, its new AI models")]
    assert g._is_same_story("Meta claims AI frontier with Muse Wonder and paywalls model", seen)
    assert g._is_same_story("Meta says new Muse model hits AI frontier after aggressive push", seen)
    assert not g._is_same_story("PyTorch 2.14 released with faster compile times", seen)
