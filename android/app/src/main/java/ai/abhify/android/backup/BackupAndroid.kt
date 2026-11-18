package ai.abhify.android.backup

import ai.abhify.android.AppGraph
import ai.abhify.android.chat.archiveBytes
import ai.abhify.android.chat.libraryBytes
import ai.abhify.android.chat.memoryBytes
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** [Rows] over the app's SQLite database (same code path as the JVM tests use with sqlite-jdbc). */
class AndroidRows(private val db: SQLiteDatabase) : Rows {
    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            generateSequence { if (c.moveToNext()) (0 until c.columnCount).associate { i -> c.getColumnName(i) to value(c, i) } else null }.toList()
        }
    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> null                                                                        // no blobs in exported tables
        else -> c.getString(i)
    }
    override fun insert(table: String, values: Map<String, Any?>): Long = db.insertOrThrow(table, null, ContentValues().apply {
        values.forEach { (k, v) -> when (v) { null -> putNull(k); is Long -> put(k, v); is Int -> put(k, v); is Double -> put(k, v); is Boolean -> put(k, if (v) 1 else 0); else -> put(k, v.toString()) } }
    })
    override fun columns(table: String): Set<String> = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(1) else null }.toSet()
    }
    override fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { val r = block(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() } }
}

/** Backup tables for [categories], plus the app's JSON files when "App files" is chosen (manual and scheduled backups). */
fun backupTables(g: AppGraph, categories: List<String>, settings: Map<String, Any?>): Map<String, org.json.JSONArray> {
    val out = LinkedHashMap(BackupIo.export(AndroidRows(g.db.readableDatabase), categories, settings))
    if ("App files" in categories) {
        val keys = workspaceKeys(g)
        out[AppFiles.TABLE] = AppFiles.export(g.context.filesDir) { id -> keys.entries.firstOrNull { it.value == id }?.key }
    }
    return out
}

/** Restores the app's JSON files from [p] (only files this phone doesn't have). Returns a short result line or null. */
fun restoreAppFiles(g: AppGraph, p: Backup.Parsed): String? {
    val rows = p.tables[AppFiles.TABLE] ?: return null
    val keys = workspaceKeys(g)                                             // after the tables were restored: new workspace ids
    val (w, s) = AppFiles.restore(g.context.filesDir, rows) { keys[it] }
    return "App files restored: ${w.ifEmpty { listOf("none") }.joinToString()}" + if (s.isNotEmpty()) " (kept this phone's ${s.joinToString()})" else ""
}

/** Workspace "name|kind" → id (how project settings are matched across backups). */
private fun workspaceKeys(g: AppGraph): Map<String, Long> =
    g.db.readableDatabase.rawQuery("SELECT id, name, kind FROM workspaces", null).use { c ->
        generateSequence { if (c.moveToNext()) "${c.getString(1)}|${c.getString(2)}" to c.getLong(0) else null }.toMap()
    }

/** What Abhify stores on this phone, measured (bytes). Nothing is deleted here except the temporary cache on request. */
object Storage {
    data class Report(val database: Long, val archive: Long, val memory: Long, val documents: Long, val driveDownloads: Long, val models: Long, val cache: Long, val total: Long)

    fun size(f: File?): Long = when { f == null || !f.exists() -> 0; f.isFile -> f.length(); else -> f.listFiles()?.sumOf { size(it) } ?: 0 }

    fun measure(g: AppGraph): Report {
        val ctx = g.context
        val dbFile = ctx.getDatabasePath("abhify.db")
        val database = listOf("", "-wal", "-shm", "-journal").sumOf { size(File(dbFile.path + it)) }
        val models = size(File(ctx.noBackupFilesDir, "models"))
        val drive = size(File(ctx.filesDir, "drive"))
        val cache = size(ctx.cacheDir)
        val total = size(ctx.filesDir) + size(ctx.noBackupFilesDir) + size(ctx.cacheDir) + size(File(ctx.applicationInfo.dataDir, "databases")) +
            size(File(ctx.applicationInfo.dataDir, "shared_prefs"))
        return Report(database, g.db.archiveBytes(), g.db.memoryBytes(), g.db.libraryBytes(), drive, models, cache, total)
    }

    /** Deletes only temporary files (exports for sharing, OCR scratch). Never the model, database or documents. */
    fun clearCache(g: AppGraph): Long { val before = size(g.context.cacheDir); g.context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; return before - size(g.context.cacheDir) }

    fun human(b: Long): String = when { b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble()); b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1024 -> "%.0f KB".format(b / 1024.0); else -> "$b B" }
}
