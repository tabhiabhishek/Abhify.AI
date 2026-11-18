package ai.abhify.android.ui

import ai.abhify.android.news.ArticleReader
import ai.abhify.android.news.NewsIntel
import ai.abhify.android.news.NewsQuery
import ai.abhify.android.voice.Voice
import ai.abhify.android.news.NewsAi
import ai.abhify.android.news.NewsArticle
import ai.abhify.android.news.NewsCenter
import ai.abhify.android.news.NewsPrefs
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 4.6.2 News Center state. Strict separation: News reads and writes only its own cache (NewsCenter) and its own
 * preferences ("news_prefs"). AI on news runs through vm.aiTask (no chat history, no memories in or out); bookmarking is
 * an explicit user action that saves to Knowledge ("News" items), never to personal memory.
 */
class NewsState(private val vm: AppViewModel) {
    private val g = vm.g
    val category = mutableStateOf("foryou")
    val result = mutableStateOf<NewsCenter.Result?>(null)
    val loading = mutableStateOf(false)
    val query = mutableStateOf("")
    val searching = mutableStateOf(false)
    val prefs = mutableStateOf(loadPrefs())
    /** AI output shown in News (never saved anywhere): title, text, the articles it was made from, the source label. */
    data class AiOut(val title: String, val text: String, val articles: List<NewsArticle>, val source: String?, val error: String?)
    val ai = mutableStateOf<AiOut?>(null)
    val bookmarked = mutableStateListOf<String>()
    private var job: Job? = null
    private var loadSeq = 0
    private val lastLoad = HashMap<String, Long>()

    /** The outcome of the last explicit refresh ("Updated · 32 stories, 5 new" or why it failed and that the old list stays). */
    val refreshStatus = mutableStateOf<String?>(null)
    /** True only while a refresh the user asked for (button or pull) is running. */
    val refreshing = mutableStateOf(false)

    fun open(cat: String = category.value, force: Boolean = false) {
        category.value = cat; searching.value = false
        if (!force) refreshStatus.value = null
        val center = g.newsCenter
        if (result.value == null || result.value?.articles?.firstOrNull()?.category != cat) result.value = center.cached(NewsQuery.cacheKey(cat, prefs.value))
        val fresh = (System.currentTimeMillis() - (lastLoad[cat] ?: 0)) < 10 * 60_000
        if (fresh && !force && result.value != null) return               // no polling: at most every 10 min unless you refresh
        if (force && refreshing.value && job?.isActive == true) return       // one refresh at a time: a second pull or tap is ignored
        job?.cancel()
        val before = result.value?.articles?.map { it.id }?.toSet().orEmpty()
        val seq = ++loadSeq                                                   // 5.6: an older refresh can never replace a newer one
        job = vm.viewModelScope.launch {
            loading.value = true; refreshing.value = force
            try {
                val r = center.load(cat, prefs.value)
                if (seq != loadSeq) return@launch                                  // 5.6: a superseded (older) refresh is dropped
                if (category.value == cat && !searching.value) {                   // a late answer for another category is ignored
                    if (r.articles.isNotEmpty() || result.value == null) result.value = r   // a failed refresh keeps the stories on screen
                    if (force) refreshStatus.value = when {
                        r.fromCache || r.articles.isEmpty() -> r.problem ?: "Couldn't reach the news sources; the previous stories stay. Pull down to try again."
                        else -> "Updated just now · ${r.articles.size} stories, ${r.articles.count { it.id !in before }} new" +
                            (if (r.failed.isNotEmpty()) " · not reachable: ${r.failed.joinToString()}" else "")
                    }
                }
                if (!r.fromCache && r.articles.isNotEmpty()) lastLoad[cat] = System.currentTimeMillis()
            } finally { loading.value = false; refreshing.value = false }
        }
    }

    fun search(q: String) {
        query.value = q
        if (q.isBlank()) { searching.value = false; open(force = false); return }
        searching.value = true
        job?.cancel()
        job = vm.viewModelScope.launch {
            loading.value = true
            result.value = g.newsCenter.search(q, prefs.value)
            loading.value = false
        }
    }

    // ---------------------------------------------------------------- in-app reading (one story at a time)
    /** The story being read: its own text, summary and Read more stage (0 = summary, 1 = expanded). Never shared between stories. */
    data class Reading(val article: NewsArticle, val stage: Int = 0, val loading: Boolean = true, val paragraphs: List<String> = emptyList(),
                       val summary: String? = null, val note: String? = null, val error: String? = null, val source: String? = null) {
        val full get() = paragraphs.isNotEmpty()
    }
    val reading = mutableStateOf<Reading?>(null)
    private var readSeq = 0
    private val readerStore by lazy { ArticleReader.Store(java.io.File(g.context.cacheDir, "news/articles")) }
    private val READ_LABEL = "News: reading"

