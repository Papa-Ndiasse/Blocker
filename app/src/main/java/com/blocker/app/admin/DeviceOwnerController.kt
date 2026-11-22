package com.blocker.app.admin

import android.Manifest
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.blocker.app.BlockerApp
import com.blocker.app.data.HardeningFlags
import com.blocker.app.guard.BlockerAccessibilityService
import com.blocker.app.guard.ScreenContentClassifier
import com.blocker.app.vpn.BlockerVpnService

data class ProtectionStatus(
    val isDeviceOwner: Boolean,
    val isUninstallBlocked: Boolean,
    val isUserControlDisabled: Boolean,
    val isAlwaysOnVpnSet: Boolean,
    val isVpnConfigLocked: Boolean,
    val isPrivateDnsLocked: Boolean,
    val isSafeBootLocked: Boolean,
    val isMultiUserLocked: Boolean,
    val isUnknownSourcesLocked: Boolean,
    val isAllAppInstallsLocked: Boolean,
    val isAccessibilityGuardEnabled: Boolean,
    val managedBrowsersSecured: Int,
    val bypassAppsSuspended: Int
)

/**
 * Applies and verifies all Android Device Owner tamper-resistance policies:
 *   - Uninstall block & Force-Stop / Clear-Data lock for Blocker itself
 *   - Always-On VPN + DISALLOW_CONFIG_VPN
 *   - Private DNS opportunistic reset + DISALLOW_CONFIG_PRIVATE_DNS
 *   - Safe Boot, Guest/Secondary User, Work Profile, Private Space blocks
 *   - Anti-Tor enforcement: blocks APK sideloading by default AND immediately hides, suspends,
 *     and silently uninstalls Tor Browser / Orbot / Tor clones via PackageInstaller.uninstall()
 *   - Managed Enterprise Policies for Chrome/Edge/Brave/Vivaldi (DnsOverHttpsMode=off, ForceGoogleSafeSearch=true)
 *   - Automatic suspension of proxy/VPN bypass browsers (Opera, Aloha, Puffin, UC)
 *   - Optional irreversible one-way hardening latches (ADB, All App Installs, Chrome Incognito, YouTube Restricted, Factory Reset)
 */
class DeviceOwnerController(private val context: Context) {

    private val appContext = context.applicationContext
    private val dpm: DevicePolicyManager =
        appContext.getSystemService(DevicePolicyManager::class.java)
    private val userManager: UserManager =
        appContext.getSystemService(UserManager::class.java)
    private val admin = BlockerDeviceAdminReceiver.componentName(appContext)

    fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(appContext.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Seals and enforces all permanent OS-level protections.
     * Safe to call repeatedly (idempotent) on boot, package install, and watchdog ticks.
     */
    fun enforcePermanentLockdown(): Boolean {
        if (!isDeviceOwner()) return false

        val app = appContext as? BlockerApp
        val prefs = app?.preferences
        prefs?.latchPermanentSeal()
        prefs?.latchUnknownSourcesLock()
        val flags = prefs?.hardening?.value ?: HardeningFlags(
            sealPermanentProtection = true,
            lockUnknownSources = true
        )

        // 1. Prevent uninstall of Blocker
        runSafely("setUninstallBlocked") {
            dpm.setUninstallBlocked(admin, appContext.packageName, true)
        }

        // 2. Prevent Force Stop & Clear Storage/Data for Blocker specifically (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runSafely("setUserControlDisabledPackages") {
                dpm.setUserControlDisabledPackages(admin, listOf(appContext.packageName))
            }
        }

        // 3. Auto-grant notification permission so foreground VPN service runs cleanly (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runSafely("grantNotifications") {
                dpm.setPermissionGrantState(
                    admin,
                    appContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        }

