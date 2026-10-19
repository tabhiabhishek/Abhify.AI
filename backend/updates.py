"""
updates.py: the ONE update manager (app installer and AI models). Replaces the unsigned check in
system_service.check_for_update (which now calls check() here).

Trust model
  * The release manifest must be SIGNED (Ed25519, ed25519_verify.py) by a publisher key that the owner
    configured in settings.json -> updates.trusted_keys {key_id: base64 public key}. No key ships in
    the repository, and the private key never belongs in it. Without a trusted key nothing is offered.
  * Only an HTTPS manifest URL is fetched. Size, SHA-256 (from the SIGNED manifest) and, when the
    manifest names one, the Windows Authenticode publisher are verified before an installer can run.
  * A matching hash from an unauthenticated source is NOT trusted: the signature is what proves origin.

Behaviour
  * check(): at most every `check_interval_hours` (default 24) unless forced; only when online. Sends
    nothing but a plain GET for the manifest (no user data, no identifiers).
  * download(): into <data root>/updates/staging, resumable, with disk check, then verified.
  * install(confirm=True): only after the user confirms. It starts the verified installer (visible,
    per-user, no administrator rights); the installer stops the app, backs up user data and keeps it.
    The installer is also archived in updates/archive for a manual rollback (reinstall the previous one).
    Never automatic, never forced: a working offline installation keeps working.
  * Models: check_models() compares local and registry digests (only when asked); apply_model_update()
    keeps the old model under a backup name, pulls, validates, and restores the backup if validation fails.

Manifest envelope (JSON):
  {"format": "abhify-update-1", "key_id": "...", "manifest": "<base64 of the manifest JSON bytes>",
   "signature": "<base64 Ed25519 signature of those bytes>"}
Manifest:
  {"app": "Abhify.AI", "version": "2.1.0", "released": "2026-11-01", "min_supported": "2.0.0",
   "notes": "...", "packages": {"windows-x64": {"url": "https://...", "size": 121257187,
   "sha256": "<64 hex>", "authenticode_subject": "O=Example Publisher" | null}}}
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

import httpx
from packaging.version import InvalidVersion, Version

import ed25519_verify
import local_file_service as lfs
from version import APP_NAME, __version__

log = logging.getLogger(__name__)

FORMAT = "abhify-update-1"
MAX_MANIFEST = 64 * 1024
MAX_PACKAGE = 2 * 1024 ** 3
PLATFORM = "windows-x64"
_lock = asyncio.Lock()
STATE: dict[str, Any] = {"download": None, "cancel": False}   # in-process progress / cancel flag of a download


class UpdateError(ValueError):
    pass


# --------------------------------------------------------------------------- settings and folders
def cfg() -> dict[str, Any]:
    return lfs.load_settings().get("updates", {})


def updates_dir() -> Path:
    return lfs.root() / "updates"


def staging_dir() -> Path:
    return updates_dir() / "staging"


def archive_dir() -> Path:
    return updates_dir() / "archive"


def _save(**changes: Any) -> None:
    lfs.update_settings(updates=changes)


# --------------------------------------------------------------------------- signed manifest
def sign_envelope(manifest: dict[str, Any], secret: bytes, key_id: str) -> dict[str, Any]:
    """Publisher side (release tool and tests). Never used by the installed app."""
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"format": FORMAT, "key_id": key_id, "manifest": base64.b64encode(raw).decode(),
            "signature": base64.b64encode(ed25519_verify.sign(secret, raw)).decode()}


def verify_envelope(raw: bytes, trusted_keys: dict[str, str]) -> dict[str, Any]:
    if len(raw) > MAX_MANIFEST:
        raise UpdateError("manifest too large")
    try:
        env = json.loads(raw)
        if env.get("format") != FORMAT:
            raise UpdateError("not a signed Abhify.AI update manifest")
        key_b64 = trusted_keys.get(env.get("key_id"))
        if not key_b64:
            raise UpdateError("manifest signed by an unknown key")
        body = base64.b64decode(env["manifest"], validate=True)
        sig = base64.b64decode(env["signature"], validate=True)
        key = base64.b64decode(key_b64, validate=True)
    except UpdateError:
        raise
    except Exception as e:                             # malformed JSON / base64 / fields
        raise UpdateError(f"malformed manifest ({type(e).__name__})") from None
    if not ed25519_verify.verify(key, body, sig):
        raise UpdateError("signature does not match: the manifest was altered or not signed by the trusted key")
    m = json.loads(body)
    return validate_manifest(m)


def validate_manifest(m: dict[str, Any]) -> dict[str, Any]:
    if m.get("app") != APP_NAME:
        raise UpdateError("manifest is for a different application")
    try:
        Version(str(m["version"])); Version(str(m.get("min_supported", "0")))
    except (InvalidVersion, KeyError):
        raise UpdateError("invalid version in manifest") from None
    pkg = (m.get("packages") or {}).get(PLATFORM)
    if pkg is not None:
        url, sha, size = str(pkg.get("url", "")), str(pkg.get("sha256", "")).lower(), pkg.get("size")
        if not url.startswith("https://"):
            raise UpdateError("package URL must use HTTPS")
        if len(sha) != 64 or any(ch not in "0123456789abcdef" for ch in sha):
            raise UpdateError("invalid package checksum")
        if not isinstance(size, int) or not 0 < size <= MAX_PACKAGE:
            raise UpdateError("invalid package size")
        pkg["sha256"] = sha
    return m


# --------------------------------------------------------------------------- check
def _internet() -> bool:
    import system_service as system
    return system.internet_available()


async def check(force: bool = False) -> dict[str, Any]:
    c = cfg()
    base = {"current": __version__, "configured": bool(c.get("manifest_url") and c.get("trusted_keys")),
            "last_check_at": c.get("last_check_at")}
    url = c.get("manifest_url")
    if not url:
        return {**base, "update_available": False, "message": "No update source configured."}
    if not str(url).startswith("https://"):
        return {**base, "update_available": False, "error": "The update manifest URL must use HTTPS."}
    if not c.get("trusted_keys"):
        return {**base, "update_available": False,
                "error": "No trusted publisher key configured, so updates can't be verified (see docs/AUTOMATIC_UPDATES.md)."}
    interval = float(c.get("check_interval_hours") or 24) * 3600
    if not force and c.get("last_check_at") and time.time() - c["last_check_at"] < interval and c.get("last_result"):
        return {**base, **c["last_result"], "cached": True}
    if not _internet():
        return {**base, "update_available": False, "error": "Offline: can't check for updates. Everything else keeps working."}
    async with _lock:                                   # never two checks at once
        try:
            async with httpx.AsyncClient(timeout=10, follow_redirects=True) as client:
                r = await client.get(url, headers={"Accept": "application/json"})
                r.raise_for_status()
            m = verify_envelope(r.content, c["trusted_keys"])
        except UpdateError as e:
            result = {"update_available": False, "error": f"Update manifest rejected: {e}"}
        except Exception as e:
            result = {"update_available": False, "error": f"Update check failed ({type(e).__name__})"}
        else:
            latest, current = Version(str(m["version"])), Version(__version__)
            pkg = (m.get("packages") or {}).get(PLATFORM)
            result = {"update_available": latest > current and pkg is not None, "latest": str(m["version"]),
                      "released": m.get("released"), "notes": str(m.get("notes", ""))[:5000],
                      "upgrade_supported": Version(str(m.get("min_supported", "0"))) <= current,
                      "package": pkg, "verified": True}
        _save(last_check_at=time.time(), last_result=result)
        log.info("Update check: %s", "update available" if result.get("update_available") else result.get("error", "up to date"))
        return {**base, "last_check_at": time.time(), **result}


# --------------------------------------------------------------------------- download and verify
def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def _verify_publisher(path: Path, expected: str) -> tuple[bool, str]:
    """Same check as setup_manager: Authenticode Valid and the expected signer."""
    import setup_manager
    return setup_manager._verify_publisher(path, expected)


async def _fetch(url: str, part: Path, total: int) -> None:
    have = part.stat().st_size if part.exists() else 0
    headers = {"Range": f"bytes={have}-"} if have else {}
    async with httpx.AsyncClient(timeout=httpx.Timeout(30, read=120), follow_redirects=True) as client:
        async with client.stream("GET", url, headers=headers) as r:
            if r.status_code == 416:
                return
            r.raise_for_status()
            if have and r.status_code != 206:
                have = 0
            with part.open("ab" if have else "wb") as f:
                async for chunk in r.aiter_bytes(1 << 20):
                    if STATE["cancel"]:
                        raise UpdateError("Download cancelled. The part already downloaded is kept; Download resumes it.")
                    have += len(chunk)
                    if have > total:
                        raise UpdateError("download is larger than the signed manifest says")
                    f.write(chunk)
                    STATE["download"] = {"done": have, "total": total}


async def download() -> dict[str, Any]:
    """Download the package from the last VERIFIED check into staging and verify it."""
    res = cfg().get("last_result") or {}
    pkg, version = res.get("package"), res.get("latest")
    if not (res.get("verified") and res.get("update_available") and pkg):
        raise UpdateError("No verified update to download. Check for updates first.")
    staging_dir().mkdir(parents=True, exist_ok=True)
    final = staging_dir() / f"Abhify.AI-Setup-{version}.exe"   # name from the signed version, never from the URL
    if final.exists() and _sha256(final) == pkg["sha256"]:
        return _prepared(version, final, pkg)
    need = pkg["size"] * 1.2 + 200 * 1024 ** 2
    if lfs.free_bytes(staging_dir()) < need:
        raise UpdateError(f"Not enough disk space for the update: about {need / 1024 ** 3:.1f} GB needed.")
    part = final.with_suffix(".exe.part")
    STATE["cancel"] = False
    try:
        await _fetch(pkg["url"], part, pkg["size"])
    except UpdateError:
        raise
    except (httpx.HTTPError, OSError) as e:          # connection lost, timeout, server error: resumable
        raise UpdateError(f"The download was interrupted ({type(e).__name__}). Press Download again to resume; "
                          "the part already downloaded is kept.") from None
    finally:
        STATE["download"] = None
    if part.stat().st_size != pkg["size"] or _sha256(part) != pkg["sha256"]:
        part.unlink(missing_ok=True)
        raise UpdateError("The downloaded file doesn't match the signed size/checksum; it was deleted.")
    if pkg.get("authenticode_subject"):
        ok, detail = await asyncio.to_thread(_verify_publisher, part, pkg["authenticode_subject"])
        if not ok:
            part.unlink(missing_ok=True)
            raise UpdateError(f"The installer's Windows signature is not valid for the expected publisher ({detail}).")
    os.replace(part, final)
    STATE["download"] = None
    return _prepared(version, final, pkg)


def _prepared(version: str, path: Path, pkg: dict) -> dict[str, Any]:
    _save(prepared={"version": version, "file": path.name, "sha256": pkg["sha256"], "size": pkg["size"],
                    "prepared_at": time.time()})
    return {"prepared": True, "version": version, "size": pkg["size"]}


def cancel_download() -> dict[str, Any]:
    """Stop a running update download at the next chunk (the .part file is kept for resuming)."""
    STATE["cancel"] = True
    return {"cancelling": True}


def _launch(path: Path) -> None:
    """Start the installer visibly and detached (the installer will stop this app)."""
    subprocess.Popen([str(path)], close_fds=True, creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))


def install(confirm: bool) -> dict[str, Any]:
    if not confirm:
        raise UpdateError("Installing an update needs your confirmation (confirm=true).")
    prep = cfg().get("prepared")
    if not prep:
        raise UpdateError("No verified update is ready. Download it first.")
    path = staging_dir() / prep["file"]
    if not path.exists() or _sha256(path) != prep["sha256"]:      # re-verify right before running it
        _save(prepared=None)
        raise UpdateError("The prepared installer changed or is missing; download it again.")
    archive_dir().mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, archive_dir() / path.name)                # kept for a manual rollback
    _save(installing={"from": __version__, "to": prep["version"], "started_at": time.time()})
    launched = platform.system() == "Windows"
    if launched:
        _launch(path)
    log.info("Update %s -> %s: installer %s after user confirmation", __version__, prep["version"],
             "started" if launched else "staged (not Windows: run it manually)")
    return {"launched": launched, "from": __version__, "to": prep["version"], "installer": path.name,
            "note": "The installer stops Abhify.AI, backs up your data and keeps it. Start Abhify.AI again when it finishes."}


def record_startup() -> dict[str, Any] | None:
    """On startup: did an update we started finish? Recorded for the status screen."""
    inst = cfg().get("installing")
    if not inst:
        return None
    ok = Version(__version__) >= Version(inst["to"])
    entry = {**inst, "finished_at": time.time(), "result": "installed" if ok else "not installed (still the old version)"}
    _save(installing=None, prepared=None if ok else cfg().get("prepared"), last_install=entry)
    return entry


def status() -> dict[str, Any]:
    c = cfg()
    return {"current": __version__, "configured": bool(c.get("manifest_url") and c.get("trusted_keys")),
            "check_on_start": bool(c.get("check_on_start")), "last_check_at": c.get("last_check_at"),
            "last_result": c.get("last_result"), "prepared": c.get("prepared"), "last_install": c.get("last_install"),
            "download": STATE["download"],
            "archive": sorted(p.name for p in archive_dir().glob("*.exe")) if archive_dir().exists() else []}


async def periodic(stop: asyncio.Event) -> None:
    """Background checks while the app runs (only if the owner enabled check_on_start)."""
    await asyncio.sleep(60)
    while not stop.is_set():
        if cfg().get("check_on_start"):
            try:
                await check()
            except Exception as e:
                log.warning("Scheduled update check failed: %s", type(e).__name__)
        try:
            await asyncio.wait_for(stop.wait(), timeout=float(cfg().get("check_interval_hours") or 24) * 3600)
        except asyncio.TimeoutError:
            pass


# --------------------------------------------------------------------------- model updates (only when asked)
REGISTRY = "https://registry.ollama.ai"


def _registry_ref(model: str) -> tuple[str, str]:
    name, _, tag = model.partition(":")
    return (name if "/" in name else f"library/{name}"), (tag or "latest")


async def check_models() -> dict[str, Any]:
    import system_service as system
    from config import get_settings
    active = system.active_models()
    if not _internet():
        return {"error": "Offline: can't check for model updates.", "models": []}
    out = []
    async with httpx.AsyncClient(timeout=15) as client:
        tags = (await client.get(f"{get_settings().ollama_base_url}/api/tags")).json().get("models", [])
        for role, model in active.items():
            local = next((m.get("digest") for m in tags if system.model_installed(model, [m["name"]])), None)
            repo, tag = _registry_ref(model)
            try:
                r = await client.head(f"{REGISTRY}/v2/{repo}/manifests/{tag}",
                                      headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json"})
                remote = (r.headers.get("docker-content-digest") or "").removeprefix("sha256:")
            except Exception:
                remote = ""
            local_d = (local or "").removeprefix("sha256:")
            out.append({"model": model, "role": role, "installed": bool(local),
                        "update_available": bool(local_d and remote and local_d != remote),
                        "unknown": not (local_d and remote)})
    return {"models": out}


async def apply_model_update(model: str, confirm: bool) -> dict[str, Any]:
    """Keep the working model as <name>-abhify-backup, pull the new one, validate, restore on failure."""
    import system_service as system
    from config import get_settings
    if not confirm:
        raise UpdateError("Updating a model downloads it again; confirm first (confirm=true).")
    if model not in system.active_models().values():
        raise UpdateError("Only the models in use can be updated here.")
    base = get_settings().ollama_base_url
    backup = model.replace(":", "-") + "-abhify-backup"
    async with httpx.AsyncClient(timeout=60) as client:
        r = await client.post(f"{base}/api/copy", json={"source": model, "destination": backup})
        if r.status_code >= 400:
            raise UpdateError("Couldn't make a backup copy of the current model; nothing was changed.")
    await system.start_pull(model, confirm=True)
    while system.PULLS.get(model, {}).get("status") == "downloading":
        await asyncio.sleep(0.5)
    pulled = system.PULLS.get(model, {}).get("status") == "done"
    ok = pulled and (await system.test_inference(model) if model == system.active_models()["llm"]
                     else {"ok": await _embeds(model)}).get("ok")
    if not ok:
        async with httpx.AsyncClient(timeout=60) as client:
            await client.post(f"{base}/api/copy", json={"source": backup, "destination": model})
        raise UpdateError(f"The updated {model} didn't pass validation; the previous version was restored.")
    return {"updated": model, "backup": backup,
            "note": f"The previous version is kept as '{backup}'. Remove it once you're happy with the update."}


async def _embeds(model: str) -> bool:
    from config import get_settings
    async with httpx.AsyncClient(timeout=120) as client:
        r = await client.post(f"{get_settings().ollama_base_url}/api/embed", json={"model": model, "input": "test"})
    return r.status_code == 200 and bool((r.json().get("embeddings") or [[]])[0])


async def remove_model_backup(model: str) -> dict[str, Any]:
    from config import get_settings
    backup = model.replace(":", "-") + "-abhify-backup"
    async with httpx.AsyncClient(timeout=60) as client:
        r = await client.request("DELETE", f"{get_settings().ollama_base_url}/api/delete", json={"model": backup})
    return {"removed": r.status_code < 400, "backup": backup}
