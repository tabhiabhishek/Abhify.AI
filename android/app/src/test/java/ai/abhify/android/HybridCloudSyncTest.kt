package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.CloudLine
import ai.abhify.android.ai.Route
import ai.abhify.android.ai.OnlineTarget
import ai.abhify.android.ai.RouteInputs
import ai.abhify.android.ai.RoutePolicy
import ai.abhify.android.providers.Provider
import ai.abhify.android.ai.Source
import ai.abhify.android.chat.CodeHighlight
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.cloud.Account
import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.Http
import ai.abhify.android.cloud.HttpResult
import ai.abhify.android.cloud.KeyValue
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.cloud.SecretBox
import ai.abhify.android.cloud.Session
import ai.abhify.android.cloud.TokenStore
import ai.abhify.android.sync.PullPage
import ai.abhify.android.sync.PushResult
import ai.abhify.android.sync.SyncApi
import ai.abhify.android.sync.SyncEngine
import ai.abhify.android.sync.SyncMerge
import ai.abhify.android.sync.SyncRecord
import ai.abhify.android.sync.SyncStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

// ====================================================================== hybrid routing (RC.3: plan of attempts)
class HybridRoutingTest {
    private fun inputs(mode: AiMode, online: Boolean = true, allowed: Boolean = true, signed: Boolean = false, model: Boolean = true,
                       usable: List<Provider> = emptyList(), target: OnlineTarget? = null, fallback: Boolean = true, others: Boolean = false) =
        RouteInputs(mode, online, allowed, signed, model, usable, target, fallback, others)

    @Test fun deviceModeNeverNeedsTheNetwork() {
        assertEquals(listOf(Route.Device), RoutePolicy.plan(inputs(AiMode.DEVICE, online = false, usable = listOf(Provider.GEMINI))))
        assertTrue(RoutePolicy.plan(inputs(AiMode.DEVICE, model = false)).single() is Route.Unavailable)
    }

    @Test fun onlineModeUsesExactlyTheChosenTarget() {
        val g = OnlineTarget.Personal(Provider.GEMINI)
        assertEquals(listOf(Route.Personal(Provider.GEMINI)), RoutePolicy.plan(inputs(AiMode.ONLINE, usable = listOf(Provider.GROQ, Provider.GEMINI), target = g)))
        for (i in listOf(inputs(AiMode.ONLINE, allowed = false, usable = listOf(Provider.GEMINI), target = g),
                         inputs(AiMode.ONLINE, online = false, usable = listOf(Provider.GEMINI), target = g),
                         inputs(AiMode.ONLINE, usable = emptyList(), target = g),                                        // key missing/not confirmed
                         inputs(AiMode.ONLINE, target = OnlineTarget.Shared, signed = false),
                         inputs(AiMode.ONLINE, target = null)))
            assertTrue("$i", RoutePolicy.plan(i).single() is Route.Unavailable)             // never a silent switch to another target
        assertEquals(listOf(Route.Server), RoutePolicy.plan(inputs(AiMode.ONLINE, target = OnlineTarget.Shared, signed = true)))
    }

    @Test fun personalKeysNeedNoAccountAndNoProviderIsExplained() {
        // RC.4 P0: a saved Gemini/Groq/OpenRouter key works with no Abhify account and is never routed to the server
        for (p in Provider.entries)
            assertEquals(listOf(Route.Personal(p)), RoutePolicy.plan(inputs(AiMode.ONLINE, signed = false, usable = listOf(p), target = OnlineTarget.Personal(p))))
        assertEquals("No online provider configured. Open AI Providers to add or select a provider.",
            (RoutePolicy.plan(inputs(AiMode.ONLINE, signed = true, target = null)).single() as Route.Unavailable).reason)
        val notReady = RoutePolicy.plan(RouteInputs(AiMode.ONLINE, true, true, false, true, emptyList(), OnlineTarget.Personal(Provider.GROQ), true,
            problems = mapOf(Provider.GROQ to "no API key saved"))).single() as Route.Unavailable
        assertEquals("Groq isn't ready: no API key saved. Open AI Providers.", notReady.reason)
        // signed in AND a provider selected: still the provider, never the server
        assertEquals(listOf(Route.Personal(Provider.GEMINI)), RoutePolicy.plan(inputs(AiMode.ONLINE, signed = true, usable = listOf(Provider.GEMINI), target = OnlineTarget.Personal(Provider.GEMINI))))
    }

