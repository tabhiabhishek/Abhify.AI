"""7.0 Synapse redesign (web/desktop): bundled fonts served and cached offline, the shell revision, the design tokens
(dark and light, text contrast >= 4.5:1), the neural core on every orb, and exactly five navigation destinations."""
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
FRONT = ROOT / "frontend"
FONTS = ("Inter.ttf", "SpaceGrotesk.ttf", "JetBrainsMono.ttf")


def _read(name):
    return (FRONT / name).read_text(encoding="utf-8")


@pytest.fixture
def client(data_root, monkeypatch):
    import main
    import system_service as system

    async def no_ollama():
        return {"reachable": False, "url": "x", "version": None, "models": []}
    monkeypatch.setattr(system, "ollama_status", no_ollama)
    monkeypatch.setattr(system, "internet_available", lambda: False)
    with TestClient(main.app) as c:
        yield c


def test_fonts_are_bundled_with_their_licences():
    for f in FONTS:
        data = (FRONT / "fonts" / f).read_bytes()
        assert len(data) > 50_000 and data[:4] in (b"\x00\x01\x00\x00", b"true"), f   # a real TrueType file
    for lic in ("OFL-Inter.txt", "OFL-SpaceGrotesk.txt", "OFL-JetBrainsMono.txt"):
        assert "SIL Open Font License" in (FRONT / "fonts" / lic).read_text(encoding="utf-8"), lic


def test_backend_serves_the_fonts(client):
    for f in FONTS:
        r = client.get(f"/fonts/{f}")
        assert r.status_code == 200 and len(r.content) == (FRONT / "fonts" / f).stat().st_size, f


def test_fonts_render_offline_from_the_shell_cache():
    sw = _read("sw.js")
    shell = re.search(r"const SHELL = \[(.*?)\];", sw, re.S).group(1)
    for f in FONTS:
        assert f"'./fonts/{f}'" in shell, f                                     # precached with the shell
    css = _read("styles.css")
    for family, f in (("Inter", "Inter.ttf"), ("Space Grotesk", "SpaceGrotesk.ttf"), ("JetBrains Mono", "JetBrainsMono.ttf")):
        face = re.search(r'@font-face \{ font-family: "%s"; src: url\("fonts/%s"\)[^}]*\}' % (family, re.escape(f)), css)
        assert face and "font-display: swap" in face.group(0), family
    assert "fonts.googleapis" not in css + _read("index.html")                 # no CDN


def test_shell_revision_and_version_for_7_0():
    import version
    sw = _read("sw.js")
    assert version.__version__ == "7.2.1"
    assert "const SHELL_REV = 'ui72.5';" in sw and f"const CACHE_VERSION = 'abhify-v{version.__version__}';" in sw


def test_synapse_tokens_present():
    css = _read("styles.css")
    assert "Synapse (7.0)" in css.split("*/")[0]                                # header names the system
    root = css[css.index(":root {"):css.index('[data-theme="light"]')]
    for tok in ("--bg-0: #06070D", "--bg-1: #090A13", "--raised: #121527", "--raised-2: #1A1E36", "--text: #EEF0FF",
                "--muted: #AEB4D6", "--text-3: #8A92B8", "--violet: #9D7BFF", "--violet-2: #C9B8FF", "--cyan: #3DE0FF",
                "--cyan-2: #8FEFFF", "--on-violet: #0B0820", "--ok: #34D399", "--warn: #FBBF24", "--bad: #F87171",
                '--font-display: "Space Grotesk"', '--mono: "JetBrains Mono"'):
        assert tok in root, tok
    assert "--violet: #5B3FE0" in css and "--bg-0: #F6F5FF" in css             # light Synapse palette
    assert "--dur-base: 220ms; --dur-slow: 340ms" in css                        # motion tokens unchanged


def _hex(css_block, name):
    return re.search(r"%s:\s*(#[0-9A-Fa-f]{6})" % re.escape(name), css_block).group(1)


def _contrast(a, b):
    def lum(h):
        c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
        c = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in c]
        return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + .05) / (lo + .05)


def test_text_contrast_is_at_least_4_5_in_both_themes():
    css = _read("styles.css")
    dark = css[css.index(":root {"):css.index('[data-theme="light"]')]
    light = css[css.index(':root[data-theme="light"] {'):css.index("@media (prefers-color-scheme: light)")]
    for block in (dark, light):
        for bg in ("--bg-0", "--bg-1", "--surface", "--raised"):
            for fg in ("--text", "--muted", "--text-3", "--violet-2", "--cyan-2", "--warn", "--bad"):
                ratio = _contrast(_hex(block, fg), _hex(block, bg))
                assert ratio >= 4.5, (fg, bg, round(ratio, 2))
        assert _contrast(_hex(block, "--on-violet"), _hex(block, "--violet")) >= 4.5          # primary button text


def test_neural_core_on_every_orb_and_reduced_motion():
    html = _read("index.html")
    for oid in ("homeOrb", "chatOrb", "newsOrb", "voiceOrb", "briefOrb"):
        m = re.search(r'<div class="orb[^"]*" id="%s"[^>]*><svg class="core"' % oid, html)
        assert m, oid
    # 7.1: the header and sidebar show the OFFICIAL Abhify.AI mark again (7.0 had replaced it with the drawn orbit core)
    assert html.count('class="logo brand-logo" src="brand/logo-mark-96.png"') == 2 and 'class="logo core-mark"' not in html
    for gid in ("synCore", "synCoreLive", "synCoreOff", "synGlow"):
        assert f'id="{gid}"' in html
    css = _read("styles.css")
    assert "animation: coreSpin 18s linear infinite" in css and "animation: coreSpin 11s linear infinite reverse" in css
    assert '.orb[data-state="error"] .core-sphere' in css and '.orb[data-state="listening"] .core-sphere' in css
    assert re.search(r"@media \(prefers-reduced-motion: reduce\) \{\s*\.core-r1, \.core-r2, \.core-sphere", css)


def test_exactly_five_navigation_destinations():
    html = _read("index.html")
    tabs = re.findall(r'<button class="tab[^"]*" role="tab" data-tab="([a-z]+)"', html)
    assert tabs == ["home", "chat", "study", "news", "more"]
    assert '<span><span class="tab-pre">AI </span>Assistant</span>' in html      # "AI Assistant" in the sidebar
    css = _read("styles.css")
    assert ".tab { min-height: 42px;" in css and "white-space: nowrap" in css.split(".tab { min-height: 42px;")[1].split("}")[0]


def test_active_model_card_uses_real_status():
    html = _read("index.html")
    for i in ("sideModel", "sideModelName", "sideModelDot", "sideModelText"):
        assert f'id="{i}"' in html
    app = _read("app.js")
    view = app[app.index("function sideModelView()"):app.index("function renderSideModel()")]
    assert "state.chatModel || state.models.def || h?.llm_model" in view          # the model that answers next
    assert "'Local · ready'" in view and "h.llm_ready" in view and "h.ollama" in view
    assert "renderSideModel();\n}" in app[app.index("function updateOrb()"):]
