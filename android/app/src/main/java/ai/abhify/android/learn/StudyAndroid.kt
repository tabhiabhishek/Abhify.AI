package ai.abhify.android.learn

import ai.abhify.android.AppGraph
import ai.abhify.android.chat.PromptBuilder
import ai.abhify.android.chat.memoryItems
import ai.abhify.android.chat.workspaces
import ai.abhify.android.llm.LlmEngine
import ai.abhify.android.workspace.ProjectStore
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * 7.2.0-dev.2 Study on the phone, outside the screens: one way to build the service (the app, the reminder, the
 * lesson-pack worker and the widget all use filesDir/study.json under the same lock), the learner's memories and
 * projects for the Personal AI Brain opt-in, the on-device AI for background work, and the progress report PDF.
 */
object StudyAndroid {
    const val SYSTEM = "You are a patient, accurate tutor helping someone prepare for job interviews."
    private val GOAL_CATEGORIES = setOf("Learning goals", "Career goals")

    fun file(context: Context) = File(context.filesDir, "study.json")

    fun service(context: Context, ai: StudyAi, brain: BrainSource = BrainSource.NONE, onSaved: () -> Unit = { StudyWidget.requestUpdate(context) }): StudyService {
        val app = context.applicationContext
        return StudyService(file(app), { app.assets.open("study_tracks.json").use { it.readBytes().toString(Charsets.UTF_8) } },
            { app.assets.open("skills_taxonomy.json").use { it.readBytes().toString(Charsets.UTF_8) } }, ai, brain = brain, onSaved = onSaved)
    }

    /** Read-only uses (widget, reminder counts): an AI that is never called. */
    val NO_AI = StudyAi { _, _, _ -> throw StudyError("No AI is used here.") }

    /** Personal memories (goals = the "Learning goals" / "Career goals" categories) and projects that aren't archived. */
    fun brainSource(g: AppGraph): BrainSource = object : BrainSource {
        override fun memoryEnabled() = g.prefs.getBoolean("memories_enabled", true)
        override fun memories() = runCatching {
            g.db.memoryItems().filter { !it.expired() }.map { BrainSource.Memory(it.id, it.content, it.category in GOAL_CATEGORIES, it.category) }
        }.getOrDefault(emptyList())
        override fun projects(): List<BrainSource.Project> = runCatching {
            val metas = ProjectStore(File(g.context.filesDir, "projects.json")).load()
            g.db.workspaces().filter { metas[it.id]?.archived != true }.map { w ->
                val m = metas[w.id]
                BrainSource.Project(w.id.toString(), w.name, m?.instructions?.ifBlank { null } ?: m?.status?.objective.orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    /** The phone's model for background Study work (lesson packs). Refuses while another answer is being generated. */
    fun backgroundAi(g: AppGraph) = StudyAi { prompt, json, maxTokens ->
        if (g.engine.generating) throw StudyError("The AI is busy with another answer.")
        if (g.store.active() == null) throw StudyError("No AI model is on this phone.")
        g.ensureModel()?.let { throw StudyError(it) }
        val ctx = g.store.active()?.context ?: 2048
        val budget = maxOf(256, ctx - prompt.length / 3 - 96)
        val sb = StringBuilder(); var err: String? = null
        g.engine.generate(listOf(PromptBuilder.Msg("system", SYSTEM), PromptBuilder.Msg("user", prompt)), minOf(maxTokens, budget),
            if (json) 0.4f else 0.6f, seed = (System.nanoTime() % 100_000).toInt()).collect { e ->
            when (e) { is LlmEngine.Event.Token -> sb.append(e.text); is LlmEngine.Event.Failed -> err = e.message; is LlmEngine.Event.Finished -> {} }
        }
        err?.let { throw StudyError("The AI on this phone couldn't answer: $it") }
        sb.toString()
    }

    /**
     * The progress report as a PDF (A4, text only) in the app cache's export folder, which the app's FileProvider shares.
     * Lines starting with "# " are headings.
     */
    fun writeReport(context: Context, lines: List<String>, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, name)
        val doc = PdfDocument()
        val body = Paint().apply { textSize = 11f; isAntiAlias = true; color = 0xFF222222.toInt() }
        val head = Paint().apply { textSize = 15f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; color = 0xFF111111.toInt() }
        val w = 595; val h = 842; val margin = 48f; val width = w - 2 * margin
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var y = margin
        try {
            for ((i, line) in lines.withIndex()) {
                val isHead = line.startsWith("# ")
                val p = if (isHead) head else body
                val text = if (isHead) line.removePrefix("# ") else line
                if (isHead && i > 0) y += 8f
                val chunks = ArrayList<String>()
                var rest = text
                if (rest.isEmpty()) chunks += ""
                while (rest.isNotEmpty()) {
                    val n = p.breakText(rest, true, width, null).coerceAtLeast(1)
                    var cut = n
                    if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) cut = sp }
                    chunks += rest.substring(0, cut).trimEnd(); rest = rest.substring(cut).trimStart()
                }
                for (c in chunks) {
                    val lh = p.textSize * 1.45f
                    if (y + lh > h - margin) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create()); y = margin }
                    y += lh
                    page.canvas.drawText(c, margin, y, p)
                }
            }
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return out
    }
}
