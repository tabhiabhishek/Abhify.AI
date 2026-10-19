"""
setup_manager.py: automatic first-run setup ("Set up automatically" in the setup wizard).

Builds on what the wizard already had (device detection and profiles in system_service, verified
model downloads and the inference test) and adds what was manual:

  plan()   what this device needs: the Ollama runtime (if missing), the models of the profile that
           fits this device (system_service.recommend_profile), sizes and the disk check
  start()  ONE confirmation for the whole plan (nothing is downloaded before it), then a background job:
             1. Ollama: download the official installer into <data root>/downloads (resumable),
                require a VALID Authenticode signature from "Ollama Inc.", run it silently per-user,
                wait until Ollama answers
             2. models: pull each missing model (Ollama verifies layer digests; a stopped download
                resumes); "done" only when Ollama lists the model
             3. validation: a real answer from the LLM AND a real embedding vector
             4. record versions, digests and sizes in config/components.json; mark setup complete
  pause()/cancel()   stop the current transfer (partial data is kept); start() again resumes
  Anything already installed is detected and skipped, so nothing is downloaded twice.

Nothing here runs during normal startup: after setup, the app starts and works without network.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, Callable

import httpx

import local_file_service as lfs
import system_service as system
from config import get_settings
from version import __version__

log = logging.getLogger(__name__)

OLLAMA_SETUP_URL = "https://ollama.com/download/OllamaSetup.exe"
OLLAMA_PUBLISHER = "O=Ollama Inc."               # required in the Authenticode signer subject
OLLAMA_SETUP_GB = 1.6                             # measured 2026-10-16: 1,511 MB (0.40.5)
OLLAMA_INSTALLED_GB = 5.0                         # estimate incl. GPU libraries; checked on the install drive

JOB: dict[str, Any] = {"status": "idle", "step": None, "message": "", "progress": None, "error": None,
                       "started_at": None, "finished_at": None, "plan": None}
_task: asyncio.Task | None = None
_stop: dict[str, str | None] = {"reason": None}   # "pause" | "cancel" | None


class SetupStopped(Exception):
    pass


# --------------------------------------------------------------------------- paths and records
def downloads_dir() -> Path:
    return lfs.root() / "downloads"


def components_path() -> Path:
    return lfs.config_dir() / "components.json"


def load_components() -> dict[str, Any]:
    try:
        return json.loads(components_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_components(data: dict[str, Any]) -> None:
    components_path().parent.mkdir(parents=True, exist_ok=True)
    tmp = components_path().with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.replace(tmp, components_path())


def ollama_exe() -> Path | None:
    found = shutil.which("ollama")
    if found:
        return Path(found)
    default = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Ollama" / "ollama.exe"
    return default if default.exists() else None


# --------------------------------------------------------------------------- plan
async def plan() -> dict[str, Any]:
    """What automatic setup would do on this device. Read-only; downloads nothing."""
    diag = await system.diagnostics()
    settings = lfs.load_settings()
    rec = diag["recommended_profile"]
    key = settings.get("model_profile") if settings.get("setup_completed") else rec["profile"]
    profile = system.PROFILES.get(key) or system.PROFILES[rec["profile"]]
    ollama = diag["ollama"]
    installed = [m["name"] for m in ollama.get("models", [])]
    comps: list[dict[str, Any]] = []
    windows = platform.system() == "Windows"
    if ollama["reachable"]:
        comps.append({"id": "ollama", "label": f"Ollama runtime {ollama.get('version') or ''}".strip(),
                      "action": "ok", "download_gb": 0})
    elif ollama_exe():
        comps.append({"id": "ollama", "label": "Ollama runtime (installed, not running)", "action": "start",
                      "download_gb": 0})
    else:
        comps.append({"id": "ollama", "label": "Ollama runtime (official installer, signed by Ollama Inc.)",
                      "action": "install" if windows else "manual", "download_gb": OLLAMA_SETUP_GB if windows else 0,
                      "source": OLLAMA_SETUP_URL if windows else "https://ollama.com/download"})
    for model, purpose in ((profile["llm"], "chat"), (profile["embed"], "document search")):
        have = system.model_installed(model, installed)
        comps.append({"id": f"model:{model}", "label": f"{model} ({purpose})", "action": "ok" if have else "download",
                      "download_gb": 0 if have else round(system.estimate_download_gb(model), 2),
                      "source": "Ollama registry (registry.ollama.ai), via the local Ollama"})
    download_gb = round(sum(c["download_gb"] for c in comps), 2)
    models_disk = system.check_disk_for(profile["llm"]) if any(c["action"] == "download" and c["id"].startswith("model:")
                                                               for c in comps) else None
    need_app_gb = OLLAMA_SETUP_GB + OLLAMA_INSTALLED_GB if comps[0]["action"] == "install" else 0
    free_app_gb = round(lfs.free_bytes(downloads_dir()) / system.GB, 1)
    problems = []
    if models_disk and not models_disk["ok"]:
        problems.append(f"Not enough disk space for the models: about {models_disk['required_free_gb']} GB needed, "
                        f"{models_disk['free_gb']} GB free.")
    if need_app_gb and free_app_gb < need_app_gb:
        problems.append(f"Not enough disk space for Ollama: about {need_app_gb:.1f} GB needed, {free_app_gb} GB free.")
    if comps[0]["action"] == "manual":
        problems.append("Install Ollama from https://ollama.com/download, then press Re-check.")
    needs_net = download_gb > 0
    if needs_net and not diag["internet"]:
        problems.append("No internet connection: setup needs it once to download the components above. "
                        "Features that are already installed keep working.")
    return {"profile": key, "profile_label": profile["label"], "reason": rec["reason"], "components": comps,
            "download_gb": download_gb, "needs_internet": needs_net, "internet": diag["internet"],
            "disk": {"models": models_disk, "app_free_gb": free_app_gb, "app_needed_gb": round(need_app_gb, 1)},
            "gpu": [g["name"] for g in diag["gpus"]], "ram_gb": diag["ram"].get("total_gb"),
            "nothing_to_do": all(c["action"] == "ok" for c in comps), "problems": problems,
            "ready_to_start": not problems}


# --------------------------------------------------------------------------- job control
def status() -> dict[str, Any]:
    pulls = {m: system.PULLS[m] for m in system.PULLS}
    return {**JOB, "pulls": pulls, "components": load_components()}


async def start(confirm: bool) -> dict[str, Any]:
    global _task
    if not confirm:
        raise ValueError("Automatic setup downloads components; confirm the plan first (confirm=true).")
    if _task and not _task.done():
        return status()
    p = await plan()
    if not p["ready_to_start"]:
        raise ValueError(" ".join(p["problems"]))
    _stop["reason"] = None
    JOB.update(status="running", step="starting", message="", progress=None, error=None,
               started_at=time.time(), finished_at=None, plan=p)
    _task = asyncio.create_task(_run(p))
    return status()


def stop(reason: str) -> dict[str, Any]:
    """reason: 'pause' (resume later) or 'cancel'. Partial downloads are kept either way."""
    if JOB["status"] != "running":
        return status()
    _stop["reason"] = reason
    for model in list(system._PULL_TASKS):
        system.cancel_pull(model)
    return status()


def _check_stop() -> None:
    if _stop["reason"]:
        raise SetupStopped(_stop["reason"])


# --------------------------------------------------------------------------- the job
async def _run(p: dict[str, Any]) -> None:
    try:
        ollama = p["components"][0]
        if ollama["action"] == "install":
            await _install_ollama()
        if ollama["action"] in ("install", "start"):
            await _ensure_ollama_running()
        _check_stop()
        system.apply_profile(p["profile"])
        for c in p["components"][1:]:
            if c["action"] == "download":
                await _pull(c["id"].split(":", 1)[1])
        await _validate()
        await _record(p["profile"])
        lfs.update_settings(setup_completed=True)
        JOB.update(status="done", step="done", message="Setup complete: the AI is installed, verified and ready.",
                   finished_at=time.time())
        log.info("Automatic setup finished (profile %s)", p["profile"])
    except SetupStopped as e:
        JOB.update(status="paused" if str(e) == "pause" else "cancelled", finished_at=time.time(),
                   message="Paused. Press Resume to continue where it stopped." if str(e) == "pause"
                   else "Cancelled. Partial downloads are kept; start again to resume.")
    except asyncio.CancelledError:
        JOB.update(status="cancelled", finished_at=time.time())
        raise
    except Exception as e:                           # every failure is retryable and explained
        JOB.update(status="failed", finished_at=time.time(), error=str(e)[:400],
                   message="Setup stopped. Fix the problem shown and press Retry; finished steps are not repeated.")
        log.warning("Automatic setup failed at %s: %s", JOB["step"], type(e).__name__)


# Hooks the tests replace (no real downloads, signatures or installers in automated tests).
async def _download(url: str, dest: Path, progress: Callable[[int, int], None]) -> None:
    """Resumable HTTPS download into `dest` (a .part file is continued with a Range request)."""
    part = dest.with_suffix(dest.suffix + ".part")
    have = part.stat().st_size if part.exists() else 0
    headers = {"Range": f"bytes={have}-"} if have else {}
    async with httpx.AsyncClient(timeout=httpx.Timeout(30, read=120), follow_redirects=True) as client:
        async with client.stream("GET", url, headers=headers) as r:
            if r.status_code == 416:                  # already complete
                os.replace(part, dest); return
            r.raise_for_status()
            if have and r.status_code != 206:          # server ignored Range: start over
                have = 0
            total = have + int(r.headers.get("content-length") or 0)
            with part.open("ab" if have else "wb") as f:
                async for chunk in r.aiter_bytes(1 << 20):
                    if _stop["reason"]:
                        raise SetupStopped(_stop["reason"])
                    f.write(chunk)
                    have += len(chunk)
                    progress(have, total)
    os.replace(part, dest)


def _verify_publisher(path: Path, expected: str) -> tuple[bool, str]:
    """Windows Authenticode: the signature must be Valid and the signer must be `expected`."""
    # The path goes in through an environment variable: with -Command, extra arguments are appended to the
    # script text (not passed as $args), and an env var can't be interpreted as code whatever the file name is.
    ps = ("$s = Get-AuthenticodeSignature -LiteralPath $env:ABHIFY_VERIFY_PATH; "
          "Write-Output $s.Status; Write-Output $s.SignerCertificate.Subject")
    out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps], capture_output=True,
                         text=True, timeout=120, env={**os.environ, "ABHIFY_VERIFY_PATH": str(Path(path).resolve())})
    lines = (out.stdout or "").strip().splitlines()
    status_, subject = (lines + ["", ""])[:2]
    return status_.strip() == "Valid" and expected in subject, f"{status_.strip()}: {subject.strip()[:120]}"


def _run_installer(path: Path) -> int:
    """Per-user silent install. Waits for the installer process only (not the tray app it starts)."""
    proc = subprocess.Popen([str(path), "/VERYSILENT", "/NORESTART", "/SUPPRESSMSGBOXES"])
    return proc.wait(timeout=1800)


async def _install_ollama() -> None:
    JOB.update(step="ollama-download", message="Downloading the Ollama runtime…")
    downloads_dir().mkdir(parents=True, exist_ok=True)
    dest = downloads_dir() / "OllamaSetup.exe"
    if not dest.exists():
        await _download(OLLAMA_SETUP_URL, dest,
                        lambda done, total: JOB.update(progress={"done": done, "total": total}))
    _check_stop()
    JOB.update(step="ollama-verify", message="Verifying the Ollama installer's signature…", progress=None)
    ok, detail = await asyncio.to_thread(_verify_publisher, dest, OLLAMA_PUBLISHER)
    if not ok:
        dest.unlink(missing_ok=True)                 # never keep or run an unverified installer
        raise RuntimeError(f"The downloaded Ollama installer is not validly signed by Ollama Inc. ({detail}); "
                           "it was deleted and not run.")
    JOB.update(step="ollama-install", message="Installing Ollama (for this Windows user, no administrator rights)…")
    code = await asyncio.to_thread(_run_installer, dest)
    if code != 0:
        raise RuntimeError(f"The Ollama installer exited with code {code}.")
    dest.unlink(missing_ok=True)                     # installed: the 1.6 GB installer isn't needed any more


async def _ensure_ollama_running(timeout: float = 120) -> None:
    JOB.update(step="ollama-start", message="Starting Ollama…")
    if (await system.ollama_status())["reachable"]:
        return
    exe = ollama_exe()
    if exe:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        subprocess.Popen([str(exe), "serve"], creationflags=flags, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL)
    end = time.time() + timeout
    while time.time() < end:
        _check_stop()
        if (await system.ollama_status())["reachable"]:
            return
        await asyncio.sleep(2)
    raise RuntimeError("Ollama didn't start. Start the Ollama app from the Start Menu, then press Retry.")


async def _pull(model: str) -> None:
    JOB.update(step=f"model:{model}", message=f"Downloading {model}…", progress=None)
    await system.start_pull(model, confirm=True)      # the user confirmed the whole plan
    while True:
        st = system.PULLS.get(model, {})
        if st.get("total"):
            JOB["progress"] = {"done": st.get("completed", 0), "total": st["total"]}
        if st.get("status") == "done":
            return
        if st.get("status") in ("failed", "cancelled"):
            _check_stop()                             # paused/cancelled by the user
            raise RuntimeError(f"{model}: {st.get('message')}")
        await asyncio.sleep(0.5)


async def _validate() -> None:
    JOB.update(step="validate", message="Checking that the AI really works…", progress=None)
    t = await system.test_inference()
    if not t.get("ok"):
        raise RuntimeError(f"The chat model didn't answer: {t.get('error')}")
    embed = system.active_models()["embed"]
    async with httpx.AsyncClient(timeout=120) as client:
        r = await client.post(f"{get_settings().ollama_base_url}/api/embed", json={"model": embed, "input": "test"})
    vec = (r.json().get("embeddings") or [[]])[0] if r.status_code == 200 else []
    if not vec:
        raise RuntimeError(f"The document-search model ({embed}) didn't produce an embedding (HTTP {r.status_code}).")
    JOB["validation"] = {"llm_reply": t.get("reply"), "llm_seconds": t.get("seconds"), "embedding_dims": len(vec)}


async def _record(profile: str) -> None:
    """config/components.json: what is installed, which versions, where, and when it was verified."""
    st = await system.ollama_status()
    async with httpx.AsyncClient(timeout=10) as client:
        tags = (await client.get(f"{get_settings().ollama_base_url}/api/tags")).json().get("models", [])
    active = system.active_models()
    models = {}
    for role, wanted in active.items():                      # the tag entry Ollama reports for each active model
        for m in tags:
            if system.model_installed(wanted, [m["name"]]):
                models[m["name"]] = {"role": role, "digest": m.get("digest"), "size_bytes": m.get("size"),
                                     "modified_at": m.get("modified_at")}
    save_components({
        "app_version": __version__, "profile": profile, "active_models": active,
        "ollama": {"version": st.get("version"), "url": st.get("url"), "executable": str(ollama_exe() or ""),
                   "models_dir": str(system.ollama_models_dir())},
        "models": models, "validation": JOB.get("validation"), "verified_at": time.time(),
    })
