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


_NET: dict[str, Any] = {"at": 0.0, "ok": False, "known": False, "refreshing": False}


def _probe_internet() -> bool:
    for host in ("1.1.1.1", "8.8.8.8"):
        try:
            with socket.create_connection((host, 53), timeout=1.5):
                return True
        except OSError:
            continue
    return False


def internet_available() -> bool:
    """Connectivity (DNS-server TCP connect), cached for 60 s.

    Only the very first call waits for the probe. Afterwards a stale value is refreshed in the
    background and the last known answer is returned immediately: offline, connect attempts
    can take seconds to time out and must never slow /health or chat down."""
    import threading

    def refresh():
        try:
            _NET.update(ok=_probe_internet(), at=time.time(), known=True)
        finally:
            _NET["refreshing"] = False

    if not _NET["known"]:
        refresh()
    elif time.time() - _NET["at"] >= 60 and not _NET["refreshing"]:
        _NET["refreshing"] = True
        threading.Thread(target=refresh, daemon=True).start()
    return _NET["ok"]


async def ollama_status() -> dict[str, Any]:
    url = get_settings().ollama_base_url
    try:
        async with httpx.AsyncClient(timeout=2) as client:
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
    comp = settings.get("companion") or {}
    settings = {**settings, "companion": {"enabled": bool(comp.get("enabled")), "created_at": comp.get("created_at")}}
    db_ok, schema = True, None                      # ^ the token hash is never returned by any endpoint
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


# --------------------------------------------------------------------------- chat model choice (dev.8)
_EMBED_HINTS = ("embed", "bge-", "bge:", "minilm", "e5-")


def is_chat_model(tag: dict) -> bool:
    """Ollama lists embedding models next to chat models; only chat models can answer in chat."""
    name = tag.get("name", "").lower()
    d = tag.get("details") or {}
    families = [str(f).lower() for f in (d.get("families") or [])] + [str(d.get("family") or "").lower()]
    return not any(h in name for h in _EMBED_HINTS) and not any("bert" in f for f in families)


async def chat_models() -> dict[str, Any]:
    """The chat models really installed in Ollama (from /api/tags; embedding models left out) and the default.
    Nothing is invented: when Ollama is down the list is empty and `ollama` is False."""
    active = active_models()["llm"]
    locked = bool(os.environ.get("LLM_MODEL"))
    url = get_settings().ollama_base_url
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            tags = (await client.get(f"{url}/api/tags")).json().get("models", [])
    except Exception as e:
        return {"ollama": False, "default": active, "default_installed": False, "locked": locked, "models": [],
                "error": f"Ollama isn't reachable ({type(e).__name__}). Start Ollama and try again."}
    names = [t["name"] for t in tags]
    loaded = await _loaded_models(url)
    chat = [t for t in tags if is_chat_model(t)]
    caps = await asyncio.gather(*[_capabilities(url, t["name"], t.get("digest", "")) for t in chat])
    models = [{"name": t["name"], "size_gb": round(t.get("size", 0) / GB, 2),
               "parameter_size": (t.get("details") or {}).get("parameter_size"),
               "quantization": (t.get("details") or {}).get("quantization_level"),
               "family": (t.get("details") or {}).get("family"),
               "default": model_installed(active, [t["name"]]),
               "installed": True,
               "loaded": any(model_installed(t["name"], [n]) for n in loaded),          # in Ollama's memory right now (/api/ps)
               "capabilities": c,                                                      # Ollama's own list (None: this Ollama doesn't say)
               "use": recommended_use(t["name"])}
              for t, c in zip(chat, caps)]
    return {"ollama": True, "default": active, "default_installed": model_installed(active, names), "locked": locked, "models": models}


async def _loaded_models(url: str) -> list[str]:
    """Models Ollama has in memory now (/api/ps); [] when it can't say."""
    try:
        async with httpx.AsyncClient(timeout=2) as client:
            return [m.get("name", "") for m in (await client.get(f"{url}/api/ps")).json().get("models", [])]
    except Exception:                                        # noqa: BLE001 - informational only
        return []


_CAPS: dict[str, list[str] | None] = {}


