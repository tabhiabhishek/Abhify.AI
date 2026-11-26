package ai.abhify.android.ui

import ai.abhify.android.learn.StudyCore
import ai.abhify.android.learn.StudyData
import ai.abhify.android.learn.StudyService
import ai.abhify.android.ui.StudyState.CourseTab
import ai.abhify.android.ui.StudyState.Screen
import ai.abhify.android.ui.StudyState.Section
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

// ---------------------------------------------------------------------- 7.2 Study: Prepare (courses, plan, stories)

/** Study → Prepare: Today, Courses, Interview plan and STAR stories, with the course, lesson and revision screens. */
@Composable
fun StudyPrepView(vm: AppViewModel) {
    val s = vm.study
    LaunchedEffect(Unit) { if (s.st.value == null && s.screen.value == Screen.HOME) s.refresh() }
    DisposableEffect(Unit) { onDispose { s.cancel() } }                 // leaving Prepare stops any AI work it started
    androidx.activity.compose.BackHandler(enabled = s.screen.value != Screen.HOME) { s.back() }
    Column(Modifier.fillMaxSize()) {
        if (s.screen.value == Screen.HOME)
            SectionChips(Section.entries.map { it.label }, s.section.value.ordinal, Modifier.padding(horizontal = Dimens.gutter, vertical = Dimens.tiny)) { s.open(Section.entries[it]) }
        Box(Modifier.weight(1f)) {
            when (s.screen.value) {
                Screen.HOME -> when (s.section.value) {
                    Section.TODAY -> TodayView(vm); Section.COURSES -> CoursesView(vm); Section.PLAN -> PlanView(vm); Section.STORIES -> StoriesView(vm)
                }
                Screen.PREFS -> PrefsView(vm)
                Screen.COURSE -> CourseView(vm)
                Screen.LESSON -> LessonView(vm)
                Screen.REVISION -> PageBody { BackLink("Back") { s.back() }; Notices(vm); RevisionContent(vm) }
                Screen.STORY -> StoryEditor(vm)
                Screen.MOCK -> MockView(vm)
                Screen.TAILOR -> TailorView(vm)
            }
        }
    }
    StudyOfferDialog(vm)
}

// ---------------------------------------------------------------------- small pieces
@Composable
private fun BackLink(text: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.semantics { contentDescription = text }) { Text("← $text") }
}

