"""
build.py: build the Abhify.AI Windows installer.

    py -3.11 packaging\\windows\\build.py
    py -3.11 packaging\\windows\\build.py --site-packages-from backend\\.venv\\Lib\\site-packages   (offline)

Steps
  1. read the version from backend/version.py (single source of truth)
  2. fetch + verify (SHA-256) the official Python 3.11 embeddable runtime and portable NSIS
  3. stage:  runtime\\ (embeddable Python + all dependencies)  app\\ (backend, frontend, launcher)
  4. verify the bundled runtime imports every dependency in isolation (-I)
  5. refuse to continue if the staged app contains user data or secrets (check_package.py)
  6. compile the NSIS installer
  7. write dist\\windows\\: installer, SHA256SUMS.txt, RELEASE_NOTES.md, INSTALL.md, latest.json template

Build machine: Windows x64 with Python 3.11 (same minor version as the bundled runtime, so
compiled wheels match). Internet for the first build (downloads are cached in packaging\\.cache),
or --site-packages-from a verified local environment. No admin rights; nothing installed
system-wide.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "packaging" / ".cache"
STAGE = ROOT / "build" / "windows" / "staging"
DIST = ROOT / "dist" / "windows"

PY_VERSION = "3.11.9"
# "SET_ON_FIRST_BUILD": the hash is recorded in packaging/.cache/<file>.sha256 on the first
# download (trust on first use) and enforced on every later build. Replace with the
# published value once confirmed (see docs/RELEASE_PROCESS.md).
DOWNLOADS = {
    "python": {"url": f"https://www.python.org/ftp/python/{PY_VERSION}/python-{PY_VERSION}-embed-amd64.zip",
               "file": f"python-{PY_VERSION}-embed-amd64.zip", "sha256": "SET_ON_FIRST_BUILD"},
    "nsis": {"url": "https://downloads.sourceforge.net/project/nsis/NSIS%203/3.10/nsis-3.10.zip",
             "file": "nsis-3.10.zip", "sha256": "SET_ON_FIRST_BUILD"},
}
APP_ALLOW = {"backend": ["*.py", "requirements.txt"], "desktop": ["launcher.pyw"], "frontend": ["**/*"]}
DEV_ONLY = {"pip", "setuptools", "wheel", "pytest", "_pytest", "pluggy", "iniconfig", "py.py",
            "_distutils_hack", "distutils-precedence.pth"}
RUNTIME_IMPORTS = ("fastapi, uvicorn, langgraph, langchain_ollama, langchain_chroma, chromadb, pypdf, ddgs, "
                   "watchdog, psutil, faster_whisper, pystray, PIL, packaging, httpx, pydantic_settings")


def log(msg: str) -> None:
    print(f"[build] {msg}", flush=True)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(name: str) -> Path:
    d = DOWNLOADS[name]
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / d["file"]
    if not dest.exists():
        log(f"downloading {d['url']}")
        tmp = dest.with_suffix(".part")
        urllib.request.urlretrieve(d["url"], tmp)
        tmp.replace(dest)
    digest = sha256(dest)
    pinned = CACHE / f"{d['file']}.sha256"
    expected = None if d["sha256"].startswith("SET_") else d["sha256"]
    expected = expected or (pinned.read_text().strip() if pinned.exists() else None)
    if expected and digest != expected:
        raise SystemExit(f"Checksum mismatch for {d['file']}: {digest} != {expected}. Delete it from {CACHE} and retry.")
    if not expected:
        pinned.write_text(digest)
        log(f"pinned {d['file']} sha256={digest}")
    return dest


def app_version() -> str:
    text = (ROOT / "backend" / "version.py").read_text(encoding="utf-8")
    return re.search(r'__version__\s*=\s*"([^"]+)"', text).group(1)


def stage_runtime(py_zip: Path, site_from: Path | None) -> None:
    rt = STAGE / "runtime"
    with zipfile.ZipFile(py_zip) as z:
        z.extractall(rt)
    pth = next(rt.glob("python*._pth"))
    pth.write_text("python311.zip\n.\nLib\\site-packages\nimport site\n", encoding="ascii")   # enable site-packages
    target = rt / "Lib" / "site-packages"
    target.mkdir(parents=True, exist_ok=True)
    if site_from:
        log(f"copying dependencies from {site_from} (offline build)")
        for item in site_from.iterdir():
            base = item.name.split("-")[0].lower()
            if item.name in DEV_ONLY or base in DEV_ONLY or item.name == "__pycache__":
                continue
            if item.is_dir():
                shutil.copytree(item, target / item.name)
            else:
                shutil.copy2(item, target / item.name)
    else:
        log("installing dependencies into the private runtime (this takes a few minutes)")
        subprocess.run([sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
                        "--no-warn-script-location", "--target", str(target),
                        "-r", str(ROOT / "backend" / "requirements.txt"),
                        "-r", str(ROOT / "packaging" / "windows" / "requirements-desktop.txt")], check=True)
    verify_runtime(rt)


def verify_runtime(rt: Path) -> None:
    """The bundled runtime must import everything the app needs using ONLY its own files (-I)."""
    out = subprocess.run([str(rt / "python.exe"), "-I", "-c", f"import {RUNTIME_IMPORTS}; print('runtime ok')"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("Bundled runtime is missing packages:\n" + out.stderr[-2000:])
    log(out.stdout.strip())


def stage_app() -> None:
    app = STAGE / "app"
    for folder, patterns in APP_ALLOW.items():
        src = ROOT / folder
        for pat in patterns:
            for f in src.glob(pat):
                if f.is_file() and "__pycache__" not in f.parts:
                    dst = app / folder / f.relative_to(src)
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(f, dst)


def make_icon() -> None:
    from PIL import Image
    Image.open(ROOT / "frontend" / "brand" / "logo-mark.png").save(
        STAGE / "Abhify.AI.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


def release_notes(version: str) -> str:
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    m = re.search(rf"## \[{re.escape(version)}\].*?(?=\n## \[|\Z)", text, re.S)
    return m.group(0) if m else f"## {version}\n\nSee CHANGELOG.md."


def main() -> None:
    ap = argparse.ArgumentParser(description="Build the Abhify.AI Windows installer")
    ap.add_argument("--site-packages-from", type=Path, default=None,
                    help="copy dependencies from this site-packages (same Python 3.11 x64) instead of downloading")
    args = ap.parse_args()
    if sys.version_info[:2] != (3, 11):
        raise SystemExit("Build with Python 3.11 so compiled wheels match the bundled runtime (py -3.11 ...)")
    version = app_version()
    vi = ".".join((re.findall(r"\d+", version) + ["0"] * 4)[:4])
    log(f"Abhify.AI {version}")
    py_zip, nsis_zip = fetch("python"), fetch("nsis")

    if STAGE.exists():
        shutil.rmtree(STAGE)
    STAGE.mkdir(parents=True)
    stage_runtime(py_zip, args.site_packages_from)
    stage_app()
    make_icon()
    shutil.copy2(ROOT / "docs" / "WINDOWS_INSTALLATION.md", STAGE / "INSTALL.md")
    (STAGE / "RELEASE_NOTES.md").write_text(release_notes(version), encoding="utf-8")

    sys.path.insert(0, str(Path(__file__).parent))
    from check_package import problems
    issues = problems(STAGE / "app")
    if issues:
        raise SystemExit("Refusing to package:\n  " + "\n  ".join(issues))
    log("package check: no user data or secrets in the app files")

    nsis_dir = CACHE / "nsis-3.10"
    if not (nsis_dir / "makensis.exe").exists():
        with zipfile.ZipFile(nsis_zip) as z:
            z.extractall(CACHE)
    DIST.mkdir(parents=True, exist_ok=True)
    out = DIST / f"Abhify.AI-Setup-{version}.exe"
    log("compiling installer (LZMA; takes a few minutes)")
    subprocess.run([str(nsis_dir / "makensis.exe"), "/V2", f"/DVERSION={version}", f"/DVIVERSION={vi}",
                    f"/DSTAGING={STAGE}", f"/DOUTFILE={out}", str(ROOT / "packaging" / "windows" / "installer.nsi")],
                   check=True)

    digest = sha256(out)
    (DIST / "SHA256SUMS.txt").write_text(f"{digest}  {out.name}\n", encoding="utf-8")
    shutil.copy2(STAGE / "RELEASE_NOTES.md", DIST / "RELEASE_NOTES.md")
    shutil.copy2(ROOT / "docs" / "WINDOWS_INSTALLATION.md", DIST / "INSTALL.md")
    (DIST / "latest.json.template").write_text(json.dumps({
        "version": version, "released": time.strftime("%Y-%m-%d"), "min_supported": "1.0.0",
        "notes": f"See RELEASE_NOTES.md for {version}",
        "windows": {"url": f"https://github.com/<owner>/<repo>/releases/download/v{version}/{out.name}",
                    "sha256": digest},
    }, indent=2), encoding="utf-8")
    log(f"done: {out} ({out.stat().st_size / 1024 ** 2:.0f} MB) sha256={digest}")


if __name__ == "__main__":
    main()