        // 4. Reset any manual unfiltered Private DNS (e.g. dns.google) and lock Private DNS settings
        runSafely("setGlobalPrivateDnsModeOpportunistic") {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
        }
        addRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)

        // 5. Lock VPN settings, Safe Mode, Guest/Secondary Users, Work Profiles, Private Space
        addRestriction(UserManager.DISALLOW_CONFIG_VPN)
        addRestriction(UserManager.DISALLOW_SAFE_BOOT)
        addRestriction(UserManager.DISALLOW_ADD_USER)
        addRestriction(UserManager.DISALLOW_USER_SWITCH)
        @Suppress("DEPRECATION")
        addRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
        if (Build.VERSION.SDK_INT >= 35) {
            addRestriction("no_add_private_profile")
        }

        // 6. Block sideloading APKs from unknown sources by default (stops Tor APK sideloading)
        addRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        addRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

        // 7. Configure Blocker as Always-On VPN (lockdownEnabled=false so non-DNS HTTPS flows natively;
        //    DISALLOW_CONFIG_VPN prevents disabling or replacing the VPN).
        runSafely("setAlwaysOnVpnPackage") {
            dpm.setAlwaysOnVpnPackage(admin, appContext.packageName, false)
        }

        // 8. Apply Managed Browser Restrictions (disable browser built-in DoH, force SafeSearch)
        applyBrowserPolicies(flags)

        // 9. Purge Tor apps + NSFW Keiyoushi/Mihon extension APKs silently + suspend proxy bypass browsers
        purgeTorApps()
        purgeNsfwMihonExtensions(flags.lockMihonMixedExtensions)
        suspendBypassApps()

        // 10. Apply any activated one-way irreversible hardening latches
        applyIrreversibleHardening(flags)

        // 11. Start the local DNS VPN immediately
        BlockerVpnService.start(appContext)
        return true
    }

    /**
     * Scans all installed applications for Mihon / Tachiyomi / Keiyoushi / Aniyomi extension APKs
     * flagged as NSFW (`tachiyomi.extension.nsfw == 1` or `tachiyomix.contentWarning >= 2`,
     * or `>= 1` when [blockMixed] is enabled) and immediately hides, suspends, and silently
     * uninstalls them via Device Owner PackageInstaller.
     */
    fun purgeNsfwMihonExtensions(blockMixed: Boolean = false): Int {
        if (!isDeviceOwner()) return 0
        val pm = appContext.packageManager
        val matched = LinkedHashSet<String>()

        runSafely("scanMihonExtensions") {
            @Suppress("DEPRECATION")
            val queryFlags = PackageManager.GET_META_DATA or
                PackageManager.GET_CONFIGURATIONS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            val packages = pm.getInstalledPackages(queryFlags)
            for (pkgInfo in packages) {
                val label = pkgInfo.applicationInfo?.let { info ->
                    runCatching { pm.getApplicationLabel(info)?.toString() }.getOrNull()
                }
                if (MihonExtensionPolicy.shouldBlockPackageInfo(pkgInfo, label, blockMixed)) {
                    matched.add(pkgInfo.packageName)
                }
            }
        }

        for (pkg in matched) {
            purgeSingleBlockedPackage(pkg, "Mihon/Keiyoushi 18+ extension")
        }
        return matched.size
    }

    /**
     * Scans all installed applications and immediately hides, suspends, and silently uninstalls
     * any Tor Browser, Orbot, Onion Browser, or Tor clone detected.
     */
    fun purgeTorApps(): Int {
        if (!isDeviceOwner()) return 0
        val pm = appContext.packageManager
        val matched = LinkedHashSet<String>()

        // Exact package check
        for (pkg in TorAppPolicy.TOR_EXACT_PACKAGES) {
            if (isPackageInstalled(pkg)) {
                matched.add(pkg)
            }
        }

        // Full installed-app heuristic check (catches Tor clones/forks from Play Store)
        runSafely("scanInstalledAppsForTor") {
            val installed = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            for (info in installed) {
                val label = runCatching { pm.getApplicationLabel(info)?.toString() }.getOrNull()
                if (TorAppPolicy.isTorPackage(info.packageName, label)) {
                    matched.add(info.packageName)
                }
            }
        }

        for (pkg in matched) {
            purgeSingleBlockedPackage(pkg, "Tor package")
        }
        return matched.size
    }

    /**
     * Immediately neutralizes and silently uninstalls a single forbidden package (Tor or
     * Keiyoushi/Mihon 18+ extension) as Device Owner with zero user interaction.
     */
    fun purgeSingleTorPackage(packageName: String) {
        purgeSingleBlockedPackage(packageName, "Tor package")
    }

    fun purgeSingleBlockedPackage(packageName: String, category: String) {
        if (!isDeviceOwner()) return
        Log.w(TAG, "Neutralizing and silently uninstalling $category: $packageName")

        // Step 1: Hide and suspend immediately so it cannot be launched or loaded even for a millisecond
        runSafely("setApplicationHidden:$packageName") {
            dpm.setApplicationHidden(admin, packageName, true)
        }
        runSafely("setPackagesSuspended:$packageName") {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
        }

        // Step 2: Silently uninstall via Device Owner PackageInstaller (zero prompt)
        runSafely("silentUninstall:$packageName") {
            val intent = Intent(appContext, UninstallResultReceiver::class.java).apply {
                putExtra("target_package", packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                packageName.hashCode(),
                intent,
                flags
            )
            appContext.packageManager.packageInstaller.uninstall(
                packageName,
                pendingIntent.intentSender
            )
        }
    }

    /**
     * Checks a newly installed package and immediately purges it if it is Tor, a Tor clone,
     * or an NSFW (or Mixed, if latched) Mihon/Keiyoushi extension.
     */
    fun inspectAndEnforceNewPackage(packageName: String) {
        if (!isDeviceOwner()) return
        val pm = appContext.packageManager
        val label = runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()
        }.getOrNull()

        if (TorAppPolicy.isTorPackage(packageName, label)) {
            purgeSingleBlockedPackage(packageName, "Tor package")
            return
        }

        val blockMixed = (appContext as? BlockerApp)?.preferences?.hardening?.value?.lockMihonMixedExtensions ?: false
        runSafely("inspectMihonExtension:$packageName") {
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
            )
            if (MihonExtensionPolicy.shouldBlockPackageInfo(pkgInfo, label, blockMixed)) {
                purgeSingleBlockedPackage(packageName, "Mihon/Keiyoushi 18+ extension")
            }
        }
    }

    fun applyBrowserPolicies(flags: HardeningFlags) {
        if (!isDeviceOwner()) return

        val blockedUrls = mutableListOf(
            "chrome://flags",
            "chrome://flags/*",
            "chrome://settings/security",
            "edge://flags",
            "edge://settings/privacy",
            // Reddit NSFW / Over-18 / Borderline paths blocked directly inside Chrome/Edge/Brave
            "*://*.reddit.com/over18*",
            "*://*.reddit.com/r/*nsfw*",
            "*://*.reddit.com/r/*porn*",
            "*://*.reddit.com/r/*hentai*",
            "*://*.reddit.com/r/*gonewild*",
            "*://*.reddit.com/r/*rule34*",
            "*://*.reddit.com/r/*ecchi*",
            "*://*.reddit.com/r/*onlyfans*",
            "*://*.reddit.com/r/*jav*",
            "*://*.reddit.com/r/*nude*",
            "*://*.reddit.com/r/*lewd*",
            "*://*.reddit.com/r/*milf*",
            "*://*.reddit.com/r/*boobs*",
            "*://*.reddit.com/r/*bdsm*",
            "*://*.reddit.com/r/*erotic*",
            "*://*.reddit.com/r/*realgirls*",
            "*://*.reddit.com/r/*celebnsfw*",
            "*://*.reddit.com/r/*fap*",
            "*://*.reddit.com/r/*cum*",
            // X / Twitter NSFW searches blocked directly inside Chrome/Edge/Brave
            "*://*.x.com/search?*nsfw*",
            "*://*.x.com/search?*porn*",
            "*://*.x.com/search?*hentai*",
            "*://*.x.com/search?*onlyfans*",
            "*://*.x.com/search?*nude*",
            "*://*.x.com/search?*xxx*",
            "*://*.x.com/search?*jav*",
            "*://*.x.com/search?*rule34*",
            "*://*.twitter.com/search?*nsfw*",
            "*://*.twitter.com/search?*porn*",
            "*://*.twitter.com/search?*hentai*",
            "*://*.twitter.com/search?*onlyfans*",
            "*://*.twitter.com/search?*nude*",
            // Pixiv R-18 & Tumblr NSFW
            "*://*.pixiv.net/*r-18*",
            "*://*.pixiv.net/*r18*",
            "*://*.tumblr.com/tagged/*nsfw*",
            "*://*.tumblr.com/tagged/*porn*"
        )
        if (flags.lockRedditEntirely) {
            blockedUrls.add("*://*.reddit.com/*")
            blockedUrls.add("*://*.redd.it/*")
        }
        if (flags.lockXEntirely) {
            blockedUrls.add("*://*.x.com/*")
            blockedUrls.add("*://*.twitter.com/*")
        }

        val chromiumPolicy = Bundle().apply {
            putString("DnsOverHttpsMode", "off")
            putBoolean("ForceGoogleSafeSearch", true)
            putStringArray("URLBlocklist", blockedUrls.toTypedArray())
            if (flags.lockChromeIncognito) {
                putInt("IncognitoModeAvailability", 1)
            }
        }

        for (pkg in CHROMIUM_BROWSER_PACKAGES) {
            if (isPackageInstalled(pkg)) {
                runSafely("setApplicationRestrictions:$pkg") {
                    dpm.setApplicationRestrictions(admin, pkg, chromiumPolicy)
                }
            }
        }
    }

    fun suspendBypassApps(): Int {
        if (!isDeviceOwner()) return 0
        val installedBypass = BYPASS_PROXY_PACKAGES.filter { isPackageInstalled(it) }
        if (installedBypass.isEmpty()) return 0

        runSafely("setPackagesSuspended") {
            dpm.setPackagesSuspended(admin, installedBypass.toTypedArray(), true)
        }
        return installedBypass.size
    }

    fun applyIrreversibleHardening(flags: HardeningFlags) {
        if (!isDeviceOwner()) return
        if (flags.lockAdbDebugging) {
            addRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
        if (flags.lockUnknownSources) {
            addRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            addRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }
        if (flags.lockAllAppInstalls) {
            addRestriction(UserManager.DISALLOW_INSTALL_APPS)
        }
        if (flags.lockRedditEntirely) {
            for (pkg in ScreenContentClassifier.REDDIT_PACKAGES) {
                if (isPackageInstalled(pkg)) {
                    runSafely("hideReddit:$pkg") { dpm.setApplicationHidden(admin, pkg, true) }
                    runSafely("suspendReddit:$pkg") { dpm.setPackagesSuspended(admin, arrayOf(pkg), true) }
                }
            }
        }
        if (flags.lockXEntirely) {
            for (pkg in ScreenContentClassifier.X_PACKAGES) {
                if (isPackageInstalled(pkg)) {
                    runSafely("hideX:$pkg") { dpm.setApplicationHidden(admin, pkg, true) }
                    runSafely("suspendX:$pkg") { dpm.setPackagesSuspended(admin, arrayOf(pkg), true) }
                }
            }
        }
        if (flags.lockMihonMixedExtensions) {
            purgeNsfwMihonExtensions(blockMixed = true)
        }
        if (flags.lockFactoryResetSettings) {
            addRestriction(UserManager.DISALLOW_FACTORY_RESET)
        }
        applyBrowserPolicies(flags)
    }

    fun getProtectionStatus(): ProtectionStatus {
        val a11yEnabled = BlockerAccessibilityService.isRunning.value ||
            BlockerAccessibilityService.isEnabledInSettings(appContext)
        val owner = isDeviceOwner()
        if (!owner) {
            return ProtectionStatus(
                isDeviceOwner = false,
                isUninstallBlocked = false,
                isUserControlDisabled = false,
                isAlwaysOnVpnSet = false,
                isVpnConfigLocked = false,
                isPrivateDnsLocked = false,
                isSafeBootLocked = false,
                isMultiUserLocked = false,
                isUnknownSourcesLocked = false,
                isAllAppInstallsLocked = false,
                isAccessibilityGuardEnabled = a11yEnabled,
                managedBrowsersSecured = 0,
                bypassAppsSuspended = 0
            )
        }

        val uninstallBlocked = runCatching {
            dpm.isUninstallBlocked(admin, appContext.packageName)
        }.getOrDefault(false)

        val userControlDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                appContext.packageName in dpm.getUserControlDisabledPackages(admin)
            }.getOrDefault(false)
        } else {
            false
        }

        val alwaysOnVpn = runCatching {
            dpm.getAlwaysOnVpnPackage(admin) == appContext.packageName
        }.getOrDefault(false)

        val browsersSecured = CHROMIUM_BROWSER_PACKAGES.count { pkg ->
            isPackageInstalled(pkg) && runCatching {
                dpm.getApplicationRestrictions(admin, pkg).getString("DnsOverHttpsMode") == "off"
            }.getOrDefault(false)
        }

        val bypassSuspended = BYPASS_PROXY_PACKAGES.count { pkg ->
            isPackageInstalled(pkg) && runCatching {
                dpm.isPackageSuspended(admin, pkg)
            }.getOrDefault(false)
        }

        val unknownSourcesLocked =
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY) ||
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

        return ProtectionStatus(
            isDeviceOwner = true,
            isUninstallBlocked = uninstallBlocked,
            isUserControlDisabled = userControlDisabled,
            isAlwaysOnVpnSet = alwaysOnVpn,
            isVpnConfigLocked = userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN),
            isPrivateDnsLocked = userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS),
            isSafeBootLocked = userManager.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT),
            isMultiUserLocked = userManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER),
            isUnknownSourcesLocked = unknownSourcesLocked,
            isAllAppInstallsLocked = userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS),
            isAccessibilityGuardEnabled = a11yEnabled,
            managedBrowsersSecured = browsersSecured,
            bypassAppsSuspended = bypassSuspended
        )
    }

    private fun addRestriction(restriction: String) {
        runSafely("addUserRestriction:$restriction") {
            dpm.addUserRestriction(admin, restriction)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun runSafely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Policy '$label' skipped or failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeviceOwnerController"

        const val ADB_SET_DEVICE_OWNER_CMD =
            "adb shell dpm set-device-owner com.blocker.app/.admin.BlockerDeviceAdminReceiver"

        val CHROMIUM_BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.vivaldi.browser"
        )

        /**
         * Proxy / built-in-VPN browsers suspended to prevent DNS bypass.
         * Tor / Orbot / Onion apps are handled even more aggressively by purgeTorApps() (hidden + silently uninstalled).
         */
        val BYPASS_PROXY_PACKAGES = listOf(
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.alohamobile.browser",
            "com.cloudmosa.puffinFree",
            "com.cloudmosa.puffin",
            "com.UCMobile.intl"
        )
    }
}
