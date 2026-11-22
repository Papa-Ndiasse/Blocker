package com.blocker.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android Device Owner / Device Admin Receiver.
 *
 * Activated via ADB:
 *   adb shell dpm set-device-owner com.blocker.app/.admin.BlockerDeviceAdminReceiver
 *
 * Intentionally provides NO deactivation or clearDeviceOwnerApp path.
 */
class BlockerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Blocker Device Admin enabled")
        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Blocker provisioning complete")
        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "La protection Blocker est verrouillée de façon permanente."
    }

    companion object {
        private const val TAG = "BlockerAdminReceiver"

        fun componentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, BlockerDeviceAdminReceiver::class.java)
        }
    }
}
