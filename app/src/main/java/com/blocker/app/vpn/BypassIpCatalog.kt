package com.blocker.app.vpn

import java.net.InetAddress

/**
 * Virtual VPN addresses, family-filtering upstream DNS servers, and
 * public unfiltered DNS/DoH/DoT resolver IPs that are routed into the TUN
 * interface and rejected to prevent DoH/DoT bypass.
 */
object BypassIpCatalog {

    const val VPN_IPV4_ADDRESS = "10.200.0.1"
    const val VPN_IPV4_DNS = "10.200.0.2"

    const val VPN_IPV6_ADDRESS = "fd00:200::1"
    const val VPN_IPV6_DNS = "fd00:200::2"

    val VPN_IPV4_DNS_BYTES: ByteArray = InetAddress.getByName(VPN_IPV4_DNS).address
    val VPN_IPV6_DNS_BYTES: ByteArray = InetAddress.getByName(VPN_IPV6_DNS).address

    /**
     * Adult-filtering upstream DNS servers (second layer of defense on top of local blocklists).
     * All chosen resolvers block adult domains WITHOUT forcing YouTube Restricted Mode.
     */
    val FAMILY_UPSTREAM_DNS_IPV4 = listOf(
        "1.1.1.3",          // Cloudflare for Families (Malware + Adult, no YouTube Restricted Mode)
        "185.228.168.10",   // CleanBrowsing Adult Filter (Adult + SafeSearch, no YouTube Restricted Mode)
        "208.67.222.123",   // OpenDNS FamilyShield (Adult, no YouTube Restricted Mode)
        "1.0.0.3",          // Cloudflare for Families Secondary
        "185.228.169.11"    // CleanBrowsing Adult Filter Secondary
    )

    /**
     * Public unfiltered DNS / DoH / DoT IPv4 addresses routed into TUN and rejected.
     * Prevents apps or browsers from hardcoding 8.8.8.8 / 1.1.1.1 over UDP/53, TCP/853, or HTTPS/443.
     */
    val BLOCKED_RESOLVER_IPV4 = listOf(
        "8.8.8.8",           // Google Public DNS / DoH
        "8.8.4.4",           // Google Public DNS / DoH
        "1.1.1.1",           // Cloudflare Unfiltered / DoH
        "1.0.0.1",           // Cloudflare Unfiltered / DoH
        "104.16.248.249",    // Mozilla Cloudflare DoH
        "104.16.249.249",    // Mozilla Cloudflare DoH
        "9.9.9.9",           // Quad9 Unfiltered
        "149.112.112.112",   // Quad9 Unfiltered
        "208.67.222.222",    // OpenDNS Unfiltered
        "208.67.220.220",    // OpenDNS Unfiltered
        "94.140.14.14",      // AdGuard Default (non-family)
        "94.140.15.15",      // AdGuard Default (non-family)
        "94.140.14.140",     // AdGuard Unfiltered
        "94.140.14.141",     // AdGuard Unfiltered
        "45.90.28.0",        // NextDNS Anycast
        "45.90.30.0",        // NextDNS Anycast
        "76.76.2.0",         // ControlD Unfiltered
        "76.76.10.0",        // ControlD Unfiltered
        "194.242.2.2",       // Mullvad DoH/DoT
        "194.242.2.3",       // Mullvad DoH/DoT
        "185.222.222.222",   // DNS.SB
        "45.11.45.11"        // DNS.SB
    )

    /**
     * Public unfiltered DNS / DoH / DoT IPv6 addresses routed into TUN and rejected.
     */
    val BLOCKED_RESOLVER_IPV6 = listOf(
        "2001:4860:4860::8888",
        "2001:4860:4860::8844",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2606:4700::6810:f8f9",
        "2606:4700::6810:f9f9",
        "2620:fe::fe",
        "2620:fe::9",
        "2620:119:35::35",
        "2620:119:53::53",
        "2a10:50c0::ad1:ff",
        "2a10:50c0::ad2:ff",
        "2a10:50c0::1:ff",
        "2a10:50c0::2:ff",
        "2a07:a8c0::",
        "2a07:a8c1::"
    )
}
