package ai.abhify.android

import ai.abhify.android.chat.Note
import ai.abhify.android.chat.SchemaV10
import ai.abhify.android.chat.SchemaV11
import ai.abhify.android.chat.SchemaV12
import ai.abhify.android.chat.SchemaV13
import ai.abhify.android.chat.SchemaV14
import ai.abhify.android.chat.SchemaV15
import ai.abhify.android.chat.SchemaV9
import ai.abhify.android.chat.Task
import ai.abhify.android.workspace.ContextOrchestrator
import ai.abhify.android.workspace.UnifiedSearch
import ai.abhify.android.workspace.UnifiedSearch.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection

internal fun currentDb(): Connection = v8Db().also { c ->
    c.createStatement().use { st -> listOf(SchemaV9.STATEMENTS, SchemaV10.STATEMENTS, SchemaV11.STATEMENTS, SchemaV12.STATEMENTS, SchemaV13.STATEMENTS,
        SchemaV14.STATEMENTS, SchemaV15.STATEMENTS).flatten().forEach(st::execute) }
}

class UnifiedWorkspaceTest {
    private fun search(c: Connection, q: String, t: Type): List<Pair<Long, String>> {
        val args = UnifiedSearch.args(q, 10) ?: return emptyList()
        return c.prepareStatement(UnifiedSearch.SQL.getValue(t)).use { p -> args.forEachIndexed { i, a -> p.setString(i + 1, a) }
            p.executeQuery().use { r -> generateSequence { if (r.next()) r.getLong(1) to (r.getString(2) + " | " + r.getString(3)) else null }.toList() } }
    }

    @Test fun everySourceIsSearchableOnTheRealSchema() {
        currentDb().use { c ->
            c.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Coroutines help', 1, 5, 'c1')")
            c.exec("INSERT INTO conversations (title, created_at, updated_at, uid) VALUES ('Other', 1, 6, 'c2')")
            c.exec("INSERT INTO messages (conversation_id, role, content, created_at, uid) VALUES (2, 'user', 'How do Kotlin flows work?', 1, 'm1')")
            c.exec("INSERT INTO conversations (title, created_at, updated_at, uid, deleted) VALUES ('Kotlin deleted', 1, 7, 'c3', 1)")
            c.exec("INSERT INTO memories (content, created_at, uid, category) VALUES ('Prefers Kotlin over Java', 1, 'm1', 'Preferences')")
            c.exec("INSERT INTO library_docs (uid, name, kind, size_bytes, chars, text, created_at, updated_at) VALUES ('d', 'guide.pdf', 'PDF', 1, 5, '" + "x".repeat(300) + " Kotlin coroutines are light threads " + "y".repeat(300) + "', 1, 1)")
            c.exec("INSERT INTO workspaces (name, kind, created_at) VALUES ('Abhify', 'work', 1)")
            c.exec("INSERT INTO notes (workspace_id, title, body, created_at, updated_at) VALUES (1, 'Standup', 'Migrate to Kotlin 2.3', 1, 1)")
            c.exec("INSERT INTO tasks (workspace_id, title, created_at, updated_at) VALUES (1, 'Update Kotlin plugin', 1, 1)")
            c.exec("INSERT INTO vault_items (uid, kind, title, body, category, created_at, updated_at) VALUES ('v', 'note', 'Kotlin tips', 'use val', 'General', 1, 1)")
            c.exec("INSERT INTO study_sessions (title, kind, source_kind, content, created_at, updated_at) VALUES ('Summary', 'SUMMARY', 'TOPIC', 'Kotlin basics', 1, 1)")
            c.exec("INSERT INTO flashcards (deck, front, back, created_at, updated_at) VALUES ('Lang', 'What is Kotlin?', 'A JVM language', 1, 1)")
            c.exec("INSERT INTO study_plans (topic, level, created_at, updated_at) VALUES ('Android', 'Beginner', 1, 1)")
            c.exec("INSERT INTO plan_items (plan_id, position, text) VALUES (1, 0, 'Day 1 – Kotlin syntax')")
            c.exec("INSERT INTO job_descriptions (title, company, body, created_at, updated_at) VALUES ('Android Dev', 'Contoso', 'Kotlin required', 1, 1)")
            c.exec("INSERT INTO job_applications (company, title, status, notes, created_at, updated_at) VALUES ('Fabrikam', 'Kotlin Dev', 'APPLIED', NULL, 1, 1)")
            c.exec("INSERT INTO interview_attempts (topic, question, answer, created_at) VALUES ('Technical', 'Explain Kotlin null safety', 'a', 1)")
            for (t in Type.entries) assertTrue("$t found nothing", search(c, "kotlin", t).isNotEmpty())
            val chats = search(c, "kotlin", Type.CHAT)
            assertEquals(listOf(2L), chats.map { it.first }); assertTrue(chats.single().second.contains("Kotlin flows"))        // message match, deleted chat excluded
            val doc = search(c, "kotlin", Type.DOCUMENT).single().second
            assertTrue(doc.contains("Kotlin coroutines") && doc.length < 250)                                                  // a snippet, not the whole text
            assertEquals(3, search(c, "kotlin", Type.CAREER).size)                                                            // job, application, practice
            assertTrue(search(c, "Kotlin flows", Type.MEMORY).isEmpty())
        }
    }

