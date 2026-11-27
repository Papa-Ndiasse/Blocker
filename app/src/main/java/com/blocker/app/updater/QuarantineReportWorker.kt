package com.blocker.app.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blocker.app.BlockerApp

/**
 * Reports any pending weekly-quarantined apps/sites to GitHub Issues on `Papa-Ndiasse/Blocker`.
 *
 * Strictly constrained to UNMETERED (Wi-Fi only) and NEVER returns Result.retry(),
 * guaranteeing 0.00 MB of 4G/5G mobile data usage.
 */
class QuarantineReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BlockerApp ?: return Result.success()
        val reporter = GitHubQuarantineReporter(app.preferences)
        reporter.reportPendingQuarantines()
        // Never retry in a background loop — will re-run automatically on next Wi-Fi app launch or quarantine
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "blocker_quarantine_github_report"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = OneTimeWorkRequestBuilder<QuarantineReportWorker>()
                .setConstraints(constraints)
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
