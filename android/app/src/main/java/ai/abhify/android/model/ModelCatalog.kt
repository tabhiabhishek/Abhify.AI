package ai.abhify.android.model

import org.json.JSONObject

/** One downloadable model. Every field comes from the catalog (assets/model_catalog.json), never from code. */
data class CatalogModel(
    val id: String,
    val name: String,
    val parameters: String,
    val version: String,            // upstream revision (Hugging Face commit) the URL and hash are pinned to
    val url: String,
    val source: String,
    val license: String,
    val licenseUrl: String,
    val redistribution: String,
    val format: String,
    val quantization: String,
    val sizeBytes: Long,
    val sha256: String,
    val minTotalRamMb: Int,         // below this total RAM the model is not offered
    val estimatedRuntimeMb: Int,    // weights + KV cache + compute buffers at the default context
    val contextOptions: List<Int>,
    val defaultContext: Int,
    val minSdk: Int,
    val abis: List<String>,
    val vulkanSupported: Boolean,   // the runtime has a Vulkan path for it
    val vulkanValidated: Boolean,   // a real device run proved it (never assumed)
    val notes: String,
) {
    val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
}

data class RuntimeInfo(val name: String, val version: String, val commit: String)

data class ModelCatalog(val format: String, val runtime: RuntimeInfo, val models: List<CatalogModel>) {
    fun byId(id: String) = models.firstOrNull { it.id == id }

    companion object {
        const val FORMAT = "abhify-model-catalog-1"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Parses and validates a catalog; throws IllegalArgumentException naming the first problem. */
        fun parse(json: String): ModelCatalog {
            val o = JSONObject(json)
            require(o.getString("format") == FORMAT) { "unknown catalog format" }
            val rt = o.getJSONObject("runtime")
            val arr = o.getJSONArray("models")
            val models = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val accel = m.optJSONObject("acceleration")?.optJSONObject("vulkan")
                val ctx = m.getJSONArray("contextOptions").let { a -> (0 until a.length()).map { a.getInt(it) } }
                CatalogModel(
                    id = m.getString("id"), name = m.getString("name"), parameters = m.getString("parameters"),
                    version = m.getString("version"), url = m.getString("url"), source = m.getString("source"),
                    license = m.getString("license"), licenseUrl = m.getString("licenseUrl"),
                    redistribution = m.getString("redistribution"), format = m.getString("format"),
                    quantization = m.getString("quantization"), sizeBytes = m.getLong("sizeBytes"),
                    sha256 = m.getString("sha256").lowercase(), minTotalRamMb = m.getInt("minTotalRamMb"),
                    estimatedRuntimeMb = m.getInt("estimatedRuntimeMb"), contextOptions = ctx,
                    defaultContext = m.getInt("defaultContext"), minSdk = m.getInt("minSdk"),
                    abis = m.getJSONArray("abis").let { a -> (0 until a.length()).map { a.getString(it) } },
                    vulkanSupported = accel?.optBoolean("supported") ?: false,
                    vulkanValidated = accel?.optBoolean("validated") ?: false,
                    notes = m.optString("notes"),
                ).also { c ->
                    require(c.url.startsWith("https://")) { "${c.id}: download URL must be HTTPS" }
                    require(SHA256.matches(c.sha256)) { "${c.id}: sha256 must be 64 hex characters" }
                    require(c.sizeBytes > 0) { "${c.id}: sizeBytes must be positive" }
                    require(c.format == "gguf") { "${c.id}: only GGUF models are supported by this runtime" }
                    require(c.defaultContext in c.contextOptions) { "${c.id}: defaultContext must be one of contextOptions" }
                    require(c.estimatedRuntimeMb > c.sizeMb) { "${c.id}: runtime estimate must exceed the file size" }
                    require(!c.vulkanValidated || c.vulkanSupported) { "${c.id}: validated GPU needs supported GPU" }
                }
            }
            require(models.map { it.id }.toSet().size == models.size) { "duplicate model ids" }
            return ModelCatalog(o.getString("format"),
                RuntimeInfo(rt.getString("name"), rt.getString("version"), rt.getString("commit")), models)
        }
    }
}
