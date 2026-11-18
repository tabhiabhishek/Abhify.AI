package ai.abhify.android

import ai.abhify.android.chat.ChatDb
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.device.DeviceProbe
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.model.ModelSelector
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real on-phone end-to-end steps, run one at a time by android/tools/phone-e2e.ps1:
 *   -e class ai.abhify.android.OnDeviceE2ETest#<step> [-e model <id>]
 * Every step runs in a fresh app process. Results are written to files/e2e/<step>.json.
 * Only meaningful on a real phone; on an emulator these prove the code path, not speed.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceE2ETest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val g get() = ctx.graph
    private val modelId get() = args.getString("model") ?: (ModelSelector.recommend(g.catalog, DeviceProbe.read(ctx)) as ai.abhify.android.model.Compatibility.Ok).model.id

    private fun save(step: String, o: JSONObject) {
        val d = File(ctx.filesDir, "e2e").apply { mkdirs() }
        File(d, "$step.json").writeText(o.put("step", step).put("at", System.currentTimeMillis()).toString(2))
    }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun battery(): JSONObject {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("level", b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).put("temp_c", (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0)
            .put("thermal_status", DeviceProbe.read(ctx).thermalStatus)
    }

    private suspend fun ask(engine: LlmEngine, msgs: List<PromptBuilder.Msg>, maxTokens: Int = 160): Pair<String, LlmEngine.Stats> {
        val sb = StringBuilder(); var stats: LlmEngine.Stats? = null; var err: String? = null
        engine.generate(msgs, maxTokens, 0.3f).collect { e ->
            when (e) { is LlmEngine.Event.Token -> sb.append(e.text); is LlmEngine.Event.Finished -> stats = e.stats; is LlmEngine.Event.Failed -> err = e.message }
        }
        assertEquals(null, err)
        return sb.toString() to stats!!
    }

    @Test fun device() {
        val d = DeviceProbe.read(ctx)
        val backend = runBlocking { g.engine.backend() }
        save("device", JSONObject().put("manufacturer", d.manufacturer).put("model", d.model).put("soc", d.socModel)
            .put("android", d.release).put("sdk", d.sdkInt).put("abis", JSONArray(d.abis)).put("total_ram_mb", d.totalRamMb)
            .put("avail_ram_mb", d.availRamMb).put("low_memory", d.lowMemory).put("low_ram_device", d.isLowRamDevice)
            .put("free_storage_mb", d.freeStorageMb).put("cores", d.cpuCores).put("cpu", DeviceProbe.cpuSummary(d))
            .put("cpu_features", JSONArray(d.cpuFeatures.sorted())).put("vulkan", d.vulkanVersion)
            .put("ggml_system_info", backend.systemInfo)
            .put("ggml_devices", JSONArray(backend.devices.map { "${it.type}:${it.name}:${it.description}" }))
            .put("app_unsupported", ModelSelector.appUnsupportedReason(d))
            .put("compatibility", JSONArray(g.catalog.models.map { m -> ModelSelector.check(m, d).toString() }))
            .put("battery", battery()))
    }

    /** Real HTTPS download from the catalog source, SHA-256 check, real self-test, activation. */
    @Test fun setup() {
        assumeTrue("needs internet", online())
        val m = g.catalog.byId(modelId)!!
        val t0 = System.currentTimeMillis()
        g.setup.run(m)
        val s = g.setup.state.value
        assertTrue("$s", s is SetupController.State.Installed)
        val inst = g.store.active()!!
        assertEquals(m.id, inst.id); assertEquals(m.sha256, inst.sha256)
        save("setup", JSONObject().put("model", m.id).put("seconds", (System.currentTimeMillis() - t0) / 1000.0)
            .put("self_test", inst.selfTest).put("runtime", inst.runtime).put("bytes", g.store.modelFile(m.id).length()))
    }

    /** Pause mid-download, then resume: the part file is kept and the final file verifies. */
    @Test fun interruptedDownload() {
        assumeTrue("needs internet", online())
        val m = g.catalog.byId(args.getString("model") ?: "qwen2.5-0.5b-instruct-q4_k_m")!!
        val part = g.store.partFile(m.id); part.delete()
        val t = Thread { g.setup.run(m) }.apply { start() }
        while (part.length() < 50L * 1024 * 1024 && t.isAlive) Thread.sleep(200)
        g.setup.pause(); t.join()
        val paused = g.setup.state.value
        val kept = part.length()
        assertTrue("$paused", paused is SetupController.State.Paused)
        assertTrue(kept >= 50L * 1024 * 1024)
        g.setup.run(m)
        assertTrue("${g.setup.state.value}", g.setup.state.value is SetupController.State.Installed)
        save("interruptedDownload", JSONObject().put("model", m.id).put("paused_at_bytes", kept).put("resumed", true))
    }

    /** Must run with airplane mode ON (the harness turns it on and restarts the app process). */
    @Test fun offlineChatAndMemory() = runBlocking {
        assertFalse("airplane mode should be on for this step", online())
        val inst = g.store.active()
        assertNotNull("no model installed", inst)
        assertTrue(g.store.quickCheck(inst!!))
        val before = battery()
        val t0 = System.currentTimeMillis()
        assertEquals(null, g.engine.load(g.store.modelFile(inst.id), inst.context, g.threads(), 0))
        val loadMs = System.currentTimeMillis() - t0
        val db = g.db
        val conv = db.newConversation("offline e2e")
        val fact = PromptBuilder.memoryCommand("remember that my favourite framework is PyTorch")!!
        db.addMemory(fact)
        val (a1, s1) = ask(g.engine, PromptBuilder.build("In two sentences, what is gradient descent?", emptyList(), db.memories().map { it.content }, "Abhi"))
        db.addMessage(conv, "user", "In two sentences, what is gradient descent?"); db.addMessage(conv, "assistant", a1)
        val (a2, s2) = ask(g.engine, PromptBuilder.build("Which deep learning framework is my favourite? Answer in one short sentence.",
            emptyList(), db.memories().map { it.content }, "Abhi"), 60)
        db.addMessage(conv, "assistant", a2)
        assertTrue(a1, a1.length > 40)
        assertTrue(a2, a2.contains("PyTorch", ignoreCase = true))
        save("offlineChatAndMemory", JSONObject().put("online", online()).put("model", inst.id).put("load_ms", loadMs)
            .put("answer1", a1).put("answer1_first_token_ms", s1.firstTokenMs).put("answer1_tps", s1.tokensPerSecond)
            .put("answer1_tokens", s1.generatedTokens).put("answer2", a2).put("conversation", conv)
            .put("peak_rss_mb", g.engine.peakMemoryMb()).put("battery_before", before).put("battery_after", battery()))
    }

    /** Next process start, still offline: the chat and the memory are still there and used. */
    @Test fun persistenceAfterRestart() = runBlocking {
        assertFalse("airplane mode should be on for this step", online())
        val conv = ChatDb(ctx).conversations().firstOrNull { it.title == "offline e2e" }
        assertNotNull("conversation lost", conv)
        val msgs = g.db.messages(conv!!.id)
        assertEquals(3, msgs.size)
        val memories = g.db.memories().map { it.content }
        assertTrue(memories.any { it.contains("PyTorch") })
        val inst = g.store.active()!!
        assertEquals(null, g.engine.load(g.store.modelFile(inst.id), inst.context, g.threads(), 0))
        val (a, _) = ask(g.engine, PromptBuilder.build("What is my favourite framework?", msgs.map { PromptBuilder.Msg(it.role, it.content) }, memories, "Abhi"), 40)
        assertTrue(a, a.contains("PyTorch", ignoreCase = true))
        save("persistenceAfterRestart", JSONObject().put("online", online()).put("messages", msgs.size).put("memories", memories.size).put("answer", a))
    }

    /** Sustained load: 8 answers back to back; tokens/s per run shows throttling. */
    @Test fun sustained() = runBlocking {
        val inst = g.store.active()!!
        assertEquals(null, g.engine.load(g.store.modelFile(inst.id), inst.context, g.threads(), 0))
        val before = battery(); val t0 = System.currentTimeMillis()
        val runs = JSONArray()
        repeat(args.getString("runs")?.toInt() ?: 8) { i ->
            val (_, s) = ask(g.engine, listOf(PromptBuilder.Msg("user", "Write about 150 words on why validation sets matter. ($i)")), 200)
            runs.put(JSONObject().put("tps", s.tokensPerSecond).put("first_token_ms", s.firstTokenMs).put("tokens", s.generatedTokens)
                .put("battery", battery()))
        }
        save("sustained", JSONObject().put("model", inst.id).put("threads", g.threads()).put("seconds", (System.currentTimeMillis() - t0) / 1000.0)
            .put("runs", runs).put("battery_before", before).put("battery_after", battery()).put("peak_rss_mb", g.engine.peakMemoryMb()))
    }

    /** Removing the model frees the storage and leaves chats in place. */
    @Test fun removeModel() {
        val inst = g.store.active() ?: return
        val chatsBefore = g.db.conversations().size
        runBlocking { g.engine.unload() }
        g.store.remove(inst.id)
        assertFalse(g.store.modelFile(inst.id).exists())
        assertEquals(chatsBefore, g.db.conversations().size)
        save("removeModel", JSONObject().put("removed", inst.id).put("bytes_after", g.store.bytesUsed()).put("chats_kept", chatsBefore))
    }
}
