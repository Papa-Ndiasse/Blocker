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
 * Periodically downloads and merges curated NSFW blocklists (~3 MB compressed)
 * STRICTLY OVER WI-FI (NetworkType.UNMETERED) and NEVER uses 4G/5G cellular data.
 * Never returns Result.retry() to make infinite download loops mathematically impossible.
 */
class BlocklistSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (EmergencyKillSwitch.isActive()) return Result.success()
        if (runAttemptCount > 0) return Result.success()
        val app = applicationContext as? BlockerApp ?: return Result.success()
        return try {
            val result = app.blocklistRepository.syncRemoteBlocklists()
            if (result.success) {
                app.reloadFilterEngine()
                Log.i(TAG, "Wi-Fi blocklist sync complete: ${result.totalDomains} domains")
            } else {
                Log.w(TAG, "Wi-Fi blocklist sync skipped: ${result.errorMessage}")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Blocklist sync aborted safely (no retry loop): ${t.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "BlocklistSyncWorker"
        private const val PERIODIC_WORK_NAME = "blocker_daily_blocklist_sync"
        private const val INITIAL_WORK_NAME = "blocker_initial_blocklist_sync"

        fun scheduleDaily(context: Context) {
            val constraints = Constraints.Builder()
                // STRICTLY Wi-Fi only: 0.00 MB of 4G/5G mobile data!
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            // UPDATE overwrites any older v1.0.x job that allowed 4G (NetworkType.CONNECTED)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                // STRICTLY Wi-Fi only: never burns mobile data on first launch!
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(45, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
