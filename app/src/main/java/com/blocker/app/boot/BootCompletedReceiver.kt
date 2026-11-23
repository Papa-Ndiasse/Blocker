package com.blocker.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch

/**
 * Re-enforces Device Owner policies immediately on boot and package replacement,
 * and executes EmergencyKillSwitch immediately if an emergency GitHub recovery release is installed.
 * (No VPN service is ever started.)
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (EmergencyKillSwitch.executeIfActive(context)) {
            Log.w(TAG, "Emergency recovery update applied on $action — protection disarmed")
            return
        }

        Log.i(TAG, "Received $action — ensuring Blocker protection is active (VPN-free mode)")

        val controller = DeviceOwnerController(context)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
