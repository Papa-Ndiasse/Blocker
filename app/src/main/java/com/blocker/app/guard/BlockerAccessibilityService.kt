package com.blocker.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.blocker.app.BlockerApp
import com.blocker.app.ui.BlockedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-Time On-Screen & In-App Content Guard (AccessibilityService).
 *
 * Inspects:
 *   1. Browser URL bars (Chrome, Edge, Brave, Firefox, Samsung Internet, Vivaldi, DDG)
 *   2. Native Reddit & X (Twitter) screens for NSFW, 18+, sensitive media warnings,
 *      mature gates, borderline subreddits, hashtags, and adult search queries
 *   3. Telegram, Discord, Instagram, TikTok, Pinterest, Tumblr, and Google Search screens
 *   4. Android Settings anti-tamper (blocks navigating into Blocker's Accessibility toggle)
 *
 * Whenever NSFW or borderline content is detected, immediately backs out / returns Home
 * and covers the screen with BlockedActivity so explicit media never stays visible.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private var lastTriggerTimeMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
        Log.i(TAG, "BlockerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val app = application as? BlockerApp
        val flags = app?.preferences?.hardening?.value
        val isSealed = flags?.sealPermanentProtection ?: false

        // 0. Instant block if user launches Tor, a proxy browser, or a locked Reddit/X app
        val launchMatch = ScreenContentClassifier.classifyLaunchedPackage(
            packageName = pkg,
            lockRedditEntirely = flags?.lockRedditEntirely ?: false,
            lockXEntirely = flags?.lockXEntirely ?: false
        )
        if (launchMatch != null) {
            triggerBlock(launchMatch, pkg)
            return
        }

        val shouldInspect = pkg in ScreenContentClassifier.BROWSER_PACKAGES ||
            pkg in ScreenContentClassifier.REDDIT_PACKAGES ||
            pkg in ScreenContentClassifier.X_PACKAGES ||
            pkg in ScreenContentClassifier.MIHON_PACKAGES ||
            pkg in ScreenContentClassifier.SOCIAL_MONITORED_PACKAGES ||
            pkg in ScreenContentClassifier.STORE_AND_INSTALLER_PACKAGES ||
            pkg in ScreenContentClassifier.SETTINGS_PACKAGES ||
            pkg.contains("settings") ||
            pkg.contains("packageinstaller")

        if (!shouldInspect) return

        val root = rootInActiveWindow ?: return

        // 1. Fast path for browser URL bars
        if (pkg in ScreenContentClassifier.BROWSER_PACKAGES) {
            val urlText = extractBrowserUrl(root)
            if (!urlText.isNullOrBlank()) {
                if (ScreenContentClassifier.isImmuneUrl(urlText)) {
                    return
                }
                val urlMatch = ScreenContentClassifier.classifyUrl(urlText)
                if (urlMatch != null) {
                    triggerBlock(urlMatch, pkg)
                    return
                }
            }
        }

        // 2. Bounded DFS extraction of visible screen text / content descriptions
        val textSamples = ArrayList<String>(64)
        collectNodeText(root, textSamples, maxNodes = 160)
        if (textSamples.isEmpty()) return

        val match = ScreenContentClassifier.classifyAppText(
            packageName = pkg,
            textSamples = textSamples,
            isSealed = isSealed
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
        super.onDestroy()
    }

    private fun triggerBlock(match: ScreenBlockMatch, sourcePackage: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < TRIGGER_COOLDOWN_MS) return
        lastTriggerTimeMs = now

        Log.w(TAG, "Blocked screen content in $sourcePackage: ${match.reason}")

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

    private fun collectNodeText(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)
        var visited = 0

        while (stack.isNotEmpty() && visited < maxNodes) {
            val current = stack.removeLast()
            visited++

            val t = current.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length <= 300) {
                out.add(t)
            }
            val desc = current.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc.length <= 300 && desc != t) {
                out.add(desc)
            }

            val childCount = current.childCount
            for (i in 0 until childCount) {
                val child = current.getChild(i) ?: continue
                stack.addLast(child)
            }
        }
    }

    companion object {
        private const val TAG = "BlockerA11yGuard"
        private const val TRIGGER_COOLDOWN_MS = 850L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val BROWSER_URL_VIEW_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.chrome.canary:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.duckduckgo.mobile.android:id/omnibarTextInput"
        )

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
