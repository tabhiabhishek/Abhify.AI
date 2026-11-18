package ai.abhify.android

import ai.abhify.android.chat.SchemaV16
import ai.abhify.android.cloud.ServerConnection
import ai.abhify.android.cloud.ServerConnection.Check
import ai.abhify.android.cloud.ServerConnection.Mode
import ai.abhify.android.device.SetupPolicy
import ai.abhify.android.drive.DriveAccounts
import ai.abhify.android.drive.DriveApi
import ai.abhify.android.drive.DriveErrors
import ai.abhify.android.drive.DriveMigration
import ai.abhify.android.drive.DriveQueue
import ai.abhify.android.drive.DriveStatus
import ai.abhify.android.ai.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DriveCentralFolderTest {
    private fun api(f: FakeDrive, cache: MutableMap<String, String> = HashMap()) = DriveApi(f, { "t" }, cache)
    private fun folders(f: FakeDrive) = f.files.values.filter { it.mime.endsWith("folder") && !it.trashed }

    @Test fun rootIsMyDriveAbhifyAiReusedNeverDuplicated() {
        val f = FakeDrive()
        f.files["decoy"] = FakeDrive.F("decoy", "Abhify.AI", "application/vnd.google-apps.folder", "someOtherFolder", ByteArray(0))   // same name elsewhere
        val root = api(f).folder(null)
        assertTrue(root != "decoy"); assertEquals("root", f.files.getValue(root).parent)                     // directly in My Drive
        assertEquals(root, api(f).folder(null)); assertEquals(root, api(f).findRoot())                        // reused by a fresh instance
        val subs = DriveApi.FOLDERS.map { api(f).folder(it) }
        assertEquals(4, subs.toSet().size); assertTrue(subs.all { f.files.getValue(it).parent == root })    // Backups, Documents, Imports, Exports under the root
        DriveApi.FOLDERS.forEach { api(f).folder(it) }
        assertEquals(1 + 1 + 4, folders(f).size)                                                             // decoy + root + 4, nothing duplicated
    }

    @Test fun movedOrDeletedFoldersAreDetectedAndLegacyNamesMap() {
        val f = FakeDrive(); val cache = HashMap<String, String>(); val a = api(f, cache)
        val backups = a.folder("Backups")
        assertEquals(backups, a.folder("Application Backups")); assertEquals(backups, a.folder("Memory Backups"))   // 3.4–4.1 names → new layout
        f.files[backups] = f.files.getValue(backups).copy(parent = "elsewhere")                                   // user moved it out of Abhify.AI
        val again = a.folder("Backups"); assertTrue(again != backups); assertEquals(a.folder(null), f.files.getValue(again).parent)
        f.files.getValue(a.folder(null)).trashed = true                                                            // root deleted
        val newRoot = a.folder(null); assertFalse(f.files.getValue(newRoot).trashed)
        assertNull(api(FakeDrive()).findRoot())                                                                    // status check never creates
    }

    @Test fun routingAccountAndMoveKeepIds() {
        assertEquals("Backups", DriveApi.folderFor("backup")); assertEquals("Backups", DriveApi.folderFor("backup-auto"))
        assertEquals("Documents", DriveApi.folderFor("doc")); assertEquals("Imports", DriveApi.folderFor("file"))
        assertEquals("Exports", DriveApi.folderFor("conversation")); assertEquals("Exports", DriveApi.folderFor("notes"))
        assertEquals("https://drive.google.com/drive/folders/abc", DriveApi.folderUrl("abc"))
        val f = FakeDrive(); val a = api(f)
        assertEquals(DriveApi.Account("perm-1", "tester@example.com", "Tester"), a.account())
        val up = a.upload("Application Backups", "old.json", "application/json", byteArrayOf(1)) as DriveApi.Upload.Done
        assertEquals(a.folder("Backups"), f.files.getValue(up.item.id).parent)                                     // legacy name still lands in Backups
        val exports = a.folder("Exports")
        a.move(up.item.id, exports, a.folder("Backups"))
        assertEquals(exports, f.files.getValue(up.item.id).parent); assertTrue(f.files.containsKey(up.item.id))   // same id, new place
    }

    @Test fun migrationProposesOnlyAbhifyFilesFromOlderFolders() {
        fun item(id: String, mime: String = "application/json") = DriveApi.Item(id, "$id.json", mime, 1, null, null)
        val plan = DriveMigration.plan(mapOf(
            "Application Backups" to ("f1" to listOf(item("b1"), item("sub", "application/vnd.google-apps.folder"))),
            "Archived Conversations" to ("f2" to listOf(item("c1"))),
            "Holiday photos" to ("f3" to listOf(item("p1")))))                                                   // not an Abhify folder: left alone
        assertEquals(listOf("b1" to "Backups", "c1" to "Exports"), plan.map { it.item.id to it.toFolder })
        assertEquals("f1", plan.first().fromId)
        val r = DriveMigration.Result(plan.take(1), listOf(plan[1] to "403"))
        assertTrue(r.summary.contains("1 moved") && r.summary.contains("left where they were"))
    }
}

