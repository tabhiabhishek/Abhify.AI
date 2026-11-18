package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.ChatEvent
import ai.abhify.android.ai.HybridAi
import ai.abhify.android.ai.RouteInputs
import ai.abhify.android.ai.Source
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.Http
import ai.abhify.android.cloud.HttpResult
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.cloud.StreamResult
import ai.abhify.android.cloud.TokenStore
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.providers.ModelInfo
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderClient
import ai.abhify.android.providers.ProviderRules
import ai.abhify.android.providers.ProviderStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mock credentials only (never real keys). */
private const val GEMINI_KEY = "AIzaFAKE-test-key-0000000000000000wxyz"
private const val GROQ_KEY = "gsk_FAKEtestkey00000000000000000000abcd"
private const val OR_KEY = "sk-or-v1-FAKEtestkey0000000000000000000000ef12"

class ProviderRulesTest {
    @Test fun masksKeys() {
        assertEquals("gsk_••••••••abcd", ProviderRules.mask(GROQ_KEY))
        assertEquals("sk-••••••••ef12", ProviderRules.mask(OR_KEY))
        assertEquals("AIza••••••••wxyz", ProviderRules.mask(GEMINI_KEY))
        assertEquals("••••", ProviderRules.mask("short"))
        listOf(GROQ_KEY, OR_KEY, GEMINI_KEY).forEach { assertFalse(ProviderRules.mask(it).contains(it.substring(8, 20))) }
    }

