package ai.abhify.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import ai.abhify.android.intel.Briefing
import ai.abhify.android.intel.DailyPlanner
import ai.abhify.android.learn.SkillGraph
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Home → Today: the daily plan from real to-dos and project next actions, with reasons; priority/blocked are the user's. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TodayCard(vm: AppViewModel) {
    val b = vm.brain
    val x = LocalExtra.current
    var editing by remember { mutableStateOf<DailyPlanner.Entry?>(null) }
    LaunchedEffect(Unit) { b.refresh() }
    val p = b.plan.value ?: return
    BentoCard("✅", "Today · ${p.minutesPlanned} of ${p.minutesAvailable} min", action = "Briefing", accent = Accent.CHAT, onClick = { vm.openBriefing() }) {
        if (p.entries.isEmpty() && p.blocked.isEmpty()) Text("No open to-dos or recorded next actions. Add to-dos in a project, or ask me to review a project.",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
        p.entries.take(5).forEach { e ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Dimens.fieldRadius)).clickable(enabled = e.taskId != null) { editing = e }
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${e.reason} · ${e.project} · ~${e.minutes} min", style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                e.taskId?.let { id -> b.meta[id]?.priority?.takeIf { it != DailyPlanner.Priority.NORMAL }?.let { Pill(it.label, if (it == DailyPlanner.Priority.HIGH) x.warn else x.muted) } }
            }
        }
        if (p.blocked.isNotEmpty()) Text("Blocked: " + p.blocked.take(3).joinToString { it.title }, style = MaterialTheme.typography.bodySmall, color = x.warn, maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(60, 120, 240, 480).forEach { m -> FilterChip(b.minutes.intValue == m, { b.setMinutes(m) }, { Text(if (m < 60) "$m min" else "${m / 60} h") }) }
        }
        Text("Only stored due dates count as overdue; nothing is rescheduled for you. Tap a to-do to set its priority.", style = MaterialTheme.typography.labelSmall, color = x.muted)
    }
    editing?.let { e -> val id = e.taskId!!; val m = b.meta[id] ?: DailyPlanner.Meta()
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(e.title, maxLines = 2) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DailyPlanner.Priority.entries.forEach { pr -> FilterChip(m.priority == pr, { b.setMeta(id, m.copy(priority = pr)) }, { Text(pr.label) }) } }
                SwitchRow("Blocked", "Waiting on something; kept out of today's plan.", m.blocked) { b.setMeta(id, m.copy(blocked = it)) }
                Text("Estimate", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(15, 30, 60, 120).forEach { mins -> FilterChip((m.minutes ?: DailyPlanner.DEFAULT_MINUTES) == mins, { b.setMeta(id, m.copy(minutes = mins)) }, { Text("$mins min") }) } }
                // 5.6: optional reminder for this to-do (no new permission; quiet hours respected; none unless you pick one)
                val ctx = vm.g.context
                var remindAt by remember(id) { mutableStateOf(TaskReminders.load(ctx)[id]) }
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                Text(remindAt?.let { "Set for ${TaskReminders.label(it)}" } ?: "None. Pick a time to get one notification (moved after quiet hours; skipped if the to-do is done).",
                    style = MaterialTheme.typography.bodySmall, color = x.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskReminders.presets(java.time.LocalDateTime.now()).forEach { (label, at) -> androidx.compose.material3.AssistChip({
                        TaskReminders.set(ctx, id, at); remindAt = TaskReminders.load(ctx)[id] }, { Text(label) }) }
                    if (remindAt != null) androidx.compose.material3.AssistChip({ TaskReminders.cancel(ctx, id); remindAt = null }, { Text("Cancel reminder") })
                }
                if (remindAt != null && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Notifications are off — allow them for Abhify") }
            } }, confirmButton = { TextButton({ editing = null }) { Text("Done") } })
    }
}