class DriveAccountTest {
    @Test fun uploadsNeverGoToAnotherAccount() {
        assertTrue(DriveAccounts.mayUpload("A", "A")); assertTrue(DriveAccounts.mayUpload(null, "A"))           // pre-4.2 row: adopted
        assertFalse(DriveAccounts.mayUpload("A", "B")); assertFalse(DriveAccounts.mayUpload("A", null))
        assertEquals("drive_folders:A", DriveAccounts.folderKey("A"))
        assertEquals(1, DriveAccounts.waitingForOthers(listOf("A" to DriveStatus.PENDING_UPLOAD, "B" to DriveStatus.PENDING_UPLOAD, "B" to DriveStatus.SYNCED, null to DriveStatus.PENDING_UPLOAD), "A"))
        assertEquals("Tester", DriveAccounts.label(null, "Tester"))

        val fake = FakeDrive(); val q = MemQueue(); val dq = DriveQueue(q)
        val mine = q.add("mine.pdf", byteArrayOf(1)).let { it.copy(account = "perm-1").also(q::save) }
        val theirs = q.add("theirs.pdf", byteArrayOf(2)).let { it.copy(account = "perm-2").also(q::save) }
        val old = q.add("old.pdf", byteArrayOf(3))
        val s = dq.runAll(DriveApi(fake, { "t" }), account = "perm-1")
        assertEquals(setOf(mine.id, old.id), s.synced.map { it.id }.toSet())
        assertEquals(DriveStatus.PENDING_UPLOAD, q.rows[theirs.id]!!.status); assertTrue(fake.files.values.none { it.name == "theirs.pdf" })
        assertEquals("perm-1", q.rows[old.id]!!.account)                                                           // adopted by the connected account
    }

    @Test fun signInErrorsAreClear() {
        assertEquals(DriveErrors.Kind.CANCELLED, DriveErrors.kind(12501)); assertEquals(DriveErrors.Kind.CANCELLED, DriveErrors.kind(16))
        assertEquals(DriveErrors.Kind.NOT_CONFIGURED, DriveErrors.kind(10)); assertEquals(DriveErrors.Kind.NETWORK, DriveErrors.kind(7))
        assertEquals(DriveErrors.Kind.DENIED, DriveErrors.kind(4)); assertEquals(DriveErrors.Kind.PLAY_SERVICES, DriveErrors.kind(2))
        assertTrue(DriveErrors.message(10).contains("developer configuration") && !DriveErrors.message(10).contains("Cloud Console"))   // users aren't asked to set up Google Cloud
        assertTrue(DriveErrors.message(12501).contains("Nothing changed"))
    }
}

class LocalFirstServerTest {
    @Test fun noServerMeansLocalModeNotAnError() {
        assertEquals(Mode.LOCAL_MODE, ServerConnection.mode("", true, null, false))
        assertEquals(Mode.LOCAL_MODE, ServerConnection.mode(null, true, false, false))
        assertEquals(Mode.LOCAL_MODE, ServerConnection.mode("https://s", enabled = false, lastCheckOk = true, signedIn = false))   // disconnected = local
        assertEquals(Mode.SERVER_CONFIGURED, ServerConnection.mode("https://s", true, null, false))
        assertEquals(Mode.SERVER_CONNECTED, ServerConnection.mode("https://s", true, true, false))
        assertEquals(Mode.SERVER_DISCONNECTED, ServerConnection.mode("https://s", true, false, false))
        assertEquals(Mode.SERVER_AUTHENTICATED, ServerConnection.mode("https://s", true, false, true))
    }

