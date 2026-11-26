package ai.abhify.android

import ai.abhify.android.intel.Citations
import ai.abhify.android.voice.SpeechStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 7.0.1 citation integrity on the phone: the same shared cases as the desktop (backend/tests/data/citation_cases.json). */
class CitationTest {
    private val cases = JSONObject(File("../../backend/tests/data/citation_cases.json").readText())

    private fun sources(key: String?): List<Citations.Src> {
        if (key == null) return emptyList()
        val arr = cases.getJSONObject("sources").getJSONArray(key)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Citations.Src(o.getString("id"), o.getString("title"), o.getString("publisher"), o.optString("url").ifEmpty { null }, o.optString("snippet"),
                if (o.isNull("published_at")) null else o.getLong("published_at"), if (o.isNull("retrieved_at")) null else o.getLong("retrieved_at"))
        }
    }

    @Test fun sharedCasesMatchTheDesktop() {
        val list = cases.getJSONArray("cases")
        assertEquals(23, list.length())
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            val name = c.getString("name")
            val (out, r) = Citations.check(c.getString("answer"), sources(if (c.isNull("sources")) null else c.getString("sources")))
            val contains = c.getJSONArray("contains"); val absent = c.getJSONArray("absent")
            for (j in 0 until contains.length()) assertTrue("$name: '${contains.getString(j)}' in '$out'", out.contains(contains.getString(j)))
            for (j in 0 until absent.length()) assertFalse("$name: '${absent.getString(j)}' not in '$out'", out.contains(absent.getString(j)))
            assertEquals(name, listOf(c.getInt("verified"), c.getInt("remapped"), c.getInt("unsupported"), c.getInt("invalid")),
                listOf(r.verified, r.remapped.size, r.unsupported.size, r.invalid.size))
            if (c.has("urls_removed")) assertEquals(name, c.getInt("urls_removed"), r.urlsRemoved.size)
            assertEquals(name, c.optInt("references", 0), r.references)
        }
    }

    @Test fun sourceLinksWithoutRetrievalAreLabelledUnverified() {
        val list = cases.getJSONArray("unverified_cases")
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i); val name = c.getString("name")
            val (out, flagged) = Citations.labelUnverified(c.getString("answer"))
            val contains = c.getJSONArray("contains"); val absent = c.getJSONArray("absent")
            for (j in 0 until contains.length()) assertTrue("$name: '${contains.getString(j)}' in '$out'", out.contains(contains.getString(j)))
            for (j in 0 until absent.length()) assertFalse("$name: '${absent.getString(j)}' not in '$out'", out.contains(absent.getString(j)))
            assertEquals(name, c.getInt("flagged"), flagged.size)
        }
        assertTrue(File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText().contains("Citations.labelUnverified(checked)"))
    }

    @Test fun noMarkerOutsideTheRegistrySurvives() {
        val src = sources("ai"); val rnd = java.util.Random(7)
        val words = listOf("OpenAI", "Gemini 4", "2 million tokens", "40%", "GPT-5.5", "Apple", "faster", "Reuters", "released", "1999")
        repeat(300) {
            val ans = (1..(1 + rnd.nextInt(4))).joinToString(" ") { "${words[rnd.nextInt(words.size)]} ${words[rnd.nextInt(words.size)]} [${"WL"[rnd.nextInt(2)]}${rnd.nextInt(10)}]." }
            val (out, _) = Citations.check(ans, src)
            Citations.MARK.findAll(out).forEach { m -> assertTrue("$ans -> $out", (m.groupValues[1] + m.groupValues[2]).uppercase() in setOf("W1", "W2")) }
        }
    }

    @Test fun reportSummaryAndWiring() {
        val (_, r) = Citations.check("Google DeepMind unveiled Gemini 4 with a 2 million token context window [W1].", sources("ai"))
        assertEquals("🔎 Citations checked: 1 citation moved to the source that has the fact", r.summary())
        assertEquals(null, Citations.check("OpenAI released GPT-5.5 [W1].", sources("ai")).second.summary())
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("ai.abhify.android.intel.Citations.check(raw, citeSources)"))                // checked before saving and showing
        assertTrue(vm.contains("id = g.db.addMessage(conv, \"assistant\", answer, note"))                  // the checked text is what's stored
        val spoken = SpeechStream(7, minChars = 10).finish("Apple bought it [unsupported]. Gemini 4 reads 2 million tokens [W2].")
        assertTrue(spoken.joinToString(" ").let { !it.contains("unsupported") && !it.contains("W2") && it.contains("Apple bought it") })
    }
}
