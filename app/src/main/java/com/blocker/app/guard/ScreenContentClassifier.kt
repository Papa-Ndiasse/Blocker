package com.blocker.app.guard

import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.TorAppPolicy
import com.blocker.app.filter.KeywordFilter
import java.net.URLDecoder

data class ScreenBlockMatch(
    val reason: String,
    val detail: String,
    val isSettingsTamper: Boolean = false,
    val goHomeImmediately: Boolean = false
)

/**
 * Pure-Kotlin real-time classifier for:
 *   1. Browser URL bars & search queries
 *   2. Native Reddit, X (Twitter), Mihon/Keiyoushi 18+, and social/messaging apps
 *   3. Tor & proxy-bypass app launches AND Play Store / PackageInstaller install attempts (no PC needed!)
 *   4. Full Settings Anti-Tamper fortress once sealed (blocks Accessibility disable, Device Admin
 *      revocation, App Info Force-Stop/Uninstall/Clear Data, VPN disconnect, and Private DNS changes)
 */
object ScreenContentClassifier {

    const val REASON_REDDIT_NSFW = "Reddit NSFW / Limite"
    const val REASON_X_NSFW = "X (Twitter) Sensible / Limite"
    const val REASON_MIHON_NSFW = "Mihon / Extension Keiyoushi 18+"
    const val REASON_BROWSER_URL = "URL / Recherche bloquée"
    const val REASON_APP_NSFW = "Contenu adulte / Limite dans app"
    const val REASON_TOR_OR_PROXY = "Application Tor / Proxy bloquée"
    const val REASON_LOCKED_PLATFORM = "Application verrouillée définitivement"
    const val REASON_SETTINGS_TAMPER = "Protection anti-désactivation verrouillée"