async def _capabilities(url: str, name: str, digest: str) -> list[str] | None:
    """What the model can do, from Ollama's /api/show "capabilities" (e.g. completion, tools, vision, thinking), cached per
    digest. None when this Ollama doesn't report it: nothing is assumed (no "vision" unless Ollama says so)."""
    key = f"{name}@{digest}"
    if key in _CAPS:
        return _CAPS[key]
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            caps = (await client.post(f"{url}/api/show", json={"model": name})).json().get("capabilities")
        _CAPS[key] = [str(c) for c in caps] if isinstance(caps, list) else None
    except Exception:                                        # noqa: BLE001
        return None
    return _CAPS[key]


# Short, honest notes for well-known families (shown as "good for"); unknown models get none rather than a guess.
_USE = (("qwen2.5-coder", "Coding"), ("qwen2.5", "All-round: reasoning, writing, code, many languages"), ("qwen3", "All-round with step-by-step thinking"),
        ("llama3.2", "Fast and light: quick answers on modest hardware"), ("llama3.1", "General chat and writing"),
        ("mistral", "General chat, fast"), ("phi4", "Reasoning and maths for its size"), ("phi3", "Light reasoning"),
        ("gemma", "General chat"), ("deepseek-r1", "Step-by-step reasoning (slower)"), ("codellama", "Coding"), ("llava", "Describing images"))


def recommended_use(name: str) -> str | None:
    n = name.lower()
    return next((u for k, u in _USE if n.startswith(k)), None)


async def delete_model(name: str) -> dict[str, Any]:
    """7.0 Model Library: removes an installed model through Ollama's /api/delete. Refuses the default chat model and the
    embedding model (the app would stop answering / searching). Returns the refreshed chat_models()."""
    url = get_settings().ollama_base_url
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            tags = (await client.get(f"{url}/api/tags")).json().get("models", [])
    except Exception as e:
        raise ConnectionError(f"Ollama isn't reachable ({type(e).__name__}).") from e
    real = next((t["name"] for t in tags if model_installed(name, [t["name"]])), None)
    if not real:
        raise ValueError(f"The model '{name}' is not installed.")
    act = active_models()
    if model_installed(act["llm"], [real]):
        raise ValueError(f"{real} is the default chat model. Choose another default first.")
    if model_installed(act["embed"], [real]):
        raise ValueError(f"{real} is the embedding model used by your knowledge base, so it can't be removed here.")
    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.request("DELETE", f"{url}/api/delete", json={"model": real})
    if r.status_code >= 400:
        raise ValueError(f"Ollama couldn't remove {real} (HTTP {r.status_code}).")
    return {"deleted": real, **(await chat_models())}


async def resolve_chat_model(name: str) -> str:
    """Checks a model asked for in chat. Returns the name to use, or raises ValueError (not installed / not a chat
    model) or ConnectionError (Ollama down). Never substitutes another model."""
    info = await chat_models()
    if not info["ollama"]:
        raise ConnectionError(info.get("error") or "Ollama isn't reachable.")
    for m in info["models"]:
        if model_installed(name, [m["name"]]):
            return m["name"]
    raise ValueError(f"The model '{name}' is not installed in Ollama (or is not a chat model). "
                     "Choose another model, or install it in Models & setup.")


_last_chat_model: str | None = None
_last_inference: dict[str, Any] | None = None


def record_inference(meta: dict[str, Any] | None, model: str | None) -> dict[str, Any] | None:
    """Speed of one answer, from Ollama's own response metadata (durations in nanoseconds). None when Ollama didn't
    report counts (then nothing is shown: no estimate is ever made up)."""
    global _last_inference
    meta = meta or {}
    count, dur = meta.get("eval_count"), meta.get("eval_duration")
    if not count or not dur:
        return None
    stats = {"model": meta.get("model") or model, "output_tokens": count, "prompt_tokens": meta.get("prompt_eval_count"),
             "tokens_per_second": round(count / (dur / 1e9), 1), "load_seconds": round((meta.get("load_duration") or 0) / 1e9, 2),
             "prompt_seconds": round((meta.get("prompt_eval_duration") or 0) / 1e9, 2),          # load + prompt = time to the first token
             "total_seconds": round((meta.get("total_duration") or 0) / 1e9, 2), "at": int(time.time())}
    _last_inference = stats
    return stats


