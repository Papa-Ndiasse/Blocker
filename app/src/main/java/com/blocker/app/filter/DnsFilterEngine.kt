package com.blocker.app.filter

sealed class FilterDecision {
    data object Allow : FilterDecision()
    data class Block(val reason: String) : FilterDecision()
    data class SafeSearchRewrite(val targetHost: String) : FilterDecision()
}

/**
 * Central DNS filtering decision engine.
 *
 * Evaluation order:
 *   1. Essential infrastructure (SafeSearch VIPs & blocklist update hosts) -> ALLOW
 *   2. Explicit DoH / DoT / Tor / Proxy bypass domains -> BLOCK
 *   3. Optional total block of high-risk platforms (Reddit / X) when latched -> BLOCK
 *   4. SafeSearch domains (Google, Bing, DuckDuckGo, Brave, Yandex, Pixabay, YouTube) -> REWRITE
 *   5. User's append-only custom blocklist -> BLOCK
 *   6. Massive adult blocklist (seed + 10 verified lists >1.2M domains) -> BLOCK
 *   7. Comprehensive heuristic adult keyword & adult TLD filter -> BLOCK
 *   8. Otherwise -> ALLOW (still forwarded through family-filtering upstream DNS!)
 */
class DnsFilterEngine(
    private val adultBlocklist: DomainMatcher = DomainMatcher(),
    private val customBlocklist: DomainMatcher = DomainMatcher(),
    @Volatile var blockRedditEntirely: Boolean = false,
    @Volatile var blockXEntirely: Boolean = false
) {

    private val immuneMatcher = DomainMatcher(IMMUNE_DOMAINS)
    private val dohBypassMatcher = DomainMatcher(DOH_BYPASS_DOMAINS)
    private val redditMatcher = DomainMatcher(REDDIT_DOMAINS)
    private val xMatcher = DomainMatcher(X_DOMAINS)

    fun evaluate(rawDomain: String): FilterDecision {
        val domain = DomainMatcher.normalize(rawDomain) ?: return FilterDecision.Allow

        if (immuneMatcher.matches(domain) || domain in ESSENTIAL_INFRA_DOMAINS) {
            return FilterDecision.Allow
        }

        if (dohBypassMatcher.matches(domain)) {
            return FilterDecision.Block(REASON_DOH_BYPASS)
        }

        if (blockRedditEntirely && redditMatcher.matches(domain)) {
            return FilterDecision.Block(REASON_SOCIAL_LOCK)
        }

        if (blockXEntirely && xMatcher.matches(domain)) {
            return FilterDecision.Block(REASON_SOCIAL_LOCK)
        }

        SafeSearchRules.targetFor(domain)?.let { target ->
            return FilterDecision.SafeSearchRewrite(target)
        }

        if (customBlocklist.matches(domain)) {
            return FilterDecision.Block(REASON_CUSTOM)
        }

        if (adultBlocklist.matches(domain)) {
            return FilterDecision.Block(REASON_BLOCKLIST)
        }

        if (KeywordFilter.matches(domain)) {
            return FilterDecision.Block(REASON_KEYWORD)
        }

        return FilterDecision.Allow
    }

    fun updateAdultBlocklist(domains: Set<String>) {
        adultBlocklist.replaceAll(domains)
    }

    fun updateAdultBlocklistHashes(sortedUniqueHashes: LongArray) {
        adultBlocklist.replaceAllHashes(sortedUniqueHashes)
    }

    fun updateCustomBlocklist(domains: Set<String>) {
        customBlocklist.replaceAll(domains)
    }

    fun addCustomDomain(domain: String) {
        customBlocklist.addDomain(domain)
    }

    val adultDomainCount: Int
        get() = adultBlocklist.size

    companion object {
        const val REASON_BLOCKLIST = "Blocklist NSFW"
        const val REASON_DOH_BYPASS = "Contournement DoH/Tor/Proxy"
        const val REASON_KEYWORD = "Filtre mots-clés"
        const val REASON_CUSTOM = "Liste personnalisée"
        const val REASON_SOCIAL_LOCK = "Plateforme à risque verrouillée"

        /**
         * Domains with total immunity (exact domain + all subdomains) across DNS,
         * blocklists, keywords, Keiyoushi sync, and screen guards.
         */
        val IMMUNE_DOMAINS = setOf(
            "poseidon-scan.net",
            "discord.com",
            "discord.gg",
            "discordapp.com",
            "discordapp.net",
            "discord.media",
            "discordcdn.com"
        )

        private val ESSENTIAL_INFRA_DOMAINS = setOf(
            SafeSearchRules.GOOGLE_SAFE_HOST,
            SafeSearchRules.BING_SAFE_HOST,
            SafeSearchRules.DUCKDUCKGO_SAFE_HOST,
            SafeSearchRules.BRAVE_SAFE_HOST,
            SafeSearchRules.YANDEX_SAFE_HOST,
            SafeSearchRules.PIXABAY_SAFE_HOST,
            "raw.githubusercontent.com",
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "nsfw.oisd.nl",
            "blocklistproject.github.io"
        )

        val REDDIT_DOMAINS = setOf(
            "reddit.com",
            "redd.it",
            "redditstatic.com",
            "redditmedia.com",
            "redditinc.com",
            "redditspace.com"
        )

        val X_DOMAINS = setOf(
            "x.com",
            "twitter.com",
            "twimg.com",
            "t.co",
            "twttr.com"
        )

        val DOH_BYPASS_DOMAINS = setOf(
            // Firefox & Apple canary domains
            "use-application-dns.net",
            "canary.use-application-dns.net",
            "mask.icloud.com",
            "mask-h2.icloud.com",
            // Unfiltered public DoH/DoT endpoints
            "dns.google",
            "dns.google.com",
            "dns64.dns.google",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "chrome.cloudflare-dns.com",
            "one.one.one.one",
            "1dot1dot1dot1.cloudflare-dns.com",
            "odoh.cloudflare-dns.com",
            "dns.quad9.net",
            "dns9.quad9.net",
            "dns10.quad9.net",
            "dns11.quad9.net",
            "doh.opendns.com",
            "dns.opendns.com",
            "dns.adguard.com",
            "dns.adguard-dns.com",
            "dns-unfiltered.adguard.com",
            "unfiltered.adguard-dns.com",
            "dns.nextdns.io",
            "freedns.controld.com",
            "p0.freedns.controld.com",
            "doh.mullvad.net",
            "dns.mullvad.net",
            "doh.cleanbrowsing.org",
            "doh.dns.sb",
            "dns.sb",
            "doh.libredns.gr",
            "dns.switch.ch",
            "doh.pub",
            "dns.alidns.com",
            "dns.twnic.tw",
            "doh.applied-privacy.net",
            "doh.ffmuc.net",
            "dns.digitale-gesellschaft.ch",
            // Tor download, bridge, Snowflake, and Onion browser domains
            "torproject.org",
            "torproject.net",
            "onionbrowser.com",
            "orbot.app",
            "guardianproject.info",
            "invizible.net",
            "tor.eff.org"
        )
    }
}
