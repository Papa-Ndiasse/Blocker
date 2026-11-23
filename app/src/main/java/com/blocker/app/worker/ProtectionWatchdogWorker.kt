package com.blocker.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch
import java.util.concurrent.TimeUnit

/**
 * Periodic self-healing watchdog (every 15 minutes) that verifies Device Owner policies
 * remain intact (VPN-free mode — never starts any VpnService).
 */
class ProtectionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (EmergencyKillSwitch.executeIfActive(applicationContext)) {
            return Result.success()
        }
        val controller = DeviceOwnerController(applicationContext)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "blocker_protection_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProtectionWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
