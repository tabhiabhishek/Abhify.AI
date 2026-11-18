package ai.abhify.android

import ai.abhify.android.chat.ChatDb
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 → v3 migration on a real Android SQLite (run on a phone or emulator). Builds the exact v2 schema
 * that 0.1.0-dev.4 / 2.0.0-rc.1 created, fills it, opens it with today's ChatDb and checks nothing was lost.
 */
@RunWith(AndroidJUnit4::class)
class DbMigrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun v2DatabaseUpgradesWithoutLosingAnything() {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, stats TEXT)")
            db.execSQL("CREATE INDEX messages_conv ON messages(conversation_id, id)")
            db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE quiz_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, question TEXT NOT NULL, correct INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE interview_views (question TEXT PRIMARY KEY, category TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO conversations (title, created_at, updated_at) VALUES ('Gradient descent', 1000, 2000), ('Transformers', 3000, 4000)")
            db.execSQL("INSERT INTO messages (conversation_id, role, content, created_at, stats) VALUES (1, 'user', 'What is GD?', 1000, NULL), (1, 'assistant', 'An optimizer…', 1100, '{\"tps\":9.1}'), (2, 'user', 'Attention?', 3000, NULL)")
            db.execSQL("INSERT INTO memories (content, created_at) VALUES ('I like PyTorch', 500), ('Exam on Friday', 600)")
            db.execSQL("INSERT INTO quiz_answers (category, question, correct, created_at) VALUES ('ml', 'Q1', 1, 700)")
            db.version = 2
        }

        val db = ChatDb(ctx, name)
        val convs = db.conversations()
        assertEquals(listOf("Transformers", "Gradient descent"), convs.map { it.title })
        assertEquals(listOf("What is GD?", "An optimizer…"), db.messages(convs.last().id).map { it.content })
        assertEquals(listOf("I like PyTorch", "Exam on Friday"), db.memories().map { it.content })
        assertEquals(1, db.progress().answered)
        // sync metadata was backfilled: stable unique ids, timestamps, one local profile owning everything, all queued once
        val r = db.readableDatabase
        r.rawQuery("SELECT COUNT(*), COUNT(DISTINCT uid), MIN(updated_at) FROM messages", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)); assertEquals(3, c.getInt(1)); assertTrue(c.getLong(2) > 0) }
        r.rawQuery("SELECT COUNT(DISTINCT profile_id) FROM (SELECT profile_id FROM conversations UNION ALL SELECT profile_id FROM messages UNION ALL SELECT profile_id FROM memories)", null)
            .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        assertEquals(db.profileId(), db.profile().id)
        assertEquals(2 + 3 + 2, db.pendingCount())                                         // queued for the first sync (only if the user enables it)
        for (t in listOf("local_profiles", "linked_accounts", "devices", "user_preferences", "sync_metadata", "sync_conflicts", "model_registry", "document_metadata"))
            r.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(t)).use { c -> assertTrue(t, c.moveToFirst()) }
        assertNotNull(db.deviceId())
        // v5 (Abhify 3.0): old answers stay complete, no feedback; the new local-only tables exist and are empty
        assertTrue(db.messages(convs.last().id).all { it.status == null && it.feedback == null })
        assertTrue(convs.all { it.folder == null })
        for (t in listOf("library_docs", "flashcards", "study_plans", "plan_items", "workspaces", "notes", "tasks"))
            r.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> assertTrue(t, c.moveToFirst()); assertEquals(t, 0, c.getInt(0)) }
        r.rawQuery("SELECT COUNT(*) FROM resume_versions", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        r.rawQuery("SELECT pages, ocr_pages, warnings, role FROM library_docs LIMIT 0", null).use { }   // v6 columns exist
        for (t in listOf("job_descriptions", "job_applications", "interview_attempts", "study_sessions", "vault_items"))
            r.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> assertTrue(t, c.moveToFirst()); assertEquals(t, 0, c.getInt(0)) }
        r.rawQuery("SELECT fields FROM job_descriptions LIMIT 0", null).use { }                 // v8 column exists
        // v9 (Abhify 3.4): memories keep their text and become confirmed, chats are active, new tables are empty
        r.rawQuery("SELECT COUNT(*) FROM memories WHERE confirmed = 1 AND importance = 2", null).use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        r.rawQuery("SELECT COUNT(*) FROM conversations WHERE archived = 0", null).use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        r.rawQuery("SELECT sha256, source, indexed_at FROM library_docs LIMIT 0", null).use { }
        for (t in listOf("doc_chunks", "drive_files", "backups"))
            r.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> assertTrue(t, c.moveToFirst()); assertEquals(t, 0, c.getInt(0)) }
        assertEquals(2, db.search("gradient").size + db.search("transformers").size)        // search (full-text or fallback) finds both chats
        // v10 (Abhify 3.5): durable upload queue + scheduled-backup history columns, with defaults
        r.rawQuery("SELECT mode, attempts, session, mime, queued_at FROM drive_files LIMIT 0", null).use { }
        r.rawQuery("SELECT auto, uri, status, error FROM backups LIMIT 0", null).use { }
        // v11 (Abhify 3.6): optional chunk vectors, empty until an approved embedding model exists
        r.rawQuery("SELECT COUNT(*) FROM chunk_vectors", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        r.rawQuery("SELECT jd_id, score FROM interview_attempts LIMIT 0", null).use { }        // v12 (3.7)
        // v13 (3.8): memory usage columns start empty; the old memories are unchanged
        r.rawQuery("SELECT COUNT(*) FROM memories WHERE use_count = 0 AND last_used_at IS NULL", null).use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        r.rawQuery("SELECT COUNT(*) FROM memory_dismissed", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        r.rawQuery("SELECT due_at, interval_days, ease, lapses, last_reviewed FROM flashcards LIMIT 0", null).use { }   // v14 (3.9)
        r.rawQuery("SELECT COUNT(*) FROM card_reviews", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        r.rawQuery("SELECT COUNT(*) FROM workspace_links", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }   // v15 (4.0)
        r.rawQuery("SELECT account FROM drive_files LIMIT 0", null).use { }                       // v16 (4.2)
        r.rawQuery("SELECT COUNT(*) FROM saved_prompts", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }   // v17 (4.4)
        assertEquals(ai.abhify.android.chat.ChatDb.VERSION, r.version)                      // v2 -> ... -> current in one open
        db.close()
        ctx.deleteDatabase(name)
    }
}