    @Test fun geminiModelsMarkOnlyTheFreeTier() {
        val body = """{"object":"list","data":[{"id":"models/gemini-3.8-flash"},{"id":"models/gemini-4-argon"},{"id":"models/gemini-3.5-flash-lite"},
            {"id":"models/gemini-embedding-2"},{"id":"models/imagen-4"},{"id":"models/gemini-3.1-pro-preview"}]}"""
        val m = ProviderRules.parseModels(Provider.GEMINI, body)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-4-argon"), m.map { it.id })
        assertEquals(setOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), m.filter { it.free }.map { it.id }.toSet())
        assertFalse(ProviderRules.allowed(Provider.GEMINI, "gemini-4-argon", allowCharged = false))
        assertTrue(ProviderRules.allowed(Provider.GEMINI, "gemini-4-argon", allowCharged = true))
    }

    @Test fun groqDropsNonChatAndInactiveModels() {
        val body = """{"object":"list","data":[{"id":"llama-3.3-70b-versatile","active":true},{"id":"whisper-large-v3","active":true},
            {"id":"meta-llama/llama-prompt-guard-2-22m","active":true},{"id":"old-model","active":false},{"id":"openai/gpt-oss-20b","active":true}]}"""
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-20b"), ProviderRules.parseModels(Provider.GROQ, body).map { it.id })
    }

    @Test fun openRouterOnlyFreeMeansFreeSuffixAndZeroPrice() {
        val body = """{"data":[
            {"id":"meta-llama/llama-3.3-70b-instruct:free","pricing":{"prompt":"0","completion":"0"}},
            {"id":"shady/model:free","pricing":{"prompt":"0.000001","completion":"0"}},
            {"id":"anthropic/claude-sonnet","pricing":{"prompt":"0.000003","completion":"0.000015"}},
            {"id":"zero/but-not-free-suffix","pricing":{"prompt":"0","completion":"0"}}]}"""
        val m = ProviderRules.parseModels(Provider.OPENROUTER, body)
        assertEquals("openrouter/free", m.first().id)                                         // the documented free router comes first
        assertEquals(setOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free"), m.filter { it.free }.map { it.id }.toSet())
        assertFalse(ProviderRules.allowed(Provider.OPENROUTER, "anthropic/claude-sonnet", false))
        assertTrue(ProviderRules.allowed(Provider.OPENROUTER, "meta-llama/llama-3.3-70b-instruct:free", false))
    }

    @Test fun errorsAreProviderNamedAndNeverEchoAKey() {
        val e = ProviderRules::errorMessage
        // live formats captured 2026-11-03 with a fake key
        assertEquals("Groq rejected the API key. Check the key in AI Providers.",
            e(Provider.GROQ, 401, """{"error":{"message":"Invalid API Key","type":"invalid_request_error","code":"invalid_api_key"}}""", null))
        assertEquals("Google Gemini rejected the API key. Check the key in AI Providers.",                       // Gemini says 400, not 401
            e(Provider.GEMINI, 400, """[{"error":{"code":400,"message":"Please pass a valid API key","status":"INVALID_ARGUMENT"}}]""", null))
        assertEquals("OpenRouter rejected the API key. Check the key in AI Providers.", e(Provider.OPENROUTER, 401, """{"error":{"message":"Missing Authentication header","code":401}}""", null))
        assertEquals("Groq rate limit reached (try again in 12s). Retry later or select another configured provider.", e(Provider.GROQ, 429, """{"error":{"message":"slow down"}}""", "12"))
        assertTrue(e(Provider.GEMINI, 404, """{"error":{"message":"models/x is not found"}}""", null).startsWith("The selected Google Gemini model is unavailable"))
        assertTrue(e(Provider.GROQ, 400, """{"error":{"message":"The model `x` does not exist"}}""", null).startsWith("The selected Groq model is unavailable"))
        assertTrue(e(Provider.OPENROUTER, 402, "{}", null).contains("nothing was charged"))
        assertTrue(e(Provider.GROQ, 403, "{}", null).startsWith("Groq refused access"))
        assertEquals("OpenRouter is unavailable right now (HTTP 503). Retry later.", e(Provider.OPENROUTER, 503, "<html>", null))
        assertTrue(e(Provider.GEMINI, 504, "", null).contains("timed out"))
        val leaked = e(Provider.GROQ, 403, """{"error":{"message":"key gsk_ABCDEFGHIJKLMNOP is blocked"}}""", null)
        assertFalse(leaked, leaked.contains("gsk_ABCDEFGH"))                                                   // keys in provider text are redacted
    }

    @Test fun networkFailuresAreClassified() {
        val n = { t: Throwable -> ProviderRules.networkMessage(Provider.GROQ, NetworkException("x", t)) }
        assertTrue(n(java.net.UnknownHostException("api.groq.com")).contains("no internet or the name couldn't be resolved"))
        assertTrue(n(java.net.SocketTimeoutException()).contains("timed out"))
        assertTrue(n(javax.net.ssl.SSLHandshakeException("bad cert")).startsWith("Secure connection to Groq failed"))
        assertTrue(n(java.net.ConnectException()).startsWith("Groq connection failed"))
    }

    @Test fun parsesStreamLines() {
        assertEquals(ProviderRules.Line.Skip, ProviderRules.parseLine(": OPENROUTER PROCESSING"))
        assertEquals(ProviderRules.Line.Done, ProviderRules.parseLine("data: [DONE]"))
        assertEquals(ProviderRules.Line.Token("Hi"), ProviderRules.parseLine("""data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}"""))
        assertEquals(ProviderRules.Line.Skip, ProviderRules.parseLine("""data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}"""))
        assertEquals(ProviderRules.Line.Skip, ProviderRules.parseLine("""data: {"choices":[],"usage":{"prompt_tokens":3}}"""))
        assertTrue(ProviderRules.parseLine("""data: {"error":{"code":502,"message":"Provider down"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""") is ProviderRules.Line.Error)
    }

    @Test fun usageIsOnlyWhatTheProviderReports() {
        assertEquals("Requests left today: 999 of 1000 · tokens left this minute: 7990 of 8000",
            ProviderRules.usageFromHeaders(Provider.GROQ, mapOf("x-ratelimit-remaining-requests" to "999", "x-ratelimit-limit-requests" to "1000",
                "x-ratelimit-remaining-tokens" to "7990", "x-ratelimit-limit-tokens" to "8000")))
        assertNull(ProviderRules.usageFromHeaders(Provider.GEMINI, mapOf("x-anything" to "1")))          // Gemini: unavailable, never invented
        val u = ProviderRules.usageFromOpenRouterKey("""{"data":{"usage":0.5,"is_free_tier":true,"free_model_daily_requests":{"used":3,"limit":50,"remaining":47}}}""")!!
        assertTrue(u.contains("3 of 50 (47 left)") && u.contains("Free tier"))
    }
}

