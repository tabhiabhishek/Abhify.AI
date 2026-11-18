package ai.abhify.android.model

/** What the app knows about this phone, read at run time (DeviceProbe), never assumed from the model name. */
data class DeviceInfo(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    val socModel: String,
    val abis: List<String>,
    val totalRamMb: Int,
    val availRamMb: Int,
    val lowMemory: Boolean,         // the system says memory is low right now
    val isLowRamDevice: Boolean,    // Android Go / "low RAM" class device
    val freeStorageMb: Long,
    val cpuCores: Int,
    val cpuFeatures: Set<String>,   // from /proc/cpuinfo: asimddp (dot product), i8mm, sve, sve2, fphp ...
    val vulkanVersion: String?,     // from PackageManager features, null if none
    val thermalStatus: Int,         // PowerManager.THERMAL_STATUS_* (0 = none)
)

sealed class Compatibility {
    data class Ok(val model: CatalogModel, val warnings: List<String>) : Compatibility()
    data class No(val model: CatalogModel, val reasons: List<String>) : Compatibility()
}

/** Pure decision logic, unit-tested. Android OS support (can the app run) is separate from model support (can this model run). */
object ModelSelector {
    const val PRIMARY_ABI = "arm64-v8a"
    private const val STORAGE_HEADROOM_MB = 300L     // keep the phone usable after the download
    private const val OS_MEMORY_MARGIN_MB = 600      // what Android and other apps need on top of the model

    /** Can the app itself work on this phone (independent of any model)? null = yes, else the reason. */
    fun appUnsupportedReason(d: DeviceInfo): String? = when {
        d.sdkInt < 29 -> "Abhify needs Android 10 or newer (this phone has Android ${d.release})."
        d.abis.none { it == PRIMARY_ABI || it == "x86_64" } ->
            "This phone's processor (${d.abis.joinToString()}) is 32-bit only; the on-device AI needs a 64-bit (arm64) processor."
        else -> null
    }

    fun check(m: CatalogModel, d: DeviceInfo, installed: Boolean = false): Compatibility {
        val no = mutableListOf<String>()
        val warn = mutableListOf<String>()
        if (d.sdkInt < m.minSdk) no += "${m.name} needs Android API ${m.minSdk}+."
        if (d.abis.none { it in m.abis }) no += "${m.name} has no build for ${d.abis.joinToString()}."
        if (d.totalRamMb < m.minTotalRamMb)
            no += "${m.name} needs at least ${gb(m.minTotalRamMb)} of RAM; this phone has ${gb(d.totalRamMb)}."
        if (!installed && d.freeStorageMb < m.sizeMb + STORAGE_HEADROOM_MB)
            no += "Not enough free storage: ${m.name} needs ${m.sizeMb + STORAGE_HEADROOM_MB} MB, ${d.freeStorageMb} MB free."
        if (d.isLowRamDevice && m.estimatedRuntimeMb > 1024) no += "${m.name} is too large for a low-RAM (Android Go) phone."
        if (no.isEmpty()) {
            if (d.availRamMb < m.estimatedRuntimeMb + OS_MEMORY_MARGIN_MB)
                warn += "Only ${d.availRamMb} MB of memory is free right now; close other apps before starting the AI."
            if (d.lowMemory) warn += "Android reports low memory right now."
            if (d.thermalStatus >= 3) warn += "The phone is hot; answers will be slow until it cools down."
        }
        return if (no.isEmpty()) Compatibility.Ok(m, warn) else Compatibility.No(m, no)
    }

    /** The largest compatible model is recommended; the user can still pick any other compatible one. */
    fun recommend(catalog: ModelCatalog, d: DeviceInfo): Compatibility? =
        catalog.models.map { check(it, d) }.filterIsInstance<Compatibility.Ok>().maxByOrNull { it.model.minTotalRamMb }

    /** Memory check right before loading (state changes after the download). null = fine. */
    fun loadBlocker(m: CatalogModel, availRamMb: Int): String? =
        if (availRamMb < m.estimatedRuntimeMb + OS_MEMORY_MARGIN_MB / 2)
            "Not enough free memory to start ${m.name} (${availRamMb} MB free, about ${m.estimatedRuntimeMb + OS_MEMORY_MARGIN_MB / 2} MB needed). Close other apps and try again."
        else null

    /** Threads: the big cores only. Using every core (incl. little cores) is usually slower on phones. */
    fun threads(cpuCores: Int): Int = when {
        cpuCores >= 8 -> 4
        cpuCores >= 6 -> 4
        cpuCores >= 4 -> 2
        else -> 1
    }

    private fun gb(mb: Int) = "%.1f GB".format(mb / 1024.0)
}
