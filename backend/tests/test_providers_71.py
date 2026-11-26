"""7.1 cloud AI providers on the desktop/web backend (providers.py): free-model rules shared with Android, key handling (real
Windows DPAPI round trip, masked, never returned, never in backups), the real-call connection test, SSRF rules for the custom
endpoint, streaming, no fallback between providers, memories/documents never sent, the opt-in Auto router. Network mocked."""
import asyncio
import json
import sys

import httpx
import pytest

import providers as P


@pytest.fixture
def prov(data_root, monkeypatch):
    """Empty data root; the provider HTTP transport fails unless a test installs a handler."""
    import local_file_service as lfs
    lfs.reset_cache()
    monkeypatch.setattr(P, "_transport", httpx.MockTransport(lambda r: httpx.Response(599)))
    yield P
    lfs.reset_cache()


def install(monkeypatch, handler):
    monkeypatch.setattr(P, "_transport", httpx.MockTransport(handler))


def run(c):
    return asyncio.run(c)


GROQ_MODELS = {"data": [{"id": "llama-3.3-70b-versatile"}, {"id": "whisper-large-v3"}, {"id": "old", "active": False}]}


def groq_ok(req):
    if req.headers.get("authorization") != "Bearer gsk_" + "a" * 40:
        return httpx.Response(401, json={"error": {"message": "Invalid API Key gsk_" + "a" * 40}})
    if req.url.path.endswith("/models"):
        return httpx.Response(200, json=GROQ_MODELS)
    lines = ['data: {"choices":[{"delta":{"content":"Hello"}}]}', ": keep-alive", 'data: {"choices":[{"delta":{"content":" there"},"finish_reason":"stop"}]}', "data: [DONE]"]
    return httpx.Response(200, text="\n".join(lines) + "\n", headers={"content-type": "text/event-stream"})


# --------------------------------------------------------------------------- pure rules (same as Providers.kt)
def test_model_lists_and_free_rules():
    g = P.parse_models("gemini", {"data": [{"id": "models/gemini-3.8-flash"}, {"id": "gemini-9-pro"}, {"id": "imagen-4"}, {"id": "text-embedding"}]})
    assert [(m["id"], m["free"]) for m in g] == [("gemini-3.8-flash", True), ("gemini-9-pro", False)]
    assert [m["id"] for m in P.parse_models("groq", GROQ_MODELS)] == ["llama-3.3-70b-versatile"]
    o = P.parse_models("openrouter", {"data": [{"id": "a/b:free", "pricing": {"prompt": "0", "completion": "0"}},
                                               {"id": "c/d", "pricing": {"prompt": "0.000001", "completion": "0.000002"}}]})
    assert [(m["id"], m["free"]) for m in o] == [("openrouter/free", True), ("a/b:free", True), ("c/d", False)]
    assert P.allowed("gemini", "gemini-3.8-flash", False) and not P.allowed("gemini", "gemini-9-pro", False) and P.allowed("gemini", "gemini-9-pro", True)
    assert P.allowed("openrouter", "a/b:free", False) and not P.allowed("openrouter", "c/d", False) and P.allowed("groq", "x", False)


def test_error_messages_never_contain_keys():
    m = P.error_message("Groq", 401, '{"error":{"message":"Invalid API Key gsk_abcdefghijklmnop"}}')
    assert m == "Groq rejected the API key. Check the key in AI providers."
    assert "gsk_" not in P.error_message("Groq", 400, '{"error":{"message":"bad request for gsk_abcdefghijklmnop"}}')
    assert "rate limit" in P.error_message("Groq", 429, "{}", "12") and "12s" in P.error_message("Groq", 429, "{}", "12")
    assert "unavailable right now (HTTP 503)" in P.error_message("Groq", 503, "x")
    assert P.error_message("Google Gemini", 400, '[{"error":{"message":"Please pass a valid API key"}}]').startswith("Google Gemini rejected")
    assert P.mask("gsk_" + "a" * 36 + "WXYZ") == "gsk_••••••••WXYZ" and P.mask("short") == "••••"


def test_sse_lines():
    assert P.parse_line('data: {"choices":[{"delta":{"content":"Hi"}}]}') == ("token", "Hi")
    assert P.parse_line("data: [DONE]") == ("done", "")
    assert P.parse_line(": OPENROUTER PROCESSING") == ("skip", "")
    assert P.parse_line('data: {"error":{"message":"overloaded"}}') == ("error", "overloaded")
    assert P.parse_line('data: {"choices":[{"delta":{},"finish_reason":"length"}]}') == ("finish", "length")


@pytest.mark.parametrize("url", ["http://192.168.1.2/v1", "http://example.com/v1", "https://169.254.169.254/x", "https://10.1.2.3/v1",
                                 "https://[fe80::1]/v1", "https://100.64.0.1/v1", "https://u:p@api.x.com/v1", "file:///etc/passwd", "https://a.com/v1?x=1"])
def test_custom_endpoint_refuses_unsafe_addresses(url):
    with pytest.raises(ValueError):
        P.check_url(url)


