package com.blocker.app.admin

/**
 * Identifies Tor Browser, Orbot, Tor forks/clones, and Tor-based proxy apps by
 * exact package ID or careful heuristic matching (with zero false positives on
 * normal apps containing "tor" like Calculator, Editor, Translator, Play Store, etc.).
 */
object TorAppPolicy {

    val TOR_EXACT_PACKAGES = setOf(
        "org.torproject.torbrowser",
        "org.torproject.torbrowser_alpha",
        "org.torproject.torbrowser_nightly",
        "org.torproject.android",
        "org.torproject.android.alpha",
        "info.guardianproject.orfox",
        "info.guardianproject.orbot",
        "pan.alexander.tordnscrypt.gp",
        "pan.alexander.tordnscrypt.stable",
        "com.onion.browser",
        "com.onion.tor.browser",
        "com.darkweb.tor.browser",
        "com.tor.browser.private",
        "com.nice.tor.browser",
        "com.ornet.vpn",
        "com.ornet.browser",
        "com.psiphon3",
        "com.psiphon3.subscription"
    )

    private val TOR_PACKAGE_PATTERNS = listOf(
        Regex("(^|\\.)torproject(\\.|$)"),
        Regex("(^|\\.)torbrowser([._-]|$)"),
        Regex("(^|\\.)tor[._-]?browser(\\.|$)"),
        Regex("(^|\\.)orbot([._-]|$)"),
        Regex("(^|\\.)orfox([._-]|$)"),
        Regex("(^|\\.)onion[._-]?browser(\\.|$)"),
        Regex("(^|\\.)tor[._-]?vpn(\\.|$)"),
        Regex("(^|\\.)tordnscrypt(\\.|$)"),
        Regex("(^|\\.)ornet(\\.|$)")
    )

    private val TOR_LABEL_PATTERNS = listOf(
        Regex("\\btor\\s+browser\\b"),
        Regex("\\borbot\\b"),
        Regex("\\borfox\\b"),
        Regex("\\bonion\\s+browser\\b"),
        Regex("\\btor\\s+vpn\\b"),
        Regex("\\btor\\s+proxy\\b"),
        Regex("\\btor\\s+private\\s+browser\\b"),
        Regex("\\binvizible\\s+pro\\b"),
        Regex("\\bdark\\s*web\\b.*\\bbrowser\\b")
    )

    fun isTorPackage(packageName: String, appLabel: String? = null): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty() || pkg == "com.blocker.app") return false

        if (pkg in TOR_EXACT_PACKAGES) return true

        if (TOR_PACKAGE_PATTERNS.any { it.containsMatchIn(pkg) }) {
            return true
        }

        if (!appLabel.isNullOrBlank()) {
            val label = appLabel.trim().lowercase()
            if (TOR_LABEL_PATTERNS.any { it.containsMatchIn(label) }) {
                return true
            }
        }

        return false
    }
}