    val MIHON_PACKAGES = setOf(
        "app.mihon",
        "app.mihon.debug",
        "eu.kanade.tachiyomi",
        "eu.kanade.tachiyomi.debug",
        "eu.kanade.tachiyomi.sy",
        "eu.kanade.tachiyomi.j2k",
        "eu.kanade.tachiyomi.az",
        "eu.kanade.tachiyomi.yokai",
        "xyz.jmir.tachiyomi.mi",
        "xyz.jmir.tachiyomi.mi.debug",
        "app.komikku",
        "eu.kanade.tachiyomi.aniyomi",
        "app.aniyomi"
    )

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.brave.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android"
    )

    val REDDIT_PACKAGES = setOf(
        "com.reddit.android",
        "com.rubenmayayo.reddit",
        "ml.docilealligator.infinityforreddit",
        "com.laurencedawson.reddit_sync",
        "com.andrewshu.android.reddit"
    )

    val X_PACKAGES = setOf(
        "com.twitter.android",
        "com.twitter.android.lite"
    )

    val SOCIAL_MONITORED_PACKAGES = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.discord",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.pinterest",
        "com.tumblr",
        "com.snapchat.android",
        "com.google.android.youtube",
        "com.google.android.googlequicksearchbox"
    )

    val STORE_AND_INSTALLER_PACKAGES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.xiaomi.mipicks",
        "org.fdroid.fdroid",
        "com.aurora.store",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter"
    )

    private val EXPLICIT_SEARCH_AND_URL_REGEX = Regex(
        "\\b(" + listOf(
            "porn", "porno", "pornography", "nsfw", "xxx", "xvideos", "xnxx", "pornhub",
            "xhamster", "redtube", "youporn", "spankbang", "eporner", "chaturbate",
            "stripchat", "bongacams", "onlyfans", "fansly", "mym\\.fans", "camgirl",
            "nude", "nudes", "nudity", "naked", "boobs", "tits", "titties", "pussy",
            "vagina", "penis", "cock", "dick", "blowjob", "handjob", "creampie",
            "bukkake", "gangbang", "threesome", "cumshot", "cumslut", "milf", "gilf",
            "bdsm", "fetish", "erotic", "erotica", "sextape", "sexvideo", "sexvid",
            "masturbation", "orgasm", "dildo", "vibrator", "slut", "whore", "escort",
            "gonewild", "realgirls", "celebnsfw", "leakednudes", "fappening", "thirsttrap",
            "lingerie", "boudoir", "gooning", "fap", "jerkoff", "horny", "hookup",
            "hentai", "hanime", "nhentai", "doujin", "doujinshi", "rule34", "r34",
            "ecchi", "ahegao", "oppai", "paizuri", "futanari", "yiff", "furryporn",
            "gelbooru", "danbooru", "e621", "lewd", "waifusgonewild", "animeplot",
            "manhwa18", "pornhwa", "comick", "r-18", "r18", "eroge", "f95zone", "iwara",
            "sfmcompile", "kemono", "coomer",
            "jav", "missav", "jable", "supjav", "javhd", "javguru", "javdb", "javbus",
            "av01", "avgle", "91porn", "koreanbj", "uncensoredjav", "asiansgonewild",
            "nudify", "undress", "deepnude", "clothoff", "deepfake", "civitai",
            "pornpen", "janitorai", "spicychat", "crushon", "candy\\.ai", "dreamgf",
            "sexe", "baise", "baiser", "salope", "pute", "branlette",
            "sodomie", "fellation", "beurette", "plan\\s*cul", "plancul", "cul\\s*nu",
            "seins\\s*nus", "femme\\s*nue", "fille\\s*nue", "nudité", "érotique",
            "erotique", "jacquie\\s*et\\s*michel", "dorcel", "tukif", "coquine",
            "chaude", "libertin", "libertine", "échangiste", "echangiste"
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    private val REDDIT_NSFW_INDICATORS = listOf(
        "nsfw", "18+", "over 18", "over18", "include_over_18",
        "plus de 18 ans", "contenu pour adultes", "contenu réservé aux adultes",
        "contenu mature", "mature content", "adult content",
        "confirm you're over 18", "you must be 18+", "show mature content",
        "afficher le contenu pour adultes", "afficher le contenu mature",
        "afficher le contenu nsfw", "blur mature", "blur nsfw", "flouter les images",
        "r/gonewild", "r/realgirls", "r/nsfw", "r/hentai", "r/rule34", "r/ecchi",
        "r/onlyfans", "r/asiansgonewild", "r/celebnsfw", "r/gentlemanboners",
        "r/cosplaygirls", "r/cosplaybutts", "r/thighdeology", "r/bikini",
        "r/lingerie", "r/thick", "r/pawg", "r/petitegonewild", "r/bustypetite",
        "r/legalteens", "r/collegesluts", "r/breedingmaterial", "r/dirtyr4r",
        "r/lewd", "r/porn", "r/boobs", "r/milf", "r/bdsm", "r/erotica",
        "r/jav", "r/nudes", "r/fap", "r/cum", "r/ass"
    )

    private val X_SENSITIVE_INDICATORS = listOf(
        "potentially sensitive content", "includes potentially sensitive",
        "may include sensitive content", "contenu potentiellement sensible",
        "contenir du contenu sensible", "age-restricted adult content",
        "contenu réservé aux adultes", "contenu pour adultes",
        "sensitive media", "média sensible", "médias sensibles",
        "display media that may contain sensitive",
        "afficher les médias pouvant contenir",
        "mark media you post as having material",
        "adult content",
        "#nsfw", "#18+", "#onlyfans", "#fansly", "#mym", "#hentai", "#r34",
        "#rule34", "#ecchi", "#lewd", "#jav", "#nude", "#nudes", "#porn",
        "#porno", "#xxx", "#sextape", "#camgirl", "#erotic", "#bdsm",
        "#fetish", "#horny", "#fap", "#gooning", "#thirsttrap", "#uncensored",
        "onlyfans.com", "fansly.com", "mym.fans", "redgifs.com",
        "fapello", "coomer.", "kemono.", "erome.com"
    )

    private val MIHON_NSFW_INDICATORS = listOf(
        "18+", "nsfw", "hentai", "smut", "erotica", "pornographic", "doujinshi",
        "ecchi", "pornhwa", "pornwha", "manhwa18", "r-18", "r18", "adult",
        "adulte", "contenu pour adultes", "contenu explicite",
        "show in sources and extensions lists",
        "afficher dans les listes de sources",
        "nhentai", "e-hentai", "hitomi", "toonily", "comick", "allporncomics",
        "ahottie", "ahrigallery", "akuma.moe", "hentaifox", "imhentai",
        "asmhentai", "pururin", "tsumino", "multporn", "8muses"
    )

    private val TOR_AND_PROXY_STORE_TERMS = listOf(
        "tor browser",
        "orbot",
        "orfox",
        "onion browser",
        "invizible pro",
        "ornet",
        "tor vpn",
        "tor proxy",
        "dark web browser",
        "opera mini",
        "opera gx",
        "opera browser",
        "aloha browser",
        "puffin web browser",
        "puffin browser",
        "psiphon"
    )

    // Settings screens that allow tampering with Blocker itself
    private val BLOCKER_TAMPER_KEYWORDS = listOf(
        // Accessibility
        "accessibilit", "raccourci", "shortcut", "utiliser blocker", "use blocker",
        // Device Admin
        "administrateur", "device admin", "désactiver cette appli",
        "deactivate this device admin", "désactiver l'appli", "deactivate", "révoquer", "revoke",
        // App Info / Force Stop / Uninstall / Clear Storage
        "forcer l'arrêt", "force stop", "désinstaller", "uninstall",
        "effacer les données", "effacer le stockage", "clear data", "clear storage",
        "supprimer les données", "infos sur l'appli", "app info",
        "informations sur l'application", "arrêter", "stop service", "turn off", "désactiver"
    )

    private val VPN_SETTINGS_TAMPER_KEYWORDS = listOf(
        "blocker dns firewall",
        "déconnecter",
        "disconnect",
        "oublier le vpn",
        "forget vpn",
        "supprimer le profil vpn",
        "delete vpn",
        "vpn toujours activé",
        "always-on vpn",
        "bloquer les connexions sans vpn",
        "block connections without vpn",
        "modifier le profil vpn",
        "edit vpn profile"
    )

    private val PRIVATE_DNS_TAMPER_KEYWORDS = listOf(
        "dns privé",
        "private dns",
        "nom d'hôte du fournisseur",
        "private dns provider hostname",
        "dns.google",
        "one.one.one.one",
        "dns.quad9.net"
    )

    fun isImmuneUrl(rawUrl: String): Boolean {
        val decoded = rawUrl.trim().lowercase()
        val withoutScheme = decoded.removePrefix("https://").removePrefix("http://")
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return host == "poseidon-scan.net" || host.endsWith(".poseidon-scan.net")
    }

    /**
     * Checks if launching [packageName] directly is forbidden (Tor apps, proxy browsers,
     * or Reddit/X if the user activated their total one-way lock).
     */
    fun classifyLaunchedPackage(
        packageName: String,
        lockRedditEntirely: Boolean = false,
        lockXEntirely: Boolean = false
    ): ScreenBlockMatch? {
        if (packageName == "com.blocker.app") return null

        if (TorAppPolicy.isTorPackage(packageName) ||
            packageName in DeviceOwnerController.BYPASS_PROXY_PACKAGES
        ) {
            return ScreenBlockMatch(
                reason = REASON_TOR_OR_PROXY,
                detail = packageName,
                goHomeImmediately = true
            )
        }

        if (lockRedditEntirely && packageName in REDDIT_PACKAGES) {
            return ScreenBlockMatch(
                reason = REASON_LOCKED_PLATFORM,
                detail = "reddit",
                goHomeImmediately = true
            )
        }

        if (lockXEntirely && packageName in X_PACKAGES) {
            return ScreenBlockMatch(
                reason = REASON_LOCKED_PLATFORM,
                detail = "x",
                goHomeImmediately = true
            )
        }

        return null
    }

    /**
     * Classifies a browser URL bar string (domain + path + query string).
     */
    fun classifyUrl(rawUrl: String): ScreenBlockMatch? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        if (isImmuneUrl(trimmed)) return null

        val decoded = runCatching {
            URLDecoder.decode(trimmed, "UTF-8")
        }.getOrDefault(trimmed).lowercase()

        val withoutScheme = decoded.removePrefix("https://").removePrefix("http://")
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.contains('.') && KeywordFilter.matches(host)) {
            return ScreenBlockMatch(REASON_BROWSER_URL, host)
        }

        if (host.contains("reddit.com") || host.contains("redd.it")) {
            for (indicator in REDDIT_NSFW_INDICATORS) {
                if (decoded.contains(indicator)) {
                    return ScreenBlockMatch(REASON_REDDIT_NSFW, indicator)
                }
            }
        }

        if (host.contains("x.com") || host.contains("twitter.com")) {
            for (indicator in X_SENSITIVE_INDICATORS) {
                if (decoded.contains(indicator)) {
                    return ScreenBlockMatch(REASON_X_NSFW, indicator)
                }
            }
        }

        if (host.contains("pixiv.net") && (decoded.contains("r-18") || decoded.contains("r18"))) {
            return ScreenBlockMatch(REASON_BROWSER_URL, "pixiv-r18")
        }

        val normalizedPath = decoded.replace('+', ' ').replace('_', ' ').replace('-', ' ').replace('/', ' ')
        val match = EXPLICIT_SEARCH_AND_URL_REGEX.find(normalizedPath)
        if (match != null) {
            return ScreenBlockMatch(REASON_BROWSER_URL, match.value)
        }

        return null
    }

    /**
     * Classifies visible text collected from an app's Accessibility tree.
     *
     * @param isSealed When true, enables full Settings anti-tamper protection (blocks disabling
     *                 Accessibility, revoking Device Admin, Force Stop/Uninstall/Clear Data,
     *                 disconnecting VPN, and changing Private DNS — all without needing PC/ADB!).
     */
    fun classifyAppText(
        packageName: String,
        textSamples: List<String>,
        isSealed: Boolean = true
    ): ScreenBlockMatch? {
        if (textSamples.isEmpty()) return null
        if (packageName == "com.blocker.app") return null

        val joined = textSamples.joinToString("\n").lowercase()

        // 1. Play Store & Package Installer Guard: block installing Tor, proxy browsers, or uninstalling Blocker
        if (packageName in STORE_AND_INSTALLER_PACKAGES || packageName.contains("packageinstaller")) {
            // Prevent uninstalling Blocker via PackageInstaller (while still allowing self-updates!)
            if (isSealed && joined.contains("blocker") &&
                (joined.contains("désinstaller") || joined.contains("uninstall")) &&
                !joined.contains("mettre à jour") && !joined.contains("update")
            ) {
                return ScreenBlockMatch(
                    reason = REASON_SETTINGS_TAMPER,
                    detail = "installer-uninstall-blocker",
                    isSettingsTamper = true,
                    goHomeImmediately = true
                )
            }

            // Block installing Tor / Orbot / Proxy browsers from Play Store or APK installer (works without PC!)
            for (term in TOR_AND_PROXY_STORE_TERMS) {
                if (joined.contains(term)) {
                    return ScreenBlockMatch(
                        reason = REASON_TOR_OR_PROXY,
                        detail = term,
                        goHomeImmediately = false
                    )
                }
            }
            return null
        }

        // 2. Android Settings Anti-Tamper Fortress (active once user taps "Sceller" — zero PC required!)
        if (packageName in SETTINGS_PACKAGES || packageName.contains("settings")) {
            // Always protect Blocker's own Accessibility toggle once enabled; protect all 5 tamper paths once sealed
            if (joined.contains("blocker") && BLOCKER_TAMPER_KEYWORDS.any { joined.contains(it) }) {
                val isAccessibilityPage = joined.contains("accessibilit") ||
                    joined.contains("utiliser blocker") || joined.contains("use blocker")
                if (isSealed || isAccessibilityPage) {
                    return ScreenBlockMatch(
                        reason = REASON_SETTINGS_TAMPER,
                        detail = "settings-blocker-tamper",
                        isSettingsTamper = true,
                        goHomeImmediately = true
                    )
                }
            }

            if (isSealed) {
                if (joined.contains("vpn") && VPN_SETTINGS_TAMPER_KEYWORDS.any { joined.contains(it) }) {
                    return ScreenBlockMatch(
                        reason = REASON_SETTINGS_TAMPER,
                        detail = "settings-vpn-tamper",
                        isSettingsTamper = true,
                        goHomeImmediately = true
                    )
                }
                if (PRIVATE_DNS_TAMPER_KEYWORDS.any { joined.contains(it) }) {
                    return ScreenBlockMatch(
                        reason = REASON_SETTINGS_TAMPER,
                        detail = "settings-private-dns-tamper",
                        isSettingsTamper = true,
                        goHomeImmediately = true
                    )
                }
            }
            return null
        }

        // 3. Reddit native app (or Reddit web inside browser)
        if (packageName in REDDIT_PACKAGES || (packageName in BROWSER_PACKAGES && joined.contains("reddit"))) {
            for (indicator in REDDIT_NSFW_INDICATORS) {
                if (containsBoundaryOrPhrase(joined, indicator)) {
                    return ScreenBlockMatch(REASON_REDDIT_NSFW, indicator)
                }
            }
            val explicitMatch = EXPLICIT_SEARCH_AND_URL_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_REDDIT_NSFW, explicitMatch.value)
            }
        }

        // 4. X (Twitter) native app (or X web inside browser)
        if (packageName in X_PACKAGES || (packageName in BROWSER_PACKAGES && (joined.contains("x.com") || joined.contains("twitter")))) {
            for (indicator in X_SENSITIVE_INDICATORS) {
                if (joined.contains(indicator)) {
                    return ScreenBlockMatch(REASON_X_NSFW, indicator)
                }
            }
            val explicitMatch = EXPLICIT_SEARCH_AND_URL_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_X_NSFW, explicitMatch.value)
            }
        }

        // 5. Mihon / Tachiyomi / Komikku / Aniyomi (blocks 18+ extensions, 18+ settings toggle, Comick, and X genres/titles)
        if (packageName in MIHON_PACKAGES) {
            for (indicator in MIHON_NSFW_INDICATORS) {
                if (containsBoundaryOrPhrase(joined, indicator)) {
                    return ScreenBlockMatch(REASON_MIHON_NSFW, indicator)
                }
            }
            val explicitMatch = EXPLICIT_SEARCH_AND_URL_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_MIHON_NSFW, explicitMatch.value)
            }
        }

        // 6. Other monitored social/messaging/search apps
        if (packageName in SOCIAL_MONITORED_PACKAGES) {
            for (indicator in listOf("18+", "nsfw", "contenu pour adultes", "mature content", "adult content", "potentially sensitive")) {
                if (containsBoundaryOrPhrase(joined, indicator)) {
                    return ScreenBlockMatch(REASON_APP_NSFW, indicator)
                }
            }
            val explicitMatch = EXPLICIT_SEARCH_AND_URL_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_APP_NSFW, explicitMatch.value)
            }
        }

        return null
    }

    private fun containsBoundaryOrPhrase(text: String, needle: String): Boolean {
        if (needle.contains(' ') || needle.contains('/') || needle.contains('#') || needle.contains('+')) {
            return text.contains(needle)
        }
        return Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(text)
    }
}
