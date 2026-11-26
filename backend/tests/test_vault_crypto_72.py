"""7.2.0-dev.1: vault, op-file and backup formats (vault_crypto.py) against the shared vectors."""
import json
import os
import pathlib

import pytest

import vault_crypto as vc

V = json.loads((pathlib.Path(__file__).parent / "data" / "vault_vectors.json").read_text(encoding="utf-8"))


def test_vectors_file_is_reproducible_byte_for_byte():
    import make_vault_vectors as mk
    assert mk.build() == V, "vault_vectors.json no longer matches the reference implementation (format drift)"


def test_base32_and_recovery_key_encoding():
    for c in V["b32c"]:
        assert vc.b32c(bytes.fromhex(c["hex"])) == c["out"]
    for c in V["recovery_keys"]:
        assert vc.new_recovery_key(lambda n, r=c["raw"]: bytes.fromhex(r)[:n]) == c["canonical"]
        assert len(c["canonical"]) == 36 and c["display"].count("-") == 8
        assert vc.normalize_recovery_key(c["display"]) == c["canonical"]
        assert vc.from_qr(c["qr"]) == c["canonical"]


def test_normalization_and_typo_detection_without_network():
    for c in V["normalize"]:
        if "out" in c:
            assert vc.normalize_recovery_key(c["in"]) == c["out"]
        else:
            with pytest.raises(vc.RecoveryKeyTypo):
                vc.normalize_recovery_key(c["in"])
    with pytest.raises(vc.RecoveryKeyTypo):
        vc.from_qr("https://example.com/abhify-rk:1:" + V["vault"]["recovery_key"])


def test_kdf_and_aead_known_answers():
    for c in V["kdf"]:
        assert vc.kdf(c["password"], bytes.fromhex(c["salt"]), c["iter"]).hex() == c["key"]
    for c in V["aead"]:
        assert vc.b64e(vc.seal(bytes.fromhex(c["key"]), bytes.fromhex(c["iv"]), bytes.fromhex(c["pt"]), c["aad"])) == c["ct"]
        assert vc.open_(bytes.fromhex(c["key"]), bytes.fromhex(c["iv"]), vc.b64d(c["ct"]), c["aad"], "x") == bytes.fromhex(c["pt"])
    with pytest.raises(vc.Unsupported):
        vc.kdf("p", b"s" * 16, 99_999)                      # too weak a file is refused
    with pytest.raises(vc.Unsupported):
        vc.kdf("p", b"s" * 16, 5_000_001)                   # a denial-of-service iteration count is refused


def test_vault_unlock_rotation_and_wrong_key():
    vt = V["vault"]
    k1 = vc.unlock_vault(vt["doc_epoch1"], vt["recovery_key"])
    assert {str(k): v.hex() for k, v in k1.items()} == vt["keys_epoch1"]
    k2 = vc.unlock_vault(vt["doc_epoch2"], vc.display_recovery_key(vt["recovery_key"]).lower())
    assert {str(k): v.hex() for k, v in k2.items()} == vt["keys_epoch2"]
    assert vt["keys_epoch2"]["1"] == vt["keys_epoch1"]["1"] and vt["keys_epoch2"]["2"] != vt["keys_epoch1"]["1"]
    with pytest.raises(vc.WrongKey):
        vc.unlock_vault(vt["doc_epoch2"], vt["wrong_recovery_key"])
    bad = json.loads(json.dumps(vt["doc_epoch1"]))
    bad["epoch"] = 3                                       # header is bound by the AAD
    with pytest.raises(vc.WrongKey):
        vc.unlock_vault(bad, vt["recovery_key"])
    for k in ("format", "v"):
        bad = json.loads(json.dumps(vt["doc_epoch1"]))
        bad[k] = "x"
        with pytest.raises((vc.Malformed, vc.Unsupported)):
            vc.unlock_vault(bad, vt["recovery_key"])


def test_vault_document_holds_no_plain_key_or_recovery_key():
    vt = V["vault"]
    text = json.dumps(vt["doc_epoch2"])
    for key_hex in vt["keys_epoch2"].values():
        assert key_hex not in text and vc.b64e(bytes.fromhex(key_hex)) not in text
    assert vt["recovery_key"] not in text and vc.display_recovery_key(vt["recovery_key"]) not in text


def test_ops_open_tamper_and_old_epoch():
    vt, o = V["vault"], V["ops"]
    keys = {int(k): bytes.fromhex(v) for k, v in vt["keys_epoch2"].items()}
    vid = vt["doc_epoch2"]["vault_id"]
    assert vc.open_ops(o["file"].encode(), keys, vid) == o["expect"]
    assert vc.open_ops(o["file_epoch1"].encode(), keys, vid)["records"] == o["expect_epoch1_records"]
    assert vc.parse_op_name(o["name"]) == ("a" * 16, 7)
    assert vc.parse_op_name("op-xyz-00000001.abx") is None and vc.parse_op_name("../op-" + "a" * 16 + "-00000001.abx") is None
    for t in o["tampered"]:
        with pytest.raises(vc.WrongKey if t["error"] == "wrong_key" else vc.Malformed):
            vc.open_ops(t["file"].encode(), keys, vid)
    with pytest.raises(vc.WrongKey):                       # epoch-2 file on a device that only has epoch 1
        vc.open_ops(o["file"].encode(), {1: keys[1]}, vid)


