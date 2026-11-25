package com.blocker.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blocker.app.BlockerApp
import com.blocker.app.admin.EmergencyKillSwitch
import java.util.concurrent.TimeUnit

/**
 * Periodically downloads and merges community NSFW DNS blocklists (HaGeZi, OISD, StevenBlack)
 * and atomically reloads the running DnsFilterEngine.
 */
class BlocklistSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (EmergencyKillSwitch.isActive()) return Result.success()
        val app = applicationContext as? BlockerApp ?: return Result.failure()
        val result = app.blocklistRepository.syncRemoteBlocklists()
        return if (result.success) {
            app.reloadFilterEngine()
            Log.i(TAG, "Blocklist sync complete: ${result.totalDomains} domains")
            Result.success()
        } else {
            Log.w(TAG, "Blocklist sync retry: ${result.errorMessage}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlocklistSyncWorker"
        private const val PERIODIC_WORK_NAME = "blocker_daily_blocklist_sync"
        private const val INITIAL_WORK_NAME = "blocker_initial_blocklist_sync"

        fun scheduleDaily(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(45, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
