package ai.abhify.android.ui

import ai.abhify.android.R
import ai.abhify.android.news.NewsArticle
import ai.abhify.android.news.NewsPrefs
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which colour identity each news category wears (data-driven categories, one mapping here). */
private fun newsAccent(id: String): Accent = when (id) {
    "foryou", "ai" -> Accent.CHAT; "local", "india" -> Accent.CAREER; "world", "technology" -> Accent.DOCS; "business", "startups" -> Accent.KNOWLEDGE
    "sports", "health", "environment" -> Accent.STUDY; "science", "education" -> Accent.SYSTEM; else -> Accent.MEMORY
}

/**
 * 4.6.2 News Center: header with the briefing, search, categories (from news_sources.json), a featured story, then
 * article cards with the publisher's image, publisher, publication time, the source's description, Read more (the
 * original link), Summarize / Explain (AI, labelled, never saved), Bookmark (explicit, to Knowledge) and Share.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsTab(vm: AppViewModel) {
    val n = vm.newsCenter
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { n.open() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (vm.speechOwner.value == "news") vm.stopSpeaking() } }   // leaving News ends its read-aloud
    var q by rememberSaveable { mutableStateOf(n.query.value) }
    var settings by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<NewsArticle?>(null) }
    var relatedFor by remember { mutableStateOf<NewsArticle?>(null) }
    fun read(a: NewsArticle) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
    fun share(a: NewsArticle) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${a.title}\n${a.url}"), "Share"))
    val r = n.result.value
    val cats = vm.g.newsCenter.catalog.categories
    fun refresh() { if (n.searching.value) n.search(q) else n.open(force = true) }
    Column(Modifier.fillMaxSize()) {
        if (n.loading.value && !n.refreshing.value) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Accent.DOCS.g2, trackColor = Color.Transparent)
        // pull down = refresh this category (stories only: no AI briefing is made and Listen isn't touched)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(n.refreshing.value, ::refresh, Modifier.fillMaxSize()) {
        PageBody(gap = Dimens.gap) {
            PageHeader("News", when {
                r == null || r.retrievedAt == 0L -> if (n.loading.value) "Getting the latest stories…" else "Latest stories from the publishers"
                r.fromCache -> "Saved ${ago(r.retrievedAt)} · offline copy"
                else -> "Updated ${ago(r.retrievedAt)}"
            }, Accent.DOCS, "📰") {
                IconAction(R.drawable.ic_refresh, "Refresh news", ::refresh)
                IconAction(R.drawable.ic_tune, "News sources and region", { settings = true })
            }
            n.refreshStatus.value?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall,
                color = if (s.startsWith("Updated")) LocalExtra.current.ok else LocalExtra.current.warn,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            // 5.3 feed language: English, Hindi or both (publisher feeds + Google News in that language)
            val langs = listOf("en" to "English", "hi" to "हिन्दी", "both" to "Both")
            SegmentedTabs(langs.map { it.second }, langs.indexOfFirst { it.first == n.prefs.value.language }.coerceAtLeast(0), accent = Accent.DOCS) { n.setLanguage(langs[it].first) }
            SearchField(q, { q = it }, "Search news: a company, a publisher, a city…")
            if (q.isNotBlank() && q != n.query.value) GradientButton("Search news for \"${q.take(40)}\"", { n.search(q) }, Modifier.fillMaxWidth())
            if (n.searching.value) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Results for \"${n.query.value}\" · last 7 days, newest first; check each story's date", style = MaterialTheme.typography.bodySmall,
                    color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
                TextButton({ q = ""; n.search("") }) { Text("Clear") }
            } else SegmentedTabs(cats.map { it.label(n.prefs.value.language) }, cats.indexOfFirst { it.id == n.category.value }.coerceAtLeast(0),
                glyphs = cats.map { it.glyph }, accent = newsAccent(n.category.value)) { n.open(cats[it].id) }
            // AI briefing (optional): from the stories shown right now, with their source numbers
            if (!r?.articles.isNullOrEmpty()) AccentCard(Accent.CHAT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiOrb(if (vm.taskBusy.value?.startsWith("News") == true) OrbState.THINKING else OrbState.IDLE, 36); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI briefing", style = MaterialTheme.typography.titleSmall)
                        Text("A short summary of the ${minOf(15, r!!.articles.size)} stories shown, with source links", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                    }
                    TextButton({ n.briefing() }, enabled = vm.taskBusy.value == null && !vm.generating.value) { Text("Create") }
                }
            }
            n.ai.value?.let { out -> AiOutCard(out, vm, onRead = ::read) { if (vm.speechOwner.value == "news") vm.stopSpeaking(); n.ai.value = null } }
            r?.problem?.let { p -> if (r.articles.isEmpty()) ErrorBanner(p, onRetry = { n.open(force = true) }) }
            if (r != null && r.failed.isNotEmpty() && r.articles.isNotEmpty()) InfoNote("Not reachable right now: ${r.failed.joinToString(", ")}.")
            if (r != null && r.articles.isEmpty() && r.problem == null && !n.loading.value)
                EmptyState("📰", if (n.searching.value) "No stories found" else "No stories right now", "Try another category or search, or refresh.")
            if (r == null && n.loading.value) repeat(3) { Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(Brand.Radius)).background(LocalExtra.current.glass)) }
            val list = r?.articles.orEmpty()
            // a tap on a story opens it inside the app; the publisher's site opens only from the reader's second Read more
            // 5.6: every card is keyed by its article id, so a refresh never reuses one story's card (and image) for another
            list.firstOrNull { it.imageUrl != null }?.let { f -> androidx.compose.runtime.key(f.id) { FeaturedCard(f, onClick = { n.openArticle(f) }, onMore = { actionsFor = f }) } }
            // 5.5 duplicate-story detection: how many other publishers among the loaded stories cover the same story
            val coverage = androidx.compose.runtime.remember(list) { ai.abhify.android.news.NewsIntel.clusters(list).flatMap { g -> g.map { it.id to g.size - 1 } }.toMap() }
            list.filter { it.id != list.firstOrNull { a -> a.imageUrl != null }?.id }.forEach { a -> androidx.compose.runtime.key(a.id) { ArticleCard(a, coverage[a.id] ?: 0, onClick = { n.openArticle(a) }, onMore = { actionsFor = a }) } }
            if (list.isNotEmpty()) InfoNote("Headlines and descriptions come from each publisher's feed (or Google News); times are their publication times. " +
                "Read more opens the original story. News is never added to your personal memory.")
        }
        }
    }
    actionsFor?.let { a ->
        ActionSheet(a.title, "${a.publisher}" + (if (a.publishedAt > 0) " · published ${ago(a.publishedAt)}" else "") + if (a.provider == "google-news") " · via Google News" else "", listOf(
            null to listOf(SheetAction("📄", "Read in the app") { n.openArticle(a) }, SheetAction("🔗", "Open the original at ${a.publisher}") { read(a) }),
            "AI (labelled, not saved)" to listOf(
                SheetAction("📝", "Summarize", enabled = vm.taskBusy.value == null) { n.summarize(a) },
                SheetAction("💡", "Explain for a beginner", enabled = vm.taskBusy.value == null) { n.explain(a) },
                SheetAction("🔀", if (ai.abhify.android.voice.Voice.isHindi(a.title)) "Translate into English (AI)" else "Translate into Hindi (AI)",
                    enabled = vm.taskBusy.value == null) { n.translate(a) }),
            // next build: news intelligence over the stories already loaded (no new requests)
            "Explore" to listOfNotNull(
                SheetAction("🔗", "Related stories") { relatedFor = a },
                ai.abhify.android.news.NewsIntel.clusters(r?.articles.orEmpty()).firstOrNull { g -> g.any { it.id == a.id } }?.let { group ->
                    SheetAction("📊", "Compare ${group.size} sources (AI)", enabled = vm.taskBusy.value == null) { n.compare(group) } },
                SheetAction("🎓", "Learn this topic") { vm.home(); vm.tab.value = AppViewModel.Tab.STUDY; vm.learnMode.value = 7
                    vm.discover.search(ai.abhify.android.news.NewsIntel.learnTopic(a.title)) }),
            "Keep" to listOf(
                SheetAction("🔖", if (a.id in n.bookmarked) "Bookmarked in Knowledge" else "Bookmark to Knowledge", enabled = a.id !in n.bookmarked) { n.bookmark(a) },
                SheetAction("📤", "Share link") { share(a) }),
        )) { actionsFor = null }
    }
    relatedFor?.let { a ->
        val rel = ai.abhify.android.news.NewsIntel.related(a, r?.articles.orEmpty())
        androidx.compose.material3.AlertDialog(onDismissRequest = { relatedFor = null }, title = { Text("Related stories") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rel.isEmpty()) Text("No related stories among the ones loaded now.", color = LocalExtra.current.muted)
                rel.forEach { x -> Column(Modifier.fillMaxWidth().clickable { relatedFor = null; n.openArticle(x) }) {
                    Text(x.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(x.publisher + if (x.publishedAt > 0) " · ${ago(x.publishedAt)}" else "", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted) } }
                Text("Matched by shared headline words among the stories already loaded.", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
            } }, confirmButton = { TextButton({ relatedFor = null }) { Text("Close") } })
    }
    if (settings) NewsSourcesSheet(n, cats) { settings = false }
    n.reading.value?.let { r -> ArticleReaderView(vm, r, onOpen = ::read) }
}

/**
 * One story inside the app (full screen over the list, so the list keeps its place; Back closes it). Title, publisher,
 * date, the summary of this story only, Read more (1st: its own text; 2nd: the original), Listen, and the original link.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArticleReaderView(vm: AppViewModel, r: NewsState.Reading, onOpen: (NewsArticle) -> Unit) {
    val n = vm.newsCenter; val a = r.article
    androidx.compose.ui.window.Dialog(onDismissRequest = n::closeArticle,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ScreenTitle("News", n::closeArticle)
            PageBody(gap = Dimens.gap) {
                Meta(a)
                Text(a.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                if (r.loading) BusyBanner(if (r.full) "Writing a summary of this article…" else "Reading the story…")
                when {
                    r.summary != null -> MarkdownText(r.summary)
                    a.summary.isNotBlank() -> Text(a.summary, style = MaterialTheme.typography.bodyLarge)
                    !r.loading -> Text("No description was given for this story.", color = LocalExtra.current.muted)
                }
                r.error?.let { ErrorBanner(it, onRetry = n::retryArticle) }
                if (!r.loading) Text(when {
                    r.summary == null -> "The publisher's own description."
                    r.full -> "AI summary of the article text (fetched from the publisher's page)${r.source?.let { " by $it" } ?: ""}. It can be wrong: check the original."
                    else -> "AI summary from the headline and short description only: the full article wasn't available here. Based on limited source content."
                }, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted)
                if (r.stage >= 1) {
                    if (r.paragraphs.isNotEmpty()) r.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    else InfoNote("There is no more text for this story here${r.note?.let { ": $it" } ?: "."} Tap Read more again to open the original article.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(if (r.stage == 0) "Read more" else "Read more at ${a.publisher}", { n.readMore()?.let { onOpen(a) } }, enabled = !r.loading)
                    if (r.summary != null || a.summary.isNotBlank()) TextButton({ vm.listenNews(a.title, r.summary ?: a.summary) }) { Text("🔊 Listen") }
                }
                if (ai.abhify.android.news.ArticleReader.safeUrl(a.url)) LinkRow("🔗", "Original article", a.publisher) { onOpen(a) }
                else InfoNote("This story has no valid link.")
            }
        }
    }
}

@Composable
private fun Placeholder(a: NewsArticle) {
    val acc = newsAccent(a.category)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(acc.g1, acc.g2))), contentAlignment = Alignment.Center) {
        GlyphIcon("📰", 32, Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun FeaturedCard(a: NewsArticle, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalExtra.current.glass).clickable(onClickLabel = "Read this story", onClick = onClick)) {
        RemoteImage(a.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { Placeholder(a) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Meta(a)
            Text(a.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Read in app", color = Accent.DOCS.text) }
                Spacer(Modifier.weight(1f)); IconAction("⋯", "More actions for this story", onMore)
            }
        }
    }
}

@Composable
private fun ArticleCard(a: NewsArticle, others: Int, onClick: () -> Unit, onMore: () -> Unit) {
    Glass(padding = 12, onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Meta(a)
                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (others > 0) Text("Also reported by $others other source${if (others == 1) "" else "s"} · ⋯ to compare", style = MaterialTheme.typography.labelSmall, color = Accent.DOCS.text)
            }
            Spacer(Modifier.width(10.dp))
            RemoteImage(a.imageUrl, null, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp))) { Placeholder(a) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Read in app", color = Accent.DOCS.text) }
            Spacer(Modifier.weight(1f)); IconAction("⋯", "More actions for this story", onMore)
        }
    }
}

@Composable
private fun Meta(a: NewsArticle) = Text(a.publisher + (if (a.publishedAt > 0) " · ${ago(a.publishedAt)}" else " · date not given") +
    (if (a.provider == "google-news") " · via Google News" else ""), style = MaterialTheme.typography.labelMedium, color = newsAccent(a.category).text, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** AI output: labelled as AI, with the numbered source links; dismissible; never saved. */