    @Test fun userTextIsNeverAWildcard() {
        currentDb().use { c ->
            c.exec("INSERT INTO memories (content, created_at, uid) VALUES ('Scored 100% in SQL', 1, 'a')")
            c.exec("INSERT INTO memories (content, created_at, uid) VALUES ('Has 1000 followers', 1, 'b')")
            c.exec("INSERT INTO memories (content, created_at, uid) VALUES ('Uses snake_case', 1, 'c')")
            c.exec("INSERT INTO memories (content, created_at, uid) VALUES ('Uses snakeXcase', 1, 'd')")
            assertEquals(1, search(c, "100%", Type.MEMORY).size)
            assertEquals(1, search(c, "snake_case", Type.MEMORY).size)
            assertNull(UnifiedSearch.args(" a ", 5))                                                   // too short: no query at all
        }
    }

    @Test fun rankingPutsTitleMatchesFirstAndCapsEachType() {
        val hits = (1..20L).map { UnifiedSearch.Hit(Type.CHAT, it, "chat $it", "mentions kotlin", it, null) } +
            UnifiedSearch.Hit(Type.NOTE, 99, "Kotlin roadmap", "", 0, 1) + UnifiedSearch.Hit(Type.MEMORY, 98, "Preferences", "likes kotlin", 50, null)
        val r = UnifiedSearch.rank(hits, "kotlin", perType = 5)
        assertEquals(99L, r.first().id); assertEquals(5, r.count { it.type == Type.CHAT }); assertEquals(20L, r.first { it.type == Type.CHAT }.id)
    }

    @Test fun workspaceContextIsRelevantBoundedAndReported() {
        val notes = listOf(Note(1, 1, "Release plan", "Ship v4.0 after phone tests", "note", 3), Note(2, 1, "Lunch", "Pizza on Friday", "note", 5),
            Note(3, 1, "Signing", "Production signing needs an owner decision", "note", 1), Note(4, 1, "Old", "Unrelated", "note", 0))
        val tasks = listOf(Task(1, 1, "Run phone tests", false, null), Task(2, 1, "Done thing", true, null))
        val parts = ContextOrchestrator.workspaceParts("Abhify", notes, tasks, "When can we ship the release?")
        assertTrue(parts[0].text.contains("Release plan")); assertFalse(parts[0].text.contains("Pizza"))                    // relevant notes only
        assertTrue(parts[1].text.contains("Run phone tests")); assertFalse(parts[1].text.contains("Done thing"))           // open tasks only
        val b = ContextOrchestrator.build(parts, 1200)
        assertTrue(b.text.length <= 1200); assertEquals(listOf("notes of workspace \"Abhify\"", "open tasks of workspace \"Abhify\""), b.parts.map { it.first })
        val tiny = ContextOrchestrator.build(listOf(ContextOrchestrator.Part("big", "line\n".repeat(500))), 300)
        assertTrue(tiny.truncated && tiny.text.length <= 300)
        assertTrue(ContextOrchestrator.build(emptyList(), 1000).text.isEmpty())
    }

    @Test fun linksAreReferencesAndContextStaysBehindConsent() {
        currentDb().use { c ->
            c.exec("INSERT INTO workspace_links (workspace_id, kind, ref_id, created_at) VALUES (1, 'doc', 5, 1)")
            assertTrue(runCatching { c.exec("INSERT INTO workspace_links (workspace_id, kind, ref_id, created_at) VALUES (1, 'doc', 5, 2)") }.isFailure)
        }
        val main = File("src/main/java/ai/abhify/android")
        val vm = File(main, "ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("if ((chatDocs.isNotEmpty() || chatWorkspace.value != null) && nextIsOnline() && contextKey() !in docConsent)"))
        assertTrue(vm.contains("(if (docsConsented) ragDoc(onlineCtx, wsOnline) else null)"))                                // online only after consent
        val studio = File(main, "chat/StudioDb.kt").readText()
        assertTrue(studio.contains("delete(\"workspace_links\", \"workspace_id = ?\"") && studio.contains("deleteChunks(d.id); deleteVectors(d.id)"))
        for (f in listOf("workspace/UnifiedSearch.kt", "workspace/ContextOrchestrator.kt", "chat/V40Db.kt", "ui/V40State.kt"))
            File(main, f).readText().let { t -> assertFalse(f, t.contains("hybrid.") || t.contains("aiTask(") || t.contains("http", ignoreCase = false) || t.contains("Log.")) }
        assertTrue(File(main, "chat/ChatDb.kt").readText().contains("if (oldVersion < 15) SchemaV15.apply"))
    }
}
