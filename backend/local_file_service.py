"""
local_file_service.py: where Abhify.AI keeps each installation's local data.

Program files and user data are kept apart. User data lives in an OS-appropriate
per-user folder, never inside the (possibly read-only) installation folder:

    Windows : %LOCALAPPDATA%\\AbhifyAI
    macOS   : ~/Library/Application Support/AbhifyAI
    Linux   : $XDG_DATA_HOME/AbhifyAI  (default ~/.local/share/AbhifyAI)

Override order (highest first):
    1. ABHIFY_DATA_DIR (or legacy DATA_DIR, used by Docker) environment variable
    2. a relocation pointer written by `relocate_data_dir()` at
       <default root>/config/location.json
    3. the OS default above

Layout:
    AbhifyAI/
    ├── config/settings.json        machine config: model profile, feature switches
    ├── data/abhify.db              SQLite: progress, conversations, memories, documents
    ├── data/chroma/                ChromaDB vector store
    ├── data/documents/uploads/     original files added through the app
    ├── data/memory/                reserved for memory exports
    ├── data/backups/               automatic + manual backups (zip / .db)
    ├── logs/
    └── cache/                      restore staging, temp files

Model weights are NOT stored here: Ollama keeps them in its own model folder.
"""
from __future__ import annotations

import json
import logging
import os
import platform
import shutil
import sqlite3
import tempfile
import time
import zipfile
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any

from version import APP_ID, __version__

log = logging.getLogger(__name__)

BACKEND_DIR = Path(__file__).resolve().parent
LEGACY_DATA_DIR = BACKEND_DIR / "data"          # v1.0.0 stored data next to the code

_root_cache: Path | None = None


# --------------------------------------------------------------------------- locations
def default_root() -> Path:
    """OS default per-user data root (no overrides)."""
    system = platform.system()
    if system == "Windows":
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
    elif system == "Darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share")
    return base / APP_ID


def _pointer_file() -> Path:
    return default_root() / "config" / "location.json"


def resolve_root() -> Path:
    """Active data root, honouring env override and relocation pointer."""
    env = os.environ.get("ABHIFY_DATA_DIR") or os.environ.get("DATA_DIR")
    if env:
        return Path(env).expanduser().resolve()
    ptr = _pointer_file()
    if ptr.is_file():
        try:
            target = Path(json.loads(ptr.read_text(encoding="utf-8"))["data_root"])
            if target.is_dir():
                return target
            log.error("Relocated data root %s is missing; falling back to default", target)
        except (ValueError, KeyError, OSError) as e:
            log.error("Ignoring unreadable location.json: %s", e)
    return default_root()


def root() -> Path:
    """Cached active root (changing it requires an app restart)."""
    global _root_cache
    if _root_cache is None:
        _root_cache = resolve_root()
    return _root_cache


def reset_cache() -> None:
    """For tests: forget the cached root so env changes take effect."""
    global _root_cache
    _root_cache = None


def config_dir() -> Path: return root() / "config"
def data_dir() -> Path: return root() / "data"
def db_path() -> Path: return data_dir() / "abhify.db"
def chroma_dir() -> Path: return data_dir() / "chroma"
def documents_dir() -> Path: return data_dir() / "documents"
def uploads_dir() -> Path: return documents_dir() / "uploads"
def memory_dir() -> Path: return data_dir() / "memory"
def backups_dir() -> Path: return data_dir() / "backups"
def logs_dir() -> Path: return root() / "logs"
def cache_dir() -> Path: return root() / "cache"
def settings_path() -> Path: return config_dir() / "settings.json"
def default_knowledge_dir() -> Path: return Path.home() / "Abhify_Knowledge"


def ensure_layout() -> Path:
    """Create the directory layout (idempotent). Returns the root."""
    for d in (config_dir(), data_dir(), chroma_dir(), uploads_dir(), memory_dir(),
              backups_dir(), logs_dir(), cache_dir()):
        d.mkdir(parents=True, exist_ok=True)
    return root()


def is_writable(path: Path) -> bool:
    """True if we can create and delete a file inside `path` (creating it if needed)."""
    try:
        path.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path, prefix=".write-test-", delete=True):
            pass
        return True
    except OSError:
        return False


def free_bytes(path: Path) -> int:
    """Free space on the drive holding `path` (walks up to an existing parent)."""
    p = path
    while not p.exists() and p != p.parent:
        p = p.parent
    return shutil.disk_usage(p).free


