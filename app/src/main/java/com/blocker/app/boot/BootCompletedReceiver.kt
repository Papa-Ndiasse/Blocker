package com.blocker.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.vpn.BlockerVpnService

/**
 * Starts the local DNS VPN and re-enforces Device Owner policies immediately on boot,
 * including during Direct Boot (LOCKED_BOOT_COMPLETED) before the user unlocks the device.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received $action — ensuring Blocker protection is active")

        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        } else {
            BlockerVpnService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
