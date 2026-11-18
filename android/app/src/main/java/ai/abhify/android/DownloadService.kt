package ai.abhify.android

import ai.abhify.android.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the model download, verification and self-test alive while the user leaves the app.
 * Started only from the consent dialog. If Android still stops it, the partial file is kept and
 * the setup screen offers "Resume".
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_MODEL) ?: return START_NOT_STICKY.also { stopSelf() }
        val model = graph.catalog.byId(id) ?: return START_NOT_STICKY.also { stopSelf() }
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing ${model.name}…", 0, 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.launch {
            launch {
                graph.setup.state.collectLatest { s ->
                    val nm = getSystemService(NotificationManager::class.java)
                    when (s) {
                        is SetupController.State.Downloading -> nm.notify(NOTIFICATION_ID,
                            notification("Downloading ${model.name}", (s.done / 1048576).toInt(), (s.total / 1048576).toInt()))
                        is SetupController.State.Verifying -> nm.notify(NOTIFICATION_ID, notification("Checking the download…", 0, 0))
                        is SetupController.State.SelfTesting -> nm.notify(NOTIFICATION_ID, notification("Testing the AI model…", 0, 0))
                        else -> {}
                    }
                }
            }
            graph.setup.run(model)
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { graph.setup.pause(); scope.cancel(); super.onDestroy() }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Abhify")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_MODEL = "model"
        fun start(context: Context, modelId: String) {
            context.startForegroundService(Intent(context, DownloadService::class.java).putExtra(EXTRA_MODEL, modelId))
        }
    }
}
