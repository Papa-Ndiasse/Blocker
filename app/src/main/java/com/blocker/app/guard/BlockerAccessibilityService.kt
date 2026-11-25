package com.blocker.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.blocker.app.BlockerApp
import com.blocker.app.BuildConfig
import com.blocker.app.admin.MihonExtensionPolicy
import com.blocker.app.filter.FilterDecision
import com.blocker.app.ui.BlockedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference

/**
 * Real-Time On-Screen & In-App Content Guard (AccessibilityService) — VPN-free fortress,
 * optimized for Samsung Galaxy A55 5G (Exynos 1480, 120Hz AMOLED, One UI 6.1/7.0):
 *   - Checks browser URL bars + extracted page/toolbar domains against the 1.2M+ DnsFilterEngine
 *   - Blocks all 489+ Keiyoushi 18+ extensions, Comick, and dynamically detected installed 18+ APKs
 *     both INSIDE Mihon/Tachiyomi AND inside Samsung/Android PackageInstaller before installation
 *   - Uses FIFO BFS (160 nodes max) so top URL bars, page titles, and Mihon headers are scanned FIRST
 *   - Instant 0ms early exit for unmonitored apps (games, Camera, Spotify, Gallery, Launcher -> 0 IPC)
 *   - 180ms content-event debounce on TYPE_WINDOW_CONTENT_CHANGED (zero 120Hz scrolling stutter)
 *   - Total immunity for poseidon-scan.net / Poseidon Scan everywhere
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastTriggerTimeMs: Long = 0L
    private var lastContentScanElapsedMs: Long = 0L
    private var lastScannedPkg: String = ""
    private var lastInstalledScanElapsedMs: Long = 0L

    @Volatile
    private var keiyoushiNsfwNames: Set<String> = emptySet()

    @Volatile
    private var keiyoushiMixedNames: Set<String> = emptySet()

    @Volatile
    private var installedNsfwExtensionLabels: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = WeakReference(this)

        if (BuildConfig.EMERGENCY_DISABLE) {
            Log.w(TAG, "Emergency disable build active — disabling AccessibilityService")
            _isRunning.value = false
            runCatching { disableSelf() }
            return
        }

        _isRunning.value = true
        Log.i(TAG, "BlockerAccessibilityService connected (VPN-free 1.2M + Keiyoushi 489+ guard)")

        guardScope.launch {
            keiyoushiNsfwNames = loadNameAsset(KEIYOUSHI_NSFW_NAMES_ASSET)
            keiyoushiMixedNames = loadNameAsset(KEIYOUSHI_MIXED_NAMES_ASSET)
            refreshInstalledNsfwExtensionLabels()
            (application as? BlockerApp)?.ensureFilterEngineLoaded()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || BuildConfig.EMERGENCY_DISABLE) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val app = application as? BlockerApp
        val flags = app?.preferences?.hardening?.value
        val isSealed = flags?.sealPermanentProtection ?: false
        val lockMixedMihon = flags?.lockMihonMixedExtensions ?: false

        // 0. Instant 0ms block on launching Tor, proxy browsers, Secure Folder (if latched), or locked Reddit/X
        val launchMatch = ScreenContentClassifier.classifyLaunchedPackage(
            packageName = pkg,
            lockRedditEntirely = flags?.lockRedditEntirely ?: false,
            lockXEntirely = flags?.lockXEntirely ?: false,
            lockSamsungSecureFolder = flags?.lockSamsungSecureFolder ?: false
        )
        if (launchMatch != null) {
            triggerBlock(launchMatch, pkg)
            return
        }

        val isBrowser = ScreenContentClassifier.isBrowserPackage(pkg)
        val isMihon = ScreenContentClassifier.isMihonPackage(pkg)
        val isStoreOrInstaller = pkg in ScreenContentClassifier.STORE_AND_INSTALLER_PACKAGES ||
            pkg.contains("packageinstaller")
        val isSettings = pkg in ScreenContentClassifier.SETTINGS_PACKAGES ||
            pkg.contains("settings")

        // Fast early exit BEFORE touching rootInActiveWindow (zero IPC overhead in games/camera/music/launcher)
        val shouldInspect = isBrowser ||
            isMihon ||
            isStoreOrInstaller ||
            isSettings ||
            pkg in ScreenContentClassifier.REDDIT_PACKAGES ||
            pkg in ScreenContentClassifier.X_PACKAGES ||
            pkg in ScreenContentClassifier.SOCIAL_MONITORED_PACKAGES

        if (!shouldInspect) return

        // 120Hz Galaxy A55 optimization: debounce high-frequency TYPE_WINDOW_CONTENT_CHANGED events
        // while keeping window transitions and text typing 100% instant (0ms).
        val eventType = event.eventType
        val nowElapsed = SystemClock.elapsedRealtime()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            pkg == lastScannedPkg &&
            nowElapsed - lastContentScanElapsedMs < CONTENT_SCAN_DEBOUNCE_MS
        ) {
            return
        }
        lastScannedPkg = pkg
        lastContentScanElapsedMs = nowElapsed

        // Periodically refresh installed Mihon 18+ extension labels when opening Mihon/PackageInstaller
        if ((isMihon || isStoreOrInstaller) &&
            nowElapsed - lastInstalledScanElapsedMs > INSTALLED_SCAN_INTERVAL_MS
        ) {
            lastInstalledScanElapsedMs = nowElapsed
            guardScope.launch { refreshInstalledNsfwExtensionLabels() }
        }

        val root = rootInActiveWindow ?: return

        // 1. Fast path for browser URL bars (Samsung Internet / Beta, Chrome, Brave, Firefox, Edge, Opera, DDG, Vivaldi)
        if (isBrowser) {
            val urlText = extractBrowserUrl(root)
            if (!urlText.isNullOrBlank()) {
                if (ScreenContentClassifier.isImmuneUrl(urlText) ||
                    ScreenContentClassifier.isImmuneText(urlText.lowercase())
                ) {
                    return
                }
                if (app != null) {
                    for (host in ScreenContentClassifier.extractDomainsFromText(urlText)) {
                        if (ScreenContentClassifier.isImmuneUrl(host)) return
                        val decision = app.filterEngine.evaluate(host)
                        if (decision is FilterDecision.Block) {
                            triggerBlock(
                                ScreenBlockMatch(ScreenContentClassifier.REASON_BROWSER_URL, host),
                                pkg
                            )
                            return
                        }
                    }
                }
                val urlMatch = ScreenContentClassifier.classifyUrl(urlText)
                if (urlMatch != null) {
                    triggerBlock(urlMatch, pkg)
                    return
                }
            }
        }

        // 2. Bounded FIFO BFS extraction (160 nodes max -> scans top URL bars, titles & Mihon headers FIRST!)
        val textSamples = ArrayList<String>(64)
        collectNodeTextBfs(root, textSamples, maxNodes = MAX_INSPECTED_NODES)
        if (textSamples.isEmpty()) return

        // 3. Domain-engine fallback for Browsers (when URL bar ID differs or is collapsed), Mihon WebViews & Google App
        if (app != null && (isBrowser || isMihon || pkg == "com.google.android.googlequicksearchbox")) {
            val joinedLower = textSamples.joinToString("\n").lowercase()
            val pageIsImmune = ScreenContentClassifier.isImmuneText(joinedLower)
            if (!pageIsImmune) {
                for (sample in textSamples) {
                    if (!sample.contains('.')) continue
                    for (host in ScreenContentClassifier.extractDomainsFromText(sample)) {
                        if (ScreenContentClassifier.isImmuneUrl(host)) continue
                        val decision = app.filterEngine.evaluate(host)
                        if (decision is FilterDecision.Block) {
                            val reason = if (isMihon) {
                                ScreenContentClassifier.REASON_MIHON_NSFW
                            } else {
                                ScreenContentClassifier.REASON_BROWSER_URL
                            }
                            triggerBlock(ScreenBlockMatch(reason, host), pkg)
                            return
                        }
                    }
                }
            }
        }

        // 4. Build active Keiyoushi 18+ extension name set for Mihon & PackageInstaller
        val activeKeiyoushiNames: Set<String> = if (isMihon || isStoreOrInstaller) {
            buildActiveKeiyoushiNameSet(lockMixedMihon)
        } else {
            emptySet()
        }

        val match = ScreenContentClassifier.classifyAppText(
            packageName = pkg,
            textSamples = textSamples,
            isSealed = isSealed,
            blockedKeiyoushiNames = activeKeiyoushiNames
        )
        if (match != null) {
            triggerBlock(match, pkg)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BlockerAccessibilityService interrupted")
    }

    override fun onDestroy() {
        _isRunning.value = false
        activeInstance = null
        guardScope.cancel()
        super.onDestroy()
    }

    private fun buildActiveKeiyoushiNameSet(includeMixed: Boolean): Set<String> {
        val base = keiyoushiNsfwNames
        val installed = installedNsfwExtensionLabels
        val mixed = if (includeMixed) keiyoushiMixedNames else emptySet()
        if (installed.isEmpty() && mixed.isEmpty()) return base
        return HashSet<String>(base.size + installed.size + mixed.size + 4).apply {
            addAll(base)
            addAll(installed)
            addAll(mixed)
        }
    }

    private fun loadNameAsset(assetName: String): Set<String> {
        val out = HashSet<String>(600)
        try {
            assets.open(assetName).use { input ->
                BufferedReader(InputStreamReader(input)).useLines { lines ->
                    for (line in lines) {
                        val clean = line.trim().lowercase()
                        if (clean.length >= 3 && !ScreenContentClassifier.isImmuneText(clean)) {
                            out.add(clean)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $assetName: ${e.message}")
        }
        return out
    }

    private fun refreshInstalledNsfwExtensionLabels() {
        val out = HashSet<String>(32)
        try {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(flags)
            val includeMixed = (application as? BlockerApp)
                ?.preferences?.hardening?.value?.lockMihonMixedExtensions ?: false

            for (pkgInfo in packages) {
                if (!MihonExtensionPolicy.isMihonExtensionPackage(pkgInfo)) continue
                val appLabel = pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it)?.toString() }
                if (MihonExtensionPolicy.shouldBlockPackageInfo(pkgInfo, appLabel, includeMixed)) {
                    val clean = appLabel.orEmpty()
                        .lowercase()
                        .removePrefix("tachiyomi:")
                        .removePrefix("mihon:")
                        .trim()
                    if (clean.length >= 3 && !ScreenContentClassifier.isImmuneText(clean)) {
                        out.add(clean)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Installed Mihon extension scan skipped: ${e.message}")
        }
        installedNsfwExtensionLabels = out
    }

    private fun triggerBlock(match: ScreenBlockMatch, sourcePackage: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTimeMs < TRIGGER_COOLDOWN_MS) return
        lastTriggerTimeMs = now

        Log.w(TAG, "Blocked screen content in $sourcePackage: ${match.reason} (${match.detail})")

        val app = application as? BlockerApp
        if (!match.isSettingsTamper) {
            app?.preferences?.recordBlocked(sourcePackage, match.reason)
        }

        if (match.isSettingsTamper || match.goHomeImmediately) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        try {
            val blockIntent = Intent(this, BlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(BlockedActivity.EXTRA_REASON, match.reason)
            }
            startActivity(blockIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch BlockedActivity: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun extractBrowserUrl(root: AccessibilityNodeInfo): String? {
        for (id in BROWSER_URL_VIEW_IDS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            }
            val text = nodes?.firstOrNull()?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * Breadth-first (FIFO) traversal so top toolbars, URL bars, page titles, and Mihon
     * source/extension headers are ALWAYS inspected first within the bounded node budget.
     */
    private fun collectNodeTextBfs(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return

        val queue = ArrayDeque<AccessibilityNodeInfo>(64)
        queue.addLast(node)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val current = queue.removeFirst()
            visited++

            val t = current.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length <= 260) {
                out.add(t)
            }
            val desc = current.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc.length <= 260 && desc != t) {
                out.add(desc)
            }

            val childCount = current.childCount
            for (i in 0 until childCount) {
                val child = current.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
    }

    companion object {
        private const val TAG = "BlockerA11yGuard"
        private const val TRIGGER_COOLDOWN_MS = 850L
        private const val CONTENT_SCAN_DEBOUNCE_MS = 180L
        private const val MAX_INSPECTED_NODES = 160
        private const val INSTALLED_SCAN_INTERVAL_MS = 20_000L
        private const val KEIYOUSHI_NSFW_NAMES_ASSET = "keiyoushi_nsfw_names.txt"
        private const val KEIYOUSHI_MIXED_NAMES_ASSET = "keiyoushi_mixed_names.txt"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        private var activeInstance: WeakReference<BlockerAccessibilityService>? = null

        private val BROWSER_URL_VIEW_IDS = listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/url_bar_text",
            "com.sec.android.app.sbrowser:id/url_bar",
            "com.sec.android.app.sbrowser.beta:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser.beta:id/url_bar_text",
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.chrome.canary:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.opera.gx:id/url_field",
            "com.kiwibrowser.browser:id/url_bar",
            "org.cromite.cromite:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android:id/omnibarTextInput"
        )

        fun disableForEmergency() {
            if (!BuildConfig.EMERGENCY_DISABLE) return
            _isRunning.value = false
            activeInstance?.get()?.let { service ->
                runCatching { service.disableSelf() }
            }
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(
                "${context.packageName}/${BlockerAccessibilityService::class.java.name}",
                ignoreCase = true
            ) || enabledServices.contains(
                "${context.packageName}/.guard.BlockerAccessibilityService",
                ignoreCase = true
            )
        }
    }
}
