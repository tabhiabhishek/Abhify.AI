"""5.6 news image regression on the web app: every card's picture is that story's own image_url, keyed by its id; a
refresh with a new first story shows the new picture; an older response can't overwrite a newer one; missing and broken
images share one placeholder class. Runs the real functions from frontend/app.js (tests/js/news_images_harness.js)."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

HERE = Path(__file__).parent
APP_JS = HERE.parents[1] / "frontend" / "app.js"


@pytest.fixture(scope="module")
def r():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    out = subprocess.run([node, str(HERE / "js" / "news_images_harness.js"), str(APP_JS)], capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)


def test_each_card_uses_its_own_image_keyed_by_id(r):
    cards = r["first"]["list"]
    assert [c["id"] for c in cards] == ["a1", "a2", "a3"]                       # featured = first with an image; feed order kept
    assert r["first"]["articles"] == 3
    for c in cards:
        assert c["img_for"] == c["id"]                                          # the image is keyed to its own story
    by_id = {c["id"]: c for c in cards}
    assert by_id["a1"]["src"] == "https://img.example/a1.jpg" and by_id["a3"]["src"] == "https://img.example/a3.jpg"
    assert by_id["a2"]["ph"] and by_id["a2"]["src"] is None                     # no image -> placeholder, never a neighbour's
    assert r["first_meta"]                                                      # source · date · category


def test_refresh_with_a_new_first_story_shows_its_image(r):
    cards = r["refreshed"]["list"]
    assert cards[0]["id"] == "b1" and cards[0]["src"] == "https://img.example/b1.jpg"
    assert {c["id"]: c["src"] for c in cards}["a1"] == "https://img.example/a1.jpg"
    assert "Updated just now" in r["refresh_meta"]


def test_an_older_response_cannot_overwrite_a_newer_one(r):
    assert "category=world" in r["race_paths"][0] and "category=technology" in r["race_paths"][1]
    assert [c["id"] for c in r["after_race"]["list"]] == ["n1"] and r["result_ids"] == ["n1"]
    assert r["after_race"]["list"][0]["src"] == "https://img.example/n1.jpg"


def test_missing_and_broken_images_share_one_placeholder(r):
    assert r["fallback"] == {"className": "nimg ph", "for": "n1", "hidden": "true"}
    assert r["missing_class"] == "nimg ph"


def test_no_unkeyed_inline_image_state():
    js = APP_JS.read_text(encoding="utf-8")
    body = js[js.index("function renderNewsCenter("):js.index("async function newsAi(")]
    assert "newsImg(x, 'nimg')" in body and "Object.assign(document.createElement" not in body
    assert 'data-for="${esc(x.id)}"' in js[js.index("function newsImg("):js.index("function newsImgFallback(")]