class ProviderStoreTest {
    @Test fun keysAreSealedMaskedAndRemovable() {
        val kv = MemKv(); val s = ProviderStore(kv, MemBox())
        assertFalse(s.anyConfigured())
        s.saveKey(Provider.GROQ, " $GROQ_KEY ")
        assertEquals(GROQ_KEY, s.key(Provider.GROQ))
        assertTrue(kv.map.values.none { it.contains(GROQ_KEY) || it.contains("FAKEtestkey") })      // never stored in clear
        assertEquals("gsk_••••••••abcd", s.config(Provider.GROQ).masked)
        s.removeKey(Provider.GROQ)
        assertNull(s.key(Provider.GROQ)); assertFalse(s.config(Provider.GROQ).configured)
        try { s.saveKey(Provider.GEMINI, "not a key"); throw AssertionError("accepted") } catch (e: IllegalArgumentException) { }
    }

    @Test fun aSavedKeyIsUsableAtOnceButChargedModelsStayBlocked() {
        val s = ProviderStore(MemKv(), MemBox())
        assertEquals("no API key saved", s.problem(Provider.GEMINI))
        s.saveKey(Provider.GEMINI, GEMINI_KEY)
        assertTrue(s.usable(Provider.GEMINI))                                                    // RC.4: no hidden confirmation gate
        s.setModel(Provider.GEMINI, "gemini-4-argon"); assertFalse(s.usable(Provider.GEMINI))     // paid model blocked
        assertTrue(s.problem(Provider.GEMINI)!!.contains("may be charged"))
        s.setAllowCharged(Provider.GEMINI, true); assertTrue(s.usable(Provider.GEMINI))
        s.setEnabled(Provider.GEMINI, false); assertFalse(s.usable(Provider.GEMINI)); assertEquals("turned off", s.problem(Provider.GEMINI))
        s.saveKey(Provider.OPENROUTER, OR_KEY); assertTrue(s.usable(Provider.OPENROUTER))          // free router by default, no confirmation needed
    }

    @Test fun modelListIsAdoptedFromWhatTheKeyOffers() {
        val s = ProviderStore(MemKv(), MemBox()); s.saveKey(Provider.GROQ, GROQ_KEY)
        val offered = listOf(ModelInfo("openai/gpt-oss-20b", true), ModelInfo("openai/gpt-oss-120b", true))
        s.adoptModels(Provider.GROQ, offered)                         // this key lists no llama-3.3: the first preferred free model is chosen
        assertEquals("openai/gpt-oss-120b", s.config(Provider.GROQ).model)
        s.setModel(Provider.GROQ, "openai/gpt-oss-20b"); s.adoptModels(Provider.GROQ, offered)
        assertEquals("openai/gpt-oss-20b", s.config(Provider.GROQ).model)                         // the user's choice is kept while offered
    }

    @Test fun priorityDefaultsAndPersists() {
        val kv = MemKv(); val s = ProviderStore(kv, MemBox())
        assertEquals(listOf(Provider.GEMINI, Provider.GROQ, Provider.OPENROUTER), s.priority)
        s.priority = listOf(Provider.GROQ, Provider.OPENROUTER, Provider.GEMINI)
        assertEquals(listOf(Provider.GROQ, Provider.OPENROUTER, Provider.GEMINI), ProviderStore(kv, MemBox()).priority)
        s.resetAll(); assertEquals(Provider.GEMINI, s.priority.first())
    }

