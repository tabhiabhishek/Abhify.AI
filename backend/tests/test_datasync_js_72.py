"""7.2.0-dev.1: the Web app's vault (frontend/vault.js) and sync engine (frontend/sync-core.js) in Node, against the same
shared vectors and sync cases as the Python reference (vault_crypto.py, sync_core.py)."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
VECTORS = HERE / "data" / "vault_vectors.json"
CASES = HERE / "data" / "sync_cases.json"


def test_web_vault_and_sync_engine_match_the_reference():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(HERE / "js" / "datasync_harness.js"), str(VECTORS), str(CASES), str(ROOT / "frontend")],
                         capture_output=True, text=True, encoding="utf-8", timeout=300)
    assert out.stdout.strip(), out.stderr
    r = json.loads(out.stdout.strip().splitlines()[-1])
    assert r["failed"] == [], r["failed"]
    assert out.returncode == 0, out.stderr
    v = json.loads(VECTORS.read_text(encoding="utf-8"))
    n_cases = len(json.loads(CASES.read_text(encoding="utf-8"))["cases"])
    per_vector = len(v["b32c"]) + len(v["recovery_keys"]) + len(v["normalize"]) + len(v["kdf"]) + len(v["aead"]) + len(v["ops"]["tampered"])
    fixed_vector_checks, engine_scenarios = 32, 14
    assert r["passed"] >= n_cases + per_vector + fixed_vector_checks + engine_scenarios, r


def test_web_modules_stay_standalone():
    """No network, no libraries, no logging in the crypto/sync modules."""
    for name in ("vault.js", "sync-core.js"):
        src = (ROOT / "frontend" / name).read_text(encoding="utf-8")
        for banned in ("fetch(", "XMLHttpRequest", "console.", "import(", "localStorage", "http://", "https://"):
            assert banned not in src, f"{name} contains {banned}"
        imports = [ln for ln in src.splitlines() if ln.startswith("import ")]
        assert imports in ([], ["import * as vc from './vault.js';"]), imports
