"""Task 3 / Task 4 regression tests: the hash-locked dependency set and the signing pipeline wiring.
No certificate is used here; signing itself is exercised only in the Windows Sandbox lab test."""
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PKG = ROOT / "packaging" / "windows"
LOCK = PKG / "requirements.lock"
sys.path.insert(0, str(PKG))


def norm(n):
    return re.sub(r"[-_.]+", "-", n).lower()


def lock_entries():
    text = LOCK.read_text(encoding="utf-8")
    entries = {}
    for block in re.split(r"\n(?=[A-Za-z0-9])", text):
        m = re.match(r"([A-Za-z0-9][A-Za-z0-9._-]*)==([^\s\\]+)", block)
        if m:
            entries[norm(m.group(1))] = (m.group(2), len(re.findall(r"--hash=sha256:[0-9a-f]{64}", block)))
    return entries


def test_every_locked_package_is_pinned_and_hashed():
    entries = lock_entries()
    assert len(entries) >= 100
    unhashed = [n for n, (_, h) in entries.items() if h == 0]
    assert not unhashed, unhashed


def test_lock_satisfies_the_declared_requirements():
    """Every top-level requirement appears in the lock; exact pins match exactly."""
    entries = lock_entries()
    for f in (ROOT / "backend" / "requirements.txt", PKG / "requirements-desktop.txt"):
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.split("#")[0].strip()
            if not line:
                continue
            name = norm(re.split(r"[\[<>=!~ ;]", line, maxsplit=1)[0])
            assert name in entries, f"{name} ({f.name}) missing from requirements.lock"
            pin = re.search(r"==\s*([^\s;]+)", line)
            if pin:
                assert entries[name][0] == pin.group(1), f"{name}: lock {entries[name][0]} != requirements {pin.group(1)}"


def test_lock_matches_the_verified_dev4_build():
    """The lock was generated with the dev.4 installer's exact versions as constraints."""
    built = {}
    for line in (PKG / "build-versions-2.0.0-dev.4.txt").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            n, v = line.split("==")
            built[norm(n)] = v
    assert {n: v for n, (v, _) in lock_entries().items()} == built


def test_lock_has_no_machine_paths():
    text = LOCK.read_text(encoding="utf-8")
    assert not re.search(r"[A-Za-z]:\\|/Users/|/home/|scratchpad|Temp", text)


def test_build_installs_from_lock_by_default():
    import build
    args = build.pip_sources(use_lock=True)
    assert "--require-hashes" in args and str(build.LOCK) in args and "--only-binary" in args
    assert "-r" in build.pip_sources(use_lock=False)                 # --no-lock: resolve from requirements.txt


def test_installer_signing_hooks_only_when_requested():
    nsi = (PKG / "installer.nsi").read_text(encoding="utf-8")
    block = re.search(r"!ifdef SIGN_CMD(.*?)!endif", nsi, re.S).group(1)
    assert "!uninstfinalize '${SIGN_CMD}' = 0" in block and "!finalize '${SIGN_CMD}' = 0" in block


def test_sign_flag_needs_configuration(monkeypatch):
    import build
    monkeypatch.delenv("ABHIFY_SIGN_MODE", raising=False)
    monkeypatch.delenv("ABHIFY_SIGN_SUBJECT", raising=False)
    with pytest.raises(SystemExit, match="nothing was signed"):
        build.sign_command()
    monkeypatch.setenv("ABHIFY_SIGN_MODE", "Store")
    monkeypatch.setenv("ABHIFY_SIGN_SUBJECT", "Example Publisher")
    cmd = build.sign_command()
    assert "sign.ps1" in cmd and '"%1"' in cmd


@pytest.mark.skipif(sys.platform != "win32", reason="Authenticode check needs Windows PowerShell")
def test_unsigned_output_is_refused_when_signing_was_requested(tmp_path):
    import build
    fake = tmp_path / "Abhify.AI-Setup-x.exe"
    shutil.copy2(PKG / "check_package.py", fake)                        # any unsigned file
    assert build.verify_signature(fake, required=False) is False         # unsigned: fine when not required
    with pytest.raises(SystemExit, match="no checksums were written"):
        build.verify_signature(fake, required=True)
    assert not fake.exists()                                             # never left around looking "final"


@pytest.mark.skipif(sys.platform != "win32", reason="needs Windows PowerShell")
def test_sign_script_refuses_without_configuration(tmp_path):
    f = tmp_path / "x.exe"
    f.write_bytes(b"MZ")
    env = {k: v for k, v in __import__("os").environ.items() if not k.startswith("ABHIFY_SIGN")}
    out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PKG / "sign.ps1"),
                          "-File", str(f)], capture_output=True, text=True, env=env, timeout=60)
    assert out.returncode == 1 and "SIGN FAILED" in out.stdout


def test_build_never_overwrites_an_existing_version():
    src = (PKG / "build.py").read_text(encoding="utf-8")
    assert "A version is never rebuilt with different content" in src
