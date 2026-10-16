"""dev.3 packaging regression tests: F1 (no build paths in bytecode), F6 (HTML install guide),
per-version build output, and one consistent version everywhere."""
import json
import re
import shutil
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PKG = ROOT / "packaging" / "windows"
sys.path.insert(0, str(PKG))


def app_version():
    return re.search(r'__version__\s*=\s*"([^"]+)"', (ROOT / "backend" / "version.py").read_text()).group(1)


# --------------------------------------------------------------------------- F1
def test_runtime_copy_skips_nested_bytecode(tmp_path, monkeypatch):
    """Exercise build.stage_runtime's copy loop on a fake site-packages with nested __pycache__."""
    import build
    site = tmp_path / "site"
    (site / "pkg" / "sub" / "__pycache__").mkdir(parents=True)
    (site / "pkg" / "__init__.py").write_text("x = 1")
    (site / "pkg" / "sub" / "__pycache__" / "m.cpython-311.pyc").write_bytes(b"D:\\Abhify.AI\\backend\\.venv")
    (site / "pkg" / "stray.pyc").write_bytes(b"x")
    (site / "__pycache__").mkdir()
    (site / "top.cpython-311.pyc").write_bytes(b"x")
    (site / "pytest").mkdir()
    # stage_runtime extracts the Python zip first; give it an empty one with a ._pth file
    import zipfile
    pyzip = tmp_path / "py.zip"
    with zipfile.ZipFile(pyzip, "w") as z:
        z.writestr("python311._pth", "")
    stage = tmp_path / "stage"
    monkeypatch.setattr(build, "STAGE", stage)
    for fn in ("bundle_vc_runtime", "check_dlls", "compile_bytecode", "verify_runtime", "check_no_build_paths"):
        monkeypatch.setattr(build, fn, lambda rt: None)
    build.stage_runtime(pyzip, site)
    copied = stage / "runtime" / "Lib" / "site-packages"
    assert (copied / "pkg" / "__init__.py").exists()
    assert not list(copied.rglob("__pycache__")) and not list(copied.rglob("*.pyc"))
    assert not (copied / "pytest").exists()


def test_build_paths_in_bytecode_fail_the_build(tmp_path):
    import build
    rt = tmp_path / "runtime"
    (rt / "Lib").mkdir(parents=True)
    (rt / "Lib" / "ok.pyc").write_bytes(b"\x00compiled Abhify.AI\\runtime\\Lib\\site-packages\\ok.py")
    build.check_no_build_paths(rt)                                     # neutral prefix: fine
    (rt / "Lib" / "bad.pyc").write_bytes(b"\x00" + str(build.ROOT / "backend" / ".venv" / "x.py").encode())
    with pytest.raises(SystemExit, match="build-machine paths"):
        build.check_no_build_paths(rt)


def test_build_never_writes_bytecode_with_staging_paths():
    src = (PKG / "build.py").read_text(encoding="utf-8")
    assert '"--no-compile"' in src                                     # online pip path
    assert '"-I", "-B", "-c"' in src                                   # import check writes no .pyc
    assert '"-s", str(site), "-p", PYC_PREFIX' in src                  # compileall strips the staging path
    order = [src.index(s) for s in ("compile_bytecode(rt)", "verify_runtime(rt)", "check_no_build_paths(rt)")]
    assert order == sorted(order)


def test_staged_runtime_bytecode_is_clean_if_built():
    rt = ROOT / "build" / "windows" / "staging" / "runtime"
    if not (rt / "python.exe").exists():
        pytest.skip("installer not built in this checkout")
    import build
    build.check_no_build_paths(rt)


