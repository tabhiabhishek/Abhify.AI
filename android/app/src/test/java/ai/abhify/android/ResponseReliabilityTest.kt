package ai.abhify.android

import ai.abhify.android.ai.AiMode
import ai.abhify.android.ai.ChatEvent
import ai.abhify.android.ai.HybridAi
import ai.abhify.android.ai.OnlineTarget
import ai.abhify.android.ai.Route
import ai.abhify.android.ai.RouteInputs
import ai.abhify.android.ai.Source
import ai.abhify.android.chat.Continuation
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.chat.SchemaV5
import ai.abhify.android.cloud.CloudClient
import ai.abhify.android.cloud.Http
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.cloud.StreamResult
import ai.abhify.android.cloud.TokenStore
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.providers.Provider
import ai.abhify.android.providers.ProviderClient
import ai.abhify.android.providers.ProviderRules
import ai.abhify.android.providers.ProviderRules.Line
import ai.abhify.android.providers.ProviderStore
import ai.abhify.android.voice.Voice
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/** RC.5: answers are never cut short by the app, and a partial answer is never shown or stored as complete. */
class ResponseReliabilityTest {
    private val prompt = listOf(PromptBuilder.Msg("user", "Explain transformers in depth"))
    private fun chunk(text: String?, finish: String?) = "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
        .put("index", 0).put("delta", JSONObject().apply { if (text == null) put("content", JSONObject.NULL) else put("content", text) })
        .put("finish_reason", finish ?: JSONObject.NULL))).toString()

    @Test fun finishReasonsAreParsedInEveryProviderShape() {
        assertEquals(Line.Token("end.", "stop"), ProviderRules.parseLine(chunk("end.", "stop")))          // text and finish in one chunk (Groq, OpenRouter)
        assertEquals(Line.Finish("length"), ProviderRules.parseLine(chunk("", "length")))                  // separate final chunk
        assertEquals(Line.Finish("length"), ProviderRules.parseLine(chunk(null, "length")))                // Gemini: "content": null
        assertEquals(Line.Token("Hi"), ProviderRules.parseLine(chunk("Hi", null)))
        assertEquals(Line.Skip, ProviderRules.parseLine(chunk(null, null)))
    }

    private fun store(p: Provider) = ProviderStore(MemKv(), MemBox()).apply {
        saveKey(p, when (p) { Provider.GEMINI -> "AIzaFAKE-test-key-0000000000000000wxyz"; Provider.GROQ -> "gsk_FAKEtestkey00000000000000000000abcd"
            Provider.OPENROUTER -> "sk-or-v1-FAKEtestkey0000000000000000000000ef12" })
    }

    @Test fun lengthLimitIsReportedNotHidden() {
        for (p in Provider.entries) {
            val http = ScriptedHttp(onStream = { _, _, on -> on(chunk("Part one ", null)); on(chunk("part two", null)); on(chunk(null, "length")); on("data: [DONE]"); StreamResult(200) })
            val out = StringBuilder()
            val r = ProviderClient(http, store(p)).chatFull(p, listOf("user" to "x"), p.defaultMaxTokens, 0.3) { out.append(it); true }
            assertNull(r.error); assertEquals("length", r.finish); assertEquals("Part one part two", out.toString())
            val body = JSONObject(http.bodies.single())
            assertEquals(p.defaultMaxTokens, body.getInt(p.maxTokensField))                             // the provider's own field name
            assertFalse(body.has(if (p == Provider.GROQ) "max_tokens" else "max_completion_tokens"))
        }
        assertTrue(Provider.entries.all { it.defaultMaxTokens >= 4096 })                                 // RC.4 sent 768
    }

    @Test fun streamEndingWithoutDoneIsInterruptedAndCompleteIsStop() {
        val p = Provider.OPENROUTER
        val cut = ScriptedHttp(onStream = { _, _, on -> on(chunk("Half an ans", null)); StreamResult(200) })
        assertEquals("interrupted", ProviderClient(cut, store(p)).chatFull(p, listOf("user" to "x"), 100, 0.3) { true }.finish)
        val ok = ScriptedHttp(onStream = { _, _, on -> on(chunk("Done.", "stop")); on("data: [DONE]"); StreamResult(200) })
        assertEquals("stop", ProviderClient(ok, store(p)).chatFull(p, listOf("user" to "x"), 100, 0.3) { true }.finish)
        val stopped = ScriptedHttp(onStream = { _, _, on -> for (t in listOf("a", "b", "c")) if (!on(chunk(t, null))) break; StreamResult(200) })
        assertEquals("cancelled", ProviderClient(stopped, store(p)).chatFull(p, listOf("user" to "x"), 100, 0.3) { false }.finish)
        val reasoningOnly = ScriptedHttp(onStream = { _, _, on -> on(chunk(null, "length")); on("data: [DONE]"); StreamResult(200) })
        assertTrue(ProviderClient(reasoningOnly, store(p)).chatFull(p, listOf("user" to "x"), 100, 0.3) { true }.error!!.contains("whole answer length limit"))
    }

    @Test fun longAnswersArriveCompleteThroughTheRouter() = runBlocking {
        val s = store(Provider.GROQ)
        val words = (1..5000).map { "w$it " }                                                             // ~5000 chunks, far beyond RC.4's limit
        val http = ScriptedHttp(onStream = { _, _, on -> words.forEach { on(chunk(it, null)) }; on(chunk(null, "stop")); on("data: [DONE]"); StreamResult(200) })
        val ai = HybridAi(CloudClient(http, TokenStore(MemKv(), MemBox())), ProviderClient(http, s), LlmEngine("/x"), { "no" }, { null },
            { p -> s.config(p).model }, { p -> s.config(p).maxTokens })
        val ev = ai.generate(prompt, prompt, RouteInputs(AiMode.ONLINE, true, true, false, false, listOf(Provider.GROQ), OnlineTarget.Personal(Provider.GROQ), false)).toList()
        assertEquals(Source.Kind.GROQ, (ev.first() as ChatEvent.Attempt).source.kind)                   // the source is known from the first moment
        assertEquals(words.joinToString(""), ev.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text })
        assertEquals("stop", (ev.last() as ChatEvent.Done).finish)
    }

    @Test fun continueUsesOnlyTheSameRoute() = runBlocking {
        val s = store(Provider.GEMINI)
        val http = ScriptedHttp(onStream = { url, _, on -> if (url.contains("googleapis")) { on(chunk("more", "stop")); on("data: [DONE]") }; StreamResult(200) })
        val ai = HybridAi(CloudClient(http, TokenStore(MemKv(), MemBox())), ProviderClient(http, s), LlmEngine("/x"), { "no" }, { null }, { s.config(it).model })
        val ev = ai.generateOn(Route.Personal(Provider.GEMINI), prompt, prompt).toList()
        assertEquals("more", ev.filterIsInstance<ChatEvent.Token>().single().text)
        assertTrue(http.requests.all { it.first.contains("googleapis") })
    }

    @Test fun continuationJoinsWithoutRepeatingText() {
        val partial = "Attention lets each token look at every other token. The scores are computed with"
        assertEquals("$partial queries and keys.", Continuation.join(partial, " queries and keys."))
        assertEquals("$partial queries and keys.", Continuation.join(partial, "The scores are computed with queries and keys."))   // repeated tail removed
        assertEquals(partial + "x", Continuation.join(partial, "x"))
        assertEquals(0, Continuation.overlap("abc", "abc", min = 8))                                       // short coincidences are kept
        assertTrue(Continuation.tail("a ".repeat(2000), 100).length <= 101)
        assertEquals("short", Continuation.tail("short", 100))
    }

    @Test fun statusRulesMarkEveryPartialAnswer() {
        assertNull(Continuation.statusOf(false, false, true, "stop"))
        assertNull(Continuation.statusOf(false, false, true, null))
        assertEquals("length", Continuation.statusOf(false, false, true, "length"))
        assertEquals("stopped", Continuation.statusOf(false, false, false, null))                         // Stop tapped: no Done event
        assertEquals("stopped", Continuation.statusOf(false, false, true, "cancelled"))
        assertEquals("interrupted", Continuation.statusOf(false, true, false, null))                      // failed after some text
        assertEquals("interrupted", Continuation.statusOf(false, false, true, "interrupted"))
        assertNull(Continuation.statusOf(true, true, false, null))                                         // nothing to store
    }

    @Test fun readAloudCoversTheWholeAnswerInParts() {
        val text = (1..400).joinToString(" ") { "Sentence number $it is here." }
        val parts = Voice.parts(text, 3500)
        assertTrue(parts.size > 1); assertTrue(parts.all { it.length <= 3500 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))                      // nothing dropped
    }

    @Test fun previewIsOnlyAViewAndNeverEndsInsideCode() {
        val long = "Intro paragraph.\n\n" + "Body sentence. ".repeat(80) + "\n\n```python\n" + "x = 1\n".repeat(200) + "```\n\nEnd."
        val p = ai.abhify.android.ui.previewOf(long)
        assertTrue(p.length < long.length); assertTrue(long.startsWith(p))
        assertEquals(0, p.split("```").size % 2 - 1)                                                       // fences balanced (even count)
        assertEquals("short", ai.abhify.android.ui.previewOf("short"))
    }

    @Test fun schemaV5MigratesAPopulatedV4DatabaseWithoutLosingAnything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            // the v4 tables this migration touches, as RC.4 created them
            st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, uid TEXT, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, profile_id TEXT, pinned INTEGER NOT NULL DEFAULT 0)")
            st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, stats TEXT, uid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, model TEXT, profile_id TEXT)")
            st.execute("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Old chat', 1, 2, 'c1')")
            val long = "x".repeat(200_000)                                                                // a very long stored answer
            st.execute("INSERT INTO messages (conversation_id, role, content, created_at, uid, model) VALUES (1, 'assistant', '$long', 3, 'm1', 'groq:llama')")
            SchemaV5.apply { st.execute(it) }
            c.createStatement().executeQuery("SELECT content, status, feedback, model FROM messages WHERE uid = 'm1'").use { r ->
                assertTrue(r.next()); assertEquals(200_000, r.getString(1).length); assertNull(r.getString(2)); assertNull(r.getObject(3)); assertEquals("groq:llama", r.getString(4))
            }
            c.createStatement().executeQuery("SELECT title, folder, pinned FROM conversations").use { r -> assertTrue(r.next()); assertEquals("Old chat", r.getString(1)); assertNull(r.getString(2)) }
            for (t in listOf("library_docs", "flashcards", "study_plans", "plan_items", "workspaces", "notes", "tasks"))
                c.createStatement().executeQuery("SELECT COUNT(*) FROM $t").use { r -> assertTrue(t, r.next()); assertEquals(0, r.getInt(1)) }
            st.execute("UPDATE messages SET status = 'length' WHERE uid = 'm1'")
            c.createStatement().executeQuery("SELECT status FROM messages").use { r -> r.next(); assertEquals("length", r.getString(1)) }
        }
        assertTrue(SchemaV5.STATEMENTS.none { Regex("\\b(DROP|DELETE FROM|RENAME|ON CONFLICT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })   // additive, SQLite 3.22-safe
    }
}