    @Test fun aSecondDeviceStartsWithNoKeys() {
        val phone = ProviderStore(MemKv(), MemBox()); phone.saveKey(Provider.GROQ, GROQ_KEY)
        val tablet = ProviderStore(MemKv(), MemBox())                                            // separate install = separate storage + Keystore key
        assertFalse(tablet.anyConfigured())
    }
}

/** A provider's HTTP API, scripted per test. */
class ScriptedHttp(val onRequest: (String) -> HttpResult = { HttpResult(404, "") },
                   val onStream: (String, String, (String) -> Boolean) -> StreamResult = { _, _, _ -> StreamResult(500) }) : Http {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    val bodies = mutableListOf<String>()
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult { requests += url to headers; return onRequest(url) }
    override fun stream(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean) = streamFull(url, headers, body, onLine).code
    override fun streamFull(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): StreamResult {
        requests += url to headers; bodies += body; return onStream(url, body, onLine)
    }
}

private fun sse(vararg tokens: String) = tokens.map { """data: {"choices":[{"index":0,"delta":{"content":"$it"}}]}""" } + "data: [DONE]"

class ProviderClientTest {
    private fun store(vararg ps: Provider) = ProviderStore(MemKv(), MemBox()).apply {
        ps.forEach { p -> saveKey(p, when (p) { Provider.GEMINI -> GEMINI_KEY; Provider.GROQ -> GROQ_KEY; Provider.OPENROUTER -> OR_KEY }); setFreeTierConfirmed(p, true) }
    }

    @Test fun validateUsesFreeListingCallsOnly() {
        val http = ScriptedHttp(onRequest = { url -> when {
            url.endsWith("/api/v1/key") -> HttpResult(200, """{"data":{"usage":0,"is_free_tier":true,"free_model_daily_requests":{"used":0,"limit":50,"remaining":50}}}""")
            else -> HttpResult(200, """{"data":[{"id":"meta-llama/x:free","pricing":{"prompt":"0","completion":"0"}}]}""") } })
        val c = ProviderClient(http, store()).validate(Provider.OPENROUTER, OR_KEY)
        assertTrue(c.ok); assertTrue(c.usage!!.contains("0 of 50"))
        assertEquals(listOf("https://openrouter.ai/api/v1/models", "https://openrouter.ai/api/v1/key"), http.requests.map { it.first })
        assertEquals("Bearer $OR_KEY", http.requests.first().second["Authorization"])
        val bad = ProviderClient(ScriptedHttp(onRequest = { HttpResult(401, """{"error":{"message":"bad"}}""") }), store()).validate(Provider.GROQ, GROQ_KEY)
        assertFalse(bad.ok); assertEquals("Groq rejected the API key. Check the key in AI Providers.", bad.message)
        val offline = ProviderClient(ScriptedHttp(onRequest = { throw NetworkException("x", java.net.UnknownHostException("api.groq.com")) }), store()).validate(Provider.GROQ, GROQ_KEY)
        assertTrue(offline.message, offline.message.startsWith("Groq connection failed"))
    }

