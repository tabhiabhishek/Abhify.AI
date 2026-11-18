package ai.abhify.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v3.9 study dashboard (top of Study): review queue, active plans, quiz history, card statistics, recent documents and
 * the recommended next session — all from this phone's own study records; nothing is sent anywhere.
 */
@Composable
internal fun StudyDashboard(vm: AppViewModel) {
    val s = vm.studio
    LaunchedEffect(Unit) { s.loadDashboard() }
    val d = s.dashboard.value ?: return
    // 4.5.1: "Today" as a bento: the recommendation first, then four stat tiles, then the one main action
    PageHeader("Study", "Your plans, cards and quizzes on this phone", Accent.STUDY, "🎓")
    BentoCard("✨", "Recommended next", accent = Accent.STUDY) {
        d.suggestions.firstOrNull()?.let { r ->
            Text(r.topic, style = MaterialTheme.typography.titleMedium)
            Text("${r.reason} → ${r.action}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        } ?: Text(if (d.cards.total + d.plans.size + d.quiz.size == 0) "Make a plan, a deck or take a quiz to get suggestions here." else "Nothing needs attention right now.",
            style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.muted)
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("${d.cards.due}", "cards due", Modifier.weight(1f), accent = Accent.STUDY); StatTile("${d.cards.fresh}", "new cards", Modifier.weight(1f), accent = Accent.DOCS)
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("${d.cards.mature}", "mature (21d+)", Modifier.weight(1f), accent = Accent.MEMORY); StatTile("${d.cards.reviewsToday}", "reviewed today", Modifier.weight(1f), accent = Accent.CAREER)
    }
    if (d.cards.due > 0) GradientButton("Review ${d.cards.due} due card(s)", { s.review(StudioState.DUE_ALL); vm.learnMode.value = 3 }, Modifier.fillMaxWidth())
    val active = d.plans.filter { it.done < it.total }
    if (active.isNotEmpty()) BentoCard("🗓", "Active plans", action = "Open", accent = Accent.STUDY, onClick = { vm.learnMode.value = 2 }) {
        active.take(3).forEach { p -> Text("${p.topic}: ${p.done} of ${p.total} lessons" + (p.targetDate?.let { " · target $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator({ if (p.total == 0) 0f else p.done / p.total.toFloat() }, Modifier.fillMaxWidth()) }
    }
    if (d.quiz.isNotEmpty() || d.sessions.isNotEmpty()) BentoCard("❔", "Quiz history", accent = Accent.DOCS) {
        d.quiz.sortedByDescending { it.answered }.take(4).forEach { q -> Text("${q.topic}: ${q.correct}/${q.answered} correct (${q.correct * 100 / maxOf(1, q.answered)}%)", style = MaterialTheme.typography.bodySmall) }
        d.sessions.take(3).forEach { q -> Text("${q.title.take(40)}: ${q.score}/${q.total}", style = MaterialTheme.typography.bodySmall) }
    }
    if (d.suggestions.size > 1) BentoCard("📈", "Topics that may need review", accent = Accent.CAREER) {
        d.suggestions.drop(1).take(4).forEach { r -> Text("• ${r.topic}: ${r.reason}", style = MaterialTheme.typography.bodySmall) }
        Text("Based only on your quizzes, cards and plans.", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
    }
    if (d.recentDocs.isNotEmpty()) Text("Recent documents: " + d.recentDocs.joinToString(" · ") { it.name.take(30) }, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
}
