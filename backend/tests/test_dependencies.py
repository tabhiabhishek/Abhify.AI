"""D2 regression test (dev.4): every third-party package the app imports must be declared in
backend/requirements.txt (or packaging/windows/requirements-desktop.txt for the launcher), either
directly or as a declared dependency of a package that is. Otherwise a clean build from the
requirements produces an app that can't start, even though the dev environment works."""
import ast
import importlib.metadata as md
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REQ_FILES = [ROOT / "backend" / "requirements.txt", ROOT / "packaging" / "windows" / "requirements-desktop.txt"]


def norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def declared() -> set[str]:
    out = set()
    for f in REQ_FILES:
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.split("#")[0].strip()
            if line:
                out.add(norm(re.split(r"[\[<>=!~ ;]", line, maxsplit=1)[0]))
    return out


def requires_closure(roots: set[str]) -> set[str]:
    """Declared packages plus everything they (transitively) require, from installed metadata."""
    seen, todo = set(), list(roots)
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        try:
            reqs = md.requires(name) or []
        except md.PackageNotFoundError:
            continue
        for r in reqs:
            if "extra ==" in r:
                if not any(f'extra == "{e}"' in r for e in ("standard",)):     # uvicorn[standard]
                    continue
            todo.append(norm(re.split(r"[\[<>=!~ ;(]", r, maxsplit=1)[0]))
    return seen


def third_party_imports() -> dict[str, set[str]]:
    files = list((ROOT / "backend").glob("*.py")) + [ROOT / "desktop" / "launcher.pyw"]
    local = {p.stem for p in (ROOT / "backend").glob("*.py")}
    mods: dict[str, set[str]] = {}
    for f in files:
        for n in ast.walk(ast.parse(f.read_text(encoding="utf-8"))):
            if isinstance(n, ast.Import):
                names = [a.name for a in n.names]
            elif isinstance(n, ast.ImportFrom) and n.module and n.level == 0:
                names = [n.module]
            else:
                continue
            for m in names:
                top = m.split(".")[0]
                if top not in local and top not in sys.stdlib_module_names:
                    mods.setdefault(top, set()).add(f.name)
    return mods


def test_every_third_party_import_is_declared():
    direct = declared()
    closure = requires_closure(direct)
    dists = md.packages_distributions()
    missing = {}
    for top, where in third_party_imports().items():
        candidates = {norm(d) for d in dists.get(top, [top])}
        if not candidates & closure:
            missing[top] = sorted(where)
    assert not missing, f"imported but not declared in requirements: {missing}"


def test_text_splitters_declared_directly():
    assert "langchain-text-splitters" in declared()