    @Test fun streamsTokensAndRecordsUsage() {
        val s = store(Provider.GROQ)
        val h = mapOf("x-ratelimit-remaining-requests" to "10", "x-ratelimit-limit-requests" to "1000")   // like UrlHttp: headers arrive before the body
        val http = ScriptedHttp(onStream = { _, _, on -> sse("Gradient ", "descent").forEach { if (!on(it)) return@ScriptedHttp StreamResult(200, h) }
            StreamResult(200, h) })
        val out = StringBuilder()
        assertNull(ProviderClient(http, s).chat(Provider.GROQ, listOf("user" to "hi"), 100, 0.3) { out.append(it); true })
        assertEquals("Gradient descent", out.toString())
        val body = JSONObject(http.bodies.single())
        assertEquals("llama-3.3-70b-versatile", body.getString("model")); assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("models"))                                                           // never a fallback list
        assertEquals("Requests left today: 10 of 1000", s.config(Provider.GROQ).usage)
        assertTrue(s.config(Provider.GROQ).lastOkAt != null)
    }

    @Test fun rateLimitMidStreamErrorAndCancellation() {
        val s = store(Provider.OPENROUTER)
        val limited = ScriptedHttp(onStream = { _, _, _ -> StreamResult(429, mapOf("retry-after" to "30"), """{"error":{"code":429,"message":"Rate limit exceeded"}}""") })
        val e = ProviderClient(limited, s).chat(Provider.OPENROUTER, listOf("user" to "hi"), 50, 0.3) { true }!!
        assertTrue(e.contains("30s")); assertEquals(e, s.config(Provider.OPENROUTER).lastError)
        val midError = ScriptedHttp(onStream = { _, _, on -> on("data: {\"choices\":[{\"delta\":{\"content\":\"Hal\"}}]}")
            on("""data: {"error":{"code":502,"message":"Upstream down"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}"""); StreamResult(200) })
        assertEquals("OpenRouter: Upstream down", ProviderClient(midError, s).chat(Provider.OPENROUTER, listOf("user" to "hi"), 50, 0.3) { true })
        val dropped = ScriptedHttp(onStream = { _, _, on -> on(sse("Hal").first()); throw NetworkException("reset") })
        assertEquals("The connection to OpenRouter was interrupted during the answer.", ProviderClient(dropped, s).chat(Provider.OPENROUTER, listOf("user" to "hi"), 50, 0.3) { true })
        val empty = ScriptedHttp(onStream = { _, _, on -> on("data: [DONE]"); StreamResult(200) })
        assertTrue(ProviderClient(empty, s).chat(Provider.OPENROUTER, listOf("user" to "hi"), 50, 0.3) { true }!!.startsWith("OpenRouter returned an empty answer"))
        var seen = 0
        val long = ScriptedHttp(onStream = { _, _, on -> for (l in sse("a", "b", "c", "d")) if (!on(l)) break; StreamResult(200) })
        ProviderClient(long, s).chat(Provider.OPENROUTER, listOf("user" to "hi"), 50, 0.3) { ++seen < 2 }      // user taps Stop after 2 tokens
        assertEquals(2, seen)
    }

    @Test fun chargedModelIsRefusedWithoutAnyRequest() {
        val s = store(Provider.GEMINI); s.setModel(Provider.GEMINI, "gemini-4-argon")
        val http = ScriptedHttp()
        val e = ProviderClient(http, s).chat(Provider.GEMINI, listOf("user" to "hi"), 50, 0.3) { true }!!
        assertTrue(e.contains("may be charged")); assertTrue(http.requests.isEmpty())
    }
}

class HybridProviderFlowTest {
    private val prompt = listOf(PromptBuilder.Msg("user", "What is overfitting?"))

    private fun ai(http: Http, store: ProviderStore) = HybridAi(CloudClient(http, TokenStore(MemKv(), MemBox())), ProviderClient(http, store),
        LlmEngine("/nonexistent"), { "no model" }, { null }, { p -> store.config(p).model })

    private fun inputs(vararg usable: Provider, others: Boolean = true) = RouteInputs(AiMode.AUTO, true, true, false, false, usable.toList(), null, false, others)

    @Test fun autoMovesToTheNextProviderOnlyBeforeTheFirstWord() = runBlocking {
        val store = ProviderStore(MemKv(), MemBox()).apply {
            saveKey(Provider.GEMINI, GEMINI_KEY); setFreeTierConfirmed(Provider.GEMINI, true); saveKey(Provider.GROQ, GROQ_KEY); setFreeTierConfirmed(Provider.GROQ, true)
        }
        val http = ScriptedHttp(onStream = { url, _, on ->
            if (url.contains("googleapis")) StreamResult(429, emptyMap(), """[{"error":{"message":"quota"}}]""")
            else { sse("Overfitting ", "is…").forEach { on(it) }; StreamResult(200) } })
        val ev = ai(http, store).generate(prompt, prompt, inputs(Provider.GEMINI, Provider.GROQ)).toList()
        assertTrue(ev.any { it is ChatEvent.Notice && (it as ChatEvent.Notice).text.contains("Gemini unavailable") })
        assertEquals("Overfitting is…", ev.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text })
        assertEquals(Source.Kind.GROQ, (ev.last() as ChatEvent.Done).source.kind)                  // labelled with who really answered