    @Test fun autoFollowsPriorityThenServerThenPhone() {
        // default: only the first provider receives the text, then the phone (if allowed); no hidden provider/server fallback
        assertEquals(listOf(Route.Personal(Provider.GROQ), Route.Device), RoutePolicy.plan(inputs(AiMode.AUTO, signed = true, usable = listOf(Provider.GROQ, Provider.GEMINI))))
        val plan = RoutePolicy.plan(inputs(AiMode.AUTO, signed = true, usable = listOf(Provider.GROQ, Provider.GEMINI), others = true))
        assertEquals(listOf(Route.Personal(Provider.GROQ), Route.Personal(Provider.GEMINI), Route.Server, Route.Device), plan)
        assertEquals(listOf(Route.Server, Route.Device), RoutePolicy.plan(inputs(AiMode.AUTO, signed = true)))       // no own keys: shared access
        assertEquals(listOf(Route.Personal(Provider.GROQ)), RoutePolicy.plan(inputs(AiMode.AUTO, usable = listOf(Provider.GROQ), fallback = false)))
        assertEquals(listOf(Route.Device), RoutePolicy.plan(inputs(AiMode.AUTO, allowed = false, usable = listOf(Provider.GROQ))))   // not allowed: phone only
        assertEquals(listOf(Route.Device), RoutePolicy.plan(inputs(AiMode.AUTO, online = false, usable = listOf(Provider.GROQ), fallback = false)))  // offline: phone even if fallback off
        assertTrue(RoutePolicy.plan(inputs(AiMode.AUTO, online = false, model = false)).single() is Route.Unavailable)
        assertTrue(RoutePolicy.mayTryNext(0)); assertFalse(RoutePolicy.mayTryNext(2))
    }

    @Test fun gatewayLinesAndLabels() {
        assertEquals(CloudLine.Token("Hi"), CloudLine.parse("""{"type":"token","text":"Hi"}"""))
        assertEquals(CloudLine.Done("qwen2.5:3b", ""), CloudLine.parse("""{"type":"done","model":"qwen2.5:3b","served_by":"cloud"}"""))
        assertTrue(CloudLine.parse("""{"type":"error","message":"down"}""") is CloudLine.Error)
        assertNull(CloudLine.parse("not json"))
        val s = Source.of(Provider.GROQ, "llama-3.3-70b-versatile")
        assertEquals(s, Source.decode(s.encode())); assertEquals("☁ Groq · llama-3.3-70b-versatile", s.label)
        assertEquals(Source(Source.Kind.SERVER, "qwen"), Source.decode("cloud:qwen"))            // rc.2 history still labelled
        assertEquals(Source(Source.Kind.DEVICE, "Qwen2.5 1.5B"), Source.decode("device:Qwen2.5 1.5B"))
        assertTrue(Source(Source.Kind.DEVICE, "x").label.startsWith("📱 On this phone"))
        assertNull(Source.decode(null))
    }

    @Test fun onlinePromptCanLeaveMemoriesOut() {
        val local = PromptBuilder.build("hi", emptyList(), listOf("secret plan"), "Abhi")
        val online = PromptBuilder.build("hi", emptyList(), emptyList(), "Abhi", onDevice = false)
        assertTrue(local[0].content.contains("secret plan") && local[0].content.contains("own phone"))
        assertFalse(online[0].content.contains("secret plan")); assertFalse(online[0].content.contains("own phone"))
    }

    @Test fun codeHighlighting() {
        val t = CodeHighlight.tokenize("def f(x):\n    return x + 1  # add\ns = 'hi'", "py")
        assertTrue(t.any { it.text == "def" && it.kind == CodeHighlight.Kind.KEYWORD })
        assertTrue(t.any { it.text == "f" && it.kind == CodeHighlight.Kind.FUNCTION })
        assertTrue(t.any { it.text == "# add" && it.kind == CodeHighlight.Kind.COMMENT })
        assertTrue(t.any { it.text == "'hi'" && it.kind == CodeHighlight.Kind.STRING })
        assertTrue(t.any { it.text == "1" && it.kind == CodeHighlight.Kind.NUMBER })
        assertEquals("def f(x):\n    return x + 1  # add\ns = 'hi'", t.joinToString("") { it.text })    // nothing lost
        assertEquals(listOf(CodeHighlight.Token("whatever", CodeHighlight.Kind.PLAIN)), CodeHighlight.tokenize("whatever", "brainfuck"))
        assertTrue(CodeHighlight.tokenize("SELECT x FROM t -- c", "sql").any { it.text == "SELECT" && it.kind == CodeHighlight.Kind.KEYWORD })
    }
}

