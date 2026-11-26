"""
7.0.2 hardware-aware local model advice for the desktop (Ollama). Isolated module: it reads facts, decides, explains.
It never downloads, never changes the default model by itself, and never invents hardware facts.

  device_report(diag)  -> facts about this computer, each labelled "verified" (read from the OS / driver / Ollama),
                          "estimate" (reported by an API known to be approximate) or "unavailable"
  advise(diag, ...)    -> one primary model, compatible alternatives, incompatible models with reasons, and what
                          would have to be downloaded (nothing when a suitable model is already installed)

Decision rules (documented in docs/MODEL_SELECTION_GUIDE.md; numbers from model_catalog.json, read from the Ollama
registry; memory needs are estimates, never benchmarks):
  * memory a model needs  = model file + KV cache (4096 tokens) + runtime buffers         (catalog memory_required_gb)
  * GPU mode              : NVIDIA GPU with a driver (Ollama uses CUDA) and need <= 85 % of its VRAM
  * CPU mode              : need <= RAM left after a reserve for Windows and apps = max(4 GB, 30 % of RAM)
  * disk                  : download + 2 GB must be free in Ollama's models folder (not needed when installed)
  * "comfortable" (what is recommended): GPU need <= 70 % of VRAM; CPU need <= 50 % of usable RAM and at most the
    7-8B tier (bigger models are too slow on a CPU to be pleasant). The LARGEST model that merely fits is NOT chosen.
  * an installed model that is comfortable wins (reuse, no duplicate download); otherwise the best comfortable catalog
    model; otherwise the smallest compatible one; otherwise none (with the reasons).
"""
from __future__ import annotations

import json
import platform
from pathlib import Path
from typing import Any

import system_service as system

CATALOG_PATH = Path(__file__).with_name("model_catalog.json")
GB = 1024 ** 3
VRAM_FIT, VRAM_COMFORT = 0.85, 0.70
RAM_COMFORT = 0.50
DISK_HEADROOM_GB = 2.0
CPU_MAX_TIER = 3                      # 7-8B: the largest tier recommended for CPU-only use


def catalog() -> dict[str, Any]:
    return json.loads(CATALOG_PATH.read_text(encoding="utf-8"))


def cpu_name() -> tuple[str | None, str]:
    """The CPU's marketing name from the Windows registry (no admin needed); elsewhere platform.processor()."""
    if platform.system() == "Windows":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as k:
                return str(winreg.QueryValueEx(k, "ProcessorNameString")[0]).strip(), "verified"
        except OSError:
            pass
    name = platform.processor()
    return (name, "verified") if name else (None, "unavailable")


def display_adapters() -> list[dict[str, Any]]:
    """All display adapters Windows reports (WMI, no admin). AdapterRAM is a 32-bit field (capped near 4 GB), so it's
    labelled an estimate; NVIDIA VRAM comes from nvidia-smi instead."""
    if platform.system() != "Windows":
        return []
    out = system._run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                       "Get-CimInstance Win32_VideoController | Select-Object Name,AdapterRAM,DriverVersion | ConvertTo-Json -Compress"],
                      timeout=10)
    try:
        rows = json.loads(out or "[]")
    except ValueError:
        return []
    rows = rows if isinstance(rows, list) else [rows]
    return [{"name": r.get("Name"), "driver": r.get("DriverVersion"),
             "reported_memory_gb": round((r.get("AdapterRAM") or 0) / GB, 1) or None} for r in rows if r.get("Name")]


def _fact(label: str, value: Any, source: str) -> dict[str, Any]:
    return {"label": label, "value": value if value not in (None, "") else "not available",
            "source": source if value not in (None, "") else "unavailable"}


def acceleration() -> dict[str, Any]:
    """7.1.0-dev.2: GPU compute support on this PC. CUDA: the highest CUDA version the NVIDIA driver supports (nvidia-smi header).
    Vulkan: whether the Vulkan runtime (vulkan-1.dll) is installed. Read-only; nothing is installed or started."""
    import os
    import re
    import shutil
    import subprocess
    out: dict[str, Any] = {"cuda": None, "vulkan": None}
    if shutil.which("nvidia-smi"):
        try:
            txt = subprocess.run(["nvidia-smi"], capture_output=True, text=True, timeout=10,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
            m = re.search(r"CUDA Version:\s*([\d.]+)", txt)
            out["cuda"] = m.group(1) if m else None
        except (OSError, subprocess.SubprocessError):
            pass
    if os.name == "nt":
        out["vulkan"] = os.path.exists(os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "vulkan-1.dll"))
    return out