    /** Opens [a] inside the app (never the publisher's site). A summary still coming for a story you left is ignored. */
    fun openArticle(a: NewsArticle) {
        if (vm.speechOwner.value == "news") vm.stopSpeaking()
        if (vm.taskBusy.value?.startsWith(READ_LABEL) == true) vm.cancelTask()        // only this reader's own earlier summary
        val seq = ++readSeq
        reading.value = Reading(a)
        vm.viewModelScope.launch {
            val rec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readerStore.load(a.id) }
            val (paras, note) = rec?.takeIf { it.paragraphs.isNotEmpty() }?.let { it.paragraphs to it.note }   // a failed fetch is tried again
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ArticleReader.fetchParagraphs(a) }
            if (seq != readSeq) return@launch
            val full = paras.sumOf { it.length } >= ArticleReader.MIN_FULL_TEXT
            val text = if (full) paras.joinToString("\n\n") else a.summary
            val hash = ArticleReader.hash(a, text)
            val cached = rec?.summary?.takeIf { rec.hash == hash }                    // a changed text never reuses an old summary
            reading.value = Reading(a, paragraphs = if (full) paras else emptyList(), note = if (full) null else note, summary = cached, loading = cached == null)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readerStore.save(ArticleReader.Record(a.id, paras, hash, cached, note)) }
            if (cached != null) return@launch
            vm.aiTask("$READ_LABEL ${a.title.take(40)}", NewsAi.SYSTEM, ArticleReader.prompt(a, text, full, ArticleReader.DEVICE_TEXT),
                sensitive = null, maxDeviceChars = 4_200, userOnline = ArticleReader.prompt(a, text, full, ArticleReader.ARTICLE_CHARS)) { r ->
                if (seq != readSeq) return@aiTask                                     // you opened another story meanwhile
                val cur = reading.value ?: return@aiTask
                if (r.error != null || r.text.isBlank()) reading.value = cur.copy(loading = false, error = r.error ?: "The model returned an empty summary.")
                else {
                    reading.value = cur.copy(loading = false, summary = r.text.trim(), source = r.source?.label, error = null)
                    vm.viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { readerStore.save(ArticleReader.Record(a.id, paras, hash, r.text.trim(), note)) }
                }
            }
        }
    }
    fun retryArticle() { reading.value?.let { openArticle(it.article) } }
    /** Read more: the first tap expands (the story's own text, or says there is none); the second returns its link to open. */
    fun readMore(): String? {
        val r = reading.value ?: return null
        if (r.stage == 0) { reading.value = r.copy(stage = 1); return null }
        if (!ArticleReader.safeUrl(r.article.url)) { reading.value = r.copy(error = "This story has no valid https link to open."); return null }
        return r.article.url
    }
    fun closeArticle() {
        readSeq++
        if (vm.taskBusy.value?.startsWith(READ_LABEL) == true) vm.cancelTask()
        if (vm.speechOwner.value == "news") vm.stopSpeaking()
        reading.value = null
    }

    // ---------------------------------------------------------------- AI (optional; through aiTask only)
    private fun runAi(title: String, prompt: String, from: List<NewsArticle>) {
        if (vm.speechOwner.value == "news") vm.stopSpeaking()          // new content: the old one stops being read
        ai.value = AiOut(title, "", from, null, null)
        vm.aiTask("News: $title", NewsAi.SYSTEM, prompt, sensitive = null) { r ->
            ai.value = AiOut(title, r.text, from, r.source?.label, r.error)
        }
    }
    fun briefing() { val a = result.value?.articles.orEmpty().take(15); if (a.isNotEmpty()) runAi("Briefing from ${a.size} stories", NewsAi.briefing(a), a) }
    fun summarize(a: NewsArticle) = runAi("Summary", NewsAi.summarize(a), listOf(a))
    fun explain(a: NewsArticle) = runAi("Explained simply", NewsAi.explain(a), listOf(a))
    /** 5.3: AI translation (labelled as such; the publisher's original stays one tap away). */
    fun translate(a: NewsArticle) {
        val toHindi = !Voice.isHindi(a.title)
        runAi(if (toHindi) "AI translation into Hindi · not the publisher's text" else "AI translation into English · not the publisher's text",
            NewsAi.translate(a, if (toHindi) "Hindi" else "English"), listOf(a))
    }
    /** Next build: AI comparison of the same story from several publishers (labelled; headlines and descriptions only). */
    fun compare(group: List<NewsArticle>) = runAi("AI comparison of ${group.size} sources · from headlines and descriptions only",
        NewsIntel.comparePrompt(group), group)
    /** 5.3 feed language: "en", "hi" or "both" (publisher feeds and Google News); reloads the category. */
    fun setLanguage(code: String) { if (prefs.value.language == code) return; savePrefs(prefs.value.copy(language = code)); open(force = true) }

    /** Explicit, user-chosen save: a Knowledge item with the link (never personal memory). */
    fun bookmark(a: NewsArticle) {
        vm.life.save("news", a.title.take(200), "${a.publisher}\n${a.url}\n\n${a.summary}".trim(), "Notes", listOf("news", a.category), "news", null)
        bookmarked += a.id
    }

    // ---------------------------------------------------------------- source manager
    fun savePrefs(p: NewsPrefs) {
        prefs.value = p; lastLoad.clear()
        g.prefs.edit().putString("news_prefs", JSONObject().put("city", p.city).put("region", p.region).put("language", p.language)
            .put("forYou", JSONArray(p.forYou.toList())).put("prioritized", JSONArray(p.prioritized.toList())).put("hidden", JSONArray(p.hidden.toList()))
            .put("topics", JSONArray(p.topics)).toString()).apply()
        open(force = true)
    }
    private fun loadPrefs(): NewsPrefs = runCatching {
        val o = JSONObject(g.prefs.getString("news_prefs", null) ?: return NewsPrefs())
        fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        NewsPrefs(o.optString("city"), o.optString("region", "IN"), o.optString("language", "en"), set("forYou").toSet().ifEmpty { NewsPrefs().forYou },
            set("prioritized").toSet(), set("hidden").toSet(), set("topics"))
    }.getOrDefault(NewsPrefs())
}