/** The briefing sheet: written + Listen, kind tabs, per-item source and Skip. */
@Composable
fun BriefingSheet(vm: AppViewModel) {
    if (!vm.briefingOpen.value) return
    val b = vm.brain
    val x = LocalExtra.current
    var kind by remember { mutableStateOf(Briefing.Kind.ON_DEMAND) }
    LaunchedEffect(kind) { b.makeBriefing(kind) }
    val brief = b.brief.value
    AlertDialog(onDismissRequest = { vm.briefingOpen.value = false }, title = { Text(kind.title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedTabs(Briefing.Kind.entries.map { it.title.substringBefore(' ') }, kind.ordinal) { kind = Briefing.Kind.entries[it] }
            if (brief == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (brief.isEmpty) Text("There's nothing in your records to brief you on yet.", color = x.muted)
            else brief.sections.filter { it.items.isNotEmpty() }.forEach { s ->
                Text(s.title, style = MaterialTheme.typography.titleSmall)
                s.items.forEach { it2 ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it2.text, style = MaterialTheme.typography.bodyMedium)
                            Text("Source: ${it2.source}", style = MaterialTheme.typography.labelSmall, color = x.muted)
                        }
                        TextButton({ b.skip(it2.key) }) { Text("Skip") }
                    }
                }
            }
            Text("Built from your records on this phone just now; nothing was sent anywhere.", style = MaterialTheme.typography.labelSmall, color = x.muted)
        } },
        confirmButton = { if (brief != null && !brief.isEmpty) TextButton({ vm.speak(brief.spoken()) }) { Text("🔊 Listen") } },
        dismissButton = { TextButton({ vm.briefingOpen.value = false }) { Text("Close") } })
}

/** Agent 2.0 panel: plan steps with status, proposals to approve (each labelled verified or suggestion), report, undo. */
@Composable
fun BoxScope.AgentOverlay(vm: AppViewModel) {
    val a = vm.agent
    if (!a.open) return
    val x = LocalExtra.current
    androidx.activity.compose.BackHandler {                              // 6.1: Back never navigates underneath an open agent run
        if (a.phase.value == AgentState.Phase.RUNNING || a.phase.value == AgentState.Phase.AWAITING_APPROVAL) a.cancel() else a.close()
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(x.glassStrong)
        .pointerInput(Unit) { detectTapGestures { } }
        .heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("🤖 Agent · ${a.workflow.value.label} · \"${a.subject.value}\"", style = MaterialTheme.typography.titleMedium)
        a.steps.forEach { s ->
            val icon = when (s.status) { "done" -> "✅"; "running" -> "⏳"; "problem", "refused" -> "⚠"; else -> "○" }
            Text("$icon ${s.title}" + (s.tool?.let { "  ·  $it" } ?: "") + if (s.detail.isNotBlank()) "\n    ${s.detail}" else "", style = MaterialTheme.typography.bodySmall)
        }
        a.summary.value?.let { s -> s.sections.filter { it.second.isNotEmpty() }.forEach { (title, lines) ->
            Text(title, style = MaterialTheme.typography.labelLarge)
            lines.forEach { l -> Text("• ${l.text}", style = MaterialTheme.typography.bodySmall); Text("    source: ${l.source}", style = MaterialTheme.typography.labelSmall, color = x.muted) } } }
        if (a.phase.value == AgentState.Phase.AWAITING_APPROVAL && a.workflow.value != AgentState.Workflow.REVIEW) {
            Text("Proposed changes (nothing changes until you approve):", style = MaterialTheme.typography.labelLarge)
            a.items.forEachIndexed { i, it ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(i in a.selected, { a.toggle(i) })
                    Column(Modifier.weight(1f)) { Text(it.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("✔ ${it.detail}", style = MaterialTheme.typography.labelSmall, color = x.ok) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ a.approveItems() }) { Text("Approve ${a.selected.size}") }; OutlinedButton({ a.cancel() }) { Text("Cancel") } }
        }
        if (a.phase.value == AgentState.Phase.AWAITING_APPROVAL && a.workflow.value == AgentState.Workflow.REVIEW) {
            Text("Proposed to-dos (nothing is created until you approve):", style = MaterialTheme.typography.labelLarge)
            a.proposals.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(i in a.selected, { a.toggle(i) })
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text((if (p.verified) "✔ " else "≈ ") + p.origin + (p.duplicateOf?.let { " · already a to-do: \"$it\"" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = if (p.verified) x.ok else x.warn)
                        p.evidence?.let { Text("“${it.take(140)}”", style = MaterialTheme.typography.labelSmall, color = x.muted) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ a.approve() }) { Text("Approve ${a.selected.size}") }
                OutlinedButton({ a.cancel() }) { Text("Cancel") }
            }
        }
        if (a.phase.value == AgentState.Phase.RUNNING) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.weight(1f)); OutlinedButton({ a.cancel() }, border = BorderStroke(1.dp, x.bad)) { Text("Cancel", color = x.bad) }
        }
        a.report.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (a.phase.value in setOf(AgentState.Phase.DONE, AgentState.Phase.FAILED, AgentState.Phase.CANCELLED)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.canUndo) OutlinedButton({ a.undo() }) { Text("Undo") }
            Button({ a.close() }) { Text("Close") }
        }
    }
}

