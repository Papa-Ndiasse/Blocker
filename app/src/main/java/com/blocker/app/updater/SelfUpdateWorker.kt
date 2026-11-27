package com.blocker.app.updater

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
import java.util.concurrent.TimeUnit

/**
 * Periodically checks GitHub Releases (every 6 hours, STRICTLY over Wi-Fi) and installs
 * newer signed Blocker releases without ever consuming 4G/5G mobile data.
 * Never returns Result.retry() to prevent download loops.
 */
class SelfUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > 0) return Result.success()
        val app = applicationContext as? BlockerApp ?: return Result.success()
        return try {
            val updater = GitHubAutoUpdater(applicationContext, app.preferences)
            when (val result = updater.checkAndInstallIfAvailable()) {
                is UpdateCheckResult.InstallingSilently -> {
                    Log.i(TAG, "Auto-installing GitHub release v${result.versionName} (${result.versionCode})")
                }
                is UpdateCheckResult.UpToDate -> Unit
                is UpdateCheckResult.ReadyNeedsDeviceOwner -> Unit
                is UpdateCheckResult.Error -> {
                    Log.w(TAG, "Self-update check error (no retry loop): ${result.message}")
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Self-update aborted safely: ${t.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SelfUpdateWorker"
        private const val PERIODIC_WORK_NAME = "blocker_github_auto_update"
        private const val MANUAL_WORK_NAME = "blocker_github_manual_update"

        fun scheduleHourly(context: Context) {
            val constraints = Constraints.Builder()
                // STRICTLY Wi-Fi only: 0.00 MB of 4G/5G mobile data!
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SelfUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            // UPDATE overwrites any older v1.0.x hourly 4G job immediately on update
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val request = OneTimeWorkRequestBuilder<SelfUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
