package ai.abhify.android.model

/**
 * 4.6.2: GPU use is validated per model. "Test GPU" checks the model that is active at the time (same answer on CPU and
 * GPU, and faster); before 4.6.2 the result was stored as one phone-wide switch, so a model that was never checked (for
 * example Qwen2.5 0.5B after the 1.5B passed) also ran on the GPU, while its install self-test had run on the CPU.
 * Now only the model that passed uses the GPU; every other model stays on the CPU path it was tested on.
 */
object GpuPolicy {
    const val LEGACY_KEY = "gpu_enabled"
    const val KEY = "gpu_model"
    /** The 4.x catalog's default model: the one "Test GPU" ran on before models could be switched. */
    const val LEGACY_MODEL = "qwen2.5-1.5b-instruct-q4_k_m"

    fun usesGpu(validatedModel: String?, activeModel: String?): Boolean = validatedModel != null && validatedModel == activeModel

    /** Upgrading from the phone-wide switch: keep GPU only for the model that switch was set with (the 1.5B), if installed. */
    fun migrateLegacy(legacyOn: Boolean, installed: Set<String>): String? = if (legacyOn && LEGACY_MODEL in installed) LEGACY_MODEL else null
}
