"""
system_service.py: hardware detection, model profiles and model setup.

Nothing here assumes a GPU. NVIDIA GPUs are detected with `nvidia-smi` when it
exists; otherwise the app runs CPU-only. Model downloads never start without an
explicit `confirm=True`, and are refused when the disk doesn't have room.
"""
from __future__ import annotations

import asyncio
import importlib.metadata as md
import json
import logging
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import httpx

import local_file_service as lfs
from config import get_settings
from version import APP_NAME, __version__

log = logging.getLogger(__name__)

GB = 1024 ** 3

# Model profiles are configuration presets, not guarantees: a model "fits" only if
# the machine really has the resources. Sizes are approximate Ollama download sizes.
PROFILES: dict[str, dict[str, Any]] = {
    "minimal": {
        "label": "Minimal", "llm": "qwen2.5:1.5b", "embed": "nomic-embed-text",
        "download_gb": 1.0 + 0.27, "min_ram_gb": 6, "min_vram_gb": 0,
        "note": "Runs on most laptops, CPU-only is fine. Shorter, simpler answers.",
    },
    "balanced": {
        "label": "Balanced", "llm": "qwen2.5", "embed": "nomic-embed-text",       # qwen2.5 = 7B
        "download_gb": 4.7 + 0.27, "min_ram_gb": 12, "min_vram_gb": 6,
        "note": "Good quality and tool use. Comfortable with a 6 GB+ GPU; slow but usable on CPU with 16 GB RAM.",
    },
    "quality": {
        "label": "Quality", "llm": "qwen2.5:14b", "embed": "nomic-embed-text",
        "download_gb": 9.0 + 0.27, "min_ram_gb": 24, "min_vram_gb": 11,
        "note": "Best answers. Needs a GPU with about 11 GB+ VRAM (or 32 GB RAM and patience on CPU).",
    },
}


# --------------------------------------------------------------------------- detection
def _run(cmd: list[str], timeout: float = 5) -> str | None:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return out.stdout.strip() if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def detect_gpus() -> list[dict[str, Any]]:
    """NVIDIA GPUs via nvidia-smi (empty list when absent)."""
    if not shutil.which("nvidia-smi"):
        return []
    out = _run(["nvidia-smi", "--query-gpu=name,memory.total,memory.used,driver_version",
                "--format=csv,noheader,nounits"])
    gpus = []
    for line in (out or "").splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) >= 4:
            try:
                gpus.append({"vendor": "NVIDIA", "name": parts[0], "vram_gb": round(int(parts[1]) / 1024, 1),
                             "vram_used_gb": round(int(parts[2]) / 1024, 1), "driver": parts[3]})
            except ValueError:
                continue
    return gpus


def _ram() -> dict[str, float | None]:
    try:
        import psutil
        vm = psutil.virtual_memory()
        return {"total_gb": round(vm.total / GB, 1), "available_gb": round(vm.available / GB, 1)}
    except ImportError:
        return {"total_gb": None, "available_gb": None}


def _cuda_for_whisper() -> bool:
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def ollama_models_dir() -> Path:
    env = os.environ.get("OLLAMA_MODELS")
    return Path(env) if env else Path.home() / ".ollama" / "models"


def internet_available(_cache: dict = {"at": 0.0, "ok": False}) -> bool:
    """Cheap connectivity probe (DNS server TCP connect), cached for 60 s."""
    if time.time() - _cache["at"] < 60:
        return _cache["ok"]
    ok = False
    for host in ("1.1.1.1", "8.8.8.8"):
        try:
            with socket.create_connection((host, 53), timeout=1.5):
                ok = True
                break
        except OSError:
            continue
    _cache.update(at=time.time(), ok=ok)
    return ok


async def ollama_status() -> dict[str, Any]:
    url = get_settings().ollama_base_url
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            tags = (await client.get(f"{url}/api/tags")).json().get("models", [])
            try:
                version = (await client.get(f"{url}/api/version")).json().get("version")
            except Exception:
                version = None
        return {"reachable": True, "url": url, "version": version,
                "models": [{"name": m["name"], "size_gb": round(m.get("size", 0) / GB, 2)} for m in tags]}
    except Exception as e:
        return {"reachable": False, "url": url, "version": None, "models": [],
                "error": f"{type(e).__name__}", "installed": bool(shutil.which("ollama"))}


def _pkg(name: str) -> str | None:
    try:
        return md.version(name)
    except md.PackageNotFoundError:
        return None


