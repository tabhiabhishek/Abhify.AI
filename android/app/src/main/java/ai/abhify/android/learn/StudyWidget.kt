package ai.abhify.android.learn

import ai.abhify.android.R
import ai.abhify.android.graph
import ai.abhify.android.ui.MainActivity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 7.2.0-dev.2 home-screen widget (RemoteViews, no extra library, no permission): the study streak, items due (flashcards
 * + missed questions) and the next lesson, read from study.json. A tap opens Abhify on Study. Updated after every Study
 * save and on the system schedule (every 30 minutes at most often).
 */
class StudyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        context.graph.scope.launch(Dispatchers.IO) {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        /** Refreshes the widgets (if any are placed) off the main thread; never blocks the caller. */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, StudyWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            app.graph.scope.launch(Dispatchers.IO) { runCatching { render(app, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = runCatching { StudyAndroid.service(context, StudyAndroid.NO_AI, onSaved = {}).snapshot() }.getOrNull()
            val w = if (s == null) StudyExtras.Widget("Study", "Open Abhify to see what's due", "")
                else StudyExtras.widgetContent(s.streak, s.cardsDue, s.questionsDue, s.nextLesson, s.courses)
            val open = PendingIntent.getActivity(context, 7202, Intent(context, MainActivity::class.java).putExtra(StudyReminderWorker.EXTRA_OPEN_STUDY, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.study_widget)
                v.setTextViewText(R.id.study_widget_streak, w.streak)
                v.setTextViewText(R.id.study_widget_due, w.due)
                v.setTextViewText(R.id.study_widget_next, w.next)
                v.setOnClickPendingIntent(R.id.study_widget_root, open)
                runCatching { manager.updateAppWidget(id, v) }
            }
        }
    }
}
