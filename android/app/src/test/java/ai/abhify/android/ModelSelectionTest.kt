package ai.abhify.android

import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.model.Compatibility
import ai.abhify.android.model.DeviceInfo
import ai.abhify.android.model.ModelCatalog
import ai.abhify.android.model.ModelSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ModelSelectionTest {
    private val json = File("src/main/assets/model_catalog.json").readText()
    private val catalog = ModelCatalog.parse(json)
    private val big = "qwen2.5-1.5b-instruct-q4_k_m"
    private val small = "qwen2.5-0.5b-instruct-q4_k_m"

    /** Device classes for the test matrix (RAM is what Android reports, below the marketed size). */
    private fun dev(totalMb: Int, availMb: Int = totalMb / 2, sdk: Int = 34, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
                    freeMb: Long = 50_000, lowRam: Boolean = false, low: Boolean = false, thermal: Int = 0) =
        DeviceInfo(sdk, "x", "m", "p", "soc", abis, totalMb, availMb, low, lowRam, freeMb, 8, setOf("asimd", "asimddp"), "1.3.0", thermal)

    @Test fun catalogIsValidAndPinned() {
        assertEquals(ModelCatalog.FORMAT, catalog.format)
        assertEquals("620d2225cc436ab0b001e3200e590e3b286ef9ab", catalog.runtime.commit)
        assertEquals(1117320736L, catalog.byId(big)!!.sizeBytes)
        assertEquals("6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", catalog.byId(big)!!.sha256)
        assertEquals(491400032L, catalog.byId(small)!!.sizeBytes)
        catalog.models.forEach { m ->
            assertTrue(m.url.startsWith("https://huggingface.co/Qwen/") && m.url.contains(m.version))   // pinned revision
            assertEquals("Apache-2.0", m.license)
            assertTrue("GPU must not be marked validated without a real-device run", !m.vulkanValidated)
        }
    }

    @Test fun catalogValidationRejectsUnsafeEntries() {
        for ((from, to) in listOf("\"https://huggingface.co/Qwen/Qwen2.5-0.5B" to "\"http://huggingface.co/Qwen/Qwen2.5-0.5B",
            "74a4da8c9fdbcd15" to "XYZ", "\"format\": \"gguf\",\n      \"quantization\": \"Q4_K_M\",\n      \"sizeBytes\": 491400032" to
                "\"format\": \"onnx\",\n      \"quantization\": \"Q4_K_M\",\n      \"sizeBytes\": 491400032")) {
            check(json.contains(from)) { from }
            try { ModelCatalog.parse(json.replaceFirst(from, to)); fail("accepted: $to") } catch (e: Exception) { }
        }
    }

    @Test fun modernPhone8GbGetsTheBiggerModel() {           // e.g. the owner's 8 GB phone reports ~7.4 GB
        val r = ModelSelector.recommend(catalog, dev(7400, 3500)) as Compatibility.Ok
        assertEquals(big, r.model.id); assertTrue(r.warnings.isEmpty())
    }

    @Test fun sixGbPhoneGetsBigger_fourGbGetsSmaller_threeGbSmaller() {
        assertEquals(big, (ModelSelector.recommend(catalog, dev(5600, 2500)) as Compatibility.Ok).model.id)
        assertEquals(small, (ModelSelector.recommend(catalog, dev(3700, 1600)) as Compatibility.Ok).model.id)
        assertEquals(small, (ModelSelector.recommend(catalog, dev(2800, 1300)) as Compatibility.Ok).model.id)
    }

    @Test fun twoGbPhoneAndAndroidGoGetNothing() {
        assertNull(ModelSelector.recommend(catalog, dev(1900)))
        val go = ModelSelector.check(catalog.byId(small)!!, dev(2800, lowRam = true))
        assertTrue(go is Compatibility.Ok)                    // 0.5B fits under 1 GB runtime
        val goBig = ModelSelector.check(catalog.byId(big)!!, dev(7400, lowRam = true))
        assertTrue(goBig is Compatibility.No)
    }

    @Test fun lowStorageBlocksTheDownloadButNotAnInstalledModel() {
        val m = catalog.byId(big)!!
        val c = ModelSelector.check(m, dev(7400, freeMb = 900)) as Compatibility.No
        assertTrue(c.reasons.single().contains("Not enough free storage"))
        assertTrue(ModelSelector.check(m, dev(7400, freeMb = 900), installed = true) is Compatibility.Ok)
        assertEquals(small, (ModelSelector.recommend(catalog, dev(7400, freeMb = 900)) as Compatibility.Ok).model.id)
    }

    @Test fun memoryPressureAndHeatAreWarningsNotCrashes() {
        val c = ModelSelector.check(catalog.byId(big)!!, dev(7400, availMb = 800, low = true, thermal = 4)) as Compatibility.Ok
        assertEquals(3, c.warnings.size)
        assertTrue(ModelSelector.loadBlocker(catalog.byId(big)!!, 800)!!.contains("Close other apps"))
        assertNull(ModelSelector.loadBlocker(catalog.byId(big)!!, 4000))
    }

    @Test fun appLevelSupport() {
        assertNull(ModelSelector.appUnsupportedReason(dev(7400)))
        assertNull(ModelSelector.appUnsupportedReason(dev(7400, sdk = 29)))                       // Android 10 minimum
        assertTrue(ModelSelector.appUnsupportedReason(dev(7400, sdk = 28))!!.contains("Android 10"))
        assertTrue(ModelSelector.appUnsupportedReason(dev(3000, abis = listOf("armeabi-v7a", "armeabi")))!!.contains("32-bit"))
        assertNull(ModelSelector.appUnsupportedReason(dev(4000, abis = listOf("x86_64"))))         // emulator
    }

    @Test fun threadsUseBigCoresOnly() {
        assertEquals(4, ModelSelector.threads(8)); assertEquals(2, ModelSelector.threads(4)); assertEquals(1, ModelSelector.threads(2))
    }

    // ---------------------------------------------------------------- prompts (same behaviour as the web app's on-device mode)
    @Test fun promptHasMemoriesNameAndBoundedHistory() {
        val history = (0 until 12).map { PromptBuilder.Msg(if (it % 2 == 0) "user" else "assistant", "m$it") }
        val msgs = PromptBuilder.build("What is overfitting?", history, listOf("I am learning PyTorch"), "Abhi")
        assertEquals("system", msgs[0].role)
        assertTrue(msgs[0].content.contains("Abhi") && msgs[0].content.contains("I am learning PyTorch") && msgs[0].content.contains("no internet access"))
        assertEquals(1 + 8 + 1, msgs.size)
        assertEquals((4 until 12).map { "m$it" }, msgs.subList(1, 9).map { it.content })
        assertEquals(PromptBuilder.Msg("user", "What is overfitting?"), msgs.last())
    }

    @Test fun memoryCommand() {
        assertEquals("I prefer short answers", PromptBuilder.memoryCommand("remember that I prefer short answers."))
        assertEquals("my exam is on Friday", PromptBuilder.memoryCommand("Remember: my exam is on Friday"))
        assertEquals("I like PyTorch", PromptBuilder.memoryCommand("please remember I like PyTorch"))
        assertNull(PromptBuilder.memoryCommand("do you remember what I said?"))
        assertNull(PromptBuilder.memoryCommand("remember"))
        assertNull(PromptBuilder.memoryCommand("what is a tensor?"))
    }
}
