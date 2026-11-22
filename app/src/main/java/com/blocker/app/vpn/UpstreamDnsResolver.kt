package com.blocker.app.vpn

import android.net.VpnService
import android.util.Log
import com.blocker.app.filter.SafeSearchRules
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Forwards allowed DNS queries through VpnService-protected UDP sockets to
 * adult-filtering upstream resolvers (Cloudflare for Families, CleanBrowsing Adult, AdGuard Family).
 *
 * Includes fast failover, response caching, and dynamic SafeSearch VIP resolution with
 * static fallback IPs so SafeSearch never breaks even on flaky networks.
 */
class UpstreamDnsResolver(
    private val vpnService: VpnService
) {
    private data class CachedBytes(val bytes: ByteArray, val expiresAtMs: Long)
    private data class CachedIps(val addresses: List<ByteArray>, val expiresAtMs: Long)

    private val responseCache = ConcurrentHashMap<String, CachedBytes>()
    private val safeSearchVipCache = ConcurrentHashMap<String, CachedIps>()

    private val upstreamAddresses: List<InetAddress> =
        BypassIpCatalog.FAMILY_UPSTREAM_DNS_IPV4.map { InetAddress.getByName(it) }

    /**
     * Forwards a raw DNS query to family-filtering upstream DNS servers.
     */
    fun forward(queryBytes: ByteArray, query: DnsQuery): ByteArray? {
        val cacheKey = "${query.domain}:${query.qType}:${query.qClass}"
        val now = System.currentTimeMillis()
        responseCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return DnsCodec.patchTransactionId(cached.bytes, query.transactionId)
            } else {
                responseCache.remove(cacheKey)
            }
        }

        val response = queryUpstreams(queryBytes) ?: return null

        // Evict periodically if cache grows large
        if (responseCache.size > MAX_CACHE_ENTRIES) {
            responseCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (responseCache.size > MAX_CACHE_ENTRIES) {
                responseCache.clear()
            }
        }
        responseCache[cacheKey] = CachedBytes(response, now + CACHE_TTL_MS)
        return response
    }

    /**
     * Resolves a SafeSearch VIP hostname (e.g. forcesafesearch.google.com) for A or AAAA.
     */
    fun resolveSafeSearchVip(targetHost: String, qType: Int): List<ByteArray> {
        if (qType != DnsCodec.TYPE_A && qType != DnsCodec.TYPE_AAAA) return emptyList()

        val key = "$targetHost:$qType"
        val now = System.currentTimeMillis()
        safeSearchVipCache[key]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.addresses
        }

        val wireQuery = DnsCodec.buildQuery(targetHost, qType)
        val wireResponse = queryUpstreams(wireQuery)
        val resolved = if (wireResponse != null) {
            DnsCodec.extractAddressRecords(wireResponse, qType)
        } else {
            emptyList()
        }

        val finalAddresses = resolved.ifEmpty { fallbackVipFor(targetHost, qType) }
        if (finalAddresses.isNotEmpty()) {
            safeSearchVipCache[key] = CachedIps(finalAddresses, now + SAFESEARCH_CACHE_TTL_MS)
        }
        return finalAddresses
    }

    private fun queryUpstreams(queryBytes: ByteArray): ByteArray? {
        for (upstream in upstreamAddresses) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!vpnService.protect(socket)) {
                    Log.w(TAG, "VpnService.protect failed for socket")
                    continue
                }
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val sendPacket = DatagramPacket(queryBytes, queryBytes.size, upstream, DNS_PORT)
                socket.send(sendPacket)

                val recvBuf = ByteArray(MAX_DNS_PACKET_SIZE)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                return recvBuf.copyOf(recvPacket.length)
            } catch (_: Exception) {
                // Fail over quickly to the next family-filtering upstream resolver
            } finally {
                socket?.close()
            }
        }
        return null
    }

    private fun fallbackVipFor(targetHost: String, qType: Int): List<ByteArray> {
        if (qType != DnsCodec.TYPE_A) return emptyList()
        val ip = when (targetHost) {
            SafeSearchRules.GOOGLE_SAFE_HOST -> "216.239.38.120"
            SafeSearchRules.BING_SAFE_HOST -> "204.79.197.220"
            else -> return emptyList()
        }
        return try {
            listOf(InetAddress.getByName(ip).address)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "UpstreamDnsResolver"
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 1800
        private const val MAX_DNS_PACKET_SIZE = 4096
        private const val CACHE_TTL_MS = 60_000L
        private const val SAFESEARCH_CACHE_TTL_MS = 300_000L
        private const val MAX_CACHE_ENTRIES = 512
    }
}
