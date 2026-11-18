package ai.abhify.android

import ai.abhify.android.ai.CloudLine
import ai.abhify.android.cloud.Account
import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.TokenStore
import ai.abhify.android.cloud.UrlHttp
import ai.abhify.android.sync.CloudSyncApi
import ai.abhify.android.sync.SyncEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * The real Kotlin client against the real cloud/ service (started here on 127.0.0.1 with a temporary
 * database). Opt-in:  ABHIFY_CLOUD_IT=1 gradlew testDebugUnitTest --tests '*CloudIntegrationTest*'
 * Add ABHIFY_CLOUD_OLLAMA_MODEL=<model> to also stream a real online answer from a local Ollama.
 */
class CloudIntegrationTest {
    companion object {
        private var server: Process? = null
        private var dataDir: File? = null
        private const val PORT = 8137
        const val BASE = "http://127.0.0.1:$PORT"

        @BeforeClass @JvmStatic fun start() {
            if (System.getenv("ABHIFY_CLOUD_IT") != "1") return
            val repo = File("../..").canonicalFile
            val python = System.getenv("ABHIFY_CLOUD_PYTHON") ?: File(repo, "backend/.venv/Scripts/python.exe").path
            dataDir = Files.createTempDirectory("abhify-cloud-it").toFile()
            server = ProcessBuilder(python, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "$PORT")
                .directory(File(repo, "cloud")).redirectErrorStream(true).redirectOutput(File(dataDir, "server.log")).apply {
                    environment()["ABHIFY_CLOUD_DATA_DIR"] = dataDir!!.path
                    environment()["ABHIFY_CLOUD_ENV"] = "dev"
                    System.getenv("ABHIFY_CLOUD_OLLAMA_MODEL")?.let { environment()["ABHIFY_CLOUD_AI_MODEL"] = it }
                }.start()
            repeat(60) {
                if (runCatching { (URL("$BASE/api/v1/health").openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)) return
                Thread.sleep(500)
            }
            error("cloud service did not start: " + File(dataDir, "server.log").readText())
        }

        @AfterClass @JvmStatic fun stop() {
            // The venv launcher starts a child python; stop the whole tree. (pid() via reflection: tests compile
            // against the Android API but run on JDK 17.)
            server?.let { p ->
                val pid = runCatching { p.javaClass.getMethod("pid").invoke(p) as Long }.getOrNull()
                if (pid != null && System.getProperty("os.name").startsWith("Windows")) ProcessBuilder("taskkill", "/F", "/T", "/PID", "$pid").start().waitFor()
                p.destroyForcibly(); p.waitFor()
            }
            dataDir?.deleteRecursively()
        }
    }

    private fun client() = CloudClient(UrlHttp(), TokenStore(MemKv(), MemBox()))
    private val pw = "Integration-Pass-1"

    @Test fun endToEnd() {
        assumeTrue("set ABHIFY_CLOUD_IT=1 to run against the real cloud service", System.getenv("ABHIFY_CLOUD_IT") == "1")

        // --- accounts
        val phone = client()
        phone.register(BASE, "asha@example.com", pw, "Asha", "Phone")
        val tablet = client()
        tablet.login(BASE, "asha@example.com", pw, "Tablet")
        assertEquals(2, phone.sessions().length())
        try { client().login(BASE, "asha@example.com", "Wrong-Pass-99", "x"); fail() } catch (e: ApiException) { assertEquals(401, e.code) }

        // --- refresh rotation with the real server; reuse of the old refresh token ends the session
        val oldRefresh = tablet.session!!.refreshToken
        tablet.accessToken(forceRefresh = true)
        assertTrue(tablet.session!!.refreshToken != oldRefresh)
        val reuse = UrlHttp().request("POST", "$BASE/api/v1/auth/refresh", emptyMap(), JSONObject().put("refresh_token", oldRefresh).toString())
        assertEquals(401, reuse.code)
        try { tablet.me(); fail("the session must be gone after reuse") } catch (e: ApiException) { assertEquals(401, e.code) }
        assertTrue(tablet.account.value is Account.Expired)
        tablet.login(BASE, "asha@example.com", pw, "Tablet")

        // --- sync between two devices through the real server rules
        val a = FakeStore("dev-phone-aaaa"); val b = FakeStore("dev-tablet-bbbb")
        a.edit("conversation", "conv-00000001", 1_000, "title" to "Gradient descent")
        a.edit("message", "msg-00000001", 1_000, "conversation_id" to "conv-00000001", "role" to "user", "content" to "hi")
        a.edit("memory", "mem-00000001", 1_000, "content" to "exam on Friday")
        assertEquals(3, SyncEngine(a, CloudSyncApi(phone)).run().pushed)
        assertEquals(3, SyncEngine(b, CloudSyncApi(tablet)).run().pulled)
        assertEquals(0, SyncEngine(a, CloudSyncApi(phone)).run().pushed)                     // idempotent

        b.edit("memory", "mem-00000001", 3_000, "content" to "exam moved to Monday"); SyncEngine(b, CloudSyncApi(tablet)).run()
        a.edit("memory", "mem-00000001", 2_000, "content" to "exam on Friday at 9")         // older concurrent edit
        SyncEngine(a, CloudSyncApi(phone)).run()
        assertEquals("exam moved to Monday", a.rows["memory" to "mem-00000001"]!!.data.getString("content"))
        assertEquals("exam on Friday at 9", a.conflicts.single().second.data.getString("content"))     // kept, not discarded

        // --- another user sees none of it
        val ravi = client(); ravi.register(BASE, "ravi@example.com", pw, "Ravi", "Phone")
        val r = FakeStore("dev-ravi-cccc")
        assertEquals(0, SyncEngine(r, CloudSyncApi(ravi)).run().pulled)

        // --- devices: end the tablet's session from the phone
        val sessions: JSONArray = phone.sessions()
        val tabletId = (0 until sessions.length()).map { sessions.getJSONObject(it) }.first { it.getString("device_name") == "Tablet" }.getString("id")
        phone.endSession(tabletId)
        try { tablet.me(); fail() } catch (e: ApiException) { assertEquals(401, e.code) }

        // --- optional: a real online answer through the gateway
        System.getenv("ABHIFY_CLOUD_OLLAMA_MODEL")?.let {
            val lines = mutableListOf<CloudLine>()
            val code = phone.stream("/api/v1/ai/chat", JSONObject().put("max_tokens", 40).put("messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "In one sentence, what is overfitting?")))) { l -> CloudLine.parse(l)?.let { lines += it }; true }
            assertEquals(200, code)
            assertTrue(lines.filterIsInstance<CloudLine.Token>().joinToString("") { it.text }.length > 20)
            assertEquals(it, (lines.last() as CloudLine.Done).model)
            println("ONLINE ANSWER: " + lines.filterIsInstance<CloudLine.Token>().joinToString("") { t -> t.text })
        }

        // --- sign out revokes on the server
        phone.logout()
        assertFalse(phone.session != null)
    }
}