/** Study → Overview: the skill graph (only measured levels) and the adaptive next step. */
@Composable
fun SkillsCard(vm: AppViewModel) {
    val b = vm.brain
    val x = LocalExtra.current
    LaunchedEffect(Unit) { b.refresh() }
    BentoCard("🎓", "Skills", accent = Accent.STUDY) {
        if (b.skills.isEmpty()) { Text("No learning records yet. Quizzes, MCQs, flashcards and plans build this map.", style = MaterialTheme.typography.bodySmall, color = x.muted); return@BentoCard }
        var msg by remember { mutableStateOf<String?>(null) }
        var confirm by remember { mutableStateOf<String?>(null) }
        b.skillNext.value?.let { n -> Text("Next: ${n.action}", style = MaterialTheme.typography.titleSmall); Text(n.reason, style = MaterialTheme.typography.bodySmall, color = x.muted)
            TextButton({ confirm = n.skill }) { Text("Add a revision to-do") } }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.ok) }
        confirm?.let { skill -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Add a revision to-do?") },
            text = { DialogText("Adds \"Revise ${skill.replaceFirstChar { it.uppercase() }}\" to a project named Study (created if you don't have one). You can delete it any time.") },
            confirmButton = { TextButton({ b.addRevisionTodo(skill) { msg = it }; confirm = null }) { Text("Add") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
        b.skills.take(8).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.skill.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(s.accuracy?.let { "${s.level.label} · $it% of ${s.answered}" + (s.trend?.let { t -> " · $t" } ?: "") } ?: if (s.answered > 0) "${s.answered} answers · not enough data" else s.level.label,
                    style = MaterialTheme.typography.bodySmall, color = when (s.level) { SkillGraph.Level.NEEDS_WORK -> x.warn; SkillGraph.Level.STRONG -> x.ok; else -> x.muted })
            }
            if (s.prerequisites.isNotEmpty() && s.goal != null) Text("    usually after: ${s.prerequisites.joinToString()} (general guidance)", style = MaterialTheme.typography.labelSmall, color = x.muted)
        }
        // 5.6: revision plan and learning path, both from the same evidence (answer counts shown)
        val revision = SkillGraph.revisionPlan(b.skills)
        if (revision.isNotEmpty()) { Text("Revision plan", style = MaterialTheme.typography.labelLarge)
            revision.forEachIndexed { i, r -> Text("${i + 1}. ${r.skill.replaceFirstChar { it.uppercase() }} — ${r.reason}", style = MaterialTheme.typography.bodySmall) } }
        b.skills.firstOrNull { it.goal != null && it.prerequisites.isNotEmpty() }?.let { g ->
            Text("Learning path to ${g.skill} (general order)", style = MaterialTheme.typography.labelLarge)
            Text(SkillGraph.learningPath(g.skill, b.skills).joinToString(" → ") { "${it.skill} [${it.status}]" }, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Levels need at least", style = MaterialTheme.typography.labelSmall, color = x.muted)
            listOf(3, 5, 10).forEach { n -> TextButton({ b.setMinAnswers(n) }) { Text(if (b.minAnswers.intValue == n) "[$n]" else "$n", style = MaterialTheme.typography.labelSmall) } }
            Text("answers", style = MaterialTheme.typography.labelSmall, color = x.muted)
        }
        Text("Nothing is estimated below that; trends need 10 answers.", style = MaterialTheme.typography.labelSmall, color = x.muted)
    }
}

/** Settings → Briefing: reminder off by default; one notification a day at the chosen hour; no content in it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BriefingSettingsGroup(vm: AppViewModel) {
    val g = vm.g
    var on by remember { mutableStateOf(g.prefs.getBoolean(BriefingWorker.PREF_ON, false)) }
    var hour by remember { mutableStateOf(g.prefs.getInt(BriefingWorker.PREF_HOUR, 8)) }
    fun save() { g.prefs.edit().putBoolean(BriefingWorker.PREF_ON, on).putInt(BriefingWorker.PREF_HOUR, hour).apply(); BriefingWorker.schedule(g.context, on, hour) }
    SettingsGroup("Personal briefing", accent = Accent.CHAT) {
        NavRow("📰", "Open my briefing now", "Today's priorities, projects, study, saved news (from your records)") { vm.openBriefing() }
        SwitchRow("Daily reminder", "Off by default. One notification a day saying the briefing is ready; nothing is read aloud or computed in the background. Skipped in quiet hours.", on) { on = it; save() }
        if (on) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(6, 7, 8, 9, 18, 20).forEach { h -> FilterChip(hour == h, { hour = h; save() }, { Text("%02d:00".format(h)) }) } }
        if (on && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(g.context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            TextButton({ runCatching { g.context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, g.context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Allow notifications for Abhify") }
    }
}
