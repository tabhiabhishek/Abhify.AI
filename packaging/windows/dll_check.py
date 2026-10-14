"""
dll_check.py: make sure every bundled binary can load on a CLEAN Windows 10/11.

Found by the Windows Sandbox test: ctranslate2, onnxruntime and ChromaDB's Rust
core need the Visual C++ runtime (msvcp140*.dll), which a fresh Windows does
not have. This module

  * lists the DLLs each bundled .dll/.pyd/.exe imports (PE import table, no tools needed)
  * fails if any import is neither shipped in the bundle nor a DLL that is part
    of every Windows 10/11 installation (the WINDOWS set below)
"""
from __future__ import annotations

import struct
from collections import defaultdict
from pathlib import Path

# DLLs present on every Windows 10/11 x64 installation (System32), plus the Universal CRT forwarders.
WINDOWS = set("""
kernel32.dll kernelbase.dll ntdll.dll user32.dll gdi32.dll advapi32.dll shell32.dll shlwapi.dll ole32.dll oleaut32.dll
combase.dll rpcrt4.dll ws2_32.dll mswsock.dll iphlpapi.dll dnsapi.dll winhttp.dll wininet.dll crypt32.dll bcrypt.dll
bcryptprimitives.dll ncrypt.dll secur32.dll sspicli.dll wintrust.dll imagehlp.dll dbghelp.dll psapi.dll pdh.dll
powrprof.dll version.dll userenv.dll setupapi.dll cfgmgr32.dll cabinet.dll msi.dll winmm.dll avicap32.dll dxgi.dll
d3d11.dll d3d12.dll dwrite.dll d2d1.dll comdlg32.dll comctl32.dll uxtheme.dll dwmapi.dll msvcrt.dll ucrtbase.dll
netapi32.dll wtsapi32.dll shcore.dll propsys.dll normaliz.dll mf.dll mfplat.dll mfreadwrite.dll opengl32.dll
""".split())
VC_RUNTIME = ["msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll", "msvcp140_atomic_wait.dll",
              "msvcp140_codecvt_ids.dll", "concrt140.dll", "vcomp140.dll"]


def pe_imports(path: Path) -> list[str]:
    """Names of DLLs imported by a PE file ([] if it isn't one)."""
    try:
        d = path.read_bytes()
        pe = struct.unpack_from("<I", d, 0x3C)[0]
        if d[pe:pe + 4] != b"PE\0\0":
            return []
        nsec = struct.unpack_from("<H", d, pe + 6)[0]
        opt = pe + 24
        magic = struct.unpack_from("<H", d, opt)[0]
        rva = struct.unpack_from("<I", d, opt + (112 if magic == 0x20B else 96) + 8)[0]
        if not rva:
            return []
        sec = opt + struct.unpack_from("<H", d, pe + 20)[0]
        secs = [struct.unpack_from("<8sIIII", d, sec + 40 * i) for i in range(nsec)]

        def off(r):
            for _n, vs, va, rs, ro in secs:
                if va <= r < va + max(vs, rs):
                    return r - va + ro
            raise ValueError("rva outside sections")
        o, out = off(rva), []
        while True:
            e = struct.unpack_from("<IIIII", d, o)
            if e[3] == 0:
                break
            n = off(e[3])
            out.append(d[n:d.index(b"\0", n)].decode("ascii", "replace").lower())
            o += 20
        return out
    except (OSError, ValueError, struct.error):
        return []


def unmet_dependencies(runtime: Path) -> dict[str, set[str]]:
    """{missing_dll: {binaries that need it}} for DLLs a clean Windows would not provide."""
    bins = [p for ext in ("*.dll", "*.pyd", "*.exe") for p in runtime.rglob(ext)]
    shipped = {p.name.lower() for p in bins}
    missing: dict[str, set[str]] = defaultdict(set)
    for b in bins:
        for dll in pe_imports(b):
            if dll in shipped or dll in WINDOWS or dll.startswith(("api-ms-win-", "ext-ms-")):
                continue
            missing[dll].add(str(b.relative_to(runtime)))
    return dict(missing)


if __name__ == "__main__":
    import sys
    m = unmet_dependencies(Path(sys.argv[1]))
    for dll, users in sorted(m.items()):
        print(f"MISSING {dll}  <- {sorted(users)[:4]}")
    print("OK: all DLL dependencies satisfied" if not m else f"{len(m)} missing DLL(s)")
    sys.exit(1 if m else 0)