    @Test fun addressesAreValidatedWithoutDowngrades() {
        assertTrue(ServerConnection.validate("", true) is Check.Invalid)
        assertTrue((ServerConnection.validate("abhify.example.com", true) as Check.Invalid).reason.contains("https://abhify.example.com"))
        assertEquals(Check.Ok("https://abhify.example.com"), ServerConnection.validate(" https://abhify.example.com/ ", false))
        assertTrue(ServerConnection.validate("http://abhify.example.com", true) is Check.Invalid)                    // never plain http to the internet
        assertTrue(ServerConnection.validate("http://10.0.2.2:8000", debug = false) is Check.Invalid)               // release: https only
        assertTrue((ServerConnection.validate("http://10.0.2.2:8000", debug = true) as Check.Ok).note!!.contains("emulator"))
        assertTrue((ServerConnection.validate("http://127.0.0.1:8000", true) as Check.Ok).note!!.contains("phone itself"))
        assertTrue((ServerConnection.validate("http://192.168.1.20:8000", true) as Check.Invalid).reason.contains("adb reverse"))
        assertTrue(ServerConnection.validate("https://s.example?x=1", true) is Check.Invalid)
        assertTrue(ServerConnection.validate("ftp://s.example", true) is Check.Invalid)
    }

    @Test fun healthIsInterpretedHonestly() {
        assertTrue(ServerConnection.interpret(200, """{"status":"ok","service":"abhify-cloud","version":"1.0.0-dev.1","ai_available":false}""").ok)
        assertTrue(ServerConnection.interpret(null, null).message.contains("continue using local features"))
        assertFalse(ServerConnection.interpret(200, """{"status":"ok"}""").ok)                                     // some other service
        assertFalse(ServerConnection.interpret(200, "<html>").ok); assertFalse(ServerConnection.interpret(404, null).ok); assertFalse(ServerConnection.interpret(503, null).ok)
        assertTrue(ServerConnection.interpret(200, """{"service":"abhify-cloud","version":"0.9"}""").message.contains("isn't supported"))
    }

    @Test fun setupAndStartupNeedNoServerAndDriveIsIndependent() {
        AiMode.entries.forEach { m -> assertTrue(SetupPolicy.Step.DRIVE in SetupPolicy.steps(m)) }                  // optional Drive step, no server step
        assertTrue(SetupPolicy.Step.entries.none { it.name.contains("SERVER") })
        val main = File("src/main/java/ai/abhify/android")
        val all = main.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(main).path.replace('\\', '/') to it.readText() }
        assertTrue(all.values.none { Regex("[A-Za-z0-9._%+-]+@gmail\\.com").containsMatchIn(it) })                // no hard-coded Gmail address
        assertTrue(all.values.none { it.contains("client_secret") || it.contains("GOCSPX") })
        for ((k, v) in all.filterKeys { it.startsWith("drive/") }) assertFalse(k, v.contains("cloud.session") || v.contains("CloudClient") || v.contains("serverUrl"))
        assertFalse(all.getValue("AppGraph.kt").contains("health("))                                               // no server check at startup
        val welcome = all.getValue("ui/OnboardingScreens.kt")
        assertTrue(welcome.contains("if (vm.g.serverUrl.isNotBlank() && vm.serverEnabled.value) {"))                 // sign-in only with a server
        assertTrue(File("src/main/res/xml/network_security_config.xml").readText().let { !it.contains("domain-config") && it.contains("cleartextTrafficPermitted=\"false\"") })
        val dbg = File("src/debug/res/xml/network_security_config.xml").readText()
        assertEquals(listOf("10.0.2.2", "127.0.0.1", "localhost"), Regex("<domain[^>]*>([^<]+)</domain>").findAll(dbg).map { it.groupValues[1] }.toList())
    }

    @Test fun schemaV16KeepsQueueRows() {
        currentDb().use { c ->
            c.exec("INSERT INTO drive_files (kind, local_ref, name, folder, status, updated_at) VALUES ('backup', 'x', 'b.json', 'Application Backups', 'pending-upload', 1)")
            c.createStatement().use { st -> SchemaV16.apply { st.execute(it) } }
            assertEquals("b.json", c.one("SELECT name FROM drive_files")); assertNull(c.one("SELECT account FROM drive_files"))
        }
        assertTrue(File("src/main/java/ai/abhify/android/chat/ChatDb.kt").readText().contains("if (oldVersion < 16) SchemaV16.apply"))
    }
}