// ====================================================================== cloud client
/** AES-GCM with an in-memory key: the same sealing scheme as KeystoreBox, without the Android Keystore. */
class MemBox : SecretBox {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun seal(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key); iv + doFinal(plain) }
    override fun open(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); doFinal(sealed, 12, sealed.size - 12)
    }
}

class MemKv : KeyValue {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class CloudClientTest {
    private val user = """"user":{"id":"u1","email":"a@b.co","display_name":"Asha","created_at":1}"""
    private fun tokens(n: Int) = """{$user,"session_id":"s1","access_token":"at_$n","access_expires_in":900,"refresh_token":"rt_$n","refresh_expires_in":2592000}"""

    private class FakeHttp(val handler: (String, String, Map<String, String>, String?) -> HttpResult) : Http {
        val calls = mutableListOf<String>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult { calls += "$method ${url.substringAfter("/api/v1")}"; return handler(method, url, headers, body) }
        override fun stream(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean): Int = 200
    }

    @Test fun tokenStoreIsSealedAndTamperIsSignedOut() {
        val kv = MemKv(); val store = TokenStore(kv, MemBox())
        store.save(Session("https://x", "u", "a@b.co", "A", "s", "at_secret", 5, "rt_secret"))
        assertFalse(kv.map["session"]!!.contains("secret"))                                    // not readable in storage
        assertEquals("at_secret", store.load()!!.accessToken)
        kv.map["session"] = kv.map["session"]!!.dropLast(4) + "AAAA"                              // tampered
        assertNull(store.load())
    }

    @Test fun loginRefreshRetryAndExpiry() {
        var now = 1_000_000L; var refreshes = 0; var rejectNextMe = false; var refreshAllowed = true
        val http = FakeHttp { m, url, h, _ ->
            when {
                url.endsWith("/auth/login") -> HttpResult(200, tokens(0))
                url.endsWith("/auth/refresh") -> if (refreshAllowed) { refreshes++; HttpResult(200, tokens(refreshes)) } else HttpResult(401, """{"detail":"Session expired. Sign in again."}""")
                url.endsWith("/users/me") -> if (rejectNextMe) { rejectNextMe = false; HttpResult(401, """{"detail":"Session expired or signed out."}""") }
                    else HttpResult(200, """{$user,"session_id":"s1","auth":"${h["Authorization"]}"}""")
                else -> HttpResult(404, "{}")
            }
        }
        val c = CloudClient(http, TokenStore(MemKv(), MemBox())) { now }
        c.login("https://srv.example/", "a@b.co", "pw", "Phone")
        assertEquals("https://srv.example", c.session!!.serverUrl)
        assertEquals("Bearer at_0", c.me().getString("auth"))
        now += 20 * 60_000                                                                      // access token expired
        assertEquals("Bearer at_1", c.me().getString("auth")); assertEquals(1, refreshes)
        rejectNextMe = true                                                                      // server revoked the access token early
        assertEquals("Bearer at_2", c.me().getString("auth")); assertEquals(2, refreshes)
        refreshAllowed = false; now += 20 * 60_000
        try { c.me(); fail() } catch (e: ApiException) { assertEquals(401, e.code) }
        assertTrue(c.account.value is Account.Expired)                                          // local data untouched; user signs in again
        assertNull(c.session)
    }

