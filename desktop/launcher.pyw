"""
launcher.pyw: Abhify.AI desktop launcher (what the Start Menu shortcut runs).

    pythonw launcher.pyw            start (or, if already running, just open the UI)
    pythonw launcher.pyw --stop     stop the running backend cleanly
    python  launcher.pyw --backup   back up user data (the installer runs this before an upgrade)
    python  launcher.pyw --status   print whether the backend is running

* Runs the FastAPI backend in-process with no console window and a tray icon
  (Open, Data folder, Knowledge folder, Quit).
* Single instance: a run file in <data root>/run/backend.json records pid and port;
  a second start only opens the browser.
* Listens on 127.0.0.1. Only when the user has turned on Companion access does it
  listen on the network (0.0.0.0) so a paired phone can connect.
* Port: ABHIFY_PORT or 8000; if that port is taken by another program, 8001-8010.
* Works from the source tree (desktop/ next to backend/) and from the installed
  layout (app/desktop/ next to app/backend/).
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parent / "backend"
sys.path.insert(0, str(BACKEND))

import local_file_service as lfs  # noqa: E402  (needs the path above)

APP_URL = "http://127.0.0.1:{port}/"
# Loopback calls must never go through a system proxy: Windows proxy settings can capture
# 127.0.0.1 and make a running instance look dead (seen in Windows Sandbox).
_LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# --------------------------------------------------------------------------- helpers
def run_file() -> Path:
    return lfs.root() / "run" / "backend.json"


def read_run() -> dict | None:
    try:
        return json.loads(run_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return str(pid) in out.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def health(port: int, timeout: float = 2.0) -> dict | None:
    try:
        with _LOCAL.open(f"http://127.0.0.1:{port}/ping", timeout=timeout) as r:
            data = json.loads(r.read())
            return data if data.get("app") == "Abhify.AI" else None
    except Exception:
        return None


def port_free(port: int, host: str = "127.0.0.1") -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind((host, port))
            return True
        except OSError:
            return False


def choose_port(preferred: int, host: str = "127.0.0.1") -> int:
    for port in [preferred, *range(8001, 8011)]:
        if port_free(port, host):
            return port
    raise RuntimeError("No free port between 8000 and 8010")


def bind_host() -> str:
    try:
        return "0.0.0.0" if lfs.load_settings()["companion"].get("enabled") else "127.0.0.1"
    except Exception:
        return "127.0.0.1"


def running_instance(wait: float = 15) -> dict | None:
    """The live instance from the run file, if any. If its process is alive but not answering yet
    (still starting, or busy), keep asking for up to `wait` seconds before treating it as dead."""
    info = read_run()
    if not info or not pid_alive(int(info.get("pid", 0))):
        return None
    end = time.time() + wait
    while True:
        if health(int(info["port"])):
            return info
        if time.time() >= end or not pid_alive(int(info.get("pid", 0))):
            return None
        time.sleep(1)


def open_ui(port: int) -> None:
    if not os.environ.get("ABHIFY_NO_BROWSER"):
        webbrowser.open(APP_URL.format(port=port))


# --------------------------------------------------------------------------- commands
def cmd_stop(wait: float = 15) -> int:
    info = read_run()
    if not info:
        print("Abhify.AI is not running.")
        return 0
    port, pid = int(info["port"]), int(info.get("pid", 0))
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{port}/system/shutdown", method="POST", data=b"")
        _LOCAL.open(req, timeout=5).read()
    except Exception:
        pass
    end = time.time() + wait
    while time.time() < end and pid_alive(pid):
        time.sleep(0.5)
    if pid_alive(pid) and os.name == "nt":                     # last resort, only our own recorded pid
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    run_file().unlink(missing_ok=True)
    print("Abhify.AI stopped.")
    return 0


def cmd_backup(label: str = "pre-upgrade") -> int:
    """Consistent backup of user data before an upgrade. No-op on a fresh machine."""
    if not lfs.db_path().exists():
        print("No existing data to back up.")
        return 0
    import database as db
    out = lfs.create_backup(label, schema_version=db.current_version(sqlite_conn()))
    print(f"Backup written: {out}")
    return 0


def sqlite_conn():
    import sqlite3
    return sqlite3.connect(lfs.db_path())


def cmd_status() -> int:
    info = running_instance()
    print(json.dumps({"running": bool(info), **(info or {})}))
    return 0


def cmd_start() -> int:
    lfs.ensure_layout()
    existing = running_instance()
    if existing:
        open_ui(int(existing["port"]))
        return 0

    host = bind_host()
    port = choose_port(int(os.environ.get("ABHIFY_PORT", "8000")), host)
    os.environ.update(ABHIFY_PORT=str(port), ABHIFY_BOUND_HOST=host)

    log_path = lfs.logs_dir() / "launcher.log"
    if sys.stdout is None or "pythonw" in Path(sys.executable).name.lower():   # no console: keep a log
        log = open(log_path, "a", encoding="utf-8", buffering=1)
        sys.stdout = sys.stderr = log

    import uvicorn
    import main                                              # the FastAPI app
    server = uvicorn.Server(uvicorn.Config(main.app, host=host, port=port, log_level="info", access_log=False))
    main.SHUTDOWN_HOOK["fn"] = lambda: setattr(server, "should_exit", True)

    run_file().parent.mkdir(parents=True, exist_ok=True)
    run_file().write_text(json.dumps({"pid": os.getpid(), "port": port, "host": host, "started": time.time(),
                                      "version": __import__("version").__version__}), encoding="utf-8")

    def open_when_ready():
        for _ in range(120):
            if health(port, 1):
                if not os.environ.get("ABHIFY_NO_BROWSER"):
                    open_ui(port)
                return
            time.sleep(0.5)
    threading.Thread(target=open_when_ready, daemon=True).start()
    icon = start_tray(port, server)
    try:
        server.run()
    finally:
        run_file().unlink(missing_ok=True)
        if icon:
            icon.stop()
    return 0


def start_tray(port: int, server) -> object | None:
    """System-tray icon. Optional: the app works without it (e.g. if pystray is missing)."""
    if os.environ.get("ABHIFY_NO_TRAY"):
        return None
    try:
        import pystray
        from PIL import Image
    except ImportError:
        return None
    icon_path = HERE.parent / "frontend" / "icons" / "icon-192.png"
    image = Image.open(icon_path) if icon_path.exists() else Image.new("RGB", (64, 64), "#0f172a")

    def open_folder(p: Path):
        p.mkdir(parents=True, exist_ok=True)
        os.startfile(str(p)) if os.name == "nt" else subprocess.run(["xdg-open", str(p)])

    menu = pystray.Menu(
        pystray.MenuItem("Open Abhify.AI", lambda: open_ui(port), default=True),
        pystray.MenuItem("Open data folder", lambda: open_folder(lfs.root())),
        pystray.MenuItem("Open knowledge folder", lambda: open_folder(lfs.knowledge_folder())),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit Abhify.AI", lambda: setattr(server, "should_exit", True)),
    )
    icon = pystray.Icon("Abhify.AI", image, f"Abhify.AI (port {port})", menu)
    icon.run_detached()
    return icon


def main_cli(argv: list[str]) -> int:
    if "--stop" in argv:
        return cmd_stop()
    if "--backup" in argv:
        i = argv.index("--backup")
        return cmd_backup(argv[i + 1] if len(argv) > i + 1 and not argv[i + 1].startswith("--") else "pre-upgrade")
    if "--status" in argv:
        return cmd_status()
    return cmd_start()


if __name__ == "__main__":
    sys.exit(main_cli(sys.argv[1:]))
