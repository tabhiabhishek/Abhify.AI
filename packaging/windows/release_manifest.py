"""
release_manifest.py: the publisher's tool for SIGNED update manifests (see docs/AUTOMATIC_UPDATES.md).
It never uploads or publishes anything.

  py -3.11 packaging\\windows\\release_manifest.py keygen --out C:\\Keys\\abhify-release.key
      Creates an Ed25519 private key OUTSIDE this repository (refused inside it) and prints the PUBLIC
      key + key id to put in settings.json -> updates.trusted_keys. Keep the private key offline/backed up.

  py -3.11 packaging\\windows\\release_manifest.py sign --key C:\\Keys\\abhify-release.key --key-id release-2026 ^
      --version 2.0.0-dev.6 --url https://<release host>/Abhify.AI-Setup-2.0.0-dev.6.exe [--authenticode "O=..."]
      Reads dist\\windows\\<version>\\Abhify.AI-Setup-<version>.exe, records its exact size and SHA-256,
      signs, verifies the result, and writes dist\\windows\\<version>\\latest.json (the envelope).
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
import ed25519_verify  # noqa: E402
import updates  # noqa: E402


def inside_repo(path: Path) -> bool:
    try:
        path.resolve().relative_to(ROOT.resolve())
        return True
    except ValueError:
        return False


def keygen(out: Path) -> dict:
    if inside_repo(out):
        raise SystemExit("Refusing to create a private key inside the repository. Choose a path outside it.")
    if out.exists():
        raise SystemExit(f"{out} exists; not overwriting a key.")
    secret = secrets.token_bytes(32)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(base64.b64encode(secret).decode(), encoding="ascii")
    try:
        os.chmod(out, 0o600)
    except OSError:
        pass
    pub = base64.b64encode(ed25519_verify.public_key(secret)).decode()
    return {"private_key_file": str(out), "public_key": pub}


def sign(key_file: Path, key_id: str, version: str, url: str, authenticode: str | None, dist: Path | None = None) -> Path:
    if inside_repo(key_file):
        raise SystemExit("Refusing to read a private key from inside the repository.")
    secret = base64.b64decode(key_file.read_text(encoding="ascii").strip(), validate=True)
    dist = dist or ROOT / "dist" / "windows" / version
    exe = dist / f"Abhify.AI-Setup-{version}.exe"
    data = exe.read_bytes()
    m = {"app": "Abhify.AI", "version": version, "min_supported": "2.0.0", "notes": f"See RELEASE_NOTES.md for {version}",
         "packages": {"windows-x64": {"url": url, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                                      "authenticode_subject": authenticode}}}
    updates.validate_manifest(json.loads(json.dumps(m)))
    env = updates.sign_envelope(m, secret, key_id)
    pub = base64.b64encode(ed25519_verify.public_key(secret)).decode()
    updates.verify_envelope(json.dumps(env).encode(), {key_id: pub})      # self-check before writing
    out = dist / "latest.json"
    out.write_text(json.dumps(env, indent=2), encoding="utf-8")
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen"); k.add_argument("--out", type=Path, required=True)
    s = sub.add_parser("sign")
    s.add_argument("--key", type=Path, required=True); s.add_argument("--key-id", required=True)
    s.add_argument("--version", required=True); s.add_argument("--url", required=True)
    s.add_argument("--authenticode", default=None)
    a = ap.parse_args()
    if a.cmd == "keygen":
        r = keygen(a.out)
        print(f"Private key written to {r['private_key_file']} (keep it offline; never commit it).")
        print(f'Add to settings.json -> "updates": {{"trusted_keys": {{"<key id>": "{r["public_key"]}"}}}}')
    else:
        print("Signed manifest:", sign(a.key, a.key_id, a.version, a.url, a.authenticode))


if __name__ == "__main__":
    main()
