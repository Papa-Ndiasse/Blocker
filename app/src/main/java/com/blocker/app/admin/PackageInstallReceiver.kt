package com.blocker.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.blocker.app.BlockerApp

/**
 * Dynamically registered inside BlockerAccessibilityService (required on Android 8.0+ for real-time
 * delivery of ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REPLACED) so that:
 *   - In Device Owner mode: any Tor Browser, Orbot, or Tor clone installed from Play Store or APK
 *     is immediately hidden, suspended, and silently uninstalled in <100ms (0 clicks).
 *   - In No-PC mode (without Device Owner): Android does not permit silent background uninstalls,
 *     so any Tor install immediately opens Android's official Uninstall dialog (1 tap to remove)
 *     while BlockerAccessibilityService blocks launching Tor completely.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val pkg = intent.data?.schemeSpecificPart
        val controller = DeviceOwnerController(context)

        if (!controller.isDeviceOwner()) {
            // No-PC mode: immediately prompt Android's official uninstall dialog for any Tor app
            if (!pkg.isNullOrBlank()) {
                val label = runCatching {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info)?.toString()
                }.getOrNull()
                if (TorAppPolicy.isTorPackage(pkg, label)) {
                    Log.w(TAG, "Tor package installed ($pkg) in No-PC mode — launching uninstall prompt")
                    requestUninstallPackage(context, pkg)
                }
            }
            return
        }

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

        /**
         * Launches Android's official Uninstall dialog for [packageName].
         * Works without Device Owner / PC (uses REQUEST_DELETE_PACKAGES permission).
         */
        fun requestUninstallPackage(context: Context, packageName: String) {
            runCatching {
                val uninstallIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(uninstallIntent)
            }.onFailure { e ->
                Log.w(TAG, "Could not launch uninstall prompt for $packageName: ${e.message}")
            }
        }
    }
}
