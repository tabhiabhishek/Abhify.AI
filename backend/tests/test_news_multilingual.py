"""5.3: the shared news catalog carries Hindi feeds; the desktop must pick feeds by language (same rules as Android)."""
import news_center as nc


def _p(lang):
    return {**nc.DEFAULT_PREFS, "language": lang}


def test_english_desktop_feed_has_no_hindi_publishers():
    feeds, problem = nc.plan("india", _p("en"))
    pubs = {f[0] for f in feeds}
    assert problem is None and "Amar Ujala" not in pubs and "Live Hindustan" not in pubs
    assert any("ceid=IN:en" in f[1] for f in feeds)


def test_hindi_and_both():
    hi, _ = nc.plan("india", _p("hi"))
    both, _ = nc.plan("india", _p("both"))
    en, _ = nc.plan("india", _p("en"))
    assert any(f[0] == "Amar Ujala" for f in hi) and not any(f[0] == "The Hindu" for f in hi)
    assert any("ceid=IN:hi" in f[1] for f in hi)
    assert {f[1] for f in both} == {f[1] for f in en} | {f[1] for f in hi}
    ai_hi, _ = nc.plan("ai", _p("hi"))                       # no Hindi publisher feed or Google topic: Google News (Hindi) search
    assert len(ai_hi) == 1 and "hl=hi-IN" in ai_hi[0][1]


def test_cache_keys_keep_english_unchanged():
    assert nc.cache_key("india", _p("en")) == "cat-india"
    assert nc.cache_key("india", _p("hi")) == "cat-india-hi" and nc.cache_key("india", _p("both")) == "cat-india-both"


def test_every_hindi_feed_is_https_and_labelled():
    cats = nc.catalog()["categories"]
    hi = [f for c in cats for f in c.get("feeds", []) if f.get("lang") == "hi"]
    assert len(hi) >= 20 and all(f["url"].startswith("https://") for f in hi)
    assert all(c.get("labelHi") for c in cats)
