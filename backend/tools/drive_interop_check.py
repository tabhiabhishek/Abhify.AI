"""Requirement A check (7.2.0-dev.1): can the Windows *Desktop* OAuth client see files the Android app created in your
Google Drive with the restricted drive.file scope, and can the phone see what Windows creates?

Runs in a throw-away data folder: it never opens or changes your Abhify.AI database, settings or backups. It signs in
with Google in your browser (drive.file only), lists what it can see under My Drive/Abhify.AI, writes one harmless text
file into Abhify.AI/Imports, prints the result, then revokes its access and deletes the temporary folder.

    backend\\.venv\\Scripts\\python backend\\tools\\drive_interop_check.py C:\\path\\to\\client_secret_DESKTOP.json
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import sys
import tempfile
import time

BACKEND = pathlib.Path(__file__).resolve().parents[1]


def main() -> int:
    if len(sys.argv) != 2 or not pathlib.Path(sys.argv[1]).is_file():
        print(__doc__)
        return 2
    tmp = tempfile.mkdtemp(prefix="abhify-interop-")
    os.environ["ABHIFY_DATA_DIR"] = tmp
    (pathlib.Path(tmp) / "config").mkdir(parents=True, exist_ok=True)
    (pathlib.Path(tmp) / "config" / "migrated_from_v1.json").write_text("{}")       # never adopt any older data
    sys.path.insert(0, str(BACKEND))
    import database
    import datasync_service as ds
    import google_oauth
    import local_file_service as lfs
    lfs.ensure_layout()
    database.migrate()
    try:
        ds.import_client(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
        url = ds.connect()["auth_url"]
        print("A Google sign-in page opened in your browser (if not, open this link):\n" + url + "\n")
        for _ in range(300):
            st = google_oauth.flow_status()
            if st["status"] != "waiting":
                break
            time.sleep(2)
        if not google_oauth.connected():
            print("Not connected:", google_oauth.flow_status().get("message") or "timed out")
            return 1
        acct = ds.finish_connect()
        r = ds.interop_check()
        print(json.dumps({"account": acct.get("email"), **r}, indent=2, ensure_ascii=False))
        print("\nRESULT (Windows side): " + ("Windows CAN see the Abhify.AI folder the phone created." if r["root_visible_before"]
              else "Windows can NOT see an Abhify.AI folder. Either the phone never created one in this account, or Google keeps each OAuth client's files separate."))
        print("Now check the phone side: More → Google Drive → Imports should list " + r["probe"])
        return 0
    except ds.DataSyncError as e:
        print("Check failed:", e)
        return 1
    finally:
        try:
            google_oauth.disconnect()                     # revoke this check's grant
        except Exception:
            pass
        lfs.reset_cache()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
