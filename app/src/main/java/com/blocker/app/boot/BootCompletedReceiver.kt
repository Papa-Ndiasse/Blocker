package com.blocker.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch
import com.blocker.app.vpn.BlockerVpnService

/**
 * Starts the local DNS VPN and re-enforces Device Owner policies immediately on boot,
 * including during Direct Boot (LOCKED_BOOT_COMPLETED) before the user unlocks the device.
 * Also executes EmergencyKillSwitch immediately on MY_PACKAGE_REPLACED if an emergency
 * GitHub recovery release was installed.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (EmergencyKillSwitch.executeIfActive(context)) {
            Log.w(TAG, "Emergency recovery update applied on $action — protection disarmed")
            return
        }

        Log.i(TAG, "Received $action — ensuring Blocker protection is active")

        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        } else if (android.net.VpnService.prepare(context) == null) {
            BlockerVpnService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
