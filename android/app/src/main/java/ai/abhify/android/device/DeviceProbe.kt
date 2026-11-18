package ai.abhify.android.device

import ai.abhify.android.model.DeviceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/** Reads the phone's real capabilities. Nothing here is guessed from the model name or Android version. */
object DeviceProbe {
    fun read(context: Context): DeviceInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)
        val features = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
        }.getOrNull() ?: emptySet()
        val pm = context.packageManager
        val vulkan = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
            ?.let { v -> "${v shr 22}.${(v shr 12) and 0x3ff}.${v and 0xfff}" }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
        return DeviceInfo(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socModel = soc,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = (mi.totalMem / (1024 * 1024)).toInt(),
            availRamMb = (mi.availMem / (1024 * 1024)).toInt(),
            lowMemory = mi.lowMemory,
            isLowRamDevice = am.isLowRamDevice,
            freeStorageMb = stat.availableBytes / (1024 * 1024),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuFeatures = features,
            vulkanVersion = vulkan,
            thermalStatus = context.getSystemService(PowerManager::class.java).currentThermalStatus,
        )
    }

    /** Human-readable CPU acceleration summary (what llama.cpp's CPU variants can use). */
    fun cpuSummary(d: DeviceInfo): String {
        val f = d.cpuFeatures
        val parts = listOfNotNull(
            "NEON".takeIf { "asimd" in f },
            "dot-product".takeIf { "asimddp" in f },
            "int8 matmul".takeIf { "i8mm" in f },
            "FP16".takeIf { "fphp" in f || "asimdhp" in f },
            "SVE2".takeIf { "sve2" in f },
            "SME".takeIf { "sme" in f },
        )
        return "${d.cpuCores} cores" + if (parts.isEmpty()) "" else " · " + parts.joinToString(", ")
    }
}