def test_custom_endpoint_allows_https_and_this_computer():
    assert P.check_url("https://api.example.com/v1/") == "https://api.example.com/v1"
    assert P.check_url("http://127.0.0.1:1234/v1") == "http://127.0.0.1:1234/v1" and P.check_url("http://localhost:11434/v1")


def test_dns_answer_to_a_private_address_is_refused(monkeypatch):
    async def fake(host, port, type=0):
        return [(2, 1, 6, "", ("192.168.0.9", 443))]
    loop = asyncio.new_event_loop()
    monkeypatch.setattr(loop, "getaddrinfo", fake)
    with pytest.raises(P.ProviderError):
        loop.run_until_complete(P._resolve_ok("https://looks-public.example.com/v1"))
    loop.close()


# --------------------------------------------------------------------------- keys
@pytest.mark.skipif(sys.platform != "win32", reason="DPAPI is Windows-only")
def test_dpapi_round_trip_is_real_encryption():
    blob = P.protect(b"gsk_secret_value_123")
    assert b"gsk_secret" not in blob and P.unprotect(blob) == b"gsk_secret_value_123"


def test_key_saved_only_after_a_real_check_and_never_returned(prov, monkeypatch):
    install(monkeypatch, groq_ok)
    with pytest.raises(P.ProviderError, match="rejected the API key"):
        run(P.save_key("groq", "gsk_" + "b" * 40))
    assert P.status_of("groq")["state"] == "not_configured" and not P._secrets_path().exists()
    s = run(P.save_key("groq", "gsk_" + "a" * 40))
    assert s["state"] == "connected" and s["model"] == "llama-3.3-70b-versatile" and s["masked"] == "gsk_••••••••aaaa"
    dumped = json.dumps(P.all_status())
    assert "a" * 20 not in dumped and "a" * 20 not in P._secrets_path().read_text()
    import local_file_service as lfs
    assert "a" * 20 not in json.dumps(lfs.load_settings())
    assert P.key("groq") == "gsk_" + "a" * 40


def test_backups_never_include_keys(prov, monkeypatch):
    import zipfile
    import local_file_service as lfs
    install(monkeypatch, groq_ok)
    run(P.save_key("groq", "gsk_" + "a" * 40))
    z = zipfile.ZipFile(lfs.create_backup("test"))
    assert not any("secrets" in n or "provider_keys" in n for n in z.namelist())


def test_remove_deletes_key_and_settings(prov, monkeypatch):
    install(monkeypatch, groq_ok)
    run(P.save_key("groq", "gsk_" + "a" * 40))
    P.remove("groq")
    assert P.key("groq") is None and P.status_of("groq")["state"] == "not_configured"


def test_openrouter_key_is_checked_not_just_the_public_model_list(prov, monkeypatch):
    def h(req):
        if req.url.path.endswith("/models"):
            return httpx.Response(200, json={"data": [{"id": "a/b:free", "pricing": {"prompt": "0", "completion": "0"}}]})
        return httpx.Response(401, json={"error": {"message": "No auth credentials found", "code": 401}})
    install(monkeypatch, h)
    with pytest.raises(P.ProviderError, match="rejected the API key"):
        run(P.save_key("openrouter", "sk-or-v1-" + "0" * 40))
    assert P.status_of("openrouter")["state"] == "not_configured"


# --------------------------------------------------------------------------- chat
def test_stream_and_failure_never_falls_back(prov, monkeypatch):
    install(monkeypatch, groq_ok)
    run(P.save_key("groq", "gsk_" + "a" * 40))

    async def collect(model):
        return [t async for t in P.stream_chat(model, [{"role": "user", "content": "hi"}])]
    assert run(collect("@groq:llama-3.3-70b-versatile")) == ["Hello", " there"]
    calls = []
    install(monkeypatch, lambda r: calls.append(r.url.host) or httpx.Response(429, json={"error": {"message": "slow down"}}, headers={"retry-after": "30"}))
    with pytest.raises(P.ProviderError, match="rate limit reached .try again in 30s"):
        run(collect("@groq:llama-3.3-70b-versatile"))
    assert calls == ["api.groq.com"]                                 # one request, to the chosen provider only
    with pytest.raises(P.ProviderError, match="no API key"):
        run(collect("@gemini:gemini-3.8-flash"))


def test_charged_model_needs_the_owners_choice(prov, monkeypatch):
    install(monkeypatch, lambda r: httpx.Response(200, json={"data": [{"id": "gemini-3.8-flash"}, {"id": "gemini-9-pro"}]}))
    run(P.save_key("gemini", "AIza" + "a" * 35))
    assert "may be charged" in P.problem("gemini", "gemini-9-pro") and P.problem("gemini", "gemini-3.8-flash") is None
    P.update("gemini", allow_charged=True)
    assert P.problem("gemini", "gemini-9-pro") is None