def dir_size(path: Path) -> int:
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file()) if path.exists() else 0


# --------------------------------------------------------------------------- settings.json
DEFAULT_SETTINGS: dict[str, Any] = {
    "setup_completed": False,
    "model_profile": "balanced",
    "models": {"llm": "qwen2.5", "embed": "nomic-embed-text"},
    "features": {"web_search": True, "voice_whisper": True, "local_tts": False, "folder_watcher": False},
    "knowledge": {
        "folder": None,                 # None = default %USERPROFILE%\Abhify_Knowledge (created on first start)
        "watcher_enabled": False,       # the user turns the watcher on explicitly
        "collection": "knowledge_base", # active Chroma collection (changes only after a validated rebuild)
        "index_embed_model": None,      # embedding model that built the active collection
        "previous_collections": [],     # kept after a rebuild until the user cleans them up
        "max_file_mb": 20,
    },
    "companion": {"enabled": False, "token_sha256": None, "created_at": None,
                  "registration_mode": "invite_only",   # invite_only | public (see membership.py)
                  "lan_access": False},                 # listen on all interfaces (plain Wi-Fi HTTP); off = 127.0.0.1
    "updates": {"manifest_url": None, "check_on_start": False,   # opt-in; nothing is checked by default
                "trusted_keys": {},                # {key_id: base64 Ed25519 PUBLIC key} of the publisher (updates.py)
                "check_interval_hours": 24},
}


def load_settings() -> dict[str, Any]:
    """settings.json merged over defaults (missing keys get defaults)."""
    data: dict[str, Any] = {}
    p = settings_path()
    if p.is_file():
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (ValueError, OSError) as e:
            log.error("settings.json unreadable (%s); using defaults", e)
    merged = json.loads(json.dumps(DEFAULT_SETTINGS))
    for k, v in data.items():
        if isinstance(v, dict) and isinstance(merged.get(k), dict):
            merged[k].update(v)
        else:
            merged[k] = v
    return merged


def save_settings(settings: dict[str, Any]) -> None:
    """Atomic write: never leaves a half-written settings.json behind."""
    config_dir().mkdir(parents=True, exist_ok=True)
    tmp = settings_path().with_suffix(".json.tmp")
    tmp.write_text(json.dumps(settings, indent=2), encoding="utf-8")
    os.replace(tmp, settings_path())


def update_settings(**changes: Any) -> dict[str, Any]:
    s = load_settings()
    for k, v in changes.items():
        if isinstance(v, dict) and isinstance(s.get(k), dict):
            s[k].update(v)
        else:
            s[k] = v
    save_settings(s)
    return s


# --------------------------------------------------------------------------- legacy migration (v1 -> v2)
def migrate_legacy_data(legacy: Path | None = None) -> dict[str, Any] | None:
    """Copy v1.0.0 data (backend/data/progress.db + chroma/) into the new data root.

    Copy, never move: the old folder is left untouched so nothing is lost if
    anything goes wrong. Runs only once, and only when the new DB doesn't exist yet.
    """
    legacy = legacy or LEGACY_DATA_DIR
    marker = config_dir() / "migrated_from_v1.json"
    if marker.exists() or db_path().exists():
        return None
    old_db, old_chroma = legacy / "progress.db", legacy / "chroma"
    if not old_db.exists() and not old_chroma.exists():
        return None
    if legacy.resolve() == data_dir().resolve():
        return None

    report: dict[str, Any] = {"from": str(legacy), "to": str(data_dir()), "at": datetime.now().isoformat()}
    if old_db.exists():
        _sqlite_copy(old_db, db_path())
        report["database"] = "copied progress.db -> abhify.db"
    if old_chroma.exists() and not any(chroma_dir().iterdir()):
        shutil.copytree(old_chroma, chroma_dir(), dirs_exist_ok=True)
        report["chroma"] = "copied"
    marker.write_text(json.dumps(report, indent=2), encoding="utf-8")
    log.info("Migrated v1 data: %s", report)
    return report


