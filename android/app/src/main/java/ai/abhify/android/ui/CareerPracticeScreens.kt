package ai.abhify.android.ui

import ai.abhify.android.career.CareerQuestions
import ai.abhify.android.career.McqPractice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the questions come from: resume or document, mode, topic, skills/projects, level, difficulty, model. Shared by MCQs and Generate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeSource(vm: AppViewModel, showBatch: Boolean) {
    val m = vm.mcq
    var models by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { m.loadSources() }
    SettingsGroup("Practise from", accent = Accent.CAREER) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            McqPractice.Mode.entries.forEach { md -> FilterChip(m.mode.value == md, { m.mode.value = md }, { Text(md.label) }) }
        }
        if (m.mode.value != McqPractice.Mode.TOPIC) {
            val resumes = m.sources.filter { it.role == "resume" }
            val list = if (m.mode.value == McqPractice.Mode.DOCUMENT) m.sources else resumes.ifEmpty { m.sources }
            if (list.isEmpty()) InfoNote("No resume yet: add one in Career → Resumes (or a document in Documents).")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.take(12).forEach { d -> FilterChip(m.sourceId.value == d.id, { m.pickSource(d.id) }, { Text((if (d.role == "resume") "📄 " else "📚 ") + d.name.take(28)) }) }
            }
        }
        if (m.mode.value in setOf(McqPractice.Mode.TOPIC, McqPractice.Mode.MIXED)) Field(m.topic.value, { m.topic.value = it.take(80) }, "Topic (e.g. SQL joins, Python generators, FastAPI)")
        if (m.mode.value == McqPractice.Mode.SKILLS) {
            val skills = m.resumeSkills()
            if (skills.isEmpty()) InfoNote("No known skills were found in this resume's text.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                skills.forEach { s -> FilterChip(s in m.skills, { if (s in m.skills) m.skills -= s else m.skills += s }, { Text(s) }) }
            }
        }
        if (m.mode.value == McqPractice.Mode.PROJECTS) {
            val projects = m.resumeProjects()
            if (projects.isEmpty()) InfoNote("No Projects section was found in this resume.")
            projects.forEach { p -> FilterChip(p in m.projects, { if (p in m.projects) m.projects -= p else m.projects += p }, { Text(p, maxLines = 2, overflow = TextOverflow.Ellipsis) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            McqPractice.LEVELS.forEach { l -> FilterChip(m.level.value == l, { m.level.value = l }, { Text(l) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            McqPractice.DIFFICULTIES.forEach { d -> FilterChip(m.difficulty.value == d, { m.difficulty.value = d }, { Text(d) }) }
            if (showBatch) McqPractice.BATCHES.forEach { n -> FilterChip(m.batch.value == n, { m.batch.value = n }, { Text("$n per batch") }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp))
            ModelChip(vm, onOpen = { models = true })
        }
        Text("Your AI mode decides where it runs (this phone or your provider); a resume goes online only after you agree.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
    }
    if (models) ModelSheet(vm) { models = false }
}

/** Career → MCQs: batches on demand, an answer is a tap, feedback right away, the answer always labelled AI-generated. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McqView(vm: AppViewModel) {
    val m = vm.mcq
    PullToRefreshBox(isRefreshing = m.busy.value, onRefresh = { if (m.questions.isNotEmpty()) m.generate(refresh = true) else m.generate() }, modifier = Modifier.fillMaxSize()) {
        PageBody(gap = Dimens.gap) {
            PageHeader("MCQ practice", "Your resume, skills, projects or any topic · as many batches as you like", Accent.STUDY, "✅")
            PracticeSource(vm, showBatch = true)
            m.error.value?.let { e ->
                ErrorBanner(e, onRetry = { m.retry() })
                TextButton({ vm.go(AppViewModel.Route.MODELS) }) { Text("Choose another model or provider") }
            }
            m.note.value?.let { InfoNote(it) }
            val q = m.questions.getOrNull(m.index.value)
            if (q == null) GradientButton(if (m.busy.value) "Writing questions…" else "Start practice", { m.generate() }, Modifier.fillMaxWidth(),
                enabled = !m.busy.value, busy = m.busy.value)
            else McqCard(vm, q)
            if (m.questions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(m::previous, enabled = m.index.value > 0) { Text("◀ Previous") }
                OutlinedButton({ m.generate() }, enabled = !m.busy.value) { Text(if (m.busy.value) "Writing…" else "Generate more") }
                TextButton({ m.generate(refresh = true) }, enabled = !m.busy.value) { Text("Refresh unanswered") }
                TextButton(m::stop) { Text("Stop") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Review mode (show answers first)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(m.review.value, { m.review.value = it })
            }
            val s = m.sessionStats(); val scope = m.scopeStats(); val all = m.overallStats()
            Text("This session: ${s.correct}/${s.attempted} correct · this ${if (m.mode.value == McqPractice.Mode.TOPIC) "topic" else "resume/document"}: " +
                "${scope.correct}/${scope.attempted} · all practice: ${all.correct}/${all.attempted}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            InfoNote("Pull down to refresh the questions you haven't answered. Answered questions and your progress stay.")
        }
    }
}

@Composable
private fun McqCard(vm: AppViewModel, q: McqPractice.Question) {
    val m = vm.mcq
    val picked = m.picks[q.key]
    val shown = picked != null || m.review.value
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Question ${m.index.value + 1} of ${m.questions.size}", style = MaterialTheme.typography.labelLarge, color = LocalExtra.current.muted, modifier = Modifier.weight(1f))
            Pill(q.difficulty); Spacer(Modifier.width(4.dp)); Pill("AI", MaterialTheme.colorScheme.secondary)
        }
        Text(q.topic, style = MaterialTheme.typography.labelMedium, color = Accent.STUDY.text)
        Text(q.q, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = Dimens.tiny))
        q.options.forEachIndexed { i, o ->
            val x = LocalExtra.current
            val c = when { !shown -> x.line; i == q.answer -> x.ok; i == picked -> x.bad; else -> x.line }
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius))
                .background(if (shown && (i == q.answer || i == picked)) c.copy(alpha = 0.10f) else Color.Transparent)
                .border(if (shown && (i == q.answer || i == picked)) 2.dp else 1.dp, c, RoundedCornerShape(Dimens.fieldRadius))
                .clickable(enabled = picked == null, onClickLabel = "Answer ${'A' + i}") { m.answer(q, i) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(if (shown && i == q.answer) x.ok else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${'A' + i}", style = MaterialTheme.typography.labelLarge, color = if (shown && i == q.answer) Color.White else MaterialTheme.colorScheme.tertiary) }
                Spacer(Modifier.width(12.dp))
                Text(o, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        if (shown) Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (picked != null) Text(if (picked == q.answer) "Correct" else "Incorrect: you chose ${'A' + picked}. The answer is ${'A' + q.answer}) ${q.options[q.answer]}",
                fontWeight = FontWeight.Bold, color = if (picked == q.answer) LocalExtra.current.ok else LocalExtra.current.bad)
            else Text("Answer: ${'A' + q.answer}) ${q.options[q.answer]}", fontWeight = FontWeight.Bold)
            Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
            if (q.quote.isNotBlank()) Text("From your document: “${q.quote}”", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Text(q.verification.label, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn)
            if (picked != null) GradientButton(if (m.index.value < m.questions.size - 1) "Next question" else if (m.busy.value) "Writing more…" else "Next batch",
                m::next, Modifier.fillMaxWidth(), enabled = !m.busy.value)
        }
    }
}

/** Question Bank → Generate: open interview questions from the resume, skills, projects or a topic; kept apart from the curated bank. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionGenView(vm: AppViewModel) {
    val m = vm.mcq
    PageBody(gap = Dimens.gap) {
        PracticeSource(vm, showBatch = false)
        SettingsGroup("Question type", accent = Accent.CHAT) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CareerQuestions.Type.entries.forEach { t -> FilterChip(m.qType.value == t, { m.qType.value = t }, { Text(t.label) }) }
                CareerQuestions.COUNTS.forEach { n -> FilterChip(m.qCount.value == n, { m.qCount.value = n }, { Text("$n questions") }) }
            }
            GradientButton(if (m.qBusy.value) "Writing questions…" else "Generate questions", m::generateQuestions, Modifier.fillMaxWidth(), enabled = !m.qBusy.value, busy = m.qBusy.value)
            m.qError.value?.let { ErrorBanner(it, onRetry = { m.generateQuestions() }) }
        }
        if (m.generated.isEmpty()) EmptyState("✨", "No generated questions yet", "They stay here, separate from the curated Question Bank.")
        m.generated.take(60).forEach { g ->
            var open by remember(g) { mutableStateOf(false) }
            Glass(onClick = { open = !open }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill(g.type); Pill(g.topic.take(24)); Pill("AI", MaterialTheme.colorScheme.secondary) }
                Text(g.question, style = MaterialTheme.typography.titleSmall)
                if (open) { MarkdownText(g.outline, color = LocalExtra.current.muted)
                    Text("Outline written by the AI: a guide, not a verified answer.", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.warn)
                    TextButton({ m.deleteGenerated(g) }) { Text("Remove") } }
                else Text("Tap to show the answer outline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