    @Test fun errorsAreReadableAndLogoutWorksOffline() {
        val http = FakeHttp { _, url, _, _ -> if (url.endsWith("/auth/login")) HttpResult(401, """{"detail":"Wrong email or password."}""") else throw NetworkException("offline") }
        val c = CloudClient(http, TokenStore(MemKv(), MemBox()))
        try { c.login("https://s", "a@b.co", "x", "P"); fail() } catch (e: ApiException) { assertEquals("Wrong email or password.", e.message) }
        assertEquals("The server answered HTTP 502.", CloudClient.detail("<html>", 502))
        assertEquals("bad", CloudClient.detail("""{"detail":[{"msg":"bad"}]}""", 422))
        val ok = CloudClient(FakeHttp { _, url, _, _ -> if (url.endsWith("/auth/login")) HttpResult(200, tokens(0)) else throw NetworkException("offline") }, TokenStore(MemKv(), MemBox()))
        ok.login("https://s", "a@b.co", "x", "P")
        ok.logout()                                                                             // server unreachable: still signed out here
        assertEquals(Account.SignedOut, ok.account.value)
    }
}

// ====================================================================== sync engine
/** An in-memory phone database with the same rules as ChatDb's SyncStore. */
class FakeStore(private val device: String) : SyncStore {
    data class Row(var data: JSONObject, var updatedAt: Long, var deleted: Boolean, var dirty: Boolean)
    val rows = mutableMapOf<Pair<String, String>, Row>()
    val conflicts = mutableListOf<Pair<SyncRecord, Row>>()
    var kinds = setOf("conversation", "message", "memory", "preference")
    private var cursor = 0L
    fun edit(kind: String, id: String, at: Long, vararg kv: Pair<String, Any>) { rows[kind to id] = Row(JSONObject(kv.toMap()), at, false, true) }
    override fun deviceId() = device
    override fun cursor() = cursor
    override fun setCursor(seq: Long) { cursor = seq }
    override fun dirty(limit: Int) = rows.filter { it.value.dirty && it.key.first in kinds }.map { (k, r) -> SyncRecord(k.first, k.second, r.updatedAt, r.deleted, r.data) }.take(limit)
    override fun markClean(kind: String, id: String, updatedAt: Long) { rows[kind to id]?.takeIf { it.updatedAt == updatedAt }?.dirty = false }
    override fun localMeta(kind: String, id: String) = rows[kind to id]?.let { it.updatedAt to it.dirty }
    override fun applyRemote(r: SyncRecord) { rows[r.kind to r.id] = Row(r.data, r.updatedAt, r.deleted, false) }
    override fun captureConflict(remote: SyncRecord) { rows[remote.kind to remote.id]?.let { if (it.data.toString() != remote.data.toString()) conflicts += remote to it.copy() } }
    override fun accepts(kind: String) = kind in kinds
}

/** The server's rules (same as cloud/app/main.py), in memory. */
class FakeServer {
    val records = mutableMapOf<Pair<String, String>, Pair<SyncRecord, Long>>()
    var seq = 0L
    fun api() = object : SyncApi {
        override fun push(deviceId: String, changes: List<SyncRecord>): PushResult {
            val acc = mutableListOf<Pair<String, String>>(); val same = mutableListOf<String>(); val conf = mutableListOf<SyncRecord>()
            changes.forEach { c ->
                val old = records[c.kind to c.id]?.first
                when {
                    old != null && old.updatedAt == c.updatedAt && old.deviceId == deviceId && old.data.toString() == c.data.toString() -> same += c.id
                    old != null && compareValues(old.updatedAt to old.deviceId, c.updatedAt to deviceId) >= 0 -> conf += old
                    else -> { seq++; records[c.kind to c.id] = c.copy(deviceId = deviceId) to seq; acc += c.kind to c.id }
                }
            }
            return PushResult(acc, same, conf, emptyList())
        }
        override fun pull(since: Long): PullPage {
            val page = records.values.filter { it.second > since }.sortedBy { it.second }
            val take = page.take(2)                                                      // tiny pages exercise pagination
            return PullPage(take.map { it.first }, take.lastOrNull()?.second ?: since, page.size > 2)
        }
    }
    private fun compareValues(a: Pair<Long, String>, b: Pair<Long, String>) = compareValuesBy(a, b, { it.first }, { it.second })
}

class SyncEngineTest {
    @Test fun firstSyncThenSecondDeviceGetsEverything() {
        val server = FakeServer(); val phone = FakeStore("dev-a"); val tablet = FakeStore("dev-b")
        phone.edit("conversation", "conv-0001", 100, "title" to "Gradient descent")
        phone.edit("message", "msg-0001", 100, "conversation_id" to "conv-0001", "role" to "user", "content" to "hi")
        phone.edit("memory", "mem-0001", 100, "content" to "likes PyTorch")
        val r = SyncEngine(phone, server.api()).run()
        assertEquals(3, r.pushed); assertTrue(phone.dirty(10).isEmpty())
        val t = SyncEngine(tablet, server.api()).run()
        assertEquals(3, t.pulled); assertEquals("likes PyTorch", tablet.rows["memory" to "mem-0001"]!!.data.getString("content"))
        assertEquals(0, SyncEngine(tablet, server.api()).run().pulled)                         // incremental: nothing new
        assertEquals(0, SyncEngine(phone, server.api()).run().pulled)                          // own changes aren't re-applied
    }