@Composable
private fun AiOutCard(out: NewsState.AiOut, vm: AppViewModel, onRead: (NewsArticle) -> Unit, onClose: () -> Unit) {
    AccentCard(Accent.CHAT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(out.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconAction(R.drawable.ic_close, "Close", onClose)
        }
        when {
            out.error != null -> ErrorBanner(out.error)
            out.text.isEmpty() -> BusyBanner("Writing from the headlines and descriptions…")
            else -> { NewsListen(vm, out); MarkdownText(out.text) }
        }
        if (out.text.isNotEmpty()) Text("AI-generated from headlines and short descriptions only (not the full articles), by ${out.source ?: "the AI"}. " +
            "It can be wrong: check the sources.", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted)
        out.articles.forEachIndexed { i, a -> LinkRow("🔗", "[${i + 1}] ${a.title}", a.publisher + if (a.publishedAt > 0) " · ${ago(a.publishedAt)}" else "") { onRead(a) } }
    }
}

/**
 * News audio: Listen reads the briefing on screen with the phone's own text-to-speech (no new AI request, nothing
 * downloaded). One item per bullet: Previous / Next, Pause / Resume, Stop, Replay and speed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsListen(vm: AppViewModel, out: NewsState.AiOut) {
    val mine = vm.speechOwner.value == "news"
    val playing = mine && vm.speaking.value
    val paused = mine && vm.speechPaused.value
    val (i, n) = vm.speechProgress.value
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.FilledTonalButton({ when { playing -> vm.pauseSpeaking(); paused -> vm.resumeSpeaking(); else -> vm.listenNews(out.title, out.text) } },
            modifier = Modifier.semantics { contentDescription = when { playing -> "Pause reading"; paused -> "Resume reading"; else -> "Listen to this briefing" } }) {
            Text(when { playing -> "⏸ Pause"; paused -> "▶ Resume"; else -> "🔊 Listen" }) }
        if (playing || paused) {
            TextButton({ vm.skipSpeech(-1) }, Modifier.semantics { contentDescription = "Previous item" }) { Text("⏮") }
            TextButton({ vm.skipSpeech(1) }, Modifier.semantics { contentDescription = "Next item" }) { Text("⏭") }
            TextButton(vm::stopSpeaking, Modifier.semantics { contentDescription = "Stop reading" }) { Text("■ Stop") }
        }
        listOf(0.8f, 1f, 1.25f, 1.5f).forEach { r -> FilterChip(vm.speechRate.value == r, { vm.setSpeechRate(r) }, { Text("${r}×".replace(".0×", "×")) }) }
    }
    if (mine && (playing || paused) && n > 0) Text("${if (paused) "Paused at" else "Reading"} part ${i + 1} of $n", style = MaterialTheme.typography.labelMedium,
        color = LocalExtra.current.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Source manager: region, language, city (for Local), For You categories, custom topics, prioritized and hidden publishers. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewsSourcesSheet(n: NewsState, catalog: List<ai.abhify.android.news.NewsCategory>, onClose: () -> Unit) {
    var p by remember { mutableStateOf(n.prefs.value) }
    var topic by remember { mutableStateOf("") }
    val cats = remember(catalog) { catalog.filter { !it.mix && !it.local }.map { it.id to it.label } }
    val publishers = remember(catalog) { catalog.flatMap { c -> c.feeds.map { it.publisher } }.distinct().sorted() }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LocalExtra.current.glassStrong) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            Text("News sources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            InfoNote("Only your city (as a search word) and the edition below are sent to Google News. No location permission is used.")
            Field(p.city, { p = p.copy(city = it.take(60)) }, "Your city for Local news (e.g. Pune)")
            Text("Edition", style = MaterialTheme.typography.labelLarge)
            val regions = listOf("IN" to "India", "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia", "CA" to "Canada", "SG" to "Singapore", "AE" to "UAE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { regions.forEach { (c, l) -> FilterChip(p.region == c, { p = p.copy(region = c) }, { Text(l) }) } }
            Text("Language (Google News)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("en" to "English", "hi" to "हिन्दी", "both" to "Both").forEach { (c, l) -> FilterChip(p.language == c, { p = p.copy(language = c) }, { Text(l) }) } }
            Text("For You shows", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cats.forEach { (id, label) -> FilterChip(id in p.forYou, { p = p.copy(forYou = if (id in p.forYou) p.forYou - id else p.forYou + id) }, { Text(label) }) }
            }
            Text("Custom topics (in For You)", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(topic, { topic = it.take(40) }, "e.g. electric vehicles", modifier = Modifier.weight(1f))
                TextButton({ if (topic.isNotBlank() && p.topics.size < 8) { p = p.copy(topics = p.topics + topic.trim()); topic = "" } }) { Text("Add") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { p.topics.forEach { t -> FilterChip(true, { p = p.copy(topics = p.topics - t) }, { Text("$t ✕") }) } }
            Text("Publishers", style = MaterialTheme.typography.labelLarge)
            InfoNote("Tap once to show first (★), again to hide (✕), again to reset.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                publishers.forEach { pub ->
                    val state = if (pub in p.prioritized) "★ " else if (pub in p.hidden) "✕ " else ""
                    FilterChip(state.isNotEmpty(), { p = when {
                        pub in p.prioritized -> p.copy(prioritized = p.prioritized - pub, hidden = p.hidden + pub)
                        pub in p.hidden -> p.copy(hidden = p.hidden - pub)
                        else -> p.copy(prioritized = p.prioritized + pub) } }, { Text(state + pub) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("Save", { n.savePrefs(p); onClose() }, Modifier.weight(1f))
                TextButton({ p = NewsPrefs() }) { Text("Reset") }
            }
            Spacer(Modifier.height(Dimens.small))
        }
    }
}
