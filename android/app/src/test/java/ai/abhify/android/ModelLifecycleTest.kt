package ai.abhify.android

import ai.abhify.android.model.CatalogModel
import ai.abhify.android.model.Downloader
import ai.abhify.android.model.ModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Downloads (resume, interruption, corruption, storage) and the install/activate/recover rules, against a local HTTP server. */
class ModelLifecycleTest {
    private lateinit var dir: File
    private lateinit var server: TestServer
    private val payload = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    // server behaviour knobs
    @Volatile private var cutAfter = -1          // close the connection after this many bytes of the body
    @Volatile private var ignoreRange = false
    @Volatile private var status = 200
    @Volatile private var body: ByteArray = payload
    private val requests = AtomicInteger()
    private val ranges = mutableListOf<String?>()
    private val url get() = "http://127.0.0.1:${server.port}/model.gguf"
    private val dl = Downloader(allowInsecureForTests = true)

    /** Minimal HTTP/1.1 server on a raw socket (the JDK's HttpServer isn't visible to Android unit tests). */
    private inner class TestServer : Thread("test-http") {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        override fun run() {
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (e: Exception) { return }
                Thread { serve(c) }.start()
            }
        }
        private fun serve(c: Socket) = c.use {
            val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            input.readLine() ?: return
            var range: String? = null
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
            requests.incrementAndGet(); synchronized(ranges) { ranges += range }
            val out = c.getOutputStream()
            val crlf = "\r\n"
            if (status != 200) { out.write("HTTP/1.1 $status X${crlf}Content-Length: 0${crlf}Connection: close$crlf$crlf".toByteArray()); return }
            val start = if (range != null && !ignoreRange) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val slice = body.copyOfRange(start, body.size)
            val head = (if (start > 0) "HTTP/1.1 206 Partial Content${crlf}Content-Range: bytes $start-${body.size - 1}/${body.size}$crlf"
                        else "HTTP/1.1 200 OK$crlf") + "Content-Length: ${slice.size}${crlf}Connection: close$crlf$crlf"
            try {
                out.write(head.toByteArray())
                if (cutAfter in 0 until slice.size) { out.write(slice, 0, cutAfter); out.flush(); return }
                out.write(slice); out.flush()
            } catch (_: Exception) { }
        }
        fun shutdown() = socket.close()
    }

    @Before fun up() {
        dir = Files.createTempDirectory("abhify-test").toFile()
        server = TestServer().also { it.isDaemon = true; it.start() }
    }

    @After fun down() { server.shutdown(); dir.deleteRecursively() }

    private fun part() = File(dir, "staging/m.part")

    @Test fun fullDownloadIsVerified() {
        val r = dl.download(url, payload.size.toLong(), sha, part())
        assertTrue(r is Downloader.Result.Done)
        assertEquals(sha, Downloader.sha256(part()))
    }

    @Test fun interruptedDownloadResumesWithRange() {
        cutAfter = 1_000_000
        val r1 = dl.download(url, payload.size.toLong(), sha, part())
        assertTrue("$r1", r1 is Downloader.Result.Interrupted)
        val kept = part().length()
        assertTrue(kept in 1..payload.size - 1)
        cutAfter = -1
        val r2 = dl.download(url, payload.size.toLong(), sha, part())
        assertTrue("$r2", r2 is Downloader.Result.Done)
        assertEquals("bytes=$kept-", ranges.last())                // resumed, not restarted
        assertEquals(sha, Downloader.sha256(part()))
    }

    @Test fun pauseKeepsThePartFile() {
        var calls = 0
        val r = dl.download(url, payload.size.toLong(), sha, part(), isStopped = { ++calls > 3 })
        assertTrue(r is Downloader.Result.Interrupted && (r as Downloader.Result.Interrupted).reason == "Paused")
        assertTrue(part().exists() && part().length() > 0)
    }

    @Test fun corruptedFileIsDeletedAndNeverUsed() {
        body = payload.copyOf().also { it[12345] = (it[12345] + 1).toByte() }
        val r = dl.download(url, payload.size.toLong(), sha, part())
        assertTrue(r is Downloader.Result.Failed && (r as Downloader.Result.Failed).partDeleted)
        assertTrue(r.toString().contains("SHA-256"))
        assertFalse(part().exists())
    }

    @Test fun wrongSizeOnServerIsRefusedBeforeDownloading() {
        body = payload.copyOf(payload.size - 10)
        val r = dl.download(url, payload.size.toLong(), sha, part())
        assertTrue(r is Downloader.Result.Failed && r.toString().contains("different size"))
    }

    @Test fun lowStorageIsRefusedWithoutAnyRequest() {
        val tight = Downloader(allowInsecureForTests = true, freeBytes = { 10L * 1024 * 1024 })
        val r = tight.download(url, payload.size.toLong(), sha, part())
        assertTrue(r is Downloader.Result.Failed && r.toString().contains("Not enough storage"))
        assertEquals(0, requests.get())
    }

    @Test fun serverIgnoringRangeRestartsCleanly() {
        cutAfter = 500_000; dl.download(url, payload.size.toLong(), sha, part())
        cutAfter = -1; ignoreRange = true
        assertTrue(dl.download(url, payload.size.toLong(), sha, part()) is Downloader.Result.Done)
        assertEquals(sha, Downloader.sha256(part()))
    }

    @Test fun busyServerIsResumableAndNotFoundIsAFailure() {
        status = 503; assertTrue(dl.download(url, payload.size.toLong(), sha, part()) is Downloader.Result.Interrupted)
        status = 404; assertTrue(dl.download(url, payload.size.toLong(), sha, part()) is Downloader.Result.Failed)
    }

    @Test fun httpIsRefusedInTheApp() {
        val r = Downloader().download(url, payload.size.toLong(), sha, part())
        assertTrue(r is Downloader.Result.Failed && r.toString().contains("HTTPS"))
        assertEquals(0, requests.get())
    }

    @Test fun noNetworkIsResumable() {
        server.shutdown()
        assertTrue(dl.download(url, payload.size.toLong(), sha, part()) is Downloader.Result.Interrupted)
    }

    // ---------------------------------------------------------------- install / activate / recover
    private fun model(id: String, version: String = "v1") = CatalogModel(id, id, "1B", version, "https://x.invalid/$id", "test", "Apache-2.0", "", "",
        "gguf", "Q4_K_M", payload.size.toLong(), sha, 1000, 2000, listOf(2048), 2048, 29, listOf("arm64-v8a"), false, false, "")

    private fun staged(store: ModelStore, id: String): File = store.partFile(id).also { it.parentFile!!.mkdirs(); it.writeBytes(payload) }

    @Test fun modelActivatesOnlyAfterARealTestAnswer() {
        val store = ModelStore(File(dir, "models"))
        val r = store.install(model("a"), staged(store, "a"), "llama.cpp v0.7.0", 2048) { ModelStore.SelfTestResult(true, "answered OK") }
        assertTrue(r.passed)
        assertEquals("a", store.activeId())
        val rec = store.active()!!
        assertEquals(sha, rec.sha256); assertEquals("llama.cpp v0.7.0", rec.runtime); assertEquals("answered OK", rec.selfTest)
        assertTrue(store.verifyFully(rec))
    }

    @Test fun failedSelfTestKeepsTheWorkingModel() {
        val store = ModelStore(File(dir, "models"))
        store.install(model("a"), staged(store, "a"), "rt", 2048) { ModelStore.SelfTestResult(true, "ok") }
        val r = store.install(model("b"), staged(store, "b"), "rt", 2048) { ModelStore.SelfTestResult(false, "no text") }
        assertFalse(r.passed)
        assertEquals("a", store.activeId())
        assertFalse(store.modelFile("b").exists())
        assertTrue(store.verifyFully(store.active()!!))
        assertEquals(payload.size.toLong(), store.partFile("b").length())   // kept for "Try again", never activated
        val again = dl.download("https://unused.invalid/x", payload.size.toLong(), sha, store.partFile("b"))
        assertTrue("$again", again is Downloader.Result.Done)               // re-verified without any request
        assertEquals(0, requests.get())
    }

    @Test fun failedUpdateOfTheSameModelLeavesTheOldFileIntact() {
        val store = ModelStore(File(dir, "models"))
        store.install(model("a", "v1"), staged(store, "a"), "rt", 2048) { ModelStore.SelfTestResult(true, "ok") }
        val crash = store.install(model("a", "v2"), staged(store, "a"), "rt", 2048) { throw IllegalStateException("model crashed") }
        assertFalse(crash.passed); assertEquals("model crashed", crash.detail)
        assertEquals("v1", store.active()!!.version)
        assertTrue(store.verifyFully(store.active()!!))
    }

    @Test fun corruptionIsDetectedAndRemovalFallsBack() {
        val store = ModelStore(File(dir, "models"))
        store.install(model("a"), staged(store, "a"), "rt", 2048) { ModelStore.SelfTestResult(true, "ok") }
        store.install(model("b"), staged(store, "b"), "rt", 2048) { ModelStore.SelfTestResult(true, "ok") }
        assertEquals("b", store.activeId())
        store.modelFile("b").appendBytes(byteArrayOf(1))
        assertFalse(store.quickCheck(store.active()!!))
        store.remove("b")
        assertEquals("a", store.activeId())
        store.remove("a")
        assertNull(store.activeId()); assertEquals(0, store.installed().size)
    }

    @Test fun damagedStateFileDoesNotCrash() {
        val store = ModelStore(File(dir, "models"))
        File(dir, "models").mkdirs(); File(dir, "models/state.json").writeText("{broken")
        assertNull(store.activeId()); assertEquals(emptyList<ModelStore.Installed>(), store.installed())
    }
}
