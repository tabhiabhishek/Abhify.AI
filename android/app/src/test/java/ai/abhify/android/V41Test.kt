package ai.abhify.android

import ai.abhify.android.training.DatasetPolicy
import ai.abhify.android.training.DatasetPolicy.EvalResult
import ai.abhify.android.training.DatasetPolicy.Example
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FineTuningSafeguardsTest {
    private fun ex(id: String, q: String, a: String, ok: Boolean = true) = Example(id, q, a, "chat", "c1", ok)

    @Test fun onlyApprovedSafeUniqueExamplesPass() {
        val r = DatasetPolicy.validate(listOf(
            ex("1", "Explain gradient descent briefly", "It moves weights against the gradient by a learning-rate step."),
            ex("2", "Explain gradient descent briefly", "It moves weights against the gradient by a learning-rate step."),      // duplicate
            ex("3", "What is my email?", "It is me@example.com and my phone is +91 98765 43210."),                            // personal data
            ex("4", "Configure the provider", "Set api_key = sk-abcdefghijklmnopqrstuvwxyz123456 in the settings"),         // secret
            ex("5", "Summarise Docker volumes", "Volumes persist data beyond the container's life.", ok = false),             // not approved
            ex("6", "Hi", "Hello"),                                                                                            // too short
            ex("7", "Write a SQL join example", "⚠️ The phone's model ran out of memory.")))                                    // an error, not an answer
        assertEquals(listOf("1"), r.accepted.map { it.id })
        val why = r.rejected.associate { it.example.id to it.reasons.joinToString() }
        assertTrue(why["2"]!!.contains("duplicate")); assertTrue(why["3"]!!.contains("email") && why["3"]!!.contains("phone"))
        assertTrue(why["4"]!!.contains("API key") || why["4"]!!.contains("credential")); assertTrue(why["5"]!!.contains("not approved"))
        assertTrue(why["6"]!!.contains("too short")); assertTrue(why["7"]!!.contains("error message"))
        assertTrue(DatasetPolicy.secrets("token: ya29." + "a0AfH6SMB" + "abcdefghijklmnop").isNotEmpty())
        assertTrue(DatasetPolicy.secrets("-----BEGIN RSA PRIVATE KEY-----").contains("private key"))
    }

    @Test fun exportIsVersionedAndChecksummed() {
        val r = DatasetPolicy.validate(listOf(ex("1", "Explain gradient descent briefly", "Weights move against the gradient each step.")))
        val data = DatasetPolicy.jsonl(r.accepted, system = "You are Abhify.")
        val line = JSONObject(data.lines().single())
        assertEquals(listOf("system", "user", "assistant"), (0 until 3).map { line.getJSONArray("messages").getJSONObject(it).getString("role") })
        val m = DatasetPolicy.manifest(3, data, r, "qwen2.5-1.5b-instruct-q4_k_m", "abc", 42)
        assertEquals(3, m.getInt("dataset_version")); assertEquals(1, m.getInt("examples")); assertEquals(64, m.getString("sha256").length)
        assertEquals(1, m.getJSONObject("sources").getInt("chat"))
        assertFalse(DatasetPolicy.manifest(3, data + " ", r, "q", null, 42).getString("sha256") == m.getString("sha256"))   // any change → new checksum
    }

    @Test fun aFineTunedModelIsOnlyOfferedWithApprovalAndWithoutRegression() {
        val base = EvalResult(0.70, 0.80, 0.10, 12.0, 1_117_320_736)
        assertFalse(DatasetPolicy.mayOffer(base.copy(taskAccuracy = 0.75), base, userApproved = false))
        assertTrue(DatasetPolicy.mayOffer(base.copy(taskAccuracy = 0.75), base, userApproved = true))
        assertFalse(DatasetPolicy.mayOffer(base.copy(taskAccuracy = 0.66), base, true))                 // quality regression
        assertFalse(DatasetPolicy.mayOffer(base.copy(hallucinationRate = 0.13), base, true))            // more hallucination
        assertFalse(DatasetPolicy.mayOffer(base.copy(tokensPerSecond = 8.0), base, true))               // too slow
        assertFalse(DatasetPolicy.APP_MAY_TRAIN)
    }

    @Test fun nothingTrainsAndTheModelIsUntouched() {
        val main = File("src/main/java/ai/abhify/android")
        val all = main.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(main).path to it.readText() }
        // the dataset code is pure: no database, file, network or AI access
        val dp = all.getValue("training${File.separator}DatasetPolicy.kt")
        assertFalse(listOf("ChatDb", "File(", "openFileOutput", "http", "hybrid", "engine.", "WorkManager").any { dp.contains(it) })
        // no training code anywhere in the app, and the native layer has no training or adapter entry point
        assertTrue(all.values.none { Regex("(?i)\\b(finetune|lora_adapter|llama_adapter|trainer)\\b").containsMatchIn(it) })
        val cpp = File("src/main/cpp/abhify_llm.cpp").readText()
        assertFalse(cpp.contains("llama_opt") || cpp.contains("adapter"))
        // the model file is written only by the verified download path
        assertTrue(all.filterKeys { !it.startsWith("model") }.values.none { it.contains("model.gguf") })
    }
}
