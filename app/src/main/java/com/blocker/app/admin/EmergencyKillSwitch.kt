package com.blocker.app.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.blocker.app.BlockerApp
import com.blocker.app.BuildConfig
import com.blocker.app.guard.BlockerAccessibilityService
import com.blocker.app.guard.ScreenContentClassifier
import com.blocker.app.vpn.BlockerVpnService

/**
 * Remote Emergency Kill-Switch activated ONLY when a new signed GitHub Release is built
 * with `EMERGENCY_DISABLE=true` (via GitHub Actions `workflow_dispatch` or commit).
 *
 * Never reachable from any button inside the normal app (`BuildConfig.EMERGENCY_DISABLE == false`).
 * If ever triggered by a recovery update:
 *   1. Cleanly stops the local DNS VPN so native internet is restored immediately
 *   2. Disarms and disables the Accessibility Service (restores full Android Settings access)
 *   3. Lifts all Device Owner restrictions, browser policies, hidden/suspended apps,
 *      uninstall block, and releases Device Owner / Device Admin cleanly
 */
object EmergencyKillSwitch {

    private const val TAG = "EmergencyKillSwitch"

    fun isActive(): Boolean = BuildConfig.EMERGENCY_DISABLE

    fun executeIfActive(context: Context): Boolean {
        if (!BuildConfig.EMERGENCY_DISABLE) return false

        Log.w(TAG, "EMERGENCY_DISABLE build detected — disarming all Blocker protections cleanly")
        val appContext = context.applicationContext

        // 1. Stop local DNS VPN immediately
        BlockerVpnService.stopForEmergency(appContext)

        // 2. Disable Accessibility Service if running
        BlockerAccessibilityService.disableForEmergency()

        // 3. Reset sealed state in Device-Protected preferences
        (appContext as? BlockerApp)?.preferences?.clearSealForEmergency()

        // 4. Lift all Device Owner & Device Admin locks cleanly
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        val admin = BlockerDeviceAdminReceiver.componentName(appContext)

        if (runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)) {
            runSafely("clearAlwaysOnVpn") {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            }
            runSafely("clearUninstallBlocked") {
                dpm.setUninstallBlocked(admin, appContext.packageName, false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runSafely("clearUserControlDisabled") {
                    dpm.setUserControlDisabledPackages(admin, emptyList())
                }
            }
            val allRestrictions = listOf(
                UserManager.DISALLOW_CONFIG_VPN,
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_USER_SWITCH,
                @Suppress("DEPRECATION")
                UserManager.DISALLOW_ADD_MANAGED_PROFILE,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_FACTORY_RESET,
                "no_add_private_profile"
            )
            for (r in allRestrictions) {
                runSafely("clearRestriction:$r") { dpm.clearUserRestriction(admin, r) }
            }

            // Clear browser managed policies
            for (pkg in DeviceOwnerController.CHROMIUM_BROWSER_PACKAGES) {
                runSafely("clearBrowserPolicy:$pkg") {
                    dpm.setApplicationRestrictions(admin, pkg, android.os.Bundle.EMPTY)
                }
            }

            // Unhide & unsuspend any suspended/hidden apps
            val possiblySuspended = (DeviceOwnerController.BYPASS_PROXY_PACKAGES +
                ScreenContentClassifier.REDDIT_PACKAGES +
                ScreenContentClassifier.X_PACKAGES +
                listOf("com.samsung.knox.securefolder")).distinct()
            runSafely("unsuspendApps") {
                dpm.setPackagesSuspended(admin, possiblySuspended.toTypedArray(), false)
            }
            for (pkg in possiblySuspended) {
                runSafely("unhide:$pkg") { dpm.setApplicationHidden(admin, pkg, false) }
            }

            // Release Device Owner so Blocker can be freely uninstalled
            @Suppress("DEPRECATION")
            runSafely("clearDeviceOwnerApp") {
                dpm.clearDeviceOwnerApp(appContext.packageName)
            }
        }

        // Release standard Device Admin if active (so Blocker can be uninstalled immediately)
        if (runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)) {
            runSafely("removeActiveAdmin") {
                dpm.removeActiveAdmin(admin)
            }
        }

        return true
    }

    private fun runSafely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Emergency cleanup step '$label' skipped: ${e.message}")
        }
    }
}
