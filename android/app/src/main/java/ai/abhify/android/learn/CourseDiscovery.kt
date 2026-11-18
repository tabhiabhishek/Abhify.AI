package ai.abhify.android.learn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 5.3 Study → Discover: real learning resources for a topic, from sources verified on 2026-11-18 (see
 * docs/ABHIFY_STUDY_PLATFORM.md):
 *  - live, keyless APIs: MIT Learn (MIT OpenCourseWare courses, with MIT's own `free` / `certification` / `prices`
 *    fields), OpenStax (free textbooks). Wikibooks is a search link: Wikimedia blocks automated requests without contact
 *    details (2026-11-18); its parser stays for a future approved client identity;
 *  - the providers' own search pages (NPTEL, SWAYAM, freeCodeCamp, Khan Academy, Coursera, edX, Microsoft Learn,
 *    YouTube, MDN, Python docs). These are links, not results: availability and prices are shown on the provider's site.
 * Nothing is invented. Cost is shown only when the source's own data says so; otherwise "Check the provider".
 * Results open on the provider's site (no embedding, no copying of course content).
 */
object CourseDiscovery {
    enum class Cost(val label: String) {
        FREE("Free"), FREE_AUDIT("Free to audit · certificate paid"), PAID("Paid"), UNKNOWN("Check the provider")
    }
    enum class Kind(val label: String) { COURSE("Course"), TEXTBOOK("Textbook"), BOOK("Open book"), SEARCH("Search on provider") }

    data class Resource(val title: String, val provider: String, val url: String, val kind: Kind, val cost: Cost, val costNote: String?,
                        val level: String?, val topics: List<String>, val license: String?, val source: String)

    const val USER_AGENT = "Abhify.AI/5.3 (local study search)"
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    // ------------------------------------------------------------------ live APIs (URLs and parsers; fetching is done by the caller)
    fun mitUrl(topic: String, limit: Int = 10) =
        "https://api.learn.mit.edu/api/v1/learning_resources_search/?q=${enc(topic)}&resource_type=course&offered_by=ocw&limit=$limit"

    /** MIT Learn JSON → resources. Cost only from MIT's fields: free + no prices → Free; certification → noted; else Paid. */
    fun parseMit(json: String): List<Resource> = runCatching {
        val a = JSONObject(json).optJSONArray("results") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { r ->
            val url = r.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val prices = r.optJSONArray("prices")?.length() ?: 0
            val free = r.optBoolean("free", false)
            val cert = r.optBoolean("certification", false)
            val cost = when { free && prices == 0 && !cert -> Cost.FREE; free && cert -> Cost.FREE_AUDIT; !free && prices > 0 -> Cost.PAID; else -> Cost.UNKNOWN }
            val level = r.optJSONArray("runs")?.optJSONObject(0)?.optJSONArray("level")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
            val topics = r.optJSONArray("topics")?.let { t -> (0 until t.length()).mapNotNull { t.optJSONObject(it)?.optString("name") } }.orEmpty().take(3)
            val by = r.optJSONObject("offered_by")?.optString("name").orEmpty().ifBlank { "MIT" }
            Resource(r.optString("title").trim(), by, url, Kind.COURSE, cost, if (cert) "Certificate offered" else null, level, topics,
                if (by.contains("OpenCourseWare")) "CC BY-NC-SA 4.0" else null, "MIT Learn")
        }.filter { it.title.isNotBlank() }
    }.getOrDefault(emptyList())

    const val OPENSTAX_URL = "https://openstax.org/apps/cms/api/v2/pages/?type=books.Book&fields=title,book_state&limit=250"

    /** OpenStax book list → the live books whose title shares a word with the topic. */
    fun parseOpenStax(json: String, topic: String): List<Resource> = runCatching {
        val words = terms(topic)
        val a = JSONObject(json).optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("book_state") == "live" }.mapNotNull { b ->
            val title = b.optString("title"); val slug = b.optJSONObject("meta")?.optString("slug").orEmpty()
            if (slug.isBlank() || words.none { it in terms(title) }) null
            else Resource(title, "OpenStax", "https://openstax.org/details/books/$slug", Kind.TEXTBOOK, Cost.FREE, "Free online textbook", null, emptyList(), null, "OpenStax")
        }.take(5)
    }.getOrDefault(emptyList())

    fun wikibooksUrl(topic: String) = "https://en.wikibooks.org/w/api.php?action=query&list=search&srsearch=${enc(topic)}&format=json&srlimit=5&srnamespace=0"

    fun parseWikibooks(json: String): List<Resource> = runCatching {
        val a = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).optString("title") }.filter { it.isNotBlank() && '/' !in it }.map { t ->
            Resource(t, "Wikibooks", "https://en.wikibooks.org/wiki/" + t.replace(' ', '_'), Kind.BOOK, Cost.FREE, "Open book", null, emptyList(), "CC BY-SA", "Wikibooks")
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ provider search links (pages checked to load on 2026-11-18)
    data class Provider(val name: String, val about: String, val searchUrl: (String) -> String)

    val PROVIDERS = listOf(
        Provider("NPTEL", "IIT/IISc video courses") { "https://nptel.ac.in/courses" },
        Provider("SWAYAM", "Indian university courses") { "https://swayam.gov.in/explorer?searchText=${enc(it)}" },
        Provider("freeCodeCamp", "Coding tutorials and curriculum") { "https://www.freecodecamp.org/news/search/?query=${enc(it)}" },
        Provider("Khan Academy", "Maths, science and computing lessons") { "https://www.khanacademy.org/search?page_search_query=${enc(it)}" },
        Provider("Coursera", "University courses (free and paid)") { "https://www.coursera.org/search?query=${enc(it)}" },
        Provider("edX", "University courses (free and paid)") { "https://www.edx.org/search?q=${enc(it)}" },
        Provider("Microsoft Learn", "Official technical training") { "https://learn.microsoft.com/en-us/search/?terms=${enc(it)}" },
        Provider("MIT OpenCourseWare", "All OCW material") { "https://ocw.mit.edu/search/?q=${enc(it)}" },
        Provider("Wikibooks", "Free open books (CC BY-SA)") { "https://en.wikibooks.org/w/index.php?search=${enc(it)}" },
        Provider("YouTube", "Educational videos") { "https://www.youtube.com/results?search_query=${enc("$it course")}" },
        Provider("MDN Web Docs", "Official web documentation") { "https://developer.mozilla.org/en-US/search?q=${enc(it)}" },
        Provider("Python docs", "Official Python documentation") { "https://docs.python.org/3/search.html?q=${enc(it)}" },
    )

    fun providerLinks(topic: String): List<Resource> = PROVIDERS.map { p ->
        Resource("Search ${p.name} for \"${topic.trim()}\"", p.name, p.searchUrl(topic), Kind.SEARCH, Cost.UNKNOWN,
            "${p.about} · availability and prices are shown on ${p.name}", null, emptyList(), null, "provider search page")
    }

    /** Combines live results (deduplicated by URL) with provider links; "free only" keeps only resources whose source says Free. */
    fun combine(live: List<Resource>, topic: String, freeOnly: Boolean, level: String? = null): List<Resource> {
        val l = live.distinctBy { it.url }.filter { !freeOnly || it.cost == Cost.FREE }
            .filter { level == null || it.level == null || it.level.equals(level, ignoreCase = true) }
        return l + if (freeOnly) emptyList() else providerLinks(topic)
    }

    private val STOP = setOf("and", "the", "for", "of", "to", "in", "a", "an", "with", "intro", "introduction")
    fun terms(s: String) = Regex("[\\p{L}\\p{N}+#]{2,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()
}
