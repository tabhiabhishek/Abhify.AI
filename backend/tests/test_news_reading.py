"""In-app news reading (desktop/web): extraction, safety, honest summaries, per-article caching, web reader contracts."""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
APP_JS = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
PAGE = ("<html><head><script>var x = '<p>not this script paragraph at all, it is code and should never be read</p>';</script></head><body>"
        "<nav><p>Home · World · Business · Sport · Technology · Science · Health · Entertainment</p></nav>"
        "<p>The city council approved a new flood barrier on Monday after a two-year review of the river defences, ending a long debate "
        "between residents, engineers and the regional water authority about how best to protect the eastern districts.</p>"
        "<p>The barrier will cost 40 million and protect about 12,000 homes in the low-lying eastern districts, officials said, adding that "
        "the money comes from the regional infrastructure fund and a national resilience grant approved last year.</p>"
        "<p>Short.</p><p>Subscribe to our newsletter.</p>"
        "<p>Work is expected to begin next spring, with the first section finished before the 2027 rainy season, according to the "
        "council's published timetable, which also lists public consultations in each affected neighbourhood.</p>"
        "<footer><p>All rights reserved. Copyright 2026 Example News Group Limited and its partners.</p></footer></body></html>")


def test_paragraph_extraction_keeps_the_article_only():
    import news_center as n
    p = n.extract_paragraphs(PAGE)
    assert [x[:20] for x in p] == ["The city council app", "The barrier will cos", "Work is expected to "]


@pytest.mark.parametrize("url,ok", [("https://www.bbc.co.uk/news/1", True), ("http://example.com/a", False), ("https://localhost/a", False),
                                    ("https://127.0.0.1/a", False), ("https://192.168.1.4/a", False), ("https://intranet/a", False),
                                    ("javascript:alert(1)", False), ("https://8.8.8.8/x", True)])
def test_only_public_https_links_are_fetched(url, ok):
    import news_center as n
    assert n.safe_public_url(url) is ok


class FakeLLM:
    prompts = []

    def __init__(self, model=None):
        self.model = model

    async def ainvoke(self, msgs, **kw):
        FakeLLM.prompts.append(msgs[1].content)
        R = type("R", (), {}); r = R(); r.content = f"Summary {len(FakeLLM.prompts)}: the council approved a flood barrier."
        return r


@pytest.fixture
def app(data_root, monkeypatch):
    import agent
    import main
    import news_center as n
    import system_service as system
    FakeLLM.prompts = []
    monkeypatch.setattr(agent, "get_llm", lambda *a, model=None, **k: FakeLLM(model))

    async def note(name): pass
    monkeypatch.setattr(system, "note_chat_model", note)
    fetched = []

    async def fake_fetch(client, url):
        fetched.append(url); return PAGE.encode()
    monkeypatch.setattr(n, "_fetch", fake_fetch)
    arts = [{"id": "a1", "title": "Council approves flood barrier", "publisher": "Example News", "url": "https://news.example.com/flood",
             "published_at": 1_800_000_000_000, "summary": "The council approved a barrier.", "category": "world", "provider": "rss"},
            {"id": "b2", "title": "Team wins the final", "publisher": "Google News", "url": "https://news.google.com/rss/articles/xyz",
             "published_at": 0, "summary": "", "category": "world", "provider": "google-news"}]
    n._cache_file("cat-world").write_text(json.dumps({"articles": arts, "retrieved_at": 1}), encoding="utf-8")
    with TestClient(main.app) as c:
        c.fetched = fetched
        yield c


def test_reading_a_story_uses_its_own_text_and_says_so(app):
    r = app.get("/news/article/a1").json()
    assert r["article"]["id"] == "a1" and r["full_text"] is True and len(r["paragraphs"]) == 3
    assert r["summary"].startswith("Summary 1") and "fetched from the publisher's page" in r["label"]
    assert "flood barrier on Monday" in FakeLLM.prompts[0] and "Team wins" not in FakeLLM.prompts[0]   # only this story
    assert "Do not add facts" in FakeLLM.prompts[0] and "untrusted" in FakeLLM.prompts[0]
    again = app.get("/news/article/a1").json()                                                          # cached: no second model call
    assert again["summary"] == r["summary"] and len(FakeLLM.prompts) == 1


def test_limited_source_is_honest_and_never_fetched_for_google_news(app):
    r = app.get("/news/article/b2").json()
    assert r["full_text"] is False and r["paragraphs"] == [] and "Google News" in r["fetch_note"]
    assert "headline and short description only" in r["label"] and "do not say you read the full article" in FakeLLM.prompts[-1]
    assert all("google" not in u for u in app.fetched)
    assert app.get("/news/article/nope").status_code == 404


def test_changed_text_gets_a_new_summary_and_ai_failure_keeps_the_reader(app, monkeypatch):
    import news_center as n
    app.get("/news/article/a1")
    rec = json.loads(n._reader_file("a1").read_text(encoding="utf-8"))
    rec["paragraphs"] = ["A completely different text about something else that changed on the page, long enough to count as content." * 5]
    n._reader_file("a1").write_text(json.dumps(rec), encoding="utf-8")
    r = app.get("/news/article/a1").json()
    assert r["summary"].startswith("Summary 2")                                                         # stale summary not reused
    import agent

    class Broken:
        async def ainvoke(self, *a, **k): raise RuntimeError("ollama down")
    monkeypatch.setattr(agent, "get_llm", lambda *a, **k: Broken())
    n._reader_file("a1").unlink()
    r = app.get("/news/article/a1").json()
    assert r["summary"] is None and "couldn't be written" in r["summary_error"] and r["paragraphs"]     # text still shown


def test_redirects_to_private_addresses_are_refused(app, monkeypatch):
    import asyncio
    import httpx
    import news_center as n

    async def via_client(client, url):                                  # the real request hooks run, as with a redirect
        await client.get("https://10.0.0.5/admin")
    monkeypatch.setattr(n, "_fetch", via_client)
    r = asyncio.run(n.read_article("a1", summarize=False))
    assert r["full_text"] is False and "couldn't be read" in (r["fetch_note"] or "")


def test_web_reader_contracts():
    js = APP_JS
    card = js[js.index("const card = (x, featured)"):js.index("const feat = a.find")]
    assert 'target="_blank"' not in card and 'data-nact="read"' in card                    # no direct jump to the site from a card
    assert "if (card && !e.target.closest('button, a, [data-nact]')) { openArticle(card.dataset.id); return; }" in js
    reader = js[js.index("const articleView"):js.index("function newsSources")]
    assert "if (seq !== articleView.seq || articleView.id !== id) return;" in reader         # a late summary for another story is dropped
    assert "if (articleView.id !== id) { if (reader.owner === 'news') reader.stop(); Object.assign(articleView, { id, stage: 0, data: null }); }" in reader
    more = reader[reader.index("if (act === 'more')"):]
    assert more.index("articleView.stage = 1") < more.index("window.open(a.url")             # 1st Read more expands, 2nd opens
    assert "if (!articleUrlOk(a.url))" in more and "x.protocol === 'https:'" in reader       # only a valid https link of THIS story
    assert "window.open(" not in js[:js.index("const articleView")]                          # nothing else opens a news site
    assert "popstate" in js and "newsAi(" not in reader                                       # back closes; no briefing
