package com.blocker.app.updater

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blocker.app.BlockerApp
import java.util.concurrent.TimeUnit

/**
 * Periodically checks GitHub Releases (every 1 hour when online) and silently installs
 * any newer signed Blocker release automatically via Device Owner PackageInstaller.
 */
class SelfUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BlockerApp ?: return Result.failure()
        val updater = GitHubAutoUpdater(applicationContext, app.preferences)
        return when (val result = updater.checkAndInstallIfAvailable()) {
            is UpdateCheckResult.InstallingSilently -> {
                Log.i(TAG, "Auto-installing GitHub release v${result.versionName} (${result.versionCode})")
                Result.success()
            }
            is UpdateCheckResult.UpToDate -> Result.success()
            is UpdateCheckResult.ReadyNeedsDeviceOwner -> Result.success()
            is UpdateCheckResult.Error -> {
                Log.w(TAG, "Self-update check error: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SelfUpdateWorker"
        private const val PERIODIC_WORK_NAME = "blocker_github_auto_update"

        fun scheduleHourly(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SelfUpdateWorker>(1, TimeUnit.HOURS)
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
            val request = OneTimeWorkRequestBuilder<SelfUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
