package ai.abhify.android.ui

import ai.abhify.android.learn.CourseDiscovery
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Topics offered as one-tap starting points (inputs only; results always come from the sources). */
private val STARTERS = listOf("Python", "Machine Learning", "Generative AI", "Deep Learning", "Data Science", "Statistics", "SQL", "React",
    "Cloud computing", "Cybersecurity", "Calculus", "English communication")

/**
 * 5.3 Study → Discover. Live results (MIT OpenCourseWare, OpenStax) with cost only as their own data states,
 * then the providers' own search pages. Every result opens on the provider's site; "Save" keeps it in Knowledge → Study;
 * "Make a learning path" hands the topic to Plans (AI-generated, labelled there).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiscoverView(vm: AppViewModel) {
    val d = vm.discover
    val ctx = LocalContext.current
    val x = LocalExtra.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Find courses, textbooks and official docs", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.OutlinedTextField(d.query.value, { d.query.value = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Any topic, e.g. Machine Learning") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { d.search() }))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ d.search() }, enabled = d.query.value.trim().length >= 2 && !d.busy.value) { Text("Search") }
                    Switch(d.freeOnly.value, { d.freeOnly.value = it; d.refilter() })
                    Text("Free only", style = MaterialTheme.typography.bodyMedium)
                }
                if (d.searchedAt.value == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STARTERS.forEach { t -> AssistChip({ d.search(t) }, { Text(t, style = MaterialTheme.typography.labelMedium) }) }
                }
                if (d.busy.value) LinearProgressIndicator(Modifier.fillMaxWidth())
                d.notes.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = x.warn) }
                Text("Only your search words are sent, to these public services. Prices and availability can change: the provider's page is always the final word.",
                    style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
        if (d.searchedAt.value != null) item {
            Glass(padding = 12) {
                Text("🗓 Learning path for \"${d.query.value}\"", fontWeight = FontWeight.SemiBold)
                Text("Abhify's AI drafts the stages and lessons for your level and time in Study → Plans. It's AI-generated advice; the resources below are real.",
                    style = MaterialTheme.typography.bodySmall, color = x.muted)
                TextButton({ d.learningPath() }) { Text("Make a learning path") }
            }
        }
        val live = d.results.filter { it.kind != CourseDiscovery.Kind.SEARCH }
        val links = d.results.filter { it.kind == CourseDiscovery.Kind.SEARCH }
        if (d.searchedAt.value != null) item {
            Text(if (live.isEmpty()) "No matching courses or books from the live sources" + (if (d.freeOnly.value) " marked free." else ".")
                else "${live.size} from live sources · checked just now", style = MaterialTheme.typography.labelLarge)
        }
        items(live, key = { it.url }) { r -> ResourceCard(r, x, { open(r.url) }) { d.save(r) } }
        if (links.isNotEmpty()) item {
            Text("Search on the providers' own sites", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Text("These open the provider's search for your topic; what's free, free to audit or paid is shown there.", style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        items(links, key = { it.url }) { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.provider, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(r.costNote.orEmpty(), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2)
                }
                TextButton({ open(r.url) }) { Text("Open ↗") }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ResourceCard(r: CourseDiscovery.Resource, x: Extra, onOpen: () -> Unit, onSave: () -> Unit) {
    Glass(padding = 12) {
        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 3)
        Text(listOfNotNull(r.provider, r.kind.label, r.level).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(r.cost.label, if (r.cost == CourseDiscovery.Cost.FREE) x.ok else MaterialTheme.colorScheme.tertiary)
            r.license?.let { Pill(it, x.muted) }
            r.topics.take(2).forEach { Pill(it, x.muted) }
        }
        r.costNote?.takeIf { r.cost != CourseDiscovery.Cost.FREE || it != "Free online textbook" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        Text("Source: ${r.source} (live)", style = MaterialTheme.typography.labelSmall, color = x.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onOpen) { Text("Open ↗") }
            TextButton(onSave) { Text("Save to Knowledge") }
        }
    }
}
