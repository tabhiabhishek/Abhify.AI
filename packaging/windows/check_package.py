"""
check_package.py: refuse to ship a package that contains user data or secrets.

Scans the staged application files (not the third-party runtime) for:
  * forbidden names: .env files, keys/certificates, SQLite databases, user-data
    and virtual-env folders, tests, logs, backups, git metadata
  * secret-looking content in text files (private keys, API tokens)
Exit code 1 (and a list) if anything is found. Used by build.py and the tests.
"""
from __future__ import annotations

import fnmatch
import re
import sys
from pathlib import Path

FORBIDDEN_NAMES = [".env", "*.env", ".env.*", "*.pem", "*.key", "*.pfx", "*.p12", "id_rsa*", "*.kdbx",
                   "*.db", "*.sqlite", "*.sqlite3", "*.log", "*.zip", "settings.json", "location.json"]
FORBIDDEN_DIRS = {".git", ".venv", "venv", "data", "tests", "logs", "backups", "__pycache__", ".pytest_cache",
                  "AbhifyAI", "chroma", "uploads"}
SECRET_RE = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----|\bAKIA[0-9A-Z]{16}\b|\bgh[pousr]_[A-Za-z0-9]{36,}\b|"
                       r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_\-]{24,}\b|\bxox[baprs]-[A-Za-z0-9-]{10,}\b")
TEXT_SUFFIXES = {".py", ".pyw", ".js", ".html", ".css", ".json", ".md", ".txt", ".nsi", ".ini", ".cfg", ".toml", ".yml"}


def problems(app_dir: Path) -> list[str]:
    found = []
    for p in app_dir.rglob("*"):
        rel = p.relative_to(app_dir)
        if any(part in FORBIDDEN_DIRS for part in rel.parts[:-1] + ((rel.parts[-1],) if p.is_dir() else ())):
            if p.is_dir():
                found.append(f"forbidden folder: {rel}")
            continue
        if p.is_dir():
            continue
        if any(fnmatch.fnmatch(p.name.lower(), pat) for pat in FORBIDDEN_NAMES):
            found.append(f"forbidden file: {rel}")
        elif p.suffix.lower() in TEXT_SUFFIXES and p.stat().st_size < 2_000_000:
            if SECRET_RE.search(p.read_text(encoding="utf-8", errors="ignore")):
                found.append(f"secret-like content: {rel}")
    return sorted(set(found))


if __name__ == "__main__":
    target = Path(sys.argv[1])
    issues = problems(target)
    for i in issues:
        print("  x", i)
    print(f"{len(issues)} problem(s) in {target}")
    sys.exit(1 if issues else 0)
