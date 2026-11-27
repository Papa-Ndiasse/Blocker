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
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.kiwibrowser.browser",
        "org.cromite.cromite",
        "com.yandex.browser",
        "mark.via.gp"
    )

    fun isMihonPackage(packageName: String): Boolean {
        if (packageName == "com.blocker.app") return false
        val low = packageName.lowercase()
        return low in MIHON_PACKAGES ||
            low.contains("mihon") ||
            low.contains("tachiyomi") ||
            low.contains("komikku") ||
            low.contains("aniyomi") ||
            low.contains("yokai")
    }

    fun isBrowserPackage(packageName: String): Boolean {
        if (packageName == "com.blocker.app") return false
        val low = packageName.lowercase()
        return low in BROWSER_PACKAGES ||
            low.contains("sbrowser") ||
            low.contains("chrome") ||
            low.contains("firefox") ||
            low.contains("browser")
    }

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
        "com.tumblr",
        "com.google.android.googlequicksearchbox"
    )

    val IMMUNE_PACKAGES = setOf(
        "com.discord",
        "com.discord.beta",
        "com.discord.canary",
        "com.discord.alpha",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.instagram.android",
        "com.google.android.youtube",
        "com.pinterest",
        "com.whatsapp"
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
        "com.samsung.accessibility",
        "com.samsung.android.lool",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter"
    )

    const val SAMSUNG_SECURE_FOLDER_PKG = "com.samsung.knox.securefolder"

    // Broad explicit regex used ONLY for browser URL bars & search queries (classifyUrl)
    private val EXPLICIT_SEARCH_AND_URL_REGEX = Regex(
        "\\b(" + listOf(
            "porn", "porno", "pornography", "nsfw", "xxx", "xvideos", "xnxx", "pornhub",
            "xhamster", "redtube", "youporn", "spankbang", "eporner", "chaturbate",
            "stripchat", "bongacams", "onlyfans", "fansly", "mym\\.fans", "camgirl",
            "nude", "nudes", "nudity", "naked", "boobs", "tits", "titties", "pussy",
            "vagina", "penis", "cock", "dick", "blowjob", "handjob", "creampie",
            "bukkake", "gangbang", "threesome", "cumshot", "cumslut", "milf", "gilf",
            "bdsm", "fetish", "erotic", "erotica", "sextape", "sexvideo", "sexvid",
            "masturbation", "orgasm", "dildo", "vibrator", "slut", "whore",
            "gonewild", "realgirls", "celebnsfw", "leakednudes", "fappening",
            "gooning", "jerkoff", "horny",
            "hentai", "hanime", "nhentai", "doujin", "doujinshi", "rule34", "r34",
            "ecchi", "ahegao", "oppai", "paizuri", "futanari", "yiff", "furryporn",
            "gelbooru", "danbooru", "e621", "lewd", "waifusgonewild",
            "manhwa18", "pornhwa", "comick", "r-18", "r18", "eroge", "f95zone", "iwara",
            "sfmcompile", "kemono", "coomer",
            "jav", "missav", "jable", "supjav", "javhd", "javguru", "javdb", "javbus",
            "av01", "avgle", "91porn", "koreanbj", "uncensoredjav", "asiansgonewild",
            "nudify", "undress", "deepnude", "clothoff", "deepfake", "civitai",
            "pornpen", "janitorai", "spicychat", "crushon", "candy\\.ai", "dreamgf",
            "sexe", "branlette", "sodomie", "fellation", "beurette",
            "plan\\s*cul", "plancul", "cul\\s*nu", "seins\\s*nus", "femme\\s*nue",
            "fille\\s*nue", "nudité", "érotique", "erotique",
            "jacquie\\s*et\\s*michel", "dorcel", "tukif"
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    // Strict unambiguous NSFW/porn/hentai/JAV/AI/Comick regex for on-screen text scanning
    // (excludes everyday French/English slang & insults that cause false positives in comments/chats)
    private val STRICT_ONSCREEN_NSFW_REGEX = Regex(
        "\\b(" + listOf(
            "porn", "porno", "pornography", "xvideos", "xnxx", "pornhub",
            "xhamster", "redtube", "youporn", "spankbang", "eporner", "chaturbate",
            "stripchat", "bongacams", "onlyfans", "fansly", "mym\\.fans", "camgirl",
            "blowjob", "handjob", "creampie", "bukkake", "gangbang", "cumshot", "cumslut",
            "sextape", "sexvideo", "sexvid", "leakednudes", "celebnsfw", "gonewild",
            "hentai", "hanime", "nhentai", "doujinshi", "rule34", "r34",
            "ahegao", "paizuri", "futanari", "furryporn", "gelbooru", "danbooru", "e621",
            "manhwa18", "pornhwa", "comick", "eroge", "f95zone", "sfmcompile",
            "missav", "jable", "supjav", "javhd", "javguru", "javdb", "javbus",
            "av01", "avgle", "91porn", "koreanbj", "uncensoredjav", "asiansgonewild",
            "nudify", "deepnude", "clothoff", "pornpen", "spicychat", "candy\\.ai", "dreamgf",
            "jacquie\\s*et\\s*michel", "dorcel", "tukif", "plan\\s*cul", "plancul"
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

    // Settings screens that allow tampering with Blocker itself (Stock Android + Samsung One UI 6.1/7.0)
    private val BLOCKER_TAMPER_KEYWORDS = listOf(
        // Accessibility (Stock + One UI)
        "accessibilit", "raccourci", "shortcut", "utiliser blocker", "use blocker",
        "applications installées", "installed apps", "services installés",
        // Device Admin (Stock + One UI)
        "administrateur", "admin. de l'appareil", "device admin", "désactiver cette appli",
        "deactivate this device admin", "désactiver l'appli", "deactivate", "révoquer", "revoke",
        // App Info / Force Stop / Uninstall / Clear Storage / Samsung Deep Sleep
        "forcer l'arrêt", "force stop", "désinstaller", "uninstall",
        "effacer les données", "effacer le stockage", "clear data", "clear storage",
        "supprimer les données", "infos sur l'appli", "app info",
        "informations sur l'application", "mise en veille profonde", "deep sleeping",
        "arrêter", "stop service", "turn off", "désactiver"
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
        return host == "poseidon-scan.net" || host.endsWith(".poseidon-scan.net") ||
            host == "discord.com" || host.endsWith(".discord.com") ||
            host == "discord.gg" || host.endsWith(".discord.gg") ||
            host == "discordapp.com" || host.endsWith(".discordapp.com") ||
            host == "discordapp.net" || host.endsWith(".discordapp.net")
    }

    /**
     * Checks if launching [packageName] directly is forbidden (Tor apps, proxy browsers,
     * Samsung Secure Folder if latched, or Reddit/X if the user activated their total one-way lock).
     */
    fun classifyLaunchedPackage(
        packageName: String,
        lockRedditEntirely: Boolean = false,
        lockXEntirely: Boolean = false,
        lockSamsungSecureFolder: Boolean = false
    ): ScreenBlockMatch? {
        if (packageName == "com.blocker.app" || packageName in IMMUNE_PACKAGES) return null

        if (TorAppPolicy.isTorPackage(packageName) ||
            packageName in DeviceOwnerController.BYPASS_PROXY_PACKAGES
        ) {
            return ScreenBlockMatch(
                reason = REASON_TOR_OR_PROXY,
                detail = packageName,
                goHomeImmediately = true
            )
        }

        if (lockSamsungSecureFolder && packageName == SAMSUNG_SECURE_FOLDER_PKG) {
            return ScreenBlockMatch(
                reason = REASON_LOCKED_PLATFORM,
                detail = "samsung-secure-folder",
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
        isSealed: Boolean = true,
        blockedKeiyoushiNames: Set<String> = emptySet()
    ): ScreenBlockMatch? {
        if (textSamples.isEmpty()) return null
        if (packageName == "com.blocker.app" || packageName in IMMUNE_PACKAGES) return null

        val joined = textSamples.joinToString("\n").lowercase()

        // 1. Play Store & Package Installer Guard: block installing Tor, proxy browsers,
        //    Keiyoushi 18+ / Comick extensions, or uninstalling Blocker
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

            // Total immunity for Poseidon Scan extension installation
            if (isImmuneText(joined)) {
                return null
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

            // Block installing ANY Keiyoushi 18+ / Comick extension APK via PackageInstaller!
            val matchedExtInstall = matchKeiyoushiExtensionName(
                textSamples = textSamples,
                blockedNames = blockedKeiyoushiNames,
                isInstaller = true
            )
            if (matchedExtInstall != null) {
                return ScreenBlockMatch(
                    reason = REASON_MIHON_NSFW,
                    detail = "installer-keiyoushi:$matchedExtInstall",
                    goHomeImmediately = true
                )
            }
            if ((joined.contains("tachiyomi") || joined.contains("mihon") || joined.contains("keiyoushi")) &&
                (MIHON_NSFW_INDICATORS.any { containsBoundaryOrPhrase(joined, it) } ||
                    EXPLICIT_SEARCH_AND_URL_REGEX.containsMatchIn(joined))
            ) {
                return ScreenBlockMatch(
                    reason = REASON_MIHON_NSFW,
                    detail = "installer-mihon-18+",
                    goHomeImmediately = true
                )
            }
            return null
        }

        // 2. Android Settings Anti-Tamper Fortress (active once user taps "Sceller" — zero PC required!)
        if (packageName in SETTINGS_PACKAGES || packageName.contains("settings")) {
            // Always protect Blocker's own Accessibility toggle once enabled; protect all tamper paths once sealed
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

        val isBrowser = isBrowserPackage(packageName)

        // 3. Reddit native app (or Reddit web inside browser)
        if (packageName in REDDIT_PACKAGES || (isBrowser && joined.contains("reddit"))) {
            matchIndicatorsFast(joined, REDDIT_PHRASE_INDICATORS, REDDIT_WORD_REGEX)?.let { hit ->
                return ScreenBlockMatch(REASON_REDDIT_NSFW, hit)
            }
            val explicitMatch = STRICT_ONSCREEN_NSFW_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_REDDIT_NSFW, explicitMatch.value)
            }
        }

        // 4. X (Twitter) native app (or X web inside browser)
        if (packageName in X_PACKAGES || (isBrowser && (joined.contains("x.com") || joined.contains("twitter")))) {
            for (indicator in X_SENSITIVE_INDICATORS) {
                if (joined.contains(indicator)) {
                    return ScreenBlockMatch(REASON_X_NSFW, indicator)
                }
            }
            val explicitMatch = STRICT_ONSCREEN_NSFW_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_X_NSFW, explicitMatch.value)
            }
        }

        // 5. Mihon / Tachiyomi / Komikku / Aniyomi (blocks all 489+ Keiyoushi 18+ extensions,
        //    installed 18+ extension labels, 18+ settings toggle, Comick, and X genres/titles)
        if (isMihonPackage(packageName)) {
            val matchedExt = matchKeiyoushiExtensionName(
                textSamples = textSamples,
                blockedNames = blockedKeiyoushiNames,
                isInstaller = false
            )
            if (matchedExt != null) {
                return ScreenBlockMatch(REASON_MIHON_NSFW, "keiyoushi:$matchedExt")
            }
            matchIndicatorsFast(joined, MIHON_PHRASE_INDICATORS, MIHON_WORD_REGEX)?.let { hit ->
                return ScreenBlockMatch(REASON_MIHON_NSFW, hit)
            }
            val explicitMatch = STRICT_ONSCREEN_NSFW_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_MIHON_NSFW, explicitMatch.value)
            }
        }

        // 6. General Browser Page Guard (catches unambiguous adult/hentai/JAV/Comick sites even when
        //    the browser hides the URL bar while scrolling, without false positives on normal pages)
        if (isBrowser) {
            if (isImmuneText(joined)) {
                return null
            }
            val explicitMatch = STRICT_ONSCREEN_NSFW_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_BROWSER_URL, explicitMatch.value)
            }
        }

        // 7. Telegram / Tumblr / Google Search (strict unambiguous adult content only)
        if (packageName in SOCIAL_MONITORED_PACKAGES) {
            if (isImmuneText(joined)) return null
            val explicitMatch = STRICT_ONSCREEN_NSFW_REGEX.find(joined)
            if (explicitMatch != null) {
                return ScreenBlockMatch(REASON_APP_NSFW, explicitMatch.value)
            }
        }

        return null
    }

    fun isImmuneText(lowerText: String): Boolean {
        return lowerText.contains("poseidon-scan.net") ||
            lowerText.contains("poseidon scan") ||
            lowerText.contains("poseidonscan")
    }

    /**
     * Extracts candidate domain names from browser URL bars, toolbars, or WebView text nodes
     * (even when the toolbar contains extra text like "pornhub.com — Connexion sécurisée").
     */
    fun extractDomainsFromText(text: String): List<String> {
        val lower = text.lowercase()
        val matches = DOMAIN_TOKEN_REGEX.findAll(lower)
        val out = ArrayList<String>(4)
        for (m in matches) {
            val host = m.groupValues[1].trimEnd('.')
            val tld = host.substringAfterLast('.')
            if (tld in IGNORED_FILE_EXTENSIONS) continue
            if (host.length in 4..253) {
                out.add(host)
            }
        }
        return out
    }

    /**
     * Matches visible UI text nodes in Mihon or PackageInstaller against the 489+ Keiyoushi 18+
     * extension names (plus Comick and dynamically detected installed 18+ extension APKs).
     */
    fun matchKeiyoushiExtensionName(
        textSamples: List<String>,
        blockedNames: Set<String>,
        isInstaller: Boolean
    ): String? {
        if (blockedNames.isEmpty()) return null
        val joined = textSamples.joinToString("\n").lowercase()
        val hasInstallerContext = !isInstaller ||
            joined.contains("tachiyomi") ||
            joined.contains("mihon") ||
            joined.contains("extension") ||
            joined.contains("installer") ||
            joined.contains("install")

        if (!hasInstallerContext) return null

        for (raw in textSamples) {
            val clean = raw.trim().lowercase()
            if (clean.length < 3 || isImmuneText(clean)) continue

            // Strip "Tachiyomi: " or "Mihon: " prefix used by extension APK labels
            val stripped = clean
                .removePrefix("tachiyomi:")
                .removePrefix("mihon:")
                .removePrefix("keiyoushi:")
                .trim()

            if (stripped in blockedNames) return stripped

            // Also match toolbar/list formats like "HentaiFox (EN)" or "ToonGod · 18+" or "Comick - All"
            val baseName = stripped
                .substringBefore(" (")
                .substringBefore(" ·")
                .substringBefore(" -")
                .substringBefore(" —")
                .trim()
            if (baseName.length >= 3 && baseName in blockedNames) {
                return baseName
            }

            // In PackageInstaller dialogs: "Voulez-vous installer Tachiyomi: HentaiFox ?"
            if (isInstaller && (clean.contains("tachiyomi:") || clean.contains("mihon:"))) {
                val afterColon = clean.substringAfter(':').trim()
                    .trimEnd('?', '!', '.', ' ')
                    .substringBefore(" ?")
                    .trim()
                if (afterColon in blockedNames) return afterColon
            }
        }
        return null
    }

    private val DOMAIN_TOKEN_REGEX = Regex(
        "(?<![a-z0-9-])([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)(?![a-z0-9-])"
    )

    private val IGNORED_FILE_EXTENSIONS = setOf(
        "apk", "png", "jpg", "jpeg", "gif", "webp", "svg", "mp4", "mp3", "mkv",
        "pdf", "zip", "rar", "7z", "txt", "json", "xml", "html", "htm", "css", "js", "php"
    )

    private fun isPhraseIndicator(s: String): Boolean =
        s.contains(' ') || s.contains('/') || s.contains('#') || s.contains('+') || s.contains('.')

    private fun buildWordBoundaryRegex(items: List<String>): Regex? {
        val words = items.filterNot(::isPhraseIndicator)
        if (words.isEmpty()) return null
        return Regex("\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    // Precompiled once at class init (zero runtime Regex allocations on Galaxy A55!)
    private val REDDIT_PHRASE_INDICATORS = REDDIT_NSFW_INDICATORS.filter(::isPhraseIndicator)
    private val REDDIT_WORD_REGEX = buildWordBoundaryRegex(REDDIT_NSFW_INDICATORS)

    private val MIHON_PHRASE_INDICATORS = MIHON_NSFW_INDICATORS.filter(::isPhraseIndicator)
    private val MIHON_WORD_REGEX = buildWordBoundaryRegex(MIHON_NSFW_INDICATORS)

    private val SOCIAL_INDICATORS = listOf(
        "18+", "nsfw", "contenu pour adultes", "mature content", "adult content", "potentially sensitive"
    )
    private val SOCIAL_PHRASE_INDICATORS = SOCIAL_INDICATORS.filter(::isPhraseIndicator)
    private val SOCIAL_WORD_REGEX = buildWordBoundaryRegex(SOCIAL_INDICATORS)

    private fun matchIndicatorsFast(text: String, phrases: List<String>, wordRegex: Regex?): String? {
        for (phrase in phrases) {
            if (text.contains(phrase)) return phrase
        }
        return wordRegex?.find(text)?.value
    }

    private fun containsBoundaryOrPhrase(text: String, needle: String): Boolean {
        if (isPhraseIndicator(needle)) {
            return text.contains(needle)
        }
        return Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(text)
    }
}
