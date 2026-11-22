package com.blocker.app

import com.blocker.app.data.BlockerPreferences
import com.blocker.app.filter.BlocklistRepository
import com.blocker.app.filter.DnsFilterEngine
import com.blocker.app.filter.DomainMatcher
import com.blocker.app.filter.FilterDecision
import com.blocker.app.filter.KeywordFilter
import com.blocker.app.filter.SafeSearchRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsFilterEngineTest {

    private fun createEngine(): DnsFilterEngine {
        return DnsFilterEngine(
            adultBlocklist = DomainMatcher(setOf("pornhub.com", "xvideos.com", "chaturbate.com")),
            customBlocklist = DomainMatcher(setOf("distraction-site.net"))
        )
    }

    @Test
    fun `blocks exact and subdomain matches from adult blocklist`() {
        val engine = createEngine()
        assertTrue(engine.evaluate("pornhub.com") is FilterDecision.Block)
        assertTrue(engine.evaluate("fr.pornhub.com") is FilterDecision.Block)
        assertTrue(engine.evaluate("cdn.media.xvideos.com.") is FilterDecision.Block)
        assertEquals(FilterDecision.Allow, engine.evaluate("github.com"))
    }

    @Test
    fun `blocks DoH and Private Relay bypass endpoints`() {
        val engine = createEngine()
        val dohDomains = listOf(
            "dns.google",
            "use-application-dns.net",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "dns.nextdns.io",
            "doh.mullvad.net"
        )
        for (domain in dohDomains) {
            val decision = engine.evaluate(domain)
            assertTrue("Expected $domain to be blocked", decision is FilterDecision.Block)
            assertEquals(DnsFilterEngine.REASON_DOH_BYPASS, (decision as FilterDecision.Block).reason)
        }
    }

    @Test
    fun `rewrites search engines to SafeSearch VIPs without breaking non-search subdomains`() {
        val engine = createEngine()

        assertEquals(
            FilterDecision.SafeSearchRewrite(SafeSearchRules.GOOGLE_SAFE_HOST),
            engine.evaluate("www.google.com")
        )
        assertEquals(
            FilterDecision.SafeSearchRewrite(SafeSearchRules.GOOGLE_SAFE_HOST),
            engine.evaluate("www.google.fr")
        )
        assertEquals(
            FilterDecision.SafeSearchRewrite(SafeSearchRules.BING_SAFE_HOST),
            engine.evaluate("www.bing.com")
        )
        assertEquals(
            FilterDecision.SafeSearchRewrite(SafeSearchRules.DUCKDUCKGO_SAFE_HOST),
            engine.evaluate("duckduckgo.com")
        )
        assertEquals(
            FilterDecision.SafeSearchRewrite(SafeSearchRules.BRAVE_SAFE_HOST),
            engine.evaluate("search.brave.com")
        )

        // Non-search Google services must stay untouched
        assertEquals(FilterDecision.Allow, engine.evaluate("mail.google.com"))
        assertEquals(FilterDecision.Allow, engine.evaluate("accounts.google.com"))
        assertEquals(FilterDecision.Allow, engine.evaluate("docs.google.com"))
        assertEquals(FilterDecision.Allow, engine.evaluate("play.google.com"))

        // SafeSearch VIP target itself must never loop or get blocked
        assertEquals(FilterDecision.Allow, engine.evaluate(SafeSearchRules.GOOGLE_SAFE_HOST))
    }

    @Test
    fun `youtube is never rewritten to restricted mode`() {
        val engine = createEngine()
        assertEquals(FilterDecision.Allow, engine.evaluate("www.youtube.com"))
        assertEquals(FilterDecision.Allow, engine.evaluate("m.youtube.com"))
    }

    @Test
    fun `blocks append-only custom domains and their subdomains`() {
        val engine = createEngine()
        val d = engine.evaluate("sub.distraction-site.net")
        assertTrue(d is FilterDecision.Block)
        assertEquals(DnsFilterEngine.REASON_CUSTOM, (d as FilterDecision.Block).reason)

        engine.addCustomDomain("newly-added.org")
        assertTrue(engine.evaluate("www.newly-added.org") is FilterDecision.Block)
    }

    @Test
    fun `keyword filter catches real, drawn, Asian JAV, and AI adult domains without false positives`() {
        assertTrue(KeywordFilter.matches("brandnew-porn-mirror.net"))
        assertTrue(KeywordFilter.matches("randomsite.xxx"))
        assertTrue(KeywordFilter.matches("something.adult"))
        assertTrue(KeywordFilter.matches("free-hentai-stream.io"))
        assertTrue(KeywordFilter.matches("new-rule34-booru.net"))
        assertTrue(KeywordFilter.matches("missav-mirror.org"))
        assertTrue(KeywordFilter.matches("javhd-free.net"))
        assertTrue(KeywordFilter.matches("nudify-online.ai"))
        assertTrue(KeywordFilter.matches("fapello-leaks.co"))

        assertFalse(KeywordFilter.matches("en.wikipedia.org"))
        assertFalse(KeywordFilter.matches("essex.ac.uk"))
        assertFalse(KeywordFilter.matches("github.com"))
        assertFalse(KeywordFilter.matches("www.ameli.fr"))
        assertFalse(KeywordFilter.matches("raw.githubusercontent.com"))
    }

    @Test
    fun `optional total locks block Reddit and X domains completely`() {
        val engine = createEngine()
        assertEquals(FilterDecision.Allow, engine.evaluate("www.reddit.com"))
        assertEquals(FilterDecision.Allow, engine.evaluate("x.com"))

        engine.blockRedditEntirely = true
        engine.blockXEntirely = true

        assertTrue(engine.evaluate("www.reddit.com") is FilterDecision.Block)
        assertTrue(engine.evaluate("i.redd.it") is FilterDecision.Block)
        assertTrue(engine.evaluate("x.com") is FilterDecision.Block)
        assertTrue(engine.evaluate("pbs.twimg.com") is FilterDecision.Block)
    }

    @Test
    fun `poseidon-scan_net has total immunity across DNS, blocklists, custom list, and keywords`() {
        val engine = DnsFilterEngine(
            adultBlocklist = DomainMatcher(setOf("poseidon-scan.net")),
            customBlocklist = DomainMatcher(setOf("poseidon-scan.net"))
        )
        assertEquals(FilterDecision.Allow, engine.evaluate("poseidon-scan.net"))
        assertEquals(FilterDecision.Allow, engine.evaluate("www.poseidon-scan.net"))
        assertEquals(FilterDecision.Allow, engine.evaluate("cdn.poseidon-scan.net"))
        assertFalse(KeywordFilter.matches("poseidon-scan.net"))
    }

    @Test
    fun `blocklist parser handles domains wildcards hosts and adblock syntax`() {
        assertEquals("example.com", BlocklistRepository.parseBlocklistLine("example.com"))
        assertEquals("example.com", BlocklistRepository.parseBlocklistLine("*.example.com"))
        assertEquals("example.com", BlocklistRepository.parseBlocklistLine("0.0.0.0 example.com # comment"))
        assertEquals("example.com", BlocklistRepository.parseBlocklistLine("||example.com^"))
        assertNull(BlocklistRepository.parseBlocklistLine("# comment"))
        assertNull(BlocklistRepository.parseBlocklistLine("0.0.0.0 localhost"))
    }

    @Test
    fun `domain normalization and masking work cleanly`() {
        assertEquals("example.com", BlockerPreferences.normalizeDomainInput("https://www.Example.COM/page?a=1"))
        assertNull(BlockerPreferences.normalizeDomainInput("not a domain"))
        assertEquals("p***b.com", BlockerPreferences.maskDomain("pornhub.com"))
    }
}