def model_installed(name: str, installed: list[str]) -> bool:
    """'qwen2.5' matches 'qwen2.5:latest'; 'qwen2.5:7b' must match exactly or by digest-equivalent tag."""
    full = name if ":" in name else f"{name}:latest"
    return full in installed or name in installed


async def diagnostics() -> dict[str, Any]:
    """Everything the setup wizard and the System panel need, in one call."""
    import database as db
    ollama = await ollama_status()
    root = lfs.root()
    gpus = detect_gpus()
    ram = _ram()
    settings = lfs.load_settings()
    db_ok, schema = True, None
    try:
        with db.connect() as c:
            schema = db.current_version(c)
    except Exception:
        db_ok = False
    chroma_ok = lfs.chroma_dir().exists() and lfs.is_writable(lfs.chroma_dir())
    return {
        "app": {"name": APP_NAME, "version": __version__, "schema_version": schema, "schema_target": db.SCHEMA_VERSION},
        "os": {"system": platform.system(), "release": platform.release(), "version": platform.version(),
               "arch": platform.machine()},
        "python": {"version": sys.version.split()[0], "executable": sys.executable},
        "cpu": {"name": platform.processor() or platform.machine(), "logical_cores": os.cpu_count()},
        "ram": ram,
        "gpus": gpus,
        "cuda_whisper": _cuda_for_whisper(),
        "disk": {
            "data_root": str(root),
            "data_free_gb": round(lfs.free_bytes(root) / GB, 1),
            "data_used_mb": round(lfs.dir_size(lfs.data_dir()) / 1024 ** 2, 1),
            "models_dir": str(ollama_models_dir()),
            "models_free_gb": round(lfs.free_bytes(ollama_models_dir()) / GB, 1),
        },
        "ollama": ollama,
        "storage": {"sqlite_ok": db_ok, "chroma_ok": chroma_ok},
        "internet": internet_available(),
        "packages": {p: _pkg(p) for p in ("fastapi", "langgraph", "langchain-core", "langchain-ollama", "chromadb",
                                          "faster-whisper", "ddgs", "pypdf", "psutil")},
        "settings": settings,
        "recommended_profile": recommend_profile(ram.get("total_gb"), gpus),
    }


def recommend_profile(ram_gb: float | None, gpus: list[dict[str, Any]]) -> dict[str, Any]:
    vram = max((g["vram_gb"] for g in gpus), default=0)
    ram = ram_gb or 8
    if vram >= 6 or ram >= 16:
        key, why = "balanced", (f"NVIDIA GPU with {vram} GB VRAM detected" if vram >= 6 else f"{ram} GB RAM, CPU mode")
    else:
        key, why = "minimal", f"{ram} GB RAM and no suitable GPU detected"
    return {"profile": key, "reason": why,
            "quality_possible": vram >= PROFILES["quality"]["min_vram_gb"]}


# --------------------------------------------------------------------------- active models
def active_models() -> dict[str, str]:
    """Model names in use: env vars (if set) override settings.json."""
    s = lfs.load_settings()
    return {"llm": os.environ.get("LLM_MODEL") or s["models"]["llm"],
            "embed": os.environ.get("EMBED_MODEL") or s["models"]["embed"]}


def apply_profile(profile: str) -> dict[str, Any]:
    if profile not in PROFILES:
        raise ValueError(f"Unknown profile '{profile}'")
    p = PROFILES[profile]
    s = lfs.update_settings(model_profile=profile, models={"llm": p["llm"], "embed": p["embed"]})
    _reset_model_caches()
    return s


def _reset_model_caches() -> None:
    """Rebuild LLM clients so a profile/model change takes effect without a restart."""
    import agent, knowledge
    agent.get_llm.cache_clear()
    agent.get_agent.cache_clear()
    knowledge.get_vectorstore.cache_clear()


# --------------------------------------------------------------------------- downloads
PULLS: dict[str, dict[str, Any]] = {}       # model -> progress state (in-process)


def estimate_download_gb(model: str) -> float:
    for p in PROFILES.values():
        if model == p["llm"]:
            return p["download_gb"] - 0.27
        if model == p["embed"]:
            return 0.27
    return 5.0                               # unknown model: assume a typical 7B download


def check_disk_for(model: str) -> dict[str, Any]:
    need = estimate_download_gb(model) * 1.15 + 0.5          # 15% + 0.5 GB headroom
    free = lfs.free_bytes(ollama_models_dir()) / GB
    return {"model": model, "estimated_download_gb": round(estimate_download_gb(model), 2),
            "required_free_gb": round(need, 2), "free_gb": round(free, 2), "ok": free >= need,
            "models_dir": str(ollama_models_dir())}


