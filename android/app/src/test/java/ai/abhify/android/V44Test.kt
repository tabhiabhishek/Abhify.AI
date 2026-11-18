package ai.abhify.android

import ai.abhify.android.backup.Backup
import ai.abhify.android.backup.BackupIo
import ai.abhify.android.career.ChatPdf
import ai.abhify.android.chat.SchemaV16
import ai.abhify.android.chat.SchemaV17
import ai.abhify.android.settings.Personalization.Creativity
import ai.abhify.android.settings.Personalization.TextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V44FeaturesTest {
    private val main = File("src/main/java/ai/abhify/android")
    private fun src(f: String) = File(main, f).readText()

    @Test fun personalizationDefaultsKeepTheOldBehaviour() {
        assertEquals(Creativity.BALANCED, Creativity.of(null)); assertEquals(0.3f, Creativity.BALANCED.temperature, 0f)   // same as before 4.4
        assertTrue(Creativity.PRECISE.temperature < Creativity.BALANCED.temperature && Creativity.CREATIVE.temperature <= 0.7f)
        assertEquals(TextSize.DEFAULT, TextSize.of("nonsense")); assertEquals(1f, TextSize.DEFAULT.scale, 0f)
        assertTrue(src("ai/HybridAi.kt").contains("temperature = deviceTemperature()"))
        assertTrue(src("AppGraph.kt").contains("Creativity.of(prefs.getString(\"creativity\", null)).temperature"))
        assertTrue(src("ui/MainActivity.kt").contains("dens.fontScale * vm.textSize.value.scale"))                       // on top of the system font size
        assertTrue(Backup.SETTINGS_WHITELIST.containsAll(listOf("creativity", "text_size")))
    }

    @Test fun savedPromptsMigrateAndBackUp() {
        currentDb().use { c ->
            c.createStatement().use { st -> SchemaV16.apply { st.execute(it) }; SchemaV17.apply { st.execute(it) } }
            c.exec("INSERT INTO saved_prompts (title, body, created_at, updated_at) VALUES ('Beginner', 'Explain simply with one example', 1, 1)")
            val bytes = Backup.encode(BackupIo.export(JdbcRows(c), listOf("Notes and tasks"), emptyMap()), listOf("Notes and tasks"), "4.4.0")
            val dst = currentDb(); dst.createStatement().use { st -> SchemaV16.apply { st.execute(it) }; SchemaV17.apply { st.execute(it) } }
            val parsed = Backup.decode(bytes)
            BackupIo.restore(JdbcRows(dst), parsed, setOf("saved_prompts"))
            BackupIo.restore(JdbcRows(dst), parsed, setOf("saved_prompts"))                                                 // twice: still one (additive)
            assertEquals(1, dst.one("SELECT COUNT(*) FROM saved_prompts")); assertEquals(0, dst.one("SELECT use_count FROM saved_prompts"))
            dst.close()
        }
        assertTrue(src("chat/ChatDb.kt").contains("if (oldVersion < 17) SchemaV17.apply"))
    }

    @Test fun editingAnEarlierQuestionNeverChangesTheOriginalChat() {
        val vm = src("ui/AppViewModel.kt")
        val branch = vm.substringAfter("fun editAsBranch(").substringBefore("\n    fun editAndResend(")
        assertTrue(branch.contains("g.db.newConversation(\"\$title (edited)\")") && branch.contains("messages.take(index)"))
        assertFalse(branch.contains("deleteMessage") || branch.contains("removeAt"))                                       // nothing removed from the original
        assertTrue(branch.contains("privateChat.value"))                                                                    // private chats aren't copied to storage
        val home = src("ui/HomeScreens.kt")
        assertTrue(home.contains("vm.editAsBranch(b, input)") && home.contains("it will be sent in a new chat; this chat stays as it is"))
    }

    @Test fun pdfExportLinesAndViewerLimits() {
        val l = ChatPdf.lines("# Title\nSome **bold** text\n- [x] done\n  - nested\n```\ncode\n```\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(listOf("Title", "Some bold text", "☑ done", "• nested", "code", "a  |  b", "1  |  2"), l.map { it.text })
        assertTrue(l[0].bold && l[4].mono && l[3].indent > l[2].indent)
        val viewer = src("ui/V44Screens.kt")
        assertTrue(viewer.contains(".coerceAtMost(2200)") && viewer.contains("lock.withLock"))                              // memory cap; one page open at a time
        assertTrue(viewer.contains("SecurityException"))                                                                   // password-protected PDFs: clear message
    }

    @Test fun vectorIconsAreTheOfficialMaterialOnesWithLicence() {
        val res = File("src/main/res/drawable")
        for (n in listOf("chat", "work_outline", "school", "description", "folder_open", "menu", "search", "settings", "arrow_back", "lightbulb")) {
            val f = File(res, "ic_$n.xml"); assertTrue(n, f.exists())
            assertTrue(n, f.readText().contains("Apache License 2.0") && f.readText().contains("android:pathData="))
        }
        val home = src("ui/HomeScreens.kt")
        // 4.5: the header menu and gear moved into the More tab; the header keeps vector icons (search, new chat, back)
        assertTrue(home.contains("IconAction(ai.abhify.android.R.drawable.ic_search, \"Search everything\"") && home.contains("IconAction(ai.abhify.android.R.drawable.ic_add, \"New chat\""))
        assertTrue(src("ui/Components.kt").contains("IconAction(R.drawable.ic_arrow_back, \"Back\", onBack)"))
    }
}
