package ai.abhify.android.ui

import ai.abhify.android.learn.CourseDiscovery
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 5.3 Study → Discover: searches the verified keyless sources in parallel (each with a timeout; one failing doesn't hide
 * the others) and lists the providers' own search pages. Only the topic is sent, to those public services; nothing
 * personal. Saving a resource uses the existing Knowledge vault (category Study), so no new storage is added.
 */
class DiscoverState(private val vm: AppViewModel) {
    val query = mutableStateOf("")
    val freeOnly = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val results = mutableStateListOf<CourseDiscovery.Resource>()
    val notes = mutableStateListOf<String>()                 // honest status per source ("MIT Learn: no connection")
    val searchedAt = mutableStateOf<Long?>(null)
    private var live: List<CourseDiscovery.Resource> = emptyList()

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8_000; readTimeout = 12_000; setRequestProperty("User-Agent", CourseDiscovery.USER_AGENT); setRequestProperty("Accept", "application/json")
        try { if (responseCode !in 200..299) error("HTTP $responseCode"); inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
    }

    fun search(topic: String = query.value) {
        val t = topic.trim().take(80); if (t.length < 2 || busy.value) return
        query.value = t; busy.value = true; notes.clear()
        vm.viewModelScope.launch {
            val parts = withContext(Dispatchers.IO) {
                listOf(
                    async { "MIT OpenCourseWare" to runCatching { CourseDiscovery.parseMit(get(CourseDiscovery.mitUrl(t))) } },
                    async { "OpenStax" to runCatching { CourseDiscovery.parseOpenStax(get(CourseDiscovery.OPENSTAX_URL), t) } },
                    // Wikibooks: Wikimedia rejects automated requests without contact details (checked 2026-11-18), so it's a search link instead
                ).awaitAll()
            }
            live = parts.flatMap { it.second.getOrDefault(emptyList()) }
            parts.forEach { (name, r) -> r.exceptionOrNull()?.let { notes += "$name couldn't be reached (${if (it is java.net.UnknownHostException) "no connection" else it.message ?: "error"})" } }
            searchedAt.value = System.currentTimeMillis()
            refilter(); busy.value = false
        }
    }

    fun refilter() { results.clear(); if (searchedAt.value != null) results.addAll(CourseDiscovery.combine(live, query.value, freeOnly.value)) }

    /** Saves a resource to Knowledge (category Study) so it can be found and resumed later. */
    fun save(r: CourseDiscovery.Resource) = vm.life.save("course", r.title.take(200),
        "${r.provider} · ${r.kind.label} · ${r.cost.label}\n${r.url}" + (r.license?.let { "\nLicence: $it" } ?: "") +
            "\nFound with Study → Discover on ${java.time.LocalDate.now()} (${r.source}).", "Study", listOf("course", r.provider))

    /** Hands the topic to Study → Plans, which generates the AI learning path (labelled AI-generated there). */
    fun learningPath() { vm.studio.planTopicPrefill.value = query.value; vm.learnMode.value = 2 }
}