async def start_pull(model: str, confirm: bool) -> dict[str, Any]:
    if not confirm:
        raise ValueError("Downloading a model needs explicit confirmation (confirm=true).")
    disk = check_disk_for(model)
    if not disk["ok"]:
        raise ValueError(f"Not enough disk space for {model}: about {disk['required_free_gb']} GB needed, "
                         f"{disk['free_gb']} GB free in {disk['models_dir']}.")
    st = PULLS.get(model)
    if st and st["status"] == "downloading":
        return st
    PULLS[model] = {"model": model, "status": "downloading", "completed": 0, "total": 0, "message": "starting"}
    asyncio.create_task(_pull_worker(model))
    return PULLS[model]


async def _pull_worker(model: str) -> None:
    st = PULLS[model]
    try:
        async with httpx.AsyncClient(timeout=None) as client:
            async with client.stream("POST", f"{get_settings().ollama_base_url}/api/pull",
                                     json={"model": model, "stream": True}) as r:
                async for line in r.aiter_lines():
                    if not line:
                        continue
                    ev = json.loads(line)
                    if "error" in ev:
                        raise RuntimeError(ev["error"])
                    st["message"] = ev.get("status", "")
                    if ev.get("total"):
                        st["total"], st["completed"] = ev["total"], ev.get("completed", 0)
        st["status"] = "done"
    except Exception as e:
        st.update(status="failed", message=str(e))
        log.warning("Model pull failed for %s: %s", model, e)


async def test_inference(model: str | None = None) -> dict[str, Any]:
    model = model or active_models()["llm"]
    t = time.time()
    try:
        async with httpx.AsyncClient(timeout=180) as client:
            r = await client.post(f"{get_settings().ollama_base_url}/api/generate",
                                  json={"model": model, "prompt": "Reply with exactly: OK", "stream": False,
                                        "options": {"num_predict": 8}})
            r.raise_for_status()
            text = r.json().get("response", "").strip()
        return {"ok": True, "model": model, "reply": text[:40], "seconds": round(time.time() - t, 1)}
    except Exception as e:
        return {"ok": False, "model": model, "error": f"{type(e).__name__}: {e}"[:300]}


# --------------------------------------------------------------------------- update check (no auto-install)
_HEX64 = set("0123456789abcdef")


async def check_for_update() -> dict[str, Any]:
    """Compare the installed version with a release manifest (if one is configured).

    Manifest format (JSON, served over HTTPS):
      {"version": "2.1.0", "released": "2026-11-01", "notes": "...", "min_supported": "2.0.0",
       "windows": {"url": "https://.../Abhify.AI-Setup-2.1.0.exe", "sha256": "<64 hex>"}}
    Nothing is downloaded or installed: the user downloads the installer and can
    verify the SHA-256 shown here before running it.
    """
    from packaging.version import InvalidVersion, Version
    cfg = lfs.load_settings().get("updates", {})
    url = cfg.get("manifest_url")
    base = {"current": __version__, "configured": bool(url)}
    if not url:
        return {**base, "update_available": False, "message": "No update source configured."}
    if not str(url).startswith("https://"):
        return {**base, "update_available": False, "error": "The update manifest URL must use HTTPS."}
    if not internet_available():
        return {**base, "update_available": False, "error": "Offline: can't check for updates."}
    try:
        async with httpx.AsyncClient(timeout=8, follow_redirects=True) as client:
            r = await client.get(url)
            r.raise_for_status()
            if len(r.content) > 64 * 1024:
                raise ValueError("manifest too large")
            m = r.json()
        latest = Version(str(m["version"]))
        win = m.get("windows") or {}
        dl, sha = str(win.get("url", "")), str(win.get("sha256", "")).lower()
        if not dl.startswith("https://") or len(sha) != 64 or not set(sha) <= _HEX64:
            raise ValueError("manifest has an invalid download URL or checksum")
        current = Version(__version__)
        return {**base, "update_available": latest > current, "latest": str(m["version"]),
                "released": m.get("released"), "notes": str(m.get("notes", ""))[:5000],
                "download_url": dl, "sha256": sha,
                "upgrade_supported": Version(str(m.get("min_supported", "0"))) <= current}
    except (InvalidVersion, KeyError, ValueError, TypeError) as e:
        return {**base, "update_available": False, "error": f"Invalid update manifest: {e}"}
    except Exception as e:
        return {**base, "update_available": False, "error": f"Update check failed ({type(e).__name__})"}