@Composable
private fun Busy(text: String) {
    Glass {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** The running task, the last error (with a way to model setup when there's no model) and the last confirmation. */
@Composable
private fun Notices(vm: AppViewModel, retry: (() -> Unit)? = null) {
    val s = vm.study
    s.busy.value?.let { Busy(it) }
    s.error.value?.let { e ->
        Glass {
            Text(e, color = LocalExtra.current.bad, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                if (s.noModel) OutlinedButton({ vm.go(AppViewModel.Route.MODELS) }) { Text("Open AI Models") }
                else retry?.let { OutlinedButton(it) { Text("Try again") } }
                TextButton({ s.error.value = null }) { Text("Dismiss") }
            }
        }
    }
    s.message.value?.let { m ->
        LaunchedEffect(m) { delay(4_000); if (s.message.value == m) s.message.value = null }
        Text(m, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.ok, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun Bar(value: Int, label: String) {
    val v = value.coerceIn(0, 100)
    LinearProgressIndicator({ v / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).clearAndSetSemantics { contentDescription = "$label: $v%" },
        color = Accent.STUDY.g2, trackColor = LocalExtra.current.line, drawStopIndicator = {}, gapSize = 0.dp)
}

@Composable
private fun Ring(value: Int, label: String) {
    val v = value.coerceIn(0, 100)
    Box(Modifier.size(68.dp).clearAndSetSemantics { contentDescription = "$label: $v%" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator({ v / 100f }, Modifier.size(68.dp), color = Accent.STUDY.g2, strokeWidth = 6.dp, trackColor = LocalExtra.current.line, gapSize = 0.dp)
        Text("$v%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

@Composable
private fun Bullets(items: List<String>) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }

@Composable
private fun Tiles(tiles: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                row.forEach { (v, l) -> StatTile(v, l, Modifier.weight(1f), accent = Accent.STUDY) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium); sub?.let { Muted(it) } }
    }
}

@Composable
private fun StudyConfirm(title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit) = Confirm(title, text, ok, onOk, onDismiss)

/** Real study time: counted every 15 s only while this screen is shown and the app is in front; reported each minute. */
@Composable
private fun StudyTimer(s: StudyState, courseId: String) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(courseId) {
        var pending = 0L
        try {
            while (true) {
                delay(15_000)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pending += 15
                if (pending >= 60) { s.reportTime(courseId, pending); pending = 0 }
            }
        } finally { s.reportTime(courseId, pending) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(options: List<String>, picked: Int?, answer: Int, onPick: (Int) -> Unit) {
    val x = LocalExtra.current
    options.forEachIndexed { i, o ->
        val c = when { picked == null -> x.line; i == answer -> x.ok; i == picked -> x.bad; else -> x.line }
        val mark = picked != null && (i == answer || i == picked)
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius))
            .background(if (mark) c.copy(alpha = 0.10f) else Color.Transparent)
            .border(if (mark) 2.dp else 1.dp, c, RoundedCornerShape(Dimens.fieldRadius))
            .clickable(enabled = picked == null, onClickLabel = "Answer ${'A' + i}") { onPick(i) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(if (picked != null && i == answer) x.ok else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Text("${'A' + i}", style = MaterialTheme.typography.labelLarge, color = if (picked != null && i == answer) Color.White else MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.width(12.dp))
            Text(o, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Feedback(ok: Boolean, answer: Int, explanation: String) {
    val c = if (ok) LocalExtra.current.ok else LocalExtra.current.bad
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.fieldRadius)).background(c.copy(alpha = 0.10f)).padding(12.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (ok) "Correct." else "Not quite: the answer is ${'A' + answer}.", fontWeight = FontWeight.Bold, color = c)
        if (explanation.isNotBlank()) Text(explanation, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------- Today
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayView(vm: AppViewModel) {
    val s = vm.study
    PageBody {
        Notices(vm) { s.refresh() }
        val st = s.st.value
        if (st == null) { if (s.loading.value) Busy("Opening Study…"); return@PageBody }
        QualityBanner(vm)
        val r = st.readiness; val cards = s.cardsDue.intValue; val q = st.reviewsDue
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(r.overall, "Interview readiness"); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Title("${if (st.plan != null) "Interview readiness" else "Your readiness"}: ${r.level}")
                    Muted((if (st.plan != null) "For: ${st.plan.jdTitle.ifEmpty { "your job description" }}" else "From your course practice. Make an interview plan to score it against a job.") +
                        if (r.skills.isEmpty()) " Answer some practice questions to see your scores." else "")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("🔥 ${st.streak} day${if (st.streak == 1) "" else "s"} streak"); Pill("$cards flashcard${if (cards == 1) "" else "s"} due")
                Pill("$q missed question${if (q == 1) "" else "s"} to review")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                GradientButton(if (cards + q > 0) "Daily revision (${cards + q})" else "Daily revision", { s.openRevision(false) }, Modifier.weight(1f), enabled = cards + q > 0)
                OutlinedButton({ s.openRevision(true) }, Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text("Weekly revision") }
            }
        }
        if (st.today.isNotEmpty()) Glass {
            Row(verticalAlignment = Alignment.CenterVertically) { Title("Today's plan"); Spacer(Modifier.weight(1f)); TextButton({ s.open(Section.PLAN) }) { Text("Open plan") } }
            st.today.forEach { PlanItemRow(vm, it.item, it.key, it.done) }
        }
        if (r.weak.isNotEmpty()) Glass {
            Title("Weak areas")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.weak.forEach { w -> FilterChip(false, { studyOrOffer(vm, w) }, { Text(w) }) } }
        }
        if (st.courses.isNotEmpty()) Glass {
            Title("Continue learning")
            st.courses.forEach { c -> CourseRow(c) { s.openCourse(c.id) } }
        }
        if (st.courses.isEmpty() && st.plan == null && st.stories == 0) Glass {
            Title("Start preparing")
            Muted("Pick a course to learn step by step, or paste a job description to get a day-by-day interview plan. Everything stays on this phone.")
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                GradientButton("Choose a course", { s.open(Section.COURSES) }, Modifier.weight(1f))
                OutlinedButton({ s.open(Section.PLAN) }, Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text("Make an interview plan") }
            }
        }
        InterviewPracticeCard(vm)
        StudySettingsCard(vm)
        InfoNote("Saved on this phone only (not backed up or synced). Lessons and questions are written by the AI model on this phone; every number here comes from what you did.")
        ExportImport(vm)
    }
}

/** Export / import of Study data as a file the user picks (Storage Access Framework: no permission, nothing synced). */
@Composable
private fun ExportImport(vm: AppViewModel) {
    val s = vm.study
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) s.exportTo(uri) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> pending = uri }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
        TextButton({ save.launch("abhify-study.json") }) { Text("Export study data") }
        TextButton({ open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import…") }
    }
    pending?.let { uri -> StudyConfirm("Import study data?", "Your current study data on this phone is replaced by the file's. A copy of it is kept on the phone first.", "Import",
        { s.importFrom(uri) }) { pending = null } }
}

@Composable
private fun CourseRow(c: StudyService.CourseSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.fieldRadius)).clickable(onClickLabel = "Open ${c.title}", onClick = onClick).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Muted("${c.done}/${c.total} lessons") }
        Bar(if (c.total > 0) 100 * c.done / c.total else 0, "${c.title} progress")
    }
}

/** A skill from the plan or weak areas: its ready-made course, else (after asking) a custom course on it. */
private fun studyOrOffer(vm: AppViewModel, skill: String, practise: Boolean = false) {
    if (!vm.study.studySkill(skill, practise)) vm.study.offerCustom.value = skill
}

@Composable
private fun PlanItemRow(vm: AppViewModel, it: StudyCore.PlanItem, key: String, done: Boolean) {
    val s = vm.study
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CheckRow(it.title, done, "${it.minutes} min") { v -> s.planItem(key, v) } }
        val real = it.skill !in setOf("All skills", "Missed questions", StudyCore.BEHAVIOURAL)
        when {
            it.type == "lesson" && real -> TextButton({ studyOrOffer(vm, it.skill) }) { Text("Study") }
            it.type == "mcq" && real -> TextButton({ studyOrOffer(vm, it.skill, true) }) { Text("Practise") }
            it.type == "story" -> TextButton({ s.open(Section.STORIES) }) { Text("Stories") }
            it.type == "review" -> TextButton({ s.openRevision(false) }) { Text("Revise") }
        }
    }
}

// ---------------------------------------------------------------------- Courses
@Composable
private fun CoursesView(vm: AppViewModel) {
    val s = vm.study
    var topic by rememberSaveable { mutableStateOf("") }
    PageBody {
        Notices(vm) { s.refresh() }
        val st = s.st.value; val t = s.tracks.value
        if (st == null || t == null) { if (s.loading.value) Busy("Loading courses…"); return@PageBody }
        if (st.courses.isNotEmpty()) {
            SectionTitle("My courses")
            Glass { st.courses.forEach { c -> CourseRow(c) { s.openCourse(c.id) } } }
        }
        SectionTitle(if (st.courses.isEmpty()) "Choose a course" else "Add a course")
        val mine = st.courses.mapNotNull { it.track }.toSet()
        t.tracks.filter { it.id !in mine }.forEach { tr ->
            Glass(onClick = { s.addTrack(tr.id) }) {
                Text("${tr.icon} ${tr.title}", style = MaterialTheme.typography.titleSmall)
                Muted(tr.summary)
                Muted("${tr.modules.size} modules · ${tr.modules.sumOf { it.lessons.size }} lessons")
            }
        }
        Glass {
            Title("Or any topic")
            OutlinedTextField(topic, { topic = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Topic") }, placeholder = { Text("e.g. Kafka, System design for fintech, Rust") }, singleLine = true)
            Muted("The AI on this phone writes a course outline for this topic; check it covers what you need.")
            GradientButton("Create course", { s.customCourse(topic) }, Modifier.fillMaxWidth(), enabled = topic.trim().length >= 2 && s.busy.value == null)
        }
    }
}

// ---------------------------------------------------------------------- preferences
@Composable
private fun PrefsView(vm: AppViewModel) {
    val s = vm.study
    val d = s.dash.value
    if (d == null) { PageBody { BackLink("Courses") { s.back() }; Notices(vm) }; return }
    val p = d.prefs
    val customTarget = p.chosen && p.target !in StudyService.TARGETS
    var target by rememberSaveable(d.id) { mutableStateOf(if (!p.chosen) "" else if (customTarget) CUSTOM else p.target) }
    var targetText by rememberSaveable(d.id) { mutableStateOf(if (customTarget) p.target else "") }
    var level by rememberSaveable(d.id) { mutableStateOf(if (!p.chosen) "" else if (p.levelKey == "custom") CUSTOM else p.levelKey) }
    var levelText by rememberSaveable(d.id) { mutableStateOf(if (p.levelKey == "custom") p.level else "") }
    var minutes by rememberSaveable(d.id) { mutableStateOf(p.minutes) }
    PageBody {
        BackLink("Courses") { s.back() }
        Notices(vm)
        Glass {
            Title("${d.title}: your learning journey")
            Muted("Tell Abhify what you're aiming for. Lessons, practice level and the plan follow your choice. You can change it any time; your progress is kept.")
            SectionTitle("Your goal")
            (StudyService.TARGETS + CUSTOM).forEach { t -> RadioRow(if (t == CUSTOM) "Custom goal" else t, target == t) { target = t } }
            if (target == CUSTOM) OutlinedTextField(targetText, { targetText = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Your goal") },
                placeholder = { Text("e.g. Robotics perception engineer") }, singleLine = true)
            SectionTitle("Your level")
            (StudyService.LEVELS.keys + CUSTOM).forEach { k -> RadioRow(StudyService.LEVELS[k] ?: "Custom level", level == k) { level = k } }
            if (level == CUSTOM) OutlinedTextField(levelText, { levelText = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Your level") },
                placeholder = { Text("e.g. Strong in Python, new to cloud") }, singleLine = true)
            SectionTitle("Time per day")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { StudyState.MINUTES.forEach { m -> FilterChip(minutes == m, { minutes = m }, { Text("$m min") }) } }
            val t = if (target == CUSTOM) targetText.trim() else target
            val l = if (level == CUSTOM) levelText.trim() else level
            GradientButton(if (p.chosen) "Update my plan" else "Build my learning plan", { s.savePrefs(t, l, minutes) }, Modifier.fillMaxWidth(),
                enabled = t.isNotEmpty() && l.isNotEmpty(), busy = s.busy.value != null)
            if (p.chosen) TextButton({ s.cancelPrefs() }) { Text("Cancel") }
        }
    }
}

private const val CUSTOM = "__custom"

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------- one course
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseView(vm: AppViewModel) {
    val s = vm.study
    val d = s.dash.value; val det = s.detail.value
    val list = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf(false) }
    if (d == null || det == null) { PageBody { BackLink("Courses") { s.back() }; Notices(vm) }; return }
    val tab = s.courseTab.value
    if (tab == CourseTab.PRACTICE) {
        StudyTimer(s, d.id)
        // unlimited practice: when the last item (the loader) is visible, ask for the next batch (one at a time)
        LaunchedEffect(list, d.id) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 }.distinctUntilChanged()
                .collect { atEnd -> if (atEnd && s.courseTab.value == CourseTab.PRACTICE) s.morePractice() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = Dimens.gutter, vertical = Dimens.small),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap), horizontalAlignment = Alignment.CenterHorizontally) {
        val w = Modifier.widthIn(max = Dimens.maxWidth).fillMaxWidth()
        item { Column(w) { BackLink("Courses") { s.back() } } }
        item { Column(w, verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { Notices(vm) } }
        item {
            Glass(w) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Muted("Goal: ${d.prefs.target} · ${d.prefs.level} · ${d.prefs.minutes} min a day")
                        TextButton({ s.editPrefs() }, contentPadding = PaddingValues(0.dp)) { Text("Change goal or level") }
                    }
                    Ring(d.progress, "Course progress")
                }
            }
        }
        item {
            Column(w) {
                Tiles(listOfNotNull("${d.lessonsDone}/${d.lessonsTotal}" to "Lessons", "${d.modulesDone}/${d.modulesTotal}" to "Modules",
                    (d.assessment.percent?.let { "$it%" } ?: "–") to (if (d.assessment.answered > 0) "Score · ${d.assessment.answered} answers" else "Score · no answers yet"),
                    StudyState.duration(d.seconds) to "Time studied · ${StudyState.duration(d.secondsWeek)} this week", "${d.exercisesDone}" to "Exercises",
                    "${d.streak} day${if (d.streak == 1) "" else "s"}" to "Streak",
                    "${d.revision.cardsDue + d.revision.questionsDue}" to "To revise · ${d.revision.wrongThisWeek} wrong this week",
                    d.capstone?.let { "${it.done}/${it.total}" to "Capstone" }))
            }
        }
        item {
            Column(w, verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
                d.nextLesson?.let { n -> GradientButton("Next: ${n.title}", { s.openLesson(n.id) }, Modifier.fillMaxWidth()) } ?: Pill("All lessons done 🎉", LocalExtra.current.ok)
                d.weak.firstOrNull()?.let { (id, title) -> OutlinedButton({ s.practiseModule(id, weak = true) }, Modifier.fillMaxWidth().heightIn(min = Dimens.touch)) { Text("Revise weak topic: $title") } }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton({ s.reportFile { f -> sharePdf(ctx, f) { s.error.value = it } } }, Modifier.fillMaxWidth().heightIn(min = Dimens.touch), enabled = s.busy.value == null) {
                    Text("Share progress report (PDF)") }
            }
        }
        item { Column(w) { BrainCard(vm, det.course) } }
        d.planChange?.let { pc -> item {
            Glass(w) {
                Title("Your plan was updated")
                Muted("Before: ${pc.previousTarget} · ${pc.previousLevel}. Your ${pc.kept} finished lesson${if (pc.kept == 1) " is" else "s are"} kept and never assigned again.")
                if (pc.nowCore.isNotEmpty()) Text("Now part of your path: ${pc.nowCore.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                if (pc.nowOptional.isNotEmpty()) Text("Now optional review: ${pc.nowOptional.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                if (pc.nowCore.isEmpty() && pc.nowOptional.isEmpty()) Muted("The same modules are core and optional as before.")
            }
        } }
        item {
            val ov = d.overview
            if (ov == null) Glass(w) {
                Muted("No course overview yet.")
                OutlinedButton({ s.buildOverview() }, enabled = s.busy.value == null) { Text("Build overview") }
            } else {
                var open by rememberSaveable(d.id) { mutableStateOf(d.lessonsDone == 0) }
                Glass(w, onClick = { open = !open }) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Title("Course overview"); Spacer(Modifier.weight(1f)); Muted(if (open) "Hide" else "Show") }
                    if (open) {
                        Text(ov.summary, style = MaterialTheme.typography.bodyMedium)
                        if (ov.objectives.isNotEmpty()) { SectionTitle("You will be able to"); Bullets(ov.objectives) }
                        if (ov.prerequisites.isNotEmpty()) { SectionTitle("Prerequisites"); Bullets(ov.prerequisites) }
                        SectionTitle("Completion criteria"); Bullets(ov.completion)
                    }
                }
            }
        }
        item { Column(w) { SegmentedTabs(CourseTab.entries.map { it.label }, tab.ordinal, accent = Accent.STUDY) { s.pickTab(CourseTab.entries[it]) } } }
        when (tab) {
            CourseTab.ROADMAP -> roadmap(vm, d, det, w)
            CourseTab.PRACTICE -> practice(vm, det, w)
            CourseTab.CARDS -> item { Column(w, verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { RevisionContent(vm) } }
            CourseTab.CAPSTONE -> item { Column(w) { CapstoneCard(vm, det.course.capstone) } }
            CourseTab.PROGRESS -> item { Column(w, verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { ProgressCards(vm, d, det) } }
        }
        if (tab != CourseTab.PRACTICE) item {
            Column(w) {
                TextButton({ confirmDelete = true }) { Text("Delete this course", color = LocalExtra.current.bad) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) StudyConfirm("Delete this course?", "Its progress, written lessons and flashcards are deleted from this phone. Your interview plan and stories stay.", "Delete",
        { s.deleteCourse(d.id) }) { confirmDelete = false }
}

private fun LazyListScope.roadmap(vm: AppViewModel, d: StudyService.Dashboard, det: StudyService.CourseDetail, w: Modifier) {
    val s = vm.study
    val mastery = d.mastery.associateBy { it.mastery.module }
    val focus = d.overview?.focus.orEmpty()
    itemsIndexed(det.modules, key = { _, m -> "mod-" + m.module.id }) { i, mv ->
        val r = mastery[mv.module.id]
        Glass(w) {
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mv.module.title, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r?.optional == true) Pill("optional review", LocalExtra.current.muted)
                        if (r?.mastery?.weak == true) Pill("needs revision", LocalExtra.current.bad)
                    }
                    focus[mv.module.id]?.let { Muted(it) }
                    r?.let { Muted(StudyState.levelLabel(it.mastery)) }
                }
            }
            r?.let { Bar(it.mastery.score, "${mv.module.title} mastery") }
            mv.lessons.forEach { l ->
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(Dimens.fieldRadius))
                    .clickable(onClickLabel = "Open lesson") { s.openLesson(l.id) }
                    .semantics(mergeDescendants = true) { contentDescription = l.title + when { l.done -> ", done"; l.ready -> ", written"; else -> "" } }
                    .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (l.done) "✓" else if (l.ready) "●" else "○", color = if (l.done) LocalExtra.current.ok else MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(24.dp))
                    Text(l.title, style = MaterialTheme.typography.bodyMedium, color = if (l.done) LocalExtra.current.muted else MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton({ s.practiseModule(mv.module.id) }) { Text("Practise this module") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.practice(vm: AppViewModel, det: StudyService.CourseDetail, w: Modifier) {
    val s = vm.study
    item {
        Glass(w) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title("Practise"); Spacer(Modifier.weight(1f))
                Pill(if (s.practiceAnswered.intValue == 0) "0 answered" else "${s.practiceRight.intValue}/${s.practiceAnswered.intValue} right")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(s.practiceModule.value == null && !s.practiceWeak.value, { s.startPractice(null, false) }, { Text("All modules") })
                FilterChip(s.practiceWeak.value, { s.startPractice(null, true) }, { Text("Weak topics first") })
                det.modules.forEach { m -> FilterChip(s.practiceModule.value == m.module.id, { s.startPractice(m.module.id, false) }, { Text(m.module.title) }) }
            }
            Muted("Questions keep coming as you scroll. Level follows your course goal. AI-generated: not independently verified.")
        }
    }
    itemsIndexed(s.practice, key = { i, p -> "q-$i-" + p.q.key }) { i, p ->
        Glass(w) {
            Row { Muted(p.q.module); Spacer(Modifier.weight(1f)); Pill("AI", MaterialTheme.colorScheme.secondary) }
            Text("${i + 1}. ${p.q.q}", style = MaterialTheme.typography.titleSmall)
            Options(p.q.options, p.picked, p.q.answer) { k -> s.pick(i, k) }
            p.picked?.let { Feedback(it == p.q.answer, p.q.answer, p.q.explanation) }
        }
    }
    item(key = "more") {
        Column(w, verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            when {
                s.practiceBusy.value -> Busy("Writing new questions… on this phone this can take a minute.")
                s.practiceError.value != null -> Glass {
                    Text(s.practiceError.value.let { if (it == StudyState.NO_NEW) it else "Couldn't write more questions: $it" }, color = LocalExtra.current.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                        if (s.practiceError.value == StudyState.NO_MODEL) OutlinedButton({ vm.go(AppViewModel.Route.MODELS) }) { Text("Open AI Models") }
                        OutlinedButton({ s.morePractice(force = true) }) { Text("Retry") }
                    }
                }
                else -> TextButton({ s.morePractice() }, Modifier.fillMaxWidth()) { Text("More questions") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CapstoneCard(vm: AppViewModel, cap: StudyData.Capstone?) {
    val s = vm.study
    Glass {
        if (cap == null) {
            Title("Capstone project")
            Muted("One project that uses what this course teaches, sized for your goal and level, doable on a laptop for free.")
            GradientButton("Plan my capstone", { s.makeCapstone() }, Modifier.fillMaxWidth(), busy = s.busy.value != null)
        } else {
            Title(cap.title)
            Text(cap.brief, style = MaterialTheme.typography.bodyMedium)
            cap.milestones.forEachIndexed { i, m -> CheckRow(m.title, m.done) { v -> s.capstoneStep(i, v) } }
            if (cap.stretch.isNotEmpty()) { SectionTitle("Stretch goals"); Bullets(cap.stretch) }
            TextButton({ s.makeCapstone() }, enabled = s.busy.value == null) { Text("Plan a different capstone") }
        }
    }
}

@Composable
private fun ProgressCards(vm: AppViewModel, d: StudyService.Dashboard, det: StudyService.CourseDetail) {
    val s = vm.study
    val sc = d.schedule; val today = java.time.LocalDate.now().toString()
    val lessonTitle = det.modules.flatMap { it.lessons }.associate { it.id to it.title }
    val modTitle = det.modules.associate { it.module.id to it.module.title }
    Glass {
        Title("Weekly milestones")
        if (sc.weeks.isEmpty()) Text("Every lesson is done.", style = MaterialTheme.typography.bodyMedium)
        sc.weeks.forEach { w -> Text("Week ${w.week} (${w.from} → ${w.to}): ${w.lessons} lessons" +
            (if (w.modulesCompleted.isNotEmpty()) " · finish " + w.modulesCompleted.joinToString(", ") { modTitle[it] ?: it } else ""), style = MaterialTheme.typography.bodyMedium) }
        Muted("${sc.perDay} lesson${if (sc.perDay == 1) "" else "s"} a day at ${d.prefs.minutes} min, a revision day every 7th day. It moves with you: done lessons drop out.")
    }
    Glass {
        Title("Next 7 days")
        sc.days.take(7).forEach { x ->
            Text((if (x.date == today) "Today · " else "") + x.date + ": " + (if (x.kind == "revision") "Revision day (flashcards and missed questions)" else x.lessons.joinToString(", ") { lessonTitle[it] ?: it }),
                style = MaterialTheme.typography.bodyMedium, fontWeight = if (x.date == today) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (sc.days.isEmpty()) Muted("Nothing scheduled: every lesson is done.")
    }
    Glass {
        Title("Weak topics")
        if (d.weak.isEmpty()) Muted("None yet: a module is weak after 3+ answers under 60% right.")
        d.weak.forEach { (id, title) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); TextButton({ s.practiseModule(id) }) { Text("Practise") } } }
    }
    Glass {
        Title("Learning history")
        if (d.history.isEmpty()) Muted("Nothing yet.")
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        d.history.forEach { h ->
            val kind = mapOf("lesson" to "Read", "done" to "Finished", "exercise" to "Exercise done", "cards" to "Flashcards made", "capstone" to "Capstone planned",
                "capstone step" to "Capstone step done", "tutor" to "Asked the tutor about", "prefs" to "Goal set")[h.kind] ?: h.kind
            Text("${fmt.format(java.util.Date(h.at))} · $kind: ${h.detail}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------- a lesson
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonView(vm: AppViewModel) {
    val s = vm.study
    val det = s.detail.value; val lid = s.lessonId.value
    val mod = det?.modules?.firstOrNull { m -> m.lessons.any { it.id == lid } }
    val l = mod?.lessons?.firstOrNull { it.id == lid }
    var question by rememberSaveable(lid) { mutableStateOf("") }
    PageBody {
        BackLink(det?.course?.title ?: "Course") { s.back() }
        if (det == null || mod == null || l == null || lid == null) { Notices(vm); return@PageBody }
        Notices(vm) { s.openLesson(lid) }
        val les = s.lesson.value?.takeIf { it.entry.lesson == lid } ?: return@PageBody
        StudyTimer(s, det.course.id)
        Glass {
            Muted("${mod.module.title} · lesson ${mod.lessons.indexOf(l) + 1} of ${mod.lessons.size}")
            Text(l.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            MarkdownText(les.entry.markdown)
            if (les.entry.markdown.contains("```mermaid")) Muted("Diagrams are shown as text on Android.")
            if (les.entry.used.isNotEmpty()) Text("Used: " + les.entry.used.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            Muted("AI-written on this phone for your goal and level" + (if (les.cached) " (saved ${java.text.DateFormat.getDateInstance().format(java.util.Date(les.entry.at))})" else "") +
                "; not independently verified. Check anything important against official docs.")
        }
        val done = lid in det.course.done; val ex = lid in det.course.exercises
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.small), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            if (done) OutlinedButton(s::toggleLessonDone, Modifier.semantics { contentDescription = "Lesson done. Tap to mark it not done." }) { Text("✓ Lesson done") }
            else GradientButton("Mark lesson done", s::toggleLessonDone)
            OutlinedButton(s::toggleExercise, Modifier.semantics { contentDescription = if (ex) "Exercise done. Tap to undo." else "I did the exercise" }) { Text(if (ex) "✓ Exercise done" else "I did the exercise") }
            OutlinedButton(s::makeCards, enabled = s.busy.value == null && !s.cardsMade.value) { Text(if (s.cardsMade.value) "✓ Flashcards made" else "Make flashcards") }
            OutlinedButton({ s.practiseModule(mod.module.id) }) { Text("Practise this module") }
            OutlinedButton({ s.openLesson(lid, refresh = true) }, enabled = s.busy.value == null) { Text("Rewrite lesson") }
            s.nextLessonAfter(lid)?.let { n -> OutlinedButton({ s.openLesson(n) }) { Text("Next lesson →") } }
        }
        Glass {
            Title("Ask the AI tutor about this lesson")
            OutlinedTextField(question, { question = it.take(1500) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Your question") },
                placeholder = { Text("e.g. Why is this better than the alternative? Show me another example.") })
            Muted("The tutor sees this lesson and your goal, nothing else from your chats or files.")
            OutlinedButton({ s.askTutor(question); question = "" }, enabled = question.isNotBlank() && s.busy.value == null) { Text("Ask") }
            s.tutor.forEach { t ->
                Text("You: ${t.question}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                when {
                    t.answer != null -> MarkdownText(t.answer)
                    t.error != null -> Text("The tutor couldn't answer: ${t.error}", color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall)
                    else -> Muted("Thinking…")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- revision
@Composable
private fun RevisionContent(vm: AppViewModel) {
    val s = vm.study
    if (s.loading.value) { Busy("Loading revision…"); return }
    val items = s.revItems
    val weekly = s.revWeekly.value
    if (items.isEmpty()) {
        EmptyState("🃏", if (weekly) "Nothing to revise from this week" else "Nothing due today",
            if (weekly) "Open lessons and make flashcards; they show up here for a week." else "Missed questions and flashcards come back here on the right day.")
        return
    }
    val i = s.revIndex.intValue
    if (i >= items.size) {
        Glass {
            Title("Revision done 🎉")
            Text("${s.revRight.intValue} of ${items.size} remembered. Items you missed come back tomorrow.")
            OutlinedButton({ s.openRevision(weekly, s.revCourse.value) }) { Text("Start again") }
        }
        return
    }
    Muted("${if (weekly) "Weekly" else "Daily"} revision · ${i + 1} of ${items.size}")
    when (val it = items[i]) {
        is StudyState.RevItem.CardItem -> {
            var shown by remember(it.id, i) { mutableStateOf(false) }
            Glass(onClick = { shown = true }, modifier = Modifier.semantics { contentDescription = if (shown) "Flashcard answer: ${it.card.back}" else "Flashcard: ${it.card.front}. Tap to show the answer." }) {
                Muted(it.card.title)
                Text(it.card.front, style = MaterialTheme.typography.titleMedium)
                if (shown) Text(it.card.back, style = MaterialTheme.typography.bodyLarge) else Muted("Tap to show the answer")
            }
            if (shown) Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                OutlinedButton({ s.gradeCard(it.id, false) }, Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text("Not yet") }
                GradientButton("I remembered", { s.gradeCard(it.id, true) }, Modifier.weight(1f))
            }
        }
        is StudyState.RevItem.Missed -> {
            val q = it.due.review.question
            var picked by remember(it.due.key, i) { mutableStateOf<Int?>(null) }
            Glass {
                Muted("You missed this · ${it.due.review.skill}")
                Text(q.q, style = MaterialTheme.typography.titleSmall)
                Options(q.options, picked, q.answer) { k -> picked = k; s.answerMissed(it.due, k) }
                picked?.let { p -> Feedback(p == q.answer, q.answer, q.explanation); GradientButton("Next", s::nextRevision, Modifier.fillMaxWidth()) }
            }
        }
    }
}

// ---------------------------------------------------------------------- interview plan
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanView(vm: AppViewModel) {
    val s = vm.study
    val st = s.st.value
    var confirm by remember { mutableStateOf(false) }
    PageBody {
        Notices(vm) { s.refresh() }
        if (st == null) { if (s.loading.value) Busy("Loading the plan…"); return@PageBody }
        val p = st.plan
        if (p == null || s.planForm.value) { PlanForm(vm, p != null); return@PageBody }
        val today = java.time.LocalDate.now()
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title(p.jdTitle.ifEmpty { "Your interview plan" })
                    val left = p.interview?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(it)) }.getOrNull() }
                    Muted((if (p.interview != null) "Interview ${p.interview}" + (if (left != null && left >= 0) " · $left day${if (left == 1L) "" else "s"} to go" else " (date passed)")
                        else "${p.days.size}-day plan (no interview date)") + " · ${p.minutes} min a day")
                }
                Ring(st.readiness.overall, "Readiness for this job")
            }
            Text("${st.readiness.level} · from your practice answers and finished lessons on these skills", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                GradientButton("Mock interview", { s.openMock() })
                OutlinedButton({ s.openTailor() }) { Text("Tailor resume") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                OutlinedButton({ s.planForm.value = true }) { Text("New plan") }
                TextButton({ confirm = true }) { Text("Delete", color = LocalExtra.current.bad) }
            }
            SectionTitle("The job asks for")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.skills.required.forEach { Pill(it) }; p.skills.preferred.forEach { Pill("$it (nice to have)", LocalExtra.current.muted) }
            }
            if (p.resumeSkills.isNotEmpty()) { SectionTitle("Found on your resume"); Muted(p.resumeSkills.joinToString(", ")) }
            SectionTitle("Focus, in order")
            p.gaps.forEachIndexed { i, g ->
                val r = st.readiness.skills.firstOrNull { it.skill == g.skill }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${i + 1}. ${g.skill}", style = MaterialTheme.typography.bodyMedium); Muted(g.why + (r?.let { " · ${it.score}%" } ?: "")) }
                    TextButton({ studyOrOffer(vm, g.skill) }) { Text("Study") }
                }
            }
        }
        p.days.forEach { d ->
            val isToday = d.date == today.toString()
            var open by rememberSaveable(d.day) { mutableStateOf(isToday) }
            val doneN = d.items.indices.count { "${d.day}:$it" in p.done }
            Glass(onClick = { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Day ${d.day} · ${d.date}" + (if (isToday) " · today" else ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Muted("$doneN/${d.items.size} done")
                }
                if (open) d.items.forEachIndexed { j, it -> PlanItemRow(vm, it, "${d.day}:$j", "${d.day}:$j" in p.done) }
            }
        }
    }
    if (confirm) StudyConfirm("Delete this interview plan?", "Your course progress and stories stay.", "Delete", { s.deletePlan() }) { confirm = false }
}

@Composable
private fun PlanForm(vm: AppViewModel, canCancel: Boolean) {
    val s = vm.study
    var jd by rememberSaveable { mutableStateOf("") }
    var resume by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(60) }
    Glass {
        Title("Interview plan from a job description")
        Muted("Paste the job description. Abhify finds the skills it asks for, compares them with your resume and makes a day-by-day plan until your interview. Everything stays on this phone; no AI is needed for this.")
        OutlinedTextField(jd, { jd = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 6, maxLines = 14, label = { Text("Job description") },
            placeholder = { Text("Paste the full job post, including the requirements") })
        OutlinedTextField(resume, { resume = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 10, label = { Text("Your resume or skills (optional)") },
            placeholder = { Text("Paste your resume text, or just a list of your skills") })
        if (vm.career.active() != null) TextButton({ s.resumeText { resume = it.take(20_000) } }) { Text("Use my resume from Career") }
        OutlinedTextField(date, { date = it.take(10) }, Modifier.fillMaxWidth(), label = { Text("Interview date (optional, YYYY-MM-DD)") }, singleLine = true,
            placeholder = { Text(java.time.LocalDate.now().plusDays(14).toString()) })
        SectionTitle("Time per day")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { StudyState.MINUTES.forEach { m -> FilterChip(minutes == m, { minutes = m }, { Text("$m min") }) } }
        GradientButton("Make my plan", { s.makePlan(jd, resume, date.trim().ifEmpty { null }, minutes) }, Modifier.fillMaxWidth(), enabled = jd.isNotBlank(), busy = s.busy.value != null)
        if (canCancel) TextButton({ s.planForm.value = false }) { Text("Cancel") }
    }
}

// ---------------------------------------------------------------------- STAR stories
private val STAR_FIELDS = listOf(Triple("situation", "Situation", "Where and when? What was going on?"), Triple("task", "Task", "What were you responsible for?"),
    Triple("action", "Action", "What did you do, step by step?"), Triple("result", "Result", "What changed? Numbers if you have them."))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoriesView(vm: AppViewModel) {
    val s = vm.study
    val comps = s.tracks.value?.competencies.orEmpty()
    val stories = s.stories
    PageBody {
        Notices(vm) { s.refresh() }
        Glass {
            Title("STAR story bank")
            Muted("Behavioural interviews ask for stories. Write each once (Situation, Task, Action, Result), get AI coaching, and cover all 8 common themes.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                comps.forEach { c -> val n = stories.count { c.id in it.competencies }
                    Pill((if (n > 0) "✓ " else "○ ") + c.title, if (n > 0) LocalExtra.current.ok else LocalExtra.current.muted) }
            }
            GradientButton("Write a story", s::newStory, Modifier.fillMaxWidth())
        }
        if (stories.isEmpty() && !s.loading.value) EmptyState("⭐", "No stories yet", comps.firstOrNull()?.let { "Start with: ${it.question}" })
        stories.sortedByDescending { it.updated }.forEach { st ->
            Glass(onClick = { s.editStory(st) }) {
                Text(st.title, style = MaterialTheme.typography.titleSmall)
                Muted(st.competencies.joinToString(", ") { k -> comps.firstOrNull { it.id == k }?.title ?: k }.ifEmpty { "no theme chosen" } +
                    (st.coach?.let { " · coached: ${it.scores.values.sum()}/20" } ?: ""))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryEditor(vm: AppViewModel) {
    val s = vm.study
    val st = s.editing.value
    val key = st?.id ?: "new"
    val comps = s.tracks.value?.competencies.orEmpty()
    var title by rememberSaveable(key) { mutableStateOf(st?.title.orEmpty()) }
    var chosen by rememberSaveable(key) { mutableStateOf(st?.competencies.orEmpty().joinToString(",")) }
    val fields = STAR_FIELDS.associate { (k, _, _) -> k to rememberSaveable(key, k) { mutableStateOf(st?.field(k).orEmpty()) } }
    var confirm by remember { mutableStateOf(false) }
    val picked = chosen.split(',').filter { it.isNotEmpty() }
    fun input() = StudyService.StoryInput(st?.id, title, picked, fields.getValue("situation").value, fields.getValue("task").value,
        fields.getValue("action").value, fields.getValue("result").value)
    PageBody {
        BackLink("Stories") { s.back() }
        Glass {
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
                placeholder = { Text("e.g. Rescued a failing data migration") })
            SectionTitle("Themes this story shows")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                comps.forEach { c -> val on = c.id in picked
                    FilterChip(on, { chosen = (if (on) picked - c.id else picked + c.id).joinToString(",") }, { Text(c.title) }) }
            }
            STAR_FIELDS.forEach { (k, label, hint) ->
                val f = fields.getValue(k)
                OutlinedTextField(f.value, { f.value = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(label) }, placeholder = { Text(hint) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                GradientButton("Save", { s.saveStory(input(), false) }, Modifier.weight(1f), enabled = title.isNotBlank() && s.busy.value == null)
                OutlinedButton({ s.saveStory(input(), true) }, Modifier.weight(1f).heightIn(min = Dimens.touch), enabled = title.isNotBlank() && s.busy.value == null) { Text("Save and get coaching") }
            }
            if (st != null) TextButton({ confirm = true }) { Text("Delete", color = LocalExtra.current.bad) }
        }
        Notices(vm) { s.saveStory(input(), true) }
        s.coach.value?.let { c ->
            Glass {
                Title("Coaching")
                STAR_FIELDS.forEach { (k, label, _) -> val v = c.scores[k] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.weight(1f)) { Bar(v * 20, "$label score") }; Spacer(Modifier.width(8.dp)); Text("$v/5", style = MaterialTheme.typography.labelLarge) } }
                if (c.missing.isNotEmpty()) { SectionTitle("Missing"); Bullets(c.missing) }
                if (c.tips.isNotEmpty()) { SectionTitle("Tips"); Bullets(c.tips) }
                if (c.improved.isNotEmpty()) {
                    SectionTitle("A tighter version (only your facts)")
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(c.improved, style = MaterialTheme.typography.bodyMedium) }
                }
                Muted("AI feedback from the model on this phone; your story stays as you wrote it.")
            }
        }
    }
    if (confirm) StudyConfirm("Delete this story?", "It is deleted from this phone.", "Delete", { st?.let { s.deleteStory(it.id) } }) { confirm = false }
}

/** "No ready-made course covers X. Create a course on it with the AI?" (from the plan or weak areas). */
@Composable
fun StudyOfferDialog(vm: AppViewModel) {
    val s = vm.study
    val skill = s.offerCustom.value ?: return
    StudyConfirm("Create a course on $skill?", "No ready-made course covers \"$skill\". The AI on this phone can write a course outline for it.", "Create course",
        { s.open(Section.COURSES); s.customCourse(skill) }) { s.offerCustom.value = null }
}

// ---------------------------------------------------------------------- 7.2.0-dev.2: Today extras (quality banner, interview practice, settings)
/** A small warning when the cached quality verdict for the model on this phone is weak or poor (the model is never blocked). */
@Composable
private fun QualityBanner(vm: AppViewModel) {
    val s = vm.study
    val q = s.quality.value?.takeIf { it.model == s.modelId() && it.verdict != "good" } ?: return
    Glass {
        Text("⚠ ${s.modelName() ?: q.model}: ${if (q.verdict == "weak") "weak" else "poor"} results in Study's quality check", color = LocalExtra.current.bad,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Muted(q.advice)
        TextButton({ vm.go(AppViewModel.Route.MODELS) }, contentPadding = PaddingValues(0.dp)) { Text("Open AI Models") }
    }
}

@Composable
private fun InterviewPracticeCard(vm: AppViewModel) {
    val s = vm.study
    Glass {
        Title("Interview practice")
        Muted("Rehearse a mock interview with a timer and AI feedback, or tailor your resume bullets to a job. Everything stays on this phone.")
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
            GradientButton("Mock interview", { s.openMock() }, Modifier.weight(1f))
            OutlinedButton({ s.openTailor() }, Modifier.weight(1f).heightIn(min = Dimens.touch)) { Text("Tailor my resume") }
        }
    }
}

private val PROBE_LABELS = mapOf("json_mcq" to "MCQ in JSON", "lesson_sections" to "lesson sections", "fact" to "a fact")

@Composable
private fun StudySettingsCard(vm: AppViewModel) {
    val s = vm.study
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) s.setReminder(true) else { s.setReminder(false); denied = true }
    }
    Glass {
        Title("Study settings")
        SwitchRow("Daily study reminder", "Off by default. One notification a day at the time you pick, with what is really due (for example \"3 flashcards due\"). No notification when nothing is due.",
            s.reminderOn.value) { on ->
            denied = false
            when {
                !on -> s.setReminder(false)
                android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED -> ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else -> s.setReminder(true)
            }
        }
        if (s.reminderOn.value) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("At about %02d:%02d".format(s.reminderHour.intValue, s.reminderMinute.intValue), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton({
                android.app.TimePickerDialog(ctx, { _, h, m -> s.setReminder(true, h, m) }, s.reminderHour.intValue, s.reminderMinute.intValue,
                    android.text.format.DateFormat.is24HourFormat(ctx)).show()
            }) { Text("Change time") }
        }
        if (denied) Text("Notifications weren't allowed, so the reminder stays off. You can allow notifications for Abhify in Android settings and turn it on again.",
            color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        SwitchRow("Offline lesson packs", "Off by default. While the phone is charging and idle (battery not low), the model on this phone writes the next 3 lessons of each course with a chosen goal, so they open instantly. Nothing is downloaded.",
            s.packsOn.value) { s.setPacks(it) }
        if (s.packsOn.value) Muted(s.packStatus() ?: "Not run yet. Android starts it when the phone is charging, idle and the battery isn't low.")
        SectionTitle("Model quality")
        val name = s.modelName()
        val q = s.quality.value?.takeIf { it.model == s.modelId() }
        when {
            name == null -> Muted("No AI model is on this phone yet.")
            q == null -> Muted("Check how well $name handles Study's lessons and questions: 3 short tests, run once and remembered. It never blocks the model.")
            else -> {
                Text("$name: ${q.verdict.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (q.verdict == "good") LocalExtra.current.ok else LocalExtra.current.bad)
                Muted(q.advice)
                Muted(StudyCore.PROBES.joinToString(" · ") { (if (q.probes[it] == true) "✓ " else "✗ ") + (PROBE_LABELS[it] ?: it) })
            }
        }
        OutlinedButton({ s.checkQuality() }, enabled = name != null && s.busy.value == null) { Text(if (q == null) "Check model quality" else "Check again") }
    }
}

// ---------------------------------------------------------------------- 7.2.0-dev.2: course page extras (personal context, progress report)
@Composable
private fun BrainCard(vm: AppViewModel, c: StudyData.Course) {
    val s = vm.study
    val b = c.brain
    var open by rememberSaveable(c.id) { mutableStateOf(false) }
    var enabled by remember(c.id, b) { mutableStateOf(b?.enabled == true) }
    var mems by remember(c.id, b) { mutableStateOf(b?.memories.orEmpty().toSet()) }
    var projs by remember(c.id, b) { mutableStateOf(b?.projects.orEmpty().toSet()) }
    LaunchedEffect(open) { if (open) s.loadBrainSources() }
    Glass(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Use my personal context"); Spacer(Modifier.weight(1f))
            Muted(if (b?.enabled == true) "On · ${b.memories.size + b.projects.size} item${if (b.memories.size + b.projects.size == 1) "" else "s"}" else "Off")
        }
        if (open) {
            Muted("Off by default. When on, this course's lessons and tutor answers may use only the items you tick here, to choose examples that fit you. Chat history is never included, and each lesson shows \"Used: …\".")
            if (s.brainSources.value?.memoryEnabled == false)
                Muted("Memory is off in Settings, so personal context can't be used in lessons. Your choices are kept for when you turn it back on.")
            SwitchRow("Use my personal context for this course", null, enabled) { enabled = it }
            if (enabled) {
                val src = s.brainSources.value
                if (src == null) Muted("Loading your memories and projects…")
                else {
                    if (src.goals.isEmpty() && src.memories.isEmpty() && src.projects.isEmpty())
                        Muted("No personal memories or projects on this phone yet. Add them in Personal memory or Projects.")
                    if (src.goals.isNotEmpty()) { SectionTitle("Goals"); src.goals.forEach { m -> CheckRow(m.text.take(140), m.id in mems) { v -> mems = if (v) mems + m.id else mems - m.id } } }
                    if (src.memories.isNotEmpty()) { SectionTitle("Memories"); src.memories.forEach { m -> CheckRow(m.text.take(140), m.id in mems, m.category.ifEmpty { null }) { v -> mems = if (v) mems + m.id else mems - m.id } } }
                    if (src.projects.isNotEmpty()) { SectionTitle("Projects"); src.projects.forEach { p -> CheckRow(p.name, p.id in projs) { v -> projs = if (v) projs + p.id else projs - p.id } } }
                    if (mems.size > 20 || projs.size > 10) Text("At most 20 memories and 10 projects are kept.", color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall)
                }
            }
            GradientButton("Save", { s.saveBrain(enabled, mems.toList(), projs.toList()) }, Modifier.fillMaxWidth(), enabled = s.busy.value == null)
        }
    }
}

private fun sharePdf(ctx: android.content.Context, f: java.io.File, onError: (String) -> Unit) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share progress report"))
    } catch (e: Exception) { onError("Couldn't share the report: ${e.message}") }
}

// ---------------------------------------------------------------------- 7.2.0-dev.2: mock interview
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockView(vm: AppViewModel) {
    val s = vm.study
    val m = s.mock.value
    var spoke by remember { mutableStateOf(false) }
    // leaving the interview stops the microphone and the question being read; nothing runs in the background
    DisposableEffect(Unit) { onDispose { if (vm.listening.value) vm.cancelVoice(); if (spoke) vm.stopSpeaking() } }
    PageBody {
        BackLink(if (m != null) "Interviews" else "Back") { s.back() }
        Notices(vm)
        when {
            m == null -> MockHome(vm)
            m.finished -> MockSummaryView(vm, m)
            else -> MockQuestionView(vm, m) { spoke = true }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockHome(vm: AppViewModel) {
    val s = vm.study
    var count by rememberSaveable { mutableStateOf(6) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val plan = s.st.value?.plan
    Glass {
        Title("Mock interview")
        Muted(if (plan != null) "Questions on your interview plan's skills (${plan.gaps.take(6).joinToString(", ") { it.skill }}) alternate with STAR behavioural themes."
            else "Questions on your weakest practice skills alternate with STAR behavioural themes. Make an interview plan to practise for a specific job.")
        Muted("One question at a time with a timer (aim for about 2 minutes). Answer by typing, or by voice: the microphone is used only while you tap \"Answer by voice\" on this screen. The AI on this phone scores relevance, depth and structure (1–5) and quotes only your own words.")
        SectionTitle("Questions")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(3, 6, 9, 12).forEach { n -> FilterChip(count == n, { count = n }, { Text("$n") }) } }
        GradientButton("Start interview", { s.startMock(count) }, Modifier.fillMaxWidth(), busy = s.busy.value != null)
    }
    if (s.mocks.isNotEmpty()) SectionTitle("Past interviews")
    val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    s.mocks.forEach { it ->
        Glass(onClick = { s.openMockSession(it.id) }) {
            Text(fmt.format(java.util.Date(it.created)) + (it.job?.takeIf { j -> j.isNotBlank() }?.let { j -> " · $j" } ?: ""), style = MaterialTheme.typography.titleSmall)
            Muted("${it.answered}/${it.questions} answered" + (it.summary?.percent?.let { p -> " · score $p%" } ?: if (it.summary == null) " · not finished" else ""))
            TextButton({ confirm = it.id }, contentPadding = PaddingValues(0.dp)) { Text("Delete", color = LocalExtra.current.bad) }
        }
    }
    confirm?.let { id -> StudyConfirm("Delete this interview?", "Its questions, answers and feedback are deleted from this phone.", "Delete", { s.deleteMock(id) }) { confirm = null } }
}

private fun clock(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockQuestionView(vm: AppViewModel, m: StudyData.MockSession, onSpoke: () -> Unit) {
    val s = vm.study
    val i = s.mockIndex.intValue.coerceIn(0, m.questions.lastIndex)
    val q = m.questions[i]
    var text by rememberSaveable(m.id, i) { mutableStateOf(q.answer.orEmpty()) }
    var seconds by rememberSaveable(m.id, i) { mutableStateOf(q.seconds ?: 0) }
    var pendingMic by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val running = q.feedback == null && s.busy.value == null
    LaunchedEffect(m.id, i, running) {                                   // the timer keeps counting past the 2-minute guide
        while (running) { delay(1_000); if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) seconds++ }
    }
    fun listen() = vm.startVoice(vm.voiceSettings.value.recognitionTag(java.util.Locale.getDefault().toLanguageTag())) { heard -> text = (text.trimEnd() + " " + heard).trim().take(4000) }
    val mic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pendingMic) listen() else if (!ok) vm.voiceError.value = "Microphone permission was not given. You can type your answer instead."
        pendingMic = false
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Glass {
        Muted("Question ${i + 1} of ${m.questions.size} · " + if (q.kind == "technical") "Technical: ${q.skill}" else "Behavioural (STAR)")
        Text(q.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            val over = seconds >= 120
            Text("⏱ ${clock(seconds)}", style = MaterialTheme.typography.titleMedium, color = if (over) LocalExtra.current.bad else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "Time on this question: ${seconds / 60} minutes ${seconds % 60} seconds" })
            Spacer(Modifier.width(12.dp))
            Muted(if (over) "Past the 2-minute guide: keep going or wrap up." else "Aim for about 2 minutes.")
        }
        OutlinedButton({ onSpoke(); vm.speak(q.question) }) { Text("Read the question aloud") }
    }
    Glass {
        OutlinedTextField(text, { text = it.take(4000) }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 14, label = { Text("Your answer") },
            placeholder = { Text("Type your answer, or tap Answer by voice") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.small), verticalArrangement = Arrangement.spacedBy(Dimens.small)) {
            if (vm.listening.value) OutlinedButton({ vm.stopVoice() }) { Text("■ Stop listening") }
            else OutlinedButton({
                vm.voiceError.value = null
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) listen()
                else { pendingMic = true; mic.launch(android.Manifest.permission.RECORD_AUDIO) }
            }, enabled = s.busy.value == null) { Text("🎤 Answer by voice") }
            GradientButton(if (q.answer == null) "Score my answer" else "Score again", { if (vm.listening.value) vm.stopVoice(); s.answerMock(text, seconds) },
                enabled = text.trim().length >= 3 && s.busy.value == null)
        }
        if (vm.listening.value) Muted("Listening… " + vm.heard.value)
        vm.voiceError.value?.let { Text(it, color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall) }
        Muted(vm.voice.description)
    }
    s.mockNote.value?.let { Text(it, color = LocalExtra.current.bad, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    q.feedback?.let { MockFeedbackCard(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
        if (q.answer != null && i < m.questions.lastIndex) GradientButton("Next question", { if (vm.listening.value) vm.cancelVoice(); s.nextMockQuestion() }, Modifier.weight(1f))
        OutlinedButton({ if (vm.listening.value) vm.cancelVoice(); s.finishMock() }, Modifier.weight(1f).heightIn(min = Dimens.touch), enabled = s.busy.value == null) { Text("Finish interview") }
    }
}

@Composable
private fun MockFeedbackCard(f: StudyCore.Feedback) {
    Glass(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) { Title("Feedback"); Spacer(Modifier.weight(1f)); Pill("${f.total}/15") }
        StudyCore.RUBRIC.forEach { k -> val v = f.scores[k] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.replaceFirstChar { it.uppercase() }, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) { Bar(v * 20, "$k score") }; Spacer(Modifier.width(8.dp)); Text("$v/5", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (f.evidence.isNotEmpty()) { SectionTitle("From your answer"); f.evidence.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium) } }
        if (f.tips.isNotEmpty()) { SectionTitle("Tips"); Bullets(f.tips) }
        Muted("AI feedback from the model on this phone; it judges only what you said.")
    }
}

@Composable
private fun MockSummaryView(vm: AppViewModel, m: StudyData.MockSession) {
    val s = vm.study
    var confirm by remember { mutableStateOf(false) }
    val sm = m.summary
    Glass {
        Title("Interview summary")
        if (sm == null || sm.answered == 0) Muted("No answers were scored in this interview.")
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(sm.percent ?: 0, "Interview score"); Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${sm.answered} of ${m.questions.size} answers scored", style = MaterialTheme.typography.bodyMedium)
                    sm.weakest?.let { Muted("Work on: $it") }
                }
            }
            sm.means.forEach { (k, v) -> Text("${k.replaceFirstChar { it.uppercase() }}: $v / 5", style = MaterialTheme.typography.bodyMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
            GradientButton("New interview", { s.mock.value = null; s.loadMocks() }, Modifier.weight(1f))
            TextButton({ confirm = true }) { Text("Delete", color = LocalExtra.current.bad) }
        }
    }
    SectionTitle("Transcript")
    m.questions.forEach { q ->
        Glass {
            Muted("Question ${q.i + 1} · " + if (q.kind == "technical") "Technical: ${q.skill}" else "Behavioural (STAR)")
            Text(q.question, style = MaterialTheme.typography.titleSmall)
            if (q.answer == null) Muted("Not answered.")
            else {
                Text(q.answer!!, style = MaterialTheme.typography.bodyMedium)
                Muted((q.seconds?.let { "Answered in ${clock(it)}" } ?: "") + (q.feedback?.let { " · ${it.total}/15" } ?: " · not scored"))
            }
        }
    }
    if (confirm) StudyConfirm("Delete this interview?", "Its questions, answers and feedback are deleted from this phone.", "Delete", { s.deleteMock(m.id) }) { confirm = false }
}

// ---------------------------------------------------------------------- 7.2.0-dev.2: resume tailoring (nothing stored)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailorView(vm: AppViewModel) {
    val s = vm.study
    var jd by rememberSaveable { mutableStateOf("") }
    var resume by rememberSaveable { mutableStateOf("") }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    PageBody {
        BackLink("Back") { s.back() }
        Notices(vm)
        Glass {
            Title("Tailor my resume to a job")
            Muted("Paste a job description and your resume. The AI on this phone rewrites your most relevant bullet points for the job using only facts already in your resume: a rewrite that adds a number or a skill that isn't in your resume is left out. Nothing is saved or sent online.")
            OutlinedTextField(jd, { jd = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 12, label = { Text("Job description") },
                placeholder = { Text("Paste the full job post, including the requirements") })
            OutlinedTextField(resume, { resume = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 12, label = { Text("Your resume") },
                placeholder = { Text("Paste your resume text, one achievement per line") })
            if (vm.career.active() != null) TextButton({ s.resumeText { resume = it.take(20_000) } }) { Text("Use my resume from Career") }
            GradientButton("Rewrite my bullets", { s.tailor(resume, jd) }, Modifier.fillMaxWidth(), enabled = jd.isNotBlank() && resume.isNotBlank(), busy = s.busy.value != null)
        }
        val t = s.tailored.value ?: return@PageBody
        Glass {
            Title("The job asks for")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                t.covered.forEach { Pill("✓ $it", LocalExtra.current.ok) }; t.missing.forEach { Pill("$it (not in your resume)", LocalExtra.current.muted) }
            }
            if (t.jobSkills.isEmpty()) Muted("No known skills were found in the job description.")
            Muted(t.note)
        }
        val accepted = t.bullets.mapNotNull { it.rewritten }
        if (accepted.isNotEmpty()) OutlinedButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(accepted.joinToString("\n") { "• $it" })); s.message.value = "Copied ${accepted.size} bullet${if (accepted.size == 1) "" else "s"}" },
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch)) { Text("Copy all accepted (${accepted.size})") }
        t.bullets.forEach { b ->
            Glass {
                Muted("Original")
                Text(b.original, style = MaterialTheme.typography.bodyMedium)
                if (b.rewritten != null) {
                    Muted("Rewritten for this job")
                    Text(b.rewritten, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(b.rewritten)); s.message.value = "Copied" }, contentPadding = PaddingValues(0.dp)) { Text("Copy") }
                } else Text(b.rejected.orEmpty(), color = LocalExtra.current.bad, style = MaterialTheme.typography.bodySmall)
                if (b.skills.isNotEmpty()) Muted("Job skills named: " + b.skills.joinToString(", "))
            }
        }
    }
}
