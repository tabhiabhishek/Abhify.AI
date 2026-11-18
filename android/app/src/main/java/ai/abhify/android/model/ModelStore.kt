package ai.abhify.android.model

import org.json.JSONObject
import java.io.File

/**
 * Model files in app-private storage and the record of what is installed:
 *   <root>/staging/<id>.part        downloads in progress (resumable)
 *   <root>/installed/<id>/model.gguf verified models
 *   <root>/state.json               active model + per-model record (version, sha256, runtime, settings, self-test)
 *
 * A new model becomes active only after a real inference test passes; until then the previous one
 * stays active and untouched. A failed test removes only the new files.
 */
class ModelStore(private val root: File) {
    data class Installed(
        val id: String, val version: String, val sha256: String, val sizeBytes: Long,
        val runtime: String, val context: Int, val installedAt: Long, val selfTest: String,
    )

    data class SelfTestResult(val passed: Boolean, val detail: String)

    private val stateFile get() = File(root, "state.json")
    fun partFile(id: String) = File(root, "staging/$id.part")
    fun modelFile(id: String) = File(root, "installed/$id/model.gguf")

    @Synchronized private fun read(): JSONObject =
        if (stateFile.exists()) runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() } else JSONObject()

    @Synchronized private fun write(o: JSONObject) {
        root.mkdirs()
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }   // atomic on the same filesystem
    }

    fun activeId(): String? = read().optString("active").ifEmpty { null }

    fun installed(): List<Installed> {
        val models = read().optJSONObject("models") ?: return emptyList()
        return models.keys().asSequence().mapNotNull { id ->
            val m = models.getJSONObject(id)
            if (!modelFile(id).exists()) null else Installed(id, m.getString("version"), m.getString("sha256"),
                m.getLong("sizeBytes"), m.getString("runtime"), m.getInt("context"), m.getLong("installedAt"), m.optString("selfTest"))
        }.toList()
    }

    fun active(): Installed? = activeId()?.let { id -> installed().firstOrNull { it.id == id } }

    /**
     * Moves a verified download into place, runs [selfTest] on it and activates it only if it passes.
     * The previously active model is kept in every case.
     */
    fun install(m: CatalogModel, verifiedPart: File, runtime: String, context: Int,
                selfTest: (File) -> SelfTestResult): SelfTestResult {
        val dest = modelFile(m.id)
        dest.parentFile!!.mkdirs()
        val tmp = File(dest.parentFile, "model.gguf.new")
        tmp.delete()
        if (!verifiedPart.renameTo(tmp)) { verifiedPart.copyTo(tmp, overwrite = true); verifiedPart.delete() }
        val result = runCatching { selfTest(tmp) }.getOrElse { SelfTestResult(false, it.message ?: "self-test crashed") }
        if (!result.passed) {
            // Not activated. The verified file goes back to staging so "Try again" re-checks it instead of re-downloading.
            if (!tmp.renameTo(verifiedPart)) tmp.delete()
            return result
        }
        dest.delete()
        check(tmp.renameTo(dest)) { "could not move the model into place" }
        val o = read()
        val models = o.optJSONObject("models") ?: JSONObject().also { o.put("models", it) }
        models.put(m.id, JSONObject().put("version", m.version).put("sha256", m.sha256).put("sizeBytes", m.sizeBytes)
            .put("runtime", runtime).put("context", context).put("installedAt", System.currentTimeMillis()).put("selfTest", result.detail))
        o.put("active", m.id)
        write(o)
        return result
    }

    fun setActive(id: String) { require(modelFile(id).exists()) { "not installed" }; write(read().put("active", id)) }

    fun setContext(id: String, context: Int) {
        val o = read(); o.optJSONObject("models")?.optJSONObject(id)?.put("context", context); write(o)
    }

    /** Removes one model (and its partial download). If it was active, another installed model (if any) becomes active. */
    fun remove(id: String) {
        File(root, "installed/$id").deleteRecursively()
        partFile(id).delete()
        val o = read()
        o.optJSONObject("models")?.remove(id)
        if (o.optString("active") == id) {
            val next = o.optJSONObject("models")?.keys()?.asSequence()?.firstOrNull { modelFile(it).exists() }
            if (next != null) o.put("active", next) else o.remove("active")
        }
        write(o)
    }

    /** Quick check at every start: the file exists with the recorded size. Full SHA-256 check: [verifyFully]. */
    fun quickCheck(i: Installed): Boolean = modelFile(i.id).length() == i.sizeBytes
    fun verifyFully(i: Installed): Boolean = quickCheck(i) && Downloader.sha256(modelFile(i.id)) == i.sha256

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
