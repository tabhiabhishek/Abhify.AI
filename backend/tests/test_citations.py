"""7.0.1 citation integrity: deterministic ids, an immutable source registry, claim-to-source mapping (small local models
put facts under the wrong number), invalid citations removed, invented URLs removed, unsupported claims marked,
publication/retrieval timestamps validated, and the web page renders/speaks the result safely. The cases in
tests/data/citation_cases.json are shared with the Android app (RealtimeTest)."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
CASES = json.loads((Path(__file__).parent / "data" / "citation_cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES["cases"], ids=[c["name"] for c in CASES["cases"]])
def test_shared_citation_cases(case):
    import citations as C
    sources = CASES["sources"][case["sources"]] if case["sources"] else []
    out, r = C.check(case["answer"], sources)
    for s in case["contains"]:
        assert s in out, (s, out)
    for s in case["absent"]:
        assert s not in out, (s, out)
    assert (r["verified"], len(r["remapped"]), len(r["unsupported"]), len(r["invalid"])) == \
        (case["verified"], case["remapped"], case["unsupported"], case["invalid"]), r
    if "urls_removed" in case:
        assert len(r["urls_removed"]) == case["urls_removed"]
    assert r.get("references", 0) == case.get("references", 0)


def test_the_checked_answer_never_cites_an_id_outside_the_registry():
    """Whatever a model writes, every remaining marker exists in the registry (fuzzed with the shared sources)."""
    import citations as C
    import random
    rnd = random.Random(7)
    src = CASES["sources"]["ai"]
    words = ["OpenAI", "Gemini 4", "2 million tokens", "40%", "GPT-5.5", "Apple", "faster", "Reuters", "released", "1999"]
    for _ in range(300):
        ans = " ".join(f"{rnd.choice(words)} {rnd.choice(words)} [{rnd.choice('WL')}{rnd.randint(0, 9)}]." for _ in range(rnd.randint(1, 4)))
        out, _ = C.check(ans, src)
        for m in C.MARK.finditer(out):
            assert (m.group(1) + m.group(2)).upper() in {"W1", "W2"}, (ans, out)


def test_registry_is_immutable_and_fingerprinted():
    import citations as C
    src = CASES["sources"]["ai"]
    a, b = C.Registry(src), C.Registry(json.loads(json.dumps(src)))
    assert a.fingerprint == b.fingerprint and len(a.fingerprint) == 16                  # deterministic for the same sources
    with pytest.raises(TypeError):
        a.sources["W9"] = None                                                         # read-only mapping
    with pytest.raises(Exception):
        a.sources["W1"].url = "https://evil.example"                                   # frozen records
    changed = json.loads(json.dumps(src)); changed[0]["url"] = "https://other.example"
    assert C.Registry(changed).fingerprint != a.fingerprint
    assert set(C.Registry(src + [{"id": "X1"}, {"id": "W1", "title": "dup"}]).sources) == {"W1", "W2"}   # bad / duplicate ids ignored


def test_timestamps_are_validated_never_guessed():
    import citations as C
    now = 1_795_020_000_000
    assert C.sanitize_timestamps(now - 3_600_000, now, now)[:2] == (now - 3_600_000, now)
    pub, ret, notes = C.sanitize_timestamps(now + 86_400_000, now, now)                 # published "tomorrow"
    assert pub is None and ret == now and notes
    pub, ret, notes = C.sanitize_timestamps(None, now + 3_600_000, now)                # retrieval in the future
    assert ret == now and notes
    assert C.sanitize_timestamps(now + 300_000, now, now)[0] == now + 300_000           # small clock skew is fine


def test_source_links_into_the_users_network_are_never_shown():
    import realtime as R
    for bad in ("http://localhost:8000/x", "http://127.0.0.1/a", "http://192.168.1.5/admin", "http://10.0.0.2/", "http://nas.local/f",
                "http://[::1]/", "http://169.254.169.254/latest", "javascript:alert(1)", "https://user:pw@example.com/"):
        assert R.SourceValidator.url(bad) is None, bad
    assert R.SourceValidator.url("https://www.reuters.com/tech/gemini-4") == "https://www.reuters.com/tech/gemini-4"


def test_answers_are_checked_before_they_are_shown_or_saved(data_root):
    """The pipeline: a misattributed citation is corrected in the returned answer AND in the saved history; the report
    reaches the page in `live.integrity`."""
    import agent
    import database as db
    import memory_service as mem
    from langchain_core.messages import AIMessage
    db.migrate()
    cid = mem.ensure_conversation(None, "q")
    live = {"needed": ["news"], "state": "verified", "sources": CASES["sources"]["ai"], "notes": [], "problems": []}
    final = AIMessage("Google DeepMind unveiled Gemini 4 with a 2 million token context window [W1]. See https://made-up.example/x")
    out = agent._finish("what's new in AI?", [final], {"save_history": True, "_ctx": {"live": live}}, cid, [], None, None)
    assert "[W2]" in out["answer"] and "[W1]" not in out["answer"] and "made-up.example" not in out["answer"]
    g = out["live"]["integrity"]
    assert g["remapped"] == [{"from": "W1", "to": "W2", "claim": g["remapped"][0]["claim"]}] and len(g["urls_removed"]) == 1
    saved = mem.recent_messages(cid)[-1]["content"]
    assert "[W2]" in saved and "made-up.example" not in saved


def test_answers_without_sources_or_markers_are_untouched(data_root):
    import agent
    import database as db
    import memory_service as mem
    from langchain_core.messages import AIMessage
    db.migrate()
    cid = mem.ensure_conversation(None, "q")
    text = "RAG retrieves passages, then the model answers. Read https://python.org for Python."
    out = agent._finish("q", [AIMessage(text)], {"save_history": True}, cid, [], None, None)
    assert out["answer"] == text and "integrity" not in (out["live"] or {})          # python.org is not presented as a source


def _js(src, names):
    def fn(name):
        start = src.index(f"\nfunction {name}(")
        i, depth = src.index("{", src.index(")", start)), 0
        while True:
            depth += {"{": 1, "}": -1}.get(src[i], 0)
            if depth == 0:
                return src[start:i + 1]
            i += 1
    return "\n".join(fn(n) for n in names)


def test_web_renders_unsupported_and_the_integrity_note_and_never_speaks_them():
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    src = (ROOT / "frontend" / "app.js").read_text(encoding="utf-8")
    unesc = next(line for line in src.splitlines() if line.startswith("const unesc ="))
    script = "\n".join([_js(src, ["esc"]), unesc, "function codeBlockHtml() { return ''; }", _js(src, ["md", "integrityHtml", "speechChunks"]),
                        'const live = {integrity: {checked: 3, remapped: [{from: "W1", to: "W2"}], unsupported: ["x"], invalid: ["W9"], urls_removed: ["u"]}};',
                        'process.stdout.write(JSON.stringify({html: md("Apple bought it [unsupported]. Gemini 4 [W2]."), note: integrityHtml(live),'
                        ' ok: integrityHtml({integrity: {checked: 1, remapped: [], unsupported: [], invalid: [], urls_removed: []}}),'
                        ' none: integrityHtml({}), speech: speechChunks("Apple bought it [unsupported]. Gemini 4 [W2].").map((x) => (typeof x === "string" ? x : x.text)).join(" ")}));'])
    out = subprocess.run([node, "-e", script], capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    assert '<span class="cite-unsupported"' in r["html"] and "[unsupported]" not in r["html"]
    assert "1 citation moved to the source that has the fact" in r["note"] and "1 statement not supported" in r["note"]
    assert "1 citation to a missing source removed" in r["note"] and "1 link not from the sources removed" in r["note"]
    assert "checked against the sources" in r["ok"] and r["none"] == ""
    assert "unsupported" not in r["speech"] and "W2" not in r["speech"] and "Apple bought it" in r["speech"]


@pytest.mark.parametrize("case", CASES["unverified_cases"], ids=[c["name"] for c in CASES["unverified_cases"]])
def test_links_presented_as_sources_without_retrieval_are_labelled_unverified(case):
    import citations as C
    out, flagged = C.label_unverified(case["answer"])
    for s in case["contains"]:
        assert s in out, (s, out)
    for s in case["absent"]:
        assert s not in out, (s, out)
    assert len(flagged) == case["flagged"]


def test_an_answer_without_sources_never_shows_a_fabricated_source_link(data_root):
    """Seen live (llama3.2, no retrieval): "(Source: https://aienews.org/news.html)" was invented from memory."""
    import agent
    import database as db
    import memory_service as mem
    from langchain_core.messages import AIMessage
    db.migrate()
    cid = mem.ensure_conversation(None, "q")
    out = agent._finish("what's new?", [AIMessage("Models improved. (Source: https://aienews.org/news.html)")], {"save_history": True}, cid, [], None, None)
    assert "https://aienews.org" not in out["answer"] and "unverified" in out["answer"]
    assert out["live"]["integrity"]["unverified_urls"] == ["https://aienews.org/news.html"] and out["live"]["state"] == "none"
    assert "https://aienews.org" not in mem.recent_messages(cid)[-1]["content"]
