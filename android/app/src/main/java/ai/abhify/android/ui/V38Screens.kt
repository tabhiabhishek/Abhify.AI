package ai.abhify.android.ui

import ai.abhify.android.backup.Storage
import ai.abhify.android.chat.MemoryItem
import ai.abhify.android.memory.MemoryReview
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** v3.8: memory overview (counts, categories, what needs attention, size, backup). */
@Composable
internal fun MemoryOverview(vm: AppViewModel) {
    val m = vm.memory
    val d = m.dashboard()
    SettingsGroup("Overview") {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Memories" to d.total, "Expiring soon" to d.expiringSoon, "Expired" to d.expired, "Duplicates" to d.duplicates, "Possible conflicts" to d.conflicts,
                "Never used" to d.neverUsed).forEach { (k, v) -> Column { Text("$v", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
                    Text(k, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted) } }
        }
        if (d.byCategory.isNotEmpty()) Text(d.byCategory.joinToString(" · ") { "${it.first} ${it.second}" }, style = MaterialTheme.typography.bodySmall)
        if (d.recentlyUpdated.isNotEmpty()) Text("Recently updated: " + d.recentlyUpdated.joinToString(" · ") { "\"${it.content.take(40)}\"" }, style = MaterialTheme.typography.bodySmall)
        Text("Size ${Storage.human(m.bytes.value)} · last backup: " + (m.lastBackup()?.let { when_(it.createdAt) + " (${it.location})" } ?: "never"),
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
}

/** v3.8: the review queue. Every action is the user's; nothing is merged, replaced or deleted automatically. */
@Composable
internal fun MemoryReviewCard(vm: AppViewModel, onMerge: (List<MemoryItem>) -> Unit) {
    val m = vm.memory
    val reviews = m.reviews()
    if (reviews.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Glass {
        Row { Text("Needs review (${reviews.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton({ open = !open }) { Text(if (open) "Hide" else "Review") } }
        if (!open) Text(reviews.groupingBy { it.kind.label }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
        if (open) reviews.take(30).forEach { r -> Column(Modifier.padding(vertical = 4.dp)) {
            Text(r.kind.label, fontWeight = FontWeight.SemiBold, color = if (r.kind == MemoryReview.Kind.CONFLICT) LocalExtra.current.warn else MaterialTheme.colorScheme.onSurface)
            r.memories.forEach { Text("• ${it.content}", style = MaterialTheme.typography.bodyMedium) }
            Text(r.reason, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                when (r.kind) {
                    MemoryReview.Kind.CONFLICT -> {
                        r.memories.forEachIndexed { i, x -> TextButton({ m.keepOnly(r, x) }) { Text(if (i == 0) "Keep newer" else "Keep older") } }
                        TextButton({ m.keepBoth(r) }) { Text("Keep both") }; TextButton({ onMerge(r.memories) }) { Text("Merge…") }
                    }
                    MemoryReview.Kind.DUPLICATE, MemoryReview.Kind.COMBINE -> { TextButton({ onMerge(r.memories) }) { Text("Merge…") }; TextButton({ m.keepBoth(r) }) { Text("Keep both") } }
                    MemoryReview.Kind.EXPIRED, MemoryReview.Kind.EXPIRING, MemoryReview.Kind.STALE -> {
                        TextButton({ m.keepMemory(r) }) { Text(if (r.kind == MemoryReview.Kind.STALE) "Keep" else "Keep (no expiry)") }
                        TextButton({ m.delete(r.memories.single()) }) { Text("Delete", color = LocalExtra.current.bad) }
                    }
                }
            }
        } }
        Muted("Suggestions only. Possible contradictions are found from simple patterns (e.g. \"preferred framework: …\", \"lives in …\") and can be wrong.")
    }
}

/** v3.8: where a memory came from (only what was recorded) with a link to its chat when it still exists. */
@Composable
internal fun MemoryProvenance(vm: AppViewModel, mem: MemoryItem) {
    val text = remember(mem.id, mem.source) { vm.memory.provenance(mem) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text + (mem.lastUsedAt?.let { " · used ${mem.useCount}×, last ${ago(it)}" } ?: " · not used yet"),
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
        MemoryReview.chatIdOf(mem.source)?.takeIf { !text.contains("no longer exists") }?.let { id ->
            TextButton({ vm.loadConversation(id); vm.tab.value = AppViewModel.Tab.CHAT; vm.home() }) { Text("Open chat") } }
    }
}

@Composable private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
