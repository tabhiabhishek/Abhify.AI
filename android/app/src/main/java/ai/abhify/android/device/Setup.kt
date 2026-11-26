package ai.abhify.android.device

import ai.abhify.android.ai.AiMode
import ai.abhify.android.model.Compatibility

/**
 * First-run setup for every new install. Nothing in it is tied to one phone: each install chooses its own AI mode,
 * model, providers, keys, OCR and privacy, and an upgrade never re-runs it (the "onboarded" flag survives updates).
 */
object SetupPolicy {
    enum class Step(val title: String) {
        DEVICE("This phone"), AI_MODE("Where answers come from"), LOCAL("AI on this phone"), ONLINE("Online AI with your own key"),
        OCR("Reading scans and photos"), DRIVE("Google Drive (optional)"), PRIVACY("Privacy and memory"), FINISH("Ready"),
    }

    /** Fresh install (or a setup that never finished) → setup; an upgrade or a finished setup → straight to the app. */
    fun showSetup(onboarded: Boolean) = !onboarded

    /** Steps for the chosen mode: local-only skips providers, online-only skips the model download (both stay in Settings). */
    fun steps(mode: AiMode): List<Step> = Step.entries.filter { s ->
        when (s) { Step.LOCAL -> mode != AiMode.ONLINE; Step.ONLINE -> mode != AiMode.DEVICE; else -> true }
    }

    data class Line(val label: String, val value: String, val ok: Boolean?)

    /** What the device check shows. Only facts read from this phone; a model is called suitable only after the compatibility check. */
    fun deviceReport(release: String, sdk: Int, totalMb: Int, mem: MemorySnapshot, freeStorageMb: Long, abis: List<String>,
                     recommendation: Compatibility?, installedModel: String?, online: Boolean, ocrBundled: Boolean,
                     device: ai.abhify.android.model.DeviceInfo? = null, executionMode: String? = null): List<Line> = listOfNotNull(
        device?.let { Line("Phone", "${it.manufacturer} ${it.model}".trim(), null) },
        device?.let { Line("Chip (SoC)", it.socModel.ifBlank { "not reported" }, null) },
        device?.let { Line("CPU", DeviceProbe.cpuSummary(it), null) },
        device?.let { Line("Graphics (GPU)", it.vulkanVersion?.let { v -> "Vulkan $v reported" } ?: "no Vulkan reported", null) },
        device?.batteryPercent?.let { b -> Line("Battery", "$b %" + if (device.charging == true) " · charging" else "", b >= 20 || device.charging == true) },
        executionMode?.let { Line("AI would run on", it, null) },
        Line("Android", "$release (API $sdk)", sdk >= 29),
        Line("Memory", "%.1f GB total · %,d MB free now".format(totalMb / 1024.0, mem.availMb), !mem.lowMemory && !mem.lowRamDevice),
        Line("Storage free", "%,d MB".format(freeStorageMb), freeStorageMb > 1500),
        Line("Processor", abis.firstOrNull() ?: "unknown", abis.any { it == "arm64-v8a" || it == "x86_64" }),
        Line("AI model on this phone", installedModel ?: "none yet", if (installedModel != null) true else null),
        Line("Recommended model", when (recommendation) {
            is Compatibility.Ok -> recommendation.model.name + " (" + recommendation.model.quantization + ", " +
                "%.1f GB download, about %,d MB of memory while running".format(recommendation.model.sizeBytes / 1e9, recommendation.model.estimatedRuntimeMb) + ")"
            is Compatibility.No -> "none fits: " + recommendation.reasons.firstOrNull().orEmpty()
            null -> "none fits this phone"
        }, recommendation is Compatibility.Ok),
        Line("Text recognition (OCR)", if (ocrBundled) "ML Kit, included (no download)" else "not available", ocrBundled),
        Line("Internet", if (online) "connected" else "offline (online AI and model downloads need it)", if (online) true else null),
        Line("Processing profile", MemoryPolicy.pick(mem, null, false).first.label + " (Auto)", true),
    )

    /** Setup can always finish (everything is optional); this says what will and won't work yet. */
    fun readiness(mode: AiMode, modelInstalled: Boolean, providerReady: Boolean, onlineAllowed: Boolean): String = when {
        mode == AiMode.DEVICE && modelInstalled -> "Ready: answers come from the AI on this phone, offline."
        mode == AiMode.DEVICE -> "Not ready yet: download a model in AI models (Settings) to chat. Documents, notes and other features work already."
        !onlineAllowed && !modelInstalled -> "Not ready yet: online AI is off and no model is downloaded. Change either in Settings."
        mode == AiMode.ONLINE && providerReady && onlineAllowed -> "Ready: answers come from your selected provider with this phone's own key."
        mode == AiMode.ONLINE -> "Not ready yet: add and select a provider key in AI providers (Settings)."
        providerReady && onlineAllowed && modelInstalled -> "Ready: your provider when online, this phone's AI when allowed as fallback."
        providerReady && onlineAllowed -> "Ready online. Offline answers need a model download (AI models)."
        modelInstalled -> "Ready on this phone. Add a provider key any time for online answers."
        else -> "Not ready yet: download a model or add a provider key in Settings."
    }
}