def test_backup_open_wrong_passphrase_and_counts():
    b = V["backup"]
    got = vc.open_backup(b["file"].encode(), b["passphrase"])
    assert got == b["expect"]
    with pytest.raises(vc.WrongKey):
        vc.open_backup(b["file"].encode(), b["wrong_passphrase"])
    with pytest.raises(vc.WrongKey):
        vc.open_backup(b["file"].encode(), "")
    h = json.loads(b["file"])
    h["platform"] = "android"                              # header bound by AAD
    with pytest.raises(vc.WrongKey):
        vc.open_backup(json.dumps(h).encode(), b["passphrase"])
    with pytest.raises(vc.Malformed):
        vc.open_backup(b"not a backup", "x")


def test_backup_round_trip_real_random_and_passphrase_rules():
    recs = {"memory": [{"rid": "mem-abc-12345", "kind": "memory", "ver": {"dev": "1" * 16, "n": 1}, "base": None, "at": 1, "del": False,
                        "data": {"content": "x", "created": 1}}]}
    for bad in ("", None, "short"):
        with pytest.raises(vc.VaultError):
            vc.seal_backup(bad, "web", "7.2.0-dev.1", 1, recs, ["Personal memory"], iterations=100_000)
    data = vc.seal_backup("a long passphrase", "web", "7.2.0-dev.1", 5, recs, ["Personal memory"], iterations=100_000)
    assert b"mem-abc" not in data and b"content" not in data       # nothing readable in the file
    assert vc.open_backup(data, "a long passphrase")["records"] == recs
    a, b2 = (vc.seal_backup("a long passphrase", "web", "7.2.0-dev.1", 5, recs, [], iterations=100_000) for _ in range(2))
    assert a != b2                                                  # fresh salt and IV every time


def test_real_recovery_keys_are_random_and_valid():
    keys = {vc.new_recovery_key() for _ in range(50)}
    assert len(keys) == 50
    for k in keys:
        assert vc.normalize_recovery_key(vc.display_recovery_key(k)) == k


def test_decompression_bomb_is_refused(monkeypatch):
    monkeypatch.setattr(vc, "MAX_PLAIN", 1000)
    with pytest.raises(vc.Malformed):
        vc.gunz(vc.gz(b"0" * 5000))


def test_module_never_logs_or_prints(capsys, caplog):
    vt = V["vault"]
    vc.unlock_vault(vt["doc_epoch1"], vt["recovery_key"])
    try:
        vc.unlock_vault(vt["doc_epoch1"], vt["wrong_recovery_key"])
    except vc.WrongKey as e:
        assert vt["wrong_recovery_key"] not in str(e)
    out = capsys.readouterr()
    assert out.out == "" and out.err == "" and caplog.records == []
    src = (pathlib.Path(vc.__file__)).read_text(encoding="utf-8")
    assert "logging" not in src and "print(" not in src


def test_type_confusion_and_damaged_bodies_raise_vault_errors():
    vt = V["vault"]
    for k, val in (("iter", "100000"), ("iter", None), ("iter", True), ("salt", 5)):
        bad = json.loads(json.dumps(vt["doc_epoch1"]))
        bad["kdf"][k] = val
        with pytest.raises(vc.VaultError):
            vc.unlock_vault(bad, vt["recovery_key"])
    assert vc.parse_op_name("op-" + "a" * 16 + "-0000000١.abx") is None                 # Arabic-Indic digit
    key, iv = bytes(32), bytes(12)
    for body in (b"[1]", b"not json", "\"x\"".encode()):
        ct = vc.seal(key, iv, vc.gz(body), "abhify-sync-ops|1|" + "0" * 32 + "|1|" + "a" * 16 + "|1|1")
        f = vc._json({"format": "abhify-sync-ops", "v": 1, "vault_id": "0" * 32, "epoch": 1, "device": "a" * 16, "seq": 1, "created": 1,
                      "iv": vc.b64e(iv), "ct": vc.b64e(ct)})
        with pytest.raises(vc.Malformed):
            vc.open_ops(f, {1: key}, "0" * 32)
    short = json.loads(V["ops"]["file"]); short["iv"] = vc.b64e(b"1234")
    with pytest.raises(vc.Malformed):
        vc.open_ops(json.dumps(short).encode(), {2: bytes(32)}, short["vault_id"])
    assert vc.gz(b"x")[9] == 255                                                          # same bytes on every OS
