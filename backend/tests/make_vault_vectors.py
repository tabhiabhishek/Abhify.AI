"""Generates backend/tests/data/vault_vectors.json (deterministic). The Python tests check the committed file still
matches this output byte for byte; the Kotlin and JavaScript ports must decode and reproduce it.

Run:  python backend/tests/make_vault_vectors.py
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import vault_crypto as vc  # noqa: E402

OUT = pathlib.Path(__file__).parent / "data" / "vault_vectors.json"


def det(seed: str):
    """Deterministic 'random' bytes for vectors only (never used by the app)."""
    state = {"i": 0}

    def rnd(n: int) -> bytes:
        out = b""
        while len(out) < n:
            out += hashlib.sha256(f"{seed}:{state['i']}".encode()).digest()
            state["i"] += 1
        return out[:n]
    return rnd


def build() -> dict:
    v: dict = {"spec": "docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md", "note": "deterministic test vectors; never real keys"}
    v["b32c"] = [{"hex": h, "out": vc.b32c(bytes.fromhex(h))} for h in ("", "00", "ff", "0102030405", "ffffffffffffffffffff", "8f3a9c")]
    raws = ["00" * 20, "ff" * 20, det("rk")(20).hex()]
    v["recovery_keys"] = []
    for i, raw in enumerate(raws):
        rk = vc.new_recovery_key(lambda n, r=raw: bytes.fromhex(r)[:n])
        v["recovery_keys"].append({"raw": raw, "canonical": rk, "display": vc.display_recovery_key(rk), "qr": vc.qr_payload(rk)})
    good = v["recovery_keys"][2]["canonical"]
    disp = vc.display_recovery_key(good)
    v["normalize"] = [
        {"in": disp, "out": good}, {"in": disp.lower(), "out": good}, {"in": " " + disp.replace("-", " ") + " ", "out": good},
        {"in": disp.replace("0", "O").replace("1", "I"), "out": good}, {"in": disp.replace("1", "l"), "out": good},
        {"in": good[:-1] + ("0" if good[-1] != "0" else "1"), "error": "typo"}, {"in": good[:35], "error": "typo"},
        {"in": good[:10] + "U" + good[11:], "error": "typo"}, {"in": "", "error": "typo"},
    ]
    v["kdf"] = [{"password": p, "salt": s, "iter": it, "key": vc.kdf(p, bytes.fromhex(s), it).hex()}
                for p, s, it in (("correct horse battery", "00112233445566778899aabbccddeeff", 100000), (good, "ffeeddccbbaa99887766554433221100", 100000),
                                 ("pässwörd ✓ नमस्ते", "0102030405060708090a0b0c0d0e0f10", 100000))]
    v["aead"] = []
    for key, iv, pt, aad in (("00" * 32, "00" * 12, "", "x"), ("11" * 32, "22" * 12, "68656c6c6f", "abhify-sync-ops|1|v|1|d|1|0"),
                             (det("k")(32).hex(), det("iv")(12).hex(), "e29c93204e6f7661".encode().hex(), "aad ✓")):
        v["aead"].append({"key": key, "iv": iv, "pt": pt, "aad": aad, "ct": vc.b64e(vc.seal(bytes.fromhex(key), bytes.fromhex(iv), bytes.fromhex(pt), aad))})
    # vault: created, then rotated once
    r = det("vault")
    doc1, keys1 = vc.create_vault(good, 1763900000000, r)
    doc2, keys2 = vc.rotate_vault(doc1, good, r)
    v["vault"] = {"recovery_key": good, "wrong_recovery_key": v["recovery_keys"][0]["canonical"],
                  "doc_epoch1": doc1, "keys_epoch1": {str(k): kk.hex() for k, kk in keys1.items()},
                  "doc_epoch2": doc2, "keys_epoch2": {str(k): kk.hex() for k, kk in keys2.items()}}
    records = [
        {"rid": "dev0000000000000a", "kind": "device", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "at": 1763900000001, "del": False,
         "data": {"name": "Test PC", "platform": "windows", "added": 1763900000000}},
        {"rid": "c-0001-conversation", "kind": "conversation", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "at": 1763900000002, "del": False,
         "data": {"title": "Trip ✈ plan", "created": 1763900000000}},
        {"rid": "m-0001-message", "kind": "message", "ver": {"dev": "a" * 16, "n": 1}, "base": None, "at": 1763900000003, "del": False,
         "data": {"conv": "c-0001-conversation", "role": "user", "content": "नमस्ते — hello", "created": 1763900000001}},
        {"rid": "mem-0001-memory", "kind": "memory", "ver": {"dev": "a" * 16, "n": 2}, "base": {"dev": "a" * 16, "n": 1}, "at": 1763900000004,
         "del": False, "data": {"content": "Prefers short answers", "created": 1763800000000, "category": "preference"}},
        {"rid": "mem-0002-memory", "kind": "memory", "ver": {"dev": "a" * 16, "n": 3}, "base": {"dev": "a" * 16, "n": 2}, "at": 1763900000005,
         "del": True, "data": {}},
    ]
    records[0]["rid"] = "a" * 16
    op = vc.seal_ops(doc2["vault_id"], 2, keys2[2], "a" * 16, 7, 1763900000010, records, r)
    op_old = vc.seal_ops(doc2["vault_id"], 1, keys2[1], "b" * 16, 1, 1763800000000, records[1:3], r)
    v["ops"] = {"file": op.decode(), "name": vc.op_name("a" * 16, 7), "expect": {"device": "a" * 16, "seq": 7, "epoch": 2, "created": 1763900000010, "records": records},
                "file_epoch1": op_old.decode(), "expect_epoch1_records": records[1:3]}
    t = json.loads(op)
    tampered = []
    for field, val in (("seq", 8), ("device", "c" * 16), ("created", 1763900000011), ("epoch", 1)):
        x = dict(t); x[field] = val; tampered.append({"what": field, "file": json.dumps(x, separators=(",", ":")), "error": "wrong_key"})
    ct = bytearray(vc.b64d(t["ct"])); ct[5] ^= 1
    x = dict(t); x["ct"] = vc.b64e(bytes(ct)); tampered.append({"what": "ciphertext bit", "file": json.dumps(x, separators=(",", ":")), "error": "wrong_key"})
    x = dict(t); x["vault_id"] = "0" * 32; tampered.append({"what": "vault_id", "file": json.dumps(x, separators=(",", ":")), "error": "malformed"})
    v["ops"]["tampered"] = tampered
    bk_records = {"conversation": [records[1]], "message": [records[2]], "memory": [records[3]]}
    bk = vc.seal_backup("correct horse battery", "windows", "7.2.0-dev.1", 1763900000020, bk_records, ["Conversations", "Personal memory"], r, iterations=100000)
    v["backup"] = {"file": bk.decode(), "passphrase": "correct horse battery", "wrong_passphrase": "correct horse batterY",
                   "expect": {"header": {"platform": "windows", "app_version": "7.2.0-dev.1", "created": 1763900000020},
                              "manifest": {"categories": ["Conversations", "Personal memory"], "counts": {"conversation": 1, "message": 1, "memory": 1},
                                           "platform": "windows", "app_version": "7.2.0-dev.1", "created": 1763900000020},
                              "records": bk_records}}
    return v


if __name__ == "__main__":
    OUT.write_text(json.dumps(build(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("wrote", OUT)