def device_report(diag: dict[str, Any], adapters: list[dict[str, Any]] | None = None, cpu: tuple[str | None, str] | None = None,
                  accel: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    cpu = cpu or cpu_name()
    ram, gpus, disk, ol = diag.get("ram") or {}, diag.get("gpus") or [], diag.get("disk") or {}, diag.get("ollama") or {}
    adapters = adapters if adapters is not None else display_adapters()
    osd = diag["os"]
    release = osd["release"]
    try:                                                        # Windows 11 still reports release "10"; build 22000+ is 11
        if osd["system"] == "Windows" and int(str(osd["version"]).split(".")[2]) >= 22000:
            release = "11"
    except (IndexError, ValueError):
        pass
    facts = [
        _fact("Operating system", f"{osd['system']} {release} (build {osd['version']}), {osd['arch']}", "verified"),
        _fact("CPU", cpu[0], cpu[1]),
        _fact("Logical cores", (diag.get("cpu") or {}).get("logical_cores"), "verified"),
        _fact("RAM total", f"{ram['total_gb']} GB" if ram.get("total_gb") else None, "verified"),
        _fact("RAM available now", f"{ram['available_gb']} GB" if ram.get("available_gb") is not None else None, "verified"),
    ]
    for g in gpus:
        facts.append(_fact(f"GPU (NVIDIA, CUDA via driver {g.get('driver')})", f"{g['name']}: {g['vram_gb']} GB VRAM, {g.get('vram_used_gb', 0)} GB in use", "verified"))
    nv = {g["name"] for g in gpus}
    for a in adapters:
        if a["name"] not in nv:
            mem = f", about {a['reported_memory_gb']} GB reported" if a.get("reported_memory_gb") else ""
            facts.append(_fact("Display adapter (no CUDA; CPU used for AI)", f"{a['name']}{mem}", "estimate" if mem else "verified"))
    if not gpus and not adapters:
        facts.append(_fact("GPU", None, "unavailable"))
    facts += [
        _fact("Free disk (Ollama models folder)", f"{disk.get('models_free_gb')} GB in {disk.get('models_dir')}" if disk.get("models_free_gb") is not None else None, "verified"),
        _fact("Ollama", f"running, version {ol.get('version')}" if ol.get("reachable") else ("installed, not running" if ol.get("installed") else "not installed"), "verified"),
        _fact("Installed models", ", ".join(m["name"] for m in ol.get("models", [])) or "none", "verified"),
    ]
    if accel is not None:                                            # 7.1.0-dev.2
        facts.append(_fact("CUDA (NVIDIA driver supports up to)", accel.get("cuda"), "verified" if accel.get("cuda") else "unavailable"))
        facts.append(_fact("Vulkan runtime", None if accel.get("vulkan") is None else ("installed" if accel["vulkan"] else "not installed"),
                           "verified" if accel.get("vulkan") is not None else "unavailable"))
    return facts


def _installed_entry(name: str, installed: dict[str, dict[str, Any]], m: dict[str, Any]) -> dict[str, Any] | None:
    for n in [m["name"], *m.get("aliases", [])]:
        if n in installed or f"{n}:latest" in installed:
            return installed.get(n) or installed.get(f"{n}:latest")
    return None


def installed_name(name: str, installed: list[str], cat: dict[str, Any] | None = None) -> str:
    """The name Ollama has a catalog model under: the advice says "qwen2.5:7b", Ollama may list it as "qwen2.5:latest"
    (same digest). Unknown names are returned unchanged."""
    cat = cat or catalog()
    for m in [*cat["models"], cat["embedding"]]:
        names = [m["name"], *m.get("aliases", [])]
        if name in names or f"{name}:latest" in names:
            for n in names:
                if n in installed or f"{n}:latest" in installed:
                    return n if n in installed else f"{n}:latest"
    return name


def fit(m: dict[str, Any], diag: dict[str, Any], installed: bool) -> dict[str, Any]:
    need = float(m["memory_required_gb"])
    ram_total = (diag.get("ram") or {}).get("total_gb") or 0
    usable = max(0.0, ram_total - max(4.0, 0.30 * ram_total))
    vram = max((g["vram_gb"] for g in diag.get("gpus") or [] if g.get("driver")), default=0.0)
    free = (diag.get("disk") or {}).get("models_free_gb")
    dl = round(m["download_bytes"] / 1e9, 2)
    gpu_ok, cpu_ok = vram > 0 and need <= VRAM_FIT * vram, need <= usable
    disk_ok = installed or (free is not None and free >= dl + DISK_HEADROOM_GB)
    reasons = []
    if not (gpu_ok or cpu_ok):
        reasons.append(f"needs about {need} GB of memory; this computer can spare about {round(usable, 1)} GB of RAM"
                       + (f" and {round(VRAM_FIT * vram, 1)} GB of VRAM" if vram else " (no NVIDIA GPU)"))
    if not disk_ok:
        reasons.append(f"needs {dl + DISK_HEADROOM_GB:.1f} GB free disk; {free} GB free")
    mode = "GPU (NVIDIA CUDA)" if gpu_ok else "CPU" if cpu_ok else None
    comfortable = (gpu_ok and need <= VRAM_COMFORT * vram) or (not gpu_ok and cpu_ok and need <= RAM_COMFORT * usable and m["tier"] <= CPU_MAX_TIER)
    return {"name": m["name"], "aliases": m.get("aliases", []), "parameters": m["parameters"], "quantization": m["quantization"], "download_gb": dl,
            "memory_required_gb": need, "min_ram_gb": m["min_ram_gb"], "recommended_ram_gb": m["recommended_ram_gb"],
            "use": m["use"], "tier": m["tier"], "license": m["license"], "digest": m["manifest_digest"], "source": m["source"],
            "installed": installed, "compatible": (gpu_ok or cpu_ok) and disk_ok, "comfortable": bool(comfortable and disk_ok),
            "mode": mode, "reasons": reasons, "library_only": bool(m.get("library_only"))}


def advise(diag: dict[str, Any], cat: dict[str, Any] | None = None, default_model: str | None = None) -> dict[str, Any]:
    cat = cat or catalog()
    ol = diag.get("ollama") or {}
    installed = {m["name"]: m for m in ol.get("models", [])}
    rows = [fit(m, diag, _installed_entry(m["name"], installed, m) is not None) for m in cat["models"]]
    compatible = [r for r in rows if r["compatible"]]
    # 7.1.0: "library_only" models are offered for download (alternatives) but never chosen automatically
    comfortable = [r for r in compatible if r["comfortable"] and not r["library_only"]]
    # a GPU makes answers several times faster: when any model runs comfortably on it, choose among those
    on_gpu = [r for r in comfortable if (r["mode"] or "").startswith("GPU")]
    pool = on_gpu or comfortable
    best = lambda rs: sorted(rs, key=lambda r: (-r["tier"], r["memory_required_gb"]))     # noqa: E731  best tier, then lighter
    reuse = best([r for r in pool if r["installed"]])
    if reuse:
        primary, why = reuse[0], "already installed and comfortable for this computer: no download needed"
    elif pool:
        primary = best(pool)[0]
        why = "the best model that runs comfortably here" + (" on the GPU" if on_gpu else "") + ", leaving memory for Windows and your other apps"
    elif [r for r in compatible if not r["library_only"]]:
        primary = sorted((r for r in compatible if not r["library_only"]), key=lambda r: r["tier"])[0]
        why = "the lightest model that fits; larger ones would leave too little memory"
    else:
        primary, why = None, "no catalog model fits this computer's memory or disk"
    emb = cat["embedding"]
    emb_installed = _installed_entry(emb["name"], installed, emb) is not None
    downloads = ([] if not primary or primary["installed"] else [{"model": primary["name"], "download_gb": primary["download_gb"]}]) + \
        ([] if emb_installed else [{"model": emb["name"], "download_gb": round(emb["download_bytes"] / 1e9, 2)}])
    return {
        "primary": primary and {**primary, "why": why},
        "alternatives": sorted((r for r in compatible if not primary or r["name"] != primary["name"]), key=lambda r: (-r["installed"], -r["tier"])),
        "incompatible": [r for r in rows if not r["compatible"]],
        "embedding": {"name": emb["name"], "installed": emb_installed, "download_gb": round(emb["download_bytes"] / 1e9, 2)},
        "downloads": downloads, "download_total_gb": round(sum(d["download_gb"] for d in downloads), 2),
        "ollama": {"reachable": bool(ol.get("reachable")), "version": ol.get("version"), "installed": bool(ol.get("installed"))},
        "current_default": default_model,
        "note": "Nothing is downloaded or changed until you choose. Your current default model stays until you pick another.",
        "rules": {"vram_fit": VRAM_FIT, "vram_comfort": VRAM_COMFORT, "ram_reserve": "max(4 GB, 30 % of RAM)", "ram_comfort": RAM_COMFORT,
                  "cpu_max_tier": CPU_MAX_TIER, "disk_headroom_gb": DISK_HEADROOM_GB, "context_tokens": cat["context_tokens"]},
    }
