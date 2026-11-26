package ai.abhify.android

import ai.abhify.android.device.MemorySnapshot
import ai.abhify.android.device.SetupPolicy
import ai.abhify.android.model.Compatibility
import ai.abhify.android.model.DeviceInfo
import ai.abhify.android.model.ModelCatalog
import ai.abhify.android.model.ModelSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 7.0.2 hardware-aware model setup on the phone: a stable recommendation (not the largest that fits), battery and heat,
 *  the execution mode shown honestly (GPU only after a per-model test), and a device report of measured facts only. */
class HardwareAwareSetupTest {
    private val catalog = ModelCatalog.parse(File("src/main/assets/model_catalog.json").readText())
    private val big = "qwen2.5-1.5b-instruct-q4_k_m"
    private val small = "qwen2.5-0.5b-instruct-q4_k_m"
    private fun dev(totalMb: Int, availMb: Int = totalMb / 2, thermal: Int = 0, battery: Int? = null, charging: Boolean? = null, vulkan: String? = "1.3.0") =
        DeviceInfo(34, "14", "Xiaomi", "Redmi K50i", "MediaTek MT6895", listOf("arm64-v8a"), totalMb, availMb, false, false, 50_000, 8,
            setOf("asimd", "asimddp"), vulkan, thermal, battery, charging)

    @Test fun eightGbPhoneGetsTheBiggerModelWhenItIsComfortable() {
        val r = ModelSelector.recommend(catalog, dev(7400, 3500)) as Compatibility.Ok
        assertEquals(big, r.model.id)
        assertTrue(ModelSelector.comfortable(r.model, dev(7400, 3500)))
    }

    @Test fun aBusyOrHotPhoneGetsTheStableSmallerModelNotTheLargestThatFits() {
        assertEquals(small, (ModelSelector.recommend(catalog, dev(7400, availMb = 1500)) as Compatibility.Ok).model.id)   // little free now
        assertEquals(small, (ModelSelector.recommend(catalog, dev(7400, 3500, thermal = 3)) as Compatibility.Ok).model.id) // severe heat
        // the big model is still COMPATIBLE (the user may choose it), just not recommended
        assertTrue(ModelSelector.check(catalog.byId(big)!!, dev(7400, availMb = 1500)) is Compatibility.Ok)
    }

    @Test fun lowBatteryIsAWarningAndChargingClearsIt() {
        val low = ModelSelector.check(catalog.byId(small)!!, dev(7400, 3500, battery = 12, charging = false)) as Compatibility.Ok
        assertTrue(low.warnings.any { it.contains("Battery is at 12 %") })
        val ok = ModelSelector.check(catalog.byId(small)!!, dev(7400, 3500, battery = 12, charging = true)) as Compatibility.Ok
        assertTrue(ok.warnings.none { it.contains("Battery") })
    }

    @Test fun executionModeIsCpuUntilTheGpuIsValidatedForTheModel() {
        assertTrue(ModelSelector.executionMode(dev(7400), gpuValidated = false).startsWith("CPU (4 threads)"))
        assertTrue(ModelSelector.executionMode(dev(7400), gpuValidated = false).contains("Vulkan 1.3.0"))
        assertTrue(ModelSelector.executionMode(dev(7400, vulkan = null), gpuValidated = false).endsWith("no Vulkan reported"))
        assertTrue(ModelSelector.executionMode(dev(7400), gpuValidated = true).startsWith("GPU (Vulkan 1.3.0)"))
    }

    @Test fun deviceReportShowsMeasuredFactsAndTheRecommendationDetails() {
        val d = dev(7400, 3500, battery = 64, charging = true)
        val mem = MemorySnapshot(7400, 3500, 300, false, false, 256, 512)
        val lines = SetupPolicy.deviceReport(d.release, d.sdkInt, d.totalRamMb, mem, d.freeStorageMb, d.abis, ModelSelector.recommend(catalog, d),
            null, true, true, device = d, executionMode = ModelSelector.executionMode(d, false)).associateBy { it.label }
        assertEquals("Xiaomi Redmi K50i", lines.getValue("Phone").value)
        assertEquals("MediaTek MT6895", lines.getValue("Chip (SoC)").value)
        assertTrue(lines.getValue("CPU").value.contains("dot-product"))
        assertEquals("Vulkan 1.3.0 reported", lines.getValue("Graphics (GPU)").value)
        assertEquals("64 % · charging", lines.getValue("Battery").value)
        assertTrue(lines.getValue("AI would run on").value.startsWith("CPU"))
        assertTrue(lines.getValue("Recommended model").value.contains("Q4_K_M") && lines.getValue("Recommended model").value.contains("1,450 MB"))
        // installation still needs approval, a verified download and a real self-test before the model is active (unchanged flow)
        val graph = File("src/main/java/ai/abhify/android/AppGraph.kt").readText()
        assertTrue(graph.contains("g.store.install(m, r.file, g.runtimeLabel, m.defaultContext) { file -> selfTest(file) }"))
    }
}