def _gpu_usage() -> list[dict[str, Any]]:
    """NVIDIA utilisation and VRAM right now (nvidia-smi); empty when there is no NVIDIA GPU or driver tool."""
    if not shutil.which("nvidia-smi"):
        return []
    out = _run(["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"])
    gpus = []
    for line in (out or "").splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) == 4 and parts[1].isdigit():
            gpus.append({"name": parts[0], "util_percent": int(parts[1]), "vram_used_gb": round(int(parts[2]) / 1024, 2),
                         "vram_total_gb": round(int(parts[3]) / 1024, 2)})
    return gpus


async def performance() -> dict[str, Any]:
    """One real sample of this computer and Ollama: CPU and RAM (psutil), NVIDIA GPU use and VRAM (nvidia-smi), the models
    Ollama has loaded right now (/api/ps) and the speed of the last answer (Ollama's metadata). Sampled only when asked."""
    import psutil
    cpu = await asyncio.to_thread(psutil.cpu_percent, 0.3)
    vm = psutil.virtual_memory()
    gpus = await asyncio.to_thread(_gpu_usage)
    loaded, reachable = [], False
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            ps = (await client.get(f"{get_settings().ollama_base_url}/api/ps")).json().get("models", [])
        reachable = True
        loaded = [{"name": m.get("name"), "size_gb": round(m.get("size", 0) / GB, 2), "vram_gb": round(m.get("size_vram", 0) / GB, 2),
                   "unloads_at": m.get("expires_at"), "chat": is_chat_model(m)} for m in ps]
    except Exception:                                        # noqa: BLE001 - reported as not reachable
        pass
    return {"cpu_percent": cpu, "ram_used_gb": round(vm.used / GB, 2), "ram_total_gb": round(vm.total / GB, 2), "ram_percent": vm.percent,
            "gpus": gpus, "ollama": reachable, "loaded": loaded, "last_inference": _last_inference, "sampled_at": int(time.time())}


async def note_chat_model(name: str) -> None:
    """Ollama keeps a model in memory for 5 minutes after use, so switching chat models would leave two of them in
    (GPU) memory. When chat moves to another model, the previous chat model is released right away (keep_alive 0).
    Best effort: a failure only means Ollama frees it later. The embedding model is never touched."""
    global _last_chat_model
    # after a restart nothing was seen yet: the configured default is what earlier chats loaded
    prev, _last_chat_model = (_last_chat_model or active_models()["llm"]), name
    if not prev or model_installed(prev, [name]) or model_installed(name, [prev]):
        return
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            await client.post(f"{get_settings().ollama_base_url}/api/generate", json={"model": prev, "keep_alive": 0})
    except Exception as e:                                   # noqa: BLE001 - best effort
        log.info("Could not release %s: %s", prev, type(e).__name__)


async def preload_model(name: str) -> None:
    """Loads [name] into Ollama's memory without generating anything (a request with no prompt), so the next answer
    doesn't wait for the load. Used when the microphone starts (stability milestone). Raises when Ollama fails."""
    async with httpx.AsyncClient(timeout=120) as client:
        r = await client.post(f"{get_settings().ollama_base_url}/api/generate", json={"model": name})
        r.raise_for_status()


async def set_default_chat_model(name: str) -> dict[str, Any]:
    """Makes an installed chat model the default for new chats (settings.json). The embedding model is unchanged."""
    if os.environ.get("LLM_MODEL"):
        raise PermissionError("The chat model is set by the LLM_MODEL environment variable; change it there.")
    real = await resolve_chat_model(name)
    lfs.update_settings(models={"llm": real})
    _reset_model_caches()
    return await chat_models()


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
_PULL_TASKS: dict[str, "asyncio.Task"] = {}   # model -> running download task (for pause/cancel)


def cancel_pull(model: str) -> bool:
    """Stop a running download (pause/cancel). Ollama keeps what was downloaded; a new pull resumes."""
    task = _PULL_TASKS.get(model)
    if task and not task.done():
        task.cancel()
        return True
    return False


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
    _PULL_TASKS[model] = asyncio.create_task(_pull_worker(model))
    return PULLS[model]


class PullError(RuntimeError):
    """A model download that did not finish; the message is shown to the user as-is."""


