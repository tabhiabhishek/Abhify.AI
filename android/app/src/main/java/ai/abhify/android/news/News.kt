package ai.abhify.android.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

data class NewsItem(val title: String, val summary: String, val source: String, val url: String, val publishedAt: Long)
data class Feed(val name: String, val url: String)

/** RSS 2.0 and Atom parsing (pure, unit-tested on the PC with real feed snippets). */
object FeedParser {
    fun parse(input: InputStream, source: String, maxItems: Int = 8,
              p: XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()): List<NewsItem> {
        p.setInput(input, null)
        val out = mutableListOf<NewsItem>()
        var inItem = false
        var title = ""; var link = ""; var summary = ""; var date = ""
        while (p.next() != XmlPullParser.END_DOCUMENT && out.size < maxItems) {   // stop early: feeds can hold 1000+ items
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "item", "entry" -> { inItem = true; title = ""; link = ""; summary = ""; date = "" }
                    "title" -> if (inItem && title.isEmpty()) title = text(p)      // not a nested <author><title>
                    "link" -> if (inItem) {
                        val href = p.getAttributeValue(null, "href")
                        val rel = p.getAttributeValue(null, "rel")
                        if (href != null) { if (rel == null || rel == "alternate") link = href } else link = text(p).ifEmpty { link }
                    }
                    "description", "summary" -> if (inItem && summary.isEmpty()) summary = text(p)
                    "content" -> if (inItem && summary.isEmpty()) summary = text(p)
                    "pubDate", "published", "updated", "date" -> if (inItem && date.isEmpty()) date = text(p)
                }
                XmlPullParser.END_TAG -> if (p.name.substringAfter(':') in setOf("item", "entry") && inItem) {
                    inItem = false
                    val t = clean(title)
                    if (t.isNotEmpty() && link.startsWith("https://")) out += NewsItem(t, clean(summary).let { if (it.length > 280) it.take(277).trimEnd() + "…" else it },
                        source, link.trim(), parseDate(date))
                }
            }
        }
        return out
    }

    private fun text(p: XmlPullParser): String = runCatching { p.nextText() }.getOrDefault("")

    /** Strips HTML tags and entities from feed text. */
    fun clean(s: String): String = s.replace(Regex("(?s)<script.*?</script>|<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#8217;", "’").replace("&#8216;", "‘").replace("&#8220;", "“").replace("&#8221;", "”").replace("&#8230;", "…")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("\\s+"), " ").trim()

    fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(t.replace(Regex("\\s+(GMT|UTC)$"), " +0000"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0)
    }

    private fun words(t: String) = t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()

    /** Newest first, recent only, and one entry per story (different outlets often run the same one). */
    fun merge(lists: List<List<NewsItem>>, now: Long, maxAgeDays: Int = 21, limit: Int = 30): List<NewsItem> {
        val seen = mutableListOf<Set<String>>()
        return lists.flatten()
            .filter { it.publishedAt == 0L || now - it.publishedAt <= maxAgeDays * 86_400_000L }
            .sortedByDescending { it.publishedAt }
            .filter { item -> val w = words(item.title); (seen.none { (it intersect w).size >= minOf(4, w.size) }).also { if (it) seen += w } }
            .take(limit)
    }
}

/**
 * Online only: called when the News tab is opened or refreshed, never in the background and never by chat.
 * The last result is cached so the tab still shows something (labelled with its age) offline.
 */
class NewsRepository(private val feeds: List<Feed>, private val cacheFile: File) {
    data class Result(val items: List<NewsItem>, val fetchedAt: Long, val fromCache: Boolean, val failedFeeds: List<String>)

    companion object {
        fun feeds(json: String): List<Feed> = JSONObject(json).getJSONArray("feeds").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { f -> Feed(f.getString("name"), f.getString("url")) } }.filter { it.url.startsWith("https://") }
        }
    }

    /** Opens a feed; some servers send gzip even when not asked, so it's detected by its magic bytes. */
    fun <T> fetchFeed(f: Feed, read: (InputStream) -> T): T {
        val c = URL(f.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.useCaches = false                                           // a refresh always asks for the current feed
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("User-Agent", "Abhify-Android/0.1 (news reader)")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            BufferedInputStream(c.inputStream).use { raw ->
                raw.mark(2)
                val gz = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                return if (gz) GZIPInputStream(raw).use(read) else read(raw)
            }
        } finally { c.disconnect() }
    }

    fun cached(): Result? = runCatching {
        val o = JSONObject(cacheFile.readText())
        val a = o.getJSONArray("items")
        Result((0 until a.length()).map { a.getJSONObject(it).let { i ->
            NewsItem(i.getString("title"), i.getString("summary"), i.getString("source"), i.getString("url"), i.getLong("publishedAt")) } },
            o.getLong("fetchedAt"), true, emptyList())
    }.getOrNull()

    suspend fun refresh(): Result = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            feeds.map { f -> async { f.name to runCatching { fetch(f) }.getOrNull() } }.awaitAll()
        }
        val ok = results.mapNotNull { it.second }
        val failed = results.filter { it.second == null }.map { it.first }
        if (ok.isEmpty()) return@withContext cached()?.copy(failedFeeds = failed) ?: Result(emptyList(), 0, true, failed)
        val now = System.currentTimeMillis()
        val items = FeedParser.merge(ok, now)
        runCatching {
            cacheFile.writeText(JSONObject().put("fetchedAt", now).put("items", JSONArray(items.map {
                JSONObject().put("title", it.title).put("summary", it.summary).put("source", it.source).put("url", it.url).put("publishedAt", it.publishedAt)
            })).toString())
        }
        Result(items, now, false, failed)
    }

    private fun fetch(f: Feed): List<NewsItem> = fetchFeed(f) { FeedParser.parse(it, f.name) }
}
