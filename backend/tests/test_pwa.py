"""PWA: manifest, icons, service worker safety, offline fallback, install docs."""
import json
import re
from pathlib import Path

import pytest
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
FRONT = ROOT / "frontend"


@pytest.fixture(scope="module")
def manifest():
    return json.loads((FRONT / "manifest.json").read_text(encoding="utf-8"))


def test_manifest_identity(manifest):
    assert manifest["name"] == "Abhify.AI" and len(manifest["short_name"]) <= 12
    assert manifest["display"] == "standalone" and manifest["start_url"] == "./" and manifest["scope"] == "./"
    assert re.fullmatch(r"#[0-9a-fA-F]{6}", manifest["theme_color"])


def test_manifest_icons_exist_with_declared_sizes(manifest):
    purposes = set()
    for icon in manifest["icons"]:
        path = FRONT / icon["src"]
        assert path.is_file(), icon["src"]
        w, h = (int(x) for x in icon["sizes"].split("x"))
        assert Image.open(path).size == (w, h), icon["src"]
        purposes.add(icon.get("purpose", "any"))
    sizes = {i["sizes"] for i in manifest["icons"]}
    assert {"192x192", "512x512"} <= sizes and "maskable" in purposes


def test_maskable_icon_is_full_bleed(manifest):
    icon = next(i for i in manifest["icons"] if i.get("purpose") == "maskable")
    img = Image.open(FRONT / icon["src"]).convert("RGBA")
    assert img.getpixel((0, 0))[3] == 255 and img.getpixel((511, 511))[3] == 255   # no transparent corners


def _sw():
    return (FRONT / "sw.js").read_text(encoding="utf-8")


def test_service_worker_precache_files_exist():
    shell = re.search(r"const SHELL = \[(.*?)\];", _sw(), re.S).group(1)
    for f in re.findall(r"'\./([^']*)'", shell):
        if f:
            assert (FRONT / f).is_file(), f


def test_service_worker_never_caches_api_routes():
    import main
    import companion
    sw_paths = set(re.findall(r"'(/[a-z\-.]+)'", re.search(r"const API_PATHS = \[(.*?)\];", _sw(), re.S).group(1)))
    missing = companion.API_PREFIXES - sw_paths
    assert not missing, f"API routes the service worker could cache: {missing}"


def test_service_worker_skips_non_get_and_cross_origin():
    sw = _sw()
    assert "req.method !== 'GET'" in sw and "url.origin !== self.location.origin" in sw
    assert re.search(r"CACHE_VERSION = 'abhify-v[\w.\-]+'", sw)
    assert "caches.delete" in sw                                         # old caches cleaned on update


def test_index_html_mobile_ready():
    html = (FRONT / "index.html").read_text(encoding="utf-8")
    assert "viewport-fit=cover" in html and 'rel="manifest"' in html and "apple-touch-icon" in html
    assert (FRONT / "icons" / "apple-touch-icon.png").is_file()
    css = (FRONT / "styles.css").read_text(encoding="utf-8")
    assert "safe-area-inset" in css and "overflow-x: hidden" in css and "--tap: 44px" in css


def test_offline_fallback_content_present():
    mock = (FRONT / "mock-data.js").read_text(encoding="utf-8")
    for key in ("news:", "questions:", "mcqs:"):
        assert key in mock
    app = (FRONT / "app.js").read_text(encoding="utf-8")
    assert "Offline Mode" in (FRONT / "index.html").read_text(encoding="utf-8")
    assert "window.MOCK.news" in app and "store.json(LS.news" in app


def test_no_secrets_in_frontend():
    for f in FRONT.rglob("*"):
        if f.suffix in {".js", ".html", ".json", ".css"} and "vendor" not in f.relative_to(FRONT).parts:   # vendored: hash-pinned (test_ondevice)
            text = f.read_text(encoding="utf-8", errors="ignore")
            assert not re.search(r"sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY", text), f


def test_mobile_install_docs_cover_both_platforms():
    doc = (ROOT / "docs" / "MOBILE_INSTALLATION.md").read_text(encoding="utf-8")
    for needle in ("Android", "iPhone", "HTTPS", "Mode A", "Mode B", "Mode C"):
        assert needle in doc
