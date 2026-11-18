package ai.abhify.android.training

import ai.abhify.android.memory.MemoryKit
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * v4.1 research infrastructure for an OPT-IN fine-tuning dataset. Pure functions only: nothing here reads chats,
 * memories or documents by itself, writes files, uploads, or trains. A future workflow would be: the user selects
 * examples → previews them → [validate] filters sensitive and unsuitable ones → the user reviews and approves each →
 * [jsonl] + [manifest] produce a versioned export → training happens elsewhere, only with explicit approval.
 */
object DatasetPolicy {
    const val POLICY_VERSION = 1

    /** One instruction/response pair the user picked. [approved] must be set by the user's own review, never by code. */
    data class Example(val id: String, val instruction: String, val response: String, val sourceKind: String, val sourceRef: String?, val approved: Boolean)
    data class Rejected(val example: Example, val reasons: List<String>)
    data class Report(val accepted: List<Example>, val rejected: List<Rejected>) { val summary get() = "${accepted.size} accepted, ${rejected.size} rejected" }

    private val SECRETS = listOf(
        Regex("sk-[A-Za-z0-9_-]{20,}") to "API key", Regex("AIza[0-9A-Za-z_-]{35}") to "Google API key", Regex("ya29\\.[0-9A-Za-z_-]{20,}") to "OAuth token",
        Regex("gsk_[A-Za-z0-9]{20,}") to "API key", Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}") to "access token",
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----") to "private key", Regex("(?i)\\b(api[_-]?key|secret|token)\\s*[:=]\\s*\\S{12,}") to "credential")

    /** Kinds of secrets in [text] (always rejected, even if the user approved the example). */
    fun secrets(text: String): List<String> = SECRETS.filter { (re, _) -> re.containsMatchIn(text) }.map { it.second }.distinct()

    /**
     * Keeps only examples that are user-approved, not sensitive, not secrets, of a useful length and not duplicates.
     * Sensitive personal data (email, phone, card, IDs, passwords, health/financial) is rejected: a model must not
     * memorise it. The caller shows the reasons so the user can fix or drop each example.
     */
    fun validate(examples: List<Example>, minChars: Int = 10, maxChars: Int = 8_000): Report {
        val seen = HashSet<String>(); val ok = mutableListOf<Example>(); val bad = mutableListOf<Rejected>()
        for (e in examples) {
            val text = e.instruction + "\n" + e.response
            val reasons = buildList {
                if (!e.approved) add("not approved by you")
                addAll(secrets(text).map { "contains a $it" })
                addAll(MemoryKit.sensitive(text).map { "looks like a $it" })
                if (e.instruction.trim().length < minChars || e.response.trim().length < minChars) add("too short")
                if (text.length > maxChars) add("too long")
                if (e.response.startsWith("⚠️")) add("an error message, not an answer")
                val key = (e.instruction.trim().lowercase() + "\u0000" + e.response.trim().lowercase())
                if (!seen.add(key)) add("duplicate")
            }
            if (reasons.isEmpty()) ok += e else bad += Rejected(e, reasons)
        }
        return Report(ok, bad)
    }

    /** Chat-format JSONL (one {"messages":[…]} per line), the common input of LoRA/QLoRA tools. */
    fun jsonl(examples: List<Example>, system: String? = null): String = examples.joinToString("\n") { e ->
        JSONObject().put("messages", JSONArray().apply {
            system?.let { put(JSONObject().put("role", "system").put("content", it)) }
            put(JSONObject().put("role", "user").put("content", e.instruction.trim()))
            put(JSONObject().put("role", "assistant").put("content", e.response.trim()))
        }).toString()
    }

    /** Version record of an export: what, how many, from which kinds of sources, the checksum, and the base model it targets. */
    fun manifest(version: Int, data: String, report: Report, baseModel: String, baseModelSha256: String?, createdAt: Long): JSONObject =
        JSONObject().put("format", "abhify-dataset").put("policy_version", POLICY_VERSION).put("dataset_version", version).put("created_at", createdAt)
            .put("examples", report.accepted.size).put("rejected", report.rejected.size)
            .put("sources", JSONObject(report.accepted.groupingBy { it.sourceKind }.eachCount() as Map<*, *>))
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) })
            .put("base_model", baseModel).put("base_model_sha256", baseModelSha256 ?: JSONObject.NULL)
            .put("note", "Exported by the user for optional research. Not used for training by Abhify itself.")

    // ------------------------------------------------------------------ gates
    /** Training is never started by the app (not on the phone, not in the background, not automatically). */
    const val APP_MAY_TRAIN = false

    data class EvalResult(val taskAccuracy: Double, val instructionFollowing: Double, val hallucinationRate: Double, val tokensPerSecond: Double, val sizeBytes: Long)

    /**
     * Whether a fine-tuned model may even be OFFERED for activation: the user approved, and it is no worse than the
     * current model beyond the tolerances (quality never drops more than 2 points; hallucination never rises more than
     * 1 point; speed ≥ 80%; size ≤ 120%). The original model is always kept for rollback.
     */
    fun mayOffer(candidate: EvalResult, baseline: EvalResult, userApproved: Boolean): Boolean = userApproved &&
        candidate.taskAccuracy >= baseline.taskAccuracy - 0.02 && candidate.instructionFollowing >= baseline.instructionFollowing - 0.02 &&
        candidate.hallucinationRate <= baseline.hallucinationRate + 0.01 && candidate.tokensPerSecond >= baseline.tokensPerSecond * 0.8 &&
        candidate.sizeBytes <= baseline.sizeBytes * 1.2
}