def _pull_problem(e: Exception) -> str:
    """Plain-language reason for a failed download (the wizard shows it, then offers Download again;
    Ollama resumes partial downloads)."""
    if isinstance(e, PullError):
        return str(e)
    if isinstance(e, httpx.ConnectError):
        return "Ollama isn't reachable. Start Ollama, then press Download again."
    if isinstance(e, (httpx.RemoteProtocolError, httpx.ReadError, httpx.ReadTimeout)):
        return ("The connection to Ollama was interrupted during the download. Press Download again to resume "
                f"({type(e).__name__}).")
    return f"{type(e).__name__}: {e}"[:300]


async def _pull_worker(model: str) -> None:
    """Stream Ollama's /api/pull. "done" requires Ollama's final {"status": "success"} AND the model
    showing up in /api/tags: a stream that just ends (network drop, Ollama restarted) is a failure,
    never a silent success."""
    st = PULLS[model]
    base = get_settings().ollama_base_url
    try:
        succeeded = False
        async with httpx.AsyncClient(timeout=httpx.Timeout(30, read=600)) as client:
            async with client.stream("POST", f"{base}/api/pull", json={"model": model, "stream": True}) as r:
                if r.status_code >= 400:
                    body = (await r.aread()).decode("utf-8", "replace")
                    try:
                        body = json.loads(body).get("error", body)
                    except ValueError:
                        pass
                    raise PullError(f"Ollama refused the download (HTTP {r.status_code}): {body}"[:300])
                async for line in r.aiter_lines():
                    if not line:
                        continue
                    ev = json.loads(line)
                    if "error" in ev:
                        raise PullError(f"Ollama: {ev['error']}"[:300])
                    st["message"] = ev.get("status", "")
                    if ev.get("total"):
                        st["total"], st["completed"] = ev["total"], ev.get("completed", 0)
                    if ev.get("status") == "success":
                        succeeded = True
            if not succeeded:
                raise PullError("The download stopped before it finished. Press Download again to resume.")
            tags = (await client.get(f"{base}/api/tags")).json()
            names = [m.get("name", "") for m in tags.get("models", [])]
            if not model_installed(model, names):
                raise PullError("Ollama reported success, but the model isn't listed as installed. "
                                "Restart Ollama and press Download again.")
        st.update(status="done", message="installed")
    except asyncio.CancelledError:                  # paused/cancelled by the user: Ollama keeps the partial download
        st.update(status="cancelled", message="Stopped. Start again to resume where it left off.")
        raise
    except Exception as e:
        st.update(status="failed", message=_pull_problem(e))
        log.warning("Model pull failed for %s: %s", model, type(e).__name__)


async def test_inference(model: str | None = None) -> dict[str, Any]:
    """Real end-to-end check: the model must load and produce a non-empty answer."""
    model = model or active_models()["llm"]
    t = time.time()
    try:
        async with httpx.AsyncClient(timeout=180) as client:
            r = await client.post(f"{get_settings().ollama_base_url}/api/generate",
                                  json={"model": model, "prompt": "Reply with exactly: OK", "stream": False,
                                        "options": {"num_predict": 8}})
            if r.status_code >= 400:                    # e.g. model missing, or not enough memory to load it
                try:
                    detail = r.json().get("error", r.text)
                except ValueError:
                    detail = r.text
                return {"ok": False, "model": model, "error": f"Ollama couldn't run {model}: {detail}"[:300]}
            text = r.json().get("response", "").strip()
        if not text:
            return {"ok": False, "model": model, "error": f"{model} loaded but returned an empty answer"}
        return {"ok": True, "model": model, "reply": text[:40], "seconds": round(time.time() - t, 1)}
    except httpx.ConnectError:
        return {"ok": False, "model": model, "error": "Ollama isn't reachable. Start Ollama and try again."}
    except Exception as e:
        return {"ok": False, "model": model, "error": f"{type(e).__name__}: {e}"[:300]}


# --------------------------------------------------------------------------- update check
async def check_for_update(force: bool = True) -> dict[str, Any]:
    """Kept for existing callers: the update manager (updates.py) does the work. Since dev.6 the
    manifest must be SIGNED by a configured publisher key; unsigned manifests are rejected."""
    import updates
    return await updates.check(force=force)
