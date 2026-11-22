package com.blocker.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch
import com.blocker.app.vpn.BlockerVpnService
import java.util.concurrent.TimeUnit

/**
 * Periodic self-healing watchdog (every 15 minutes) that verifies Device Owner policies
 * remain intact and restarts the local DNS VPN service if it was ever killed by OEM battery savers.
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
        } else if (!BlockerVpnService.isRunning.value) {
            // Only restart if VPN permission was already granted
            if (android.net.VpnService.prepare(applicationContext) == null) {
                BlockerVpnService.start(applicationContext)
            }
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
