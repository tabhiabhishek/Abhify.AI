package ai.abhify.android.sync

import ai.abhify.android.cloud.ApiException
import ai.abhify.android.cloud.NetworkException
import ai.abhify.android.graph
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** "Online" = Android has a validated internet connection (not just Wi-Fi without internet). */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()
    var onRestored: () -> Unit = {}

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update(validated(caps)) }
            override fun onLost(network: Network) { update(false) }
        })
    }

    private fun validated(c: NetworkCapabilities) = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    private fun isOnlineNow() = cm.getNetworkCapabilities(cm.activeNetwork)?.let(::validated) ?: false
    private fun update(now: Boolean) { val was = _online.value; _online.value = now; if (now && !was) onRestored() }
}

/** Runs one sync pass in the background. Local data is always usable; this only moves changes. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (!g.syncEnabled || g.cloud.session == null) { g.syncStatus.value = if (g.cloud.session == null) SyncStatus.Off else SyncStatus.Idle; return Result.success() }
        g.syncStatus.value = SyncStatus.Running
        return try {
            val report = SyncEngine(g.db, CloudSyncApi(g.cloud)).run()
            g.syncStatus.value = SyncStatus.Done(System.currentTimeMillis(), report)
            Result.success()
        } catch (e: NetworkException) {
            g.syncStatus.value = SyncStatus.failure(e, System.currentTimeMillis()); Result.retry()
        } catch (e: ApiException) {
            g.syncStatus.value = SyncStatus.failure(e, System.currentTimeMillis())
            if (e.code == 401 || e.code in 400..499) Result.failure() else Result.retry()       // expired sign-in: wait for the user
        }
    }

    companion object {
        /** Any connection, or Wi-Fi (unmetered) only when the user chose that. */
        private fun net(wifiOnly: Boolean) = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context, wifiOnly: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("abhify-sync-periodic", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(net(wifiOnly)).build())
        }

        /** Soon, when the network allows (coalesces bursts of local changes into one run). */
        fun syncSoon(context: Context, delaySeconds: Long = 5, wifiOnly: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork("abhify-sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net(wifiOnly)).setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("abhify-sync-periodic")
            WorkManager.getInstance(context).cancelUniqueWork("abhify-sync-now")
        }
    }
}
