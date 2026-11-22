package com.blocker.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blocker.app.BlockerApp

/**
 * Dynamically registered inside BlockerVpnService (required on Android 8.0+ for real-time
 * delivery of ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REPLACED) so that:
 *   - Any Tor Browser, Orbot, or Tor clone installed from Play Store or elsewhere is
 *     immediately hidden, suspended, and silently uninstalled in <100ms
 *   - Newly installed Chromium browsers immediately receive SafeSearch & DoH=off policies
 *   - Newly installed proxy-bypass browsers (Opera, Aloha, Puffin, UC) are suspended
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val controller = DeviceOwnerController(context)
        if (!controller.isDeviceOwner()) return

        val pkg = intent.data?.schemeSpecificPart
        Log.i(TAG, "Package event ($action) for $pkg — enforcing anti-Tor & browser policies")

        if (!pkg.isNullOrBlank()) {
            controller.inspectAndEnforceNewPackage(pkg)
        }
        controller.purgeTorApps()

        val app = context.applicationContext as? BlockerApp
        val flags = app?.preferences?.hardening?.value
        controller.purgeNsfwMihonExtensions(flags?.lockMihonMixedExtensions ?: false)
        if (flags != null) {
            controller.applyBrowserPolicies(flags)
        }
        controller.suspendBypassApps()
    }

    companion object {
        private const val TAG = "PackageInstallReceiver"
    }
}