# --------------------------------------------------------------------------- F6
def test_install_guide_html_is_complete_and_safe():
    from md2html import convert
    md = (ROOT / "docs" / "WINDOWS_INSTALLATION.md").read_text(encoding="utf-8")
    out = convert(md)
    assert out.startswith("<!doctype html>") and '<meta charset="utf-8">' in out and "viewport" in out
    assert "<h1>Installing Abhify.AI on Windows</h1>" in out
    assert out.count("<table>") == len(re.findall(r"^\|[\s:|-]+\|?$", md, re.M))   # every table converted
    assert "<script" not in out.lower() and 'href="http://' not in out          # no scripts, no insecure links
    assert re.search(r'<a href="[^"]+\.md"', out) is None              # relative .md links aren't dead links
    assert "**" not in out and "```" not in out                        # no raw markdown left
    for h in re.findall(r"^#+\s+(.*)$", md, re.M):
        assert re.sub(r"[`*]", "", h).split("(")[0].strip()[:20] in re.sub(r"<[^>]+>", "", out)


def test_md2html_escapes_html():
    from md2html import convert
    out = convert("# T\n\nA <script>alert(1)</script> & `<b>` [x](javascript:alert(1))")
    assert "<script>" not in out and "&lt;script&gt;" in out and "javascript:" not in out.split("<main>")[1].split("x")[0]
    assert 'href="javascript' not in out


def test_installer_finish_page_opens_html_guide():
    nsi = (PKG / "installer.nsi").read_text(encoding="utf-8")
    assert '!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR\\INSTALL.html"' in nsi
    assert "INSTALL.md\"\n" not in nsi.split("MUI_FINISHPAGE_SHOWREADME")[1].split("\n")[0]
    un = nsi[nsi.index('Section "Uninstall"'):]
    assert 'Delete "$INSTDIR\\INSTALL.html"' in un
    src = (PKG / "build.py").read_text(encoding="utf-8")
    assert 'STAGE / "INSTALL.html"' in src and 'STAGE / "INSTALL.md"' not in src


# --------------------------------------------------------------------------- versions and output
def test_each_version_builds_into_its_own_folder():
    src = (PKG / "build.py").read_text(encoding="utf-8")
    assert "dist = DIST / version" in src
    assert 'DIST / "SHA256SUMS.txt"' not in src                        # never overwrite another version's sums


def test_version_is_consistent():
    v = app_version()
    assert re.fullmatch(r"\d+\.\d+\.\d+(-(dev|rc)\.\d+)?", v)        # SemVer with -dev.N / -rc.N
    sw = (ROOT / "frontend" / "sw.js").read_text(encoding="utf-8")
    assert f"const CACHE_VERSION = 'abhify-v{v}';" in sw              # phones pick up the new shell
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    assert f"## [{v}]" in changelog
    assert f"`{v}`" in (ROOT / "README.md").read_text(encoding="utf-8")


def test_readme_documents_every_api_route():
    """README maintenance guard: every real route must appear in the README's API table."""
    import main
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    missing = []
    for r in main.app.routes:
        if not hasattr(r, "methods") or r.path.startswith(("/docs", "/redoc", "/openapi")):
            continue
        path = r.path.split("/{")[0]                                   # /memory/{memory_id} -> /memory
        parent, last = path.rsplit("/", 1)
        if f"`{path}" not in readme and f" {path}`" not in readme and f"{path}`" not in readme \
                and not re.search(re.escape(parent) + r"/\{[^}]*\b" + re.escape(last) + r"\b", readme):
            missing.append(r.path)
    assert not missing, f"routes not documented in README.md: {missing}"


def test_dev2_installer_untouched_if_present():
    """dev.2 must keep its published name and checksum (never reused for different content)."""
    exe = ROOT / "dist" / "windows" / "Abhify.AI-Setup-2.0.0-dev.2.exe"
    if not exe.exists():
        pytest.skip("dev.2 installer not in this checkout")
    import hashlib
    h = hashlib.sha256()
    with exe.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    assert h.hexdigest() == "9e299def998a379db51f8a10dbe363ad38f7ea8091c437a52a5434166752f8b2"
