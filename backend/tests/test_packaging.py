"""Installer & launcher: safe defaults, data preservation, no secrets in packages."""
import importlib.util
import json
import re
import socket
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PKG = ROOT / "packaging" / "windows"


def load_launcher():
    spec = importlib.util.spec_from_file_location("abhify_launcher", ROOT / "desktop" / "launcher.pyw")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture
def launcher(data_root):
    return load_launcher()


# --------------------------------------------------------------------------- launcher
def test_launcher_binds_localhost_unless_companion_enabled(launcher):
    import local_file_service as lfs
    assert launcher.bind_host() == "127.0.0.1"
    lfs.update_settings(companion={"enabled": True})
    assert launcher.bind_host() == "0.0.0.0"


def test_launcher_picks_next_free_port(launcher):
    with socket.socket() as busy:
        busy.bind(("127.0.0.1", 0))
        taken = busy.getsockname()[1]
        assert launcher.choose_port(taken) != taken


def test_launcher_single_instance_detection(launcher, monkeypatch):
    import os
    assert launcher.running_instance() is None                          # no run file
    launcher.run_file().parent.mkdir(parents=True, exist_ok=True)
    launcher.run_file().write_text(json.dumps({"pid": os.getpid(), "port": 8765}))
    monkeypatch.setattr(launcher, "health", lambda port, timeout=2: {"app": "Abhify.AI"})
    assert launcher.running_instance()["port"] == 8765                  # second start only opens the UI
    monkeypatch.setattr(launcher, "health", lambda port, timeout=2: None)
    assert launcher.running_instance() is None                          # stale run file is ignored


def test_backup_command_before_upgrade(launcher, migrated):
    import local_file_service as lfs
    import memory_service as mem
    mem.add_memory("survives upgrades")
    assert launcher.cmd_backup("pre-upgrade") == 0
    backups = [b["name"] for b in lfs.list_backups()]
    assert any(n.startswith("abhify-backup-pre-upgrade-") for n in backups)


def test_backup_command_noop_on_fresh_machine(launcher):
    assert launcher.cmd_backup() == 0                                   # no database yet: nothing to do


# --------------------------------------------------------------------------- package contents
def test_package_check_rejects_user_data_and_secrets(tmp_path):
    sys.path.insert(0, str(PKG))
    from check_package import problems
    app = tmp_path / "app"
    (app / "backend").mkdir(parents=True)
    (app / "backend" / "main.py").write_text("print('ok')")
    assert problems(app) == []
    (app / "backend" / ".env").write_text("SECRET=1")
    (app / "backend" / "data").mkdir()
    (app / "backend" / "data" / "abhify.db").write_bytes(b"x")
    (app / "backend" / "keys.py").write_text('K = "ghp_' + "a" * 36 + '"')
    found = "\n".join(problems(app))
    assert ".env" in found and "data" in found and "secret-like content" in found


def test_build_allowlist_excludes_dev_and_user_files():
    sys.path.insert(0, str(PKG))
    import build
    assert build.APP_ALLOW["backend"] == ["*.py", "requirements.txt"]  # no tests/, data/, .venv, .env
    assert {"pytest", "pip"} <= build.DEV_ONLY


# --------------------------------------------------------------------------- installer script safety
@pytest.fixture(scope="module")
def nsi():
    return (PKG / "installer.nsi").read_text(encoding="utf-8")


def test_installer_needs_no_admin_and_installs_per_user(nsi):
    assert "RequestExecutionLevel user" in nsi
    assert 'InstallDir "$LOCALAPPDATA\\Programs\\${APPNAME}"' in nsi
    assert "HKLM" not in nsi                                            # per-user registry only


def test_installer_upgrade_stops_and_backs_up_first(nsi):
    stop, backup, replace = nsi.index("--stop"), nsi.index("--backup pre-upgrade"), nsi.index('RMDir /r "$INSTDIR\\runtime"')
    assert stop < backup < replace


def test_uninstall_keeps_user_data_by_default(nsi):
    un = nsi[nsi.index('Section "Uninstall"'):]
    delete_data = un.index('RMDir /r "$LOCALAPPDATA\\AbhifyAI"')
    prompt = un.index("MessageBox MB_YESNO")
    assert prompt < delete_data and "MB_DEFBUTTON2" in un and "/SD IDNO IDNO keep_data" in un
    assert "AbhifyAI" not in nsi[:nsi.index('Section "Uninstall"')].split("RMDir")[-1]   # install never deletes data


def test_installer_never_downloads_models(nsi):
    assert not re.search(r"ollama pull|/api/pull|NSISdl|inetc", nsi, re.I)


def test_installer_checks_windows_version_and_64bit(nsi):
    assert "${AtLeastWin10}" in nsi and "${RunningX64}" in nsi