def _sqlite_copy(src: Path, dst: Path) -> None:
    """Consistent copy of a live SQLite DB using the backup API."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    s, d = sqlite3.connect(src), sqlite3.connect(dst)
    try:
        s.backup(d)
    finally:                       # `with sqlite3.connect()` does NOT close; Windows keeps the file locked
        d.close()
        s.close()


# --------------------------------------------------------------------------- backup / restore
BACKUP_INCLUDE = ("config", "data/abhify.db", "data/chroma", "data/documents", "data/memory",
                  # JSON files of later features (no database change): AI tasks, projects, career practice
                  "data/tasks.json", "data/projects.json", "data/career_mcq.json")


def create_backup(label: str = "manual", dest_dir: Path | None = None, schema_version: int | None = None) -> Path:
    """Zip the user's data (DBs copied consistently). Excludes backups/, logs/, cache/."""
    dest_dir = dest_dir or backups_dir()
    dest_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = dest_dir / f"abhify-backup-{label}-{stamp}.zip"
    with tempfile.TemporaryDirectory(dir=cache_dir()) as tmp:
        tmp_root = Path(tmp)
        for rel in BACKUP_INCLUDE:
            src = root() / rel
            if not src.exists():
                continue
            dst = tmp_root / rel
            if src.is_file() and src.suffix in (".db", ".sqlite3"):
                _sqlite_copy(src, dst)
            elif src.is_file():
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)
            else:
                shutil.copytree(src, dst, ignore=_ignore_sqlite_journals, dirs_exist_ok=True)
                for db in src.rglob("*.sqlite3"):           # re-copy live sqlite files consistently
                    _sqlite_copy(db, dst / db.relative_to(src))
        manifest = {"app": APP_ID, "app_version": __version__, "schema_version": schema_version,
                    "created": datetime.now().isoformat(), "label": label}
        (tmp_root / "backup-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for f in tmp_root.rglob("*"):
                if f.is_file():
                    z.write(f, f.relative_to(tmp_root).as_posix())
    log.info("Backup written: %s", out)
    return out


def _ignore_sqlite_journals(_dir: str, names: list[str]) -> list[str]:
    return [n for n in names if n.endswith(("-wal", "-shm", "-journal"))]


def list_backups() -> list[dict[str, Any]]:
    out = []
    for f in sorted(backups_dir().glob("abhify-*"), key=lambda p: p.stat().st_mtime, reverse=True):
        out.append({"name": f.name, "size": f.stat().st_size,
                    "created": datetime.fromtimestamp(f.stat().st_mtime).isoformat(timespec="seconds")})
    return out


def validate_backup(zip_path: Path, max_schema: int) -> dict[str, Any]:
    """Check a backup zip is ours, safe to extract, and not from a newer schema."""
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        for n in names:                                     # zip-slip protection
            p = PurePosixPath(n)
            if p.is_absolute() or ".." in p.parts or ":" in n:
                raise ValueError(f"Unsafe path in backup: {n}")
            if not (n == "backup-manifest.json" or n.startswith(("config/", "data/"))):
                raise ValueError(f"Unexpected file in backup: {n}")
        try:
            manifest = json.loads(z.read("backup-manifest.json"))
        except KeyError:
            raise ValueError("Not an Abhify.AI backup (manifest missing)")
    if manifest.get("app") != APP_ID:
        raise ValueError("Not an Abhify.AI backup")
    sv = manifest.get("schema_version")
    if isinstance(sv, int) and sv > max_schema:
        raise ValueError(f"Backup schema v{sv} is newer than this app supports (v{max_schema}). Update the app first.")
    return manifest


def stage_restore(zip_path: Path, max_schema: int) -> dict[str, Any]:
    """Extract a validated backup into cache/restore-staging. Applied on next start."""
    manifest = validate_backup(zip_path, max_schema)
    staging = cache_dir() / "restore-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(staging)
    (cache_dir() / "restore-pending.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def apply_pending_restore() -> dict[str, Any] | None:
    """Run at startup BEFORE databases are opened.

    The current data is moved aside to backups/pre-restore-<time>/ (not deleted),
    then the staged backup is moved into place.
    """
    pending = cache_dir() / "restore-pending.json"
    staging = cache_dir() / "restore-staging"
    if not pending.exists() or not staging.exists():
        return None
    manifest = json.loads(pending.read_text(encoding="utf-8"))
    aside = backups_dir() / f"pre-restore-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    aside.mkdir(parents=True)
    for rel in BACKUP_INCLUDE:
        cur = root() / rel
        if cur.exists():
            (aside / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(cur), str(aside / rel))
    for rel in BACKUP_INCLUDE:
        new = staging / rel
        if new.exists():
            (root() / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(new), str(root() / rel))
    shutil.rmtree(staging, ignore_errors=True)
    pending.unlink()
    ensure_layout()
    log.warning("Restored backup from %s; previous data kept in %s", manifest.get("created"), aside)
    return {"restored": manifest, "previous_data": str(aside)}


# --------------------------------------------------------------------------- relocation
def relocate_data_dir(new_root: Path) -> dict[str, Any]:
    """Copy all user data to `new_root` and point the app at it (restart required).

    The old data stays where it is; delete it manually once you've confirmed the
    new location works. Not available when ABHIFY_DATA_DIR / DATA_DIR is set.
    """
    if os.environ.get("ABHIFY_DATA_DIR") or os.environ.get("DATA_DIR"):
        raise ValueError("Data location is fixed by the ABHIFY_DATA_DIR/DATA_DIR environment variable")
    new_root = new_root.expanduser()
    if not new_root.is_absolute():
        raise ValueError("Please choose an absolute folder path")
    new_root = new_root.resolve()
    cur = root().resolve()
    if new_root == cur or cur in new_root.parents:
        raise ValueError("The new location must be outside the current data folder")
    if new_root.exists() and any(new_root.iterdir()):
        raise ValueError("The target folder must be empty or not exist yet")
    if not is_writable(new_root):
        raise ValueError("The target folder is not writable")
    need = dir_size(cur)
    if free_bytes(new_root) < need * 1.1 + 50 * 1024 * 1024:
        raise ValueError(f"Not enough free space: need about {need / 1e9:.2f} GB")

    for rel in BACKUP_INCLUDE:
        src = cur / rel
        if not src.exists():
            continue
        dst = new_root / rel
        if src.is_file() and src.suffix == ".db":
            _sqlite_copy(src, dst)
        elif src.is_file():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        else:
            shutil.copytree(src, dst, ignore=_ignore_sqlite_journals)
            for db in src.rglob("*.sqlite3"):
                _sqlite_copy(db, dst / db.relative_to(src))
    copied = dir_size(new_root)
    if copied < need * 0.5:                                   # sanity check (journals excluded)
        raise ValueError("Copy verification failed; the old location is still in use")

    _pointer_file().parent.mkdir(parents=True, exist_ok=True)
    tmp = _pointer_file().with_suffix(".tmp")
    tmp.write_text(json.dumps({"data_root": str(new_root), "moved_at": time.time(), "previous": str(cur)}), encoding="utf-8")
    os.replace(tmp, _pointer_file())
    return {"new_root": str(new_root), "previous_root": str(cur), "bytes_copied": copied, "restart_required": True}


# --------------------------------------------------------------------------- knowledge folder policy
def knowledge_folder() -> Path:
    configured = load_settings()["knowledge"].get("folder")
    return Path(configured) if configured else default_knowledge_dir()


def validate_knowledge_folder(path: Path) -> Path:
    """A folder the user may choose to watch. Refuses broad or sensitive locations so the
    watcher can never be pointed at a whole drive, the system, or Abhify.AI's own data."""
    path = Path(path).expanduser()
    if not path.is_absolute():
        raise ValueError("Please choose an absolute folder path")
    path = path.resolve()
    home = Path.home().resolve()
    if path == Path(path.anchor):
        raise ValueError("A whole drive can't be watched. Choose a specific folder.")
    if path == home:
        raise ValueError("Your entire user folder can't be watched. Choose a subfolder such as Documents/Notes.")
    forbidden = [root(), Path(os.environ.get("SystemRoot", r"C:\Windows")),
                 Path(os.environ.get("ProgramFiles", r"C:\Program Files")),
                 Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")),
                 Path(os.environ.get("ProgramData", r"C:\ProgramData")),
                 home / "AppData", home / ".ollama", BACKEND_DIR.parent]
    for f in forbidden:
        try:
            f = f.resolve()
        except OSError:
            continue
        if path == f or f in path.parents:
            raise ValueError(f"This location can't be watched ({f}).")
    if root().resolve().is_relative_to(path):
        raise ValueError("The knowledge folder can't contain Abhify.AI's data folder.")
    if path.exists() and not path.is_dir():
        raise ValueError("That path is a file, not a folder")
    return path
