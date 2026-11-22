package com.blocker.app.guard

import com.blocker.app.filter.KeywordFilter
import java.net.URLDecoder

data class ScreenBlockMatch(
    val reason: String,
    val detail: String,
    val isSettingsTamper: Boolean = false
)

/**
 * Pure-Kotlin real-time classifier for browser URL bars, search queries, and in-app
 * accessibility text (Reddit, X/Twitter, Telegram, Discord, Instagram, TikTok, Pinterest,
 * Tumblr, and Android Settings anti-tamper).
 */
object ScreenContentClassifier {

    const val REASON_REDDIT_NSFW = "Reddit NSFW / Limite"
    const val REASON_X_NSFW = "X (Twitter) Sensible / Limite"
    const val REASON_MIHON_NSFW = "Mihon / Extension Keiyoushi 18+"
    const val REASON_BROWSER_URL = "URL / Recherche bloquée"
    const val REASON_APP_NSFW = "Contenu adulte / Limite dans app"
    const val REASON_SETTINGS_TAMPER = "Protection anti-désactivation"

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

    // Explicit & borderline phrases/words blocked in URLs, searches, and monitored apps
    private val EXPLICIT_SEARCH_AND_URL_REGEX = Regex(
        "\\b(" + listOf(
            // English explicit & borderline
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
            // Drawn / Hentai / Anime / Comics / Furry / 3D
            "hentai", "hanime", "nhentai", "doujin", "doujinshi", "rule34", "r34",
            "ecchi", "ahegao", "oppai", "paizuri", "futanari", "yiff", "furryporn",
            "gelbooru", "danbooru", "e621", "lewd", "waifusgonewild", "animeplot",
            "manhwa18", "pornhwa", "comick", "r-18", "r18", "eroge", "f95zone", "iwara",
            "sfmcompile", "kemono", "coomer",
            // Asian / JAV
            "jav", "missav", "jable", "supjav", "javhd", "javguru", "javdb", "javbus",
            "av01", "avgle", "91porn", "koreanbj", "uncensoredjav", "asiansgonewild",
            // AI Porn / Deepfake / Nudify
            "nudify", "undress", "deepnude", "clothoff", "deepfake", "civitai",
            "pornpen", "janitorai", "spicychat", "crushon", "candy\\.ai", "dreamgf",
            // French explicit & borderline
            "sexe", "porno", "baise", "baiser", "salope", "pute", "branlette",
            "sodomie", "fellation", "beurette", "plan\\s*cul", "plancul", "cul\\s*nu",
            "seins\\s*nus", "femme\\s*nue", "fille\\s*nue", "nudité", "érotique",
            "erotique", "jacquie\\s*et\\s*michel", "dorcel", "tukif", "coquine",
            "chaude", "libertin", "libertine", "échangiste", "echangiste"
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    // Reddit native NSFW / Mature / 18+ UI indicators (English & French) + borderline subreddits
    private val REDDIT_NSFW_INDICATORS = listOf(
        "nsfw",
        "18+",
        "over 18",
        "over18",
        "include_over_18",
        "plus de 18 ans",
        "contenu pour adultes",
        "contenu réservé aux adultes",
        "contenu mature",
        "mature content",
        "adult content",
        "confirm you're over 18",
        "you must be 18+",
        "show mature content",
        "afficher le contenu pour adultes",
        "afficher le contenu mature",
        "afficher le contenu nsfw",
        "blur mature",
        "blur nsfw",
        "flouter les images",
        "r/gonewild",
        "r/realgirls",
        "r/nsfw",
        "r/hentai",
        "r/rule34",
        "r/ecchi",
        "r/onlyfans",
        "r/asiansgonewild",
        "r/celebnsfw",
        "r/gentlemanboners",
        "r/cosplaygirls",
        "r/cosplaybutts",
        "r/thighdeology",
        "r/bikini",
        "r/lingerie",
        "r/thick",
        "r/pawg",
        "r/petitegonewild",
        "r/bustypetite",
        "r/legalteens",
        "r/collegesluts",
        "r/breedingmaterial",
        "r/dirtyr4r",
        "r/lewd",
        "r/porn",
        "r/boobs",
        "r/milf",
        "r/bdsm",
        "r/erotica",
        "r/jav",
        "r/nudes",
        "r/fap",
        "r/cum",
        "r/ass"
    )

    // X (Twitter) native sensitive media warnings & settings (English & French)
    private val X_SENSITIVE_INDICATORS = listOf(
        "potentially sensitive content",
        "includes potentially sensitive",
        "may include sensitive content",
        "contenu potentiellement sensible",
        "contenir du contenu sensible",
        "age-restricted adult content",
        "contenu réservé aux adultes",
        "contenu pour adultes",
        "sensitive media",
        "média sensible",
        "médias sensibles",
        "display media that may contain sensitive",
        "afficher les médias pouvant contenir",
        "mark media you post as having material",
        "adult content",
        "#nsfw",
        "#18+",
        "#onlyfans",
        "#fansly",
        "#mym",
        "#hentai",
        "#r34",
        "#rule34",
        "#ecchi",
        "#lewd",
        "#jav",
        "#nude",
        "#nudes",
        "#porn",
        "#porno",
        "#xxx",
        "#sextape",
        "#camgirl",
        "#erotic",
        "#bdsm",
        "#fetish",
        "#horny",
        "#fap",
        "#gooning",
        "#thirsttrap",
        "#uncensored",
        "onlyfans.com",
        "fansly.com",
        "mym.fans",
        "redgifs.com",
        "fapello",
        "coomer.",
        "kemono.",
        "erome.com"
    )

    private val MIHON_NSFW_INDICATORS = listOf(
        "18+",
        "nsfw",
        "hentai",
        "smut",
        "erotica",
        "pornographic",
        "doujinshi",
        "ecchi",
        "pornhwa",
        "pornwha",
        "manhwa18",
        "r-18",
        "r18",
        "adult",
        "adulte",
        "contenu pour adultes",
        "contenu explicite",
        "show in sources and extensions lists",
        "afficher dans les listes de sources",
        "nhentai",
        "e-hentai",
        "hitomi",
        "toonily",
        "comick",
        "allporncomics",
        "ahottie",
        "ahrigallery",
        "akuma.moe",
        "hentaifox",
        "imhentai",
        "asmhentai",
        "pururin",
        "tsumino",
        "multporn",
        "8muses"
    )

    private val SETTINGS_TAMPER_KEYWORDS = listOf(
        "accessibilit",
        "raccourci",
        "shortcut",
        "utiliser blocker",
        "use blocker",
        "désactiver",
        "turn off",
        "arrêter",
        "stop service"
    )

    fun isImmuneUrl(rawUrl: String): Boolean {
        val decoded = rawUrl.trim().lowercase()
        val withoutScheme = decoded.removePrefix("https://").removePrefix("http://")
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return host == "poseidon-scan.net" || host.endsWith(".poseidon-scan.net")
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

        // Extract host candidate if URL contains a dot
        val withoutScheme = decoded.removePrefix("https://").removePrefix("http://")
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.contains('.') && KeywordFilter.matches(host)) {
            return ScreenBlockMatch(REASON_BROWSER_URL, host)
        }

        // Check Reddit web URLs for NSFW/borderline subreddits or Over-18 gates
        if (host.contains("reddit.com") || host.contains("redd.it")) {
            for (indicator in REDDIT_NSFW_INDICATORS) {
                if (decoded.contains(indicator)) {
                    return ScreenBlockMatch(REASON_REDDIT_NSFW, indicator)
                }
            }
        }

        // Check X / Twitter web URLs for sensitive/NSFW searches or tags
        if (host.contains("x.com") || host.contains("twitter.com")) {
            for (indicator in X_SENSITIVE_INDICATORS) {
                if (decoded.contains(indicator)) {
                    return ScreenBlockMatch(REASON_X_NSFW, indicator)
                }
            }
        }

        // Check Pixiv R-18 or Tumblr tagged NSFW
        if (host.contains("pixiv.net") && (decoded.contains("r-18") || decoded.contains("r18"))) {
            return ScreenBlockMatch(REASON_BROWSER_URL, "pixiv-r18")
        }

        // Check full URL path & query string for explicit/borderline terms
        val normalizedPath = decoded.replace('+', ' ').replace('_', ' ').replace('-', ' ').replace('/', ' ')
        val match = EXPLICIT_SEARCH_AND_URL_REGEX.find(normalizedPath)
        if (match != null) {
            return ScreenBlockMatch(REASON_BROWSER_URL, match.value)
        }

        return null
    }

    /**
     * Classifies visible text collected from an app's Accessibility tree.
     */
    fun classifyAppText(packageName: String, textSamples: List<String>): ScreenBlockMatch? {
        if (textSamples.isEmpty()) return null

        // Never self-block Blocker's own UI
        if (packageName == "com.blocker.app") return null

        val joined = textSamples.joinToString("\n").lowercase()

        // 1. Anti-tamper guard in Android Settings: prevent turning off Blocker AccessibilityService
        if (packageName == "com.android.settings" || packageName.contains("settings")) {
            if (joined.contains("blocker") && SETTINGS_TAMPER_KEYWORDS.any { joined.contains(it) }) {
                return ScreenBlockMatch(
                    reason = REASON_SETTINGS_TAMPER,
                    detail = "settings-accessibility",
                    isSettingsTamper = true
                )
            }
            return null
        }

        // 2. Reddit native app (or Reddit web inside browser)
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

        // 3. X (Twitter) native app (or X web inside browser)
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

        // 3b. Mihon / Tachiyomi / Komikku / Aniyomi (blocks 18+ extensions, 18+ settings toggle, and X genres/titles)
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

        // 4. Other monitored social/messaging/search apps (Telegram, Discord, Instagram, TikTok, Pinterest, Tumblr, Google App)
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
        // Multi-word phrases, paths (r/...), hashtags (#...), or "18+" can use substring match safely
        if (needle.contains(' ') || needle.contains('/') || needle.contains('#') || needle.contains('+')) {
            return text.contains(needle)
        }
        return Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(text)
    }
}
