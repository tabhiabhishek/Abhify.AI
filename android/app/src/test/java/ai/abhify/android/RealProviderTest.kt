package ai.abhify.android

import ai.abhify.android.cloud.UrlHttp
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderClient
import ai.abhify.android.providers.ProviderStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in calls to the real providers with the app's own client (free models only, a one-line answer).
 * Run with ABHIFY_REAL_PROVIDERS=1 and any of ABHIFY_GEMINI_KEY / ABHIFY_GROQ_KEY / ABHIFY_OPENROUTER_KEY set in the
 * environment. Keys are read from the environment only and are never printed: the output shows the provider,
 * model, result and answer length.
 */
class RealProviderTest {
    private fun key(p: Provider) = System.getenv("ABHIFY_${p.name}_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    @Test fun configuredProvidersAnswer() {
        assumeTrue("set ABHIFY_REAL_PROVIDERS=1 and provider keys to run", System.getenv("ABHIFY_REAL_PROVIDERS") == "1")
        val ps = Provider.entries.filter { key(it) != null }
        assumeTrue("no ABHIFY_<PROVIDER>_KEY set", ps.isNotEmpty())
        for (p in ps) {
            val k = key(p)!!
            val store = ProviderStore(MemKv(), MemBox())
            val client = ProviderClient(UrlHttp(), store)
            val check = client.validate(p, k)
            println("REAL ${p.title}: validate ok=${check.ok} · ${check.message}")
            assertFalse("message leaks the key", check.message.contains(k))
            assertTrue("${p.title}: ${check.message}", check.ok)
            store.saveKey(p, k); store.adoptModels(p, check.models)
            val out = StringBuilder()
            val err = client.chat(p, listOf("user" to "Reply with one short sentence: what is overfitting?"), 1024, 0.2) { out.append(it); true }
            println("REAL ${p.title} · ${store.config(p).model}: ${err ?: "answered ${out.length} chars"}")
            err?.let { assertFalse("error leaks the key", it.contains(k)) }
            assertNull("${p.title}: $err", err)
            assertTrue(out.isNotBlank())
            // RC.5: a long answer arrives complete (finish "stop"), or is reported as cut by the limit, never silently short
            val long = StringBuilder()
            val r = client.chatFull(p, listOf("user" to "Explain how transformers work in about 700 words with headings."), store.config(p).maxTokens, 0.3) { long.append(it); true }
            println("REAL ${p.title} long answer: ${long.length} chars · finish=${r.finish} · ${r.error ?: "ok"}")
            r.error?.let { assertFalse("error leaks the key", it.contains(k)) }
            assertNull("${p.title}: ${r.error}", r.error)
            assertTrue("finish=${r.finish}", r.finish == "stop" || r.finish == "length")
            assertTrue(long.length > 1500)
        }
    }
}
