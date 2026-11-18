package ai.abhify.android

import ai.abhify.android.chat.MemoryItem
import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV11
import ai.abhify.android.chat.SchemaV12
import ai.abhify.android.chat.SchemaV13
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.memory.MemoryKit
import ai.abhify.android.memory.MemoryReview
import ai.abhify.android.memory.MemoryReview.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdvancedMemoryTest {
    private val DAY = 86_400_000L
    private val NOW = 1_000 * DAY
    private fun m(id: Long, c: String, cat: String = "General", imp: Int = 2, updated: Long = NOW - DAY, expires: Long? = null, used: Long? = null, uses: Int = 0, src: String? = "manual") =
        MemoryItem(id, "u$id", c, cat, imp, true, src, expires, updated, updated, used, uses, null)
    private val related: (String, String) -> Boolean = { a, b -> setOf(a, b) == setOf("flask", "fastapi") || setOf(a, b) == setOf("pytorch", "tensorflow") }

    @Test fun contradictionsAreDetectedFromSlotsNotFromAnyTwoPreferences() {
        assertTrue(MemoryReview.contradicts("Preferred framework: Flask.", "Preferred framework: FastAPI."))           // the spec's example
        assertTrue(MemoryReview.contradicts("Lives in Pune", "The user lives in Bengaluru"))
        assertTrue(MemoryReview.contradicts("Works as a data analyst", "Works as an ML engineer"))
        assertTrue(MemoryReview.contradicts("Prefers PyTorch over TensorFlow", "Prefers TensorFlow"))                  // opposite preference
        assertFalse(MemoryReview.contradicts("Prefers dark mode", "Prefers PyTorch examples"))                        // different things
        assertFalse(MemoryReview.contradicts("Prefers Flask", "Prefers FastAPI"))                                     // unknown relation: not claimed
        assertTrue(MemoryReview.contradicts("Prefers Flask", "Prefers FastAPI", related))                             // taxonomy says alternatives
        assertFalse(MemoryReview.contradicts("Preferred framework: Flask", "Preferred framework: flask"))             // same value = duplicate, not conflict
        assertFalse(MemoryReview.contradicts("Is building an Android app called Abhify", "Lives in Pune"))
        assertEquals(MemoryReview.Slot("preferred framework", "flask"), MemoryReview.slot("My preferred framework is Flask."))
        assertNull(MemoryReview.slot("Studies NLP every evening"))
    }

    @Test fun reviewQueueFindsEachKindAndRemembersKeepBoth() {
        val items = listOf(
            m(1, "Preferred framework: Flask", updated = NOW - 2 * DAY), m(2, "Preferred framework: FastAPI", updated = NOW - DAY),
            m(3, "Likes short answers"), m(4, "Likes short answers."),
            m(5, "Learning Kotlin coroutines and flows", "Learning goals"), m(6, "Learning Kotlin coroutines for Android", "Learning goals"),
            m(7, "Exam on Friday", expires = NOW - DAY), m(8, "Interview next week", expires = NOW + 3 * DAY),
            m(9, "Once tried Rust", imp = 1, updated = NOW - 400 * DAY), m(10, "Uses Linux", imp = 1, updated = NOW - 400 * DAY, used = NOW - DAY, uses = 3))
        val r = MemoryReview.review(items, NOW)
        val kinds = r.groupBy { it.kind }
        assertEquals(setOf(1L, 2L), kinds[Kind.CONFLICT]!!.single().memories.map { it.id }.toSet())
        assertEquals(setOf(3L, 4L), kinds[Kind.DUPLICATE]!!.single().memories.map { it.id }.toSet())
        assertEquals(setOf(5L, 6L), kinds[Kind.COMBINE]!!.single().memories.map { it.id }.toSet())
        assertEquals(7L, kinds[Kind.EXPIRED]!!.single().memories.single().id); assertEquals(8L, kinds[Kind.EXPIRING]!!.single().memories.single().id)
        assertEquals(listOf(9L), kinds[Kind.STALE]!!.map { it.memories.single().id })                               // 10 was used recently
        assertEquals(Kind.CONFLICT, r.first().kind)                                                                  // most important first
        val afterKeepBoth = MemoryReview.review(items, NOW, dismissed = setOf(kinds[Kind.CONFLICT]!!.single().key))
        assertTrue(afterKeepBoth.none { it.kind == Kind.CONFLICT })
        val d = MemoryReview.dashboard(items, r, NOW)
        assertEquals(10, d.total); assertEquals(1, d.conflicts); assertEquals(1, d.duplicates); assertEquals(1, d.expired); assertEquals(1, d.expiringSoon); assertEquals(9, d.neverUsed)
    }

    @Test fun provenanceIsNeverInvented() {
        val titles = mapOf(5L to "ML chat")
        assertEquals("Source not recorded (added before v3.4)", MemoryReview.provenance(null))
        assertTrue(MemoryReview.provenance("chat:5", { titles[it] }).contains("the chat \"ML chat\""))
        assertTrue(MemoryReview.provenance("chat:6", { titles[it] }).contains("no longer exists"))
        assertTrue(MemoryReview.provenance("remember:5", { titles[it] }).startsWith("\"Remember that…\" in the chat"))
        assertTrue(MemoryReview.provenance("chat").contains("not recorded"))
        assertTrue(MemoryReview.provenance("doc:3", docName = { "notes.pdf" }).contains("notes.pdf"))
        assertEquals(5L, MemoryReview.chatIdOf("remember:5")); assertNull(MemoryReview.chatIdOf("manual"))
    }

    @Test fun retrievalUsesContextAndRecencyButStaysBounded() {
        val items = listOf(m(1, "Building the Abhify Android app in Kotlin", "Active projects", updated = NOW - 300 * DAY),
            m(2, "Prefers PyTorch examples", "Preferences", updated = NOW - DAY), m(3, "Prefers TensorFlow examples", "Preferences", updated = NOW - 300 * DAY),
            m(4, "Lives in Pune", "Personal facts"))
        // a follow-up with no shared words: the ongoing chat about the Kotlin app makes the project memory relevant
        val follow = MemoryKit.relevant("How do I fix this crash?", items, now = NOW, context = "My Abhify Android app in Kotlin crashes on start")
        assertEquals(listOf(1L), follow.map { it.item.id })
        assertTrue(MemoryKit.relevant("How do I fix this crash?", items, now = NOW).isEmpty())                       // without context: nothing
        assertTrue(MemoryKit.relevant("capital of France?", items, now = NOW, context = "we talked about Pune").isEmpty())   // one context word is not enough
        val ex = MemoryKit.relevant("Show me examples", items, now = NOW).map { it.item.id }
        assertEquals(2L, ex.first())                                                                                  // recent memory first on a tie
        val many = (1..30L).map { m(it, "Kotlin coroutine fact $it", updated = NOW) }
        assertTrue(MemoryKit.relevant("kotlin coroutine", many, now = NOW, context = "kotlin coroutine kotlin").size <= 6)
    }

    @Test fun schemaV13AndWiring() {
        v8Db().use { c ->
            c.createStatement().use { st -> listOf(SchemaV9.STATEMENTS, SchemaV10.STATEMENTS, SchemaV11.STATEMENTS, SchemaV12.STATEMENTS).flatten().forEach(st::execute) }
            c.exec("INSERT INTO memories (content, created_at, uid, category) VALUES ('Prefers PyTorch', 1, 'm1', 'Preferences')")
            c.createStatement().use { st -> SchemaV13.apply { st.execute(it) } }
            assertEquals("Prefers PyTorch", c.one("SELECT content FROM memories")); assertEquals(0, c.one("SELECT use_count FROM memories")); assertNull(c.one("SELECT last_used_at FROM memories"))
            c.exec("INSERT INTO memory_dismissed (pair_key, kind, created_at) VALUES ('CONFLICT:a|b', 'CONFLICT', 1)")
            assertTrue(runCatching { c.exec("INSERT INTO memory_dismissed (pair_key, kind, created_at) VALUES ('CONFLICT:a|b', 'CONFLICT', 2)") }.isFailure)
        }
        val vm = File("src/main/java/ai/abhify/android/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("relevantMemories(question, recentContext(), mark = false)"))                          // private chats store nothing
        assertTrue(vm.contains("g.db.addMemoryItem(f, \"General\", 2, \"remember:\$c\")"))                            // provenance recorded
        val st = File("src/main/java/ai/abhify/android/ui/V34State.kt").readText()
        assertTrue(st.contains("clash?.let { \"may contradict"))                                                     // add asks before saving a conflict
        assertTrue(File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText().contains("if (oldVersion < 13) SchemaV13.apply"))
    }
}
