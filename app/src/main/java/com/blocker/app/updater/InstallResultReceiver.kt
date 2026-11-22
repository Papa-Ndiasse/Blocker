package com.blocker.app.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch
import java.io.File

/**
 * Receives completion status from silent self-updates committed via PackageInstaller.Session.
 * Cleans up the downloaded APK and immediately re-enforces all Device Owner locks
 * (unless the installed build is an EMERGENCY_DISABLE recovery build).
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (EmergencyKillSwitch.executeIfActive(context)) {
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: ""

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmIntent) }
            }
            return
        }

        // Clean cached APK
        runCatching {
            val deviceCtx = context.createDeviceProtectedStorageContext()
            File(deviceCtx.cacheDir, GitHubAutoUpdater.PENDING_APK_NAME).delete()
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "Silent self-update to v$versionName succeeded")
        } else {
            Log.w(TAG, "Silent self-update to v$versionName failed (status=$status): $message")
        }

        // Always re-enforce all Device Owner locks (including DISALLOW_INSTALL_APPS if latched)
        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val EXTRA_VERSION_NAME = "extra_version_name"
    }
}