        val midway = ScriptedHttp(onStream = { url, _, on ->
            if (url.contains("googleapis")) { on("data: {\"choices\":[{\"delta\":{\"content\":\"Half\"}}]}"); throw NetworkException("dropped") }
            else { sse("x").forEach { on(it) }; StreamResult(200) } })
        val ev2 = ai(midway, store).generate(prompt, prompt, inputs(Provider.GEMINI, Provider.GROQ)).toList()
        assertTrue(ev2.last() is ChatEvent.Failed)                                                  // no stitching two models into one answer
        assertTrue(midway.requests.none { it.first.contains("groq") })

        // default (other providers not allowed): Gemini's failure is reported and Groq never receives the text
        val strict = ScriptedHttp(onStream = { url, _, on ->
            if (url.contains("googleapis")) StreamResult(429, emptyMap(), """[{"error":{"message":"quota"}}]""") else { sse("x").forEach { on(it) }; StreamResult(200) } })
        val ev3 = ai(strict, store).generate(prompt, prompt, inputs(Provider.GEMINI, Provider.GROQ, others = false)).toList()
        assertTrue((ev3.filterNot { it is ChatEvent.Attempt }.single() as ChatEvent.Failed).message.startsWith("Google Gemini rate limit reached"))
        assertTrue(strict.requests.none { it.first.contains("groq") })
    }

    @Test fun onlineModeWithAPersonalKeyNeverCallsTheAbhifyServer() = runBlocking {
        val store = ProviderStore(MemKv(), MemBox()).apply { saveKey(Provider.GROQ, GROQ_KEY) }
        val http = ScriptedHttp(onStream = { _, _, on -> sse("Hi").forEach { on(it) }; StreamResult(200) })
        val i = RouteInputs(AiMode.ONLINE, true, true, false, false, listOf(Provider.GROQ), ai.abhify.android.ai.OnlineTarget.Personal(Provider.GROQ), false)
        val ev = ai(http, store).generate(prompt, prompt, i).toList()
        assertEquals(Source.Kind.GROQ, (ev.last() as ChatEvent.Done).source.kind)
        assertTrue(http.requests.all { it.first.startsWith("https://api.groq.com/") })
    }

    @Test fun nothingConfiguredExplainsWhy() = runBlocking {
        val ev = ai(ScriptedHttp(), ProviderStore(MemKv(), MemBox())).generate(prompt, prompt, inputs()).toList()
        assertTrue((ev.single() as ChatEvent.Failed).message.contains("API key"))
    }
}

/** No real-looking provider key anywhere in the app's sources, resources or assets. */
class NoKeysInSourceTest {
    @Test fun noKeysCommitted() {
        val patterns = listOf(Regex("AIza[0-9A-Za-z_\\-]{35}"), Regex("gsk_[A-Za-z0-9]{40,}"), Regex("sk-or-v1-[a-f0-9]{40,}"))
        val roots = listOf(File("src/main"), File("../../cloud/app"), File("../../docs"))
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "json", "py", "md", "txt", "properties") }.toList() }
            .filter { f -> val t = f.readText(); patterns.any { it.containsMatchIn(t) } }
        assertEquals(emptyList<File>(), hits)
    }

    @Test fun syncAndExportCodeNeverTouchesProviderKeys() {
        for (f in listOf("src/main/java/ai/abhify/android/chat/ChatDb.kt", "src/main/java/ai/abhify/android/sync/Sync.kt", "src/main/java/ai/abhify/android/sync/SyncWork.kt")) {
            val t = File(f).readText()
            assertFalse(f, t.contains("ProviderStore") || t.contains("abhify_providers") || t.contains("providerStore"))
        }
    }
}
