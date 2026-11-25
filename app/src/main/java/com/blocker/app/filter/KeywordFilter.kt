package com.blocker.app.filter

/**
 * Comprehensive heuristic domain keyword & adult-TLD filter catching brand-new,
 * mirror, or obscure adult domains across ALL categories:
 *   - Real human adult / French / tubes / cams / escorts
 *   - Drawn / Hentai / Doujin / Rule34 / Boorus / Manhwa / Porhwa / Comics / Furry / 3D
 *   - Asian / JAV / AV / Chinese / Korean / Indian adult
 *   - AI Porn / Deepfakes / Nudify / Undress / NSFW AI Chatbots / OF-Fansly leaks
 *
 * Includes a strict allowlist to avoid false positives on legitimate domains.
 */
object KeywordFilter {

    private val HIGH_CONFIDENCE_TOKENS = listOf(
        // Real human / general tubes & explicit terms
        "porn", "porno", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
        "spankbang", "eporner", "chaturbate", "stripchat", "bongacams", "livejasmin",
        "myfreecams", "camsoda", "brazzers", "bangbros", "naughtyamerica", "realitykings",
        "motherless", "youjizz", "jizzbunker", "tnaflix", "drtuber", "upornia",
        "hqporner", "daftsex", "sxyprn", "thumbzilla", "extremetube", "keezmovies",
        "sunporno", "porntrex", "pornone", "nudevista", "nudogram", "literotica",
        "milf", "bdsm", "camgirl", "camgirls", "camwhore", "livecams", "sexcam",
        "sexcams", "sexchat", "sexvid", "sexvideo", "sexvideos", "sextube", "sextape",
        "fucktube", "fapvid", "fapzone", "faphouse", "fapreactor", "erotic", "erotica",
        "nsfw", "adultvideo", "adultvideos", "adultcams", "escortgirl", "escortgirls",
        "blowjob", "handjob", "creampie", "bukkake", "gangbang", "cumshot", "cumslut",
        "masturbat", "pussy", "boobs", "redgifs", "scrolller", "x-art",
        // French adult
        "jacquieetmichel", "dorcel", "tukif", "beurette", "plan-cul", "plancul",
        "sexemodel", "salope", "baise",
        // Drawn / Hentai / Doujin / Booru / Rule34 / Manhwa / Comics / Furry / 3D
        "hentai", "hanime", "nhentai", "e-hentai", "exhentai", "doujin", "doujinshi",
        "rule34", "r34", "gelbooru", "danbooru", "booru", "ecchi", "ahegao",
        "futanari", "paizuri", "oppai", "yiff", "furryporn", "e621", "furaffinity",
        "manhwa18", "pornhwa", "pornwha", "toonily", "comick", "multporn", "porncomix",
        "allporncomic", "milftoon", "jabcomix", "f95zone", "nutaku", "eroge",
        "erogames", "lewd", "sfmcompile", "iwara", "sankakucomplex", "hentaifoundry",
        "imhentai", "asmhentai", "pururin", "tsumino", "fakku", "wnacg", "18comic",
        "jmcomic",
        // Asian / JAV / AV / Chinese / Korean / Indian
        "javhd", "javguru", "javmost", "javdb", "javbus", "javlibrary", "javfree",
        "javgg", "javct", "javtiful", "javbangers", "javmix", "javdoe", "javsub",
        "javseen", "javplay", "missav", "jable", "supjav", "njav", "123av", "7mmtv",
        "av01", "avjb", "avgle", "thisav", "hpjav", "netflav", "tktube", "91porn",
        "sehuatang", "koreanbj", "krbj", "desipapa", "desiporn", "masahub",
        "caribbeancom", "1pondo", "tokyo-hot", "heyzo", "fc2ppv", "uncensoredjav",
        "indianporn",
        // AI Porn / Deepfake / Nudify / Undress / Leaks / OF-Fansly
        "nudify", "undress", "deepnude", "clothoff", "deepfake", "mrdeepfakes",
        "pornpen", "porngen", "pornworks", "pornjoy", "createporn", "pornjourney",
        "promptchan", "spicychat", "crushon", "janitorai", "dreamgf", "lustgf",
        "candyai", "civitai", "onlyfans", "fansly", "mymfans", "fapello", "simpcity",
        "coomer", "kemono", "erome", "bunkr", "thothub", "leakedzone", "nudostar",
        "faponic", "dirtyship", "celebjihad", "fappening", "aznude", "ancensored",
        "erothots", "leakgirls", "leakedmodels", "onlyleak", "nudes", "nude"
    )

    private val ADULT_TLDS = setOf(
        "xxx",
        "porn",
        "sex",
        "adult",
        "cam",
        "sexy"
    )

    private val SAFE_ALLOWLIST = setOf(
        "cleanbrowsing.org",
        "adguard-dns.com",
        "cloudflare-dns.com",
        "oisd.nl",
        "jsdelivr.net",
        "githubusercontent.com",
        "github.com",
        "blocklistproject.github.io",
        "wikipedia.org",
        "wikimedia.org",
        "wiktionary.org",
        "dictionary.com",
        "merriam-webster.com",
        "larousse.fr",
        "cnrtl.fr",
        "who.int",
        "nih.gov",
        "cdc.gov",
        "ameli.fr",
        "service-public.fr",
        "gouv.fr",
        "essex.ac.uk",
        "sussex.ac.uk",
        "middlesex.edu",
        "google.com",
        "google.fr",
        "googleapis.com",
        "gstatic.com",
        "android.com",
        "microsoft.com",
        "apple.com",
        "amazon.com",
        "amazon.fr",
        "poseidon-scan.net",
        "discord.com",
        "discord.gg",
        "discordapp.com",
        "discordapp.net",
        "discord.media",
        "discordcdn.com"
    )

    // Single precompiled automaton replaces 190 sequential String.contains() calls per label
    private val HIGH_CONFIDENCE_TOKEN_REGEX: Regex = Regex(
        HIGH_CONFIDENCE_TOKENS.joinToString("|") { Regex.escape(it) }
    )

    private val BOUNDARY_LABEL_REGEX: Regex = Regex(
        "(^|[.-])(xxx|sex|sexe)($|[.-])"
    )

    fun matches(rawDomain: String): Boolean {
        val domain = rawDomain.trim().lowercase().trimEnd('.')
        val lastDot = domain.lastIndexOf('.')
        if (lastDot <= 0 || lastDot >= domain.length - 1) return false

        if (isAllowlisted(domain)) return false

        val tld = domain.substring(lastDot + 1)
        if (tld in ADULT_TLDS) return true

        val body = domain.substring(0, lastDot)
        if (BOUNDARY_LABEL_REGEX.containsMatchIn(body)) return true
        return HIGH_CONFIDENCE_TOKEN_REGEX.containsMatchIn(body)
    }

    private fun isAllowlisted(domain: String): Boolean {
        if (domain in SAFE_ALLOWLIST) return true
        var dot = domain.indexOf('.')
        while (dot != -1 && dot < domain.length - 1) {
            if (domain.substring(dot + 1) in SAFE_ALLOWLIST) return true
            dot = domain.indexOf('.', dot + 1)
        }
        return false
    }
}
