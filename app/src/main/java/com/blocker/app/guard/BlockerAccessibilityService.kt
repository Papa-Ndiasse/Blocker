package com.blocker.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.blocker.app.BlockerApp
import com.blocker.app.BuildConfig
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-lightweight, zero-ANR On-Screen & In-App Content Guard for Samsung Galaxy A55 / One UI:
 *   - ZERO PackageManager.getInstalledPackages() Binder storms (uses pre-built 489+ Keiyoushi offline list)
 *   - AT MOST 1 single findAccessibilityNodeInfosByViewId() call per browser via direct Map lookup
 *   - Bounded 50-node FIFO BFS extraction on main thread (<1.5ms), then ALL regex/domain/hash
 *     classification runs OFF the Main Thread on Dispatchers.Default with AtomicBoolean backpressure
 *   - 450ms debounce on TYPE_WINDOW_CONTENT_CHANGED (0ms on TYPE_WINDOW_STATE_CHANGED)
 *   - Full Throwable isolation so the service can NEVER crash or stall One UI / SystemUI
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isClassifying = AtomicBoolean(false)

    private var lastTriggerTimeMs: Long = 0L
    private var lastContentScanElapsedMs: Long = 0L
    private var lastScannedPkg: String = ""

    @Volatile
    private var keiyoushiNsfwNames: Set<String> = emptySet()

    @Volatile
    private var keiyoushiMixedNames: Set<String> = emptySet()

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
        Log.i(TAG, "BlockerAccessibilityService connected (ultra-safe A55 One UI mode)")

        guardScope.launch {
            keiyoushiNsfwNames = loadNameAsset(KEIYOUSHI_NSFW_NAMES_ASSET)
            keiyoushiMixedNames = loadNameAsset(KEIYOUSHI_MIXED_NAMES_ASSET)
            runCatching { (application as? BlockerApp)?.ensureFilterEngineLoaded() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEventSafely(event)
        } catch (t: Throwable) {
            Log.w(TAG, "Accessibility event skipped safely: ${t.message}")
        }
    }

    private fun handleEventSafely(event: AccessibilityEvent?) {
        if (event == null || BuildConfig.EMERGENCY_DISABLE) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Never inspect SystemUI, One UI Home, or Keyboard
        if (pkg in IGNORED_SYSTEM_PACKAGES) return

        val app = application as? BlockerApp ?: return
        val flags = app.preferences.hardening.value
        val isSealed = flags.sealPermanentProtection
        val lockMixedMihon = flags.lockMihonMixedExtensions

        // 0. Instant 0ms block on launching Tor, proxy browsers, Secure Folder (if latched), or locked Reddit/X
        val launchMatch = ScreenContentClassifier.classifyLaunchedPackage(
            packageName = pkg,
            lockRedditEntirely = flags.lockRedditEntirely,
            lockXEntirely = flags.lockXEntirely,
            lockSamsungSecureFolder = flags.lockSamsungSecureFolder
        )
        if (launchMatch != null) {
            triggerBlockOnMain(launchMatch, pkg)
            return
        }

        val isBrowser = ScreenContentClassifier.isBrowserPackage(pkg)
        val isMihon = ScreenContentClassifier.isMihonPackage(pkg)
        val isStoreOrInstaller = pkg in ScreenContentClassifier.STORE_AND_INSTALLER_PACKAGES ||
            pkg.contains("packageinstaller")
        val isSettings = pkg in ScreenContentClassifier.SETTINGS_PACKAGES ||
            pkg.contains("settings")

        // Fast early exit BEFORE touching rootInActiveWindow
        val shouldInspect = isBrowser ||
            isMihon ||
            isStoreOrInstaller ||
            isSettings ||
            pkg in ScreenContentClassifier.REDDIT_PACKAGES ||
            pkg in ScreenContentClassifier.X_PACKAGES ||
            pkg in ScreenContentClassifier.SOCIAL_MONITORED_PACKAGES

        if (!shouldInspect) return

        // Backpressure: if previous background classification is still running, drop this frame cleanly
        val eventType = event.eventType
        val nowElapsed = SystemClock.elapsedRealtime()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            pkg == lastScannedPkg &&
            nowElapsed - lastContentScanElapsedMs < CONTENT_SCAN_DEBOUNCE_MS
        ) {
            return
        }
        if (!isClassifying.compareAndSet(false, true)) {
            return
        }

        lastScannedPkg = pkg
        lastContentScanElapsedMs = nowElapsed

        // Fast, bounded snapshot on main thread (<1.5ms): at most 1 URL-bar lookup + 50 BFS text nodes
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        }
        if (root == null) {
            isClassifying.set(false)
            return
        }

        val urlText = if (isBrowser) extractSingleBrowserUrl(root, pkg) else null
        val textSamples = ArrayList<String>(40)
        collectNodeTextBfs(root, textSamples, maxNodes = MAX_INSPECTED_NODES)

        // All regex, 600k+ hash lookup, and Keiyoushi 489+ name matching runs OFF the Main Thread!
        guardScope.launch {
            try {
                val match = classifySnapshotOffMain(
                    app = app,
                    pkg = pkg,
                    isBrowser = isBrowser,
                    isMihon = isMihon,
                    isStoreOrInstaller = isStoreOrInstaller,
                    isSealed = isSealed,
                    lockMixedMihon = lockMixedMihon,
                    urlText = urlText,
                    textSamples = textSamples
                )
                if (match != null) {
                    mainHandler.post { triggerBlockOnMain(match, pkg) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Off-main classification error: ${t.message}")
            } finally {
                isClassifying.set(false)
            }
        }
    }

    private fun classifySnapshotOffMain(
        app: BlockerApp,
        pkg: String,
        isBrowser: Boolean,
        isMihon: Boolean,
        isStoreOrInstaller: Boolean,
        isSealed: Boolean,
        lockMixedMihon: Boolean,
        urlText: String?,
        textSamples: List<String>
    ): ScreenBlockMatch? {
        // 1. Browser URL bar check (instant domain hash + URL keyword match)
        if (isBrowser && !urlText.isNullOrBlank()) {
            if (ScreenContentClassifier.isImmuneUrl(urlText) ||
                ScreenContentClassifier.isImmuneText(urlText.lowercase())
            ) {
                return null
            }
            for (host in ScreenContentClassifier.extractDomainsFromText(urlText)) {
                if (ScreenContentClassifier.isImmuneUrl(host)) return null
                val decision = app.filterEngine.evaluate(host)
                if (decision is FilterDecision.Block) {
                    return ScreenBlockMatch(ScreenContentClassifier.REASON_BROWSER_URL, host)
                }
            }
            val urlMatch = ScreenContentClassifier.classifyUrl(urlText)
            if (urlMatch != null) return urlMatch
        }

        if (textSamples.isEmpty()) return null

        // 2. Domain-engine fallback on visible nodes (Browsers, Mihon WebViews, Google Search)
        if (isBrowser || isMihon || pkg == "com.google.android.googlequicksearchbox") {
            val joinedLower = textSamples.joinToString("\n").lowercase()
            if (!ScreenContentClassifier.isImmuneText(joinedLower)) {
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
                            return ScreenBlockMatch(reason, host)
                        }
                    }
                }
            }
        }

        // 3. Keiyoushi 489+ extension names + Reddit/X/Mihon/Browser/Settings/Installer classifier
        val activeKeiyoushiNames: Set<String> = if (isMihon || isStoreOrInstaller) {
            if (lockMixedMihon && keiyoushiMixedNames.isNotEmpty()) {
                HashSet<String>(keiyoushiNsfwNames.size + keiyoushiMixedNames.size).apply {
                    addAll(keiyoushiNsfwNames)
                    addAll(keiyoushiMixedNames)
                }
            } else {
                keiyoushiNsfwNames
            }
        } else {
            emptySet()
        }

        return ScreenContentClassifier.classifyAppText(
            packageName = pkg,
            textSamples = textSamples,
            isSealed = isSealed,
            blockedKeiyoushiNames = activeKeiyoushiNames
        )
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

    private fun triggerBlockOnMain(match: ScreenBlockMatch, sourcePackage: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTimeMs < TRIGGER_COOLDOWN_MS) return
        lastTriggerTimeMs = now

        Log.w(TAG, "Blocked screen content in $sourcePackage: ${match.reason} (${match.detail})")

        val app = application as? BlockerApp
        if (!match.isSettingsTamper) {
            app?.preferences?.recordBlocked(sourcePackage, match.reason)
        }

        runCatching {
            if (match.isSettingsTamper || match.goHomeImmediately) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
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
            runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        }
    }

    /**
     * Direct O(1) lookup: performs AT MOST 1 single Binder IPC call for the active browser package
     * (never loops through 20 unrelated browser view IDs).
     */
    private fun extractSingleBrowserUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        val viewId = BROWSER_URL_VIEW_ID_MAP[pkg] ?: return null
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (_: Throwable) {
            null
        }
        return nodes?.firstOrNull()?.text?.toString()
    }

    private fun collectNodeTextBfs(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return

        val queue = ArrayDeque<AccessibilityNodeInfo>(32)
        queue.addLast(node)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val current = queue.removeFirst()
            visited++

            val t = try {
                current.text?.toString()?.trim()
            } catch (_: Throwable) {
                null
            }
            if (!t.isNullOrEmpty() && t.length <= 220) {
                out.add(t)
            }
            val desc = try {
                current.contentDescription?.toString()?.trim()
            } catch (_: Throwable) {
                null
            }
            if (!desc.isNullOrEmpty() && desc.length <= 220 && desc != t) {
                out.add(desc)
            }

            val childCount = try {
                current.childCount
            } catch (_: Throwable) {
                0
            }
            val limit = childCount.coerceAtMost(24)
            for (i in 0 until limit) {
                val child = try {
                    current.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                queue.addLast(child)
            }
        }
    }

    companion object {
        private const val TAG = "BlockerA11yGuard"
        private const val TRIGGER_COOLDOWN_MS = 1000L
        private const val CONTENT_SCAN_DEBOUNCE_MS = 450L
        private const val MAX_INSPECTED_NODES = 50
        private const val KEIYOUSHI_NSFW_NAMES_ASSET = "keiyoushi_nsfw_names.txt"
        private const val KEIYOUSHI_MIXED_NAMES_ASSET = "keiyoushi_mixed_names.txt"

        private val IGNORED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.app.aodservice"
        )

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        private var activeInstance: WeakReference<BlockerAccessibilityService>? = null

        private val BROWSER_URL_VIEW_ID_MAP = mapOf(
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser.beta" to "com.sec.android.app.sbrowser.beta:id/location_bar_edit_text",
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "com.chrome.canary" to "com.chrome.canary:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.opera.gx" to "com.opera.gx:id/url_field",
            "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
            "org.cromite.cromite" to "org.cromite.cromite:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta" to "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix" to "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput"
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