    @Test fun repeatedRunsAreIdempotent() {
        val server = FakeServer(); val phone = FakeStore("dev-a")
        phone.edit("memory", "mem-0001", 100, "content" to "x")
        SyncEngine(phone, server.api()).run()
        phone.rows["memory" to "mem-0001"]!!.dirty = true                                      // e.g. the app died before marking clean
        val again = SyncEngine(phone, server.api()).run()
        assertEquals(0, again.pushed); assertEquals(1L, server.seq); assertTrue(phone.dirty(10).isEmpty())
    }

    @Test fun concurrentEditsKeepBothVersions() {
        val server = FakeServer(); val phone = FakeStore("dev-a"); val tablet = FakeStore("dev-b")
        phone.edit("memory", "mem-0001", 100, "content" to "exam on Friday"); SyncEngine(phone, server.api()).run(); SyncEngine(tablet, server.api()).run()
        tablet.edit("memory", "mem-0001", 300, "content" to "exam moved to Monday"); SyncEngine(tablet, server.api()).run()
        phone.edit("memory", "mem-0001", 200, "content" to "exam on Friday at 9")            // older, unsent edit on the phone
        val r = SyncEngine(phone, server.api()).run()
        assertEquals("exam moved to Monday", phone.rows["memory" to "mem-0001"]!!.data.getString("content"))   // newest wins for display
        assertEquals("exam on Friday at 9", phone.conflicts.single().second.data.getString("content"))        // ...but the phone's edit is kept
        assertTrue(r.conflictsResolved >= 1)
    }

    @Test fun newerLocalEditSurvivesAnOlderRemote() {
        val local = Pair(500L, true)
        assertFalse(SyncMerge.remoteWins(local, SyncRecord("memory", "m", 400, false, JSONObject(), "dev-z"), "dev-a"))
        assertTrue(SyncMerge.remoteWins(local, SyncRecord("memory", "m", 600, false, JSONObject(), "dev-a"), "dev-b"))
        assertTrue(SyncMerge.remoteWins(Pair(500L, false), SyncRecord("memory", "m", 500, false, JSONObject(), "dev-z"), "dev-a"))
        assertTrue(SyncMerge.remoteWins(null, SyncRecord("memory", "m", 1, false, JSONObject()), "dev-a"))
    }

    @Test fun disabledKindsNeitherUploadNorDownload() {
        val server = FakeServer(); val phone = FakeStore("dev-a"); val tablet = FakeStore("dev-b")
        phone.kinds = setOf("conversation", "message")
        phone.edit("memory", "mem-0001", 100, "content" to "private"); phone.edit("conversation", "conv-0001", 100, "title" to "t")
        SyncEngine(phone, server.api()).run()
        assertEquals(setOf("conversation" to "conv-0001"), server.records.keys)
        tablet.edit("memory", "mem-0002", 100, "content" to "from tablet"); SyncEngine(tablet, server.api()).run()
        SyncEngine(phone, server.api()).run()
        assertNull(phone.rows["memory" to "mem-0002"])
    }

    @Test fun deletionsPropagate() {
        val server = FakeServer(); val phone = FakeStore("dev-a"); val tablet = FakeStore("dev-b")
        phone.edit("memory", "mem-0001", 100, "content" to "x"); SyncEngine(phone, server.api()).run(); SyncEngine(tablet, server.api()).run()
        phone.rows["memory" to "mem-0001"] = FakeStore.Row(JSONObject(), 200, true, true); SyncEngine(phone, server.api()).run()
        SyncEngine(tablet, server.api()).run()
        assertTrue(tablet.rows["memory" to "mem-0001"]!!.deleted)
    }
}
