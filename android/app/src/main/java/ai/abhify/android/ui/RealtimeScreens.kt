package ai.abhify.android.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 6.2 the live state while retrieving ("Checking current information…"); announced politely to TalkBack. */
@Composable
fun LiveStateLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * 6.2 live sources under an answer: the state ("✓ Sources checked", "🕘 Using cached information", "⚠ Live information
 * unavailable · couldn't check: weather"), one chip per source ([W#] opens the original page in the browser; [L#] are your
 * own records/phone status and only expand), details inline, and Refresh for fresh live information.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSources(m: AppViewModel.UiMsg, onRefresh: (() -> Unit)?) {
    if (m.links.isEmpty() && m.live == null) return
    val x = LocalExtra.current
    val ctx = LocalContext.current
    var open by remember(m.dbId, m.text.length) { mutableStateOf<AppViewModel.LiveLink?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.live?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith("⚠")) x.warn else x.muted) }
        if (m.links.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.links.forEach { l ->
                Text(l.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(x.line.copy(alpha = 0.35f))
                        .clickable(onClickLabel = "Show source details") { open = if (open == l) null else l }
                        .semantics { contentDescription = "Source ${l.label}: ${l.detail}" }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        open?.let { l ->
            Column(Modifier.clip(RoundedCornerShape(Dimens.fieldRadius)).background(x.line.copy(alpha = 0.2f)).padding(10.dp)) {
                Text(l.detail, style = MaterialTheme.typography.bodySmall)
                l.url?.let { u -> TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u.toUri())) } }) {
                    Text("Open original: " + (u.toUri().host ?: "link"), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    ?: Text("From the app's own data on this phone (no web page).", style = MaterialTheme.typography.labelSmall, color = x.muted)
            }
        }
        onRefresh?.let { TextButton(it) { Text("↻ Refresh live information") } }
    }
}

/** 6.2 Settings → Live information: what may be looked up and which services that uses. */
@Composable
fun LiveInfoSettingsGroup(vm: AppViewModel) {
    vm.realtimeVersion.intValue                                                  // recompose after a change
    val s = vm.realtimeSettings()
    SettingsGroup("Live information", accent = Accent.CHAT) {
        SwitchRow("Look up current information", "Questions about news, weather, exchange rates, your projects, to-dos or this phone are answered " +
            "with information retrieved now, with sources. Only the question's topic is sent to the news search (Google News); your documents, " +
            "memories and records never leave the phone for this.", s.enabled) { vm.setRealtime("rt_enabled", it) }
        if (s.enabled) {
            SwitchRow("Weather (Open-Meteo)", "Sends only the place name below to Open-Meteo.com (free, no account; data CC BY 4.0). No device location is used.", s.weather) { vm.setRealtime("rt_weather", it) }
            if (s.weather) {
                var place by remember { mutableStateOf(s.place) }
                OutlinedTextField(place, { place = it.take(80); vm.setRealtime("rt_place", place) }, singleLine = true, label = { Text("Place for weather (e.g. Pune)") })
            }
            SwitchRow("Exchange rates (ECB via Frankfurter)", "Daily reference rates from the European Central Bank (frankfurter.dev, free, no account). Not live market prices.", s.fx) { vm.setRealtime("rt_fx", it) }
            Text("General web search isn't available on the phone: it needs a search provider (for example Brave or Tavily) with an API key, " +
                "which needs your approval. Recent news is checked instead, and answers say so.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        }
    }
}