def test_cloud_chat_sends_no_memories_or_documents_and_names_the_model(prov, monkeypatch, migrated):
    import agent
    import memory_service as mem
    mem.add_memory("My bank PIN is 4321 and I live at 5 Secret Road")
    sent = []

    def h(req):
        if req.url.path.endswith("/models"):
            return groq_ok(req)
        sent.append(json.loads(req.content))
        return groq_ok(req)
    install(monkeypatch, h)
    run(P.save_key("groq", "gsk_" + "a" * 40))
    monkeypatch.setattr(agent, "record_inference", lambda *a, **k: None)
    monkeypatch.setattr(agent, "document_context", lambda *a, **k: ("SECRET DOCUMENT TEXT", ["doc.pdf"]))
    r = run(agent.run_agent("Where do I live? Also what's in my documents?", None, model="@groq:llama-3.3-70b-versatile"))
    body = json.dumps(sent)
    assert "4321" not in body and "Secret Road" not in body and "SECRET DOCUMENT" not in body
    assert r["served_by"] == "@groq:llama-3.3-70b-versatile" and r["cloud"] and r["answer"] == "Hello there"
    assert mem.recent_messages(r["conversation_id"])[-1]["model"] == "@groq:llama-3.3-70b-versatile"
    with pytest.raises(ValueError, match="Project chats use your local models"):
        run(agent.run_agent("x", None, model="@groq:llama-3.3-70b-versatile", project_id="p1"))


def test_router_is_off_by_default_and_only_routes_to_a_usable_target(prov, monkeypatch):
    install(monkeypatch, groq_ok)
    coding = {"focus": "coding"}
    assert P.route("fix this bug in my code", coding) == (None, None)
    with pytest.raises(ValueError):
        P.save_router(auto=True)                                     # no target
    with pytest.raises(ValueError):
        P.save_router(coding="qwen2.5:latest")                       # targets are cloud models only
    P.save_router(auto=True, coding="@groq:llama-3.3-70b-versatile")
    assert P.route("fix this bug", coding) == (None, None)           # target not connected: stays local
    run(P.save_key("groq", "gsk_" + "a" * 40))
    assert P.route("fix this bug", coding) == ("@groq:llama-3.3-70b-versatile", "coding question")
    assert P.route("hello there", {"focus": "general"}) == (None, None)
    P.remove("groq")
    assert P.router_settings()["auto"] is False                      # removing the target turns Auto off


def test_api_routes_are_local_only(migrated):
    import companion
    from fastapi.testclient import TestClient
    import main
    assert not any(r.startswith("/providers") for r in companion.REMOTE_ROUTES) and not any(p.startswith("/providers") for p in companion.REMOTE_PREFIXES)
    c = TestClient(main.app)
    d = c.get("/providers").json()
    assert [p["id"] for p in d["providers"]] == ["gemini", "groq", "openrouter", "custom"] and d["rules"]["memories_sent"] is False
    assert c.post("/chat", json={"message": "hi", "model": "@groq:llama-3.3-70b-versatile"}).status_code == 400
    assert c.put("/providers/custom/endpoint", json={"base_url": "http://192.168.1.5/v1"}).status_code == 400


def test_page_shows_cloud_models_honestly():
    """The chip/tag text for cloud ids, and a cloud pick whose provider was removed is shown as not connected."""
    import shutil
    import subprocess
    from pathlib import Path
    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    app = Path(__file__).resolve().parents[2] / "frontend" / "app.js"
    js = r"""
const fs = require('fs'), vm = require('vm'); const src = fs.readFileSync(process.argv[1], 'utf8');
function extract(n) { let s = src.indexOf('\nfunction ' + n + '('); let i = src.indexOf('{', src.indexOf(')', s)), d = 0;
  for (; i < src.length; i++) { if (src[i] === '{') d++; else if (src[i] === '}' && --d === 0) return src.slice(s, i + 1); } }
const c = {}; vm.createContext(c);
vm.runInContext(['cloudModel', 'modelDisplay', 'modelChipView'].map(extract).join('\n') + "\nvar PROV_TITLE = { groq: 'Groq', custom: 'Custom endpoint' };", c);
const M = (cloud) => ({ loaded: true, list: [{ name: 'qwen2.5:latest' }], def: 'qwen2.5:latest', ollama: true, cloud });
console.log(JSON.stringify({
  parse: c.cloudModel('@openrouter:meta-llama/llama-3.3-70b-instruct:free'), local: c.cloudModel('qwen2.5:latest'),
  shown: c.modelDisplay('@groq:llama-3.3-70b-versatile'), plain: c.modelDisplay('qwen2.5:latest'),
  ok: c.modelChipView({ engine: 'pc', models: M([{ id: '@groq:x' }]), chatModel: '@groq:x' }),
  gone: c.modelChipView({ engine: 'pc', models: M([]), chatModel: '@groq:x' }) }));
"""
    out = subprocess.run([node, "-e", js, str(app)], capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert out.returncode == 0, out.stderr
    r = json.loads(out.stdout)
    assert r["parse"] == {"cloud": True, "provider": "openrouter", "model": "meta-llama/llama-3.3-70b-instruct:free"}
    assert r["local"] == {"cloud": False, "model": "qwen2.5:latest"}
    assert r["shown"] == "Groq · llama-3.3-70b-versatile · cloud" and r["plain"] == "qwen2.5:latest"
    assert r["ok"]["kind"] == "cloud" and r["gone"]["kind"] == "error" and "not connected" in r["gone"]["label"]
